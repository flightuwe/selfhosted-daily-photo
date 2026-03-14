package api

import (
	"bytes"
	"crypto/rand"
	"encoding/csv"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	_ "image/png"
	"io"
	"math"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-contrib/cors"
	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/auth"
	"github.com/yosho/selfhosted-bereal/backend/internal/config"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"github.com/yosho/selfhosted-bereal/backend/internal/notify"
	"github.com/yosho/selfhosted-bereal/backend/internal/scheduler"
	"github.com/yosho/selfhosted-bereal/backend/internal/storage"
	"gorm.io/gorm"
)

type userPromptRule struct {
	ID            string `json:"id"`
	Enabled       bool   `json:"enabled"`
	TriggerType   string `json:"triggerType"`
	Title         string `json:"title"`
	Body          string `json:"body"`
	ConfirmLabel  string `json:"confirmLabel"`
	DeclineLabel  string `json:"declineLabel"`
	CooldownHours int    `json:"cooldownHours"`
	Priority      int    `json:"priority"`
}

type Server struct {
	DB       *gorm.DB
	Config   config.Config
	Auth     *auth.Manager
	Store    *storage.LocalStore
	Notifier notify.Sender
	Prompt   *scheduler.DailyPromptService
	Location *time.Location
	Monitor  *Monitor
	FeedCache   *FeedDayCache
	FeedLimiter *FeedPollLimiter
}

func (s *Server) Router() *gin.Engine {
	r := gin.Default()
	r.Use(s.requestIDMiddleware(), s.metricsMiddleware())
	r.Use(cors.New(cors.Config{
		AllowOrigins:     s.Config.AllowedOrigins,
		AllowMethods:     []string{"GET", "POST", "PUT", "DELETE"},
		AllowHeaders:     []string{"Authorization", "Content-Type", "X-Request-ID"},
		ExposeHeaders:    []string{"Content-Length", "X-Request-ID"},
		AllowCredentials: true,
		MaxAge:           12 * time.Hour,
	}))

	r.Static("/uploads", s.Config.UploadDir)

	api := r.Group("/api")
	{
		api.GET("/health", s.handleHealth)
		api.GET("/health/live", s.handleLiveHealth)
		api.GET("/health/ready", s.handleReadyHealth)
		api.GET("/metrics", s.handleMetrics)
		api.POST("/auth/register", s.handleRegister)
		api.POST("/auth/register/preview", s.handleInvitePreview)
		api.POST("/auth/register/confirm", s.handleInviteRegister)
		api.POST("/auth/login", s.handleLogin)

		protected := api.Group("")
		protected.Use(s.requireAuth)
		{
			protected.GET("/me", s.handleMe)
			protected.GET("/me/user-prompts/evaluate", s.handleEvaluateUserPrompts)
			protected.GET("/users/:id/profile", s.handleUserProfile)
			protected.POST("/debug/client-log", s.handleClientDebugLog)
			protected.GET("/me/invite", s.handleMyInvite)
			protected.POST("/me/invite/roll", s.handleRollMyInvite)
			protected.PUT("/me/profile", s.handleUpdateProfile)
			protected.POST("/me/avatar", s.handleUploadAvatar)
			protected.PUT("/me/preferences", s.handleUpdatePreferences)
			protected.PUT("/me/password", s.handleChangePassword)
			protected.GET("/me/photos", s.handleMyPhotos)
			protected.DELETE("/me/photos/:id", s.handleDeleteMyPhoto)
			protected.POST("/devices", s.handleDevice)
			protected.GET("/prompt/current", s.handleCurrentPrompt)
			protected.GET("/prompt/rules", s.handlePromptRules)
			protected.GET("/moment/special/status", s.handleSpecialMomentStatus)
			protected.POST("/moment/special/request", s.handleSpecialMomentRequest)
			protected.POST("/uploads", s.handleUpload)
			protected.POST("/uploads/dual", s.handleDualUpload)
			protected.GET("/feed", s.handleFeed)
			protected.GET("/feed/days", s.handleFeedDays)
			protected.GET("/feed/day-stats", s.handleFeedDayStats)
			protected.GET("/community/stats", s.handleCommunityStats)
			protected.GET("/chat", s.handleChatList)
			protected.POST("/chat", s.handleChatCreate)
			protected.DELETE("/chat/:id", s.handleDeleteChatMessage)
			protected.GET("/photos/:id/interactions", s.handlePhotoInteractions)
			protected.POST("/photos/:id/reaction", s.handlePhotoReaction)
			protected.POST("/photos/:id/comments", s.handlePhotoComment)
		}

		admin := api.Group("/admin")
		admin.Use(s.requireAuth, s.requireAdmin)
		{
			admin.GET("/settings", s.handleGetSettings)
			admin.PUT("/settings", s.handleUpdateSettings)
			admin.GET("/stats", s.handleAdminStats)
			admin.GET("/feed", s.handleAdminFeed)
			admin.GET("/calendar", s.handleAdminCalendar)
			admin.GET("/history", s.handleAdminHistory)
			admin.GET("/search", s.handleAdminSearch)
			admin.GET("/time-capsules", s.handleAdminTimeCapsules)
			admin.PUT("/calendar/:day", s.handleAdminCalendarDay)

			admin.POST("/prompt/trigger", s.handleTriggerPrompt)
			admin.POST("/prompt/reset-today", s.handleAdminResetToday)
			admin.POST("/notifications/broadcast", s.handleBroadcastNotification)
			admin.POST("/notifications/user/:id", s.handleUserNotification)
			admin.POST("/chat/clear", s.handleAdminClearChat)
			admin.GET("/chat/commands", s.handleAdminListChatCommands)
			admin.POST("/chat/commands", s.handleAdminCreateChatCommand)
			admin.PUT("/chat/commands/:id", s.handleAdminUpdateChatCommand)
			admin.DELETE("/chat/commands/:id", s.handleAdminDeleteChatCommand)
			admin.GET("/reports", s.handleAdminListReports)
			admin.PUT("/reports/:id", s.handleAdminUpdateReport)
			admin.DELETE("/reports/:id", s.handleAdminDeleteReport)
			admin.DELETE("/reports", s.handleAdminDeleteReports)
			admin.GET("/debug/logs", s.handleAdminDebugLogs)
			admin.DELETE("/debug/logs", s.handleAdminDeleteDebugLogs)
			admin.GET("/debug/logs/export", s.handleAdminDebugLogsExport)
			admin.GET("/system/health", s.handleAdminSystemHealth)
			admin.GET("/performance/overview", s.handleAdminPerformanceOverview)
			admin.GET("/performance/routes", s.handleAdminPerformanceRoutes)
			admin.GET("/performance/spikes", s.handleAdminPerformanceSpikes)
			admin.GET("/performance/slo", s.handleAdminPerformanceSLO)
			admin.GET("/performance/export", s.handleAdminPerformanceExport)
			admin.GET("/performance/tracking", s.handleAdminPerformanceTracking)
			admin.PUT("/performance/tracking", s.handleAdminPerformanceTrackingUpdate)
			admin.GET("/performance/tracking/export", s.handleAdminPerformanceTrackingExport)

			admin.GET("/users", s.handleAdminListUsers)
			admin.POST("/users", s.handleAdminCreateUser)
			admin.POST("/users/:id/token", s.handleAdminIssueUserToken)
			admin.PUT("/users/:id", s.handleAdminUpdateUser)
			admin.DELETE("/users/:id", s.handleAdminDeleteUser)
		}
	}

	return r
}

type authRequest struct {
	Username string `json:"username" binding:"required,min=3,max=64"`
	Password string `json:"password" binding:"required,min=6,max=128"`
}

type invitePreviewRequest struct {
	InviteCode string `json:"inviteCode" binding:"required,min=6,max=32"`
}

type inviteRegisterRequest struct {
	InviteCode string `json:"inviteCode" binding:"required,min=6,max=32"`
	Username   string `json:"username" binding:"required,min=3,max=64"`
	Password   string `json:"password" binding:"required,min=6,max=128"`
}

func (s *Server) handleRegister(c *gin.Context) {
	c.JSON(http.StatusBadRequest, gin.H{
		"error": "invite registration required",
		"hint":  "use /api/auth/register/preview and /api/auth/register/confirm",
	})
}

func (s *Server) handleInvitePreview(c *gin.Context) {
	var req invitePreviewRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	code := normalizeInviteCode(req.InviteCode)
	if code == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid invite code"})
		return
	}

	invite, inviter, err := s.findActiveInviteWithUser(code)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "invite code not found"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"inviteCode": invite.Code,
		"inviter": gin.H{
			"id":            inviter.ID,
			"username":      inviter.Username,
			"favoriteColor": defaultColor(inviter.FavoriteColor),
		},
	})
}

func (s *Server) handleInviteRegister(c *gin.Context) {
	var req inviteRegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	code := normalizeInviteCode(req.InviteCode)
	if code == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid invite code"})
		return
	}
	username := strings.ToLower(strings.TrimSpace(req.Username))
	if len(username) < 3 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "username too short"})
		return
	}

	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "hash failed"})
		return
	}

	var user models.User
	var inviter models.User
	txErr := s.DB.Transaction(func(tx *gorm.DB) error {
		invite, loadedInviter, findErr := s.findActiveInviteWithUserTx(tx, code)
		if findErr != nil {
			return findErr
		}
		inviter = loadedInviter

		user = models.User{
			Username:      username,
			PasswordHash:  hash,
			FavoriteColor: "#1F5FBF",
		}
		if err := tx.Create(&user).Error; err != nil {
			return err
		}

		now := time.Now().In(s.Location)
		res := tx.Model(&models.InviteCode{}).
			Where("id = ? AND active = ? AND used_by_id IS NULL", invite.ID, true).
			Updates(map[string]any{
				"active":     false,
				"used_by_id": user.ID,
				"used_at":    now,
			})
		if res.Error != nil {
			return res.Error
		}
		if res.RowsAffected == 0 {
			return gorm.ErrRecordNotFound
		}

		_, err = s.createInviteCodeTx(tx, invite.UserID)
		return err
	})
	if txErr != nil {
		if errors.Is(txErr, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "invite code not found"})
			return
		}
		if strings.Contains(strings.ToLower(txErr.Error()), "unique") {
			c.JSON(http.StatusConflict, gin.H{"error": "username exists"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "register failed"})
		return
	}

	welcomeText := fmt.Sprintf("Herzlich willkommen %s (Einladung von %s erhalten)", user.Username, inviter.Username)
	_ = s.DB.Create(&models.ChatMessage{
		UserID: inviter.ID,
		Body:   welcomeText,
		Source: "command",
	}).Error

	inviteTokens := s.inviteRegistrationNotificationTokens()
	if len(inviteTokens) > 0 {
		sendResult, sendErr := s.Notifier.Send(inviteTokens, notify.Message{
			Title:  "Neues Mitglied",
			Body:   welcomeText,
			Type:   "invite_registered",
			Action: "open_chat",
		})
		s.recordPushResult(sendResult, sendErr)
		s.removeInvalidTokens(sendResult.InvalidTokens)
	}

	token, _ := s.Auth.Sign(user.ID, user.Username, user.IsAdmin)
	c.JSON(http.StatusCreated, gin.H{
		"token": token,
		"user":  s.userOwnJSON(user),
		"inviter": gin.H{
			"id":            inviter.ID,
			"username":      inviter.Username,
			"favoriteColor": defaultColor(inviter.FavoriteColor),
		},
	})
}

func (s *Server) handleLogin(c *gin.Context) {
	var req authRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}

	var user models.User
	if err := s.DB.Where("username = ?", strings.ToLower(req.Username)).First(&user).Error; err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid credentials"})
		return
	}

	if !auth.CheckPassword(user.PasswordHash, req.Password) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid credentials"})
		return
	}

	token, _ := s.Auth.Sign(user.ID, user.Username, user.IsAdmin)
	c.JSON(http.StatusOK, gin.H{"token": token, "user": s.userOwnJSON(user)})
}

func (s *Server) handleMe(c *gin.Context) {
	user, _ := userFromContext(c)
	if user.FavoriteColor == "" {
		user.FavoriteColor = "#1F5FBF"
	}

	// Keep this count in Go instead of SQL datetime comparisons.
	// SQLite can store mixed datetime formats/timezones, and direct SQL comparisons
	// may undercount even though a post is inside the prompt window.
	var photos []models.Photo
	dailyMomentCount := int64(0)
	streakDays := int64(0)
	if err := s.DB.Where("user_id = ?", user.ID).Order("created_at desc").Limit(500).Find(&photos).Error; err == nil {
		days := make([]string, 0, len(photos))
		daySeen := make(map[string]struct{}, len(photos))
		postedDaySet := make(map[string]struct{}, len(photos))
		for _, p := range photos {
			postedDaySet[p.Day] = struct{}{}
			if _, exists := daySeen[p.Day]; exists {
				continue
			}
			daySeen[p.Day] = struct{}{}
			days = append(days, p.Day)
		}

		promptByDay := make(map[string]models.DailyPrompt, len(days))
		if len(days) > 0 {
			var prompts []models.DailyPrompt
			if err := s.DB.Where("day IN ?", days).Find(&prompts).Error; err == nil {
				for _, pr := range prompts {
					promptByDay[pr.Day] = pr
				}
			}
		}

		countedDays := map[string]struct{}{}
		for _, p := range photos {
			if _, exists := countedDays[p.Day]; exists {
				continue
			}
			prompt, ok := promptByDay[p.Day]
			if !ok || prompt.TriggeredAt == nil || prompt.UploadUntil == nil {
				continue
			}
			if !p.CreatedAt.Before(*prompt.TriggeredAt) && !p.CreatedAt.After(*prompt.UploadUntil) {
				dailyMomentCount++
				countedDays[p.Day] = struct{}{}
			}
		}

		today := time.Now().In(s.Location).Format("2006-01-02")
		anchor := ""
		if _, ok := postedDaySet[today]; ok {
			anchor = today
		} else {
			yesterday := time.Now().In(s.Location).AddDate(0, 0, -1).Format("2006-01-02")
			if _, ok := postedDaySet[yesterday]; ok {
				anchor = yesterday
			}
		}
		if anchor != "" {
			dayCursor, err := time.ParseInLocation("2006-01-02", anchor, s.Location)
			if err == nil {
				for {
					dayKey := dayCursor.Format("2006-01-02")
					if _, ok := postedDaySet[dayKey]; !ok {
						break
					}
					streakDays++
					dayCursor = dayCursor.AddDate(0, 0, -1)
				}
			}
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"user":             s.userOwnJSON(user),
		"dailyMomentCount": dailyMomentCount,
		"streakDays":       streakDays,
	})
}

func (s *Server) handleMyInvite(c *gin.Context) {
	user, _ := userFromContext(c)
	invite, err := s.ensureActiveInviteCode(user.ID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "invite load failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"inviteCode": invite.Code,
	})
}

func (s *Server) handleRollMyInvite(c *gin.Context) {
	user, _ := userFromContext(c)
	var invite models.InviteCode
	err := s.DB.Transaction(func(tx *gorm.DB) error {
		var txErr error
		invite, txErr = s.createInviteCodeTx(tx, user.ID)
		return txErr
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "invite roll failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"inviteCode": invite.Code,
	})
}

func (s *Server) handleUpdateProfile(c *gin.Context) {
	user, _ := userFromContext(c)
	var req struct {
		Username          string  `json:"username" binding:"required,min=3,max=64"`
		FavoriteColor     string  `json:"favoriteColor"`
		Bio               *string `json:"bio"`
		StatusText        *string `json:"statusText"`
		StatusEmoji       *string `json:"statusEmoji"`
		StatusExpiresAt   *string `json:"statusExpiresAt"`
		ProfileVisible    *bool   `json:"profileVisible"`
		AvatarVisible     *bool   `json:"avatarVisible"`
		BioVisible        *bool   `json:"bioVisible"`
		StatusVisible     *bool   `json:"statusVisible"`
		QuietHoursEnabled *bool   `json:"quietHoursEnabled"`
		QuietHoursStart   *string `json:"quietHoursStart"`
		QuietHoursEnd     *string `json:"quietHoursEnd"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	username := strings.ToLower(strings.TrimSpace(req.Username))
	if len(username) < 3 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "username too short"})
		return
	}
	color, ok := normalizeColor(req.FavoriteColor)
	if !ok {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid favoriteColor"})
		return
	}

	var existing models.User
	if err := s.DB.Where("username = ? AND id <> ?", username, user.ID).First(&existing).Error; err == nil {
		c.JSON(http.StatusConflict, gin.H{"error": "username exists"})
		return
	}

	updates := map[string]any{
		"username":       username,
		"favorite_color": color,
	}
	if req.Bio != nil {
		updates["bio"] = strings.TrimSpace(*req.Bio)
	}
	if req.StatusText != nil {
		updates["status_text"] = strings.TrimSpace(*req.StatusText)
	}
	if req.StatusEmoji != nil {
		updates["status_emoji"] = strings.TrimSpace(*req.StatusEmoji)
	}
	if req.StatusExpiresAt != nil {
		expRaw := strings.TrimSpace(*req.StatusExpiresAt)
		if expRaw == "" {
			updates["status_expires_at"] = nil
		} else {
			parsed, err := time.Parse(time.RFC3339, expRaw)
			if err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "invalid statusExpiresAt"})
				return
			}
			updates["status_expires_at"] = parsed.In(s.Location)
		}
	}
	if req.ProfileVisible != nil {
		updates["profile_visible"] = *req.ProfileVisible
	}
	if req.AvatarVisible != nil {
		updates["avatar_visible"] = *req.AvatarVisible
	}
	if req.BioVisible != nil {
		updates["bio_visible"] = *req.BioVisible
	}
	if req.StatusVisible != nil {
		updates["status_visible"] = *req.StatusVisible
	}
	if req.QuietHoursEnabled != nil {
		updates["quiet_hours_enabled"] = *req.QuietHoursEnabled
	}
	if req.QuietHoursStart != nil {
		start := strings.TrimSpace(*req.QuietHoursStart)
		if start != "" && !isHHMM(start) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid quietHoursStart"})
			return
		}
		if start != "" {
			updates["quiet_hours_start"] = start
		}
	}
	if req.QuietHoursEnd != nil {
		end := strings.TrimSpace(*req.QuietHoursEnd)
		if end != "" && !isHHMM(end) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid quietHoursEnd"})
			return
		}
		if end != "" {
			updates["quiet_hours_end"] = end
		}
	}

	if err := s.DB.Model(&models.User{}).Where("id = ?", user.ID).Updates(updates).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}

	var updated models.User
	if err := s.DB.First(&updated, user.ID).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"user": s.userOwnJSON(updated)})
}

func (s *Server) handleUpdatePreferences(c *gin.Context) {
	user, _ := userFromContext(c)
	var req struct {
		ChatPushEnabled               *bool  `json:"chatPushEnabled"`
		InviteRegistrationPushEnabled *bool  `json:"inviteRegistrationPushEnabled"`
		PhotoReactionPushEnabled      *bool  `json:"photoReactionPushEnabled"`
		PhotoCommentPushEnabled       *bool  `json:"photoCommentPushEnabled"`
		AllowPhotoDownload            *bool  `json:"allowPhotoDownload"`
		DiagnosticsConsentGranted     *bool  `json:"diagnosticsConsentGranted"`
		DiagnosticsConsentSource      string `json:"diagnosticsConsentSource"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	updates := map[string]any{}
	if req.ChatPushEnabled != nil {
		updates["chat_push_enabled"] = *req.ChatPushEnabled
	}
	if req.InviteRegistrationPushEnabled != nil {
		updates["invite_registration_push_enabled"] = *req.InviteRegistrationPushEnabled
	}
	if req.PhotoReactionPushEnabled != nil {
		updates["photo_reaction_push_enabled"] = *req.PhotoReactionPushEnabled
	}
	if req.PhotoCommentPushEnabled != nil {
		updates["photo_comment_push_enabled"] = *req.PhotoCommentPushEnabled
	}
	if req.AllowPhotoDownload != nil {
		updates["allow_photo_download"] = *req.AllowPhotoDownload
	}
	if req.DiagnosticsConsentGranted != nil {
		updates["diagnostics_consent_granted"] = *req.DiagnosticsConsentGranted
		now := time.Now().In(s.Location)
		updates["diagnostics_consent_updated_at"] = &now
		source := strings.TrimSpace(req.DiagnosticsConsentSource)
		if source == "" {
			source = "profile_toggle"
		}
		if len(source) > 32 {
			source = source[:32]
		}
		updates["diagnostics_consent_source"] = source
	}
	if len(updates) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "no preferences provided"})
		return
	}
	if err := s.DB.Model(&models.User{}).Where("id = ?", user.ID).Updates(updates).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	var updated models.User
	if err := s.DB.First(&updated, user.ID).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"user": s.userOwnJSON(updated)})
}

func (s *Server) handleUploadAvatar(c *gin.Context) {
	user, _ := userFromContext(c)
	fileHeader, err := c.FormFile("avatar")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "avatar file required"})
		return
	}
	relPath, err := s.saveAvatarFile(user.ID, fileHeader)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "avatar save failed"})
		return
	}

	var current models.User
	if err := s.DB.Select("id", "avatar_path").First(&current, user.ID).Error; err == nil {
		old := strings.TrimSpace(current.AvatarPath)
		if old != "" && old != relPath && strings.HasPrefix(old, "avatars/") {
			_ = s.removePhotoFile(old)
		}
	}
	if err := s.DB.Model(&models.User{}).Where("id = ?", user.ID).Update("avatar_path", relPath).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "avatar update failed"})
		return
	}
	var updated models.User
	if err := s.DB.First(&updated, user.ID).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"user": s.userOwnJSON(updated)})
}

func (s *Server) handleUserProfile(c *gin.Context) {
	viewer, _ := userFromContext(c)
	targetID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
		return
	}
	var target models.User
	if err := s.DB.First(&target, targetID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}
	sameUser := viewer.ID == target.ID
	if !sameUser && !target.ProfileVisible {
		c.JSON(http.StatusOK, gin.H{
			"profileVisible": false,
			"user":           s.userPublicJSON(viewer.ID, target),
			"photos":         []gin.H{},
			"isSelf":         false,
		})
		return
	}

	photos, err := s.loadVisibleUserPhotos(viewer.ID, target.ID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "profile query failed"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"profileVisible": true,
		"user":           s.userPublicJSON(viewer.ID, target),
		"photos":         photos,
		"isSelf":         sameUser,
	})
}

