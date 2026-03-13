package api

import (
	"bytes"
	"encoding/csv"
	"fmt"
	"math"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

type performanceBucketRow struct {
	BucketStart time.Time `json:"bucketStart"`
	Requests    int64     `json:"requests"`
	Errors      int64     `json:"errors"`
	Errors4xx   int64     `json:"errors4xx"`
	Errors5xx   int64     `json:"errors5xx"`
	P95Ms       float64   `json:"p95Ms"`
	P99Ms       float64   `json:"p99Ms"`
	MaxMs       float64   `json:"maxMs"`
	BytesIn     int64     `json:"bytesIn"`
	BytesOut    int64     `json:"bytesOut"`
}

type systemBucketRow struct {
	BucketStart         time.Time `json:"bucketStart"`
	MemAllocBytes       uint64    `json:"memAllocBytes"`
	MemSysBytes         uint64    `json:"memSysBytes"`
	NumGoroutine        int       `json:"numGoroutine"`
	LastGCPauseMs       float64   `json:"lastGCPauseMs"`
	DBOpenConnections   int       `json:"dbOpenConnections"`
	DBInUseConnections  int       `json:"dbInUseConnections"`
	DBIdleConnections   int       `json:"dbIdleConnections"`
	DBWaitCount         int64     `json:"dbWaitCount"`
	DBWaitDurationMs    float64   `json:"dbWaitDurationMs"`
}

type dbHotspotRow struct {
	Route      string  `json:"route"`
	QueryGroup string  `json:"queryGroup"`
	Count      int64   `json:"count"`
	P95PeakMs  float64 `json:"p95PeakMs"`
	P99PeakMs  float64 `json:"p99PeakMs"`
	MaxPeakMs  float64 `json:"maxPeakMs"`
}

type routeHotspotRow struct {
	Route    string  `json:"route"`
	Method   string  `json:"method"`
	Requests int64   `json:"requests"`
	Errors   int64   `json:"errors"`
	Errors4x int64   `json:"errors4xx"`
	Errors5x int64   `json:"errors5xx"`
	P95Peak  float64 `json:"p95PeakMs"`
	P99Peak  float64 `json:"p99PeakMs"`
	MaxPeak  float64 `json:"maxPeakMs"`
	ErrorRate float64 `json:"errorRate"`
}

type performanceErrorClassRow struct {
	ErrorClass string  `json:"errorClass"`
	Count      int64   `json:"count"`
	Ratio      float64 `json:"ratio"`
}

func (s *Server) handleAdminPerformanceOverview(c *gin.Context) {
	from, to, ok := s.parsePerformanceRange(c)
	if !ok {
		return
	}
	bucket := strings.ToLower(strings.TrimSpace(c.DefaultQuery("bucket", "1m")))
	if bucket != "1m" && bucket != "5m" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid bucket (allowed: 1m, 5m)"})
		return
	}
	step := time.Minute
	if bucket == "5m" {
		step = 5 * time.Minute
	}

	rows, err := s.loadMinuteMetrics(from, to, 50000)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	systemRows, err := s.loadSystemMinuteMetrics(from, to, 50000)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "system metrics query failed"})
		return
	}
	dbRows, err := s.loadDBQueryMinuteMetrics(from, to, 70000)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "db metrics query failed"})
		return
	}

	buckets := aggregateMinuteRows(rows, step, s.Location)
	systemBuckets := aggregateSystemRows(systemRows, step, s.Location)
	hotspots := aggregateDBHotspots(dbRows, 12)
	errorClasses := s.collectPerformanceErrorClasses(from, to, 90000)
	var throttleCount int64
	for _, item := range errorClasses {
		if item.ErrorClass == "http_429" || item.ErrorClass == "rate_limited" || item.ErrorClass == "feed_rate_limited" {
			throttleCount += item.Count
		}
	}
	totalRequests := sumInt64FromBuckets(buckets, func(item performanceBucketRow) int64 { return item.Requests })
	totalErrors := sumInt64FromBuckets(buckets, func(item performanceBucketRow) int64 { return item.Errors })
	throttleRate := safeRate(throttleCount, totalRequests)
	windowMinutes := int(to.Sub(from) / time.Minute)
	if windowMinutes < 1 {
		windowMinutes = 1
	}
	slo := buildSLOState(rows, time.Now().In(s.Location), s.Location, windowMinutes)
	c.JSON(http.StatusOK, gin.H{
		"schemaVersion": "1.1",
		"from":          from,
		"to":            to,
		"bucket":        bucket,
		"items":         buckets,
		"system":        systemBuckets,
		"dbHotspots":    hotspots,
		"errorClasses":  errorClasses,
		"slo":           slo,
		"summary": gin.H{
			"requests":      totalRequests,
			"errors":        totalErrors,
			"p95Peak":       maxFloatFromBuckets(buckets, func(item performanceBucketRow) float64 { return item.P95Ms }),
			"p99Peak":       maxFloatFromBuckets(buckets, func(item performanceBucketRow) float64 { return item.P99Ms }),
			"throttleCount": throttleCount,
			"throttleRate":  perfRoundFloat(throttleRate, 4),
		},
	})
}

