package api

import (
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type Monitor struct {
	mu sync.Mutex

	DB       *gorm.DB
	Location *time.Location

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
	ThrottleTotal     int64
	ThrottleByReason  map[string]int64

	minuteBuckets map[minuteBucketKey]*minuteBucket
	dbQueryBuckets map[dbQueryBucketKey]*dbQueryBucket
	activeSpike   *spikeWindow
	lastMaintenanceAt time.Time
	lastSystemMinute  time.Time
}

type RequestMetric struct {
	At        time.Time `json:"at"`
	Method    string    `json:"method"`
	Path      string    `json:"path"`
	Status    int       `json:"status"`
	LatencyMs float64   `json:"latencyMs"`
	RequestID string    `json:"requestId"`
	BytesIn   int64     `json:"bytesIn"`
	BytesOut  int64     `json:"bytesOut"`
}

type ComponentStatus struct {
	Name    string `json:"name"`
	OK      bool   `json:"ok"`
	Message string `json:"message"`
}

type minuteBucketKey struct {
	Minute      time.Time
	Route       string
	Method      string
	StatusClass string
}

type minuteBucket struct {
	Key       minuteBucketKey
	Count     int64
	Latencies []float64
	Max       float64
	BytesIn   int64
	BytesOut  int64
}

type dbQueryBucketKey struct {
	Minute     time.Time
	Route      string
	QueryGroup string
}

type dbQueryBucket struct {
	Key       dbQueryBucketKey
	Count     int64
	Latencies []float64
	Max       float64
}

type spikeWindow struct {
	EventID       uint
	Day           string
	WindowStart   time.Time
	WindowEnd     time.Time
	TriggerAt     time.Time
	Latencies     []float64
	P95PeakMs     float64
	LastPersistAt time.Time
}

func NewMonitor(database *gorm.DB, loc *time.Location) *Monitor {
	return &Monitor{
		DB:               database,
		Location:         loc,
		StartedAt:        time.Now(),
		RecentMaxSamples: 300,
		RecentRequests:   make([]RequestMetric, 0, 300),
		minuteBuckets:    make(map[minuteBucketKey]*minuteBucket),
		dbQueryBuckets:   make(map[dbQueryBucketKey]*dbQueryBucket),
		ThrottleByReason: make(map[string]int64, 4),
	}
}

func (m *Monitor) RecordRequest(metric RequestMetric) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := metric.At
	if now.IsZero() {
		now = time.Now()
	}
	loc := m.Location
	if loc == nil {
		loc = time.Local
	}
	now = now.In(loc)

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

	minute := now.Truncate(time.Minute)
	route := strings.TrimSpace(metric.Path)
	if route == "" {
		route = "/unknown"
	}
	key := minuteBucketKey{
		Minute:      minute,
		Route:       route,
		Method:      strings.ToUpper(strings.TrimSpace(metric.Method)),
		StatusClass: statusClass(metric.Status),
	}
	bucket, ok := m.minuteBuckets[key]
	if !ok {
		bucket = &minuteBucket{
			Key:       key,
			Latencies: make([]float64, 0, 64),
		}
		m.minuteBuckets[key] = bucket
	}
	bucket.Count++
	bucket.BytesIn += maxInt64(metric.BytesIn, 0)
	bucket.BytesOut += maxInt64(metric.BytesOut, 0)
	if metric.LatencyMs > bucket.Max {
		bucket.Max = metric.LatencyMs
	}
	// Keep minute-level latency samples bounded but representative.
	if len(bucket.Latencies) < 2500 {
		bucket.Latencies = append(bucket.Latencies, metric.LatencyMs)
	}

	m.flushCompletedMinuteBucketsLocked(minute)
	m.flushCompletedDBQueryBucketsLocked(minute)
	m.recordSystemMinuteSnapshotLocked(minute)
	m.runMaintenanceLocked(now)
	m.recordSpikeRequestLocked(metric, now)
}

func (m *Monitor) RecordDBQuery(route, queryGroup string, duration time.Duration) {
	if m == nil {
		return
	}
	now := time.Now()
	loc := m.Location
	if loc == nil {
		loc = time.Local
	}
	now = now.In(loc)
	minute := now.Truncate(time.Minute)
	route = strings.TrimSpace(route)
	if route == "" {
		route = "/unknown"
	}
	queryGroup = strings.TrimSpace(queryGroup)
	if queryGroup == "" {
		queryGroup = "unknown"
	}
	ms := float64(duration.Microseconds()) / 1000.0

	m.mu.Lock()
	defer m.mu.Unlock()
	key := dbQueryBucketKey{
		Minute:     minute,
		Route:      route,
		QueryGroup: queryGroup,
	}
	bucket, ok := m.dbQueryBuckets[key]
	if !ok {
		bucket = &dbQueryBucket{
			Key:       key,
			Latencies: make([]float64, 0, 32),
		}
		m.dbQueryBuckets[key] = bucket
	}
	bucket.Count++
	if ms > bucket.Max {
		bucket.Max = ms
	}
	if len(bucket.Latencies) < 2000 {
		bucket.Latencies = append(bucket.Latencies, ms)
	}
	m.flushCompletedDBQueryBucketsLocked(minute)
}

