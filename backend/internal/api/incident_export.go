package api

import (
	"bufio"
	"fmt"
	"net/http"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

const (
	incidentExportSchemaVersion = "incident_bundle_v1"
	incidentLogMaxLines         = 1200
	incidentLogMaxBytes         = 600 * 1024
)

var (
	reGatewayTime = regexp.MustCompile(`\[(\d{2}/[A-Za-z]{3}/\d{4}:\d{2}:\d{2}:\d{2} [+\-]\d{4})\]`)
	reBackendTime = regexp.MustCompile(`\b(\d{4}/\d{2}/\d{2}) (\d{2}:\d{2}:\d{2})\b`)
	reBackendGin  = regexp.MustCompile(`\b(\d{4}/\d{2}/\d{2}) - (\d{2}:\d{2}:\d{2})\b`)
	reBearerToken = regexp.MustCompile(`(?i)Bearer\s+[A-Za-z0-9\-\._~\+\/]+=*`)
	reQueryToken  = regexp.MustCompile(`(?i)(token=)[^&\s"]+`)
)

func (s *Server) handleAdminIncidentExport(c *gin.Context) {
	format := strings.ToLower(strings.TrimSpace(c.DefaultQuery("format", "json")))
	if format != "json" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid format"})
		return
	}
	includeGateway := true
	if raw := strings.TrimSpace(c.Query("includeGateway")); raw != "" {
		parsed, err := strconv.ParseBool(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid includeGateway"})
			return
		}
		includeGateway = parsed
	}
	statusOnly := false
	if raw := strings.TrimSpace(c.Query("statusOnly")); raw != "" {
		parsed, err := strconv.ParseBool(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid statusOnly"})
			return
		}
		statusOnly = parsed
	}

	from, to, ok := s.parseIncidentRange(c)
	if !ok {
		return
	}
	requestID := requestIDFromContext(c)

	triggerRows, err := s.loadTriggerAuditRowsForRange(from, to, 120000)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "trigger audit query failed"})
		return
	}
	triggerSummary := summarizeTriggerAuditRows(triggerRows)

	backendLog, backendWarnings := s.collectForensicLogExcerpt(s.Config.ForensicBackendLogPath, from, to, "backend")
	gatewayLog := gin.H{"available": false}
	gatewayWarnings := []string{}
	if includeGateway {
		gatewayLog, gatewayWarnings = s.collectForensicLogExcerpt(s.Config.ForensicGatewayLogPath, from, to, "gateway")
	}

	collectionWarnings := make([]string, 0, 8)
	collectionWarnings = append(collectionWarnings, backendWarnings...)
	collectionWarnings = append(collectionWarnings, gatewayWarnings...)

	responseMeta := gin.H{
		"schemaVersion": incidentExportSchemaVersion,
		"generatedAt":   time.Now().In(s.Location),
		"serverVersion": s.Config.AppVersion,
		"timezone":      s.Config.Timezone,
		"requestId":     requestID,
		"from":          from,
		"to":            to,
		"includeGateway": includeGateway,
	}

	if statusOnly {
		lastSource := ""
		if len(triggerRows) > 0 {
			lastSource = strings.TrimSpace(triggerRows[0].Source)
		}
		c.JSON(http.StatusOK, gin.H{
			"meta": responseMeta,
			"status": gin.H{
				"duplicateAttempts": triggerSummary["duplicateAttempts"],
				"multipleAttemptDays": triggerSummary["multipleAttemptDays"],
				"lastTriggerSource": lastSource,
				"gatewayLogAvailable": gatewayLog["available"],
				"backendLogAvailable": backendLog["available"],
			},
			"collectionWarnings": collectionWarnings,
		})
		return
	}

	minuteRows, _ := s.loadMinuteMetrics(from, to, 100000)
	spikeRows := make([]models.DailySpikeEvent, 0, 128)
	_ = s.DB.Where("window_end >= ? AND window_start <= ?", from, to).Order("trigger_at desc").Limit(500).Find(&spikeRows).Error
	errorClasses := s.collectPerformanceErrorClasses(from, to, 120000)
	windowMinutes := int(to.Sub(from) / time.Minute)
	if windowMinutes < 1 {
		windowMinutes = 1
	}

	components := s.collectComponentStatus()
	latestPrompt := gin.H{}
	var latestPromptRow models.DailyPrompt
	if err := s.DB.Order("day desc").Limit(1).First(&latestPromptRow).Error; err == nil {
		latestPrompt = gin.H{
			"day":          latestPromptRow.Day,
			"triggeredAt":  latestPromptRow.TriggeredAt,
			"uploadUntil":  latestPromptRow.UploadUntil,
			"triggerSource": latestPromptRow.TriggerSource,
			"requestedBy":  latestPromptRow.RequestedBy,
		}
	}

	liveSnapshot := gin.H{
		"components":   components,
		"latestPrompt": latestPrompt,
	}
	if s.Monitor != nil {
		liveSnapshot["monitor"] = s.Monitor.Snapshot()
	}

	historySlice, historyErr := s.buildIncidentHistorySlice(from, to, triggerRows)
	if historyErr != nil {
		collectionWarnings = append(collectionWarnings, "history slice collection failed")
	}

	c.JSON(http.StatusOK, gin.H{
		"meta":            responseMeta,
		"triggerAudit":    triggerRows,
		"triggerSummary":  triggerSummary,
		"performance": gin.H{
			"overview": gin.H{
				"bucket": "1m",
				"items":  aggregateMinuteRows(minuteRows, time.Minute, s.Location),
			},
			"routes":      aggregateRouteHotspots(minuteRows, 25),
			"spikes":      spikeRows,
			"slo":         buildSLOState(minuteRows, time.Now().In(s.Location), s.Location, windowMinutes),
			"errorClasses": errorClasses,
		},
		"historySlice":       historySlice,
		"liveSnapshot":       liveSnapshot,
		"rawBackendLogExcerpt": backendLog,
		"rawGatewayLogExcerpt": gatewayLog,
		"collectionWarnings": collectionWarnings,
	})
}