func (s *Server) handleAdminPerformanceRoutes(c *gin.Context) {
	from, to, ok := s.parsePerformanceRange(c)
	if !ok {
		return
	}
	top := 20
	if raw := strings.TrimSpace(c.Query("top")); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > 200 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid top"})
			return
		}
		top = n
	}

	rows, err := s.loadMinuteMetrics(from, to, 70000)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	items := aggregateRouteHotspots(rows, top)
	c.JSON(http.StatusOK, gin.H{
		"from":  from,
		"to":    to,
		"top":   top,
		"items": items,
	})
}

func (s *Server) handleAdminPerformanceSpikes(c *gin.Context) {
	days := 14
	if raw := strings.TrimSpace(c.Query("days")); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > 120 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid days"})
			return
		}
		days = n
	}
	start := time.Now().In(s.Location).AddDate(0, 0, -(days - 1))
	start = time.Date(start.Year(), start.Month(), start.Day(), 0, 0, 0, 0, s.Location)

	var rows []models.DailySpikeEvent
	if err := s.DB.Where("window_start >= ?", start).Order("trigger_at desc").Limit(300).Find(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	items := make([]gin.H, 0, len(rows))
	for _, row := range rows {
		items = append(items, gin.H{
			"id":            row.ID,
			"day":           row.Day,
			"triggerAt":     row.TriggerAt,
			"windowStart":   row.WindowStart,
			"windowEnd":     row.WindowEnd,
			"pushSent":      row.PushSent,
			"uploadCount":   row.UploadCount,
			"feedReadCount": row.FeedReadCount,
			"errorCount":    row.ErrorCount,
			"p95PeakMs":     row.P95PeakMs,
			"finalizedAt":   row.FinalizedAt,
		})
	}
	c.JSON(http.StatusOK, gin.H{
		"days":  days,
		"items": items,
	})
}

func (s *Server) handleAdminPerformanceSLO(c *gin.Context) {
	now := time.Now().In(s.Location)
	windowMinutes := 30
	if raw := strings.TrimSpace(c.Query("windowMinutes")); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 5 || n > 180 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid windowMinutes"})
			return
		}
		windowMinutes = n
	}
	from := now.Add(-time.Duration(windowMinutes) * time.Minute)
	rows, err := s.loadMinuteMetrics(from, now, 50000)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, buildSLOState(rows, now, s.Location, windowMinutes))
}

