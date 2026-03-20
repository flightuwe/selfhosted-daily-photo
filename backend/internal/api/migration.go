package api

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/csv"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"github.com/yosho/selfhosted-bereal/backend/internal/notify"
	"gorm.io/gorm"
)

const migrationDefaultDurationDays = 7

type migrationSyncRequest struct {
	UserID         uint   `json:"userId"`
	Username       string `json:"username"`
	OccurredAt     string `json:"occurredAt"`
	AppVersion     string `json:"appVersion"`
	SourceInstance string `json:"sourceInstance"`
	Signature      string `json:"signature"`
}

type migrationSettingsRequest struct {
	MigrationAutoOffEnabled     *bool   `json:"migrationAutoOffEnabled"`
	MigrationTargetBaseURL      *string `json:"migrationTargetBaseUrl"`
	MigrationDownloadURL        *string `json:"migrationDownloadUrl"`
	MigrationPushTitle          *string `json:"migrationPushTitle"`
	MigrationPushBody           *string `json:"migrationPushBody"`
	MigrationScreenTitle        *string `json:"migrationScreenTitle"`
	MigrationScreenBody         *string `json:"migrationScreenBody"`
	MigrationRequirePromptFirst *bool   `json:"migrationRequirePromptFirst"`
	MigrationCallbackSecret     *string `json:"migrationCallbackSecret"`
	MigrationExpectedSource     *string `json:"migrationExpectedSource"`
}

type migrationActivateRequest struct {
	Days *int `json:"days"`
}

type migrationPushRequest struct {
	Title      string `json:"title"`
	Body       string `json:"body"`
	TestUserID *uint  `json:"testUserId"`
}

func normalizeMigrationURL(raw string) string {
	clean := strings.TrimSpace(raw)
	if clean == "" {
		return ""
	}
	parsed, err := url.Parse(clean)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return ""
	}
	parsed.RawQuery = ""
	parsed.Fragment = ""
	out := strings.TrimRight(parsed.String(), "/")
	return out
}

func (s *Server) migrationResolved(settings models.AppSettings, now time.Time) (models.AppSettings, bool, string, int64, int64) {
	migratedCount := s.migratedUserCount()
	baseline := settings.MigrationBaselineUserCount
	if baseline < 0 {
		baseline = 0
	}
	if !settings.MigrationEnabled {
		return settings, false, "", migratedCount, baseline
	}
	if !settings.MigrationAutoOffEnabled {
		return settings, true, "", migratedCount, baseline
	}
	if settings.MigrationUntil != nil && now.After(settings.MigrationUntil.In(s.Location)) {
		settings.MigrationEnabled = false
		_ = s.DB.Model(&models.AppSettings{}).Where("id = ?", settings.ID).Update("migration_enabled", false).Error
		return settings, false, "time_elapsed", migratedCount, baseline
	}
	if baseline > 0 && migratedCount >= baseline {
		settings.MigrationEnabled = false
		_ = s.DB.Model(&models.AppSettings{}).Where("id = ?", settings.ID).Update("migration_enabled", false).Error
		return settings, false, "quota_reached", migratedCount, baseline
	}
	return settings, true, "", migratedCount, baseline
}

func (s *Server) migratedUserCount() int64 {
	var count int64
	_ = s.DB.Model(&models.MigrationUserStatus{}).Count(&count).Error
	return count
}

