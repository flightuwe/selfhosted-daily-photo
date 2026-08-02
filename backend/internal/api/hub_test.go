package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

func TestHubTimelineCursorPaginationETagAndExplicitViewedState(t *testing.T) {
	server := newSearchTestServer(t)
	viewer := models.User{Username: "timeline-viewer", PasswordHash: "x"}
	if err := server.DB.Create(&viewer).Error; err != nil {
		t.Fatalf("create viewer: %v", err)
	}
	now := time.Now().UTC()
	for index := 0; index < 5; index++ {
		if err := server.DB.Create(&models.HubSystemEvent{
			EventType: "test_event", Scope: hubTimelineSystemEventScope,
			Title: fmt.Sprintf("Event %d", index), OccurredAt: now.Add(-time.Duration(index) * time.Minute),
		}).Error; err != nil {
			t.Fatalf("create timeline event: %v", err)
		}
	}

	requestPage := func(target, etag string) (*httptest.ResponseRecorder, map[string]any) {
		recorder := httptest.NewRecorder()
		context, _ := gin.CreateTestContext(recorder)
		context.Request = httptest.NewRequest(http.MethodGet, target, nil)
		if etag != "" {
			context.Request.Header.Set("If-None-Match", etag)
		}
		context.Set("user", viewer)
		server.handleHubTimeline(context)
		var payload map[string]any
		if recorder.Body.Len() > 0 {
			if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
				t.Fatalf("decode timeline response: %v", err)
			}
		}
		return recorder, payload
	}

	first, firstPayload := requestPage("/api/hub/timeline?limit=2&explicit_viewed=true", "")
	if first.Code != http.StatusOK {
		t.Fatalf("first page status = %d, body=%s", first.Code, first.Body.String())
	}
	items, _ := firstPayload["items"].([]any)
	if len(items) != 2 || firstPayload["hasMore"] != true {
		t.Fatalf("first page items/hasMore = %d/%#v", len(items), firstPayload["hasMore"])
	}
	cursor, _ := firstPayload["nextCursor"].(string)
	if cursor == "" {
		t.Fatal("expected next cursor")
	}
	firstETag := first.Header().Get("ETag")
	if firstETag == "" {
		t.Fatal("timeline response did not include an ETag")
	}
	unchanged, _ := requestPage("/api/hub/timeline?limit=2&explicit_viewed=true", firstETag)
	if unchanged.Code != http.StatusNotModified {
		t.Fatalf("unchanged timeline status = %d, want 304; sent=%q received=%q body=%s", unchanged.Code, firstETag, unchanged.Header().Get("ETag"), unchanged.Body.String())
	}
	second, secondPayload := requestPage("/api/hub/timeline?limit=2&explicit_viewed=true&cursor="+cursor, "")
	secondItems, _ := secondPayload["items"].([]any)
	if second.Code != http.StatusOK || len(secondItems) != 2 {
		t.Fatalf("second page status/items = %d/%d", second.Code, len(secondItems))
	}
	var stored models.User
	if err := server.DB.First(&stored, viewer.ID).Error; err != nil {
		t.Fatalf("reload viewer: %v", err)
	}
	if stored.HubTimelineLastViewedAt != nil {
		t.Fatal("technical timeline GET must not update last viewed state")
	}
	legacy, _ := requestPage("/api/hub/timeline?limit=2", "")
	if legacy.Code != http.StatusOK {
		t.Fatalf("legacy timeline status = %d", legacy.Code)
	}
	if err := server.DB.First(&stored, viewer.ID).Error; err != nil || stored.HubTimelineLastViewedAt == nil {
		t.Fatalf("legacy GET viewed compatibility was not preserved: %v", err)
	}
	stored.HubTimelineLastViewedAt = nil
	if err := server.DB.Model(&stored).Update("hub_timeline_last_viewed_at", nil).Error; err != nil {
		t.Fatalf("reset viewed state: %v", err)
	}
	viewedRecorder := httptest.NewRecorder()
	viewedContext, _ := gin.CreateTestContext(viewedRecorder)
	viewedContext.Request = httptest.NewRequest(http.MethodPost, "/api/hub/timeline/viewed", nil)
	viewedContext.Set("user", viewer)
	server.handleHubTimelineViewed(viewedContext)
	if viewedRecorder.Code != http.StatusOK {
		t.Fatalf("mark viewed status = %d", viewedRecorder.Code)
	}
	if err := server.DB.First(&stored, viewer.ID).Error; err != nil || stored.HubTimelineLastViewedAt == nil {
		t.Fatalf("explicit viewed state was not persisted: %v", err)
	}
}

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
	lockedPhoto, _ := lockedItems[0]["photo"].(gin.H)
	if got, _ := lockedPhoto["thumbnailUrl"].(string); strings.Contains(got, "locked.jpg") {
		t.Fatalf("locked capsule leaked original thumbnail URL: %q", got)
	}
	if media, ok := lockedPhoto["media"].([]gin.H); ok && len(media) != 0 {
		t.Fatalf("locked capsule exposed original media entries: %#v", media)
	}
}

