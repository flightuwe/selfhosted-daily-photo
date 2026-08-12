package api

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	dailydb "github.com/yosho/selfhosted-bereal/backend/internal/db"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
)

type distributionProfileRequest struct {
	Name                      string `json:"name"`
	Enabled                   bool   `json:"enabled"`
	IsDefault                 bool   `json:"isDefault"`
	SourceMode                string `json:"sourceMode"`
	Channel                   string `json:"channel"`
	ProjectURL                string `json:"projectUrl"`
	ReleaseIndexURL           string `json:"releaseIndexUrl"`
	ReleaseHistoryURL         string `json:"releaseHistoryUrl"`
	ReleasePageURL            string `json:"releasePageUrl"`
	DirectAPKURL              string `json:"directApkUrl"`
	DirectAPKVersionName      string `json:"directApkVersionName"`
	DirectAPKVersionCode      int64  `json:"directApkVersionCode"`
	DirectAPKSHA256           string `json:"directApkSha256"`
	DirectAPKSizeBytes        *int64 `json:"directApkSizeBytes"`
	ExpectedPackageName       string `json:"expectedPackageName"`
	ExpectedSigningCertSHA256 string `json:"expectedSigningCertSha256"`
	MinSupportedVersionCode   *int64 `json:"minSupportedVersionCode"`
	AllowPrerelease           bool   `json:"allowPrerelease"`
	ExpectedRevision          int64  `json:"expectedRevision"`
}

type distributionProfileInsert struct {
	ID                        uint `gorm:"primaryKey"`
	Name                      string
	Enabled                   bool
	IsDefault                 bool
	SourceMode                string
	Channel                   string
	ProjectURL                string
	ReleaseIndexURL           string
	ReleaseHistoryURL         string
	ReleasePageURL            string
	DirectAPKURL              string
	DirectAPKVersionName      string
	DirectAPKVersionCode      int64
	DirectAPKSHA256           string
	DirectAPKSizeBytes        *int64
	ExpectedPackageName       string
	ExpectedSigningCertSHA256 string
	MinSupportedVersionCode   *int64
	AllowPrerelease           bool
	Revision                  int64
	CreatedByUserID           *uint
	CreatedAt                 time.Time
	UpdatedAt                 time.Time
}

func insertDistributionProfile(database *gorm.DB, profile *models.DistributionProfile) *gorm.DB {
	// Use an insert-only representation without model default tags. GORM otherwise
	// replaces false booleans with the schema defaults before binding the INSERT.
	row := distributionProfileInsert{
		Name: profile.Name, Enabled: profile.Enabled, IsDefault: profile.IsDefault,
		SourceMode: profile.SourceMode, Channel: profile.Channel, ProjectURL: profile.ProjectURL,
		ReleaseIndexURL: profile.ReleaseIndexURL, ReleaseHistoryURL: profile.ReleaseHistoryURL,
		ReleasePageURL: profile.ReleasePageURL, DirectAPKURL: profile.DirectAPKURL,
		DirectAPKVersionName: profile.DirectAPKVersionName, DirectAPKVersionCode: profile.DirectAPKVersionCode,
		DirectAPKSHA256: profile.DirectAPKSHA256, DirectAPKSizeBytes: profile.DirectAPKSizeBytes,
		ExpectedPackageName: profile.ExpectedPackageName, ExpectedSigningCertSHA256: profile.ExpectedSigningCertSHA256,
		MinSupportedVersionCode: profile.MinSupportedVersionCode, AllowPrerelease: profile.AllowPrerelease,
		Revision:        profile.Revision,
		CreatedByUserID: profile.CreatedByUserID, CreatedAt: profile.CreatedAt, UpdatedAt: profile.UpdatedAt,
	}
	result := database.Table("distribution_profiles").Create(&row)
	if result.Error == nil {
		profile.ID = row.ID
		profile.CreatedAt = row.CreatedAt
		profile.UpdatedAt = row.UpdatedAt
	}
	return result
}