func (s *Server) handleAdminPerformanceExport(c *gin.Context) {
	from, to, ok := s.parsePerformanceRange(c)
	if !ok {
		return
	}
	format := strings.ToLower(strings.TrimSpace(c.DefaultQuery("format", "json")))
	if format != "json" && format != "csv" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid format"})
		return
	}

	rows, err := s.loadMinuteMetrics(from, to, 100000)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	systemRows, err := s.loadSystemMinuteMetrics(from, to, 100000)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "system metrics query failed"})
		return
	}
	dbRows, err := s.loadDBQueryMinuteMetrics(from, to, 100000)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "db metrics query failed"})
		return
	}
	var spikes []models.DailySpikeEvent
	_ = s.DB.Where("window_start >= ? AND window_end <= ?", from, to).Order("trigger_at desc").Limit(500).Find(&spikes).Error
	windowMinutes := int(to.Sub(from) / time.Minute)
	if windowMinutes < 1 {
		windowMinutes = 1
	}
	slo := buildSLOState(rows, time.Now().In(s.Location), s.Location, windowMinutes)

	ts := time.Now().In(s.Location).Format("20060102-150405")
	if format == "json" {
		filename := fmt.Sprintf("performance-%s.json", ts)
		c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", filename))
		c.Header("Content-Type", "application/json; charset=utf-8")
		errorClasses := s.collectPerformanceErrorClasses(from, to, 120000)
		overviewBuckets := aggregateMinuteRows(rows, time.Minute, s.Location)
		systemBuckets := aggregateSystemRows(systemRows, time.Minute, s.Location)
		hotspots := aggregateDBHotspots(dbRows, 20)
		routes := aggregateRouteHotspots(rows, 25)
		var throttleCount int64
		for _, row := range errorClasses {
			if row.ErrorClass == "http_429" || row.ErrorClass == "rate_limited" || row.ErrorClass == "feed_rate_limited" {
				throttleCount += row.Count
			}
		}
		totalRequests := sumInt64FromBuckets(overviewBuckets, func(item performanceBucketRow) int64 { return item.Requests })
		totalErrors := sumInt64FromBuckets(overviewBuckets, func(item performanceBucketRow) int64 { return item.Errors })
		c.JSON(http.StatusOK, gin.H{
			"schemaVersion": "1.1",
			"generatedAt":   time.Now().In(s.Location),
			"from":          from,
			"to":            to,
			"minuteRows":    rows,
			"systemRows":    systemRows,
			"dbQueryRows":   dbRows,
			"spikes":        spikes,
			"errorClasses":  errorClasses,
			"slo":           slo,
			"overview": gin.H{
				"bucket":      "1m",
				"items":       overviewBuckets,
				"system":      systemBuckets,
				"dbHotspots":  hotspots,
				"errorClasses": errorClasses,
				"summary": gin.H{
					"requests":      totalRequests,
					"errors":        totalErrors,
					"p95Peak":       maxFloatFromBuckets(overviewBuckets, func(item performanceBucketRow) float64 { return item.P95Ms }),
					"p99Peak":       maxFloatFromBuckets(overviewBuckets, func(item performanceBucketRow) float64 { return item.P99Ms }),
					"throttleCount": throttleCount,
					"throttleRate":  perfRoundFloat(safeRate(throttleCount, totalRequests), 4),
				},
			},
			"routes": routes,
		})
		return
	}

	filename := fmt.Sprintf("performance-%s.csv", ts)
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", filename))
	c.Header("Content-Type", "text/csv; charset=utf-8")
	var buf bytes.Buffer
	buf.Write([]byte{0xEF, 0xBB, 0xBF})
	writer := csv.NewWriter(&buf)
	header := []string{"section", "minute", "route", "method", "status_class", "count", "p50_latency_ms", "p95_latency_ms", "p99_latency_ms", "max_latency_ms", "bytes_in", "bytes_out", "query_group", "mem_alloc_bytes", "mem_sys_bytes", "goroutines", "gc_pause_total_ms", "last_gc_pause_ms", "db_open", "db_in_use", "db_idle", "db_wait_count", "db_wait_duration_ms", "spike_id", "day", "trigger_at", "window_start", "window_end", "push_sent", "upload_count", "feed_read_count", "error_count", "spike_p95_peak_ms", "finalized_at", "error_class", "ratio"}
	_ = writer.Write(header)
	writePerfRow := func(cols ...string) {
		if len(cols) < len(header) {
			padded := make([]string, len(header))
			copy(padded, cols)
			cols = padded
		}
		if len(cols) > len(header) {
			cols = cols[:len(header)]
		}
		_ = writer.Write(cols)
	}
	for _, row := range rows {
		writePerfRow(
			"minute_metric",
			row.Minute.In(s.Location).Format(time.RFC3339),
			row.Route,
			row.Method,
			row.StatusClass,
			strconv.FormatInt(row.Count, 10),
			floatToString(row.P50Latency),
			floatToString(row.P95Latency),
			floatToString(row.P99Latency),
			floatToString(row.MaxLatency),
			strconv.FormatInt(row.BytesIn, 10),
			strconv.FormatInt(row.BytesOut, 10),
			"", "", "", "", "", "", "", "", "", "", "", "",
			"", "", "", "", "", "", "", "", "", "", "",
		)
	}
	for _, row := range dbRows {
		writePerfRow(
			"db_query_metric",
			row.Minute.In(s.Location).Format(time.RFC3339),
			row.Route,
			"", "",
			strconv.FormatInt(row.Count, 10),
			floatToString(row.P50Ms),
			floatToString(row.P95Ms),
			floatToString(row.P99Ms),
			floatToString(row.MaxMs),
			"", "",
			row.QueryGroup,
			"", "", "", "", "", "", "", "", "", "",
			"", "", "", "", "", "", "", "", "", "", "",
		)
	}
	for _, row := range systemRows {
		writePerfRow(
			"system_metric",
			row.Minute.In(s.Location).Format(time.RFC3339),
			"", "", "", "", "", "", "", "", "", "",
			"",
			strconv.FormatUint(row.MemAllocBytes, 10),
			strconv.FormatUint(row.MemSysBytes, 10),
			strconv.Itoa(row.NumGoroutine),
			floatToString(row.GCPauseTotalMs),
			floatToString(row.LastGCPauseMs),
			strconv.Itoa(row.DBOpenConnections),
			strconv.Itoa(row.DBInUseConnections),
			strconv.Itoa(row.DBIdleConnections),
			strconv.FormatInt(row.DBWaitCount, 10),
			floatToString(row.DBWaitDurationMs),
			"", "", "", "", "", "", "", "", "", "", "",
		)
	}
	for _, spike := range spikes {
		finalized := ""
		if spike.FinalizedAt != nil {
			finalized = spike.FinalizedAt.In(s.Location).Format(time.RFC3339)
		}
		writePerfRow(
			"spike_event",
			"", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
			strconv.FormatUint(uint64(spike.ID), 10),
			spike.Day,
			spike.TriggerAt.In(s.Location).Format(time.RFC3339),
			spike.WindowStart.In(s.Location).Format(time.RFC3339),
			spike.WindowEnd.In(s.Location).Format(time.RFC3339),
			strconv.FormatInt(spike.PushSent, 10),
			strconv.FormatInt(spike.UploadCount, 10),
			strconv.FormatInt(spike.FeedReadCount, 10),
			strconv.FormatInt(spike.ErrorCount, 10),
			floatToString(spike.P95PeakMs),
			finalized,
			"",
			"",
		)
	}
	errorClasses := s.collectPerformanceErrorClasses(from, to, 120000)
	for _, row := range errorClasses {
		writePerfRow(
			"error_class",
			"", "", "", "",
			strconv.FormatInt(row.Count, 10),
			"", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
			row.ErrorClass,
			floatToString(row.Ratio),
		)
	}
	writer.Flush()
	if err := writer.Error(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "csv export failed"})
		return
	}
	c.Data(http.StatusOK, "text/csv; charset=utf-8", buf.Bytes())
}

