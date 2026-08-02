package api

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

const (
	hubTimelineWindowDays       = 7
	hubTimelinePreviewLimit     = 8
	hubTimelineDefaultLimit     = 80
	hubTimelineMaxLimit         = 180
	hubTimelineSystemEventScope = "global"
)

type hubTimelinePrefs struct {
	feedPosts      bool
	specialMoments bool
	invites        bool
	reactions      bool
	comments       bool
	bookmarks      bool
	postChanges    bool
}

type hubTimelineItem struct {
	SortAt time.Time
	Data   gin.H
}

type hubTargetRef struct {
	Day           string
	PhotoID       uint
	CommentID     uint
	PhotoMojiID   uint
	ReactionEmoji string
}

func (s *Server) handleHubBootstrap(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	bootstrap, err := s.hubBootstrapPayload(user, now)
	if err != nil {
		c.JSON(500, gin.H{"error": "hub bootstrap failed"})
		return
	}
	c.JSON(200, bootstrap)
}

func (s *Server) handleHubTimeline(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	limit := hubTimelineDefaultLimit
	if raw := strings.TrimSpace(c.Query("limit")); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil {
			switch {
			case parsed < 1:
				limit = 1
			case parsed > hubTimelineMaxLimit:
				limit = hubTimelineMaxLimit
			default:
				limit = parsed
			}
		}
	}

	viewedAt, clearedAt, err := s.hubTimelineState(user.ID)
	if err != nil {
		c.JSON(500, gin.H{"error": "hub timeline state failed"})
		return
	}
	offset, err := decodeHubTimelineOffsetCursor(strings.TrimSpace(c.Query("cursor")))
	if err != nil {
		c.JSON(400, gin.H{"error": "invalid cursor"})
		return
	}
	itemsWithLookahead, unreadCount, err := s.hubTimelinePayload(user, now, offset+limit+1, viewedAt, clearedAt)
	if err != nil {
		c.JSON(500, gin.H{"error": "hub timeline failed"})
		return
	}
	if offset > len(itemsWithLookahead) {
		offset = len(itemsWithLookahead)
	}
	end := offset + limit
	if end > len(itemsWithLookahead) {
		end = len(itemsWithLookahead)
	}
	items := itemsWithLookahead[offset:end]
	hasMore := end < len(itemsWithLookahead)
	nextCursor := ""
	if hasMore {
		nextCursor = encodeHubTimelineOffsetCursor(end)
	}
	// APKs before timeline_v2 do not know the explicit viewed endpoint. Keep
	// their historical GET-means-viewed behavior while v2 clients opt out.
	if !strings.EqualFold(strings.TrimSpace(c.Query("explicit_viewed")), "true") {
		_ = s.DB.Model(&models.User{}).Where("id = ?", user.ID).
			Update("hub_timeline_last_viewed_at", now.UTC()).Error
	}
	revision := s.syncRevision(timelineRevisionScope)
	etag := revisionETag("timeline", map[string]int64{"all": revision})
	c.Header("ETag", etag)
	c.Header("Cache-Control", "private, no-cache")
	if strings.TrimSpace(c.GetHeader("If-None-Match")) == etag {
		c.Status(304)
		c.Writer.WriteHeaderNow()
		return
	}
	c.JSON(200, gin.H{
		"schemaVersion": "hub_timeline_v2",
		"serverNow":     now,
		"windowDays":    hubTimelineWindowDays,
		"revision":      revision,
		"nextCursor":    nextCursor,
		"hasMore":       hasMore,
		"unreadCount":   unreadCount,
		"clearedAt":     clearedAt,
		"viewedAt":      viewedAt,
		"items":         items,
	})
}

func encodeHubTimelineOffsetCursor(offset int) string {
	return base64.RawURLEncoding.EncodeToString([]byte(strconv.Itoa(offset)))
}

func decodeHubTimelineOffsetCursor(value string) (int, error) {
	if value == "" {
		return 0, nil
	}
	raw, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return 0, err
	}
	offset, err := strconv.Atoi(string(raw))
	if err != nil || offset < 0 || offset > hubTimelineMaxLimit*10 {
		return 0, fmt.Errorf("invalid cursor")
	}
	return offset, nil
}

func (s *Server) handleHubTimelineViewed(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location).UTC()
	if err := s.DB.Model(&models.User{}).Where("id = ?", user.ID).
		Update("hub_timeline_last_viewed_at", now).Error; err != nil {
		c.JSON(500, gin.H{"error": "hub timeline viewed state failed"})
		return
	}
	c.JSON(200, gin.H{"ok": true, "viewedAt": now})
}

func (s *Server) handleHubTimelineClear(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location).UTC()
	if err := s.DB.Model(&models.User{}).Where("id = ?", user.ID).Updates(map[string]any{
		"hub_timeline_cleared_at":     now,
		"hub_timeline_last_viewed_at": now,
	}).Error; err != nil {
		c.JSON(500, gin.H{"error": "hub timeline clear failed"})
		return
	}
	s.bumpSyncRevision(timelineRevisionScope)
	c.JSON(200, gin.H{"ok": true, "clearedAt": now})
}