const distributionAdminBodyLimit = 64 << 10

func bindDistributionJSON(c *gin.Context, target any) bool {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, distributionAdminBodyLimit)
	if err := c.ShouldBindJSON(target); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			c.JSON(http.StatusRequestEntityTooLarge, gin.H{"error": "payload too large", "errorClass": "payload_too_large"})
		} else {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		}
		return false
	}
	return true
}

func (request distributionProfileRequest) profile() models.DistributionProfile {
	return models.DistributionProfile{
		Name: request.Name, Enabled: request.Enabled, IsDefault: request.IsDefault,
		SourceMode: request.SourceMode, Channel: request.Channel, ProjectURL: request.ProjectURL,
		ReleaseIndexURL: request.ReleaseIndexURL, ReleaseHistoryURL: request.ReleaseHistoryURL,
		ReleasePageURL: request.ReleasePageURL, DirectAPKURL: request.DirectAPKURL,
		DirectAPKVersionName: request.DirectAPKVersionName, DirectAPKVersionCode: request.DirectAPKVersionCode,
		DirectAPKSHA256: request.DirectAPKSHA256, DirectAPKSizeBytes: request.DirectAPKSizeBytes,
		ExpectedPackageName: request.ExpectedPackageName, ExpectedSigningCertSHA256: request.ExpectedSigningCertSHA256,
		MinSupportedVersionCode: request.MinSupportedVersionCode, AllowPrerelease: request.AllowPrerelease,
		Revision: 1,
	}
}

func (s *Server) handleAppDistribution(c *gin.Context) {
	user, ok := userFromContext(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "missing user"})
		return
	}
	report, err := distributionClientVersionFromQuery(c)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error(), "errorClass": "invalid_client_version"})
		return
	}
	if report != nil {
		user, err = s.applyDistributionRollout(user, *report)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "distribution rollout failed"})
			return
		}
	}
	profile, err := s.effectiveDistributionProfile(user)
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.Header("Cache-Control", "private, max-age=300")
			c.JSON(http.StatusOK, gin.H{"schemaVersion": 1, "enabled": false})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "distribution profile lookup failed"})
		return
	}
	assignment := uint(0)
	if user.DistributionProfileID != nil {
		assignment = *user.DistributionProfileID
	}
	etagInput := fmt.Sprintf("%d:%d:%d:%d", user.ID, assignment, profile.ID, profile.Revision)
	etagDigest := sha256.Sum256([]byte(etagInput))
	etag := `"` + hex.EncodeToString(etagDigest[:16]) + `"`
	c.Header("Cache-Control", "private, max-age=300")
	c.Header("ETag", etag)
	if c.GetHeader("If-None-Match") == etag {
		c.Status(http.StatusNotModified)
		return
	}
	c.JSON(http.StatusOK, distributionClientResponse(profile))
}

func (s *Server) effectiveDistributionProfile(user models.User) (models.DistributionProfile, error) {
	var profile models.DistributionProfile
	if user.DistributionProfileID != nil {
		err := s.DB.First(&profile, *user.DistributionProfileID).Error
		return profile, err
	}
	err := s.DB.Where("is_default = ?", true).First(&profile).Error
	return profile, err
}

func distributionClientResponse(profile models.DistributionProfile) gin.H {
	response := gin.H{
		"schemaVersion": 1, "enabled": profile.Enabled && profile.SourceMode != "disabled",
		"profileId": profile.ID, "profileUpdatedAt": profile.UpdatedAt,
		"channel": profile.Channel, "projectUrl": profile.ProjectURL,
		"releaseIndexUrl": profile.ReleaseIndexURL, "releaseHistoryUrl": profile.ReleaseHistoryURL,
		"releasePageUrl": profile.ReleasePageURL, "directApk": nil,
		"expectedPackageName":       profile.ExpectedPackageName,
		"expectedSigningCertSha256": profile.ExpectedSigningCertSHA256,
		"minSupportedVersionCode":   profile.MinSupportedVersionCode,
		"allowPrerelease":           profile.AllowPrerelease,
	}
	if profile.SourceMode == "direct" && profile.Enabled {
		response["directApk"] = gin.H{
			"versionName": profile.DirectAPKVersionName, "versionCode": profile.DirectAPKVersionCode,
			"url": profile.DirectAPKURL, "sha256": profile.DirectAPKSHA256, "size": profile.DirectAPKSizeBytes,
		}
	}
	return response
}