func (s *Server) collectPerformanceErrorClasses(from, to time.Time, limit int) []performanceErrorClassRow {
	if limit < 100 {
		limit = 100
	}
	if limit > 200000 {
		limit = 200000
	}
	var rows []models.ClientDebugLog
	err := s.DB.
		Select("type, message, meta, created_at").
		Where("created_at >= ? AND created_at <= ?", from, to).
		Where("type IN ?", []string{"dashboard_load_failed", "feed_refresh_failed", "upload_queue_failed", "chat_send_failed"}).
		Order("created_at desc").
		Limit(limit).
		Find(&rows).Error
	if err != nil || len(rows) == 0 {
		return []performanceErrorClassRow{}
	}
	counts := make(map[string]int64, 16)
	var total int64
	for _, row := range rows {
		class := normalizePerformanceErrorClass(row)
		counts[class]++
		total++
	}
	if total == 0 {
		return []performanceErrorClassRow{}
	}
	out := make([]performanceErrorClassRow, 0, len(counts))
	for class, count := range counts {
		out = append(out, performanceErrorClassRow{
			ErrorClass: class,
			Count:      count,
			Ratio:      perfRoundFloat(float64(count)/float64(total), 4),
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Count == out[j].Count {
			return out[i].ErrorClass < out[j].ErrorClass
		}
		return out[i].Count > out[j].Count
	})
	return out
}

func normalizePerformanceErrorClass(row models.ClientDebugLog) string {
	meta := parseSemicolonMeta(row.Meta)
	if v := strings.ToLower(strings.TrimSpace(meta["failureClass"])); v != "" {
		return normalizeErrorClassValue(v)
	}
	if v := strings.ToLower(strings.TrimSpace(meta["network"])); v != "" {
		return normalizeErrorClassValue(v)
	}
	if raw := strings.TrimSpace(meta["http"]); raw != "" {
		if code, err := strconv.Atoi(raw); err == nil {
			if code == 429 {
				return "http_429"
			}
			if code >= 500 {
				return "http5xx"
			}
			if code >= 400 {
				return "http4xx"
			}
		}
	}
	if v := strings.ToLower(strings.TrimSpace(meta["reason"])); v != "" {
		return normalizeErrorClassValue(v)
	}
	msg := strings.ToLower(strings.TrimSpace(row.Message))
	switch {
	case strings.Contains(msg, "resolve host") || strings.Contains(msg, "servername konnte nicht"):
		return "dns"
	case strings.Contains(msg, "timed out") || strings.Contains(msg, "zu langsam"):
		return "timeout"
	case strings.Contains(msg, "failed to connect") || strings.Contains(msg, "verbindung"):
		return "connect"
	}
	return "other"
}

func normalizeErrorClassValue(v string) string {
	trimmed := strings.ToLower(strings.TrimSpace(v))
	switch trimmed {
	case "dns", "connect", "timeout", "offline", "db":
		return trimmed
	case "http_429", "429", "feed_rate_limited", "rate_limited":
		return "http_429"
	case "http4xx", "http_4xx":
		return "http4xx"
	case "http5xx", "http_5xx":
		return "http5xx"
	}
	if strings.HasPrefix(trimmed, "http_") {
		codeRaw := strings.TrimPrefix(trimmed, "http_")
		if code, err := strconv.Atoi(codeRaw); err == nil {
			if code == 429 {
				return "http_429"
			}
			if code >= 500 {
				return "http5xx"
			}
			if code >= 400 {
				return "http4xx"
			}
		}
	}
	if strings.HasPrefix(trimmed, "http") && strings.Contains(trimmed, "4") {
		return "http4xx"
	}
	if strings.HasPrefix(trimmed, "http") && strings.Contains(trimmed, "5") {
		return "http5xx"
	}
	return trimmed
}

func parseSemicolonMeta(meta string) map[string]string {
	out := make(map[string]string, 12)
	for _, part := range strings.Split(meta, ";") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		eq := strings.Index(part, "=")
		if eq <= 0 {
			continue
		}
		key := strings.TrimSpace(part[:eq])
		val := strings.TrimSpace(part[eq+1:])
		if key == "" {
			continue
		}
		out[key] = val
	}
	return out
}