func (s *Server) parseIncidentRange(c *gin.Context) (time.Time, time.Time, bool) {
	now := time.Now().In(s.Location)
	from := now.Add(-2 * time.Hour)
	to := now

	if day := strings.TrimSpace(c.Query("day")); day != "" {
		dayStart, err := time.ParseInLocation("2006-01-02", day, s.Location)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid day"})
			return time.Time{}, time.Time{}, false
		}
		from = dayStart
		to = dayStart.Add(24*time.Hour - time.Nanosecond)
	}
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
	if to.Sub(from) > 72*time.Hour {
		c.JSON(http.StatusBadRequest, gin.H{"error": "range too large (max 72h)"})
		return time.Time{}, time.Time{}, false
	}
	return from, to, true
}

func (s *Server) loadTriggerAuditRowsForRange(from, to time.Time, limit int) ([]models.DailyTriggerAuditEvent, error) {
	if limit < 1 {
		limit = 1
	}
	if limit > 120000 {
		limit = 120000
	}
	var rows []models.DailyTriggerAuditEvent
	err := s.DB.
		Where("occurred_at >= ? AND occurred_at <= ?", from, to).
		Order("occurred_at desc").
		Limit(limit).
		Find(&rows).Error
	return rows, err
}

func summarizeTriggerAuditRows(rows []models.DailyTriggerAuditEvent) gin.H {
	attempts := len(rows)
	triggered := 0
	blocked := 0
	failed := 0
	dbLocked := 0
	byDayAttempts := make(map[string]int, 16)
	for _, row := range rows {
		dayKey := strings.TrimSpace(row.Day)
		if dayKey == "" {
			dayKey = row.OccurredAt.Format("2006-01-02")
		}
		byDayAttempts[dayKey]++
		switch strings.ToLower(strings.TrimSpace(row.Result)) {
		case "triggered":
			triggered++
		case "blocked":
			blocked++
		default:
			failed++
		}
		if strings.EqualFold(strings.TrimSpace(row.Reason), "db_locked") {
			dbLocked++
		}
	}
	duplicateAttempts := 0
	multipleAttemptDays := 0
	for _, count := range byDayAttempts {
		if count > 1 {
			multipleAttemptDays++
			duplicateAttempts += count - 1
		}
	}
	return gin.H{
		"attempts":            attempts,
		"triggered":           triggered,
		"blocked":             blocked,
		"failed":              failed,
		"dbLocked":            dbLocked,
		"duplicateAttempts":   duplicateAttempts,
		"multipleAttemptDays": multipleAttemptDays,
		"blockedRate":         safeRatio(blocked, maxInt(1, attempts)),
		"failedRate":          safeRatio(failed, maxInt(1, attempts)),
	}
}