func (s *Server) handleAdminDistributionProfiles(c *gin.Context) {
	var profiles []models.DistributionProfile
	if err := s.DB.Order("is_default desc, name asc, id asc").Find(&profiles).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	items := make([]gin.H, 0, len(profiles))
	for _, profile := range profiles {
		var assignedUsers int64
		_ = s.DB.Model(&models.User{}).Where("distribution_profile_id = ?", profile.ID).Count(&assignedUsers).Error
		items = append(items, gin.H{
			"profile": profile, "assignedUserCount": assignedUsers,
			"clientPreview": distributionClientResponse(profile),
		})
	}
	c.JSON(http.StatusOK, gin.H{
		"items": items,
		"deploymentPolicy": gin.H{
			"allowInsecureHttp":              s.Config.AllowInsecureDistributionURLs,
			"privateHostAllowlistConfigured": len(s.Config.DistributionPrivateHostAllowlist) > 0,
			"manifestMaxBytes":               s.Config.DistributionManifestMaxBytes,
			"apkMaxBytes":                    s.Config.DistributionAPKMaxBytes,
		},
	})
}

func (s *Server) handleAdminCreateDistributionProfile(c *gin.Context) {
	s.distributionWriteMu.Lock()
	defer s.distributionWriteMu.Unlock()

	actor, _ := userFromContext(c)
	var request distributionProfileRequest
	if !bindDistributionJSON(c, &request) {
		return
	}
	profile := request.profile()
	wantsDefault := profile.IsDefault
	profile.IsDefault = false
	profile.CreatedByUserID = &actor.ID
	if err := validateDistributionProfile(&profile, s.Config); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error(), "errorClass": distributionErrorClass(err)})
		return
	}
	var previousDefault models.DistributionProfile
	if err := s.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("is_default = ?", true).First(&previousDefault).Error; err != nil {
			return err
		}
		if err := insertDistributionProfile(tx, &profile).Error; err != nil {
			return err
		}
		if wantsDefault {
			if err := dailydb.SetDefaultDistributionProfile(tx, profile.ID, false); err != nil {
				return err
			}
			profile.IsDefault = true
			profile.Enabled = true
		}
		if err := appendDistributionAudit(tx, actor, "profile_created", &profile.ID, nil, nil, distributionProfileAuditSnapshot(profile), nil, ""); err != nil {
			return err
		}
		if wantsDefault && previousDefault.ID != profile.ID {
			return appendDistributionAudit(tx, actor, "default_changed", &profile.ID, nil, distributionProfileAuditSnapshot(previousDefault), distributionProfileAuditSnapshot(profile), nil, "")
		}
		return nil
	}); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "create failed"})
		return
	}
	_ = s.DB.First(&profile, profile.ID).Error
	c.JSON(http.StatusCreated, gin.H{"profile": profile, "clientPreview": distributionClientResponse(profile)})
}

