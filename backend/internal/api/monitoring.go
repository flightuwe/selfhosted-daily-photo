package api

import (
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

type Monitor struct {
	mu sync.Mutex

	StartedAt        time.Time
	TotalRequests    int64
	TotalErrors      int64
	Total4xx         int64
	Total5xx         int64
	RecentRequests   []RequestMetric
	RecentMaxSamples int

	PushSent          int64
	PushFailed        int64
	PushInvalidTokens int64
	PushErrors        int64
}

type RequestMetric struct {
	At        time.Time `json:"at"`
	Method    string    `json:"method"`
	Path      string    `json:"path"`
	Status    int       `json:"status"`
	LatencyMs float64   `json:"latencyMs"`
	RequestID string    `json:"requestId"`
}

type ComponentStatus struct {
	Name    string `json:"name"`
	OK      bool   `json:"ok"`
	Message string `json:"message"`
}

func NewMonitor() *Monitor {
	return &Monitor{
		StartedAt:        time.Now(),
		RecentMaxSamples: 300,
		RecentRequests:   make([]RequestMetric, 0, 300),
	}
}

func (m *Monitor) RecordRequest(metric RequestMetric) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.TotalRequests++
	if metric.Status >= 400 && metric.Status < 500 {
		m.Total4xx++
		m.TotalErrors++
	}
	if metric.Status >= 500 {
		m.Total5xx++
		m.TotalErrors++
	}
	m.RecentRequests = append(m.RecentRequests, metric)
	if len(m.RecentRequests) > m.RecentMaxSamples {
		m.RecentRequests = m.RecentRequests[len(m.RecentRequests)-m.RecentMaxSamples:]
	}
}

func (m *Monitor) RecordPush(sent, failed, invalid int, hadError bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.PushSent += int64(sent)
	m.PushFailed += int64(failed)
	m.PushInvalidTokens += int64(invalid)
	if hadError {
		m.PushErrors++
	}
}

func (m *Monitor) Snapshot() gin.H {
	m.mu.Lock()
	defer m.mu.Unlock()

	recent := make([]RequestMetric, len(m.RecentRequests))
	copy(recent, m.RecentRequests)

	var p95 float64
	if len(recent) > 0 {
		lat := make([]float64, 0, len(recent))
		for _, r := range recent {
			lat = append(lat, r.LatencyMs)
		}
		p95 = percentile(lat, 95)
	}

	errRate := 0.0
	if m.TotalRequests > 0 {
		errRate = (float64(m.TotalErrors) / float64(m.TotalRequests)) * 100
	}

	return gin.H{
		"startedAt":         m.StartedAt,
		"uptimeSec":         int64(time.Since(m.StartedAt).Seconds()),
		"requestsTotal":     m.TotalRequests,
		"errorsTotal":       m.TotalErrors,
		"errors4xx":         m.Total4xx,
		"errors5xx":         m.Total5xx,
		"errorRatePercent":  roundFloat(errRate, 3),
		"p95LatencyMs":      roundFloat(p95, 3),
		"recentRequestsCnt": len(recent),
		"push": gin.H{
			"sent":          m.PushSent,
			"failed":        m.PushFailed,
			"invalidTokens": m.PushInvalidTokens,
			"errors":        m.PushErrors,
		},
	}
}

func (s *Server) requestIDMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		reqID := strings.TrimSpace(c.GetHeader("X-Request-ID"))
		if reqID == "" {
			reqID = fmt.Sprintf("req-%d", time.Now().UnixNano())
		}
		c.Set("requestId", reqID)
		c.Writer.Header().Set("X-Request-ID", reqID)
		c.Next()
	}
}

func (s *Server) metricsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()

		if s.Monitor == nil {
			return
		}

		path := c.FullPath()
		if path == "" {
			path = c.Request.URL.Path
		}
		metric := RequestMetric{
			At:        time.Now(),
			Method:    c.Request.Method,
			Path:      path,
			Status:    c.Writer.Status(),
			LatencyMs: float64(time.Since(start).Microseconds()) / 1000.0,
			RequestID: c.Writer.Header().Get("X-Request-ID"),
		}
		s.Monitor.RecordRequest(metric)
	}
}

func (s *Server) handleLiveHealth(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"ok":       true,
		"status":   "live",
		"version":  s.Config.AppVersion,
		"provider": s.Notifier.Name(),
		"time":     time.Now().In(s.Location),
	})
}

func (s *Server) handleReadyHealth(c *gin.Context) {
	components := s.collectComponentStatus()
	allOK := true
	for _, cp := range components {
		if !cp.OK {
			allOK = false
			break
		}
	}
	code := http.StatusOK
	if !allOK {
		code = http.StatusServiceUnavailable
	}
	c.JSON(code, gin.H{
		"ok":         allOK,
		"status":     "ready",
		"version":    s.Config.AppVersion,
		"provider":   s.Notifier.Name(),
		"components": components,
		"time":       time.Now().In(s.Location),
	})
}

