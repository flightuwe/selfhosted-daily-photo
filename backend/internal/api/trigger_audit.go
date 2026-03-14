package api

import (
	"bytes"
	"encoding/csv"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

func (s *Server) handleAdminTriggerAudit(c *gin.Context) {
	limit := 200
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > 2000 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid limit"})
			return
		}
		limit = n
	}

	rows, from, to, err := s.loadTriggerAuditRows(c, limit)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "invalid") || strings.Contains(strings.ToLower(err.Error()), "range") || strings.Contains(strings.ToLower(err.Error()), "from must be before to") {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "trigger audit query failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"items": rows,
		"from":  from,
		"to":    to,
		"count": len(rows),
		"limit": limit,
	})
}

func (s *Server) handleAdminTriggerAuditSummary(c *gin.Context) {
	days := 7
	if raw := strings.TrimSpace(c.Query("days")); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > 120 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid days"})
			return
		}
		days = n
	}

	now := time.Now().In(s.Location)
	from := now.AddDate(0, 0, -(days - 1))
	from = time.Date(from.Year(), from.Month(), from.Day(), 0, 0, 0, 0, s.Location)
	to := now

	var rows []models.DailyTriggerAuditEvent
	if err := s.DB.
		Where("occurred_at >= ? AND occurred_at <= ?", from, to).
		Order("occurred_at desc").
		Limit(40000).
		Find(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "trigger audit summary query failed"})
		return
	}

	type dayAgg struct {
		Attempts int `json:"attempts"`
		Triggered int `json:"triggered"`
		Blocked int `json:"blocked"`
		Failed int `json:"failed"`
		DBLock int `json:"dbLocked"`
	}
	byDay := make(map[string]*dayAgg, days)
	bySource := make(map[string]int, 12)
	attempts := 0
	triggered := 0
	blocked := 0
	failed := 0
	dbLocked := 0
	for _, row := range rows {
		dayKey := strings.TrimSpace(row.Day)
		if dayKey == "" {
			dayKey = row.OccurredAt.In(s.Location).Format("2006-01-02")
		}
		entry := byDay[dayKey]
		if entry == nil {
			entry = &dayAgg{}
			byDay[dayKey] = entry
		}
		entry.Attempts++
		attempts++
		result := strings.ToLower(strings.TrimSpace(row.Result))
		switch result {
		case "triggered":
			entry.Triggered++
			triggered++
		case "blocked":
			entry.Blocked++
			blocked++
		default:
			entry.Failed++
			failed++
		}
		if strings.EqualFold(strings.TrimSpace(row.Reason), "db_locked") {
			entry.DBLock++
			dbLocked++
		}
		source := strings.TrimSpace(row.Source)
		if source == "" {
			source = "unknown"
		}
		bySource[source]++
	}

	dayRows := make([]gin.H, 0, len(byDay))
	duplicateAttempts := 0
	multipleAttemptDays := 0
	for dayKey, agg := range byDay {
		if agg.Attempts > 1 {
			multipleAttemptDays++
			duplicateAttempts += agg.Attempts - 1
		}
		dayRows = append(dayRows, gin.H{
			"day":       dayKey,
			"attempts":  agg.Attempts,
			"triggered": agg.Triggered,
			"blocked":   agg.Blocked,
			"failed":    agg.Failed,
			"dbLocked":  agg.DBLock,
		})
	}
	sort.Slice(dayRows, func(i, j int) bool {
		return fmt.Sprint(dayRows[i]["day"]) > fmt.Sprint(dayRows[j]["day"])
	})

	sourceRows := make([]gin.H, 0, len(bySource))
	for source, count := range bySource {
		sourceRows = append(sourceRows, gin.H{
			"source": source,
			"count":  count,
		})
	}
	sort.Slice(sourceRows, func(i, j int) bool {
		return asInt64(sourceRows[i]["count"]) > asInt64(sourceRows[j]["count"])
	})

	c.JSON(http.StatusOK, gin.H{
		"days":  days,
		"from":  from,
		"to":    to,
		"summary": gin.H{
			"attempts":            attempts,
			"triggered":           triggered,
			"blocked":             blocked,
			"failed":              failed,
			"dbLocked":            dbLocked,
			"duplicateAttempts":   duplicateAttempts,
			"multipleAttemptDays": multipleAttemptDays,
			"blockedRate":         safeRatio(blocked, maxInt(1, attempts)),
			"failedRate":          safeRatio(failed, maxInt(1, attempts)),
		},
		"byDay":    dayRows,
		"bySource": sourceRows,
	})
}

