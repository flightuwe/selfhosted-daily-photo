package api

import (
	"errors"
	"fmt"
	"strconv"
	"strings"

	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
)

const timelineRevisionScope = "timeline"

func feedRevisionScope(day string) string {
	return "feed:" + strings.TrimSpace(day)
}

func (s *Server) syncRevision(scope string) int64 {
	if s == nil || s.DB == nil || strings.TrimSpace(scope) == "" {
		return 1
	}
	var row models.SyncRevision
	err := s.DB.Where("scope = ?", scope).First(&row).Error
	if err == nil {
		if row.Revision < 1 {
			return 1
		}
		return row.Revision
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return 1
	}
	row = models.SyncRevision{Scope: scope, Revision: 1}
	if createErr := s.DB.Create(&row).Error; createErr != nil {
		// A concurrent creator may have won the unique-key race.
		if retryErr := s.DB.Where("scope = ?", scope).First(&row).Error; retryErr == nil && row.Revision > 0 {
			return row.Revision
		}
		return 1
	}
	return row.Revision
}

func (s *Server) bumpSyncRevision(scope string) int64 {
	if s == nil || s.DB == nil || strings.TrimSpace(scope) == "" {
		return 1
	}
	returnRevision := int64(1)
	_ = s.DB.Transaction(func(tx *gorm.DB) error {
		var row models.SyncRevision
		err := tx.Where("scope = ?", scope).First(&row).Error
		if errors.Is(err, gorm.ErrRecordNotFound) {
			row = models.SyncRevision{Scope: scope, Revision: 2}
			if err := tx.Create(&row).Error; err != nil {
				return err
			}
			returnRevision = row.Revision
			return nil
		}
		if err != nil {
			return err
		}
		row.Revision++
		if row.Revision < 2 {
			row.Revision = 2
		}
		if err := tx.Model(&row).Update("revision", row.Revision).Error; err != nil {
			return err
		}
		returnRevision = row.Revision
		return nil
	})
	return returnRevision
}

func revisionETag(prefix string, revisions map[string]int64) string {
	parts := make([]string, 0, len(revisions))
	for key, revision := range revisions {
		parts = append(parts, key+"="+strconv.FormatInt(revision, 10))
	}
	// The caller inserts feed days in response order. Sorting makes the wire
	// validator deterministic even when the map iteration order changes.
	sortStrings(parts)
	return fmt.Sprintf("W/\"%s:%s\"", prefix, strings.Join(parts, ","))
}

func sortStrings(values []string) {
	for i := 1; i < len(values); i++ {
		for j := i; j > 0 && values[j] < values[j-1]; j-- {
			values[j], values[j-1] = values[j-1], values[j]
		}
	}
}