func TestHubTimelinePayloadDeduplicatesBookmarkedOwnComment(t *testing.T) {
	server := newSearchTestServer(t)

	viewer := models.User{
		Username:                   "viewer",
		PasswordHash:               "x",
		PhotoCommentPushEnabled:    true,
		BookmarkedPhotoPushEnabled: true,
	}
	owner := models.User{Username: "owner", PasswordHash: "x"}
	for _, user := range []*models.User{&viewer, &owner} {
		if err := server.DB.Create(user).Error; err != nil {
			t.Fatalf("create user %s: %v", user.Username, err)
		}
	}

	photo := models.Photo{
		UserID:    owner.ID,
		User:      owner,
		Day:       "2026-07-11",
		FilePath:  "2026-07-11/owner.jpg",
		CreatedAt: time.Date(2026, 7, 11, 9, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	if err := server.DB.Create(&models.PhotoBookmark{UserID: viewer.ID, PhotoID: photo.ID, Active: true}).Error; err != nil {
		t.Fatalf("create bookmark: %v", err)
	}
	comment := models.PhotoComment{
		PhotoID:   photo.ID,
		UserID:    viewer.ID,
		User:      viewer,
		Body:      "Mein eigener Kommentar",
		CreatedAt: time.Date(2026, 7, 11, 9, 10, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&comment).Error; err != nil {
		t.Fatalf("create comment: %v", err)
	}

	items, _, err := server.hubTimelinePayload(viewer, time.Date(2026, 7, 11, 12, 0, 0, 0, time.UTC), 40, nil, nil)
	if err != nil {
		t.Fatalf("hubTimelinePayload failed: %v", err)
	}
	commentCount := 0
	bookmarkContext := false
	for _, item := range items {
		if item["id"] == "comment-"+fmt.Sprint(comment.ID) {
			commentCount++
			if item["bookmarkContext"] == true {
				bookmarkContext = true
			}
		}
	}
	if commentCount != 1 {
		t.Fatalf("expected exactly one merged comment item, got %d", commentCount)
	}
	if !bookmarkContext {
		t.Fatal("expected merged comment item to preserve bookmark context")
	}
}

func TestHubTimelinePayloadIncludesOwnerCommentOnBookmarkedPhoto(t *testing.T) {
	server := newSearchTestServer(t)

	viewer := models.User{
		Username:                   "viewer",
		PasswordHash:               "x",
		BookmarkedPhotoPushEnabled: true,
	}
	owner := models.User{Username: "owner", PasswordHash: "x"}
	for _, user := range []*models.User{&viewer, &owner} {
		if err := server.DB.Create(user).Error; err != nil {
			t.Fatalf("create user %s: %v", user.Username, err)
		}
	}

	photo := models.Photo{
		UserID:    owner.ID,
		User:      owner,
		Day:       "2026-07-11",
		FilePath:  "2026-07-11/owner.jpg",
		CreatedAt: time.Date(2026, 7, 11, 9, 0, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&photo).Error; err != nil {
		t.Fatalf("create photo: %v", err)
	}
	if err := server.DB.Create(&models.PhotoBookmark{UserID: viewer.ID, PhotoID: photo.ID, Active: true}).Error; err != nil {
		t.Fatalf("create bookmark: %v", err)
	}
	comment := models.PhotoComment{
		PhotoID:   photo.ID,
		UserID:    owner.ID,
		User:      owner,
		Body:      "Antwort vom Owner",
		CreatedAt: time.Date(2026, 7, 11, 9, 10, 0, 0, time.UTC),
	}
	if err := server.DB.Create(&comment).Error; err != nil {
		t.Fatalf("create comment: %v", err)
	}

	items, _, err := server.hubTimelinePayload(viewer, time.Date(2026, 7, 11, 12, 0, 0, 0, time.UTC), 40, nil, nil)
	if err != nil {
		t.Fatalf("hubTimelinePayload failed: %v", err)
	}
	found := false
	for _, item := range items {
		if item["id"] == "comment-"+fmt.Sprint(comment.ID) {
			found = true
			if item["bookmarkContext"] != true {
				t.Fatal("expected bookmarked owner comment to be marked as bookmark context")
			}
		}
	}
	if !found {
		t.Fatal("expected owner comment on bookmarked photo to appear in timeline")
	}
}
