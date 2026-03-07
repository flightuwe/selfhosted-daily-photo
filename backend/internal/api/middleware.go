package api

import (
    "net/http"
    "strings"

    "github.com/gin-gonic/gin"
    "github.com/yosho/selfhosted-bereal/backend/internal/models"
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
