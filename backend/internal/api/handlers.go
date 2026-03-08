package api

import (
    "errors"
    "fmt"
    "mime/multipart"
    "net/http"
    "path/filepath"
    "regexp"
    "strconv"
    "strings"
    "time"

    "github.com/gin-contrib/cors"
    "github.com/gin-gonic/gin"
    "github.com/yosho/selfhosted-bereal/backend/internal/auth"
    "github.com/yosho/selfhosted-bereal/backend/internal/config"
    "github.com/yosho/selfhosted-bereal/backend/internal/models"
    "github.com/yosho/selfhosted-bereal/backend/internal/notify"
    "github.com/yosho/selfhosted-bereal/backend/internal/scheduler"
    "github.com/yosho/selfhosted-bereal/backend/internal/storage"
    "gorm.io/gorm"
)

type Server struct {
    DB       *gorm.DB
    Config   config.Config
    Auth     *auth.Manager
    Store    *storage.LocalStore
    Notifier notify.Sender
    Prompt   *scheduler.DailyPromptService
    Location *time.Location
}

func (s *Server) Router() *gin.Engine {
    r := gin.Default()
    r.Use(cors.New(cors.Config{
        AllowOrigins:     s.Config.AllowedOrigins,
        AllowMethods:     []string{"GET", "POST", "PUT", "DELETE"},
        AllowHeaders:     []string{"Authorization", "Content-Type"},
        ExposeHeaders:    []string{"Content-Length"},
        AllowCredentials: true,
        MaxAge:           12 * time.Hour,
    }))

    r.Static("/uploads", s.Config.UploadDir)

	api := r.Group("/api")
	{
		api.GET("/health", s.handleHealth)
        api.POST("/auth/register", s.handleRegister)
        api.POST("/auth/login", s.handleLogin)

        protected := api.Group("")
        protected.Use(s.requireAuth)
        {
            protected.GET("/me", s.handleMe)
            protected.PUT("/me/profile", s.handleUpdateProfile)
            protected.PUT("/me/password", s.handleChangePassword)
            protected.GET("/me/photos", s.handleMyPhotos)
            protected.POST("/devices", s.handleDevice)
            protected.GET("/prompt/current", s.handleCurrentPrompt)
            protected.POST("/uploads", s.handleUpload)
            protected.POST("/uploads/dual", s.handleDualUpload)
            protected.GET("/feed", s.handleFeed)
            protected.GET("/feed/days", s.handleFeedDays)
            protected.GET("/chat", s.handleChatList)
            protected.POST("/chat", s.handleChatCreate)
        }

        admin := api.Group("/admin")
        admin.Use(s.requireAuth, s.requireAdmin)
        {
            admin.GET("/settings", s.handleGetSettings)
            admin.PUT("/settings", s.handleUpdateSettings)
            admin.GET("/stats", s.handleAdminStats)
            admin.GET("/feed", s.handleAdminFeed)
            admin.GET("/calendar", s.handleAdminCalendar)
            admin.PUT("/calendar/:day", s.handleAdminCalendarDay)

            admin.POST("/prompt/trigger", s.handleTriggerPrompt)
            admin.POST("/prompt/reset-today", s.handleAdminResetToday)
            admin.POST("/notifications/broadcast", s.handleBroadcastNotification)
            admin.POST("/notifications/user/:id", s.handleUserNotification)
            admin.POST("/chat/clear", s.handleAdminClearChat)

            admin.GET("/users", s.handleAdminListUsers)
            admin.POST("/users", s.handleAdminCreateUser)
            admin.PUT("/users/:id", s.handleAdminUpdateUser)
            admin.DELETE("/users/:id", s.handleAdminDeleteUser)
        }
    }

    return r
}

type authRequest struct {
    Username string `json:"username" binding:"required,min=3,max=64"`
    Password string `json:"password" binding:"required,min=6,max=128"`
}

func (s *Server) handleRegister(c *gin.Context) {
    var req authRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }

    hash, err := auth.HashPassword(req.Password)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "hash failed"})
        return
    }

    user := models.User{Username: strings.ToLower(req.Username), PasswordHash: hash}
    if err := s.DB.Create(&user).Error; err != nil {
        c.JSON(http.StatusConflict, gin.H{"error": "username exists"})
        return
    }

    token, _ := s.Auth.Sign(user.ID, user.Username, user.IsAdmin)
    c.JSON(http.StatusCreated, gin.H{"token": token, "user": user})
}

