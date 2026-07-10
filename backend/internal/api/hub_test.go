package api

import (
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

func TestHubTimelinePayloadMarksUnreadAndRespectsClear(t *testing.T) {
	server := newSearchTestServer(t)

	owner := models.User{Username: "owner", PasswordHash: "x", PhotoCommentPushEnabled: true}
	actor := models.User{Username: "actor", PasswordHash: "x"}
	for _, user := range []*models.User{&owner, &actor} {
		if err := server.DB.Create(user).Error; err != nil {
			t.Fatalf("create user %s: %v", user.Username, err)
		}
	}

	photo := models.Photo{
		UserID:    owner.ID,
		User:      owner,
		Day:       "2026-07-10",
		FilePath:  "2026-07-10/owner.jpg",
		CreatedAt: time.Date(2026, 7, 10, 10, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}

	commentCreatedAt := time.Date(2026, 7, 10, 11, 0, 0, 0, time.UTC)
	comment := models.PhotoComment{
		PhotoID:   photo.ID,
		UserID:    actor.ID,
		User:      actor,
		Body:      "Hallo vom Hub-Test",
		CreatedAt: commentCreatedAt,
	}
	if err := server.DB.Create(&comment).Error; err != nil {
		t.Fatalf("create comment: %v", err)
	}

	now := time.Date(2026, 7, 10, 12, 0, 0, 0, time.UTC)
	items, unreadCount, err := server.hubTimelinePayload(owner, now, 20, nil, nil)
	if err != nil {
		t.Fatalf("hubTimelinePayload failed: %v", err)
	}
	if len(items) == 0 {
		t.Fatal("expected at least one hub timeline item")
	}
	if unreadCount == 0 {
		t.Fatal("expected unread hub timeline items")
	}

	clearedAt := now
	itemsAfterClear, unreadAfterClear, err := server.hubTimelinePayload(owner, now, 20, nil, &clearedAt)
	if err != nil {
		t.Fatalf("hubTimelinePayload after clear failed: %v", err)
	}
	if len(itemsAfterClear) != 0 {
		t.Fatalf("expected cleared timeline to hide prior items, got %d items", len(itemsAfterClear))
	}
	if unreadAfterClear != 0 {
		t.Fatalf("expected no unread items after clear, got %d", unreadAfterClear)
	}
}

func TestHubTimeCapsulesPayloadSeparatesLockedAndReleased(t *testing.T) {
	server := newSearchTestServer(t)

	viewer := models.User{Username: "viewer", PasswordHash: "x"}
	author := models.User{Username: "author", PasswordHash: "x"}
	for _, user := range []*models.User{&viewer, &author} {
		if err := server.DB.Create(user).Error; err != nil {
			t.Fatalf("create user %s: %v", user.Username, err)
		}
	}

	now := time.Date(2026, 7, 10, 12, 0, 0, 0, time.UTC)
	lockedAt := now.Add(6 * time.Hour)
	releasedAt := now.Add(-6 * time.Hour)

	locked := models.Photo{
		UserID:             author.ID,
		User:               author,
		Day:                "2026-07-12",
		FilePath:           "2026-07-12/locked.jpg",
		CapsulePreviewPath: "2026-07-12/locked_preview.jpg",
		CapsuleVisibleAt:   &lockedAt,
		CreatedAt:          now.Add(-2 * time.Hour),
	}
	released := models.Photo{
		UserID:           author.ID,
		User:             author,
		Day:              "2026-07-09",
		FilePath:         "2026-07-09/released.jpg",
		CapsuleVisibleAt: &releasedAt,
		CreatedAt:        now.Add(-24 * time.Hour),
	}
	if err := server.DB.Create(&locked).Error; err != nil {
		t.Fatalf("create locked capsule: %v", err)
	}
	if err := server.DB.Create(&released).Error; err != nil {
		t.Fatalf("create released capsule: %v", err)
	}

	payload, err := server.hubTimeCapsulesPayload(viewer.ID, now)
	if err != nil {
		t.Fatalf("hubTimeCapsulesPayload failed: %v", err)
	}

	lockedItems, _ := payload["locked"].([]gin.H)
	releasedItems, _ := payload["released"].([]gin.H)
	if len(lockedItems) != 1 {
		t.Fatalf("expected 1 locked capsule, got %d", len(lockedItems))
	}
	if len(releasedItems) != 1 {
		t.Fatalf("expected 1 released capsule, got %d", len(releasedItems))
	}
}
