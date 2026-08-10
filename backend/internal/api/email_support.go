package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"html"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/auth"
	mailservice "github.com/yosho/selfhosted-bereal/backend/internal/email"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

const (
	emailActionVerify     = "verify_email"
	emailActionReset      = "password_reset"
	emailActionNewsletter = "newsletter_optin"
	newsletterConsentV1   = "newsletter-de-v1"
)

type emailSettingsInput struct {
	Enabled       bool   `json:"enabled"`
	Provider      string `json:"provider"`
	Host          string `json:"host"`
	Port          int    `json:"port"`
	TLSMode       string `json:"tlsMode"`
	AuthMode      string `json:"authMode"`
	Username      string `json:"username"`
	Password      string `json:"password"`
	ClearPassword bool   `json:"clearPassword"`
	FromName      string `json:"fromName"`
	FromAddress   string `json:"fromAddress"`
	ReplyTo       string `json:"replyTo"`
	ActionBaseURL string `json:"actionBaseUrl"`
}

func (s *Server) emailSupportAvailable() bool {
	if s.EmailSecrets == nil || s.EmailSender == nil {
		return false
	}
	var settings models.EmailSettings
	return s.DB.First(&settings).Error == nil
}

func (s *Server) emailDeliveryEnabled() bool {
	if !s.emailSupportAvailable() {
		return false
	}
	var settings models.EmailSettings
	return s.DB.First(&settings).Error == nil && settings.Enabled && strings.TrimSpace(settings.SecretCiphertext) != ""
}

func emailSettingsJSON(settings models.EmailSettings) gin.H {
	return gin.H{
		"enabled":            settings.Enabled,
		"host":               settings.Host,
		"port":               settings.Port,
		"tlsMode":            settings.TLSMode,
		"authMode":           settings.AuthMode,
		"username":           settings.Username,
		"passwordConfigured": strings.TrimSpace(settings.SecretCiphertext) != "",
		"fromName":           settings.FromName,
		"fromAddress":        settings.FromAddress,
		"replyTo":            settings.ReplyTo,
		"actionBaseUrl":      settings.ActionBaseURL,
		"lastTestAt":         settings.LastTestAt,
		"lastTestOk":         settings.LastTestOK,
		"lastTestStage":      settings.LastTestStage,
		"lastTestError":      settings.LastTestError,
		"lastDeliveryAt":     settings.LastDeliveryAt,
		"lastDeliveryError":  settings.LastDeliveryError,
	}
}

func (s *Server) handleAdminGetEmailSettings(c *gin.Context) {
	var settings models.EmailSettings
	if err := s.DB.First(&settings).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "email settings unavailable"})
		return
	}
	response := emailSettingsJSON(settings)
	if settings.LastTestOK && settings.SecretCiphertext != "" && s.EmailSecrets != nil {
		if password, _, err := s.EmailSecrets.Decrypt("smtp-password", settings.SecretCiphertext); err != nil || settings.LastTestConfigHash != smtpConfigHash(settings, password) {
			response["lastTestOk"] = false
			response["lastTestStage"] = "not_tested"
			response["lastTestError"] = ""
		}
	}
	c.JSON(http.StatusOK, response)
}

func (s *Server) validateEmailSettingsInput(input emailSettingsInput, existing models.EmailSettings) (models.EmailSettings, string, error) {
	next := existing
	if strings.EqualFold(strings.TrimSpace(input.Provider), "posteo") {
		if strings.TrimSpace(input.Host) == "" {
			input.Host = "posteo.de"
		}
		if input.Port == 0 {
			input.Port = 587
		}
		if strings.TrimSpace(input.TLSMode) == "" {
			input.TLSMode = "starttls"
		}
	}
	next.Host = strings.TrimSpace(input.Host)
	next.Port = input.Port
	next.TLSMode = strings.ToLower(strings.TrimSpace(input.TLSMode))
	next.AuthMode = strings.ToLower(strings.TrimSpace(input.AuthMode))
	if next.AuthMode == "" {
		next.AuthMode = "auto"
	}
	next.Username = strings.TrimSpace(input.Username)
	next.FromName = strings.TrimSpace(input.FromName)
	next.FromAddress = strings.TrimSpace(input.FromAddress)
	next.ReplyTo = strings.TrimSpace(input.ReplyTo)
	next.ActionBaseURL = strings.TrimRight(strings.TrimSpace(input.ActionBaseURL), "/")
	if next.Host == "" || next.Port < 1 || next.Port > 65535 {
		return next, "", errors.New("host and port are required")
	}
	if len(next.Host) > 255 || strings.ContainsAny(next.Host, "\r\n /\\") || len(next.Username) > 254 || len(next.FromName) > 120 || strings.ContainsAny(next.FromName, "\r\n") || len(next.ActionBaseURL) > 500 || len(input.Password) > 4096 {
		return next, "", errors.New("SMTP configuration contains an invalid or oversized field")
	}
	if next.TLSMode != "starttls" && next.TLSMode != "implicit" {
		return next, "", errors.New("TLS mode must be starttls or implicit")
	}
	if next.AuthMode != "auto" && next.AuthMode != "plain" && next.AuthMode != "login" {
		return next, "", errors.New("unsupported authentication mode")
	}
	if _, _, err := mailservice.NormalizeAddress(next.FromAddress); err != nil {
		return next, "", errors.New("valid sender address required")
	}
	if next.ReplyTo != "" {
		if _, _, err := mailservice.NormalizeAddress(next.ReplyTo); err != nil {
			return next, "", errors.New("invalid reply-to address")
		}
	}
	parsed, err := url.Parse(next.ActionBaseURL)
	if err != nil || parsed.Scheme != "https" || parsed.Hostname() == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" || (parsed.Path != "" && parsed.Path != "/") {
		return next, "", errors.New("action base URL must be a clean HTTPS origin")
	}
	if len(s.Config.EmailAllowedActionHosts) > 0 && !stringInFold(parsed.Hostname(), s.Config.EmailAllowedActionHosts) {
		return next, "", errors.New("action base URL host is not deployment-approved")
	}
	password := input.Password
	if input.ClearPassword {
		next.SecretCiphertext = ""
		password = ""
	} else if password != "" {
		if s.EmailSecrets == nil {
			return next, "", mailservice.ErrMasterKeyUnavailable
		}
		ciphertext, err := s.EmailSecrets.Encrypt("smtp-password", password)
		if err != nil {
			return next, "", err
		}
		next.SecretCiphertext = ciphertext
	} else if next.SecretCiphertext != "" && s.EmailSecrets != nil {
		var old bool
		password, old, err = s.EmailSecrets.Decrypt("smtp-password", next.SecretCiphertext)
		if err != nil {
			return next, "", err
		}
		if old {
			next.SecretCiphertext, _ = s.EmailSecrets.Encrypt("smtp-password", password)
		}
	}
	testedDraft := existing.LastTestOK && existing.LastTestConfigHash == smtpConfigHash(next, password)
	if !testedDraft {
		next.LastTestOK = false
		next.LastTestStage = "not_tested"
		next.LastTestError = ""
	}
	if input.Enabled && (s.EmailSecrets == nil || next.SecretCiphertext == "" || !testedDraft) {
		return next, password, errors.New("successful SMTP test and configured master-key/password required before enabling")
	}
	next.Enabled = input.Enabled
	return next, password, nil
}