func (s *Server) handleAdminUpdateDistributionProfile(c *gin.Context) {
	s.distributionWriteMu.Lock()
	defer s.distributionWriteMu.Unlock()

	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid profile id"})
		return
	}
	actor, _ := userFromContext(c)
	var before models.DistributionProfile
	if err := s.DB.First(&before, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "profile not found"})
		return
	}
	var request distributionProfileRequest
	if !bindDistributionJSON(c, &request) {
		return
	}
	if request.ExpectedRevision < 1 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "expectedRevision is required", "errorClass": "revision_required"})
		return
	}
	if before.Revision != request.ExpectedRevision {
		c.JSON(http.StatusConflict, gin.H{"error": "profile changed concurrently", "errorClass": "revision_conflict", "currentRevision": before.Revision, "currentProfile": before})
		return
	}
	candidate := request.profile()
	candidate.ID = before.ID
	candidate.CreatedAt = before.CreatedAt
	candidate.CreatedByUserID = before.CreatedByUserID
	if before.IsDefault && (!candidate.IsDefault || !candidate.Enabled || candidate.SourceMode == "disabled") {
		if err := appendDistributionAudit(s.DB, actor, "profile_update_attempt", &before.ID, nil, distributionProfileAuditSnapshot(before), distributionProfileAuditSnapshot(candidate), nil, "default_invariant"); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "audit failed"})
			return
		}
		c.JSON(http.StatusConflict, gin.H{"error": "default profile cannot be disabled or unset without a replacement", "errorClass": "default_invariant"})
		return
	}
	wantsDefault := candidate.IsDefault
	if !before.IsDefault {
		candidate.IsDefault = false
	}
	if err := validateDistributionProfile(&candidate, s.Config); err != nil {
		if auditErr := appendDistributionAudit(s.DB, actor, "profile_update_attempt", &before.ID, nil, distributionProfileAuditSnapshot(before), distributionProfileAuditSnapshot(candidate), nil, distributionErrorClass(err)); auditErr != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "audit failed"})
			return
		}
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error(), "errorClass": distributionErrorClass(err)})
		return
	}
	var conflict *models.DistributionProfile
	if err := s.DB.Transaction(func(tx *gorm.DB) error {
		var current models.DistributionProfile
		if err := tx.First(&current, before.ID).Error; err != nil {
			return err
		}
		if current.Revision != request.ExpectedRevision {
			conflict = &current
			return nil
		}
		before = current
		var previousDefault models.DistributionProfile
		if err := tx.Where("is_default = ?", true).First(&previousDefault).Error; err != nil {
			return err
		}
		updates := map[string]any{
			"Name": candidate.Name, "Enabled": candidate.Enabled, "SourceMode": candidate.SourceMode, "Channel": candidate.Channel,
			"ProjectURL": candidate.ProjectURL, "ReleaseIndexURL": candidate.ReleaseIndexURL,
			"ReleaseHistoryURL": candidate.ReleaseHistoryURL, "ReleasePageURL": candidate.ReleasePageURL,
			"DirectAPKURL": candidate.DirectAPKURL, "DirectAPKVersionName": candidate.DirectAPKVersionName,
			"DirectAPKVersionCode": candidate.DirectAPKVersionCode, "DirectAPKSHA256": candidate.DirectAPKSHA256,
			"DirectAPKSizeBytes": candidate.DirectAPKSizeBytes, "ExpectedPackageName": candidate.ExpectedPackageName,
			"ExpectedSigningCertSHA256": candidate.ExpectedSigningCertSHA256,
			"MinSupportedVersionCode":   candidate.MinSupportedVersionCode, "AllowPrerelease": candidate.AllowPrerelease,
		}
		updates["Revision"] = gorm.Expr("revision + 1")
		updated := tx.Model(&models.DistributionProfile{}).Where("id = ? AND revision = ?", before.ID, request.ExpectedRevision).Updates(updates)
		if updated.Error != nil {
			return updated.Error
		}
		if updated.RowsAffected != 1 {
			if err := tx.First(&current, before.ID).Error; err != nil {
				return err
			}
			conflict = &current
			return nil
		}
		if wantsDefault && !before.IsDefault {
			if err := dailydb.SetDefaultDistributionProfile(tx, before.ID, false); err != nil {
				return err
			}
		}
		if err := tx.First(&candidate, before.ID).Error; err != nil {
			return err
		}
		if err := appendDistributionAudit(tx, actor, "profile_updated", &candidate.ID, nil, distributionProfileAuditSnapshot(before), distributionProfileAuditSnapshot(candidate), nil, ""); err != nil {
			return err
		}
		if before.Enabled != candidate.Enabled {
			action := "profile_deactivated"
			if candidate.Enabled {
				action = "profile_activated"
			}
			if err := appendDistributionAudit(tx, actor, action, &candidate.ID, nil, distributionProfileAuditSnapshot(before), distributionProfileAuditSnapshot(candidate), nil, ""); err != nil {
				return err
			}
		}
		if wantsDefault && !before.IsDefault {
			return appendDistributionAudit(tx, actor, "default_changed", &candidate.ID, nil, distributionProfileAuditSnapshot(previousDefault), distributionProfileAuditSnapshot(candidate), nil, "")
		}
		return nil
	}); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
		return
	}
	if conflict != nil {
		c.JSON(http.StatusConflict, gin.H{"error": "profile changed concurrently", "errorClass": "revision_conflict", "currentRevision": conflict.Revision, "currentProfile": conflict})
		return
	}
	c.JSON(http.StatusOK, gin.H{"profile": candidate, "clientPreview": distributionClientResponse(candidate)})
}