func (s *Server) handleHubTimeCapsules(c *gin.Context) {
	user, _ := userFromContext(c)
	now := time.Now().In(s.Location)
	payload, err := s.hubTimeCapsulesPayload(user.ID, now)
	if err != nil {
		c.JSON(500, gin.H{"error": "hub time capsules failed"})
		return
	}
	c.JSON(200, payload)
}

func (s *Server) hubBootstrapPayload(user models.User, now time.Time) (gin.H, error) {
	viewedAt, clearedAt, err := s.hubTimelineState(user.ID)
	if err != nil {
		return nil, err
	}
	items, unreadCount, err := s.hubTimelinePayload(user, now, hubTimelinePreviewLimit, viewedAt, clearedAt)
	if err != nil {
		return nil, err
	}
	capsules, err := s.hubTimeCapsulesPayload(user.ID, now)
	if err != nil {
		return nil, err
	}

	dashboard, dashErr := s.dashboardSummaryForHub(user.ID, now)
	if dashErr != nil {
		return nil, dashErr
	}

	return gin.H{
		"schemaVersion": "hub_bootstrap_v1",
		"serverNow":     now,
		"timeline": gin.H{
			"unreadCount": unreadCount,
			"items":       items,
			"viewedAt":    viewedAt,
			"clearedAt":   clearedAt,
			"windowDays":  hubTimelineWindowDays,
		},
		"timeCapsules": capsules,
		"dashboard":    dashboard,
	}, nil
}

func (s *Server) dashboardSummaryForHub(userID uint, now time.Time) (gin.H, error) {
	calendar, err := s.calendarPayload(userID, "public", 0, now)
	if err != nil {
		return nil, err
	}
	items, _ := calendar["items"].([]gin.H)
	if items == nil {
		items = []gin.H{}
	}
	dayStats, _ := calendar["dayStats"].([]gin.H)
	if dayStats == nil {
		dayStats = []gin.H{}
	}
	return gin.H{
		"calendarPreview": dayStats,
		"feedPreview":     items,
	}, nil
}

func (s *Server) hubTimelineState(userID uint) (*time.Time, *time.Time, error) {
	var user models.User
	if err := s.DB.Select("id", "hub_timeline_last_viewed_at", "hub_timeline_cleared_at").First(&user, userID).Error; err != nil {
		return nil, nil, err
	}
	return user.HubTimelineLastViewedAt, user.HubTimelineClearedAt, nil
}

func hubTimelinePreferences(user models.User) hubTimelinePrefs {
	return hubTimelinePrefs{
		// Feed-post pushes are currently client-local and not persisted server-side.
		// Until that preference is modeled on the backend, the hub keeps post activity visible.
		feedPosts:      true,
		specialMoments: user.SpecialMomentPushEnabled,
		invites:        user.InviteRegistrationPushEnabled,
		reactions:      user.PhotoReactionPushEnabled,
		comments:       user.PhotoCommentPushEnabled,
		bookmarks:      user.BookmarkedPhotoPushEnabled,
		postChanges:    user.PostChangePushEnabled,
	}
}

func (s *Server) hubTimelinePayload(user models.User, now time.Time, limit int, viewedAt *time.Time, clearedAt *time.Time) ([]gin.H, int, error) {
	from := now.AddDate(0, 0, -(hubTimelineWindowDays - 1))
	prefs := hubTimelinePreferences(user)
	items := make([]hubTimelineItem, 0, limit*2)

	if prefs.feedPosts {
		rows, err := s.hubPostItems(user.ID, now, from)
		if err != nil {
			return nil, 0, err
		}
		items = append(items, rows...)
	}
	if prefs.comments {
		rows, err := s.hubCommentItems(user.ID, now, from)
		if err != nil {
			return nil, 0, err
		}
		items = append(items, rows...)
	}
	if prefs.reactions {
		rows, err := s.hubReactionItems(user.ID, now, from)
		if err != nil {
			return nil, 0, err
		}
		items = append(items, rows...)
		rows, err = s.hubFotomojiItems(user.ID, now, from)
		if err != nil {
			return nil, 0, err
		}
		items = append(items, rows...)
	}
	if prefs.bookmarks {
		rows, err := s.hubBookmarkedActivityItems(user.ID, now, from)
		if err != nil {
			return nil, 0, err
		}
		items = append(items, rows...)
	}
	if prefs.postChanges {
		rows, err := s.hubPostChangeItems(user.ID, now, from)
		if err != nil {
			return nil, 0, err
		}
		items = append(items, rows...)
	}
	rows, err := s.hubTimeCapsuleUnlockItems(user.ID, now, from)
	if err != nil {
		return nil, 0, err
	}
	items = append(items, rows...)
	if prefs.specialMoments {
		rows, err := s.hubSpecialMomentItems(user.ID, from)
		if err != nil {
			return nil, 0, err
		}
		items = append(items, rows...)
	}
	if prefs.invites {
		rows, err := s.hubInviteItems(user.ID, from)
		if err != nil {
			return nil, 0, err
		}
		items = append(items, rows...)
	}
	rows, err = s.hubSystemEventItems(from)
	if err != nil {
		return nil, 0, err
	}
	items = append(items, rows...)
	items = mergeHubTimelineItems(items)

	sort.SliceStable(items, func(i, j int) bool {
		if items[i].SortAt.Equal(items[j].SortAt) {
			leftID := fmtHubTimelineID(items[i].Data)
			rightID := fmtHubTimelineID(items[j].Data)
			return leftID > rightID
		}
		return items[i].SortAt.After(items[j].SortAt)
	})
	if len(items) > limit {
		items = items[:limit]
	}

	out := make([]gin.H, 0, len(items))
	unreadCount := 0
	for _, item := range items {
		if hubTimelineItemCleared(item.SortAt, clearedAt) {
			continue
		}
		unread := !hubTimelineItemRead(item.SortAt, viewedAt)
		item.Data["unread"] = unread
		if unread {
			unreadCount++
		}
		out = append(out, item.Data)
	}
	return out, unreadCount, nil
}