func (s *Server) parsePerformanceRange(c *gin.Context) (time.Time, time.Time, bool) {
	now := time.Now().In(s.Location)
	from := now.Add(-6 * time.Hour)
	to := now

	if raw := strings.TrimSpace(c.Query("from")); raw != "" {
		parsed, err := parseTimeQuery(raw, s.Location)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid from"})
			return time.Time{}, time.Time{}, false
		}
		from = parsed
	}
	if raw := strings.TrimSpace(c.Query("to")); raw != "" {
		parsed, err := parseTimeQuery(raw, s.Location)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid to"})
			return time.Time{}, time.Time{}, false
		}
		to = parsed
	}
	if !from.Before(to) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "from must be before to"})
		return time.Time{}, time.Time{}, false
	}
	if to.Sub(from) > 31*24*time.Hour {
		c.JSON(http.StatusBadRequest, gin.H{"error": "range too large"})
		return time.Time{}, time.Time{}, false
	}
	return from, to, true
}

func parseTimeQuery(raw string, loc *time.Location) (time.Time, error) {
	if t, err := time.Parse(time.RFC3339, raw); err == nil {
		return t.In(loc), nil
	}
	if t, err := time.ParseInLocation("2006-01-02 15:04:05", raw, loc); err == nil {
		return t, nil
	}
	if t, err := time.ParseInLocation("2006-01-02", raw, loc); err == nil {
		return t, nil
	}
	return time.Time{}, fmt.Errorf("invalid time")
}