func smtpConfigHash(settings models.EmailSettings, password string) string {
	canonical := strings.Join([]string{strings.ToLower(settings.Host), fmt.Sprint(settings.Port), settings.TLSMode, settings.AuthMode, settings.Username, password, settings.FromName, strings.ToLower(settings.FromAddress), strings.ToLower(settings.ReplyTo), settings.ActionBaseURL}, "\x00")
	sum := sha256.Sum256([]byte(canonical))
	return hex.EncodeToString(sum[:])
}

func stringInFold(value string, values []string) bool {
	for _, candidate := range values {
		if strings.EqualFold(strings.TrimSpace(value), strings.TrimSpace(candidate)) {
			return true
		}
	}
	return false
}

func (s *Server) handleAdminUpdateEmailSettings(c *gin.Context) {
	var input emailSettingsInput
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	var existing models.EmailSettings
	if err := s.DB.First(&existing).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings unavailable"})
		return
	}
	next, _, err := s.validateEmailSettingsInput(input, existing)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if err := s.DB.Save(&next).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "settings save failed"})
		return
	}
	c.JSON(http.StatusOK, emailSettingsJSON(next))
}

func (s *Server) draftSMTP(input emailSettingsInput) (models.EmailSettings, mailservice.SMTPConfig, error) {
	var existing models.EmailSettings
	if err := s.DB.First(&existing).Error; err != nil {
		return existing, mailservice.SMTPConfig{}, err
	}
	input.Enabled = false
	next, password, err := s.validateEmailSettingsInput(input, existing)
	if err != nil {
		return next, mailservice.SMTPConfig{}, err
	}
	return next, mailservice.SMTPConfig{Host: next.Host, Port: next.Port, TLSMode: next.TLSMode, AuthMode: next.AuthMode, Username: next.Username, Password: password}, nil
}

func (s *Server) handleAdminTestEmailConnection(c *gin.Context) {
	var input emailSettingsInput
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	settings, smtpConfig, err := s.draftSMTP(input)
	if err == nil {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 20*time.Second)
		defer cancel()
		err = s.EmailSender.Check(ctx, smtpConfig)
	}
	now := time.Now().UTC()
	clean := mailservice.SanitizeError(err)
	configHash := ""
	if err == nil {
		configHash = smtpConfigHash(settings, smtpConfig.Password)
	}
	_ = s.DB.Model(&models.EmailSettings{}).Where("id = ?", settings.ID).Updates(map[string]any{"last_test_at": now, "last_test_ok": err == nil, "last_test_stage": stageForSMTPError(err), "last_test_error": clean, "last_test_config_hash": configHash}).Error
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"ok": false, "stage": stageForSMTPError(err), "error": clean})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true, "stage": "accepted", "message": "TLS connection and authentication succeeded"})
}