func hubTimelineItemCleared(at time.Time, clearedAt *time.Time) bool {
	return clearedAt != nil && !at.After(clearedAt.UTC())
}

func hubTimelineItemRead(at time.Time, viewedAt *time.Time) bool {
	return viewedAt != nil && !at.After(viewedAt.UTC())
}

func fmtHubTimelineID(item gin.H) string {
	if id, ok := item["id"].(string); ok {
		return id
	}
	return ""
}

func mergeHubTimelineItems(items []hubTimelineItem) []hubTimelineItem {
	if len(items) < 2 {
		return items
	}
	out := make([]hubTimelineItem, 0, len(items))
	byID := make(map[string]int, len(items))
	for _, item := range items {
		id := fmtHubTimelineID(item.Data)
		if id == "" {
			out = append(out, item)
			continue
		}
		if idx, ok := byID[id]; ok {
			out[idx] = mergeHubTimelineItem(out[idx], item)
			continue
		}
		byID[id] = len(out)
		out = append(out, item)
	}
	return out
}

func mergeHubTimelineItem(primary hubTimelineItem, secondary hubTimelineItem) hubTimelineItem {
	if secondary.SortAt.After(primary.SortAt) {
		primary.SortAt = secondary.SortAt
	}
	if primary.Data == nil {
		primary.Data = gin.H{}
	}
	if secondary.Data == nil {
		return primary
	}
	if primary.Data["bookmarkContext"] == true || secondary.Data["bookmarkContext"] == true {
		primary.Data["bookmarkContext"] = true
	}
	primaryTargetURL, _ := primary.Data["targetUrl"].(string)
	if strings.TrimSpace(primaryTargetURL) == "" {
		if targetURL, ok := secondary.Data["targetUrl"].(string); ok && strings.TrimSpace(targetURL) != "" {
			primary.Data["targetUrl"] = targetURL
		}
	}
	primaryBody, _ := primary.Data["body"].(string)
	if strings.TrimSpace(primaryBody) == "" {
		if body, ok := secondary.Data["body"].(string); ok {
			primary.Data["body"] = body
		}
	}
	primaryTitle, _ := primary.Data["title"].(string)
	if strings.TrimSpace(primaryTitle) == "" {
		if title, ok := secondary.Data["title"].(string); ok {
			primary.Data["title"] = title
		}
	}
	for _, key := range []string{"photo", "photoUser", "actor", "comment", "photoMoji", "target", "meta"} {
		if _, exists := primary.Data[key]; !exists {
			if value, ok := secondary.Data[key]; ok {
				primary.Data[key] = value
			}
		}
	}
	return primary
}

func (s *Server) hubPostItems(viewerID uint, now, from time.Time) ([]hubTimelineItem, error) {
	var photos []models.Photo
	if err := s.DB.Preload("User").
		Where("created_at >= ?", from.UTC()).
		Order("created_at desc, id desc").
		Limit(60).
		Find(&photos).Error; err != nil {
		return nil, err
	}
	return s.hubPhotoRowsFromPhotos(viewerID, now, photos, func(photo models.Photo) gin.H {
		label := "Neuer Beitrag"
		if photo.PromptOnly {
			label = "Neuer Daily-Moment"
		}
		if photo.UserID == viewerID {
			label = "Dein Beitrag"
			if photo.PromptOnly {
				label = "Dein Daily-Moment"
			}
		}
		return gin.H{
			"id":         "post-" + strconv.FormatUint(uint64(photo.ID), 10),
			"type":       map[bool]string{true: "feed_post", false: "extra_post"}[photo.PromptOnly],
			"group":      "activity",
			"system":     false,
			"accent":     "feed",
			"title":      label,
			"body":       "@" + photo.User.Username + " · " + photo.Day,
			"occurredAt": photo.CreatedAt,
			"day":        photo.Day,
			"target":     s.hubTargetJSON(hubTargetRef{Day: photo.Day, PhotoID: photo.ID}),
			"actor":      s.userPublicJSON(viewerID, photo.User),
		}
	})
}