func (s *Server) handleAdminDeleteDistributionProfile(c *gin.Context) {
	s.distributionWriteMu.Lock()
	defer s.distributionWriteMu.Unlock()

	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid profile id"})
		return
	}
	actor, _ := userFromContext(c)
	var profile models.DistributionProfile
	if err := s.DB.First(&profile, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "profile not found"})
		return
	}
	var assigned int64
	if err := s.DB.Model(&models.User{}).Where("distribution_profile_id = ?", id).Count(&assigned).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "assignment check failed"})
		return
	}
	var rolloutReferences int64
	if err := s.DB.Model(&models.DistributionRollout{}).
		Where("migration_profile_id = ? OR stable_profile_id = ?", id, id).Count(&rolloutReferences).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "rollout reference query failed"})
		return
	}
	if rolloutReferences > 0 {
		c.JSON(http.StatusConflict, gin.H{"error": "profile is referenced by rollout automation", "errorClass": "profile_rollout_referenced"})
		return
	}
	if profile.IsDefault || assigned > 0 {
		errorClass := "profile_assigned"
		if profile.IsDefault {
			errorClass = "default_invariant"
		}
		if err := appendDistributionAudit(s.DB, actor, "profile_delete_attempt", &profile.ID, nil, distributionProfileAuditSnapshot(profile), nil, nil, errorClass); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "audit failed"})
			return
		}
		c.JSON(http.StatusConflict, gin.H{"error": "default or assigned profile cannot be deleted", "errorClass": errorClass, "assignedUserCount": assigned})
		return
	}
	if err := s.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Delete(&models.DistributionProfile{}, profile.ID).Error; err != nil {
			return err
		}
		return appendDistributionAudit(tx, actor, "profile_deleted", &profile.ID, nil, distributionProfileAuditSnapshot(profile), nil, nil, "")
	}); err != nil {
		c.JSON(http.StatusConflict, gin.H{"error": "delete failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleAdminTestDistributionProfile(c *gin.Context) {
	id, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid profile id"})
		return
	}
	actor, _ := userFromContext(c)
	var profile models.DistributionProfile
	if err := s.DB.First(&profile, id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "profile not found"})
		return
	}
	result := testDistributionProfile(c.Request.Context(), s.Config, profile)
	if err := appendDistributionAudit(s.DB, actor, "profile_tested", &profile.ID, nil, nil, nil, result, result.ErrorClass); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "audit failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"result": result})
}