func (s *Server) handleChangePassword(c *gin.Context) {
	user, _ := userFromContext(c)

	var req struct {
		CurrentPassword string `json:"currentPassword" binding:"required,min=6,max=128"`
		NewPassword     string `json:"newPassword" binding:"required,min=6,max=128"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	if !auth.CheckPassword(user.PasswordHash, req.CurrentPassword) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "current password invalid"})
		return
	}
	hash, err := auth.HashPassword(req.NewPassword)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "hash failed"})
		return
	}
	if err := s.DB.Model(&models.User{}).Where("id = ?", user.ID).Update("password_hash", hash).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

type deviceRequest struct {
	Token      string `json:"token" binding:"required,max=255"`
	DeviceName string `json:"deviceName" binding:"max=120"`
	AppVersion string `json:"appVersion" binding:"max=40"`
}

func (s *Server) handleDevice(c *gin.Context) {
	user, _ := userFromContext(c)
	var req deviceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}

	d := models.DeviceToken{
		Token:      req.Token,
		UserID:     user.ID,
		DeviceName: strings.TrimSpace(req.DeviceName),
		AppVersion: strings.TrimSpace(req.AppVersion),
	}
	if d.AppVersion == "" {
		d.AppVersion = "unknown"
	}
	_ = s.DB.Where("token = ?", req.Token).Assign(d).FirstOrCreate(&d).Error
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

type clientDebugLogRequest struct {
	Type       string `json:"type" binding:"required,max=32"`
	Message    string `json:"message" binding:"required,max=500"`
	Meta       string `json:"meta" binding:"max=4000"`
	AppVersion string `json:"appVersion" binding:"max=40"`
	DeviceName string `json:"deviceName" binding:"max=120"`
	SessionID  string `json:"sessionId" binding:"max=64"`
	RequestID  string `json:"requestId" binding:"max=64"`
}

func (s *Server) handleClientDebugLog(c *gin.Context) {
	user, _ := userFromContext(c)
	var req clientDebugLogRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	logType := strings.TrimSpace(req.Type)
	if logType == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "type required"})
		return
	}
	msg := strings.TrimSpace(req.Message)
	if msg == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "message required"})
		return
	}
	entry := models.ClientDebugLog{
		UserID:     user.ID,
		Type:       logType,
		Message:    msg,
		Meta:       strings.TrimSpace(req.Meta),
		AppVersion: strings.TrimSpace(req.AppVersion),
		DeviceName: strings.TrimSpace(req.DeviceName),
		SessionID:  strings.TrimSpace(req.SessionID),
		RequestID:  strings.TrimSpace(req.RequestID),
	}
	if entry.AppVersion == "" {
		entry.AppVersion = "unknown"
	}
	if entry.DeviceName == "" {
		entry.DeviceName = "unknown"
	}
	if entry.RequestID == "" {
		if reqID, ok := c.Get("requestId"); ok {
			entry.RequestID = strings.TrimSpace(fmt.Sprint(reqID))
		}
	}
	if err := s.DB.Create(&entry).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "log save failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleCurrentPrompt(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	day := now.Format("2006-01-02")

	var prompt models.DailyPrompt
	err := s.DB.Where("day = ?", day).First(&prompt).Error
	if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	canUpload := isPromptWindowActive(prompt, now)
	hasPromptPosted, _ := s.userHasPostedForDay(user.ID, day)
	hasAnyPost, _ := s.userHasAnyPhotoForDay(user.ID, day)
	hasVisiblePost, _ := s.userHasVisiblePhotoForDay(user.ID, day, now)
	var ownPhoto gin.H
	if hasPromptPosted {
		var p models.Photo
		if err := s.DB.Where("user_id = ? AND day = ? AND prompt_only = ?", user.ID, day, true).Order("created_at desc").First(&p).Error; err == nil {
			ownPhoto = s.photoJSON(p)
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"day":                  day,
		"triggered":            prompt.TriggeredAt,
		"uploadUntil":          prompt.UploadUntil,
		"canUpload":            canUpload,
		"hasPosted":            hasPromptPosted,
		"hasPromptPostedToday": hasPromptPosted,
		"hasVisiblePostToday":  hasVisiblePost,
		"hasAnyPostToday":      hasAnyPost,
		"ownPhoto":             ownPhoto,
		"triggerSource":        prompt.TriggerSource,
		"requestedByUser":      prompt.RequestedBy,
	})
}

func (s *Server) handleEvaluateUserPrompts(c *gin.Context) {
	user, _ := userFromContext(c)
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	settings = normalizeSettings(settings)
	rules := parseUserPromptRulesJSON(settings.UserPromptRulesJSON)
	appVersion := strings.TrimSpace(c.Query("appVersion"))

	items := make([]gin.H, 0)
	for _, rule := range rules {
		if !rule.Enabled {
			continue
		}
		shouldShow := false
		switch rule.TriggerType {
		case "app_version":
			shouldShow = appVersion != "" && !user.DiagnosticsConsentGranted
		case "app_start":
			shouldShow = !user.DiagnosticsConsentGranted
		case "time_based":
			shouldShow = false
		}
		if !shouldShow {
			continue
		}
		items = append(items, gin.H{
			"id":            rule.ID,
			"enabled":       rule.Enabled,
			"triggerType":   rule.TriggerType,
			"title":         rule.Title,
			"body":          rule.Body,
			"confirmLabel":  rule.ConfirmLabel,
			"declineLabel":  rule.DeclineLabel,
			"cooldownHours": rule.CooldownHours,
			"priority":      rule.Priority,
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"items":      items,
		"appVersion": appVersion,
		"serverNow":  time.Now().In(s.Location),
	})
}

func (s *Server) handlePromptRules(c *gin.Context) {
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	settings = normalizeSettings(settings)
	c.JSON(http.StatusOK, gin.H{
		"promptWindowStartHour": settings.PromptWindowStartHour,
		"promptWindowEndHour":   settings.PromptWindowEndHour,
		"uploadWindowMinutes":   settings.UploadWindowMinutes,
		"maxUploadBytes":        settings.MaxUploadBytes,
		"timezone":              s.Config.Timezone,
	})
}

func (s *Server) handleSpecialMomentStatus(c *gin.Context) {
	user, _ := userFromContext(c)
	status, err := s.specialMomentStatus(user.ID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "status query failed"})
		return
	}
	c.JSON(http.StatusOK, status)
}

func (s *Server) handleSpecialMomentRequest(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	day := now.Format("2006-01-02")

	if s.isDailyWindowActive(day, now) {
		c.JSON(http.StatusForbidden, gin.H{
			"error": "special moment unavailable during active daily window",
		})
		return
	}

	status, err := s.specialMomentStatus(user.ID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "status query failed"})
		return
	}
	canRequest, _ := status["canRequest"].(bool)
	if !canRequest {
		c.JSON(http.StatusTooManyRequests, gin.H{
			"error":  "sondermoment already requested this week",
			"status": status,
		})
		return
	}

	prompt, settings, err := s.Prompt.TriggerNowWithSource("special_request", &user)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "special trigger failed"})
		return
	}
	if s.Monitor != nil {
		triggerAt := time.Now().In(s.Location)
		if prompt.TriggeredAt != nil {
			triggerAt = prompt.TriggeredAt.In(s.Location)
		}
		s.markDailySpikeIfEnabled(prompt.Day, triggerAt)
	}

	reqRow := models.SpecialMomentRequest{
		UserID:      user.ID,
		RequestedAt: now,
	}
	if err := s.DB.Create(&reqRow).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save special request failed"})
		return
	}

	pushBody := fmt.Sprintf("Sondermoment von %s angefordert! Du hast %d Minuten Zeit.", user.Username, settings.UploadWindowMinutes)
	tokens := s.allDeviceTokens()
	sendResult, sendErr := s.Notifier.Send(tokens, notify.Message{
		Title:  "Sondermoment",
		Body:   pushBody,
		Type:   "special_request",
		Action: "open_camera",
	})
	s.recordPushResult(sendResult, sendErr)
	removed := s.removeInvalidTokens(sendResult.InvalidTokens)

	nextStatus, _ := s.specialMomentStatus(user.ID)
	c.JSON(http.StatusOK, gin.H{
		"ok":             true,
		"prompt":         prompt,
		"status":         nextStatus,
		"provider":       s.Notifier.Name(),
		"sentTo":         sendResult.Sent,
		"failed":         sendResult.Failed,
		"invalidRemoved": removed,
		"notificationErr": func() string {
			if sendErr != nil {
				return sendErr.Error()
			}
			return ""
		}(),
	})
}

func (s *Server) handleUpload(c *gin.Context) {
	user, _ := userFromContext(c)

	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}

	if settings.MaxUploadBytes > 0 {
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, settings.MaxUploadBytes)
	}

	fileHeader, err := c.FormFile("photo")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "photo file required"})
		return
	}

	kind := c.PostForm("kind")
	if kind == "" {
		kind = "extra"
	}
	if kind != "prompt" && kind != "extra" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "kind must be prompt or extra"})
		return
	}

	day := time.Now().In(s.Location).Format("2006-01-02")
	now := time.Now().In(s.Location)
	todayWindowActive := s.isDailyWindowActive(day, now)

	if _, err := s.cleanupInvalidPromptOnlyPhotosForDay(day); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	hasPromptPosted, err := s.userHasPostedForDay(user.ID, day)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	if kind == "prompt" {
		if !s.isPromptUploadAllowed(day, now) {
			c.JSON(http.StatusForbidden, gin.H{"error": "prompt inactive"})
			return
		}
		if hasPromptPosted {
			c.JSON(http.StatusConflict, gin.H{"error": "Du hast heute bereits gepostet"})
			return
		}
	}
	if kind == "extra" && todayWindowActive {
		c.JSON(http.StatusForbidden, gin.H{"error": "extra unavailable during daily moment window"})
		return
	}

	capsuleMode, capsuleVisibleAt, capsulePrivate, capsuleGroupRemind, capsuleErr := parseCapsuleForm(c, kind, todayWindowActive, now)
	if capsuleErr != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": capsuleErr.Error()})
		return
	}

	src, err := fileHeader.Open()
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "open upload failed"})
		return
	}
	defer src.Close()

	ext := strings.ToLower(filepath.Ext(fileHeader.Filename))
	relPath, err := s.Store.SavePhoto(day, user.ID, src, ext)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	capsulePreviewPath := ""
	if capsuleVisibleAt != nil {
		previewPath, previewErr := s.ensureCapsulePreview(relPath)
		if previewErr != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "capsule preview failed"})
			return
		}
		capsulePreviewPath = previewPath
	}

	photo := models.Photo{
		UserID:             user.ID,
		Day:                day,
		PromptOnly:         kind == "prompt",
		FilePath:           relPath,
		CapsulePreviewPath: capsulePreviewPath,
		Caption:            c.PostForm("caption"),
		CapsuleMode:        capsuleMode,
		CapsuleVisibleAt:   capsuleVisibleAt,
		CapsulePrivate:     capsulePrivate,
		CapsuleGroupRemind: capsuleGroupRemind,
	}
	if err := s.DB.Create(&photo).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "db write failed"})
		return
	}

	s.invalidateFeedDayCache(photo.Day)
	s.notifyPostCreated(user, photo)
	c.JSON(http.StatusCreated, gin.H{"photo": s.photoJSON(photo)})
}

func (s *Server) handleAdminCalendar(c *gin.Context) {
	days := 7
	if raw := c.Query("days"); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil {
			days = n
		}
	}

	plans, err := s.Prompt.EnsurePlans(days)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "calendar query failed"})
		return
	}

	dayList := make([]string, 0, len(plans))
	for _, p := range plans {
		dayList = append(dayList, p.Day)
	}

	var prompts []models.DailyPrompt
	_ = s.DB.Where("day IN ?", dayList).Find(&prompts).Error
	promptByDay := make(map[string]models.DailyPrompt, len(prompts))
	for _, p := range prompts {
		promptByDay[p.Day] = p
	}

	out := make([]gin.H, 0, len(plans))
	for _, p := range plans {
		row := gin.H{
			"day":       p.Day,
			"plannedAt": p.PlannedAt,
			"isManual":  p.IsManual,
			"source":    "auto",
		}
		if p.IsManual {
			row["source"] = "manual"
		}
		if prompt, ok := promptByDay[p.Day]; ok {
			row["triggeredAt"] = prompt.TriggeredAt
			row["uploadUntil"] = prompt.UploadUntil
			row["triggerSource"] = prompt.TriggerSource
			row["requestedByUser"] = prompt.RequestedBy
		}
		out = append(out, row)
	}

	c.JSON(http.StatusOK, gin.H{"items": out})
}

func (s *Server) handleAdminTimeCapsules(c *gin.Context) {
	now := time.Now().In(s.Location)

	var photos []models.Photo
	if err := s.DB.Preload("User").
		Where("capsule_visible_at IS NOT NULL").
		Where("capsule_visible_at > ?", now).
		Order("capsule_visible_at asc, created_at asc").
		Limit(200).
		Find(&photos).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	items := make([]gin.H, 0, len(photos))
	for _, p := range photos {
		items = append(items, gin.H{
			"photoId":     p.ID,
			"day":         p.Day,
			"capsuleMode": p.CapsuleMode,
			"capsuledAt":  p.CreatedAt,
			"unlocksAt":   p.CapsuleVisibleAt,
			"previewUrl":  fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.FilePath),
			"secondPreviewUrl": func() string {
				if strings.TrimSpace(p.SecondPath) == "" {
					return ""
				}
				return fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.SecondPath)
			}(),
			"user": gin.H{
				"id":            p.User.ID,
				"username":      p.User.Username,
				"favoriteColor": defaultColor(p.User.FavoriteColor),
			},
		})
	}

	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) handleAdminCalendarDay(c *gin.Context) {
	day := c.Param("day")
	if _, err := time.ParseInLocation("2006-01-02", day, s.Location); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid day format"})
		return
	}

	var req struct {
		PlannedAt string `json:"plannedAt" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}

	var planned time.Time
	var err error
	if len(req.PlannedAt) == len("2006-01-02T15:04") {
		planned, err = time.ParseInLocation("2006-01-02T15:04", req.PlannedAt, s.Location)
	} else {
		planned, err = time.Parse(time.RFC3339, req.PlannedAt)
		planned = planned.In(s.Location)
	}
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid plannedAt format"})
		return
	}
	if planned.Format("2006-01-02") != day {
		c.JSON(http.StatusBadRequest, gin.H{"error": "plannedAt day mismatch"})
		return
	}

	plan, err := s.Prompt.SetPlanForDay(day, planned, true)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save plan failed"})
		return
	}

	var prompt models.DailyPrompt
	_ = s.DB.Where("day = ?", day).First(&prompt).Error

	c.JSON(http.StatusOK, gin.H{
		"day":             plan.Day,
		"plannedAt":       plan.PlannedAt,
		"isManual":        plan.IsManual,
		"source":          "manual",
		"triggeredAt":     prompt.TriggeredAt,
		"uploadUntil":     prompt.UploadUntil,
		"triggerSource":   prompt.TriggerSource,
		"requestedByUser": prompt.RequestedBy,
	})
}

func (s *Server) handleAdminFeed(c *gin.Context) {
	adminUser, _ := userFromContext(c)
	day := c.Query("day")
	if day == "" {
		day = time.Now().In(s.Location).Format("2006-01-02")
	}

	var prompt models.DailyPrompt
	_ = s.DB.Where("day = ?", day).First(&prompt).Error

	var photos []models.Photo
	photosQueryStart := time.Now()
	if err := s.DB.Preload("User").Where("day = ?", day).Order("created_at desc").Find(&photos).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/feed", "feed_photos_query", time.Since(photosQueryStart))
	}

	photoIDs := make([]uint, 0, len(photos))
	for _, p := range photos {
		photoIDs = append(photoIDs, p.ID)
	}
	interactionStart := time.Now()
	reactionByPhoto, commentByPhoto, err := s.feedInteractionPreview(photoIDs)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "interaction query failed"})
		return
	}
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/feed", "feed_interaction_preview", time.Since(interactionStart))
	}

	out := make([]gin.H, 0, len(photos))
	now := time.Now().In(s.Location)
	for _, p := range photos {
		capsuleLocked := p.CapsuleVisibleAt != nil && now.Before(*p.CapsuleVisibleAt)
		capsuleReleased := strings.TrimSpace(p.CapsuleMode) != "" && !capsuleLocked
		isEarly := false
		isLate := false
		if prompt.TriggeredAt != nil && p.CreatedAt.Before(*prompt.TriggeredAt) {
			isEarly = true
		}
		if prompt.UploadUntil != nil && p.CreatedAt.After(*prompt.UploadUntil) {
			isLate = true
		}
		reactions := reactionByPhoto[p.ID]
		if reactions == nil {
			reactions = []gin.H{}
		}
		comments := commentByPhoto[p.ID]
		if comments == nil {
			comments = []gin.H{}
		}
		out = append(out, gin.H{
			"isEarly":         isEarly,
			"isLate":          isLate,
			"capsuleLocked":   capsuleLocked,
			"capsuleReleased": capsuleReleased,
			"photo":           s.photoJSON(p),
			"user":            s.userPublicJSON(adminUser.ID, p.User),
			"reactions":       reactions,
			"comments":        comments,
			"triggerSource":   prompt.TriggerSource,
			"requestedByUser": prompt.RequestedBy,
		})
	}

	recap, _ := s.monthlyRecapForDay(day, adminUser.ID)

	c.JSON(http.StatusOK, gin.H{
		"items":           out,
		"day":             day,
		"triggeredAt":     prompt.TriggeredAt,
		"uploadUntil":     prompt.UploadUntil,
		"triggerSource":   prompt.TriggerSource,
		"requestedByUser": prompt.RequestedBy,
		"monthRecap":      recap,
	})
}

func (s *Server) handleFeed(c *gin.Context) {
	user, _ := userFromContext(c)
	day := c.Query("day")
	if day == "" {
		day = time.Now().In(s.Location).Format("2006-01-02")
	}
	today := time.Now().In(s.Location).Format("2006-01-02")
	now := time.Now().In(s.Location)
	if allow, retryAfter := s.allowFeedRead(user.ID, now); !allow {
		if s.Monitor != nil {
			s.Monitor.RecordThrottle("feed_spike_poll_guard")
		}
		c.Header("Retry-After", strconv.Itoa(retryAfter))
		c.Header("X-RateLimit-Policy", "soft")
		c.Header("X-RateLimit-Reason", "feed_spike_poll_guard")
		c.Header("X-RateLimit-Scope", "feed")
		c.JSON(http.StatusTooManyRequests, gin.H{
			"error":      "Zu viele Feed-Aktualisierungen in kurzer Zeit. Bitte gleich erneut versuchen.",
			"code":       "feed_rate_limited",
			"reasonTag":  "feed_spike_poll_guard",
			"retryAfter": retryAfter,
		})
		return
	}
	if s.shouldUseFeedCache(day, now) {
		if cached, ok := s.feedCachedPayload(user.ID, day, now); ok {
			c.JSON(http.StatusOK, cached)
			return
		}
	}
	if day == today {
		hasPosted, err := s.userHasVisiblePhotoForDay(user.ID, day, now)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
		if !hasPosted {
			c.JSON(http.StatusForbidden, gin.H{
				"error": "Poste zuerst einen sichtbaren Beitrag, um die Beitraege der anderen zu sehen",
				"code":  "feed_locked",
			})
			return
		}
	}

	var prompt models.DailyPrompt
	_ = s.DB.Where("day = ?", day).First(&prompt).Error

	var photos []models.Photo
	if err := s.DB.Preload("User").Where("day = ?", day).Order("created_at desc").Find(&photos).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	photoIDs := make([]uint, 0, len(photos))
	for _, p := range photos {
		photoIDs = append(photoIDs, p.ID)
	}
	reactionByPhoto, commentByPhoto, err := s.feedInteractionPreview(photoIDs)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "interaction query failed"})
		return
	}

	out := make([]gin.H, 0, len(photos))
	for _, p := range photos {
		if !photoVisibleToViewer(user.ID, p, now) {
			continue
		}
		capsuleLocked := p.CapsuleVisibleAt != nil && now.Before(*p.CapsuleVisibleAt)
		capsuleReleased := strings.TrimSpace(p.CapsuleMode) != "" && !capsuleLocked
		isEarly := false
		isLate := false
		if prompt.TriggeredAt != nil && p.CreatedAt.Before(*prompt.TriggeredAt) {
			isEarly = true
		}
		if prompt.UploadUntil != nil && p.CreatedAt.After(*prompt.UploadUntil) {
			isLate = true
		}
		reactions := reactionByPhoto[p.ID]
		if reactions == nil {
			reactions = []gin.H{}
		}
		comments := commentByPhoto[p.ID]
		if comments == nil {
			comments = []gin.H{}
		}
		out = append(out, gin.H{
			"isEarly":         isEarly,
			"isLate":          isLate,
			"capsuleLocked":   capsuleLocked,
			"capsuleReleased": capsuleReleased,
			"photo":           s.photoJSON(p),
			"user":            s.userPublicJSON(user.ID, p.User),
			"reactions":       reactions,
			"comments":        comments,
			"triggerSource":   prompt.TriggerSource,
			"requestedByUser": prompt.RequestedBy,
		})
	}

	recapStart := time.Now()
	recap, _ := s.monthlyRecapForDay(day, user.ID)
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/feed", "feed_monthly_recap", time.Since(recapStart))
	}
	payload := gin.H{
		"items":           out,
		"day":             day,
		"triggeredAt":     prompt.TriggeredAt,
		"uploadUntil":     prompt.UploadUntil,
		"triggerSource":   prompt.TriggerSource,
		"requestedByUser": prompt.RequestedBy,
		"monthRecap":      recap,
	}
	if s.shouldUseFeedCache(day, now) {
		s.putFeedCachedPayload(user.ID, day, payload, now)
	}
	c.JSON(http.StatusOK, payload)
}

func (s *Server) handleGetSettings(c *gin.Context) {
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	settings = normalizeSettings(settings)
	c.JSON(http.StatusOK, settingsJSON(settings))
}

type settingsRequest struct {
	PromptWindowStartHour   int              `json:"promptWindowStartHour"`
	PromptWindowEndHour     int              `json:"promptWindowEndHour"`
	UploadWindowMinutes     int              `json:"uploadWindowMinutes"`
	FeedCommentPreviewLimit int              `json:"feedCommentPreviewLimit"`
	PromptNotificationText  string           `json:"promptNotificationText"`
	MaxUploadBytes          int64            `json:"maxUploadBytes"`
	ChatCommandEnabled      bool             `json:"chatCommandEnabled"`
	ChatCommandValue        string           `json:"chatCommandValue"`
	ChatCommandTrigger      bool             `json:"chatCommandTrigger"`
	ChatCommandSendPush     bool             `json:"chatCommandSendPush"`
	ChatCommandPushText     string           `json:"chatCommandPushText"`
	ChatCommandEchoChat     bool             `json:"chatCommandEchoChat"`
	ChatCommandEchoText     string           `json:"chatCommandEchoText"`
	PerformanceTrackingEnabled       *bool            `json:"performanceTrackingEnabled"`
	PerformanceTrackingWindowMinutes *int             `json:"performanceTrackingWindowMinutes"`
	PerformanceTrackingOneShot       *bool            `json:"performanceTrackingOneShot"`
	UserPromptRules         []userPromptRule `json:"userPromptRules"`
}

func (s *Server) handleUpdateSettings(c *gin.Context) {
	var req settingsRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}

	if req.PromptWindowStartHour < 0 || req.PromptWindowStartHour > 23 || req.PromptWindowEndHour < 1 || req.PromptWindowEndHour > 24 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid hours"})
		return
	}
	if req.PromptWindowStartHour >= req.PromptWindowEndHour {
		c.JSON(http.StatusBadRequest, gin.H{"error": "start hour must be before end hour"})
		return
	}

	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}

	req.ChatCommandValue = strings.TrimSpace(req.ChatCommandValue)
	req.ChatCommandPushText = strings.TrimSpace(req.ChatCommandPushText)
	req.ChatCommandEchoText = strings.TrimSpace(req.ChatCommandEchoText)
	if req.ChatCommandEnabled && req.ChatCommandValue == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "chat command is empty"})
		return
	}
	if req.UserPromptRules != nil {
		if err := validateUserPromptRulesRequest(req.UserPromptRules); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
	}

	oldStartHour := settings.PromptWindowStartHour
	oldEndHour := settings.PromptWindowEndHour

	settings.PromptWindowStartHour = req.PromptWindowStartHour
	settings.PromptWindowEndHour = req.PromptWindowEndHour
	settings.UploadWindowMinutes = req.UploadWindowMinutes
	settings.FeedCommentPreviewLimit = req.FeedCommentPreviewLimit
	settings.PromptNotificationText = req.PromptNotificationText
	settings.MaxUploadBytes = req.MaxUploadBytes
	settings.ChatCommandEnabled = req.ChatCommandEnabled
	settings.ChatCommandValue = req.ChatCommandValue
	settings.ChatCommandTrigger = req.ChatCommandTrigger
	settings.ChatCommandSendPush = req.ChatCommandSendPush
	settings.ChatCommandPushText = req.ChatCommandPushText
	settings.ChatCommandEchoChat = req.ChatCommandEchoChat
	settings.ChatCommandEchoText = req.ChatCommandEchoText
	if req.PerformanceTrackingEnabled != nil {
		settings.PerformanceTrackingEnabled = *req.PerformanceTrackingEnabled
	}
	if req.PerformanceTrackingWindowMinutes != nil {
		settings.PerformanceTrackingWindowMinutes = *req.PerformanceTrackingWindowMinutes
	}
	if req.PerformanceTrackingOneShot != nil {
		settings.PerformanceTrackingOneShot = *req.PerformanceTrackingOneShot
	}
	if req.UserPromptRules != nil {
		settings.UserPromptRulesJSON = encodeUserPromptRulesJSON(req.UserPromptRules)
	}
	settings = normalizeSettings(settings)

	if err := s.DB.Save(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}

	if s.Prompt != nil && (oldStartHour != settings.PromptWindowStartHour || oldEndHour != settings.PromptWindowEndHour) {
		if err := s.Prompt.RefreshAutoPlans(30); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "saved settings but failed to refresh upcoming plans"})
			return
		}
	}

	c.JSON(http.StatusOK, settingsJSON(settings))
}

