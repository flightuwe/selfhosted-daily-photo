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

const activityTouchThrottleWindow = 45 * time.Second

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
	lookupStart := time.Now()
	if err := s.DB.First(&user, claims.UserID).Error; err != nil {
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "user not found"})
		return
	}
	route := c.FullPath()
	if route == "" {
		route = c.Request.URL.Path
	}
	if s.Monitor != nil {
		s.Monitor.RecordDBQuery(route, "auth_user_lookup", time.Since(lookupStart))
	}

	c.Set("user", user)
	now := time.Now().In(s.Location)
	if s.shouldTouchDailyUserActivity(user.ID, now) {
		touchStart := time.Now()
		s.touchDailyUserActivity(user.ID, now)
		if s.Monitor != nil {
			s.Monitor.RecordDBQuery(route, "auth_activity_touch", time.Since(touchStart))
		}
	}
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

func (s *Server) shouldTouchDailyUserActivity(userID uint, now time.Time) bool {
	if userID == 0 {
		return false
	}
	s.activityTouchMu.Lock()
	defer s.activityTouchMu.Unlock()
	if s.activityTouchLast == nil {
		s.activityTouchLast = make(map[uint]time.Time, 64)
	}
	if last, ok := s.activityTouchLast[userID]; ok && now.Sub(last) < activityTouchThrottleWindow {
		return false
	}
	s.activityTouchLast[userID] = now
	if len(s.activityTouchLast) > 5000 {
		cutoff := now.Add(-24 * time.Hour)
		for id, ts := range s.activityTouchLast {
			if ts.Before(cutoff) {
				delete(s.activityTouchLast, id)
			}
		}
	}
	return true
}
