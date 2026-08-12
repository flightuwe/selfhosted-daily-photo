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

func TestDistributionProfileCreatePersistsExplicitEnabledValue(t *testing.T) {
	server, admin, _ := newDistributionAPITestServer(t)
	router := server.Router()
	adminToken := distributionToken(t, server, admin)
	validHash := strings.Repeat("ab", 32)
	tests := []struct {
		name        string
		payload     map[string]any
		wantEnabled bool
	}{
		{
			name: "disabled manifest",
			payload: map[string]any{
				"name": "Disabled manifest", "enabled": false, "isDefault": false,
				"sourceMode": "manifest", "channel": "stable", "releaseIndexUrl": "https://example.org/index.json",
				"expectedPackageName": "com.selfhosted.daily",
			},
		},
		{
			name: "disabled direct APK",
			payload: map[string]any{
				"name": "Disabled direct", "enabled": false, "isDefault": false,
				"sourceMode": "direct", "channel": "stable", "directApkUrl": "https://example.org/app.apk",
				"directApkVersionName": "1.2.3", "directApkVersionCode": 123, "directApkSha256": validHash,
				"expectedPackageName": "com.selfhosted.daily",
			},
		},
		{
			name: "disabled mode overrides contradictory input",
			payload: map[string]any{
				"name": "Disabled mode", "enabled": true, "isDefault": false,
				"sourceMode": "disabled", "channel": "stable", "expectedPackageName": "com.selfhosted.daily",
			},
		},
		{
			name: "enabled non-default",
			payload: map[string]any{
				"name": "Enabled manifest", "enabled": true, "isDefault": false,
				"sourceMode": "manifest", "channel": "stable", "releaseIndexUrl": "https://example.org/index.json",
				"expectedPackageName": "com.selfhosted.daily",
			},
			wantEnabled: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			response := distributionRequest(t, router, http.MethodPost, "/api/admin/distribution/profiles", adminToken, tc.payload)
			if response.Code != http.StatusCreated {
				t.Fatalf("create status=%d body=%s", response.Code, response.Body.String())
			}
			var created struct {
				Profile       models.DistributionProfile `json:"profile"`
				ClientPreview map[string]any             `json:"clientPreview"`
			}
			if err := json.Unmarshal(response.Body.Bytes(), &created); err != nil {
				t.Fatal(err)
			}
			var stored models.DistributionProfile
			if err := server.DB.First(&stored, created.Profile.ID).Error; err != nil {
				t.Fatal(err)
			}
			if created.Profile.Enabled != tc.wantEnabled || stored.Enabled != tc.wantEnabled {
				t.Fatalf("enabled response=%v stored=%v want=%v", created.Profile.Enabled, stored.Enabled, tc.wantEnabled)
			}
			if got, ok := created.ClientPreview["enabled"].(bool); !ok || got != tc.wantEnabled {
				t.Fatalf("client preview enabled=%v want=%v", created.ClientPreview["enabled"], tc.wantEnabled)
			}
			if stored.IsDefault {
				t.Fatalf("non-default profile persisted as default: %+v", stored)
			}
		})
	}
}