func (s *Server) handleTriggerPrompt(c *gin.Context) {
	adminUser, _ := userFromContext(c)
	var req struct {
		Silent        bool   `json:"silent"`
		NotifyUserIDs []uint `json:"notifyUserIds"`
	}
	if c.Request.ContentLength > 0 {
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
			return
		}
	}

	notifyIDs := make([]uint, 0, len(req.NotifyUserIDs))
	seenIDs := make(map[uint]struct{}, len(req.NotifyUserIDs))
	for _, id := range req.NotifyUserIDs {
		if id == 0 {
			continue
		}
		if _, exists := seenIDs[id]; exists {
			continue
		}
		seenIDs[id] = struct{}{}
		notifyIDs = append(notifyIDs, id)
	}

	triggerSource := "admin_manual"
	switch {
	case req.Silent:
		triggerSource = "admin_manual_silent"
	case len(notifyIDs) > 0:
		triggerSource = "admin_manual_targeted"
	}

	prompt, settings, err := s.Prompt.TriggerNowWithSource(triggerSource, &adminUser)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "trigger failed"})
		return
	}
	if s.Monitor != nil {
		triggerAt := time.Now().In(s.Location)
		if prompt.TriggeredAt != nil {
			triggerAt = prompt.TriggeredAt.In(s.Location)
		}
		s.markDailySpikeIfEnabled(prompt.Day, triggerAt)
	}

	mode := "broadcast_all"
	tokens := make([]string, 0, 64)
	if req.Silent {
		mode = "silent"
	} else if len(notifyIDs) > 0 {
		mode = "targeted_users"
		for _, id := range notifyIDs {
			tokens = append(tokens, s.userDeviceTokens(id)...)
		}
	} else {
		tokens = s.allDeviceTokens()
	}

	sendResult := notify.SendResult{}
	var sendErr error
	removed := int64(0)
	if mode != "silent" {
		sendResult, sendErr = s.Notifier.SendDailyPrompt(tokens, settings.PromptNotificationText)
		s.recordPushResult(sendResult, sendErr)
		removed = s.removeInvalidTokens(sendResult.InvalidTokens)
	}

	if sendErr != nil {
		c.JSON(http.StatusOK, gin.H{
			"prompt":          prompt,
			"settings":        settings,
			"mode":            mode,
			"targetUsers":     notifyIDs,
			"devices":         len(tokens),
			"provider":        s.Notifier.Name(),
			"sentTo":          sendResult.Sent,
			"failed":          sendResult.Failed,
			"invalidRemoved":  removed,
			"notificationErr": sendErr.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"prompt":         prompt,
		"settings":       settings,
		"mode":           mode,
		"targetUsers":    notifyIDs,
		"devices":        len(tokens),
		"provider":       s.Notifier.Name(),
		"sentTo":         sendResult.Sent,
		"failed":         sendResult.Failed,
		"invalidRemoved": removed,
	})
}

func (s *Server) handleAdminResetToday(c *gin.Context) {
	day := time.Now().In(s.Location).Format("2006-01-02")
	now := time.Now().In(s.Location)

	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	uploadUntil := now.Add(time.Duration(settings.UploadWindowMinutes) * time.Minute)

	txErr := s.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("day = ?", day).Delete(&models.Photo{}).Error; err != nil {
			return err
		}
		if err := tx.Where("day = ?", day).Delete(&models.DailyPrompt{}).Error; err != nil {
			return err
		}
		prompt := models.DailyPrompt{
			Day:           day,
			TriggeredAt:   &now,
			UploadUntil:   &uploadUntil,
			TriggerSource: "admin_reset",
		}
		return tx.Create(&prompt).Error
	})
	if txErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "reset failed"})
		return
	}
	s.invalidateFeedDayCache(day)
	if s.Monitor != nil {
		s.markDailySpikeIfEnabled(day, now)
	}

	c.JSON(http.StatusOK, gin.H{
		"ok":          true,
		"day":         day,
		"triggeredAt": now,
		"uploadUntil": uploadUntil,
		"message":     "heutiger Moment wurde zurueckgesetzt und neu gestartet",
	})
}

func (s *Server) handleAdminCreateUser(c *gin.Context) {
	var req struct {
		Username string `json:"username" binding:"required,min=3,max=64"`
		Password string `json:"password" binding:"required,min=6,max=128"`
		IsAdmin  bool   `json:"isAdmin"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload: username>=3, password>=6"})
		return
	}

	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "hash failed"})
		return
	}

	user := models.User{Username: strings.ToLower(req.Username), PasswordHash: hash, IsAdmin: req.IsAdmin}
	if err := s.DB.Create(&user).Error; err != nil {
		c.JSON(http.StatusConflict, gin.H{"error": "username exists"})
		return
	}

	c.JSON(http.StatusCreated, toAdminUser(user, 0, 0, nil, nil, 0, "", nil, "", "", nil, nil))
}

func (s *Server) handleAdminListUsers(c *gin.Context) {
	var users []models.User
	if err := s.DB.Order("created_at desc").Find(&users).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	type inviteUsageRow struct {
		UsedByID      uint
		InvitedByID   uint
		InvitedByName string
		InvitedAt     *time.Time
	}
	inviteByUserID := make(map[uint]inviteUsageRow, len(users))
	inviteRows := make([]inviteUsageRow, 0)
	_ = s.DB.Table("invite_codes AS ic").
		Select("ic.used_by_id AS used_by_id, ic.user_id AS invited_by_id, inviter.username AS invited_by_name, ic.used_at AS invited_at").
		Joins("JOIN users AS inviter ON inviter.id = ic.user_id").
		Where("ic.used_by_id IS NOT NULL").
		Find(&inviteRows).Error
	for _, row := range inviteRows {
		inviteByUserID[row.UsedByID] = row
	}

	type userDebugRow struct {
		UserID      uint
		AppVersion  string
		Message     string
		CreatedAt   time.Time
		LastSuccess *time.Time
	}
	debugByUserID := make(map[uint]userDebugRow, len(users))
	debugRows := make([]models.ClientDebugLog, 0)
	_ = s.DB.Order("created_at desc").Limit(300).Find(&debugRows).Error
	for _, row := range debugRows {
		if row.UserID == 0 {
			continue
		}
		entry, exists := debugByUserID[row.UserID]
		rowType := strings.ToLower(strings.TrimSpace(row.Type))
		if !exists {
			entry = userDebugRow{UserID: row.UserID}
		}

		if entry.LastSuccess == nil && rowType == "profile_open_ok" {
			t := row.CreatedAt
			entry.LastSuccess = &t
		}

		if strings.TrimSpace(entry.Message) == "" && rowType != "profile_open_ok" {
			entry.AppVersion = strings.TrimSpace(row.AppVersion)
			entry.Message = strings.TrimSpace(row.Message)
			entry.CreatedAt = row.CreatedAt
		}
		debugByUserID[row.UserID] = entry
	}

	out := make([]gin.H, 0, len(users))
	for _, u := range users {
		var photoCount int64
		var tokenRows []models.DeviceToken
		_ = s.DB.Model(&models.Photo{}).Where("user_id = ?", u.ID).Count(&photoCount).Error
		_ = s.DB.Where("user_id = ?", u.ID).Find(&tokenRows).Error
		tokenCount := int64(len(tokenRows))
		deviceNames := make([]string, 0, len(tokenRows))
		deviceDetails := make([]gin.H, 0, len(tokenRows))
		seenNames := make(map[string]struct{}, len(tokenRows))
		latestDeviceVersion := ""
		for _, row := range tokenRows {
			name := strings.TrimSpace(row.DeviceName)
			if name == "" {
				name = "Unbekanntes Geraet"
			}
			if _, exists := seenNames[name]; exists {
				if latestDeviceVersion == "" && strings.TrimSpace(row.AppVersion) != "" {
					latestDeviceVersion = strings.TrimSpace(row.AppVersion)
				}
				continue
			}
			seenNames[name] = struct{}{}
			deviceNames = append(deviceNames, name)
			appVersion := strings.TrimSpace(row.AppVersion)
			if appVersion == "" {
				appVersion = "unknown"
			}
			if latestDeviceVersion == "" || (strings.EqualFold(latestDeviceVersion, "unknown") && !strings.EqualFold(appVersion, "unknown")) {
				latestDeviceVersion = appVersion
			}
			deviceDetails = append(deviceDetails, gin.H{
				"name":       name,
				"appVersion": appVersion,
			})
		}
		invite := inviteByUserID[u.ID]
		dbg := debugByUserID[u.ID]
		lastAppVersion := strings.TrimSpace(dbg.AppVersion)
		if lastAppVersion == "" || strings.EqualFold(lastAppVersion, "unknown") {
			lastAppVersion = latestDeviceVersion
		}
		out = append(out, toAdminUser(
			u,
			photoCount,
			tokenCount,
			deviceNames,
			deviceDetails,
			invite.InvitedByID,
			invite.InvitedByName,
			invite.InvitedAt,
			lastAppVersion,
			dbg.Message,
			func() *time.Time {
				if dbg.CreatedAt.IsZero() {
					return nil
				}
				t := dbg.CreatedAt
				return &t
			}(),
			dbg.LastSuccess,
		))
	}

	c.JSON(http.StatusOK, gin.H{"items": out})
}

func (s *Server) handleAdminDebugLogs(c *gin.Context) {
	limit := 100
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil {
			if n < 10 {
				n = 10
			}
			if n > 500 {
				n = 500
			}
			limit = n
		}
	}
	userID := uint(0)
	if raw := strings.TrimSpace(c.Query("userId")); raw != "" {
		parsed, err := parseUintParam(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
			return
		}
		userID = parsed
	}

	sinceHours, err := parseAdminSinceHours(c.Query("sinceHours"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid sinceHours"})
		return
	}
	serverNow := time.Now().UTC()
	since := adminSinceCutoff(serverNow, sinceHours)
	q := s.DB.Preload("User").Where("created_at >= ?", since).Order("created_at desc").Limit(limit)
	if userID != 0 {
		q = q.Where("user_id = ?", userID)
	}
	var rows []models.ClientDebugLog
	if err := q.Find(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	items := make([]gin.H, 0, len(rows))
	for _, row := range rows {
		items = append(items, gin.H{
			"id":         row.ID,
			"createdAt":  row.CreatedAt,
			"type":       row.Type,
			"message":    row.Message,
			"meta":       row.Meta,
			"appVersion": row.AppVersion,
			"deviceName": row.DeviceName,
			"sessionId":  row.SessionID,
			"requestId":  row.RequestID,
			"user": gin.H{
				"id":       row.User.ID,
				"username": row.User.Username,
			},
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"items":      items,
		"sinceHours": sinceHours,
		"since":      since.In(s.Location),
		"serverNow":  serverNow.In(s.Location),
	})
}

func (s *Server) handleAdminDeleteDebugLogs(c *gin.Context) {
	userID := uint(0)
	if raw := strings.TrimSpace(c.Query("userId")); raw != "" {
		parsed, err := parseUintParam(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
			return
		}
		userID = parsed
	}

	sinceHours, err := parseAdminSinceHours(c.Query("sinceHours"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid sinceHours"})
		return
	}
	since := adminSinceCutoff(time.Now().UTC(), sinceHours)
	q := s.DB.Where("created_at >= ?", since)
	if userID != 0 {
		q = q.Where("user_id = ?", userID)
	}
	result := q.Delete(&models.ClientDebugLog{})
	if result.Error != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"deletedCount": result.RowsAffected,
		"userId":       userID,
		"sinceHours":   sinceHours,
	})
}

func (s *Server) handleAdminDebugLogsExport(c *gin.Context) {
	userID := uint(0)
	if raw := strings.TrimSpace(c.Query("userId")); raw != "" {
		parsed, err := parseUintParam(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
			return
		}
		userID = parsed
	}

	sinceHours, err := parseAdminSinceHours(c.Query("sinceHours"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid sinceHours"})
		return
	}

	format := strings.ToLower(strings.TrimSpace(c.Query("format")))
	if format == "" {
		format = "csv"
	}
	if format != "csv" && format != "json" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid format"})
		return
	}

	limit := 5000
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid limit"})
			return
		}
		if n < 10 {
			n = 10
		}
		if n > 10000 {
			n = 10000
		}
		limit = n
	}

	since := adminSinceCutoff(time.Now().UTC(), sinceHours)
	q := s.DB.Preload("User").Where("created_at >= ?", since).Order("created_at desc").Limit(limit)
	if userID != 0 {
		q = q.Where("user_id = ?", userID)
	}

	var rows []models.ClientDebugLog
	if err := q.Find(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	nameScope := "all-users"
	if userID != 0 {
		nameScope = fmt.Sprintf("user-%d", userID)
	}
	ts := time.Now().In(s.Location).Format("20060102-150405")

	if format == "json" {
		filename := fmt.Sprintf("debug-logs-%s-last-%dh-%s.json", nameScope, sinceHours, ts)
		c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", filename))
		c.Header("Content-Type", "application/json; charset=utf-8")

		items := make([]gin.H, 0, len(rows))
		for _, row := range rows {
			items = append(items, gin.H{
				"id":         row.ID,
				"createdAt":  row.CreatedAt,
				"type":       row.Type,
				"message":    row.Message,
				"meta":       row.Meta,
				"appVersion": row.AppVersion,
				"deviceName": row.DeviceName,
				"sessionId":  row.SessionID,
				"requestId":  row.RequestID,
				"user": gin.H{
					"id":       row.User.ID,
					"username": row.User.Username,
				},
			})
		}

		c.JSON(http.StatusOK, gin.H{
			"generatedAt": time.Now().In(s.Location),
			"sinceHours":  sinceHours,
			"userId":      userID,
			"count":       len(items),
			"items":       items,
		})
		return
	}

	filename := fmt.Sprintf("debug-logs-%s-last-%dh-%s.csv", nameScope, sinceHours, ts)
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", filename))
	c.Header("Content-Type", "text/csv; charset=utf-8")

	var buf bytes.Buffer
	buf.Write([]byte{0xEF, 0xBB, 0xBF})
	writer := csv.NewWriter(&buf)
	_ = writer.Write([]string{
		"id", "created_at", "user_id", "username", "device_name", "app_version", "session_id", "request_id", "type", "message", "meta",
	})
	for _, row := range rows {
		_ = writer.Write([]string{
			strconv.FormatUint(uint64(row.ID), 10),
			row.CreatedAt.In(s.Location).Format(time.RFC3339),
			strconv.FormatUint(uint64(row.UserID), 10),
			row.User.Username,
			row.DeviceName,
			row.AppVersion,
			row.SessionID,
			row.RequestID,
			row.Type,
			row.Message,
			row.Meta,
		})
	}
	writer.Flush()
	if err := writer.Error(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "csv export failed"})
		return
	}
	c.Data(http.StatusOK, "text/csv; charset=utf-8", buf.Bytes())
}

func (s *Server) handleAdminListReports(c *gin.Context) {
	limit := 200
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil {
			if n < 10 {
				n = 10
			}
			if n > 500 {
				n = 500
			}
			limit = n
		}
	}

	userID := uint(0)
	if raw := strings.TrimSpace(c.Query("userId")); raw != "" {
		parsed, err := parseUintParam(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
			return
		}
		userID = parsed
	}

	reportType := strings.ToLower(strings.TrimSpace(c.Query("type")))
	if reportType != "" && reportType != "bug" && reportType != "idea" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid report type"})
		return
	}

	status := strings.ToLower(strings.TrimSpace(c.Query("status")))
	if status != "" && !isValidUserReportStatus(status) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid report status"})
		return
	}

	q := s.DB.Preload("User").Order("created_at desc").Limit(limit)
	if userID != 0 {
		q = q.Where("user_id = ?", userID)
	}
	if reportType != "" {
		q = q.Where("type = ?", reportType)
	}
	if status != "" {
		q = q.Where("status = ?", status)
	}

	var rows []models.UserReport
	if err := q.Find(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	items := make([]gin.H, 0, len(rows))
	for _, row := range rows {
		items = append(items, userReportJSON(row))
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) handleAdminDeleteReport(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid report id"})
		return
	}

	result := s.DB.Delete(&models.UserReport{}, id)
	if result.Error != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}
	if result.RowsAffected == 0 {
		c.JSON(http.StatusNotFound, gin.H{"error": "report not found"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"ok": true, "deletedId": id})
}

func (s *Server) handleAdminDeleteReports(c *gin.Context) {
	userID := uint(0)
	if raw := strings.TrimSpace(c.Query("userId")); raw != "" {
		parsed, err := parseUintParam(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
			return
		}
		userID = parsed
	}

	reportType := strings.ToLower(strings.TrimSpace(c.Query("type")))
	if reportType != "" && reportType != "bug" && reportType != "idea" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid report type"})
		return
	}

	status := strings.ToLower(strings.TrimSpace(c.Query("status")))
	if status != "" && !isValidUserReportStatus(status) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid report status"})
		return
	}

	q := s.DB.Model(&models.UserReport{})
	if userID != 0 {
		q = q.Where("user_id = ?", userID)
	}
	if reportType != "" {
		q = q.Where("type = ?", reportType)
	}
	if status != "" {
		q = q.Where("status = ?", status)
	}

	result := q.Delete(&models.UserReport{})
	if result.Error != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"ok":           true,
		"deletedCount": result.RowsAffected,
	})
}

func (s *Server) handleAdminUpdateReport(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid report id"})
		return
	}

	var req struct {
		Status            string `json:"status"`
		GithubIssueNumber *int   `json:"githubIssueNumber"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}

	status := strings.ToLower(strings.TrimSpace(req.Status))
	if !isValidUserReportStatus(status) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid report status"})
		return
	}
	if req.GithubIssueNumber != nil && *req.GithubIssueNumber <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid github issue number"})
		return
	}

	var report models.UserReport
	if err := s.DB.Preload("User").First(&report, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "report not found"})
		return
	}

	report.Status = status
	report.GithubIssueNumber = req.GithubIssueNumber
	if err := s.DB.Save(&report).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	if err := s.DB.Preload("User").First(&report, id).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "reload failed"})
		return
	}

	c.JSON(http.StatusOK, userReportJSON(report))
}

