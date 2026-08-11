package db

import (
	"database/sql"
	"fmt"
	"path/filepath"
	"strings"
	"testing"

	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
)

func createLegacyDistributionTestDB(t *testing.T, path string) {
	t.Helper()
	database, err := sql.Open("sqlite3", path)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	statements := []string{
		`CREATE TABLE users (
			id integer PRIMARY KEY AUTOINCREMENT,
			username text NOT NULL UNIQUE,
			password_hash text NOT NULL,
			auth_version integer NOT NULL DEFAULT 1,
			is_admin numeric NOT NULL DEFAULT 0,
			favorite_color text DEFAULT '#1F5FBF',
			created_at datetime
		)`,
		`INSERT INTO users(id, username, password_hash, auth_version, is_admin, favorite_color, created_at)
			VALUES (41, 'existing-user', 'existing-password-hash', 7, 1, '#123456', '2026-08-01T10:00:00Z')`,
		`CREATE TABLE app_settings (
			id integer PRIMARY KEY AUTOINCREMENT,
			prompt_window_start_hour integer DEFAULT 8,
			prompt_window_end_hour integer DEFAULT 20,
			upload_window_minutes integer DEFAULT 10,
			created_at datetime,
			updated_at datetime
		)`,
		`INSERT INTO app_settings(id, prompt_window_start_hour, prompt_window_end_hour, upload_window_minutes)
			VALUES (1, 9, 19, 12)`,
	}
	for _, statement := range statements {
		if _, err := database.Exec(statement); err != nil {
			if strings.Contains(strings.ToLower(err.Error()), "requires cgo") {
				t.Skipf("sqlite driver requires cgo in this environment: %v", err)
			}
			t.Fatalf("prepare legacy database: %v", err)
		}
	}
}

func connectDistributionTestDB(t *testing.T, path string) *gorm.DB {
	t.Helper()
	database, err := Connect(path)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "requires cgo") {
			t.Skipf("sqlite driver requires cgo in this environment: %v", err)
		}
		t.Fatal(err)
	}
	return database
}

func TestDistributionMigrationSeedsExactlyOneDefaultIdempotently(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "app.db")
	for run := 0; run < 2; run++ {
		database := connectDistributionTestDB(t, dbPath)
		var profiles []models.DistributionProfile
		if err := database.Order("id asc").Find(&profiles).Error; err != nil {
			t.Fatalf("list profiles run %d: %v", run+1, err)
		}
		if len(profiles) != 1 {
			t.Fatalf("profile count run %d = %d, want 1", run+1, len(profiles))
		}
		profile := profiles[0]
		if !profile.Enabled || !profile.IsDefault {
			t.Fatalf("seeded profile must be enabled default: %+v", profile)
		}
		if profile.ReleaseIndexURL != officialDistributionReleaseIndexURL {
			t.Fatalf("release index = %q", profile.ReleaseIndexURL)
		}
		if profile.ExpectedPackageName != officialDistributionPackageName {
			t.Fatalf("package name = %q", profile.ExpectedPackageName)
		}
		if profile.ExpectedSigningCertSHA256 != officialDistributionSigningCertSHA256 {
			t.Fatalf("signing fingerprint = %q", profile.ExpectedSigningCertSHA256)
		}
		sqlDB, err := database.DB()
		if err != nil {
			t.Fatal(err)
		}
		if err := sqlDB.Close(); err != nil {
			t.Fatal(err)
		}
	}
}

func TestDistributionMigrationPreservesExistingUsersAndSettingsAndIsIdempotent(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "legacy-app.db")
	createLegacyDistributionTestDB(t, dbPath)

	for run := 1; run <= 2; run++ {
		database := connectDistributionTestDB(t, dbPath)
		var user models.User
		if err := database.First(&user, 41).Error; err != nil {
			t.Fatalf("load existing user after migration %d: %v", run, err)
		}
		if user.Username != "existing-user" || user.PasswordHash != "existing-password-hash" ||
			user.AuthVersion != 7 || !user.IsAdmin || user.FavoriteColor != "#123456" {
			t.Fatalf("existing user changed after migration %d: %+v", run, user)
		}
		if user.DistributionProfileID != nil {
			t.Fatalf("existing user unexpectedly assigned after migration %d: %v", run, *user.DistributionProfileID)
		}

		var settings models.AppSettings
		if err := database.First(&settings, 1).Error; err != nil {
			t.Fatalf("load existing settings after migration %d: %v", run, err)
		}
		if settings.PromptWindowStartHour != 9 || settings.PromptWindowEndHour != 19 || settings.UploadWindowMinutes != 12 {
			t.Fatalf("existing settings changed after migration %d: %+v", run, settings)
		}

		var profiles, defaults, auditRows int64
		if err := database.Model(&models.DistributionProfile{}).Count(&profiles).Error; err != nil {
			t.Fatal(err)
		}
		if err := database.Model(&models.DistributionProfile{}).Where("is_default = ? AND enabled = ?", true, true).Count(&defaults).Error; err != nil {
			t.Fatal(err)
		}
		if err := database.Model(&models.DistributionAuditEvent{}).Count(&auditRows).Error; err != nil {
			t.Fatal(err)
		}
		if profiles != 1 || defaults != 1 || auditRows != 0 {
			t.Fatalf("migration %d counts profiles=%d defaults=%d audit=%d", run, profiles, defaults, auditRows)
		}
		sqlDB, err := database.DB()
		if err != nil {
			t.Fatal(err)
		}
		if err := sqlDB.Close(); err != nil {
			t.Fatal(err)
		}
	}
}