func (s *Server) hubCommentItems(viewerID uint, now, from time.Time) ([]hubTimelineItem, error) {
	return s.hubCommentItemsMatching(viewerID, now, from, func(photo models.Photo, item models.PhotoComment) bool {
		return photo.UserID == viewerID || item.UserID == viewerID
	}, false)
}

func (s *Server) hubCommentItemsMatching(
	viewerID uint,
	now, from time.Time,
	include func(photo models.Photo, item models.PhotoComment) bool,
	bookmarkContext bool,
) ([]hubTimelineItem, error) {
	type row struct {
		Comment models.PhotoComment
		Photo   models.Photo
	}
	var comments []models.PhotoComment
	if err := s.DB.Preload("User").
		Where("created_at >= ?", from.UTC()).
		Order("created_at desc, id desc").
		Limit(80).
		Find(&comments).Error; err != nil {
		return nil, err
	}
	photoIDs := make([]uint, 0, len(comments))
	for _, item := range comments {
		photoIDs = append(photoIDs, item.PhotoID)
	}
	photosByID, err := s.hubPhotosByID(photoIDs)
	if err != nil {
		return nil, err
	}
	rows := make([]hubTimelineItem, 0, len(comments))
	for _, item := range comments {
		photo, ok := photosByID[item.PhotoID]
		if !ok || !photoVisibleToViewer(viewerID, photo, now) {
			continue
		}
		if include != nil && !include(photo, item) {
			continue
		}
		title := "Neuer Kommentar"
		if item.UserID == viewerID {
			title = "Dein Kommentar"
		}
		body := strings.TrimSpace(item.Body)
		if len(body) > 120 {
			body = body[:120] + "..."
		}
		row := s.hubTimelinePhotoItem(
			viewerID,
			photo,
			item.CreatedAt,
			gin.H{
				"id":         "comment-" + strconv.FormatUint(uint64(item.ID), 10),
				"type":       "photo_comment",
				"group":      "interaction",
				"system":     false,
				"accent":     "comment",
				"title":      title,
				"body":       body,
				"occurredAt": item.CreatedAt,
				"day":        photo.Day,
				"target":     s.hubTargetJSON(hubTargetRef{Day: photo.Day, PhotoID: photo.ID, CommentID: item.ID}),
				"actor":      s.userPublicJSON(viewerID, item.User),
				"comment":    s.photoCommentJSON(item),
			},
		)
		if bookmarkContext {
			row.Data["bookmarkContext"] = true
		}
		rows = append(rows, row)
	}
	return rows, nil
}

func (s *Server) hubReactionItems(viewerID uint, now, from time.Time) ([]hubTimelineItem, error) {
	return s.hubReactionItemsMatching(viewerID, now, from, func(photo models.Photo, item models.PhotoReaction) bool {
		return photo.UserID == viewerID || item.UserID == viewerID
	}, false)
}

func (s *Server) hubReactionItemsMatching(
	viewerID uint,
	now, from time.Time,
	include func(photo models.Photo, item models.PhotoReaction) bool,
	bookmarkContext bool,
) ([]hubTimelineItem, error) {
	var reactions []models.PhotoReaction
	if err := s.DB.Where("created_at >= ?", from.UTC()).
		Order("created_at desc, id desc").
		Limit(80).
		Find(&reactions).Error; err != nil {
		return nil, err
	}
	photoIDs := make([]uint, 0, len(reactions))
	userIDs := make([]uint, 0, len(reactions))
	for _, item := range reactions {
		photoIDs = append(photoIDs, item.PhotoID)
		userIDs = append(userIDs, item.UserID)
	}
	photosByID, err := s.hubPhotosByID(photoIDs)
	if err != nil {
		return nil, err
	}
	usersByID, err := s.hubUsersByID(userIDs)
	if err != nil {
		return nil, err
	}
	rows := make([]hubTimelineItem, 0, len(reactions))
	for _, item := range reactions {
		photo, ok := photosByID[item.PhotoID]
		if !ok || !photoVisibleToViewer(viewerID, photo, now) {
			continue
		}
		if include != nil && !include(photo, item) {
			continue
		}
		actor := usersByID[item.UserID]
		title := "Neue Reaktion"
		if item.UserID == viewerID {
			title = "Deine Reaktion"
		}
		row := s.hubTimelinePhotoItem(
			viewerID,
			photo,
			item.CreatedAt,
			gin.H{
				"id":            "reaction-" + strconv.FormatUint(uint64(item.ID), 10),
				"type":          "photo_reaction",
				"group":         "interaction",
				"system":        false,
				"accent":        "reaction",
				"title":         title,
				"body":          item.Emoji + " auf @" + photo.User.Username + " · " + photo.Day,
				"occurredAt":    item.CreatedAt,
				"day":           photo.Day,
				"reactionEmoji": item.Emoji,
				"target":        s.hubTargetJSON(hubTargetRef{Day: photo.Day, PhotoID: photo.ID, ReactionEmoji: item.Emoji}),
				"actor":         s.userPublicJSON(viewerID, actor),
			},
		)
		if bookmarkContext {
			row.Data["bookmarkContext"] = true
		}
		rows = append(rows, row)
	}
	return rows, nil
}

