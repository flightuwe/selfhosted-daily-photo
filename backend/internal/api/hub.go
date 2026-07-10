package api

import (
	"encoding/json"
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
	items, unreadCount, err := s.hubTimelinePayload(user, now, limit, viewedAt, clearedAt)
	if err != nil {
		c.JSON(500, gin.H{"error": "hub timeline failed"})
		return
	}
	_ = s.DB.Model(&models.User{}).Where("id = ?", user.ID).Update("hub_timeline_last_viewed_at", now.UTC()).Error
	c.JSON(200, gin.H{
		"schemaVersion": "hub_timeline_v1",
		"serverNow":     now,
		"windowDays":    hubTimelineWindowDays,
		"unreadCount":   unreadCount,
		"clearedAt":     clearedAt,
		"viewedAt":      viewedAt,
		"items":         items,
	})
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
		if photo.UserID != viewerID && item.UserID != viewerID {
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
		rows = append(rows, s.hubTimelinePhotoItem(
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
		))
	}
	return rows, nil
}

func (s *Server) hubReactionItems(viewerID uint, now, from time.Time) ([]hubTimelineItem, error) {
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
		if photo.UserID != viewerID && item.UserID != viewerID {
			continue
		}
		actor := usersByID[item.UserID]
		title := "Neue Reaktion"
		if item.UserID == viewerID {
			title = "Deine Reaktion"
		}
		rows = append(rows, s.hubTimelinePhotoItem(
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
		))
	}
	return rows, nil
}

func (s *Server) hubFotomojiItems(viewerID uint, now, from time.Time) ([]hubTimelineItem, error) {
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
		if photo.UserID != viewerID && item.UserID != viewerID {
			continue
		}
		title := "Neue FotoMoji"
		if item.UserID == viewerID {
			title = "Deine FotoMoji"
		}
		rows = append(rows, s.hubTimelinePhotoItem(
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
		))
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
	commentRows, err := s.hubCommentItems(viewerID, now, from)
	if err != nil {
		return nil, err
	}
	reactionRows, err := s.hubReactionItems(viewerID, now, from)
	if err != nil {
		return nil, err
	}
	fotomojiRows, err := s.hubFotomojiItems(viewerID, now, from)
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
		row.Data["group"] = "bookmarked"
		row.Data["accent"] = "bookmark"
		row.Data["bookmarkContext"] = true
		out = append(out, row)
	}
	return out, nil
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
		preview := s.photoJSONForViewer(viewerID, photo, decorations)
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
