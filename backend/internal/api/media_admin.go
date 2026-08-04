package api

import (
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

// mediaAVIFEnabled requires both the deployment capability and the durable
// operator switch. A database lookup failure fails closed for AVIF delivery.
func (s *Server) mediaAVIFEnabled() bool {
	if s == nil || s.DB == nil || !s.Config.MediaAVIFEnabled {
		return false
	}
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		return false
	}
	return !settings.MediaAVIFDisabled
}

// Three terminal encoder failures in one hour pause only AVIF. WebP, JPEG and
// originals remain available; an admin can explicitly resume after inspection.
func (s *Server) autoPauseAVIFOnFailures() error {
	cutoff := time.Now().UTC().Add(-time.Hour)
	var failures int64
	if err := s.DB.Model(&models.MediaDerivative{}).Where("format = ? AND status = ? AND updated_at >= ?", "avif", "failed", cutoff).Count(&failures).Error; err != nil || failures < 3 {
		return err
	}
	return s.DB.Model(&models.AppSettings{}).Where("media_avif_disabled = ?", false).Updates(map[string]any{
		"media_avif_disabled": true, "media_avif_auto_paused": true,
		"media_avif_auto_pause_reason": "Automatisch pausiert: mindestens 3 terminale AVIF-Encoderfehler innerhalb einer Stunde.",
	}).Error
}

func (s *Server) handleAdminMediaRenditions(c *gin.Context) {
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	var recent []models.MediaDerivative
	_ = s.DB.Order("updated_at DESC").Limit(80).Find(&recent).Error
	var preferences []struct {
		Preference string
		Users      int64
	}
	_ = s.DB.Model(&models.User{}).
		Select("COALESCE(NULLIF(media_format_preference, ''), 'auto') AS preference, COUNT(*) AS users").
		Group("preference").Order("users DESC").Scan(&preferences).Error
	items := make([]gin.H, 0, len(recent))
	for _, row := range recent {
		photo, found := s.photoForDerivativeSource(row.SourcePath)
		items = append(items, gin.H{
			"id": row.ID, "sourcePath": row.SourcePath, "variant": row.Variant, "purpose": row.Purpose,
			"format": row.Format, "width": row.Width, "status": row.Status, "byteSize": row.ByteSize,
			"attempts": row.Attempts, "lastError": row.LastError, "createdAt": row.CreatedAt,
			"updatedAt": row.UpdatedAt, "completedAt": row.CompletedAt, "lastRequestedAt": row.LastRequestedAt,
			"photoId": photo.ID, "day": photo.Day, "postCreatedAt": photo.CreatedAt,
			"userId": photo.UserID, "username": photo.User.Username, "postFound": found,
		})
	}
	prefs := make([]gin.H, 0, len(preferences))
	for _, row := range preferences {
		prefs = append(prefs, gin.H{"preference": row.Preference, "users": row.Users})
	}
	queueTelemetry := s.mediaDerivativeQueueTelemetry(time.Now())
	c.JSON(http.StatusOK, gin.H{
		"runtimeAvailable": s.Config.MediaAVIFEnabled,
		"avifEnabled":      s.mediaAVIFEnabled(),
		"operatorDisabled": settings.MediaAVIFDisabled,
		"backgroundPaused": settings.MediaDerivativeBackgroundPaused,
		"backgroundPolicy": s.backgroundDerivativePolicy(time.Now()),
		"autoPaused":       settings.MediaAVIFAutoPaused, "autoPauseReason": settings.MediaAVIFAutoPauseReason,
		"renditions":        s.mediaDerivativeStats(),
		"queueTelemetry":    queueTelemetry,
		"recentConversions": items,
		"viewerPreferences": prefs,
		"serverNow":         time.Now().UTC(),
	})
}

