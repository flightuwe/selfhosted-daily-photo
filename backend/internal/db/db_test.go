package db

import (
	"path/filepath"
	"strings"
	"testing"

	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

func TestEnsureFotomojiTemplateVersionBackfill(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "app.db")
	database, err := gorm.Open(sqlite.Open(dbPath), &gorm.Config{})
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "requires cgo") {
			t.Skipf("sqlite driver requires cgo in this environment: %v", err)
		}
		t.Fatalf("open sqlite: %v", err)
	}
	if err := database.AutoMigrate(&models.UserFotomojiTemplate{}, &models.UserFotomojiTemplateVersion{}); err != nil {
		t.Fatalf("automigrate: %v", err)
	}

	legacy := models.UserFotomojiTemplate{
		UserID:   10,
		Emoji:    "❤️",
		FilePath: "legacy/path.jpg",
	}
	if err := database.Create(&legacy).Error; err != nil {
		t.Fatalf("create legacy template: %v", err)
	}
	already := models.UserFotomojiTemplate{
		UserID:          11,
		Emoji:           "🔥",
		FilePath:        "already/path.jpg",
		ActiveVersionID: 42,
	}
	if err := database.Create(&already).Error; err != nil {
		t.Fatalf("create existing template: %v", err)
	}

	if err := ensureFotomojiTemplateVersionBackfill(database); err != nil {
		t.Fatalf("first backfill: %v", err)
	}

	var updated models.UserFotomojiTemplate
	if err := database.Where("id = ?", legacy.ID).First(&updated).Error; err != nil {
		t.Fatalf("load updated template: %v", err)
	}
	if updated.ActiveVersionID == 0 {
		t.Fatalf("active version id not set after backfill")
	}

	var legacyVersion models.UserFotomojiTemplateVersion
	if err := database.Where("id = ?", updated.ActiveVersionID).First(&legacyVersion).Error; err != nil {
		t.Fatalf("load created version: %v", err)
	}
	if legacyVersion.FilePath != legacy.FilePath || legacyVersion.Emoji != legacy.Emoji || legacyVersion.UserID != legacy.UserID {
		t.Fatalf("created version mismatch: %+v", legacyVersion)
	}

	var firstCount int64
	if err := database.Model(&models.UserFotomojiTemplateVersion{}).Count(&firstCount).Error; err != nil {
		t.Fatalf("count versions: %v", err)
	}

	if err := ensureFotomojiTemplateVersionBackfill(database); err != nil {
		t.Fatalf("second backfill: %v", err)
	}
	var secondCount int64
	if err := database.Model(&models.UserFotomojiTemplateVersion{}).Count(&secondCount).Error; err != nil {
		t.Fatalf("count versions after rerun: %v", err)
	}
	if secondCount != firstCount {
		t.Fatalf("backfill not idempotent: first=%d second=%d", firstCount, secondCount)
	}

	var preserved models.UserFotomojiTemplate
	if err := database.Where("id = ?", already.ID).First(&preserved).Error; err != nil {
		t.Fatalf("load preserved template: %v", err)
	}
	if preserved.ActiveVersionID != already.ActiveVersionID {
		t.Fatalf("existing active version id changed: got %d want %d", preserved.ActiveVersionID, already.ActiveVersionID)
	}
}