func (s *Server) handleAdminTriggerAuditExport(c *gin.Context) {
	format := strings.ToLower(strings.TrimSpace(c.DefaultQuery("format", "json")))
	if format != "json" && format != "csv" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid format"})
		return
	}

	rows, from, to, err := s.loadTriggerAuditRows(c, 120000)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "invalid") || strings.Contains(strings.ToLower(err.Error()), "range") || strings.Contains(strings.ToLower(err.Error()), "from must be before to") {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "trigger audit export query failed"})
		return
	}

	ts := time.Now().In(s.Location).Format("20060102-150405")
	if format == "json" {
		filename := fmt.Sprintf("trigger-audit-%s.json", ts)
		c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", filename))
		c.Header("Content-Type", "application/json; charset=utf-8")
		c.JSON(http.StatusOK, gin.H{
			"schemaVersion": "trigger_audit_v1",
			"generatedAt":   time.Now().In(s.Location),
			"from":          from,
			"to":            to,
			"count":         len(rows),
			"items":         rows,
		})
		return
	}

	filename := fmt.Sprintf("trigger-audit-%s.csv", ts)
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", filename))
	c.Header("Content-Type", "text/csv; charset=utf-8")
	var buf bytes.Buffer
	buf.Write([]byte{0xEF, 0xBB, 0xBF})
	writer := csv.NewWriter(&buf)
	_ = writer.Write([]string{
		"id", "day", "occurred_at", "request_id", "source", "attempt_type", "result", "reason",
		"actor_user_id", "actor_username", "before_triggered_at", "after_triggered_at",
		"before_trigger_source", "after_trigger_source", "server_instance", "error_message", "meta_json",
	})
	for _, row := range rows {
		actorUserID := ""
		if row.ActorUserID != nil {
			actorUserID = strconv.FormatUint(uint64(*row.ActorUserID), 10)
		}
		beforeTriggeredAt := ""
		if row.BeforeTriggeredAt != nil {
			beforeTriggeredAt = row.BeforeTriggeredAt.In(s.Location).Format(time.RFC3339)
		}
		afterTriggeredAt := ""
		if row.AfterTriggeredAt != nil {
			afterTriggeredAt = row.AfterTriggeredAt.In(s.Location).Format(time.RFC3339)
		}
		_ = writer.Write([]string{
			strconv.FormatUint(uint64(row.ID), 10),
			row.Day,
			row.OccurredAt.In(s.Location).Format(time.RFC3339),
			row.RequestID,
			row.Source,
			row.AttemptType,
			row.Result,
			row.Reason,
			actorUserID,
			row.ActorUsername,
			beforeTriggeredAt,
			afterTriggeredAt,
			row.BeforeTriggerSource,
			row.AfterTriggerSource,
			row.ServerInstance,
			row.ErrorMessage,
			row.MetaJSON,
		})
	}
	writer.Flush()
	if err := writer.Error(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "csv export failed"})
		return
	}
	c.Data(http.StatusOK, "text/csv; charset=utf-8", buf.Bytes())
}

func (s *Server) loadTriggerAuditRows(c *gin.Context, limit int) ([]models.DailyTriggerAuditEvent, time.Time, time.Time, error) {
	if limit < 1 {
		limit = 1
	}
	if limit > 120000 {
		limit = 120000
	}

	now := time.Now().In(s.Location)
	from := now.Add(-24 * time.Hour)
	to := now

	dayFilter := strings.TrimSpace(c.Query("day"))
	if dayFilter != "" {
		dayStart, parseErr := time.ParseInLocation("2006-01-02", dayFilter, s.Location)
		if parseErr != nil {
			return nil, time.Time{}, time.Time{}, parseErr
		}
		from = dayStart
		to = dayStart.Add(24*time.Hour - time.Nanosecond)
	}
	if raw := strings.TrimSpace(c.Query("from")); raw != "" {
		parsed, parseErr := parseTimeQuery(raw, s.Location)
		if parseErr != nil {
			return nil, time.Time{}, time.Time{}, parseErr
		}
		from = parsed
	}
	if raw := strings.TrimSpace(c.Query("to")); raw != "" {
		parsed, parseErr := parseTimeQuery(raw, s.Location)
		if parseErr != nil {
			return nil, time.Time{}, time.Time{}, parseErr
		}
		to = parsed
	}
	if !from.Before(to) {
		return nil, time.Time{}, time.Time{}, fmt.Errorf("from must be before to")
	}
	if to.Sub(from) > 60*24*time.Hour {
		return nil, time.Time{}, time.Time{}, fmt.Errorf("range too large")
	}

	query := s.DB.Model(&models.DailyTriggerAuditEvent{}).
		Where("occurred_at >= ? AND occurred_at <= ?", from, to)
	if dayFilter != "" {
		query = query.Where("day = ?", dayFilter)
	}
	if source := strings.TrimSpace(c.Query("source")); source != "" {
		query = query.Where("source = ?", source)
	}
	if result := strings.TrimSpace(c.Query("result")); result != "" {
		query = query.Where("result = ?", result)
	}
	if actor := strings.TrimSpace(c.Query("actorUserId")); actor != "" {
		if actorID, err := strconv.ParseUint(actor, 10, 64); err == nil && actorID > 0 {
			query = query.Where("actor_user_id = ?", actorID)
		}
	}
	if requestID := strings.TrimSpace(c.Query("requestId")); requestID != "" {
		query = query.Where("request_id = ?", requestID)
	}

	var rows []models.DailyTriggerAuditEvent
	err := query.Order("occurred_at desc").Limit(limit).Find(&rows).Error
	return rows, from, to, err
}