func (s *Server) hubFotomojiItems(viewerID uint, now, from time.Time) ([]hubTimelineItem, error) {
	return s.hubFotomojiItemsMatching(viewerID, now, from, func(photo models.Photo, item models.PhotoFotomoji) bool {
		return photo.UserID == viewerID || item.UserID == viewerID
	}, false)
}

func (s *Server) hubFotomojiItemsMatching(
	viewerID uint,
	now, from time.Time,
	include func(photo models.Photo, item models.PhotoFotomoji) bool,
	bookmarkContext bool,
) ([]hubTimelineItem, error) {
	var items []models.PhotoFotomoji
	if err := s.DB.Preload("User").
		Where("created_at >= ?", from.UTC()).
		Order("created_at desc, id desc").
		Limit(80).
		Find(&items).Error; err != nil {
		return nil, err
	}
	photoIDs := make([]uint, 0, len(items))
	for _, item := range items {
		photoIDs = append(photoIDs, item.PhotoID)
	}
	photosByID, err := s.hubPhotosByID(photoIDs)
	if err != nil {
		return nil, err
	}
	rows := make([]hubTimelineItem, 0, len(items))
	for _, item := range items {
		photo, ok := photosByID[item.PhotoID]
		if !ok || !photoVisibleToViewer(viewerID, photo, now) {
			continue
		}
		if include != nil && !include(photo, item) {
			continue
		}
		title := "Neue FotoMoji"
		if item.UserID == viewerID {
			title = "Deine FotoMoji"
		}
		row := s.hubTimelinePhotoItem(
			viewerID,
			photo,
			item.CreatedAt,
			gin.H{
				"id":         "fotomoji-" + strconv.FormatUint(uint64(item.ID), 10),
				"type":       "photo_fotomoji",
				"group":      "interaction",
				"system":     false,
				"accent":     "fotomoji",
				"title":      title,
				"body":       item.Emoji + " auf @" + photo.User.Username + " · " + photo.Day,
				"occurredAt": item.CreatedAt,
				"day":        photo.Day,
				"target":     s.hubTargetJSON(hubTargetRef{Day: photo.Day, PhotoID: photo.ID, PhotoMojiID: item.ID}),
				"actor":      s.userPublicJSON(viewerID, item.User),
				"photoMoji":  s.photoFotomojiJSON(item, true),
			},
		)
		if bookmarkContext {
			row.Data["bookmarkContext"] = true
		}
		rows = append(rows, row)
	}
	return rows, nil
}

func (s *Server) hubBookmarkedActivityItems(viewerID uint, now, from time.Time) ([]hubTimelineItem, error) {
	var bookmarks []models.PhotoBookmark
	if err := s.DB.Where("user_id = ? AND active = ?", viewerID, true).Find(&bookmarks).Error; err != nil {
		return nil, err
	}
	if len(bookmarks) == 0 {
		return []hubTimelineItem{}, nil
	}
	photoIDs := make([]uint, 0, len(bookmarks))
	bookmarkSet := make(map[uint]struct{}, len(bookmarks))
	for _, bookmark := range bookmarks {
		photoIDs = append(photoIDs, bookmark.PhotoID)
		bookmarkSet[bookmark.PhotoID] = struct{}{}
	}
	commentRows, err := s.hubCommentItemsMatching(viewerID, now, from, func(photo models.Photo, item models.PhotoComment) bool {
		_, exists := bookmarkSet[photo.ID]
		return exists
	}, true)
	if err != nil {
		return nil, err
	}
	reactionRows, err := s.hubReactionItemsMatching(viewerID, now, from, func(photo models.Photo, item models.PhotoReaction) bool {
		_, exists := bookmarkSet[photo.ID]
		return exists
	}, true)
	if err != nil {
		return nil, err
	}
	fotomojiRows, err := s.hubFotomojiItemsMatching(viewerID, now, from, func(photo models.Photo, item models.PhotoFotomoji) bool {
		_, exists := bookmarkSet[photo.ID]
		return exists
	}, true)
	if err != nil {
		return nil, err
	}
	all := append(append(commentRows, reactionRows...), fotomojiRows...)
	out := make([]hubTimelineItem, 0, len(all))
	for _, row := range all {
		target, _ := row.Data["target"].(gin.H)
		photoIDValue, ok := target["photoId"].(uint)
		if !ok {
			switch value := target["photoId"].(type) {
			case int:
				photoIDValue = uint(value)
			case float64:
				photoIDValue = uint(value)
			}
		}
		if _, exists := bookmarkSet[photoIDValue]; !exists {
			continue
		}
		row.Data["bookmarkContext"] = true
		out = append(out, row)
	}
	return out, nil
}