func (s *Server) handleAdminTestEmailMessage(c *gin.Context) {
	admin, _ := userFromContext(c)
	if !s.takeEmailRateLimit("admin-test-10m", fmt.Sprint(admin.ID), 10*time.Minute, 5) {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": "admin test email limit reached"})
		return
	}
	var body struct {
		Settings emailSettingsInput `json:"settings"`
		To       string             `json:"to"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	to, _, err := mailservice.NormalizeAddress(body.To)
	settings, smtpConfig, cfgErr := s.draftSMTP(body.Settings)
	if err == nil {
		err = cfgErr
	}
	if err == nil {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 25*time.Second)
		defer cancel()
		err = s.EmailSender.Send(ctx, smtpConfig, mailservice.Message{FromName: settings.FromName, FromAddress: settings.FromAddress, ReplyTo: settings.ReplyTo, To: to, Subject: "Daily SMTP-Test", Text: "Diese Testmail bestätigt, dass Daily E-Mails an den SMTP-Server übergeben kann.", HTML: "<p>Diese Testmail bestätigt, dass <strong>Daily</strong> E-Mails an den SMTP-Server übergeben kann.</p>"})
	}
	now := time.Now().UTC()
	clean := mailservice.SanitizeError(err)
	configHash := ""
	if err == nil {
		configHash = smtpConfigHash(settings, smtpConfig.Password)
	}
	_ = s.DB.Model(&models.EmailSettings{}).Where("id = ?", settings.ID).Updates(map[string]any{"last_test_at": now, "last_test_ok": err == nil, "last_test_stage": stageForSMTPError(err), "last_test_error": clean, "last_test_config_hash": configHash}).Error
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"ok": false, "stage": stageForSMTPError(err), "error": clean})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true, "message": "SMTP server accepted the test message; delivery is not guaranteed"})
}

func stageForSMTPError(err error) string {
	if err == nil {
		return "accepted"
	}
	clean := strings.ToLower(mailservice.SanitizeError(err))
	switch {
	case strings.Contains(clean, "auth"):
		return "authentication"
	case strings.Contains(clean, "tls"), strings.Contains(clean, "certificate"):
		return "tls"
	case strings.Contains(clean, "resolv"):
		return "dns"
	case strings.Contains(clean, "timeout"):
		return "connect"
	default:
		return "smtp"
	}
}

func smtpResultCode(err error) int {
	if err == nil {
		return 250
	}
	for _, field := range strings.Fields(err.Error()) {
		field = strings.Trim(field, "[]():,;.-")
		if len(field) != 3 {
			continue
		}
		if code, parseErr := strconv.Atoi(field); parseErr == nil && code >= 400 && code <= 599 {
			return code
		}
	}
	return 0
}

func (s *Server) handleAdminEmailStatus(c *gin.Context) {
	var queued, failed int64
	_ = s.DB.Model(&models.EmailDelivery{}).Where("status IN ?", []string{"queued", "retry", "sending"}).Count(&queued).Error
	_ = s.DB.Model(&models.EmailDelivery{}).Where("status = ?", "failed").Count(&failed).Error
	var recent []models.EmailDelivery
	_ = s.DB.Select("id", "kind", "status", "attempts", "last_stage", "smtp_result_code", "last_error", "updated_at").Where("status = ?", "failed").Order("updated_at desc").Limit(20).Find(&recent).Error
	c.JSON(http.StatusOK, gin.H{"queueLength": queued, "failedJobs": failed, "deliveryEnabled": s.emailDeliveryEnabled(), "recentFailures": recent})
}

func actionToken() (raw, hash, ciphertext string, err error) {
	buf := make([]byte, 32)
	if _, err = rand.Read(buf); err != nil {
		return
	}
	raw = base64.RawURLEncoding.EncodeToString(buf)
	sum := sha256.Sum256([]byte(raw))
	hash = hex.EncodeToString(sum[:])
	return
}

func (s *Server) queueAction(tx *gorm.DB, userID uint, purpose, recipient, pendingNormalized string, newsletter bool, source, appVersion, ipHash string) error {
	if s.EmailSecrets == nil {
		return mailservice.ErrMasterKeyUnavailable
	}
	raw, hash, _, err := actionToken()
	if err != nil {
		return err
	}
	ciphertext, err := s.EmailSecrets.Encrypt("email-action-token", raw)
	if err != nil {
		return err
	}
	now := time.Now().UTC()
	if err := tx.Model(&models.EmailAction{}).Where("user_id = ? AND purpose = ? AND consumed_at IS NULL AND invalidated_at IS NULL", userID, purpose).Updates(map[string]any{"invalidated_at": now, "token_ciphertext": ""}).Error; err != nil {
		return err
	}
	action := models.EmailAction{UserID: userID, Purpose: purpose, TokenHash: hash, TokenCiphertext: ciphertext, PendingEmail: recipient, PendingEmailNormalized: pendingNormalized, NewsletterOptInRequested: newsletter, ConsentVersion: newsletterConsentV1, Source: source, AppVersion: appVersion, RequestIPHash: ipHash}
	if err := tx.Create(&action).Error; err != nil {
		return err
	}
	delivery := models.EmailDelivery{ActionID: &action.ID, UserID: userID, Kind: purpose, Recipient: recipient, Status: "queued", NextAttemptAt: now}
	return tx.Create(&delivery).Error
}

func hashLimitKey(value string) string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(value)))
	return hex.EncodeToString(sum[:])
}

func (s *Server) takeEmailRateLimit(scope, key string, window time.Duration, limit int) bool {
	if strings.TrimSpace(key) == "" {
		key = "missing"
	}
	now := time.Now().UTC()
	windowStart := now.Truncate(window)
	row := models.EmailRateLimit{Scope: scope, KeyHash: hashLimitKey(key), WindowStart: windowStart, Count: 1}
	err := s.DB.Clauses(clause.OnConflict{Columns: []clause.Column{{Name: "scope"}, {Name: "key_hash"}, {Name: "window_start"}}, DoUpdates: clause.Assignments(map[string]any{"count": gorm.Expr("count + 1"), "updated_at": now})}).Create(&row).Error
	if err != nil {
		return false
	}
	var current models.EmailRateLimit
	if s.DB.Where("scope = ? AND key_hash = ? AND window_start = ?", scope, row.KeyHash, windowStart).First(&current).Error != nil {
		return false
	}
	return current.Count <= limit
}

func (s *Server) handlePasswordResetRequest(c *gin.Context) {
	started := time.Now()
	defer func() {
		remaining := 250*time.Millisecond - time.Since(started)
		if remaining > 0 {
			time.Sleep(remaining)
		}
	}()
	var body struct {
		Email string `json:"email"`
	}
	_ = c.ShouldBindJSON(&body)
	_, normalized, addressErr := mailservice.NormalizeAddress(body.Email)
	ipHash := ""
	if s.EmailSecrets != nil {
		ipHash = s.EmailSecrets.IPHash(c.ClientIP())
	}
	allowed := s.takeEmailRateLimit("reset-ip-hour", ipHash, time.Hour, 10)
	if normalized != "" {
		allowed = s.takeEmailRateLimit("reset-address-hour", normalized, time.Hour, 3) && allowed
	}
	if addressErr == nil && allowed && s.emailDeliveryEnabled() {
		var user models.User
		if s.DB.Where("email_normalized = ? AND email_verified_at IS NOT NULL", normalized).First(&user).Error == nil {
			_ = s.DB.Transaction(func(tx *gorm.DB) error {
				return s.queueAction(tx, user.ID, emailActionReset, user.Email, normalized, false, "password_reset", s.Config.AppVersion, ipHash)
			})
		}
	}
	c.JSON(http.StatusAccepted, gin.H{"ok": true, "message": "If a matching verified account exists, an email will be sent."})
}

func (s *Server) handlePasswordResetConfirm(c *gin.Context) {
	var body struct {
		Token    string `json:"token" binding:"required,min=40,max=128"`
		Password string `json:"password" binding:"required,min=6,max=128"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	if err := s.consumeAction(body.Token, emailActionReset, body.Password); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "link invalid or expired", "errorCode": "invalid_email_action"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true, "message": "password changed"})
}

