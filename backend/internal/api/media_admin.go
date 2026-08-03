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
	c.JSON(http.StatusOK, gin.H{
		"runtimeAvailable": s.Config.MediaAVIFEnabled,
		"avifEnabled":      s.mediaAVIFEnabled(),
		"operatorDisabled": settings.MediaAVIFDisabled,
		"autoPaused":       settings.MediaAVIFAutoPaused, "autoPauseReason": settings.MediaAVIFAutoPauseReason,
		"renditions":        s.mediaDerivativeStats(),
		"recentConversions": items,
		"viewerPreferences": prefs,
		"serverNow":         time.Now().UTC(),
	})
}

func (s *Server) handleAdminMediaRenditionsUpdate(c *gin.Context) {
	var req struct {
		AVIFEnabled *bool `json:"avifEnabled"`
	}
	if err := c.ShouldBindJSON(&req); err != nil || req.AVIFEnabled == nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "avifEnabled is required"})
		return
	}
	if *req.AVIFEnabled && !s.Config.MediaAVIFEnabled {
		c.JSON(http.StatusConflict, gin.H{"error": "AVIF runtime capability is disabled; set MEDIA_AVIF_ENABLED=true and restart first"})
		return
	}
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	settings.MediaAVIFDisabled = !*req.AVIFEnabled
	if *req.AVIFEnabled {
		settings.MediaAVIFAutoPaused = false
		settings.MediaAVIFAutoPauseReason = ""
	}
	if err := s.DB.Save(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	if *req.AVIFEnabled {
		_ = s.enqueueRecentMediaDerivativeBackfill()
	}
	c.JSON(http.StatusOK, gin.H{"runtimeAvailable": s.Config.MediaAVIFEnabled, "avifEnabled": s.mediaAVIFEnabled(), "operatorDisabled": settings.MediaAVIFDisabled, "autoPaused": settings.MediaAVIFAutoPaused, "autoPauseReason": settings.MediaAVIFAutoPauseReason})
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