func (m *Monitor) recordSpikeRequestLocked(metric RequestMetric, now time.Time) {
	if m.activeSpike == nil {
		return
	}
	spike := m.activeSpike
	if now.Before(spike.WindowStart) {
		return
	}
	if now.After(spike.WindowEnd) {
		m.finalizeSpikeLocked(now)
		return
	}

	if len(spike.Latencies) < 8000 {
		spike.Latencies = append(spike.Latencies, metric.LatencyMs)
	}
	if len(spike.Latencies) >= 20 && len(spike.Latencies)%20 == 0 {
		p95 := percentile(spike.Latencies, 95)
		if p95 > spike.P95PeakMs {
			spike.P95PeakMs = p95
		}
	}

	if m.DB == nil || spike.EventID == 0 {
		return
	}
	updates := map[string]any{
		"updated_at": now,
	}
	if strings.Contains(metric.Path, "/uploads") && strings.EqualFold(metric.Method, http.MethodPost) {
		updates["upload_count"] = gorm.Expr("upload_count + ?", 1)
	}
	if strings.Contains(metric.Path, "/feed") && strings.EqualFold(metric.Method, http.MethodGet) {
		updates["feed_read_count"] = gorm.Expr("feed_read_count + ?", 1)
	}
	if metric.Status >= 400 {
		updates["error_count"] = gorm.Expr("error_count + ?", 1)
	}
	if len(updates) == 1 {
		return
	}
	if spike.P95PeakMs > 0 && now.Sub(spike.LastPersistAt) >= 5*time.Second {
		updates["p95_peak_ms"] = spike.P95PeakMs
		spike.LastPersistAt = now
	}
	_ = m.DB.Model(&models.DailySpikeEvent{}).Where("id = ?", spike.EventID).Updates(updates).Error
}

func (m *Monitor) finalizeSpikeLocked(now time.Time) {
	if m.activeSpike == nil {
		return
	}
	if m.DB != nil && m.activeSpike.EventID != 0 {
		finalizedAt := now
		_ = m.DB.Model(&models.DailySpikeEvent{}).Where("id = ?", m.activeSpike.EventID).Updates(map[string]any{
			"p95_peak_ms": m.activeSpike.P95PeakMs,
			"finalized_at": &finalizedAt,
			"updated_at":  now,
		}).Error
	}
	m.activeSpike = nil
}

func (m *Monitor) flushCompletedMinuteBucketsLocked(currentMinute time.Time) {
	if m.DB == nil {
		return
	}
	for key, bucket := range m.minuteBuckets {
		if !key.Minute.Before(currentMinute) {
			continue
		}
		record := models.APIMinuteMetric{
			Minute:      key.Minute,
			Route:       key.Route,
			Method:      key.Method,
			StatusClass: key.StatusClass,
			Count:       bucket.Count,
			P50Latency:  percentile(bucket.Latencies, 50),
			P95Latency:  percentile(bucket.Latencies, 95),
			P99Latency:  percentile(bucket.Latencies, 99),
			MaxLatency:  bucket.Max,
			BytesIn:     bucket.BytesIn,
			BytesOut:    bucket.BytesOut,
		}
		_ = m.DB.Clauses(clause.OnConflict{
			Columns: []clause.Column{
				{Name: "minute"},
				{Name: "route"},
				{Name: "method"},
				{Name: "status_class"},
			},
			DoUpdates: clause.Assignments(map[string]any{
				"count":       gorm.Expr("count + ?", record.Count),
				"p50_latency": record.P50Latency,
				"p95_latency": record.P95Latency,
				"p99_latency": record.P99Latency,
				"max_latency": gorm.Expr("MAX(max_latency, ?)", record.MaxLatency),
				"bytes_in":    gorm.Expr("bytes_in + ?", record.BytesIn),
				"bytes_out":   gorm.Expr("bytes_out + ?", record.BytesOut),
				"updated_at":  time.Now(),
			}),
		}).Create(&record).Error
		delete(m.minuteBuckets, key)
	}
}

