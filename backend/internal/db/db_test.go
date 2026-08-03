package db

import (
	"path/filepath"
	"strings"
	"testing"
	"time"

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

func TestEnsurePhotoPublicNumbersBackfill(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "app.db")
	database, err := gorm.Open(sqlite.Open(dbPath), &gorm.Config{})
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "requires cgo") {
			t.Skipf("sqlite driver requires cgo in this environment: %v", err)
		}
		t.Fatalf("open sqlite: %v", err)
	}
	if err := database.AutoMigrate(&models.User{}, &models.Photo{}); err != nil {
		t.Fatalf("automigrate: %v", err)
	}

	photos := []models.Photo{
		{UserID: 1, Day: "2026-05-26", FilePath: "a.jpg", CreatedAt: time.Date(2026, 5, 26, 10, 0, 0, 0, time.UTC)},
		{UserID: 1, Day: "2026-05-26", FilePath: "b.jpg", CreatedAt: time.Date(2026, 5, 26, 11, 0, 0, 0, time.UTC)},
		{UserID: 1, Day: "2026-05-27", FilePath: "c.jpg", CreatedAt: time.Date(2026, 5, 27, 9, 0, 0, 0, time.UTC)},
	}
	for _, photo := range photos {
		if err := database.Create(&photo).Error; err != nil {
			t.Fatalf("create photo: %v", err)
		}
	}

	if err := ensurePhotoPublicNumbers(database); err != nil {
		t.Fatalf("ensurePhotoPublicNumbers: %v", err)
	}

	var stored []models.Photo
	if err := database.Order("day asc, created_at asc, id asc").Find(&stored).Error; err != nil {
		t.Fatalf("load photos: %v", err)
	}
	want := []string{"260526001", "260526002", "260527001"}
	for i, photo := range stored {
		if photo.PublicNumber == nil || *photo.PublicNumber != want[i] {
			got := "<nil>"
			if photo.PublicNumber != nil {
				got = *photo.PublicNumber
			}
			t.Fatalf("photo %d public number = %q, want %q", i, got, want[i])
		}
	}
}

func TestEnsureCalendarQueryIndexesSupportVisibleDayPlan(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "app.db")
	database, err := gorm.Open(sqlite.Open(dbPath), &gorm.Config{})
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "requires cgo") {
			t.Skipf("sqlite runtime unavailable: %v", err)
		}
		t.Fatal(err)
	}
	if err := database.AutoMigrate(&models.Photo{}); err != nil {
		t.Fatal(err)
	}
	if err := ensureCalendarQueryIndexes(database); err != nil {
		t.Fatal(err)
	}

	var indexes []struct {
		Name string `gorm:"column:name"`
	}
	if err := database.Raw("PRAGMA index_list('photos')").Scan(&indexes).Error; err != nil {
		t.Fatal(err)
	}
	joined := ""
	for _, index := range indexes {
		joined += index.Name + " "
	}
	for _, want := range []string{"idx_photos_calendar_user_day", "idx_photos_calendar_visibility_day"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("calendar index %q missing from %q", want, joined)
		}
	}

	var plan []struct {
		Detail string `gorm:"column:detail"`
	}
	err = database.Raw(`EXPLAIN QUERY PLAN
		WITH visible_photos AS (
			SELECT day, user_id FROM photos WHERE user_id = ?
			UNION ALL
			SELECT day, user_id FROM photos WHERE user_id <> ? AND (capsule_visible_at IS NULL OR capsule_visible_at <= ?)
		)
		SELECT day, COUNT(*), COUNT(DISTINCT user_id) FROM visible_photos GROUP BY day ORDER BY day DESC`, 7, 7, time.Now()).Scan(&plan).Error
	if err != nil {
		t.Fatal(err)
	}
	planText := ""
	for _, row := range plan {
		planText += row.Detail + "\n"
	}
	if !strings.Contains(planText, "idx_photos_calendar_user_day") {
		t.Fatalf("calendar plan does not use user/day index:\n%s", planText)
	}
}
