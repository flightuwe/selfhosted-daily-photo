package api

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

func (s *Server) handleAdminTriggerRuntime(c *gin.Context) {
	now := time.Now().In(s.Location)
	state := s.Prompt.RuntimeState(now)

	windowMinutes := 60
	if raw := strings.TrimSpace(c.Query("windowMinutes")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil && n >= 5 && n <= 240 {
			windowMinutes = n
		}
	}
	from := now.Add(-time.Duration(windowMinutes) * time.Minute)
	dailyFilter := "source NOT IN ?"
	specialSources := []string{"special_request", "chat_command"}
	var attempts int64
	var blocked int64
	var failed int64
	var dbLocked int64
	var trueDuplicateTriggers int64
	var leaseUnavailable int64
	_ = s.DB.Model(&models.DailyTriggerAuditEvent{}).Where("occurred_at >= ?", from).Where(dailyFilter, specialSources).Count(&attempts).Error
	_ = s.DB.Model(&models.DailyTriggerAuditEvent{}).Where("occurred_at >= ? AND result = ?", from, "blocked").Where(dailyFilter, specialSources).Count(&blocked).Error
	_ = s.DB.Model(&models.DailyTriggerAuditEvent{}).Where("occurred_at >= ? AND result = ?", from, "failed").Where(dailyFilter, specialSources).Count(&failed).Error
	_ = s.DB.Model(&models.DailyTriggerAuditEvent{}).Where("occurred_at >= ? AND reason = ?", from, "db_locked").Where(dailyFilter, specialSources).Count(&dbLocked).Error
	_ = s.DB.Model(&models.DailyTriggerAuditEvent{}).Where("day = ? AND result = ?", now.Format("2006-01-02"), "triggered").Where(dailyFilter, specialSources).Count(&trueDuplicateTriggers).Error
	if trueDuplicateTriggers > 0 {
		trueDuplicateTriggers--
	}
	_ = s.DB.Model(&models.DailyTriggerAuditEvent{}).Where("occurred_at >= ? AND reason IN ?", from, []string{"lease_owned", "lease_expired"}).Where(dailyFilter, specialSources).Count(&leaseUnavailable).Error

	reasonCounts := map[string]int64{}
	type reasonRow struct {
		Reason string
		Count  int64
	}
	var grouped []reasonRow
	_ = s.DB.Model(&models.DailyTriggerAuditEvent{}).
		Select("reason, count(*) as count").
		Where("occurred_at >= ?", from).
		Where(dailyFilter, specialSources).
		Group("reason").
		Scan(&grouped).Error
	for _, row := range grouped {
		reason := strings.TrimSpace(strings.ToLower(row.Reason))
		if reason == "" {
			reason = "unknown"
		}
		reasonCounts[reason] = row.Count
	}
	blockRate := 0.0
	if attempts > 0 {
		blockRate = float64(blocked) / float64(attempts)
	}
	reasonRates := map[string]float64{}
	if attempts > 0 {
		for reason, count := range reasonCounts {
			reasonRates[reason] = float64(count) / float64(attempts)
		}
	}

	const (
		sloDuplicateTodayThreshold = int64(1)
		sloBlockRateThreshold      = 0.35
		sloDBLockThreshold         = int64(1)
	)
	violations := make([]gin.H, 0, 4)
	if trueDuplicateTriggers >= sloDuplicateTodayThreshold {
		violations = append(violations, gin.H{
			"id":        "duplicate_trigger_attempts_per_day",
			"severity":  "high",
			"threshold": sloDuplicateTodayThreshold,
			"observed":  trueDuplicateTriggers,
			"unit":      "count",
		})
	}
	if leaseUnavailable > 0 {
		violations = append(violations, gin.H{"id": "lease_unavailable_for_due_prompt", "severity": "high", "threshold": int64(1), "observed": leaseUnavailable, "unit": "count"})
	}
	if blockRate >= sloBlockRateThreshold && attempts >= 3 {
		violations = append(violations, gin.H{
			"id":        "trigger_block_rate",
			"severity":  "medium",
			"threshold": sloBlockRateThreshold,
			"observed":  blockRate,
			"unit":      "ratio",
		})
	}
	if dbLocked >= sloDBLockThreshold {
		violations = append(violations, gin.H{
			"id":        "db_locked_trigger_failures",
			"severity":  "high",
			"threshold": sloDBLockThreshold,
			"observed":  dbLocked,
			"unit":      "count",
		})
	}
	sloStatus := "ok"
	if len(violations) > 0 {
		sloStatus = "breach"
	}

	var lastDispatch models.DailyDispatch
	lastDispatchState := gin.H{}
	if err := s.DB.Order("created_at desc").Limit(1).First(&lastDispatch).Error; err == nil {
		lastDispatchState = gin.H{
			"day":            lastDispatch.Day,
			"kind":           lastDispatch.Kind,
			"source":         lastDispatch.Source,
			"status":         lastDispatch.Status,
			"sentCount":      lastDispatch.SentCount,
			"failedCount":    lastDispatch.FailedCount,
			"errorMessage":   lastDispatch.ErrorMessage,
			"serverInstance": lastDispatch.ServerInstance,
			"updatedAt":      lastDispatch.UpdatedAt,
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"serverNow":     now,
		"windowMinutes": windowMinutes,
		"runtime":       state,
		"recent": gin.H{
			"attempts":         attempts,
			"blocked":          blocked,
			"failed":           failed,
			"dbLocked":         dbLocked,
			"duplicateToday":   trueDuplicateTriggers,
			"leaseUnavailable": leaseUnavailable,
			"blockRate":        blockRate,
			"byReason":         reasonCounts,
			"byReasonRate":     reasonRates,
		},
		"slo": gin.H{
			"evaluatedAt":   now,
			"windowMinutes": windowMinutes,
			"status":        sloStatus,
			"thresholds": gin.H{
				"duplicateToday": sloDuplicateTodayThreshold,
				"blockRate":      sloBlockRateThreshold,
				"dbLocked":       sloDBLockThreshold,
			},
			"violations": violations,
		},
		"dispatch": gin.H{
			"kind": s.Prompt.DispatchKindDailyPromptPush(),
			"last": lastDispatchState,
		},
	})
}

func (s *Server) handleAdminTriggerRuntimeUpdate(c *gin.Context) {
	var req struct {
		Action string `json:"action"`
		Reason string `json:"reason"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	action := strings.TrimSpace(strings.ToLower(req.Action))
	switch action {
	case "pause":
		if err := s.Prompt.SetAutoPaused(strings.TrimSpace(req.Reason)); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "pause failed"})
			return
		}
	case "unpause":
		if err := s.Prompt.ClearAutoPaused(); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "unpause failed"})
			return
		}
	case "release_lease":
		if err := s.Prompt.ReleaseLease(); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "lease release failed"})
			return
		}
	default:
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid action"})
		return
	}

	s.handleAdminTriggerRuntime(c)
}
