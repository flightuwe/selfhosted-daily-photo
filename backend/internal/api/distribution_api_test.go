package api

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/yosho/selfhosted-bereal/backend/internal/auth"
	"github.com/yosho/selfhosted-bereal/backend/internal/config"
	dailydb "github.com/yosho/selfhosted-bereal/backend/internal/db"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
)

func newDistributionAPITestServer(t *testing.T) (*Server, models.User, models.User) {
	t.Helper()
	database, err := dailydb.Connect(filepath.Join(t.TempDir(), "app.db"))
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "requires cgo") {
			t.Skipf("sqlite driver requires cgo in this environment: %v", err)
		}
		t.Fatal(err)
	}
	admin := models.User{Username: "distribution-admin", PasswordHash: "test", AuthVersion: 1, IsAdmin: true}
	user := models.User{Username: "distribution-user", PasswordHash: "test", AuthVersion: 1}
	if err := database.Create(&admin).Error; err != nil {
		t.Fatal(err)
	}
	if err := database.Create(&user).Error; err != nil {
		t.Fatal(err)
	}
	manager := auth.NewManager("distribution-test-secret", time.Hour)
	return &Server{
		DB: database,
		Config: config.Config{
			AllowedOrigins:               []string{"*"},
			DistributionManifestMaxBytes: 1024 * 1024,
			DistributionAPKMaxBytes:      250 * 1024 * 1024,
		},
		Auth: manager, Location: time.UTC,
	}, admin, user
}

func distributionToken(t *testing.T, server *Server, user models.User) string {
	t.Helper()
	token, err := server.Auth.Sign(user.ID, user.Username, user.IsAdmin, user.AuthVersion)
	if err != nil {
		t.Fatal(err)
	}
	return token
}

func distributionRequest(t *testing.T, router http.Handler, method string, path string, token string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var payload bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&payload).Encode(body); err != nil {
			t.Fatal(err)
		}
	}
	request := httptest.NewRequest(method, path, &payload)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)
	return response
}

