package api

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/yosho/selfhosted-bereal/backend/internal/config"
	"github.com/yosho/selfhosted-bereal/backend/internal/db"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

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

func newSearchTestServer(t *testing.T) *Server {
	t.Helper()
	database, err := db.Connect(filepath.Join(t.TempDir(), "app.db"))
	if err != nil {
		t.Skipf("sqlite runtime unavailable: %v", err)
	}
	return &Server{
		DB:       database,
		Config:   config.Config{PublicBaseURL: "https://daily.example"},
		Location: time.UTC,
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
