package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/config"
	"github.com/yosho/selfhosted-bereal/backend/internal/db"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"github.com/yosho/selfhosted-bereal/backend/internal/notify"
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

	day, allowed, acceptedGrace := server.resolvePromptUploadDay("2026-03-13", now, &capturedAt)
	if day != "2026-03-12" || !allowed || !acceptedGrace {
		t.Fatalf("resolvePromptUploadDay() = (%q, %v, %v), want (%q, true, true)", day, allowed, acceptedGrace, "2026-03-12")
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

	day, allowed, acceptedGrace := server.resolvePromptUploadDay("2026-03-21", now, &capturedAt)
	if day != "2026-03-21" || allowed || acceptedGrace {
		t.Fatalf("resolvePromptUploadDay() = (%q, %v, %v), want (%q, false, false)", day, allowed, acceptedGrace, "2026-03-21")
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

	got := invalidPromptOnlyPhotoIDs(photos, promptByDay)
	if len(got) != 2 || got[0] != uint(2) || got[1] != uint(3) {
		t.Fatalf("invalidPromptOnlyPhotoIDs() = %v, want [2 3]", got)
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
	database, err := db.Connect(filepath.Join(t.TempDir(), "app.db"))
	if err != nil {
		t.Skipf("sqlite runtime unavailable: %v", err)
	}
	return &Server{
		DB:       database,
		Notifier: &recordingSender{},
		Config:   config.Config{PublicBaseURL: "https://daily.example"},
		Location: time.UTC,
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
	}, "Neue Reaktion", "alice hat auf deinen Beitrag reagiert", "photo_reaction", photo)

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
	}, "Aktivitaet auf gemerktem Beitrag", "bob hat einen gemerkten Beitrag kommentiert", "bookmarked_photo_comment", photo)

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