func (s *Server) migrationInfoJSON(settings models.AppSettings, active bool, now time.Time, autoOffReason string, migratedCount int64, baseline int64) gin.H {
	remainingSec := int64(0)
	if settings.MigrationUntil != nil {
		remainingSec = int64(settings.MigrationUntil.In(s.Location).Sub(now).Seconds())
		if remainingSec < 0 {
			remainingSec = 0
		}
	}
	ratio := 0.0
	if baseline > 0 {
		ratio = float64(migratedCount) / float64(baseline)
		if ratio > 1 {
			ratio = 1
		}
	}
	return gin.H{
		"enabled":                  active,
		"startedAt":                settings.MigrationStartedAt,
		"until":                    settings.MigrationUntil,
		"autoOffEnabled":           settings.MigrationAutoOffEnabled,
		"autoOffReason":            autoOffReason,
		"targetBaseUrl":            strings.TrimSpace(settings.MigrationTargetBaseURL),
		"downloadUrl":              strings.TrimSpace(settings.MigrationDownloadURL),
		"pushTitle":                strings.TrimSpace(settings.MigrationPushTitle),
		"pushBody":                 strings.TrimSpace(settings.MigrationPushBody),
		"screenTitle":              strings.TrimSpace(settings.MigrationScreenTitle),
		"screenBody":               strings.TrimSpace(settings.MigrationScreenBody),
		"requirePromptFirst":       settings.MigrationRequirePromptFirst,
		"baselineUserCount":        baseline,
		"migratedUserCount":        migratedCount,
		"migrationRatio":           ratio,
		"remainingSeconds":         remainingSec,
		"callbackExpectedSource":   strings.TrimSpace(settings.MigrationExpectedSource),
		"callbackSecretConfigured": strings.TrimSpace(settings.MigrationCallbackSecret) != "",
	}
}

func (s *Server) migrationSettingsRow() (models.AppSettings, error) {
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		return models.AppSettings{}, err
	}
	return normalizeSettings(settings), nil
}

func (s *Server) handleMigrationInfo(c *gin.Context) {
	settings, err := s.migrationSettingsRow()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	now := time.Now().In(s.Location)
	settings, active, reason, migratedCount, baseline := s.migrationResolved(settings, now)
	c.JSON(http.StatusOK, gin.H{
		"migration": s.migrationInfoJSON(settings, active, now, reason, migratedCount, baseline),
		"serverNow": now,
	})
}

func migrationSignPayload(secret string, userID uint, username, occurredAt, appVersion, sourceInstance string) string {
	payload := fmt.Sprintf("%d|%s|%s|%s|%s", userID, strings.TrimSpace(username), strings.TrimSpace(occurredAt), strings.TrimSpace(appVersion), strings.TrimSpace(sourceInstance))
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(payload))
	return hex.EncodeToString(mac.Sum(nil))
}

func (s *Server) handleMigrationSyncLogin(c *gin.Context) {
	settings, err := s.migrationSettingsRow()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	secret := strings.TrimSpace(settings.MigrationCallbackSecret)
	if secret == "" {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "migration callback secret missing"})
		return
	}
	var req migrationSyncRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	if req.UserID == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "userId required"})
		return
	}
	if expected := strings.TrimSpace(settings.MigrationExpectedSource); expected != "" && !strings.EqualFold(expected, strings.TrimSpace(req.SourceInstance)) {
		c.JSON(http.StatusForbidden, gin.H{"error": "unexpected source"})
		return
	}
	expectedSig := migrationSignPayload(secret, req.UserID, req.Username, req.OccurredAt, req.AppVersion, req.SourceInstance)
	if !hmac.Equal([]byte(strings.ToLower(strings.TrimSpace(req.Signature))), []byte(strings.ToLower(expectedSig))) {
		c.JSON(http.StatusForbidden, gin.H{"error": "invalid signature"})
		return
	}
	now := time.Now().In(s.Location)
	seenAt := now
	if parsed, parseErr := time.Parse(time.RFC3339, strings.TrimSpace(req.OccurredAt)); parseErr == nil {
		seenAt = parsed.In(s.Location)
	}
	var existing models.MigrationUserStatus
	err = s.DB.Where("user_id = ?", req.UserID).First(&existing).Error
	if err == nil {
		updates := map[string]any{
			"username":            strings.TrimSpace(req.Username),
			"last_seen_on_new_at": seenAt,
			"source_instance":     strings.TrimSpace(req.SourceInstance),
			"last_app_version":    strings.TrimSpace(req.AppVersion),
			"updated_at":          now,
		}
		if existing.FirstSeenOnNewAt == nil {
			updates["first_seen_on_new_at"] = seenAt
		}
		_ = s.DB.Model(&models.MigrationUserStatus{}).Where("id = ?", existing.ID).Updates(updates).Error
		c.JSON(http.StatusOK, gin.H{"ok": true, "updated": true})
		return
	}
	if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "sync failed"})
		return
	}
	row := models.MigrationUserStatus{
		UserID:           req.UserID,
		Username:         strings.TrimSpace(req.Username),
		FirstSeenOnNewAt: &seenAt,
		LastSeenOnNewAt:  &seenAt,
		SourceInstance:   strings.TrimSpace(req.SourceInstance),
		LastAppVersion:   strings.TrimSpace(req.AppVersion),
	}
	if createErr := s.DB.Create(&row).Error; createErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "sync failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true, "created": true})
}

