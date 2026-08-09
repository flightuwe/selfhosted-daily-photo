package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/auth"
	"github.com/yosho/selfhosted-bereal/backend/internal/config"
	"github.com/yosho/selfhosted-bereal/backend/internal/db"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"github.com/yosho/selfhosted-bereal/backend/internal/notify"
	"github.com/yosho/selfhosted-bereal/backend/internal/storage"
)

type recordingSender struct {
	messages []notify.Message
}

func (s *recordingSender) Send(tokens []string, message notify.Message) (notify.SendResult, error) {
	s.messages = append(s.messages, message)
	return notify.SendResult{
		Requested: len(tokens),
		Sent:      len(tokens),
	}, nil
}

func (s *recordingSender) SendDailyPrompt(tokens []string, body string) (notify.SendResult, error) {
	return notify.SendResult{
		Requested: len(tokens),
		Sent:      len(tokens),
	}, nil
}

func (s *recordingSender) Name() string { return "recording" }

func TestIsPromptWindowActive(t *testing.T) {
	triggeredAt := time.Date(2026, 3, 12, 13, 0, 0, 0, time.UTC)
	uploadUntil := triggeredAt.Add(10 * time.Minute)
	prompt := models.DailyPrompt{
		TriggeredAt: &triggeredAt,
		UploadUntil: &uploadUntil,
	}

	tests := []struct {
		name string
		now  time.Time
		want bool
	}{
		{name: "before trigger", now: triggeredAt.Add(-time.Second), want: false},
		{name: "at trigger", now: triggeredAt, want: true},
		{name: "inside window", now: triggeredAt.Add(5 * time.Minute), want: true},
		{name: "at upload until", now: uploadUntil, want: true},
		{name: "after window", now: uploadUntil.Add(time.Second), want: false},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := isPromptWindowActive(prompt, tc.now); got != tc.want {
				t.Fatalf("isPromptWindowActive() = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestIsPromptWindowActiveWithoutBounds(t *testing.T) {
	now := time.Now().UTC()
	if got := isPromptWindowActive(models.DailyPrompt{}, now); got {
		t.Fatal("isPromptWindowActive() = true, want false when prompt has no bounds")
	}
}

func TestResolvePromptUploadDayUsesCapturedAtGrace(t *testing.T) {
	server := newSearchTestServer(t)
	triggeredAt := time.Date(2026, 3, 12, 13, 0, 0, 0, time.UTC)
	uploadUntil := triggeredAt.Add(10 * time.Minute)
	prompt := models.DailyPrompt{
		Day:         "2026-03-12",
		TriggeredAt: &triggeredAt,
		UploadUntil: &uploadUntil,
	}
	if err := server.DB.Create(&prompt).Error; err != nil {
		t.Fatalf("create prompt: %v", err)
	}
	now := time.Date(2026, 3, 13, 10, 0, 0, 0, time.UTC)
	capturedAt := triggeredAt.Add(5 * time.Minute)

	day, allowed, acceptedGrace, blockedCode := server.resolvePromptUploadDecision("2026-03-13", now, &capturedAt)
	if day != "2026-03-12" || !allowed || !acceptedGrace {
		t.Fatalf("resolvePromptUploadDecision() = (%q, %v, %v, %q), want (%q, true, true, \"\")", day, allowed, acceptedGrace, blockedCode, "2026-03-12")
	}
}

func TestResolvePromptUploadDayRejectsTooOldCapturedAt(t *testing.T) {
	server := newSearchTestServer(t)
	triggeredAt := time.Date(2026, 3, 12, 13, 0, 0, 0, time.UTC)
	uploadUntil := triggeredAt.Add(10 * time.Minute)
	prompt := models.DailyPrompt{
		Day:         "2026-03-12",
		TriggeredAt: &triggeredAt,
		UploadUntil: &uploadUntil,
	}
	if err := server.DB.Create(&prompt).Error; err != nil {
		t.Fatalf("create prompt: %v", err)
	}
	now := time.Date(2026, 3, 21, 10, 0, 0, 0, time.UTC)
	capturedAt := triggeredAt.Add(5 * time.Minute)

	day, allowed, acceptedGrace, blockedCode := server.resolvePromptUploadDecision("2026-03-21", now, &capturedAt)
	if day != "2026-03-21" || allowed || acceptedGrace {
		t.Fatalf("resolvePromptUploadDecision() = (%q, %v, %v, %q), want (%q, false, false, prompt_inactive)", day, allowed, acceptedGrace, blockedCode, "2026-03-21")
	}
}

func TestBuildUploadTimelineItemParsesStructuredUploadMeta(t *testing.T) {
	row := models.ClientDebugLog{
		ID:         7,
		Type:       "upload_queue_failed",
		Message:    "Server antwortet zu langsam. Upload wird spaeter automatisch fortgesetzt.",
		Meta:       "uploadClientId=up_123;queueItemId=q_123;kind=prompt;attempt=3;bytesTotal=4096;durationMs=25000;pingMs=420;failureClass=timeout;network=timeout;networkStable=false;activeNetwork=true;internet=true;validated=false;metered=true;transport=cellular;downKbps=18000;upKbps=2200;capturedAt=2026-05-29T18:10:00Z;queuedAt=2026-05-29T18:11:00Z",
		AppVersion: "0.5.8",
		DeviceName: "Pixel",
		SessionID:  "sess_1",
		RequestID:  "req_1",
		User:       models.User{ID: 9, Username: "alice"},
		CreatedAt:  time.Date(2026, 5, 29, 18, 12, 0, 0, time.UTC),
	}

	item, ok := buildUploadTimelineItem(row, time.UTC)
	if !ok {
		t.Fatal("buildUploadTimelineItem() returned ok=false")
	}
	if got := item["stage"]; got != "fehlgeschlagen" {
		t.Fatalf("stage = %#v, want fehlgeschlagen", got)
	}
	if got := item["source"]; got != "queue" {
		t.Fatalf("source = %#v, want queue", got)
	}
	if got := item["uploadClientId"]; got != "up_123" {
		t.Fatalf("uploadClientId = %#v, want up_123", got)
	}
	if got := item["kind"]; got != "prompt" {
		t.Fatalf("kind = %#v, want prompt", got)
	}
	network, ok := item["network"].(gin.H)
	if !ok {
		t.Fatalf("network missing or wrong type: %#v", item["network"])
	}
	if got, ok := network["transport"].(string); !ok || got != "cellular" {
		t.Fatalf("network.transport = %#v, want cellular", network["transport"])
	}
	if got, ok := network["stable"].(*bool); !ok || got == nil || *got {
		t.Fatalf("network.stable = %#v, want pointer to false", network["stable"])
	}
	if got, ok := item["attempt"].(int64); !ok || got != 3 {
		t.Fatalf("attempt = %#v, want 3", item["attempt"])
	}
}

func TestUploadTimelineStageRecognizesDirectAndQueueStages(t *testing.T) {
	stage, source, ok := uploadTimelineStage("upload_direct_succeeded")
	if !ok || stage != "erfolgreich" || source != "direct" {
		t.Fatalf("uploadTimelineStage(direct) = (%q, %q, %v)", stage, source, ok)
	}
	stage, source, ok = uploadTimelineStage("upload_queue_waiting_for_network")
	if !ok || stage != "wartet_auf_verbindung" || source != "queue" {
		t.Fatalf("uploadTimelineStage(queue) = (%q, %q, %v)", stage, source, ok)
	}
}

func TestDebugFailureFamilyRecognizesCertAndDns(t *testing.T) {
	row := models.ClientDebugLog{
		Type:    "dashboard_load_failed",
		Message: "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.",
		Meta:    "failureClass=cert_path_validator;transport=wifi",
	}
	if got := debugFailureFamily(row); got != "cert_path_validator" {
		t.Fatalf("debugFailureFamily(cert) = %q, want cert_path_validator", got)
	}
	row = models.ClientDebugLog{
		Type:    "feed_refresh_failed",
		Message: "Servername konnte nicht aufgeloest werden",
		Meta:    "failureClass=dns;network=dns",
	}
	if got := debugFailureFamily(row); got != "dns" {
		t.Fatalf("debugFailureFamily(dns) = %q, want dns", got)
	}
}

func TestDebugSignalCategorySeparatesOperationalFailuresFromMobileNoise(t *testing.T) {
	tests := []struct {
		name string
		row  models.ClientDebugLog
		want string
	}{
		{name: "offline refresh", row: models.ClientDebugLog{Type: "feed_refresh_failed", Meta: "failureClass=no_active_network"}, want: "connectivity"},
		{name: "compose cancellation", row: models.ClientDebugLog{Type: "dashboard_load_failed", Meta: "failureClass=JobCancellationException"}, want: "cancelled"},
		{name: "gateway failure", row: models.ClientDebugLog{Type: "dashboard_load_failed", Meta: "failureClass=http_502"}, want: "server"},
		{name: "unhandled crash", row: models.ClientDebugLog{Type: "crash_unhandled", Meta: "failureClass=none"}, want: "crash"},
		{name: "successful trace", row: models.ClientDebugLog{Type: "feed_refresh_result", Meta: "failureClass=none"}, want: ""},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := debugSignalCategory(tc.row); got != tc.want {
				t.Fatalf("debugSignalCategory() = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestClientDebugLogRequestIDUpdatesRetryAggregate(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "debug-retry", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatal(err)
	}
	upload := func(meta string) *httptest.ResponseRecorder {
		recorder := httptest.NewRecorder()
		context, _ := gin.CreateTestContext(recorder)
		context.Request = httptest.NewRequest(http.MethodPost, "/api/debug/client-log", strings.NewReader(fmt.Sprintf(`{"type":"feed_refresh_failed","message":"offline","meta":%q,"appVersion":"test","deviceName":"test","requestId":"dbg_retry"}`, meta)))
		context.Request.Header.Set("Content-Type", "application/json")
		context.Set("user", user)
		server.handleClientDebugLog(context)
		return recorder
	}
	if response := upload("failureClass=no_active_network;aggregateCount=1"); response.Code != http.StatusOK {
		t.Fatalf("first upload status=%d body=%s", response.Code, response.Body.String())
	}
	if response := upload("failureClass=no_active_network;aggregateCount=4"); response.Code != http.StatusOK {
		t.Fatalf("retry upload status=%d body=%s", response.Code, response.Body.String())
	}
	var rows []models.ClientDebugLog
	if err := server.DB.Where("user_id = ? AND request_id = ?", user.ID, "dbg_retry").Find(&rows).Error; err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || !strings.Contains(rows[0].Meta, "aggregateCount=4") {
		t.Fatalf("retry rows=%#v, want one row with latest aggregate", rows)
	}
}

func TestInvalidPromptOnlyPhotoIDs(t *testing.T) {
	triggeredAt := time.Date(2026, 3, 12, 13, 0, 0, 0, time.UTC)
	uploadUntil := triggeredAt.Add(10 * time.Minute)
	photos := []models.Photo{
		{ID: 1, Day: "2026-03-12", CreatedAt: triggeredAt.Add(2 * time.Minute)},
		{ID: 2, Day: "2026-03-12", CreatedAt: triggeredAt.Add(-2 * time.Minute)},
		{ID: 3, Day: "2026-03-13", CreatedAt: triggeredAt.Add(24 * time.Hour)},
	}
	promptByDay := map[string]models.DailyPrompt{
		"2026-03-12": {
			Day:         "2026-03-12",
			TriggeredAt: &triggeredAt,
			UploadUntil: &uploadUntil,
		},
	}

	got := invalidPromptOnlyPhotoIDs(photos, promptByDay, nil)
	if len(got) != 2 || got[0] != uint(2) || got[1] != uint(3) {
		t.Fatalf("invalidPromptOnlyPhotoIDs() = %v, want [2 3]", got)
	}
}

func TestInvalidPromptOnlyPhotoIDsKeepsLegacySpecialAuditWindowPost(t *testing.T) {
	dailyTriggeredAt := time.Date(2026, 3, 12, 17, 0, 0, 0, time.UTC)
	dailyUploadUntil := dailyTriggeredAt.Add(10 * time.Minute)
	specialTriggeredAt := time.Date(2026, 3, 12, 9, 56, 0, 0, time.UTC)
	photos := []models.Photo{
		{ID: 1, Day: "2026-03-12", PromptOnly: true, CreatedAt: specialTriggeredAt.Add(2 * time.Minute)},
		{ID: 2, Day: "2026-03-12", PromptOnly: true, CreatedAt: dailyTriggeredAt.Add(-time.Hour)},
	}
	promptByDay := map[string]models.DailyPrompt{
		"2026-03-12": {
			Day:         "2026-03-12",
			TriggeredAt: &dailyTriggeredAt,
			UploadUntil: &dailyUploadUntil,
		},
	}
	auditWindowsByDay := map[string][]promptUploadWindow{
		"2026-03-12": {
			{TriggeredAt: specialTriggeredAt, UploadUntil: specialTriggeredAt.Add(10 * time.Minute)},
		},
	}

	got := invalidPromptOnlyPhotoIDs(photos, promptByDay, auditWindowsByDay)
	if len(got) != 1 || got[0] != uint(2) {
		t.Fatalf("invalidPromptOnlyPhotoIDs() = %v, want [2]", got)
	}
}

func TestPhotoVisibleToViewer(t *testing.T) {
	now := time.Date(2026, 3, 12, 12, 0, 0, 0, time.UTC)
	future := now.Add(2 * time.Hour)

	tests := []struct {
		name   string
		viewer uint
		photo  models.Photo
		want   bool
	}{
		{name: "own locked capsule remains visible", viewer: 7, photo: models.Photo{UserID: 7, CapsuleVisibleAt: &future}, want: true},
		{name: "foreign locked capsule hidden", viewer: 7, photo: models.Photo{UserID: 8, CapsuleVisibleAt: &future}, want: false},
		{name: "foreign released photo visible", viewer: 7, photo: models.Photo{UserID: 8}, want: true},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := photoVisibleToViewer(tc.viewer, tc.photo, now); got != tc.want {
				t.Fatalf("photoVisibleToViewer() = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestDiscoverTargetIndexPrefersFocusedPhoto(t *testing.T) {
	targetPhotoID := int64(42)
	payloads := []discoverDayPayload{
		{
			Day: "2026-03-12",
			Payload: gin.H{
				"items": []gin.H{
					{"photo": gin.H{"id": uint(7)}},
				},
			},
		},
		{
			Day: "2026-03-11",
			Payload: gin.H{
				"items": []gin.H{
					{"photo": gin.H{"id": uint(42)}},
				},
			},
		},
	}

	if got := discoverTargetIndex(payloads, "2026-03-12", &targetPhotoID); got != 1 {
		t.Fatalf("discoverTargetIndex() = %d, want 1", got)
	}
}

func TestDiscoverOffsetForTargetCentersWhenPossible(t *testing.T) {
	if got := discoverOffsetForTarget(20, 5, 9); got != 7 {
		t.Fatalf("discoverOffsetForTarget() = %d, want 7", got)
	}
	if got := discoverOffsetForTarget(20, 5, 1); got != 0 {
		t.Fatalf("discoverOffsetForTarget() near start = %d, want 0", got)
	}
	if got := discoverOffsetForTarget(20, 5, 19); got != 15 {
		t.Fatalf("discoverOffsetForTarget() near end = %d, want 15", got)
	}
}

func TestPhotoEffectiveTimeUsesCapturedAt(t *testing.T) {
	uploadedAt := time.Date(2026, 3, 12, 18, 0, 0, 0, time.UTC)
	capturedAt := uploadedAt.Add(-12 * time.Minute)
	photo := models.Photo{CreatedAt: uploadedAt, CapturedAt: &capturedAt}
	if got := photoEffectiveTime(photo); !got.Equal(capturedAt) {
		t.Fatalf("photoEffectiveTime() = %v, want %v", got, capturedAt)
	}
}

func TestPhotoTimeShiftedThreshold(t *testing.T) {
	uploadedAt := time.Date(2026, 3, 12, 18, 0, 0, 0, time.UTC)
	within := uploadedAt.Add(-5 * time.Minute)
	if photoTimeShifted(models.Photo{CreatedAt: uploadedAt, CapturedAt: &within}) {
		t.Fatal("photoTimeShifted() = true, want false at exactly 5 minutes")
	}
	shifted := uploadedAt.Add(-6 * time.Minute)
	if !photoTimeShifted(models.Photo{CreatedAt: uploadedAt, CapturedAt: &shifted}) {
		t.Fatal("photoTimeShifted() = false, want true beyond threshold")
	}
}

func TestPublicPhotoNumberHelpers(t *testing.T) {
	if got := formatPublicPhotoNumber("2026-05-26", 1); got != "260526001" {
		t.Fatalf("formatPublicPhotoNumber() = %q, want %q", got, "260526001")
	}
	if seq, ok := parsePublicPhotoSequence("2026-05-26", "260526017"); !ok || seq != 17 {
		t.Fatalf("parsePublicPhotoSequence() = (%d, %v), want (17, true)", seq, ok)
	}
	if _, ok := parsePublicPhotoSequence("2026-05-26", "260527001"); ok {
		t.Fatal("parsePublicPhotoSequence() = true for wrong day prefix")
	}
}

func TestPhotoJSONIncludesPublicNumber(t *testing.T) {
	server := &Server{Config: config.Config{PublicBaseURL: "https://daily.example"}}
	number := "260526007"
	photo := models.Photo{
		ID:           7,
		Day:          "2026-05-26",
		FilePath:     "2026-05-26/test.jpg",
		PublicNumber: &number,
		CreatedAt:    time.Date(2026, 5, 26, 12, 0, 0, 0, time.UTC),
	}
	row := server.photoJSON(photo)
	if got := row["publicNumber"]; got != "260526007" {
		t.Fatalf("photoJSON publicNumber = %v, want %q", got, "260526007")
	}
}

func TestCalendarBookmarksPayloadIncludesAllBookmarkedPhotosForSameDay(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	author := models.User{Username: "author", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	if err := server.DB.Create(&author).Error; err != nil {
		t.Fatalf("create author: %v", err)
	}
	day := "2026-05-26"
	publicA := "260526001"
	publicB := "260526002"
	photos := []models.Photo{
		{UserID: author.ID, User: author, Day: day, FilePath: "a.jpg", PublicNumber: &publicA, CreatedAt: time.Date(2026, 5, 26, 10, 0, 0, 0, time.UTC)},
		{UserID: author.ID, User: author, Day: day, FilePath: "b.jpg", PublicNumber: &publicB, CreatedAt: time.Date(2026, 5, 26, 11, 0, 0, 0, time.UTC)},
	}
	for _, photo := range photos {
		if err := server.DB.Create(&photo).Error; err != nil {
			t.Fatalf("create photo: %v", err)
		}
		if err := server.DB.Create(&models.PhotoBookmark{UserID: viewer.ID, PhotoID: photo.ID}).Error; err != nil {
			t.Fatalf("create bookmark: %v", err)
		}
	}
	payload, err := server.calendarPayload(viewer.ID, "bookmarks", 0, time.Date(2026, 5, 26, 12, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("calendarPayload: %v", err)
	}
	photosByDay, ok := payload["photosByDay"].(map[string][]gin.H)
	if !ok {
		t.Fatalf("photosByDay missing or wrong type: %#v", payload["photosByDay"])
	}
	if got := len(photosByDay[day]); got != 2 {
		t.Fatalf("photosByDay[%s] len = %d, want 2", day, got)
	}
}

func TestCalendarPayloadIncludesInteractionPreviewMetadata(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	author := models.User{Username: "author", PasswordHash: "x"}
	reactor := models.User{Username: "reactor", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	if err := server.DB.Create(&author).Error; err != nil {
		t.Fatalf("create author: %v", err)
	}
	if err := server.DB.Create(&reactor).Error; err != nil {
		t.Fatalf("create reactor: %v", err)
	}

	day := "2026-07-01"
	photo := models.Photo{
		UserID:    author.ID,
		User:      author,
		Day:       day,
		FilePath:  day + "/post.jpg",
		CreatedAt: time.Date(2026, 7, 1, 12, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	if err := server.DB.Create(&models.PhotoComment{
		PhotoID: photo.ID,
		UserID:  reactor.ID,
		Body:    "sichtbar",
	}).Error; err != nil {
		t.Fatalf("create comment: %v", err)
	}
	if err := server.DB.Create(&models.PhotoReaction{
		PhotoID: photo.ID,
		UserID:  reactor.ID,
		Emoji:   "fire",
	}).Error; err != nil {
		t.Fatalf("create reaction: %v", err)
	}

	payload, err := server.calendarPayload(viewer.ID, "public", 0, time.Date(2026, 7, 2, 12, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("calendarPayload: %v", err)
	}
	items, ok := payload["items"].([]gin.H)
	if !ok || len(items) != 1 {
		t.Fatalf("items = %#v, want one item", payload["items"])
	}
	snapshot, ok := items[0]["interactionSnapshot"].(gin.H)
	if !ok {
		t.Fatalf("interactionSnapshot = %#v, want gin.H", items[0]["interactionSnapshot"])
	}
	if got := snapshot["kind"]; got != "preview" {
		t.Fatalf("interactionSnapshot.kind = %#v, want preview", got)
	}
	if got := snapshot["commentPreviewLimit"]; got == nil {
		t.Fatalf("interactionSnapshot.commentPreviewLimit missing")
	}
	counts, ok := items[0]["interactionCounts"].(gin.H)
	if !ok {
		t.Fatalf("interactionCounts = %#v, want gin.H", items[0]["interactionCounts"])
	}
	if got := counts["comments"]; got != int64(1) {
		t.Fatalf("interactionCounts.comments = %#v, want 1", got)
	}
	if got := counts["reactions"]; got != int64(1) {
		t.Fatalf("interactionCounts.reactions = %#v, want 1", got)
	}
}

func TestCompactCalendarPublicPayloadRemovesDuplicatedAndHeavyData(t *testing.T) {
	payload := gin.H{
		"days":  []string{"2026-08-03"},
		"items": []gin.H{{"photo": gin.H{"id": uint(7), "paints": []gin.H{{"pathsJson": strings.Repeat("x", 4096)}}}}},
		"photosByDay": map[string][]gin.H{
			"2026-08-03": {{
				"photo": gin.H{
					"id":           uint(7),
					"url":          "https://example.invalid/uploads/photo.jpg",
					"thumbnailUrl": "https://example.invalid/uploads/thumb.jpg",
					"marks":        []gin.H{{"id": 1}},
					"paints":       []gin.H{{"pathsJson": strings.Repeat("x", 4096)}},
				},
				"user": gin.H{"id": uint(2), "username": "author"},
			}},
		},
	}

	compact := compactCalendarPublicPayload(payload)
	items, ok := compact["items"].([]gin.H)
	if !ok || len(items) != 0 {
		t.Fatalf("compact items = %#v, want empty list", compact["items"])
	}
	rows := compact["photosByDay"].(map[string][]gin.H)["2026-08-03"]
	photo := rows[0]["photo"].(gin.H)
	if _, exists := photo["marks"]; exists {
		t.Fatal("compact photo still contains marks")
	}
	if _, exists := photo["paints"]; exists {
		t.Fatal("compact photo still contains paints")
	}
	if got := photo["thumbnailUrl"]; got != "https://example.invalid/uploads/thumb.jpg" {
		t.Fatalf("thumbnailUrl = %#v, want preserved", got)
	}
	legacyPhoto := payload["photosByDay"].(map[string][]gin.H)["2026-08-03"][0]["photo"].(gin.H)
	if _, exists := legacyPhoto["paints"]; !exists {
		t.Fatal("compact conversion mutated the legacy payload")
	}
}

func TestInvalidateFeedDayCacheBumpsCalendarRevision(t *testing.T) {
	server := newSearchTestServer(t)
	before := server.syncRevision(calendarRevisionScope)
	server.invalidateFeedDayCache("2026-08-03")
	after := server.syncRevision(calendarRevisionScope)
	if after <= before {
		t.Fatalf("calendar revision = %d after invalidation, want > %d", after, before)
	}
}

func TestCalendarPublicCompactUsesETagAndReturnsNotModified(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "calendar-etag-viewer", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}

	firstRecorder := httptest.NewRecorder()
	firstContext, _ := gin.CreateTestContext(firstRecorder)
	firstContext.Request = httptest.NewRequest(http.MethodGet, "/api/calendar/public?compact=true", nil)
	firstContext.Set("user", viewer)
	server.handleCalendarPublic(firstContext)
	if firstRecorder.Code != http.StatusOK {
		t.Fatalf("first status = %d, want 200; body=%s", firstRecorder.Code, firstRecorder.Body.String())
	}
	etag := firstRecorder.Header().Get("ETag")
	if etag == "" {
		t.Fatal("compact calendar response has no ETag")
	}
	var body map[string]any
	if err := json.Unmarshal(firstRecorder.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode compact calendar: %v", err)
	}
	if got := body["schemaVersion"]; got != "calendar_public_v2" {
		t.Fatalf("schemaVersion = %#v, want calendar_public_v2", got)
	}

	secondRecorder := httptest.NewRecorder()
	secondContext, _ := gin.CreateTestContext(secondRecorder)
	secondContext.Request = httptest.NewRequest(http.MethodGet, "/api/calendar/public?compact=true", nil)
	secondContext.Request.Header.Set("If-None-Match", etag)
	secondContext.Set("user", viewer)
	server.handleCalendarPublic(secondContext)
	if secondRecorder.Code != http.StatusNotModified {
		t.Fatalf("second status = %d, want 304; body=%s", secondRecorder.Code, secondRecorder.Body.String())
	}
	if secondRecorder.Body.Len() != 0 {
		t.Fatalf("304 body length = %d, want 0", secondRecorder.Body.Len())
	}
}

func TestSortPhotosForFeedUsesEffectiveTime(t *testing.T) {
	uploadA := time.Date(2026, 3, 12, 18, 0, 0, 0, time.UTC)
	capturedA := uploadA.Add(-10 * time.Minute)
	uploadB := time.Date(2026, 3, 12, 17, 57, 0, 0, time.UTC)
	photos := []models.Photo{
		{ID: 1, CreatedAt: uploadA, CapturedAt: &capturedA},
		{ID: 2, CreatedAt: uploadB},
	}
	sortPhotosForFeed(photos)
	if photos[0].ID != 2 || photos[1].ID != 1 {
		t.Fatalf("sortPhotosForFeed() order = [%d %d], want [2 1]", photos[0].ID, photos[1].ID)
	}
}

func TestMomentKindFromTriggerSource(t *testing.T) {
	tests := []struct {
		name          string
		triggerSource string
		want          string
	}{
		{name: "special request", triggerSource: "special_request", want: "special"},
		{name: "chat command", triggerSource: "chat_command", want: "special"},
		{name: "scheduler", triggerSource: "scheduler", want: "daily"},
		{name: "unknown source fallback", triggerSource: "legacy_source", want: "daily"},
		{name: "blank source fallback", triggerSource: "   ", want: "daily"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := momentKindFromTriggerSource(tc.triggerSource); got != tc.want {
				t.Fatalf("momentKindFromTriggerSource() = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestNormalizePollOptions(t *testing.T) {
	in := []string{
		"  Ja  ",
		"Nein",
		"ja",
		"",
		"  Vielleicht spaeter ",
	}
	got := normalizePollOptions(in)
	want := []string{"Ja", "Nein", "Vielleicht spaeter"}
	if len(got) != len(want) {
		t.Fatalf("normalizePollOptions() len = %d, want %d (%v)", len(got), len(want), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("normalizePollOptions()[%d] = %q, want %q", i, got[i], want[i])
		}
	}
}

func TestExtractHashtags(t *testing.T) {
	got := extractHashtags("Am See mit #Klogrind und nochmal #klogrind plus #SunSet")
	want := []string{"#klogrind", "#sunset"}
	if len(got) != len(want) {
		t.Fatalf("extractHashtags() len = %d, want %d (%v)", len(got), len(want), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("extractHashtags()[%d] = %q, want %q", i, got[i], want[i])
		}
	}
}

func TestIsValidUserReportType(t *testing.T) {
	valid := []string{"bug", "idea", "post", " POST "}
	for _, candidate := range valid {
		if !isValidUserReportType(candidate) {
			t.Fatalf("isValidUserReportType(%q) = false, want true", candidate)
		}
	}
	if isValidUserReportType("other") {
		t.Fatal("isValidUserReportType(\"other\") = true, want false")
	}
}

func TestUserReportJSONIncludesReportedPhoto(t *testing.T) {
	server := &Server{Config: config.Config{PublicBaseURL: "https://daily.example"}}
	number := "260526004"
	photoID := uint(44)
	row := server.userReportJSON(models.UserReport{
		ID:      8,
		UserID:  1,
		User:    models.User{ID: 1, Username: "reporter", FavoriteColor: "#123456"},
		Type:    "post",
		PhotoID: &photoID,
		Photo: models.Photo{
			ID:           photoID,
			UserID:       2,
			User:         models.User{ID: 2, Username: "poster", FavoriteColor: "#654321"},
			Day:          "2026-05-26",
			FilePath:     "2026-05-26/test.jpg",
			PublicNumber: &number,
			CreatedAt:    time.Date(2026, 5, 26, 12, 0, 0, 0, time.UTC),
		},
	})
	if got, ok := row["photoId"].(uint); !ok || got != photoID {
		t.Fatalf("photoId = %#v, want %d", row["photoId"], photoID)
	}
	photo, ok := row["photo"].(gin.H)
	if !ok {
		t.Fatalf("photo missing or wrong type: %#v", row["photo"])
	}
	if got := photo["publicNumber"]; got != number {
		t.Fatalf("photo publicNumber = %#v, want %q", got, number)
	}
	photoUser, ok := row["photoUser"].(gin.H)
	if !ok {
		t.Fatalf("photoUser missing or wrong type: %#v", row["photoUser"])
	}
	if got := photoUser["username"]; got != "poster" {
		t.Fatalf("photoUser.username = %#v, want %q", got, "poster")
	}
}

func newSearchTestServer(t *testing.T) *Server {
	t.Helper()
	root := t.TempDir()
	database, err := db.Connect(filepath.Join(root, "app.db"))
	if err != nil {
		t.Skipf("sqlite runtime unavailable: %v", err)
	}
	uploadDir := filepath.Join(root, "uploads")
	store, err := storage.NewLocalStore(uploadDir)
	if err != nil {
		t.Fatalf("create local store: %v", err)
	}
	return &Server{
		DB:       database,
		Store:    store,
		Notifier: &recordingSender{},
		Auth:     auth.NewManager("test-secret", time.Hour),
		Config: config.Config{
			AllowedOrigins: []string{"https://daily.example"},
			PublicBaseURL:  "https://daily.example",
			UploadDir:      uploadDir,
		},
		Location: time.UTC,
	}
}

func TestRouterBuildsWithoutPhotoCommentRouteConflict(t *testing.T) {
	server := newSearchTestServer(t)
	defer func() {
		if recovered := recover(); recovered != nil {
			t.Fatalf("server.Router() panicked: %v", recovered)
		}
	}()

	router := server.Router()
	if router == nil {
		t.Fatal("server.Router() returned nil")
	}
}

func TestHandleCommunityPostActivateRejectsTimeCapsulesByMode(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "capsule-owner", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}
	photo := models.Photo{
		UserID:      user.ID,
		Day:         "2026-08-05",
		FilePath:    "capsule.jpg",
		CapsuleMode: "date",
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create capsule: %v", err)
	}
	rec := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(rec)
	ctx.Params = gin.Params{{Key: "id", Value: strconv.FormatUint(uint64(photo.ID), 10)}}
	ctx.Set("user", user)

	server.handleCommunityPostActivate(ctx)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want %d; body=%s", rec.Code, http.StatusBadRequest, rec.Body.String())
	}
	var stored models.Photo
	if err := server.DB.First(&stored, photo.ID).Error; err != nil {
		t.Fatalf("reload photo: %v", err)
	}
	if stored.CommunityPost {
		t.Fatal("time capsule was incorrectly promoted to a community post")
	}
}

func TestHandleAuthRefreshReturnsSessionRevokedErrorCodeForUnknownToken(t *testing.T) {
	server := newSearchTestServer(t)
	rec := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(rec)
	ctx.Request = httptest.NewRequest(http.MethodPost, "/api/auth/refresh", strings.NewReader(`{"refreshToken":"deadbeefdeadbeefdeadbeefdeadbeef"}`))
	ctx.Request.Header.Set("Content-Type", "application/json")

	server.handleAuthRefresh(ctx)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("handleAuthRefresh() status = %d, want 401", rec.Code)
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode auth refresh response: %v", err)
	}
	if got := body["errorCode"]; got != "session_revoked" {
		t.Fatalf("errorCode = %#v, want session_revoked", got)
	}
}

func TestHandleUploadDeduplicatesByUploadClientIDOnRetry(t *testing.T) {
	server := newSearchTestServer(t)
	now := time.Now().UTC()
	user := models.User{Username: "poster", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}
	prompt := models.DailyPrompt{
		Day:         now.Format("2006-01-02"),
		TriggeredAt: ptrTime(now.Add(-time.Minute)),
		UploadUntil: ptrTime(now.Add(5 * time.Minute)),
	}
	if err := server.DB.Create(&prompt).Error; err != nil {
		t.Fatalf("create prompt: %v", err)
	}

	makeRequest := func(filename string, payload []byte) *http.Request {
		var body bytes.Buffer
		writer := multipart.NewWriter(&body)
		if err := writer.WriteField("kind", "prompt"); err != nil {
			t.Fatalf("write kind: %v", err)
		}
		if err := writer.WriteField("upload_client_id", "retry-client-1"); err != nil {
			t.Fatalf("write upload_client_id: %v", err)
		}
		part, err := writer.CreateFormFile("photo", filename)
		if err != nil {
			t.Fatalf("create form file: %v", err)
		}
		if _, err := part.Write(payload); err != nil {
			t.Fatalf("write payload: %v", err)
		}
		if err := writer.Close(); err != nil {
			t.Fatalf("close writer: %v", err)
		}
		req := httptest.NewRequest(http.MethodPost, "/api/uploads", &body)
		req.Header.Set("Content-Type", writer.FormDataContentType())
		return req
	}

	firstRec := httptest.NewRecorder()
	firstCtx, _ := gin.CreateTestContext(firstRec)
	firstCtx.Request = makeRequest("prompt-a.jpg", []byte("prompt-upload-a"))
	firstCtx.Set("user", user)
	server.handleUpload(firstCtx)
	if firstRec.Code != http.StatusCreated {
		t.Fatalf("first handleUpload() status = %d, want 201", firstRec.Code)
	}

	retryRec := httptest.NewRecorder()
	retryCtx, _ := gin.CreateTestContext(retryRec)
	retryCtx.Request = makeRequest("prompt-b.jpg", []byte("prompt-upload-b"))
	retryCtx.Set("user", user)
	server.handleUpload(retryCtx)
	if retryRec.Code != http.StatusOK {
		t.Fatalf("retry handleUpload() status = %d, want 200", retryRec.Code)
	}

	var retryBody struct {
		Deduplicated bool           `json:"deduplicated"`
		Photo        map[string]any `json:"photo"`
	}
	if err := json.Unmarshal(retryRec.Body.Bytes(), &retryBody); err != nil {
		t.Fatalf("decode retry response: %v", err)
	}
	if !retryBody.Deduplicated {
		t.Fatalf("retry response deduplicated = %v, want true", retryBody.Deduplicated)
	}

	var photos []models.Photo
	if err := server.DB.Where("user_id = ?", user.ID).Order("id asc").Find(&photos).Error; err != nil {
		t.Fatalf("load photos: %v", err)
	}
	if len(photos) != 1 {
		t.Fatalf("photo count = %d, want 1", len(photos))
	}
	if photos[0].UploadClientID != "retry-client-1" {
		t.Fatalf("stored upload client id = %q, want retry-client-1", photos[0].UploadClientID)
	}
}

func TestHandleUploadAllowsDailyAfterEarlierSpecialMomentPost(t *testing.T) {
	server := newSearchTestServer(t)
	now := time.Now().UTC()
	user := models.User{Username: "poster", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}
	dailyTriggeredAt := now.Add(-time.Minute)
	prompt := models.DailyPrompt{
		Day:           now.Format("2006-01-02"),
		TriggeredAt:   &dailyTriggeredAt,
		UploadUntil:   ptrTime(now.Add(5 * time.Minute)),
		TriggerSource: "scheduler",
	}
	if err := server.DB.Create(&prompt).Error; err != nil {
		t.Fatalf("create prompt: %v", err)
	}
	specialCreatedAt := now.Add(-7 * time.Hour)
	if err := server.DB.Create(&models.DailyTriggerAuditEvent{
		Day:        prompt.Day,
		OccurredAt: specialCreatedAt.Add(-2 * time.Minute),
		Source:     "special_request",
		Result:     "triggered",
	}).Error; err != nil {
		t.Fatalf("create special audit: %v", err)
	}
	if err := server.DB.Create(&models.Photo{
		UserID:     user.ID,
		Day:        prompt.Day,
		PromptOnly: true,
		FilePath:   prompt.Day + "/special.jpg",
		CreatedAt:  specialCreatedAt,
	}).Error; err != nil {
		t.Fatalf("create legacy special photo: %v", err)
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	if err := writer.WriteField("kind", "prompt"); err != nil {
		t.Fatalf("write kind: %v", err)
	}
	part, err := writer.CreateFormFile("photo", "daily.jpg")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := part.Write([]byte("daily-upload")); err != nil {
		t.Fatalf("write payload: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	rec := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(rec)
	req := httptest.NewRequest(http.MethodPost, "/api/uploads", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	ctx.Request = req
	ctx.Set("user", user)

	server.handleUpload(ctx)

	if rec.Code != http.StatusCreated {
		t.Fatalf("handleUpload() status = %d, want 201; body=%s", rec.Code, rec.Body.String())
	}
	var photos []models.Photo
	if err := server.DB.Where("user_id = ?", user.ID).Order("created_at asc").Find(&photos).Error; err != nil {
		t.Fatalf("load photos: %v", err)
	}
	if len(photos) != 2 {
		t.Fatalf("photo count = %d, want 2", len(photos))
	}
	if got := photos[1].MomentKind; got != "daily" {
		t.Fatalf("new daily photo moment kind = %q, want daily", got)
	}
	if !photos[0].PromptOnly {
		t.Fatalf("legacy special photo prompt_only = false, want true")
	}
}

func newJSONRequestContext(method, target string, body string, user models.User) (*gin.Context, *httptest.ResponseRecorder) {
	rec := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(rec)
	ctx.Request = httptest.NewRequest(method, target, strings.NewReader(body))
	ctx.Request.Header.Set("Content-Type", "application/json")
	if user.ID != 0 {
		ctx.Set("user", user)
	}
	return ctx, rec
}

func TestHandleUploadReturnsUploadWindowClosedErrorCode(t *testing.T) {
	server := newSearchTestServer(t)
	now := time.Now().UTC()
	user := models.User{Username: "poster", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}
	prompt := models.DailyPrompt{
		Day:         now.Format("2006-01-02"),
		TriggeredAt: ptrTime(now.Add(-2 * time.Hour)),
		UploadUntil: ptrTime(now.Add(-time.Minute)),
	}
	if err := server.DB.Create(&prompt).Error; err != nil {
		t.Fatalf("create prompt: %v", err)
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	if err := writer.WriteField("kind", "prompt"); err != nil {
		t.Fatalf("write kind: %v", err)
	}
	part, err := writer.CreateFormFile("photo", "prompt-a.jpg")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := part.Write([]byte("prompt-upload-a")); err != nil {
		t.Fatalf("write payload: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	rec := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(rec)
	req := httptest.NewRequest(http.MethodPost, "/api/uploads", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	ctx.Request = req
	ctx.Set("user", user)

	server.handleUpload(ctx)

	if rec.Code != http.StatusForbidden {
		t.Fatalf("handleUpload() status = %d, want 403", rec.Code)
	}
	var response map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &response); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if got := response["errorCode"]; got != "upload_window_closed" {
		t.Fatalf("errorCode = %#v, want upload_window_closed", got)
	}
}

func ptrTime(v time.Time) *time.Time { return &v }

func TestExtraUploadOfflineGraceAllowsOnlyPreWindowCapturesWithin24Hours(t *testing.T) {
	server := newSearchTestServer(t)
	now := time.Date(2026, 8, 2, 18, 0, 0, 0, time.UTC)
	triggeredAt := now.Add(-30 * time.Minute)
	prompt := models.DailyPrompt{
		Day:         now.Format("2006-01-02"),
		TriggeredAt: &triggeredAt,
		UploadUntil: ptrTime(now.Add(30 * time.Minute)),
	}
	if err := server.DB.Create(&prompt).Error; err != nil {
		t.Fatalf("create prompt: %v", err)
	}

	validCapture := triggeredAt.Add(-time.Minute)
	if !server.extraUploadOfflineGraceAllowed(prompt.Day, now, &validCapture) {
		t.Fatal("expected capture immediately before the active window to receive offline grace")
	}
	tooOld := now.Add(-24*time.Hour - time.Second)
	if server.extraUploadOfflineGraceAllowed(prompt.Day, now, &tooOld) {
		t.Fatal("capture older than 24 hours must not receive offline grace")
	}
	duringWindow := triggeredAt.Add(time.Minute)
	if server.extraUploadOfflineGraceAllowed(prompt.Day, now, &duringWindow) {
		t.Fatal("capture during the active window must not receive offline grace")
	}
	if server.extraUploadOfflineGraceAllowed(prompt.Day, now, nil) {
		t.Fatal("missing capture timestamp must not receive offline grace")
	}
}

func TestFeedRevisionBumpsAndKnownRevisionParsing(t *testing.T) {
	server := newSearchTestServer(t)
	day := "2026-08-02"
	initialFeed := server.syncRevision(feedRevisionScope(day))
	initialTimeline := server.syncRevision(timelineRevisionScope)
	server.invalidateFeedDayCache(day)
	if got := server.syncRevision(feedRevisionScope(day)); got <= initialFeed {
		t.Fatalf("feed revision = %d, want > %d", got, initialFeed)
	}
	if got := server.syncRevision(timelineRevisionScope); got <= initialTimeline {
		t.Fatalf("timeline revision = %d, want > %d", got, initialTimeline)
	}
	parsed := parseKnownFeedRevisions("2026-08-02:7,2026-08-01:4,invalid,2026-08-03:0")
	if parsed["2026-08-02"] != 7 || parsed["2026-08-01"] != 4 || len(parsed) != 2 {
		t.Fatalf("parsed known revisions = %#v", parsed)
	}
}

func TestFeedWindowReturnsRevisionsDeltaAndNotModified(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "feed-delta-user", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}
	day := time.Now().UTC().Format("2006-01-02")
	photo := models.Photo{UserID: user.ID, User: user, Day: day, MomentKind: "daily", FilePath: day + "/delta.jpg", CreatedAt: time.Now().UTC()}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}

	request := func(target, etag string) (*httptest.ResponseRecorder, map[string]any) {
		recorder := httptest.NewRecorder()
		context, _ := gin.CreateTestContext(recorder)
		context.Request = httptest.NewRequest(http.MethodGet, target, nil)
		context.Request.Header.Set("If-None-Match", etag)
		context.Set("user", user)
		server.handleFeedWindow(context)
		var payload map[string]any
		if recorder.Body.Len() > 0 {
			if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
				t.Fatalf("decode feed window: %v", err)
			}
		}
		return recorder, payload
	}
	target := "/api/feed/window?anchor_day=" + day + "&before_days=0&after_days=0"
	first, payload := request(target, "")
	if first.Code != http.StatusOK {
		t.Fatalf("first feed window status = %d, body=%s", first.Code, first.Body.String())
	}
	revisionMap, _ := payload["dayRevisions"].(map[string]any)
	revision, ok := revisionMap[day].(float64)
	if !ok || revision < 1 {
		t.Fatalf("day revision missing: %#v", payload["dayRevisions"])
	}
	delta, _ := request(target+"&known_revisions="+day+":"+strconv.FormatInt(int64(revision), 10), "")
	if delta.Code != http.StatusNotModified {
		t.Fatalf("delta feed status = %d, body=%s", delta.Code, delta.Body.String())
	}
	if delta.Body.Len() != 0 || delta.Body.Len()*10 > first.Body.Len()*3 {
		t.Fatalf("unchanged feed bytes = %d, full bytes = %d; want at least 70%% reduction", delta.Body.Len(), first.Body.Len())
	}
	notModified, _ := request(target, first.Header().Get("ETag"))
	if notModified.Code != http.StatusNotModified {
		t.Fatalf("conditional feed status = %d, want 304; body=%s", notModified.Code, notModified.Body.String())
	}
}

func TestFeedDaysReturnsGlobalVisibleBounds(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "feed-bounds-viewer", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	now := time.Now().In(server.Location)
	oldest := now.AddDate(0, 0, -9).Format("2006-01-02")
	newest := now.AddDate(0, 0, -2).Format("2006-01-02")
	for _, day := range []string{oldest, newest} {
		photo := models.Photo{
			UserID:    viewer.ID,
			Day:       day,
			FilePath:  day + "/bounds.jpg",
			CreatedAt: now,
		}
		if err := server.DB.Create(&photo).Error; err != nil {
			t.Fatalf("create photo for %s: %v", day, err)
		}
	}

	recorder := httptest.NewRecorder()
	context, _ := gin.CreateTestContext(recorder)
	context.Request = httptest.NewRequest(http.MethodGet, "/api/feed/days?limit=1&include_bounds=true", nil)
	context.Set("user", viewer)
	server.handleFeedDays(context)
	if recorder.Code != http.StatusOK {
		t.Fatalf("feed days status = %d, body=%s", recorder.Code, recorder.Body.String())
	}
	var payload struct {
		Items            []string `json:"items"`
		OldestVisibleDay string   `json:"oldestVisibleDay"`
		NewestVisibleDay string   `json:"newestVisibleDay"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode feed days: %v", err)
	}
	if payload.OldestVisibleDay != oldest || payload.NewestVisibleDay != newest {
		t.Fatalf("feed bounds = oldest=%q newest=%q, want oldest=%q newest=%q", payload.OldestVisibleDay, payload.NewestVisibleDay, oldest, newest)
	}
	if len(payload.Items) != 1 || payload.Items[0] != newest {
		t.Fatalf("limited feed index = %v, want newest %q only", payload.Items, newest)
	}
}

func TestAttachmentUploadIsIdempotentForSelectableOlderOwnPost(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "attachment-owner", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}
	photo := models.Photo{
		UserID: user.ID, User: user, Day: time.Now().UTC().AddDate(0, 0, -2).Format("2006-01-02"),
		FilePath: "older/primary.jpg", CreatedAt: time.Now().UTC().AddDate(0, 0, -2),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	makeRequest := func(payload string) *http.Request {
		var body bytes.Buffer
		writer := multipart.NewWriter(&body)
		_ = writer.WriteField("upload_client_id", "attachment-retry-1")
		_ = writer.WriteField("captured_at", time.Now().UTC().Add(-time.Hour).Format(time.RFC3339))
		part, err := writer.CreateFormFile("photo", "attachment.jpg")
		if err != nil {
			t.Fatalf("create attachment part: %v", err)
		}
		if _, err := part.Write([]byte(payload)); err != nil {
			t.Fatalf("write attachment: %v", err)
		}
		if err := writer.Close(); err != nil {
			t.Fatalf("close multipart: %v", err)
		}
		request := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/api/photos/%d/attachments", photo.ID), &body)
		request.Header.Set("Content-Type", writer.FormDataContentType())
		return request
	}
	call := func(payload string) *httptest.ResponseRecorder {
		recorder := httptest.NewRecorder()
		context, _ := gin.CreateTestContext(recorder)
		context.Request = makeRequest(payload)
		context.Params = gin.Params{{Key: "id", Value: strconv.FormatUint(uint64(photo.ID), 10)}}
		context.Set("user", user)
		server.handlePhotoAttachmentCreate(context)
		return recorder
	}
	first := call("first-body")
	if first.Code != http.StatusCreated {
		t.Fatalf("first attachment status = %d, body=%s", first.Code, first.Body.String())
	}
	retry := call("different-retry-body")
	if retry.Code != http.StatusOK {
		t.Fatalf("retry attachment status = %d, body=%s", retry.Code, retry.Body.String())
	}
	var response map[string]any
	if err := json.Unmarshal(retry.Body.Bytes(), &response); err != nil || response["deduplicated"] != true {
		t.Fatalf("attachment retry was not marked deduplicated: %#v, err=%v", response, err)
	}
	var count int64
	if err := server.DB.Model(&models.PhotoAttachment{}).Where("photo_id = ?", photo.ID).Count(&count).Error; err != nil || count != 1 {
		t.Fatalf("attachment count = %d, err=%v", count, err)
	}
}

func TestHandleUpdatePreferencesPersistsYoloMode(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "tester", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}

	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	body := httptest.NewRequest(http.MethodPut, "/api/me/preferences", strings.NewReader(`{"yoloModeEnabled":true}`))
	body.Header.Set("Content-Type", "application/json")
	c.Request = body
	c.Set("user", user)

	server.handleUpdatePreferences(c)

	if rec.Code != http.StatusOK {
		t.Fatalf("handleUpdatePreferences() status = %d, want 200", rec.Code)
	}
	var updated models.User
	if err := server.DB.First(&updated, user.ID).Error; err != nil {
		t.Fatalf("load updated user: %v", err)
	}
	if !updated.YoloModeEnabled {
		t.Fatal("expected yoloModeEnabled to persist as true")
	}
}

func TestHandleUpdatePreferencesPersistsMediaDataModeIndependentlyFromYolo(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "media-mode", PasswordHash: "x", YoloModeEnabled: true}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}
	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	c.Request = httptest.NewRequest(http.MethodPut, "/api/me/preferences", strings.NewReader(`{"mediaDataMode":"data_saver"}`))
	c.Request.Header.Set("Content-Type", "application/json")
	c.Set("user", user)
	server.handleUpdatePreferences(c)
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	var updated models.User
	if err := server.DB.First(&updated, user.ID).Error; err != nil {
		t.Fatal(err)
	}
	if updated.MediaDataMode != "data_saver" || !updated.YoloModeEnabled {
		t.Fatalf("unexpected preferences: mode=%q yolo=%v", updated.MediaDataMode, updated.YoloModeEnabled)
	}
}

func TestCompactCalendarOmitsInteractionPayloadAndIsMateriallySmaller(t *testing.T) {
	server := newSearchTestServer(t)
	server.Config.MediaRenditionsEnabled = false
	user := models.User{Username: "calendar-compact", PasswordHash: "x", FavoriteColor: "#123456"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatal(err)
	}
	photo := models.Photo{UserID: user.ID, User: user, Day: "2026-08-03", FilePath: "photos/compact.jpg", Caption: strings.Repeat("caption", 30), CreatedAt: time.Now().UTC()}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatal(err)
	}
	for index := 0; index < 20; index++ {
		clientID := fmt.Sprintf("compact-comment-%d", index)
		comment := models.PhotoComment{PhotoID: photo.ID, UserID: user.ID, Body: strings.Repeat("interaction-payload-", 20), ClientCommentID: &clientID}
		if err := server.DB.Create(&comment).Error; err != nil {
			t.Fatal(err)
		}
	}
	legacy, err := server.calendarPayload(user.ID, "public", 0, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	compact, err := server.calendarPublicCompactPayload(user.ID, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	legacyJSON, _ := json.Marshal(legacy)
	compactJSON, _ := json.Marshal(compact)
	if bytes.Contains(compactJSON, []byte("interaction-payload")) || bytes.Contains(compactJSON, []byte(`"comments"`)) || bytes.Contains(compactJSON, []byte(`"paints"`)) || bytes.Contains(compactJSON, []byte(`"marks"`)) {
		t.Fatalf("compact response leaked interaction payload: %s", compactJSON)
	}
	if len(compactJSON)*5 > len(legacyJSON) {
		t.Fatalf("compact response is not at least 80%% smaller: compact=%d legacy=%d", len(compactJSON), len(legacyJSON))
	}
}

func TestCompactCalendarPreviewBoundsRecentDays(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "calendar-preview", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	for index := 0; index < 70; index++ {
		photo := models.Photo{
			UserID: user.ID, Day: now.AddDate(0, 0, -index).Format("2006-01-02"),
			FilePath: fmt.Sprintf("photos/preview-%d.jpg", index), CreatedAt: now.Add(-time.Duration(index) * time.Hour),
		}
		if err := server.DB.Create(&photo).Error; err != nil {
			t.Fatalf("create photo %d: %v", index, err)
		}
	}
	payload, err := server.calendarPublicCompactPayloadForDays(user.ID, now, 60)
	if err != nil {
		t.Fatal(err)
	}
	days, _ := payload["days"].([]string)
	stats, _ := payload["dayStats"].([]gin.H)
	if len(days) != 60 || len(stats) != 60 {
		t.Fatalf("preview lengths = %d days, %d stats; want 60", len(days), len(stats))
	}
	if days[0] != now.Format("2006-01-02") || days[len(days)-1] != now.AddDate(0, 0, -59).Format("2006-01-02") {
		t.Fatalf("unexpected preview range: first=%q last=%q", days[0], days[len(days)-1])
	}
}

func TestCalendarPublicIndexIsCompleteAndMediaFree(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "calendar-index", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	for index := 0; index < 80; index++ {
		photo := models.Photo{UserID: user.ID, Day: now.AddDate(0, 0, -index).Format("2006-01-02"), FilePath: fmt.Sprintf("photos/index-%d.jpg", index), CreatedAt: now}
		if err := server.DB.Create(&photo).Error; err != nil {
			t.Fatalf("create photo %d: %v", index, err)
		}
	}
	payload, err := server.calendarPublicIndexPayload(user.ID, now)
	if err != nil {
		t.Fatal(err)
	}
	days := payload["days"].([]string)
	if len(days) != 80 || days[0] != "2026-08-03" || days[len(days)-1] != "2026-05-16" {
		t.Fatalf("index days = %d (%q..%q), want complete descending 80-day index", len(days), days[0], days[len(days)-1])
	}
	encoded, _ := json.Marshal(payload)
	if bytes.Contains(encoded, []byte("photosByDay")) || bytes.Contains(encoded, []byte("thumbnailUrl")) || bytes.Contains(encoded, []byte("/uploads/")) {
		t.Fatalf("media-free index leaked card payload: %s", encoded)
	}
}

func TestCalendarPublicIndexHandlesConcurrentFullIndexReads(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "calendar-load-viewer", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	for userOffset := 0; userOffset < 8; userOffset++ {
		user := models.User{Username: fmt.Sprintf("calendar-load-%d", userOffset), PasswordHash: "x"}
		if err := server.DB.Create(&user).Error; err != nil {
			t.Fatal(err)
		}
		for dayOffset := 0; dayOffset < 120; dayOffset++ {
			if err := server.DB.Create(&models.Photo{UserID: user.ID, Day: now.AddDate(0, 0, -dayOffset).Format("2006-01-02"), FilePath: fmt.Sprintf("photos/load-%d-%d.jpg", userOffset, dayOffset), CreatedAt: now}).Error; err != nil {
				t.Fatal(err)
			}
		}
	}

	const readers = 16
	var wg sync.WaitGroup
	errs := make(chan error, readers)
	for i := 0; i < readers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			payload, err := server.calendarPublicIndexPayload(viewer.ID, now)
			if err != nil {
				errs <- err
				return
			}
			if got := len(payload["days"].([]string)); got != 120 {
				errs <- fmt.Errorf("index days = %d, want 120", got)
			}
		}()
	}
	finished := make(chan struct{})
	go func() { wg.Wait(); close(finished) }()
	select {
	case <-finished:
	case <-time.After(5 * time.Second):
		t.Fatal("concurrent calendar index reads did not complete within 5 seconds")
	}
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
}

func TestCalendarPublicWindowBoundsCardsToRequestedDays(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "calendar-window", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 8, 3, 12, 0, 0, 0, time.UTC)
	for index := 0; index < 20; index++ {
		photo := models.Photo{UserID: user.ID, Day: now.AddDate(0, 0, -index).Format("2006-01-02"), FilePath: fmt.Sprintf("photos/window-%d.jpg", index), CreatedAt: now}
		if err := server.DB.Create(&photo).Error; err != nil {
			t.Fatalf("create photo %d: %v", index, err)
		}
	}
	payload, err := server.calendarPublicCompactWindowPayload(user.ID, now, "2026-07-29", 5)
	if err != nil {
		t.Fatal(err)
	}
	days := payload["days"].([]string)
	if len(days) != 5 || days[0] != "2026-07-29" || days[4] != "2026-07-25" {
		t.Fatalf("window days = %#v, want requested five-day window", days)
	}
	if !payload["hasMore"].(bool) || payload["nextCursor"] != "2026-07-25" {
		t.Fatalf("window paging = hasMore=%#v nextCursor=%#v, want true/2026-07-25", payload["hasMore"], payload["nextCursor"])
	}
}

func TestHandleUpdatePreferencesPersistsAutoSubscribeInteractedPostsEnabled(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "tester", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}

	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	body := httptest.NewRequest(http.MethodPut, "/api/me/preferences", strings.NewReader(`{"autoSubscribeInteractedPostsEnabled":true}`))
	body.Header.Set("Content-Type", "application/json")
	c.Request = body
	c.Set("user", user)

	server.handleUpdatePreferences(c)

	if rec.Code != http.StatusOK {
		t.Fatalf("handleUpdatePreferences() status = %d, want 200", rec.Code)
	}
	var updated models.User
	if err := server.DB.First(&updated, user.ID).Error; err != nil {
		t.Fatalf("load updated user: %v", err)
	}
	if !updated.AutoSubscribeInteractedPostsEnabled {
		t.Fatal("expected autoSubscribeInteractedPostsEnabled to persist as true")
	}
}

func TestHandleUpdatePreferencesPersistsPostChangePushEnabled(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "tester", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}

	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	body := httptest.NewRequest(http.MethodPut, "/api/me/preferences", strings.NewReader(`{"postChangePushEnabled":true}`))
	body.Header.Set("Content-Type", "application/json")
	c.Request = body
	c.Set("user", user)

	server.handleUpdatePreferences(c)

	if rec.Code != http.StatusOK {
		t.Fatalf("handleUpdatePreferences() status = %d, want 200", rec.Code)
	}
	var updated models.User
	if err := server.DB.First(&updated, user.ID).Error; err != nil {
		t.Fatalf("load updated user: %v", err)
	}
	if !updated.PostChangePushEnabled {
		t.Fatal("expected postChangePushEnabled to persist as true")
	}
}

func TestHandlePhotoInteractionSubscriptionAutoSubscribesForeignPost(t *testing.T) {
	server := newSearchTestServer(t)
	author := models.User{Username: "author", PasswordHash: "x"}
	actor := models.User{Username: "actor", PasswordHash: "x", AutoSubscribeInteractedPostsEnabled: true}
	for _, user := range []*models.User{&author, &actor} {
		if err := server.DB.Create(user).Error; err != nil {
			t.Fatalf("create user %s: %v", user.Username, err)
		}
	}
	photo := models.Photo{
		UserID:    author.ID,
		User:      author,
		Day:       "2026-06-18",
		FilePath:  "2026-06-18/test.jpg",
		CreatedAt: time.Date(2026, 6, 18, 10, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	now := time.Date(2026, 6, 18, 12, 0, 0, 0, time.UTC)
	if err := server.handlePhotoInteractionSubscription(photo, actor.ID, "comment", now); err != nil {
		t.Fatalf("handlePhotoInteractionSubscription() error = %v", err)
	}

	var bookmark models.PhotoBookmark
	if err := server.DB.Where("user_id = ? AND photo_id = ?", actor.ID, photo.ID).First(&bookmark).Error; err != nil {
		t.Fatalf("load bookmark: %v", err)
	}
	if !bookmark.Active || bookmark.SubscriptionSource != photoBookmarkSourceAutoInteraction {
		t.Fatalf("bookmark = %+v, want active auto interaction bookmark", bookmark)
	}
	if bookmark.LastActivityAt == nil || !bookmark.LastActivityAt.Equal(now) {
		t.Fatalf("lastActivityAt = %v, want %v", bookmark.LastActivityAt, now)
	}
	wantExpiry := now.Add(autoInteractionBookmarkTTL)
	if bookmark.AutoExpiresAt == nil || !bookmark.AutoExpiresAt.Equal(wantExpiry) {
		t.Fatalf("autoExpiresAt = %v, want %v", bookmark.AutoExpiresAt, wantExpiry)
	}
}

func TestHandlePhotoInteractionSubscriptionSkipsOwnPost(t *testing.T) {
	server := newSearchTestServer(t)
	owner := models.User{Username: "owner", PasswordHash: "x", AutoSubscribeInteractedPostsEnabled: true}
	if err := server.DB.Create(&owner).Error; err != nil {
		t.Fatalf("create owner: %v", err)
	}
	photo := models.Photo{
		UserID:    owner.ID,
		User:      owner,
		Day:       "2026-06-18",
		FilePath:  "2026-06-18/test.jpg",
		CreatedAt: time.Date(2026, 6, 18, 10, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	if err := server.handlePhotoInteractionSubscription(photo, owner.ID, "comment", time.Date(2026, 6, 18, 12, 0, 0, 0, time.UTC)); err != nil {
		t.Fatalf("handlePhotoInteractionSubscription() error = %v", err)
	}
	var count int64
	if err := server.DB.Model(&models.PhotoBookmark{}).Where("user_id = ? AND photo_id = ?", owner.ID, photo.ID).Count(&count).Error; err != nil {
		t.Fatalf("count bookmarks: %v", err)
	}
	if count != 0 {
		t.Fatalf("bookmark count = %d, want 0", count)
	}
}

func TestHandlePhotoInteractionSubscriptionRefreshesExistingAutoBookmarks(t *testing.T) {
	server := newSearchTestServer(t)
	author := models.User{Username: "author", PasswordHash: "x"}
	actor := models.User{Username: "actor", PasswordHash: "x"}
	subscriber := models.User{Username: "subscriber", PasswordHash: "x"}
	for _, user := range []*models.User{&author, &actor, &subscriber} {
		if err := server.DB.Create(user).Error; err != nil {
			t.Fatalf("create user %s: %v", user.Username, err)
		}
	}
	photo := models.Photo{
		UserID:    author.ID,
		User:      author,
		Day:       "2026-06-18",
		FilePath:  "2026-06-18/test.jpg",
		CreatedAt: time.Date(2026, 6, 18, 10, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	oldActivity := time.Date(2026, 6, 18, 11, 0, 0, 0, time.UTC)
	oldExpiry := oldActivity.Add(autoInteractionBookmarkTTL)
	bookmark := models.PhotoBookmark{
		UserID:             subscriber.ID,
		PhotoID:            photo.ID,
		Active:             true,
		SubscriptionSource: photoBookmarkSourceAutoInteraction,
		LastActivityAt:     &oldActivity,
		AutoExpiresAt:      &oldExpiry,
		CreatedAt:          oldActivity,
	}
	if err := server.DB.Create(&bookmark).Error; err != nil {
		t.Fatalf("create bookmark: %v", err)
	}
	now := time.Date(2026, 6, 18, 13, 0, 0, 0, time.UTC)
	if err := server.handlePhotoInteractionSubscription(photo, actor.ID, "reaction", now); err != nil {
		t.Fatalf("handlePhotoInteractionSubscription() error = %v", err)
	}
	if err := server.DB.First(&bookmark, bookmark.ID).Error; err != nil {
		t.Fatalf("reload bookmark: %v", err)
	}
	if bookmark.LastActivityAt == nil || !bookmark.LastActivityAt.Equal(now) {
		t.Fatalf("lastActivityAt = %v, want %v", bookmark.LastActivityAt, now)
	}
	if bookmark.AutoExpiresAt == nil || !bookmark.AutoExpiresAt.Equal(now.Add(autoInteractionBookmarkTTL)) {
		t.Fatalf("autoExpiresAt = %v, want %v", bookmark.AutoExpiresAt, now.Add(autoInteractionBookmarkTTL))
	}
}

func TestRemovePhotoBookmarkBlocksAutoUntilNextOwnInteraction(t *testing.T) {
	server := newSearchTestServer(t)
	author := models.User{Username: "author", PasswordHash: "x"}
	actor := models.User{Username: "actor", PasswordHash: "x", AutoSubscribeInteractedPostsEnabled: true}
	for _, user := range []*models.User{&author, &actor} {
		if err := server.DB.Create(user).Error; err != nil {
			t.Fatalf("create user %s: %v", user.Username, err)
		}
	}
	photo := models.Photo{
		UserID:    author.ID,
		User:      author,
		Day:       "2026-06-18",
		FilePath:  "2026-06-18/test.jpg",
		CreatedAt: time.Date(2026, 6, 18, 10, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	firstInteraction := time.Date(2026, 6, 18, 11, 0, 0, 0, time.UTC)
	if err := server.handlePhotoInteractionSubscription(photo, actor.ID, "comment", firstInteraction); err != nil {
		t.Fatalf("initial subscription: %v", err)
	}
	if err := server.removePhotoBookmark(actor.ID, photo.ID, firstInteraction.Add(5*time.Minute)); err != nil {
		t.Fatalf("removePhotoBookmark() error = %v", err)
	}
	var bookmark models.PhotoBookmark
	if err := server.DB.Where("user_id = ? AND photo_id = ?", actor.ID, photo.ID).First(&bookmark).Error; err != nil {
		t.Fatalf("load blocked bookmark: %v", err)
	}
	if bookmark.Active || !bookmark.AutoResubscribeBlocked {
		t.Fatalf("bookmark after manual opt-out = %+v, want inactive blocked row", bookmark)
	}
	secondInteraction := time.Date(2026, 6, 18, 14, 0, 0, 0, time.UTC)
	if err := server.handlePhotoInteractionSubscription(photo, actor.ID, "reaction", secondInteraction); err != nil {
		t.Fatalf("reactivate subscription: %v", err)
	}
	if err := server.DB.First(&bookmark, bookmark.ID).Error; err != nil {
		t.Fatalf("reload bookmark: %v", err)
	}
	if !bookmark.Active || bookmark.AutoResubscribeBlocked {
		t.Fatalf("bookmark after next interaction = %+v, want active unblocked row", bookmark)
	}
}

func TestHandlePhotoBookmarkDeleteDoesNotRecreateAutoBookmark(t *testing.T) {
	server := newSearchTestServer(t)
	author := models.User{Username: "author", PasswordHash: "x"}
	actor := models.User{Username: "actor", PasswordHash: "x", AutoSubscribeInteractedPostsEnabled: true}
	for _, user := range []*models.User{&author, &actor} {
		if err := server.DB.Create(user).Error; err != nil {
			t.Fatalf("create user %s: %v", user.Username, err)
		}
	}
	photo := models.Photo{
		UserID:    author.ID,
		User:      author,
		Day:       "2026-06-18",
		FilePath:  "2026-06-18/test.jpg",
		CreatedAt: time.Date(2026, 6, 18, 10, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	if err := server.handlePhotoInteractionSubscription(photo, actor.ID, "comment", time.Date(2026, 6, 18, 11, 0, 0, 0, time.UTC)); err != nil {
		t.Fatalf("initial subscription: %v", err)
	}

	rec := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(rec)
	ctx.Request = httptest.NewRequest(http.MethodDelete, fmt.Sprintf("/api/photos/%d/bookmark", photo.ID), nil)
	ctx.Set("user", actor)
	ctx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoBookmarkDelete(ctx)

	if rec.Code != http.StatusOK {
		t.Fatalf("handlePhotoBookmarkDelete() status = %d, want 200", rec.Code)
	}
	var bookmark models.PhotoBookmark
	if err := server.DB.Where("user_id = ? AND photo_id = ?", actor.ID, photo.ID).First(&bookmark).Error; err != nil {
		t.Fatalf("load bookmark: %v", err)
	}
	if bookmark.Active || !bookmark.AutoResubscribeBlocked {
		t.Fatalf("bookmark after delete = %+v, want inactive blocked row", bookmark)
	}
}

func TestPruneExpiredAutoInteractionBookmarksRemovesOnlyExpiredAutoRows(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "user", PasswordHash: "x"}
	other := models.User{Username: "other", PasswordHash: "x"}
	for _, candidate := range []*models.User{&user, &other} {
		if err := server.DB.Create(candidate).Error; err != nil {
			t.Fatalf("create user %s: %v", candidate.Username, err)
		}
	}
	expiredAt := time.Date(2026, 6, 20, 10, 0, 0, 0, time.UTC)
	futureAt := expiredAt.Add(2 * time.Hour)
	rows := []models.PhotoBookmark{
		{
			UserID:             user.ID,
			PhotoID:            1,
			Active:             true,
			SubscriptionSource: photoBookmarkSourceAutoInteraction,
			AutoExpiresAt:      &expiredAt,
			CreatedAt:          expiredAt.Add(-time.Hour),
		},
		{
			UserID:             user.ID,
			PhotoID:            2,
			Active:             true,
			SubscriptionSource: photoBookmarkSourceManual,
			CreatedAt:          expiredAt.Add(-time.Hour),
		},
		{
			UserID:                 other.ID,
			PhotoID:                3,
			Active:                 false,
			SubscriptionSource:     photoBookmarkSourceAutoInteraction,
			AutoResubscribeBlocked: true,
			CreatedAt:              expiredAt.Add(-time.Hour),
		},
		{
			UserID:             other.ID,
			PhotoID:            4,
			Active:             true,
			SubscriptionSource: photoBookmarkSourceAutoInteraction,
			AutoExpiresAt:      &futureAt,
			CreatedAt:          expiredAt.Add(-time.Hour),
		},
	}
	for _, row := range rows {
		if err := server.DB.Create(&row).Error; err != nil {
			t.Fatalf("create bookmark row: %v", err)
		}
	}
	removed, err := server.pruneExpiredAutoInteractionBookmarks(expiredAt)
	if err != nil {
		t.Fatalf("pruneExpiredAutoInteractionBookmarks() error = %v", err)
	}
	if removed != 1 {
		t.Fatalf("removed = %d, want 1", removed)
	}
	var remaining int64
	if err := server.DB.Model(&models.PhotoBookmark{}).Count(&remaining).Error; err != nil {
		t.Fatalf("count remaining bookmarks: %v", err)
	}
	if remaining != 3 {
		t.Fatalf("remaining bookmarks = %d, want 3", remaining)
	}
}

func TestHandleUpdatePreferencesPersistsNsfwPreferences(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "tester", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}

	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	body := httptest.NewRequest(http.MethodPut, "/api/me/preferences", strings.NewReader(`{"allowCommunityNsfwMarking":true,"showNsfwByDefault":true}`))
	body.Header.Set("Content-Type", "application/json")
	c.Request = body
	c.Set("user", user)

	server.handleUpdatePreferences(c)

	if rec.Code != http.StatusOK {
		t.Fatalf("handleUpdatePreferences() status = %d, want 200", rec.Code)
	}
	var updated models.User
	if err := server.DB.First(&updated, user.ID).Error; err != nil {
		t.Fatalf("load updated user: %v", err)
	}
	if !updated.AllowCommunityNsfwMarking || !updated.ShowNsfwByDefault {
		t.Fatalf("expected NSFW preferences to persist, got %+v", updated)
	}
}

func TestPhotoNsfwFlowRespectsPermissions(t *testing.T) {
	server := newSearchTestServer(t)
	poster := models.User{Username: "poster", PasswordHash: "x"}
	other := models.User{Username: "other", PasswordHash: "x"}
	admin := models.User{Username: "admin", PasswordHash: "x", IsAdmin: true}
	for _, user := range []*models.User{&poster, &other, &admin} {
		if err := server.DB.Create(user).Error; err != nil {
			t.Fatalf("create user %s: %v", user.Username, err)
		}
	}
	photo := models.Photo{
		UserID:    poster.ID,
		User:      poster,
		Day:       "2026-05-26",
		FilePath:  "2026-05-26/test.jpg",
		CreatedAt: time.Date(2026, 5, 26, 18, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}

	forbiddenRec := httptest.NewRecorder()
	forbiddenCtx, _ := gin.CreateTestContext(forbiddenRec)
	forbiddenCtx.Request = httptest.NewRequest(http.MethodPost, fmt.Sprintf("/api/photos/%d/nsfw", photo.ID), nil)
	forbiddenCtx.Set("user", other)
	forbiddenCtx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoNsfwCreate(forbiddenCtx)
	if forbiddenRec.Code != http.StatusForbidden {
		t.Fatalf("mark without poster opt-in status = %d, want 403", forbiddenRec.Code)
	}

	if err := server.DB.Model(&models.User{}).Where("id = ?", poster.ID).Update("allow_community_nsfw_marking", true).Error; err != nil {
		t.Fatalf("enable poster nsfw opt-in: %v", err)
	}

	markRec := httptest.NewRecorder()
	markCtx, _ := gin.CreateTestContext(markRec)
	markCtx.Request = httptest.NewRequest(http.MethodPost, fmt.Sprintf("/api/photos/%d/nsfw", photo.ID), nil)
	markCtx.Set("user", other)
	markCtx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoNsfwCreate(markCtx)
	if markRec.Code != http.StatusOK {
		t.Fatalf("mark with poster opt-in status = %d, want 200", markRec.Code)
	}

	var updated models.Photo
	if err := server.DB.First(&updated, photo.ID).Error; err != nil {
		t.Fatalf("load photo after mark: %v", err)
	}
	if !updated.Nsfw || updated.NsfwMarkedByUserID == nil || *updated.NsfwMarkedByUserID != other.ID {
		t.Fatalf("photo NSFW metadata after mark = %+v, want marked by other", updated)
	}

	unmarkForbiddenRec := httptest.NewRecorder()
	unmarkForbiddenCtx, _ := gin.CreateTestContext(unmarkForbiddenRec)
	unmarkForbiddenCtx.Request = httptest.NewRequest(http.MethodDelete, fmt.Sprintf("/api/photos/%d/nsfw", photo.ID), nil)
	unmarkForbiddenCtx.Set("user", other)
	unmarkForbiddenCtx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoNsfwDelete(unmarkForbiddenCtx)
	if unmarkForbiddenRec.Code != http.StatusForbidden {
		t.Fatalf("non-poster unmark status = %d, want 403", unmarkForbiddenRec.Code)
	}

	posterUnmarkRec := httptest.NewRecorder()
	posterUnmarkCtx, _ := gin.CreateTestContext(posterUnmarkRec)
	posterUnmarkCtx.Request = httptest.NewRequest(http.MethodDelete, fmt.Sprintf("/api/photos/%d/nsfw", photo.ID), nil)
	posterUnmarkCtx.Set("user", poster)
	posterUnmarkCtx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoNsfwDelete(posterUnmarkCtx)
	if posterUnmarkRec.Code != http.StatusOK {
		t.Fatalf("poster unmark status = %d, want 200", posterUnmarkRec.Code)
	}

	updated = models.Photo{}
	if err := server.DB.First(&updated, photo.ID).Error; err != nil {
		t.Fatalf("load photo after poster unmark: %v", err)
	}
	if updated.Nsfw || updated.NsfwMarkedByUserID != nil || updated.NsfwMarkedAt != nil {
		t.Fatalf("photo NSFW metadata after poster unmark = %+v, want cleared", updated)
	}

	if err := server.DB.Model(&models.Photo{}).Where("id = ?", photo.ID).Updates(map[string]any{
		"nsfw":                   true,
		"nsfw_marked_by_user_id": other.ID,
		"nsfw_marked_at":         time.Now().UTC(),
	}).Error; err != nil {
		t.Fatalf("re-mark photo: %v", err)
	}

	adminUnmarkRec := httptest.NewRecorder()
	adminUnmarkCtx, _ := gin.CreateTestContext(adminUnmarkRec)
	adminUnmarkCtx.Request = httptest.NewRequest(http.MethodDelete, fmt.Sprintf("/api/photos/%d/nsfw", photo.ID), nil)
	adminUnmarkCtx.Set("user", admin)
	adminUnmarkCtx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoNsfwDelete(adminUnmarkCtx)
	if adminUnmarkRec.Code != http.StatusOK {
		t.Fatalf("admin unmark status = %d, want 200", adminUnmarkRec.Code)
	}
}

func TestPhotoNsfwMarkAutoSubscribesForeignMarker(t *testing.T) {
	server := newSearchTestServer(t)
	poster := models.User{Username: "poster", PasswordHash: "x", AllowCommunityNsfwMarking: true}
	other := models.User{Username: "other", PasswordHash: "x", AutoSubscribeInteractedPostsEnabled: true}
	for _, user := range []*models.User{&poster, &other} {
		if err := server.DB.Create(user).Error; err != nil {
			t.Fatalf("create user %s: %v", user.Username, err)
		}
	}
	photo := models.Photo{
		UserID:    poster.ID,
		User:      poster,
		Day:       "2026-06-18",
		FilePath:  "2026-06-18/test.jpg",
		CreatedAt: time.Date(2026, 6, 18, 18, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}

	rec := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(rec)
	ctx.Request = httptest.NewRequest(http.MethodPost, fmt.Sprintf("/api/photos/%d/nsfw", photo.ID), nil)
	ctx.Set("user", other)
	ctx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoNsfwCreate(ctx)

	if rec.Code != http.StatusOK {
		t.Fatalf("handlePhotoNsfwCreate() status = %d, want 200", rec.Code)
	}
	var bookmark models.PhotoBookmark
	if err := server.DB.Where("user_id = ? AND photo_id = ?", other.ID, photo.ID).First(&bookmark).Error; err != nil {
		t.Fatalf("load bookmark: %v", err)
	}
	if !bookmark.Active || bookmark.SubscriptionSource != photoBookmarkSourceAutoInteraction {
		t.Fatalf("bookmark after nsfw mark = %+v, want active auto bookmark", bookmark)
	}
}

func TestPhotoJSONForViewerIncludesNsfwFlags(t *testing.T) {
	server := newSearchTestServer(t)
	poster := models.User{Username: "poster", PasswordHash: "x"}
	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	if err := server.DB.Create(&poster).Error; err != nil {
		t.Fatalf("create poster: %v", err)
	}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	photo := models.Photo{
		UserID:             poster.ID,
		User:               poster,
		Day:                "2026-05-26",
		FilePath:           "2026-05-26/test.jpg",
		Nsfw:               true,
		NsfwMarkedByUserID: &viewer.ID,
		CreatedAt:          time.Date(2026, 5, 26, 18, 0, 0, 0, time.UTC),
	}
	now := time.Now().UTC()
	photo.NsfwMarkedAt = &now
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}

	photo.User.AllowCommunityNsfwMarking = true
	viewerDecorations, err := server.photoDecorationsForViewer(viewer.ID, []uint{photo.ID})
	if err != nil {
		t.Fatalf("photoDecorationsForViewer(viewer) error = %v", err)
	}
	row := server.photoJSONForViewer(viewer.ID, photo, viewerDecorations)
	if row["nsfw"] != true || row["nsfwMarkAllowed"] != true || row["nsfwUnmarkAllowed"] != false {
		t.Fatalf("viewer row nsfw flags = %#v", row)
	}

	ownerDecorations, err := server.photoDecorationsForViewer(poster.ID, []uint{photo.ID})
	if err != nil {
		t.Fatalf("photoDecorationsForViewer(owner) error = %v", err)
	}
	ownerRow := server.photoJSONForViewer(poster.ID, photo, ownerDecorations)
	if ownerRow["nsfwMarkAllowed"] != true || ownerRow["nsfwUnmarkAllowed"] != true {
		t.Fatalf("owner row nsfw flags = %#v", ownerRow)
	}
}

func TestNotifyPhotoNsfwMarkedSendsOwnerAndBookmarkNotifications(t *testing.T) {
	server := newSearchTestServer(t)
	sender, ok := server.Notifier.(*recordingSender)
	if !ok {
		t.Fatal("recording sender missing")
	}
	owner := models.User{Username: "owner", PasswordHash: "x", PostChangePushEnabled: true, OwnPostNumberInPushEnabled: true}
	actor := models.User{Username: "actor", PasswordHash: "x"}
	bookmarker := models.User{Username: "bookmarker", PasswordHash: "x", PostChangePushEnabled: true, PostNumberInPushEnabled: true}
	for _, user := range []*models.User{&owner, &actor, &bookmarker} {
		if err := server.DB.Create(user).Error; err != nil {
			t.Fatalf("create user %s: %v", user.Username, err)
		}
	}
	if err := server.DB.Create(&models.DeviceToken{UserID: owner.ID, Token: "owner-token"}).Error; err != nil {
		t.Fatalf("create owner token: %v", err)
	}
	if err := server.DB.Create(&models.DeviceToken{UserID: bookmarker.ID, Token: "bookmarker-token"}).Error; err != nil {
		t.Fatalf("create bookmarker token: %v", err)
	}
	number := "260618001"
	photo := models.Photo{ID: 42, UserID: owner.ID, Day: "2026-06-18", PublicNumber: &number}
	if err := server.DB.Create(&models.PhotoBookmark{
		UserID:             bookmarker.ID,
		PhotoID:            photo.ID,
		Active:             true,
		SubscriptionSource: photoBookmarkSourceManual,
		CreatedAt:          time.Now().UTC(),
	}).Error; err != nil {
		t.Fatalf("create bookmark: %v", err)
	}

	server.notifyPhotoNsfwMarked(actor, photo)

	if len(sender.messages) != 2 {
		t.Fatalf("messages = %d, want 2", len(sender.messages))
	}
	if sender.messages[0].Type != "photo_nsfw_marked" {
		t.Fatalf("owner message type = %q, want photo_nsfw_marked", sender.messages[0].Type)
	}
	if sender.messages[1].Type != "bookmarked_photo_nsfw_marked" {
		t.Fatalf("bookmark message type = %q, want bookmarked_photo_nsfw_marked", sender.messages[1].Type)
	}
}

func TestHandlePhotoAttachmentCreateReturnsUpdatedMediaAndRejectsPrimaryDuplicate(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "owner", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}
	now := time.Now().UTC()
	photo := models.Photo{
		UserID:    user.ID,
		User:      user,
		Day:       now.Format("2006-01-02"),
		FilePath:  "2026-06-18/test.jpg",
		CreatedAt: now.Add(-time.Minute),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}

	makeRequest := func(filename string, payload []byte) *http.Request {
		var body bytes.Buffer
		writer := multipart.NewWriter(&body)
		part, err := writer.CreateFormFile("photo", filename)
		if err != nil {
			t.Fatalf("create form file: %v", err)
		}
		if _, err := part.Write(payload); err != nil {
			t.Fatalf("write payload: %v", err)
		}
		if err := writer.Close(); err != nil {
			t.Fatalf("close writer: %v", err)
		}
		req := httptest.NewRequest(http.MethodPost, fmt.Sprintf("/api/photos/%d/attachments", photo.ID), &body)
		req.Header.Set("Content-Type", writer.FormDataContentType())
		return req
	}

	firstPayload := []byte("first-attachment")
	firstRec := httptest.NewRecorder()
	firstCtx, _ := gin.CreateTestContext(firstRec)
	firstCtx.Request = makeRequest("extra-one.jpg", firstPayload)
	firstCtx.Set("user", user)
	firstCtx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoAttachmentCreate(firstCtx)

	if firstRec.Code != http.StatusCreated {
		t.Fatalf("first attachment status = %d, want 201", firstRec.Code)
	}
	var firstBody struct {
		Photo map[string]any `json:"photo"`
	}
	if err := json.Unmarshal(firstRec.Body.Bytes(), &firstBody); err != nil {
		t.Fatalf("decode first response: %v", err)
	}
	media, ok := firstBody.Photo["media"].([]any)
	if !ok || len(media) != 2 {
		t.Fatalf("photo.media = %#v, want 2 items", firstBody.Photo["media"])
	}
	if got, ok := firstBody.Photo["mediaCount"].(float64); !ok || int(got) != 2 {
		t.Fatalf("photo.mediaCount = %#v, want 2", firstBody.Photo["mediaCount"])
	}

	sum := sha256.Sum256(firstPayload)
	if err := server.DB.Model(&models.Photo{}).Where("id = ?", photo.ID).Update("primary_digest", hex.EncodeToString(sum[:])).Error; err != nil {
		t.Fatalf("update primary digest: %v", err)
	}
	dupRec := httptest.NewRecorder()
	dupCtx, _ := gin.CreateTestContext(dupRec)
	dupCtx.Request = makeRequest("extra-dup.jpg", firstPayload)
	dupCtx.Set("user", user)
	dupCtx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoAttachmentCreate(dupCtx)

	if dupRec.Code != http.StatusConflict {
		t.Fatalf("duplicate attachment status = %d, want 409", dupRec.Code)
	}
}

func TestHandlePhotoAttachmentCreateAllowsOlderVisiblePostOfSameDay(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "owner", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}
	now := time.Now().UTC()
	older := models.Photo{
		UserID:    user.ID,
		User:      user,
		Day:       now.Format("2006-01-02"),
		FilePath:  "2026-06-18/older.jpg",
		CreatedAt: now.Add(-3 * time.Minute),
	}
	latest := models.Photo{
		UserID:    user.ID,
		User:      user,
		Day:       now.Format("2006-01-02"),
		FilePath:  "2026-06-18/latest.jpg",
		CreatedAt: now.Add(-time.Minute),
	}
	if err := server.DB.Create(&older).Error; err != nil {
		t.Fatalf("create older photo: %v", err)
	}
	if err := server.DB.Create(&latest).Error; err != nil {
		t.Fatalf("create latest photo: %v", err)
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("photo", "append.jpg")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := part.Write([]byte("append-visible-same-day")); err != nil {
		t.Fatalf("write payload: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	rec := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(rec)
	ctx.Request = httptest.NewRequest(http.MethodPost, fmt.Sprintf("/api/photos/%d/attachments", older.ID), &body)
	ctx.Request.Header.Set("Content-Type", writer.FormDataContentType())
	ctx.Set("user", user)
	ctx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", older.ID)}}
	server.handlePhotoAttachmentCreate(ctx)

	if rec.Code != http.StatusCreated {
		t.Fatalf("attachment to older visible post status = %d, want 201", rec.Code)
	}
}

func TestHandlePhotoAttachmentCreateAllowsOtherUserOnActiveCommunityPost(t *testing.T) {
	server := newSearchTestServer(t)
	owner := models.User{Username: "community-owner", PasswordHash: "x"}
	contributor := models.User{Username: "community-contributor", PasswordHash: "x"}
	if err := server.DB.Create(&owner).Error; err != nil {
		t.Fatalf("create owner: %v", err)
	}
	if err := server.DB.Create(&contributor).Error; err != nil {
		t.Fatalf("create contributor: %v", err)
	}
	now := time.Now().UTC()
	activatedAt := now.Add(-time.Minute)
	community := models.Photo{
		UserID: owner.ID, User: owner, Day: now.Format("2006-01-02"),
		FilePath: "community/primary.jpg", CommunityPost: true,
		CommunityActivatedAt: &activatedAt, CreatedAt: activatedAt,
	}
	contributorsOwnPost := models.Photo{
		UserID: contributor.ID, User: contributor, Day: now.Format("2006-01-02"),
		FilePath: "contributor/own-daily.jpg", CreatedAt: now.Add(-2 * time.Minute),
	}
	if err := server.DB.Create(&community).Error; err != nil {
		t.Fatalf("create community post: %v", err)
	}
	if err := server.DB.Create(&contributorsOwnPost).Error; err != nil {
		t.Fatalf("create contributor own post: %v", err)
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("photo", "community-contribution.jpg")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := part.Write([]byte("community-contribution")); err != nil {
		t.Fatalf("write payload: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	recorder := httptest.NewRecorder()
	context, _ := gin.CreateTestContext(recorder)
	context.Request = httptest.NewRequest(http.MethodPost, fmt.Sprintf("/api/photos/%d/attachments", community.ID), &body)
	context.Request.Header.Set("Content-Type", writer.FormDataContentType())
	context.Set("user", contributor)
	context.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", community.ID)}}
	server.handlePhotoAttachmentCreate(context)

	if recorder.Code != http.StatusCreated {
		t.Fatalf("community attachment status = %d, want 201, body=%s", recorder.Code, recorder.Body.String())
	}
	var attachment models.PhotoAttachment
	if err := server.DB.Where("photo_id = ?", community.ID).First(&attachment).Error; err != nil {
		t.Fatalf("find community attachment: %v", err)
	}
	if attachment.UserID != contributor.ID {
		t.Fatalf("attachment user_id = %d, want contributor %d", attachment.UserID, contributor.ID)
	}
	var ownAttachmentCount int64
	if err := server.DB.Model(&models.PhotoAttachment{}).Where("photo_id = ?", contributorsOwnPost.ID).Count(&ownAttachmentCount).Error; err != nil {
		t.Fatalf("count own-post attachments: %v", err)
	}
	if ownAttachmentCount != 0 {
		t.Fatalf("contributor own-post attachment count = %d, want 0", ownAttachmentCount)
	}

	var response struct {
		Photo struct {
			CommunityContributors []struct {
				ID uint `json:"id"`
			} `json:"communityContributors"`
		} `json:"photo"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(response.Photo.CommunityContributors) != 2 {
		t.Fatalf("community contributors = %#v, want owner and contributor", response.Photo.CommunityContributors)
	}
	if response.Photo.CommunityContributors[0].ID != owner.ID || response.Photo.CommunityContributors[1].ID != contributor.ID {
		t.Fatalf("community contributors = %#v, want owner=%d contributor=%d", response.Photo.CommunityContributors, owner.ID, contributor.ID)
	}
}

func TestHandlePhotoAttachmentCreateRejectsOtherUserOnNormalPost(t *testing.T) {
	server := newSearchTestServer(t)
	owner := models.User{Username: "normal-owner", PasswordHash: "x"}
	otherUser := models.User{Username: "normal-other", PasswordHash: "x"}
	if err := server.DB.Create(&owner).Error; err != nil {
		t.Fatalf("create owner: %v", err)
	}
	if err := server.DB.Create(&otherUser).Error; err != nil {
		t.Fatalf("create other user: %v", err)
	}
	photo := models.Photo{
		UserID: owner.ID, User: owner, Day: time.Now().UTC().Format("2006-01-02"),
		FilePath: "normal/primary.jpg", CreatedAt: time.Now().UTC().Add(-time.Minute),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create normal post: %v", err)
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("photo", "forbidden.jpg")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := part.Write([]byte("forbidden-contribution")); err != nil {
		t.Fatalf("write payload: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	recorder := httptest.NewRecorder()
	context, _ := gin.CreateTestContext(recorder)
	context.Request = httptest.NewRequest(http.MethodPost, fmt.Sprintf("/api/photos/%d/attachments", photo.ID), &body)
	context.Request.Header.Set("Content-Type", writer.FormDataContentType())
	context.Set("user", otherUser)
	context.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoAttachmentCreate(context)

	if recorder.Code != http.StatusForbidden {
		t.Fatalf("normal foreign attachment status = %d, want 403, body=%s", recorder.Code, recorder.Body.String())
	}
	var attachmentCount int64
	if err := server.DB.Model(&models.PhotoAttachment{}).Where("photo_id = ?", photo.ID).Count(&attachmentCount).Error; err != nil {
		t.Fatalf("count normal-post attachments: %v", err)
	}
	if attachmentCount != 0 {
		t.Fatalf("normal foreign attachment count = %d, want 0", attachmentCount)
	}
}

func TestHandlePhotoAttachmentCreateAcceptsCapturedAtWithoutSeconds(t *testing.T) {
	server := newSearchTestServer(t)
	user := models.User{Username: "owner", PasswordHash: "x"}
	if err := server.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}
	now := time.Now().UTC()
	photo := models.Photo{
		UserID:    user.ID,
		User:      user,
		Day:       now.Format("2006-01-02"),
		FilePath:  "2026-06-18/test.jpg",
		CreatedAt: now.Add(-time.Minute),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	if err := writer.WriteField("captured_at", "2026-07-04T18:58+02:00"); err != nil {
		t.Fatalf("write captured_at: %v", err)
	}
	part, err := writer.CreateFormFile("photo", "append.jpg")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	if _, err := part.Write([]byte("append-without-seconds")); err != nil {
		t.Fatalf("write payload: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("close writer: %v", err)
	}

	rec := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(rec)
	ctx.Request = httptest.NewRequest(http.MethodPost, fmt.Sprintf("/api/photos/%d/attachments", photo.ID), &body)
	ctx.Request.Header.Set("Content-Type", writer.FormDataContentType())
	ctx.Set("user", user)
	ctx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoAttachmentCreate(ctx)

	if rec.Code != http.StatusCreated {
		t.Fatalf("attachment with captured_at without seconds status = %d, want 201, body=%s", rec.Code, rec.Body.String())
	}
}

func TestSendPhotoNotificationUsesOwnAndBookmarkPostNumberFlagsSeparately(t *testing.T) {
	server := newSearchTestServer(t)
	sender, ok := server.Notifier.(*recordingSender)
	if !ok {
		t.Fatal("recording sender missing")
	}
	number := "260526001"
	photo := models.Photo{Day: "2026-05-26", PublicNumber: &number}

	server.sendPhotoNotification([]notificationRecipient{
		{Token: "own-off", PostNumberInPushEnabled: false},
		{Token: "own-on", PostNumberInPushEnabled: true},
	}, "Neue Reaktion", "alice hat auf deinen Beitrag reagiert", "photo_reaction", photo, "")

	if len(sender.messages) != 2 {
		t.Fatalf("messages = %d, want 2", len(sender.messages))
	}
	if got := sender.messages[0].Body; got != "alice hat auf deinen Beitrag reagiert" {
		t.Fatalf("body without post number = %q", got)
	}
	if got := sender.messages[1].Body; got != "alice hat auf deinen Beitrag reagiert #260526001" {
		t.Fatalf("body with post number = %q", got)
	}

	sender.messages = nil
	server.sendPhotoNotification([]notificationRecipient{
		{Token: "bookmark-off", PostNumberInPushEnabled: false},
		{Token: "bookmark-on", PostNumberInPushEnabled: true},
	}, "Aktivitaet auf gemerktem Beitrag", "bob hat einen gemerkten Beitrag kommentiert", "bookmarked_photo_comment", photo, "")

	if len(sender.messages) != 2 {
		t.Fatalf("bookmark messages = %d, want 2", len(sender.messages))
	}
	if got := sender.messages[0].Body; got != "bob hat einen gemerkten Beitrag kommentiert" {
		t.Fatalf("bookmark body without post number = %q", got)
	}
	if got := sender.messages[1].Body; got != "bob hat einen gemerkten Beitrag kommentiert #260526001" {
		t.Fatalf("bookmark body with post number = %q", got)
	}
}

func TestHandleFeedWindowLocksTodayWithoutOwnVisiblePost(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	author := models.User{Username: "author", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	if err := server.DB.Create(&author).Error; err != nil {
		t.Fatalf("create author: %v", err)
	}
	today := time.Now().In(time.UTC).Format("2006-01-02")
	photo := models.Photo{
		UserID:    author.ID,
		User:      author,
		Day:       today,
		FilePath:  today + "/test.jpg",
		CreatedAt: time.Now().In(time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}

	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	req := httptest.NewRequest(http.MethodGet, "/api/feed/window?anchor_day="+today, nil)
	c.Request = req
	c.Set("user", viewer)

	server.handleFeedWindow(c)

	if rec.Code != http.StatusForbidden {
		t.Fatalf("handleFeedWindow() status = %d, want 403", rec.Code)
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode body: %v", err)
	}
	if got := body["code"]; got != "feed_locked" {
		t.Fatalf("code = %#v, want feed_locked", got)
	}
	if got := body["errorCode"]; got != "daily_required" {
		t.Fatalf("errorCode = %#v, want daily_required", got)
	}
}

func TestHandleFeedWindowExplicitZeroSkipsNewerSide(t *testing.T) {
	server, viewer := newFeedWindowDirectionTestServer(t)

	body := requestFeedWindowForTest(t, server, viewer, "/api/feed/window?anchor_day=2026-05-12&before_days=0&after_days=2")
	days := feedWindowDaysForTest(t, body)

	want := []string{"2026-05-12", "2026-05-11", "2026-05-10"}
	if fmt.Sprint(days) != fmt.Sprint(want) {
		t.Fatalf("days = %v, want %v", days, want)
	}
	if got := int(body["requestedBeforeDays"].(float64)); got != 0 {
		t.Fatalf("requestedBeforeDays = %d, want 0", got)
	}
	if got := body["minReturnedDay"]; got != "2026-05-10" {
		t.Fatalf("minReturnedDay = %#v, want 2026-05-10", got)
	}
	if got := body["maxReturnedDay"]; got != "2026-05-12" {
		t.Fatalf("maxReturnedDay = %#v, want 2026-05-12", got)
	}
}

func TestHandleFeedWindowExplicitZeroSkipsOlderSide(t *testing.T) {
	server, viewer := newFeedWindowDirectionTestServer(t)

	body := requestFeedWindowForTest(t, server, viewer, "/api/feed/window?anchor_day=2026-05-12&before_days=2&after_days=0")
	days := feedWindowDaysForTest(t, body)

	want := []string{"2026-07-12", "2026-05-14", "2026-05-12"}
	if fmt.Sprint(days) != fmt.Sprint(want) {
		t.Fatalf("days = %v, want %v", days, want)
	}
	if got := int(body["requestedAfterDays"].(float64)); got != 0 {
		t.Fatalf("requestedAfterDays = %d, want 0", got)
	}
	if got := body["minReturnedDay"]; got != "2026-05-12" {
		t.Fatalf("minReturnedDay = %#v, want 2026-05-12", got)
	}
	if got := body["maxReturnedDay"]; got != "2026-07-12" {
		t.Fatalf("maxReturnedDay = %#v, want 2026-07-12", got)
	}
}

func TestHandleFeedWindowRejectsNegativeWindowCount(t *testing.T) {
	server, viewer := newFeedWindowDirectionTestServer(t)

	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	c.Request = httptest.NewRequest(http.MethodGet, "/api/feed/window?anchor_day=2026-05-12&before_days=-1&after_days=0", nil)
	c.Set("user", viewer)

	server.handleFeedWindow(c)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("handleFeedWindow() status = %d, want 400", rec.Code)
	}
}

func newFeedWindowDirectionTestServer(t *testing.T) (*Server, models.User) {
	t.Helper()
	server := newSearchTestServer(t)
	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	for _, day := range []string{"2026-07-12", "2026-05-14", "2026-05-12", "2026-05-11", "2026-05-10"} {
		createdAt, err := time.Parse("2006-01-02", day)
		if err != nil {
			t.Fatalf("parse day %s: %v", day, err)
		}
		photo := models.Photo{
			UserID:    viewer.ID,
			User:      viewer,
			Day:       day,
			FilePath:  day + "/test.jpg",
			CreatedAt: createdAt,
		}
		if err := server.DB.Create(&photo).Error; err != nil {
			t.Fatalf("create photo %s: %v", day, err)
		}
	}
	return server, viewer
}

func requestFeedWindowForTest(t *testing.T, server *Server, viewer models.User, target string) map[string]any {
	t.Helper()
	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	c.Request = httptest.NewRequest(http.MethodGet, target, nil)
	c.Set("user", viewer)

	server.handleFeedWindow(c)

	if rec.Code != http.StatusOK {
		t.Fatalf("handleFeedWindow() status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	return body
}

func feedWindowDaysForTest(t *testing.T, body map[string]any) []string {
	t.Helper()
	rawDays, ok := body["days"].([]any)
	if !ok {
		t.Fatalf("days missing or wrong type: %#v", body["days"])
	}
	days := make([]string, 0, len(rawDays))
	for _, raw := range rawDays {
		payload, ok := raw.(map[string]any)
		if !ok {
			t.Fatalf("day payload wrong type: %#v", raw)
		}
		day, ok := payload["day"].(string)
		if !ok {
			t.Fatalf("day missing or wrong type: %#v", payload["day"])
		}
		days = append(days, day)
	}
	return days
}

func TestFeedDaysForUserDoesNotReinsertLockedTodayAnchor(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	author := models.User{Username: "author", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	if err := server.DB.Create(&author).Error; err != nil {
		t.Fatalf("create author: %v", err)
	}
	today := time.Now().In(time.UTC).Format("2006-01-02")
	oldDay := time.Now().In(time.UTC).AddDate(0, 0, -1).Format("2006-01-02")
	photos := []models.Photo{
		{UserID: author.ID, User: author, Day: today, FilePath: today + "/today.jpg", CreatedAt: time.Now().In(time.UTC)},
		{UserID: author.ID, User: author, Day: oldDay, FilePath: oldDay + "/old.jpg", CreatedAt: time.Now().In(time.UTC).AddDate(0, 0, -1)},
	}
	for _, photo := range photos {
		if err := server.DB.Create(&photo).Error; err != nil {
			t.Fatalf("create photo %s: %v", photo.Day, err)
		}
	}

	days, _, _, err := server.feedDaysForUser(viewer.ID, "", "", "", "", 60, today, time.Now().In(time.UTC))
	if err != nil {
		t.Fatalf("feedDaysForUser() error = %v", err)
	}
	for _, day := range days {
		if day == today {
			t.Fatalf("feedDaysForUser() returned locked today anchor %q in %v", today, days)
		}
	}
}

func TestFeedPayloadForDayIncludesInteractionPreviewMetadata(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	author := models.User{Username: "author", PasswordHash: "x"}
	reactor := models.User{Username: "reactor", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	if err := server.DB.Create(&author).Error; err != nil {
		t.Fatalf("create author: %v", err)
	}
	if err := server.DB.Create(&reactor).Error; err != nil {
		t.Fatalf("create reactor: %v", err)
	}

	day := "2026-07-01"
	if err := server.DB.Create(&models.DailyPrompt{Day: day, TriggerSource: "daily_moment"}).Error; err != nil {
		t.Fatalf("create prompt: %v", err)
	}
	photo := models.Photo{
		UserID:    author.ID,
		Day:       day,
		FilePath:  day + "/post.jpg",
		CreatedAt: time.Date(2026, 7, 1, 12, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	if err := server.DB.Create(&models.PhotoComment{
		PhotoID: photo.ID,
		UserID:  reactor.ID,
		Body:    "sichtbar",
	}).Error; err != nil {
		t.Fatalf("create comment: %v", err)
	}
	if err := server.DB.Create(&models.PhotoReaction{
		PhotoID: photo.ID,
		UserID:  reactor.ID,
		Emoji:   "fire",
	}).Error; err != nil {
		t.Fatalf("create reaction: %v", err)
	}

	payload, status, err := server.feedPayloadForDay(viewer.ID, day, time.Date(2026, 7, 2, 12, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("feedPayloadForDay() error = %v", err)
	}
	if status != http.StatusOK {
		t.Fatalf("feedPayloadForDay() status = %d, want 200", status)
	}
	items, ok := payload["items"].([]gin.H)
	if !ok || len(items) != 1 {
		t.Fatalf("items = %#v, want one item", payload["items"])
	}
	snapshot, ok := items[0]["interactionSnapshot"].(gin.H)
	if !ok {
		t.Fatalf("interactionSnapshot = %#v, want gin.H", items[0]["interactionSnapshot"])
	}
	if got := snapshot["kind"]; got != "preview" {
		t.Fatalf("interactionSnapshot.kind = %#v, want preview", got)
	}
	if got := snapshot["commentPreviewLimit"]; got == nil {
		t.Fatalf("interactionSnapshot.commentPreviewLimit missing")
	}
	counts, ok := items[0]["interactionCounts"].(gin.H)
	if !ok {
		t.Fatalf("interactionCounts = %#v, want gin.H", items[0]["interactionCounts"])
	}
	if got := counts["comments"]; got != int64(1) {
		t.Fatalf("interactionCounts.comments = %#v, want 1", got)
	}
	if got := counts["reactions"]; got != int64(1) {
		t.Fatalf("interactionCounts.reactions = %#v, want 1", got)
	}
}

func TestPhotoInteractionsPayloadIncludesFullCounts(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	owner := models.User{Username: "owner", PasswordHash: "x"}
	other := models.User{Username: "other", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	if err := server.DB.Create(&owner).Error; err != nil {
		t.Fatalf("create owner: %v", err)
	}
	if err := server.DB.Create(&other).Error; err != nil {
		t.Fatalf("create other: %v", err)
	}
	photo := models.Photo{
		UserID:    owner.ID,
		Day:       "2026-07-02",
		FilePath:  "2026-07-02/post.jpg",
		CreatedAt: time.Date(2026, 7, 2, 12, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	if err := server.DB.Create(&models.PhotoComment{PhotoID: photo.ID, UserID: viewer.ID, Body: "eins"}).Error; err != nil {
		t.Fatalf("create comment: %v", err)
	}
	if err := server.DB.Create(&models.PhotoReaction{PhotoID: photo.ID, UserID: viewer.ID, Emoji: "fire"}).Error; err != nil {
		t.Fatalf("create first reaction: %v", err)
	}
	if err := server.DB.Create(&models.PhotoReaction{PhotoID: photo.ID, UserID: other.ID, Emoji: "fire"}).Error; err != nil {
		t.Fatalf("create second reaction: %v", err)
	}

	payload, err := server.photoInteractionsPayload(photo, viewer.ID)
	if err != nil {
		t.Fatalf("photoInteractionsPayload() error = %v", err)
	}
	if got := payload["full"]; got != true {
		t.Fatalf("full = %#v, want true", got)
	}
	counts, ok := payload["counts"].(gin.H)
	if !ok {
		t.Fatalf("counts = %#v, want gin.H", payload["counts"])
	}
	if got := counts["comments"]; got != 1 {
		t.Fatalf("counts.comments = %#v, want 1", got)
	}
	if got := counts["reactions"]; got != 2 {
		t.Fatalf("counts.reactions = %#v, want 2", got)
	}
}

func TestFeedPayloadForDayAllowsPastDayWithoutTodayPost(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	author := models.User{Username: "author", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	if err := server.DB.Create(&author).Error; err != nil {
		t.Fatalf("create author: %v", err)
	}
	oldDay := time.Now().In(time.UTC).AddDate(0, 0, -2).Format("2006-01-02")
	photo := models.Photo{
		UserID:    author.ID,
		User:      author,
		Day:       oldDay,
		FilePath:  oldDay + "/old.jpg",
		CreatedAt: time.Now().In(time.UTC).AddDate(0, 0, -2),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}

	payload, status, err := server.feedPayloadForDay(viewer.ID, oldDay, time.Now().In(time.UTC))
	if err != nil || status != http.StatusOK {
		t.Fatalf("feedPayloadForDay() = (%v, %d, %v), want status 200 and no error", payload, status, err)
	}
}

func TestSearchPhotoHitsUsesCaptionAndComments(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	author := models.User{Username: "author", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	if err := server.DB.Create(&author).Error; err != nil {
		t.Fatalf("create author: %v", err)
	}
	photo := models.Photo{
		UserID:    author.ID,
		User:      author,
		Day:       "2026-05-26",
		FilePath:  "2026-05-26/test.jpg",
		Caption:   "Sonnenuntergang am See #LakeTrip",
		CreatedAt: time.Date(2026, 5, 26, 18, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	comment := models.PhotoComment{
		PhotoID: photo.ID,
		UserID:  viewer.ID,
		Body:    "Das war kompletter klogrind",
	}
	if err := server.DB.Create(&comment).Error; err != nil {
		t.Fatalf("create comment: %v", err)
	}
	if err := server.refreshPhotoSearchDocument(photo.ID); err != nil {
		t.Fatalf("refreshPhotoSearchDocument() error = %v", err)
	}
	now := time.Date(2026, 5, 26, 20, 0, 0, 0, time.UTC)
	normalized, hits, err := server.searchPhotoHits(viewer.ID, "#laketrip", now, false, 10)
	if err != nil {
		t.Fatalf("searchPhotoHits() hashtag error = %v", err)
	}
	if normalized != "#laketrip" {
		t.Fatalf("normalized hashtag query = %q, want %q", normalized, "#laketrip")
	}
	if len(hits) != 1 || hits[0].Photo.ID != photo.ID {
		t.Fatalf("searchPhotoHits() hashtag ids = %v, want [%d]", len(hits), photo.ID)
	}
	if len(hits[0].MatchedHashtags) != 1 || hits[0].MatchedHashtags[0] != "#laketrip" {
		t.Fatalf("matched hashtags = %v, want [#laketrip]", hits[0].MatchedHashtags)
	}
	normalized, hits, err = server.searchPhotoHits(viewer.ID, "klogrind", now, false, 10)
	if err != nil {
		t.Fatalf("searchPhotoHits() comment error = %v", err)
	}
	if normalized != "klogrind" {
		t.Fatalf("normalized comment query = %q, want %q", normalized, "klogrind")
	}
	if len(hits) != 1 || len(hits[0].MatchedComments) == 0 {
		t.Fatalf("searchPhotoHits() comment matches = %+v, want comment excerpt", hits)
	}
}

func TestHandlePhotoCommentDeduplicatesByClientCommentID(t *testing.T) {
	server := newSearchTestServer(t)
	actor := models.User{Username: "actor", PasswordHash: "x"}
	owner := models.User{Username: "owner", PasswordHash: "x"}
	if err := server.DB.Create(&actor).Error; err != nil {
		t.Fatalf("create actor: %v", err)
	}
	if err := server.DB.Create(&owner).Error; err != nil {
		t.Fatalf("create owner: %v", err)
	}
	photo := models.Photo{
		UserID:    owner.ID,
		Day:       "2026-07-06",
		FilePath:  "2026-07-06/test.jpg",
		CreatedAt: time.Date(2026, 7, 6, 12, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	if err := server.DB.Create(&models.Photo{
		UserID:    actor.ID,
		Day:       "2026-07-06",
		FilePath:  "2026-07-06/actor.jpg",
		CreatedAt: time.Date(2026, 7, 6, 11, 0, 0, 0, time.UTC),
	}).Error; err != nil {
		t.Fatalf("create actor photo: %v", err)
	}

	body := `{"body":"identisch","clientCommentId":"c_1"}`
	firstCtx, firstRec := newJSONRequestContext(http.MethodPost, "/api/photos/1/comments", body, actor)
	firstCtx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoComment(firstCtx)
	if firstRec.Code != http.StatusCreated {
		t.Fatalf("first comment status = %d, want 201, body=%s", firstRec.Code, firstRec.Body.String())
	}

	secondCtx, secondRec := newJSONRequestContext(http.MethodPost, "/api/photos/1/comments", body, actor)
	secondCtx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoComment(secondCtx)
	if secondRec.Code != http.StatusOK {
		t.Fatalf("retry comment status = %d, want 200, body=%s", secondRec.Code, secondRec.Body.String())
	}

	var count int64
	if err := server.DB.Model(&models.PhotoComment{}).Where("photo_id = ?", photo.ID).Count(&count).Error; err != nil {
		t.Fatalf("count comments: %v", err)
	}
	if count != 1 {
		t.Fatalf("comment count = %d, want 1", count)
	}
}

func TestHandlePhotoCommentRejectsConsecutiveDuplicateBody(t *testing.T) {
	server := newSearchTestServer(t)
	actor := models.User{Username: "actor", PasswordHash: "x"}
	owner := models.User{Username: "owner", PasswordHash: "x"}
	if err := server.DB.Create(&actor).Error; err != nil {
		t.Fatalf("create actor: %v", err)
	}
	if err := server.DB.Create(&owner).Error; err != nil {
		t.Fatalf("create owner: %v", err)
	}
	photo := models.Photo{
		UserID:    owner.ID,
		Day:       "2026-07-06",
		FilePath:  "2026-07-06/test.jpg",
		CreatedAt: time.Date(2026, 7, 6, 12, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	if err := server.DB.Create(&models.Photo{
		UserID:    actor.ID,
		Day:       "2026-07-06",
		FilePath:  "2026-07-06/actor.jpg",
		CreatedAt: time.Date(2026, 7, 6, 11, 0, 0, 0, time.UTC),
	}).Error; err != nil {
		t.Fatalf("create actor photo: %v", err)
	}

	firstCtx, firstRec := newJSONRequestContext(http.MethodPost, "/api/photos/1/comments", `{"body":"gleich"}`, actor)
	firstCtx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoComment(firstCtx)
	if firstRec.Code != http.StatusCreated {
		t.Fatalf("first comment status = %d, want 201, body=%s", firstRec.Code, firstRec.Body.String())
	}

	secondCtx, secondRec := newJSONRequestContext(http.MethodPost, "/api/photos/1/comments", `{"body":"gleich"}`, actor)
	secondCtx.Params = gin.Params{{Key: "id", Value: fmt.Sprintf("%d", photo.ID)}}
	server.handlePhotoComment(secondCtx)
	if secondRec.Code != http.StatusConflict {
		t.Fatalf("duplicate comment status = %d, want 409, body=%s", secondRec.Code, secondRec.Body.String())
	}
	var response map[string]any
	if err := json.Unmarshal(secondRec.Body.Bytes(), &response); err != nil {
		t.Fatalf("decode duplicate response: %v", err)
	}
	if got := response["errorCode"]; got != "duplicate_consecutive_comment" {
		t.Fatalf("errorCode = %#v, want duplicate_consecutive_comment", got)
	}
}

func TestHandleDeletePhotoCommentDeletesOwnCommentAndSendsCancel(t *testing.T) {
	server := newSearchTestServer(t)
	sender, ok := server.Notifier.(*recordingSender)
	if !ok {
		t.Fatal("recording sender missing")
	}
	actor := models.User{Username: "actor", PasswordHash: "x"}
	owner := models.User{Username: "owner", PasswordHash: "x", PhotoCommentPushEnabled: true}
	bookmarker := models.User{Username: "bookmarker", PasswordHash: "x", BookmarkedPhotoPushEnabled: true}
	if err := server.DB.Create(&actor).Error; err != nil {
		t.Fatalf("create actor: %v", err)
	}
	if err := server.DB.Create(&owner).Error; err != nil {
		t.Fatalf("create owner: %v", err)
	}
	if err := server.DB.Create(&bookmarker).Error; err != nil {
		t.Fatalf("create bookmarker: %v", err)
	}
	if err := server.DB.Create(&models.DeviceToken{UserID: owner.ID, Token: "owner_token"}).Error; err != nil {
		t.Fatalf("create owner token: %v", err)
	}
	if err := server.DB.Create(&models.DeviceToken{UserID: bookmarker.ID, Token: "bookmarker_token"}).Error; err != nil {
		t.Fatalf("create bookmarker token: %v", err)
	}
	photo := models.Photo{
		UserID:    owner.ID,
		Day:       "2026-07-06",
		FilePath:  "2026-07-06/test.jpg",
		CreatedAt: time.Date(2026, 7, 6, 12, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	if err := server.DB.Create(&models.Photo{
		UserID:    actor.ID,
		Day:       "2026-07-06",
		FilePath:  "2026-07-06/actor.jpg",
		CreatedAt: time.Date(2026, 7, 6, 11, 0, 0, 0, time.UTC),
	}).Error; err != nil {
		t.Fatalf("create actor photo: %v", err)
	}
	if err := server.DB.Create(&models.PhotoBookmark{UserID: bookmarker.ID, PhotoID: photo.ID, Active: true}).Error; err != nil {
		t.Fatalf("create bookmark: %v", err)
	}
	clientCommentID := "c_delete"
	comment := models.PhotoComment{
		PhotoID:         photo.ID,
		UserID:          actor.ID,
		Body:            "loesch mich",
		ClientCommentID: &clientCommentID,
	}
	if err := server.DB.Create(&comment).Error; err != nil {
		t.Fatalf("create comment: %v", err)
	}
	if err := server.DB.Preload("User").First(&comment, comment.ID).Error; err != nil {
		t.Fatalf("reload comment: %v", err)
	}

	server.notifyPhotoComment(actor, photo, comment)
	beforeCancel := len(sender.messages)

	ctx, rec := newJSONRequestContext(http.MethodDelete, "/api/photos/1/comments/1", "", actor)
	ctx.Params = gin.Params{
		{Key: "id", Value: fmt.Sprintf("%d", photo.ID)},
		{Key: "commentId", Value: fmt.Sprintf("%d", comment.ID)},
	}
	server.handleDeletePhotoComment(ctx)
	if rec.Code != http.StatusOK {
		t.Fatalf("delete comment status = %d, want 200, body=%s", rec.Code, rec.Body.String())
	}

	var remaining int64
	if err := server.DB.Model(&models.PhotoComment{}).Where("id = ?", comment.ID).Count(&remaining).Error; err != nil {
		t.Fatalf("count deleted comment: %v", err)
	}
	if remaining != 0 {
		t.Fatalf("remaining deleted comment rows = %d, want 0", remaining)
	}
	if len(sender.messages) <= beforeCancel {
		t.Fatalf("notification count = %d, want cancel message after %d", len(sender.messages), beforeCancel)
	}
	last := sender.messages[len(sender.messages)-1]
	if last.Type != "notification_cancel" {
		t.Fatalf("last notification type = %q, want notification_cancel", last.Type)
	}
	if last.NotificationKey != photoCommentNotificationKey(comment.ID) {
		t.Fatalf("last notification key = %q, want %q", last.NotificationKey, photoCommentNotificationKey(comment.ID))
	}
}

func TestSearchPhotoHitsRespectsVisibility(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	author := models.User{Username: "author", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	if err := server.DB.Create(&author).Error; err != nil {
		t.Fatalf("create author: %v", err)
	}
	visibleAt := time.Date(2026, 5, 27, 12, 0, 0, 0, time.UTC)
	photo := models.Photo{
		UserID:           author.ID,
		User:             author,
		Day:              "2026-05-26",
		FilePath:         "2026-05-26/locked.jpg",
		Caption:          "Versteckt #secretspot",
		CapsuleVisibleAt: &visibleAt,
		CreatedAt:        time.Date(2026, 5, 26, 18, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	if err := server.refreshPhotoSearchDocument(photo.ID); err != nil {
		t.Fatalf("refreshPhotoSearchDocument() error = %v", err)
	}
	now := time.Date(2026, 5, 26, 20, 0, 0, 0, time.UTC)
	_, hits, err := server.searchPhotoHits(viewer.ID, "#secretspot", now, false, 10)
	if err != nil {
		t.Fatalf("searchPhotoHits() error = %v", err)
	}
	if len(hits) != 0 {
		t.Fatalf("searchPhotoHits() returned hidden hit = %+v, want none", hits)
	}
}