func (s *Server) handleLogin(c *gin.Context) {
    var req authRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }

    var user models.User
    if err := s.DB.Where("username = ?", strings.ToLower(req.Username)).First(&user).Error; err != nil {
        c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid credentials"})
        return
    }

    if !auth.CheckPassword(user.PasswordHash, req.Password) {
        c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid credentials"})
        return
    }

    token, _ := s.Auth.Sign(user.ID, user.Username, user.IsAdmin)
    c.JSON(http.StatusOK, gin.H{"token": token, "user": user})
}

func (s *Server) handleMe(c *gin.Context) {
    user, _ := userFromContext(c)
    if user.FavoriteColor == "" {
        user.FavoriteColor = "#1F5FBF"
    }
    c.JSON(http.StatusOK, gin.H{"user": user})
}

func (s *Server) handleUpdateProfile(c *gin.Context) {
    user, _ := userFromContext(c)
    var req struct {
        Username      string `json:"username" binding:"required,min=3,max=64"`
        FavoriteColor string `json:"favoriteColor"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }
    username := strings.ToLower(strings.TrimSpace(req.Username))
    if len(username) < 3 {
        c.JSON(http.StatusBadRequest, gin.H{"error": "username too short"})
        return
    }
    color, ok := normalizeColor(req.FavoriteColor)
    if !ok {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid favoriteColor"})
        return
    }

    var existing models.User
    if err := s.DB.Where("username = ? AND id <> ?", username, user.ID).First(&existing).Error; err == nil {
        c.JSON(http.StatusConflict, gin.H{"error": "username exists"})
        return
    }

    if err := s.DB.Model(&models.User{}).Where("id = ?", user.ID).Updates(map[string]any{
        "username":       username,
        "favorite_color": color,
    }).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
        return
    }

    var updated models.User
    if err := s.DB.First(&updated, user.ID).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }
    c.JSON(http.StatusOK, gin.H{"user": updated})
}

func (s *Server) handleChangePassword(c *gin.Context) {
    user, _ := userFromContext(c)

    var req struct {
        CurrentPassword string `json:"currentPassword" binding:"required,min=6,max=128"`
        NewPassword     string `json:"newPassword" binding:"required,min=6,max=128"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }
    if !auth.CheckPassword(user.PasswordHash, req.CurrentPassword) {
        c.JSON(http.StatusUnauthorized, gin.H{"error": "current password invalid"})
        return
    }
    hash, err := auth.HashPassword(req.NewPassword)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "hash failed"})
        return
    }
    if err := s.DB.Model(&models.User{}).Where("id = ?", user.ID).Update("password_hash", hash).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
        return
    }
    c.JSON(http.StatusOK, gin.H{"ok": true})
}

type deviceRequest struct {
    Token string `json:"token" binding:"required,max=255"`
}

func (s *Server) handleDevice(c *gin.Context) {
    user, _ := userFromContext(c)
    var req deviceRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }

    d := models.DeviceToken{Token: req.Token, UserID: user.ID}
    _ = s.DB.Where("token = ?", req.Token).Assign(d).FirstOrCreate(&d).Error
    c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleCurrentPrompt(c *gin.Context) {
    user, _ := userFromContext(c)
    now := time.Now().In(s.Location)
    day := now.Format("2006-01-02")

    var prompt models.DailyPrompt
    err := s.DB.Where("day = ?", day).First(&prompt).Error
    if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }

    canUpload := prompt.UploadUntil != nil && now.Before(*prompt.UploadUntil)
    hasPosted, _ := s.userHasPostedForDay(user.ID, day)
    var ownPhoto gin.H
    if hasPosted {
        var p models.Photo
        if err := s.DB.Where("user_id = ? AND day = ? AND prompt_only = ?", user.ID, day, true).Order("created_at desc").First(&p).Error; err == nil {
            ownPhoto = s.photoJSON(p)
        }
    }

    c.JSON(http.StatusOK, gin.H{
        "day":         day,
        "triggered":   prompt.TriggeredAt,
        "uploadUntil": prompt.UploadUntil,
        "canUpload":   canUpload,
        "hasPosted":   hasPosted,
        "ownPhoto":    ownPhoto,
    })
}