func (s *Server) loadMinuteMetrics(from, to time.Time, limit int) ([]models.APIMinuteMetric, error) {
	if limit < 1 {
		limit = 1
	}
	if limit > 120000 {
		limit = 120000
	}
	var rows []models.APIMinuteMetric
	err := s.DB.Where("minute >= ? AND minute <= ?", from, to).Order("minute asc").Limit(limit).Find(&rows).Error
	return rows, err
}

func (s *Server) loadSystemMinuteMetrics(from, to time.Time, limit int) ([]models.SystemMinuteMetric, error) {
	if limit < 1 {
		limit = 1
	}
	if limit > 120000 {
		limit = 120000
	}
	var rows []models.SystemMinuteMetric
	err := s.DB.Where("minute >= ? AND minute <= ?", from, to).Order("minute asc").Limit(limit).Find(&rows).Error
	return rows, err
}

func (s *Server) loadDBQueryMinuteMetrics(from, to time.Time, limit int) ([]models.DBQueryMinuteMetric, error) {
	if limit < 1 {
		limit = 1
	}
	if limit > 120000 {
		limit = 120000
	}
	var rows []models.DBQueryMinuteMetric
	err := s.DB.Where("minute >= ? AND minute <= ?", from, to).Order("minute asc").Limit(limit).Find(&rows).Error
	return rows, err
}

func aggregateSystemRows(rows []models.SystemMinuteMetric, step time.Duration, loc *time.Location) []systemBucketRow {
	if step <= 0 {
		step = time.Minute
	}
	m := make(map[time.Time]*systemBucketRow, len(rows))
	for _, row := range rows {
		key := row.Minute.In(loc).Truncate(step)
		item, ok := m[key]
		if !ok {
			item = &systemBucketRow{BucketStart: key}
			m[key] = item
		}
		// Use the latest minute sample in each bucket as representative state.
		item.MemAllocBytes = row.MemAllocBytes
		item.MemSysBytes = row.MemSysBytes
		item.NumGoroutine = row.NumGoroutine
		item.LastGCPauseMs = row.LastGCPauseMs
		item.DBOpenConnections = row.DBOpenConnections
		item.DBInUseConnections = row.DBInUseConnections
		item.DBIdleConnections = row.DBIdleConnections
		item.DBWaitCount = row.DBWaitCount
		item.DBWaitDurationMs = row.DBWaitDurationMs
	}
	out := make([]systemBucketRow, 0, len(m))
	for _, item := range m {
		out = append(out, *item)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].BucketStart.Before(out[j].BucketStart) })
	return out
}

