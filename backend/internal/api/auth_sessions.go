package api

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
)

type authTokens struct {
	AccessToken  string
	RefreshToken string
	SessionID    string
}

func hashRefreshToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func generateSecureToken(size int) (string, error) {
	buf := make([]byte, size)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

func (s *Server) issueSessionTokens(user models.User, deviceName string) (authTokens, error) {
	sessionID := uuid.NewString()
	rawRefreshToken, err := generateSecureToken(32)
	if err != nil {
		return authTokens{}, err
	}
	now := time.Now().In(s.Location)
	expiresAt := now.Add(s.Config.RefreshTokenTTL)
	session := models.UserSession{
		SessionID:        sessionID,
		UserID:           user.ID,
		RefreshTokenHash: hashRefreshToken(rawRefreshToken),
		DeviceName:       strings.TrimSpace(deviceName),
		LastUsedAt:       now,
		ExpiresAt:        &expiresAt,
	}
	if err := s.DB.Create(&session).Error; err != nil {
		return authTokens{}, err
	}
	accessToken, signErr := s.Auth.SignAccess(user.ID, user.Username, user.IsAdmin, sessionID, user.AuthVersion)
	if signErr != nil {
		_ = s.DB.Delete(&models.UserSession{}, session.ID).Error
		return authTokens{}, signErr
	}
	return authTokens{
		AccessToken:  accessToken,
		RefreshToken: rawRefreshToken,
		SessionID:    sessionID,
	}, nil
}

func (s *Server) rotateSessionTokens(rawRefreshToken string) (authTokens, models.User, error) {
	now := time.Now().In(s.Location)
	hashed := hashRefreshToken(strings.TrimSpace(rawRefreshToken))
	var session models.UserSession
	if err := s.DB.Where("refresh_token_hash = ?", hashed).First(&session).Error; err != nil {
		return authTokens{}, models.User{}, err
	}
	if session.RevokedAt != nil {
		return authTokens{}, models.User{}, gorm.ErrRecordNotFound
	}
	if session.ExpiresAt != nil && session.ExpiresAt.Before(now) {
		return authTokens{}, models.User{}, gorm.ErrRecordNotFound
	}
	var user models.User
	if err := s.DB.First(&user, session.UserID).Error; err != nil {
		return authTokens{}, models.User{}, err
	}
	newRawRefresh, err := generateSecureToken(32)
	if err != nil {
		return authTokens{}, models.User{}, err
	}
	newExpiresAt := now.Add(s.Config.RefreshTokenTTL)
	nextHash := hashRefreshToken(newRawRefresh)
	update := s.DB.Exec(
		`UPDATE user_sessions
		SET refresh_token_hash = ?, last_used_at = ?, expires_at = ?, updated_at = ?
		WHERE id = ? AND revoked_at IS NULL AND refresh_token_hash = ?`,
		nextHash,
		now,
		newExpiresAt,
		now,
		session.ID,
		hashed,
	)
	if update.Error != nil {
		return authTokens{}, models.User{}, update.Error
	}
	if update.RowsAffected != 1 {
		var current models.UserSession
		err := s.DB.Select("id", "refresh_token_hash", "revoked_at").First(&current, session.ID).Error
		if err != nil {
			if errors.Is(err, gorm.ErrRecordNotFound) {
				return authTokens{}, models.User{}, gorm.ErrRecordNotFound
			}
			return authTokens{}, models.User{}, err
		}
		if current.RevokedAt != nil || current.RefreshTokenHash != hashed {
			return authTokens{}, models.User{}, gorm.ErrRecordNotFound
		}
		retry := s.DB.Exec(
			`UPDATE user_sessions
			SET refresh_token_hash = ?, last_used_at = ?, expires_at = ?, updated_at = ?
			WHERE id = ? AND revoked_at IS NULL AND refresh_token_hash = ?`,
			nextHash,
			now,
			newExpiresAt,
			now,
			session.ID,
			hashed,
		)
		if retry.Error != nil {
			return authTokens{}, models.User{}, retry.Error
		}
		if retry.RowsAffected != 1 {
			return authTokens{}, models.User{}, gorm.ErrRecordNotFound
		}
	}
	accessToken, signErr := s.Auth.SignAccess(user.ID, user.Username, user.IsAdmin, session.SessionID, user.AuthVersion)
	if signErr != nil {
		return authTokens{}, models.User{}, signErr
	}
	return authTokens{
		AccessToken:  accessToken,
		RefreshToken: newRawRefresh,
		SessionID:    session.SessionID,
	}, user, nil
}

func (s *Server) revokeSessionByID(sessionID string, userID uint) {
	if strings.TrimSpace(sessionID) == "" || userID == 0 {
		return
	}
	now := time.Now().In(s.Location)
	_ = s.DB.Model(&models.UserSession{}).
		Where("session_id = ? AND user_id = ? AND revoked_at IS NULL", sessionID, userID).
		Updates(map[string]any{
			"revoked_at":   now,
			"updated_at":   now,
			"last_used_at": now,
		}).Error
}

func (s *Server) revokeAllSessionsByUserID(userID uint) {
	if userID == 0 {
		return
	}
	now := time.Now().In(s.Location)
	_ = s.DB.Model(&models.UserSession{}).
		Where("user_id = ? AND revoked_at IS NULL", userID).
		Updates(map[string]any{
			"revoked_at":   now,
			"updated_at":   now,
			"last_used_at": now,
		}).Error
}
