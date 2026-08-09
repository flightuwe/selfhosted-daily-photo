package api

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/yosho/selfhosted-bereal/backend/internal/auth"
	"github.com/yosho/selfhosted-bereal/backend/internal/config"
	mailservice "github.com/yosho/selfhosted-bereal/backend/internal/email"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

type fakeEmailSender struct {
	mu       sync.Mutex
	messages []mailservice.Message
	err      error
}

func (f *fakeEmailSender) Check(context.Context, mailservice.SMTPConfig) error { return f.err }
func (f *fakeEmailSender) Send(_ context.Context, _ mailservice.SMTPConfig, message mailservice.Message) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.err == nil {
		f.messages = append(f.messages, message)
	}
	return f.err
}

func emailTestServer(t *testing.T) (*Server, *fakeEmailSender) {
	t.Helper()
	s := newSearchTestServer(t)
	master := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32))
	secrets, err := mailservice.NewSecrets(master, "")
	if err != nil {
		t.Fatal(err)
	}
	sender := &fakeEmailSender{}
	s.EmailSecrets, s.EmailSender, s.EmailWorkerID = secrets, sender, "test-worker"
	password, err := secrets.Encrypt("smtp-password", "smtp-secret")
	if err != nil {
		t.Fatal(err)
	}
	if err := s.DB.Model(&models.EmailSettings{}).Where("1 = 1").Updates(map[string]any{
		"enabled": true, "host": "smtp.example.com", "port": 587, "tls_mode": "starttls", "auth_mode": "auto", "username": "mailer", "secret_ciphertext": password,
		"from_name": "Daily", "from_address": "daily@example.com", "action_base_url": "https://daily.example",
	}).Error; err != nil {
		t.Fatal(err)
	}
	return s, sender
}

func requestJSON(router http.Handler, method, path string, body any) *httptest.ResponseRecorder {
	payload, _ := json.Marshal(body)
	req := httptest.NewRequest(method, path, bytes.NewReader(payload))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	return rec
}

func TestPasswordResetIsGenericAndRevokesEveryCredential(t *testing.T) {
	s, sender := emailTestServer(t)
	hash, _ := auth.HashPassword("old-password")
	now := time.Now().UTC()
	user := models.User{Username: "reset-user", PasswordHash: hash, AuthVersion: 1, Email: "User@Example.com", EmailNormalized: "user@example.com", EmailVerifiedAt: &now}
	if err := s.DB.Create(&user).Error; err != nil {
		t.Fatal(err)
	}
	oldJWT, err := s.Auth.Sign(user.ID, user.Username, true, user.AuthVersion)
	if err != nil {
		t.Fatal(err)
	}
	session := models.UserSession{SessionID: "session-1", UserID: user.ID, RefreshTokenHash: strings.Repeat("a", 64), LastUsedAt: now}
	if err := s.DB.Create(&session).Error; err != nil {
		t.Fatal(err)
	}
	router := s.Router()
	known := requestJSON(router, http.MethodPost, "/api/auth/password-reset/request", map[string]any{"email": "USER@example.com"})
	unknown := requestJSON(router, http.MethodPost, "/api/auth/password-reset/request", map[string]any{"email": "unknown@example.com"})
	if known.Code != http.StatusAccepted || unknown.Code != http.StatusAccepted || known.Body.String() != unknown.Body.String() {
		t.Fatalf("reset responses differ: %d %q / %d %q", known.Code, known.Body.String(), unknown.Code, unknown.Body.String())
	}
	s.processOneEmailDelivery(context.Background())
	if len(sender.messages) != 1 {
		t.Fatalf("messages = %d, want 1", len(sender.messages))
	}
	linkStart := strings.Index(sender.messages[0].Text, "https://")
	link := strings.TrimSpace(sender.messages[0].Text[linkStart:])
	parsed, err := url.Parse(link)
	if err != nil {
		t.Fatal(err)
	}
	token := url.Values{}
	for _, part := range strings.Split(parsed.Fragment, "&") {
		pair := strings.SplitN(part, "=", 2)
		if len(pair) == 2 {
			token.Set(pair[0], pair[1])
		}
	}
	confirm := requestJSON(router, http.MethodPost, "/api/auth/password-reset/confirm", map[string]any{"token": token.Get("token"), "password": "new-password"})
	if confirm.Code != http.StatusOK {
		t.Fatalf("confirm = %d %s", confirm.Code, confirm.Body.String())
	}
	second := requestJSON(router, http.MethodPost, "/api/auth/password-reset/confirm", map[string]any{"token": token.Get("token"), "password": "other-password"})
	if second.Code != http.StatusBadRequest {
		t.Fatalf("reused token = %d", second.Code)
	}
	if err := s.DB.First(&user, user.ID).Error; err != nil {
		t.Fatal(err)
	}
	if user.AuthVersion != 2 || !auth.CheckPassword(user.PasswordHash, "new-password") {
		t.Fatalf("password/auth version not updated: %d", user.AuthVersion)
	}
	meRequest := httptest.NewRequest(http.MethodGet, "/api/me", nil)
	meRequest.Header.Set("Authorization", "Bearer "+oldJWT)
	meResponse := httptest.NewRecorder()
	router.ServeHTTP(meResponse, meRequest)
	if meResponse.Code != http.StatusUnauthorized || !strings.Contains(meResponse.Body.String(), "credentials_changed") {
		t.Fatalf("old JWT remained valid: %d %s", meResponse.Code, meResponse.Body.String())
	}
	if err := s.DB.First(&session, session.ID).Error; err != nil || session.RevokedAt == nil {
		t.Fatalf("session not revoked: %v %#v", err, session.RevokedAt)
	}
	var action models.EmailAction
	if err := s.DB.Where("user_id = ? AND purpose = ?", user.ID, emailActionReset).First(&action).Error; err != nil {
		t.Fatal(err)
	}
	if action.TokenCiphertext != "" || action.ExpiresAt == nil || action.SentAt == nil {
		t.Fatalf("action delivery state incorrect: %#v", action)
	}
}