func (s *Server) handleEmailActionConfirm(c *gin.Context) {
	var body struct {
		Token   string `json:"token" binding:"required,min=40,max=128"`
		Purpose string `json:"purpose" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil || (body.Purpose != emailActionVerify && body.Purpose != emailActionNewsletter) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	if err := s.consumeAction(body.Token, body.Purpose, ""); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "link invalid or expired", "errorCode": "invalid_email_action"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true, "purpose": body.Purpose})
}

func tokenHash(raw string) string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(raw)))
	return hex.EncodeToString(sum[:])
}

func (s *Server) consumeAction(rawToken, purpose, newPassword string) error {
	now := time.Now().UTC()
	return s.DB.Transaction(func(tx *gorm.DB) error {
		var action models.EmailAction
		if err := tx.Where("token_hash = ? AND purpose = ? AND consumed_at IS NULL AND invalidated_at IS NULL AND sent_at IS NOT NULL AND expires_at > ?", tokenHash(rawToken), purpose, now).First(&action).Error; err != nil {
			return err
		}
		result := tx.Model(&models.EmailAction{}).Where("id = ? AND consumed_at IS NULL AND invalidated_at IS NULL", action.ID).Update("consumed_at", now)
		if result.Error != nil || result.RowsAffected != 1 {
			return gorm.ErrRecordNotFound
		}
		switch purpose {
		case emailActionReset:
			return setPasswordTx(tx, action.UserID, newPassword, now)
		case emailActionVerify:
			var user models.User
			if err := tx.First(&user, action.UserID).Error; err != nil || user.PendingEmailNormalized != action.PendingEmailNormalized {
				return gorm.ErrRecordNotFound
			}
			var collision int64
			if err := tx.Model(&models.User{}).Where("email_normalized = ? AND id <> ?", action.PendingEmailNormalized, user.ID).Count(&collision).Error; err != nil || collision > 0 {
				return gorm.ErrDuplicatedKey
			}
			oldAddress := user.Email
			if err := tx.Model(&models.User{}).Where("id = ?", user.ID).Updates(map[string]any{"email": action.PendingEmail, "email_normalized": action.PendingEmailNormalized, "email_verified_at": now, "pending_email": "", "pending_email_normalized": "", "pending_email_requested_at": nil}).Error; err != nil {
				return err
			}
			if err := tx.Model(&models.EmailAction{}).Where("user_id = ? AND purpose = ? AND id <> ? AND consumed_at IS NULL", user.ID, purpose, action.ID).Updates(map[string]any{"invalidated_at": now, "token_ciphertext": ""}).Error; err != nil {
				return err
			}
			if action.NewsletterOptInRequested {
				if err := s.queueNewsletterAction(tx, user.ID, action.PendingEmail, action.PendingEmailNormalized, action.Source); err != nil {
					return err
				}
			} else if err := unsubscribeNewsletterTx(tx, user.ID, now); err != nil {
				return err
			}
			if oldAddress != "" && !strings.EqualFold(oldAddress, action.PendingEmail) {
				return tx.Create(&models.EmailDelivery{UserID: user.ID, Kind: "email_changed_notice", Recipient: oldAddress, Status: "queued", NextAttemptAt: now}).Error
			}
		case emailActionNewsletter:
			var user models.User
			if err := tx.First(&user, action.UserID).Error; err != nil || user.EmailNormalized == "" || user.EmailNormalized != action.PendingEmailNormalized {
				return gorm.ErrRecordNotFound
			}
			return tx.Model(&models.NewsletterSubscription{}).Where("user_id = ? AND email_normalized = ?", user.ID, user.EmailNormalized).Updates(map[string]any{"status": "subscribed", "confirmed_at": now, "revoked_at": nil, "consent_version": action.ConsentVersion}).Error
		}
		return nil
	})
}

func revokeAllSessionsTx(tx *gorm.DB, userID uint, now time.Time) error {
	return tx.Model(&models.UserSession{}).Where("user_id = ? AND revoked_at IS NULL", userID).Updates(map[string]any{"revoked_at": now, "updated_at": now, "last_used_at": now}).Error
}

func setPasswordTx(tx *gorm.DB, userID uint, newPassword string, now time.Time) error {
	if len(newPassword) < 6 || len(newPassword) > 128 {
		return errors.New("password must be between 6 and 128 characters")
	}
	hash, err := auth.HashPassword(newPassword)
	if err != nil {
		return err
	}
	if err := tx.Model(&models.User{}).Where("id = ?", userID).Updates(map[string]any{"password_hash": hash, "auth_version": gorm.Expr("auth_version + 1")}).Error; err != nil {
		return err
	}
	if err := revokeAllSessionsTx(tx, userID, now); err != nil {
		return err
	}
	return tx.Model(&models.EmailAction{}).Where("user_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL", userID).Updates(map[string]any{"invalidated_at": now, "token_ciphertext": ""}).Error
}

func (s *Server) handleGetMyEmail(c *gin.Context) {
	user, _ := userFromContext(c)
	var subscription models.NewsletterSubscription
	status := "unsubscribed"
	if s.DB.Where("user_id = ?", user.ID).First(&subscription).Error == nil {
		status = subscription.Status
	}
	var pendingAction models.EmailAction
	pendingNewsletter := false
	if s.DB.Where("user_id = ? AND purpose = ? AND consumed_at IS NULL AND invalidated_at IS NULL", user.ID, emailActionVerify).Order("created_at desc").First(&pendingAction).Error == nil {
		pendingNewsletter = pendingAction.NewsletterOptInRequested
	}
	c.JSON(http.StatusOK, gin.H{"email": user.Email, "verifiedAt": user.EmailVerifiedAt, "pendingEmail": user.PendingEmail, "pendingRequestedAt": user.PendingEmailRequestedAt, "pendingNewsletterOptIn": pendingNewsletter, "newsletterStatus": status, "emailSupport": s.emailSupportAvailable(), "deliveryEnabled": s.emailDeliveryEnabled()})
}

func (s *Server) handleRequestEmailVerification(c *gin.Context) {
	user, _ := userFromContext(c)
	var body struct {
		Email           string `json:"email" binding:"required"`
		CurrentPassword string `json:"currentPassword" binding:"required"`
		NewsletterOptIn bool   `json:"newsletterOptIn"`
	}
	if err := c.ShouldBindJSON(&body); err != nil || !auth.CheckPassword(user.PasswordHash, body.CurrentPassword) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "current password invalid"})
		return
	}
	address, normalized, err := mailservice.NormalizeAddress(body.Email)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid email address"})
		return
	}
	if !s.emailDeliveryEnabled() {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "email delivery disabled"})
		return
	}
	if !s.takeEmailRateLimit("verify-user-hour", fmt.Sprint(user.ID), time.Hour, 3) {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": "please wait before requesting another email"})
		return
	}
	if user.PendingEmailRequestedAt != nil && time.Since(*user.PendingEmailRequestedAt) < time.Minute {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": "please wait before requesting another email"})
		return
	}
	var collision int64
	_ = s.DB.Model(&models.User{}).Where("email_normalized = ? AND id <> ?", normalized, user.ID).Count(&collision).Error
	if collision > 0 {
		c.JSON(http.StatusConflict, gin.H{"error": "email address unavailable"})
		return
	}
	now := time.Now().UTC()
	err = s.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&models.User{}).Where("id = ?", user.ID).Updates(map[string]any{"pending_email": address, "pending_email_normalized": normalized, "pending_email_requested_at": now}).Error; err != nil {
			return err
		}
		return s.queueAction(tx, user.ID, emailActionVerify, address, normalized, body.NewsletterOptIn, "profile", s.Config.AppVersion, "")
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "verification request failed"})
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"ok": true, "pendingEmail": address})
}

func (s *Server) handleDeletePendingEmail(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().UTC()
	_ = s.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&models.EmailAction{}).Where("user_id = ? AND purpose = ? AND consumed_at IS NULL", user.ID, emailActionVerify).Updates(map[string]any{"invalidated_at": now, "token_ciphertext": ""}).Error; err != nil {
			return err
		}
		return tx.Model(&models.User{}).Where("id = ?", user.ID).Updates(map[string]any{"pending_email": "", "pending_email_normalized": "", "pending_email_requested_at": nil}).Error
	})
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleDeleteMyEmail(c *gin.Context) {
	user, _ := userFromContext(c)
	var body struct {
		CurrentPassword string `json:"currentPassword" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil || !auth.CheckPassword(user.PasswordHash, body.CurrentPassword) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "current password invalid"})
		return
	}
	if err := s.removeUserEmail(user.ID, true); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "email removal failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleAdminDeleteUserEmail(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
		return
	}
	if err := s.removeUserEmail(id, true); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "email removal failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleAdminGetUserEmail(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
		return
	}

	var user models.User
	if err := s.DB.First(&user, id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "email lookup failed"})
		return
	}

	newsletterStatus := "unsubscribed"
	var newsletter models.NewsletterSubscription
	if err := s.DB.Where("user_id = ?", id).First(&newsletter).Error; err == nil {
		newsletterStatus = newsletter.Status
	} else if !errors.Is(err, gorm.ErrRecordNotFound) {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "newsletter lookup failed"})
		return
	}

	admin, _ := userFromContext(c)
	log.Printf("admin_email_reveal adminUserID=%d targetUserID=%d requestId=%s", admin.ID, id, requestIDFromContext(c))
	c.Header("Cache-Control", "no-store")
	c.Header("Pragma", "no-cache")
	c.JSON(http.StatusOK, gin.H{
		"email":                   user.Email,
		"emailVerifiedAt":         user.EmailVerifiedAt,
		"pendingEmail":            user.PendingEmail,
		"pendingEmailRequestedAt": user.PendingEmailRequestedAt,
		"newsletterStatus":        newsletterStatus,
		"newsletterConfirmedAt":   newsletter.ConfirmedAt,
	})
}

