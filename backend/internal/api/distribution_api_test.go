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
	"sync"
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

func createRolloutTestProfile(t *testing.T, server *Server, name string) models.DistributionProfile {
	t.Helper()
	profile := models.DistributionProfile{
		Name: name, Enabled: true, SourceMode: "manifest", Channel: "stable",
		ReleaseIndexURL:     "https://updates.invalid/" + strings.ToLower(strings.ReplaceAll(name, " ", "-")) + "/index.json",
		ExpectedPackageName: "com.selfhosted.daily", Revision: 1,
	}
	if err := server.DB.Create(&profile).Error; err != nil {
		t.Fatal(err)
	}
	return profile
}

func configureRolloutForTest(t *testing.T, server *Server, migration models.DistributionProfile, stable models.DistributionProfile) {
	t.Helper()
	if err := server.DB.Model(&models.DistributionRollout{}).Where("id = ?", distributionRolloutSingletonID).Updates(map[string]any{
		"enabled": true, "migration_profile_id": migration.ID, "stable_profile_id": stable.ID,
		"entry_version_code": int64(142030), "stable_version_code": int64(142031),
	}).Error; err != nil {
		t.Fatal(err)
	}
}

func TestDistributionRolloutAutomaticallyMovesBridgeAndStableClients(t *testing.T) {
	server, _, user := newDistributionAPITestServer(t)
	var stable models.DistributionProfile
	if err := server.DB.Where("is_default = ?", true).First(&stable).Error; err != nil {
		t.Fatal(err)
	}
	migration := createRolloutTestProfile(t, server, "Migration 0.8.31")
	configureRolloutForTest(t, server, migration, stable)
	token := distributionToken(t, server, user)

	bridge := distributionRequest(t, server.Router(), http.MethodGet, "/api/app-distribution?versionName=0.8.30&versionCode=142030", token, nil)
	if bridge.Code != http.StatusOK || !strings.Contains(bridge.Body.String(), `"profileId":`+strconvUint(migration.ID)) {
		t.Fatalf("bridge response status=%d body=%s", bridge.Code, bridge.Body.String())
	}
	var stored models.User
	if err := server.DB.First(&stored, user.ID).Error; err != nil {
		t.Fatal(err)
	}
	if stored.DistributionProfileID == nil || *stored.DistributionProfileID != migration.ID {
		t.Fatalf("bridge assignment = %v, want %d", stored.DistributionProfileID, migration.ID)
	}
	var state models.DistributionClientState
	if err := server.DB.First(&state, user.ID).Error; err != nil {
		t.Fatal(err)
	}
	if state.VersionCode != 142030 || state.VersionName != "0.8.30" || state.Phase != "migration" {
		t.Fatalf("bridge state = %+v", state)
	}

	// Repeated checks update last-seen state but never duplicate transition audit.
	distributionRequest(t, server.Router(), http.MethodGet, "/api/app-distribution?versionName=0.8.30&versionCode=142030", token, nil)
	var auditCount int64
	if err := server.DB.Model(&models.DistributionAuditEvent{}).Where("target_user_id = ?", user.ID).Count(&auditCount).Error; err != nil {
		t.Fatal(err)
	}
	if auditCount != 1 {
		t.Fatalf("bridge audit count = %d, want 1", auditCount)
	}

	stableResponse := distributionRequest(t, server.Router(), http.MethodGet, "/api/app-distribution?versionName=0.8.31&versionCode=142031", token, nil)
	if stableResponse.Code != http.StatusOK || !strings.Contains(stableResponse.Body.String(), `"profileId":`+strconvUint(stable.ID)) {
		t.Fatalf("stable response status=%d body=%s", stableResponse.Code, stableResponse.Body.String())
	}
	if err := server.DB.First(&stored, user.ID).Error; err != nil {
		t.Fatal(err)
	}
	if stored.DistributionProfileID != nil {
		t.Fatalf("stable client retained override: %v", *stored.DistributionProfileID)
	}
	if err := server.DB.First(&state, user.ID).Error; err != nil {
		t.Fatal(err)
	}
	if state.VersionCode != 142031 || state.Phase != "stable" {
		t.Fatalf("stable state = %+v", state)
	}
	distributionRequest(t, server.Router(), http.MethodGet, "/api/app-distribution?versionName=0.8.31&versionCode=142031", token, nil)
	if err := server.DB.Model(&models.DistributionAuditEvent{}).Where("target_user_id = ?", user.ID).Count(&auditCount).Error; err != nil {
		t.Fatal(err)
	}
	if auditCount != 2 {
		t.Fatalf("transition audit count = %d, want 2", auditCount)
	}
}