func (s *Server) EnsureHubVersionSystemEvent(now time.Time) error {
	version := strings.TrimSpace(s.Config.AppVersion)
	if version == "" || strings.EqualFold(version, "dev") {
		return nil
	}
	eventType := "backend_runtime_update"
	title := "Backend-Update"
	body := "Runtime-Version " + version + " ist aktiv."
	metaJSON := `{"version":"` + version + `","kind":"backend_runtime"}`
	var existing models.HubSystemEvent
	err := s.DB.Where("event_type = ? AND meta_json = ?", eventType, metaJSON).First(&existing).Error
	if err == nil {
		return nil
	}
	if err != nil && !strings.Contains(strings.ToLower(err.Error()), "record not found") {
		return err
	}
	if err := s.DB.Create(&models.HubSystemEvent{
		EventType:  eventType,
		Scope:      hubTimelineSystemEventScope,
		Title:      title,
		Body:       body,
		OccurredAt: now.UTC(),
		MetaJSON:   metaJSON,
	}).Error; err != nil {
		return err
	}
	s.bumpSyncRevision(timelineRevisionScope)
	return nil
}

func (s *Server) hubPostChangeItems(viewerID uint, now, from time.Time) ([]hubTimelineItem, error) {
	type attachmentRow struct {
		Attachment models.PhotoAttachment
		Photo      models.Photo
	}
	var attachments []models.PhotoAttachment
	if err := s.DB.Where("created_at >= ?", from.UTC()).
		Order("created_at desc, id desc").
		Limit(60).
		Find(&attachments).Error; err != nil {
		return nil, err
	}
	photoIDs := make([]uint, 0, len(attachments))
	for _, attachment := range attachments {
		photoIDs = append(photoIDs, attachment.PhotoID)
	}
	photosByID, err := s.hubPhotosByID(photoIDs)
	if err != nil {
		return nil, err
	}
	rows := make([]hubTimelineItem, 0, len(attachments)+20)
	for _, attachment := range attachments {
		photo, ok := photosByID[attachment.PhotoID]
		if !ok || !photoVisibleToViewer(viewerID, photo, now) {
			continue
		}
		if photo.UserID != viewerID {
			var bookmarkCount int64
			if err := s.DB.Model(&models.PhotoBookmark{}).
				Where("user_id = ? AND photo_id = ? AND active = ?", viewerID, photo.ID, true).
				Count(&bookmarkCount).Error; err != nil {
				return nil, err
			}
			if bookmarkCount == 0 {
				continue
			}
		}
		rows = append(rows, s.hubTimelinePhotoItem(
			viewerID,
			photo,
			attachment.CreatedAt,
			gin.H{
				"id":         "attachment-" + strconv.FormatUint(uint64(attachment.ID), 10),
				"type":       "bookmarked_photo_media_appended",
				"group":      "change",
				"system":     false,
				"accent":     "change",
				"title":      "Bild hinzugefuegt",
				"body":       "@" + photo.User.Username + " · " + photo.Day,
				"occurredAt": attachment.CreatedAt,
				"day":        photo.Day,
				"target":     s.hubTargetJSON(hubTargetRef{Day: photo.Day, PhotoID: photo.ID}),
				"actor":      s.userPublicJSON(viewerID, photo.User),
			},
		))
	}

	var nsfwPhotos []models.Photo
	if err := s.DB.Preload("User").
		Where("nsfw_marked_at IS NOT NULL AND nsfw_marked_at >= ?", from.UTC()).
		Order("nsfw_marked_at desc, id desc").
		Limit(40).
		Find(&nsfwPhotos).Error; err != nil {
		return nil, err
	}
	for _, photo := range nsfwPhotos {
		if !photoVisibleToViewer(viewerID, photo, now) {
			continue
		}
		if photo.UserID != viewerID {
			var bookmarkCount int64
			if err := s.DB.Model(&models.PhotoBookmark{}).
				Where("user_id = ? AND photo_id = ? AND active = ?", viewerID, photo.ID, true).
				Count(&bookmarkCount).Error; err != nil {
				return nil, err
			}
			if bookmarkCount == 0 {
				continue
			}
		}
		title := "NSFW-Hinweis gesetzt"
		eventType := "bookmarked_photo_nsfw_marked"
		if !photo.Nsfw {
			title = "NSFW-Hinweis entfernt"
			eventType = "bookmarked_photo_nsfw_unmarked"
		}
		if photo.NsfwMarkedAt == nil {
			continue
		}
		rows = append(rows, s.hubTimelinePhotoItem(
			viewerID,
			photo,
			photo.NsfwMarkedAt.UTC(),
			gin.H{
				"id":         "nsfw-" + strconv.FormatUint(uint64(photo.ID), 10),
				"type":       eventType,
				"group":      "change",
				"system":     false,
				"accent":     "change",
				"title":      title,
				"body":       "@" + photo.User.Username + " · " + photo.Day,
				"occurredAt": photo.NsfwMarkedAt,
				"day":        photo.Day,
				"target":     s.hubTargetJSON(hubTargetRef{Day: photo.Day, PhotoID: photo.ID}),
				"actor":      s.userPublicJSON(viewerID, photo.User),
			},
		))
	}
	return rows, nil
}