func (s *Server) handleAdminMigrationGet(c *gin.Context) {
	settings, err := s.migrationSettingsRow()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	now := time.Now().In(s.Location)
	settings, active, reason, migratedCount, baseline := s.migrationResolved(settings, now)
	c.JSON(http.StatusOK, gin.H{
		"migration": s.migrationInfoJSON(settings, active, now, reason, migratedCount, baseline),
	})
}

func (s *Server) handleAdminMigrationPut(c *gin.Context) {
	var req migrationSettingsRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	settings, err := s.migrationSettingsRow()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	if req.MigrationAutoOffEnabled != nil {
		settings.MigrationAutoOffEnabled = *req.MigrationAutoOffEnabled
	}
	if req.MigrationTargetBaseURL != nil {
		settings.MigrationTargetBaseURL = strings.TrimSpace(*req.MigrationTargetBaseURL)
	}
	if req.MigrationDownloadURL != nil {
		settings.MigrationDownloadURL = strings.TrimSpace(*req.MigrationDownloadURL)
	}
	if req.MigrationPushTitle != nil {
		settings.MigrationPushTitle = strings.TrimSpace(*req.MigrationPushTitle)
	}
	if req.MigrationPushBody != nil {
		settings.MigrationPushBody = strings.TrimSpace(*req.MigrationPushBody)
	}
	if req.MigrationScreenTitle != nil {
		settings.MigrationScreenTitle = strings.TrimSpace(*req.MigrationScreenTitle)
	}
	if req.MigrationScreenBody != nil {
		settings.MigrationScreenBody = strings.TrimSpace(*req.MigrationScreenBody)
	}
	if req.MigrationRequirePromptFirst != nil {
		settings.MigrationRequirePromptFirst = *req.MigrationRequirePromptFirst
	}
	if req.MigrationCallbackSecret != nil {
		settings.MigrationCallbackSecret = strings.TrimSpace(*req.MigrationCallbackSecret)
	}
	if req.MigrationExpectedSource != nil {
		settings.MigrationExpectedSource = strings.TrimSpace(*req.MigrationExpectedSource)
	}
	settings.MigrationTargetBaseURL = normalizeMigrationURL(settings.MigrationTargetBaseURL)
	settings.MigrationDownloadURL = strings.TrimSpace(settings.MigrationDownloadURL)
	settings.MigrationPushTitle = defaultIfBlank(settings.MigrationPushTitle, "Daily umgezogen")
	settings.MigrationPushBody = defaultIfBlank(settings.MigrationPushBody, "Bitte aktualisiere Daily und verbinde dich mit dem neuen Server.")
	settings.MigrationScreenTitle = defaultIfBlank(settings.MigrationScreenTitle, "Daily ist umgezogen")
	settings.MigrationScreenBody = defaultIfBlank(settings.MigrationScreenBody, "Diese Instanz ist im Migrationsmodus. Bitte installiere die aktuelle App-Version und trage den neuen Server ein.")
	if err := s.DB.Save(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	now := time.Now().In(s.Location)
	settings, active, reason, migratedCount, baseline := s.migrationResolved(settings, now)
	c.JSON(http.StatusOK, gin.H{
		"migration": s.migrationInfoJSON(settings, active, now, reason, migratedCount, baseline),
	})
}

func (s *Server) handleAdminMigrationActivate(c *gin.Context) {
	var req migrationActivateRequest
	_ = c.ShouldBindJSON(&req)
	days := migrationDefaultDurationDays
	if req.Days != nil && *req.Days > 0 && *req.Days <= 30 {
		days = *req.Days
	}
	settings, err := s.migrationSettingsRow()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	now := time.Now().In(s.Location)
	until := now.Add(time.Duration(days) * 24 * time.Hour)
	var baseline int64
	if err := s.DB.Model(&models.User{}).Where("is_admin = ?", false).Count(&baseline).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "baseline failed"})
		return
	}
	settings.MigrationEnabled = true
	settings.MigrationStartedAt = &now
	settings.MigrationUntil = &until
	settings.MigrationBaselineUserCount = baseline
	settings.MigrationAutoOffEnabled = true
	settings.MigrationPushTitle = defaultIfBlank(settings.MigrationPushTitle, "Daily umgezogen")
	settings.MigrationPushBody = defaultIfBlank(settings.MigrationPushBody, "Bitte aktualisiere Daily und verbinde dich mit dem neuen Server.")
	settings.MigrationScreenTitle = defaultIfBlank(settings.MigrationScreenTitle, "Daily ist umgezogen")
	settings.MigrationScreenBody = defaultIfBlank(settings.MigrationScreenBody, "Diese Instanz ist im Migrationsmodus. Bitte installiere die aktuelle App-Version und trage den neuen Server ein.")
	if saveErr := s.DB.Save(&settings).Error; saveErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "activate failed"})
		return
	}
	migratedCount := s.migratedUserCount()
	c.JSON(http.StatusOK, gin.H{
		"migration": s.migrationInfoJSON(settings, true, now, "", migratedCount, baseline),
	})
}