func (s *Server) handleAdminUpdateUser(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
		return
	}

	var req struct {
		Password *string `json:"password"`
		IsAdmin  *bool   `json:"isAdmin"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}

	var user models.User
	if err := s.DB.First(&user, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	if req.Password != nil {
		if len(strings.TrimSpace(*req.Password)) < 6 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "password must be at least 6 chars"})
			return
		}
		hash, err := auth.HashPassword(*req.Password)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "hash failed"})
			return
		}
		user.PasswordHash = hash
	}

	if req.IsAdmin != nil {
		user.IsAdmin = *req.IsAdmin
	}

	if err := s.DB.Save(&user).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}

	var photoCount int64
	var tokenCount int64
	_ = s.DB.Model(&models.Photo{}).Where("user_id = ?", user.ID).Count(&photoCount).Error
	_ = s.DB.Model(&models.DeviceToken{}).Where("user_id = ?", user.ID).Count(&tokenCount).Error

	c.JSON(http.StatusOK, toAdminUser(user, photoCount, tokenCount, nil, nil, 0, "", nil, "", "", nil, nil))
}

func (s *Server) handleAdminIssueUserToken(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
		return
	}

	var user models.User
	if err := s.DB.First(&user, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	token, err := s.Auth.Sign(user.ID, user.Username, user.IsAdmin)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "token create failed"})
		return
	}

	claims, parseErr := s.Auth.Parse(token)
	var expiresAt *time.Time
	if parseErr == nil && claims != nil && claims.ExpiresAt != nil {
		t := claims.ExpiresAt.Time
		expiresAt = &t
	}

	c.JSON(http.StatusOK, gin.H{
		"userId":    user.ID,
		"username":  user.Username,
		"isAdmin":   user.IsAdmin,
		"token":     token,
		"expiresAt": expiresAt,
	})
}

func (s *Server) handleAdminDeleteUser(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
		return
	}

	adminUser, _ := userFromContext(c)
	if adminUser.ID == id {
		c.JSON(http.StatusBadRequest, gin.H{"error": "cannot delete current admin"})
		return
	}

	var user models.User
	if err := s.DB.First(&user, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	_ = s.DB.Where("user_id = ?", id).Delete(&models.DeviceToken{}).Error
	_ = s.DB.Where("user_id = ?", id).Delete(&models.Photo{}).Error
	if err := s.DB.Delete(&user).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleAdminStats(c *gin.Context) {
	var users int64
	var photos int64
	var devices int64
	var prompts int64
	var totalImages int64
	var runningDays int64
	var storageBytes int64
	var diagnosticsConsentUsers int64

	_ = s.DB.Model(&models.User{}).Count(&users).Error
	_ = s.DB.Model(&models.Photo{}).Count(&photos).Error
	_ = s.DB.Model(&models.DeviceToken{}).Count(&devices).Error
	_ = s.DB.Model(&models.DailyPrompt{}).Count(&prompts).Error
	_ = s.DB.Model(&models.User{}).Where("diagnostics_consent_granted = ?", true).Count(&diagnosticsConsentUsers).Error
	_ = s.DB.Raw("SELECT COALESCE(SUM(CASE WHEN second_path IS NOT NULL AND second_path <> '' THEN 2 ELSE 1 END),0) FROM photos").Scan(&totalImages).Error

	var startedAt *time.Time
	_ = s.DB.Raw(`
SELECT MIN(created_at) FROM (
    SELECT MIN(created_at) AS created_at FROM users
    UNION ALL SELECT MIN(created_at) FROM photos
    UNION ALL SELECT MIN(created_at) FROM daily_prompts
    UNION ALL SELECT MIN(created_at) FROM app_settings
) t
WHERE created_at IS NOT NULL
`).Scan(&startedAt).Error
	if startedAt != nil && !startedAt.IsZero() {
		d := int64(time.Now().In(s.Location).Sub(startedAt.In(s.Location)).Hours() / 24)
		if d < 0 {
			d = 0
		}
		runningDays = d + 1
	}

	_ = filepath.Walk(s.Config.UploadDir, func(_ string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		storageBytes += info.Size()
		return nil
	})

	c.JSON(http.StatusOK, gin.H{
		"users":                   users,
		"photos":                  photos,
		"devices":                 devices,
		"prompts":                 prompts,
		"totalImages":             totalImages,
		"runningDays":             runningDays,
		"storageBytes":            storageBytes,
		"diagnosticsConsentUsers": diagnosticsConsentUsers,
		"diagnosticsConsentRate":  safeRatio(int(diagnosticsConsentUsers), maxInt(1, int(users))),
	})
}

func (s *Server) handleAdminSearch(c *gin.Context) {
	q := strings.TrimSpace(c.Query("q"))
	if q == "" {
		c.JSON(http.StatusOK, gin.H{"items": []gin.H{}})
		return
	}
	limit := 12
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil {
			if n < 3 {
				n = 3
			}
			if n > 50 {
				n = 50
			}
			limit = n
		}
	}

	scopeRaw := strings.ToLower(strings.TrimSpace(c.Query("scope")))
	scopeSet := map[string]bool{
		"users":    true,
		"reports":  true,
		"commands": true,
		"history":  true,
	}
	if scopeRaw != "" {
		scopeSet = map[string]bool{
			"users":    false,
			"reports":  false,
			"commands": false,
			"history":  false,
		}
		for _, part := range strings.Split(scopeRaw, ",") {
			k := strings.TrimSpace(part)
			if _, ok := scopeSet[k]; ok {
				scopeSet[k] = true
			}
		}
	}

	items := make([]gin.H, 0, limit*2)
	like := "%" + strings.ToLower(q) + "%"

	if scopeSet["users"] {
		var rows []models.User
		if err := s.DB.
			Where("LOWER(username) LIKE ?", like).
			Order("username asc").
			Limit(limit).
			Find(&rows).Error; err == nil {
			for _, row := range rows {
				items = append(items, gin.H{
					"type":  "users",
					"id":    strconv.FormatUint(uint64(row.ID), 10),
					"label": "@" + row.Username,
					"meta":  fmt.Sprintf("User #%d", row.ID),
					"target": gin.H{
						"tab": "users",
					},
				})
			}
		}
	}

	if scopeSet["reports"] {
		var rows []models.UserReport
		if err := s.DB.
			Preload("User").
			Where("LOWER(body) LIKE ? OR LOWER(type) LIKE ? OR LOWER(status) LIKE ?", like, like, like).
			Order("created_at desc").
			Limit(limit).
			Find(&rows).Error; err == nil {
			for _, row := range rows {
				body := strings.TrimSpace(row.Body)
				if len(body) > 90 {
					body = body[:90] + "..."
				}
				meta := fmt.Sprintf("%s | %s | @%s", row.Type, row.Status, row.User.Username)
				items = append(items, gin.H{
					"type":  "reports",
					"id":    strconv.FormatUint(uint64(row.ID), 10),
					"label": body,
					"meta":  meta,
					"target": gin.H{
						"tab": "reports",
					},
				})
			}
		}
	}

	if scopeSet["commands"] {
		var rows []models.ChatCommand
		if err := s.DB.
			Where("LOWER(name) LIKE ? OR LOWER(command) LIKE ? OR LOWER(response_text) LIKE ?", like, like, like).
			Order("name asc").
			Limit(limit).
			Find(&rows).Error; err == nil {
			for _, row := range rows {
				meta := strings.TrimSpace(row.ResponseText)
				if len(meta) > 90 {
					meta = meta[:90] + "..."
				}
				items = append(items, gin.H{
					"type":  "commands",
					"id":    strconv.FormatUint(uint64(row.ID), 10),
					"label": fmt.Sprintf("%s (%s)", row.Name, row.Command),
					"meta":  meta,
					"target": gin.H{
						"tab": "commands",
					},
				})
			}
		}
	}

	if scopeSet["history"] {
		dayLike := "%" + q + "%"
		var promptRows []models.DailyPrompt
		if err := s.DB.
			Where("day LIKE ? OR requested_by LIKE ? OR trigger_source LIKE ?", dayLike, like, like).
			Order("day desc").
			Limit(limit).
			Find(&promptRows).Error; err == nil {
			for _, row := range promptRows {
				meta := "Prompt"
				if strings.TrimSpace(row.TriggerSource) != "" {
					meta = "Prompt | " + row.TriggerSource
				}
				items = append(items, gin.H{
					"type":  "history",
					"id":    row.Day,
					"label": row.Day,
					"meta":  meta,
					"target": gin.H{
						"tab": "history",
						"day": row.Day,
					},
				})
			}
		}

		var planRows []models.PromptPlan
		if err := s.DB.
			Where("day LIKE ?", dayLike).
			Order("day desc").
			Limit(limit).
			Find(&planRows).Error; err == nil {
			seen := make(map[string]struct{}, len(promptRows))
			for _, row := range promptRows {
				seen[row.Day] = struct{}{}
			}
			for _, row := range planRows {
				if _, exists := seen[row.Day]; exists {
					continue
				}
				items = append(items, gin.H{
					"type":  "history",
					"id":    row.Day + "-plan",
					"label": row.Day,
					"meta":  "Plan",
					"target": gin.H{
						"tab": "history",
						"day": row.Day,
					},
				})
			}
		}
	}

	if len(items) > 200 {
		items = items[:200]
	}

	c.JSON(http.StatusOK, gin.H{
		"items": items,
		"q":     q,
		"limit": limit,
	})
}

func (s *Server) handleAdminHistory(c *gin.Context) {
	days := 30
	if raw := strings.TrimSpace(c.Query("days")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil {
			days = n
		}
	}
	if days < 1 {
		days = 1
	}
	if days > 120 {
		days = 120
	}
	excludeEmpty := true
	if raw := strings.TrimSpace(c.Query("excludeEmpty")); raw != "" {
		if parsed, err := strconv.ParseBool(raw); err == nil {
			excludeEmpty = parsed
		}
	}
	offset := 0
	if raw := strings.TrimSpace(c.Query("offset")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil && n >= 0 {
			offset = n
		}
	}

	now := time.Now().In(s.Location)
	startDayDate := now.AddDate(0, 0, -offset)
	dayList := make([]string, 0, days)
	for i := 0; i < days; i++ {
		dayList = append(dayList, startDayDate.AddDate(0, 0, -i).Format("2006-01-02"))
	}
	oldest := dayList[len(dayList)-1]
	newest := dayList[0]

	var plans []models.PromptPlan
	if err := s.DB.Where("day >= ? AND day <= ?", oldest, newest).Find(&plans).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "history query failed"})
		return
	}
	planByDay := make(map[string]models.PromptPlan, len(plans))
	for _, plan := range plans {
		planByDay[plan.Day] = plan
	}

	var prompts []models.DailyPrompt
	if err := s.DB.Where("day >= ? AND day <= ?", oldest, newest).Find(&prompts).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "history query failed"})
		return
	}
	promptByDay := make(map[string]models.DailyPrompt, len(prompts))
	for _, prompt := range prompts {
		promptByDay[prompt.Day] = prompt
	}

	var photos []models.Photo
	if err := s.DB.Where("day >= ? AND day <= ?", oldest, newest).Find(&photos).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "history query failed"})
		return
	}
	photoUserIDs := make(map[uint]struct{}, len(photos))
	for _, p := range photos {
		photoUserIDs[p.UserID] = struct{}{}
	}

	dayStart := time.Date(startDayDate.AddDate(0, 0, -(days-1)).Year(), startDayDate.AddDate(0, 0, -(days-1)).Month(), startDayDate.AddDate(0, 0, -(days-1)).Day(), 0, 0, 0, 0, s.Location)
	dayEnd := time.Date(startDayDate.Year(), startDayDate.Month(), startDayDate.Day(), 23, 59, 59, int(time.Second-time.Nanosecond), s.Location)

	var comments []models.PhotoComment
	_ = s.DB.Where("created_at >= ? AND created_at <= ?", dayStart, dayEnd).Find(&comments).Error
	var reactions []models.PhotoReaction
	_ = s.DB.Where("created_at >= ? AND created_at <= ?", dayStart, dayEnd).Find(&reactions).Error
	var chats []models.ChatMessage
	_ = s.DB.Where("created_at >= ? AND created_at <= ?", dayStart, dayEnd).Find(&chats).Error
	var debugLogs []models.ClientDebugLog
	_ = s.DB.Where("created_at >= ? AND created_at <= ?", dayStart, dayEnd).Find(&debugLogs).Error

	var activities []models.DailyUserActivity
	if err := s.DB.Preload("User").Where("day >= ? AND day <= ?", oldest, newest).Order("day desc, first_seen_at asc").Find(&activities).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "history query failed"})
		return
	}

	var firstTrackedActivity models.DailyUserActivity
	trackingAvailableFrom := ""
	if err := s.DB.Order("day asc").First(&firstTrackedActivity).Error; err == nil {
		trackingAvailableFrom = firstTrackedActivity.Day
	}
	usernameByID := make(map[uint]string)
	userIDs := make([]uint, 0, len(photoUserIDs))
	for userID := range photoUserIDs {
		userIDs = append(userIDs, userID)
	}
	for _, row := range activities {
		if row.UserID == 0 {
			continue
		}
		if _, ok := photoUserIDs[row.UserID]; !ok {
			userIDs = append(userIDs, row.UserID)
		}
		if name := strings.TrimSpace(row.User.Username); name != "" {
			usernameByID[row.UserID] = name
		}
	}
	if len(userIDs) > 0 {
		var users []models.User
		_ = s.DB.Where("id IN ?", userIDs).Find(&users).Error
		for _, u := range users {
			usernameByID[u.ID] = u.Username
		}
	}

	type dayMetrics struct {
		postedUsers       map[uint]struct{}
		promptUsers       map[uint]struct{}
		extraUsers        map[uint]struct{}
		photoCount        int
		dailyMomentPhotos int
		extraPhotos       int
		timeCapsules      int
		privateCapsules   int
		commentCount      int
		reactionCount     int
		chatMessageCount  int
		debugErrorCount   int
		onlineUsers       map[uint]struct{}
		userActivity      map[uint]gin.H
	}

	metricsByDay := make(map[string]*dayMetrics, len(dayList))
	getMetrics := func(day string) *dayMetrics {
		if existing, ok := metricsByDay[day]; ok {
			return existing
		}
		created := &dayMetrics{
			postedUsers:  make(map[uint]struct{}),
			promptUsers:  make(map[uint]struct{}),
			extraUsers:   make(map[uint]struct{}),
			onlineUsers:  make(map[uint]struct{}),
			userActivity: make(map[uint]gin.H),
		}
		metricsByDay[day] = created
		return created
	}

	for _, photo := range photos {
		metrics := getMetrics(photo.Day)
		metrics.photoCount++
		metrics.postedUsers[photo.UserID] = struct{}{}
		if strings.TrimSpace(photo.CapsuleMode) != "" {
			metrics.timeCapsules++
		}
		if photo.CapsulePrivate {
			metrics.privateCapsules++
		}
		if prompt, ok := promptByDay[photo.Day]; ok && prompt.TriggeredAt != nil && prompt.UploadUntil != nil &&
			!photo.CreatedAt.Before(*prompt.TriggeredAt) && !photo.CreatedAt.After(*prompt.UploadUntil) {
			metrics.dailyMomentPhotos++
			metrics.promptUsers[photo.UserID] = struct{}{}
		} else {
			metrics.extraPhotos++
			metrics.extraUsers[photo.UserID] = struct{}{}
		}
	}
	for _, row := range comments {
		metrics := getMetrics(row.CreatedAt.In(s.Location).Format("2006-01-02"))
		metrics.commentCount++
	}
	for _, row := range reactions {
		metrics := getMetrics(row.CreatedAt.In(s.Location).Format("2006-01-02"))
		metrics.reactionCount++
	}
	for _, row := range chats {
		metrics := getMetrics(row.CreatedAt.In(s.Location).Format("2006-01-02"))
		metrics.chatMessageCount++
	}
	for _, row := range debugLogs {
		kind := strings.ToLower(strings.TrimSpace(row.Type))
		if strings.Contains(kind, "failed") || strings.Contains(kind, "error") || strings.Contains(kind, "crash") {
			metrics := getMetrics(row.CreatedAt.In(s.Location).Format("2006-01-02"))
			metrics.debugErrorCount++
		}
	}
	for _, row := range activities {
		metrics := getMetrics(row.Day)
		metrics.onlineUsers[row.UserID] = struct{}{}
		name := strings.TrimSpace(row.User.Username)
		if name == "" {
			name = strings.TrimSpace(usernameByID[row.UserID])
		}
		metrics.userActivity[row.UserID] = gin.H{
			"userId":       row.UserID,
			"username":     name,
			"firstSeenAt":  row.FirstSeenAt,
			"lastSeenAt":   row.LastSeenAt,
			"requestCount": row.RequestCount,
			"posted":       false,
			"postedPrompt": false,
			"postedExtra":  false,
		}
	}

	userPostedDaySet := make(map[uint]map[string]bool)
	userPromptDaySet := make(map[uint]map[string]bool)
	userExtraDaySet := make(map[uint]map[string]bool)
	for _, day := range dayList {
		metrics := getMetrics(day)
		for userID := range metrics.postedUsers {
			if _, ok := userPostedDaySet[userID]; !ok {
				userPostedDaySet[userID] = make(map[string]bool)
			}
			userPostedDaySet[userID][day] = true
		}
		for userID := range metrics.promptUsers {
			if _, ok := userPromptDaySet[userID]; !ok {
				userPromptDaySet[userID] = make(map[string]bool)
			}
			userPromptDaySet[userID][day] = true
		}
		for userID := range metrics.extraUsers {
			if _, ok := userExtraDaySet[userID]; !ok {
				userExtraDaySet[userID] = make(map[string]bool)
			}
			userExtraDaySet[userID][day] = true
		}
		for userID, row := range metrics.userActivity {
			_, posted := metrics.postedUsers[userID]
			_, postedPrompt := metrics.promptUsers[userID]
			_, postedExtra := metrics.extraUsers[userID]
			row["posted"] = posted
			row["postedPrompt"] = postedPrompt
			row["postedExtra"] = postedExtra
			metrics.userActivity[userID] = row
		}
	}

	items := make([]gin.H, 0, len(dayList))
	anomalies := make([]gin.H, 0, len(dayList))
	timeseries := make([]gin.H, 0, len(dayList))
	conversion := make([]gin.H, 0, len(dayList))
	totalPhotos := 0
	totalPromptPhotos := 0
	totalExtraPhotos := 0
	totalCapsulePhotos := 0
	totalPostedUsers := 0
	totalPromptUsers := 0
	totalExtraUsers := 0
	totalOnlineUsers := 0
	totalDebugErrors := 0
	totalDaysWithPosts := 0
	totalDaysOnTime := 0
	totalDaysWithTriggerPerformance := 0
	totalTriggerDelayAbs := 0
	totalRequestsAllDays := 0
	for _, day := range dayList {
		metrics := getMetrics(day)
		plan, hasPlan := planByDay[day]
		prompt, hasPrompt := promptByDay[day]
		onlineTrackingAvailable := trackingAvailableFrom != "" && day >= trackingAvailableFrom
		onlineUsersCount := 0
		if onlineTrackingAvailable {
			onlineUsersCount = len(metrics.onlineUsers)
		}
		userActivityRows := make([]gin.H, 0, len(metrics.userActivity))
		for _, row := range metrics.userActivity {
			userActivityRows = append(userActivityRows, row)
		}
		sort.Slice(userActivityRows, func(i, j int) bool {
			a := strings.ToLower(strings.TrimSpace(fmt.Sprint(userActivityRows[i]["username"])))
			b := strings.ToLower(strings.TrimSpace(fmt.Sprint(userActivityRows[j]["username"])))
			if a == b {
				return fmt.Sprint(userActivityRows[i]["userId"]) < fmt.Sprint(userActivityRows[j]["userId"])
			}
			return a < b
		})
		triggerDelayMinutes := 0
		onTime := false
		if hasPlan && hasPrompt && prompt.TriggeredAt != nil {
			triggerDelayMinutes = int(math.Round(prompt.TriggeredAt.Sub(plan.PlannedAt).Minutes()))
			onTime = triggerDelayMinutes >= -2 && triggerDelayMinutes <= 2
		}
		promptPhotoRatio := 0.0
		if metrics.photoCount > 0 {
			promptPhotoRatio = float64(metrics.dailyMomentPhotos) / float64(metrics.photoCount)
		}
		extraPhotoRatio := 0.0
		if metrics.photoCount > 0 {
			extraPhotoRatio = float64(metrics.extraPhotos) / float64(metrics.photoCount)
		}
		capsulePhotoRatio := 0.0
		if metrics.photoCount > 0 {
			capsulePhotoRatio = float64(metrics.timeCapsules) / float64(metrics.photoCount)
		}
		avgRequestsPerOnlineUser := 0.0
		totalRequests := 0
		for _, row := range userActivityRows {
			totalRequests += int(asInt64(row["requestCount"]))
		}
		if onlineUsersCount > 0 {
			avgRequestsPerOnlineUser = float64(totalRequests) / float64(onlineUsersCount)
		}
		isEmptyDay := !hasPlan &&
			!(hasPrompt && (prompt.TriggeredAt != nil || prompt.UploadUntil != nil)) &&
			metrics.photoCount == 0 &&
			metrics.commentCount == 0 &&
			metrics.reactionCount == 0 &&
			metrics.chatMessageCount == 0 &&
			onlineUsersCount == 0 &&
			metrics.debugErrorCount == 0
		if excludeEmpty && isEmptyDay {
			continue
		}
		row := gin.H{
			"day":                     day,
			"plannedAt":               nil,
			"triggeredAt":             nil,
			"uploadUntil":             nil,
			"source":                  "auto",
			"triggerSource":           "",
			"requestedByUser":         "",
			"onlineUsersCount":        nil,
			"postedUsersCount":        len(metrics.postedUsers),
			"dailyMomentUsersCount":   len(metrics.promptUsers),
			"extraUsersCount":         len(metrics.extraUsers),
			"photoCount":              metrics.photoCount,
			"dailyMomentPhotoCount":   metrics.dailyMomentPhotos,
			"extraPhotoCount":         metrics.extraPhotos,
			"timeCapsuleCount":        metrics.timeCapsules,
			"privateCapsuleCount":     metrics.privateCapsules,
			"commentCount":            metrics.commentCount,
			"reactionCount":           metrics.reactionCount,
			"chatMessageCount":        metrics.chatMessageCount,
			"debugErrorCount":         metrics.debugErrorCount,
			"onlineTrackingAvailable": onlineTrackingAvailable,
			"userActivity":            userActivityRows,
			"analytics": gin.H{
				"promptPhotoRatio":      promptPhotoRatio,
				"extraPhotoRatio":       extraPhotoRatio,
				"capsulePhotoRatio":     capsulePhotoRatio,
				"promptUserRatio":       safeRatio(len(metrics.promptUsers), maxInt(1, len(metrics.postedUsers))),
				"extraUserRatio":        safeRatio(len(metrics.extraUsers), maxInt(1, len(metrics.postedUsers))),
				"avgRequestsPerOnline":  avgRequestsPerOnlineUser,
				"triggerDelayMinutes":   triggerDelayMinutes,
				"onTimeTrigger":         onTime,
				"hasTriggerPerformance": hasPlan && hasPrompt && prompt.TriggeredAt != nil,
				"totalRequests":         totalRequests,
			},
		}
		if hasPlan {
			row["plannedAt"] = plan.PlannedAt
			if plan.IsManual {
				row["source"] = "manual"
			}
		}
		if hasPrompt {
			row["triggeredAt"] = prompt.TriggeredAt
			row["uploadUntil"] = prompt.UploadUntil
			row["triggerSource"] = prompt.TriggerSource
			row["requestedByUser"] = prompt.RequestedBy
		}
		if onlineTrackingAvailable {
			row["onlineUsersCount"] = onlineUsersCount
		}
		items = append(items, row)
		timeseries = append(timeseries, gin.H{
			"day":               day,
			"onlineUsers":       onlineUsersCount,
			"postedUsers":       len(metrics.postedUsers),
			"dailyMomentUsers":  len(metrics.promptUsers),
			"extraUsers":        len(metrics.extraUsers),
			"photoCount":        metrics.photoCount,
			"dailyMomentPhotos": metrics.dailyMomentPhotos,
			"extraPhotos":       metrics.extraPhotos,
			"capsulePhotos":     metrics.timeCapsules,
			"debugErrors":       metrics.debugErrorCount,
			"triggerDelayMin":   triggerDelayMinutes,
			"onTimeTrigger":     onTime,
		})
		conversion = append(conversion, gin.H{
			"day":              day,
			"onlineUsers":      onlineUsersCount,
			"postedUsers":      len(metrics.postedUsers),
			"dailyMomentUsers": len(metrics.promptUsers),
			"extraUsers":       len(metrics.extraUsers),
		})
		totalPhotos += metrics.photoCount
		totalPromptPhotos += metrics.dailyMomentPhotos
		totalExtraPhotos += metrics.extraPhotos
		totalCapsulePhotos += metrics.timeCapsules
		totalPostedUsers += len(metrics.postedUsers)
		totalPromptUsers += len(metrics.promptUsers)
		totalExtraUsers += len(metrics.extraUsers)
		totalOnlineUsers += onlineUsersCount
		totalDebugErrors += metrics.debugErrorCount
		totalRequestsAllDays += totalRequests
		if metrics.photoCount > 0 {
			totalDaysWithPosts++
		}
		if hasPlan && hasPrompt && prompt.TriggeredAt != nil {
			totalDaysWithTriggerPerformance++
			if onTime {
				totalDaysOnTime++
			}
			totalTriggerDelayAbs += int(math.Abs(float64(triggerDelayMinutes)))
		}
		if len(metrics.postedUsers) <= 1 && onlineUsersCount >= 4 {
			anomalies = append(anomalies, gin.H{
				"day":      day,
				"severity": "high",
				"reason":   "low participation despite activity",
				"details":  fmt.Sprintf("online=%d posted=%d", onlineUsersCount, len(metrics.postedUsers)),
			})
		}
		if metrics.extraPhotos >= 3 && metrics.dailyMomentPhotos == 0 {
			anomalies = append(anomalies, gin.H{
				"day":      day,
				"severity": "medium",
				"reason":   "extras dominate without daily moments",
				"details":  fmt.Sprintf("extras=%d daily=%d", metrics.extraPhotos, metrics.dailyMomentPhotos),
			})
		}
		if hasPlan && hasPrompt && prompt.TriggeredAt != nil && int(math.Abs(float64(triggerDelayMinutes))) >= 90 {
			anomalies = append(anomalies, gin.H{
				"day":      day,
				"severity": "medium",
				"reason":   "trigger shift is unusually large",
				"details":  fmt.Sprintf("delay=%dmin", triggerDelayMinutes),
			})
		}
		if metrics.debugErrorCount >= 5 {
			anomalies = append(anomalies, gin.H{
				"day":      day,
				"severity": "high",
				"reason":   "elevated error indicators",
				"details":  fmt.Sprintf("debugErrorCount=%d", metrics.debugErrorCount),
			})
		}
	}
	sort.Slice(timeseries, func(i, j int) bool {
		return fmt.Sprint(timeseries[i]["day"]) < fmt.Sprint(timeseries[j]["day"])
	})
	sort.Slice(conversion, func(i, j int) bool {
		return fmt.Sprint(conversion[i]["day"]) < fmt.Sprint(conversion[j]["day"])
	})

	type boardRow struct {
		UserID             uint
		Username           string
		PostedDays         int
		PromptDays         int
		ExtraDays          int
		OnlineDays         int
		ReliabilityScore   float64
		ExtraBiasScore     float64
		Participation7d    float64
		Participation30d   float64
		ParticipationDelta float64
	}
	leaderboardRaw := make([]boardRow, 0)
	for userID := range usernameByID {
		postedDays := len(userPostedDaySet[userID])
		promptDays := len(userPromptDaySet[userID])
		extraDays := len(userExtraDaySet[userID])
		onlineDays := 0
		for _, day := range dayList {
			if _, ok := metricsByDay[day]; !ok {
				continue
			}
			if _, ok := metricsByDay[day].onlineUsers[userID]; ok {
				onlineDays++
			}
		}
		reliabilityScore := safeRatio(promptDays, maxInt(1, postedDays))
		extraBias := safeRatio(extraDays, maxInt(1, postedDays))
		participation7, participation30, participationDelta := computeParticipationTrend(userPostedDaySet[userID], dayList)
		if postedDays == 0 && onlineDays == 0 {
			continue
		}
		leaderboardRaw = append(leaderboardRaw, boardRow{
			UserID:             userID,
			Username:           usernameByID[userID],
			PostedDays:         postedDays,
			PromptDays:         promptDays,
			ExtraDays:          extraDays,
			OnlineDays:         onlineDays,
			ReliabilityScore:   reliabilityScore,
			ExtraBiasScore:     extraBias,
			Participation7d:    participation7,
			Participation30d:   participation30,
			ParticipationDelta: participationDelta,
		})
	}
	reliableTop := make([]gin.H, 0)
	extraHeavyTop := make([]gin.H, 0)
	sort.Slice(leaderboardRaw, func(i, j int) bool {
		if leaderboardRaw[i].ReliabilityScore == leaderboardRaw[j].ReliabilityScore {
			return leaderboardRaw[i].PromptDays > leaderboardRaw[j].PromptDays
		}
		return leaderboardRaw[i].ReliabilityScore > leaderboardRaw[j].ReliabilityScore
	})
	for i := 0; i < len(leaderboardRaw) && i < 5; i++ {
		row := leaderboardRaw[i]
		reliableTop = append(reliableTop, gin.H{
			"userId":             row.UserID,
			"username":           row.Username,
			"postedDays":         row.PostedDays,
			"promptDays":         row.PromptDays,
			"extraDays":          row.ExtraDays,
			"onlineDays":         row.OnlineDays,
			"reliabilityScore":   row.ReliabilityScore,
			"participation7d":    row.Participation7d,
			"participation30d":   row.Participation30d,
			"participationDelta": row.ParticipationDelta,
		})
	}
	sort.Slice(leaderboardRaw, func(i, j int) bool {
		if leaderboardRaw[i].ExtraBiasScore == leaderboardRaw[j].ExtraBiasScore {
			return leaderboardRaw[i].ExtraDays > leaderboardRaw[j].ExtraDays
		}
		return leaderboardRaw[i].ExtraBiasScore > leaderboardRaw[j].ExtraBiasScore
	})
	for i := 0; i < len(leaderboardRaw) && i < 5; i++ {
		row := leaderboardRaw[i]
		extraHeavyTop = append(extraHeavyTop, gin.H{
			"userId":         row.UserID,
			"username":       row.Username,
			"postedDays":     row.PostedDays,
			"promptDays":     row.PromptDays,
			"extraDays":      row.ExtraDays,
			"extraBiasScore": row.ExtraBiasScore,
		})
	}
	sort.Slice(leaderboardRaw, func(i, j int) bool {
		return leaderboardRaw[i].Participation7d > leaderboardRaw[j].Participation7d
	})
	cohorts := make([]gin.H, 0, len(leaderboardRaw))
	for _, row := range leaderboardRaw {
		cohorts = append(cohorts, gin.H{
			"userId":             row.UserID,
			"username":           row.Username,
			"postedDays":         row.PostedDays,
			"promptDays":         row.PromptDays,
			"extraDays":          row.ExtraDays,
			"participation7d":    row.Participation7d,
			"participation30d":   row.Participation30d,
			"participationDelta": row.ParticipationDelta,
		})
	}
	avgPostedUsersPerDay := safeRatio(totalPostedUsers, maxInt(1, len(items)))
	avgOnlineUsersPerDay := safeRatio(totalOnlineUsers, maxInt(1, len(items)))
	avgRequestsPerOnlineUser := safeRatio(totalRequestsAllDays, maxInt(1, totalOnlineUsers))
	avgAbsoluteTriggerDelay := safeRatio(totalTriggerDelayAbs, maxInt(1, totalDaysWithTriggerPerformance))
	distribution := gin.H{
		"photoMix": gin.H{
			"promptRatio":  safeRatio(totalPromptPhotos, maxInt(1, totalPhotos)),
			"extraRatio":   safeRatio(totalExtraPhotos, maxInt(1, totalPhotos)),
			"capsuleRatio": safeRatio(totalCapsulePhotos, maxInt(1, totalPhotos)),
		},
		"userMix": gin.H{
			"promptRatio": safeRatio(totalPromptUsers, maxInt(1, totalPostedUsers)),
			"extraRatio":  safeRatio(totalExtraUsers, maxInt(1, totalPostedUsers)),
		},
		"rawTotals": gin.H{
			"photos":            totalPhotos,
			"dailyMomentPhotos": totalPromptPhotos,
			"extraPhotos":       totalExtraPhotos,
			"capsulePhotos":     totalCapsulePhotos,
			"postedUsersSum":    totalPostedUsers,
			"onlineUsersSum":    totalOnlineUsers,
		},
	}
	reliability := gin.H{
		"daysAnalyzed":                   len(items),
		"daysWithPosts":                  totalDaysWithPosts,
		"daysWithTriggerPerformance":     totalDaysWithTriggerPerformance,
		"onTimeTriggerDays":              totalDaysOnTime,
		"onTimeTriggerRate":              safeRatio(totalDaysOnTime, maxInt(1, totalDaysWithTriggerPerformance)),
		"avgAbsoluteTriggerDelayMinutes": avgAbsoluteTriggerDelay,
		"debugErrorIndicators":           totalDebugErrors,
		"errorIndicatorRatePerDay":       safeRatio(totalDebugErrors, maxInt(1, len(items))),
		"avgPostedUsersPerDay":           avgPostedUsersPerDay,
		"avgOnlineUsersPerDay":           avgOnlineUsersPerDay,
		"avgRequestsPerOnlineUser":       avgRequestsPerOnlineUser,
	}

	c.JSON(http.StatusOK, gin.H{
		"items":               items,
		"days":                days,
		"offset":              offset,
		"excludeEmpty":        excludeEmpty,
		"onlineTrackingSince": trackingAvailableFrom,
		"leaderboard": gin.H{
			"reliableTop":   reliableTop,
			"extraHeavyTop": extraHeavyTop,
		},
		"timeseries": timeseries,
		"distribution": gin.H{
			"photoMix":  distribution["photoMix"],
			"userMix":   distribution["userMix"],
			"rawTotals": distribution["rawTotals"],
		},
		"conversion":  conversion,
		"reliability": reliability,
		"cohorts":     cohorts,
		"anomalies":   anomalies,
	})
}

func safeRatio(numerator int, denominator int) float64 {
	if denominator <= 0 {
		return 0
	}
	return float64(numerator) / float64(denominator)
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func asInt64(v any) int64 {
	switch t := v.(type) {
	case int:
		return int64(t)
	case int64:
		return t
	case int32:
		return int64(t)
	case float64:
		return int64(t)
	default:
		return 0
	}
}

func computeParticipationTrend(userDays map[string]bool, orderedDays []string) (float64, float64, float64) {
	if len(orderedDays) == 0 {
		return 0, 0, 0
	}
	last7Window := minInt(7, len(orderedDays))
	last30Window := minInt(30, len(orderedDays))
	last7 := 0
	last30 := 0
	prev7 := 0
	prev7Window := minInt(last7Window, maxInt(0, len(orderedDays)-last7Window))
	for i := 0; i < last7Window; i++ {
		if userDays[orderedDays[i]] {
			last7++
		}
	}
	for i := 0; i < last30Window; i++ {
		if userDays[orderedDays[i]] {
			last30++
		}
	}
	for i := last7Window; i < last7Window+prev7Window; i++ {
		if i >= len(orderedDays) {
			break
		}
		if userDays[orderedDays[i]] {
			prev7++
		}
	}
	recent7 := safeRatio(last7, maxInt(1, last7Window))
	recent30 := safeRatio(last30, maxInt(1, last30Window))
	prev7Ratio := safeRatio(prev7, maxInt(1, prev7Window))
	return recent7, recent30, recent7 - prev7Ratio
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func (s *Server) handleBroadcastNotification(c *gin.Context) {
	var req struct {
		Body string `json:"body" binding:"required,min=3,max=255"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}

	tokens := s.allDeviceTokens()
	sendResult, err := s.Notifier.SendDailyPrompt(tokens, req.Body)
	s.recordPushResult(sendResult, err)
	removed := s.removeInvalidTokens(sendResult.InvalidTokens)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"error":          "broadcast failed",
			"details":        err.Error(),
			"provider":       s.Notifier.Name(),
			"sentTo":         sendResult.Sent,
			"failed":         sendResult.Failed,
			"invalidRemoved": removed,
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"ok":             true,
		"sentTo":         sendResult.Sent,
		"failed":         sendResult.Failed,
		"invalidRemoved": removed,
		"provider":       s.Notifier.Name(),
	})
}