func TestEmailSettingsJSONNeverContainsSecret(t *testing.T) {
	raw, err := json.Marshal(emailSettingsJSON(models.EmailSettings{Username: "user", SecretCiphertext: "v1.top-secret"}))
	if err != nil {
		t.Fatal(err)
	}
	text := string(raw)
	if strings.Contains(text, "top-secret") || strings.Contains(strings.ToLower(text), "ciphertext") {
		t.Fatalf("secret leaked: %s", text)
	}
	if !strings.Contains(text, `"passwordConfigured":true`) {
		t.Fatalf("passwordConfigured missing: %s", text)
	}
}

func TestEmailSettingsRetainAndRequireMatchingDraftTest(t *testing.T) {
	secrets, _ := mailservice.NewSecrets(base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{9}, 32)), "")
	ciphertext, _ := secrets.Encrypt("smtp-password", "retained-password")
	server := &Server{EmailSecrets: secrets, Config: config.Config{EmailAllowedActionHosts: []string{"daily.example"}}}
	existing := models.EmailSettings{Host: "smtp.example", Port: 587, TLSMode: "starttls", AuthMode: "auto", Username: "mailer", SecretCiphertext: ciphertext, FromName: "Daily", FromAddress: "daily@example.com", ActionBaseURL: "https://daily.example", LastTestOK: true}
	existing.LastTestConfigHash = smtpConfigHash(existing, "retained-password")
	input := emailSettingsInput{Enabled: true, Host: existing.Host, Port: existing.Port, TLSMode: existing.TLSMode, AuthMode: existing.AuthMode, Username: existing.Username, FromName: existing.FromName, FromAddress: existing.FromAddress, ActionBaseURL: existing.ActionBaseURL}
	next, password, err := server.validateEmailSettingsInput(input, existing)
	if err != nil || password != "retained-password" || next.SecretCiphertext == "" || !next.Enabled {
		t.Fatalf("retain failed: %#v %q %v", next, password, err)
	}
	input.Host = "untested.example"
	if _, _, err := server.validateEmailSettingsInput(input, existing); err == nil {
		t.Fatal("changed untested draft was enabled")
	}
}

func TestEmailConfirmationCollisionKeepsOldAddressAndTokenUnconsumed(t *testing.T) {
	s, _ := emailTestServer(t)
	now := time.Now().UTC()
	target := models.User{Username: "target", PasswordHash: "x", AuthVersion: 1, Email: "old@example.com", EmailNormalized: "old@example.com", EmailVerifiedAt: &now, PendingEmail: "new@example.com", PendingEmailNormalized: "new@example.com", PendingEmailRequestedAt: &now}
	if err := s.DB.Create(&target).Error; err != nil {
		t.Fatal(err)
	}
	owner := models.User{Username: "owner", PasswordHash: "x", AuthVersion: 1, Email: "new@example.com", EmailNormalized: "new@example.com", EmailVerifiedAt: &now}
	if err := s.DB.Create(&owner).Error; err != nil {
		t.Fatal(err)
	}
	token := strings.Repeat("b", 43)
	expires := now.Add(time.Hour)
	action := models.EmailAction{UserID: target.ID, Purpose: emailActionVerify, TokenHash: tokenHash(token), PendingEmail: target.PendingEmail, PendingEmailNormalized: target.PendingEmailNormalized, SentAt: &now, ExpiresAt: &expires}
	if err := s.DB.Create(&action).Error; err != nil {
		t.Fatal(err)
	}
	if err := s.consumeAction(token, emailActionVerify, ""); err == nil {
		t.Fatal("colliding email confirmation succeeded")
	}
	if err := s.DB.First(&target, target.ID).Error; err != nil {
		t.Fatal(err)
	}
	if target.EmailNormalized != "old@example.com" || target.PendingEmailNormalized != "new@example.com" {
		t.Fatalf("address state changed: %#v", target)
	}
	if err := s.DB.First(&action, action.ID).Error; err != nil {
		t.Fatal(err)
	}
	if action.ConsumedAt != nil {
		t.Fatal("failed confirmation consumed token")
	}
}