func aggregateDBHotspots(rows []models.DBQueryMinuteMetric, top int) []dbHotspotRow {
	if top < 1 {
		top = 1
	}
	if top > 200 {
		top = 200
	}
	type key struct {
		Route      string
		QueryGroup string
	}
	agg := make(map[key]*dbHotspotRow, len(rows))
	for _, row := range rows {
		k := key{Route: row.Route, QueryGroup: row.QueryGroup}
		item, ok := agg[k]
		if !ok {
			item = &dbHotspotRow{
				Route:      row.Route,
				QueryGroup: row.QueryGroup,
			}
			agg[k] = item
		}
		item.Count += row.Count
		if row.P95Ms > item.P95PeakMs {
			item.P95PeakMs = row.P95Ms
		}
		if row.P99Ms > item.P99PeakMs {
			item.P99PeakMs = row.P99Ms
		}
		if row.MaxMs > item.MaxPeakMs {
			item.MaxPeakMs = row.MaxMs
		}
	}
	out := make([]dbHotspotRow, 0, len(agg))
	for _, item := range agg {
		out = append(out, *item)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].P95PeakMs == out[j].P95PeakMs {
			return out[i].Count > out[j].Count
		}
		return out[i].P95PeakMs > out[j].P95PeakMs
	})
	if len(out) > top {
		out = out[:top]
	}
	return out
}

func aggregateRouteHotspots(rows []models.APIMinuteMetric, top int) []routeHotspotRow {
	if top < 1 {
		top = 1
	}
	if top > 200 {
		top = 200
	}
	aggMap := make(map[string]*routeHotspotRow, 256)
	for _, row := range rows {
		key := row.Method + "|" + row.Route
		item, exists := aggMap[key]
		if !exists {
			item = &routeHotspotRow{Route: row.Route, Method: row.Method}
			aggMap[key] = item
		}
		item.Requests += row.Count
		switch row.StatusClass {
		case "4xx":
			item.Errors += row.Count
			item.Errors4x += row.Count
		case "5xx":
			item.Errors += row.Count
			item.Errors5x += row.Count
		}
		if row.P95Latency > item.P95Peak {
			item.P95Peak = row.P95Latency
		}
		if row.P99Latency > item.P99Peak {
			item.P99Peak = row.P99Latency
		}
		if row.MaxLatency > item.MaxPeak {
			item.MaxPeak = row.MaxLatency
		}
	}
	out := make([]routeHotspotRow, 0, len(aggMap))
	for _, item := range aggMap {
		if item.Requests > 0 {
			item.ErrorRate = perfRoundFloat(float64(item.Errors)/float64(item.Requests), 4)
		}
		out = append(out, *item)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Requests == out[j].Requests {
			return out[i].P95Peak > out[j].P95Peak
		}
		return out[i].Requests > out[j].Requests
	})
	if len(out) > top {
		out = out[:top]
	}
	return out
}

