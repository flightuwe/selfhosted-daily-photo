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
		items = append(items, gin.H{
			"id": row.ID, "sourcePath": row.SourcePath, "variant": row.Variant, "purpose": row.Purpose,
			"format": row.Format, "width": row.Width, "status": row.Status, "byteSize": row.ByteSize,
			"attempts": row.Attempts, "lastError": row.LastError, "createdAt": row.CreatedAt,
			"updatedAt": row.UpdatedAt, "completedAt": row.CompletedAt,
		})
	}
	prefs := make([]gin.H, 0, len(preferences))
	for _, row := range preferences {
		prefs = append(prefs, gin.H{"preference": row.Preference, "users": row.Users})
	}
	c.JSON(http.StatusOK, gin.H{
		"runtimeAvailable":  s.Config.MediaAVIFEnabled,
		"avifEnabled":       s.mediaAVIFEnabled(),
		"operatorDisabled":  settings.MediaAVIFDisabled,
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
	if err := s.DB.Save(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	if *req.AVIFEnabled {
		_ = s.enqueueRecentMediaDerivativeBackfill()
	}
	c.JSON(http.StatusOK, gin.H{"runtimeAvailable": s.Config.MediaAVIFEnabled, "avifEnabled": s.mediaAVIFEnabled(), "operatorDisabled": settings.MediaAVIFDisabled})
}

func normalizeMediaFormatLabel(value string) string { return strings.ToLower(strings.TrimSpace(value)) }