func (s *Server) collectForensicLogExcerpt(path string, from, to time.Time, kind string) (gin.H, []string) {
	warnings := make([]string, 0, 2)
	path = strings.TrimSpace(path)
	if path == "" {
		return gin.H{"available": false, "path": "", "lines": []string{}}, append(warnings, fmt.Sprintf("%s log path not configured", kind))
	}
	file, err := os.Open(path)
	if err != nil {
		return gin.H{"available": false, "path": path, "lines": []string{}}, append(warnings, fmt.Sprintf("%s log not available: %v", kind, err))
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	buffer := make([]byte, 0, 64*1024)
	scanner.Buffer(buffer, 1024*1024)

	lines := make([]string, 0, minInt(incidentLogMaxLines, 500))
	collectedBytes := 0
	totalScanned := 0
	matched := 0
	truncated := false
	for scanner.Scan() {
		totalScanned++
		line := scanner.Text()
		ts, hasTs := parseForensicLogTime(line, kind, s.Location)
		if hasTs {
			if ts.Before(from) || ts.After(to) {
				continue
			}
		}
		clean := sanitizeLogLine(line)
		lineBytes := len(clean) + 1
		if len(lines) >= incidentLogMaxLines || collectedBytes+lineBytes > incidentLogMaxBytes {
			truncated = true
			break
		}
		lines = append(lines, clean)
		collectedBytes += lineBytes
		if hasTs {
			matched++
		}
	}
	if scanErr := scanner.Err(); scanErr != nil {
		warnings = append(warnings, fmt.Sprintf("%s log read error: %v", kind, scanErr))
	}
	return gin.H{
		"available":     true,
		"path":          path,
		"lineCount":     len(lines),
		"matchedLines":  matched,
		"totalScanned":  totalScanned,
		"truncated":     truncated,
		"maxLines":      incidentLogMaxLines,
		"maxBytes":      incidentLogMaxBytes,
		"lines":         lines,
	}, warnings
}

func parseForensicLogTime(line string, kind string, loc *time.Location) (time.Time, bool) {
	if loc == nil {
		loc = time.UTC
	}
	if kind == "gateway" {
		m := reGatewayTime.FindStringSubmatch(line)
		if len(m) >= 2 {
			if parsed, err := time.Parse("02/Jan/2006:15:04:05 -0700", m[1]); err == nil {
				return parsed.In(loc), true
			}
		}
		return time.Time{}, false
	}
	m := reBackendTime.FindStringSubmatch(line)
	if len(m) >= 3 {
		if parsed, err := time.ParseInLocation("2006/01/02 15:04:05", fmt.Sprintf("%s %s", m[1], m[2]), loc); err == nil {
			return parsed, true
		}
	}
	m = reBackendGin.FindStringSubmatch(line)
	if len(m) >= 3 {
		if parsed, err := time.ParseInLocation("2006/01/02 15:04:05", fmt.Sprintf("%s %s", m[1], m[2]), loc); err == nil {
			return parsed, true
		}
	}
	return time.Time{}, false
}

func sanitizeLogLine(line string) string {
	out := reBearerToken.ReplaceAllString(line, "Bearer ***")
	out = reQueryToken.ReplaceAllString(out, "${1}***")
	return out
}

func (s *Server) buildIncidentHistorySlice(from, to time.Time, triggerRows []models.DailyTriggerAuditEvent) ([]gin.H, error) {
	oldest := from.In(s.Location).Format("2006-01-02")
	newest := to.In(s.Location).Format("2006-01-02")

	var prompts []models.DailyPrompt
	if err := s.DB.Where("day >= ? AND day <= ?", oldest, newest).Find(&prompts).Error; err != nil {
		return nil, err
	}
	promptByDay := make(map[string]models.DailyPrompt, len(prompts))
	for _, row := range prompts {
		promptByDay[row.Day] = row
	}

	var photos []models.Photo
	if err := s.DB.Where("day >= ? AND day <= ?", oldest, newest).Find(&photos).Error; err != nil {
		return nil, err
	}
	type dayStats struct {
		PostedUsers map[uint]struct{}
		PromptUsers map[uint]struct{}
		PhotoCount  int
	}
	statsByDay := make(map[string]*dayStats, 16)
	getStats := func(day string) *dayStats {
		if entry, ok := statsByDay[day]; ok {
			return entry
		}
		created := &dayStats{
			PostedUsers: make(map[uint]struct{}),
			PromptUsers: make(map[uint]struct{}),
		}
		statsByDay[day] = created
		return created
	}
	for _, photo := range photos {
		stats := getStats(photo.Day)
		stats.PhotoCount++
		stats.PostedUsers[photo.UserID] = struct{}{}
		if prompt, ok := promptByDay[photo.Day]; ok && prompt.TriggeredAt != nil && prompt.UploadUntil != nil &&
			!photo.CreatedAt.Before(*prompt.TriggeredAt) && !photo.CreatedAt.After(*prompt.UploadUntil) {
			stats.PromptUsers[photo.UserID] = struct{}{}
		}
	}

	triggerCounts := make(map[string]gin.H, 16)
	for _, row := range triggerRows {
		dayKey := strings.TrimSpace(row.Day)
		if dayKey == "" {
			dayKey = row.OccurredAt.In(s.Location).Format("2006-01-02")
		}
		existing, ok := triggerCounts[dayKey]
		if !ok {
			existing = gin.H{
				"triggerAttemptCount": 0,
				"triggerBlockedCount": 0,
				"triggerFailedCount":  0,
			}
		}
		existing["triggerAttemptCount"] = asInt64(existing["triggerAttemptCount"]) + 1
		switch strings.ToLower(strings.TrimSpace(row.Result)) {
		case "blocked":
			existing["triggerBlockedCount"] = asInt64(existing["triggerBlockedCount"]) + 1
		case "failed":
			existing["triggerFailedCount"] = asInt64(existing["triggerFailedCount"]) + 1
		}
		triggerCounts[dayKey] = existing
	}

	dayCursor, _ := time.ParseInLocation("2006-01-02", oldest, s.Location)
	endDay, _ := time.ParseInLocation("2006-01-02", newest, s.Location)
	items := make([]gin.H, 0, 8)
	for !dayCursor.After(endDay) {
		dayKey := dayCursor.Format("2006-01-02")
		stats := getStats(dayKey)
		trigger := triggerCounts[dayKey]
		if trigger == nil {
			trigger = gin.H{
				"triggerAttemptCount": 0,
				"triggerBlockedCount": 0,
				"triggerFailedCount":  0,
			}
		}
		items = append(items, gin.H{
			"day":                   dayKey,
			"photoCount":            stats.PhotoCount,
			"postedUsersCount":      len(stats.PostedUsers),
			"dailyMomentUsersCount": len(stats.PromptUsers),
			"triggerAttemptCount":   trigger["triggerAttemptCount"],
			"triggerBlockedCount":   trigger["triggerBlockedCount"],
			"triggerFailedCount":    trigger["triggerFailedCount"],
			"multipleTriggerAlert":  asInt64(trigger["triggerAttemptCount"]) > 1,
		})
		dayCursor = dayCursor.AddDate(0, 0, 1)
	}
	sort.Slice(items, func(i, j int) bool {
		return fmt.Sprint(items[i]["day"]) > fmt.Sprint(items[j]["day"])
	})
	return items, nil
}