func (s *Server) handleAdminTestDistributionDraft(c *gin.Context) {
	actor, _ := userFromContext(c)
	var request distributionProfileRequest
	if !bindDistributionJSON(c, &request) {
		return
	}
	profile := request.profile()
	result := testDistributionProfile(c.Request.Context(), s.Config, profile)
	var profileID *uint
	if profile.ID > 0 {
		profileID = &profile.ID
	}
	if err := appendDistributionAudit(s.DB, actor, "profile_draft_tested", profileID, nil, nil, distributionProfileAuditSnapshot(profile), result, result.ErrorClass); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "audit failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"result": result})
}

func (s *Server) handleAdminDistributionAudit(c *gin.Context) {
	limit, _ := strconv.Atoi(strings.TrimSpace(c.Query("limit")))
	if limit < 1 || limit > 500 {
		limit = 200
	}
	var rows []models.DistributionAuditEvent
	if err := s.DB.Order("created_at desc, id desc").Limit(limit).Find(&rows).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
		return
	}
	items := make([]gin.H, 0, len(rows))
	for _, row := range rows {
		items = append(items, gin.H{
			"id": row.ID, "actorUserId": row.ActorUserID, "actorUsername": row.ActorUsername,
			"action": row.Action, "profileId": row.ProfileID, "targetUserId": row.TargetUserID,
			"before": distributionAuditJSON(row.BeforeJSON), "after": distributionAuditJSON(row.AfterJSON),
			"testResult": distributionAuditJSON(row.TestResultJSON), "errorClass": row.ErrorClass, "createdAt": row.CreatedAt,
		})
	}
	c.JSON(http.StatusOK, gin.H{"items": items})
}

func distributionAuditJSON(raw string) any {
	if strings.TrimSpace(raw) == "" {
		return nil
	}
	var value any
	if json.Unmarshal([]byte(raw), &value) != nil {
		return nil
	}
	return value
}

func (s *Server) handleAdminUserDistributionProfile(c *gin.Context) {
	s.distributionWriteMu.Lock()
	defer s.distributionWriteMu.Unlock()

	userID, err := parseUintParam(c.Param("id"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
		return
	}
	actor, _ := userFromContext(c)
	var request struct {
		DistributionProfileID *uint `json:"distributionProfileId"`
	}
	if !bindDistributionJSON(c, &request) {
		return
	}
	var user models.User
	if err := s.DB.First(&user, userID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}
	if request.DistributionProfileID != nil {
		var profile models.DistributionProfile
		if err := s.DB.First(&profile, *request.DistributionProfileID).Error; err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "distribution profile not found"})
			return
		}
	}
	before := map[string]any{"distributionProfileId": user.DistributionProfileID}
	after := map[string]any{"distributionProfileId": request.DistributionProfileID}
	if err := s.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&models.User{}).Where("id = ?", user.ID).Update("distribution_profile_id", request.DistributionProfileID).Error; err != nil {
			return err
		}
		return appendDistributionAudit(tx, actor, "user_assignment_changed", request.DistributionProfileID, &user.ID, before, after, nil, "")
	}); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "assignment failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"userId": user.ID, "distributionProfileId": request.DistributionProfileID})
}

func appendDistributionAudit(database *gorm.DB, actor models.User, action string, profileID *uint, targetUserID *uint, before any, after any, testResult any, errorClass string) error {
	actorID := actor.ID
	row := models.DistributionAuditEvent{
		ActorUserID: &actorID, ActorUsername: actor.Username, Action: action,
		ProfileID: profileID, TargetUserID: targetUserID, ErrorClass: strings.TrimSpace(errorClass), CreatedAt: time.Now().UTC(),
	}
	if before != nil {
		row.BeforeJSON = marshalDistributionAudit(before)
	}
	if after != nil {
		row.AfterJSON = marshalDistributionAudit(after)
	}
	if testResult != nil {
		row.TestResultJSON = marshalDistributionAudit(testResult)
	}
	return database.Create(&row).Error
}