func (s *Server) handleUserNotification(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
		return
	}
	var req struct {
		Body string `json:"body" binding:"required,min=3,max=255"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}

	var user models.User
	if err := s.DB.First(&user, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	tokens := s.userDeviceTokens(id)
	sendResult, sendErr := s.Notifier.SendDailyPrompt(tokens, req.Body)
	s.recordPushResult(sendResult, sendErr)
	removed := s.removeInvalidTokens(sendResult.InvalidTokens)
	if sendErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"error":          "user broadcast failed",
			"details":        sendErr.Error(),
			"provider":       s.Notifier.Name(),
			"userId":         id,
			"username":       user.Username,
			"sentTo":         sendResult.Sent,
			"failed":         sendResult.Failed,
			"invalidRemoved": removed,
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"ok":             true,
		"provider":       s.Notifier.Name(),
		"userId":         id,
		"username":       user.Username,
		"devices":        len(tokens),
		"sentTo":         sendResult.Sent,
		"failed":         sendResult.Failed,
		"invalidRemoved": removed,
	})
}

func (s *Server) handleAdminClearChat(c *gin.Context) {
	if err := s.DB.Session(&gorm.Session{AllowGlobalUpdate: true}).Delete(&models.ChatMessage{}).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "chat clear failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleChatList(c *gin.Context) {
	viewer, _ := userFromContext(c)
	var messages []models.ChatMessage
	if err := s.DB.Preload("User").Order("created_at desc").Limit(100).Find(&messages).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	out := make([]gin.H, 0, len(messages))
	for i := len(messages) - 1; i >= 0; i-- {
		m := messages[i]
		out = append(out, gin.H{
			"id":        m.ID,
			"body":      m.Body,
			"source":    defaultIfBlank(strings.TrimSpace(m.Source), "user"),
			"createdAt": m.CreatedAt,
			"user":      s.userPublicJSON(viewer.ID, m.User),
		})
	}
	c.JSON(http.StatusOK, gin.H{"items": out})
}

func (s *Server) handleFeedDays(c *gin.Context) {
	user, _ := userFromContext(c)
	fromDay := strings.TrimSpace(c.Query("from"))
	toDay := strings.TrimSpace(c.Query("to"))
	if (fromDay == "") != (toDay == "") {
		c.JSON(http.StatusBadRequest, gin.H{"error": "from/to must be provided together"})
		return
	}
	if fromDay != "" {
		fromParsed, err := time.ParseInLocation("2006-01-02", fromDay, s.Location)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid from date"})
			return
		}
		toParsed, err := time.ParseInLocation("2006-01-02", toDay, s.Location)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid to date"})
			return
		}
		if fromParsed.After(toParsed) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "from must be before or equal to to"})
			return
		}
	}
	type row struct {
		Day string
	}
	var rows []row
	now := time.Now().In(s.Location)
	query := s.DB.Model(&models.Photo{}).
		Where("user_id = ? OR (capsule_visible_at IS NULL OR capsule_visible_at <= ?)", user.ID, now)
	if fromDay != "" {
		query = query.Where("day >= ? AND day <= ?", fromDay, toDay)
	}
	if err := query.
		Select("DISTINCT day").
		Order("day desc").
		Limit(365).
		Scan(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	today := now.Format("2006-01-02")
	hasPostedToday := true
	includeToday := fromDay == "" || (fromDay <= today && today <= toDay)
	if includeToday {
		var err error
		hasPostedToday, err = s.userHasVisiblePhotoForDay(user.ID, today, now)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
	}
	days := make([]string, 0, len(rows))
	for _, r := range rows {
		if r.Day == today && !hasPostedToday {
			continue
		}
		days = append(days, r.Day)
	}
	c.JSON(http.StatusOK, gin.H{"items": days})
}

func (s *Server) handleFeedDayStats(c *gin.Context) {
	user, _ := userFromContext(c)
	fromDay := strings.TrimSpace(c.Query("from"))
	toDay := strings.TrimSpace(c.Query("to"))
	if (fromDay == "") != (toDay == "") {
		c.JSON(http.StatusBadRequest, gin.H{"error": "from/to must be provided together"})
		return
	}
	if fromDay != "" {
		fromParsed, err := time.ParseInLocation("2006-01-02", fromDay, s.Location)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid from date"})
			return
		}
		toParsed, err := time.ParseInLocation("2006-01-02", toDay, s.Location)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid to date"})
			return
		}
		if fromParsed.After(toParsed) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "from must be before or equal to to"})
			return
		}
	}
	type dayRow struct {
		Day              string
		PostCount        int64
		ParticipantCount int64
	}
	type interactionRow struct {
		PhotoID uint
		Count   int64
	}
	var rows []dayRow
	now := time.Now().In(s.Location)
	query := s.DB.Model(&models.Photo{}).
		Select("day, COUNT(*) as post_count, COUNT(DISTINCT user_id) as participant_count").
		Where("user_id = ? OR (capsule_visible_at IS NULL OR capsule_visible_at <= ?)", user.ID, now).
		Group("day")
	if fromDay != "" {
		query = query.Where("day >= ? AND day <= ?", fromDay, toDay)
	}
	if err := query.
		Order("day desc").
		Limit(365).
		Scan(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	today := now.Format("2006-01-02")
	hasPostedToday := true
	includeToday := fromDay == "" || (fromDay <= today && today <= toDay)
	if includeToday {
		var err error
		hasPostedToday, err = s.userHasVisiblePhotoForDay(user.ID, today, now)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
	}

	visibleRows := make([]dayRow, 0, len(rows))
	visibleDays := make([]string, 0, len(rows))
	for _, r := range rows {
		if r.Day == today && !hasPostedToday {
			continue
		}
		visibleRows = append(visibleRows, r)
		visibleDays = append(visibleDays, r.Day)
	}

	var photos []models.Photo
	if len(visibleDays) > 0 {
		if err := s.DB.Preload("User").
			Where("day IN ?", visibleDays).
			Where("user_id = ? OR (capsule_visible_at IS NULL OR capsule_visible_at <= ?)", user.ID, now).
			Order("day desc, created_at desc, id desc").
			Find(&photos).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
	}

	photoIDs := make([]uint, 0, len(photos))
	for _, photo := range photos {
		photoIDs = append(photoIDs, photo.ID)
	}

	reactionCounts := make(map[uint]int64, len(photoIDs))
	commentCounts := make(map[uint]int64, len(photoIDs))
	if len(photoIDs) > 0 {
		var reactionRows []interactionRow
		if err := s.DB.Model(&models.PhotoReaction{}).
			Select("photo_id, COUNT(*) as count").
			Where("photo_id IN ?", photoIDs).
			Group("photo_id").
			Scan(&reactionRows).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
		for _, row := range reactionRows {
			reactionCounts[row.PhotoID] = row.Count
		}

		var commentRows []interactionRow
		if err := s.DB.Model(&models.PhotoComment{}).
			Select("photo_id, COUNT(*) as count").
			Where("photo_id IN ?", photoIDs).
			Group("photo_id").
			Scan(&commentRows).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
		for _, row := range commentRows {
			commentCounts[row.PhotoID] = row.Count
		}
	}

	featuredByDay := make(map[string]gin.H, len(visibleRows))
	bestByDay := make(map[string]models.Photo, len(visibleRows))
	bestReactionByDay := make(map[string]int64, len(visibleRows))
	bestCommentByDay := make(map[string]int64, len(visibleRows))
	for _, photo := range photos {
		day := photo.Day
		reactionCount := reactionCounts[photo.ID]
		commentCount := commentCounts[photo.ID]
		interactionCount := reactionCount + commentCount

		best, ok := bestByDay[day]
		if ok {
			bestReaction := bestReactionByDay[day]
			bestComment := bestCommentByDay[day]
			bestInteraction := bestReaction + bestComment
			if interactionCount < bestInteraction {
				continue
			}
			if interactionCount == bestInteraction && reactionCount < bestReaction {
				continue
			}
			if interactionCount == bestInteraction && reactionCount == bestReaction && commentCount < bestComment {
				continue
			}
			if interactionCount == bestInteraction && reactionCount == bestReaction && commentCount == bestComment {
				if photo.CreatedAt.Before(best.CreatedAt) {
					continue
				}
				if photo.CreatedAt.Equal(best.CreatedAt) && photo.ID < best.ID {
					continue
				}
			}
		}

		bestByDay[day] = photo
		bestReactionByDay[day] = reactionCount
		bestCommentByDay[day] = commentCount
		featuredByDay[day] = gin.H{
			"photoId":          photo.ID,
			"url":              fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, photo.FilePath),
			"secondUrl":        "",
			"user":             s.userPublicJSON(user.ID, photo.User),
			"reactionCount":    reactionCount,
			"commentCount":     commentCount,
			"interactionCount": interactionCount,
		}
		if photo.SecondPath != "" {
			featuredByDay[day]["secondUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, photo.SecondPath)
		}
	}

	out := make([]gin.H, 0, len(visibleRows))
	for _, r := range visibleRows {
		item := gin.H{
			"day":              r.Day,
			"count":            r.PostCount,
			"postCount":        r.PostCount,
			"participantCount": r.ParticipantCount,
			"featuredPhoto":    nil,
		}
		if featured, ok := featuredByDay[r.Day]; ok {
			item["featuredPhoto"] = featured
		}
		out = append(out, item)
	}
	c.JSON(http.StatusOK, gin.H{"items": out})
}

func (s *Server) handleDeleteChatMessage(c *gin.Context) {
	user, _ := userFromContext(c)
	chatID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid chat id"})
		return
	}

	var msg models.ChatMessage
	if err := s.DB.First(&msg, chatID).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "message not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	if msg.UserID != user.ID {
		c.JSON(http.StatusForbidden, gin.H{"error": "not allowed"})
		return
	}
	if defaultIfBlank(strings.TrimSpace(msg.Source), "user") != "user" {
		c.JSON(http.StatusForbidden, gin.H{"error": "message cannot be deleted"})
		return
	}

	if err := s.DB.Delete(&msg).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"ok": true, "deletedId": msg.ID})
}

func (s *Server) handleCommunityStats(c *gin.Context) {
	now := time.Now().In(s.Location)
	todayStart := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, s.Location)
	todayDay := now.Format("2006-01-02")
	sinceDay := now.AddDate(0, 0, -6).Format("2006-01-02")
	sinceTime := now.AddDate(0, 0, -7)

	var registeredUsers int64
	_ = s.DB.Model(&models.User{}).Count(&registeredUsers).Error

	var activeUsersToday int64
	_ = s.DB.Model(&models.Photo{}).
		Select("COUNT(DISTINCT user_id)").
		Where("day = ?", todayDay).
		Scan(&activeUsersToday).Error

	var postsToday int64
	_ = s.DB.Model(&models.Photo{}).
		Where("day = ?", todayDay).
		Count(&postsToday).Error

	var chatMessagesToday int64
	_ = s.DB.Model(&models.ChatMessage{}).
		Where("created_at >= ?", todayStart).
		Count(&chatMessagesToday).Error

	type latestRow struct {
		Username  string    `gorm:"column:username"`
		CreatedAt time.Time `gorm:"column:created_at"`
	}
	var latest latestRow
	latestFound := s.DB.Table("photos").
		Select("users.username as username, photos.created_at as created_at").
		Joins("JOIN users ON users.id = photos.user_id").
		Order("photos.created_at desc").
		Limit(1).
		Scan(&latest)

	type reactionRow struct {
		Emoji string `gorm:"column:emoji"`
		Count int64  `gorm:"column:count"`
	}
	var reactionRows []reactionRow
	_ = s.DB.Model(&models.PhotoReaction{}).
		Select("emoji, COUNT(*) as count").
		Where("created_at >= ?", sinceTime).
		Group("emoji").
		Order("count desc").
		Limit(5).
		Scan(&reactionRows).Error

	var prompts []models.DailyPrompt
	_ = s.DB.
		Where("day >= ? AND day <= ?", sinceDay, todayDay).
		Find(&prompts).Error
	promptByDay := make(map[string]models.DailyPrompt, len(prompts))
	for _, p := range prompts {
		promptByDay[p.Day] = p
	}

	var photos []models.Photo
	_ = s.DB.
		Where("day >= ? AND day <= ?", sinceDay, todayDay).
		Find(&photos).Error
	dailyMomentUsers := map[uint]struct{}{}
	for _, p := range photos {
		prompt, ok := promptByDay[p.Day]
		if !ok || prompt.TriggeredAt == nil || prompt.UploadUntil == nil {
			continue
		}
		if !p.CreatedAt.Before(*prompt.TriggeredAt) && !p.CreatedAt.After(*prompt.UploadUntil) {
			dailyMomentUsers[p.UserID] = struct{}{}
		}
	}

	participants := len(dailyMomentUsers)
	percent := 0
	if registeredUsers > 0 {
		percent = int(math.Round((float64(participants) / float64(registeredUsers)) * 100.0))
	}

	topReactions := make([]gin.H, 0, len(reactionRows))
	for _, row := range reactionRows {
		topReactions = append(topReactions, gin.H{
			"emoji": row.Emoji,
			"count": row.Count,
		})
	}

	latestActive := any(nil)
	if latestFound.Error == nil && strings.TrimSpace(latest.Username) != "" {
		latestActive = gin.H{
			"username":  latest.Username,
			"createdAt": latest.CreatedAt,
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"registeredUsers":   registeredUsers,
		"activeUsersToday":  activeUsersToday,
		"latestActiveUser":  latestActive,
		"postsToday":        postsToday,
		"chatMessagesToday": chatMessagesToday,
		"topReactions7d":    topReactions,
		"dailyMomentParticipation7d": gin.H{
			"participants": participants,
			"totalUsers":   registeredUsers,
			"percent":      percent,
		},
	})
}

func (s *Server) handleChatCreate(c *gin.Context) {
	user, _ := userFromContext(c)
	var req struct {
		Body            string `json:"body" binding:"required,min=1,max=500"`
		ClientMessageID string `json:"clientMessageId" binding:"omitempty,max=64"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	body := strings.TrimSpace(req.Body)
	if body == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "message empty"})
		return
	}
	if reportType, reportBody, ok := parseUserReportPrefix(body); ok {
		s.handleUserReportFromChat(c, user, reportType, reportBody)
		return
	}
	clientMessageID := strings.TrimSpace(req.ClientMessageID)
	if clientMessageID != "" {
		var existing models.ChatMessage
		err := s.DB.Preload("User").
			Where("user_id = ? AND client_message_id = ?", user.ID, clientMessageID).
			First(&existing).Error
		if err == nil {
			c.JSON(http.StatusOK, gin.H{
				"id":        existing.ID,
				"body":      existing.Body,
				"source":    defaultIfBlank(strings.TrimSpace(existing.Source), "user"),
				"createdAt": existing.CreatedAt,
				"user": gin.H{
					"id":            existing.User.ID,
					"username":      existing.User.Username,
					"favoriteColor": defaultColor(existing.User.FavoriteColor),
				},
			})
			return
		}
		if !errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "chat dedupe lookup failed"})
			return
		}
	}

	if handled, err := s.tryHandleChatCommand(c, user, body); handled || err != nil {
		return
	}

	if existing, ok, err := s.findRecentDuplicateChatMessage(user.ID, body, 3*time.Second); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "chat dedupe lookup failed"})
		return
	} else if ok {
		c.JSON(http.StatusOK, gin.H{
			"id":        existing.ID,
			"body":      existing.Body,
			"source":    defaultIfBlank(strings.TrimSpace(existing.Source), "user"),
			"createdAt": existing.CreatedAt,
			"user": gin.H{
				"id":            existing.User.ID,
				"username":      existing.User.Username,
				"favoriteColor": defaultColor(existing.User.FavoriteColor),
			},
		})
		return
	}

	msg := models.ChatMessage{UserID: user.ID, Body: body, Source: "user"}
	if clientMessageID != "" {
		msg.ClientMessageID = &clientMessageID
	}
	if err := s.DB.Create(&msg).Error; err != nil {
		if clientMessageID != "" {
			var existing models.ChatMessage
			findErr := s.DB.Preload("User").
				Where("user_id = ? AND client_message_id = ?", user.ID, clientMessageID).
				First(&existing).Error
			if findErr == nil {
				c.JSON(http.StatusOK, gin.H{
					"id":        existing.ID,
					"body":      existing.Body,
					"source":    defaultIfBlank(strings.TrimSpace(existing.Source), "user"),
					"createdAt": existing.CreatedAt,
					"user": gin.H{
						"id":            existing.User.ID,
						"username":      existing.User.Username,
						"favoriteColor": defaultColor(existing.User.FavoriteColor),
					},
				})
				return
			}
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	pushText := fmt.Sprintf("Neue Chat-Nachricht von %s", user.Username)
	tokens := s.chatNotificationTokens(user.ID)
	if len(tokens) > 0 {
		sendResult, sendErr := s.Notifier.Send(tokens, notify.Message{
			Title:  "Daily Chat",
			Body:   pushText,
			Type:   "chat",
			Action: "open_chat",
		})
		s.recordPushResult(sendResult, sendErr)
		s.removeInvalidTokens(sendResult.InvalidTokens)
	}
	c.JSON(http.StatusCreated, gin.H{
		"id":        msg.ID,
		"body":      msg.Body,
		"source":    defaultIfBlank(strings.TrimSpace(msg.Source), "user"),
		"createdAt": msg.CreatedAt,
		"user": gin.H{
			"id":            user.ID,
			"username":      user.Username,
			"favoriteColor": defaultColor(user.FavoriteColor),
		},
	})
}

func (s *Server) findRecentDuplicateChatMessage(userID uint, body string, window time.Duration) (models.ChatMessage, bool, error) {
	normalized := normalizeChatBodyForDedupe(body)
	if normalized == "" {
		return models.ChatMessage{}, false, nil
	}
	cutoff := time.Now().Add(-window)
	var recent []models.ChatMessage
	if err := s.DB.Preload("User").
		Where("user_id = ? AND created_at >= ?", userID, cutoff).
		Order("created_at desc").
		Limit(20).
		Find(&recent).Error; err != nil {
		return models.ChatMessage{}, false, err
	}
	for _, msg := range recent {
		if normalizeChatBodyForDedupe(msg.Body) == normalized {
			return msg, true, nil
		}
	}
	return models.ChatMessage{}, false, nil
}

func normalizeChatBodyForDedupe(v string) string {
	parts := strings.Fields(strings.TrimSpace(v))
	if len(parts) == 0 {
		return ""
	}
	return strings.ToLower(strings.Join(parts, " "))
}

func parseUserReportPrefix(body string) (string, string, bool) {
	trimmed := strings.TrimSpace(body)
	lowered := strings.ToLower(trimmed)
	switch {
	case strings.HasPrefix(lowered, "bug:"):
		return "bug", strings.TrimSpace(trimmed[4:]), true
	case strings.HasPrefix(lowered, "idee:"):
		return "idea", strings.TrimSpace(trimmed[5:]), true
	default:
		return "", "", false
	}
}

func isValidUserReportStatus(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "open", "in_review", "done", "rejected":
		return true
	default:
		return false
	}
}