func (s *Server) handleAdminMigrationDeactivate(c *gin.Context) {
	settings, err := s.migrationSettingsRow()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	settings.MigrationEnabled = false
	if saveErr := s.DB.Save(&settings).Error; saveErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "deactivate failed"})
		return
	}
	now := time.Now().In(s.Location)
	migratedCount := s.migratedUserCount()
	c.JSON(http.StatusOK, gin.H{
		"migration": s.migrationInfoJSON(settings, false, now, "manual", migratedCount, settings.MigrationBaselineUserCount),
	})
}

func (s *Server) handleAdminMigrationPush(c *gin.Context) {
	var req migrationPushRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	settings, err := s.migrationSettingsRow()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	title := defaultIfBlank(req.Title, settings.MigrationPushTitle)
	body := defaultIfBlank(req.Body, settings.MigrationPushBody)
	if len(body) < 3 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "push body too short"})
		return
	}
	var tokens []string
	if req.TestUserID != nil && *req.TestUserID > 0 {
		tokens = s.userDeviceTokens(*req.TestUserID)
	} else {
		tokens = s.allDeviceTokens()
	}
	result, sendErr := s.Notifier.Send(tokens, notify.Message{
		Title:  title,
		Body:   body,
		Type:   "migration_notice",
		Action: "open_app",
	})
	s.recordPushResult(result, sendErr)
	removed := s.removeInvalidTokens(result.InvalidTokens)
	if sendErr != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"error":          "migration push failed",
			"details":        sendErr.Error(),
			"sentTo":         result.Sent,
			"failed":         result.Failed,
			"invalidRemoved": removed,
		})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"ok":             true,
		"sentTo":         result.Sent,
		"failed":         result.Failed,
		"invalidRemoved": removed,
	})
}