func (s *Server) handleUpload(c *gin.Context) {
    user, _ := userFromContext(c)

    var settings models.AppSettings
    if err := s.DB.First(&settings).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
        return
    }

    if settings.MaxUploadBytes > 0 {
        c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, settings.MaxUploadBytes)
    }

    fileHeader, err := c.FormFile("photo")
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "photo file required"})
        return
    }

    kind := c.PostForm("kind")
    if kind == "" {
        kind = "extra"
    }
    if kind != "prompt" && kind != "extra" {
        c.JSON(http.StatusBadRequest, gin.H{"error": "kind must be prompt or extra"})
        return
    }

    now := time.Now().In(s.Location)
    day := now.Format("2006-01-02")

    hasPosted, err := s.userHasPostedForDay(user.ID, day)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }

    if kind == "prompt" {
        if hasPosted {
            c.JSON(http.StatusConflict, gin.H{"error": "Du hast heute bereits gepostet"})
            return
        }
        var prompt models.DailyPrompt
        if err := s.DB.Where("day = ?", day).First(&prompt).Error; err != nil {
            c.JSON(http.StatusForbidden, gin.H{"error": "prompt inactive"})
            return
        }
        if prompt.UploadUntil == nil || now.After(*prompt.UploadUntil) {
            c.JSON(http.StatusForbidden, gin.H{"error": "upload window closed"})
            return
        }
    } else {
        if !hasPosted {
            c.JSON(http.StatusForbidden, gin.H{"error": "poste zuerst dein Tagesmoment"})
            return
        }
    }

    src, err := fileHeader.Open()
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "open upload failed"})
        return
    }
    defer src.Close()

    ext := strings.ToLower(filepath.Ext(fileHeader.Filename))
    relPath, err := s.Store.SavePhoto(day, user.ID, src, ext)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
        return
    }

    photo := models.Photo{
        UserID:     user.ID,
        Day:        day,
        PromptOnly: kind == "prompt",
        FilePath:   relPath,
        Caption:    c.PostForm("caption"),
    }
    if err := s.DB.Create(&photo).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "db write failed"})
        return
    }

    c.JSON(http.StatusCreated, gin.H{"photo": s.photoJSON(photo)})
}

func (s *Server) handleAdminCalendar(c *gin.Context) {
    days := 7
    if raw := c.Query("days"); raw != "" {
        if n, err := strconv.Atoi(raw); err == nil {
            days = n
        }
    }

    plans, err := s.Prompt.EnsurePlans(days)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "calendar query failed"})
        return
    }

    dayList := make([]string, 0, len(plans))
    for _, p := range plans {
        dayList = append(dayList, p.Day)
    }

    var prompts []models.DailyPrompt
    _ = s.DB.Where("day IN ?", dayList).Find(&prompts).Error
    promptByDay := make(map[string]models.DailyPrompt, len(prompts))
    for _, p := range prompts {
        promptByDay[p.Day] = p
    }

    out := make([]gin.H, 0, len(plans))
    for _, p := range plans {
        row := gin.H{
            "day":       p.Day,
            "plannedAt": p.PlannedAt,
            "isManual":  p.IsManual,
            "source":    "auto",
        }
        if p.IsManual {
            row["source"] = "manual"
        }
        if prompt, ok := promptByDay[p.Day]; ok {
            row["triggeredAt"] = prompt.TriggeredAt
            row["uploadUntil"] = prompt.UploadUntil
        }
        out = append(out, row)
    }

    c.JSON(http.StatusOK, gin.H{"items": out})
}