func parseAdminSinceHours(raw string) (int, error) {
	sinceHours := 24
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return sinceHours, nil
	}
	n, err := strconv.Atoi(raw)
	if err != nil {
		return 0, err
	}
	if n < 1 {
		n = 1
	}
	if n > 168 {
		n = 168
	}
	return n, nil
}

func adminSinceCutoff(now time.Time, sinceHours int) time.Time {
	return now.UTC().Add(-time.Duration(sinceHours) * time.Hour)
}

func userReportJSON(row models.UserReport) gin.H {
	return gin.H{
		"id":                row.ID,
		"type":              strings.TrimSpace(row.Type),
		"body":              row.Body,
		"source":            defaultIfBlank(strings.TrimSpace(row.Source), "chat_prefix"),
		"status":            defaultIfBlank(strings.TrimSpace(row.Status), "open"),
		"githubIssueNumber": row.GithubIssueNumber,
		"createdAt":         row.CreatedAt,
		"updatedAt":         row.UpdatedAt,
		"user": gin.H{
			"id":            row.User.ID,
			"username":      row.User.Username,
			"favoriteColor": defaultColor(row.User.FavoriteColor),
		},
	}
}

func (s *Server) findRecentDuplicateUserReport(userID uint, reportType string, body string, window time.Duration) (models.UserReport, bool, error) {
	normalizedBody := normalizeChatBodyForDedupe(body)
	if normalizedBody == "" {
		return models.UserReport{}, false, nil
	}
	cutoff := time.Now().Add(-window)
	var recent []models.UserReport
	if err := s.DB.Preload("User").
		Where("user_id = ? AND type = ? AND created_at >= ?", userID, reportType, cutoff).
		Order("created_at desc, id desc").
		Limit(20).
		Find(&recent).Error; err != nil {
		return models.UserReport{}, false, err
	}
	for _, row := range recent {
		if normalizeChatBodyForDedupe(row.Body) == normalizedBody {
			return row, true, nil
		}
	}
	return models.UserReport{}, false, nil
}

func (s *Server) handleUserReportFromChat(c *gin.Context, user models.User, reportType string, body string) {
	if strings.TrimSpace(body) == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "report empty"})
		return
	}
	if existing, ok, err := s.findRecentDuplicateUserReport(user.ID, reportType, body, 10*time.Second); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "report dedupe lookup failed"})
		return
	} else if ok {
		msg := "Meldung wurde bereits an den Server geschickt."
		if reportType == "bug" {
			msg = "Bugreport wurde bereits an den Server geschickt."
		} else if reportType == "idea" {
			msg = "Idee wurde bereits an den Server geschickt."
		}
		c.JSON(http.StatusOK, gin.H{
			"report":       true,
			"reportId":     existing.ID,
			"reportType":   reportType,
			"reportStatus": existing.Status,
			"message":      msg,
		})
		return
	}

	report := models.UserReport{
		UserID: user.ID,
		Type:   reportType,
		Body:   body,
		Source: "chat_prefix",
		Status: "open",
	}
	if err := s.DB.Create(&report).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "report save failed"})
		return
	}
	successMessage := "Meldung wurde an den Server geschickt."
	if reportType == "bug" {
		successMessage = "Bugreport wurde an den Server geschickt."
	} else if reportType == "idea" {
		successMessage = "Idee wurde an den Server geschickt."
	}
	c.JSON(http.StatusCreated, gin.H{
		"report":       true,
		"reportId":     report.ID,
		"reportType":   report.Type,
		"reportStatus": report.Status,
		"message":      successMessage,
	})
}

func (s *Server) tryHandleChatCommand(c *gin.Context, user models.User, body string) (bool, error) {
	normalized := normalizeCommandValue(body)
	if normalized == "" {
		return false, nil
	}

	var cmd models.ChatCommand
	if err := s.DB.Where("enabled = ? AND command = ?", true, normalized).First(&cmd).Error; err != nil {
		return false, nil
	}
	if cmd.RequireAdmin && !user.IsAdmin {
		c.JSON(http.StatusForbidden, gin.H{"error": "command requires admin"})
		return true, errors.New("command requires admin")
	}
	if cmd.CooldownSecond > 0 && cmd.LastUsedAt != nil {
		if time.Since(*cmd.LastUsedAt) < time.Duration(cmd.CooldownSecond)*time.Second {
			c.JSON(http.StatusTooManyRequests, gin.H{"error": "command cooldown active"})
			return true, errors.New("command cooldown")
		}
	}

	var (
		prompt         models.DailyPrompt
		sendResult     notify.SendResult
		sendErr        error
		invalidRemoved int64
		chatMessage    models.ChatMessage
		hasChatMessage bool
	)

	switch cmd.Action {
	case "trigger_moment":
		var triggerErr error
		prompt, _, triggerErr = s.Prompt.TriggerNowWithSource("chat_command", &user)
		if triggerErr != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "command trigger failed"})
			return true, triggerErr
		}
		if s.Monitor != nil {
			triggerAt := time.Now().In(s.Location)
			if prompt.TriggeredAt != nil {
				triggerAt = prompt.TriggeredAt.In(s.Location)
			}
			s.markDailySpikeIfEnabled(prompt.Day, triggerAt)
		}
		if cmd.SendPush {
			pushText := renderCommandText(cmd.PushText, user.Username)
			tokens := s.allDeviceTokens()
			sendResult, sendErr = s.Notifier.SendDailyPrompt(tokens, pushText)
			s.recordPushResult(sendResult, sendErr)
			invalidRemoved = s.removeInvalidTokens(sendResult.InvalidTokens)
		}
		if cmd.PostChat {
			chatMessage = models.ChatMessage{
				UserID: user.ID,
				Body:   renderCommandText(defaultIfBlank(cmd.ResponseText, "Moment wurde von {user} angefordert."), user.Username),
				Source: "command",
			}
			if err := s.DB.Create(&chatMessage).Error; err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "command chat write failed"})
				return true, err
			}
			hasChatMessage = true
		}
	case "clear_chat":
		if err := s.DB.Session(&gorm.Session{AllowGlobalUpdate: true}).Delete(&models.ChatMessage{}).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "chat clear failed"})
			return true, err
		}
		if cmd.PostChat {
			chatMessage = models.ChatMessage{
				UserID: user.ID,
				Body:   renderCommandText(defaultIfBlank(cmd.ResponseText, "Chat wurde von {user} geleert."), user.Username),
				Source: "command",
			}
			if err := s.DB.Create(&chatMessage).Error; err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "command chat write failed"})
				return true, err
			}
			hasChatMessage = true
		}
	case "broadcast_push":
		if cmd.SendPush {
			pushText := renderCommandText(defaultIfBlank(cmd.PushText, "{user} hat eine Nachricht gesendet."), user.Username)
			tokens := s.allDeviceTokens()
			sendResult, sendErr = s.Notifier.SendDailyPrompt(tokens, pushText)
			s.recordPushResult(sendResult, sendErr)
			invalidRemoved = s.removeInvalidTokens(sendResult.InvalidTokens)
		}
		if cmd.PostChat {
			chatMessage = models.ChatMessage{
				UserID: user.ID,
				Body:   renderCommandText(defaultIfBlank(cmd.ResponseText, "Push wurde von {user} gesendet."), user.Username),
				Source: "command",
			}
			if err := s.DB.Create(&chatMessage).Error; err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "command chat write failed"})
				return true, err
			}
			hasChatMessage = true
		}
	case "send_chat_message":
		chatMessage = models.ChatMessage{
			UserID: user.ID,
			Body:   renderCommandText(defaultIfBlank(cmd.ResponseText, "Command von {user} ausgefuehrt."), user.Username),
			Source: "command",
		}
		if err := s.DB.Create(&chatMessage).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "command chat write failed"})
			return true, err
		}
		hasChatMessage = true
	default:
		c.JSON(http.StatusBadRequest, gin.H{"error": "unknown command action"})
		return true, errors.New("unknown action")
	}

	now := time.Now()
	_ = s.DB.Model(&models.ChatCommand{}).Where("id = ?", cmd.ID).Update("last_used_at", &now).Error

	resp := gin.H{
		"command":        true,
		"commandId":      cmd.ID,
		"commandValue":   cmd.Command,
		"action":         cmd.Action,
		"provider":       s.Notifier.Name(),
		"sentTo":         sendResult.Sent,
		"failed":         sendResult.Failed,
		"invalidRemoved": invalidRemoved,
	}
	if hasChatMessage {
		resp["id"] = chatMessage.ID
		resp["body"] = chatMessage.Body
		resp["source"] = defaultIfBlank(strings.TrimSpace(chatMessage.Source), "command")
		resp["createdAt"] = chatMessage.CreatedAt
		resp["user"] = gin.H{
			"id":            user.ID,
			"username":      user.Username,
			"favoriteColor": defaultColor(user.FavoriteColor),
		}
	}
	if cmd.Action == "trigger_moment" {
		resp["prompt"] = prompt
		resp["triggerSource"] = "chat_command"
		resp["requestedByUser"] = user.Username
	}
	if sendErr != nil {
		resp["notificationErr"] = sendErr.Error()
	}
	c.JSON(http.StatusCreated, resp)
	return true, nil
}

func (s *Server) handleAdminListChatCommands(c *gin.Context) {
	var cmds []models.ChatCommand
	if err := s.DB.Order("created_at asc").Find(&cmds).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": cmds})
}

func (s *Server) handleAdminCreateChatCommand(c *gin.Context) {
	var req models.ChatCommand
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	cmd, err := sanitizeChatCommand(req)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if err := s.DB.Create(&cmd).Error; err != nil {
		c.JSON(http.StatusConflict, gin.H{"error": "command exists"})
		return
	}
	c.JSON(http.StatusCreated, cmd)
}

func (s *Server) handleAdminUpdateChatCommand(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid command id"})
		return
	}
	var existing models.ChatCommand
	if err := s.DB.First(&existing, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "command not found"})
		return
	}
	var req models.ChatCommand
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	req.ID = existing.ID
	req.CreatedAt = existing.CreatedAt
	req.LastUsedAt = existing.LastUsedAt
	cmd, err := sanitizeChatCommand(req)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if err := s.DB.Save(&cmd).Error; err != nil {
		c.JSON(http.StatusConflict, gin.H{"error": "command save failed"})
		return
	}
	c.JSON(http.StatusOK, cmd)
}

func (s *Server) handleAdminDeleteChatCommand(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid command id"})
		return
	}
	if err := s.DB.Delete(&models.ChatCommand{}, id).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleDualUpload(c *gin.Context) {
	user, _ := userFromContext(c)

	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}

	if settings.MaxUploadBytes > 0 {
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, settings.MaxUploadBytes*2)
	}

	backHeader, err := c.FormFile("photo_back")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "photo_back file required"})
		return
	}
	frontHeader, err := c.FormFile("photo_front")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "photo_front file required"})
		return
	}

	kind := c.PostForm("kind")
	if kind == "" {
		kind = "prompt"
	}
	if kind != "prompt" && kind != "extra" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "kind must be prompt or extra"})
		return
	}

	now := time.Now().In(s.Location)
	day := now.Format("2006-01-02")
	todayWindowActive := s.isDailyWindowActive(day, now)

	if _, err := s.cleanupInvalidPromptOnlyPhotosForDay(day); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	hasPromptPosted, err := s.userHasPostedForDay(user.ID, day)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	if kind == "prompt" {
		if !s.isPromptUploadAllowed(day, now) {
			c.JSON(http.StatusForbidden, gin.H{"error": "prompt inactive"})
			return
		}
		if hasPromptPosted {
			c.JSON(http.StatusConflict, gin.H{"error": "Du hast heute bereits gepostet"})
			return
		}
	}
	if kind == "extra" && todayWindowActive {
		c.JSON(http.StatusForbidden, gin.H{"error": "extra unavailable during daily moment window"})
		return
	}

	capsuleMode, capsuleVisibleAt, capsulePrivate, capsuleGroupRemind, capsuleErr := parseCapsuleForm(c, kind, todayWindowActive, now)
	if capsuleErr != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": capsuleErr.Error()})
		return
	}

	backPath, err := s.saveUploadedFile(day, user.ID, backHeader)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save back failed"})
		return
	}
	frontPath, err := s.saveUploadedFile(day, user.ID, frontHeader)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save front failed"})
		return
	}
	capsulePreviewPath := ""
	capsuleSecondPreviewPath := ""
	if capsuleVisibleAt != nil {
		previewBack, previewErr := s.ensureCapsulePreview(backPath)
		if previewErr != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "capsule preview failed"})
			return
		}
		previewFront, previewFrontErr := s.ensureCapsulePreview(frontPath)
		if previewFrontErr != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "capsule preview failed"})
			return
		}
		capsulePreviewPath = previewBack
		capsuleSecondPreviewPath = previewFront
	}

	photo := models.Photo{
		UserID:                   user.ID,
		Day:                      day,
		PromptOnly:               kind == "prompt",
		FilePath:                 backPath,
		SecondPath:               frontPath,
		CapsulePreviewPath:       capsulePreviewPath,
		CapsuleSecondPreviewPath: capsuleSecondPreviewPath,
		Caption:                  c.PostForm("caption"),
		CapsuleMode:              capsuleMode,
		CapsuleVisibleAt:         capsuleVisibleAt,
		CapsulePrivate:           capsulePrivate,
		CapsuleGroupRemind:       capsuleGroupRemind,
	}
	if err := s.DB.Create(&photo).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "db write failed"})
		return
	}

	s.invalidateFeedDayCache(photo.Day)
	s.notifyPostCreated(user, photo)
	c.JSON(http.StatusCreated, gin.H{"photo": s.photoJSON(photo)})
}

func (s *Server) handleHealth(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"ok":       true,
		"version":  s.Config.AppVersion,
		"provider": s.Notifier.Name(),
		"features": gin.H{
			"chatDelete": true,
		},
	})
}

func (s *Server) handleMyPhotos(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)

	var photos []models.Photo
	if err := s.DB.Where("user_id = ?", user.ID).Order("created_at desc").Limit(120).Find(&photos).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	days := make([]string, 0, len(photos))
	daySeen := make(map[string]struct{}, len(photos))
	for _, p := range photos {
		if _, ok := daySeen[p.Day]; ok {
			continue
		}
		daySeen[p.Day] = struct{}{}
		days = append(days, p.Day)
	}

	var prompts []models.DailyPrompt
	promptByDay := make(map[string]models.DailyPrompt, len(days))
	if len(days) > 0 {
		if err := s.DB.Where("day IN ?", days).Find(&prompts).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
		for _, pr := range prompts {
			promptByDay[pr.Day] = pr
		}
	}

	out := make([]gin.H, 0, len(photos))
	for _, p := range photos {
		if p.CapsuleVisibleAt != nil && now.Before(*p.CapsuleVisibleAt) {
			continue
		}
		row := s.photoJSON(p)
		dailyMoment := false
		if prompt, ok := promptByDay[p.Day]; ok && prompt.TriggeredAt != nil && prompt.UploadUntil != nil {
			dailyMoment = !p.CreatedAt.Before(*prompt.TriggeredAt) && !p.CreatedAt.After(*prompt.UploadUntil)
		}
		row["dailyMoment"] = dailyMoment
		out = append(out, row)
	}
	c.JSON(http.StatusOK, gin.H{"items": out})
}

func (s *Server) handleDeleteMyPhoto(c *gin.Context) {
	user, _ := userFromContext(c)
	photoID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid photo id"})
		return
	}

	var photo models.Photo
	if err := s.DB.First(&photo, photoID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "photo not found"})
		return
	}
	if photo.UserID != user.ID {
		c.JSON(http.StatusForbidden, gin.H{"error": "not allowed"})
		return
	}

	if err := s.DB.Where("photo_id = ?", photo.ID).Delete(&models.PhotoReaction{}).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete reactions failed"})
		return
	}
	if err := s.DB.Where("photo_id = ?", photo.ID).Delete(&models.PhotoComment{}).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete comments failed"})
		return
	}
	if err := s.DB.Delete(&photo).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}
	if err := s.removePhotoFile(photo.FilePath); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete file failed"})
		return
	}
	if err := s.removePhotoFile(photo.SecondPath); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete second file failed"})
		return
	}
	if err := s.removePhotoFile(photo.CapsulePreviewPath); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete preview file failed"})
		return
	}
	if err := s.removePhotoFile(photo.CapsuleSecondPreviewPath); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete second preview file failed"})
		return
	}

	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, gin.H{"ok": true, "deletedId": photo.ID})
}

func (s *Server) handlePhotoInteractions(c *gin.Context) {
	user, _ := userFromContext(c)
	photoID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid photo id"})
		return
	}

	photo, err := s.loadPhotoForInteraction(photoID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "photo not found"})
		return
	}
	if ok, lockErr := s.ensurePhotoVisibleToUser(user.ID, photo); !ok {
		c.JSON(http.StatusForbidden, gin.H{"error": lockErr})
		return
	}

	out, err := s.photoInteractionsPayload(photo, user.ID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, out)
}

func (s *Server) handlePhotoReaction(c *gin.Context) {
	user, _ := userFromContext(c)
	photoID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid photo id"})
		return
	}
	var req struct {
		Emoji string `json:"emoji" binding:"required,max=16"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	emoji := strings.TrimSpace(req.Emoji)
	if emoji == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "emoji required"})
		return
	}

	photo, err := s.loadPhotoForInteraction(photoID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "photo not found"})
		return
	}
	if ok, lockErr := s.ensurePhotoVisibleToUser(user.ID, photo); !ok {
		c.JSON(http.StatusForbidden, gin.H{"error": lockErr})
		return
	}

	var existing models.PhotoReaction
	findErr := s.DB.Where("photo_id = ? AND user_id = ?", photoID, user.ID).First(&existing).Error
	shouldNotify := false
	if findErr == nil {
		if existing.Emoji == emoji {
			if err := s.DB.Delete(&existing).Error; err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "reaction delete failed"})
				return
			}
		} else {
			if err := s.DB.Model(&existing).Update("emoji", emoji).Error; err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "reaction update failed"})
				return
			}
			shouldNotify = true
		}
	} else if errors.Is(findErr, gorm.ErrRecordNotFound) {
		row := models.PhotoReaction{
			PhotoID: photoID,
			UserID:  user.ID,
			Emoji:   emoji,
		}
		if err := s.DB.Create(&row).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "reaction create failed"})
			return
		}
		shouldNotify = true
	} else {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "reaction query failed"})
		return
	}

	out, err := s.photoInteractionsPayload(photo, user.ID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if shouldNotify {
		s.notifyPhotoReaction(user, photo)
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, out)
}

func (s *Server) handlePhotoComment(c *gin.Context) {
	user, _ := userFromContext(c)
	photoID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid photo id"})
		return
	}
	var req struct {
		Body string `json:"body" binding:"required,max=500"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	body := strings.TrimSpace(req.Body)
	if body == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "comment required"})
		return
	}

	photo, err := s.loadPhotoForInteraction(photoID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "photo not found"})
		return
	}
	if ok, lockErr := s.ensurePhotoVisibleToUser(user.ID, photo); !ok {
		c.JSON(http.StatusForbidden, gin.H{"error": lockErr})
		return
	}

	comment := models.PhotoComment{
		PhotoID: photoID,
		UserID:  user.ID,
		Body:    body,
	}
	if err := s.DB.Create(&comment).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "comment create failed"})
		return
	}

	out, err := s.photoInteractionsPayload(photo, user.ID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	s.notifyPhotoComment(user, photo)
	c.JSON(http.StatusCreated, out)
}

type monthReliableRow struct {
	UserID        uint
	Username      string
	FavoriteColor string
	Count         int64
}

type spontaneousRow struct {
	Day       string
	UserID    uint
	Username  string
	CreatedAt time.Time
	DeltaSec  int64
}

func (s *Server) monthlyRecapForDay(day string, viewerUserID uint) (gin.H, error) {
	dayTime, err := time.ParseInLocation("2006-01-02", day, s.Location)
	if err != nil {
		return nil, nil
	}
	monthStart := time.Date(dayTime.Year(), dayTime.Month(), 1, 0, 0, 0, 0, s.Location)
	nextMonthStart := monthStart.AddDate(0, 1, 0)
	if time.Now().In(s.Location).Before(nextMonthStart) {
		return nil, nil
	}

	monthPrefix := dayTime.Format("2006-01")
	var maxPhotoDay string
	if err := s.DB.Model(&models.Photo{}).
		Where("day LIKE ?", monthPrefix+"-%").
		Select("MAX(day)").Scan(&maxPhotoDay).Error; err != nil {
		return nil, err
	}
	if maxPhotoDay == "" || maxPhotoDay != day {
		return nil, nil
	}

	monthEnd := nextMonthStart.Add(-time.Second)
	startStr := monthStart.Format("2006-01-02")
	endStr := monthEnd.Format("2006-01-02")

	var yourMoments int64
	if err := s.DB.Model(&models.Photo{}).
		Where("user_id = ? AND prompt_only = ? AND day >= ? AND day <= ?", viewerUserID, true, startStr, endStr).
		Count(&yourMoments).Error; err != nil {
		return nil, err
	}

	var reliable monthReliableRow
	_ = s.DB.Table("photos").
		Select("photos.user_id as user_id, users.username as username, users.favorite_color as favorite_color, COUNT(*) as count").
		Joins("JOIN users ON users.id = photos.user_id").
		Where("photos.prompt_only = ? AND photos.day >= ? AND photos.day <= ?", true, startStr, endStr).
		Group("photos.user_id, users.username, users.favorite_color").
		Order("count DESC, users.username ASC").
		Limit(1).
		Scan(&reliable).Error

	spontaneous := make([]spontaneousRow, 0, 5)
	_ = s.DB.Table("photos").
		Select("photos.day as day, photos.user_id as user_id, users.username as username, photos.created_at as created_at, CAST((julianday(photos.created_at)-julianday(daily_prompts.triggered_at))*86400 AS INTEGER) as delta_sec").
		Joins("JOIN users ON users.id = photos.user_id").
		Joins("JOIN daily_prompts ON daily_prompts.day = photos.day").
		Where("photos.prompt_only = ? AND photos.day >= ? AND photos.day <= ? AND daily_prompts.triggered_at IS NOT NULL", true, startStr, endStr).
		Order("delta_sec ASC, photos.created_at ASC").
		Limit(5).
		Scan(&spontaneous).Error

	fastest := make([]gin.H, 0, len(spontaneous))
	for _, row := range spontaneous {
		minutes := row.DeltaSec / 60
		if minutes < 0 {
			minutes = 0
		}
		fastest = append(fastest, gin.H{
			"day":                 row.Day,
			"userId":              row.UserID,
			"username":            row.Username,
			"minutesAfterTrigger": minutes,
			"createdAt":           row.CreatedAt,
		})
	}

	monthLabel := germanMonthLabel(monthStart)
	recap := gin.H{
		"month":          monthPrefix,
		"monthLabel":     monthLabel,
		"yourMoments":    yourMoments,
		"topSpontaneous": fastest,
	}
	if reliable.UserID != 0 {
		recap["mostReliableUser"] = gin.H{
			"id":            reliable.UserID,
			"username":      reliable.Username,
			"favoriteColor": defaultColor(reliable.FavoriteColor),
			"count":         reliable.Count,
		}
	}
	return recap, nil
}

