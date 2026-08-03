package api

import (
	"fmt"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

type calendarCountRow struct {
	PhotoID uint  `gorm:"column:photo_id"`
	Count   int64 `gorm:"column:count"`
}

// calendarPublicCompactPayload deliberately avoids feedInteractionPreview and
// photoDecorationsForViewer. Those paths hydrate comments, reactions, marks and
// paint paths and were the dominant source of calendar response size and CPU.
func (s *Server) calendarPublicCompactPayload(viewerID uint, now time.Time) (gin.H, error) {
	visibleDays := s.DB.Model(&models.Photo{}).
		Select("day").
		Where("user_id = ? OR capsule_visible_at IS NULL OR capsule_visible_at <= ?", viewerID, now).
		Group("day").
		Order("day desc").
		Limit(365)

	var photos []models.Photo
	if err := s.DB.Preload("User").
		Where("photos.day IN (?)", visibleDays).
		Where("photos.user_id = ? OR photos.capsule_visible_at IS NULL OR photos.capsule_visible_at <= ?", viewerID, now).
		Order("photos.day desc, photos.created_at desc, photos.id desc").
		Find(&photos).Error; err != nil {
		return nil, err
	}
	sortPhotosForFeed(photos)

	days := make([]string, 0, 365)
	daySeen := make(map[string]struct{}, 365)
	photoIDs := make([]uint, 0, len(photos))
	for _, photo := range photos {
		photoIDs = append(photoIDs, photo.ID)
		if _, exists := daySeen[photo.Day]; !exists {
			daySeen[photo.Day] = struct{}{}
			days = append(days, photo.Day)
		}
	}

	reactionCounts, err := s.calendarAggregateCounts("photo_reactions", photoIDs)
	if err != nil {
		return nil, err
	}
	commentCounts, err := s.calendarAggregateCounts("photo_comments", photoIDs)
	if err != nil {
		return nil, err
	}
	bookmarkCounts, err := s.calendarActiveBookmarkCounts(photoIDs)
	if err != nil {
		return nil, err
	}
	bookmarkedByMe, err := s.calendarViewerBookmarks(viewerID, photoIDs)
	if err != nil {
		return nil, err
	}
	attachments := s.photoAttachmentsByPhotoIDs(photoIDs)

	photosByDay := make(map[string][]gin.H, len(days))
	postCountByDay := make(map[string]int64, len(days))
	participantsByDay := make(map[string]map[uint]struct{}, len(days))
	bestByDay := make(map[string]models.Photo, len(days))
	bestScoreByDay := make(map[string]int64, len(days))
	for _, photo := range photos {
		row := s.calendarCompactPhotoJSON(photo, attachments[photo.ID])
		row["bookmarkedByMe"] = bookmarkedByMe[photo.ID]
		row["bookmarkCount"] = bookmarkCounts[photo.ID]
		photosByDay[photo.Day] = append(photosByDay[photo.Day], gin.H{
			"photo": row,
			"user":  s.calendarCompactUserJSON(viewerID, photo.User),
		})
		postCountByDay[photo.Day]++
		if participantsByDay[photo.Day] == nil {
			participantsByDay[photo.Day] = map[uint]struct{}{}
		}
		participantsByDay[photo.Day][photo.UserID] = struct{}{}
		score := reactionCounts[photo.ID] + commentCounts[photo.ID]
		best, exists := bestByDay[photo.Day]
		if !exists || score > bestScoreByDay[photo.Day] ||
			(score == bestScoreByDay[photo.Day] && photoEffectiveTime(photo).After(photoEffectiveTime(best))) {
			bestByDay[photo.Day] = photo
			bestScoreByDay[photo.Day] = score
		}
	}

	stats := make([]gin.H, 0, len(days))
	for _, day := range days {
		stat := gin.H{
			"day":              day,
			"count":            postCountByDay[day],
			"postCount":        postCountByDay[day],
			"participantCount": int64(len(participantsByDay[day])),
			"featuredPhoto":    nil,
		}
		if featured, ok := bestByDay[day]; ok {
			featuredRow := gin.H{
				"photoId":          featured.ID,
				"url":              fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, featured.FilePath),
				"thumbnailUrl":     s.photoThumbnailURL(featured.FilePath),
				"secondUrl":        "",
				"user":             s.calendarCompactUserJSON(viewerID, featured.User),
				"reactionCount":    reactionCounts[featured.ID],
				"commentCount":     commentCounts[featured.ID],
				"interactionCount": reactionCounts[featured.ID] + commentCounts[featured.ID],
				"bookmarkedByMe":   bookmarkedByMe[featured.ID],
				"bookmarkCount":    bookmarkCounts[featured.ID],
				"publicNumber":     photoPublicNumberValue(featured),
			}
			if strings.TrimSpace(featured.SecondPath) != "" {
				featuredRow["secondUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, featured.SecondPath)
				featuredRow["secondThumbnailUrl"] = s.photoThumbnailURL(featured.SecondPath)
			}
			stat["featuredPhoto"] = featuredRow
		}
		stats = append(stats, stat)
	}
	users := make([]gin.H, 0)
	seenUsers := map[uint]struct{}{}
	for _, photo := range photos {
		if _, exists := seenUsers[photo.UserID]; exists {
			continue
		}
		seenUsers[photo.UserID] = struct{}{}
		users = append(users, s.calendarCompactUserJSON(viewerID, photo.User))
	}
	return gin.H{
		"days":        days,
		"dayStats":    stats,
		"photosByDay": photosByDay,
		"users":       users,
		"items":       []gin.H{},
	}, nil
}

func (s *Server) calendarCompactPhotoJSON(photo models.Photo, attachments []models.PhotoAttachment) gin.H {
	media := make([]gin.H, 0, 2+len(attachments))
	addMedia := func(id, path, sourceKind string, capturedAt *time.Time) {
		if strings.TrimSpace(path) == "" {
			return
		}
		media = append(media, gin.H{
			"id": id, "url": fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, path),
			"thumbnailUrl": s.photoThumbnailURL(path), "capturedAt": capturedAt, "sourceKind": sourceKind,
		})
	}
	addMedia(fmt.Sprintf("photo-%d-primary", photo.ID), photo.FilePath, "primary", photo.CapturedAt)
	addMedia(fmt.Sprintf("photo-%d-secondary", photo.ID), photo.SecondPath, "secondary", photo.CapturedAt)
	for _, attachment := range attachments {
		addMedia(fmt.Sprintf("attachment-%d", attachment.ID), attachment.FilePath, "attachment", attachment.CapturedAt)
	}
	row := gin.H{
		"id": photo.ID, "day": photo.Day, "caption": photo.Caption,
		"url":          fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, photo.FilePath),
		"thumbnailUrl": s.photoThumbnailURL(photo.FilePath), "createdAt": photo.CreatedAt, "capturedAt": photo.CapturedAt,
		"mediaCount": len(media), "publicNumber": photoPublicNumberValue(photo),
		"capsuleVisibleAt": photo.CapsuleVisibleAt, "nsfw": photo.Nsfw,
	}
	if len(media) > 1 {
		row["media"] = media
	}
	if strings.TrimSpace(photo.SecondPath) != "" {
		row["secondUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, photo.SecondPath)
		row["secondThumbnailUrl"] = s.photoThumbnailURL(photo.SecondPath)
	}
	return row
}

