package api

import (
	"context"
	"image"
	"image/color"
	"image/jpeg"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

func TestDerivativeCommandReportsMissingBinary(t *testing.T) {
	if err := runDerivativeCommand(context.Background(), "daily-encoder-that-does-not-exist"); err == nil {
		t.Fatal("expected missing binary error")
	}
}

func TestMediaDerivativePathIsDeterministicAndRooted(t *testing.T) {
	spec := baseMediaVariants[0]
	a := mediaDerivativeRelativePath("photos/source.jpg", spec)
	b := mediaDerivativeRelativePath("photos/source.jpg", spec)
	if a != b || filepath.ToSlash(a)[:11] != "renditions/" {
		t.Fatalf("unexpected derivative path %q / %q", a, b)
	}
}

func TestVisibleMediaDerivativeRequestPrioritizesAVIF(t *testing.T) {
	server := newSearchTestServer(t)
	server.Config.MediaRenditionsEnabled = true
	server.Config.MediaAVIFEnabled = true
	if err := server.DB.Create(&models.AppSettings{}).Error; err != nil {
		t.Fatal(err)
	}
	server.enqueueMediaDerivatives("photos/visible.jpg", 0, true)
	var rows []models.MediaDerivative
	if err := server.DB.Where("source_path = ?", "photos/visible.jpg").Find(&rows).Error; err != nil {
		t.Fatal(err)
	}
	priorities := map[string]int{}
	for _, row := range rows {
		priorities[row.Format] = row.Priority
	}
	if priorities["avif"] <= priorities["webp"] || priorities["webp"] <= priorities["jpeg"] {
		t.Fatalf("visible priorities = %#v, want avif > webp > jpeg", priorities)
	}
}

func TestDiscardBackgroundDerivativeQueueKeepsVisibleWork(t *testing.T) {
	server := newSearchTestServer(t)
	background := models.MediaDerivative{SourcePath: "photos/background.jpg", Variant: "feed-webp-720", Purpose: "feed", Format: "webp", Width: 720, Quality: 75, OutputPath: "renditions/a/background.webp", Status: mediaDerivativeQueued, Priority: 70}
	visible := models.MediaDerivative{SourcePath: "photos/visible.jpg", Variant: "feed-avif-720", Purpose: "feed", Format: "avif", Width: 720, Quality: 55, OutputPath: "renditions/a/visible.avif", Status: mediaDerivativeQueued, Priority: 10030}
	if err := server.DB.Create(&background).Error; err != nil {
		t.Fatal(err)
	}
	if err := server.DB.Create(&visible).Error; err != nil {
		t.Fatal(err)
	}
	if err := server.discardBackgroundDerivativeQueue(); err != nil {
		t.Fatal(err)
	}
	var gotBackground, gotVisible models.MediaDerivative
	_ = server.DB.First(&gotBackground, background.ID).Error
	_ = server.DB.First(&gotVisible, visible.ID).Error
	if gotBackground.Status != mediaDerivativeEvicted || gotVisible.Status != mediaDerivativeQueued {
		t.Fatalf("unexpected queue states background=%s visible=%s", gotBackground.Status, gotVisible.Status)
	}
}

func TestParseRenditionAccessLineDropsPersonalPath(t *testing.T) {
	day, format, status, bytes, ok := parseRenditionAccessLine(`127.0.0.1 - - [03/Aug/2026:11:22:33 +0000] "GET /uploads/renditions/ab/cd/private-name_feed-avif-720.avif HTTP/1.1" 200 12345 "-" "Daily"`)
	if !ok || day != "2026-08-03" || format != "avif" || status != 200 || bytes != 12345 {
		t.Fatalf("unexpected parse result: %s %s %d %d %v", day, format, status, bytes, ok)
	}
	if _, _, _, _, ok := parseRenditionAccessLine(`127.0.0.1 - - [03/Aug/2026:11:22:33 +0000] "GET /uploads/private.jpg HTTP/1.1" 200 999`); ok {
		t.Fatal("non-rendition path must be ignored")
	}
}

func TestSecureDerivativePathsRejectTraversal(t *testing.T) {
	server := newSearchTestServer(t)
	server.Config.MediaRenditionsEnabled = true
	if _, err := server.secureDerivativeOutput("../escape.jpg"); err == nil {
		t.Fatal("expected output traversal to be rejected")
	}
	if _, err := server.secureUploadFile("../escape.jpg"); err == nil {
		t.Fatal("expected source traversal to be rejected")
	}
}

func TestSecureDerivativeOutputRejectsSymlinkDirectory(t *testing.T) {
	server := newSearchTestServer(t)
	outside := t.TempDir()
	link := filepath.Join(server.Config.UploadDir, "renditions", "aa")
	if err := os.MkdirAll(filepath.Dir(link), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	if _, err := server.secureDerivativeOutput("renditions/aa/file.webp"); err == nil {
		t.Fatal("expected symlink derivative directory to be rejected")
	}
}

func TestJpegDerivativeUsesAtomicReadyFile(t *testing.T) {
	server := newSearchTestServer(t)
	server.Config.MediaRenditionsEnabled = true
	if err := os.MkdirAll(filepath.Join(server.Config.UploadDir, "photos"), 0o755); err != nil {
		t.Fatal(err)
	}
	source := filepath.Join(server.Config.UploadDir, "photos", "source.jpg")
	file, err := os.Create(source)
	if err != nil {
		t.Fatal(err)
	}
	img := image.NewRGBA(image.Rect(0, 0, 1280, 720))
	for y := 0; y < 720; y++ {
		for x := 0; x < 1280; x++ {
			img.Set(x, y, color.RGBA{R: uint8(x % 255), G: uint8(y % 255), B: 120, A: 255})
		}
	}
	if err := jpeg.Encode(file, img, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatal(err)
	}
	_ = file.Close()
	spec := baseMediaVariants[3]
	row := models.MediaDerivative{
		SourcePath: "photos/source.jpg", OutputPath: mediaDerivativeRelativePath("photos/source.jpg", spec),
		Format: spec.Format, Width: spec.Width, Quality: spec.Quality,
	}
	bytes, err := server.encodeMediaDerivative(context.Background(), row)
	if err != nil || bytes <= 0 {
		t.Fatalf("encode derivative: bytes=%d err=%v", bytes, err)
	}
	output, _ := server.secureDerivativeOutput(row.OutputPath)
	if info, err := os.Stat(output); err != nil || info.Size() != bytes {
		t.Fatalf("ready file missing or wrong size: info=%v err=%v", info, err)
	}
	matches, _ := filepath.Glob(filepath.Join(filepath.Dir(output), ".daily-rendition-*"))
	if len(matches) != 0 {
		t.Fatalf("temporary files remain: %v", matches)
	}
}

func TestDerivativeBudgetEvictsOnlyRegenerableOldFiles(t *testing.T) {
	server := newSearchTestServer(t)
	server.Config.MediaDerivativeMaxBytes = 100
	old := time.Now().UTC().Add(-10 * 24 * time.Hour)
	create := func(name string, created time.Time, requested *time.Time) models.MediaDerivative {
		path := filepath.ToSlash(filepath.Join("renditions", "aa", name+".jpg"))
		full, err := server.secureDerivativeOutput(path)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, make([]byte, 80), 0o644); err != nil {
			t.Fatal(err)
		}
		row := models.MediaDerivative{SourcePath: "photos/" + name + ".jpg", Variant: name, Purpose: "feed", Format: "jpeg", Width: 720, Quality: 80, OutputPath: path, Status: mediaDerivativeReady, ByteSize: 80, CreatedAt: created, LastRequestedAt: requested}
		if err := server.DB.Create(&row).Error; err != nil {
			t.Fatal(err)
		}
		return row
	}
	evictable := create("old", old, &old)
	protectedAt := time.Now().UTC()
	protected := create("recent", old, &protectedAt)
	original := filepath.Join(server.Config.UploadDir, "photos", "old.jpg")
	if err := os.MkdirAll(filepath.Dir(original), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(original, []byte("original"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := server.cleanupMediaDerivatives(); err != nil {
		t.Fatal(err)
	}
	var oldRow, recentRow models.MediaDerivative
	_ = server.DB.First(&oldRow, evictable.ID).Error
	_ = server.DB.First(&recentRow, protected.ID).Error
	if oldRow.Status != mediaDerivativeEvicted || recentRow.Status != mediaDerivativeReady {
		t.Fatalf("unexpected statuses old=%s recent=%s", oldRow.Status, recentRow.Status)
	}
	if _, err := os.Stat(original); err != nil {
		t.Fatalf("original was removed: %v", err)
	}
}