func TestDistributionProfileInsertSQLIncludesFalseEnabled(t *testing.T) {
	server, _, _ := newDistributionAPITestServer(t)
	profile := models.DistributionProfile{
		Name: "Dry run", Enabled: false, IsDefault: true, SourceMode: "manifest", Channel: "stable",
		AllowPrerelease:     true,
		ExpectedPackageName: "com.selfhosted.daily",
	}
	result := insertDistributionProfile(server.DB.Session(&gorm.Session{DryRun: true}), &profile)
	if result.Error != nil {
		t.Fatal(result.Error)
	}
	sql := strings.ToLower(result.Statement.SQL.String())
	if !strings.Contains(sql, "enabled") || !strings.Contains(sql, "insert") {
		t.Fatalf("INSERT does not explicitly include enabled: %s", sql)
	}
	explainedSQL := strings.ToLower(server.DB.Dialector.Explain(result.Statement.SQL.String(), result.Statement.Vars...))
	if !strings.Contains(explainedSQL, "false") {
		t.Fatalf("INSERT does not bind enabled=false: %s", explainedSQL)
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
	var storedDefault models.DistributionProfile
	if err := server.DB.First(&storedDefault, created.Profile.ID).Error; err != nil {
		t.Fatal(err)
	}
	if !storedDefault.Enabled || !storedDefault.IsDefault {
		t.Fatalf("new default was not persisted enabled and default: %+v", storedDefault)
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

func TestDistributionCreateRollsBackWhenDefaultSwitchFails(t *testing.T) {
	server, admin, _ := newDistributionAPITestServer(t)
	if err := server.DB.Exec(`CREATE TRIGGER fail_distribution_default_switch
		BEFORE UPDATE OF is_default ON distribution_profiles
		WHEN OLD.is_default = 1 AND NEW.is_default = 0
		BEGIN SELECT RAISE(ABORT, 'injected default switch failure'); END`).Error; err != nil {
		t.Fatal(err)
	}
	response := distributionRequest(t, server.Router(), http.MethodPost, "/api/admin/distribution/profiles", distributionToken(t, server, admin), map[string]any{
		"name": "Must roll back default", "enabled": true, "isDefault": true,
		"sourceMode": "manifest", "channel": "stable", "releaseIndexUrl": "https://example.org/index.json",
		"expectedPackageName": "com.selfhosted.daily",
	})
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("default switch failure status=%d body=%s", response.Code, response.Body.String())
	}
	var profiles int64
	if err := server.DB.Model(&models.DistributionProfile{}).Where("name = ?", "Must roll back default").Count(&profiles).Error; err != nil {
		t.Fatal(err)
	}
	if profiles != 0 {
		t.Fatalf("profile remained after failed default switch: count=%d", profiles)
	}
	var defaults int64
	if err := server.DB.Model(&models.DistributionProfile{}).Where("is_default = ? AND enabled = ?", true, true).Count(&defaults).Error; err != nil {
		t.Fatal(err)
	}
	if defaults != 1 {
		t.Fatalf("default invariant changed after rollback: %d", defaults)
	}
}

func TestDistributionQueryURLsAreNeverPersisted(t *testing.T) {
	server, admin, _ := newDistributionAPITestServer(t)
	router := server.Router()
	adminToken := distributionToken(t, server, admin)
	createRejected := distributionRequest(t, router, http.MethodPost, "/api/admin/distribution/profiles", adminToken, map[string]any{
		"name": "Rejected query create", "enabled": true, "isDefault": false,
		"sourceMode": "manifest", "channel": "stable", "releaseIndexUrl": "https://example.org/index.json?api_key=placeholder",
		"expectedPackageName": "com.selfhosted.daily",
	})
	if createRejected.Code != http.StatusBadRequest || !strings.Contains(createRejected.Body.String(), "url_query_not_allowed") {
		t.Fatalf("query create status=%d body=%s", createRejected.Code, createRejected.Body.String())
	}
	var rejectedCount int64
	if err := server.DB.Model(&models.DistributionProfile{}).Where("name = ?", "Rejected query create").Count(&rejectedCount).Error; err != nil {
		t.Fatal(err)
	}
	if rejectedCount != 0 {
		t.Fatalf("rejected query profile persisted: %d", rejectedCount)
	}

	createAllowed := distributionRequest(t, router, http.MethodPost, "/api/admin/distribution/profiles", adminToken, map[string]any{
		"name": "Stable existing profile", "enabled": true, "isDefault": false,
		"sourceMode": "manifest", "channel": "stable", "releaseIndexUrl": "https://example.org/index.json",
		"expectedPackageName": "com.selfhosted.daily",
	})
	if createAllowed.Code != http.StatusCreated {
		t.Fatalf("allowed create status=%d body=%s", createAllowed.Code, createAllowed.Body.String())
	}
	var created struct {
		Profile models.DistributionProfile `json:"profile"`
	}
	if err := json.Unmarshal(createAllowed.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	updateRejected := distributionRequest(t, router, http.MethodPut, "/api/admin/distribution/profiles/"+strconvUint(created.Profile.ID), adminToken, map[string]any{
		"name": "Changed by rejected update", "enabled": true, "isDefault": false,
		"sourceMode": "manifest", "channel": "stable", "releaseIndexUrl": "https://example.org/index.json?token=placeholder",
		"expectedPackageName": "com.selfhosted.daily",
	})
	if updateRejected.Code != http.StatusBadRequest || !strings.Contains(updateRejected.Body.String(), "url_query_not_allowed") {
		t.Fatalf("query update status=%d body=%s", updateRejected.Code, updateRejected.Body.String())
	}
	var stored models.DistributionProfile
	if err := server.DB.First(&stored, created.Profile.ID).Error; err != nil {
		t.Fatal(err)
	}
	if stored.Name != created.Profile.Name || stored.ReleaseIndexURL != created.Profile.ReleaseIndexURL {
		t.Fatalf("rejected query update changed profile: before=%+v after=%+v", created.Profile, stored)
	}
}

func strconvUint(value uint) string {
	return fmt.Sprintf("%d", value)
}