func (s *Server) calendarCompactUserJSON(viewerID uint, user models.User) gin.H {
	row := gin.H{
		"id": user.ID, "username": user.Username, "favoriteColor": user.FavoriteColor,
		"avatarUrl": "", "profileVisible": user.ProfileVisible, "avatarVisible": false,
	}
	if (viewerID == user.ID || (user.ProfileVisible && user.AvatarVisible)) && strings.TrimSpace(user.AvatarPath) != "" {
		row["avatarVisible"] = true
		row["avatarUrl"] = s.avatarURL(user.AvatarPath)
	}
	return row
}

func (s *Server) calendarAggregateCounts(table string, photoIDs []uint) (map[uint]int64, error) {
	out := make(map[uint]int64, len(photoIDs))
	if len(photoIDs) == 0 {
		return out, nil
	}
	if table != "photo_reactions" && table != "photo_comments" {
		return out, fmt.Errorf("unsupported aggregate table")
	}
	var rows []calendarCountRow
	if err := s.DB.Table(table).Select("photo_id, COUNT(*) AS count").
		Where("photo_id IN ?", photoIDs).Group("photo_id").Scan(&rows).Error; err != nil {
		return nil, err
	}
	for _, row := range rows {
		out[row.PhotoID] = row.Count
	}
	return out, nil
}

func (s *Server) calendarActiveBookmarkCounts(photoIDs []uint) (map[uint]int64, error) {
	out := make(map[uint]int64, len(photoIDs))
	if len(photoIDs) == 0 {
		return out, nil
	}
	var rows []calendarCountRow
	if err := s.DB.Model(&models.PhotoBookmark{}).Select("photo_id, COUNT(*) AS count").
		Where("photo_id IN ? AND active = ?", photoIDs, true).Group("photo_id").Scan(&rows).Error; err != nil {
		return nil, err
	}
	for _, row := range rows {
		out[row.PhotoID] = row.Count
	}
	return out, nil
}

func (s *Server) calendarViewerBookmarks(viewerID uint, photoIDs []uint) (map[uint]bool, error) {
	out := make(map[uint]bool, len(photoIDs))
	if viewerID == 0 || len(photoIDs) == 0 {
		return out, nil
	}
	var rows []models.PhotoBookmark
	if err := s.DB.Select("photo_id").Where("user_id = ? AND photo_id IN ? AND active = ?", viewerID, photoIDs, true).
		Find(&rows).Error; err != nil {
		return nil, err
	}
	for _, row := range rows {
		out[row.PhotoID] = true
	}
	return out, nil
}