func TestDistributionRolloutPreservesManualOverridesAndRejectsInvalidReports(t *testing.T) {
	server, _, user := newDistributionAPITestServer(t)
	var stable models.DistributionProfile
	if err := server.DB.Where("is_default = ?", true).First(&stable).Error; err != nil {
		t.Fatal(err)
	}
	migration := createRolloutTestProfile(t, server, "Migration")
	manual := createRolloutTestProfile(t, server, "Manual")
	configureRolloutForTest(t, server, migration, stable)
	if err := server.DB.Model(&models.User{}).Where("id = ?", user.ID).Update("distribution_profile_id", manual.ID).Error; err != nil {
		t.Fatal(err)
	}
	token := distributionToken(t, server, user)

	response := distributionRequest(t, server.Router(), http.MethodGet, "/api/app-distribution?versionName=0.8.30&versionCode=142030", token, nil)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"profileId":`+strconvUint(manual.ID)) {
		t.Fatalf("manual response status=%d body=%s", response.Code, response.Body.String())
	}
	var state models.DistributionClientState
	if err := server.DB.First(&state, user.ID).Error; err != nil || state.Phase != "manual_override" {
		t.Fatalf("manual state err=%v state=%+v", err, state)
	}

	invalid := distributionRequest(t, server.Router(), http.MethodGet, "/api/app-distribution?versionName=bad&versionCode=0", token, nil)
	if invalid.Code != http.StatusBadRequest || !strings.Contains(invalid.Body.String(), "invalid_client_version") {
		t.Fatalf("invalid report status=%d body=%s", invalid.Code, invalid.Body.String())
	}
	missingCode := distributionRequest(t, server.Router(), http.MethodGet, "/api/app-distribution?versionName=0.8.31", token, nil)
	if missingCode.Code != http.StatusBadRequest {
		t.Fatalf("missing code status=%d body=%s", missingCode.Code, missingCode.Body.String())
	}
	var stored models.User
	if err := server.DB.First(&stored, user.ID).Error; err != nil {
		t.Fatal(err)
	}
	if stored.DistributionProfileID == nil || *stored.DistributionProfileID != manual.ID {
		t.Fatalf("invalid report changed manual assignment: %v", stored.DistributionProfileID)
	}
}

func TestDistributionRolloutAdminConfigurationIsValidatedAndRevisionGuarded(t *testing.T) {
	server, admin, _ := newDistributionAPITestServer(t)
	var stable models.DistributionProfile
	if err := server.DB.Where("is_default = ?", true).First(&stable).Error; err != nil {
		t.Fatal(err)
	}
	migration := createRolloutTestProfile(t, server, "Migration")
	token := distributionToken(t, server, admin)
	router := server.Router()

	bad := distributionRequest(t, router, http.MethodPut, "/api/admin/distribution/rollout", token, map[string]any{
		"enabled": true, "migrationProfileId": migration.ID, "stableProfileId": stable.ID,
		"entryVersionCode": 142031, "stableVersionCode": 142030, "expectedRevision": 1,
	})
	if bad.Code != http.StatusBadRequest || !strings.Contains(bad.Body.String(), "invalid_rollout") {
		t.Fatalf("invalid rollout status=%d body=%s", bad.Code, bad.Body.String())
	}

	goodPayload := map[string]any{
		"enabled": true, "migrationProfileId": migration.ID, "stableProfileId": stable.ID,
		"entryVersionCode": 142030, "stableVersionCode": 142031, "expectedRevision": 1,
	}
	good := distributionRequest(t, router, http.MethodPut, "/api/admin/distribution/rollout", token, goodPayload)
	if good.Code != http.StatusOK || !strings.Contains(good.Body.String(), `"revision":2`) {
		t.Fatalf("valid rollout status=%d body=%s", good.Code, good.Body.String())
	}
	conflict := distributionRequest(t, router, http.MethodPut, "/api/admin/distribution/rollout", token, goodPayload)
	if conflict.Code != http.StatusConflict || !strings.Contains(conflict.Body.String(), "revision_conflict") {
		t.Fatalf("rollout conflict status=%d body=%s", conflict.Code, conflict.Body.String())
	}
	status := distributionRequest(t, router, http.MethodGet, "/api/admin/distribution/rollout", token, nil)
	if status.Code != http.StatusOK || !strings.Contains(status.Body.String(), `"unknown":2`) {
		t.Fatalf("rollout status=%d body=%s", status.Code, status.Body.String())
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
		"expectedRevision": created.Profile.Revision,
		"name":             created.Profile.Name, "enabled": false, "isDefault": false,
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
		"expectedRevision": created.Profile.Revision,
		"name":             "Changed by rejected update", "enabled": true, "isDefault": false,
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

func TestDistributionUpdateRejectsStaleRevisionWithoutLostUpdate(t *testing.T) {
	server, admin, _ := newDistributionAPITestServer(t)
	router := server.Router()
	token := distributionToken(t, server, admin)
	create := distributionRequest(t, router, http.MethodPost, "/api/admin/distribution/profiles", token, map[string]any{
		"name": "Concurrent", "enabled": true, "isDefault": false, "sourceMode": "manifest", "channel": "stable",
		"releaseIndexUrl": "https://example.org/index.json", "expectedPackageName": "com.selfhosted.daily",
	})
	var created struct {
		Profile models.DistributionProfile `json:"profile"`
	}
	if create.Code != http.StatusCreated || json.Unmarshal(create.Body.Bytes(), &created) != nil {
		t.Fatalf("create=%d %s", create.Code, create.Body.String())
	}
	payload := func(name string) map[string]any {
		return map[string]any{
			"expectedRevision": created.Profile.Revision, "name": name, "enabled": true, "isDefault": false,
			"sourceMode": "manifest", "channel": "stable", "releaseIndexUrl": "https://example.org/index.json",
			"expectedPackageName": "com.selfhosted.daily",
		}
	}
	start := make(chan struct{})
	responses := make(chan *httptest.ResponseRecorder, 2)
	var wait sync.WaitGroup
	for _, name := range []string{"Writer A", "Writer B"} {
		name := name
		encoded, err := json.Marshal(payload(name))
		if err != nil {
			t.Fatal(err)
		}
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			request := httptest.NewRequest(http.MethodPut, "/api/admin/distribution/profiles/"+strconvUint(created.Profile.ID), bytes.NewReader(encoded))
			request.Header.Set("Content-Type", "application/json")
			request.Header.Set("Authorization", "Bearer "+token)
			response := httptest.NewRecorder()
			router.ServeHTTP(response, request)
			responses <- response
		}()
	}
	close(start)
	wait.Wait()
	close(responses)
	statusCounts := map[int]int{}
	for response := range responses {
		statusCounts[response.Code]++
		if response.Code == http.StatusConflict && !strings.Contains(response.Body.String(), "revision_conflict") {
			t.Fatalf("conflict body=%s", response.Body.String())
		}
	}
	if statusCounts[http.StatusOK] != 1 || statusCounts[http.StatusConflict] != 1 {
		t.Fatalf("status counts=%v", statusCounts)
	}
	var stored models.DistributionProfile
	if err := server.DB.First(&stored, created.Profile.ID).Error; err != nil {
		t.Fatal(err)
	}
	if (stored.Name != "Writer A" && stored.Name != "Writer B") || stored.Revision != created.Profile.Revision+1 {
		t.Fatalf("stored=%+v", stored)
	}
	var audit models.DistributionAuditEvent
	if err := server.DB.Where("action = ? AND profile_id = ?", "profile_updated", stored.ID).Order("id desc").First(&audit).Error; err != nil {
		t.Fatal(err)
	}
	before := distributionAuditJSON(audit.BeforeJSON).(map[string]any)
	if before["name"] != "Concurrent" {
		t.Fatalf("audit before=%v", before)
	}
}

func TestDistributionConcurrentDefaultSwitchKeepsSingleEnabledDefault(t *testing.T) {
	server, admin, _ := newDistributionAPITestServer(t)
	router := server.Router()
	token := distributionToken(t, server, admin)
	profiles := make([]models.DistributionProfile, 0, 2)
	for _, name := range []string{"Default A", "Default B"} {
		created := distributionRequest(t, router, http.MethodPost, "/api/admin/distribution/profiles", token, map[string]any{
			"name": name, "enabled": true, "isDefault": false, "sourceMode": "manifest", "channel": "stable",
			"releaseIndexUrl": "https://example.org/index.json", "expectedPackageName": "com.selfhosted.daily",
		})
		var response struct {
			Profile models.DistributionProfile `json:"profile"`
		}
		if created.Code != http.StatusCreated || json.Unmarshal(created.Body.Bytes(), &response) != nil {
			t.Fatalf("create=%d %s", created.Code, created.Body.String())
		}
		profiles = append(profiles, response.Profile)
	}
	start := make(chan struct{})
	statuses := make(chan int, 2)
	var wait sync.WaitGroup
	for _, profile := range profiles {
		profile := profile
		payload, _ := json.Marshal(map[string]any{
			"expectedRevision": profile.Revision, "name": profile.Name, "enabled": true, "isDefault": true,
			"sourceMode": "manifest", "channel": "stable", "releaseIndexUrl": profile.ReleaseIndexURL,
			"expectedPackageName": profile.ExpectedPackageName,
		})
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			request := httptest.NewRequest(http.MethodPut, "/api/admin/distribution/profiles/"+strconvUint(profile.ID), bytes.NewReader(payload))
			request.Header.Set("Content-Type", "application/json")
			request.Header.Set("Authorization", "Bearer "+token)
			response := httptest.NewRecorder()
			router.ServeHTTP(response, request)
			statuses <- response.Code
		}()
	}
	close(start)
	wait.Wait()
	close(statuses)
	for status := range statuses {
		if status != http.StatusOK {
			t.Fatalf("unexpected concurrent default status=%d", status)
		}
	}
	var defaults int64
	if err := server.DB.Model(&models.DistributionProfile{}).Where("is_default = ? AND enabled = ?", true, true).Count(&defaults).Error; err != nil {
		t.Fatal(err)
	}
	if defaults != 1 {
		t.Fatalf("enabled defaults=%d", defaults)
	}
}

func TestDistributionDraftTestAndRejectedAttemptFailWhenAuditFails(t *testing.T) {
	server, admin, _ := newDistributionAPITestServer(t)
	token := distributionToken(t, server, admin)
	if err := server.DB.Exec(`CREATE TRIGGER fail_distribution_audit_p2 BEFORE INSERT ON distribution_profile_audit BEGIN SELECT RAISE(ABORT, 'audit unavailable'); END`).Error; err != nil {
		t.Fatal(err)
	}
	draft := distributionRequest(t, server.Router(), http.MethodPost, "/api/admin/distribution/test", token, map[string]any{
		"name": "Draft", "enabled": false, "sourceMode": "disabled", "channel": "stable", "expectedPackageName": "com.selfhosted.daily",
	})
	if draft.Code != http.StatusInternalServerError || strings.Contains(draft.Body.String(), `"result"`) {
		t.Fatalf("draft=%d %s", draft.Code, draft.Body.String())
	}
	var current models.DistributionProfile
	if err := server.DB.Where("is_default = ?", true).First(&current).Error; err != nil {
		t.Fatal(err)
	}
	rejected := distributionRequest(t, server.Router(), http.MethodPut, "/api/admin/distribution/profiles/"+strconvUint(current.ID), token, map[string]any{
		"expectedRevision": current.Revision, "name": current.Name, "enabled": false, "isDefault": false,
		"sourceMode": "disabled", "channel": current.Channel, "expectedPackageName": current.ExpectedPackageName,
	})
	if rejected.Code != http.StatusInternalServerError {
		t.Fatalf("rejected=%d %s", rejected.Code, rejected.Body.String())
	}
}

func TestDistributionAuditIsAppendOnlyAndReadable(t *testing.T) {
	server, admin, _ := newDistributionAPITestServer(t)
	token := distributionToken(t, server, admin)
	created := distributionRequest(t, server.Router(), http.MethodPost, "/api/admin/distribution/profiles", token, map[string]any{
		"name": "Audited", "enabled": false, "sourceMode": "disabled", "channel": "stable", "expectedPackageName": "com.selfhosted.daily",
	})
	if created.Code != http.StatusCreated {
		t.Fatalf("create=%d %s", created.Code, created.Body.String())
	}
	var row models.DistributionAuditEvent
	if err := server.DB.Order("id desc").First(&row).Error; err != nil {
		t.Fatal(err)
	}
	if err := server.DB.Model(&row).Update("error_class", "tampered").Error; err == nil {
		t.Fatal("audit UPDATE succeeded")
	}
	if err := server.DB.Delete(&row).Error; err == nil {
		t.Fatal("audit DELETE succeeded")
	}
	read := distributionRequest(t, server.Router(), http.MethodGet, "/api/admin/distribution/audit", token, nil)
	if read.Code != http.StatusOK || !strings.Contains(read.Body.String(), "profile_created") {
		t.Fatalf("read=%d %s", read.Code, read.Body.String())
	}
}

func TestDistributionAdminBodyLimit(t *testing.T) {
	server, admin, _ := newDistributionAPITestServer(t)
	response := distributionRequest(t, server.Router(), http.MethodPost, "/api/admin/distribution/test", distributionToken(t, server, admin), map[string]any{
		"name": strings.Repeat("x", distributionAdminBodyLimit), "enabled": false, "sourceMode": "disabled", "channel": "stable",
	})
	if response.Code != http.StatusRequestEntityTooLarge || !strings.Contains(response.Body.String(), "payload_too_large") {
		t.Fatalf("response=%d %s", response.Code, response.Body.String())
	}
}

func strconvUint(value uint) string {
	return fmt.Sprintf("%d", value)
}