func buildSLOState(rows []models.APIMinuteMetric, now time.Time, loc *time.Location, windowMinutes int) gin.H {
	if windowMinutes < 1 {
		windowMinutes = 1
	}
	const (
		feedP95ThresholdMs      = 2500.0
		global5xxRateThreshold  = 0.020
		uploadErrorRateThreshold = 0.080
		feed4xxRateThreshold    = 0.150
	)
	var (
		totalReq int64
		total5xx int64

		feedReq int64
		feedErr4xx int64
		feedP95Peak float64

		uploadReq int64
		uploadErr int64
	)
	for _, row := range rows {
		totalReq += row.Count
		if row.StatusClass == "5xx" {
			total5xx += row.Count
		}
		if strings.Contains(row.Route, "/feed") {
			feedReq += row.Count
			if row.StatusClass == "4xx" {
				feedErr4xx += row.Count
			}
			if row.P95Latency > feedP95Peak {
				feedP95Peak = row.P95Latency
			}
		}
		if strings.Contains(row.Route, "/uploads") {
			uploadReq += row.Count
			if row.StatusClass == "4xx" || row.StatusClass == "5xx" {
				uploadErr += row.Count
			}
		}
	}
	global5xxRate := safeRate(total5xx, totalReq)
	feed4xxRate := safeRate(feedErr4xx, feedReq)
	uploadErrorRate := safeRate(uploadErr, uploadReq)

	violations := make([]gin.H, 0, 4)
	if feedP95Peak > feedP95ThresholdMs {
		violations = append(violations, gin.H{
			"id": "feed_p95_latency",
			"severity": "high",
			"threshold": feedP95ThresholdMs,
			"observed": perfRoundFloat(feedP95Peak, 3),
			"unit": "ms",
		})
	}
	if global5xxRate > global5xxRateThreshold {
		violations = append(violations, gin.H{
			"id": "global_5xx_rate",
			"severity": "high",
			"threshold": global5xxRateThreshold,
			"observed": perfRoundFloat(global5xxRate, 4),
			"unit": "ratio",
		})
	}
	if uploadErrorRate > uploadErrorRateThreshold {
		violations = append(violations, gin.H{
			"id": "upload_error_rate",
			"severity": "medium",
			"threshold": uploadErrorRateThreshold,
			"observed": perfRoundFloat(uploadErrorRate, 4),
			"unit": "ratio",
		})
	}
	if feed4xxRate > feed4xxRateThreshold {
		violations = append(violations, gin.H{
			"id": "feed_4xx_rate",
			"severity": "medium",
			"threshold": feed4xxRateThreshold,
			"observed": perfRoundFloat(feed4xxRate, 4),
			"unit": "ratio",
		})
	}

	return gin.H{
		"evaluatedAt": now.In(loc),
		"windowMinutes": windowMinutes,
		"status": map[bool]string{true: "breach", false: "ok"}[len(violations) > 0],
		"metrics": gin.H{
			"feedP95PeakMs": perfRoundFloat(feedP95Peak, 3),
			"global5xxRate": perfRoundFloat(global5xxRate, 4),
			"uploadErrorRate": perfRoundFloat(uploadErrorRate, 4),
			"feed4xxRate": perfRoundFloat(feed4xxRate, 4),
			"requestsTotal": totalReq,
		},
		"thresholds": gin.H{
			"feedP95PeakMs": feedP95ThresholdMs,
			"global5xxRate": global5xxRateThreshold,
			"uploadErrorRate": uploadErrorRateThreshold,
			"feed4xxRate": feed4xxRateThreshold,
		},
		"violations": violations,
	}
}

func safeRate(part, total int64) float64 {
	if total <= 0 {
		return 0
	}
	return float64(part) / float64(total)
}

func perfRoundFloat(v float64, precision int) float64 {
	if precision < 0 {
		precision = 0
	}
	f := math.Pow(10, float64(precision))
	return math.Round(v*f) / f
}

func aggregateMinuteRows(rows []models.APIMinuteMetric, step time.Duration, loc *time.Location) []performanceBucketRow {
	if step <= 0 {
		step = time.Minute
	}
	m := make(map[time.Time]*performanceBucketRow, len(rows))
	for _, row := range rows {
		key := row.Minute.In(loc).Truncate(step)
		item, ok := m[key]
		if !ok {
			item = &performanceBucketRow{BucketStart: key}
			m[key] = item
		}
		item.Requests += row.Count
		switch row.StatusClass {
		case "4xx":
			item.Errors += row.Count
			item.Errors4xx += row.Count
		case "5xx":
			item.Errors += row.Count
			item.Errors5xx += row.Count
		}
		if row.P95Latency > item.P95Ms {
			item.P95Ms = row.P95Latency
		}
		if row.P99Latency > item.P99Ms {
			item.P99Ms = row.P99Latency
		}
		if row.MaxLatency > item.MaxMs {
			item.MaxMs = row.MaxLatency
		}
		item.BytesIn += row.BytesIn
		item.BytesOut += row.BytesOut
	}
	out := make([]performanceBucketRow, 0, len(m))
	for _, item := range m {
		out = append(out, *item)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].BucketStart.Before(out[j].BucketStart) })
	return out
}

func sumInt64FromBuckets(items []performanceBucketRow, read func(performanceBucketRow) int64) int64 {
	var out int64
	for _, item := range items {
		out += read(item)
	}
	return out
}

func maxFloatFromBuckets(items []performanceBucketRow, read func(performanceBucketRow) float64) float64 {
	max := 0.0
	for _, item := range items {
		if v := read(item); v > max {
			max = v
		}
	}
	return max
}

func floatToString(v float64) string {
	return strconv.FormatFloat(v, 'f', 3, 64)
}