func germanMonthLabel(t time.Time) string {
	names := []string{
		"Januar", "Februar", "Maerz", "April", "Mai", "Juni",
		"Juli", "August", "September", "Oktober", "November", "Dezember",
	}
	idx := int(t.Month()) - 1
	if idx < 0 || idx >= len(names) {
		return t.Format("2006-01")
	}
	return fmt.Sprintf("%s %d", names[idx], t.Year())
}

func (s *Server) saveUploadedFile(day string, userID uint, header *multipart.FileHeader) (string, error) {
	src, err := header.Open()
	if err != nil {
		return "", err
	}
	defer src.Close()
	ext := strings.ToLower(filepath.Ext(header.Filename))
	return s.Store.SavePhoto(day, userID, src, ext)
}

func (s *Server) ensureCapsulePreview(relPath string) (string, error) {
	cleanRel := filepath.ToSlash(strings.TrimSpace(relPath))
	if cleanRel == "" {
		return "", errors.New("empty photo path")
	}
	ext := filepath.Ext(cleanRel)
	base := strings.TrimSuffix(cleanRel, ext)
	previewRel := filepath.ToSlash(filepath.Join("capsule-previews", base+"_preview.jpg"))
	previewFull := filepath.Join(s.Config.UploadDir, previewRel)
	if _, err := os.Stat(previewFull); err == nil {
		return previewRel, nil
	}

	srcFull := filepath.Join(s.Config.UploadDir, cleanRel)
	srcFile, err := os.Open(srcFull)
	if err != nil {
		return "", err
	}
	defer srcFile.Close()

	img, _, decodeErr := image.Decode(srcFile)
	if decodeErr != nil {
		img = buildFallbackPreviewImage()
	}
	blurred := buildBlurPreview(img)

	if err := os.MkdirAll(filepath.Dir(previewFull), 0o755); err != nil {
		return "", err
	}
	outFile, err := os.Create(previewFull)
	if err != nil {
		return "", err
	}
	defer outFile.Close()
	if err := jpeg.Encode(outFile, blurred, &jpeg.Options{Quality: 55}); err != nil {
		return "", err
	}
	return previewRel, nil
}

func buildFallbackPreviewImage() *image.RGBA {
	w, h := 96, 96
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			shade := uint8(90 + (x+y)%30)
			img.SetRGBA(x, y, color.RGBA{R: shade, G: shade, B: shade + 10, A: 255})
		}
	}
	return img
}

func buildBlurPreview(src image.Image) *image.RGBA {
	b := src.Bounds()
	srcW := maxInt(1, b.Dx())
	srcH := maxInt(1, b.Dy())
	targetMax := 640
	targetW := srcW
	targetH := srcH
	if targetW > targetMax || targetH > targetMax {
		if targetW >= targetH {
			targetH = maxInt(1, int(float64(targetH)*float64(targetMax)/float64(targetW)))
			targetW = targetMax
		} else {
			targetW = maxInt(1, int(float64(targetW)*float64(targetMax)/float64(targetH)))
			targetH = targetMax
		}
	}
	normalized := scaleImageNearest(src, targetW, targetH)
	smallW := maxInt(10, targetW/18)
	smallH := maxInt(10, targetH/18)
	pixelated := scaleImageNearest(normalized, smallW, smallH)
	preview := scaleImageNearest(pixelated, targetW, targetH)
	applyDimOverlay(preview, color.RGBA{R: 18, G: 26, B: 48, A: 74})
	return preview
}

func scaleImageNearest(src image.Image, width int, height int) *image.RGBA {
	if width < 1 {
		width = 1
	}
	if height < 1 {
		height = 1
	}
	srcBounds := src.Bounds()
	srcW := maxInt(1, srcBounds.Dx())
	srcH := maxInt(1, srcBounds.Dy())
	dst := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		sy := srcBounds.Min.Y + (y*srcH)/height
		for x := 0; x < width; x++ {
			sx := srcBounds.Min.X + (x*srcW)/width
			dst.Set(x, y, src.At(sx, sy))
		}
	}
	return dst
}

func applyDimOverlay(img *image.RGBA, overlay color.RGBA) {
	b := img.Bounds()
	alpha := float64(overlay.A) / 255.0
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			cr, cg, cb, ca := img.At(x, y).RGBA()
			r := uint8(float64(cr>>8)*(1-alpha) + float64(overlay.R)*alpha)
			g := uint8(float64(cg>>8)*(1-alpha) + float64(overlay.G)*alpha)
			bl := uint8(float64(cb>>8)*(1-alpha) + float64(overlay.B)*alpha)
			img.SetRGBA(x, y, color.RGBA{R: r, G: g, B: bl, A: uint8(ca >> 8)})
		}
	}
}

func (s *Server) saveAvatarFile(userID uint, header *multipart.FileHeader) (string, error) {
	src, err := header.Open()
	if err != nil {
		return "", err
	}
	defer src.Close()

	ext := strings.ToLower(strings.TrimSpace(filepath.Ext(header.Filename)))
	switch ext {
	case ".jpg", ".jpeg", ".png", ".webp":
	default:
		ext = ".jpg"
	}
	fileName := fmt.Sprintf("u%d_%d%s", userID, time.Now().UnixNano(), ext)
	relPath := filepath.ToSlash(filepath.Join("avatars", fileName))
	fullPath := filepath.Join(s.Config.UploadDir, relPath)
	if err := os.MkdirAll(filepath.Dir(fullPath), 0o755); err != nil {
		return "", err
	}
	dst, err := os.Create(fullPath)
	if err != nil {
		return "", err
	}
	defer dst.Close()
	if _, err := io.Copy(dst, src); err != nil {
		return "", err
	}
	return relPath, nil
}

func (s *Server) removePhotoFile(relPath string) error {
	rel := strings.TrimSpace(relPath)
	if rel == "" {
		return nil
	}
	fullPath := filepath.Join(s.Config.UploadDir, rel)
	err := os.Remove(fullPath)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

func (s *Server) photoJSON(p models.Photo) gin.H {
	out := gin.H{
		"id":                 p.ID,
		"day":                p.Day,
		"promptOnly":         p.PromptOnly,
		"caption":            p.Caption,
		"createdAt":          p.CreatedAt,
		"capsuleMode":        p.CapsuleMode,
		"capsuleVisibleAt":   p.CapsuleVisibleAt,
		"capsulePrivate":     false,
		"capsuleGroupRemind": p.CapsuleGroupRemind,
		"url":                fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.FilePath),
		"capsuleLocked":      false,
		"capsulePreviewUrl":  "",
	}
	if p.SecondPath != "" {
		out["secondUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.SecondPath)
	}
	if strings.TrimSpace(p.CapsulePreviewPath) != "" {
		out["capsulePreviewUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.CapsulePreviewPath)
	}
	return out
}

func (s *Server) avatarURL(path string) string {
	cleaned := strings.TrimSpace(path)
	if cleaned == "" {
		return ""
	}
	if strings.HasPrefix(cleaned, "http://") || strings.HasPrefix(cleaned, "https://") {
		return cleaned
	}
	cleaned = strings.TrimPrefix(cleaned, "/")
	return fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, cleaned)
}

func statusIsActive(u models.User, now time.Time) bool {
	text := strings.TrimSpace(u.StatusText)
	emoji := strings.TrimSpace(u.StatusEmoji)
	if text == "" && emoji == "" {
		return false
	}
	if u.StatusExpiresAt == nil {
		return true
	}
	return !now.After(*u.StatusExpiresAt)
}

func (s *Server) userOwnJSON(u models.User) gin.H {
	avatarURL := ""
	if strings.TrimSpace(u.AvatarPath) != "" {
		avatarURL = s.avatarURL(u.AvatarPath)
	}
	return gin.H{
		"id":                            u.ID,
		"username":                      u.Username,
		"isAdmin":                       u.IsAdmin,
		"favoriteColor":                 defaultColor(u.FavoriteColor),
		"chatPushEnabled":               u.ChatPushEnabled,
		"inviteRegistrationPushEnabled": u.InviteRegistrationPushEnabled,
		"photoReactionPushEnabled":      u.PhotoReactionPushEnabled,
		"photoCommentPushEnabled":       u.PhotoCommentPushEnabled,
		"allowPhotoDownload":            u.AllowPhotoDownload,
		"avatarUrl":                     avatarURL,
		"bio":                           strings.TrimSpace(u.Bio),
		"statusText":                    strings.TrimSpace(u.StatusText),
		"statusEmoji":                   strings.TrimSpace(u.StatusEmoji),
		"statusExpiresAt":               u.StatusExpiresAt,
		"profileVisible":                u.ProfileVisible,
		"avatarVisible":                 u.AvatarVisible,
		"bioVisible":                    u.BioVisible,
		"statusVisible":                 u.StatusVisible,
		"quietHoursEnabled":             u.QuietHoursEnabled,
		"quietHoursStart":               defaultIfBlank(u.QuietHoursStart, "22:00"),
		"quietHoursEnd":                 defaultIfBlank(u.QuietHoursEnd, "07:00"),
		"diagnosticsConsentGranted":     u.DiagnosticsConsentGranted,
		"diagnosticsConsentUpdatedAt":   u.DiagnosticsConsentUpdatedAt,
		"diagnosticsConsentSource":      strings.TrimSpace(u.DiagnosticsConsentSource),
		"createdAt":                     u.CreatedAt,
	}
}

func (s *Server) userPublicJSON(viewerID uint, u models.User) gin.H {
	own := viewerID == u.ID
	out := gin.H{
		"id":                            u.ID,
		"username":                      u.Username,
		"isAdmin":                       u.IsAdmin,
		"favoriteColor":                 defaultColor(u.FavoriteColor),
		"chatPushEnabled":               false,
		"inviteRegistrationPushEnabled": false,
		"photoReactionPushEnabled":      false,
		"photoCommentPushEnabled":       false,
		"allowPhotoDownload":            u.AllowPhotoDownload,
		"avatarUrl":                     "",
		"bio":                           "",
		"statusText":                    "",
		"statusEmoji":                   "",
		"statusExpiresAt":               nil,
		"profileVisible":                false,
		"avatarVisible":                 false,
		"bioVisible":                    false,
		"statusVisible":                 false,
		"quietHoursEnabled":             false,
		"quietHoursStart":               "22:00",
		"quietHoursEnd":                 "07:00",
		"createdAt":                     u.CreatedAt,
	}
	now := time.Now().In(s.Location)
	if own {
		for k, v := range s.userOwnJSON(u) {
			out[k] = v
		}
		return out
	}

	out["profileVisible"] = u.ProfileVisible
	if !u.ProfileVisible {
		return out
	}

	if u.AvatarVisible && strings.TrimSpace(u.AvatarPath) != "" {
		out["avatarVisible"] = true
		out["avatarUrl"] = s.avatarURL(u.AvatarPath)
	} else {
		out["avatarVisible"] = false
	}
	if u.BioVisible && strings.TrimSpace(u.Bio) != "" {
		out["bioVisible"] = true
		out["bio"] = strings.TrimSpace(u.Bio)
	} else {
		out["bioVisible"] = false
	}
	if u.StatusVisible && statusIsActive(u, now) {
		out["statusVisible"] = true
		out["statusText"] = strings.TrimSpace(u.StatusText)
		out["statusEmoji"] = strings.TrimSpace(u.StatusEmoji)
		out["statusExpiresAt"] = u.StatusExpiresAt
	}
	return out
}

func (s *Server) loadVisibleUserPhotos(viewerID uint, targetID uint) ([]gin.H, error) {
	var photos []models.Photo
	if err := s.DB.Where("user_id = ?", targetID).Order("created_at desc").Limit(120).Find(&photos).Error; err != nil {
		return nil, err
	}
	now := time.Now().In(s.Location)
	out := make([]gin.H, 0, len(photos))
	for _, p := range photos {
		locked := p.CapsuleVisibleAt != nil && now.Before(*p.CapsuleVisibleAt)
		if locked {
			row := s.profilePhotoJSON(p, locked)
			out = append(out, row)
			continue
		}
		out = append(out, s.profilePhotoJSON(p, false))
	}
	return out, nil
}

func (s *Server) profilePhotoJSON(p models.Photo, locked bool) gin.H {
	row := s.photoJSON(p)
	row["capsulePrivate"] = false
	row["capsuleLocked"] = locked
	if !locked {
		return row
	}
	row["secondUrl"] = ""

	previewPath := strings.TrimSpace(p.CapsulePreviewPath)
	if previewPath == "" {
		if generatedPath, err := s.ensureCapsulePreview(p.FilePath); err == nil {
			previewPath = generatedPath
			_ = s.DB.Model(&models.Photo{}).Where("id = ?", p.ID).Update("capsule_preview_path", generatedPath).Error
		}
	}
	if previewPath != "" {
		row["capsulePreviewUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, previewPath)
		row["url"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, previewPath)
	} else {
		row["url"] = ""
	}
	secondPreview := strings.TrimSpace(p.CapsuleSecondPreviewPath)
	if secondPreview == "" && strings.TrimSpace(p.SecondPath) != "" {
		if generatedSecond, err := s.ensureCapsulePreview(p.SecondPath); err == nil {
			secondPreview = generatedSecond
			_ = s.DB.Model(&models.Photo{}).Where("id = ?", p.ID).Update("capsule_second_preview_path", generatedSecond).Error
		}
	}
	if secondPreview != "" {
		row["secondUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, secondPreview)
	}
	return row
}

func (s *Server) allDeviceTokens() []string {
	var rows []models.DeviceToken
	_ = s.DB.Find(&rows).Error
	tokens := make([]string, 0, len(rows))
	for _, t := range rows {
		tokens = append(tokens, t.Token)
	}
	return tokens
}

func parseUintParam(v string) (uint, error) {
	n, err := strconv.ParseUint(v, 10, 32)
	if err != nil {
		return 0, err
	}
	return uint(n), nil
}

func toAdminUser(
	u models.User,
	photoCount, tokenCount int64,
	deviceNames []string,
	deviceDetails []gin.H,
	invitedByID uint,
	invitedBy string,
	invitedAt *time.Time,
	lastAppVersion string,
	lastError string,
	lastErrorAt *time.Time,
	lastProfileOkAt *time.Time,
) gin.H {
	out := gin.H{
		"id":              u.ID,
		"username":        u.Username,
		"isAdmin":         u.IsAdmin,
		"createdAt":       u.CreatedAt,
		"photoCount":      photoCount,
		"deviceCount":     tokenCount,
		"deviceNames":     deviceNames,
		"deviceDetails":   deviceDetails,
		"lastAppVersion":  strings.TrimSpace(lastAppVersion),
		"lastError":       strings.TrimSpace(lastError),
		"lastErrorAt":     lastErrorAt,
		"lastProfileOkAt": lastProfileOkAt,
	}
	if invitedByID != 0 {
		out["invitedById"] = invitedByID
		out["invitedBy"] = invitedBy
	}
	if invitedAt != nil {
		out["invitedAt"] = invitedAt
	}
	return out
}

func (s *Server) userHasPostedForDay(userID uint, day string) (bool, error) {
	var count int64
	if err := s.DB.Model(&models.Photo{}).Where("user_id = ? AND day = ? AND prompt_only = ?", userID, day, true).Count(&count).Error; err != nil {
		return false, err
	}
	return count > 0, nil
}

func (s *Server) userHasAnyPhotoForDay(userID uint, day string) (bool, error) {
	var count int64
	if err := s.DB.Model(&models.Photo{}).Where("user_id = ? AND day = ?", userID, day).Count(&count).Error; err != nil {
		return false, err
	}
	return count > 0, nil
}

func (s *Server) userHasVisiblePhotoForDay(userID uint, day string, now time.Time) (bool, error) {
	var count int64
	if err := s.DB.Model(&models.Photo{}).
		Where("user_id = ? AND day = ?", userID, day).
		Where("capsule_visible_at IS NULL OR capsule_visible_at <= ?", now).
		Count(&count).Error; err != nil {
		return false, err
	}
	return count > 0, nil
}

type photoReactionCountRow struct {
	Emoji string
	Count int64
}

type photoReactionPreviewRow struct {
	PhotoID uint
	Emoji   string
	Count   int64
}

func (s *Server) loadPhotoForInteraction(photoID uint) (models.Photo, error) {
	var photo models.Photo
	if err := s.DB.First(&photo, photoID).Error; err != nil {
		return models.Photo{}, err
	}
	return photo, nil
}

func (s *Server) feedInteractionPreview(photoIDs []uint) (map[uint][]gin.H, map[uint][]gin.H, error) {
	reactionByPhoto := make(map[uint][]gin.H)
	commentByPhoto := make(map[uint][]gin.H)
	if len(photoIDs) == 0 {
		return reactionByPhoto, commentByPhoto, nil
	}
	commentLimit := 10
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err == nil {
		settings = normalizeSettings(settings)
		commentLimit = settings.FeedCommentPreviewLimit
	}

	var reactionRows []photoReactionPreviewRow
	reactionQueryStart := time.Now()
	if err := s.DB.Model(&models.PhotoReaction{}).
		Select("photo_id as photo_id, emoji as emoji, COUNT(*) as count").
		Where("photo_id IN ?", photoIDs).
		Group("photo_id, emoji").
		Order("count desc, emoji asc").
		Scan(&reactionRows).Error; err != nil {
		return nil, nil, err
	}
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/feed", "feed_reaction_preview_query", time.Since(reactionQueryStart))
	}
	for _, row := range reactionRows {
		reactionByPhoto[row.PhotoID] = append(reactionByPhoto[row.PhotoID], gin.H{
			"emoji": row.Emoji,
			"count": row.Count,
		})
	}

	type commentPreviewRow struct {
		PhotoID       uint
		ID            uint
		Body          string
		CreatedAt     time.Time
		UserID        uint
		Username      string
		FavoriteColor string
	}
	rows := make([]commentPreviewRow, 0, len(photoIDs)*commentLimit)
	commentQueryStart := time.Now()
	if err := s.DB.Raw(`
		SELECT photo_id, id, body, created_at, user_id, username, favorite_color
		FROM (
			SELECT
				pc.photo_id AS photo_id,
				pc.id AS id,
				pc.body AS body,
				pc.created_at AS created_at,
				u.id AS user_id,
				u.username AS username,
				u.favorite_color AS favorite_color,
				ROW_NUMBER() OVER (PARTITION BY pc.photo_id ORDER BY pc.created_at DESC, pc.id DESC) AS rn
			FROM photo_comments pc
			JOIN users u ON u.id = pc.user_id
			WHERE pc.photo_id IN ?
		)
		WHERE rn <= ?
		ORDER BY created_at DESC, id DESC
	`, photoIDs, commentLimit).Scan(&rows).Error; err != nil {
		return nil, nil, err
	}
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/feed", "feed_comment_preview_query", time.Since(commentQueryStart))
	}
	for _, item := range rows {
		commentByPhoto[item.PhotoID] = append(commentByPhoto[item.PhotoID], gin.H{
			"id":        item.ID,
			"body":      item.Body,
			"createdAt": item.CreatedAt,
			"user": gin.H{
				"id":            item.UserID,
				"username":      item.Username,
				"favoriteColor": defaultColor(item.FavoriteColor),
			},
		})
	}
	for photoID, list := range commentByPhoto {
		for i, j := 0, len(list)-1; i < j; i, j = i+1, j-1 {
			list[i], list[j] = list[j], list[i]
		}
		commentByPhoto[photoID] = list
	}

	return reactionByPhoto, commentByPhoto, nil
}

func (s *Server) ensurePhotoVisibleToUser(userID uint, photo models.Photo) (bool, string) {
	now := time.Now().In(s.Location)
	if !photoVisibleToViewer(userID, photo, now) {
		return false, "capsule locked"
	}

	today := time.Now().In(s.Location).Format("2006-01-02")
	if photo.Day != today {
		return true, ""
	}
	if photo.UserID == userID {
		return true, ""
	}
	hasPosted, err := s.userHasVisiblePhotoForDay(userID, today, now)
	if err != nil {
		return false, "query failed"
	}
	if !hasPosted {
		return false, "Poste zuerst einen sichtbaren Beitrag, um die Beitraege der anderen zu sehen"
	}
	return true, ""
}

func (s *Server) isDailyWindowActive(day string, now time.Time) bool {
	var prompt models.DailyPrompt
	if err := s.DB.Where("day = ?", day).First(&prompt).Error; err != nil {
		return false
	}
	return isPromptWindowActive(prompt, now)
}

func (s *Server) isPromptUploadAllowed(day string, now time.Time) bool {
	var prompt models.DailyPrompt
	if err := s.DB.Where("day = ?", day).First(&prompt).Error; err != nil {
		return false
	}
	return isPromptWindowActive(prompt, now)
}

func isPromptWindowActive(prompt models.DailyPrompt, now time.Time) bool {
	if prompt.TriggeredAt == nil || prompt.UploadUntil == nil {
		return false
	}
	return !now.Before(*prompt.TriggeredAt) && !now.After(*prompt.UploadUntil)
}

func (s *Server) allowFeedRead(userID uint, now time.Time) (bool, int) {
	if s == nil || s.Monitor == nil || !s.Monitor.IsInActiveSpike(now) {
		return true, 0
	}
	if s.FeedLimiter == nil {
		return true, 0
	}
	return s.FeedLimiter.Allow(userID, now)
}

func (s *Server) shouldUseFeedCache(day string, now time.Time) bool {
	if s == nil || s.Monitor == nil || s.FeedCache == nil {
		return false
	}
	today := now.In(s.Location).Format("2006-01-02")
	if day != today {
		return false
	}
	return s.Monitor.IsInActiveSpike(now)
}

func (s *Server) feedCachedPayload(userID uint, day string, now time.Time) (gin.H, bool) {
	if s == nil || s.FeedCache == nil {
		return nil, false
	}
	return s.FeedCache.Get(userID, day, now)
}

func (s *Server) putFeedCachedPayload(userID uint, day string, payload gin.H, now time.Time) {
	if s == nil || s.FeedCache == nil {
		return
	}
	s.FeedCache.Put(userID, day, payload, now)
}

func (s *Server) invalidateFeedDayCache(day string) {
	if s == nil || s.FeedCache == nil || strings.TrimSpace(day) == "" {
		return
	}
	s.FeedCache.InvalidateDay(day)
}

func photoVisibleToViewer(userID uint, photo models.Photo, now time.Time) bool {
	if photo.UserID == userID {
		return true
	}
	if photo.CapsuleVisibleAt != nil && now.Before(*photo.CapsuleVisibleAt) {
		return false
	}
	return true
}

func invalidPromptOnlyPhotoIDs(photos []models.Photo, promptByDay map[string]models.DailyPrompt) []uint {
	ids := make([]uint, 0)
	for _, photo := range photos {
		prompt, ok := promptByDay[photo.Day]
		if !ok || !isPromptWindowActive(prompt, photo.CreatedAt) {
			ids = append(ids, photo.ID)
		}
	}
	return ids
}

func (s *Server) cleanupInvalidPromptOnlyPhotosForDay(day string) (int64, error) {
	return s.cleanupInvalidPromptOnlyPhotosSinceDay(day)
}

func (s *Server) CleanupInvalidPromptOnlyPhotosRecent(days int) (int64, error) {
	if days < 1 {
		days = 1
	}
	startDay := time.Now().In(s.Location).AddDate(0, 0, -(days - 1)).Format("2006-01-02")
	return s.cleanupInvalidPromptOnlyPhotosSinceDay(startDay)
}

func (s *Server) cleanupInvalidPromptOnlyPhotosSinceDay(startDay string) (int64, error) {
	var photos []models.Photo
	if err := s.DB.
		Where("prompt_only = ? AND day >= ?", true, startDay).
		Find(&photos).Error; err != nil {
		return 0, err
	}
	if len(photos) == 0 {
		return 0, nil
	}

	daySet := make(map[string]struct{}, len(photos))
	for _, photo := range photos {
		daySet[photo.Day] = struct{}{}
	}
	days := make([]string, 0, len(daySet))
	for day := range daySet {
		days = append(days, day)
	}

	var prompts []models.DailyPrompt
	if err := s.DB.Where("day IN ?", days).Find(&prompts).Error; err != nil {
		return 0, err
	}
	promptByDay := make(map[string]models.DailyPrompt, len(prompts))
	for _, prompt := range prompts {
		promptByDay[prompt.Day] = prompt
	}

	invalidIDs := invalidPromptOnlyPhotoIDs(photos, promptByDay)
	if len(invalidIDs) == 0 {
		return 0, nil
	}

	result := s.DB.Model(&models.Photo{}).Where("id IN ?", invalidIDs).Update("prompt_only", false)
	if result.Error != nil {
		return 0, result.Error
	}
	return result.RowsAffected, nil
}

func parseCapsuleForm(c *gin.Context, kind string, dailyWindowActive bool, now time.Time) (string, *time.Time, bool, bool, error) {
	mode := strings.ToLower(strings.TrimSpace(c.PostForm("capsule_mode")))
	_ = parseFormBool(c.PostForm("capsule_private"))
	groupRemind := parseFormBool(c.PostForm("capsule_group_remind"))

	if mode == "" {
		if groupRemind {
			return "", nil, false, false, errors.New("capsule_mode required")
		}
		return "", nil, false, false, nil
	}

	if kind != "extra" {
		return "", nil, false, false, errors.New("time capsule only allowed for extra uploads")
	}
	if dailyWindowActive {
		return "", nil, false, false, errors.New("time capsule unavailable during daily moment window")
	}

	var visibleAt time.Time
	switch mode {
	case "7d":
		visibleAt = now.AddDate(0, 0, 7)
	case "30d":
		visibleAt = now.AddDate(0, 0, 30)
	case "1y":
		visibleAt = now.AddDate(1, 0, 0)
	default:
		return "", nil, false, false, errors.New("invalid capsule_mode (allowed: 7d, 30d, 1y)")
	}
	return mode, &visibleAt, false, groupRemind, nil
}

func parseFormBool(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "true", "yes", "on":
		return true
	default:
		return false
	}
}