func (s *Server) hubSpecialMomentItems(viewerID uint, from time.Time) ([]hubTimelineItem, error) {
	var requests []models.SpecialMomentRequest
	if err := s.DB.Preload("User").
		Where("requested_at >= ?", from.UTC()).
		Order("requested_at desc, id desc").
		Limit(24).
		Find(&requests).Error; err != nil {
		return nil, err
	}
	rows := make([]hubTimelineItem, 0, len(requests))
	for _, request := range requests {
		title := "Sondermoment angefragt"
		if request.UserID == viewerID {
			title = "Dein Sondermoment"
		}
		rows = append(rows, hubTimelineItem{
			SortAt: request.RequestedAt.UTC(),
			Data: gin.H{
				"id":         "special-" + strconv.FormatUint(uint64(request.ID), 10),
				"type":       "special_moment",
				"group":      "system",
				"system":     false,
				"accent":     "special",
				"title":      title,
				"body":       "@" + request.User.Username,
				"occurredAt": request.RequestedAt,
				"actor":      s.userPublicJSON(viewerID, request.User),
			},
		})
	}
	return rows, nil
}

func (s *Server) hubInviteItems(viewerID uint, from time.Time) ([]hubTimelineItem, error) {
	var users []models.User
	if err := s.DB.Where("created_at >= ?", from.UTC()).
		Order("created_at desc, id desc").
		Limit(24).
		Find(&users).Error; err != nil {
		return nil, err
	}
	rows := make([]hubTimelineItem, 0, len(users))
	for _, user := range users {
		rows = append(rows, hubTimelineItem{
			SortAt: user.CreatedAt.UTC(),
			Data: gin.H{
				"id":         "invite-" + strconv.FormatUint(uint64(user.ID), 10),
				"type":       "invite_registration",
				"group":      "community",
				"system":     false,
				"accent":     "invite",
				"title":      "Neues Mitglied",
				"body":       "@" + user.Username,
				"occurredAt": user.CreatedAt,
				"actor":      s.userPublicJSON(viewerID, user),
			},
		})
	}
	return rows, nil
}

func (s *Server) hubTimeCapsuleUnlockItems(viewerID uint, now, from time.Time) ([]hubTimelineItem, error) {
	var photos []models.Photo
	if err := s.DB.Preload("User").
		Where("capsule_visible_at IS NOT NULL").
		Where("capsule_visible_at >= ? AND capsule_visible_at <= ?", from.UTC(), now.UTC()).
		Order("capsule_visible_at desc, id desc").
		Limit(40).
		Find(&photos).Error; err != nil {
		return nil, err
	}
	return s.hubPhotoRowsFromPhotos(viewerID, now, photos, func(photo models.Photo) gin.H {
		label := "Timecapsule freigeschaltet"
		if photo.UserID == viewerID {
			label = "Deine Timecapsule ist offen"
		}
		return gin.H{
			"id":         "capsule-unlock-" + strconv.FormatUint(uint64(photo.ID), 10),
			"type":       "timecapsule_unlocked",
			"group":      "capsule",
			"system":     false,
			"accent":     "capsule",
			"title":      label,
			"body":       "@" + photo.User.Username + " · " + photo.Day,
			"occurredAt": photo.CapsuleVisibleAt,
			"day":        photo.Day,
			"target":     s.hubTargetJSON(hubTargetRef{Day: photo.Day, PhotoID: photo.ID}),
			"actor":      s.userPublicJSON(viewerID, photo.User),
			"celebrate":  true,
		}
	})
}

func (s *Server) hubSystemEventItems(from time.Time) ([]hubTimelineItem, error) {
	var events []models.HubSystemEvent
	if err := s.DB.Where("scope = ? AND occurred_at >= ?", hubTimelineSystemEventScope, from.UTC()).
		Order("occurred_at desc, id desc").
		Limit(24).
		Find(&events).Error; err != nil {
		return nil, err
	}
	rows := make([]hubTimelineItem, 0, len(events))
	for _, event := range events {
		row := gin.H{
			"id":         "system-" + strconv.FormatUint(uint64(event.ID), 10),
			"type":       event.EventType,
			"group":      "system",
			"system":     true,
			"accent":     "system",
			"title":      event.Title,
			"body":       event.Body,
			"occurredAt": event.OccurredAt,
			"targetUrl":  strings.TrimSpace(event.TargetURL),
		}
		if strings.TrimSpace(event.MetaJSON) != "" {
			var meta map[string]any
			if err := json.Unmarshal([]byte(event.MetaJSON), &meta); err == nil && len(meta) > 0 {
				row["meta"] = meta
			}
		}
		rows = append(rows, hubTimelineItem{SortAt: event.OccurredAt.UTC(), Data: row})
	}
	return rows, nil
}