func (s *Server) handleAdminCalendarDay(c *gin.Context) {
    day := c.Param("day")
    if _, err := time.ParseInLocation("2006-01-02", day, s.Location); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid day format"})
        return
    }

    var req struct {
        PlannedAt string `json:"plannedAt" binding:"required"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }

    var planned time.Time
    var err error
    if len(req.PlannedAt) == len("2006-01-02T15:04") {
        planned, err = time.ParseInLocation("2006-01-02T15:04", req.PlannedAt, s.Location)
    } else {
        planned, err = time.Parse(time.RFC3339, req.PlannedAt)
        planned = planned.In(s.Location)
    }
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid plannedAt format"})
        return
    }
    if planned.Format("2006-01-02") != day {
        c.JSON(http.StatusBadRequest, gin.H{"error": "plannedAt day mismatch"})
        return
    }

    plan, err := s.Prompt.SetPlanForDay(day, planned, true)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "save plan failed"})
        return
    }

    var prompt models.DailyPrompt
    _ = s.DB.Where("day = ?", day).First(&prompt).Error

    c.JSON(http.StatusOK, gin.H{
        "day":        plan.Day,
        "plannedAt":  plan.PlannedAt,
        "isManual":   plan.IsManual,
        "source":     "manual",
        "triggeredAt": prompt.TriggeredAt,
        "uploadUntil": prompt.UploadUntil,
    })
}

func (s *Server) handleAdminFeed(c *gin.Context) {
    day := c.Query("day")
    if day == "" {
        day = time.Now().In(s.Location).Format("2006-01-02")
    }

    var prompt models.DailyPrompt
    _ = s.DB.Where("day = ?", day).First(&prompt).Error

    var photos []models.Photo
    if err := s.DB.Preload("User").Where("day = ?", day).Order("created_at desc").Find(&photos).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }

    out := make([]gin.H, 0, len(photos))
    for _, p := range photos {
        isLate := false
        if prompt.UploadUntil != nil && p.CreatedAt.After(*prompt.UploadUntil) {
            isLate = true
        }
        out = append(out, gin.H{
            "isLate": isLate,
            "photo":  s.photoJSON(p),
            "user": gin.H{
                "id":       p.User.ID,
                "username": p.User.Username,
                "favoriteColor": defaultColor(p.User.FavoriteColor),
            },
        })
    }

    c.JSON(http.StatusOK, gin.H{"items": out, "day": day})
}

func (s *Server) handleFeed(c *gin.Context) {
    user, _ := userFromContext(c)
    day := c.Query("day")
    if day == "" {
        day = time.Now().In(s.Location).Format("2006-01-02")
    }
    today := time.Now().In(s.Location).Format("2006-01-02")
    if day == today {
        hasPosted, err := s.userHasPostedForDay(user.ID, day)
        if err != nil {
            c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
            return
        }
        if !hasPosted {
            c.JSON(http.StatusForbidden, gin.H{
                "error": "Poste zuerst dein Foto, um die Beitraege der anderen zu sehen",
                "code":  "feed_locked",
            })
            return
        }
    }

    var prompt models.DailyPrompt
    _ = s.DB.Where("day = ?", day).First(&prompt).Error

    var photos []models.Photo
    if err := s.DB.Preload("User").Where("day = ?", day).Order("created_at desc").Find(&photos).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }

    out := make([]gin.H, 0, len(photos))
    for _, p := range photos {
        isLate := false
        if prompt.UploadUntil != nil && p.CreatedAt.After(*prompt.UploadUntil) {
            isLate = true
        }
        out = append(out, gin.H{
            "isLate":     isLate,
            "photo":      s.photoJSON(p),
            "user": gin.H{
                "id":       p.User.ID,
                "username": p.User.Username,
                "favoriteColor": defaultColor(p.User.FavoriteColor),
            },
        })
    }

    c.JSON(http.StatusOK, gin.H{"items": out})
}

func (s *Server) handleGetSettings(c *gin.Context) {
    var settings models.AppSettings
    if err := s.DB.First(&settings).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
        return
    }
    c.JSON(http.StatusOK, settings)
}

type settingsRequest struct {
    PromptWindowStartHour  int    `json:"promptWindowStartHour"`
    PromptWindowEndHour    int    `json:"promptWindowEndHour"`
    UploadWindowMinutes    int    `json:"uploadWindowMinutes"`
    PromptNotificationText string `json:"promptNotificationText"`
    MaxUploadBytes         int64  `json:"maxUploadBytes"`
}

func (s *Server) handleUpdateSettings(c *gin.Context) {
    var req settingsRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }

    if req.PromptWindowStartHour < 0 || req.PromptWindowStartHour > 23 || req.PromptWindowEndHour < 1 || req.PromptWindowEndHour > 24 {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid hours"})
        return
    }

    var settings models.AppSettings
    if err := s.DB.First(&settings).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
        return
    }

    settings.PromptWindowStartHour = req.PromptWindowStartHour
    settings.PromptWindowEndHour = req.PromptWindowEndHour
    settings.UploadWindowMinutes = req.UploadWindowMinutes
    settings.PromptNotificationText = req.PromptNotificationText
    settings.MaxUploadBytes = req.MaxUploadBytes

    if err := s.DB.Save(&settings).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
        return
    }

    c.JSON(http.StatusOK, settings)
}

func (s *Server) handleTriggerPrompt(c *gin.Context) {
    prompt, settings, err := s.Prompt.TriggerNow()
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "trigger failed"})
        return
    }

    tokens := s.allDeviceTokens()
    sendResult, sendErr := s.Notifier.SendDailyPrompt(tokens, settings.PromptNotificationText)
    removed := s.removeInvalidTokens(sendResult.InvalidTokens)
    if sendErr != nil {
        c.JSON(http.StatusOK, gin.H{
            "prompt":          prompt,
            "settings":        settings,
            "devices":         len(tokens),
            "provider":        s.Notifier.Name(),
            "sentTo":          sendResult.Sent,
            "failed":          sendResult.Failed,
            "invalidRemoved":  removed,
            "notificationErr": sendErr.Error(),
        })
        return
    }

    c.JSON(http.StatusOK, gin.H{
        "prompt":         prompt,
        "settings":       settings,
        "devices":        len(tokens),
        "provider":       s.Notifier.Name(),
        "sentTo":         sendResult.Sent,
        "failed":         sendResult.Failed,
        "invalidRemoved": removed,
    })
}

func (s *Server) handleAdminResetToday(c *gin.Context) {
    day := time.Now().In(s.Location).Format("2006-01-02")
    now := time.Now().In(s.Location)

    var settings models.AppSettings
    if err := s.DB.First(&settings).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
        return
    }
    uploadUntil := now.Add(time.Duration(settings.UploadWindowMinutes) * time.Minute)

    txErr := s.DB.Transaction(func(tx *gorm.DB) error {
        if err := tx.Where("day = ?", day).Delete(&models.Photo{}).Error; err != nil {
            return err
        }
        if err := tx.Where("day = ?", day).Delete(&models.DailyPrompt{}).Error; err != nil {
            return err
        }
        prompt := models.DailyPrompt{
            Day:         day,
            TriggeredAt: &now,
            UploadUntil: &uploadUntil,
        }
        return tx.Create(&prompt).Error
    })
    if txErr != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "reset failed"})
        return
    }

    c.JSON(http.StatusOK, gin.H{
        "ok":          true,
        "day":         day,
        "triggeredAt": now,
        "uploadUntil": uploadUntil,
        "message":     "heutiger Moment wurde zurueckgesetzt und neu gestartet",
    })
}

func (s *Server) handleAdminCreateUser(c *gin.Context) {
    var req struct {
        Username string `json:"username" binding:"required,min=3,max=64"`
        Password string `json:"password" binding:"required,min=6,max=128"`
        IsAdmin  bool   `json:"isAdmin"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload: username>=3, password>=6"})
        return
    }

    hash, err := auth.HashPassword(req.Password)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "hash failed"})
        return
    }

    user := models.User{Username: strings.ToLower(req.Username), PasswordHash: hash, IsAdmin: req.IsAdmin}
    if err := s.DB.Create(&user).Error; err != nil {
        c.JSON(http.StatusConflict, gin.H{"error": "username exists"})
        return
    }

    c.JSON(http.StatusCreated, toAdminUser(user, 0, 0))
}