func (s *Server) removeUserEmail(userID uint, notifyOld bool) error {
	now := time.Now().UTC()
	return s.DB.Transaction(func(tx *gorm.DB) error {
		var user models.User
		if err := tx.First(&user, userID).Error; err != nil {
			return err
		}
		if err := tx.Model(&models.User{}).Where("id = ?", userID).Updates(map[string]any{"email": "", "email_normalized": "", "email_verified_at": nil, "pending_email": "", "pending_email_normalized": "", "pending_email_requested_at": nil}).Error; err != nil {
			return err
		}
		if err := tx.Model(&models.EmailAction{}).Where("user_id = ? AND consumed_at IS NULL", userID).Updates(map[string]any{"invalidated_at": now, "token_ciphertext": ""}).Error; err != nil {
			return err
		}
		if err := unsubscribeNewsletterTx(tx, userID, now); err != nil {
			return err
		}
		if notifyOld && user.Email != "" && s.emailDeliveryEnabled() {
			return tx.Create(&models.EmailDelivery{UserID: userID, Kind: "email_removed_notice", Recipient: user.Email, Status: "queued", NextAttemptAt: now}).Error
		}
		return nil
	})
}

func unsubscribeNewsletterTx(tx *gorm.DB, userID uint, now time.Time) error {
	return tx.Model(&models.NewsletterSubscription{}).Where("user_id = ?", userID).Updates(map[string]any{"status": "unsubscribed", "revoked_at": now}).Error
}

