package api

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/csv"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	_ "image/png"
	"io"
	"math"
	mrand "math/rand"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-contrib/cors"
	"github.com/gin-contrib/gzip"
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
	DB                 *gorm.DB
	Config             config.Config
	Auth               *auth.Manager
	Store              *storage.LocalStore
	Notifier           notify.Sender
	Prompt             *scheduler.DailyPromptService
	Location           *time.Location
	Monitor            *Monitor
	FeedCache          *FeedDayCache
	FeedLimiter        *FeedPollLimiter
	activityTouchMu    sync.Mutex
	activityTouchLast  map[uint]time.Time
	photoSearchMu      sync.Mutex
	photoSearchReady   bool
	performanceCacheMu sync.Mutex
	performanceCache   map[string]performanceCacheEntry
}

const photoTimeShiftThreshold = 5 * time.Minute
const duplicateDigestWindow = time.Minute
const maxPhotoPaintPaths = 12
const maxPhotoPaintPointsPerPath = 96

var photoSearchTokenPattern = regexp.MustCompile(`(?i)#[\p{L}\p{N}_]+|[\p{L}\p{N}_]+`)
var photoHashtagPattern = regexp.MustCompile(`(?i)(?:^|[^\p{L}\p{N}_])(#[\p{L}\p{N}_]+)`)

type photoSearchHit struct {
	Photo           models.Photo
	BookmarkedByMe  bool
	BookmarkCount   int64
	Excerpt         string
	MatchedCaption  bool
	MatchedComments []string
	MatchedHashtags []string
}

type photoPaintPoint struct {
	X float64 `json:"x"`
	Y float64 `json:"y"`
}

type photoPaintPath struct {
	Points []photoPaintPoint `json:"points"`
}

type viewerPhotoDecorations struct {
	bookmarkMap    map[uint]bool
	bookmarkCounts map[uint]int64
	marksByPhoto   map[uint][]gin.H
	paintsByPhoto  map[uint][]gin.H
	myMarked       map[uint]bool
	myPainted      map[uint]bool
	viewer         *models.User
}

func normalizePhotoSearchTokens(raw string) []string {
	matches := photoSearchTokenPattern.FindAllString(raw, -1)
	if len(matches) == 0 {
		return nil
	}
	out := make([]string, 0, len(matches))
	seen := make(map[string]struct{}, len(matches))
	for _, match := range matches {
		token := strings.ToLower(strings.TrimSpace(match))
		if token == "" || token == "#" {
			continue
		}
		if _, ok := seen[token]; ok {
			continue
		}
		seen[token] = struct{}{}
		out = append(out, token)
	}
	return out
}

func normalizedPhotoSearchQuery(raw string) string {
	return strings.Join(normalizePhotoSearchTokens(raw), " ")
}

func extractHashtags(text string) []string {
	matches := photoHashtagPattern.FindAllStringSubmatch(text, -1)
	if len(matches) == 0 {
		return nil
	}
	out := make([]string, 0, len(matches))
	seen := make(map[string]struct{}, len(matches))
	for _, match := range matches {
		if len(match) < 2 {
			continue
		}
		tag := strings.ToLower(strings.TrimSpace(match[1]))
		if tag == "" || tag == "#" {
			continue
		}
		if _, ok := seen[tag]; ok {
			continue
		}
		seen[tag] = struct{}{}
		out = append(out, tag)
	}
	return out
}

func containsAnyPhotoSearchToken(text string, tokens []string) bool {
	if strings.TrimSpace(text) == "" || len(tokens) == 0 {
		return false
	}
	lower := strings.ToLower(text)
	for _, token := range tokens {
		if strings.Contains(lower, token) {
			return true
		}
	}
	return false
}

func clipSearchExcerpt(text string, tokens []string) string {
	clean := strings.Join(strings.Fields(strings.TrimSpace(text)), " ")
	if clean == "" {
		return ""
	}
	lower := strings.ToLower(clean)
	for _, token := range tokens {
		idx := strings.Index(lower, token)
		if idx < 0 {
			continue
		}
		start := idx - 36
		if start < 0 {
			start = 0
		}
		end := idx + len(token) + 52
		if end > len(clean) {
			end = len(clean)
		}
		snippet := strings.TrimSpace(clean[start:end])
		if start > 0 {
			snippet = "..." + snippet
		}
		if end < len(clean) {
			snippet = snippet + "..."
		}
		return snippet
	}
	if len(clean) > 100 {
		return clean[:100] + "..."
	}
	return clean
}

func normalizeCreativePostMode(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "mark", "paint", "both":
		return strings.ToLower(strings.TrimSpace(v))
	default:
		return "none"
	}
}

func normalizeMediaDataMode(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "data_saver", "automatic":
		return strings.ToLower(strings.TrimSpace(v))
	default:
		return "normal"
	}
}

func normalizeMediaFormatPreference(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "avif", "webp", "jpeg":
		return strings.ToLower(strings.TrimSpace(v))
	default:
		return "auto"
	}
}

func creativeModeAllowsMark(v string) bool {
	mode := normalizeCreativePostMode(v)
	return mode == "mark" || mode == "both"
}

func creativeModeAllowsPaint(v string) bool {
	mode := normalizeCreativePostMode(v)
	return mode == "paint" || mode == "both"
}

func canViewerMarkNsfwPhoto(viewer models.User, photo models.Photo) bool {
	if viewer.ID == photo.UserID {
		return true
	}
	return photo.User.AllowCommunityNsfwMarking
}

func canViewerUnmarkNsfwPhoto(viewer models.User, photo models.Photo) bool {
	return viewer.ID == photo.UserID || viewer.IsAdmin
}

func (s *Server) Router() *gin.Engine {
	r := gin.Default()
	r.Use(s.requestIDMiddleware(), s.responseMetaMiddleware(), s.metricsMiddleware())
	r.Use(gzip.Gzip(gzip.DefaultCompression))
	r.Use(cors.New(cors.Config{
		AllowOrigins:     s.Config.AllowedOrigins,
		AllowMethods:     []string{"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"},
		AllowHeaders:     []string{"Authorization", "Content-Type", "X-Request-ID"},
		ExposeHeaders:    []string{"Content-Length", "ETag", "X-Request-ID", "X-Server-Instance", "X-App-Version"},
		AllowCredentials: true,
		MaxAge:           12 * time.Hour,
	}))

	uploads := r.Group("/uploads")
	uploads.Use(func(c *gin.Context) {
		c.Header("Cache-Control", "public, max-age=604800, immutable")
		c.Next()
	})
	uploads.StaticFS("/", http.Dir(s.Config.UploadDir))

	api := r.Group("/api")
	{
		api.GET("/health", s.handleHealth)
		api.GET("/health/live", s.handleLiveHealth)
		api.GET("/health/ready", s.handleReadyHealth)
		api.GET("/metrics", s.handleMetrics)
		api.GET("/migration/info", s.handleMigrationInfo)
		api.POST("/migration/sync/login", s.handleMigrationSyncLogin)
		api.POST("/migration/handoff/consume", s.handleMigrationHandoffConsume)
		api.POST("/auth/register", s.handleRegister)
		api.POST("/auth/register/preview", s.handleInvitePreview)
		api.POST("/auth/register/confirm", s.handleInviteRegister)
		api.POST("/auth/login", s.handleLogin)
		api.POST("/auth/refresh", s.handleAuthRefresh)

		protected := api.Group("")
		protected.Use(s.requireAuth)
		{
			protected.POST("/auth/logout", s.handleAuthLogout)
			protected.POST("/auth/logout-all", s.handleAuthLogoutAll)
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
			protected.GET("/me/fotomojis/templates", s.handleListMyFotomojiTemplates)
			protected.POST("/me/fotomojis/templates", s.handleUpsertMyFotomojiTemplate)
			protected.DELETE("/me/fotomojis/templates/:emoji", s.handleDeleteMyFotomojiTemplate)
			protected.POST("/devices", s.handleDevice)
			protected.POST("/migration/handoff", s.handleMigrationHandoff)
			protected.GET("/prompt/current", s.handleCurrentPrompt)
			protected.GET("/prompt/rules", s.handlePromptRules)
			protected.GET("/dashboard/bootstrap", s.handleDashboardBootstrap)
			protected.GET("/dashboard/core", s.handleDashboardCore)
			protected.GET("/hub/bootstrap", s.handleHubBootstrap)
			protected.GET("/hub/timeline", s.handleHubTimeline)
			protected.POST("/hub/timeline/viewed", s.handleHubTimelineViewed)
			protected.POST("/hub/timeline/clear", s.handleHubTimelineClear)
			protected.GET("/hub/time-capsules", s.handleHubTimeCapsules)
			protected.GET("/moment/special/status", s.handleSpecialMomentStatus)
			protected.POST("/moment/special/request", s.handleSpecialMomentRequest)
			protected.POST("/uploads", s.handleUpload)
			protected.POST("/uploads/dual", s.handleDualUpload)
			protected.GET("/feed", s.handleFeed)
			protected.GET("/feed/window", s.handleFeedWindow)
			protected.GET("/feed/discover", s.handleFeedDiscover)
			protected.GET("/feed/days", s.handleFeedDays)
			protected.GET("/feed/day-stats", s.handleFeedDayStats)
			protected.GET("/calendar/public", s.handleCalendarPublic)
			protected.GET("/calendar/user/:id", s.handleCalendarUser)
			protected.GET("/calendar/bookmarks", s.handleCalendarBookmarks)
			protected.GET("/calendar/time-capsules", s.handleCalendarTimeCapsules)
			protected.GET("/calendar/search", s.handleCalendarSearch)
			protected.GET("/community/stats", s.handleCommunityStats)
			protected.GET("/chat", s.handleChatList)
			protected.POST("/chat", s.handleChatCreate)
			protected.POST("/chat/polls", s.handleChatPollCreate)
			protected.POST("/chat/polls/:id/vote", s.handleChatPollVote)
			protected.POST("/chat/polls/:id/close", s.handleChatPollClose)
			protected.DELETE("/chat/:id", s.handleDeleteChatMessage)
			protected.GET("/photos/:id/interactions", s.handlePhotoInteractions)
			protected.DELETE("/photos/bookmarks", s.handlePhotoBookmarksClear)
			protected.POST("/photos/:id/bookmark", s.handlePhotoBookmarkCreate)
			protected.DELETE("/photos/:id/bookmark", s.handlePhotoBookmarkDelete)
			protected.POST("/photos/:id/mark", s.handlePhotoMarkCreate)
			protected.DELETE("/photos/:id/mark", s.handlePhotoMarkDelete)
			protected.PUT("/photos/:id/paint", s.handlePhotoPaintUpsert)
			protected.DELETE("/photos/:id/paint", s.handlePhotoPaintDelete)
			protected.POST("/photos/:id/report", s.handlePhotoReportCreate)
			protected.POST("/photos/:id/nsfw", s.handlePhotoNsfwCreate)
			protected.DELETE("/photos/:id/nsfw", s.handlePhotoNsfwDelete)
			protected.POST("/photos/:id/reaction", s.handlePhotoReaction)
			protected.POST("/photos/:id/fotomojis", s.handlePhotoFotomojiFromTemplate)
			protected.POST("/photos/:id/fotomojis/upload", s.handlePhotoFotomojiUpload)
			protected.POST("/photos/:id/comments", s.handlePhotoComment)
			protected.DELETE("/photos/:id/comments/:commentId", s.handleDeletePhotoComment)
			protected.POST("/photos/:id/attachments", s.handlePhotoAttachmentCreate)
		}

		admin := api.Group("/admin")
		admin.Use(s.requireAuth, s.requireAdmin)
		{
			admin.GET("/settings", s.handleGetSettings)
			admin.PUT("/settings", s.handleUpdateSettings)
			admin.GET("/media/renditions", s.handleAdminMediaRenditions)
			admin.PUT("/media/renditions", s.handleAdminMediaRenditionsUpdate)
			admin.GET("/stats", s.handleAdminStats)
			admin.GET("/feed", s.handleAdminFeed)
			admin.GET("/locations", s.handleAdminLocations)
			admin.DELETE("/photos/:id/location", s.handleAdminDeletePhotoLocation)
			admin.GET("/calendar", s.handleAdminCalendar)
			admin.GET("/history", s.handleAdminHistory)
			admin.GET("/search", s.handleAdminSearch)
			admin.GET("/polls", s.handleAdminPolls)
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
			admin.GET("/fotomojis", s.handleAdminListFotomojis)
			admin.GET("/fotomojis/history", s.handleAdminFotomojiHistory)
			admin.POST("/fotomojis/bulk-delete", s.handleAdminBulkDeleteFotomojis)
			admin.DELETE("/fotomojis/:id", s.handleAdminDeleteFotomoji)
			admin.GET("/debug/logs", s.handleAdminDebugLogs)
			admin.GET("/debug/logs/summary", s.handleAdminDebugLogsSummary)
			admin.GET("/debug/upload-timeline", s.handleAdminUploadTimeline)
			admin.DELETE("/debug/logs", s.handleAdminDeleteDebugLogs)
			admin.GET("/debug/logs/export", s.handleAdminDebugLogsExport)
			admin.GET("/system/health", s.handleAdminSystemHealth)
			admin.GET("/performance/overview", s.handleAdminPerformanceOverview)
			admin.GET("/performance/routes", s.handleAdminPerformanceRoutes)
			admin.GET("/performance/spikes", s.handleAdminPerformanceSpikes)
			admin.GET("/performance/slo", s.handleAdminPerformanceSLO)
			admin.GET("/performance/export", s.handleAdminPerformanceExport)
			admin.GET("/incidents/export", s.handleAdminIncidentExport)
			admin.GET("/trigger-runtime", s.handleAdminTriggerRuntime)
			admin.PUT("/trigger-runtime", s.handleAdminTriggerRuntimeUpdate)
			admin.GET("/trigger-audit", s.handleAdminTriggerAudit)
			admin.GET("/trigger-audit/summary", s.handleAdminTriggerAuditSummary)
			admin.GET("/trigger-audit/export", s.handleAdminTriggerAuditExport)
			admin.GET("/performance/tracking", s.handleAdminPerformanceTracking)
			admin.PUT("/performance/tracking", s.handleAdminPerformanceTrackingUpdate)
			admin.GET("/performance/tracking/export", s.handleAdminPerformanceTrackingExport)

			admin.GET("/users", s.handleAdminListUsers)
			admin.POST("/users", s.handleAdminCreateUser)
			admin.POST("/users/:id/token", s.handleAdminIssueUserToken)
			admin.PUT("/users/:id", s.handleAdminUpdateUser)
			admin.DELETE("/users/:id", s.handleAdminDeleteUser)
			admin.GET("/migration", s.handleAdminMigrationGet)
			admin.PUT("/migration", s.handleAdminMigrationPut)
			admin.POST("/migration/activate", s.handleAdminMigrationActivate)
			admin.POST("/migration/deactivate", s.handleAdminMigrationDeactivate)
			admin.POST("/migration/push", s.handleAdminMigrationPush)
			admin.POST("/migration/link/export", s.handleAdminMigrationLinkExport)
			admin.POST("/migration/link/import", s.handleAdminMigrationLinkImport)
			admin.GET("/migration/export", s.handleAdminMigrationExport)
		}
	}

	return r
}

// handleDashboardCore is deliberately small enough for the first visible app
// frame. Feed indexes, invite creation, append targets and social payloads are
// deferred to the normal bootstrap.
func (s *Server) handleDashboardCore(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	day := now.Format("2006-01-02")
	dailyMomentCount, streakDays, _ := s.computeUserMomentStats(user.ID)
	bookmarksGivenCount, bookmarksReceivedCount, _ := s.computeUserBookmarkStats(user.ID)
	var prompt models.DailyPrompt
	_ = s.DB.Where("day = ?", day).Limit(1).Find(&prompt).Error
	var settings models.AppSettings
	_ = s.DB.Limit(1).Find(&settings).Error
	settings = normalizeSettings(settings)
	hasPromptPosted, _ := s.userHasPostedForMomentDay(user.ID, day, momentKindFromTriggerSource(prompt.TriggerSource), prompt)
	triggerStatus, _ := s.currentDayTriggerStatus(day, "/api/dashboard/core")
	specialStatus, _ := s.specialMomentStatus(user.ID)
	c.JSON(http.StatusOK, gin.H{
		"schemaVersion": "dashboard_bootstrap_v1", "serverNow": now,
		"me":                  gin.H{"user": s.userOwnJSON(user), "dailyMomentCount": dailyMomentCount, "streakDays": streakDays, "bookmarksGivenCount": bookmarksGivenCount, "bookmarksReceivedCount": bookmarksReceivedCount},
		"inviteCode":          "",
		"prompt":              gin.H{"day": day, "triggered": prompt.TriggeredAt, "uploadUntil": prompt.UploadUntil, "canUpload": isPromptWindowActive(prompt, now), "hasPosted": hasPromptPosted, "hasPromptPostedToday": hasPromptPosted, "triggerSource": prompt.TriggerSource, "requestedByUser": prompt.RequestedBy, "momentKind": momentKindFromTriggerSource(prompt.TriggerSource), "dailyTriggeredAt": triggerStatus.DailyTriggeredAt, "dailyPending": triggerStatus.DailyPending, "specialTriggeredAt": triggerStatus.SpecialTriggeredAt, "specialRequestedByUser": triggerStatus.SpecialRequestedByUser, "specialRequestedByUserColor": triggerStatus.SpecialRequestedByUserColor},
		"promptRules":         gin.H{"promptWindowStartHour": settings.PromptWindowStartHour, "promptWindowEndHour": settings.PromptWindowEndHour, "uploadWindowMinutes": settings.UploadWindowMinutes, "maxUploadBytes": settings.MaxUploadBytes, "chatMessageMaxLength": settings.ChatMessageMaxLength, "chatMessageUnlimited": settings.ChatMessageUnlimited, "timezone": s.Config.Timezone},
		"specialMomentStatus": specialStatus,
		"photos":              []gin.H{}, "chat": []gin.H{}, "feedDays": []string{}, "communityStats": nil,
	})
}

type authRequest struct {
	Username   string `json:"username" binding:"required,min=3,max=64"`
	Password   string `json:"password" binding:"required,min=6,max=128"`
	DeviceName string `json:"deviceName" binding:"max=120"`
}

type invitePreviewRequest struct {
	InviteCode string `json:"inviteCode" binding:"required,min=6,max=32"`
}

type inviteRegisterRequest struct {
	InviteCode string `json:"inviteCode" binding:"required,min=6,max=32"`
	Username   string `json:"username" binding:"required,min=3,max=64"`
	Password   string `json:"password" binding:"required,min=6,max=128"`
	DeviceName string `json:"deviceName" binding:"max=120"`
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

	tokens, tokenErr := s.issueSessionTokens(user, req.DeviceName)
	if tokenErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "session create failed"})
		return
	}
	c.JSON(http.StatusCreated, gin.H{
		"token":        tokens.AccessToken,
		"accessToken":  tokens.AccessToken,
		"refreshToken": tokens.RefreshToken,
		"sessionId":    tokens.SessionID,
		"user":         s.userOwnJSON(user),
		"inviter": gin.H{
			"id":            inviter.ID,
			"username":      inviter.Username,
			"favoriteColor": defaultColor(inviter.FavoriteColor),
		},
	})
	s.maybeReportMigratedLogin(user, s.Config.AppVersion)
}

func (s *Server) handleLogin(c *gin.Context) {
	var req authRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}

	var user models.User
	if err := s.DB.Where("username = ?", strings.ToLower(req.Username)).First(&user).Error; err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid_credentials"})
		return
	}

	if !auth.CheckPassword(user.PasswordHash, req.Password) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid_credentials"})
		return
	}

	tokens, tokenErr := s.issueSessionTokens(user, req.DeviceName)
	if tokenErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "session create failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"token":        tokens.AccessToken,
		"accessToken":  tokens.AccessToken,
		"refreshToken": tokens.RefreshToken,
		"sessionId":    tokens.SessionID,
		"user":         s.userOwnJSON(user),
	})
	s.maybeReportMigratedLogin(user, s.Config.AppVersion)
}

func (s *Server) handleMe(c *gin.Context) {
	user, _ := userFromContext(c)
	if user.FavoriteColor == "" {
		user.FavoriteColor = "#1F5FBF"
	}

	dailyMomentCount, streakDays, _ := s.computeUserMomentStats(user.ID)
	bookmarksGivenCount, bookmarksReceivedCount, _ := s.computeUserBookmarkStats(user.ID)

	c.JSON(http.StatusOK, gin.H{
		"user":                   s.userOwnJSON(user),
		"dailyMomentCount":       dailyMomentCount,
		"streakDays":             streakDays,
		"bookmarksGivenCount":    bookmarksGivenCount,
		"bookmarksReceivedCount": bookmarksReceivedCount,
	})
}

func (s *Server) computeUserMomentStats(userID uint) (int64, int64, error) {
	// Keep this count in Go instead of SQL datetime comparisons.
	// SQLite can store mixed datetime formats/timezones, and direct SQL comparisons
	// may undercount even though a post is inside the prompt window.
	var photos []models.Photo
	if err := s.DB.Where("user_id = ?", userID).Order("created_at desc").Limit(500).Find(&photos).Error; err != nil {
		return 0, 0, err
	}
	sortPhotosForFeed(photos)
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
		if err := s.DB.Where("day IN ?", days).Find(&prompts).Error; err != nil {
			return 0, 0, err
		}
		for _, pr := range prompts {
			promptByDay[pr.Day] = pr
		}
	}

	dailyMomentCount := int64(0)
	countedDays := map[string]struct{}{}
	for _, p := range photos {
		if _, exists := countedDays[p.Day]; exists {
			continue
		}
		prompt, ok := promptByDay[p.Day]
		if !ok || prompt.TriggeredAt == nil || prompt.UploadUntil == nil {
			continue
		}
		effectiveAt := photoEffectiveTime(p)
		if !effectiveAt.Before(*prompt.TriggeredAt) && !effectiveAt.After(*prompt.UploadUntil) {
			dailyMomentCount++
			countedDays[p.Day] = struct{}{}
		}
	}

	streakDays := int64(0)
	now := time.Now().In(s.Location)
	today := now.Format("2006-01-02")
	anchor := ""
	if _, ok := postedDaySet[today]; ok {
		anchor = today
	} else {
		yesterday := now.AddDate(0, 0, -1).Format("2006-01-02")
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
	return dailyMomentCount, streakDays, nil
}

func (s *Server) computeUserBookmarkStats(userID uint) (int64, int64, error) {
	bookmarksGivenCount := int64(0)
	if err := s.DB.Model(&models.PhotoBookmark{}).Where("user_id = ? AND active = ?", userID, true).Count(&bookmarksGivenCount).Error; err != nil {
		return 0, 0, err
	}
	bookmarksReceivedCount := int64(0)
	if err := s.DB.Table("photo_bookmarks").
		Joins("JOIN photos ON photos.id = photo_bookmarks.photo_id").
		Where("photos.user_id = ? AND photo_bookmarks.active = ?", userID, true).
		Count(&bookmarksReceivedCount).Error; err != nil {
		return 0, 0, err
	}
	return bookmarksGivenCount, bookmarksReceivedCount, nil
}

func (s *Server) handleMyInvite(c *gin.Context) {
	user, _ := userFromContext(c)
	invite, err := s.loadOrCreateInviteCode(user.ID)
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
	s.bumpSyncRevision(calendarRevisionScope)
	s.bumpSyncRevision(timelineRevisionScope)

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
		ChatPushEnabled                     *bool   `json:"chatPushEnabled"`
		PollPushEnabled                     *bool   `json:"pollPushEnabled"`
		SpecialMomentPushEnabled            *bool   `json:"specialMomentPushEnabled"`
		InviteRegistrationPushEnabled       *bool   `json:"inviteRegistrationPushEnabled"`
		PhotoReactionPushEnabled            *bool   `json:"photoReactionPushEnabled"`
		PhotoFotomojiPushEnabled            *bool   `json:"photoFotomojiPushEnabled"`
		PhotoCommentPushEnabled             *bool   `json:"photoCommentPushEnabled"`
		BookmarkedPhotoPushEnabled          *bool   `json:"bookmarkedPhotoPushEnabled"`
		PostChangePushEnabled               *bool   `json:"postChangePushEnabled"`
		AutoSubscribeInteractedPostsEnabled *bool   `json:"autoSubscribeInteractedPostsEnabled"`
		OwnPostNumberInPushEnabled          *bool   `json:"ownPostNumberInPushEnabled"`
		PostNumberInPushEnabled             *bool   `json:"postNumberInPushEnabled"`
		YoloModeEnabled                     *bool   `json:"yoloModeEnabled"`
		MediaDataMode                       *string `json:"mediaDataMode"`
		MediaFormatPreference               *string `json:"mediaFormatPreference"`
		AllowPhotoDownload                  *bool   `json:"allowPhotoDownload"`
		AllowCommunityNsfwMarking           *bool   `json:"allowCommunityNsfwMarking"`
		ShowNsfwByDefault                   *bool   `json:"showNsfwByDefault"`
		CreativePostMode                    *string `json:"creativePostMode"`
		LocationFeatureEnabled              *bool   `json:"locationFeatureEnabled"`
		LocationShareDefaultEnabled         *bool   `json:"locationShareDefaultEnabled"`
		DiagnosticsConsentGranted           *bool   `json:"diagnosticsConsentGranted"`
		DiagnosticsConsentSource            string  `json:"diagnosticsConsentSource"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	updates := map[string]any{}
	if req.ChatPushEnabled != nil {
		updates["chat_push_enabled"] = *req.ChatPushEnabled
	}
	if req.PollPushEnabled != nil {
		updates["poll_push_enabled"] = *req.PollPushEnabled
	}
	if req.SpecialMomentPushEnabled != nil {
		updates["special_moment_push_enabled"] = *req.SpecialMomentPushEnabled
	}
	if req.InviteRegistrationPushEnabled != nil {
		updates["invite_registration_push_enabled"] = *req.InviteRegistrationPushEnabled
	}
	if req.PhotoReactionPushEnabled != nil {
		updates["photo_reaction_push_enabled"] = *req.PhotoReactionPushEnabled
	}
	if req.PhotoFotomojiPushEnabled != nil {
		updates["photo_fotomoji_push_enabled"] = *req.PhotoFotomojiPushEnabled
	}
	if req.PhotoCommentPushEnabled != nil {
		updates["photo_comment_push_enabled"] = *req.PhotoCommentPushEnabled
	}
	if req.BookmarkedPhotoPushEnabled != nil {
		updates["bookmarked_photo_push_enabled"] = *req.BookmarkedPhotoPushEnabled
	}
	if req.PostChangePushEnabled != nil {
		updates["post_change_push_enabled"] = *req.PostChangePushEnabled
	}
	if req.AutoSubscribeInteractedPostsEnabled != nil {
		updates["auto_subscribe_interacted_posts_enabled"] = *req.AutoSubscribeInteractedPostsEnabled
	}
	if req.OwnPostNumberInPushEnabled != nil {
		updates["own_post_number_in_push_enabled"] = *req.OwnPostNumberInPushEnabled
	}
	if req.PostNumberInPushEnabled != nil {
		updates["post_number_in_push_enabled"] = *req.PostNumberInPushEnabled
	}
	if req.YoloModeEnabled != nil {
		updates["yolo_mode_enabled"] = *req.YoloModeEnabled
	}
	if req.MediaDataMode != nil {
		mode := normalizeMediaDataMode(*req.MediaDataMode)
		if mode != strings.ToLower(strings.TrimSpace(*req.MediaDataMode)) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid mediaDataMode"})
			return
		}
		updates["media_data_mode"] = mode
	}
	if req.MediaFormatPreference != nil {
		preference := normalizeMediaFormatPreference(*req.MediaFormatPreference)
		if preference != strings.ToLower(strings.TrimSpace(*req.MediaFormatPreference)) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid mediaFormatPreference"})
			return
		}
		updates["media_format_preference"] = preference
	}
	if req.AllowPhotoDownload != nil {
		updates["allow_photo_download"] = *req.AllowPhotoDownload
	}
	if req.AllowCommunityNsfwMarking != nil {
		updates["allow_community_nsfw_marking"] = *req.AllowCommunityNsfwMarking
	}
	if req.ShowNsfwByDefault != nil {
		updates["show_nsfw_by_default"] = *req.ShowNsfwByDefault
	}
	if req.CreativePostMode != nil {
		updates["creative_post_mode"] = normalizeCreativePostMode(*req.CreativePostMode)
	}
	if req.LocationFeatureEnabled != nil {
		updates["location_feature_enabled"] = *req.LocationFeatureEnabled
		if !*req.LocationFeatureEnabled {
			updates["location_share_default_enabled"] = false
		}
	}
	if req.LocationShareDefaultEnabled != nil {
		shareDefault := *req.LocationShareDefaultEnabled
		if req.LocationFeatureEnabled == nil {
			var current models.User
			if err := s.DB.Select("id", "location_feature_enabled").First(&current, user.ID).Error; err == nil && !current.LocationFeatureEnabled {
				shareDefault = false
			}
		}
		updates["location_share_default_enabled"] = shareDefault
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
	bookmarksGivenCount := int64(0)
	bookmarksReceivedCount := int64(0)
	if sameUser {
		bookmarksGivenCount, bookmarksReceivedCount, _ = s.computeUserBookmarkStats(target.ID)
	}

	c.JSON(http.StatusOK, gin.H{
		"profileVisible":         true,
		"user":                   s.userPublicJSON(viewer.ID, target),
		"photos":                 photos,
		"isSelf":                 sameUser,
		"bookmarksGivenCount":    bookmarksGivenCount,
		"bookmarksReceivedCount": bookmarksReceivedCount,
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
	s.revokeAllSessionsByUserID(user.ID)
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
	// Debug uploads are retried by mobile clients. A request ID identifies the
	// local aggregate: retries replace that aggregate instead of creating a new
	// row, while a higher aggregateCount still reaches the diagnostics views.
	if entry.RequestID != "" {
		var existing models.ClientDebugLog
		if err := s.DB.Where("user_id = ? AND request_id = ?", user.ID, entry.RequestID).First(&existing).Error; err == nil {
			if existing.Type == entry.Type &&
				existing.Message == entry.Message &&
				existing.Meta == entry.Meta &&
				existing.AppVersion == entry.AppVersion &&
				existing.DeviceName == entry.DeviceName &&
				existing.SessionID == entry.SessionID {
				// Older clients may send an already acknowledged batch again. The
				// read above is enough to prove idempotence; avoid a needless write.
				c.JSON(http.StatusOK, gin.H{"ok": true, "deduplicated": true})
				return
			}
			if err := s.DB.Model(&existing).Updates(map[string]any{
				"type":        entry.Type,
				"message":     entry.Message,
				"meta":        entry.Meta,
				"app_version": entry.AppVersion,
				"device_name": entry.DeviceName,
				"session_id":  entry.SessionID,
			}).Error; err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "log update failed"})
				return
			}
			c.JSON(http.StatusOK, gin.H{"ok": true, "deduplicated": true})
			return
		} else if !errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "log lookup failed"})
			return
		}
	}
	if err := s.DB.Create(&entry).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "log save failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func debugMetaPairs(meta string) map[string]string {
	out := map[string]string{}
	for _, raw := range strings.Split(meta, ";") {
		part := strings.TrimSpace(raw)
		if part == "" {
			continue
		}
		key, value, ok := strings.Cut(part, "=")
		if !ok {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.TrimSpace(value)
		if key == "" {
			continue
		}
		out[key] = value
	}
	return out
}

// debugSignalCategory separates actionable operational failures from expected
// mobile lifecycle and connectivity states. The latter are useful diagnostics,
// but must never turn a healthy day into a high-severity incident on their own.
func debugSignalCategory(row models.ClientDebugLog) string {
	kind := strings.ToLower(strings.TrimSpace(row.Type))
	meta := debugMetaPairs(row.Meta)
	family := strings.ToLower(strings.TrimSpace(meta["failureClass"]))
	if family == "" || family == "none" {
		family = strings.ToLower(strings.TrimSpace(debugFailureFamily(row)))
	}
	if !strings.Contains(kind, "failed") && !strings.Contains(kind, "error") && !strings.Contains(kind, "crash") {
		return ""
	}
	if strings.Contains(kind, "crash") || strings.Contains(family, "illegalstate") {
		return "crash"
	}
	if strings.HasPrefix(family, "http_5") || family == "http5xx" || family == "http_502" {
		return "server"
	}
	switch family {
	case "no_active_network", "dns", "unknownhostexception", "timeout", "sockettimeoutexception", "connectexception", "interruptedioexception":
		return "connectivity"
	case "jobcancellationexception", "leftcompositioncancellationexception", "worker_cancelled":
		return "cancelled"
	}
	return "client"
}

func debugMetaInt(meta map[string]string, key string) *int64 {
	raw := strings.TrimSpace(meta[key])
	if raw == "" {
		return nil
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return nil
	}
	return &value
}

func debugMetaBool(meta map[string]string, key string) *bool {
	raw := strings.TrimSpace(strings.ToLower(meta[key]))
	if raw == "" {
		return nil
	}
	switch raw {
	case "true":
		value := true
		return &value
	case "false":
		value := false
		return &value
	default:
		return nil
	}
}

func debugMetaTime(meta map[string]string, key string) *time.Time {
	raw := strings.TrimSpace(meta[key])
	if raw == "" {
		return nil
	}
	parsed, err := time.Parse(time.RFC3339, raw)
	if err != nil {
		return nil
	}
	return &parsed
}

func debugMetaCount(meta map[string]string) int64 {
	if value := debugMetaInt(meta, "aggregateCount"); value != nil && *value > 0 {
		return *value
	}
	return 1
}

func debugFailureFamily(row models.ClientDebugLog) string {
	meta := debugMetaPairs(row.Meta)
	failureClass := strings.TrimSpace(strings.ToLower(meta["failureClass"]))
	network := strings.TrimSpace(strings.ToLower(meta["network"]))
	reason := strings.TrimSpace(strings.ToLower(meta["reason"]))
	joined := strings.ToLower(strings.Join([]string{row.Type, row.Message, row.Meta, failureClass, network, reason}, ";"))
	switch {
	case strings.Contains(joined, "cert_path_validator"):
		return "cert_path_validator"
	case strings.Contains(joined, "ssl_handshake"):
		return "ssl_handshake"
	case strings.Contains(joined, "reason=no_active_network"), strings.Contains(joined, "network=no_active_network"), strings.Contains(joined, "failureclass=no_active_network"):
		return "no_active_network"
	case strings.Contains(joined, "network=dns"), strings.Contains(joined, "failureclass=dns"), strings.Contains(joined, "unknownhostexception"):
		return "dns"
	case strings.Contains(joined, "ssl_other"):
		return "ssl_other"
	default:
		return ""
	}
}

func uploadTimelineStage(logType string) (stage string, source string, ok bool) {
	switch strings.TrimSpace(logType) {
	case "upload_direct_started":
		return "gestartet", "direct", true
	case "upload_direct_server_ack_pending":
		return "wartet_auf_bestaetigung", "direct", true
	case "upload_direct_succeeded":
		return "erfolgreich", "direct", true
	case "upload_direct_failed":
		return "fehlgeschlagen", "direct", true
	case "upload_queue_enqueued":
		return "wartend", "queue", true
	case "upload_queue_attempt_started":
		return "gestartet", "queue", true
	case "upload_queue_waiting_for_network":
		return "wartet_auf_verbindung", "queue", true
	case "upload_queue_server_ack_pending":
		return "wartet_auf_bestaetigung", "queue", true
	case "upload_queue_succeeded":
		return "erfolgreich", "queue", true
	case "upload_queue_failed":
		return "fehlgeschlagen", "queue", true
	case "upload_queue_state_recovered":
		return "wiederhergestellt", "queue", true
	default:
		return "", "", false
	}
}

func buildUploadTimelineItem(row models.ClientDebugLog, location *time.Location) (gin.H, bool) {
	stage, source, ok := uploadTimelineStage(row.Type)
	if !ok {
		return nil, false
	}
	meta := debugMetaPairs(row.Meta)
	uploadClientID := strings.TrimSpace(meta["uploadClientId"])
	queueItemID := strings.TrimSpace(meta["queueItemId"])
	timelineID := uploadClientID
	if timelineID == "" {
		timelineID = queueItemID
	}
	if timelineID == "" {
		timelineID = strings.TrimSpace(row.RequestID)
	}
	if timelineID == "" {
		timelineID = fmt.Sprintf("log_%d", row.ID)
	}

	item := gin.H{
		"id":                    row.ID,
		"timelineId":            timelineID,
		"createdAt":             row.CreatedAt.In(location),
		"type":                  row.Type,
		"stage":                 stage,
		"source":                source,
		"message":               row.Message,
		"meta":                  row.Meta,
		"appVersion":            row.AppVersion,
		"deviceName":            row.DeviceName,
		"sessionId":             row.SessionID,
		"requestId":             row.RequestID,
		"uploadClientId":        uploadClientID,
		"queueItemId":           queueItemID,
		"kind":                  strings.TrimSpace(meta["kind"]),
		"failureClass":          strings.TrimSpace(meta["failureClass"]),
		"failureFamily":         debugFailureFamily(row),
		"securityFailureClass":  strings.TrimSpace(meta["securityFailureClass"]),
		"networkStateClass":     strings.TrimSpace(meta["networkStateClass"]),
		"retrySuppressedReason": strings.TrimSpace(meta["retrySuppressedReason"]),
		"userAdviceShown":       debugMetaBool(meta, "userAdviceShown"),
		"networkKind":           strings.TrimSpace(meta["network"]),
		"user": gin.H{
			"id":       row.User.ID,
			"username": row.User.Username,
		},
		"network": gin.H{
			"activeNetwork": debugMetaBool(meta, "activeNetwork"),
			"internet":      debugMetaBool(meta, "internet"),
			"validated":     debugMetaBool(meta, "validated"),
			"metered":       debugMetaBool(meta, "metered"),
			"stable":        debugMetaBool(meta, "networkStable"),
			"transport":     strings.TrimSpace(meta["transport"]),
			"downKbps":      debugMetaInt(meta, "downKbps"),
			"upKbps":        debugMetaInt(meta, "upKbps"),
		},
	}

	if v := debugMetaInt(meta, "attempt"); v != nil {
		item["attempt"] = *v
	}
	if count := debugMetaCount(meta); count > 1 {
		item["aggregateCount"] = count
	}
	if v := debugMetaInt(meta, "bytesTotal"); v != nil {
		item["bytesTotal"] = *v
	}
	if v := debugMetaInt(meta, "bytesSent"); v != nil {
		item["bytesSent"] = *v
	}
	if v := debugMetaInt(meta, "durationMs"); v != nil {
		item["durationMs"] = *v
	}
	if v := debugMetaInt(meta, "pingMs"); v != nil {
		item["pingMs"] = *v
	}
	if v := debugMetaInt(meta, "http"); v != nil {
		item["httpCode"] = *v
	}
	if v := debugMetaTime(meta, "capturedAt"); v != nil {
		item["capturedAt"] = v.In(location)
	}
	if v := debugMetaTime(meta, "queuedAt"); v != nil {
		item["queuedAt"] = v.In(location)
	}
	if v := debugMetaTime(meta, "firstSeenAt"); v != nil {
		item["firstSeenAt"] = v.In(location)
	}
	if v := debugMetaTime(meta, "lastSeenAt"); v != nil {
		item["lastSeenAt"] = v.In(location)
	}
	if v := strings.TrimSpace(meta["pingFailure"]); v != "" {
		item["pingFailure"] = v
	}
	if v := strings.TrimSpace(meta["responseRequestId"]); v != "" {
		item["responseRequestId"] = v
	}
	return item, true
}

func (s *Server) handleCurrentPrompt(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	day := now.Format("2006-01-02")

	var prompt models.DailyPrompt
	promptQueryStart := time.Now()
	err := s.DB.Where("day = ?", day).First(&prompt).Error
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/prompt/current", "prompt_current_prompt_query", time.Since(promptQueryStart))
	}
	if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	canUpload := isPromptWindowActive(prompt, now)
	activeMomentKind := momentKindFromTriggerSource(prompt.TriggerSource)
	type dayStatsRow struct {
		PostCount    int64 `gorm:"column:post_count"`
		VisibleCount int64 `gorm:"column:visible_count"`
	}
	stats := dayStatsRow{}
	statsQueryStart := time.Now()
	_ = s.DB.Raw(`
		SELECT
			COALESCE(COUNT(*), 0) AS post_count,
			COALESCE(SUM(CASE WHEN capsule_visible_at IS NULL OR capsule_visible_at <= ? THEN 1 ELSE 0 END), 0) AS visible_count
		FROM photos
		WHERE user_id = ? AND day = ?
	`, now, user.ID, day).Scan(&stats).Error
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/prompt/current", "prompt_current_user_day_stats", time.Since(statsQueryStart))
	}
	hasPromptPosted, _ := s.userHasPostedForMomentDay(user.ID, day, activeMomentKind, prompt)
	hasAnyPost := stats.PostCount > 0
	hasVisiblePost := stats.VisibleCount > 0
	triggerStatus, _ := s.currentDayTriggerStatus(day, "/api/prompt/current")
	var settings models.AppSettings
	_ = s.DB.First(&settings).Error
	settings = normalizeSettings(settings)
	var ownPhoto gin.H
	canAppendToOwnLatestPost := false
	var appendTargetPhotoID any = nil
	var appendRemainingMediaSlots any = nil
	if hasPromptPosted {
		var p models.Photo
		ownPhotoQueryStart := time.Now()
		if err := s.ownPromptPhotoForMomentDay(user.ID, day, activeMomentKind, prompt, &p); err == nil {
			ownPhoto = s.photoJSON(p)
		}
		if s.Monitor != nil {
			s.Monitor.RecordDBQuery("/api/prompt/current", "prompt_current_own_photo_query", time.Since(ownPhotoQueryStart))
		}
	}
	if latestPhoto, ok, err := s.latestAppendablePhotoForDay(user.ID, day, now); err == nil && ok {
		appendTargetPhotoID = latestPhoto.ID
		remaining, unlimited := s.remainingPostMediaSlots(latestPhoto, settings)
		canAppendToOwnLatestPost = unlimited || remaining > 0
		if !unlimited {
			appendRemainingMediaSlots = remaining
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"day":                         day,
		"triggered":                   prompt.TriggeredAt,
		"uploadUntil":                 prompt.UploadUntil,
		"canUpload":                   canUpload,
		"hasPosted":                   hasPromptPosted,
		"hasPromptPostedToday":        hasPromptPosted,
		"hasVisiblePostToday":         hasVisiblePost,
		"hasAnyPostToday":             hasAnyPost,
		"ownPhoto":                    ownPhoto,
		"triggerSource":               prompt.TriggerSource,
		"requestedByUser":             prompt.RequestedBy,
		"momentKind":                  activeMomentKind,
		"dailyTriggeredAt":            triggerStatus.DailyTriggeredAt,
		"dailyPending":                triggerStatus.DailyPending,
		"specialTriggeredAt":          triggerStatus.SpecialTriggeredAt,
		"specialRequestedByUser":      triggerStatus.SpecialRequestedByUser,
		"specialRequestedByUserColor": triggerStatus.SpecialRequestedByUserColor,
		"canAppendToOwnLatestPost":    canAppendToOwnLatestPost,
		"appendTargetPhotoId":         appendTargetPhotoID,
		"appendRemainingMediaSlots":   appendRemainingMediaSlots,
		"appendMediaUnlimited":        settings.PostMediaUnlimited,
	})
}

func (s *Server) handleDashboardBootstrap(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	includeChat := parseQueryBool(c.Query("includeChat"), true)
	includePhotos := parseQueryBool(c.Query("includePhotos"), true)
	includeCommunity := parseQueryBool(c.Query("includeCommunity"), true)

	dailyMomentCount, streakDays, _ := s.computeUserMomentStats(user.ID)
	bookmarksGivenCount, bookmarksReceivedCount, _ := s.computeUserBookmarkStats(user.ID)

	inviteCode := ""
	if invite, err := s.loadOrCreateInviteCode(user.ID); err == nil {
		inviteCode = invite.Code
	}

	day := now.Format("2006-01-02")
	var prompt models.DailyPrompt
	_ = s.DB.Where("day = ?", day).First(&prompt).Error
	canUpload := isPromptWindowActive(prompt, now)
	activeMomentKind := momentKindFromTriggerSource(prompt.TriggerSource)

	type dayStatsRow struct {
		PostCount    int64 `gorm:"column:post_count"`
		VisibleCount int64 `gorm:"column:visible_count"`
	}
	stats := dayStatsRow{}
	_ = s.DB.Raw(`
		SELECT
			COALESCE(COUNT(*), 0) AS post_count,
			COALESCE(SUM(CASE WHEN capsule_visible_at IS NULL OR capsule_visible_at <= ? THEN 1 ELSE 0 END), 0) AS visible_count
		FROM photos
		WHERE user_id = ? AND day = ?
	`, now, user.ID, day).Scan(&stats).Error
	hasPromptPosted, _ := s.userHasPostedForMomentDay(user.ID, day, activeMomentKind, prompt)
	hasAnyPost := stats.PostCount > 0
	hasVisiblePost := stats.VisibleCount > 0
	triggerStatus, _ := s.currentDayTriggerStatus(day, "/api/dashboard/bootstrap")

	var ownPhoto gin.H
	canAppendToOwnLatestPost := false
	var appendTargetPhotoID any = nil
	var appendRemainingMediaSlots any = nil
	if hasPromptPosted {
		var p models.Photo
		if err := s.ownPromptPhotoForMomentDay(user.ID, day, activeMomentKind, prompt, &p); err == nil {
			ownPhoto = s.photoJSON(p)
		}
	}
	var settings models.AppSettings
	_ = s.DB.First(&settings).Error
	settings = normalizeSettings(settings)
	if latestPhoto, ok, err := s.latestAppendablePhotoForDay(user.ID, day, now); err == nil && ok {
		appendTargetPhotoID = latestPhoto.ID
		remaining, unlimited := s.remainingPostMediaSlots(latestPhoto, settings)
		canAppendToOwnLatestPost = unlimited || remaining > 0
		if !unlimited {
			appendRemainingMediaSlots = remaining
		}
	}

	specialStatus, _ := s.specialMomentStatus(user.ID)
	feedDays, _, _, _ := s.feedDaysForUser(user.ID, "", "", "", "", 60, "", now)

	photos := []gin.H{}
	if includePhotos {
		items, _ := s.myPhotosPayload(user.ID, now)
		photos = items
	}
	chat := []gin.H{}
	if includeChat {
		items, _ := s.chatListPayload(user)
		chat = items
	}
	community := gin.H{}
	if includeCommunity {
		stats, _ := s.communityStatsPayload(now)
		community = stats
	}

	c.JSON(http.StatusOK, gin.H{
		"schemaVersion": "dashboard_bootstrap_v1",
		"serverNow":     now,
		"capabilities": gin.H{
			"bootstrap":              true,
			"lightweightCommentPost": true,
		},
		"me": gin.H{
			"user":                   s.userOwnJSON(user),
			"dailyMomentCount":       dailyMomentCount,
			"streakDays":             streakDays,
			"bookmarksGivenCount":    bookmarksGivenCount,
			"bookmarksReceivedCount": bookmarksReceivedCount,
		},
		"inviteCode": inviteCode,
		"prompt": gin.H{
			"day":                         day,
			"triggered":                   prompt.TriggeredAt,
			"uploadUntil":                 prompt.UploadUntil,
			"canUpload":                   canUpload,
			"hasPosted":                   hasPromptPosted,
			"hasPromptPostedToday":        hasPromptPosted,
			"hasVisiblePostToday":         hasVisiblePost,
			"hasAnyPostToday":             hasAnyPost,
			"ownPhoto":                    ownPhoto,
			"triggerSource":               prompt.TriggerSource,
			"requestedByUser":             prompt.RequestedBy,
			"momentKind":                  activeMomentKind,
			"dailyTriggeredAt":            triggerStatus.DailyTriggeredAt,
			"dailyPending":                triggerStatus.DailyPending,
			"specialTriggeredAt":          triggerStatus.SpecialTriggeredAt,
			"specialRequestedByUser":      triggerStatus.SpecialRequestedByUser,
			"specialRequestedByUserColor": triggerStatus.SpecialRequestedByUserColor,
			"canAppendToOwnLatestPost":    canAppendToOwnLatestPost,
			"appendTargetPhotoId":         appendTargetPhotoID,
			"appendRemainingMediaSlots":   appendRemainingMediaSlots,
			"appendMediaUnlimited":        settings.PostMediaUnlimited,
		},
		"promptRules": gin.H{
			"promptWindowStartHour": settings.PromptWindowStartHour,
			"promptWindowEndHour":   settings.PromptWindowEndHour,
			"uploadWindowMinutes":   settings.UploadWindowMinutes,
			"maxUploadBytes":        settings.MaxUploadBytes,
			"chatMessageMaxLength":  settings.ChatMessageMaxLength,
			"chatMessageUnlimited":  settings.ChatMessageUnlimited,
			"timezone":              s.Config.Timezone,
		},
		"specialMomentStatus": specialStatus,
		"feedDays":            feedDays,
		"photos":              photos,
		"chat":                chat,
		"communityStats":      community,
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
		"chatMessageMaxLength":  settings.ChatMessageMaxLength,
		"chatMessageUnlimited":  settings.ChatMessageUnlimited,
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

	prompt, settings, err := s.Prompt.TriggerNowWithSourceAndMeta("special_request", &user, scheduler.TriggerAttemptMeta{
		RequestID:   requestIDFromContext(c),
		AttemptType: "special",
	})
	if err != nil {
		if errors.Is(err, scheduler.ErrAlreadyTriggeredToday) {
			c.JSON(http.StatusConflict, gin.H{"error": "already_triggered_today"})
			return
		}
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
	tokens := s.specialMomentNotificationTokens(user.ID)
	sendResult := notify.SendResult{}
	var sendErr error
	removed := int64(0)
	created, _, reserveErr := s.Prompt.ReserveDispatch(prompt.Day, s.Prompt.DispatchKindSpecialMomentPush(), "special_request", requestIDFromContext(c))
	if reserveErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "dispatch reserve failed"})
		return
	}
	if created {
		sendResult, sendErr = s.Notifier.Send(tokens, notify.Message{
			Title:  "Sondermoment",
			Body:   pushBody,
			Type:   "special_request",
			Action: "open_camera",
		})
		s.recordPushResult(sendResult, sendErr)
		removed = s.removeInvalidTokens(sendResult.InvalidTokens)
		dispatchStatus := "sent"
		dispatchErr := ""
		if sendErr != nil {
			dispatchStatus = "failed"
			dispatchErr = sendErr.Error()
		}
		s.Prompt.MarkDispatchResult(prompt.Day, s.Prompt.DispatchKindSpecialMomentPush(), dispatchStatus, int64(sendResult.Sent), int64(sendResult.Failed), dispatchErr)
	}

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

	capturedAt, err := s.parseCapturedAtValue(c.PostForm("captured_at"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid captured_at"})
		return
	}
	acceptedViaOfflineGrace := false
	uploadClientID := normalizeUploadClientID(c.PostForm("upload_client_id"))
	if uploadClientID != "" {
		if existing, ok, err := s.findPhotoByUploadClientID(user.ID, uploadClientID); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		} else if ok {
			c.JSON(http.StatusOK, gin.H{"photo": s.photoJSON(existing), "deduplicated": true, "acceptedViaOfflineGrace": false})
			return
		}
	}

	now := time.Now().In(s.Location)
	day := now.Format("2006-01-02")
	todayWindowActive := s.isDailyWindowActive(day, now)
	momentKind := ""
	if kind == "prompt" {
		resolvedDay, allowed, acceptedOffline, blockedCode := s.resolvePromptUploadDecision(day, now, capturedAt)
		day = resolvedDay
		acceptedViaOfflineGrace = acceptedOffline
		if !allowed {
			message := "prompt inactive"
			if blockedCode == "upload_window_closed" {
				message = "upload window closed"
			}
			c.JSON(http.StatusForbidden, gin.H{"error": message, "errorCode": blockedCode})
			return
		}
		momentKind = s.promptMomentKindForDay(day)
	}

	if _, err := s.cleanupInvalidPromptOnlyPhotosForDay(day); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	hasPromptPosted, err := s.userHasPostedForMomentDay(user.ID, day, momentKind, models.DailyPrompt{})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	if kind == "extra" && todayWindowActive {
		if s.extraUploadOfflineGraceAllowed(day, now, capturedAt) {
			acceptedViaOfflineGrace = true
		} else {
			c.JSON(http.StatusForbidden, gin.H{
				"error":        "extra unavailable during daily moment window",
				"errorCode":    "extra_window_blocked",
				"actionNeeded": true,
			})
			return
		}
	}

	capsuleMode, capsuleVisibleAt, capsulePrivate, capsuleGroupRemind, capsuleErr := parseCapsuleForm(c, kind, todayWindowActive, now)
	if capsuleErr != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": capsuleErr.Error()})
		return
	}
	locationShared, latitude, longitude, locationErr := parseLocationForm(c)
	if locationErr != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": locationErr.Error()})
		return
	}
	if !user.LocationFeatureEnabled {
		locationShared = false
		latitude = nil
		longitude = nil
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
		MomentKind:         momentKind,
		UploadClientID:     uploadClientID,
		FilePath:           relPath,
		PrimaryDigest:      "",
		CapsulePreviewPath: capsulePreviewPath,
		Caption:            c.PostForm("caption"),
		CapsuleMode:        capsuleMode,
		CapsuleVisibleAt:   capsuleVisibleAt,
		CapsulePrivate:     capsulePrivate,
		CapsuleGroupRemind: capsuleGroupRemind,
		LocationShared:     locationShared,
		LocationLatitude:   latitude,
		LocationLongitude:  longitude,
		CapturedAt:         capturedAt,
	}
	photo.PrimaryDigest, err = s.fileDigest(relPath)
	if err != nil {
		s.removePhotoFiles(photo)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "digest failed"})
		return
	}
	if existing, ok, err := s.findRecentDuplicatePhoto(user.ID, day, kind == "prompt", photo.PrimaryDigest, photo.SecondaryDigest, now); err != nil {
		s.removePhotoFiles(photo)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	} else if ok {
		s.removePhotoFiles(photo)
		c.JSON(http.StatusOK, gin.H{"photo": s.photoJSON(existing), "deduplicated": true, "acceptedViaOfflineGrace": acceptedViaOfflineGrace})
		return
	}
	if uploadClientID != "" {
		if existing, ok, err := s.findPhotoByUploadClientID(user.ID, uploadClientID); err != nil {
			s.removePhotoFiles(photo)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		} else if ok {
			s.removePhotoFiles(photo)
			c.JSON(http.StatusOK, gin.H{"photo": s.photoJSON(existing), "deduplicated": true, "acceptedViaOfflineGrace": acceptedViaOfflineGrace})
			return
		}
	}
	if kind == "prompt" && hasPromptPosted {
		s.removePhotoFiles(photo)
		c.JSON(http.StatusConflict, gin.H{"error": "Du hast heute bereits gepostet", "errorCode": "already_posted"})
		return
	}
	if err := s.DB.Create(&photo).Error; err != nil {
		s.removePhotoFiles(photo)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "db write failed"})
		return
	}
	if err := s.assignAndPersistPublicPhotoNumber(&photo); err != nil {
		s.removePhotoFiles(photo)
		_ = s.DB.Delete(&photo).Error
		c.JSON(http.StatusInternalServerError, gin.H{"error": "public number failed"})
		return
	}
	if err := s.refreshPhotoSearchDocument(photo.ID); err != nil {
		s.removePhotoFiles(photo)
		_ = s.DB.Delete(&photo).Error
		c.JSON(http.StatusInternalServerError, gin.H{"error": "search index failed"})
		return
	}

	s.invalidateFeedDayCache(photo.Day)
	// New uploads are the most likely media to be opened next. Queue their
	// regenerable variants now instead of waiting for a later feed response.
	s.enqueueMediaDerivatives(filepath.ToSlash(filepath.Clean(relPath)), 8_000, false)
	s.notifyPostCreated(user, photo)
	c.JSON(http.StatusCreated, gin.H{"photo": s.photoJSON(photo), "acceptedViaOfflineGrace": acceptedViaOfflineGrace})
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
	type triggerRow struct {
		Day           string    `gorm:"column:day"`
		OccurredAt    time.Time `gorm:"column:occurred_at"`
		Source        string    `gorm:"column:source"`
		ActorUsername string    `gorm:"column:actor_username"`
	}
	type dayTriggerSummary struct {
		DailyTriggeredAt       *time.Time
		DailyTriggerSource     string
		DailyRequestedByUser   string
		SpecialTriggeredAt     *time.Time
		SpecialTriggerSource   string
		SpecialRequestedByUser string
	}
	triggerSummaryByDay := make(map[string]dayTriggerSummary, len(dayList))
	triggerRows := make([]triggerRow, 0, len(dayList)*2)
	_ = s.DB.
		Table("daily_trigger_audit_events").
		Select("day, occurred_at, source, actor_username").
		Where("day IN ? AND result = ?", dayList, "triggered").
		Order("occurred_at asc").
		Find(&triggerRows).Error
	for _, ev := range triggerRows {
		sum := triggerSummaryByDay[ev.Day]
		switch triggerKindFromTriggerSource(ev.Source) {
		case "special":
			when := ev.OccurredAt
			sum.SpecialTriggeredAt = &when
			sum.SpecialTriggerSource = strings.TrimSpace(ev.Source)
			if name := strings.TrimSpace(ev.ActorUsername); name != "" {
				sum.SpecialRequestedByUser = name
			}
		default:
			when := ev.OccurredAt
			sum.DailyTriggeredAt = &when
			sum.DailyTriggerSource = strings.TrimSpace(ev.Source)
			if name := strings.TrimSpace(ev.ActorUsername); name != "" {
				sum.DailyRequestedByUser = name
			}
		}
		triggerSummaryByDay[ev.Day] = sum
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
		summary, hasSummary := triggerSummaryByDay[p.Day]
		row["dailyTriggeredAt"] = summary.DailyTriggeredAt
		row["specialTriggeredAt"] = summary.SpecialTriggeredAt
		row["dailyPending"] = summary.DailyTriggeredAt == nil
		row["specialRequestedByUser"] = summary.SpecialRequestedByUser
		if hasSummary && summary.DailyTriggeredAt != nil {
			// Backward-compatible fields: table status must reflect Daily trigger, not special.
			row["triggeredAt"] = summary.DailyTriggeredAt
			row["triggerSource"] = summary.DailyTriggerSource
			row["requestedByUser"] = summary.DailyRequestedByUser
			row["momentKind"] = "daily"
		} else if hasSummary && summary.SpecialTriggeredAt != nil {
			// Keep special info visible in legacy columns, but day remains daily-pending.
			row["triggeredAt"] = nil
			row["triggerSource"] = summary.SpecialTriggerSource
			row["requestedByUser"] = summary.SpecialRequestedByUser
			row["momentKind"] = "special"
		}
		if prompt, ok := promptByDay[p.Day]; ok {
			if row["triggeredAt"] == nil && !(hasSummary && summary.SpecialTriggeredAt != nil && summary.DailyTriggeredAt == nil) {
				row["triggeredAt"] = prompt.TriggeredAt
			}
			row["uploadUntil"] = prompt.UploadUntil
			if strings.TrimSpace(fmt.Sprint(row["triggerSource"])) == "" {
				row["triggerSource"] = prompt.TriggerSource
			}
			if strings.TrimSpace(fmt.Sprint(row["requestedByUser"])) == "" {
				row["requestedByUser"] = prompt.RequestedBy
			}
			if strings.TrimSpace(fmt.Sprint(row["momentKind"])) == "" {
				row["momentKind"] = momentKindFromTriggerSource(prompt.TriggerSource)
			}
			if row["dailyTriggeredAt"] == nil && row["specialTriggeredAt"] == nil && prompt.TriggeredAt != nil {
				kind := momentKindFromTriggerSource(prompt.TriggerSource)
				if kind == "special" {
					row["specialTriggeredAt"] = prompt.TriggeredAt
					row["specialRequestedByUser"] = prompt.RequestedBy
					row["dailyPending"] = true
					row["triggeredAt"] = nil
				} else {
					row["dailyTriggeredAt"] = prompt.TriggeredAt
					row["dailyPending"] = false
				}
			}
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
	triggerStatus, _ := s.currentDayTriggerStatus(day, "/api/feed")
	momentKind := momentKindFromTriggerSource(prompt.TriggerSource)
	requestedByUser := strings.TrimSpace(prompt.RequestedBy)
	if requestedByUser == "" {
		requestedByUser = strings.TrimSpace(triggerStatus.SpecialRequestedByUser)
	}
	specialRequestedByUserColor := strings.TrimSpace(triggerStatus.SpecialRequestedByUserColor)

	c.JSON(http.StatusOK, gin.H{
		"day":                         plan.Day,
		"plannedAt":                   plan.PlannedAt,
		"isManual":                    plan.IsManual,
		"source":                      "manual",
		"triggeredAt":                 prompt.TriggeredAt,
		"uploadUntil":                 prompt.UploadUntil,
		"triggerSource":               prompt.TriggerSource,
		"requestedByUser":             requestedByUser,
		"momentKind":                  momentKind,
		"specialRequestedByUserColor": specialRequestedByUserColor,
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
	triggerStatus, _ := s.currentDayTriggerStatus(day, "/api/feed")
	momentKind := momentKindFromTriggerSource(prompt.TriggerSource)
	requestedByUser := strings.TrimSpace(prompt.RequestedBy)
	if requestedByUser == "" {
		requestedByUser = strings.TrimSpace(triggerStatus.SpecialRequestedByUser)
	}
	specialRequestedByUserColor := strings.TrimSpace(triggerStatus.SpecialRequestedByUserColor)

	var photos []models.Photo
	photosQueryStart := time.Now()
	if err := s.DB.Preload("User").Where("day = ?", day).Order("created_at desc").Find(&photos).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	sortPhotosForFeed(photos)
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/feed", "feed_photos_query", time.Since(photosQueryStart))
	}

	photoIDs := make([]uint, 0, len(photos))
	for _, p := range photos {
		photoIDs = append(photoIDs, p.ID)
	}
	decorations, err := s.photoDecorationsForViewer(adminUser.ID, photoIDs)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "photo decorations query failed"})
		return
	}
	interactionStart := time.Now()
	reactionByPhoto, commentByPhoto, photoMojiByPhoto, countsByPhoto, commentPreviewLimit, err := s.feedInteractionPreview(photoIDs)
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
		effectiveAt := photoEffectiveTime(p)
		if prompt.TriggeredAt != nil && effectiveAt.Before(*prompt.TriggeredAt) {
			isEarly = true
		}
		if prompt.UploadUntil != nil && effectiveAt.After(*prompt.UploadUntil) {
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
		photoMojis := photoMojiByPhoto[p.ID]
		if photoMojis == nil {
			photoMojis = []gin.H{}
		}
		out = append(out, gin.H{
			"isEarly":           isEarly,
			"isLate":            isLate,
			"capsuleLocked":     capsuleLocked,
			"capsuleReleased":   capsuleReleased,
			"photo":             s.photoJSONForViewer(adminUser.ID, p, decorations),
			"user":              s.userPublicJSON(adminUser.ID, p.User),
			"reactions":         reactions,
			"comments":          comments,
			"photoMojis":        photoMojis,
			"interactionCounts": countsByPhoto[p.ID],
			"interactionSnapshot": gin.H{
				"kind":                "preview",
				"commentPreviewLimit": commentPreviewLimit,
			},
			"triggerSource":               prompt.TriggerSource,
			"requestedByUser":             requestedByUser,
			"momentKind":                  momentKind,
			"specialRequestedByUserColor": specialRequestedByUserColor,
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
		"momentKind":      momentKind,
		"monthRecap":      recap,
	})
}

func (s *Server) handleAdminLocations(c *gin.Context) {
	userFilter, _ := strconv.ParseUint(strings.TrimSpace(c.Query("userId")), 10, 64)
	fromDay := strings.TrimSpace(c.Query("from"))
	toDay := strings.TrimSpace(c.Query("to"))

	query := s.DB.Preload("User").
		Where("location_shared = ? AND location_latitude IS NOT NULL AND location_longitude IS NOT NULL", true)
	if userFilter > 0 {
		query = query.Where("user_id = ?", uint(userFilter))
	}
	if fromDay != "" {
		query = query.Where("day >= ?", fromDay)
	}
	if toDay != "" {
		query = query.Where("day <= ?", toDay)
	}

	var photos []models.Photo
	if err := query.Order("created_at desc").Limit(500).Find(&photos).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	sortPhotosForFeed(photos)

	items := make([]gin.H, 0, len(photos))
	for _, photo := range photos {
		if !photo.LocationShared || photo.LocationLatitude == nil || photo.LocationLongitude == nil {
			continue
		}
		items = append(items, gin.H{
			"photoId":           photo.ID,
			"day":               photo.Day,
			"createdAt":         photo.CreatedAt,
			"user":              s.userPublicJSON(0, photo.User),
			"photo":             s.photoJSON(photo),
			"locationLatitude":  *photo.LocationLatitude,
			"locationLongitude": *photo.LocationLongitude,
			"locationDisplay":   formatLocationDisplay(*photo.LocationLatitude, *photo.LocationLongitude),
			"locationMapsUrl":   googleMapsLocationURL(*photo.LocationLatitude, *photo.LocationLongitude),
		})
	}

	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) handleAdminDeletePhotoLocation(c *gin.Context) {
	photoID, err := strconv.ParseUint(c.Param("id"), 10, 64)
	if err != nil || photoID == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid photo id"})
		return
	}

	var photo models.Photo
	if err := s.DB.First(&photo, uint(photoID)).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "photo not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	updates := map[string]any{
		"location_shared":    false,
		"location_latitude":  nil,
		"location_longitude": nil,
	}
	if err := s.DB.Model(&models.Photo{}).Where("id = ?", photo.ID).Updates(updates).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, gin.H{"ok": true, "photoId": photo.ID})
}

func (s *Server) handleFeed(c *gin.Context) {
	user, _ := userFromContext(c)
	day := c.Query("day")
	if day == "" {
		day = time.Now().In(s.Location).Format("2006-01-02")
	}
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
	payload, status, err := s.feedPayloadForDay(user.ID, day, now)
	if err != nil {
		writeFeedDayAccessError(c, status, err)
		return
	}
	if s.shouldUseFeedCache(day, now) {
		s.putFeedCachedPayload(user.ID, day, payload, now)
	}
	c.JSON(http.StatusOK, payload)
}

func (s *Server) handleFeedWindow(c *gin.Context) {
	user, _ := userFromContext(c)
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

	anchorDay := strings.TrimSpace(c.Query("anchor_day"))
	if anchorDay == "" {
		anchorDay = now.Format("2006-01-02")
	}
	if _, err := time.ParseInLocation("2006-01-02", anchorDay, s.Location); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid anchor_day"})
		return
	}
	beforeDays, err := parseNonNegativeQueryInt(c, "before_days", 2)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	afterDays, err := parseNonNegativeQueryInt(c, "after_days", 2)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	focusPhotoID := strings.TrimSpace(c.Query("focus_photo_id"))

	beforeDays = minInt(beforeDays, 60)
	afterDays = minInt(afterDays, 60)

	newerDays := []string{}
	hasNewer := false
	if beforeDays > 0 {
		var err error
		newerDays, _, hasNewer, err = s.feedDaysForUser(user.ID, "", "", "", anchorDay, beforeDays, "", now)
		if err != nil {
			if strings.Contains(err.Error(), "invalid") {
				c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
		newerDays = filterDaysAfter(newerDays, anchorDay)
	}
	olderDays := []string{}
	hasOlder := false
	if afterDays > 0 {
		var err error
		olderDays, hasOlder, _, err = s.feedDaysForUser(user.ID, "", "", anchorDay, "", afterDays, "", now)
		if err != nil {
			if strings.Contains(err.Error(), "invalid") {
				c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
		olderDays = filterDaysBefore(olderDays, anchorDay)
	}
	selectedDays := append(append(newerDays, anchorDay), olderDays...)
	selectedDays = uniqueDays(selectedDays)
	sort.Slice(selectedDays, func(i, j int) bool { return selectedDays[i] > selectedDays[j] })
	revisions := make(map[string]int64, len(selectedDays))
	for _, day := range selectedDays {
		revisions[day] = s.syncRevision(feedRevisionScope(day))
	}
	etag := revisionETag("feed-window", revisions)
	c.Header("ETag", etag)
	c.Header("Cache-Control", "private, no-cache")
	if strings.TrimSpace(c.GetHeader("If-None-Match")) == etag {
		c.Status(http.StatusNotModified)
		c.Writer.WriteHeaderNow()
		return
	}
	knownRevisions := parseKnownFeedRevisions(c.Query("known_revisions"))
	allKnownUnchanged := len(selectedDays) > 0
	for _, day := range selectedDays {
		known, ok := knownRevisions[day]
		if !ok || known != revisions[day] {
			allKnownUnchanged = false
			break
		}
	}
	if allKnownUnchanged {
		c.Status(http.StatusNotModified)
		c.Writer.WriteHeaderNow()
		return
	}
	unchangedDays := make([]string, 0, len(selectedDays))
	items := make([]gin.H, 0, len(selectedDays))
	for _, day := range selectedDays {
		if known, ok := knownRevisions[day]; ok && known == revisions[day] {
			unchangedDays = append(unchangedDays, day)
			continue
		}
		payload, status, payloadErr := s.feedPayloadForDay(user.ID, day, now)
		if payloadErr != nil {
			writeFeedDayAccessError(c, status, payloadErr)
			return
		}
		payload["revision"] = revisions[day]
		items = append(items, payload)
	}
	c.JSON(http.StatusOK, gin.H{
		"schemaVersion":        "feed_window_v2",
		"anchorDay":            anchorDay,
		"days":                 items,
		"dayRevisions":         revisions,
		"unchangedDays":        unchangedDays,
		"hasOlder":             hasOlder,
		"hasNewer":             hasNewer,
		"oldestLoadedDay":      selectedDays[len(selectedDays)-1],
		"newestLoadedDay":      selectedDays[0],
		"requestedBeforeDays":  beforeDays,
		"requestedAfterDays":   afterDays,
		"minReturnedDay":       selectedDays[len(selectedDays)-1],
		"maxReturnedDay":       selectedDays[0],
		"resolvedFocusPhotoId": parseOptionalInt64(focusPhotoID),
	})
}

func parseKnownFeedRevisions(raw string) map[string]int64 {
	result := make(map[string]int64)
	for _, part := range strings.Split(strings.TrimSpace(raw), ",") {
		pair := strings.SplitN(strings.TrimSpace(part), ":", 2)
		if len(pair) != 2 {
			continue
		}
		if _, err := time.Parse("2006-01-02", pair[0]); err != nil {
			continue
		}
		revision, err := strconv.ParseInt(pair[1], 10, 64)
		if err == nil && revision > 0 {
			result[pair[0]] = revision
		}
	}
	return result
}

func (s *Server) handleFeedDiscover(c *gin.Context) {
	user, _ := userFromContext(c)
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

	mode := strings.ToLower(strings.TrimSpace(c.Query("mode")))
	if mode != "trend" && mode != "random" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid mode"})
		return
	}
	offset, err := parseNonNegativeQueryInt(c, "offset", 0)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	limitDays, err := parseNonNegativeQueryInt(c, "limit_days", 7)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if limitDays <= 0 {
		limitDays = 7
	}
	if limitDays > 21 {
		limitDays = 21
	}
	randomSeed, _ := strconv.ParseInt(strings.TrimSpace(c.Query("random_seed")), 10, 64)
	if randomSeed == 0 {
		randomSeed = now.UnixNano()
	}
	anchorDay := strings.TrimSpace(c.Query("anchor_day"))
	if anchorDay != "" {
		if _, err := time.ParseInLocation("2006-01-02", anchorDay, s.Location); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid anchor_day date"})
			return
		}
	}
	focusPhotoID := parseOptionalInt64(strings.TrimSpace(c.Query("focus_photo_id")))

	days, _, _, err := s.feedDaysForUser(user.ID, "", "", "", "", 180, "", now)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	payloads := make([]discoverDayPayload, 0, len(days))
	for _, day := range days {
		payload, status, payloadErr := s.feedPayloadForDay(user.ID, day, now)
		if payloadErr != nil {
			writeFeedDayAccessError(c, status, payloadErr)
			return
		}
		items, _ := payload["items"].([]gin.H)
		sortFeedItemsForDiscover(items, mode, randomSeed, day)
		payload["items"] = items

		entry := discoverDayPayload{
			Day:     day,
			Payload: payload,
		}
		for idx, item := range items {
			interaction := feedItemInteractionCount(item)
			bookmark := feedItemBookmarkCount(item)
			entry.InteractionSum += interaction
			entry.BookmarkSum += bookmark
			score := interaction*10 + bookmark*3
			if idx == 0 || score > entry.BestScore {
				entry.BestScore = score
				entry.BestAt = feedItemEffectiveTime(item)
			}
		}
		payloads = append(payloads, entry)
	}

	switch mode {
	case "trend":
		sort.SliceStable(payloads, func(i, j int) bool {
			left := payloads[i]
			right := payloads[j]
			leftScore := left.InteractionSum*10 + left.BookmarkSum*3 + left.BestScore
			rightScore := right.InteractionSum*10 + right.BookmarkSum*3 + right.BestScore
			if leftScore != rightScore {
				return leftScore > rightScore
			}
			if !left.BestAt.Equal(right.BestAt) {
				return left.BestAt.After(right.BestAt)
			}
			return left.Day > right.Day
		})
	case "random":
		sort.SliceStable(payloads, func(i, j int) bool {
			left := seededSortWeight(randomSeed, payloads[i].Day, 0)
			right := seededSortWeight(randomSeed, payloads[j].Day, 0)
			if left != right {
				return left < right
			}
			return payloads[i].Day > payloads[j].Day
		})
	}

	if targetIndex := discoverTargetIndex(payloads, anchorDay, focusPhotoID); targetIndex >= 0 {
		offset = discoverOffsetForTarget(len(payloads), limitDays, targetIndex)
	}
	if offset > len(payloads) {
		offset = len(payloads)
	}
	end := offset + limitDays
	if end > len(payloads) {
		end = len(payloads)
	}
	selected := payloads[offset:end]
	daysOut := make([]gin.H, 0, len(selected))
	for _, entry := range selected {
		daysOut = append(daysOut, entry.Payload)
	}
	resolvedAnchorDay := ""
	if len(selected) > 0 {
		resolvedAnchorDay = selected[0].Day
	}
	var oldestLoaded string
	var newestLoaded string
	if len(selected) > 0 {
		newestLoaded = selected[0].Day
		oldestLoaded = selected[len(selected)-1].Day
	}
	c.JSON(http.StatusOK, gin.H{
		"anchorDay":            resolvedAnchorDay,
		"days":                 daysOut,
		"hasOlder":             end < len(payloads),
		"hasNewer":             offset > 0,
		"oldestLoadedDay":      oldestLoaded,
		"newestLoadedDay":      newestLoaded,
		"resolvedFocusPhotoId": focusPhotoID,
		"mode":                 mode,
		"offset":               offset,
		"nextOffset":           end,
		"randomSeed":           randomSeed,
	})
}

func discoverTargetIndex(payloads []discoverDayPayload, anchorDay string, focusPhotoID *int64) int {
	if focusPhotoID != nil {
		targetID := uint64(*focusPhotoID)
		for idx, payload := range payloads {
			items, _ := payload.Payload["items"].([]gin.H)
			for _, item := range items {
				if feedItemPhotoID(item) == targetID {
					return idx
				}
			}
		}
	}
	if anchorDay != "" {
		for idx, payload := range payloads {
			if payload.Day == anchorDay {
				return idx
			}
		}
	}
	return -1
}

func discoverOffsetForTarget(total int, limit int, targetIndex int) int {
	if total <= 0 || limit <= 0 || targetIndex < 0 {
		return 0
	}
	if total <= limit {
		return 0
	}
	offset := targetIndex - limit/2
	if offset < 0 {
		offset = 0
	}
	maxOffset := total - limit
	if offset > maxOffset {
		offset = maxOffset
	}
	return offset
}

func writeFeedDayAccessError(c *gin.Context, status int, err error) {
	if status == http.StatusForbidden && err != nil && err.Error() == "feed locked" {
		c.JSON(http.StatusForbidden, gin.H{
			"error":     "Poste zuerst einen sichtbaren Beitrag, um die Beitraege der anderen zu sehen",
			"code":      "feed_locked",
			"errorCode": "daily_required",
		})
		return
	}
	c.JSON(status, gin.H{"error": err.Error()})
}

func (s *Server) canViewerSeeFeedDay(userID uint, day string, now time.Time) (bool, error) {
	today := now.Format("2006-01-02")
	if strings.TrimSpace(day) != today {
		return true, nil
	}
	return s.userHasVisiblePhotoForDay(userID, day, now)
}

func (s *Server) feedPayloadForDay(userID uint, day string, now time.Time) (gin.H, int, error) {
	if s.shouldUseFeedCache(day, now) {
		if cached, ok := s.feedCachedPayload(userID, day, now); ok {
			return cached, http.StatusOK, nil
		}
	}
	canView, err := s.canViewerSeeFeedDay(userID, day, now)
	if err != nil {
		return nil, http.StatusInternalServerError, errors.New("query failed")
	}
	if !canView {
		return nil, http.StatusForbidden, errors.New("feed locked")
	}

	var prompt models.DailyPrompt
	_ = s.DB.Where("day = ?", day).First(&prompt).Error
	triggerStatus, _ := s.currentDayTriggerStatus(day, "/api/feed")
	momentKind := momentKindFromTriggerSource(prompt.TriggerSource)
	requestedByUser := strings.TrimSpace(prompt.RequestedBy)
	if requestedByUser == "" {
		requestedByUser = strings.TrimSpace(triggerStatus.SpecialRequestedByUser)
	}
	specialRequestedByUserColor := strings.TrimSpace(triggerStatus.SpecialRequestedByUserColor)

	var photos []models.Photo
	if err := s.DB.Preload("User").Where("day = ?", day).Order("created_at desc").Find(&photos).Error; err != nil {
		return nil, http.StatusInternalServerError, errors.New("query failed")
	}
	sortPhotosForFeed(photos)

	photoIDs := make([]uint, 0, len(photos))
	for _, p := range photos {
		photoIDs = append(photoIDs, p.ID)
	}
	decorations, err := s.photoDecorationsForViewer(userID, photoIDs)
	if err != nil {
		return nil, http.StatusInternalServerError, errors.New("photo decorations query failed")
	}
	attachmentByPhoto := s.photoAttachmentsByPhotoIDs(photoIDs)
	reactionByPhoto, commentByPhoto, photoMojiByPhoto, countsByPhoto, commentPreviewLimit, err := s.feedInteractionPreview(photoIDs)
	if err != nil {
		return nil, http.StatusInternalServerError, errors.New("interaction query failed")
	}

	out := make([]gin.H, 0, len(photos))
	for _, p := range photos {
		if !photoVisibleToViewer(userID, p, now) {
			continue
		}
		capsuleLocked := p.CapsuleVisibleAt != nil && now.Before(*p.CapsuleVisibleAt)
		capsuleReleased := strings.TrimSpace(p.CapsuleMode) != "" && !capsuleLocked
		isEarly := false
		isLate := false
		effectiveAt := photoEffectiveTime(p)
		if prompt.TriggeredAt != nil && effectiveAt.Before(*prompt.TriggeredAt) {
			isEarly = true
		}
		if prompt.UploadUntil != nil && effectiveAt.After(*prompt.UploadUntil) {
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
		photoMojis := photoMojiByPhoto[p.ID]
		if photoMojis == nil {
			photoMojis = []gin.H{}
		}
		out = append(out, gin.H{
			"isEarly":           isEarly,
			"isLate":            isLate,
			"capsuleLocked":     capsuleLocked,
			"capsuleReleased":   capsuleReleased,
			"photo":             s.photoJSONForViewerWithAttachments(userID, p, decorations, attachmentByPhoto[p.ID]),
			"user":              s.userPublicJSON(userID, p.User),
			"reactions":         reactions,
			"comments":          comments,
			"photoMojis":        photoMojis,
			"interactionCounts": countsByPhoto[p.ID],
			"interactionSnapshot": gin.H{
				"kind":                "preview",
				"commentPreviewLimit": commentPreviewLimit,
			},
			"triggerSource":               prompt.TriggerSource,
			"requestedByUser":             requestedByUser,
			"momentKind":                  momentKind,
			"specialRequestedByUserColor": specialRequestedByUserColor,
		})
	}

	recapStart := time.Now()
	recap, _ := s.monthlyRecapForDay(day, userID)
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/feed", "feed_monthly_recap", time.Since(recapStart))
	}
	payload := gin.H{
		"items":                       out,
		"day":                         day,
		"triggeredAt":                 prompt.TriggeredAt,
		"uploadUntil":                 prompt.UploadUntil,
		"triggerSource":               prompt.TriggerSource,
		"requestedByUser":             requestedByUser,
		"momentKind":                  momentKind,
		"specialRequestedByUser":      triggerStatus.SpecialRequestedByUser,
		"specialRequestedByUserColor": specialRequestedByUserColor,
		"specialTriggeredAt":          triggerStatus.SpecialTriggeredAt,
		"dailyTriggeredAt":            triggerStatus.DailyTriggeredAt,
		"dailyPending":                triggerStatus.DailyPending,
		"monthRecap":                  recap,
	}
	if s.shouldUseFeedCache(day, now) {
		s.putFeedCachedPayload(userID, day, payload, now)
	}
	return payload, http.StatusOK, nil
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
	PromptWindowStartHour            int              `json:"promptWindowStartHour"`
	PromptWindowEndHour              int              `json:"promptWindowEndHour"`
	UploadWindowMinutes              int              `json:"uploadWindowMinutes"`
	FeedCommentPreviewLimit          int              `json:"feedCommentPreviewLimit"`
	PromptNotificationText           string           `json:"promptNotificationText"`
	MaxUploadBytes                   int64            `json:"maxUploadBytes"`
	ChatMessageMaxLength             int              `json:"chatMessageMaxLength"`
	ChatMessageUnlimited             bool             `json:"chatMessageUnlimited"`
	PostMediaMaxCount                int              `json:"postMediaMaxCount"`
	PostMediaUnlimited               bool             `json:"postMediaUnlimited"`
	ChatCommandEnabled               bool             `json:"chatCommandEnabled"`
	ChatCommandValue                 string           `json:"chatCommandValue"`
	ChatCommandTrigger               bool             `json:"chatCommandTrigger"`
	ChatCommandSendPush              bool             `json:"chatCommandSendPush"`
	ChatCommandPushText              string           `json:"chatCommandPushText"`
	ChatCommandEchoChat              bool             `json:"chatCommandEchoChat"`
	ChatCommandEchoText              string           `json:"chatCommandEchoText"`
	PerformanceTrackingEnabled       *bool            `json:"performanceTrackingEnabled"`
	PerformanceTrackingWindowMinutes *int             `json:"performanceTrackingWindowMinutes"`
	PerformanceTrackingOneShot       *bool            `json:"performanceTrackingOneShot"`
	UserPromptRules                  []userPromptRule `json:"userPromptRules"`
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
	settings.ChatMessageMaxLength = req.ChatMessageMaxLength
	settings.ChatMessageUnlimited = req.ChatMessageUnlimited
	settings.PostMediaMaxCount = req.PostMediaMaxCount
	settings.PostMediaUnlimited = req.PostMediaUnlimited
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

	prompt, settings, err := s.Prompt.TriggerNowWithSourceAndMeta(triggerSource, &adminUser, scheduler.TriggerAttemptMeta{
		RequestID:   requestIDFromContext(c),
		AttemptType: "admin",
	})
	if err != nil {
		if errors.Is(err, scheduler.ErrAlreadyTriggeredToday) {
			c.JSON(http.StatusConflict, gin.H{"error": "already_triggered_today"})
			return
		}
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
		created, _, reserveErr := s.Prompt.ReserveDispatch(prompt.Day, s.Prompt.DispatchKindDailyPromptPush(), triggerSource, requestIDFromContext(c))
		if reserveErr != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "dispatch reserve failed"})
			return
		}
		if created {
			sendResult, sendErr = s.Notifier.SendDailyPrompt(tokens, settings.PromptNotificationText)
			s.recordPushResult(sendResult, sendErr)
			removed = s.removeInvalidTokens(sendResult.InvalidTokens)
			dispatchStatus := "sent"
			dispatchErr := ""
			if sendErr != nil {
				dispatchStatus = "failed"
				dispatchErr = sendErr.Error()
			}
			s.Prompt.MarkDispatchResult(prompt.Day, s.Prompt.DispatchKindDailyPromptPush(), dispatchStatus, int64(sendResult.Sent), int64(sendResult.Failed), dispatchErr)
		}
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
	adminUser, _ := userFromContext(c)
	day := time.Now().In(s.Location).Format("2006-01-02")
	now := time.Now().In(s.Location)

	txErr := s.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("day = ?", day).Delete(&models.Photo{}).Error; err != nil {
			return err
		}
		return tx.Where("day = ?", day).Delete(&models.DailyPrompt{}).Error
	})
	if txErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "reset failed"})
		return
	}

	prompt, _, triggerErr := s.Prompt.TriggerNowWithSourceAndMeta("admin_reset", &adminUser, scheduler.TriggerAttemptMeta{
		RequestID:   requestIDFromContext(c),
		AttemptType: "reset",
	})
	if triggerErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "reset trigger failed"})
		return
	}
	s.invalidateFeedDayCache(day)
	if s.Monitor != nil {
		s.markDailySpikeIfEnabled(day, now)
	}

	c.JSON(http.StatusOK, gin.H{
		"ok":          true,
		"day":         day,
		"triggeredAt": prompt.TriggeredAt,
		"uploadUntil": prompt.UploadUntil,
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

func (s *Server) handleAdminUploadTimeline(c *gin.Context) {
	limit := 150
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil {
			if n < 20 {
				n = 20
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
	q := s.DB.Preload("User").
		Where("created_at >= ?", since).
		Where("type LIKE ?", "upload_%").
		Order("created_at desc").
		Limit(limit)
	if userID != 0 {
		q = q.Where("user_id = ?", userID)
	}

	var rows []models.ClientDebugLog
	if err := q.Find(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	items := make([]gin.H, 0, len(rows))
	uniqueUploads := map[string]struct{}{}
	failedCount := 0
	waitingForNetworkCount := 0
	liveCount := 0
	for _, row := range rows {
		item, ok := buildUploadTimelineItem(row, s.Location)
		if !ok {
			continue
		}
		items = append(items, item)
		key := fmt.Sprint(item["timelineId"])
		if key != "" {
			uniqueUploads[key] = struct{}{}
		}
		stage := fmt.Sprint(item["stage"])
		switch stage {
		case "fehlgeschlagen":
			failedCount++
		case "wartet_auf_verbindung":
			waitingForNetworkCount++
		}
		if stage == "gestartet" || stage == "wartet_auf_bestaetigung" || stage == "wartend" || stage == "wartet_auf_verbindung" {
			liveCount++
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"items":      items,
		"sinceHours": sinceHours,
		"since":      since.In(s.Location),
		"serverNow":  serverNow.In(s.Location),
		"summary": gin.H{
			"total":                  len(items),
			"uniqueUploads":          len(uniqueUploads),
			"failedCount":            failedCount,
			"waitingForNetworkCount": waitingForNetworkCount,
			"liveCount":              liveCount,
		},
	})
}

func (s *Server) handleAdminDebugLogsSummary(c *gin.Context) {
	limit := 1000
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil {
			if n < 50 {
				n = 50
			}
			if n > 5000 {
				n = 5000
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
	type summaryRow struct {
		Count         int64
		FirstSeenAt   time.Time
		LastSeenAt    time.Time
		SampleMessage string
		SampleMeta    string
		FailureFamily string
		TopTransport  string
		UserID        uint
		Username      string
		DeviceName    string
		Signature     string
	}
	grouped := map[string]*summaryRow{}
	for _, row := range rows {
		meta := debugMetaPairs(row.Meta)
		failureFamily := debugFailureFamily(row)
		transport := strings.TrimSpace(meta["transport"])
		signature := strings.Join([]string{
			strconv.FormatUint(uint64(row.UserID), 10),
			row.DeviceName,
			row.Type,
			failureFamily,
			strings.TrimSpace(meta["endpoint"]),
			transport,
			strings.TrimSpace(meta["failureClass"]),
		}, "|")
		entry := grouped[signature]
		if entry == nil {
			entry = &summaryRow{
				Count:         0,
				FirstSeenAt:   row.CreatedAt,
				LastSeenAt:    row.CreatedAt,
				SampleMessage: row.Message,
				SampleMeta:    row.Meta,
				FailureFamily: failureFamily,
				TopTransport:  transport,
				UserID:        row.UserID,
				Username:      row.User.Username,
				DeviceName:    row.DeviceName,
				Signature:     signature,
			}
			grouped[signature] = entry
		}
		if row.CreatedAt.Before(entry.FirstSeenAt) {
			entry.FirstSeenAt = row.CreatedAt
		}
		if row.CreatedAt.After(entry.LastSeenAt) {
			entry.LastSeenAt = row.CreatedAt
			entry.SampleMessage = row.Message
			entry.SampleMeta = row.Meta
		}
		entry.Count += debugMetaCount(meta)
		if entry.TopTransport == "" && transport != "" {
			entry.TopTransport = transport
		}
	}
	items := make([]gin.H, 0, len(grouped))
	for _, row := range grouped {
		items = append(items, gin.H{
			"count":         row.Count,
			"firstSeenAt":   row.FirstSeenAt.In(s.Location),
			"lastSeenAt":    row.LastSeenAt.In(s.Location),
			"sampleMessage": row.SampleMessage,
			"sampleMeta":    row.SampleMeta,
			"failureFamily": row.FailureFamily,
			"topTransport":  row.TopTransport,
			"deviceName":    row.DeviceName,
			"signature":     row.Signature,
			"user": gin.H{
				"id":       row.UserID,
				"username": row.Username,
			},
		})
	}
	sort.Slice(items, func(i, j int) bool {
		return items[i]["count"].(int64) > items[j]["count"].(int64)
	})
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
	if reportType != "" && !isValidUserReportType(reportType) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid report type"})
		return
	}

	status := strings.ToLower(strings.TrimSpace(c.Query("status")))
	if status != "" && !isValidUserReportStatus(status) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid report status"})
		return
	}

	q := s.DB.Preload("User").Preload("Photo").Preload("Photo.User").Order("created_at desc").Limit(limit)
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
		items = append(items, s.userReportJSON(row))
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
	if reportType != "" && !isValidUserReportType(reportType) {
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
	if err := s.DB.Preload("User").Preload("Photo").Preload("Photo.User").First(&report, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "report not found"})
		return
	}

	report.Status = status
	report.GithubIssueNumber = req.GithubIssueNumber
	if err := s.DB.Save(&report).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	if err := s.DB.Preload("User").Preload("Photo").Preload("Photo.User").First(&report, id).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "reload failed"})
		return
	}

	c.JSON(http.StatusOK, s.userReportJSON(report))
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
	var affectedDays []string
	_ = s.DB.Model(&models.Photo{}).Where("user_id = ?", id).Distinct("day").Pluck("day", &affectedDays).Error

	_ = s.DB.Where("user_id = ?", id).Delete(&models.DeviceToken{}).Error
	_ = s.DB.Where("user_id = ?", id).Delete(&models.Photo{}).Error
	if err := s.DB.Delete(&user).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}
	for _, day := range affectedDays {
		s.invalidateFeedDayCache(day)
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

	var firstActivityDay string
	_ = s.DB.Raw(`
SELECT MIN(day) FROM (
    SELECT day FROM photos WHERE day <> ''
    UNION ALL SELECT day FROM daily_prompts WHERE day <> ''
) t
`).Scan(&firstActivityDay).Error
	if firstDay, err := time.ParseInLocation("2006-01-02", firstActivityDay, s.Location); err == nil {
		today := time.Now().In(s.Location)
		currentDay := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, s.Location)
		d := int64(currentDay.Sub(firstDay).Hours() / 24)
		if d >= 0 {
			runningDays = d + 1
		}
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
		"posts":    true,
	}
	if scopeRaw != "" {
		scopeSet = map[string]bool{
			"users":    false,
			"reports":  false,
			"commands": false,
			"history":  false,
			"posts":    false,
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

	if scopeSet["posts"] {
		adminUser, _ := userFromContext(c)
		if _, hits, err := s.searchPhotoHits(adminUser.ID, q, time.Now().In(s.Location), true, limit); err == nil {
			for _, hit := range hits {
				label := strings.TrimSpace(hit.Excerpt)
				if label == "" {
					label = strings.TrimSpace(hit.Photo.Caption)
				}
				if label == "" {
					label = fmt.Sprintf("@%s am %s", hit.Photo.User.Username, hit.Photo.Day)
				}
				metaParts := []string{"@" + hit.Photo.User.Username, hit.Photo.Day}
				if len(hit.MatchedHashtags) > 0 {
					metaParts = append(metaParts, strings.Join(hit.MatchedHashtags, " "))
				}
				items = append(items, gin.H{
					"type":  "posts",
					"id":    strconv.FormatUint(uint64(hit.Photo.ID), 10),
					"label": label,
					"meta":  strings.Join(metaParts, " | "),
					"target": gin.H{
						"tab": "feed",
						"day": hit.Photo.Day,
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

func (s *Server) handleAdminPolls(c *gin.Context) {
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
	var (
		fromTime *time.Time
		toTime   *time.Time
	)
	day := strings.TrimSpace(c.Query("day"))
	if day != "" {
		parsed, err := time.ParseInLocation("2006-01-02", day, s.Location)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid day"})
			return
		}
		start := parsed
		end := parsed.Add(24 * time.Hour)
		fromTime = &start
		toTime = &end
	} else {
		if raw := strings.TrimSpace(c.Query("from")); raw != "" {
			parsed, err := time.Parse(time.RFC3339, raw)
			if err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "invalid from"})
				return
			}
			v := parsed.In(s.Location)
			fromTime = &v
		}
		if raw := strings.TrimSpace(c.Query("to")); raw != "" {
			parsed, err := time.Parse(time.RFC3339, raw)
			if err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "invalid to"})
				return
			}
			v := parsed.In(s.Location)
			toTime = &v
		}
	}
	openOnly := false
	if raw := strings.TrimSpace(c.Query("openOnly")); raw != "" {
		if parsed, err := strconv.ParseBool(raw); err == nil {
			openOnly = parsed
		}
	}
	creatorUserID := uint(0)
	if raw := strings.TrimSpace(c.Query("creatorUserId")); raw != "" {
		n, err := strconv.ParseUint(raw, 10, 64)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid creatorUserId"})
			return
		}
		creatorUserID = uint(n)
	}
	type pollRow struct {
		ID               uint       `gorm:"column:id"`
		UserID           uint       `gorm:"column:user_id"`
		Username         string     `gorm:"column:username"`
		FavoriteColor    string     `gorm:"column:favorite_color"`
		Question         string     `gorm:"column:poll_question"`
		AllowMultiSelect bool       `gorm:"column:poll_allow_multiple"`
		ClosedAt         *time.Time `gorm:"column:poll_closed_at"`
		CreatedAt        time.Time  `gorm:"column:created_at"`
		Source           string     `gorm:"column:source"`
		Body             string     `gorm:"column:body"`
	}
	query := s.DB.Table("chat_messages AS cm").
		Select("cm.id, cm.user_id, u.username, u.favorite_color, cm.poll_question, cm.poll_allow_multiple, cm.poll_closed_at, cm.created_at, cm.source, cm.body").
		Joins("JOIN users u ON u.id = cm.user_id").
		Where("cm.message_type = ?", "poll")
	if fromTime != nil {
		query = query.Where("cm.created_at >= ?", *fromTime)
	}
	if toTime != nil {
		query = query.Where("cm.created_at < ?", *toTime)
	}
	if openOnly {
		query = query.Where("cm.poll_closed_at IS NULL")
	}
	if creatorUserID > 0 {
		query = query.Where("cm.user_id = ?", creatorUserID)
	}
	rows := make([]pollRow, 0, limit)
	if err := query.Order("cm.created_at desc").Limit(limit).Scan(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "poll query failed"})
		return
	}
	if len(rows) == 0 {
		c.JSON(http.StatusOK, gin.H{"items": []gin.H{}, "count": 0, "limit": limit})
		return
	}
	pollIDs := make([]uint, 0, len(rows))
	for _, row := range rows {
		pollIDs = append(pollIDs, row.ID)
	}
	type optionRow struct {
		ID            uint   `gorm:"column:id"`
		ChatMessageID uint   `gorm:"column:chat_message_id"`
		OptionText    string `gorm:"column:option_text"`
		SortOrder     int    `gorm:"column:sort_order"`
	}
	var optionRows []optionRow
	if err := s.DB.Table("chat_poll_options").
		Select("id, chat_message_id, option_text, sort_order").
		Where("chat_message_id IN ?", pollIDs).
		Order("chat_message_id asc, sort_order asc, id asc").
		Scan(&optionRows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "poll options query failed"})
		return
	}
	type voteRow struct {
		ChatMessageID uint      `gorm:"column:chat_message_id"`
		OptionID      uint      `gorm:"column:option_id"`
		UserID        uint      `gorm:"column:user_id"`
		Username      string    `gorm:"column:username"`
		FavoriteColor string    `gorm:"column:favorite_color"`
		CreatedAt     time.Time `gorm:"column:created_at"`
	}
	var voteRows []voteRow
	if err := s.DB.Table("chat_poll_votes AS v").
		Select("v.chat_message_id, v.option_id, v.user_id, u.username, u.favorite_color, v.created_at").
		Joins("JOIN users u ON u.id = v.user_id").
		Where("v.chat_message_id IN ?", pollIDs).
		Order("v.chat_message_id asc, v.option_id asc, v.created_at asc").
		Scan(&voteRows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "poll votes query failed"})
		return
	}
	optionByPoll := make(map[uint][]optionRow, len(pollIDs))
	for _, row := range optionRows {
		optionByPoll[row.ChatMessageID] = append(optionByPoll[row.ChatMessageID], row)
	}
	votesByOption := make(map[uint][]voteRow, len(voteRows))
	uniqueVoterByPoll := make(map[uint]map[uint]struct{}, len(pollIDs))
	for _, row := range voteRows {
		votesByOption[row.OptionID] = append(votesByOption[row.OptionID], row)
		if _, ok := uniqueVoterByPoll[row.ChatMessageID]; !ok {
			uniqueVoterByPoll[row.ChatMessageID] = map[uint]struct{}{}
		}
		uniqueVoterByPoll[row.ChatMessageID][row.UserID] = struct{}{}
	}
	items := make([]gin.H, 0, len(rows))
	for _, poll := range rows {
		options := make([]gin.H, 0, len(optionByPoll[poll.ID]))
		totalVotes := 0
		for _, option := range optionByPoll[poll.ID] {
			voters := make([]gin.H, 0, len(votesByOption[option.ID]))
			for _, vote := range votesByOption[option.ID] {
				voters = append(voters, gin.H{
					"userId":        vote.UserID,
					"username":      vote.Username,
					"favoriteColor": defaultColor(vote.FavoriteColor),
					"votedAt":       vote.CreatedAt,
				})
			}
			voteCount := len(voters)
			totalVotes += voteCount
			options = append(options, gin.H{
				"id":        option.ID,
				"text":      strings.TrimSpace(option.OptionText),
				"sortOrder": option.SortOrder,
				"votes":     voteCount,
				"voters":    voters,
			})
		}
		totalVoters := len(uniqueVoterByPoll[poll.ID])
		items = append(items, gin.H{
			"id":               poll.ID,
			"question":         strings.TrimSpace(poll.Question),
			"allowMultiSelect": poll.AllowMultiSelect,
			"isClosed":         poll.ClosedAt != nil,
			"closedAt":         poll.ClosedAt,
			"createdAt":        poll.CreatedAt,
			"source":           defaultIfBlank(strings.TrimSpace(poll.Source), "user"),
			"body":             poll.Body,
			"totalVotes":       totalVotes,
			"totalVoters":      totalVoters,
			"creator": gin.H{
				"id":            poll.UserID,
				"username":      poll.Username,
				"favoriteColor": defaultColor(poll.FavoriteColor),
			},
			"options": options,
		})
	}
	c.JSON(http.StatusOK, gin.H{
		"items": items,
		"count": len(items),
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
	debugLimit := 1000
	if raw := strings.TrimSpace(c.Query("debugLimit")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil {
			debugLimit = n
		}
	}
	if debugLimit < 100 {
		debugLimit = 100
	}
	if debugLimit > 5000 {
		debugLimit = 5000
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

	type dayTriggerAuditCounts struct {
		Attempts           int
		Blocked            int
		Failed             int
		DailyAttempts      int
		DailyBlocked       int
		DailyFailed        int
		DailyTriggered     int
		SpecialAttempts    int
		SpecialBlocked     int
		SpecialFailed      int
		SpecialTriggered   int
		DailyTriggeredAt   *time.Time
		SpecialTriggeredAt *time.Time
	}
	triggerAuditByDay := make(map[string]dayTriggerAuditCounts, len(dayList))
	var triggerAudits []models.DailyTriggerAuditEvent
	if err := s.DB.
		Select("day, source, result, occurred_at").
		Where("day >= ? AND day <= ?", oldest, newest).
		Find(&triggerAudits).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "history query failed"})
		return
	}
	for _, ev := range triggerAudits {
		counts := triggerAuditByDay[ev.Day]
		counts.Attempts++
		kind := triggerKindFromTriggerSource(ev.Source)
		if kind == "special" {
			counts.SpecialAttempts++
		} else {
			counts.DailyAttempts++
		}
		switch strings.ToLower(strings.TrimSpace(ev.Result)) {
		case "blocked":
			counts.Blocked++
			if kind == "special" {
				counts.SpecialBlocked++
			} else {
				counts.DailyBlocked++
			}
		case "failed":
			counts.Failed++
			if kind == "special" {
				counts.SpecialFailed++
			} else {
				counts.DailyFailed++
			}
		case "triggered":
			if kind == "special" {
				counts.SpecialTriggered++
				when := ev.OccurredAt
				if counts.SpecialTriggeredAt == nil || counts.SpecialTriggeredAt.Before(when) {
					counts.SpecialTriggeredAt = &when
				}
			} else {
				counts.DailyTriggered++
				when := ev.OccurredAt
				if counts.DailyTriggeredAt == nil || counts.DailyTriggeredAt.Before(when) {
					counts.DailyTriggeredAt = &when
				}
			}
		}
		triggerAuditByDay[ev.Day] = counts
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
	var debugLogTotal int64
	_ = s.DB.Model(&models.ClientDebugLog{}).Where("created_at >= ? AND created_at <= ?", dayStart, dayEnd).Count(&debugLogTotal).Error
	var debugLogs []models.ClientDebugLog
	_ = s.DB.Select("user_id", "type", "meta", "created_at").Where("created_at >= ? AND created_at <= ?", dayStart, dayEnd).Order("created_at DESC").Limit(debugLimit).Find(&debugLogs).Error

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
		postedUsers            map[uint]struct{}
		promptUsers            map[uint]struct{}
		extraUsers             map[uint]struct{}
		photoCount             int
		dailyMomentPhotos      int
		extraPhotos            int
		timeCapsules           int
		privateCapsules        int
		commentCount           int
		reactionCount          int
		chatMessageCount       int
		debugErrorCount        int
		debugConnectivityCount int
		debugCancelledCount    int
		debugServerCount       int
		debugCrashCount        int
		debugClientCount       int
		debugSignalUsers       map[string]map[uint]struct{}
		onlineUsers            map[uint]struct{}
		userActivity           map[uint]gin.H
	}

	metricsByDay := make(map[string]*dayMetrics, len(dayList))
	getMetrics := func(day string) *dayMetrics {
		if existing, ok := metricsByDay[day]; ok {
			return existing
		}
		created := &dayMetrics{
			postedUsers:      make(map[uint]struct{}),
			promptUsers:      make(map[uint]struct{}),
			extraUsers:       make(map[uint]struct{}),
			onlineUsers:      make(map[uint]struct{}),
			userActivity:     make(map[uint]gin.H),
			debugSignalUsers: make(map[string]map[uint]struct{}),
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
		category := debugSignalCategory(row)
		if category == "" {
			continue
		}
		metrics := getMetrics(row.CreatedAt.In(s.Location).Format("2006-01-02"))
		if metrics.debugSignalUsers[category] == nil {
			metrics.debugSignalUsers[category] = make(map[uint]struct{})
		}
		metrics.debugSignalUsers[category][row.UserID] = struct{}{}
		count := int(debugMetaCount(debugMetaPairs(row.Meta)))
		switch category {
		case "connectivity":
			metrics.debugConnectivityCount += count
		case "cancelled":
			metrics.debugCancelledCount += count
		case "server":
			metrics.debugServerCount += count
			metrics.debugErrorCount += count
		case "crash":
			metrics.debugCrashCount += count
			metrics.debugErrorCount += count
		default:
			metrics.debugClientCount += count
			metrics.debugErrorCount += count
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
	totalDebugConnectivity := 0
	totalDebugCancelled := 0
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
			metrics.debugErrorCount == 0 &&
			triggerAuditByDay[day].Attempts == 0
		if excludeEmpty && isEmptyDay {
			continue
		}
		row := gin.H{
			"day":                         day,
			"plannedAt":                   nil,
			"triggeredAt":                 nil,
			"uploadUntil":                 nil,
			"source":                      "auto",
			"triggerSource":               "",
			"requestedByUser":             "",
			"momentKind":                  "daily",
			"onlineUsersCount":            nil,
			"postedUsersCount":            len(metrics.postedUsers),
			"dailyMomentUsersCount":       len(metrics.promptUsers),
			"extraUsersCount":             len(metrics.extraUsers),
			"photoCount":                  metrics.photoCount,
			"dailyMomentPhotoCount":       metrics.dailyMomentPhotos,
			"extraPhotoCount":             metrics.extraPhotos,
			"timeCapsuleCount":            metrics.timeCapsules,
			"privateCapsuleCount":         metrics.privateCapsules,
			"commentCount":                metrics.commentCount,
			"reactionCount":               metrics.reactionCount,
			"chatMessageCount":            metrics.chatMessageCount,
			"debugErrorCount":             metrics.debugErrorCount,
			"debugConnectivityCount":      metrics.debugConnectivityCount,
			"debugCancelledCount":         metrics.debugCancelledCount,
			"debugServerCount":            metrics.debugServerCount,
			"debugCrashCount":             metrics.debugCrashCount,
			"debugClientCount":            metrics.debugClientCount,
			"onlineTrackingAvailable":     onlineTrackingAvailable,
			"triggerAttemptCount":         triggerAuditByDay[day].Attempts,
			"triggerBlockedCount":         triggerAuditByDay[day].Blocked,
			"triggerFailedCount":          triggerAuditByDay[day].Failed,
			"dailyTriggerAttemptCount":    triggerAuditByDay[day].DailyAttempts,
			"dailyTriggerBlockedCount":    triggerAuditByDay[day].DailyBlocked,
			"dailyTriggerFailedCount":     triggerAuditByDay[day].DailyFailed,
			"dailyTriggeredCount":         triggerAuditByDay[day].DailyTriggered,
			"specialTriggerAttemptCount":  triggerAuditByDay[day].SpecialAttempts,
			"specialTriggerBlockedCount":  triggerAuditByDay[day].SpecialBlocked,
			"specialTriggerFailedCount":   triggerAuditByDay[day].SpecialFailed,
			"specialTriggeredCount":       triggerAuditByDay[day].SpecialTriggered,
			"dailyTriggeredAt":            triggerAuditByDay[day].DailyTriggeredAt,
			"specialTriggeredAt":          triggerAuditByDay[day].SpecialTriggeredAt,
			"dailyPending":                triggerAuditByDay[day].DailyTriggered == 0,
			"multipleTriggerAlert":        triggerAuditByDay[day].DailyAttempts > 1,
			"dailyMultipleTriggerAlert":   triggerAuditByDay[day].DailyAttempts > 1,
			"specialMultipleTriggerAlert": triggerAuditByDay[day].SpecialAttempts > 1,
			"userActivity":                userActivityRows,
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
			row["momentKind"] = momentKindFromTriggerSource(prompt.TriggerSource)
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
		totalDebugConnectivity += metrics.debugConnectivityCount
		totalDebugCancelled += metrics.debugCancelledCount
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
		if metrics.debugServerCount > 0 || metrics.debugCrashCount > 0 {
			anomalies = append(anomalies, gin.H{
				"day":      day,
				"severity": "high",
				"reason":   "server or crash signal observed",
				"details":  fmt.Sprintf("server=%d crash=%d", metrics.debugServerCount, metrics.debugCrashCount),
			})
		} else if metrics.debugClientCount >= 3 && len(metrics.debugSignalUsers["client"]) >= 2 {
			anomalies = append(anomalies, gin.H{
				"day":      day,
				"severity": "medium",
				"reason":   "repeated client failures across users",
				"details":  fmt.Sprintf("client=%d users=%d", metrics.debugClientCount, len(metrics.debugSignalUsers["client"])),
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
		"connectivityIndicators":         totalDebugConnectivity,
		"cancelledIndicators":            totalDebugCancelled,
		"avgPostedUsersPerDay":           avgPostedUsersPerDay,
		"avgOnlineUsersPerDay":           avgOnlineUsersPerDay,
		"avgRequestsPerOnlineUser":       avgRequestsPerOnlineUser,
	}

	c.JSON(http.StatusOK, gin.H{
		"items":               items,
		"days":                days,
		"offset":              offset,
		"excludeEmpty":        excludeEmpty,
		"debugLogSample":      gin.H{"loaded": len(debugLogs), "total": debugLogTotal, "limit": debugLimit, "truncated": debugLogTotal > int64(len(debugLogs))},
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

func requestIDFromContext(c *gin.Context) string {
	if c == nil {
		return ""
	}
	if requestID, ok := c.Get("requestId"); ok {
		return strings.TrimSpace(fmt.Sprint(requestID))
	}
	return strings.TrimSpace(c.GetHeader("X-Request-ID"))
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

func parsePositiveQueryInt(c *gin.Context, key string, fallback int, maxAllowed int) (int, error) {
	raw := strings.TrimSpace(c.Query(key))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value <= 0 {
		return 0, fmt.Errorf("invalid %s", key)
	}
	if maxAllowed > 0 && value > maxAllowed {
		return maxAllowed, nil
	}
	return value, nil
}

func parseNonNegativeQueryInt(c *gin.Context, key string, fallback int) (int, error) {
	raw := strings.TrimSpace(c.Query(key))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 0 {
		return 0, fmt.Errorf("invalid %s", key)
	}
	return value, nil
}

func slicesIndex(items []string, target string) int {
	for idx, item := range items {
		if item == target {
			return idx
		}
	}
	return -1
}

func uniqueDays(days []string) []string {
	if len(days) == 0 {
		return days
	}
	out := make([]string, 0, len(days))
	seen := make(map[string]struct{}, len(days))
	for _, day := range days {
		day = strings.TrimSpace(day)
		if day == "" {
			continue
		}
		if _, ok := seen[day]; ok {
			continue
		}
		seen[day] = struct{}{}
		out = append(out, day)
	}
	return out
}

func filterDaysAfter(days []string, anchorDay string) []string {
	out := make([]string, 0, len(days))
	for _, day := range days {
		if day > anchorDay {
			out = append(out, day)
		}
	}
	return out
}

func filterDaysBefore(days []string, anchorDay string) []string {
	out := make([]string, 0, len(days))
	for _, day := range days {
		if day < anchorDay {
			out = append(out, day)
		}
	}
	return out
}

func parseOptionalInt64(raw string) *int64 {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return nil
	}
	return &value
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
	sendResult, err := s.Notifier.Send(tokens, notify.Message{
		Title:  "Daily Nachricht",
		Body:   strings.TrimSpace(req.Body),
		Type:   "broadcast",
		Action: "open_app",
	})
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
	sendResult, sendErr := s.Notifier.Send(tokens, notify.Message{
		Title:  "Daily Nachricht",
		Body:   strings.TrimSpace(req.Body),
		Type:   "broadcast",
		Action: "open_app",
	})
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
	_ = s.DB.Session(&gorm.Session{AllowGlobalUpdate: true}).Delete(&models.ChatPollVote{}).Error
	_ = s.DB.Session(&gorm.Session{AllowGlobalUpdate: true}).Delete(&models.ChatPollOption{}).Error
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleChatList(c *gin.Context) {
	viewer, _ := userFromContext(c)
	items, err := s.chatListPayload(viewer)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) chatListPayload(viewer models.User) ([]gin.H, error) {
	type chatListRow struct {
		ID                uint       `gorm:"column:id"`
		Body              string     `gorm:"column:body"`
		Source            string     `gorm:"column:source"`
		MessageType       string     `gorm:"column:message_type"`
		PollQuestion      string     `gorm:"column:poll_question"`
		PollAllowMultiple bool       `gorm:"column:poll_allow_multiple"`
		PollClosedAt      *time.Time `gorm:"column:poll_closed_at"`
		CreatedAt         time.Time  `gorm:"column:created_at"`
		UserID            uint       `gorm:"column:user_id"`
		Username          string     `gorm:"column:username"`
		FavoriteColor     string     `gorm:"column:favorite_color"`
	}
	queryStart := time.Now()
	rows := make([]chatListRow, 0, 100)
	err := s.DB.Table("chat_messages AS cm").
		Select("cm.id, cm.body, cm.source, cm.message_type, cm.poll_question, cm.poll_allow_multiple, cm.poll_closed_at, cm.created_at, u.id AS user_id, u.username, u.favorite_color").
		Joins("JOIN users u ON u.id = cm.user_id").
		Order("cm.created_at desc").
		Limit(100).
		Scan(&rows).Error
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/chat", "chat_list_query", time.Since(queryStart))
	}
	if err != nil {
		return nil, err
	}
	pollMessageIDs := make([]uint, 0)
	for _, r := range rows {
		if defaultIfBlank(strings.TrimSpace(r.MessageType), "text") == "poll" {
			pollMessageIDs = append(pollMessageIDs, r.ID)
		}
	}
	pollPayloadByMessageID, err := s.chatPollPayloadByMessageID(viewer, pollMessageIDs)
	if err != nil {
		return nil, err
	}
	out := make([]gin.H, 0, len(rows))
	for i := len(rows) - 1; i >= 0; i-- {
		r := rows[i]
		msgType := defaultIfBlank(strings.TrimSpace(r.MessageType), "text")
		pollPayload := any(nil)
		if msgType == "poll" {
			poll := pollPayloadByMessageID[r.ID]
			if poll == nil {
				poll = gin.H{
					"options":             []gin.H{},
					"mySelectedOptionIds": []uint{},
					"totalVoters":         int64(0),
				}
			}
			poll["question"] = strings.TrimSpace(r.PollQuestion)
			poll["allowMultiSelect"] = r.PollAllowMultiple
			poll["isClosed"] = r.PollClosedAt != nil
			poll["closedAt"] = r.PollClosedAt
			poll["canClose"] = viewer.IsAdmin || r.UserID == viewer.ID
			pollPayload = poll
		}
		out = append(out, gin.H{
			"id":        r.ID,
			"type":      msgType,
			"body":      r.Body,
			"source":    defaultIfBlank(strings.TrimSpace(r.Source), "user"),
			"createdAt": r.CreatedAt,
			"poll":      pollPayload,
			"user": s.userPublicJSON(viewer.ID, models.User{
				ID:            r.UserID,
				Username:      r.Username,
				FavoriteColor: r.FavoriteColor,
			}),
		})
	}
	return out, nil
}

func (s *Server) chatPollPayloadByMessageID(viewer models.User, messageIDs []uint) (map[uint]gin.H, error) {
	out := make(map[uint]gin.H, len(messageIDs))
	if len(messageIDs) == 0 {
		return out, nil
	}
	type optionRow struct {
		ID            uint   `gorm:"column:id"`
		ChatMessageID uint   `gorm:"column:chat_message_id"`
		OptionText    string `gorm:"column:option_text"`
		SortOrder     int    `gorm:"column:sort_order"`
	}
	var optionRows []optionRow
	if err := s.DB.Table("chat_poll_options").
		Select("id, chat_message_id, option_text, sort_order").
		Where("chat_message_id IN ?", messageIDs).
		Order("chat_message_id asc, sort_order asc, id asc").
		Scan(&optionRows).Error; err != nil {
		return nil, err
	}
	type voteCountRow struct {
		ChatMessageID uint  `gorm:"column:chat_message_id"`
		OptionID      uint  `gorm:"column:option_id"`
		Count         int64 `gorm:"column:count"`
	}
	var voteCounts []voteCountRow
	if err := s.DB.Table("chat_poll_votes").
		Select("chat_message_id, option_id, COUNT(*) as count").
		Where("chat_message_id IN ?", messageIDs).
		Group("chat_message_id, option_id").
		Scan(&voteCounts).Error; err != nil {
		return nil, err
	}
	type voterCountRow struct {
		ChatMessageID uint  `gorm:"column:chat_message_id"`
		Count         int64 `gorm:"column:count"`
	}
	var voterCounts []voterCountRow
	if err := s.DB.Table("chat_poll_votes").
		Select("chat_message_id, COUNT(DISTINCT user_id) as count").
		Where("chat_message_id IN ?", messageIDs).
		Group("chat_message_id").
		Scan(&voterCounts).Error; err != nil {
		return nil, err
	}
	type myVoteRow struct {
		ChatMessageID uint `gorm:"column:chat_message_id"`
		OptionID      uint `gorm:"column:option_id"`
	}
	var myVotes []myVoteRow
	if err := s.DB.Table("chat_poll_votes").
		Select("chat_message_id, option_id").
		Where("chat_message_id IN ? AND user_id = ?", messageIDs, viewer.ID).
		Scan(&myVotes).Error; err != nil {
		return nil, err
	}
	voteCountByOptionID := make(map[uint]int64, len(voteCounts))
	for _, row := range voteCounts {
		voteCountByOptionID[row.OptionID] = row.Count
	}
	totalByMessageID := make(map[uint]int64, len(voterCounts))
	for _, row := range voterCounts {
		totalByMessageID[row.ChatMessageID] = row.Count
	}
	selectedByMessageID := make(map[uint]map[uint]struct{}, len(messageIDs))
	selectedIDsByMessageID := make(map[uint][]uint, len(messageIDs))
	for _, row := range myVotes {
		if _, ok := selectedByMessageID[row.ChatMessageID]; !ok {
			selectedByMessageID[row.ChatMessageID] = map[uint]struct{}{}
		}
		if _, exists := selectedByMessageID[row.ChatMessageID][row.OptionID]; exists {
			continue
		}
		selectedByMessageID[row.ChatMessageID][row.OptionID] = struct{}{}
		selectedIDsByMessageID[row.ChatMessageID] = append(selectedIDsByMessageID[row.ChatMessageID], row.OptionID)
	}
	optionsByMessageID := make(map[uint][]gin.H, len(messageIDs))
	for _, row := range optionRows {
		_, selected := selectedByMessageID[row.ChatMessageID][row.ID]
		optionsByMessageID[row.ChatMessageID] = append(optionsByMessageID[row.ChatMessageID], gin.H{
			"id":       row.ID,
			"text":     strings.TrimSpace(row.OptionText),
			"votes":    voteCountByOptionID[row.ID],
			"selected": selected,
		})
	}
	for _, messageID := range messageIDs {
		options := optionsByMessageID[messageID]
		if options == nil {
			options = []gin.H{}
		}
		selectedIDs := selectedIDsByMessageID[messageID]
		if selectedIDs == nil {
			selectedIDs = []uint{}
		}
		out[messageID] = gin.H{
			"options":             options,
			"mySelectedOptionIds": selectedIDs,
			"totalVoters":         totalByMessageID[messageID],
		}
	}
	return out, nil
}

func (s *Server) handleFeedDays(c *gin.Context) {
	user, _ := userFromContext(c)
	fromDay := strings.TrimSpace(c.Query("from"))
	toDay := strings.TrimSpace(c.Query("to"))
	beforeDay := strings.TrimSpace(c.Query("before_day"))
	afterDay := strings.TrimSpace(c.Query("after_day"))
	anchorDay := strings.TrimSpace(c.Query("anchor_day"))
	limit, err := parsePositiveQueryInt(c, "limit", 60, 180)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	now := time.Now().In(s.Location)
	days, hasOlder, hasNewer, err := s.feedDaysForUser(user.ID, fromDay, toDay, beforeDay, afterDay, limit, anchorDay, now)
	if err != nil {
		if strings.Contains(err.Error(), "invalid") || strings.Contains(err.Error(), "from/to") || strings.Contains(err.Error(), "before_day/after_day") {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	response := gin.H{
		"items":    days,
		"hasOlder": hasOlder,
		"hasNewer": hasNewer,
	}
	if strings.EqualFold(strings.TrimSpace(c.Query("include_bounds")), "true") {
		oldestVisibleDay, newestVisibleDay, err := s.feedDayBoundsForUser(user.ID, now)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
		response["oldestVisibleDay"] = oldestVisibleDay
		response["newestVisibleDay"] = newestVisibleDay
	}
	c.JSON(http.StatusOK, response)
}

// feedDayBoundsForUser returns global feed boundaries under exactly the same
// visibility and today's-feed-lock rules as feedDaysForUser. The mobile client
// uses this to jump to the real beginning without repeatedly paging through a
// partial local day index.
func (s *Server) feedDayBoundsForUser(userID uint, now time.Time) (string, string, error) {
	today := now.Format("2006-01-02")
	hasPostedToday, err := s.userHasVisiblePhotoForDay(userID, today, now)
	if err != nil {
		return "", "", err
	}
	type bounds struct {
		OldestVisibleDay string
		NewestVisibleDay string
	}
	var result bounds
	query := s.DB.Model(&models.Photo{}).
		Where("user_id = ? OR (capsule_visible_at IS NULL OR capsule_visible_at <= ?)", userID, now)
	if !hasPostedToday {
		query = query.Where("day <> ?", today)
	}
	queryStart := time.Now()
	if err := query.Select("MIN(day) AS oldest_visible_day, MAX(day) AS newest_visible_day").Scan(&result).Error; err != nil {
		return "", "", err
	}
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/feed/days", "feed_day_bounds_query", time.Since(queryStart))
	}
	return result.OldestVisibleDay, result.NewestVisibleDay, nil
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
		var fotomojiRows []interactionRow
		if err := s.DB.Model(&models.PhotoFotomoji{}).
			Select("photo_id, COUNT(*) as count").
			Where("photo_id IN ?", photoIDs).
			Group("photo_id").
			Scan(&fotomojiRows).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
		for _, row := range fotomojiRows {
			reactionCounts[row.PhotoID] += row.Count
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

func (s *Server) handleCalendarPublic(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	compact := strings.EqualFold(strings.TrimSpace(c.Query("compact")), "true")
	view := strings.ToLower(strings.TrimSpace(c.Query("view")))
	if view != "" && view != "index" && view != "window" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "unsupported calendar view"})
		return
	}
	before := strings.TrimSpace(c.Query("before"))
	if view == "window" && before != "" {
		if _, err := time.Parse("2006-01-02", before); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid calendar cursor"})
			return
		}
	}
	limit := 14
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid calendar limit"})
			return
		}
		limit = parsed
	}
	revision := int64(0)
	if compact {
		revision = s.syncRevision(calendarRevisionScope)
		etagScope := "all"
		if view == "index" {
			etagScope = "index"
		} else if view == "window" {
			etagScope = view + ":before=" + before + ":limit=" + strconv.Itoa(limit)
		}
		etag := revisionETag("calendar-public", map[string]int64{etagScope: revision})
		c.Header("ETag", etag)
		c.Header("Cache-Control", "private, no-cache")
		if strings.TrimSpace(c.GetHeader("If-None-Match")) == etag {
			c.Status(http.StatusNotModified)
			c.Writer.WriteHeaderNow()
			return
		}
	}
	var payload gin.H
	var err error
	if compact {
		switch view {
		case "index":
			payload, err = s.calendarPublicIndexPayload(user.ID, now)
		case "window":
			payload, err = s.calendarPublicCompactWindowPayload(user.ID, now, before, limit)
		default:
			// Legacy compact consumers keep the historical complete-card response.
			payload, err = s.calendarPublicCompactPayload(user.ID, now)
		}
	} else {
		payload, err = s.calendarPayload(user.ID, "public", 0, now)
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if compact {
		payload["schemaVersion"] = ifThenCalendarSchema(view)
		payload["revision"] = revision
		payload["serverNow"] = now.UTC()
	}
	c.JSON(http.StatusOK, payload)
}

func ifThenCalendarSchema(view string) string {
	switch view {
	case "index":
		return "calendar_public_index_v1"
	case "window":
		return "calendar_public_window_v1"
	default:
		return "calendar_public_v2"
	}
}

// compactCalendarPublicPayload keeps the calendar metadata and image references
// but removes the duplicated feed representation and large interactive drawing
// payloads. Feed details are fetched through the revisioned feed endpoint.
func compactCalendarPublicPayload(payload gin.H) gin.H {
	result := gin.H{}
	for key, value := range payload {
		result[key] = value
	}
	result["items"] = []gin.H{}
	photosByDay, ok := payload["photosByDay"].(map[string][]gin.H)
	if !ok {
		return result
	}
	compactDays := make(map[string][]gin.H, len(photosByDay))
	for day, rows := range photosByDay {
		compactRows := make([]gin.H, 0, len(rows))
		for _, row := range rows {
			copyRow := gin.H{}
			for key, value := range row {
				copyRow[key] = value
			}
			if photo, photoOK := row["photo"].(gin.H); photoOK {
				copyPhoto := gin.H{}
				for key, value := range photo {
					copyPhoto[key] = value
				}
				delete(copyPhoto, "marks")
				delete(copyPhoto, "paints")
				copyRow["photo"] = copyPhoto
			}
			compactRows = append(compactRows, copyRow)
		}
		compactDays[day] = compactRows
	}
	result["photosByDay"] = compactDays
	return result
}

func (s *Server) handleCalendarUser(c *gin.Context) {
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
	now := time.Now().In(s.Location)
	payload, err := s.calendarPayload(viewer.ID, "user", targetID, now)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, payload)
}

func (s *Server) handleCalendarBookmarks(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	scope := strings.ToLower(strings.TrimSpace(c.Query("scope")))
	payloadScope := "bookmarks"
	if scope == "all" {
		payloadScope = "bookmarks_all"
	}
	payload, err := s.calendarPayload(user.ID, payloadScope, 0, now)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, payload)
}

func (s *Server) handleCalendarTimeCapsules(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	payload, err := s.calendarTimeCapsulesPayload(user.ID, now)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, payload)
}

func (s *Server) handleCalendarSearch(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	payload, err := s.calendarSearchPayload(user.ID, c.Query("q"), now)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, payload)
}

func (s *Server) handlePhotoBookmarkCreate(c *gin.Context) {
	user, _ := userFromContext(c)
	photo, err := s.loadVisiblePhotoForViewer(user.ID, c.Param("id"))
	if err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, gorm.ErrRecordNotFound):
			status = http.StatusNotFound
		case err.Error() == "invalid_photo_id":
			status = http.StatusBadRequest
		case err.Error() == "not_visible":
			status = http.StatusForbidden
		}
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}
	if err := s.setManualPhotoBookmark(user.ID, photo.ID, time.Now().UTC()); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "bookmark failed"})
		return
	}
	decorations, err := s.photoDecorationsForViewer(user.ID, []uint{photo.ID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "photo decorations query failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, gin.H{"ok": true, "photo": s.photoJSONForViewer(user.ID, photo, decorations)})
}

func (s *Server) handlePhotoBookmarkDelete(c *gin.Context) {
	user, _ := userFromContext(c)
	photo, err := s.loadVisiblePhotoForViewer(user.ID, c.Param("id"))
	if err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, gorm.ErrRecordNotFound):
			status = http.StatusNotFound
		case err.Error() == "invalid_photo_id":
			status = http.StatusBadRequest
		case err.Error() == "not_visible":
			status = http.StatusForbidden
		}
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}
	if err := s.removePhotoBookmark(user.ID, photo.ID, time.Now().UTC()); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "bookmark delete failed"})
		return
	}
	decorations, err := s.photoDecorationsForViewer(user.ID, []uint{photo.ID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "photo decorations query failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, gin.H{"ok": true, "photo": s.photoJSONForViewer(user.ID, photo, decorations)})
}

func (s *Server) handlePhotoBookmarksClear(c *gin.Context) {
	user, _ := userFromContext(c)
	var affectedDays []string
	if err := s.DB.Table("photo_bookmarks").
		Select("DISTINCT photos.day").
		Joins("JOIN photos ON photos.id = photo_bookmarks.photo_id").
		Where("photo_bookmarks.user_id = ?", user.ID).
		Pluck("photos.day", &affectedDays).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "bookmark query failed"})
		return
	}
	result := s.DB.Where("user_id = ?", user.ID).Delete(&models.PhotoBookmark{})
	if result.Error != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "bookmark clear failed"})
		return
	}
	for _, day := range affectedDays {
		s.invalidateFeedDayCache(day)
	}
	c.JSON(http.StatusOK, gin.H{"ok": true, "deletedCount": result.RowsAffected})
}

func canViewerMarkPhoto(viewerID uint, photo models.Photo) bool {
	if viewerID == photo.UserID {
		return true
	}
	return creativeModeAllowsMark(photo.User.CreativePostMode)
}

func canViewerPaintPhoto(viewerID uint, photo models.Photo) bool {
	if viewerID == photo.UserID {
		return true
	}
	return creativeModeAllowsPaint(photo.User.CreativePostMode)
}

func (s *Server) handlePhotoNsfwCreate(c *gin.Context) {
	user, _ := userFromContext(c)
	photo, err := s.loadVisiblePhotoForViewer(user.ID, c.Param("id"))
	if err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, gorm.ErrRecordNotFound):
			status = http.StatusNotFound
		case err.Error() == "invalid_photo_id":
			status = http.StatusBadRequest
		case err.Error() == "not_visible":
			status = http.StatusForbidden
		}
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}
	if !canViewerMarkNsfwPhoto(user, photo) {
		c.JSON(http.StatusForbidden, gin.H{"error": "nsfw_marking_not_allowed"})
		return
	}
	now := time.Now().UTC()
	updates := map[string]any{
		"nsfw":                   true,
		"nsfw_marked_by_user_id": user.ID,
		"nsfw_marked_at":         &now,
	}
	if err := s.DB.Model(&models.Photo{}).Where("id = ?", photo.ID).Updates(updates).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "nsfw_save_failed"})
		return
	}
	photo.Nsfw = true
	photo.NsfwMarkedByUserID = &user.ID
	photo.NsfwMarkedAt = &now
	if err := s.handlePhotoInteractionSubscription(photo, user.ID, "nsfw", now); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "bookmark sync failed"})
		return
	}
	s.notifyPhotoNsfwMarked(user, photo)
	decorations, err := s.photoDecorationsForViewer(user.ID, []uint{photo.ID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "photo decorations query failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, gin.H{"ok": true, "photo": s.photoJSONForViewer(user.ID, photo, decorations)})
}

func (s *Server) handlePhotoNsfwDelete(c *gin.Context) {
	user, _ := userFromContext(c)
	photo, err := s.loadVisiblePhotoForViewer(user.ID, c.Param("id"))
	if err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, gorm.ErrRecordNotFound):
			status = http.StatusNotFound
		case err.Error() == "invalid_photo_id":
			status = http.StatusBadRequest
		case err.Error() == "not_visible":
			status = http.StatusForbidden
		}
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}
	if !canViewerUnmarkNsfwPhoto(user, photo) {
		c.JSON(http.StatusForbidden, gin.H{"error": "nsfw_unmarking_not_allowed"})
		return
	}
	if err := s.DB.Exec(
		"UPDATE photos SET nsfw = ?, nsfw_marked_by_user_id = NULL, nsfw_marked_at = NULL WHERE id = ?",
		false,
		photo.ID,
	).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "nsfw_delete_failed"})
		return
	}
	photo.Nsfw = false
	photo.NsfwMarkedByUserID = nil
	photo.NsfwMarkedAt = nil
	s.notifyPhotoNsfwUnmarked(user, photo)
	decorations, err := s.photoDecorationsForViewer(user.ID, []uint{photo.ID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "photo decorations query failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, gin.H{"ok": true, "photo": s.photoJSONForViewer(user.ID, photo, decorations)})
}

func generatePhotoMark(user models.User, photo models.Photo) models.PhotoMark {
	seed := time.Now().UTC().UnixNano() ^ (int64(user.ID) << 16) ^ (int64(photo.ID) << 32)
	rng := mrand.New(mrand.NewSource(seed))
	centerX := 0.16 + rng.Float64()*0.68
	centerY := 0.16 + rng.Float64()*0.68
	return models.PhotoMark{
		PhotoID:   photo.ID,
		UserID:    user.ID,
		Color:     defaultColor(user.FavoriteColor),
		Surface:   "card",
		CenterX:   centerX,
		CenterY:   centerY,
		RadiusX:   0.09 + rng.Float64()*0.09,
		RadiusY:   0.07 + rng.Float64()*0.08,
		Rotation:  -25 + rng.Float64()*50,
		Seed:      seed,
		Layer:     time.Now().UTC().UnixMilli(),
		CreatedAt: time.Now().UTC(),
		UpdatedAt: time.Now().UTC(),
	}
}

func sanitizePhotoPaintPaths(paths []photoPaintPath) ([]photoPaintPath, error) {
	if len(paths) == 0 {
		return nil, errors.New("paint_empty")
	}
	if len(paths) > maxPhotoPaintPaths {
		return nil, errors.New("paint_too_many_paths")
	}
	out := make([]photoPaintPath, 0, len(paths))
	for _, path := range paths {
		if len(path.Points) < 2 {
			continue
		}
		if len(path.Points) > maxPhotoPaintPointsPerPath {
			return nil, errors.New("paint_too_many_points")
		}
		points := make([]photoPaintPoint, 0, len(path.Points))
		for _, point := range path.Points {
			points = append(points, photoPaintPoint{
				X: math.Max(0, math.Min(1, point.X)),
				Y: math.Max(0, math.Min(1, point.Y)),
			})
		}
		out = append(out, photoPaintPath{Points: points})
	}
	if len(out) == 0 {
		return nil, errors.New("paint_empty")
	}
	return out, nil
}

func parsePhotoPaintDeleteTarget(c *gin.Context, caller models.User, photo models.Photo) (uint, error) {
	targetUserID := caller.ID
	if raw := strings.TrimSpace(c.Query("userId")); raw != "" {
		parsed, err := parseUintParam(raw)
		if err != nil {
			return 0, errors.New("invalid_user_id")
		}
		targetUserID = parsed
	}
	if targetUserID != caller.ID && caller.ID != photo.UserID {
		return 0, errors.New("forbidden_target_user")
	}
	return targetUserID, nil
}

func (s *Server) handlePhotoMarkCreate(c *gin.Context) {
	user, _ := userFromContext(c)
	photo, err := s.loadVisiblePhotoForViewer(user.ID, c.Param("id"))
	if err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, gorm.ErrRecordNotFound):
			status = http.StatusNotFound
		case err.Error() == "invalid_photo_id":
			status = http.StatusBadRequest
		case err.Error() == "not_visible":
			status = http.StatusForbidden
		}
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}
	if !canViewerMarkPhoto(user.ID, photo) {
		c.JSON(http.StatusForbidden, gin.H{"error": "marking_not_allowed"})
		return
	}

	mark := generatePhotoMark(user, photo)
	var existing models.PhotoMark
	err = s.DB.Where("photo_id = ? AND user_id = ?", photo.ID, user.ID).First(&existing).Error
	switch {
	case err == nil:
		mark.ID = existing.ID
		mark.CreatedAt = existing.CreatedAt
		if err := s.DB.Save(&mark).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "mark_save_failed"})
			return
		}
	case errors.Is(err, gorm.ErrRecordNotFound):
		if err := s.DB.Create(&mark).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "mark_create_failed"})
			return
		}
	default:
		c.JSON(http.StatusInternalServerError, gin.H{"error": "mark_query_failed"})
		return
	}
	if err := s.handlePhotoInteractionSubscription(photo, user.ID, "mark", time.Now().UTC()); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "bookmark sync failed"})
		return
	}
	decorations, err := s.photoDecorationsForViewer(user.ID, []uint{photo.ID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "photo decorations query failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, gin.H{"ok": true, "photo": s.photoJSONForViewer(user.ID, photo, decorations)})
}

func (s *Server) handlePhotoMarkDelete(c *gin.Context) {
	user, _ := userFromContext(c)
	photo, err := s.loadVisiblePhotoForViewer(user.ID, c.Param("id"))
	if err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, gorm.ErrRecordNotFound):
			status = http.StatusNotFound
		case err.Error() == "invalid_photo_id":
			status = http.StatusBadRequest
		case err.Error() == "not_visible":
			status = http.StatusForbidden
		}
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}
	targetUserID, err := parsePhotoPaintDeleteTarget(c, user, photo)
	if err != nil {
		status := http.StatusForbidden
		if err.Error() == "invalid_user_id" {
			status = http.StatusBadRequest
		}
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}
	if err := s.DB.Where("photo_id = ? AND user_id = ?", photo.ID, targetUserID).Delete(&models.PhotoMark{}).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "mark_delete_failed"})
		return
	}
	decorations, err := s.photoDecorationsForViewer(user.ID, []uint{photo.ID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "photo decorations query failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, gin.H{"ok": true, "photo": s.photoJSONForViewer(user.ID, photo, decorations)})
}

func (s *Server) handlePhotoPaintUpsert(c *gin.Context) {
	user, _ := userFromContext(c)
	photo, err := s.loadVisiblePhotoForViewer(user.ID, c.Param("id"))
	if err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, gorm.ErrRecordNotFound):
			status = http.StatusNotFound
		case err.Error() == "invalid_photo_id":
			status = http.StatusBadRequest
		case err.Error() == "not_visible":
			status = http.StatusForbidden
		}
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}
	if !canViewerPaintPhoto(user.ID, photo) {
		c.JSON(http.StatusForbidden, gin.H{"error": "painting_not_allowed"})
		return
	}
	var req struct {
		Paths       []photoPaintPath `json:"paths"`
		StrokeWidth float64          `json:"strokeWidth"`
		Surface     string           `json:"surface"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid_payload"})
		return
	}
	sanitizedPaths, err := sanitizePhotoPaintPaths(req.Paths)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	strokeWidth := req.StrokeWidth
	if strokeWidth <= 0 {
		strokeWidth = 0.035
	}
	if strokeWidth < 0.01 {
		strokeWidth = 0.01
	}
	if strokeWidth > 0.12 {
		strokeWidth = 0.12
	}
	surface := "card"
	if strings.EqualFold(strings.TrimSpace(req.Surface), "frame") {
		surface = "frame"
	}
	pathsJSON, err := json.Marshal(sanitizedPaths)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "paint_encode_failed"})
		return
	}
	paint := models.PhotoPaint{
		PhotoID:     photo.ID,
		UserID:      user.ID,
		Color:       defaultColor(user.FavoriteColor),
		Surface:     surface,
		StrokeWidth: strokeWidth,
		PathsJSON:   string(pathsJSON),
		UpdatedAt:   time.Now().UTC(),
	}
	var existing models.PhotoPaint
	err = s.DB.Where("photo_id = ? AND user_id = ?", photo.ID, user.ID).First(&existing).Error
	switch {
	case err == nil:
		paint.ID = existing.ID
		paint.CreatedAt = existing.CreatedAt
		if err := s.DB.Save(&paint).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "paint_save_failed"})
			return
		}
	case errors.Is(err, gorm.ErrRecordNotFound):
		paint.CreatedAt = time.Now().UTC()
		if err := s.DB.Create(&paint).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "paint_create_failed"})
			return
		}
	default:
		c.JSON(http.StatusInternalServerError, gin.H{"error": "paint_query_failed"})
		return
	}
	if err := s.handlePhotoInteractionSubscription(photo, user.ID, "paint", time.Now().UTC()); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "bookmark sync failed"})
		return
	}
	decorations, err := s.photoDecorationsForViewer(user.ID, []uint{photo.ID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "photo decorations query failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, gin.H{"ok": true, "photo": s.photoJSONForViewer(user.ID, photo, decorations)})
}

func (s *Server) handlePhotoPaintDelete(c *gin.Context) {
	user, _ := userFromContext(c)
	photo, err := s.loadVisiblePhotoForViewer(user.ID, c.Param("id"))
	if err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, gorm.ErrRecordNotFound):
			status = http.StatusNotFound
		case err.Error() == "invalid_photo_id":
			status = http.StatusBadRequest
		case err.Error() == "not_visible":
			status = http.StatusForbidden
		}
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}
	targetUserID, err := parsePhotoPaintDeleteTarget(c, user, photo)
	if err != nil {
		status := http.StatusForbidden
		if err.Error() == "invalid_user_id" {
			status = http.StatusBadRequest
		}
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}
	if err := s.DB.Where("photo_id = ? AND user_id = ?", photo.ID, targetUserID).Delete(&models.PhotoPaint{}).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "paint_delete_failed"})
		return
	}
	decorations, err := s.photoDecorationsForViewer(user.ID, []uint{photo.ID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "photo decorations query failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, gin.H{"ok": true, "photo": s.photoJSONForViewer(user.ID, photo, decorations)})
}

func (s *Server) handlePhotoReportCreate(c *gin.Context) {
	user, _ := userFromContext(c)
	photo, err := s.loadVisiblePhotoForViewer(user.ID, c.Param("id"))
	if err != nil {
		status := http.StatusInternalServerError
		switch {
		case errors.Is(err, gorm.ErrRecordNotFound):
			status = http.StatusNotFound
		case err.Error() == "invalid_photo_id":
			status = http.StatusBadRequest
		case err.Error() == "not_visible":
			status = http.StatusForbidden
		}
		c.JSON(status, gin.H{"error": err.Error()})
		return
	}

	if existing, ok, err := s.findExistingPhotoReport(user.ID, photo.ID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "report dedupe lookup failed"})
		return
	} else if ok {
		c.JSON(http.StatusOK, gin.H{
			"ok":           true,
			"report":       true,
			"reportId":     existing.ID,
			"reportType":   existing.Type,
			"reportStatus": existing.Status,
			"message":      "Danke fuer dein Feedback, wir pruefen das.",
		})
		return
	}

	body := fmt.Sprintf("Post von @%s am %s gemeldet.", photo.User.Username, photo.Day)
	if number := photoPublicNumberValue(photo); number != "" {
		body = fmt.Sprintf("Post #%s von @%s am %s gemeldet.", number, photo.User.Username, photo.Day)
	}
	if caption := strings.TrimSpace(photo.Caption); caption != "" {
		body = fmt.Sprintf("%s Caption: %s", body, caption)
	}

	report := models.UserReport{
		UserID:  user.ID,
		Type:    "post",
		PhotoID: &photo.ID,
		Body:    body,
		Source:  "photo_menu",
		Status:  "open",
	}
	if err := s.DB.Create(&report).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "report save failed"})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"ok":           true,
		"report":       true,
		"reportId":     report.ID,
		"reportType":   report.Type,
		"reportStatus": report.Status,
		"message":      "Danke fuer dein Feedback, wir pruefen das.",
	})
}

func (s *Server) loadVisiblePhotoForViewer(viewerID uint, rawPhotoID string) (models.Photo, error) {
	photoID, err := parseUintParam(rawPhotoID)
	if err != nil {
		return models.Photo{}, errors.New("invalid_photo_id")
	}
	var photo models.Photo
	if err := s.DB.Preload("User").First(&photo, photoID).Error; err != nil {
		return models.Photo{}, err
	}
	if !photoVisibleToViewer(viewerID, photo, time.Now().In(s.Location)) {
		return models.Photo{}, errors.New("not_visible")
	}
	return photo, nil
}

func (s *Server) calendarPayload(viewerID uint, scope string, targetUserID uint, now time.Time) (gin.H, error) {
	var photos []models.Photo
	query := s.DB.Preload("User").Model(&models.Photo{})
	orderClause := "photos.day desc, photos.created_at desc, photos.id desc"
	switch scope {
	case "bookmarks":
		query = query.Joins("JOIN photo_bookmarks pb ON pb.photo_id = photos.id AND pb.user_id = ? AND pb.active = ?", viewerID, true)
		orderClause = "pb.created_at desc, photos.created_at desc, photos.id desc"
	case "bookmarks_all":
		query = query.
			Joins("JOIN photo_bookmarks pb ON pb.photo_id = photos.id AND pb.active = ?", true).
			Group("photos.id").
			Order("COUNT(pb.id) desc").
			Order("photos.day desc").
			Order("photos.created_at desc").
			Order("photos.id desc")
		orderClause = ""
	case "user":
		query = query.Where("photos.user_id = ?", targetUserID)
	}
	if orderClause != "" {
		query = query.Order(orderClause)
	}
	if err := query.Find(&photos).Error; err != nil {
		return nil, err
	}

	if scope != "bookmarks_all" {
		sortPhotosForFeed(photos)
	}
	visiblePhotos := make([]models.Photo, 0, len(photos))
	daySeen := make(map[string]struct{}, len(photos))
	days := make([]string, 0, len(photos))
	for _, photo := range photos {
		if !photoVisibleToViewer(viewerID, photo, now) {
			continue
		}
		visiblePhotos = append(visiblePhotos, photo)
		if _, ok := daySeen[photo.Day]; ok {
			continue
		}
		daySeen[photo.Day] = struct{}{}
		days = append(days, photo.Day)
		if len(days) >= 365 {
			break
		}
	}
	if len(days) == 0 {
		users, err := s.calendarUsers(viewerID, now)
		if err != nil {
			return nil, err
		}
		return gin.H{
			"days":        []string{},
			"dayStats":    []gin.H{},
			"photosByDay": gin.H{},
			"users":       users,
			"items":       []gin.H{},
		}, nil
	}
	allowedDays := make(map[string]struct{}, len(days))
	for _, day := range days {
		allowedDays[day] = struct{}{}
	}
	filteredPhotos := make([]models.Photo, 0, len(visiblePhotos))
	for _, photo := range visiblePhotos {
		if _, ok := allowedDays[photo.Day]; ok {
			filteredPhotos = append(filteredPhotos, photo)
		}
	}

	photoIDs := make([]uint, 0, len(filteredPhotos))
	for _, photo := range filteredPhotos {
		photoIDs = append(photoIDs, photo.ID)
	}
	reactionCounts := make(map[uint]int64, len(photoIDs))
	commentCounts := make(map[uint]int64, len(photoIDs))
	reactionPreviewByPhoto := make(map[uint][]gin.H, len(photoIDs))
	commentPreviewByPhoto := make(map[uint][]gin.H, len(photoIDs))
	photoMojiPreviewByPhoto := make(map[uint][]gin.H, len(photoIDs))
	countsByPhoto := make(map[uint]gin.H, len(photoIDs))
	commentPreviewLimit := 0
	if len(photoIDs) > 0 {
		var previewErr error
		reactionPreviewByPhoto, commentPreviewByPhoto, photoMojiPreviewByPhoto, countsByPhoto, commentPreviewLimit, previewErr = s.feedInteractionPreview(photoIDs)
		if previewErr != nil {
			return nil, previewErr
		}
		for photoID, counts := range countsByPhoto {
			if reactionCount, ok := counts["reactions"].(int64); ok {
				reactionCounts[photoID] = reactionCount
			}
			if commentCount, ok := counts["comments"].(int64); ok {
				commentCounts[photoID] = commentCount
			}
		}
	}

	postCountByDay := make(map[string]int64, len(days))
	participantsByDay := make(map[string]map[uint]struct{}, len(days))
	bestByDay := make(map[string]models.Photo, len(days))
	bestReactionByDay := make(map[string]int64, len(days))
	bestCommentByDay := make(map[string]int64, len(days))
	for _, photo := range filteredPhotos {
		postCountByDay[photo.Day]++
		participants := participantsByDay[photo.Day]
		if participants == nil {
			participants = map[uint]struct{}{}
			participantsByDay[photo.Day] = participants
		}
		participants[photo.UserID] = struct{}{}

		reactionCount := reactionCounts[photo.ID]
		commentCount := commentCounts[photo.ID]
		interactionCount := reactionCount + commentCount
		best, ok := bestByDay[photo.Day]
		if ok {
			bestReaction := bestReactionByDay[photo.Day]
			bestComment := bestCommentByDay[photo.Day]
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
				if photoEffectiveTime(photo).Before(photoEffectiveTime(best)) {
					continue
				}
				if photoEffectiveTime(photo).Equal(photoEffectiveTime(best)) && photo.ID < best.ID {
					continue
				}
			}
		}
		bestByDay[photo.Day] = photo
		bestReactionByDay[photo.Day] = reactionCount
		bestCommentByDay[photo.Day] = commentCount
	}

	decorations, err := s.photoDecorationsForViewer(viewerID, photoIDs)
	if err != nil {
		return nil, err
	}

	outStats := make([]gin.H, 0, len(days))
	outPhotosByDay := make(map[string][]gin.H, len(days))
	outItems := make([]gin.H, 0, len(filteredPhotos))
	for _, photo := range filteredPhotos {
		// Calendar payloads can contain up to a year of posts. They expose any
		// already-ready variants for compatibility, but must never enqueue an
		// entire historic rendition backlog merely because the calendar opened.
		photoRow := s.photoJSONForViewerWithoutDerivativeQueue(viewerID, photo, decorations)
		userRow := s.userPublicJSON(viewerID, photo.User)
		row := gin.H{
			"photo": photoRow,
			"user":  userRow,
		}
		outPhotosByDay[photo.Day] = append(outPhotosByDay[photo.Day], row)
		item := gin.H{
			"isEarly":           false,
			"isLate":            false,
			"capsuleLocked":     false,
			"capsuleReleased":   false,
			"photo":             photoRow,
			"user":              userRow,
			"reactions":         reactionPreviewByPhoto[photo.ID],
			"comments":          commentPreviewByPhoto[photo.ID],
			"photoMojis":        photoMojiPreviewByPhoto[photo.ID],
			"interactionCounts": countsByPhoto[photo.ID],
			"interactionSnapshot": gin.H{
				"kind":                "preview",
				"commentPreviewLimit": commentPreviewLimit,
			},
		}
		if item["reactions"] == nil {
			item["reactions"] = []gin.H{}
		}
		if item["comments"] == nil {
			item["comments"] = []gin.H{}
		}
		if item["photoMojis"] == nil {
			item["photoMojis"] = []gin.H{}
		}
		if item["interactionCounts"] == nil {
			item["interactionCounts"] = gin.H{
				"reactions":  0,
				"comments":   0,
				"photoMojis": 0,
			}
		}
		outItems = append(outItems, item)
	}
	if scope == "bookmarks_all" {
		sort.SliceStable(outItems, func(i, j int) bool {
			leftBookmarks := feedItemBookmarkCount(outItems[i])
			rightBookmarks := feedItemBookmarkCount(outItems[j])
			if leftBookmarks != rightBookmarks {
				return leftBookmarks > rightBookmarks
			}
			leftInteractions := feedItemInteractionCount(outItems[i])
			rightInteractions := feedItemInteractionCount(outItems[j])
			if leftInteractions != rightInteractions {
				return leftInteractions > rightInteractions
			}
			leftAt := feedItemEffectiveTime(outItems[i])
			rightAt := feedItemEffectiveTime(outItems[j])
			if !leftAt.Equal(rightAt) {
				return leftAt.After(rightAt)
			}
			return feedItemPhotoID(outItems[i]) > feedItemPhotoID(outItems[j])
		})
	}
	for _, day := range days {
		item := gin.H{
			"day":              day,
			"count":            postCountByDay[day],
			"postCount":        postCountByDay[day],
			"participantCount": int64(len(participantsByDay[day])),
			"featuredPhoto":    nil,
		}
		if photo, ok := bestByDay[day]; ok {
			reactionCount := bestReactionByDay[day]
			commentCount := bestCommentByDay[day]
			featured := gin.H{
				"photoId":          photo.ID,
				"url":              fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, photo.FilePath),
				"thumbnailUrl":     s.photoThumbnailURL(photo.FilePath),
				"secondUrl":        "",
				"user":             s.userPublicJSON(viewerID, photo.User),
				"reactionCount":    reactionCount,
				"commentCount":     commentCount,
				"interactionCount": reactionCount + commentCount,
				"bookmarkedByMe":   decorations.bookmarkMap[photo.ID],
				"bookmarkCount":    decorations.bookmarkCounts[photo.ID],
				"publicNumber":     photoPublicNumberValue(photo),
			}
			if strings.TrimSpace(photo.SecondPath) != "" {
				featured["secondUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, photo.SecondPath)
				featured["secondThumbnailUrl"] = s.photoThumbnailURL(photo.SecondPath)
			}
			item["featuredPhoto"] = featured
		}
		outStats = append(outStats, item)
	}

	users, err := s.calendarUsers(viewerID, now)
	if err != nil {
		return nil, err
	}
	return gin.H{
		"days":        days,
		"dayStats":    outStats,
		"photosByDay": outPhotosByDay,
		"users":       users,
		"items":       outItems,
	}, nil
}

func (s *Server) timeCapsulePhotoJSONForViewer(viewerID uint, photo models.Photo, decorations *viewerPhotoDecorations, now time.Time) gin.H {
	row := s.photoJSONForViewer(viewerID, photo, decorations)
	locked := photo.CapsuleVisibleAt != nil && now.Before(*photo.CapsuleVisibleAt)
	if locked {
		row["url"] = ""
		row["capsulePreviewUrl"] = ""
		row["thumbnailUrl"] = ""
		delete(row, "secondUrl")
		delete(row, "secondThumbnailUrl")
		row["media"] = []gin.H{}
		row["mediaCount"] = 0
		row["caption"] = ""
		row["locationShared"] = false
		row["locationDisplay"] = ""
		row["locationMapsUrl"] = ""
		row["marks"] = []gin.H{}
		row["paints"] = []gin.H{}
		row["canMark"] = false
		row["canPaint"] = false
		row["markedByMe"] = false
		row["paintedByMe"] = false
	}
	return row
}

func (s *Server) calendarTimeCapsulesPayload(viewerID uint, now time.Time) (gin.H, error) {
	var photos []models.Photo
	if err := s.DB.Preload("User").
		Where("TRIM(capsule_mode) <> ''").
		Order("created_at desc, id desc").
		Find(&photos).Error; err != nil {
		return nil, err
	}
	if len(photos) == 0 {
		users, err := s.calendarUsers(viewerID, now)
		if err != nil {
			return nil, err
		}
		return gin.H{
			"days":          []string{},
			"dayStats":      []gin.H{},
			"photosByDay":   gin.H{},
			"users":         users,
			"items":         []gin.H{},
			"lockedCount":   0,
			"releasedCount": 0,
		}, nil
	}

	photoIDs := make([]uint, 0, len(photos))
	for _, photo := range photos {
		photoIDs = append(photoIDs, photo.ID)
	}
	decorations, err := s.photoDecorationsForViewer(viewerID, photoIDs)
	if err != nil {
		return nil, err
	}
	reactionByPhoto, commentByPhoto, photoMojiByPhoto, countsByPhoto, commentPreviewLimit, err := s.feedInteractionPreview(photoIDs)
	if err != nil {
		return nil, err
	}
	reactionCounts := make(map[uint]int64, len(photoIDs))
	commentCounts := make(map[uint]int64, len(photoIDs))
	for photoID, counts := range countsByPhoto {
		if reactionCount, ok := counts["reactions"].(int64); ok {
			reactionCounts[photoID] = reactionCount
		}
		if commentCount, ok := counts["comments"].(int64); ok {
			commentCounts[photoID] = commentCount
		}
	}

	daySeen := make(map[string]struct{}, len(photos))
	days := make([]string, 0, len(photos))
	postCountByDay := make(map[string]int64, len(photos))
	participantsByDay := make(map[string]map[uint]struct{}, len(photos))
	bestByDay := make(map[string]models.Photo, len(photos))
	bestReactionByDay := make(map[string]int64, len(photos))
	bestCommentByDay := make(map[string]int64, len(photos))
	items := make([]gin.H, 0, len(photos))
	photosByDay := make(map[string][]gin.H, len(photos))
	lockedCount := 0
	releasedCount := 0

	for _, photo := range photos {
		if !photoVisibleToViewer(viewerID, photo, now) {
			continue
		}
		locked := photo.CapsuleVisibleAt != nil && now.Before(*photo.CapsuleVisibleAt)
		released := strings.TrimSpace(photo.CapsuleMode) != "" && !locked
		if locked {
			lockedCount++
		} else {
			releasedCount++
		}
		if _, ok := daySeen[photo.Day]; !ok {
			daySeen[photo.Day] = struct{}{}
			days = append(days, photo.Day)
		}
		postCountByDay[photo.Day]++
		participants := participantsByDay[photo.Day]
		if participants == nil {
			participants = map[uint]struct{}{}
			participantsByDay[photo.Day] = participants
		}
		participants[photo.UserID] = struct{}{}

		reactionCount := reactionCounts[photo.ID]
		commentCount := commentCounts[photo.ID]
		interactionCount := reactionCount + commentCount
		best, ok := bestByDay[photo.Day]
		replaceBest := true
		if ok {
			bestInteraction := bestReactionByDay[photo.Day] + bestCommentByDay[photo.Day]
			if interactionCount < bestInteraction {
				replaceBest = false
			}
			if interactionCount == bestInteraction && photoEffectiveTime(photo).Before(photoEffectiveTime(best)) {
				replaceBest = false
			}
		}
		if replaceBest {
			bestByDay[photo.Day] = photo
			bestReactionByDay[photo.Day] = reactionCount
			bestCommentByDay[photo.Day] = commentCount
		}
		row := gin.H{
			"isEarly":           false,
			"isLate":            false,
			"capsuleLocked":     locked,
			"capsuleReleased":   released,
			"photo":             s.timeCapsulePhotoJSONForViewer(viewerID, photo, decorations, now),
			"user":              s.userPublicJSON(viewerID, photo.User),
			"reactions":         reactionByPhoto[photo.ID],
			"comments":          commentByPhoto[photo.ID],
			"photoMojis":        photoMojiByPhoto[photo.ID],
			"interactionCounts": countsByPhoto[photo.ID],
			"interactionSnapshot": gin.H{
				"kind":                "preview",
				"commentPreviewLimit": commentPreviewLimit,
			},
		}
		if row["reactions"] == nil {
			row["reactions"] = []gin.H{}
		}
		if row["comments"] == nil {
			row["comments"] = []gin.H{}
		}
		if row["photoMojis"] == nil {
			row["photoMojis"] = []gin.H{}
		}
		items = append(items, row)
		photosByDay[photo.Day] = append(photosByDay[photo.Day], gin.H{
			"photo": row["photo"],
			"user":  row["user"],
		})
	}

	sort.SliceStable(items, func(i, j int) bool {
		leftReleased, _ := items[i]["capsuleReleased"].(bool)
		rightReleased, _ := items[j]["capsuleReleased"].(bool)
		if leftReleased != rightReleased {
			return leftReleased
		}
		leftPhoto, _ := items[i]["photo"].(gin.H)
		rightPhoto, _ := items[j]["photo"].(gin.H)
		leftVisibleAt, _ := leftPhoto["capsuleVisibleAt"].(*time.Time)
		rightVisibleAt, _ := rightPhoto["capsuleVisibleAt"].(*time.Time)
		if leftReleased && rightReleased {
			leftAt := feedItemEffectiveTime(items[i])
			rightAt := feedItemEffectiveTime(items[j])
			if !leftAt.Equal(rightAt) {
				return leftAt.After(rightAt)
			}
		} else {
			if leftVisibleAt != nil && rightVisibleAt != nil && !leftVisibleAt.Equal(*rightVisibleAt) {
				return leftVisibleAt.Before(*rightVisibleAt)
			}
		}
		return feedItemPhotoID(items[i]) > feedItemPhotoID(items[j])
	})

	outStats := make([]gin.H, 0, len(days))
	for _, day := range days {
		item := gin.H{
			"day":              day,
			"count":            postCountByDay[day],
			"postCount":        postCountByDay[day],
			"participantCount": int64(len(participantsByDay[day])),
			"featuredPhoto":    nil,
		}
		if photo, ok := bestByDay[day]; ok {
			featuredPhoto := s.timeCapsulePhotoJSONForViewer(viewerID, photo, decorations, now)
			featuredURL, _ := featuredPhoto["url"].(string)
			featuredSecondURL, _ := featuredPhoto["secondUrl"].(string)
			featuredLocked := photo.CapsuleVisibleAt != nil && now.Before(*photo.CapsuleVisibleAt)
			item["featuredPhoto"] = gin.H{
				"photoId":          photo.ID,
				"url":              featuredURL,
				"secondUrl":        featuredSecondURL,
				"user":             s.userPublicJSON(viewerID, photo.User),
				"reactionCount":    bestReactionByDay[day],
				"commentCount":     bestCommentByDay[day],
				"interactionCount": bestReactionByDay[day] + bestCommentByDay[day],
				"bookmarkedByMe":   decorations.bookmarkMap[photo.ID],
				"bookmarkCount":    decorations.bookmarkCounts[photo.ID],
				"publicNumber":     photoPublicNumberValue(photo),
				"capsuleLocked":    featuredLocked,
				"capsuleVisibleAt": photo.CapsuleVisibleAt,
			}
		}
		outStats = append(outStats, item)
	}

	users, err := s.calendarUsers(viewerID, now)
	if err != nil {
		return nil, err
	}
	return gin.H{
		"days":          days,
		"dayStats":      outStats,
		"photosByDay":   photosByDay,
		"users":         users,
		"items":         items,
		"lockedCount":   lockedCount,
		"releasedCount": releasedCount,
	}, nil
}

func (s *Server) calendarUsers(viewerID uint, now time.Time) ([]gin.H, error) {
	type calendarUserRow struct {
		UserID        uint   `gorm:"column:user_id"`
		Username      string `gorm:"column:username"`
		FavoriteColor string `gorm:"column:favorite_color"`
	}
	var rows []calendarUserRow
	if err := s.DB.Table("photos").
		Select("DISTINCT photos.user_id as user_id, users.username as username, users.favorite_color as favorite_color").
		Joins("JOIN users ON users.id = photos.user_id").
		Where("photos.capsule_visible_at IS NULL OR photos.capsule_visible_at <= ? OR photos.user_id = ?", now, viewerID).
		Order("LOWER(users.username) asc").
		Scan(&rows).Error; err != nil {
		return nil, err
	}
	out := make([]gin.H, 0, len(rows))
	for _, row := range rows {
		out = append(out, gin.H{
			"id":            row.UserID,
			"username":      row.Username,
			"favoriteColor": defaultColor(row.FavoriteColor),
		})
	}
	return out, nil
}

func (s *Server) ensurePhotoSearchReady() error {
	s.photoSearchMu.Lock()
	defer s.photoSearchMu.Unlock()
	if s.photoSearchReady {
		return nil
	}
	var photoIDs []uint
	if err := s.DB.Model(&models.Photo{}).Order("id asc").Pluck("id", &photoIDs).Error; err != nil {
		return err
	}
	for _, photoID := range photoIDs {
		if err := s.refreshPhotoSearchDocument(photoID); err != nil {
			return err
		}
	}
	s.photoSearchReady = true
	return nil
}

func (s *Server) optionalPhotoSearchExec(query string, args ...any) {
	sqlDB, err := s.DB.DB()
	if err != nil {
		return
	}
	_, _ = sqlDB.Exec(query, args...)
}

func (s *Server) refreshPhotoSearchDocument(photoID uint) error {
	var photo models.Photo
	if err := s.DB.Select("id, day, user_id, caption").First(&photo, photoID).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return s.deletePhotoSearchDocument(photoID)
		}
		return err
	}
	var comments []models.PhotoComment
	if err := s.DB.
		Select("body").
		Where("photo_id = ?", photoID).
		Order("created_at asc, id asc").
		Find(&comments).Error; err != nil {
		return err
	}
	commentParts := make([]string, 0, len(comments))
	hashtagParts := extractHashtags(photo.Caption)
	for _, comment := range comments {
		body := strings.TrimSpace(comment.Body)
		if body == "" {
			continue
		}
		commentParts = append(commentParts, body)
		hashtagParts = append(hashtagParts, extractHashtags(body)...)
	}
	hashtagSet := make(map[string]struct{}, len(hashtagParts))
	hashtags := make([]string, 0, len(hashtagParts))
	for _, tag := range hashtagParts {
		if _, ok := hashtagSet[tag]; ok {
			continue
		}
		hashtagSet[tag] = struct{}{}
		hashtags = append(hashtags, tag)
	}
	commentText := strings.Join(commentParts, "\n")
	body := strings.TrimSpace(strings.Join([]string{
		strings.TrimSpace(photo.Caption),
		commentText,
		strings.Join(hashtags, " "),
	}, "\n"))
	terms := normalizePhotoSearchTokens(body)
	if err := s.DB.Exec("DELETE FROM photo_search_terms WHERE photo_id = ?", photoID).Error; err != nil {
		return err
	}
	if err := s.DB.Exec("DELETE FROM photo_search_docs WHERE photo_id = ?", photoID).Error; err != nil {
		return err
	}
	s.optionalPhotoSearchExec("DELETE FROM photo_search WHERE CAST(photo_id AS INTEGER) = ?", photoID)
	if err := s.DB.Exec(
		"INSERT INTO photo_search_docs (photo_id, day, user_id, caption, comments, hashtags, body) VALUES (?, ?, ?, ?, ?, ?, ?)",
		photo.ID,
		photo.Day,
		photo.UserID,
		strings.TrimSpace(photo.Caption),
		commentText,
		strings.Join(hashtags, " "),
		body,
	).Error; err != nil {
		return err
	}
	for _, term := range terms {
		if err := s.DB.Exec("INSERT INTO photo_search_terms (term, photo_id) VALUES (?, ?)", term, photo.ID).Error; err != nil {
			return err
		}
	}
	s.optionalPhotoSearchExec(
		"INSERT INTO photo_search (photo_id, day, user_id, caption, comments, hashtags, body) VALUES (?, ?, ?, ?, ?, ?, ?)",
		photo.ID,
		photo.Day,
		photo.UserID,
		strings.TrimSpace(photo.Caption),
		commentText,
		strings.Join(hashtags, " "),
		body,
	)
	return nil
}

func (s *Server) deletePhotoSearchDocument(photoID uint) error {
	if err := s.DB.Exec("DELETE FROM photo_search_terms WHERE photo_id = ?", photoID).Error; err != nil {
		return err
	}
	if err := s.DB.Exec("DELETE FROM photo_search_docs WHERE photo_id = ?", photoID).Error; err != nil {
		return err
	}
	s.optionalPhotoSearchExec("DELETE FROM photo_search WHERE CAST(photo_id AS INTEGER) = ?", photoID)
	return nil
}

func buildPhotoSearchHit(photo models.Photo, comments []models.PhotoComment, tokens []string, bookmarked bool, bookmarkCount int64) photoSearchHit {
	matchedComments := make([]string, 0, 2)
	for _, comment := range comments {
		if !containsAnyPhotoSearchToken(comment.Body, tokens) {
			continue
		}
		matchedComments = append(matchedComments, clipSearchExcerpt(comment.Body, tokens))
		if len(matchedComments) >= 2 {
			break
		}
	}
	allHashtags := extractHashtags(photo.Caption)
	for _, comment := range comments {
		allHashtags = append(allHashtags, extractHashtags(comment.Body)...)
	}
	queryHashtags := make(map[string]struct{}, len(tokens))
	for _, token := range tokens {
		if strings.HasPrefix(token, "#") {
			queryHashtags[token] = struct{}{}
		}
	}
	matchedHashtags := make([]string, 0, len(allHashtags))
	seenTags := make(map[string]struct{}, len(allHashtags))
	for _, tag := range allHashtags {
		if _, seen := seenTags[tag]; seen {
			continue
		}
		seenTags[tag] = struct{}{}
		if len(queryHashtags) == 0 {
			continue
		}
		if _, ok := queryHashtags[strings.ToLower(tag)]; ok {
			matchedHashtags = append(matchedHashtags, strings.ToLower(tag))
		}
	}
	excerpt := clipSearchExcerpt(photo.Caption, tokens)
	if excerpt == "" && len(matchedComments) > 0 {
		excerpt = matchedComments[0]
	}
	return photoSearchHit{
		Photo:           photo,
		BookmarkedByMe:  bookmarked,
		BookmarkCount:   bookmarkCount,
		Excerpt:         excerpt,
		MatchedCaption:  containsAnyPhotoSearchToken(photo.Caption, tokens),
		MatchedComments: matchedComments,
		MatchedHashtags: matchedHashtags,
	}
}

func (s *Server) searchPhotoHits(viewerID uint, rawQuery string, now time.Time, includeHidden bool, limit int) (string, []photoSearchHit, error) {
	if limit <= 0 {
		limit = 20
	}
	if err := s.ensurePhotoSearchReady(); err != nil {
		return "", nil, err
	}
	tokens := normalizePhotoSearchTokens(rawQuery)
	normalized := strings.Join(tokens, " ")
	if len(tokens) == 0 {
		return normalized, []photoSearchHit{}, nil
	}
	type searchRow struct {
		PhotoID uint `gorm:"column:photo_id"`
	}
	var matches []searchRow
	if err := s.DB.Raw(
		`SELECT photo_id
		 FROM photo_search_terms
		 WHERE term IN ?
		 GROUP BY photo_id
		 HAVING COUNT(DISTINCT term) = ?
		 ORDER BY MAX(photo_id) DESC
		 LIMIT ?`,
		tokens,
		len(tokens),
		limit*4,
	).Scan(&matches).Error; err != nil {
		return normalized, nil, err
	}
	if len(matches) == 0 {
		return normalized, []photoSearchHit{}, nil
	}
	photoIDs := make([]uint, 0, len(matches))
	for _, row := range matches {
		photoIDs = append(photoIDs, row.PhotoID)
	}
	var photos []models.Photo
	if err := s.DB.Preload("User").Where("id IN ?", photoIDs).Find(&photos).Error; err != nil {
		return normalized, nil, err
	}
	photoByID := make(map[uint]models.Photo, len(photos))
	for _, photo := range photos {
		photoByID[photo.ID] = photo
	}
	var allComments []models.PhotoComment
	if err := s.DB.Where("photo_id IN ?", photoIDs).Order("created_at asc, id asc").Find(&allComments).Error; err != nil {
		return normalized, nil, err
	}
	commentsByPhotoID := make(map[uint][]models.PhotoComment, len(photoIDs))
	for _, comment := range allComments {
		commentsByPhotoID[comment.PhotoID] = append(commentsByPhotoID[comment.PhotoID], comment)
	}
	decorations, err := s.photoDecorationsForViewer(viewerID, photoIDs)
	if err != nil {
		return normalized, nil, err
	}
	hits := make([]photoSearchHit, 0, minInt(limit, len(matches)))
	seen := make(map[uint]struct{}, len(matches))
	for _, row := range matches {
		if _, ok := seen[row.PhotoID]; ok {
			continue
		}
		seen[row.PhotoID] = struct{}{}
		photo, ok := photoByID[row.PhotoID]
		if !ok {
			continue
		}
		if !includeHidden && !photoVisibleToViewer(viewerID, photo, now) {
			continue
		}
		hits = append(hits, buildPhotoSearchHit(photo, commentsByPhotoID[photo.ID], tokens, decorations.bookmarkMap[photo.ID], decorations.bookmarkCounts[photo.ID]))
		if len(hits) >= limit {
			break
		}
	}
	return normalized, hits, nil
}

func (s *Server) calendarSearchPayload(viewerID uint, rawQuery string, now time.Time) (gin.H, error) {
	normalized, hits, err := s.searchPhotoHits(viewerID, rawQuery, now, false, 120)
	if err != nil {
		return nil, err
	}
	if len(hits) == 0 {
		return gin.H{
			"query":              strings.TrimSpace(rawQuery),
			"normalizedQuery":    normalized,
			"days":               []string{},
			"dayStats":           []gin.H{},
			"matchedPhotosByDay": gin.H{},
		}, nil
	}
	type interactionRow struct {
		PhotoID uint
		Count   int64
	}
	photoIDs := make([]uint, 0, len(hits))
	days := make([]string, 0, len(hits))
	daySeen := make(map[string]struct{}, len(hits))
	photosByDay := make(map[string][]photoSearchHit, len(hits))
	for _, hit := range hits {
		photoIDs = append(photoIDs, hit.Photo.ID)
		photosByDay[hit.Photo.Day] = append(photosByDay[hit.Photo.Day], hit)
		if _, ok := daySeen[hit.Photo.Day]; ok {
			continue
		}
		daySeen[hit.Photo.Day] = struct{}{}
		days = append(days, hit.Photo.Day)
	}
	reactionCounts := make(map[uint]int64, len(photoIDs))
	commentCounts := make(map[uint]int64, len(photoIDs))
	var reactionRows []interactionRow
	if err := s.DB.Model(&models.PhotoReaction{}).
		Select("photo_id, COUNT(*) as count").
		Where("photo_id IN ?", photoIDs).
		Group("photo_id").
		Scan(&reactionRows).Error; err != nil {
		return nil, err
	}
	for _, row := range reactionRows {
		reactionCounts[row.PhotoID] = row.Count
	}
	var fotomojiRows []interactionRow
	if err := s.DB.Model(&models.PhotoFotomoji{}).
		Select("photo_id, COUNT(*) as count").
		Where("photo_id IN ?", photoIDs).
		Group("photo_id").
		Scan(&fotomojiRows).Error; err != nil {
		return nil, err
	}
	for _, row := range fotomojiRows {
		reactionCounts[row.PhotoID] += row.Count
	}
	var commentRows []interactionRow
	if err := s.DB.Model(&models.PhotoComment{}).
		Select("photo_id, COUNT(*) as count").
		Where("photo_id IN ?", photoIDs).
		Group("photo_id").
		Scan(&commentRows).Error; err != nil {
		return nil, err
	}
	for _, row := range commentRows {
		commentCounts[row.PhotoID] = row.Count
	}
	decorations, err := s.photoDecorationsForViewer(viewerID, photoIDs)
	if err != nil {
		return nil, err
	}
	matchedPhotosByDay := make(gin.H, len(days))
	dayStats := make([]gin.H, 0, len(days))
	for _, day := range days {
		dayHits := photosByDay[day]
		participants := make(map[uint]struct{}, len(dayHits))
		var featured *photoSearchHit
		var featuredReactions int64
		var featuredComments int64
		matchRows := make([]gin.H, 0, len(dayHits))
		for _, hit := range dayHits {
			participants[hit.Photo.UserID] = struct{}{}
			photoRow := s.photoJSONForViewer(viewerID, hit.Photo, decorations)
			matchRows = append(matchRows, gin.H{
				"photo":           photoRow,
				"user":            s.userPublicJSON(viewerID, hit.Photo.User),
				"excerpt":         hit.Excerpt,
				"matchedCaption":  hit.MatchedCaption,
				"matchedComments": hit.MatchedComments,
				"matchedHashtags": hit.MatchedHashtags,
			})
			reactions := reactionCounts[hit.Photo.ID]
			comments := commentCounts[hit.Photo.ID]
			if featured == nil {
				copied := hit
				featured = &copied
				featuredReactions = reactions
				featuredComments = comments
				continue
			}
			interactionCount := reactions + comments
			bestInteraction := featuredReactions + featuredComments
			switch {
			case interactionCount > bestInteraction:
			case interactionCount == bestInteraction && reactions > featuredReactions:
			case interactionCount == bestInteraction && reactions == featuredReactions && comments > featuredComments:
			case interactionCount == bestInteraction && reactions == featuredReactions && comments == featuredComments && photoEffectiveTime(hit.Photo).After(photoEffectiveTime(featured.Photo)):
			case interactionCount == bestInteraction && reactions == featuredReactions && comments == featuredComments && photoEffectiveTime(hit.Photo).Equal(photoEffectiveTime(featured.Photo)) && hit.Photo.ID > featured.Photo.ID:
			default:
				continue
			}
			copied := hit
			featured = &copied
			featuredReactions = reactions
			featuredComments = comments
		}
		matchedPhotosByDay[day] = matchRows
		stat := gin.H{
			"day":              day,
			"count":            int64(len(dayHits)),
			"postCount":        int64(len(dayHits)),
			"participantCount": int64(len(participants)),
			"featuredPhoto":    nil,
		}
		if featured != nil {
			featuredRow := gin.H{
				"photoId":          featured.Photo.ID,
				"url":              fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, featured.Photo.FilePath),
				"thumbnailUrl":     s.photoThumbnailURL(featured.Photo.FilePath),
				"secondUrl":        "",
				"user":             s.userPublicJSON(viewerID, featured.Photo.User),
				"reactionCount":    featuredReactions,
				"commentCount":     featuredComments,
				"interactionCount": featuredReactions + featuredComments,
				"bookmarkedByMe":   featured.BookmarkedByMe,
				"publicNumber":     photoPublicNumberValue(featured.Photo),
			}
			if strings.TrimSpace(featured.Photo.SecondPath) != "" {
				featuredRow["secondUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, featured.Photo.SecondPath)
				featuredRow["secondThumbnailUrl"] = s.photoThumbnailURL(featured.Photo.SecondPath)
			}
			stat["featuredPhoto"] = featuredRow
		}
		dayStats = append(dayStats, stat)
	}
	return gin.H{
		"query":              strings.TrimSpace(rawQuery),
		"normalizedQuery":    normalized,
		"days":               days,
		"dayStats":           dayStats,
		"matchedPhotosByDay": matchedPhotosByDay,
	}, nil
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
	if defaultIfBlank(strings.TrimSpace(msg.MessageType), "text") == "poll" {
		_ = s.DB.Where("chat_message_id = ?", msg.ID).Delete(&models.ChatPollVote{}).Error
		_ = s.DB.Where("chat_message_id = ?", msg.ID).Delete(&models.ChatPollOption{}).Error
	}

	c.JSON(http.StatusOK, gin.H{"ok": true, "deletedId": msg.ID})
}

func (s *Server) handleCommunityStats(c *gin.Context) {
	now := time.Now().In(s.Location)
	payload, err := s.communityStatsPayload(now)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, payload)
}

func (s *Server) communityStatsPayload(now time.Time) (gin.H, error) {
	todayStart := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, s.Location)
	todayDay := now.Format("2006-01-02")
	sinceDay := now.AddDate(0, 0, -6).Format("2006-01-02")
	sinceTime := now.AddDate(0, 0, -7)

	var registeredUsers int64
	if err := s.DB.Model(&models.User{}).Count(&registeredUsers).Error; err != nil {
		return nil, err
	}

	var activeUsersToday int64
	if err := s.DB.Model(&models.Photo{}).
		Select("COUNT(DISTINCT user_id)").
		Where("day = ?", todayDay).
		Scan(&activeUsersToday).Error; err != nil {
		return nil, err
	}

	var postsToday int64
	if err := s.DB.Model(&models.Photo{}).
		Where("day = ?", todayDay).
		Count(&postsToday).Error; err != nil {
		return nil, err
	}

	var chatMessagesToday int64
	if err := s.DB.Model(&models.ChatMessage{}).
		Where("created_at >= ?", todayStart).
		Count(&chatMessagesToday).Error; err != nil {
		return nil, err
	}

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
	if err := s.DB.Model(&models.PhotoReaction{}).
		Select("emoji, COUNT(*) as count").
		Where("created_at >= ?", sinceTime).
		Group("emoji").
		Order("count desc").
		Limit(5).
		Scan(&reactionRows).Error; err != nil {
		return nil, err
	}

	var prompts []models.DailyPrompt
	if err := s.DB.
		Where("day >= ? AND day <= ?", sinceDay, todayDay).
		Find(&prompts).Error; err != nil {
		return nil, err
	}
	promptByDay := make(map[string]models.DailyPrompt, len(prompts))
	for _, p := range prompts {
		promptByDay[p.Day] = p
	}

	var photos []models.Photo
	if err := s.DB.
		Where("day >= ? AND day <= ?", sinceDay, todayDay).
		Find(&photos).Error; err != nil {
		return nil, err
	}
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
	return gin.H{
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
	}, nil
}

func (s *Server) handleChatCreate(c *gin.Context) {
	user, _ := userFromContext(c)
	var req struct {
		Body            string `json:"body" binding:"required,min=1"`
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
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	settings = normalizeSettings(settings)
	if !settings.ChatMessageUnlimited && len([]rune(body)) > settings.ChatMessageMaxLength {
		c.JSON(http.StatusBadRequest, gin.H{
			"error":          "message too long",
			"maxLength":      settings.ChatMessageMaxLength,
			"trimmedLength":  len([]rune(body)),
			"unlimited":      false,
			"messageTooLong": true,
		})
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
		"type":      "text",
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

func normalizePollOptionText(value string) string {
	return strings.Join(strings.Fields(strings.TrimSpace(value)), " ")
}

func normalizePollOptions(raw []string) []string {
	normalized := make([]string, 0, len(raw))
	seen := map[string]struct{}{}
	for _, item := range raw {
		clean := normalizePollOptionText(item)
		if clean == "" {
			continue
		}
		key := strings.ToLower(clean)
		if _, exists := seen[key]; exists {
			continue
		}
		seen[key] = struct{}{}
		normalized = append(normalized, clean)
	}
	return normalized
}

func (s *Server) handleChatPollCreate(c *gin.Context) {
	user, _ := userFromContext(c)
	var req struct {
		Question         string   `json:"question" binding:"required,min=3,max=280"`
		Options          []string `json:"options" binding:"required"`
		AllowMultiSelect bool     `json:"allowMultiSelect"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	question := strings.TrimSpace(req.Question)
	if question == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "question empty"})
		return
	}
	options := normalizePollOptions(req.Options)
	if len(options) < 2 || len(options) > 8 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "poll options must be between 2 and 8"})
		return
	}
	for _, option := range options {
		if len(option) > 120 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "poll option too long"})
			return
		}
	}
	var msg models.ChatMessage
	err := s.DB.Transaction(func(tx *gorm.DB) error {
		msg = models.ChatMessage{
			UserID:            user.ID,
			Body:              question,
			Source:            "user",
			MessageType:       "poll",
			PollQuestion:      question,
			PollAllowMultiple: req.AllowMultiSelect,
		}
		if err := tx.Create(&msg).Error; err != nil {
			return err
		}
		pollOptions := make([]models.ChatPollOption, 0, len(options))
		for idx, option := range options {
			pollOptions = append(pollOptions, models.ChatPollOption{
				ChatMessageID: msg.ID,
				OptionText:    option,
				SortOrder:     idx,
			})
		}
		return tx.Create(&pollOptions).Error
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	tokens := s.pollNotificationTokens(user.ID)
	if len(tokens) > 0 {
		pushText := fmt.Sprintf("%s hat eine Umfrage gestartet", user.Username)
		sendResult, sendErr := s.Notifier.Send(tokens, notify.Message{
			Title:  "Neue Umfrage",
			Body:   pushText,
			Type:   "chat_poll",
			Action: "open_chat",
		})
		s.recordPushResult(sendResult, sendErr)
		s.removeInvalidTokens(sendResult.InvalidTokens)
	}
	pollPayloadByID, payloadErr := s.chatPollPayloadByMessageID(user, []uint{msg.ID})
	if payloadErr != nil {
		c.JSON(http.StatusCreated, gin.H{
			"id":        msg.ID,
			"type":      "poll",
			"body":      msg.Body,
			"source":    msg.Source,
			"createdAt": msg.CreatedAt,
			"poll": gin.H{
				"question":         question,
				"allowMultiSelect": req.AllowMultiSelect,
				"isClosed":         false,
				"closedAt":         nil,
				"canClose":         true,
			},
			"user": s.userPublicJSON(user.ID, user),
		})
		return
	}
	poll := pollPayloadByID[msg.ID]
	poll["question"] = question
	poll["allowMultiSelect"] = req.AllowMultiSelect
	poll["isClosed"] = false
	poll["closedAt"] = nil
	poll["canClose"] = true
	c.JSON(http.StatusCreated, gin.H{
		"id":        msg.ID,
		"type":      "poll",
		"body":      msg.Body,
		"source":    msg.Source,
		"createdAt": msg.CreatedAt,
		"poll":      poll,
		"user":      s.userPublicJSON(user.ID, user),
	})
}

func (s *Server) handleChatPollVote(c *gin.Context) {
	user, _ := userFromContext(c)
	chatID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid poll id"})
		return
	}
	var req struct {
		OptionIDs []uint `json:"optionIds" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	var msg models.ChatMessage
	if err := s.DB.Where("id = ? AND message_type = ?", chatID, "poll").First(&msg).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "poll not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if msg.PollClosedAt != nil {
		c.JSON(http.StatusConflict, gin.H{"error": "poll closed"})
		return
	}
	seen := map[uint]struct{}{}
	optionIDs := make([]uint, 0, len(req.OptionIDs))
	for _, id := range req.OptionIDs {
		if id == 0 {
			continue
		}
		if _, exists := seen[id]; exists {
			continue
		}
		seen[id] = struct{}{}
		optionIDs = append(optionIDs, id)
	}
	if len(optionIDs) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "optionIds required"})
		return
	}
	if !msg.PollAllowMultiple && len(optionIDs) != 1 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "single choice poll requires exactly one option"})
		return
	}
	var options []models.ChatPollOption
	if err := s.DB.Where("chat_message_id = ?", msg.ID).Order("sort_order asc, id asc").Find(&options).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	allowed := map[uint]struct{}{}
	for _, option := range options {
		allowed[option.ID] = struct{}{}
	}
	for _, optionID := range optionIDs {
		if _, ok := allowed[optionID]; !ok {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid option id"})
			return
		}
	}
	if err := s.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("chat_message_id = ? AND user_id = ?", msg.ID, user.ID).Delete(&models.ChatPollVote{}).Error; err != nil {
			return err
		}
		votes := make([]models.ChatPollVote, 0, len(optionIDs))
		for _, optionID := range optionIDs {
			votes = append(votes, models.ChatPollVote{
				ChatMessageID: msg.ID,
				OptionID:      optionID,
				UserID:        user.ID,
			})
		}
		return tx.Create(&votes).Error
	}); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "vote failed"})
		return
	}
	payloadByID, payloadErr := s.chatPollPayloadByMessageID(user, []uint{msg.ID})
	if payloadErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "poll payload failed"})
		return
	}
	poll := payloadByID[msg.ID]
	poll["question"] = strings.TrimSpace(msg.PollQuestion)
	poll["allowMultiSelect"] = msg.PollAllowMultiple
	poll["isClosed"] = msg.PollClosedAt != nil
	poll["closedAt"] = msg.PollClosedAt
	poll["canClose"] = user.IsAdmin || msg.UserID == user.ID
	c.JSON(http.StatusOK, gin.H{
		"ok":   true,
		"poll": poll,
	})
}

func (s *Server) handleChatPollClose(c *gin.Context) {
	user, _ := userFromContext(c)
	chatID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid poll id"})
		return
	}
	var msg models.ChatMessage
	if err := s.DB.Where("id = ? AND message_type = ?", chatID, "poll").First(&msg).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "poll not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if msg.UserID != user.ID && !user.IsAdmin {
		c.JSON(http.StatusForbidden, gin.H{"error": "not allowed"})
		return
	}
	now := time.Now().In(s.Location)
	if msg.PollClosedAt == nil {
		if err := s.DB.Model(&models.ChatMessage{}).Where("id = ?", msg.ID).Update("poll_closed_at", now).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "close failed"})
			return
		}
		msg.PollClosedAt = &now
	}
	c.JSON(http.StatusOK, gin.H{"ok": true, "closedAt": msg.PollClosedAt})
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

func isValidUserReportType(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "bug", "idea", "post":
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

func (s *Server) parseAdminDateRange(fromRaw string, toRaw string) (string, string, *time.Time, *time.Time, error) {
	fromDay := strings.TrimSpace(fromRaw)
	toDay := strings.TrimSpace(toRaw)
	if (fromDay == "") != (toDay == "") {
		return "", "", nil, nil, errors.New("from/to must be provided together")
	}
	if fromDay == "" {
		return "", "", nil, nil, nil
	}
	fromParsed, err := time.ParseInLocation("2006-01-02", fromDay, s.Location)
	if err != nil {
		return "", "", nil, nil, errors.New("invalid from date")
	}
	toParsed, err := time.ParseInLocation("2006-01-02", toDay, s.Location)
	if err != nil {
		return "", "", nil, nil, errors.New("invalid to date")
	}
	if fromParsed.After(toParsed) {
		return "", "", nil, nil, errors.New("from must be before or equal to to")
	}
	fromStart := fromParsed
	toExclusive := toParsed.AddDate(0, 0, 1)
	return fromDay, toDay, &fromStart, &toExclusive, nil
}

func adminSinceCutoff(now time.Time, sinceHours int) time.Time {
	return now.UTC().Add(-time.Duration(sinceHours) * time.Hour)
}

func (s *Server) userReportJSON(row models.UserReport) gin.H {
	payload := gin.H{
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
	if row.PhotoID != nil && row.Photo.ID != 0 {
		payload["photoId"] = row.Photo.ID
		payload["photo"] = s.photoJSON(row.Photo)
		payload["photoUser"] = gin.H{
			"id":            row.Photo.User.ID,
			"username":      row.Photo.User.Username,
			"favoriteColor": defaultColor(row.Photo.User.FavoriteColor),
		}
	}
	return payload
}

func (s *Server) findExistingPhotoReport(userID uint, photoID uint) (models.UserReport, bool, error) {
	var report models.UserReport
	if err := s.DB.
		Preload("User").
		Preload("Photo").
		Preload("Photo.User").
		Where("user_id = ? AND type = ? AND photo_id = ?", userID, "post", photoID).
		Order("created_at desc, id desc").
		First(&report).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return models.UserReport{}, false, nil
		}
		return models.UserReport{}, false, err
	}
	return report, true, nil
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
		prompt, _, triggerErr = s.Prompt.TriggerNowWithSourceAndMeta("chat_command", &user, scheduler.TriggerAttemptMeta{
			RequestID:   requestIDFromContext(c),
			AttemptType: "chat",
		})
		if triggerErr != nil {
			if errors.Is(triggerErr, scheduler.ErrAlreadyTriggeredToday) {
				c.JSON(http.StatusConflict, gin.H{"error": "already_triggered_today"})
				return true, triggerErr
			}
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
			tokens := s.specialMomentNotificationTokens(user.ID)
			created, _, reserveErr := s.Prompt.ReserveDispatch(prompt.Day, s.Prompt.DispatchKindSpecialMomentPush(), "chat_command", requestIDFromContext(c))
			if reserveErr != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "dispatch reserve failed"})
				return true, reserveErr
			}
			if created {
				sendResult, sendErr = s.Notifier.Send(tokens, notify.Message{
					Title:  "Sondermoment",
					Body:   pushText,
					Type:   "special_request",
					Action: "open_camera",
				})
				s.recordPushResult(sendResult, sendErr)
				invalidRemoved = s.removeInvalidTokens(sendResult.InvalidTokens)
				dispatchStatus := "sent"
				dispatchErr := ""
				if sendErr != nil {
					dispatchStatus = "failed"
					dispatchErr = sendErr.Error()
				}
				s.Prompt.MarkDispatchResult(prompt.Day, s.Prompt.DispatchKindSpecialMomentPush(), dispatchStatus, int64(sendResult.Sent), int64(sendResult.Failed), dispatchErr)
			}
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
			sendResult, sendErr = s.Notifier.Send(tokens, notify.Message{
				Title:  "Daily Nachricht",
				Body:   pushText,
				Type:   "broadcast",
				Action: "open_app",
			})
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
		resp["momentKind"] = "special"
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

	capturedAt, err := s.parseCapturedAtValue(c.PostForm("captured_at"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid captured_at"})
		return
	}
	acceptedViaOfflineGrace := false
	uploadClientID := normalizeUploadClientID(c.PostForm("upload_client_id"))
	if uploadClientID != "" {
		if existing, ok, err := s.findPhotoByUploadClientID(user.ID, uploadClientID); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		} else if ok {
			c.JSON(http.StatusOK, gin.H{"photo": s.photoJSON(existing), "deduplicated": true, "acceptedViaOfflineGrace": acceptedViaOfflineGrace})
			return
		}
	}

	now := time.Now().In(s.Location)
	day := now.Format("2006-01-02")
	todayWindowActive := s.isDailyWindowActive(day, now)
	momentKind := ""
	if kind == "prompt" {
		resolvedDay, allowed, acceptedOffline, blockedCode := s.resolvePromptUploadDecision(day, now, capturedAt)
		day = resolvedDay
		acceptedViaOfflineGrace = acceptedOffline
		if !allowed {
			message := "prompt inactive"
			if blockedCode == "upload_window_closed" {
				message = "upload window closed"
			}
			c.JSON(http.StatusForbidden, gin.H{"error": message, "errorCode": blockedCode})
			return
		}
		momentKind = s.promptMomentKindForDay(day)
	}

	if _, err := s.cleanupInvalidPromptOnlyPhotosForDay(day); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	hasPromptPosted, err := s.userHasPostedForMomentDay(user.ID, day, momentKind, models.DailyPrompt{})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	if kind == "extra" && todayWindowActive {
		if s.extraUploadOfflineGraceAllowed(day, now, capturedAt) {
			acceptedViaOfflineGrace = true
		} else {
			c.JSON(http.StatusForbidden, gin.H{
				"error":        "extra unavailable during daily moment window",
				"errorCode":    "extra_window_blocked",
				"actionNeeded": true,
			})
			return
		}
	}

	capsuleMode, capsuleVisibleAt, capsulePrivate, capsuleGroupRemind, capsuleErr := parseCapsuleForm(c, kind, todayWindowActive, now)
	if capsuleErr != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": capsuleErr.Error()})
		return
	}
	locationShared, latitude, longitude, locationErr := parseLocationForm(c)
	if locationErr != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": locationErr.Error()})
		return
	}
	if !user.LocationFeatureEnabled {
		locationShared = false
		latitude = nil
		longitude = nil
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
		MomentKind:               momentKind,
		UploadClientID:           uploadClientID,
		FilePath:                 backPath,
		SecondPath:               frontPath,
		CapsulePreviewPath:       capsulePreviewPath,
		CapsuleSecondPreviewPath: capsuleSecondPreviewPath,
		CapturedAt:               capturedAt,
		Caption:                  c.PostForm("caption"),
		CapsuleMode:              capsuleMode,
		CapsuleVisibleAt:         capsuleVisibleAt,
		CapsulePrivate:           capsulePrivate,
		CapsuleGroupRemind:       capsuleGroupRemind,
		LocationShared:           locationShared,
		LocationLatitude:         latitude,
		LocationLongitude:        longitude,
	}
	photo.PrimaryDigest, err = s.fileDigest(backPath)
	if err != nil {
		s.removePhotoFiles(photo)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "digest failed"})
		return
	}
	photo.SecondaryDigest, err = s.fileDigest(frontPath)
	if err != nil {
		s.removePhotoFiles(photo)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "digest failed"})
		return
	}
	if existing, ok, err := s.findRecentDuplicatePhoto(user.ID, day, kind == "prompt", photo.PrimaryDigest, photo.SecondaryDigest, now); err != nil {
		s.removePhotoFiles(photo)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	} else if ok {
		s.removePhotoFiles(photo)
		c.JSON(http.StatusOK, gin.H{"photo": s.photoJSON(existing), "deduplicated": true, "acceptedViaOfflineGrace": acceptedViaOfflineGrace})
		return
	}
	if uploadClientID != "" {
		if existing, ok, err := s.findPhotoByUploadClientID(user.ID, uploadClientID); err != nil {
			s.removePhotoFiles(photo)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		} else if ok {
			s.removePhotoFiles(photo)
			c.JSON(http.StatusOK, gin.H{"photo": s.photoJSON(existing), "deduplicated": true, "acceptedViaOfflineGrace": acceptedViaOfflineGrace})
			return
		}
	}
	if kind == "prompt" && hasPromptPosted {
		s.removePhotoFiles(photo)
		c.JSON(http.StatusConflict, gin.H{"error": "Du hast heute bereits gepostet", "errorCode": "already_posted"})
		return
	}
	if err := s.DB.Create(&photo).Error; err != nil {
		s.removePhotoFiles(photo)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "db write failed"})
		return
	}
	if err := s.assignAndPersistPublicPhotoNumber(&photo); err != nil {
		s.removePhotoFiles(photo)
		_ = s.DB.Delete(&photo).Error
		c.JSON(http.StatusInternalServerError, gin.H{"error": "public number failed"})
		return
	}
	if err := s.refreshPhotoSearchDocument(photo.ID); err != nil {
		s.removePhotoFiles(photo)
		_ = s.DB.Delete(&photo).Error
		c.JSON(http.StatusInternalServerError, gin.H{"error": "search index failed"})
		return
	}

	s.invalidateFeedDayCache(photo.Day)
	s.enqueueMediaDerivatives(filepath.ToSlash(filepath.Clean(backPath)), 8_000, false)
	s.enqueueMediaDerivatives(filepath.ToSlash(filepath.Clean(frontPath)), 8_000, false)
	s.notifyPostCreated(user, photo)
	c.JSON(http.StatusCreated, gin.H{"photo": s.photoJSON(photo), "acceptedViaOfflineGrace": acceptedViaOfflineGrace})
}

func (s *Server) handlePhotoAttachmentCreate(c *gin.Context) {
	user, _ := userFromContext(c)
	photoID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid photo id"})
		return
	}
	fileHeader, err := c.FormFile("photo")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "missing photo"})
		return
	}
	capturedAt, err := s.parseCapturedAtValue(c.PostForm("captured_at"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid captured_at"})
		return
	}

	now := time.Now().In(s.Location)
	var photo models.Photo
	if err := s.DB.Where("id = ? AND user_id = ?", photoID, user.ID).First(&photo).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "photo not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if !photoVisibleToViewer(user.ID, photo, now) {
		c.JSON(http.StatusForbidden, gin.H{"error": "append not allowed for hidden post"})
		return
	}
	uploadClientID := normalizeUploadClientID(c.PostForm("upload_client_id"))
	if uploadClientID != "" {
		var existing models.PhotoAttachment
		if err := s.DB.Where("photo_id = ? AND upload_client_id = ?", photo.ID, uploadClientID).First(&existing).Error; err == nil {
			decorations, decorationErr := s.photoDecorationsForViewer(user.ID, []uint{photo.ID})
			if decorationErr != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "photo decorations query failed"})
				return
			}
			attachmentByPhoto := s.photoAttachmentsByPhotoIDs([]uint{photo.ID})
			c.JSON(http.StatusOK, gin.H{
				"ok":           true,
				"deduplicated": true,
				"photo":        s.photoJSONForViewerWithAttachments(user.ID, photo, decorations, attachmentByPhoto[photo.ID]),
			})
			return
		} else if !errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
	}
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	settings = normalizeSettings(settings)
	if remaining, unlimited := s.remainingPostMediaSlots(photo, settings); !unlimited && remaining <= 0 {
		c.JSON(http.StatusConflict, gin.H{
			"error":    "attachment limit reached",
			"code":     "post_media_limit_reached",
			"maxCount": settings.PostMediaMaxCount,
		})
		return
	}

	locationShared, latitude, longitude, locationErr := parseLocationForm(c)
	if locationErr != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": locationErr.Error()})
		return
	}
	if locationShared || latitude != nil || longitude != nil {
		// Appends do not alter the post-level location state.
		locationShared = false
	}

	savedPath, err := s.saveUploadedFile(photo.Day, user.ID, fileHeader)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	previewPath := ""
	if photo.CapsuleVisibleAt != nil {
		previewPath, err = s.ensureCapsulePreview(savedPath)
		if err != nil {
			_ = s.removePhotoFile(savedPath)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "capsule preview failed"})
			return
		}
	}
	digest, err := s.fileDigest(savedPath)
	if err != nil {
		_ = s.removePhotoFile(savedPath)
		_ = s.removePhotoFile(previewPath)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "digest failed"})
		return
	}
	var duplicateCount int64
	if err := s.DB.Model(&models.Photo{}).
		Where("id = ? AND (primary_digest = ? OR secondary_digest = ?)", photo.ID, digest, digest).
		Count(&duplicateCount).Error; err != nil {
		_ = s.removePhotoFile(savedPath)
		_ = s.removePhotoFile(previewPath)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if duplicateCount == 0 {
		if err := s.DB.Model(&models.PhotoAttachment{}).Where("photo_id = ? AND digest = ?", photo.ID, digest).Count(&duplicateCount).Error; err != nil {
			_ = s.removePhotoFile(savedPath)
			_ = s.removePhotoFile(previewPath)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
	}
	if duplicateCount > 0 {
		_ = s.removePhotoFile(savedPath)
		_ = s.removePhotoFile(previewPath)
		c.JSON(http.StatusConflict, gin.H{"error": "attachment duplicate"})
		return
	}
	var attachmentCount int64
	if err := s.DB.Model(&models.PhotoAttachment{}).Where("photo_id = ?", photo.ID).Count(&attachmentCount).Error; err != nil {
		_ = s.removePhotoFile(savedPath)
		_ = s.removePhotoFile(previewPath)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	attachment := models.PhotoAttachment{
		PhotoID:        photo.ID,
		UploadClientID: uploadClientID,
		FilePath:       savedPath,
		PreviewPath:    previewPath,
		Digest:         digest,
		SortOrder:      int(attachmentCount) + 2,
		CapturedAt:     capturedAt,
		CreatedAt:      time.Now().UTC(),
	}
	if err := s.DB.Create(&attachment).Error; err != nil {
		_ = s.removePhotoFile(savedPath)
		_ = s.removePhotoFile(previewPath)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "db write failed"})
		return
	}
	if err := s.refreshPhotoSearchDocument(photo.ID); err != nil {
		_ = s.DB.Delete(&attachment).Error
		_ = s.removePhotoFile(savedPath)
		_ = s.removePhotoFile(previewPath)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "search index failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	s.enqueueMediaDerivatives(filepath.ToSlash(filepath.Clean(savedPath)), 8_000, false)
	s.notifyPhotoAttachmentAppended(user, photo)
	decorations, err := s.photoDecorationsForViewer(user.ID, []uint{photo.ID})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "photo decorations query failed"})
		return
	}
	attachmentByPhoto := s.photoAttachmentsByPhotoIDs([]uint{photo.ID})
	c.JSON(http.StatusCreated, gin.H{
		"ok":    true,
		"photo": s.photoJSONForViewerWithAttachments(user.ID, photo, decorations, attachmentByPhoto[photo.ID]),
	})
}

func (s *Server) handleHealth(c *gin.Context) {
	formats := []string{"jpeg", "webp"}
	if s.mediaAVIFEnabled() {
		formats = append(formats, "avif")
	}
	c.JSON(http.StatusOK, gin.H{
		"ok":       true,
		"version":  s.Config.AppVersion,
		"provider": s.Notifier.Name(),
		"features": gin.H{
			"chatDelete":    true,
			"commentDelete": true,
		},
		"mediaCapabilities": gin.H{
			"renditions":  s.Config.MediaRenditionsEnabled,
			"formats":     formats,
			"avifEnabled": s.mediaAVIFEnabled(),
		},
	})
}

func (s *Server) handleMyPhotos(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	items, err := s.myPhotosPayload(user.ID, now)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) myPhotosPayload(userID uint, now time.Time) ([]gin.H, error) {
	queryStart := time.Now()
	var photos []models.Photo
	if err := s.DB.Where("user_id = ?", userID).Order("created_at desc").Limit(120).Find(&photos).Error; err != nil {
		return nil, err
	}
	sortPhotosForFeed(photos)
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/me/photos", "my_photos_query", time.Since(queryStart))
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
		promptQueryStart := time.Now()
		if err := s.DB.Where("day IN ?", days).Find(&prompts).Error; err != nil {
			return nil, err
		}
		if s.Monitor != nil {
			s.Monitor.RecordDBQuery("/api/me/photos", "my_photos_prompt_query", time.Since(promptQueryStart))
		}
		for _, pr := range prompts {
			promptByDay[pr.Day] = pr
		}
	}
	photoIDs := make([]uint, 0, len(photos))
	for _, p := range photos {
		photoIDs = append(photoIDs, p.ID)
	}
	decorations, err := s.photoDecorationsForViewer(userID, photoIDs)
	if err != nil {
		return nil, err
	}
	attachmentByPhoto := s.photoAttachmentsByPhotoIDs(photoIDs)

	out := make([]gin.H, 0, len(photos))
	for _, p := range photos {
		if p.CapsuleVisibleAt != nil && now.Before(*p.CapsuleVisibleAt) {
			continue
		}
		row := s.photoJSONForViewerWithAttachments(userID, p, decorations, attachmentByPhoto[p.ID])
		dailyMoment := false
		if prompt, ok := promptByDay[p.Day]; ok && prompt.TriggeredAt != nil && prompt.UploadUntil != nil {
			effectiveAt := photoEffectiveTime(p)
			dailyMoment = !effectiveAt.Before(*prompt.TriggeredAt) && !effectiveAt.After(*prompt.UploadUntil)
		}
		row["dailyMoment"] = dailyMoment
		out = append(out, row)
	}
	return out, nil
}

func (s *Server) feedDaysForUser(
	userID uint,
	fromDay string,
	toDay string,
	beforeDay string,
	afterDay string,
	limit int,
	anchorDay string,
	now time.Time,
) ([]string, bool, bool, error) {
	fromDay = strings.TrimSpace(fromDay)
	toDay = strings.TrimSpace(toDay)
	beforeDay = strings.TrimSpace(beforeDay)
	afterDay = strings.TrimSpace(afterDay)
	anchorDay = strings.TrimSpace(anchorDay)
	if (fromDay == "") != (toDay == "") {
		return nil, false, false, errors.New("from/to must be provided together")
	}
	if fromDay != "" && (beforeDay != "" || afterDay != "" || anchorDay != "") {
		return nil, false, false, errors.New("from/to cannot be combined with before_day, after_day or anchor_day")
	}
	if beforeDay != "" && afterDay != "" {
		return nil, false, false, errors.New("before_day/after_day must be used individually")
	}
	if fromDay != "" {
		fromParsed, err := time.ParseInLocation("2006-01-02", fromDay, s.Location)
		if err != nil {
			return nil, false, false, errors.New("invalid from date")
		}
		toParsed, err := time.ParseInLocation("2006-01-02", toDay, s.Location)
		if err != nil {
			return nil, false, false, errors.New("invalid to date")
		}
		if fromParsed.After(toParsed) {
			return nil, false, false, errors.New("from must be before or equal to to")
		}
	}
	if beforeDay != "" {
		if _, err := time.ParseInLocation("2006-01-02", beforeDay, s.Location); err != nil {
			return nil, false, false, errors.New("invalid before_day date")
		}
	}
	if afterDay != "" {
		if _, err := time.ParseInLocation("2006-01-02", afterDay, s.Location); err != nil {
			return nil, false, false, errors.New("invalid after_day date")
		}
	}
	if anchorDay != "" {
		if _, err := time.ParseInLocation("2006-01-02", anchorDay, s.Location); err != nil {
			return nil, false, false, errors.New("invalid anchor_day date")
		}
	}
	if limit <= 0 {
		limit = 60
	}
	type row struct {
		Day string
	}
	var rows []row
	queryStart := time.Now()
	query := s.DB.Model(&models.Photo{}).
		Where("user_id = ? OR (capsule_visible_at IS NULL OR capsule_visible_at <= ?)", userID, now)
	if fromDay != "" {
		query = query.Where("day >= ? AND day <= ?", fromDay, toDay)
	} else if beforeDay != "" {
		query = query.Where("day < ?", beforeDay)
	} else if afterDay != "" {
		query = query.Where("day > ?", afterDay)
	}
	order := "day desc"
	if afterDay != "" {
		order = "day asc"
	}
	if err := query.
		Select("DISTINCT day").
		Order(order).
		Limit(limit + 1).
		Scan(&rows).Error; err != nil {
		return nil, false, false, err
	}
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/feed/days", "feed_days_query", time.Since(queryStart))
	}
	today := now.Format("2006-01-02")
	hasPostedToday := true
	includeToday := fromDay == "" || (fromDay <= today && today <= toDay)
	if includeToday {
		var err error
		hasPostedToday, err = s.userHasVisiblePhotoForDay(userID, today, now)
		if err != nil {
			return nil, false, false, err
		}
	}
	days := make([]string, 0, len(rows))
	for _, r := range rows {
		if r.Day == today && !hasPostedToday {
			continue
		}
		days = append(days, r.Day)
	}
	if afterDay != "" {
		sort.Slice(days, func(i, j int) bool { return days[i] > days[j] })
	}
	hasExtra := len(days) > limit
	if hasExtra {
		days = days[:limit]
	}
	if anchorDay != "" {
		idx := slicesIndex(days, anchorDay)
		canAnchor, err := s.canViewerSeeFeedDay(userID, anchorDay, now)
		if err != nil {
			return nil, false, false, err
		}
		if idx < 0 && canAnchor {
			days = append(days, anchorDay)
			sort.Slice(days, func(i, j int) bool { return days[i] > days[j] })
		}
	}
	hasOlder := false
	hasNewer := false
	switch {
	case fromDay != "":
		hasOlder = false
		hasNewer = false
	case beforeDay != "":
		hasOlder = hasExtra
		hasNewer = true
	case afterDay != "":
		hasOlder = true
		hasNewer = hasExtra
	default:
		hasOlder = hasExtra
		hasNewer = false
	}
	return days, hasOlder, hasNewer, nil
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
	var photoMojis []models.PhotoFotomoji
	if err := s.DB.Where("photo_id = ?", photo.ID).Find(&photoMojis).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete fotomojis failed"})
		return
	}
	if err := s.DB.Where("photo_id = ?", photo.ID).Delete(&models.PhotoFotomoji{}).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete fotomojis failed"})
		return
	}
	for _, item := range photoMojis {
		_ = s.removeFotomojiFileIfUnreferenced(item.FilePath, item.ID)
	}
	if err := s.DB.Where("photo_id = ?", photo.ID).Delete(&models.PhotoComment{}).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete comments failed"})
		return
	}
	var attachments []models.PhotoAttachment
	if err := s.DB.Where("photo_id = ?", photo.ID).Find(&attachments).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete attachments failed"})
		return
	}
	if err := s.DB.Where("photo_id = ?", photo.ID).Delete(&models.PhotoAttachment{}).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete attachments failed"})
		return
	}
	if err := s.DB.Delete(&photo).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}
	if err := s.deletePhotoSearchDocument(photo.ID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "search index delete failed"})
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
	for _, attachment := range attachments {
		if err := s.removePhotoFile(attachment.FilePath); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "delete attachment file failed"})
			return
		}
		if err := s.removePhotoFile(attachment.PreviewPath); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "delete attachment preview file failed"})
			return
		}
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

	if shouldNotify {
		if err := s.handlePhotoInteractionSubscription(photo, user.ID, "reaction", time.Now().UTC()); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "bookmark sync failed"})
			return
		}
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

func (s *Server) handlePhotoFotomojiFromTemplate(c *gin.Context) {
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
	emoji := normalizeFotomojiEmoji(req.Emoji)
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

	var tpl models.UserFotomojiTemplate
	if err := s.DB.Where("user_id = ? AND emoji = ?", user.ID, emoji).First(&tpl).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "template not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "template query failed"})
		return
	}

	shouldNotify, err := s.upsertPhotoFotomojiRecord(photo, user, emoji, tpl.FilePath)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "fotomoji save failed"})
		return
	}
	if shouldNotify {
		if err := s.handlePhotoInteractionSubscription(photo, user.ID, "fotomoji", time.Now().UTC()); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "bookmark sync failed"})
			return
		}
	}
	out, err := s.photoInteractionsPayload(photo, user.ID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if shouldNotify {
		s.notifyPhotoFotomoji(user, photo)
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusOK, out)
}

func (s *Server) handlePhotoFotomojiUpload(c *gin.Context) {
	user, _ := userFromContext(c)
	photoID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid photo id"})
		return
	}

	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err == nil && settings.MaxUploadBytes > 0 {
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, settings.MaxUploadBytes)
	}

	fileHeader, err := c.FormFile("photo")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "photo file required"})
		return
	}
	emoji := normalizeFotomojiEmoji(c.PostForm("emoji"))
	if emoji == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "emoji required"})
		return
	}
	saveTemplate := parseFormBool(c.PostForm("saveTemplate"))

	photo, err := s.loadPhotoForInteraction(photoID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "photo not found"})
		return
	}
	if ok, lockErr := s.ensurePhotoVisibleToUser(user.ID, photo); !ok {
		c.JSON(http.StatusForbidden, gin.H{"error": lockErr})
		return
	}

	uploadDay := filepath.ToSlash(filepath.Join(photo.Day, "fotomojis"))
	savedPath, err := s.saveUploadedFile(uploadDay, user.ID, fileHeader)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save file failed"})
		return
	}

	if saveTemplate {
		if err := s.upsertUserFotomojiTemplate(user.ID, emoji, savedPath); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "save template failed"})
			return
		}
	}

	shouldNotify, err := s.upsertPhotoFotomojiRecord(photo, user, emoji, savedPath)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "fotomoji save failed"})
		return
	}
	if shouldNotify {
		if err := s.handlePhotoInteractionSubscription(photo, user.ID, "fotomoji", time.Now().UTC()); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "bookmark sync failed"})
			return
		}
	}
	out, err := s.photoInteractionsPayload(photo, user.ID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if shouldNotify {
		s.notifyPhotoFotomoji(user, photo)
	}
	s.invalidateFeedDayCache(photo.Day)
	c.JSON(http.StatusCreated, out)
}

func (s *Server) handleListMyFotomojiTemplates(c *gin.Context) {
	user, _ := userFromContext(c)
	var rows []models.UserFotomojiTemplate
	if err := s.DB.Where("user_id = ?", user.ID).Order("emoji asc, updated_at desc").Find(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	items := make([]gin.H, 0, len(rows))
	for _, row := range rows {
		items = append(items, s.fotomojiTemplateJSON(row))
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func (s *Server) handleUpsertMyFotomojiTemplate(c *gin.Context) {
	user, _ := userFromContext(c)
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err == nil && settings.MaxUploadBytes > 0 {
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, settings.MaxUploadBytes)
	}
	fileHeader, err := c.FormFile("photo")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "photo file required"})
		return
	}
	emoji := normalizeFotomojiEmoji(c.PostForm("emoji"))
	if emoji == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "emoji required"})
		return
	}
	uploadDay := filepath.ToSlash(filepath.Join("fotomoji-templates", strconv.FormatUint(uint64(user.ID), 10)))
	savedPath, err := s.saveUploadedFile(uploadDay, user.ID, fileHeader)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save file failed"})
		return
	}
	if err := s.upsertUserFotomojiTemplate(user.ID, emoji, savedPath); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save template failed"})
		return
	}
	var out models.UserFotomojiTemplate
	if err := s.DB.Where("user_id = ? AND emoji = ?", user.ID, emoji).First(&out).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"item": s.fotomojiTemplateJSON(out)})
}

func (s *Server) handleDeleteMyFotomojiTemplate(c *gin.Context) {
	user, _ := userFromContext(c)
	emoji := normalizeFotomojiEmoji(c.Param("emoji"))
	if emoji == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "emoji required"})
		return
	}
	var row models.UserFotomojiTemplate
	if err := s.DB.Where("user_id = ? AND emoji = ?", user.ID, emoji).First(&row).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "template not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	oldPath := strings.TrimSpace(row.FilePath)
	activeVersionID := row.ActiveVersionID
	if activeVersionID == 0 {
		var inferred models.UserFotomojiTemplateVersion
		if err := s.DB.Where("user_id = ? AND emoji = ? AND file_path = ?", user.ID, emoji, oldPath).
			Order("id desc").
			First(&inferred).Error; err == nil {
			activeVersionID = inferred.ID
		}
	}
	if activeVersionID != 0 {
		if err := s.DB.Where("id = ? AND user_id = ? AND emoji = ?", activeVersionID, user.ID, emoji).
			Delete(&models.UserFotomojiTemplateVersion{}).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
			return
		}
	}
	var fallback models.UserFotomojiTemplateVersion
	err := s.DB.Where("user_id = ? AND emoji = ?", user.ID, emoji).
		Order("id desc").
		First(&fallback).Error
	switch {
	case err == nil:
		if err := s.DB.Model(&row).Updates(map[string]any{
			"file_path":         fallback.FilePath,
			"active_version_id": fallback.ID,
			"updated_at":        time.Now().UTC(),
		}).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "update failed"})
			return
		}
		if oldPath != strings.TrimSpace(fallback.FilePath) {
			_ = s.removeFotomojiFileIfUnreferenced(oldPath, 0)
		}
		c.JSON(http.StatusOK, gin.H{
			"ok":    true,
			"emoji": emoji,
			"fallback": s.fotomojiTemplateVersionJSON(
				fallback.ID,
				fallback.FilePath,
				fallback.CreatedAt,
				true,
				0,
			),
		})
	case errors.Is(err, gorm.ErrRecordNotFound):
		if err := s.DB.Delete(&row).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
			return
		}
		_ = s.removeFotomojiFileIfUnreferenced(oldPath, 0)
		c.JSON(http.StatusOK, gin.H{"ok": true, "emoji": emoji, "fallback": nil})
	default:
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
	}
}

func (s *Server) handleAdminListFotomojis(c *gin.Context) {
	limit := 200
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil {
			if n < 20 {
				n = 20
			}
			if n > 1000 {
				n = 1000
			}
			limit = n
		}
	}
	day := strings.TrimSpace(c.Query("day"))
	filterEmojiRaw := strings.TrimSpace(c.Query("emoji"))
	filterEmoji := normalizeFotomojiEmoji(filterEmojiRaw)
	if filterEmojiRaw != "" && filterEmoji == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid emoji"})
		return
	}
	fromDay, toDay, fromTime, toTime, rangeErr := s.parseAdminDateRange(c.Query("from"), c.Query("to"))
	if rangeErr != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": rangeErr.Error()})
		return
	}
	reactorUserID := uint(0)
	if raw := strings.TrimSpace(c.Query("userId")); raw != "" {
		parsed, err := parseUintParam(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
			return
		}
		reactorUserID = parsed
	}

	q := s.DB.Model(&models.PhotoFotomoji{}).
		Preload("User").
		Order("photo_fotomojis.created_at desc, photo_fotomojis.id desc").
		Limit(limit)
	if reactorUserID != 0 {
		q = q.Where("photo_fotomojis.user_id = ?", reactorUserID)
	}
	if filterEmoji != "" {
		q = q.Where("photo_fotomojis.emoji = ?", filterEmoji)
	}
	if day != "" {
		q = q.Joins("JOIN photos ON photos.id = photo_fotomojis.photo_id").Where("photos.day = ?", day)
	}
	if fromTime != nil && toTime != nil {
		q = q.Where("photo_fotomojis.created_at >= ? AND photo_fotomojis.created_at < ?", fromTime.UTC(), toTime.UTC())
	}

	var rows []models.PhotoFotomoji
	if err := q.Find(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	photoIDs := make([]uint, 0, len(rows))
	for _, row := range rows {
		photoIDs = append(photoIDs, row.PhotoID)
	}
	photoByID := make(map[uint]models.Photo, len(photoIDs))
	if len(photoIDs) > 0 {
		var photos []models.Photo
		if err := s.DB.Preload("User").Where("id IN ?", photoIDs).Find(&photos).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
		for _, photo := range photos {
			photoByID[photo.ID] = photo
		}
	}

	items := make([]gin.H, 0, len(rows))
	for _, row := range rows {
		photo := photoByID[row.PhotoID]
		items = append(items, gin.H{
			"id":        row.ID,
			"emoji":     row.Emoji,
			"url":       s.avatarURL(row.FilePath),
			"createdAt": row.CreatedAt,
			"updatedAt": row.UpdatedAt,
			"user": gin.H{
				"id":            row.User.ID,
				"username":      row.User.Username,
				"favoriteColor": defaultColor(row.User.FavoriteColor),
			},
			"photo": gin.H{
				"id":  row.PhotoID,
				"day": photo.Day,
				"user": gin.H{
					"id":            photo.User.ID,
					"username":      photo.User.Username,
					"favoriteColor": defaultColor(photo.User.FavoriteColor),
				},
			},
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"items": items,
		"count": len(items),
		"filters": gin.H{
			"day":    day,
			"userId": reactorUserID,
			"emoji":  filterEmoji,
			"from":   fromDay,
			"to":     toDay,
		},
	})
}

func (s *Server) handleAdminFotomojiHistory(c *gin.Context) {
	limit := 1200
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		if n, err := strconv.Atoi(raw); err == nil {
			if n < 50 {
				n = 50
			}
			if n > 5000 {
				n = 5000
			}
			limit = n
		}
	}
	filterUserID := uint(0)
	if raw := strings.TrimSpace(c.Query("userId")); raw != "" {
		parsed, err := parseUintParam(raw)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
			return
		}
		filterUserID = parsed
	}
	filterEmojiRaw := strings.TrimSpace(c.Query("emoji"))
	filterEmoji := normalizeFotomojiEmoji(filterEmojiRaw)
	if filterEmojiRaw != "" && filterEmoji == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid emoji"})
		return
	}
	fromDay, toDay, fromTime, toTime, rangeErr := s.parseAdminDateRange(c.Query("from"), c.Query("to"))
	if rangeErr != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": rangeErr.Error()})
		return
	}

	type templateRow struct {
		UserID          uint      `gorm:"column:user_id"`
		Emoji           string    `gorm:"column:emoji"`
		FilePath        string    `gorm:"column:file_path"`
		ActiveVersionID uint      `gorm:"column:active_version_id"`
		UpdatedAt       time.Time `gorm:"column:updated_at"`
		Username        string    `gorm:"column:username"`
		FavoriteColor   string    `gorm:"column:favorite_color"`
	}
	type versionRow struct {
		VersionID     uint      `gorm:"column:version_id"`
		UserID        uint      `gorm:"column:user_id"`
		Emoji         string    `gorm:"column:emoji"`
		FilePath      string    `gorm:"column:file_path"`
		CreatedAt     time.Time `gorm:"column:created_at"`
		Username      string    `gorm:"column:username"`
		FavoriteColor string    `gorm:"column:favorite_color"`
	}
	var activeRows []templateRow
	activeQuery := s.DB.Table("user_fotomoji_templates t").
		Select("t.user_id, t.emoji, t.file_path, t.active_version_id, t.updated_at, u.username, u.favorite_color").
		Joins("JOIN users u ON u.id = t.user_id").
		Order("u.username asc, t.emoji asc")
	if filterUserID != 0 {
		activeQuery = activeQuery.Where("t.user_id = ?", filterUserID)
	}
	if filterEmoji != "" {
		activeQuery = activeQuery.Where("t.emoji = ?", filterEmoji)
	}
	if fromTime != nil && toTime != nil {
		activeQuery = activeQuery.Where("t.updated_at >= ? AND t.updated_at < ?", fromTime.UTC(), toTime.UTC())
	}
	if err := activeQuery.Find(&activeRows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	var versionRows []versionRow
	versionQuery := s.DB.Table("user_fotomoji_template_versions v").
		Select("v.id as version_id, v.user_id, v.emoji, v.file_path, v.created_at, u.username, u.favorite_color").
		Joins("JOIN users u ON u.id = v.user_id").
		Order("v.created_at desc, v.id desc").
		Limit(limit)
	if filterUserID != 0 {
		versionQuery = versionQuery.Where("v.user_id = ?", filterUserID)
	}
	if filterEmoji != "" {
		versionQuery = versionQuery.Where("v.emoji = ?", filterEmoji)
	}
	if fromTime != nil && toTime != nil {
		versionQuery = versionQuery.Where("v.created_at >= ? AND v.created_at < ?", fromTime.UTC(), toTime.UTC())
	}
	if err := versionQuery.Find(&versionRows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}

	filePathSet := make(map[string]struct{}, len(versionRows)+len(activeRows))
	for _, row := range versionRows {
		path := strings.TrimSpace(row.FilePath)
		if path != "" {
			filePathSet[path] = struct{}{}
		}
	}
	for _, row := range activeRows {
		path := strings.TrimSpace(row.FilePath)
		if path != "" {
			filePathSet[path] = struct{}{}
		}
	}
	filePaths := make([]string, 0, len(filePathSet))
	for path := range filePathSet {
		filePaths = append(filePaths, path)
	}
	postUsageByPath := make(map[string]int64, len(filePaths))
	if len(filePaths) > 0 {
		type usageRow struct {
			FilePath string `gorm:"column:file_path"`
			Count    int64  `gorm:"column:count"`
		}
		var usageRows []usageRow
		if err := s.DB.Model(&models.PhotoFotomoji{}).
			Select("file_path, COUNT(*) as count").
			Where("file_path IN ?", filePaths).
			Group("file_path").
			Scan(&usageRows).Error; err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
		for _, row := range usageRows {
			postUsageByPath[row.FilePath] = row.Count
		}
	}

	type emojiHistory struct {
		Emoji           string
		ActiveVersionID uint
		ActivePath      string
		ActiveUpdatedAt time.Time
		Versions        []versionRow
	}
	type userHistory struct {
		UserID        uint
		Username      string
		FavoriteColor string
		ByEmoji       map[string]*emojiHistory
	}
	userByID := map[uint]*userHistory{}
	ensureUser := func(userID uint, username string, favoriteColor string) *userHistory {
		if existing, ok := userByID[userID]; ok {
			return existing
		}
		created := &userHistory{
			UserID:        userID,
			Username:      username,
			FavoriteColor: favoriteColor,
			ByEmoji:       map[string]*emojiHistory{},
		}
		userByID[userID] = created
		return created
	}
	ensureEmoji := func(user *userHistory, emoji string) *emojiHistory {
		if existing, ok := user.ByEmoji[emoji]; ok {
			return existing
		}
		created := &emojiHistory{Emoji: emoji}
		user.ByEmoji[emoji] = created
		return created
	}

	for _, row := range activeRows {
		u := ensureUser(row.UserID, row.Username, defaultColor(row.FavoriteColor))
		e := ensureEmoji(u, row.Emoji)
		e.ActiveVersionID = row.ActiveVersionID
		e.ActivePath = row.FilePath
		e.ActiveUpdatedAt = row.UpdatedAt
	}
	versionByID := map[uint]versionRow{}
	for _, row := range versionRows {
		u := ensureUser(row.UserID, row.Username, defaultColor(row.FavoriteColor))
		e := ensureEmoji(u, row.Emoji)
		e.Versions = append(e.Versions, row)
		versionByID[row.VersionID] = row
	}

	userIDs := make([]uint, 0, len(userByID))
	for userID := range userByID {
		userIDs = append(userIDs, userID)
	}
	sort.Slice(userIDs, func(i, j int) bool {
		left := userByID[userIDs[i]]
		right := userByID[userIDs[j]]
		if left.Username == right.Username {
			return left.UserID < right.UserID
		}
		return strings.ToLower(left.Username) < strings.ToLower(right.Username)
	})

	items := make([]gin.H, 0, len(userIDs))
	for _, userID := range userIDs {
		userItem := userByID[userID]
		emojis := make([]string, 0, len(userItem.ByEmoji))
		for emoji := range userItem.ByEmoji {
			emojis = append(emojis, emoji)
		}
		sort.Strings(emojis)
		emojiItems := make([]gin.H, 0, len(emojis))
		for _, emoji := range emojis {
			history := userItem.ByEmoji[emoji]
			sort.Slice(history.Versions, func(i, j int) bool {
				if history.Versions[i].CreatedAt.Equal(history.Versions[j].CreatedAt) {
					return history.Versions[i].VersionID > history.Versions[j].VersionID
				}
				return history.Versions[i].CreatedAt.After(history.Versions[j].CreatedAt)
			})
			versionItems := make([]gin.H, 0, len(history.Versions))
			for _, version := range history.Versions {
				versionItems = append(versionItems, s.fotomojiTemplateVersionJSON(
					version.VersionID,
					version.FilePath,
					version.CreatedAt,
					version.VersionID == history.ActiveVersionID,
					postUsageByPath[version.FilePath],
				))
			}
			activeOut := any(nil)
			if history.ActiveVersionID != 0 {
				if activeVersion, ok := versionByID[history.ActiveVersionID]; ok {
					activeOut = s.fotomojiTemplateVersionJSON(
						activeVersion.VersionID,
						activeVersion.FilePath,
						activeVersion.CreatedAt,
						true,
						postUsageByPath[activeVersion.FilePath],
					)
				}
			}
			if activeOut == nil && strings.TrimSpace(history.ActivePath) != "" {
				activeOut = gin.H{
					"id":             history.ActiveVersionID,
					"url":            s.avatarURL(history.ActivePath),
					"filePath":       history.ActivePath,
					"createdAt":      history.ActiveUpdatedAt,
					"isActive":       true,
					"postUsageCount": postUsageByPath[history.ActivePath],
				}
			}
			emojiItems = append(emojiItems, gin.H{
				"emoji":         emoji,
				"activeVersion": activeOut,
				"versions":      versionItems,
			})
		}
		items = append(items, gin.H{
			"user": gin.H{
				"id":            userItem.UserID,
				"username":      userItem.Username,
				"favoriteColor": defaultColor(userItem.FavoriteColor),
			},
			"emojis": emojiItems,
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"items":        items,
		"userCount":    len(items),
		"versionCount": len(versionRows),
		"filters": gin.H{
			"userId": filterUserID,
			"emoji":  filterEmoji,
			"from":   fromDay,
			"to":     toDay,
		},
	})
}

func (s *Server) handleAdminDeleteFotomoji(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid fotomoji id"})
		return
	}
	var row models.PhotoFotomoji
	if err := s.DB.First(&row, id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "fotomoji not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	var photo models.Photo
	_ = s.DB.Select("id", "day").First(&photo, row.PhotoID).Error
	if err := s.DB.Delete(&row).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}
	_ = s.removeFotomojiFileIfUnreferenced(row.FilePath, row.ID)
	if strings.TrimSpace(photo.Day) != "" {
		s.invalidateFeedDayCache(photo.Day)
	}
	c.JSON(http.StatusOK, gin.H{"ok": true, "deletedId": row.ID})
}

func (s *Server) handleAdminBulkDeleteFotomojis(c *gin.Context) {
	var req struct {
		IDs []uint `json:"ids"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	if len(req.IDs) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "ids required"})
		return
	}
	if len(req.IDs) > 1000 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "too many ids"})
		return
	}
	idSet := make(map[uint]struct{}, len(req.IDs))
	ids := make([]uint, 0, len(req.IDs))
	for _, id := range req.IDs {
		if id == 0 {
			continue
		}
		if _, exists := idSet[id]; exists {
			continue
		}
		idSet[id] = struct{}{}
		ids = append(ids, id)
	}
	if len(ids) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "ids required"})
		return
	}

	var rows []models.PhotoFotomoji
	if err := s.DB.Where("id IN ?", ids).Find(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if len(rows) == 0 {
		c.JSON(http.StatusNotFound, gin.H{"error": "fotomojis not found"})
		return
	}

	foundIDs := make([]uint, 0, len(rows))
	photoIDSet := make(map[uint]struct{}, len(rows))
	filePathSet := make(map[string]struct{}, len(rows))
	for _, row := range rows {
		foundIDs = append(foundIDs, row.ID)
		photoIDSet[row.PhotoID] = struct{}{}
		path := strings.TrimSpace(row.FilePath)
		if path != "" {
			filePathSet[path] = struct{}{}
		}
	}

	if err := s.DB.Where("id IN ?", foundIDs).Delete(&models.PhotoFotomoji{}).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}

	photoIDs := make([]uint, 0, len(photoIDSet))
	for photoID := range photoIDSet {
		photoIDs = append(photoIDs, photoID)
	}
	daySet := make(map[string]struct{}, len(photoIDs))
	if len(photoIDs) > 0 {
		var photos []models.Photo
		if err := s.DB.Select("id", "day").Where("id IN ?", photoIDs).Find(&photos).Error; err == nil {
			for _, photo := range photos {
				day := strings.TrimSpace(photo.Day)
				if day != "" {
					daySet[day] = struct{}{}
				}
			}
		}
	}

	for path := range filePathSet {
		_ = s.removeFotomojiFileIfUnreferenced(path, 0)
	}
	for day := range daySet {
		s.invalidateFeedDayCache(day)
	}

	c.JSON(http.StatusOK, gin.H{
		"ok":           true,
		"deletedCount": len(foundIDs),
		"deletedIds":   foundIDs,
	})
}

func (s *Server) handlePhotoComment(c *gin.Context) {
	user, _ := userFromContext(c)
	photoID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid photo id"})
		return
	}
	var req struct {
		Body            string `json:"body" binding:"required,max=500"`
		ClientCommentID string `json:"clientCommentId" binding:"omitempty,max=64"`
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

	clientCommentID := strings.TrimSpace(req.ClientCommentID)
	if existing, ok, err := s.findPhotoCommentByClientID(user.ID, clientCommentID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "comment dedupe lookup failed"})
		return
	} else if ok {
		out, payloadErr := s.photoCommentMutationPayload(photo, user.ID, "comment_created", &existing, 0, true)
		if payloadErr != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
			return
		}
		c.JSON(http.StatusOK, out)
		return
	}

	var (
		comment      models.PhotoComment
		deduplicated bool
	)
	createStart := time.Now()
	err = s.DB.Transaction(func(tx *gorm.DB) error {
		if clientCommentID != "" {
			var existing models.PhotoComment
			err := tx.Preload("User").
				Where("user_id = ? AND client_comment_id = ?", user.ID, clientCommentID).
				First(&existing).Error
			if err == nil {
				comment = existing
				deduplicated = true
				return nil
			}
			if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
				return err
			}
		}

		var last models.PhotoComment
		err := tx.Where("photo_id = ?", photoID).
			Order("created_at desc, id desc").
			First(&last).Error
		if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
			return err
		}
		if err == nil &&
			last.UserID == user.ID &&
			normalizePhotoCommentBodyForDedupe(last.Body) == body {
			preloadErr := tx.Preload("User").First(&last, last.ID).Error
			if preloadErr != nil {
				return preloadErr
			}
			comment = last
			return errors.New("duplicate_consecutive_comment")
		}

		comment = models.PhotoComment{
			PhotoID: photoID,
			UserID:  user.ID,
			Body:    body,
		}
		if clientCommentID != "" {
			comment.ClientCommentID = &clientCommentID
		}
		if err := tx.Create(&comment).Error; err != nil {
			if clientCommentID != "" {
				var existing models.PhotoComment
				findErr := tx.Preload("User").
					Where("user_id = ? AND client_comment_id = ?", user.ID, clientCommentID).
					First(&existing).Error
				if findErr == nil {
					comment = existing
					deduplicated = true
					return nil
				}
			}
			return err
		}
		return tx.Preload("User").First(&comment, comment.ID).Error
	})
	if err != nil {
		if err.Error() == "duplicate_consecutive_comment" {
			out, payloadErr := s.photoCommentMutationPayload(photo, user.ID, "comment_created", &comment, 0, true)
			if payloadErr != nil {
				c.JSON(http.StatusConflict, gin.H{"error": "duplicate consecutive comment", "errorCode": "duplicate_consecutive_comment"})
				return
			}
			out["error"] = "duplicate consecutive comment"
			out["errorCode"] = "duplicate_consecutive_comment"
			c.JSON(http.StatusConflict, out)
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "comment create failed"})
		return
	}
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/photos/:id/comments", "photo_comment_insert", time.Since(createStart))
	}
	if !deduplicated {
		if err := s.handlePhotoInteractionSubscription(photo, user.ID, "comment", time.Now().UTC()); err != nil {
			_ = s.DB.Delete(&comment).Error
			c.JSON(http.StatusInternalServerError, gin.H{"error": "bookmark sync failed"})
			return
		}
	}
	if err := s.refreshPhotoSearchDocument(photo.ID); err != nil {
		if !deduplicated {
			_ = s.DB.Delete(&comment).Error
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "search index failed"})
		return
	}
	payloadStart := time.Now()
	out, payloadErr := s.photoCommentMutationPayload(photo, user.ID, "comment_created", &comment, 0, deduplicated)
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/photos/:id/comments", "photo_comment_mutation_payload", time.Since(payloadStart))
	}
	if payloadErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if !deduplicated {
		s.invalidateFeedDayCache(photo.Day)
	}
	if !deduplicated {
		s.notifyPhotoComment(user, photo, comment)
		c.JSON(http.StatusCreated, out)
		return
	}
	c.JSON(http.StatusOK, out)
}

func (s *Server) handleDeletePhotoComment(c *gin.Context) {
	user, _ := userFromContext(c)
	photoID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid photo id"})
		return
	}
	commentID, err := parseUintParam(c.Param("commentId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid comment id"})
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

	var comment models.PhotoComment
	if err := s.DB.Preload("User").
		Where("id = ? AND photo_id = ?", commentID, photoID).
		First(&comment).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "comment not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	if comment.UserID != user.ID {
		c.JSON(http.StatusForbidden, gin.H{"error": "not allowed"})
		return
	}
	if err := s.DB.Delete(&comment).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
		return
	}
	if err := s.refreshPhotoSearchDocument(photo.ID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "search index failed"})
		return
	}
	payloadStart := time.Now()
	out, payloadErr := s.photoCommentMutationPayload(photo, user.ID, "comment_deleted", nil, comment.ID, false)
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/photos/:id/comments/:commentId", "photo_comment_delete_payload", time.Since(payloadStart))
	}
	if payloadErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	s.invalidateFeedDayCache(photo.Day)
	s.cancelPhotoCommentNotification(photo, comment)
	c.JSON(http.StatusOK, out)
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

func normalizeFotomojiEmoji(raw string) string {
	emoji := strings.TrimSpace(raw)
	if emoji == "" {
		return ""
	}
	if len([]rune(emoji)) > 8 {
		return ""
	}
	return emoji
}

func normalizeUploadClientID(raw string) string {
	value := strings.TrimSpace(raw)
	if len(value) > 64 {
		value = value[:64]
	}
	return value
}

func formatPublicPhotoNumber(day string, seq int) string {
	parsed, err := time.Parse("2006-01-02", strings.TrimSpace(day))
	if err != nil {
		return ""
	}
	if seq < 1 {
		seq = 1
	}
	return fmt.Sprintf("%02d%02d%02d%03d", parsed.Year()%100, int(parsed.Month()), parsed.Day(), seq)
}

func publicPhotoNumberPrefix(day string) string {
	number := formatPublicPhotoNumber(day, 1)
	if len(number) < 6 {
		return ""
	}
	return number[:6]
}

func parsePublicPhotoSequence(day string, publicNumber string) (int, bool) {
	number := strings.TrimSpace(publicNumber)
	prefix := publicPhotoNumberPrefix(day)
	if prefix == "" || len(number) != 9 || !strings.HasPrefix(number, prefix) {
		return 0, false
	}
	seq, err := strconv.Atoi(number[6:])
	if err != nil || seq < 1 {
		return 0, false
	}
	return seq, true
}

func (s *Server) assignAndPersistPublicPhotoNumber(photo *models.Photo) error {
	if photo == nil {
		return errors.New("nil photo")
	}
	if photo.PublicNumber != nil && strings.TrimSpace(*photo.PublicNumber) != "" {
		return nil
	}
	return s.DB.Transaction(func(tx *gorm.DB) error {
		var existing []string
		if err := tx.Model(&models.Photo{}).
			Where("day = ? AND public_number <> ''", photo.Day).
			Pluck("public_number", &existing).Error; err != nil {
			return err
		}
		nextSeq := 1
		for _, number := range existing {
			if seq, ok := parsePublicPhotoSequence(photo.Day, number); ok && seq >= nextSeq {
				nextSeq = seq + 1
			}
		}
		publicNumber := formatPublicPhotoNumber(photo.Day, nextSeq)
		if publicNumber == "" {
			return errors.New("invalid photo day")
		}
		if err := tx.Model(&models.Photo{}).Where("id = ?", photo.ID).Update("public_number", publicNumber).Error; err != nil {
			return err
		}
		photo.PublicNumber = &publicNumber
		return nil
	})
}

func (s *Server) parseCapturedAtValue(raw string) (*time.Time, error) {
	value := strings.TrimSpace(raw)
	if value == "" {
		return nil, nil
	}
	layouts := []string{
		time.RFC3339,
		"2006-01-02T15:04Z07:00",
	}
	for _, layout := range layouts {
		parsed, err := time.Parse(layout, value)
		if err == nil {
			captured := parsed.In(s.Location)
			return &captured, nil
		}
	}
	return nil, errors.New("invalid captured_at")
}

func photoEffectiveTime(photo models.Photo) time.Time {
	if photo.CapturedAt != nil && !photo.CapturedAt.IsZero() {
		return *photo.CapturedAt
	}
	return photo.CreatedAt
}

func photoTimeShifted(photo models.Photo) bool {
	if photo.CapturedAt == nil || photo.CapturedAt.IsZero() || photo.CreatedAt.IsZero() {
		return false
	}
	diff := photo.CreatedAt.Sub(*photo.CapturedAt)
	if diff < 0 {
		diff = -diff
	}
	return diff > photoTimeShiftThreshold
}

func photoPublicNumberValue(photo models.Photo) string {
	if photo.PublicNumber == nil {
		return ""
	}
	return strings.TrimSpace(*photo.PublicNumber)
}

func sortPhotosForFeed(photos []models.Photo) {
	sort.SliceStable(photos, func(i, j int) bool {
		left := photoEffectiveTime(photos[i])
		right := photoEffectiveTime(photos[j])
		if !left.Equal(right) {
			return left.After(right)
		}
		if !photos[i].CreatedAt.Equal(photos[j].CreatedAt) {
			return photos[i].CreatedAt.After(photos[j].CreatedAt)
		}
		return photos[i].ID > photos[j].ID
	})
}

type discoverDayPayload struct {
	Day            string
	Payload        gin.H
	InteractionSum int
	BookmarkSum    int
	BestScore      int
	BestAt         time.Time
}

func feedItemInteractionCount(item gin.H) int {
	count := 0
	if reactions, ok := item["reactions"].([]gin.H); ok {
		count += len(reactions)
	}
	if comments, ok := item["comments"].([]gin.H); ok {
		count += len(comments)
	}
	if photoMojis, ok := item["photoMojis"].([]gin.H); ok {
		count += len(photoMojis)
	}
	return count
}

func feedItemBookmarkCount(item gin.H) int {
	photo, ok := item["photo"].(gin.H)
	if !ok {
		return 0
	}
	switch raw := photo["bookmarkCount"].(type) {
	case int:
		return raw
	case int32:
		return int(raw)
	case int64:
		return int(raw)
	case float64:
		return int(raw)
	default:
		return 0
	}
}

func feedItemEffectiveTime(item gin.H) time.Time {
	photo, ok := item["photo"].(gin.H)
	if !ok {
		return time.Time{}
	}
	switch raw := photo["createdAt"].(type) {
	case time.Time:
		return raw
	case *time.Time:
		if raw != nil {
			return *raw
		}
	case string:
		if parsed, err := time.Parse(time.RFC3339Nano, raw); err == nil {
			return parsed
		}
	}
	return time.Time{}
}

func feedItemPhotoID(item gin.H) uint64 {
	photo, ok := item["photo"].(gin.H)
	if !ok {
		return 0
	}
	switch raw := photo["id"].(type) {
	case uint:
		return uint64(raw)
	case uint64:
		return raw
	case int:
		if raw > 0 {
			return uint64(raw)
		}
	case int64:
		if raw > 0 {
			return uint64(raw)
		}
	case float64:
		if raw > 0 {
			return uint64(raw)
		}
	}
	return 0
}

func seededSortWeight(seed int64, day string, photoID uint64) uint64 {
	sum := sha256.Sum256([]byte(fmt.Sprintf("%d|%s|%d", seed, day, photoID)))
	return uint64(sum[0])<<56 | uint64(sum[1])<<48 | uint64(sum[2])<<40 | uint64(sum[3])<<32 |
		uint64(sum[4])<<24 | uint64(sum[5])<<16 | uint64(sum[6])<<8 | uint64(sum[7])
}

func sortFeedItemsForDiscover(items []gin.H, mode string, seed int64, day string) {
	switch mode {
	case "trend":
		sort.SliceStable(items, func(i, j int) bool {
			leftInteraction := feedItemInteractionCount(items[i])
			rightInteraction := feedItemInteractionCount(items[j])
			if leftInteraction != rightInteraction {
				return leftInteraction > rightInteraction
			}
			leftBookmark := feedItemBookmarkCount(items[i])
			rightBookmark := feedItemBookmarkCount(items[j])
			if leftBookmark != rightBookmark {
				return leftBookmark > rightBookmark
			}
			leftTime := feedItemEffectiveTime(items[i])
			rightTime := feedItemEffectiveTime(items[j])
			if !leftTime.Equal(rightTime) {
				return leftTime.After(rightTime)
			}
			return feedItemPhotoID(items[i]) > feedItemPhotoID(items[j])
		})
	case "random":
		sort.SliceStable(items, func(i, j int) bool {
			left := seededSortWeight(seed, day, feedItemPhotoID(items[i]))
			right := seededSortWeight(seed, day, feedItemPhotoID(items[j]))
			if left != right {
				return left < right
			}
			return feedItemPhotoID(items[i]) > feedItemPhotoID(items[j])
		})
	}
}

func (s *Server) findPhotoByUploadClientID(userID uint, uploadClientID string) (models.Photo, bool, error) {
	if strings.TrimSpace(uploadClientID) == "" {
		return models.Photo{}, false, nil
	}
	var photo models.Photo
	err := s.DB.Where("user_id = ? AND upload_client_id = ?", userID, uploadClientID).Order("id desc").First(&photo).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return models.Photo{}, false, nil
	}
	if err != nil {
		return models.Photo{}, false, err
	}
	return photo, true, nil
}

func (s *Server) findRecentDuplicatePhoto(userID uint, day string, promptOnly bool, primaryDigest string, secondaryDigest string, now time.Time) (models.Photo, bool, error) {
	if strings.TrimSpace(primaryDigest) == "" {
		return models.Photo{}, false, nil
	}
	var photos []models.Photo
	cutoff := now.Add(-duplicateDigestWindow)
	if err := s.DB.
		Where("user_id = ? AND day = ? AND prompt_only = ? AND created_at >= ?", userID, day, promptOnly, cutoff).
		Order("created_at desc, id desc").
		Find(&photos).Error; err != nil {
		return models.Photo{}, false, err
	}
	for _, photo := range photos {
		if photo.PrimaryDigest == primaryDigest && photo.SecondaryDigest == secondaryDigest {
			return photo, true, nil
		}
	}
	return models.Photo{}, false, nil
}

func (s *Server) fileDigest(relPath string) (string, error) {
	cleanRel := filepath.ToSlash(strings.TrimSpace(relPath))
	if cleanRel == "" {
		return "", errors.New("empty photo path")
	}
	fullPath := filepath.Join(s.Config.UploadDir, cleanRel)
	file, err := os.Open(fullPath)
	if err != nil {
		return "", err
	}
	defer file.Close()
	sum := sha256.New()
	if _, err := io.Copy(sum, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(sum.Sum(nil)), nil
}

func (s *Server) removePhotoFiles(photo models.Photo) {
	for _, rel := range []string{
		photo.FilePath,
		photo.SecondPath,
		photo.CapsulePreviewPath,
		photo.CapsuleSecondPreviewPath,
	} {
		cleanRel := filepath.ToSlash(strings.TrimSpace(rel))
		if cleanRel == "" {
			continue
		}
		_ = os.Remove(filepath.Join(s.Config.UploadDir, cleanRel))
	}
	var attachments []models.PhotoAttachment
	if err := s.DB.Where("photo_id = ?", photo.ID).Find(&attachments).Error; err == nil {
		for _, attachment := range attachments {
			for _, rel := range []string{attachment.FilePath, attachment.PreviewPath} {
				cleanRel := filepath.ToSlash(strings.TrimSpace(rel))
				if cleanRel == "" {
					continue
				}
				_ = os.Remove(filepath.Join(s.Config.UploadDir, cleanRel))
			}
		}
	}
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

func (s *Server) ensurePhotoThumbnail(relPath string) (string, error) {
	cleanRel := filepath.ToSlash(strings.TrimSpace(relPath))
	if cleanRel == "" {
		return "", errors.New("empty photo path")
	}
	ext := filepath.Ext(cleanRel)
	base := strings.TrimSuffix(cleanRel, ext)
	thumbnailRel := filepath.ToSlash(filepath.Join("thumbnails", base+"_thumb.jpg"))
	thumbnailFull := filepath.Join(s.Config.UploadDir, thumbnailRel)
	if _, err := os.Stat(thumbnailFull); err == nil {
		return thumbnailRel, nil
	}
	source, err := os.Open(filepath.Join(s.Config.UploadDir, cleanRel))
	if err != nil {
		return "", err
	}
	defer source.Close()
	img, _, err := image.Decode(source)
	if err != nil {
		return "", err
	}
	bounds := img.Bounds()
	width, height := maxInt(1, bounds.Dx()), maxInt(1, bounds.Dy())
	const targetMax = 480
	if width > targetMax || height > targetMax {
		if width >= height {
			height = maxInt(1, int(float64(height)*float64(targetMax)/float64(width)))
			width = targetMax
		} else {
			width = maxInt(1, int(float64(width)*float64(targetMax)/float64(height)))
			height = targetMax
		}
	}
	thumbnail := scaleImageNearest(img, width, height)
	if err := os.MkdirAll(filepath.Dir(thumbnailFull), 0o755); err != nil {
		return "", err
	}
	out, err := os.CreateTemp(filepath.Dir(thumbnailFull), ".daily-thumb-*.tmp")
	if err != nil {
		return "", err
	}
	tempPath := out.Name()
	defer os.Remove(tempPath)
	if err := jpeg.Encode(out, thumbnail, &jpeg.Options{Quality: 72}); err != nil {
		_ = out.Close()
		return "", err
	}
	if err := out.Close(); err != nil {
		return "", err
	}
	if err := os.Rename(tempPath, thumbnailFull); err != nil {
		if _, statErr := os.Stat(thumbnailFull); statErr != nil {
			return "", err
		}
	}
	return thumbnailRel, nil
}

func (s *Server) photoThumbnailURL(relPath string) string {
	thumbnail, err := s.ensurePhotoThumbnail(relPath)
	if err != nil || strings.TrimSpace(thumbnail) == "" {
		return ""
	}
	return fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, thumbnail)
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

func (s *Server) removeFotomojiFileIfUnreferenced(relPath string, excludeFotomojiID uint) error {
	path := strings.TrimSpace(relPath)
	if path == "" {
		return nil
	}
	var photoCount int64
	q := s.DB.Model(&models.PhotoFotomoji{}).Where("file_path = ?", path)
	if excludeFotomojiID > 0 {
		q = q.Where("id <> ?", excludeFotomojiID)
	}
	if err := q.Count(&photoCount).Error; err != nil {
		return err
	}
	if photoCount > 0 {
		return nil
	}
	var templateCount int64
	if err := s.DB.Model(&models.UserFotomojiTemplate{}).Where("file_path = ?", path).Count(&templateCount).Error; err != nil {
		return err
	}
	if templateCount > 0 {
		return nil
	}
	var templateVersionCount int64
	if err := s.DB.Model(&models.UserFotomojiTemplateVersion{}).Where("file_path = ?", path).Count(&templateVersionCount).Error; err != nil {
		return err
	}
	if templateVersionCount > 0 {
		return nil
	}
	return s.removePhotoFile(path)
}

func (s *Server) upsertUserFotomojiTemplate(userID uint, emoji string, filePath string) error {
	return s.DB.Transaction(func(tx *gorm.DB) error {
		now := time.Now().UTC()
		version := models.UserFotomojiTemplateVersion{
			UserID:    userID,
			Emoji:     emoji,
			FilePath:  filePath,
			CreatedAt: now,
		}
		if err := tx.Create(&version).Error; err != nil {
			return err
		}
		var existing models.UserFotomojiTemplate
		err := tx.Where("user_id = ? AND emoji = ?", userID, emoji).First(&existing).Error
		if err == nil {
			return tx.Model(&existing).Updates(map[string]any{
				"file_path":         filePath,
				"active_version_id": version.ID,
				"updated_at":        now,
			}).Error
		}
		if !errors.Is(err, gorm.ErrRecordNotFound) {
			return err
		}
		row := models.UserFotomojiTemplate{
			UserID:          userID,
			Emoji:           emoji,
			FilePath:        filePath,
			ActiveVersionID: version.ID,
			CreatedAt:       now,
			UpdatedAt:       now,
		}
		return tx.Create(&row).Error
	})
}

func (s *Server) upsertPhotoFotomojiRecord(photo models.Photo, actor models.User, emoji string, filePath string) (bool, error) {
	var existing models.PhotoFotomoji
	err := s.DB.Where("photo_id = ? AND user_id = ?", photo.ID, actor.ID).First(&existing).Error
	if err == nil {
		oldPath := existing.FilePath
		oldEmoji := existing.Emoji
		if err := s.DB.Model(&existing).Updates(map[string]any{
			"emoji":      emoji,
			"file_path":  filePath,
			"updated_at": time.Now().UTC(),
		}).Error; err != nil {
			return false, err
		}
		if oldPath != filePath {
			_ = s.removeFotomojiFileIfUnreferenced(oldPath, existing.ID)
		}
		return oldPath != filePath || oldEmoji != emoji, nil
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return false, err
	}
	row := models.PhotoFotomoji{
		PhotoID:   photo.ID,
		UserID:    actor.ID,
		Emoji:     emoji,
		FilePath:  filePath,
		CreatedAt: time.Now().UTC(),
		UpdatedAt: time.Now().UTC(),
	}
	if err := s.DB.Create(&row).Error; err != nil {
		return false, err
	}
	return true, nil
}

func (s *Server) fotomojiTemplateJSON(tpl models.UserFotomojiTemplate) gin.H {
	return gin.H{
		"id":              tpl.ID,
		"emoji":           tpl.Emoji,
		"url":             s.avatarURL(tpl.FilePath),
		"createdAt":       tpl.CreatedAt,
		"updatedAt":       tpl.UpdatedAt,
		"activeVersionId": tpl.ActiveVersionID,
	}
}

func (s *Server) fotomojiTemplateVersionJSON(versionID uint, filePath string, createdAt time.Time, isActive bool, postUsageCount int64) gin.H {
	return gin.H{
		"id":             versionID,
		"url":            s.avatarURL(filePath),
		"filePath":       filePath,
		"createdAt":      createdAt,
		"isActive":       isActive,
		"postUsageCount": postUsageCount,
	}
}

func (s *Server) photoFotomojiJSON(item models.PhotoFotomoji, includeUser bool) gin.H {
	out := gin.H{
		"id":        item.ID,
		"emoji":     item.Emoji,
		"url":       s.avatarURL(item.FilePath),
		"createdAt": item.CreatedAt,
		"updatedAt": item.UpdatedAt,
	}
	if includeUser {
		out["user"] = gin.H{
			"id":            item.User.ID,
			"username":      item.User.Username,
			"favoriteColor": defaultColor(item.User.FavoriteColor),
		}
	}
	return out
}

func (s *Server) photoMarkJSON(item models.PhotoMark) gin.H {
	surface := strings.TrimSpace(item.Surface)
	if surface == "" {
		surface = "frame"
	}
	return gin.H{
		"id":        item.ID,
		"userId":    item.UserID,
		"username":  strings.TrimSpace(item.User.Username),
		"color":     defaultColor(item.Color),
		"surface":   surface,
		"centerX":   item.CenterX,
		"centerY":   item.CenterY,
		"radiusX":   item.RadiusX,
		"radiusY":   item.RadiusY,
		"rotation":  item.Rotation,
		"seed":      item.Seed,
		"layer":     item.Layer,
		"createdAt": item.CreatedAt,
		"updatedAt": item.UpdatedAt,
	}
}

func (s *Server) photoPaintJSON(item models.PhotoPaint) gin.H {
	surface := strings.TrimSpace(item.Surface)
	if surface == "" {
		surface = "frame"
	}
	return gin.H{
		"id":          item.ID,
		"userId":      item.UserID,
		"username":    strings.TrimSpace(item.User.Username),
		"color":       defaultColor(item.Color),
		"surface":     surface,
		"strokeWidth": item.StrokeWidth,
		"pathsJson":   item.PathsJSON,
		"createdAt":   item.CreatedAt,
		"updatedAt":   item.UpdatedAt,
	}
}

func (s *Server) photoAttachmentsByPhotoIDs(photoIDs []uint) map[uint][]models.PhotoAttachment {
	if s == nil || s.DB == nil || len(photoIDs) == 0 {
		return map[uint][]models.PhotoAttachment{}
	}
	var attachments []models.PhotoAttachment
	if err := s.DB.Where("photo_id IN ?", photoIDs).Order("photo_id asc, sort_order asc, id asc").Find(&attachments).Error; err != nil {
		return map[uint][]models.PhotoAttachment{}
	}
	out := make(map[uint][]models.PhotoAttachment, len(photoIDs))
	for _, item := range attachments {
		out[item.PhotoID] = append(out[item.PhotoID], item)
	}
	return out
}

func (s *Server) photoMediaJSON(p models.Photo, attachments []models.PhotoAttachment, queueMissing ...bool) []gin.H {
	shouldQueueMissing := true
	if len(queueMissing) > 0 {
		shouldQueueMissing = queueMissing[0]
	}
	media := make([]gin.H, 0, 2+len(attachments))
	if strings.TrimSpace(p.FilePath) != "" {
		item := gin.H{
			"id":         fmt.Sprintf("photo-%d-primary", p.ID),
			"url":        fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.FilePath),
			"capturedAt": p.CapturedAt,
			"sourceKind": "primary",
		}
		if thumbnailURL := s.photoThumbnailURL(p.FilePath); thumbnailURL != "" {
			item["thumbnailUrl"] = thumbnailURL
		}
		item["renditions"] = s.mediaRenditionsJSONWithQueue(p.FilePath, shouldQueueMissing)
		if strings.TrimSpace(p.CapsulePreviewPath) != "" {
			item["previewUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.CapsulePreviewPath)
		}
		media = append(media, item)
	}
	if strings.TrimSpace(p.SecondPath) != "" {
		item := gin.H{
			"id":         fmt.Sprintf("photo-%d-secondary", p.ID),
			"url":        fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.SecondPath),
			"capturedAt": p.CapturedAt,
			"sourceKind": "secondary",
		}
		if thumbnailURL := s.photoThumbnailURL(p.SecondPath); thumbnailURL != "" {
			item["thumbnailUrl"] = thumbnailURL
		}
		item["renditions"] = s.mediaRenditionsJSONWithQueue(p.SecondPath, shouldQueueMissing)
		if strings.TrimSpace(p.CapsuleSecondPreviewPath) != "" {
			item["previewUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.CapsuleSecondPreviewPath)
		}
		media = append(media, item)
	}
	for _, attachment := range attachments {
		item := gin.H{
			"id":         fmt.Sprintf("attachment-%d", attachment.ID),
			"url":        fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, attachment.FilePath),
			"capturedAt": attachment.CapturedAt,
			"sourceKind": "attachment",
		}
		if thumbnailURL := s.photoThumbnailURL(attachment.FilePath); thumbnailURL != "" {
			item["thumbnailUrl"] = thumbnailURL
		}
		item["renditions"] = s.mediaRenditionsJSONWithQueue(attachment.FilePath, shouldQueueMissing)
		if strings.TrimSpace(attachment.PreviewPath) != "" {
			item["previewUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, attachment.PreviewPath)
		}
		media = append(media, item)
	}
	return media
}

func (s *Server) photoJSON(p models.Photo) gin.H {
	attachmentMap := s.photoAttachmentsByPhotoIDs([]uint{p.ID})
	return s.photoJSONWithAttachments(p, attachmentMap[p.ID])
}

func (s *Server) photoJSONWithAttachments(p models.Photo, attachments []models.PhotoAttachment, queueMissing ...bool) gin.H {
	shouldQueueMissing := true
	if len(queueMissing) > 0 {
		shouldQueueMissing = queueMissing[0]
	}
	effectiveAt := photoEffectiveTime(p)
	publicNumber := ""
	if p.PublicNumber != nil {
		publicNumber = strings.TrimSpace(*p.PublicNumber)
	}
	creativeMode := "none"
	if p.User.ID != 0 {
		creativeMode = normalizeCreativePostMode(p.User.CreativePostMode)
	}
	out := gin.H{
		"id":                 p.ID,
		"day":                p.Day,
		"promptOnly":         p.PromptOnly,
		"momentKind":         normalizePhotoMomentKind(p.MomentKind),
		"caption":            p.Caption,
		"createdAt":          effectiveAt,
		"capturedAt":         p.CapturedAt,
		"uploadedAt":         p.CreatedAt,
		"timeShifted":        photoTimeShifted(p),
		"capsuleMode":        p.CapsuleMode,
		"capsuleVisibleAt":   p.CapsuleVisibleAt,
		"capsulePrivate":     false,
		"capsuleGroupRemind": p.CapsuleGroupRemind,
		"url":                fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.FilePath),
		"capsuleLocked":      false,
		"capsulePreviewUrl":  "",
		"locationShared":     false,
		"locationDisplay":    "",
		"locationMapsUrl":    "",
		"deduplicated":       false,
		"bookmarkedByMe":     false,
		"bookmarkCount":      0,
		"media":              []gin.H{},
		"mediaCount":         0,
		"nsfw":               p.Nsfw,
		"nsfwMarkedByUserId": p.NsfwMarkedByUserID,
		"nsfwMarkedAt":       p.NsfwMarkedAt,
		"nsfwMarkAllowed":    false,
		"nsfwUnmarkAllowed":  false,
		"publicNumber":       publicNumber,
		"creativePostMode":   creativeMode,
		"canMark":            false,
		"canPaint":           false,
		"markedByMe":         false,
		"paintedByMe":        false,
		"marks":              []gin.H{},
		"paints":             []gin.H{},
	}
	if thumbnailURL := s.photoThumbnailURL(p.FilePath); thumbnailURL != "" {
		out["thumbnailUrl"] = thumbnailURL
	}
	if p.SecondPath != "" {
		out["secondUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.SecondPath)
		if thumbnailURL := s.photoThumbnailURL(p.SecondPath); thumbnailURL != "" {
			out["secondThumbnailUrl"] = thumbnailURL
		}
	}
	media := s.photoMediaJSON(p, attachments, shouldQueueMissing)
	out["media"] = media
	out["mediaCount"] = len(media)
	if strings.TrimSpace(p.CapsulePreviewPath) != "" {
		out["capsulePreviewUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.CapsulePreviewPath)
	}
	if p.LocationShared && p.LocationLatitude != nil && p.LocationLongitude != nil {
		out["locationShared"] = true
		out["locationDisplay"] = formatLocationDisplay(*p.LocationLatitude, *p.LocationLongitude)
		out["locationMapsUrl"] = googleMapsLocationURL(*p.LocationLatitude, *p.LocationLongitude)
	}
	return out
}

func (s *Server) photoJSONForViewer(viewerID uint, p models.Photo, decorations *viewerPhotoDecorations) gin.H {
	attachmentMap := s.photoAttachmentsByPhotoIDs([]uint{p.ID})
	return s.photoJSONForViewerWithAttachments(viewerID, p, decorations, attachmentMap[p.ID])
}

func (s *Server) photoJSONForViewerWithoutDerivativeQueue(viewerID uint, p models.Photo, decorations *viewerPhotoDecorations) gin.H {
	attachmentMap := s.photoAttachmentsByPhotoIDs([]uint{p.ID})
	return s.photoJSONForViewerWithAttachmentsAndQueue(viewerID, p, decorations, attachmentMap[p.ID], false)
}

func (s *Server) photoJSONForViewerWithAttachments(viewerID uint, p models.Photo, decorations *viewerPhotoDecorations, attachments []models.PhotoAttachment) gin.H {
	return s.photoJSONForViewerWithAttachmentsAndQueue(viewerID, p, decorations, attachments, true)
}

func (s *Server) photoJSONForViewerWithAttachmentsAndQueue(viewerID uint, p models.Photo, decorations *viewerPhotoDecorations, attachments []models.PhotoAttachment, queueMissing bool) gin.H {
	row := s.photoJSONWithAttachments(p, attachments, queueMissing)
	creativeMode := normalizeCreativePostMode(strings.TrimSpace(p.User.CreativePostMode))
	if decorations != nil {
		if decorations.bookmarkMap != nil {
			row["bookmarkedByMe"] = decorations.bookmarkMap[p.ID]
		}
		if decorations.bookmarkCounts != nil {
			row["bookmarkCount"] = decorations.bookmarkCounts[p.ID]
		}
		if decorations.marksByPhoto != nil {
			if marks := decorations.marksByPhoto[p.ID]; marks != nil {
				row["marks"] = marks
			}
		}
		if decorations.paintsByPhoto != nil {
			if paints := decorations.paintsByPhoto[p.ID]; paints != nil {
				row["paints"] = paints
			}
		}
		if decorations.myMarked != nil {
			row["markedByMe"] = decorations.myMarked[p.ID]
		}
		if decorations.myPainted != nil {
			row["paintedByMe"] = decorations.myPainted[p.ID]
		}
	}
	row["creativePostMode"] = creativeMode
	row["canMark"] = viewerID == p.UserID || creativeModeAllowsMark(creativeMode)
	row["canPaint"] = viewerID == p.UserID || creativeModeAllowsPaint(creativeMode)
	row["nsfw"] = p.Nsfw
	row["nsfwMarkedByUserId"] = p.NsfwMarkedByUserID
	row["nsfwMarkedAt"] = p.NsfwMarkedAt
	row["nsfwMarkAllowed"] = canViewerMarkNsfwPhoto(models.User{ID: viewerID}, p)
	if decorations != nil && decorations.viewer != nil {
		row["nsfwMarkAllowed"] = canViewerMarkNsfwPhoto(*decorations.viewer, p)
		row["nsfwUnmarkAllowed"] = canViewerUnmarkNsfwPhoto(*decorations.viewer, p)
	} else {
		row["nsfwUnmarkAllowed"] = viewerID == p.UserID
	}
	return row
}

func (s *Server) bookmarkMapForViewer(viewerID uint, photoIDs []uint) (map[uint]bool, error) {
	out := make(map[uint]bool, len(photoIDs))
	if viewerID == 0 || len(photoIDs) == 0 {
		return out, nil
	}
	var rows []models.PhotoBookmark
	if err := s.DB.
		Where("user_id = ? AND active = ? AND photo_id IN ?", viewerID, true, photoIDs).
		Find(&rows).Error; err != nil {
		return nil, err
	}
	for _, row := range rows {
		out[row.PhotoID] = true
	}
	return out, nil
}

func (s *Server) bookmarkCountsForPhotos(photoIDs []uint) (map[uint]int64, error) {
	out := make(map[uint]int64, len(photoIDs))
	if len(photoIDs) == 0 {
		return out, nil
	}
	var rows []struct {
		PhotoID uint
		Count   int64
	}
	if err := s.DB.Model(&models.PhotoBookmark{}).
		Select("photo_id, COUNT(*) AS count").
		Where("active = ? AND photo_id IN ?", true, photoIDs).
		Group("photo_id").
		Find(&rows).Error; err != nil {
		return nil, err
	}
	for _, row := range rows {
		out[row.PhotoID] = row.Count
	}
	return out, nil
}

func (s *Server) photoDecorationsForViewer(viewerID uint, photoIDs []uint) (*viewerPhotoDecorations, error) {
	decorations := &viewerPhotoDecorations{
		bookmarkMap:    map[uint]bool{},
		bookmarkCounts: map[uint]int64{},
		marksByPhoto:   map[uint][]gin.H{},
		paintsByPhoto:  map[uint][]gin.H{},
		myMarked:       map[uint]bool{},
		myPainted:      map[uint]bool{},
	}
	if len(photoIDs) == 0 {
		return decorations, nil
	}
	if viewerID != 0 {
		var viewer models.User
		if err := s.DB.Select("id", "is_admin").First(&viewer, viewerID).Error; err == nil {
			decorations.viewer = &viewer
		}
	}
	bookmarkMap, err := s.bookmarkMapForViewer(viewerID, photoIDs)
	if err != nil {
		return nil, err
	}
	bookmarkCounts, err := s.bookmarkCountsForPhotos(photoIDs)
	if err != nil {
		return nil, err
	}
	decorations.bookmarkMap = bookmarkMap
	decorations.bookmarkCounts = bookmarkCounts

	var marks []models.PhotoMark
	if err := s.DB.Preload("User").
		Where("photo_id IN ?", photoIDs).
		Order("layer asc, updated_at asc, id asc").
		Find(&marks).Error; err != nil {
		return nil, err
	}
	for _, mark := range marks {
		decorations.marksByPhoto[mark.PhotoID] = append(decorations.marksByPhoto[mark.PhotoID], s.photoMarkJSON(mark))
		if viewerID != 0 && mark.UserID == viewerID {
			decorations.myMarked[mark.PhotoID] = true
		}
	}

	var paints []models.PhotoPaint
	if err := s.DB.Preload("User").
		Where("photo_id IN ?", photoIDs).
		Order("updated_at asc, id asc").
		Find(&paints).Error; err != nil {
		return nil, err
	}
	for _, paint := range paints {
		decorations.paintsByPhoto[paint.PhotoID] = append(decorations.paintsByPhoto[paint.PhotoID], s.photoPaintJSON(paint))
		if viewerID != 0 && paint.UserID == viewerID {
			decorations.myPainted[paint.PhotoID] = true
		}
	}
	return decorations, nil
}

func formatLocationDisplay(lat float64, lon float64) string {
	return fmt.Sprintf("%.6f, %.6f", lat, lon)
}

func googleMapsLocationURL(lat float64, lon float64) string {
	return fmt.Sprintf("https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", lat, lon)
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
		"id":                                  u.ID,
		"username":                            u.Username,
		"isAdmin":                             u.IsAdmin,
		"favoriteColor":                       defaultColor(u.FavoriteColor),
		"chatPushEnabled":                     u.ChatPushEnabled,
		"pollPushEnabled":                     u.PollPushEnabled,
		"specialMomentPushEnabled":            u.SpecialMomentPushEnabled,
		"inviteRegistrationPushEnabled":       u.InviteRegistrationPushEnabled,
		"photoReactionPushEnabled":            u.PhotoReactionPushEnabled,
		"photoFotomojiPushEnabled":            u.PhotoFotomojiPushEnabled,
		"photoCommentPushEnabled":             u.PhotoCommentPushEnabled,
		"bookmarkedPhotoPushEnabled":          u.BookmarkedPhotoPushEnabled,
		"postChangePushEnabled":               u.PostChangePushEnabled,
		"autoSubscribeInteractedPostsEnabled": u.AutoSubscribeInteractedPostsEnabled,
		"ownPostNumberInPushEnabled":          u.OwnPostNumberInPushEnabled,
		"postNumberInPushEnabled":             u.PostNumberInPushEnabled,
		"yoloModeEnabled":                     u.YoloModeEnabled,
		"mediaDataMode":                       normalizeMediaDataMode(u.MediaDataMode),
		"mediaFormatPreference":               normalizeMediaFormatPreference(u.MediaFormatPreference),
		"allowPhotoDownload":                  u.AllowPhotoDownload,
		"allowCommunityNsfwMarking":           u.AllowCommunityNsfwMarking,
		"showNsfwByDefault":                   u.ShowNsfwByDefault,
		"creativePostMode":                    normalizeCreativePostMode(u.CreativePostMode),
		"locationFeatureEnabled":              u.LocationFeatureEnabled,
		"locationShareDefaultEnabled":         u.LocationShareDefaultEnabled,
		"avatarUrl":                           avatarURL,
		"bio":                                 strings.TrimSpace(u.Bio),
		"statusText":                          strings.TrimSpace(u.StatusText),
		"statusEmoji":                         strings.TrimSpace(u.StatusEmoji),
		"statusExpiresAt":                     u.StatusExpiresAt,
		"profileVisible":                      u.ProfileVisible,
		"avatarVisible":                       u.AvatarVisible,
		"bioVisible":                          u.BioVisible,
		"statusVisible":                       u.StatusVisible,
		"quietHoursEnabled":                   u.QuietHoursEnabled,
		"quietHoursStart":                     defaultIfBlank(u.QuietHoursStart, "22:00"),
		"quietHoursEnd":                       defaultIfBlank(u.QuietHoursEnd, "07:00"),
		"diagnosticsConsentGranted":           u.DiagnosticsConsentGranted,
		"diagnosticsConsentUpdatedAt":         u.DiagnosticsConsentUpdatedAt,
		"diagnosticsConsentSource":            strings.TrimSpace(u.DiagnosticsConsentSource),
		"createdAt":                           u.CreatedAt,
	}
}

func (s *Server) userPublicJSON(viewerID uint, u models.User) gin.H {
	own := viewerID == u.ID
	out := gin.H{
		"id":                                  u.ID,
		"username":                            u.Username,
		"isAdmin":                             u.IsAdmin,
		"favoriteColor":                       defaultColor(u.FavoriteColor),
		"chatPushEnabled":                     false,
		"pollPushEnabled":                     false,
		"specialMomentPushEnabled":            false,
		"inviteRegistrationPushEnabled":       false,
		"photoReactionPushEnabled":            false,
		"photoFotomojiPushEnabled":            false,
		"photoCommentPushEnabled":             false,
		"bookmarkedPhotoPushEnabled":          false,
		"postChangePushEnabled":               false,
		"autoSubscribeInteractedPostsEnabled": false,
		"ownPostNumberInPushEnabled":          false,
		"postNumberInPushEnabled":             false,
		"yoloModeEnabled":                     false,
		"mediaDataMode":                       "normal",
		"mediaFormatPreference":               "auto",
		"allowPhotoDownload":                  u.AllowPhotoDownload,
		"allowCommunityNsfwMarking":           false,
		"showNsfwByDefault":                   false,
		"creativePostMode":                    normalizeCreativePostMode(u.CreativePostMode),
		"locationFeatureEnabled":              false,
		"locationShareDefaultEnabled":         false,
		"avatarUrl":                           "",
		"bio":                                 "",
		"statusText":                          "",
		"statusEmoji":                         "",
		"statusExpiresAt":                     nil,
		"profileVisible":                      false,
		"avatarVisible":                       false,
		"bioVisible":                          false,
		"statusVisible":                       false,
		"quietHoursEnabled":                   false,
		"quietHoursStart":                     "22:00",
		"quietHoursEnd":                       "07:00",
		"createdAt":                           u.CreatedAt,
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
	if err := s.DB.Preload("User").Where("user_id = ?", targetID).Order("created_at desc").Limit(120).Find(&photos).Error; err != nil {
		return nil, err
	}
	sortPhotosForFeed(photos)
	now := time.Now().In(s.Location)
	photoIDs := make([]uint, 0, len(photos))
	for _, p := range photos {
		photoIDs = append(photoIDs, p.ID)
	}
	decorations, err := s.photoDecorationsForViewer(viewerID, photoIDs)
	if err != nil {
		return nil, err
	}
	out := make([]gin.H, 0, len(photos))
	for _, p := range photos {
		locked := p.CapsuleVisibleAt != nil && now.Before(*p.CapsuleVisibleAt)
		if locked {
			row := s.profilePhotoJSONForViewer(viewerID, p, locked, decorations)
			out = append(out, row)
			continue
		}
		out = append(out, s.profilePhotoJSONForViewer(viewerID, p, false, decorations))
	}
	return out, nil
}

func (s *Server) profilePhotoJSON(p models.Photo, locked bool) gin.H {
	return s.profilePhotoJSONForViewer(0, p, locked, nil)
}

func (s *Server) profilePhotoJSONForViewer(viewerID uint, p models.Photo, locked bool, decorations *viewerPhotoDecorations) gin.H {
	row := s.photoJSONForViewer(viewerID, p, decorations)
	row["capsulePrivate"] = false
	row["capsuleLocked"] = locked
	if !locked {
		return row
	}
	row["secondUrl"] = ""
	row["thumbnailUrl"] = ""
	row["secondThumbnailUrl"] = ""
	row["media"] = []gin.H{}
	row["mediaCount"] = 0

	previewPath := strings.TrimSpace(p.CapsulePreviewPath)
	if previewPath == "" {
		if generatedPath, err := s.ensureCapsulePreview(p.FilePath); err == nil {
			previewPath = generatedPath
			_ = s.DB.Model(&models.Photo{}).Where("id = ?", p.ID).Update("capsule_preview_path", generatedPath).Error
		}
	}
	if previewPath != "" {
		row["capsulePreviewUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, previewPath)
		row["thumbnailUrl"] = row["capsulePreviewUrl"]
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
	return s.userHasPostedForMomentDay(userID, day, "daily", models.DailyPrompt{})
}

func normalizePhotoMomentKind(kind string) string {
	switch strings.TrimSpace(strings.ToLower(kind)) {
	case "special":
		return "special"
	case "daily":
		return "daily"
	default:
		return ""
	}
}

func (s *Server) promptMomentKindForDay(day string) string {
	var prompt models.DailyPrompt
	if err := s.DB.Where("day = ?", day).First(&prompt).Error; err != nil {
		return "daily"
	}
	return momentKindFromTriggerSource(prompt.TriggerSource)
}

func (s *Server) userHasPostedForMomentDay(userID uint, day string, momentKind string, prompt models.DailyPrompt) (bool, error) {
	var photo models.Photo
	err := s.ownPromptPhotoForMomentDay(userID, day, momentKind, prompt, &photo)
	if err == nil {
		return true, nil
	}
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return false, nil
	}
	return false, err
}

func (s *Server) ownPromptPhotoForMomentDay(userID uint, day string, momentKind string, prompt models.DailyPrompt, out *models.Photo) error {
	momentKind = normalizePhotoMomentKind(momentKind)
	if momentKind == "" {
		momentKind = "daily"
	}

	query := s.DB.Where("user_id = ? AND day = ? AND prompt_only = ?", userID, day, true)
	switch momentKind {
	case "special":
		if prompt.Day == "" {
			_ = s.DB.Where("day = ?", day).First(&prompt).Error
		}
		if prompt.TriggeredAt != nil && prompt.UploadUntil != nil {
			query = query.Where("(moment_kind = ? OR (moment_kind = '' AND created_at >= ? AND created_at <= ?))", "special", prompt.TriggeredAt, prompt.UploadUntil)
		} else {
			query = query.Where("moment_kind = ?", "special")
		}
	default:
		if prompt.Day == "" {
			_ = s.DB.Where("day = ?", day).First(&prompt).Error
		}
		if prompt.TriggeredAt != nil && prompt.UploadUntil != nil {
			query = query.Where("(moment_kind = ? OR (moment_kind = '' AND created_at >= ? AND created_at <= ?))", "daily", prompt.TriggeredAt, prompt.UploadUntil)
		} else {
			query = query.Where("moment_kind = ?", "daily")
		}
	}
	return query.Order("created_at desc").First(out).Error
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

func (s *Server) latestAppendablePhotoForDay(userID uint, day string, now time.Time) (models.Photo, bool, error) {
	var photos []models.Photo
	if err := s.DB.Where("user_id = ? AND day = ?", userID, day).Order("created_at desc").Find(&photos).Error; err != nil {
		return models.Photo{}, false, err
	}
	sortPhotosForFeed(photos)
	for _, photo := range photos {
		if photoVisibleToViewer(userID, photo, now) {
			return photo, true, nil
		}
	}
	return models.Photo{}, false, nil
}

func (s *Server) photoMediaCount(photo models.Photo) int {
	count := 0
	if strings.TrimSpace(photo.FilePath) != "" {
		count++
	}
	if strings.TrimSpace(photo.SecondPath) != "" {
		count++
	}
	var attachmentCount int64
	if err := s.DB.Model(&models.PhotoAttachment{}).Where("photo_id = ?", photo.ID).Count(&attachmentCount).Error; err == nil {
		count += int(attachmentCount)
	}
	return count
}

func (s *Server) remainingPostMediaSlots(photo models.Photo, settings models.AppSettings) (int, bool) {
	if settings.PostMediaUnlimited {
		return 0, true
	}
	remaining := settings.PostMediaMaxCount - s.photoMediaCount(photo)
	if remaining < 0 {
		remaining = 0
	}
	return remaining, false
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

type photoFotomojiPreviewRow struct {
	PhotoID       uint
	ID            uint
	Emoji         string
	FilePath      string
	CreatedAt     time.Time
	UserID        uint
	Username      string
	FavoriteColor string
}

func (s *Server) loadPhotoForInteraction(photoID uint) (models.Photo, error) {
	var photo models.Photo
	if err := s.DB.First(&photo, photoID).Error; err != nil {
		return models.Photo{}, err
	}
	return photo, nil
}

func (s *Server) feedInteractionPreview(photoIDs []uint) (map[uint][]gin.H, map[uint][]gin.H, map[uint][]gin.H, map[uint]gin.H, int, error) {
	reactionByPhoto := make(map[uint][]gin.H)
	commentByPhoto := make(map[uint][]gin.H)
	photoMojiByPhoto := make(map[uint][]gin.H)
	countsByPhoto := make(map[uint]gin.H)
	if len(photoIDs) == 0 {
		return reactionByPhoto, commentByPhoto, photoMojiByPhoto, countsByPhoto, 0, nil
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
		return nil, nil, nil, nil, 0, err
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
		return nil, nil, nil, nil, 0, err
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

	fotomojiLimit := 40
	fotomojiRows := make([]photoFotomojiPreviewRow, 0, len(photoIDs)*8)
	fotomojiQueryStart := time.Now()
	if err := s.DB.Raw(`
		SELECT photo_id, id, emoji, file_path, created_at, user_id, username, favorite_color
		FROM (
			SELECT
				pf.photo_id AS photo_id,
				pf.id AS id,
				pf.emoji AS emoji,
				pf.file_path AS file_path,
				pf.created_at AS created_at,
				u.id AS user_id,
				u.username AS username,
				u.favorite_color AS favorite_color,
				ROW_NUMBER() OVER (PARTITION BY pf.photo_id ORDER BY pf.created_at DESC, pf.id DESC) AS rn
			FROM photo_fotomojis pf
			JOIN users u ON u.id = pf.user_id
			WHERE pf.photo_id IN ?
		)
		WHERE rn <= ?
		ORDER BY created_at ASC, id ASC
	`, photoIDs, fotomojiLimit).Scan(&fotomojiRows).Error; err != nil {
		return nil, nil, nil, nil, 0, err
	}
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/feed", "feed_fotomoji_preview_query", time.Since(fotomojiQueryStart))
	}
	for _, item := range fotomojiRows {
		photoMojiByPhoto[item.PhotoID] = append(photoMojiByPhoto[item.PhotoID], gin.H{
			"id":        item.ID,
			"emoji":     item.Emoji,
			"url":       s.avatarURL(item.FilePath),
			"createdAt": item.CreatedAt,
			"user": gin.H{
				"id":            item.UserID,
				"username":      item.Username,
				"favoriteColor": defaultColor(item.FavoriteColor),
			},
		})
	}

	type interactionCountRow struct {
		PhotoID uint
		Count   int64
	}
	commentCounts := make(map[uint]int64, len(photoIDs))
	reactionCounts := make(map[uint]int64, len(photoIDs))
	photoMojiCounts := make(map[uint]int64, len(photoIDs))

	var commentCountRows []interactionCountRow
	if err := s.DB.Model(&models.PhotoComment{}).
		Select("photo_id as photo_id, COUNT(*) as count").
		Where("photo_id IN ?", photoIDs).
		Group("photo_id").
		Scan(&commentCountRows).Error; err != nil {
		return nil, nil, nil, nil, 0, err
	}
	for _, row := range commentCountRows {
		commentCounts[row.PhotoID] = row.Count
	}

	var reactionCountRows []interactionCountRow
	if err := s.DB.Model(&models.PhotoReaction{}).
		Select("photo_id as photo_id, COUNT(*) as count").
		Where("photo_id IN ?", photoIDs).
		Group("photo_id").
		Scan(&reactionCountRows).Error; err != nil {
		return nil, nil, nil, nil, 0, err
	}
	for _, row := range reactionCountRows {
		reactionCounts[row.PhotoID] = row.Count
	}

	var photoMojiCountRows []interactionCountRow
	if err := s.DB.Model(&models.PhotoFotomoji{}).
		Select("photo_id as photo_id, COUNT(*) as count").
		Where("photo_id IN ?", photoIDs).
		Group("photo_id").
		Scan(&photoMojiCountRows).Error; err != nil {
		return nil, nil, nil, nil, 0, err
	}
	for _, row := range photoMojiCountRows {
		photoMojiCounts[row.PhotoID] = row.Count
	}

	for _, photoID := range photoIDs {
		countsByPhoto[photoID] = gin.H{
			"comments":   commentCounts[photoID],
			"reactions":  reactionCounts[photoID],
			"photoMojis": photoMojiCounts[photoID],
		}
	}

	return reactionByPhoto, commentByPhoto, photoMojiByPhoto, countsByPhoto, commentLimit, nil
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

func (s *Server) extraUploadOfflineGraceAllowed(day string, now time.Time, capturedAt *time.Time) bool {
	if capturedAt == nil || capturedAt.IsZero() {
		return false
	}
	capturedLocal := capturedAt.In(s.Location)
	if capturedLocal.After(now) || now.Sub(capturedLocal) > 24*time.Hour {
		return false
	}
	var prompt models.DailyPrompt
	if err := s.DB.Where("day = ?", day).First(&prompt).Error; err != nil || prompt.TriggeredAt == nil {
		return false
	}
	return capturedLocal.Before(prompt.TriggeredAt.In(s.Location))
}

func (s *Server) isPromptUploadAllowed(day string, now time.Time) bool {
	var prompt models.DailyPrompt
	if err := s.DB.Where("day = ?", day).First(&prompt).Error; err != nil {
		return false
	}
	return isPromptWindowActive(prompt, now)
}

func (s *Server) promptUploadBlockedCode(day string, effectiveAt time.Time) string {
	var prompt models.DailyPrompt
	if err := s.DB.Where("day = ?", day).First(&prompt).Error; err != nil {
		return "prompt_inactive"
	}
	if prompt.TriggeredAt == nil || prompt.UploadUntil == nil {
		return "prompt_inactive"
	}
	if effectiveAt.After(*prompt.UploadUntil) {
		return "upload_window_closed"
	}
	return "prompt_inactive"
}

func (s *Server) resolvePromptUploadDecision(defaultDay string, now time.Time, capturedAt *time.Time) (string, bool, bool, string) {
	if s.isPromptUploadAllowed(defaultDay, now) {
		return defaultDay, true, false, ""
	}
	blockedCode := s.promptUploadBlockedCode(defaultDay, now)
	if capturedAt == nil || capturedAt.IsZero() {
		return defaultDay, false, false, blockedCode
	}
	capturedLocal := capturedAt.In(s.Location)
	if now.Sub(capturedLocal) > 7*24*time.Hour {
		return defaultDay, false, false, blockedCode
	}
	capturedDay := capturedLocal.Format("2006-01-02")
	if s.isPromptUploadAllowed(capturedDay, capturedLocal) {
		return capturedDay, true, true, ""
	}
	return defaultDay, false, false, s.promptUploadBlockedCode(capturedDay, capturedLocal)
}

type dayTriggerStatus struct {
	DailyTriggeredAt            *time.Time
	DailyPending                bool
	SpecialTriggeredAt          *time.Time
	SpecialRequestedByUser      string
	SpecialRequestedByUserColor string
}

func (s *Server) currentDayTriggerStatus(day string, route string) (dayTriggerStatus, error) {
	status := dayTriggerStatus{DailyPending: true}
	type row struct {
		OccurredAt    time.Time `gorm:"column:occurred_at"`
		Source        string    `gorm:"column:source"`
		ActorUsername string    `gorm:"column:actor_username"`
		FavoriteColor string    `gorm:"column:favorite_color"`
	}
	rows := make([]row, 0, 8)
	queryStart := time.Now()
	err := s.DB.
		Table("daily_trigger_audit_events dtae").
		Select("dtae.occurred_at, dtae.source, dtae.actor_username, u.favorite_color").
		Joins("LEFT JOIN users u ON u.id = dtae.actor_user_id").
		Where("day = ? AND result = ?", day, "triggered").
		Order("occurred_at asc").
		Find(&rows).Error
	if s.Monitor != nil && route != "" {
		s.Monitor.RecordDBQuery(route, "prompt_day_trigger_status_query", time.Since(queryStart))
	}
	if err != nil {
		return status, err
	}
	for _, item := range rows {
		switch triggerKindFromTriggerSource(item.Source) {
		case "special":
			when := item.OccurredAt
			status.SpecialTriggeredAt = &when
			if name := strings.TrimSpace(item.ActorUsername); name != "" {
				status.SpecialRequestedByUser = name
			}
			status.SpecialRequestedByUserColor = defaultColor(item.FavoriteColor)
		default:
			when := item.OccurredAt
			status.DailyTriggeredAt = &when
			status.DailyPending = false
		}
	}
	return status, nil
}

func isPromptWindowActive(prompt models.DailyPrompt, now time.Time) bool {
	if prompt.TriggeredAt == nil || prompt.UploadUntil == nil {
		return false
	}
	return !now.Before(*prompt.TriggeredAt) && !now.After(*prompt.UploadUntil)
}

func momentKindFromTriggerSource(triggerSource string) string {
	return triggerKindFromTriggerSource(triggerSource)
}

func triggerKindFromTriggerSource(triggerSource string) string {
	switch strings.TrimSpace(strings.ToLower(triggerSource)) {
	case "special_request", "chat_command":
		return "special"
	default:
		return "daily"
	}
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
	if s == nil || strings.TrimSpace(day) == "" {
		return
	}
	if s.FeedCache != nil {
		s.FeedCache.InvalidateDay(day)
	}
	s.bumpSyncRevision(feedRevisionScope(day))
	s.bumpSyncRevision(timelineRevisionScope)
	s.bumpSyncRevision(calendarRevisionScope)
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

type notificationRecipient struct {
	Token                   string
	PostNumberInPushEnabled bool
}

func formatNotificationPostReference(photo models.Photo) string {
	number := photoPublicNumberValue(photo)
	if number == "" {
		return ""
	}
	return " #" + number
}

type promptUploadWindow struct {
	TriggeredAt time.Time
	UploadUntil time.Time
}

func invalidPromptOnlyPhotoIDs(photos []models.Photo, promptByDay map[string]models.DailyPrompt, auditWindowsByDay map[string][]promptUploadWindow) []uint {
	ids := make([]uint, 0)
	for _, photo := range photos {
		if normalizePhotoMomentKind(photo.MomentKind) != "" {
			continue
		}
		effectiveAt := photoEffectiveTime(photo)
		prompt, ok := promptByDay[photo.Day]
		if ok && prompt.TriggeredAt != nil && prompt.UploadUntil != nil &&
			!effectiveAt.Before(*prompt.TriggeredAt) && !effectiveAt.After(*prompt.UploadUntil) {
			continue
		}
		validAuditWindow := false
		for _, window := range auditWindowsByDay[photo.Day] {
			if !effectiveAt.Before(window.TriggeredAt) && !effectiveAt.After(window.UploadUntil) {
				validAuditWindow = true
				break
			}
		}
		if validAuditWindow {
			continue
		}
		ids = append(ids, photo.ID)
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

	var settings models.AppSettings
	_ = s.DB.First(&settings).Error
	settings = normalizeSettings(settings)
	var audits []models.DailyTriggerAuditEvent
	if err := s.DB.
		Where("day IN ? AND result = ?", days, "triggered").
		Find(&audits).Error; err != nil {
		return 0, err
	}
	auditWindowsByDay := make(map[string][]promptUploadWindow, len(days))
	for _, audit := range audits {
		triggeredAt := audit.OccurredAt
		auditWindowsByDay[audit.Day] = append(auditWindowsByDay[audit.Day], promptUploadWindow{
			TriggeredAt: triggeredAt,
			UploadUntil: triggeredAt.Add(time.Duration(settings.UploadWindowMinutes) * time.Minute),
		})
	}

	invalidIDs := invalidPromptOnlyPhotoIDs(photos, promptByDay, auditWindowsByDay)
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

func parseLocationForm(c *gin.Context) (bool, *float64, *float64, error) {
	shared := parseFormBool(c.PostForm("location_shared"))
	latRaw := strings.TrimSpace(c.PostForm("location_latitude"))
	lonRaw := strings.TrimSpace(c.PostForm("location_longitude"))
	if !shared {
		return false, nil, nil, nil
	}
	if latRaw == "" || lonRaw == "" {
		return false, nil, nil, nil
	}
	lat, err := strconv.ParseFloat(latRaw, 64)
	if err != nil {
		return false, nil, nil, errors.New("invalid location_latitude")
	}
	lon, err := strconv.ParseFloat(lonRaw, 64)
	if err != nil {
		return false, nil, nil, errors.New("invalid location_longitude")
	}
	if lat < -90 || lat > 90 {
		return false, nil, nil, errors.New("location_latitude out of range")
	}
	if lon < -180 || lon > 180 {
		return false, nil, nil, errors.New("location_longitude out of range")
	}
	return true, &lat, &lon, nil
}

func parseQueryBool(v string, fallback bool) bool {
	raw := strings.ToLower(strings.TrimSpace(v))
	switch raw {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		return fallback
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

	var myPhotoMoji models.PhotoFotomoji
	myPhotoMojiOut := any(nil)
	if err := s.DB.Preload("User").Where("photo_id = ? AND user_id = ?", photoID, viewerID).First(&myPhotoMoji).Error; err == nil {
		myPhotoMojiOut = s.photoFotomojiJSON(myPhotoMoji, true)
	}

	var comments []models.PhotoComment
	if err := s.DB.Preload("User").
		Where("photo_id = ?", photoID).
		Order("created_at asc").
		Limit(200).
		Find(&comments).Error; err != nil {
		return nil, err
	}

	var photoMojis []models.PhotoFotomoji
	if err := s.DB.Preload("User").
		Where("photo_id = ?", photoID).
		Order("created_at asc").
		Limit(200).
		Find(&photoMojis).Error; err != nil {
		return nil, err
	}

	reactions := make([]gin.H, 0, len(reactionRows))
	reactionTotal := 0
	for _, row := range reactionRows {
		reactionTotal += int(row.Count)
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

	photoMojiItems := make([]gin.H, 0, len(photoMojis))
	for _, item := range photoMojis {
		photoMojiItems = append(photoMojiItems, s.photoFotomojiJSON(item, true))
	}

	return gin.H{
		"photoId":     photoID,
		"reactions":   reactions,
		"myReaction":  myReaction,
		"comments":    commentItems,
		"photoMojis":  photoMojiItems,
		"myPhotoMoji": myPhotoMojiOut,
		"canDownload": canDownload,
		"counts": gin.H{
			"comments":   len(comments),
			"reactions":  reactionTotal,
			"photoMojis": len(photoMojis),
		},
		"full": true,
	}, nil
}

func (s *Server) photoInteractionsLightPayload(photo models.Photo, commenter models.User) (gin.H, error) {
	photoID := photo.ID
	var (
		reactionsTotal  int64
		photoMojisTotal int64
		commentsTotal   int64
	)
	if err := s.DB.Model(&models.PhotoReaction{}).Where("photo_id = ?", photoID).Count(&reactionsTotal).Error; err != nil {
		return nil, err
	}
	if err := s.DB.Model(&models.PhotoFotomoji{}).Where("photo_id = ?", photoID).Count(&photoMojisTotal).Error; err != nil {
		return nil, err
	}
	if err := s.DB.Model(&models.PhotoComment{}).Where("photo_id = ?", photoID).Count(&commentsTotal).Error; err != nil {
		return nil, err
	}
	var my models.PhotoReaction
	myReaction := ""
	_ = s.DB.Where("photo_id = ? AND user_id = ?", photoID, commenter.ID).First(&my).Error
	if my.ID != 0 {
		myReaction = my.Emoji
	}
	canDownload := false
	var owner models.User
	if err := s.DB.Select("id", "allow_photo_download").First(&owner, photo.UserID).Error; err == nil {
		canDownload = owner.AllowPhotoDownload
	}

	return gin.H{
		"photoId":     photoID,
		"reactions":   []gin.H{},
		"photoMojis":  []gin.H{},
		"comments":    []gin.H{},
		"myReaction":  myReaction,
		"myPhotoMoji": nil,
		"canDownload": canDownload,
		"counts": gin.H{
			"reactions":  reactionsTotal,
			"photoMojis": photoMojisTotal,
			"comments":   commentsTotal,
		},
		"commentCreated": gin.H{
			"user": gin.H{
				"id":            commenter.ID,
				"username":      commenter.Username,
				"favoriteColor": defaultColor(commenter.FavoriteColor),
			},
			"createdAt": time.Now().In(s.Location),
		},
		"full": false,
	}, nil
}

func (s *Server) photoCommentJSON(comment models.PhotoComment) gin.H {
	return gin.H{
		"id":        comment.ID,
		"body":      comment.Body,
		"createdAt": comment.CreatedAt,
		"user": gin.H{
			"id":            comment.User.ID,
			"username":      comment.User.Username,
			"favoriteColor": defaultColor(comment.User.FavoriteColor),
		},
	}
}

func normalizePhotoCommentBodyForDedupe(v string) string {
	return strings.TrimSpace(v)
}

func (s *Server) findPhotoCommentByClientID(userID uint, clientCommentID string) (models.PhotoComment, bool, error) {
	clientCommentID = strings.TrimSpace(clientCommentID)
	if clientCommentID == "" {
		return models.PhotoComment{}, false, nil
	}
	var existing models.PhotoComment
	err := s.DB.Preload("User").
		Where("user_id = ? AND client_comment_id = ?", userID, clientCommentID).
		First(&existing).Error
	if err == nil {
		return existing, true, nil
	}
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return models.PhotoComment{}, false, nil
	}
	return models.PhotoComment{}, false, err
}

func (s *Server) photoCommentMutationPayload(photo models.Photo, viewerID uint, mutationType string, comment *models.PhotoComment, deletedCommentID uint, deduplicated bool) (gin.H, error) {
	out, err := s.photoInteractionsPayload(photo, viewerID)
	if err != nil {
		return nil, err
	}
	var (
		commentCount   int64
		reactionCount  int64
		photoMojiCount int64
	)
	if err := s.DB.Model(&models.PhotoComment{}).Where("photo_id = ?", photo.ID).Count(&commentCount).Error; err != nil {
		return nil, err
	}
	if err := s.DB.Model(&models.PhotoReaction{}).Where("photo_id = ?", photo.ID).Count(&reactionCount).Error; err != nil {
		return nil, err
	}
	if err := s.DB.Model(&models.PhotoFotomoji{}).Where("photo_id = ?", photo.ID).Count(&photoMojiCount).Error; err != nil {
		return nil, err
	}
	out["counts"] = gin.H{
		"comments":   commentCount,
		"reactions":  reactionCount,
		"photoMojis": photoMojiCount,
	}
	out["full"] = true
	out["deletedCommentId"] = deletedCommentID
	if comment != nil {
		out["comment"] = s.photoCommentJSON(*comment)
	}
	out["mutation"] = gin.H{
		"type":         mutationType,
		"deduplicated": deduplicated,
	}
	return out, nil
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

func (s *Server) pollNotificationTokens(senderID uint) []string {
	var rows []models.DeviceToken
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Where("users.id <> ? AND users.poll_push_enabled = ?", senderID, true).
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

func (s *Server) specialMomentNotificationTokens(requesterID uint) []string {
	var rows []models.DeviceToken
	query := s.DB.Table("device_tokens").
		Select("device_tokens.token").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Where("users.special_moment_push_enabled = ?", true)
	if requesterID > 0 {
		query = query.Where("users.id <> ?", requesterID)
	}
	_ = query.Find(&rows).Error
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

func (s *Server) reactionNotificationRecipients(ownerID, actorID uint) []notificationRecipient {
	var rows []struct {
		Token                   string
		PostNumberInPushEnabled bool
	}
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token, users.own_post_number_in_push_enabled AS post_number_in_push_enabled").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Where("users.id = ? AND users.photo_reaction_push_enabled = ? AND users.id <> ?", ownerID, true, actorID).
		Find(&rows).Error
	recipients := make([]notificationRecipient, 0, len(rows))
	for _, row := range rows {
		recipients = append(recipients, notificationRecipient{
			Token:                   row.Token,
			PostNumberInPushEnabled: row.PostNumberInPushEnabled,
		})
	}
	return recipients
}

func (s *Server) fotomojiNotificationRecipients(ownerID, actorID uint) []notificationRecipient {
	var rows []struct {
		Token                   string
		PostNumberInPushEnabled bool
	}
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token, users.own_post_number_in_push_enabled AS post_number_in_push_enabled").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Where("users.id = ? AND users.id <> ? AND (users.photo_fotomoji_push_enabled = ? OR users.photo_reaction_push_enabled = ?)", ownerID, actorID, true, true).
		Find(&rows).Error
	recipients := make([]notificationRecipient, 0, len(rows))
	for _, row := range rows {
		recipients = append(recipients, notificationRecipient{
			Token:                   row.Token,
			PostNumberInPushEnabled: row.PostNumberInPushEnabled,
		})
	}
	return recipients
}

func (s *Server) commentNotificationRecipients(ownerID, actorID uint) []notificationRecipient {
	var rows []struct {
		Token                   string
		PostNumberInPushEnabled bool
	}
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token, users.own_post_number_in_push_enabled AS post_number_in_push_enabled").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Where("users.id = ? AND users.photo_comment_push_enabled = ? AND users.id <> ?", ownerID, true, actorID).
		Find(&rows).Error
	recipients := make([]notificationRecipient, 0, len(rows))
	for _, row := range rows {
		recipients = append(recipients, notificationRecipient{
			Token:                   row.Token,
			PostNumberInPushEnabled: row.PostNumberInPushEnabled,
		})
	}
	return recipients
}

func (s *Server) bookmarkedPhotoNotificationRecipients(photoID, ownerID, actorID uint) []notificationRecipient {
	var rows []struct {
		Token                   string
		PostNumberInPushEnabled bool
	}
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token, users.post_number_in_push_enabled").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Joins("JOIN photo_bookmarks ON photo_bookmarks.user_id = users.id").
		Where("photo_bookmarks.photo_id = ? AND photo_bookmarks.active = ? AND users.bookmarked_photo_push_enabled = ? AND users.id <> ? AND users.id <> ?", photoID, true, true, ownerID, actorID).
		Find(&rows).Error
	recipients := make([]notificationRecipient, 0, len(rows))
	for _, row := range rows {
		recipients = append(recipients, notificationRecipient{
			Token:                   row.Token,
			PostNumberInPushEnabled: row.PostNumberInPushEnabled,
		})
	}
	return recipients
}

func (s *Server) ownPostChangeNotificationRecipients(ownerID, actorID uint) []notificationRecipient {
	var rows []struct {
		Token                   string
		PostNumberInPushEnabled bool
	}
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token, users.own_post_number_in_push_enabled AS post_number_in_push_enabled").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Where("users.id = ? AND users.post_change_push_enabled = ? AND users.id <> ?", ownerID, true, actorID).
		Find(&rows).Error
	recipients := make([]notificationRecipient, 0, len(rows))
	for _, row := range rows {
		recipients = append(recipients, notificationRecipient{
			Token:                   row.Token,
			PostNumberInPushEnabled: row.PostNumberInPushEnabled,
		})
	}
	return recipients
}

func (s *Server) bookmarkedPostChangeNotificationRecipients(photoID, ownerID, actorID uint) []notificationRecipient {
	var rows []struct {
		Token                   string
		PostNumberInPushEnabled bool
	}
	_ = s.DB.Table("device_tokens").
		Select("device_tokens.token, users.post_number_in_push_enabled").
		Joins("JOIN users ON users.id = device_tokens.user_id").
		Joins("JOIN photo_bookmarks ON photo_bookmarks.user_id = users.id").
		Where("photo_bookmarks.photo_id = ? AND photo_bookmarks.active = ? AND users.post_change_push_enabled = ? AND users.id <> ? AND users.id <> ?", photoID, true, true, ownerID, actorID).
		Find(&rows).Error
	recipients := make([]notificationRecipient, 0, len(rows))
	for _, row := range rows {
		recipients = append(recipients, notificationRecipient{
			Token:                   row.Token,
			PostNumberInPushEnabled: row.PostNumberInPushEnabled,
		})
	}
	return recipients
}

func (s *Server) sendPhotoNotification(recipients []notificationRecipient, title string, baseBody string, messageType string, photo models.Photo, notificationKey string) {
	if len(recipients) == 0 {
		return
	}
	withPostNumber := make([]string, 0, len(recipients))
	withoutPostNumber := make([]string, 0, len(recipients))
	postRef := formatNotificationPostReference(photo)
	for _, recipient := range recipients {
		if strings.TrimSpace(recipient.Token) == "" {
			continue
		}
		if recipient.PostNumberInPushEnabled && postRef != "" {
			withPostNumber = append(withPostNumber, recipient.Token)
		} else {
			withoutPostNumber = append(withoutPostNumber, recipient.Token)
		}
	}
	sendGroup := func(tokens []string, body string) {
		if len(tokens) == 0 {
			return
		}
		sendResult, sendErr := s.Notifier.Send(tokens, notify.Message{
			Title:           title,
			Body:            body,
			Type:            messageType,
			Action:          "open_feed",
			Day:             photo.Day,
			PhotoID:         int64(photo.ID),
			NotificationKey: notificationKey,
		})
		s.recordPushResult(sendResult, sendErr)
		s.removeInvalidTokens(sendResult.InvalidTokens)
	}
	sendGroup(withoutPostNumber, baseBody)
	if len(withPostNumber) > 0 {
		sendGroup(withPostNumber, baseBody+postRef)
	}
}

func photoCommentNotificationKey(commentID uint) string {
	if commentID == 0 {
		return ""
	}
	return fmt.Sprintf("comment:%d", commentID)
}

func (s *Server) notifyPhotoReaction(actor models.User, photo models.Photo) {
	now := time.Now().In(s.Location)
	if photo.CapsuleVisibleAt != nil && now.Before(*photo.CapsuleVisibleAt) {
		return
	}
	if photo.UserID != 0 && photo.UserID != actor.ID {
		body := fmt.Sprintf("%s hat auf deinen Beitrag reagiert", actor.Username)
		recipients := s.reactionNotificationRecipients(photo.UserID, actor.ID)
		s.sendPhotoNotification(recipients, "Neue Reaktion", body, "photo_reaction", photo, "")
	}
	body := fmt.Sprintf("%s hat auf einen gemerkten Beitrag reagiert", actor.Username)
	bookmarkRecipients := s.bookmarkedPhotoNotificationRecipients(photo.ID, photo.UserID, actor.ID)
	s.sendPhotoNotification(bookmarkRecipients, "Aktivitaet auf gemerktem Beitrag", body, "bookmarked_photo_reaction", photo, "")
}

func (s *Server) notifyPhotoFotomoji(actor models.User, photo models.Photo) {
	now := time.Now().In(s.Location)
	if photo.CapsuleVisibleAt != nil && now.Before(*photo.CapsuleVisibleAt) {
		return
	}
	if photo.UserID != 0 && photo.UserID != actor.ID {
		body := fmt.Sprintf("%s hat mit einem Foto auf deinen Beitrag reagiert", actor.Username)
		recipients := s.fotomojiNotificationRecipients(photo.UserID, actor.ID)
		s.sendPhotoNotification(recipients, "Neue FotoMoji", body, "photo_fotomoji", photo, "")
	}
	body := fmt.Sprintf("%s hat mit einem Foto auf einen gemerkten Beitrag reagiert", actor.Username)
	bookmarkRecipients := s.bookmarkedPhotoNotificationRecipients(photo.ID, photo.UserID, actor.ID)
	s.sendPhotoNotification(bookmarkRecipients, "Aktivitaet auf gemerktem Beitrag", body, "bookmarked_photo_fotomoji", photo, "")
}

func (s *Server) notifyPhotoComment(actor models.User, photo models.Photo, comment models.PhotoComment) {
	now := time.Now().In(s.Location)
	if photo.CapsuleVisibleAt != nil && now.Before(*photo.CapsuleVisibleAt) {
		return
	}
	notificationKey := photoCommentNotificationKey(comment.ID)
	if photo.UserID != 0 && photo.UserID != actor.ID {
		body := fmt.Sprintf("%s hat deinen Beitrag kommentiert", actor.Username)
		recipients := s.commentNotificationRecipients(photo.UserID, actor.ID)
		s.sendPhotoNotification(recipients, "Neuer Kommentar", body, "photo_comment", photo, notificationKey)
	}
	body := fmt.Sprintf("%s hat einen gemerkten Beitrag kommentiert", actor.Username)
	bookmarkRecipients := s.bookmarkedPhotoNotificationRecipients(photo.ID, photo.UserID, actor.ID)
	s.sendPhotoNotification(bookmarkRecipients, "Aktivitaet auf gemerktem Beitrag", body, "bookmarked_photo_comment", photo, notificationKey)
}

func (s *Server) cancelPhotoCommentNotification(photo models.Photo, comment models.PhotoComment) {
	notificationKey := photoCommentNotificationKey(comment.ID)
	if notificationKey == "" {
		return
	}
	sendCancel := func(recipients []notificationRecipient) {
		tokens := make([]string, 0, len(recipients))
		for _, recipient := range recipients {
			if strings.TrimSpace(recipient.Token) == "" {
				continue
			}
			tokens = append(tokens, recipient.Token)
		}
		if len(tokens) == 0 {
			return
		}
		sendResult, sendErr := s.Notifier.Send(tokens, notify.Message{
			Type:            "notification_cancel",
			Action:          "cancel_notification",
			Day:             photo.Day,
			PhotoID:         int64(photo.ID),
			NotificationKey: notificationKey,
		})
		s.recordPushResult(sendResult, sendErr)
		s.removeInvalidTokens(sendResult.InvalidTokens)
	}
	if photo.UserID != 0 && photo.UserID != comment.UserID {
		sendCancel(s.commentNotificationRecipients(photo.UserID, comment.UserID))
	}
	sendCancel(s.bookmarkedPhotoNotificationRecipients(photo.ID, photo.UserID, comment.UserID))
}

func (s *Server) notifyPhotoAttachmentAppended(actor models.User, photo models.Photo) {
	body := fmt.Sprintf("%s hat einem gemerkten Beitrag ein weiteres Bild hinzugefuegt", actor.Username)
	recipients := s.bookmarkedPostChangeNotificationRecipients(photo.ID, photo.UserID, actor.ID)
	s.sendPhotoNotification(recipients, "Aenderung an gemerktem Beitrag", body, "bookmarked_photo_media_appended", photo, "")
}

func (s *Server) notifyPhotoNsfwMarked(actor models.User, photo models.Photo) {
	if photo.UserID != 0 && photo.UserID != actor.ID {
		body := fmt.Sprintf("%s hat deinen Beitrag als NSFW markiert", actor.Username)
		recipients := s.ownPostChangeNotificationRecipients(photo.UserID, actor.ID)
		s.sendPhotoNotification(recipients, "NSFW-Hinweis gesetzt", body, "photo_nsfw_marked", photo, "")
	}
	body := fmt.Sprintf("%s hat einen gemerkten Beitrag als NSFW markiert", actor.Username)
	recipients := s.bookmarkedPostChangeNotificationRecipients(photo.ID, photo.UserID, actor.ID)
	s.sendPhotoNotification(recipients, "Aenderung an gemerktem Beitrag", body, "bookmarked_photo_nsfw_marked", photo, "")
}

func (s *Server) notifyPhotoNsfwUnmarked(actor models.User, photo models.Photo) {
	if photo.UserID != 0 && photo.UserID != actor.ID {
		body := fmt.Sprintf("%s hat den NSFW-Hinweis von deinem Beitrag entfernt", actor.Username)
		recipients := s.ownPostChangeNotificationRecipients(photo.UserID, actor.ID)
		s.sendPhotoNotification(recipients, "NSFW-Hinweis entfernt", body, "photo_nsfw_unmarked", photo, "")
	}
	body := fmt.Sprintf("%s hat den NSFW-Hinweis von einem gemerkten Beitrag entfernt", actor.Username)
	recipients := s.bookmarkedPostChangeNotificationRecipients(photo.ID, photo.UserID, actor.ID)
	s.sendPhotoNotification(recipients, "Aenderung an gemerktem Beitrag", body, "bookmarked_photo_nsfw_unmarked", photo, "")
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

func (s *Server) loadOrCreateInviteCode(userID uint) (models.InviteCode, error) {
	var invite models.InviteCode
	queryStart := time.Now()
	err := s.DB.
		Select("id", "user_id", "code", "active", "used_by_id", "created_at", "updated_at").
		Where("user_id = ? AND active = ? AND used_by_id IS NULL", userID, true).
		Order("created_at desc").
		First(&invite).Error
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/me/invite", "my_invite_lookup", time.Since(queryStart))
	}
	if err == nil {
		return invite, nil
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return models.InviteCode{}, err
	}
	createStart := time.Now()
	txErr := s.DB.Transaction(func(tx *gorm.DB) error {
		var txCreateErr error
		invite, txCreateErr = s.createInviteCodeTx(tx, userID)
		return txCreateErr
	})
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery("/api/me/invite", "my_invite_create", time.Since(createStart))
	}
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
	const defaultChatMessageMaxLength = 5000
	const defaultPostMediaMaxCount = 6
	if strings.TrimSpace(settings.ChatCommandValue) == "" {
		settings.ChatCommandValue = "-moment"
	}
	if strings.TrimSpace(settings.ChatCommandPushText) == "" {
		settings.ChatCommandPushText = "Sondermoment von {user}! Jetzt 10 Minuten posten."
	}
	if strings.TrimSpace(settings.ChatCommandEchoText) == "" {
		settings.ChatCommandEchoText = "Sondermoment wurde von {user} angefordert."
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
	if settings.ChatMessageMaxLength <= 0 {
		settings.ChatMessageMaxLength = defaultChatMessageMaxLength
	}
	if settings.PostMediaMaxCount == 0 && !settings.PostMediaUnlimited {
		settings.PostMediaUnlimited = true
	}
	if settings.PostMediaMaxCount <= 0 {
		settings.PostMediaMaxCount = defaultPostMediaMaxCount
	}
	if settings.PerformanceTrackingWindowMinutes < 5 {
		settings.PerformanceTrackingWindowMinutes = 30
	}
	if settings.PerformanceTrackingWindowMinutes > 180 {
		settings.PerformanceTrackingWindowMinutes = 180
	}
	settings.MigrationTargetBaseURL = normalizeMigrationURL(settings.MigrationTargetBaseURL)
	settings.MigrationExpectedSource = normalizeMigrationURL(settings.MigrationExpectedSource)
	settings.MigrationReportTarget = normalizeMigrationURL(settings.MigrationReportTarget)
	settings.MigrationReportSource = normalizeMigrationURL(settings.MigrationReportSource)
	settings.MigrationDownloadURL = strings.TrimSpace(settings.MigrationDownloadURL)
	settings.MigrationPushTitle = defaultIfBlank(settings.MigrationPushTitle, "Daily umgezogen")
	settings.MigrationPushBody = defaultIfBlank(settings.MigrationPushBody, "Bitte aktualisiere Daily und verbinde dich mit dem neuen Server.")
	settings.MigrationScreenTitle = defaultIfBlank(settings.MigrationScreenTitle, "Daily ist umgezogen")
	settings.MigrationScreenBody = defaultIfBlank(settings.MigrationScreenBody, "Diese Instanz ist im Migrationsmodus. Bitte installiere die aktuelle App-Version und trage den neuen Server ein.")
	settings.UserPromptRulesJSON = encodeUserPromptRulesJSON(parseUserPromptRulesJSON(settings.UserPromptRulesJSON))
	return settings
}

func settingsJSON(settings models.AppSettings) gin.H {
	return gin.H{
		"id":                               settings.ID,
		"promptWindowStartHour":            settings.PromptWindowStartHour,
		"promptWindowEndHour":              settings.PromptWindowEndHour,
		"uploadWindowMinutes":              settings.UploadWindowMinutes,
		"feedCommentPreviewLimit":          settings.FeedCommentPreviewLimit,
		"promptNotificationText":           settings.PromptNotificationText,
		"maxUploadBytes":                   settings.MaxUploadBytes,
		"chatMessageMaxLength":             settings.ChatMessageMaxLength,
		"chatMessageUnlimited":             settings.ChatMessageUnlimited,
		"postMediaMaxCount":                settings.PostMediaMaxCount,
		"postMediaUnlimited":               settings.PostMediaUnlimited,
		"chatCommandEnabled":               settings.ChatCommandEnabled,
		"chatCommandValue":                 settings.ChatCommandValue,
		"chatCommandTrigger":               settings.ChatCommandTrigger,
		"chatCommandSendPush":              settings.ChatCommandSendPush,
		"chatCommandPushText":              settings.ChatCommandPushText,
		"chatCommandEchoChat":              settings.ChatCommandEchoChat,
		"chatCommandEchoText":              settings.ChatCommandEchoText,
		"performanceTrackingEnabled":       settings.PerformanceTrackingEnabled,
		"performanceTrackingWindowMinutes": settings.PerformanceTrackingWindowMinutes,
		"performanceTrackingOneShot":       settings.PerformanceTrackingOneShot,
		"migrationEnabled":                 settings.MigrationEnabled,
		"migrationStartedAt":               settings.MigrationStartedAt,
		"migrationUntil":                   settings.MigrationUntil,
		"migrationAutoOffEnabled":          settings.MigrationAutoOffEnabled,
		"migrationTargetBaseUrl":           settings.MigrationTargetBaseURL,
		"migrationDownloadUrl":             settings.MigrationDownloadURL,
		"migrationPushTitle":               settings.MigrationPushTitle,
		"migrationPushBody":                settings.MigrationPushBody,
		"migrationScreenTitle":             settings.MigrationScreenTitle,
		"migrationScreenBody":              settings.MigrationScreenBody,
		"migrationRequirePromptFirst":      settings.MigrationRequirePromptFirst,
		"migrationExpectedSource":          settings.MigrationExpectedSource,
		"migrationReportEnabled":           settings.MigrationReportEnabled,
		"migrationReportTarget":            settings.MigrationReportTarget,
		"migrationReportSource":            settings.MigrationReportSource,
		"migrationBaselineUserCount":       settings.MigrationBaselineUserCount,
		"userPromptRulesJson":              settings.UserPromptRulesJSON,
		"userPromptRules":                  parseUserPromptRulesJSON(settings.UserPromptRulesJSON),
		"createdAt":                        settings.CreatedAt,
		"updatedAt":                        settings.UpdatedAt,
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