func (s *Server) handleAdminListUsers(c *gin.Context) {
    var users []models.User
    if err := s.DB.Order("created_at desc").Find(&users).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }

    out := make([]gin.H, 0, len(users))
    for _, u := range users {
        var photoCount int64
        var tokenCount int64
        _ = s.DB.Model(&models.Photo{}).Where("user_id = ?", u.ID).Count(&photoCount).Error
        _ = s.DB.Model(&models.DeviceToken{}).Where("user_id = ?", u.ID).Count(&tokenCount).Error
        out = append(out, toAdminUser(u, photoCount, tokenCount))
    }

    c.JSON(http.StatusOK, gin.H{"items": out})
}

func (s *Server) handleAdminUpdateUser(c *gin.Context) {
    id, err := parseUintParam(c.Param("id"))
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
        return
    }

    var req struct {
        Password *string `json:"password"`
        IsAdmin  *bool   `json:"isAdmin"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }

    var user models.User
    if err := s.DB.First(&user, id).Error; err != nil {
        c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
        return
    }

    if req.Password != nil {
        if len(strings.TrimSpace(*req.Password)) < 6 {
            c.JSON(http.StatusBadRequest, gin.H{"error": "password must be at least 6 chars"})
            return
        }
        hash, err := auth.HashPassword(*req.Password)
        if err != nil {
            c.JSON(http.StatusInternalServerError, gin.H{"error": "hash failed"})
            return
        }
        user.PasswordHash = hash
    }

    if req.IsAdmin != nil {
        user.IsAdmin = *req.IsAdmin
    }

    if err := s.DB.Save(&user).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
        return
    }

    var photoCount int64
    var tokenCount int64
    _ = s.DB.Model(&models.Photo{}).Where("user_id = ?", user.ID).Count(&photoCount).Error
    _ = s.DB.Model(&models.DeviceToken{}).Where("user_id = ?", user.ID).Count(&tokenCount).Error

    c.JSON(http.StatusOK, toAdminUser(user, photoCount, tokenCount))
}

func (s *Server) handleAdminDeleteUser(c *gin.Context) {
    id, err := parseUintParam(c.Param("id"))
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
        return
    }

    adminUser, _ := userFromContext(c)
    if adminUser.ID == id {
        c.JSON(http.StatusBadRequest, gin.H{"error": "cannot delete current admin"})
        return
    }

    var user models.User
    if err := s.DB.First(&user, id).Error; err != nil {
        c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
        return
    }

    _ = s.DB.Where("user_id = ?", id).Delete(&models.DeviceToken{}).Error
    _ = s.DB.Where("user_id = ?", id).Delete(&models.Photo{}).Error
    if err := s.DB.Delete(&user).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
        return
    }

    c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleAdminStats(c *gin.Context) {
    var users int64
    var photos int64
    var devices int64
    var prompts int64

    _ = s.DB.Model(&models.User{}).Count(&users).Error
    _ = s.DB.Model(&models.Photo{}).Count(&photos).Error
    _ = s.DB.Model(&models.DeviceToken{}).Count(&devices).Error
    _ = s.DB.Model(&models.DailyPrompt{}).Count(&prompts).Error

    c.JSON(http.StatusOK, gin.H{
        "users":   users,
        "photos":  photos,
        "devices": devices,
        "prompts": prompts,
    })
}

func (s *Server) handleBroadcastNotification(c *gin.Context) {
    var req struct {
        Body string `json:"body" binding:"required,min=3,max=255"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }

    tokens := s.allDeviceTokens()
    sendResult, err := s.Notifier.SendDailyPrompt(tokens, req.Body)
    removed := s.removeInvalidTokens(sendResult.InvalidTokens)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{
            "error":          "broadcast failed",
            "details":        err.Error(),
            "provider":       s.Notifier.Name(),
            "sentTo":         sendResult.Sent,
            "failed":         sendResult.Failed,
            "invalidRemoved": removed,
        })
        return
    }

	c.JSON(http.StatusOK, gin.H{
		"ok":      true,
		"sentTo":  sendResult.Sent,
		"failed":  sendResult.Failed,
		"invalidRemoved": removed,
		"provider": s.Notifier.Name(),
	})
}