func (s *Server) queueNewsletterAction(tx *gorm.DB, userID uint, address, normalized, source string) error {
	now := time.Now().UTC()
	sub := models.NewsletterSubscription{UserID: userID, Email: address, EmailNormalized: normalized, Status: "pending", ConsentVersion: newsletterConsentV1, Source: source, RequestedAt: &now}
	if err := tx.Clauses(clause.OnConflict{Columns: []clause.Column{{Name: "user_id"}}, DoUpdates: clause.Assignments(map[string]any{"email": address, "email_normalized": normalized, "status": "pending", "consent_version": newsletterConsentV1, "source": source, "requested_at": now, "confirmed_at": nil, "revoked_at": nil})}).Create(&sub).Error; err != nil {
		return err
	}
	return s.queueAction(tx, userID, emailActionNewsletter, address, normalized, false, source, s.Config.AppVersion, "")
}

func (s *Server) handleNewsletterSubscribe(c *gin.Context) {
	user, _ := userFromContext(c)
	if user.EmailVerifiedAt == nil || user.EmailNormalized == "" || !s.emailDeliveryEnabled() {
		c.JSON(http.StatusBadRequest, gin.H{"error": "verified email and enabled delivery required"})
		return
	}
	if !s.takeEmailRateLimit("newsletter-user-day", fmt.Sprint(user.ID), 24*time.Hour, 3) {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": "newsletter confirmation limit reached"})
		return
	}
	if err := s.DB.Transaction(func(tx *gorm.DB) error {
		return s.queueNewsletterAction(tx, user.ID, user.Email, user.EmailNormalized, "profile")
	}); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "subscription request failed"})
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"ok": true, "status": "pending"})
}

func (s *Server) handleNewsletterUnsubscribe(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().UTC()
	_ = s.DB.Transaction(func(tx *gorm.DB) error {
		if err := unsubscribeNewsletterTx(tx, user.ID, now); err != nil {
			return err
		}
		return tx.Model(&models.EmailAction{}).Where("user_id = ? AND purpose = ? AND consumed_at IS NULL", user.ID, emailActionNewsletter).Updates(map[string]any{"invalidated_at": now, "token_ciphertext": ""}).Error
	})
	c.JSON(http.StatusOK, gin.H{"ok": true, "status": "unsubscribed"})
}

func (s *Server) handleUserPromptEvent(c *gin.Context) {
	user, _ := userFromContext(c)
	ruleID := strings.TrimSpace(c.Param("id"))
	var body struct {
		Event      string `json:"event" binding:"required"`
		AppVersion string `json:"appVersion"`
	}
	if err := c.ShouldBindJSON(&body); err != nil || !stringInFold(body.Event, []string{"shown", "later", "accepted", "declined", "completed"}) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid prompt event"})
		return
	}
	now := time.Now().UTC()
	state := models.UserPromptState{UserID: user.ID, RuleID: ruleID, LastEvent: strings.ToLower(body.Event)}
	updates := map[string]any{"last_event": state.LastEvent, "updated_at": now}
	if state.LastEvent == "shown" || state.LastEvent == "later" {
		state.LastShownAt, state.LastShownVersion = &now, strings.TrimSpace(body.AppVersion)
		updates["last_shown_at"], updates["last_shown_version"] = now, state.LastShownVersion
	}
	if state.LastEvent == "accepted" {
		state.AcceptedAt, updates["accepted_at"] = &now, now
	}
	if state.LastEvent == "completed" {
		state.CompletedAt, updates["completed_at"] = &now, now
	}
	if err := s.DB.Clauses(clause.OnConflict{Columns: []clause.Column{{Name: "user_id"}, {Name: "rule_id"}}, DoUpdates: clause.Assignments(updates)}).Create(&state).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "prompt event save failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) RunEmailDeliveryLoop(ctx context.Context, interval time.Duration) {
	if interval <= 0 {
		interval = 3 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	cleanupTicker := time.NewTicker(6 * time.Hour)
	defer cleanupTicker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.processOneEmailDelivery(ctx)
		case <-cleanupTicker.C:
			_ = s.DB.Where("window_start < ?", time.Now().UTC().Add(-8*24*time.Hour)).Delete(&models.EmailRateLimit{}).Error
		}
	}
}