func (s *Server) hubTimeCapsulesPayload(viewerID uint, now time.Time) (gin.H, error) {
	var photos []models.Photo
	if err := s.DB.Preload("User").
		Where("capsule_visible_at IS NOT NULL").
		Order("capsule_visible_at asc, created_at asc").
		Limit(120).
		Find(&photos).Error; err != nil {
		return nil, err
	}
	photoIDs := make([]uint, 0, len(photos))
	for _, photo := range photos {
		photoIDs = append(photoIDs, photo.ID)
	}
	decorations, err := s.photoDecorationsForViewer(viewerID, photoIDs)
	if err != nil {
		return nil, err
	}
	locked := make([]gin.H, 0, len(photos))
	released := make([]gin.H, 0, len(photos))
	for _, photo := range photos {
		if photo.CapsuleVisibleAt == nil {
			continue
		}
		preview := s.timeCapsulePhotoJSONForViewer(viewerID, photo, decorations, now)
		item := gin.H{
			"photo":         preview,
			"user":          s.userPublicJSON(viewerID, photo.User),
			"day":           photo.Day,
			"photoId":       photo.ID,
			"visibleAt":     photo.CapsuleVisibleAt,
			"countdownSecs": int64(photo.CapsuleVisibleAt.Sub(now).Seconds()),
			"target":        s.hubTargetJSON(hubTargetRef{Day: photo.Day, PhotoID: photo.ID}),
		}
		if now.Before(*photo.CapsuleVisibleAt) {
			locked = append(locked, item)
			continue
		}
		if photoVisibleToViewer(viewerID, photo, now) {
			released = append(released, item)
		}
	}
	sort.SliceStable(locked, func(i, j int) bool {
		left := locked[i]["visibleAt"].(*time.Time)
		right := locked[j]["visibleAt"].(*time.Time)
		return left.Before(*right)
	})
	sort.SliceStable(released, func(i, j int) bool {
		left := released[i]["visibleAt"].(*time.Time)
		right := released[j]["visibleAt"].(*time.Time)
		return left.After(*right)
	})
	return gin.H{
		"schemaVersion": "hub_time_capsules_v1",
		"serverNow":     now,
		"lockedCount":   len(locked),
		"releasedCount": len(released),
		"locked":        locked,
		"released":      released,
	}, nil
}

func (s *Server) hubTargetJSON(target hubTargetRef) gin.H {
	return gin.H{
		"tab":           "feed",
		"day":           target.Day,
		"photoId":       target.PhotoID,
		"commentId":     target.CommentID,
		"photoMojiId":   target.PhotoMojiID,
		"reactionEmoji": target.ReactionEmoji,
	}
}

func (s *Server) hubPhotoRowsFromPhotos(viewerID uint, now time.Time, photos []models.Photo, build func(photo models.Photo) gin.H) ([]hubTimelineItem, error) {
	photoIDs := make([]uint, 0, len(photos))
	for _, photo := range photos {
		photoIDs = append(photoIDs, photo.ID)
	}
	decorations, err := s.photoDecorationsForViewer(viewerID, photoIDs)
	if err != nil {
		return nil, err
	}
	rows := make([]hubTimelineItem, 0, len(photos))
	for _, photo := range photos {
		if !photoVisibleToViewer(viewerID, photo, now) {
			continue
		}
		rows = append(rows, s.hubTimelinePhotoItem(viewerID, photo, photo.CreatedAt, build(photo)).withDecorations(s, viewerID, photo, decorations))
	}
	return rows, nil
}

func (item hubTimelineItem) withDecorations(s *Server, viewerID uint, photo models.Photo, decorations *viewerPhotoDecorations) hubTimelineItem {
	item.Data["photo"] = s.photoJSONForViewer(viewerID, photo, decorations)
	item.Data["photoUser"] = s.userPublicJSON(viewerID, photo.User)
	return item
}

func (s *Server) hubTimelinePhotoItem(viewerID uint, photo models.Photo, sortAt time.Time, data gin.H) hubTimelineItem {
	return hubTimelineItem{
		SortAt: sortAt.UTC(),
		Data:   data,
	}
}

func (s *Server) hubPhotosByID(photoIDs []uint) (map[uint]models.Photo, error) {
	if len(photoIDs) == 0 {
		return map[uint]models.Photo{}, nil
	}
	var photos []models.Photo
	if err := s.DB.Preload("User").Where("id IN ?", photoIDs).Find(&photos).Error; err != nil {
		return nil, err
	}
	out := make(map[uint]models.Photo, len(photos))
	for _, photo := range photos {
		out[photo.ID] = photo
	}
	return out, nil
}

func (s *Server) hubUsersByID(userIDs []uint) (map[uint]models.User, error) {
	if len(userIDs) == 0 {
		return map[uint]models.User{}, nil
	}
	var users []models.User
	if err := s.DB.Where("id IN ?", userIDs).Find(&users).Error; err != nil {
		return nil, err
	}
	out := make(map[uint]models.User, len(users))
	for _, user := range users {
		out[user.ID] = user
	}
	return out, nil
}