func (s *Server) photoInteractionsPayload(photo models.Photo, viewerID uint) (gin.H, error) {
	photoID := photo.ID
	canDownload := false
	var owner models.User
	if err := s.DB.Select("id", "allow_photo_download").First(&owner, photo.UserID).Error; err == nil {
		canDownload = owner.AllowPhotoDownload
	}

	reactionRows := make([]photoReactionCountRow, 0)
	if err := s.DB.Model(&models.PhotoReaction{}).
		Select("emoji, COUNT(*) as count").
		Where("photo_id = ?", photoID).
		Group("emoji").
		Order("count desc, emoji asc").
		Scan(&reactionRows).Error; err != nil {
		return nil, err
	}

	var my models.PhotoReaction
	myReaction := ""
	if err := s.DB.Where("photo_id = ? AND user_id = ?", photoID, viewerID).First(&my).Error; err == nil {
		myReaction = my.Emoji
	}

	var comments []models.PhotoComment
	if err := s.DB.Preload("User").
		Where("photo_id = ?", photoID).
		Order("created_at asc").
		Limit(200).
		Find(&comments).Error; err != nil {
		return nil, err
	}

	reactions := make([]gin.H, 0, len(reactionRows))
	for _, row := range reactionRows {
		reactions = append(reactions, gin.H{
			"emoji": row.Emoji,
			"count": row.Count,
		})
	}

	commentItems := make([]gin.H, 0, len(comments))
	for _, item := range comments {
		commentItems = append(commentItems, gin.H{
			"id":        item.ID,
			"body":      item.Body,
			"createdAt": item.CreatedAt,
			"user": gin.H{
				"id":            item.User.ID,
				"username":      item.User.Username,
				"favoriteColor": defaultColor(item.User.FavoriteColor),
			},
		})
	}

	return gin.H{
		"photoId":     photoID,
		"reactions":   reactions,
		"myReaction":  myReaction,
		"comments":    commentItems,
		"canDownload": canDownload,
	}, nil
}

func (s *Server) ensurePromptForPostingDay(day string) (models.DailyPrompt, error) {
	var prompt models.DailyPrompt
	err := s.DB.Where("day = ?", day).First(&prompt).Error
	if err == nil {
		if strings.TrimSpace(prompt.TriggerSource) == "" {
			prompt.TriggerSource = "daily_moment"
			if saveErr := s.DB.Save(&prompt).Error; saveErr != nil {
				return models.DailyPrompt{}, saveErr
			}
		}
		return prompt, nil
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return models.DailyPrompt{}, err
	}

	prompt = models.DailyPrompt{
		Day:           day,
		TriggerSource: "daily_moment",
	}
	if err := s.DB.Create(&prompt).Error; err != nil {
		return models.DailyPrompt{}, err
	}
	return prompt, nil
}

func (s *Server) userDeviceTokens(userID uint) []string {
	var rows []models.DeviceToken
	_ = s.DB.Where("user_id = ?", userID).Find(&rows).Error
	tokens := make([]string, 0, len(rows))
	for _, t := range rows {
		tokens = append(tokens, t.Token)
	}
	return tokens
}

func (s *Server) postNotificationTokens(senderID uint) []string {
	var rows []models.DeviceToken
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Where("users.id <> ?", senderID).
		Find(&rows).Error
	tokens := make([]string, 0, len(rows))
	for _, t := range rows {
		tokens = append(tokens, t.Token)
	}
	return tokens
}

func (s *Server) chatNotificationTokens(senderID uint) []string {
	var rows []models.DeviceToken
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Where("users.id <> ? AND users.chat_push_enabled = ?", senderID, true).
		Find(&rows).Error
	tokens := make([]string, 0, len(rows))
	for _, t := range rows {
		tokens = append(tokens, t.Token)
	}
	return tokens
}

func (s *Server) inviteRegistrationNotificationTokens() []string {
	var rows []models.DeviceToken
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Where("users.invite_registration_push_enabled = ?", true).
		Find(&rows).Error
	tokens := make([]string, 0, len(rows))
	for _, t := range rows {
		tokens = append(tokens, t.Token)
	}
	return tokens
}

func (s *Server) notifyPostCreated(author models.User, photo models.Photo) {
	// Delayed capsules should not trigger immediate post notifications.
	if photo.CapsuleVisibleAt != nil {
		return
	}
	tokens := s.postNotificationTokens(author.ID)
	if len(tokens) == 0 {
		return
	}
	body := fmt.Sprintf("%s hat gepostet", author.Username)
	sendResult, sendErr := s.Notifier.Send(tokens, notify.Message{
		Title:   "Neuer Beitrag",
		Body:    body,
		Type:    "post",
		Action:  "open_feed",
		Day:     photo.Day,
		PhotoID: int64(photo.ID),
	})
	s.recordPushResult(sendResult, sendErr)
	s.removeInvalidTokens(sendResult.InvalidTokens)
}

func (s *Server) reactionNotificationTokens(ownerID, actorID uint) []string {
	var rows []models.DeviceToken
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Where("users.id = ? AND users.photo_reaction_push_enabled = ? AND users.id <> ?", ownerID, true, actorID).
		Find(&rows).Error
	tokens := make([]string, 0, len(rows))
	for _, t := range rows {
		tokens = append(tokens, t.Token)
	}
	return tokens
}

func (s *Server) commentNotificationTokens(ownerID, actorID uint) []string {
	var rows []models.DeviceToken
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Where("users.id = ? AND users.photo_comment_push_enabled = ? AND users.id <> ?", ownerID, true, actorID).
		Find(&rows).Error
	tokens := make([]string, 0, len(rows))
	for _, t := range rows {
		tokens = append(tokens, t.Token)
	}
	return tokens
}

func (s *Server) notifyPhotoReaction(actor models.User, photo models.Photo) {
	if photo.UserID == 0 || photo.UserID == actor.ID {
		return
	}
	now := time.Now().In(s.Location)
	if photo.CapsuleVisibleAt != nil && now.Before(*photo.CapsuleVisibleAt) {
		return
	}
	tokens := s.reactionNotificationTokens(photo.UserID, actor.ID)
	if len(tokens) == 0 {
		return
	}
	body := fmt.Sprintf("%s hat auf deinen Beitrag reagiert", actor.Username)
	sendResult, sendErr := s.Notifier.Send(tokens, notify.Message{
		Title:   "Neue Reaktion",
		Body:    body,
		Type:    "photo_reaction",
		Action:  "open_feed",
		Day:     photo.Day,
		PhotoID: int64(photo.ID),
	})
	s.recordPushResult(sendResult, sendErr)
	s.removeInvalidTokens(sendResult.InvalidTokens)
}

func (s *Server) notifyPhotoComment(actor models.User, photo models.Photo) {
	if photo.UserID == 0 || photo.UserID == actor.ID {
		return
	}
	now := time.Now().In(s.Location)
	if photo.CapsuleVisibleAt != nil && now.Before(*photo.CapsuleVisibleAt) {
		return
	}
	tokens := s.commentNotificationTokens(photo.UserID, actor.ID)
	if len(tokens) == 0 {
		return
	}
	body := fmt.Sprintf("%s hat deinen Beitrag kommentiert", actor.Username)
	sendResult, sendErr := s.Notifier.Send(tokens, notify.Message{
		Title:   "Neuer Kommentar",
		Body:    body,
		Type:    "photo_comment",
		Action:  "open_feed",
		Day:     photo.Day,
		PhotoID: int64(photo.ID),
	})
	s.recordPushResult(sendResult, sendErr)
	s.removeInvalidTokens(sendResult.InvalidTokens)
}

func (s *Server) removeInvalidTokens(tokens []string) int64 {
	if len(tokens) == 0 {
		return 0
	}
	tx := s.DB.Where("token IN ?", tokens).Delete(&models.DeviceToken{})
	if tx.Error != nil {
		return 0
	}
	return tx.RowsAffected
}

func (s *Server) recordPushResult(result notify.SendResult, err error) {
	if s.Monitor == nil {
		return
	}
	s.Monitor.RecordPush(result.Sent, result.Failed, len(result.InvalidTokens), err != nil)
}

func (s *Server) specialMomentStatus(userID uint) (gin.H, error) {
	var latest models.SpecialMomentRequest
	err := s.DB.Where("user_id = ?", userID).Order("requested_at desc").First(&latest).Error
	if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, err
	}
	now := time.Now().In(s.Location)
	if errors.Is(err, gorm.ErrRecordNotFound) || latest.ID == 0 {
		return gin.H{
			"canRequest":        true,
			"requestedThisWeek": false,
			"remainingSeconds":  0,
			"nextAllowedAt":     nil,
			"lastRequestedAt":   nil,
		}, nil
	}

	nextAllowed := latest.RequestedAt.In(s.Location).Add(7 * 24 * time.Hour)
	remaining := int64(nextAllowed.Sub(now).Seconds())
	if remaining < 0 {
		remaining = 0
	}
	canRequest := remaining == 0
	return gin.H{
		"canRequest":        canRequest,
		"requestedThisWeek": !canRequest,
		"remainingSeconds":  remaining,
		"nextAllowedAt":     nextAllowed,
		"lastRequestedAt":   latest.RequestedAt,
	}, nil
}

func normalizeInviteCode(raw string) string {
	cleaned := strings.ToUpper(strings.TrimSpace(raw))
	cleaned = strings.ReplaceAll(cleaned, "-", "")
	cleaned = strings.ReplaceAll(cleaned, " ", "")
	return cleaned
}

func generateInviteCode() (string, error) {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	const size = 10
	buf := make([]byte, size)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	out := make([]byte, size)
	for i, b := range buf {
		out[i] = alphabet[int(b)%len(alphabet)]
	}
	return string(out), nil
}

func (s *Server) findActiveInviteWithUser(code string) (models.InviteCode, models.User, error) {
	return s.findActiveInviteWithUserTx(s.DB, code)
}

func (s *Server) findActiveInviteWithUserTx(tx *gorm.DB, code string) (models.InviteCode, models.User, error) {
	var invite models.InviteCode
	err := tx.Where("code = ? AND active = ? AND used_by_id IS NULL", code, true).First(&invite).Error
	if err != nil {
		return models.InviteCode{}, models.User{}, err
	}
	var inviter models.User
	if err := tx.First(&inviter, invite.UserID).Error; err != nil {
		return models.InviteCode{}, models.User{}, err
	}
	return invite, inviter, nil
}

func (s *Server) ensureActiveInviteCode(userID uint) (models.InviteCode, error) {
	var invite models.InviteCode
	err := s.DB.Where("user_id = ? AND active = ? AND used_by_id IS NULL", userID, true).
		Order("created_at desc").First(&invite).Error
	if err == nil {
		return invite, nil
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return models.InviteCode{}, err
	}

	txErr := s.DB.Transaction(func(tx *gorm.DB) error {
		var txCreateErr error
		invite, txCreateErr = s.createInviteCodeTx(tx, userID)
		return txCreateErr
	})
	if txErr != nil {
		return models.InviteCode{}, txErr
	}
	return invite, nil
}

func (s *Server) createInviteCodeTx(tx *gorm.DB, userID uint) (models.InviteCode, error) {
	if err := tx.Model(&models.InviteCode{}).
		Where("user_id = ? AND active = ? AND used_by_id IS NULL", userID, true).
		Update("active", false).Error; err != nil {
		return models.InviteCode{}, err
	}

	var lastErr error
	for i := 0; i < 8; i++ {
		code, err := generateInviteCode()
		if err != nil {
			return models.InviteCode{}, err
		}
		invite := models.InviteCode{
			UserID: userID,
			Code:   code,
			Active: true,
		}
		if err := tx.Create(&invite).Error; err != nil {
			lastErr = err
			if strings.Contains(strings.ToLower(err.Error()), "unique") {
				continue
			}
			return models.InviteCode{}, err
		}
		return invite, nil
	}
	if lastErr == nil {
		lastErr = errors.New("invite code generation failed")
	}
	return models.InviteCode{}, lastErr
}

func defaultColor(v string) string {
	if c, ok := normalizeColor(v); ok {
		return c
	}
	return "#1F5FBF"
}

var colorRe = regexp.MustCompile(`^#?[0-9a-fA-F]{6}$`)
var hhmmRe = regexp.MustCompile(`^(?:[01]\d|2[0-3]):[0-5]\d$`)

func normalizeColor(v string) (string, bool) {
	x := strings.TrimSpace(v)
	if x == "" {
		return "#1F5FBF", true
	}
	if !colorRe.MatchString(x) {
		return "", false
	}
	if !strings.HasPrefix(x, "#") {
		x = "#" + x
	}
	return strings.ToUpper(x), true
}

func isHHMM(v string) bool {
	return hhmmRe.MatchString(strings.TrimSpace(v))
}

func defaultUserPromptRules() []userPromptRule {
	return []userPromptRule{
		{
			ID:            "diagnostics_consent_v1",
			Enabled:       true,
			TriggerType:   "app_version",
			Title:         "Diagnose & Performance teilen?",
			Body:          "Wenn du zustimmst, sendet die App bei Problemen und Ladezeiten technische Diagnosedaten. Das hilft uns, Fehler und Engpaesse schneller zu finden. Du kannst das jederzeit im Profil widerrufen.",
			ConfirmLabel:  "Zustimmen",
			DeclineLabel:  "Nicht teilen",
			CooldownHours: 0,
			Priority:      10,
		},
	}
}

func sanitizeUserPromptRules(in []userPromptRule) []userPromptRule {
	out := make([]userPromptRule, 0, len(in))
	seen := map[string]struct{}{}
	for _, rule := range in {
		id := strings.TrimSpace(rule.ID)
		trigger := strings.TrimSpace(strings.ToLower(rule.TriggerType))
		title := strings.TrimSpace(rule.Title)
		body := strings.TrimSpace(rule.Body)
		confirm := strings.TrimSpace(rule.ConfirmLabel)
		decline := strings.TrimSpace(rule.DeclineLabel)
		if id == "" || title == "" || body == "" || confirm == "" || decline == "" {
			continue
		}
		if _, exists := seen[id]; exists {
			continue
		}
		switch trigger {
		case "app_version", "app_start", "time_based":
		default:
			continue
		}
		cooldown := rule.CooldownHours
		if cooldown < 0 {
			cooldown = 0
		}
		if cooldown > 24*30 {
			cooldown = 24 * 30
		}
		priority := rule.Priority
		if priority < 0 {
			priority = 0
		}
		if priority > 1000 {
			priority = 1000
		}
		out = append(out, userPromptRule{
			ID:            id,
			Enabled:       rule.Enabled,
			TriggerType:   trigger,
			Title:         title,
			Body:          body,
			ConfirmLabel:  confirm,
			DeclineLabel:  decline,
			CooldownHours: cooldown,
			Priority:      priority,
		})
		seen[id] = struct{}{}
	}
	if len(out) == 0 {
		return defaultUserPromptRules()
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Priority > out[j].Priority })
	return out
}

func parseUserPromptRulesJSON(raw string) []userPromptRule {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return defaultUserPromptRules()
	}
	var rules []userPromptRule
	if err := json.Unmarshal([]byte(trimmed), &rules); err != nil {
		return defaultUserPromptRules()
	}
	return sanitizeUserPromptRules(rules)
}

func validateUserPromptRulesRequest(rules []userPromptRule) error {
	seen := map[string]struct{}{}
	for _, rule := range rules {
		id := strings.TrimSpace(rule.ID)
		if id == "" {
			return errors.New("user prompt rule id required")
		}
		if _, exists := seen[id]; exists {
			return errors.New("duplicate user prompt rule id")
		}
		seen[id] = struct{}{}
		trigger := strings.ToLower(strings.TrimSpace(rule.TriggerType))
		switch trigger {
		case "app_version", "app_start", "time_based":
		default:
			return errors.New("invalid user prompt triggerType")
		}
		if strings.TrimSpace(rule.Title) == "" || strings.TrimSpace(rule.Body) == "" {
			return errors.New("user prompt title/body required")
		}
		if strings.TrimSpace(rule.ConfirmLabel) == "" || strings.TrimSpace(rule.DeclineLabel) == "" {
			return errors.New("user prompt labels required")
		}
	}
	return nil
}

func encodeUserPromptRulesJSON(rules []userPromptRule) string {
	safe := sanitizeUserPromptRules(rules)
	buf, err := json.Marshal(safe)
	if err != nil {
		return "[]"
	}
	return string(buf)
}

func normalizeSettings(settings models.AppSettings) models.AppSettings {
	if strings.TrimSpace(settings.ChatCommandValue) == "" {
		settings.ChatCommandValue = "-moment"
	}
	if strings.TrimSpace(settings.ChatCommandPushText) == "" {
		settings.ChatCommandPushText = "{user} hat einen Moment angefordert. Jetzt 10 Minuten posten."
	}
	if strings.TrimSpace(settings.ChatCommandEchoText) == "" {
		settings.ChatCommandEchoText = "Moment wurde von {user} angefordert."
	}
	if settings.UploadWindowMinutes <= 0 {
		settings.UploadWindowMinutes = 10
	}
	if settings.FeedCommentPreviewLimit <= 0 {
		settings.FeedCommentPreviewLimit = 10
	}
	if settings.FeedCommentPreviewLimit > 50 {
		settings.FeedCommentPreviewLimit = 50
	}
	if settings.PerformanceTrackingWindowMinutes < 5 {
		settings.PerformanceTrackingWindowMinutes = 30
	}
	if settings.PerformanceTrackingWindowMinutes > 180 {
		settings.PerformanceTrackingWindowMinutes = 180
	}
	settings.UserPromptRulesJSON = encodeUserPromptRulesJSON(parseUserPromptRulesJSON(settings.UserPromptRulesJSON))
	return settings
}

func settingsJSON(settings models.AppSettings) gin.H {
	return gin.H{
		"id":                      settings.ID,
		"promptWindowStartHour":   settings.PromptWindowStartHour,
		"promptWindowEndHour":     settings.PromptWindowEndHour,
		"uploadWindowMinutes":     settings.UploadWindowMinutes,
		"feedCommentPreviewLimit": settings.FeedCommentPreviewLimit,
		"promptNotificationText":  settings.PromptNotificationText,
		"maxUploadBytes":          settings.MaxUploadBytes,
		"chatCommandEnabled":      settings.ChatCommandEnabled,
		"chatCommandValue":        settings.ChatCommandValue,
		"chatCommandTrigger":      settings.ChatCommandTrigger,
		"chatCommandSendPush":     settings.ChatCommandSendPush,
		"chatCommandPushText":     settings.ChatCommandPushText,
		"chatCommandEchoChat":     settings.ChatCommandEchoChat,
		"chatCommandEchoText":     settings.ChatCommandEchoText,
		"performanceTrackingEnabled":       settings.PerformanceTrackingEnabled,
		"performanceTrackingWindowMinutes": settings.PerformanceTrackingWindowMinutes,
		"performanceTrackingOneShot":       settings.PerformanceTrackingOneShot,
		"userPromptRulesJson":     settings.UserPromptRulesJSON,
		"userPromptRules":         parseUserPromptRulesJSON(settings.UserPromptRulesJSON),
		"createdAt":               settings.CreatedAt,
		"updatedAt":               settings.UpdatedAt,
	}
}

func (s *Server) performanceTrackingConfig() (enabled bool, windowMinutes int) {
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		return false, 30
	}
	settings = normalizeSettings(settings)
	return settings.PerformanceTrackingEnabled, settings.PerformanceTrackingWindowMinutes
}

func (s *Server) performanceTrackingSettings() (models.AppSettings, error) {
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		return models.AppSettings{}, err
	}
	return normalizeSettings(settings), nil
}

func (s *Server) markDailySpikeIfEnabled(day string, triggerAt time.Time) {
	if s.Monitor == nil {
		return
	}
	settings, err := s.performanceTrackingSettings()
	if err != nil {
		return
	}
	enabled := settings.PerformanceTrackingEnabled
	windowMinutes := settings.PerformanceTrackingWindowMinutes
	if !enabled {
		return
	}
	if windowMinutes < 5 {
		windowMinutes = 30
	}
	s.Monitor.MarkDailySpike(day, triggerAt, time.Duration(windowMinutes)*time.Minute)
	if settings.PerformanceTrackingOneShot {
		_ = s.DB.Model(&models.AppSettings{}).
			Where("id = ?", settings.ID).
			Updates(map[string]any{
				"performance_tracking_enabled":  false,
				"performance_tracking_one_shot": false,
			}).Error
	}
}

func (s *Server) TrackDailyPromptSpikeIfEnabled(prompt models.DailyPrompt) {
	triggerAt := time.Now().In(s.Location)
	if prompt.TriggeredAt != nil {
		triggerAt = prompt.TriggeredAt.In(s.Location)
	}
	s.markDailySpikeIfEnabled(prompt.Day, triggerAt)
}

func normalizeCommandValue(v string) string {
	out := strings.ToLower(strings.TrimSpace(v))
	if out == "" {
		return ""
	}
	if !strings.HasPrefix(out, "-") {
		out = "-" + out
	}
	return out
}

func sanitizeChatCommand(in models.ChatCommand) (models.ChatCommand, error) {
	out := in
	out.Name = strings.TrimSpace(out.Name)
	out.Command = normalizeCommandValue(out.Command)
	out.Action = strings.TrimSpace(out.Action)
	out.PushText = strings.TrimSpace(out.PushText)
	out.ResponseText = strings.TrimSpace(out.ResponseText)
	if out.CooldownSecond < 0 {
		out.CooldownSecond = 0
	}
	if out.Name == "" {
		return out, errors.New("name required")
	}
	if out.Command == "" {
		return out, errors.New("command required")
	}
	switch out.Action {
	case "trigger_moment", "clear_chat", "broadcast_push", "send_chat_message":
	default:
		return out, errors.New("invalid action")
	}
	return out, nil
}

func defaultIfBlank(v string, fallback string) string {
	x := strings.TrimSpace(v)
	if x == "" {
		return fallback
	}
	return x
}

func renderCommandText(template string, username string) string {
	t := defaultIfBlank(template, "{user}")
	return strings.ReplaceAll(t, "{user}", username)
}