func (s *Server) processOneEmailDelivery(ctx context.Context) {
	if !s.emailDeliveryEnabled() {
		return
	}
	now := time.Now().UTC()
	var delivery models.EmailDelivery
	if err := s.DB.Where("status IN ? AND next_attempt_at <= ? AND (locked_until IS NULL OR locked_until < ?)", []string{"queued", "retry", "sending"}, now, now).Order("next_attempt_at, id").First(&delivery).Error; err != nil {
		return
	}
	lockUntil := now.Add(45 * time.Second)
	claim := s.DB.Model(&models.EmailDelivery{}).Where("id = ? AND (locked_until IS NULL OR locked_until < ?)", delivery.ID, now).Updates(map[string]any{"status": "sending", "locked_by": s.EmailWorkerID, "locked_until": lockUntil, "attempts": gorm.Expr("attempts + 1")})
	if claim.Error != nil || claim.RowsAffected != 1 {
		return
	}
	delivery.Attempts++
	var settings models.EmailSettings
	if s.DB.First(&settings).Error != nil {
		return
	}
	password, oldKey, err := s.EmailSecrets.Decrypt("smtp-password", settings.SecretCiphertext)
	if err == nil && oldKey {
		if rotated, rotateErr := s.EmailSecrets.Encrypt("smtp-password", password); rotateErr == nil {
			_ = s.DB.Model(&settings).Update("secret_ciphertext", rotated).Error
		}
	}
	var action models.EmailAction
	if err == nil && delivery.ActionID != nil {
		err = s.DB.First(&action, *delivery.ActionID).Error
		if err == nil && (action.InvalidatedAt != nil || action.ConsumedAt != nil) {
			_ = s.DB.Model(&delivery).Updates(map[string]any{"status": "cancelled", "locked_by": "", "locked_until": nil}).Error
			return
		}
	}
	message := mailservice.Message{}
	if err == nil {
		message, err = s.emailMessage(settings, delivery, action)
	}
	if err == nil {
		sendCtx, cancel := context.WithTimeout(ctx, 25*time.Second)
		err = s.EmailSender.Send(sendCtx, mailservice.SMTPConfig{Host: settings.Host, Port: settings.Port, TLSMode: settings.TLSMode, AuthMode: settings.AuthMode, Username: settings.Username, Password: password}, message)
		cancel()
	}
	if err != nil {
		clean := mailservice.SanitizeError(err)
		if delivery.Attempts >= 4 {
			_ = s.DB.Model(&delivery).Updates(map[string]any{"status": "failed", "last_stage": stageForSMTPError(err), "smtp_result_code": smtpResultCode(err), "last_error": clean, "locked_by": "", "locked_until": nil}).Error
			if delivery.ActionID != nil {
				_ = s.DB.Model(&models.EmailAction{}).Where("id = ?", *delivery.ActionID).Updates(map[string]any{"invalidated_at": now, "token_ciphertext": ""}).Error
			}
		} else {
			delays := []time.Duration{30 * time.Second, 2 * time.Minute, 5 * time.Minute, 15 * time.Minute}
			_ = s.DB.Model(&delivery).Updates(map[string]any{"status": "retry", "next_attempt_at": now.Add(delays[delivery.Attempts-1]), "last_stage": stageForSMTPError(err), "smtp_result_code": smtpResultCode(err), "last_error": clean, "locked_by": "", "locked_until": nil}).Error
		}
		_ = s.DB.Model(&settings).Updates(map[string]any{"last_delivery_error": clean}).Error
		return
	}
	_ = s.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&delivery).Updates(map[string]any{"status": "sent", "sent_at": now, "last_error": "", "last_stage": "accepted", "smtp_result_code": 250, "locked_by": "", "locked_until": nil}).Error; err != nil {
			return err
		}
		if delivery.ActionID != nil {
			ttl := 24 * time.Hour
			if action.Purpose == emailActionReset {
				ttl = 30 * time.Minute
			}
			expires := now.Add(ttl)
			if err := tx.Model(&models.EmailAction{}).Where("id = ? AND sent_at IS NULL", action.ID).Updates(map[string]any{"sent_at": now, "expires_at": expires, "token_ciphertext": ""}).Error; err != nil {
				return err
			}
		}
		return tx.Model(&settings).Updates(map[string]any{"last_delivery_at": now, "last_delivery_error": ""}).Error
	})
}

func (s *Server) emailMessage(settings models.EmailSettings, delivery models.EmailDelivery, action models.EmailAction) (mailservice.Message, error) {
	message := mailservice.Message{FromName: settings.FromName, FromAddress: settings.FromAddress, ReplyTo: settings.ReplyTo, To: delivery.Recipient}
	switch delivery.Kind {
	case "email_changed_notice":
		message.Subject = "Daily: Deine E-Mail-Adresse wurde geändert"
		message.Text = "Die mit deinem Daily-Konto verknüpfte E-Mail-Adresse wurde geändert. Wenn du das nicht warst, wende dich bitte an den Administrator."
		message.HTML = "<p>Die mit deinem Daily-Konto verknüpfte E-Mail-Adresse wurde geändert.</p><p>Wenn du das nicht warst, wende dich bitte an den Administrator.</p>"
		return message, nil
	case "email_removed_notice":
		message.Subject = "Daily: Deine E-Mail-Adresse wurde entfernt"
		message.Text = "Die Recovery-E-Mail-Adresse wurde von deinem Daily-Konto entfernt. Wenn du das nicht warst, wende dich bitte an den Administrator."
		message.HTML = "<p>Die Recovery-E-Mail-Adresse wurde von deinem Daily-Konto entfernt.</p><p>Wenn du das nicht warst, wende dich bitte an den Administrator.</p>"
		return message, nil
	}
	if action.ID == 0 || action.TokenCiphertext == "" {
		return message, errors.New("email action token unavailable")
	}
	raw, _, err := s.EmailSecrets.Decrypt("email-action-token", action.TokenCiphertext)
	if err != nil {
		return message, err
	}
	path := "verify"
	if action.Purpose == emailActionReset {
		path = "reset"
	}
	link := strings.TrimRight(settings.ActionBaseURL, "/") + "/email-action/" + path + "#token=" + url.QueryEscape(raw)
	if action.Purpose == emailActionNewsletter {
		link += "&purpose=" + emailActionNewsletter
	}
	safeLink := html.EscapeString(link)
	switch action.Purpose {
	case emailActionVerify:
		message.Subject = "Daily: E-Mail-Adresse bestätigen"
		message.Text = "Bestätige deine E-Mail-Adresse über diesen Link (24 Stunden gültig):\n\n" + link
		message.HTML = `<p>Bestätige deine E-Mail-Adresse für Daily.</p><p><a href="` + safeLink + `">E-Mail-Adresse bestätigen</a></p><p>Der Link ist 24 Stunden gültig.</p>`
	case emailActionReset:
		message.Subject = "Daily: Passwort zurücksetzen"
		message.Text = "Setze dein Daily-Passwort über diesen Link zurück (30 Minuten gültig):\n\n" + link
		message.HTML = `<p>Du kannst dein Daily-Passwort zurücksetzen.</p><p><a href="` + safeLink + `">Passwort zurücksetzen</a></p><p>Der Link ist 30 Minuten gültig.</p>`
	case emailActionNewsletter:
		message.Subject = "Daily: Newsletter-Anmeldung bestätigen"
		message.Text = "Bestätige die Newsletter-Anmeldung über diesen Link (24 Stunden gültig):\n\n" + link
		message.HTML = `<p>Bestätige deine freiwillige Newsletter-Anmeldung.</p><p><a href="` + safeLink + `">Newsletter abonnieren</a></p><p>Der Link ist 24 Stunden gültig.</p>`
	default:
		return message, errors.New("unknown email action")
	}
	return message, nil
}

