package api

import (
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

func (s *Server) requireAuth(c *gin.Context) {
	header := c.GetHeader("Authorization")
	if header == "" || !strings.HasPrefix(header, "Bearer ") {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing token"})
		return
	}

	token := strings.TrimPrefix(header, "Bearer ")
	claims, err := s.Auth.Parse(token)
	if err != nil {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
		return
	}

	var user models.User
	if err := s.DB.First(&user, claims.UserID).Error; err != nil {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "user not found"})
		return
	}

	c.Set("user", user)
	s.touchDailyUserActivity(user.ID, time.Now().In(s.Location))
	c.Next()
}

func (s *Server) requireAdmin(c *gin.Context) {
	user, ok := userFromContext(c)
	if !ok || !user.IsAdmin {
		c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "admin required"})
		return
	}
	c.Next()
}

func userFromContext(c *gin.Context) (models.User, bool) {
	v, ok := c.Get("user")
	if !ok {
		return models.User{}, false
	}
	user, ok := v.(models.User)
	return user, ok
}

func (s *Server) touchDailyUserActivity(userID uint, now time.Time) {
	if userID == 0 || s.DB == nil || s.Location == nil {
		return
	}
	day := now.In(s.Location).Format("2006-01-02")
	entry := models.DailyUserActivity{
		Day:          day,
		UserID:       userID,
		FirstSeenAt:  now,
		LastSeenAt:   now,
		RequestCount: 1,
	}
	_ = s.DB.Clauses(clause.OnConflict{
		Columns: []clause.Column{
			{Name: "day"},
			{Name: "user_id"},
		},
		DoUpdates: clause.Assignments(map[string]any{
			"last_seen_at":  now,
			"request_count": gorm.Expr("request_count + ?", 1),
			"updated_at":    now,
		}),
	}).Create(&entry).Error
}
