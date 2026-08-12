package api

import (
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

const distributionRolloutSingletonID uint = 1

var errDistributionRolloutRevisionConflict = errors.New("distribution rollout revision conflict")

type distributionClientVersion struct {
	Name string
	Code int64
}

type distributionRolloutRequest struct {
	Enabled            bool  `json:"enabled"`
	MigrationProfileID uint  `json:"migrationProfileId"`
	StableProfileID    uint  `json:"stableProfileId"`
	EntryVersionCode   int64 `json:"entryVersionCode"`
	StableVersionCode  int64 `json:"stableVersionCode"`
	ExpectedRevision   int64 `json:"expectedRevision"`
}

func distributionClientVersionFromQuery(c *gin.Context) (*distributionClientVersion, error) {
	rawCode, codePresent := c.GetQuery("versionCode")
	rawName, namePresent := c.GetQuery("versionName")
	if !codePresent && !namePresent {
		return nil, nil
	}
	if !codePresent || strings.TrimSpace(rawCode) == "" {
		return nil, errors.New("versionCode is required when reporting an app version")
	}
	code, err := strconv.ParseInt(strings.TrimSpace(rawCode), 10, 64)
	if err != nil || code <= 0 || code > 2_000_000_000 {
		return nil, errors.New("versionCode must be between 1 and 2000000000")
	}
	name := strings.TrimSpace(rawName)
	if len(name) > 80 || strings.IndexFunc(name, unicode.IsControl) >= 0 {
		return nil, errors.New("versionName is invalid")
	}
	return &distributionClientVersion{Name: name, Code: code}, nil
}

func validateDistributionRollout(tx *gorm.DB, rollout models.DistributionRollout) (models.DistributionProfile, models.DistributionProfile, error) {
	if !rollout.Enabled {
		return models.DistributionProfile{}, models.DistributionProfile{}, nil
	}
	if rollout.MigrationProfileID == 0 || rollout.StableProfileID == 0 || rollout.MigrationProfileID == rollout.StableProfileID {
		return models.DistributionProfile{}, models.DistributionProfile{}, errors.New("migration and stable profiles must be distinct")
	}
	if rollout.EntryVersionCode <= 0 || rollout.StableVersionCode <= rollout.EntryVersionCode {
		return models.DistributionProfile{}, models.DistributionProfile{}, errors.New("stableVersionCode must be greater than entryVersionCode")
	}
	var migration, stable models.DistributionProfile
	if err := tx.First(&migration, rollout.MigrationProfileID).Error; err != nil {
		return migration, stable, fmt.Errorf("migration profile: %w", err)
	}
	if err := tx.First(&stable, rollout.StableProfileID).Error; err != nil {
		return migration, stable, fmt.Errorf("stable profile: %w", err)
	}
	if !migration.Enabled || migration.SourceMode == "disabled" {
		return migration, stable, errors.New("migration profile must be enabled")
	}
	if !stable.Enabled || stable.SourceMode == "disabled" || !stable.IsDefault {
		return migration, stable, errors.New("stable profile must be the enabled default")
	}
	return migration, stable, nil
}

func sameDistributionProfileID(current *uint, wanted *uint) bool {
	if current == nil || wanted == nil {
		return current == nil && wanted == nil
	}
	return *current == *wanted
}

func (s *Server) applyDistributionRollout(user models.User, report distributionClientVersion) (models.User, error) {
	s.distributionWriteMu.Lock()
	defer s.distributionWriteMu.Unlock()

	err := s.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.First(&user, user.ID).Error; err != nil {
			return err
		}
		var rollout models.DistributionRollout
		if err := tx.First(&rollout, distributionRolloutSingletonID).Error; err != nil {
			return err
		}
		var migration models.DistributionProfile
		if rollout.Enabled {
			var err error
			migration, _, err = validateDistributionRollout(tx, rollout)
			if err != nil {
				return fmt.Errorf("invalid distribution rollout: %w", err)
			}
		}

		phase := "observed"
		var wanted *uint
		changeAssignment := false
		action := ""
		if rollout.Enabled {
			switch {
			case report.Code < rollout.EntryVersionCode:
				phase = "below_entry"
			case report.Code >= rollout.StableVersionCode:
				phase = "stable"
				if user.DistributionProfileID != nil && *user.DistributionProfileID == rollout.MigrationProfileID {
					changeAssignment = true
					action = "user_assignment_auto_stable"
				} else if user.DistributionProfileID != nil {
					phase = "manual_override"
				}
			default:
				phase = "migration"
				migrationID := migration.ID
				wanted = &migrationID
				if user.DistributionProfileID == nil {
					changeAssignment = true
					action = "user_assignment_auto_migration"
				} else if *user.DistributionProfileID != migration.ID {
					phase = "manual_override"
				}
			}
		}

		if changeAssignment && !sameDistributionProfileID(user.DistributionProfileID, wanted) {
			before := map[string]any{"distributionProfileId": user.DistributionProfileID, "versionName": report.Name, "versionCode": report.Code}
			after := map[string]any{"distributionProfileId": wanted, "versionName": report.Name, "versionCode": report.Code, "phase": phase}
			if err := tx.Model(&models.User{}).Where("id = ?", user.ID).Update("distribution_profile_id", wanted).Error; err != nil {
				return err
			}
			if err := appendDistributionAudit(tx, user, action, wanted, &user.ID, before, after, nil, ""); err != nil {
				return err
			}
			user.DistributionProfileID = wanted
		}

		now := time.Now().UTC()
		state := models.DistributionClientState{
			UserID: user.ID, VersionName: report.Name, VersionCode: report.Code,
			Phase: phase, LastSeenAt: now, CreatedAt: now, UpdatedAt: now,
		}
		return tx.Clauses(clause.OnConflict{
			Columns:   []clause.Column{{Name: "user_id"}},
			DoUpdates: clause.AssignmentColumns([]string{"version_name", "version_code", "phase", "last_seen_at", "updated_at"}),
		}).Create(&state).Error
	})
	return user, err
}