func (s *Server) mediaDerivativeQueueTelemetry(now time.Time) gin.H {
	telemetry := gin.H{"pending": int64(0), "paused": int64(0), "running": gin.H{}, "lastCompleted": gin.H{}, "throughput": gin.H{}, "eta": gin.H{}}
	var pending, paused int64
	_ = s.DB.Model(&models.MediaDerivative{}).Where("status = ?", mediaDerivativeQueued).Count(&pending).Error
	_ = s.DB.Model(&models.MediaDerivative{}).Where("status = ?", mediaDerivativePaused).Count(&paused).Error
	telemetry["pending"] = pending
	telemetry["paused"] = paused
	var running models.MediaDerivative
	if err := s.DB.Where("status = ?", mediaDerivativeRunning).Order("started_at asc, id asc").First(&running).Error; err == nil {
		runningJSON := gin.H{"id": running.ID, "sourcePath": running.SourcePath, "variant": running.Variant, "format": running.Format, "startedAt": running.StartedAt}
		if running.StartedAt != nil {
			runningJSON["ageSeconds"] = int64(now.Sub(*running.StartedAt).Seconds())
		}
		telemetry["running"] = runningJSON
	}
	var last models.MediaDerivative
	if err := s.DB.Where("status = ? AND completed_at IS NOT NULL", mediaDerivativeReady).Order("completed_at desc").First(&last).Error; err == nil {
		lastJSON := gin.H{"id": last.ID, "sourcePath": last.SourcePath, "variant": last.Variant, "format": last.Format, "completedAt": last.CompletedAt, "byteSize": last.ByteSize}
		if last.StartedAt != nil && last.CompletedAt != nil {
			lastJSON["durationMs"] = last.CompletedAt.Sub(*last.StartedAt).Milliseconds()
		}
		telemetry["lastCompleted"] = lastJSON
	}
	var completedHour, completedDay int64
	_ = s.DB.Model(&models.MediaDerivative{}).Where("status = ? AND completed_at >= ?", mediaDerivativeReady, now.Add(-time.Hour)).Count(&completedHour).Error
	_ = s.DB.Model(&models.MediaDerivative{}).Where("status = ? AND completed_at >= ?", mediaDerivativeReady, now.Add(-24*time.Hour)).Count(&completedDay).Error
	telemetry["throughput"] = gin.H{"completedLastHour": completedHour, "completedLast24Hours": completedDay}
	// One worker ticks every five seconds. At the configured five-hour night
	// window this is 3,600 background jobs/night; daytime idle slots add up to
	// 216 more jobs/day, but are deliberately excluded from the conservative ETA.
	nightlyCapacity := int64(5 * 60 * 60 / 5)
	nights := int64(0)
	if paused > 0 {
		nights = (paused + nightlyCapacity - 1) / nightlyCapacity
	}
	telemetry["eta"] = gin.H{"policyCapacityPerNight": nightlyCapacity, "conservativeNights": nights, "daytimeCapacityMax": int64(18 * 60 / 5), "basis": "night window only; daytime idle work shortens this estimate"}
	return telemetry
}

func (s *Server) handleAdminMediaRenditionsUpdate(c *gin.Context) {
	var req struct {
		AVIFEnabled      *bool `json:"avifEnabled"`
		BackgroundPaused *bool `json:"backgroundPaused"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || (req.AVIFEnabled == nil && req.BackgroundPaused == nil) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "avifEnabled or backgroundPaused is required"})
		return
	}
	if req.AVIFEnabled != nil && *req.AVIFEnabled && !s.Config.MediaAVIFEnabled {
		c.JSON(http.StatusConflict, gin.H{"error": "AVIF runtime capability is disabled; set MEDIA_AVIF_ENABLED=true and restart first"})
		return
	}
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	if req.AVIFEnabled != nil {
		settings.MediaAVIFDisabled = !*req.AVIFEnabled
	}
	if req.AVIFEnabled != nil && *req.AVIFEnabled {
		settings.MediaAVIFAutoPaused = false
		settings.MediaAVIFAutoPauseReason = ""
	}
	if req.BackgroundPaused != nil {
		settings.MediaDerivativeBackgroundPaused = *req.BackgroundPaused
	}
	if err := s.DB.Save(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	if req.AVIFEnabled != nil && *req.AVIFEnabled {
		_ = s.enqueueRecentMediaDerivativeBackfill()
	}
	c.JSON(http.StatusOK, gin.H{"runtimeAvailable": s.Config.MediaAVIFEnabled, "avifEnabled": s.mediaAVIFEnabled(), "operatorDisabled": settings.MediaAVIFDisabled, "backgroundPaused": settings.MediaDerivativeBackgroundPaused, "backgroundPolicy": s.backgroundDerivativePolicy(time.Now()), "autoPaused": settings.MediaAVIFAutoPaused, "autoPauseReason": settings.MediaAVIFAutoPauseReason})
}

func (s *Server) photoForDerivativeSource(source string) (models.Photo, bool) {
	var photo models.Photo
	err := s.DB.Preload("User").Where("file_path = ? OR second_path = ?", source, source).First(&photo).Error
	if err != nil {
		err = s.DB.Preload("User").Joins("JOIN photo_attachments ON photo_attachments.photo_id = photos.id").Where("photo_attachments.file_path = ?", source).First(&photo).Error
	}
	return photo, err == nil
}

func normalizeMediaFormatLabel(value string) string { return strings.ToLower(strings.TrimSpace(value)) }