func (m *Monitor) flushCompletedDBQueryBucketsLocked(currentMinute time.Time) {
	if m.DB == nil {
		return
	}
	for key, bucket := range m.dbQueryBuckets {
		if !key.Minute.Before(currentMinute) {
			continue
		}
		record := models.DBQueryMinuteMetric{
			Minute:     key.Minute,
			Route:      key.Route,
			QueryGroup: key.QueryGroup,
			Count:      bucket.Count,
			P50Ms:      percentile(bucket.Latencies, 50),
			P95Ms:      percentile(bucket.Latencies, 95),
			P99Ms:      percentile(bucket.Latencies, 99),
			MaxMs:      bucket.Max,
		}
		_ = m.DB.Clauses(clause.OnConflict{
			Columns: []clause.Column{
				{Name: "minute"},
				{Name: "route"},
				{Name: "query_group"},
			},
			DoUpdates: clause.Assignments(map[string]any{
				"count":      gorm.Expr("count + ?", record.Count),
				"p50_ms":     record.P50Ms,
				"p95_ms":     record.P95Ms,
				"p99_ms":     record.P99Ms,
				"max_ms":     gorm.Expr("MAX(max_ms, ?)", record.MaxMs),
				"updated_at": time.Now(),
			}),
		}).Create(&record).Error
		delete(m.dbQueryBuckets, key)
	}
}

func (m *Monitor) recordSystemMinuteSnapshotLocked(minute time.Time) {
	if m.DB == nil {
		return
	}
	if !m.lastSystemMinute.IsZero() && m.lastSystemMinute.Equal(minute) {
		return
	}
	m.lastSystemMinute = minute
	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)
	sqlDB, err := m.DB.DB()
	if err != nil {
		return
	}
	dbStats := sqlDB.Stats()
	lastPause := 0.0
	if mem.NumGC > 0 {
		idx := (mem.NumGC - 1) % uint32(len(mem.PauseNs))
		lastPause = float64(mem.PauseNs[idx]) / float64(time.Millisecond)
	}
	record := models.SystemMinuteMetric{
		Minute:             minute,
		MemAllocBytes:      mem.Alloc,
		MemSysBytes:        mem.Sys,
		NumGoroutine:       runtime.NumGoroutine(),
		GCPauseTotalMs:     float64(mem.PauseTotalNs) / float64(time.Millisecond),
		LastGCPauseMs:      lastPause,
		DBOpenConnections:  dbStats.OpenConnections,
		DBInUseConnections: dbStats.InUse,
		DBIdleConnections:  dbStats.Idle,
		DBWaitCount:        int64(dbStats.WaitCount),
		DBWaitDurationMs:   float64(dbStats.WaitDuration.Microseconds()) / 1000.0,
	}
	_ = m.DB.Clauses(clause.OnConflict{
		Columns: []clause.Column{{Name: "minute"}},
		DoUpdates: clause.Assignments(map[string]any{
			"mem_alloc_bytes":       record.MemAllocBytes,
			"mem_sys_bytes":         record.MemSysBytes,
			"num_goroutine":         record.NumGoroutine,
			"gc_pause_total_ms":     record.GCPauseTotalMs,
			"last_gc_pause_ms":      record.LastGCPauseMs,
			"db_open_connections":   record.DBOpenConnections,
			"db_in_use_connections": record.DBInUseConnections,
			"db_idle_connections":   record.DBIdleConnections,
			"db_wait_count":         record.DBWaitCount,
			"db_wait_duration_ms":   record.DBWaitDurationMs,
			"updated_at":            time.Now(),
		}),
	}).Create(&record).Error
}

func (m *Monitor) runMaintenanceLocked(now time.Time) {
	if m.DB == nil {
		return
	}
	if !m.lastMaintenanceAt.IsZero() && now.Sub(m.lastMaintenanceAt) < time.Hour {
		return
	}
	m.lastMaintenanceAt = now

	minuteCutoff := now.Add(-14 * 24 * time.Hour)
	queryCutoff := now.Add(-14 * 24 * time.Hour)
	systemCutoff := now.Add(-30 * 24 * time.Hour)
	spikeCutoff := now.Add(-120 * 24 * time.Hour)
	triggerAuditCutoff := now.Add(-30 * 24 * time.Hour)
	_ = m.DB.Where("minute < ?", minuteCutoff).Delete(&models.APIMinuteMetric{}).Error
	_ = m.DB.Where("minute < ?", queryCutoff).Delete(&models.DBQueryMinuteMetric{}).Error
	_ = m.DB.Where("minute < ?", systemCutoff).Delete(&models.SystemMinuteMetric{}).Error
	_ = m.DB.Where("window_end < ?", spikeCutoff).Delete(&models.DailySpikeEvent{}).Error
	_ = m.DB.Where("occurred_at < ?", triggerAuditCutoff).Delete(&models.DailyTriggerAuditEvent{}).Error
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
	if m.DB != nil && m.activeSpike != nil && m.activeSpike.EventID != 0 {
		_ = m.DB.Model(&models.DailySpikeEvent{}).Where("id = ?", m.activeSpike.EventID).Updates(map[string]any{
			"push_sent":   gorm.Expr("push_sent + ?", sent),
			"updated_at":  time.Now().In(m.Location),
			"finalized_at": nil,
		}).Error
	}
}