func (s *Server) handleAdminDistributionRollout(c *gin.Context) {
	var rollout models.DistributionRollout
	if err := s.DB.First(&rollout, distributionRolloutSingletonID).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "rollout query failed"})
		return
	}
	var totalUsers int64
	if err := s.DB.Model(&models.User{}).Count(&totalUsers).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "user count failed"})
		return
	}
	var states []models.DistributionClientState
	if err := s.DB.Order("last_seen_at desc, user_id asc").Find(&states).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "client state query failed"})
		return
	}
	summary := map[string]int64{"totalUsers": totalUsers, "unknown": totalUsers - int64(len(states))}
	for _, state := range states {
		summary[state.Phase]++
	}
	c.JSON(http.StatusOK, gin.H{"rollout": rollout, "summary": summary, "clients": states})
}

func (s *Server) handleAdminDistributionRolloutUpdate(c *gin.Context) {
	s.distributionWriteMu.Lock()
	defer s.distributionWriteMu.Unlock()

	actor, _ := userFromContext(c)
	var request distributionRolloutRequest
	if !bindDistributionJSON(c, &request) {
		return
	}
	var current models.DistributionRollout
	if err := s.DB.First(&current, distributionRolloutSingletonID).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "rollout query failed"})
		return
	}
	candidate := current
	candidate.Enabled = request.Enabled
	candidate.MigrationProfileID = request.MigrationProfileID
	candidate.StableProfileID = request.StableProfileID
	candidate.EntryVersionCode = request.EntryVersionCode
	candidate.StableVersionCode = request.StableVersionCode
	if !candidate.Enabled {
		candidate.MigrationProfileID = request.MigrationProfileID
		candidate.StableProfileID = request.StableProfileID
		candidate.EntryVersionCode = request.EntryVersionCode
		candidate.StableVersionCode = request.StableVersionCode
	}
	if _, _, err := validateDistributionRollout(s.DB, candidate); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error(), "errorClass": "invalid_rollout"})
		return
	}
	if request.ExpectedRevision != current.Revision {
		c.JSON(http.StatusConflict, gin.H{"error": "rollout revision conflict", "errorClass": "revision_conflict", "rollout": current})
		return
	}
	updates := map[string]any{
		"enabled": request.Enabled, "migration_profile_id": request.MigrationProfileID,
		"stable_profile_id": request.StableProfileID, "entry_version_code": request.EntryVersionCode,
		"stable_version_code": request.StableVersionCode, "revision": gorm.Expr("revision + 1"),
		"updated_by_user_id": actor.ID,
	}
	if err := s.DB.Transaction(func(tx *gorm.DB) error {
		result := tx.Model(&models.DistributionRollout{}).
			Where("id = ? AND revision = ?", distributionRolloutSingletonID, request.ExpectedRevision).
			Updates(updates)
		if result.Error != nil {
			return result.Error
		}
		if result.RowsAffected != 1 {
			return errDistributionRolloutRevisionConflict
		}
		return appendDistributionAudit(tx, actor, "rollout_config_changed", nil, nil, current, candidate, nil, "")
	}); err != nil {
		if errors.Is(err, errDistributionRolloutRevisionConflict) {
			_ = s.DB.First(&current, distributionRolloutSingletonID).Error
			c.JSON(http.StatusConflict, gin.H{"error": "rollout revision conflict", "errorClass": "revision_conflict", "rollout": current})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "rollout update failed"})
		return
	}
	if err := s.DB.First(&current, distributionRolloutSingletonID).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "rollout reload failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"rollout": current})
}
