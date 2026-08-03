package api

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"net/http/httptest"
)

func TestStorageReportSeparatesOriginalsAndRenditions(t *testing.T) {
	server := newSearchTestServer(t)
	root := t.TempDir()
	server.Config.UploadDir = filepath.Join(root, "data", "uploads")
	server.Config.DatabasePath = filepath.Join(root, "data", "app.db")
	server.Config.ForensicBackendLogPath = filepath.Join(root, "logs", "backend", "backend.log")
	server.Config.ForensicGatewayLogPath = filepath.Join(root, "logs", "nginx", "access.log")

	write := func(relative string, size int) {
		path := filepath.Join(root, relative)
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, make([]byte, size), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("data/uploads/photos/primary.jpg", 11)
	write("data/uploads/renditions/aa/preview.webp", 7)
	write("data/uploads/capsules/preview.jpg", 5)
	write("data/app.db", 13)
	write("data/app.db-wal", 3)
	write("data/app.db-shm", 2)
	write("logs/backend/backend.log", 17)
	write("logs/nginx/access.log", 19)

	report := server.storageReport()
	if report.UploadBytes != 23 {
		t.Fatalf("upload bytes = %d, want 23", report.UploadBytes)
	}
	if report.OriginalBytes != 11 || report.RenditionBytes != 7 || report.OtherUploadBytes != 5 {
		t.Fatalf("unexpected media split: originals=%d renditions=%d other=%d", report.OriginalBytes, report.RenditionBytes, report.OtherUploadBytes)
	}
	if report.DatabaseBytes != 13 || report.DatabaseWalBytes != 3 || report.DatabaseShmBytes != 2 {
		t.Fatalf("unexpected database split: %+v", report)
	}
	if report.BackendLogBytes != 17 || report.GatewayLogBytes != 19 {
		t.Fatalf("unexpected log split: %+v", report)
	}
}

func TestAdminStatsCountsActiveDaysFromDailyData(t *testing.T) {
	server := newSearchTestServer(t)
	now := time.Now().In(server.Location)
	yesterday := now.AddDate(0, 0, -1).Format("2006-01-02")
	if err := server.DB.Create(&models.DailyPrompt{Day: yesterday}).Error; err != nil {
		t.Fatal(err)
	}

	recorder := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(recorder)
	server.handleAdminStats(ctx)
	var response struct {
		RunningDays int64 `json:"runningDays"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.RunningDays != 2 {
		t.Fatalf("runningDays = %d, want 2", response.RunningDays)
	}
}