func (m *Monitor) RecordThrottle(reason string) {
	if m == nil {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	clean := strings.TrimSpace(reason)
	if clean == "" {
		clean = "unknown"
	}
	m.ThrottleTotal++
	m.ThrottleByReason[clean]++
}

func (m *Monitor) MarkDailySpike(day string, triggerAt time.Time, window time.Duration) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if day == "" {
		day = triggerAt.In(m.Location).Format("2006-01-02")
	}
	if window <= 0 {
		window = 30 * time.Minute
	}
	start := triggerAt.In(m.Location)
	end := start.Add(window)

	m.finalizeSpikeLocked(start)

	record := models.DailySpikeEvent{
		Day:         day,
		TriggerAt:   start,
		WindowStart: start,
		WindowEnd:   end,
	}
	if m.DB != nil {
		_ = m.DB.Create(&record).Error
	}
	m.activeSpike = &spikeWindow{
		EventID:       record.ID,
		Day:           day,
		WindowStart:   start,
		WindowEnd:     end,
		TriggerAt:     start,
		Latencies:     make([]float64, 0, 256),
		LastPersistAt: start,
	}
}

func (m *Monitor) IsInActiveSpike(now time.Time) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.activeSpike == nil {
		return false
	}
	if now.Before(m.activeSpike.WindowStart) || now.After(m.activeSpike.WindowEnd) {
		return false
	}
	return true
}

func (m *Monitor) Snapshot() gin.H {
	m.mu.Lock()
	defer m.mu.Unlock()

	recent := make([]RequestMetric, len(m.RecentRequests))
	copy(recent, m.RecentRequests)
	throttleReasons := make(map[string]int64, len(m.ThrottleByReason))
	for k, v := range m.ThrottleByReason {
		throttleReasons[k] = v
	}

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

	spike := gin.H{
		"active": false,
	}
	if m.activeSpike != nil {
		spike = gin.H{
			"active":      true,
			"day":         m.activeSpike.Day,
			"windowStart": m.activeSpike.WindowStart,
			"windowEnd":   m.activeSpike.WindowEnd,
			"p95PeakMs":   roundFloat(m.activeSpike.P95PeakMs, 3),
		}
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
		"spike":             spike,
		"throttle": gin.H{
			"total":    m.ThrottleTotal,
			"byReason": throttleReasons,
		},
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
			BytesIn:   c.Request.ContentLength,
			BytesOut:  int64(c.Writer.Size()),
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
		Day           string     `json:"day"`
		TriggeredAt   *time.Time `json:"triggeredAt"`
		UploadUntil   *time.Time `json:"uploadUntil"`
		TriggerSource string     `json:"triggerSource"`
		RequestedBy   string     `json:"requestedByUser"`
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
	var latestSystem models.SystemMinuteMetric
	if err := s.DB.Order("minute desc").Limit(1).First(&latestSystem).Error; err == nil {
		resp["system"] = gin.H{
			"minute":             latestSystem.Minute,
			"memAllocBytes":      latestSystem.MemAllocBytes,
			"memSysBytes":        latestSystem.MemSysBytes,
			"numGoroutine":       latestSystem.NumGoroutine,
			"gcPauseTotalMs":     latestSystem.GCPauseTotalMs,
			"lastGCPauseMs":      latestSystem.LastGCPauseMs,
			"dbOpenConnections":  latestSystem.DBOpenConnections,
			"dbInUseConnections": latestSystem.DBInUseConnections,
			"dbIdleConnections":  latestSystem.DBIdleConnections,
			"dbWaitCount":        latestSystem.DBWaitCount,
			"dbWaitDurationMs":   latestSystem.DBWaitDurationMs,
		}
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
	sort.Float64s(clone)
	idx := int(math.Ceil((float64(p)/100.0)*float64(len(clone)))) - 1
	if idx < 0 {
		idx = 0
	}
	if idx >= len(clone) {
		idx = len(clone) - 1
	}
	return clone[idx]
}

func statusClass(status int) string {
	switch {
	case status >= 500:
		return "5xx"
	case status >= 400:
		return "4xx"
	case status >= 300:
		return "3xx"
	case status >= 200:
		return "2xx"
	default:
		return "1xx"
	}
}

func maxInt64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

func (s ComponentStatus) MarshalJSON() ([]byte, error) {
	type alias ComponentStatus
	return json.Marshal(alias(s))
}