func (s *Server) handleAdminMigrationExport(c *gin.Context) {
	format := strings.ToLower(strings.TrimSpace(c.DefaultQuery("format", "json")))
	if format != "csv" {
		format = "json"
	}
	settings, err := s.migrationSettingsRow()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
		return
	}
	rows := make([]models.MigrationUserStatus, 0, 128)
	_ = s.DB.Order("updated_at desc").Find(&rows).Error
	migratedCount := int64(len(rows))
	now := time.Now().In(s.Location)
	settings, active, reason, _, baseline := s.migrationResolved(settings, now)
	if format == "csv" {
		var b strings.Builder
		w := csv.NewWriter(&b)
		_ = w.Write([]string{"userId", "username", "firstSeenOnNewAt", "lastSeenOnNewAt", "sourceInstance", "lastAppVersion", "updatedAt"})
		for _, item := range rows {
			firstSeen := ""
			if item.FirstSeenOnNewAt != nil {
				firstSeen = item.FirstSeenOnNewAt.In(s.Location).Format(time.RFC3339)
			}
			lastSeen := ""
			if item.LastSeenOnNewAt != nil {
				lastSeen = item.LastSeenOnNewAt.In(s.Location).Format(time.RFC3339)
			}
			_ = w.Write([]string{
				strconv.FormatUint(uint64(item.UserID), 10),
				item.Username,
				firstSeen,
				lastSeen,
				item.SourceInstance,
				item.LastAppVersion,
				item.UpdatedAt.In(s.Location).Format(time.RFC3339),
			})
		}
		w.Flush()
		c.Header("Content-Type", "text/csv; charset=utf-8")
		c.Header("Content-Disposition", "attachment; filename=\"migration-export.csv\"")
		c.String(http.StatusOK, b.String())
		return
	}
	items := make([]gin.H, 0, len(rows))
	for _, item := range rows {
		items = append(items, gin.H{
			"userId":           item.UserID,
			"username":         item.Username,
			"firstSeenOnNewAt": item.FirstSeenOnNewAt,
			"lastSeenOnNewAt":  item.LastSeenOnNewAt,
			"sourceInstance":   item.SourceInstance,
			"lastAppVersion":   item.LastAppVersion,
			"updatedAt":        item.UpdatedAt,
		})
	}
	c.JSON(http.StatusOK, gin.H{
		"schemaVersion": "migration_export_v1",
		"generatedAt":   now,
		"migration":     s.migrationInfoJSON(settings, active, now, reason, migratedCount, baseline),
		"items":         items,
	})
}

func (s *Server) isMigrationRouteAllowed(path string) bool {
	switch strings.TrimSpace(path) {
	case "/api/devices":
		return true
	default:
		return false
	}
}

func (s *Server) enforceMigrationLock(c *gin.Context, user models.User) bool {
	if user.IsAdmin {
		return true
	}
	settings, err := s.migrationSettingsRow()
	if err != nil {
		return true
	}
	now := time.Now().In(s.Location)
	settings, active, reason, migratedCount, baseline := s.migrationResolved(settings, now)
	if !active {
		return true
	}
	path := c.FullPath()
	if path == "" {
		path = c.Request.URL.Path
	}
	if s.isMigrationRouteAllowed(path) {
		return true
	}
	c.AbortWithStatusJSON(http.StatusLocked, gin.H{
		"error":     "migration required",
		"errorCode": "migration_required",
		"migration": s.migrationInfoJSON(settings, true, now, reason, migratedCount, baseline),
	})
	return false
}

func (s *Server) maybeReportMigratedLogin(user models.User, appVersion string) {
	if !s.Config.MigrationReportEnabled {
		return
	}
	target := strings.TrimSpace(s.Config.MigrationReportTarget)
	secret := strings.TrimSpace(s.Config.MigrationReportSecret)
	if target == "" || secret == "" || user.ID == 0 {
		return
	}
	occurredAt := time.Now().In(s.Location).Format(time.RFC3339)
	source := strings.TrimSpace(s.Config.MigrationReportSource)
	if source == "" {
		source = strings.TrimSpace(s.Config.PublicBaseURL)
	}
	payload := migrationSyncRequest{
		UserID:         user.ID,
		Username:       strings.TrimSpace(user.Username),
		OccurredAt:     occurredAt,
		AppVersion:     strings.TrimSpace(appVersion),
		SourceInstance: source,
	}
	payload.Signature = migrationSignPayload(secret, payload.UserID, payload.Username, payload.OccurredAt, payload.AppVersion, payload.SourceInstance)
	body, _ := json.Marshal(payload)
	go func() {
		httpClient := &http.Client{Timeout: 3 * time.Second}
		req, err := http.NewRequest(http.MethodPost, target, bytes.NewReader(body))
		if err != nil {
			return
		}
		req.Header.Set("Content-Type", "application/json")
		resp, doErr := httpClient.Do(req)
		if doErr == nil && resp != nil {
			_ = resp.Body.Close()
		}
	}()
}
