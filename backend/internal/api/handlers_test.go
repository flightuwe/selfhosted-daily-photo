package api

import (
	"testing"
	"time"

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
		{name: "own private photo remains visible", viewer: 7, photo: models.Photo{UserID: 7, CapsulePrivate: true}, want: true},
		{name: "foreign private photo hidden", viewer: 7, photo: models.Photo{UserID: 8, CapsulePrivate: true}, want: false},
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