func TestDistributionDefaultUniqueIndexAndTransactionalSwitch(t *testing.T) {
	database := connectDistributionTestDB(t, filepath.Join(t.TempDir(), "app.db"))
	second := models.DistributionProfile{
		Name:                "Selfhosted Beta",
		Enabled:             false,
		SourceMode:          "disabled",
		Channel:             "beta",
		ExpectedPackageName: officialDistributionPackageName,
	}
	if err := database.Create(&second).Error; err != nil {
		t.Fatal(err)
	}

	invalidDefault := models.DistributionProfile{
		Name:                "Invalid second default",
		Enabled:             false,
		IsDefault:           true,
		SourceMode:          "disabled",
		Channel:             "internal",
		ExpectedPackageName: officialDistributionPackageName,
	}
	if err := database.Create(&invalidDefault).Error; err == nil {
		t.Fatal("partial unique index accepted a second default profile")
	}

	if err := SetDefaultDistributionProfile(database, second.ID); err != nil {
		t.Fatalf("switch default: %v", err)
	}
	var defaults []models.DistributionProfile
	if err := database.Where("is_default = ?", true).Find(&defaults).Error; err != nil {
		t.Fatal(err)
	}
	if len(defaults) != 1 || defaults[0].ID != second.ID || !defaults[0].Enabled {
		t.Fatalf("unexpected defaults after switch: %+v", defaults)
	}
	if err := database.Model(&models.DistributionProfile{}).Where("id = ?", second.ID).Update("enabled", false).Error; err == nil {
		t.Fatal("database trigger allowed disabling the default profile")
	}
}

func TestDistributionDefaultSwitchRollsBackCompletelyOnFailure(t *testing.T) {
	database := connectDistributionTestDB(t, filepath.Join(t.TempDir(), "app.db"))
	var original models.DistributionProfile
	if err := database.Where("is_default = ?", true).First(&original).Error; err != nil {
		t.Fatal(err)
	}
	next := models.DistributionProfile{Name: "Rollback target", Enabled: false, SourceMode: "disabled", Channel: "stable", ExpectedPackageName: officialDistributionPackageName}
	if err := database.Create(&next).Error; err != nil {
		t.Fatal(err)
	}
	trigger := fmt.Sprintf(`CREATE TRIGGER fail_distribution_default_switch
		BEFORE UPDATE OF is_default ON distribution_profiles
		WHEN NEW.id = %d AND NEW.is_default = 1
		BEGIN SELECT RAISE(ABORT, 'injected default switch failure'); END`, next.ID)
	if err := database.Exec(trigger).Error; err != nil {
		t.Fatal(err)
	}
	defer database.Exec(`DROP TRIGGER IF EXISTS fail_distribution_default_switch`)

	if err := SetDefaultDistributionProfile(database, next.ID); err == nil {
		t.Fatal("injected default switch failure was ignored")
	}
	var storedOriginal, storedNext models.DistributionProfile
	if err := database.First(&storedOriginal, original.ID).Error; err != nil {
		t.Fatal(err)
	}
	if err := database.First(&storedNext, next.ID).Error; err != nil {
		t.Fatal(err)
	}
	if !storedOriginal.IsDefault || !storedOriginal.Enabled || storedNext.IsDefault || storedNext.Enabled {
		t.Fatalf("failed switch was not rolled back: original=%+v next=%+v", storedOriginal, storedNext)
	}
}

func TestDistributionProfileAssignmentUsesRestrictForeignKey(t *testing.T) {
	database := connectDistributionTestDB(t, filepath.Join(t.TempDir(), "app.db"))
	profile := models.DistributionProfile{
		Name:                "Assigned",
		Enabled:             true,
		SourceMode:          "manifest",
		Channel:             "stable",
		ReleaseIndexURL:     "https://example.org/index.json",
		ExpectedPackageName: officialDistributionPackageName,
	}
	if err := database.Create(&profile).Error; err != nil {
		t.Fatal(err)
	}
	user := models.User{
		Username:              "distribution-user",
		PasswordHash:          "test",
		AuthVersion:           1,
		DistributionProfileID: &profile.ID,
	}
	if err := database.Create(&user).Error; err != nil {
		t.Fatal(err)
	}
	if err := database.Delete(&profile).Error; err == nil {
		t.Fatal("assigned distribution profile was deleted despite RESTRICT foreign key")
	}
	var stored models.User
	if err := database.First(&stored, user.ID).Error; err != nil {
		t.Fatal(err)
	}
	if stored.DistributionProfileID == nil || *stored.DistributionProfileID != profile.ID {
		t.Fatalf("profile assignment changed after rejected delete: %+v", stored.DistributionProfileID)
	}
	unassigned := models.User{Username: "invalid-assignment-user", PasswordHash: "test", AuthVersion: 1}
	if err := database.Create(&unassigned).Error; err != nil {
		t.Fatal(err)
	}
	if err := database.Model(&models.User{}).Where("id = ?", unassigned.ID).Update("distribution_profile_id", 999999).Error; err == nil {
		t.Fatal("database foreign key accepted a nonexistent distribution profile")
	}
}
