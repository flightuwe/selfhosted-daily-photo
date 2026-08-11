package db

import (
	"path/filepath"
	"strings"
	"testing"

	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
)

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
}