func (s *Server) handleMetrics(c *gin.Context) {
	snapshot := gin.H{}
	if s.Monitor != nil {
		snapshot = s.Monitor.Snapshot()
	}
	c.JSON(http.StatusOK, gin.H{
		"ok":      true,
		"metrics": snapshot,
	})
}

func (s *Server) handleAdminSystemHealth(c *gin.Context) {
	components := s.collectComponentStatus()
	allOK := true
	for _, cp := range components {
		if !cp.OK {
			allOK = false
			break
		}
	}

	var latestPrompt struct {
		Day          string     `json:"day"`
		TriggeredAt  *time.Time `json:"triggeredAt"`
		UploadUntil  *time.Time `json:"uploadUntil"`
		TriggerSource string    `json:"triggerSource"`
		RequestedBy  string     `json:"requestedByUser"`
	}
	_ = s.DB.Table("daily_prompts").Select("day, triggered_at, upload_until, trigger_source, requested_by").Order("day desc").Limit(1).Scan(&latestPrompt).Error

	var dataSizeBytes int64
	_ = filepath.Walk(s.Config.UploadDir, func(_ string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		dataSizeBytes += info.Size()
		return nil
	})

	resp := gin.H{
		"ok":              allOK,
		"version":         s.Config.AppVersion,
		"provider":        s.Notifier.Name(),
		"time":            time.Now().In(s.Location),
		"components":      components,
		"latestPrompt":    latestPrompt,
		"uploadSizeBytes": dataSizeBytes,
	}
	if s.Monitor != nil {
		resp["metrics"] = s.Monitor.Snapshot()
	}
	c.JSON(http.StatusOK, resp)
}

func (s *Server) collectComponentStatus() []ComponentStatus {
	out := make([]ComponentStatus, 0, 4)

	out = append(out, s.checkDB())
	out = append(out, s.checkUploadDir())
	out = append(out, s.checkScheduler())
	out = append(out, s.checkFCM())

	return out
}

func (s *Server) checkDB() ComponentStatus {
	type one struct{ N int }
	var row one
	if err := s.DB.Raw("SELECT 1 as n").Scan(&row).Error; err != nil || row.N != 1 {
		return ComponentStatus{Name: "database", OK: false, Message: "db query failed"}
	}
	return ComponentStatus{Name: "database", OK: true, Message: "ok"}
}

func (s *Server) checkUploadDir() ComponentStatus {
	testDir := s.Config.UploadDir
	if err := os.MkdirAll(testDir, 0o755); err != nil {
		return ComponentStatus{Name: "storage", OK: false, Message: "mkdir failed"}
	}
	testFile := filepath.Join(testDir, ".healthcheck")
	payload := []byte(time.Now().Format(time.RFC3339Nano))
	if err := os.WriteFile(testFile, payload, 0o644); err != nil {
		return ComponentStatus{Name: "storage", OK: false, Message: "write failed"}
	}
	_ = os.Remove(testFile)
	return ComponentStatus{Name: "storage", OK: true, Message: "ok"}
}

func (s *Server) checkScheduler() ComponentStatus {
	if !s.Config.SchedulerEnabled {
		return ComponentStatus{Name: "scheduler", OK: true, Message: "disabled by config"}
	}
	var latest struct {
		Day         string
		TriggeredAt *time.Time
	}
	_ = s.DB.Table("daily_prompts").Select("day, triggered_at").Order("day desc").Limit(1).Scan(&latest).Error
	if latest.Day == "" {
		return ComponentStatus{Name: "scheduler", OK: true, Message: "no prompt yet"}
	}
	return ComponentStatus{Name: "scheduler", OK: true, Message: "last day " + latest.Day}
}

func (s *Server) checkFCM() ComponentStatus {
	if !s.Config.FCMEnabled {
		return ComponentStatus{Name: "fcm", OK: true, Message: "disabled"}
	}
	if s.Notifier.Name() != "fcm" {
		return ComponentStatus{Name: "fcm", OK: false, Message: "configured but provider fallback active"}
	}
	return ComponentStatus{Name: "fcm", OK: true, Message: "ready"}
}

func roundFloat(v float64, prec int) float64 {
	p := math.Pow(10, float64(prec))
	return math.Round(v*p) / p
}

func percentile(values []float64, p int) float64 {
	if len(values) == 0 {
		return 0
	}
	clone := append([]float64(nil), values...)
	for i := 0; i < len(clone); i++ {
		for j := i + 1; j < len(clone); j++ {
			if clone[j] < clone[i] {
				clone[i], clone[j] = clone[j], clone[i]
			}
		}
	}
	idx := int(math.Ceil((float64(p)/100.0)*float64(len(clone)))) - 1
	if idx < 0 {
		idx = 0
	}
	if idx >= len(clone) {
		idx = len(clone) - 1
	}
	return clone[idx]
}

func (s ComponentStatus) MarshalJSON() ([]byte, error) {
	type alias ComponentStatus
	return json.Marshal(alias(s))
}
