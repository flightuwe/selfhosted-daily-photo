package api

import (
	"errors"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
)

type refreshRequest struct {
	RefreshToken string `json:"refreshToken" binding:"required,min=32,max=255"`
}

func (s *Server) handleAuthRefresh(c *gin.Context) {
	var req refreshRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
		return
	}
	tokens, user, err := s.rotateSessionTokens(req.RefreshToken)
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "session_revoked", "errorCode": "session_revoked"})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": "refresh failed", "errorCode": "refresh_failed"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"token":        tokens.AccessToken,
		"accessToken":  tokens.AccessToken,
		"refreshToken": tokens.RefreshToken,
		"sessionId":    tokens.SessionID,
		"user":         s.userOwnJSON(user),
	})
}

func (s *Server) handleAuthLogout(c *gin.Context) {
	user, ok := userFromContext(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized", "errorCode": "unauthorized"})
		return
	}
	claims, hasClaims := userClaimsFromContext(c)
	if hasClaims {
		s.revokeSessionByID(strings.TrimSpace(claims.SessionID), user.ID)
	}
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleAuthLogoutAll(c *gin.Context) {
	user, ok := userFromContext(c)
	if !ok {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized", "errorCode": "unauthorized"})
		return
	}
	s.revokeAllSessionsByUserID(user.ID)
	c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) revokeSessionByRefreshToken(refreshToken string) {
	hashed := hashRefreshToken(strings.TrimSpace(refreshToken))
	if hashed == "" {
		return
	}
	var session models.UserSession
	if err := s.DB.Select("session_id", "user_id").Where("refresh_token_hash = ?", hashed).First(&session).Error; err == nil {
		s.revokeSessionByID(session.SessionID, session.UserID)
	}
}