func (s *Server) handleUserNotification(c *gin.Context) {
    id, err := parseUintParam(c.Param("id"))
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid user id"})
        return
    }
    var req struct {
        Body string `json:"body" binding:"required,min=3,max=255"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }

    var user models.User
    if err := s.DB.First(&user, id).Error; err != nil {
        c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
        return
    }

    tokens := s.userDeviceTokens(id)
    sendResult, sendErr := s.Notifier.SendDailyPrompt(tokens, req.Body)
    removed := s.removeInvalidTokens(sendResult.InvalidTokens)
    if sendErr != nil {
        c.JSON(http.StatusInternalServerError, gin.H{
            "error":          "user broadcast failed",
            "details":        sendErr.Error(),
            "provider":       s.Notifier.Name(),
            "userId":         id,
            "username":       user.Username,
            "sentTo":         sendResult.Sent,
            "failed":         sendResult.Failed,
            "invalidRemoved": removed,
        })
        return
    }

    c.JSON(http.StatusOK, gin.H{
        "ok":            true,
        "provider":      s.Notifier.Name(),
        "userId":        id,
        "username":      user.Username,
        "devices":       len(tokens),
        "sentTo":        sendResult.Sent,
        "failed":        sendResult.Failed,
        "invalidRemoved": removed,
    })
}

func (s *Server) handleAdminClearChat(c *gin.Context) {
    if err := s.DB.Session(&gorm.Session{AllowGlobalUpdate: true}).Delete(&models.ChatMessage{}).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "chat clear failed"})
        return
    }
    c.JSON(http.StatusOK, gin.H{"ok": true})
}

func (s *Server) handleChatList(c *gin.Context) {
    var messages []models.ChatMessage
    if err := s.DB.Preload("User").Order("created_at desc").Limit(100).Find(&messages).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }
    out := make([]gin.H, 0, len(messages))
    for i := len(messages) - 1; i >= 0; i-- {
        m := messages[i]
        out = append(out, gin.H{
            "id":        m.ID,
            "body":      m.Body,
            "createdAt": m.CreatedAt,
            "user": gin.H{
                "id":       m.User.ID,
                "username": m.User.Username,
                "favoriteColor": defaultColor(m.User.FavoriteColor),
            },
        })
    }
    c.JSON(http.StatusOK, gin.H{"items": out})
}

func (s *Server) handleFeedDays(c *gin.Context) {
    type row struct {
        Day string
    }
    var rows []row
    if err := s.DB.Model(&models.Photo{}).Select("DISTINCT day").Order("day desc").Limit(365).Scan(&rows).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }
    days := make([]string, 0, len(rows))
    for _, r := range rows {
        days = append(days, r.Day)
    }
    c.JSON(http.StatusOK, gin.H{"items": days})
}

func (s *Server) handleChatCreate(c *gin.Context) {
    user, _ := userFromContext(c)
    var req struct {
        Body string `json:"body" binding:"required,min=1,max=500"`
    }
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }
    msg := models.ChatMessage{UserID: user.ID, Body: strings.TrimSpace(req.Body)}
    if msg.Body == "" {
        c.JSON(http.StatusBadRequest, gin.H{"error": "message empty"})
        return
    }
    if err := s.DB.Create(&msg).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
        return
    }
    c.JSON(http.StatusCreated, gin.H{
        "id":        msg.ID,
        "body":      msg.Body,
        "createdAt": msg.CreatedAt,
        "user": gin.H{
            "id":       user.ID,
            "username": user.Username,
            "favoriteColor": defaultColor(user.FavoriteColor),
        },
    })
}

func (s *Server) handleDualUpload(c *gin.Context) {
    user, _ := userFromContext(c)

    var settings models.AppSettings
    if err := s.DB.First(&settings).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
        return
    }

    if settings.MaxUploadBytes > 0 {
        c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, settings.MaxUploadBytes*2)
    }

    backHeader, err := c.FormFile("photo_back")
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "photo_back file required"})
        return
    }
    frontHeader, err := c.FormFile("photo_front")
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "photo_front file required"})
        return
    }

    kind := c.PostForm("kind")
    if kind == "" {
        kind = "prompt"
    }
    if kind != "prompt" && kind != "extra" {
        c.JSON(http.StatusBadRequest, gin.H{"error": "kind must be prompt or extra"})
        return
    }

    now := time.Now().In(s.Location)
    day := now.Format("2006-01-02")

    hasPosted, err := s.userHasPostedForDay(user.ID, day)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }

    if kind == "prompt" {
        if hasPosted {
            c.JSON(http.StatusConflict, gin.H{"error": "Du hast heute bereits gepostet"})
            return
        }
        var prompt models.DailyPrompt
        if err := s.DB.Where("day = ?", day).First(&prompt).Error; err != nil {
            c.JSON(http.StatusForbidden, gin.H{"error": "prompt inactive"})
            return
        }
        if prompt.UploadUntil == nil || now.After(*prompt.UploadUntil) {
            c.JSON(http.StatusForbidden, gin.H{"error": "upload window closed"})
            return
        }
    } else {
        if !hasPosted {
            c.JSON(http.StatusForbidden, gin.H{"error": "poste zuerst dein Tagesmoment"})
            return
        }
    }

    backPath, err := s.saveUploadedFile(day, user.ID, backHeader)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "save back failed"})
        return
    }
    frontPath, err := s.saveUploadedFile(day, user.ID, frontHeader)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "save front failed"})
        return
    }

    photo := models.Photo{
        UserID:     user.ID,
        Day:        day,
        PromptOnly: kind == "prompt",
        FilePath:   backPath,
        SecondPath: frontPath,
        Caption:    c.PostForm("caption"),
    }
    if err := s.DB.Create(&photo).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "db write failed"})
        return
    }

    c.JSON(http.StatusCreated, gin.H{"photo": s.photoJSON(photo)})
}

func (s *Server) handleHealth(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"ok":       true,
		"version":  s.Config.AppVersion,
		"provider": s.Notifier.Name(),
	})
}

func (s *Server) handleMyPhotos(c *gin.Context) {
    user, _ := userFromContext(c)

    var photos []models.Photo
    if err := s.DB.Where("user_id = ?", user.ID).Order("created_at desc").Limit(120).Find(&photos).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }

    out := make([]gin.H, 0, len(photos))
    for _, p := range photos {
        out = append(out, s.photoJSON(p))
    }
    c.JSON(http.StatusOK, gin.H{"items": out})
}

func (s *Server) saveUploadedFile(day string, userID uint, header *multipart.FileHeader) (string, error) {
    src, err := header.Open()
    if err != nil {
        return "", err
    }
    defer src.Close()
    ext := strings.ToLower(filepath.Ext(header.Filename))
    return s.Store.SavePhoto(day, userID, src, ext)
}

func (s *Server) photoJSON(p models.Photo) gin.H {
    out := gin.H{
        "id":         p.ID,
        "day":        p.Day,
        "promptOnly": p.PromptOnly,
        "caption":    p.Caption,
        "createdAt":  p.CreatedAt,
        "url":        fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.FilePath),
    }
    if p.SecondPath != "" {
        out["secondUrl"] = fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, p.SecondPath)
    }
    return out
}

func (s *Server) allDeviceTokens() []string {
    var rows []models.DeviceToken
    _ = s.DB.Find(&rows).Error
    tokens := make([]string, 0, len(rows))
    for _, t := range rows {
        tokens = append(tokens, t.Token)
    }
    return tokens
}

func parseUintParam(v string) (uint, error) {
    n, err := strconv.ParseUint(v, 10, 32)
    if err != nil {
        return 0, err
    }
    return uint(n), nil
}

func toAdminUser(u models.User, photoCount, tokenCount int64) gin.H {
    return gin.H{
        "id":          u.ID,
        "username":    u.Username,
        "isAdmin":     u.IsAdmin,
        "createdAt":   u.CreatedAt,
        "photoCount":  photoCount,
        "deviceCount": tokenCount,
    }
}

func (s *Server) userHasPostedForDay(userID uint, day string) (bool, error) {
    var count int64
    if err := s.DB.Model(&models.Photo{}).Where("user_id = ? AND day = ? AND prompt_only = ?", userID, day, true).Count(&count).Error; err != nil {
        return false, err
    }
    return count > 0, nil
}

func (s *Server) userDeviceTokens(userID uint) []string {
    var rows []models.DeviceToken
    _ = s.DB.Where("user_id = ?", userID).Find(&rows).Error
    tokens := make([]string, 0, len(rows))
    for _, t := range rows {
        tokens = append(tokens, t.Token)
    }
    return tokens
}

func (s *Server) removeInvalidTokens(tokens []string) int64 {
    if len(tokens) == 0 {
        return 0
    }
    tx := s.DB.Where("token IN ?", tokens).Delete(&models.DeviceToken{})
    if tx.Error != nil {
        return 0
    }
    return tx.RowsAffected
}

func defaultColor(v string) string {
    if c, ok := normalizeColor(v); ok {
        return c
    }
    return "#1F5FBF"
}

var colorRe = regexp.MustCompile(`^#?[0-9a-fA-F]{6}$`)

func normalizeColor(v string) (string, bool) {
    x := strings.TrimSpace(v)
    if x == "" {
        return "#1F5FBF", true
    }
    if !colorRe.MatchString(x) {
        return "", false
    }
    if !strings.HasPrefix(x, "#") {
        x = "#" + x
    }
    return strings.ToUpper(x), true
}