func (s *Server) handleAssetLinks(c *gin.Context) {
	fingerprints := make([]string, 0, len(s.Config.AndroidAppCertSHA256))
	for _, value := range s.Config.AndroidAppCertSHA256 {
		if clean := strings.ToUpper(strings.TrimSpace(value)); validSHA256Fingerprint(clean) {
			fingerprints = append(fingerprints, clean)
		}
	}
	c.Header("Cache-Control", "public, max-age=3600")
	if len(fingerprints) == 0 || strings.TrimSpace(s.Config.AndroidAppPackage) == "" {
		c.JSON(http.StatusOK, []gin.H{})
		return
	}
	c.JSON(http.StatusOK, []gin.H{{"relation": []string{"delegate_permission/common.handle_all_urls"}, "target": gin.H{"namespace": "android_app", "package_name": s.Config.AndroidAppPackage, "sha256_cert_fingerprints": fingerprints}}})
}

func validSHA256Fingerprint(value string) bool {
	parts := strings.Split(value, ":")
	if len(parts) != 32 {
		return false
	}
	for _, part := range parts {
		if len(part) != 2 {
			return false
		}
		if _, err := hex.DecodeString(part); err != nil {
			return false
		}
	}
	return true
}

func (s *Server) handleEmailActionPage(c *gin.Context) {
	nonceBytes := make([]byte, 18)
	_, _ = rand.Read(nonceBytes)
	nonce := base64.RawURLEncoding.EncodeToString(nonceBytes)
	reset := strings.HasSuffix(c.Request.URL.Path, "/reset")
	purpose := emailActionVerify
	if reset {
		purpose = emailActionReset
	}
	c.Header("Cache-Control", "no-store")
	c.Header("Pragma", "no-cache")
	c.Header("Referrer-Policy", "no-referrer")
	c.Header("X-Content-Type-Options", "nosniff")
	c.Header("Content-Security-Policy", "default-src 'none'; style-src 'nonce-"+nonce+"'; script-src 'nonce-"+nonce+"'; connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'")
	c.Data(http.StatusOK, "text/html; charset=utf-8", []byte(emailActionHTML(nonce, purpose, reset)))
}

func emailActionHTML(nonce, purpose string, reset bool) string {
	fields := ""
	if reset {
		fields = `<label>Neues Passwort<input id="password" type="password" minlength="6" maxlength="128" required></label><label>Passwort wiederholen<input id="repeat" type="password" minlength="6" maxlength="128" required></label>`
	}
	return `<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Daily</title><style nonce="` + nonce + `">body{font:16px system-ui;background:#f4f6fb;color:#172033;margin:0;display:grid;place-items:center;min-height:100vh}.card{background:white;padding:28px;border-radius:18px;box-shadow:0 8px 30px #0002;width:min(420px,calc(100% - 48px))}label{display:block;margin:14px 0}input,button{box-sizing:border-box;width:100%;padding:12px;border-radius:10px;border:1px solid #ccd3df}button{background:#1f5fbf;color:white;border:0;font-weight:700}#status{min-height:24px}</style></head><body><main class="card"><h1>Daily</h1><p>Öffne diesen Link bevorzugt in der Daily-App oder bestätige hier.</p>` + fields + `<button id="confirm">Bestätigen</button><p id="status"></p></main><script nonce="` + nonce + `">const params=new URLSearchParams(location.hash.slice(1)),token=params.get('token'),actionPurpose=params.get('purpose')||'` + purpose + `';history.replaceState(null,'',location.pathname);document.getElementById('confirm').onclick=async()=>{const status=document.getElementById('status');if(!token){status.textContent='Der Link enthält kein Token.';return;}const password=document.getElementById('password');const repeat=document.getElementById('repeat');let endpoint='/api/email-actions/confirm',body={token,purpose:actionPurpose};if(password){if(password.value.length<6||password.value!==repeat.value){status.textContent='Die Passwörter stimmen nicht überein oder sind zu kurz.';return;}endpoint='/api/auth/password-reset/confirm';body={token,password:password.value};}status.textContent='Wird geprüft …';const response=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body),credentials:'omit',referrerPolicy:'no-referrer'});status.textContent=response.ok?'Erfolgreich. Du kannst Daily jetzt öffnen.':'Der Link ist ungültig, abgelaufen oder wurde bereits verwendet.';};</script></body></html>`
}