func TestDistributionClientDefaultOverrideAndAdminRights(t *testing.T) {
	server, admin, user := newDistributionAPITestServer(t)
	router := server.Router()
	adminToken := distributionToken(t, server, admin)
	userToken := distributionToken(t, server, user)

	forbidden := distributionRequest(t, router, http.MethodGet, "/api/admin/distribution/profiles", userToken, nil)
	if forbidden.Code != http.StatusForbidden {
		t.Fatalf("regular user admin status = %d body=%s", forbidden.Code, forbidden.Body.String())
	}
	forbiddenAudit := distributionRequest(t, router, http.MethodGet, "/api/admin/distribution/audit", userToken, nil)
	if forbiddenAudit.Code != http.StatusForbidden {
		t.Fatalf("regular user audit status = %d body=%s", forbiddenAudit.Code, forbiddenAudit.Body.String())
	}
	defaultResponse := distributionRequest(t, router, http.MethodGet, "/api/app-distribution", userToken, nil)
	if defaultResponse.Code != http.StatusOK || !strings.Contains(defaultResponse.Body.String(), `"enabled":true`) {
		t.Fatalf("default response status=%d body=%s", defaultResponse.Code, defaultResponse.Body.String())
	}
	if got := defaultResponse.Header().Get("Cache-Control"); !strings.Contains(got, "private") {
		t.Fatalf("Cache-Control = %q", got)
	}
	var defaultPayload map[string]any
	if err := json.Unmarshal(defaultResponse.Body.Bytes(), &defaultPayload); err != nil {
		t.Fatal(err)
	}
	if defaultPayload["profileUpdatedAt"] == nil {
		t.Fatalf("client payload lacks profileUpdatedAt: %v", defaultPayload)
	}
	for _, forbiddenField := range []string{"actorUserId", "actorUsername", "audit", "deploymentPolicy", "privateHostAllowlistConfigured", "createdByUserId"} {
		if _, exists := defaultPayload[forbiddenField]; exists {
			t.Fatalf("client payload exposes %q: %v", forbiddenField, defaultPayload)
		}
	}

	create := distributionRequest(t, router, http.MethodPost, "/api/admin/distribution/profiles", adminToken, map[string]any{
		"name": "Disabled user profile", "enabled": false, "isDefault": false,
		"sourceMode": "disabled", "channel": "stable", "expectedPackageName": "com.selfhosted.daily",
	})
	if create.Code != http.StatusCreated {
		t.Fatalf("create status=%d body=%s", create.Code, create.Body.String())
	}
	var created struct {
		Profile models.DistributionProfile `json:"profile"`
	}
	if err := json.Unmarshal(create.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	assign := distributionRequest(t, router, http.MethodPut, "/api/admin/users/"+strconvUint(user.ID)+"/distribution-profile", adminToken, map[string]any{
		"distributionProfileId": created.Profile.ID,
	})
	if assign.Code != http.StatusOK {
		t.Fatalf("assignment status=%d body=%s", assign.Code, assign.Body.String())
	}
	overrideResponse := distributionRequest(t, router, http.MethodGet, "/api/app-distribution", userToken, nil)
	if overrideResponse.Code != http.StatusOK || !strings.Contains(overrideResponse.Body.String(), `"enabled":false`) {
		t.Fatalf("override response status=%d body=%s", overrideResponse.Code, overrideResponse.Body.String())
	}
	deleteAssigned := distributionRequest(t, router, http.MethodDelete, "/api/admin/distribution/profiles/"+strconvUint(created.Profile.ID), adminToken, nil)
	if deleteAssigned.Code != http.StatusConflict || !strings.Contains(deleteAssigned.Body.String(), "profile_assigned") {
		t.Fatalf("assigned delete status=%d body=%s", deleteAssigned.Code, deleteAssigned.Body.String())
	}
	audit := distributionRequest(t, router, http.MethodGet, "/api/admin/distribution/audit", adminToken, nil)
	if audit.Code != http.StatusOK || !strings.Contains(audit.Body.String(), "user_assignment_changed") || !strings.Contains(audit.Body.String(), "profile_delete_attempt") {
		t.Fatalf("audit status=%d body=%s", audit.Code, audit.Body.String())
	}
}

func TestDistributionClientEndpointNeverFetchesConfiguredSource(t *testing.T) {
	server, _, user := newDistributionAPITestServer(t)
	hits := 0
	external := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		hits++
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"schemaVersion":1,"latest":"9.9.9","releases":[{"version":"9.9.9"}]}`))
	}))
	defer external.Close()
	if err := server.DB.Model(&models.DistributionProfile{}).Where("is_default = ?", true).
		Update("release_index_url", external.URL).Error; err != nil {
		t.Fatal(err)
	}

	response := distributionRequest(t, server.Router(), http.MethodGet, "/api/app-distribution", distributionToken(t, server, user), nil)
	if response.Code != http.StatusOK {
		t.Fatalf("client distribution status=%d body=%s", response.Code, response.Body.String())
	}
	if hits != 0 {
		t.Fatalf("client endpoint performed %d external request(s)", hits)
	}
}

func TestDistributionDefaultSwitchAndInvariantThroughAPI(t *testing.T) {
	server, admin, _ := newDistributionAPITestServer(t)
	router := server.Router()
	adminToken := distributionToken(t, server, admin)
	create := distributionRequest(t, router, http.MethodPost, "/api/admin/distribution/profiles", adminToken, map[string]any{
		"name": "New default", "enabled": true, "isDefault": true,
		"sourceMode": "manifest", "channel": "stable",
		"projectUrl": "https://example.org/daily", "releaseIndexUrl": "https://example.org/releases/index.json",
		"expectedPackageName": "com.selfhosted.daily",
	})
	if create.Code != http.StatusCreated {
		t.Fatalf("create default status=%d body=%s", create.Code, create.Body.String())
	}
	var created struct {
		Profile models.DistributionProfile `json:"profile"`
	}
	if err := json.Unmarshal(create.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	var defaults int64
	if err := server.DB.Model(&models.DistributionProfile{}).Where("is_default = ? AND enabled = ?", true, true).Count(&defaults).Error; err != nil {
		t.Fatal(err)
	}
	if defaults != 1 || !created.Profile.IsDefault {
		t.Fatalf("default count=%d profile=%+v", defaults, created.Profile)
	}
	update := distributionRequest(t, router, http.MethodPut, "/api/admin/distribution/profiles/"+strconvUint(created.Profile.ID), adminToken, map[string]any{
		"name": created.Profile.Name, "enabled": false, "isDefault": false,
		"sourceMode": "disabled", "channel": created.Profile.Channel,
		"expectedPackageName": created.Profile.ExpectedPackageName,
	})
	if update.Code != http.StatusConflict || !strings.Contains(update.Body.String(), "default_invariant") {
		t.Fatalf("default disable status=%d body=%s", update.Code, update.Body.String())
	}
	deleteDefault := distributionRequest(t, router, http.MethodDelete, "/api/admin/distribution/profiles/"+strconvUint(created.Profile.ID), adminToken, nil)
	if deleteDefault.Code != http.StatusConflict {
		t.Fatalf("default delete status=%d body=%s", deleteDefault.Code, deleteDefault.Body.String())
	}
}

func TestDistributionProfileForeignKeyRejectsUnknownAssignment(t *testing.T) {
	server, admin, user := newDistributionAPITestServer(t)
	response := distributionRequest(t, server.Router(), http.MethodPut, "/api/admin/users/"+strconvUint(user.ID)+"/distribution-profile", distributionToken(t, server, admin), map[string]any{
		"distributionProfileId": 999999,
	})
	if response.Code != http.StatusBadRequest {
		t.Fatalf("unknown assignment status=%d body=%s", response.Code, response.Body.String())
	}
	var stored models.User
	if err := server.DB.First(&stored, user.ID).Error; err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		t.Fatal(err)
	}
	if stored.DistributionProfileID != nil {
		t.Fatalf("unknown profile assignment was stored: %v", *stored.DistributionProfileID)
	}
}

func TestDistributionMutationRollsBackWhenAuditInsertFails(t *testing.T) {
	server, admin, _ := newDistributionAPITestServer(t)
	if err := server.DB.Exec(`CREATE TRIGGER fail_distribution_audit
		BEFORE INSERT ON distribution_profile_audit
		BEGIN SELECT RAISE(ABORT, 'injected audit failure'); END`).Error; err != nil {
		t.Fatal(err)
	}
	response := distributionRequest(t, server.Router(), http.MethodPost, "/api/admin/distribution/profiles", distributionToken(t, server, admin), map[string]any{
		"name": "Must roll back", "enabled": true, "isDefault": false,
		"sourceMode": "manifest", "channel": "stable",
		"releaseIndexUrl":     "https://example.org/releases/index.json",
		"expectedPackageName": "com.selfhosted.daily",
	})
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("audit failure status=%d body=%s", response.Code, response.Body.String())
	}
	var profiles int64
	if err := server.DB.Model(&models.DistributionProfile{}).Where("name = ?", "Must roll back").Count(&profiles).Error; err != nil {
		t.Fatal(err)
	}
	if profiles != 0 {
		t.Fatalf("profile mutation committed without audit: count=%d", profiles)
	}
}

func strconvUint(value uint) string {
	return fmt.Sprintf("%d", value)
}
