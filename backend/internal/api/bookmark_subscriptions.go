package api

import (
	"context"
	"errors"
	"log"
	"time"

	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
)

const (
	photoBookmarkSourceManual          = "manual"
	photoBookmarkSourceAutoInteraction = "auto_interaction"
	autoInteractionBookmarkTTL         = 48 * time.Hour
)

func (s *Server) setManualPhotoBookmark(userID, photoID uint, now time.Time) error {
	var bookmark models.PhotoBookmark
	err := s.DB.Where("user_id = ? AND photo_id = ?", userID, photoID).First(&bookmark).Error
	switch {
	case errors.Is(err, gorm.ErrRecordNotFound):
		row := models.PhotoBookmark{
			UserID:             userID,
			PhotoID:            photoID,
			Active:             true,
			SubscriptionSource: photoBookmarkSourceManual,
			CreatedAt:          now,
		}
		return s.DB.Create(&row).Error
	case err != nil:
		return err
	case bookmark.Active && bookmark.SubscriptionSource == photoBookmarkSourceManual:
		return nil
	default:
		updates := map[string]any{
			"active":                   true,
			"subscription_source":      photoBookmarkSourceManual,
			"last_activity_at":         nil,
			"auto_expires_at":          nil,
			"auto_resubscribe_blocked": false,
			"created_at":               now,
		}
		return s.DB.Model(&models.PhotoBookmark{}).Where("id = ?", bookmark.ID).Updates(updates).Error
	}
}

func (s *Server) removePhotoBookmark(userID, photoID uint, now time.Time) error {
	var bookmark models.PhotoBookmark
	if err := s.DB.Where("user_id = ? AND photo_id = ?", userID, photoID).First(&bookmark).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil
		}
		return err
	}
	if bookmark.SubscriptionSource == photoBookmarkSourceAutoInteraction && bookmark.Active {
		return s.DB.Model(&models.PhotoBookmark{}).Where("id = ?", bookmark.ID).Updates(map[string]any{
			"active":                   false,
			"last_activity_at":         nil,
			"auto_expires_at":          nil,
			"auto_resubscribe_blocked": true,
		}).Error
	}
	return s.DB.Delete(&bookmark).Error
}

func (s *Server) handlePhotoInteractionSubscription(photo models.Photo, actorID uint, interactionType string, now time.Time) error {
	if actorID == 0 || photo.ID == 0 {
		return nil
	}
	if actorID != photo.UserID {
		var pref struct {
			AutoSubscribeInteractedPostsEnabled bool
		}
		if err := s.DB.Model(&models.User{}).
			Select("auto_subscribe_interacted_posts_enabled").
			Where("id = ?", actorID).
			Take(&pref).Error; err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
			return err
		}
		if pref.AutoSubscribeInteractedPostsEnabled {
			if err := s.upsertAutoInteractionBookmark(actorID, photo.ID, interactionType, now); err != nil {
				return err
			}
		}
	}
	return s.refreshAutoInteractionBookmarksForPhoto(photo.ID, now)
}

func (s *Server) upsertAutoInteractionBookmark(userID, photoID uint, interactionType string, now time.Time) error {
	_ = interactionType
	autoExpiresAt := now.Add(autoInteractionBookmarkTTL)
	var bookmark models.PhotoBookmark
	err := s.DB.Where("user_id = ? AND photo_id = ?", userID, photoID).First(&bookmark).Error
	switch {
	case errors.Is(err, gorm.ErrRecordNotFound):
		row := models.PhotoBookmark{
			UserID:                 userID,
			PhotoID:                photoID,
			Active:                 true,
			SubscriptionSource:     photoBookmarkSourceAutoInteraction,
			LastActivityAt:         &now,
			AutoExpiresAt:          &autoExpiresAt,
			AutoResubscribeBlocked: false,
			CreatedAt:              now,
		}
		return s.DB.Create(&row).Error
	case err != nil:
		return err
	case bookmark.Active && bookmark.SubscriptionSource == photoBookmarkSourceManual:
		return nil
	default:
		updates := map[string]any{
			"active":                   true,
			"subscription_source":      photoBookmarkSourceAutoInteraction,
			"last_activity_at":         &now,
			"auto_expires_at":          &autoExpiresAt,
			"auto_resubscribe_blocked": false,
		}
		if !bookmark.Active {
			updates["created_at"] = now
		}
		return s.DB.Model(&models.PhotoBookmark{}).Where("id = ?", bookmark.ID).Updates(updates).Error
	}
}

func (s *Server) refreshAutoInteractionBookmarksForPhoto(photoID uint, now time.Time) error {
	autoExpiresAt := now.Add(autoInteractionBookmarkTTL)
	return s.DB.Model(&models.PhotoBookmark{}).
		Where("photo_id = ? AND active = ? AND subscription_source = ?", photoID, true, photoBookmarkSourceAutoInteraction).
		Updates(map[string]any{
			"last_activity_at": &now,
			"auto_expires_at":  &autoExpiresAt,
		}).Error
}

func (s *Server) pruneExpiredAutoInteractionBookmarks(now time.Time) (int64, error) {
	result := s.DB.Where("active = ? AND subscription_source = ? AND auto_expires_at IS NOT NULL AND auto_expires_at <= ?",
		true,
		photoBookmarkSourceAutoInteraction,
		now,
	).Delete(&models.PhotoBookmark{})
	return result.RowsAffected, result.Error
}

func (s *Server) RunAutoBookmarkCleanupLoop(ctx context.Context, interval time.Duration) {
	if interval <= 0 {
		interval = 30 * time.Minute
	}
	runOnce := func() {
		removed, err := s.pruneExpiredAutoInteractionBookmarks(time.Now().UTC())
		if err != nil {
			log.Printf("auto bookmark cleanup failed: %v", err)
			return
		}
		if removed > 0 {
			log.Printf("auto bookmark cleanup removed %d expired subscriptions", removed)
		}
	}
	runOnce()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			runOnce()
		}
	}
}
