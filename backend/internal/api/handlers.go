package api

import (
    "errors"
    "fmt"
    "mime/multipart"
    "net/http"
    "os"
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
    Monitor  *Monitor
}

func (s *Server) Router() *gin.Engine {
    r := gin.Default()
    r.Use(s.requestIDMiddleware(), s.metricsMiddleware())
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
        api.GET("/health/live", s.handleLiveHealth)
        api.GET("/health/ready", s.handleReadyHealth)
        api.GET("/metrics", s.handleMetrics)
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
            protected.GET("/prompt/rules", s.handlePromptRules)
            protected.GET("/moment/special/status", s.handleSpecialMomentStatus)
            protected.POST("/moment/special/request", s.handleSpecialMomentRequest)
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
            admin.GET("/chat/commands", s.handleAdminListChatCommands)
            admin.POST("/chat/commands", s.handleAdminCreateChatCommand)
            admin.PUT("/chat/commands/:id", s.handleAdminUpdateChatCommand)
            admin.DELETE("/chat/commands/:id", s.handleAdminDeleteChatCommand)
            admin.GET("/system/health", s.handleAdminSystemHealth)

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
        "day":             day,
        "triggered":       prompt.TriggeredAt,
        "uploadUntil":     prompt.UploadUntil,
        "canUpload":       canUpload,
        "hasPosted":       hasPosted,
        "ownPhoto":        ownPhoto,
        "triggerSource":   prompt.TriggerSource,
        "requestedByUser": prompt.RequestedBy,
    })
}

func (s *Server) handlePromptRules(c *gin.Context) {
    var settings models.AppSettings
    if err := s.DB.First(&settings).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
        return
    }
    settings = normalizeSettings(settings)
    c.JSON(http.StatusOK, gin.H{
        "promptWindowStartHour": settings.PromptWindowStartHour,
        "promptWindowEndHour":   settings.PromptWindowEndHour,
        "uploadWindowMinutes":   settings.UploadWindowMinutes,
        "maxUploadBytes":        settings.MaxUploadBytes,
        "timezone":              s.Config.Timezone,
    })
}

func (s *Server) handleSpecialMomentStatus(c *gin.Context) {
    user, _ := userFromContext(c)
    status, err := s.specialMomentStatus(user.ID)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "status query failed"})
        return
    }
    c.JSON(http.StatusOK, status)
}

func (s *Server) handleSpecialMomentRequest(c *gin.Context) {
    user, _ := userFromContext(c)
    now := time.Now().In(s.Location)

    status, err := s.specialMomentStatus(user.ID)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "status query failed"})
        return
    }
    canRequest, _ := status["canRequest"].(bool)
    if !canRequest {
        c.JSON(http.StatusTooManyRequests, gin.H{
            "error":  "sondermoment already requested this week",
            "status": status,
        })
        return
    }

    prompt, settings, err := s.Prompt.TriggerNowWithSource("special_request", &user)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "special trigger failed"})
        return
    }

    reqRow := models.SpecialMomentRequest{
        UserID:      user.ID,
        RequestedAt: now,
    }
    if err := s.DB.Create(&reqRow).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "save special request failed"})
        return
    }

    pushBody := fmt.Sprintf("Sondermoment von %s angefordert! Du hast %d Minuten Zeit.", user.Username, settings.UploadWindowMinutes)
    tokens := s.allDeviceTokens()
    sendResult, sendErr := s.Notifier.SendDailyPrompt(tokens, pushBody)
    s.recordPushResult(sendResult, sendErr)
    removed := s.removeInvalidTokens(sendResult.InvalidTokens)

    nextStatus, _ := s.specialMomentStatus(user.ID)
    c.JSON(http.StatusOK, gin.H{
        "ok":            true,
        "prompt":        prompt,
        "status":        nextStatus,
        "provider":      s.Notifier.Name(),
        "sentTo":        sendResult.Sent,
        "failed":        sendResult.Failed,
        "invalidRemoved": removed,
        "notificationErr": func() string {
            if sendErr != nil {
                return sendErr.Error()
            }
            return ""
        }(),
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
            row["triggerSource"] = prompt.TriggerSource
            row["requestedByUser"] = prompt.RequestedBy
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
        "triggerSource": prompt.TriggerSource,
        "requestedByUser": prompt.RequestedBy,
    })
}

func (s *Server) handleAdminFeed(c *gin.Context) {
    adminUser, _ := userFromContext(c)
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
            "triggerSource":   prompt.TriggerSource,
            "requestedByUser": prompt.RequestedBy,
        })
    }

    recap, _ := s.monthlyRecapForDay(day, adminUser.ID)

    c.JSON(http.StatusOK, gin.H{
        "items":           out,
        "day":             day,
        "triggeredAt":     prompt.TriggeredAt,
        "uploadUntil":     prompt.UploadUntil,
        "triggerSource":   prompt.TriggerSource,
        "requestedByUser": prompt.RequestedBy,
        "monthRecap":      recap,
    })
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
            "triggerSource":   prompt.TriggerSource,
            "requestedByUser": prompt.RequestedBy,
        })
    }

    recap, _ := s.monthlyRecapForDay(day, user.ID)

    c.JSON(http.StatusOK, gin.H{
        "items":           out,
        "day":             day,
        "triggeredAt":     prompt.TriggeredAt,
        "uploadUntil":     prompt.UploadUntil,
        "triggerSource":   prompt.TriggerSource,
        "requestedByUser": prompt.RequestedBy,
        "monthRecap":      recap,
    })
}

func (s *Server) handleGetSettings(c *gin.Context) {
    var settings models.AppSettings
    if err := s.DB.First(&settings).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "settings missing"})
        return
    }
    settings = normalizeSettings(settings)
    c.JSON(http.StatusOK, settings)
}

type settingsRequest struct {
    PromptWindowStartHour  int    `json:"promptWindowStartHour"`
    PromptWindowEndHour    int    `json:"promptWindowEndHour"`
    UploadWindowMinutes    int    `json:"uploadWindowMinutes"`
    PromptNotificationText string `json:"promptNotificationText"`
    MaxUploadBytes         int64  `json:"maxUploadBytes"`
    ChatCommandEnabled     bool   `json:"chatCommandEnabled"`
    ChatCommandValue       string `json:"chatCommandValue"`
    ChatCommandTrigger     bool   `json:"chatCommandTrigger"`
    ChatCommandSendPush    bool   `json:"chatCommandSendPush"`
    ChatCommandPushText    string `json:"chatCommandPushText"`
    ChatCommandEchoChat    bool   `json:"chatCommandEchoChat"`
    ChatCommandEchoText    string `json:"chatCommandEchoText"`
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

    req.ChatCommandValue = strings.TrimSpace(req.ChatCommandValue)
    req.ChatCommandPushText = strings.TrimSpace(req.ChatCommandPushText)
    req.ChatCommandEchoText = strings.TrimSpace(req.ChatCommandEchoText)
    if req.ChatCommandEnabled && req.ChatCommandValue == "" {
        c.JSON(http.StatusBadRequest, gin.H{"error": "chat command is empty"})
        return
    }

    settings.PromptWindowStartHour = req.PromptWindowStartHour
    settings.PromptWindowEndHour = req.PromptWindowEndHour
    settings.UploadWindowMinutes = req.UploadWindowMinutes
    settings.PromptNotificationText = req.PromptNotificationText
    settings.MaxUploadBytes = req.MaxUploadBytes
    settings.ChatCommandEnabled = req.ChatCommandEnabled
    settings.ChatCommandValue = req.ChatCommandValue
    settings.ChatCommandTrigger = req.ChatCommandTrigger
    settings.ChatCommandSendPush = req.ChatCommandSendPush
    settings.ChatCommandPushText = req.ChatCommandPushText
    settings.ChatCommandEchoChat = req.ChatCommandEchoChat
    settings.ChatCommandEchoText = req.ChatCommandEchoText
    settings = normalizeSettings(settings)

    if err := s.DB.Save(&settings).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "save failed"})
        return
    }

    c.JSON(http.StatusOK, settings)
}

func (s *Server) handleTriggerPrompt(c *gin.Context) {
    adminUser, _ := userFromContext(c)
    prompt, settings, err := s.Prompt.TriggerNowWithSource("admin_manual", &adminUser)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "trigger failed"})
        return
    }

    tokens := s.allDeviceTokens()
    sendResult, sendErr := s.Notifier.SendDailyPrompt(tokens, settings.PromptNotificationText)
    s.recordPushResult(sendResult, sendErr)
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
            Day:           day,
            TriggeredAt:   &now,
            UploadUntil:   &uploadUntil,
            TriggerSource: "admin_reset",
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
    var totalImages int64
    var runningDays int64
    var storageBytes int64

    _ = s.DB.Model(&models.User{}).Count(&users).Error
    _ = s.DB.Model(&models.Photo{}).Count(&photos).Error
    _ = s.DB.Model(&models.DeviceToken{}).Count(&devices).Error
    _ = s.DB.Model(&models.DailyPrompt{}).Count(&prompts).Error
    _ = s.DB.Raw("SELECT COALESCE(SUM(CASE WHEN second_path IS NOT NULL AND second_path <> '' THEN 2 ELSE 1 END),0) FROM photos").Scan(&totalImages).Error

    var startedAt *time.Time
    _ = s.DB.Raw(`
SELECT MIN(created_at) FROM (
    SELECT MIN(created_at) AS created_at FROM users
    UNION ALL SELECT MIN(created_at) FROM photos
    UNION ALL SELECT MIN(created_at) FROM daily_prompts
    UNION ALL SELECT MIN(created_at) FROM app_settings
) t
WHERE created_at IS NOT NULL
`).Scan(&startedAt).Error
    if startedAt != nil && !startedAt.IsZero() {
        d := int64(time.Now().In(s.Location).Sub(startedAt.In(s.Location)).Hours() / 24)
        if d < 0 {
            d = 0
        }
        runningDays = d + 1
    }

    _ = filepath.Walk(s.Config.UploadDir, func(_ string, info os.FileInfo, err error) error {
        if err != nil || info == nil || info.IsDir() {
            return nil
        }
        storageBytes += info.Size()
        return nil
    })

    c.JSON(http.StatusOK, gin.H{
        "users":       users,
        "photos":      photos,
        "devices":     devices,
        "prompts":     prompts,
        "totalImages": totalImages,
        "runningDays": runningDays,
        "storageBytes": storageBytes,
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
    s.recordPushResult(sendResult, err)
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
    s.recordPushResult(sendResult, sendErr)
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
    user, _ := userFromContext(c)
    type row struct {
        Day string
    }
    var rows []row
    if err := s.DB.Model(&models.Photo{}).Select("DISTINCT day").Order("day desc").Limit(365).Scan(&rows).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }
    today := time.Now().In(s.Location).Format("2006-01-02")
    hasPostedToday, err := s.userHasPostedForDay(user.ID, today)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }
    days := make([]string, 0, len(rows))
    for _, r := range rows {
        if r.Day == today && !hasPostedToday {
            continue
        }
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
    body := strings.TrimSpace(req.Body)
    if body == "" {
        c.JSON(http.StatusBadRequest, gin.H{"error": "message empty"})
        return
    }

    if handled, err := s.tryHandleChatCommand(c, user, body); handled || err != nil {
        return
    }

    msg := models.ChatMessage{UserID: user.ID, Body: body}
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

func (s *Server) tryHandleChatCommand(c *gin.Context, user models.User, body string) (bool, error) {
    normalized := normalizeCommandValue(body)
    if normalized == "" {
        return false, nil
    }

    var cmd models.ChatCommand
    if err := s.DB.Where("enabled = ? AND command = ?", true, normalized).First(&cmd).Error; err != nil {
        return false, nil
    }
    if cmd.RequireAdmin && !user.IsAdmin {
        c.JSON(http.StatusForbidden, gin.H{"error": "command requires admin"})
        return true, errors.New("command requires admin")
    }
    if cmd.CooldownSecond > 0 && cmd.LastUsedAt != nil {
        if time.Since(*cmd.LastUsedAt) < time.Duration(cmd.CooldownSecond)*time.Second {
            c.JSON(http.StatusTooManyRequests, gin.H{"error": "command cooldown active"})
            return true, errors.New("command cooldown")
        }
    }

    var (
        prompt         models.DailyPrompt
        sendResult     notify.SendResult
        sendErr        error
        invalidRemoved int64
        chatMessage    models.ChatMessage
        hasChatMessage bool
    )

    switch cmd.Action {
    case "trigger_moment":
        var triggerErr error
        prompt, _, triggerErr = s.Prompt.TriggerNowWithSource("chat_command", &user)
        if triggerErr != nil {
            c.JSON(http.StatusInternalServerError, gin.H{"error": "command trigger failed"})
            return true, triggerErr
        }
        if cmd.SendPush {
            pushText := renderCommandText(cmd.PushText, user.Username)
            tokens := s.allDeviceTokens()
            sendResult, sendErr = s.Notifier.SendDailyPrompt(tokens, pushText)
            s.recordPushResult(sendResult, sendErr)
            invalidRemoved = s.removeInvalidTokens(sendResult.InvalidTokens)
        }
        if cmd.PostChat {
            chatMessage = models.ChatMessage{
                UserID: user.ID,
                Body:   renderCommandText(defaultIfBlank(cmd.ResponseText, "Moment wurde von {user} angefordert."), user.Username),
            }
            if err := s.DB.Create(&chatMessage).Error; err != nil {
                c.JSON(http.StatusInternalServerError, gin.H{"error": "command chat write failed"})
                return true, err
            }
            hasChatMessage = true
        }
    case "clear_chat":
        if err := s.DB.Session(&gorm.Session{AllowGlobalUpdate: true}).Delete(&models.ChatMessage{}).Error; err != nil {
            c.JSON(http.StatusInternalServerError, gin.H{"error": "chat clear failed"})
            return true, err
        }
        if cmd.PostChat {
            chatMessage = models.ChatMessage{
                UserID: user.ID,
                Body:   renderCommandText(defaultIfBlank(cmd.ResponseText, "Chat wurde von {user} geleert."), user.Username),
            }
            if err := s.DB.Create(&chatMessage).Error; err != nil {
                c.JSON(http.StatusInternalServerError, gin.H{"error": "command chat write failed"})
                return true, err
            }
            hasChatMessage = true
        }
    case "broadcast_push":
        if cmd.SendPush {
            pushText := renderCommandText(defaultIfBlank(cmd.PushText, "{user} hat eine Nachricht gesendet."), user.Username)
            tokens := s.allDeviceTokens()
            sendResult, sendErr = s.Notifier.SendDailyPrompt(tokens, pushText)
            s.recordPushResult(sendResult, sendErr)
            invalidRemoved = s.removeInvalidTokens(sendResult.InvalidTokens)
        }
        if cmd.PostChat {
            chatMessage = models.ChatMessage{
                UserID: user.ID,
                Body:   renderCommandText(defaultIfBlank(cmd.ResponseText, "Push wurde von {user} gesendet."), user.Username),
            }
            if err := s.DB.Create(&chatMessage).Error; err != nil {
                c.JSON(http.StatusInternalServerError, gin.H{"error": "command chat write failed"})
                return true, err
            }
            hasChatMessage = true
        }
    case "send_chat_message":
        chatMessage = models.ChatMessage{
            UserID: user.ID,
            Body:   renderCommandText(defaultIfBlank(cmd.ResponseText, "Command von {user} ausgefuehrt."), user.Username),
        }
        if err := s.DB.Create(&chatMessage).Error; err != nil {
            c.JSON(http.StatusInternalServerError, gin.H{"error": "command chat write failed"})
            return true, err
        }
        hasChatMessage = true
    default:
        c.JSON(http.StatusBadRequest, gin.H{"error": "unknown command action"})
        return true, errors.New("unknown action")
    }

    now := time.Now()
    _ = s.DB.Model(&models.ChatCommand{}).Where("id = ?", cmd.ID).Update("last_used_at", &now).Error

    resp := gin.H{
        "command":        true,
        "commandId":      cmd.ID,
        "commandValue":   cmd.Command,
        "action":         cmd.Action,
        "provider":       s.Notifier.Name(),
        "sentTo":         sendResult.Sent,
        "failed":         sendResult.Failed,
        "invalidRemoved": invalidRemoved,
    }
    if hasChatMessage {
        resp["id"] = chatMessage.ID
        resp["body"] = chatMessage.Body
        resp["createdAt"] = chatMessage.CreatedAt
        resp["user"] = gin.H{
            "id":            user.ID,
            "username":      user.Username,
            "favoriteColor": defaultColor(user.FavoriteColor),
        }
    }
    if cmd.Action == "trigger_moment" {
        resp["prompt"] = prompt
        resp["triggerSource"] = "chat_command"
        resp["requestedByUser"] = user.Username
    }
    if sendErr != nil {
        resp["notificationErr"] = sendErr.Error()
    }
    c.JSON(http.StatusCreated, resp)
    return true, nil
}

func (s *Server) handleAdminListChatCommands(c *gin.Context) {
    var cmds []models.ChatCommand
    if err := s.DB.Order("created_at asc").Find(&cmds).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "query failed"})
        return
    }
    c.JSON(http.StatusOK, gin.H{"items": cmds})
}

func (s *Server) handleAdminCreateChatCommand(c *gin.Context) {
    var req models.ChatCommand
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }
    cmd, err := sanitizeChatCommand(req)
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }
    if err := s.DB.Create(&cmd).Error; err != nil {
        c.JSON(http.StatusConflict, gin.H{"error": "command exists"})
        return
    }
    c.JSON(http.StatusCreated, cmd)
}

func (s *Server) handleAdminUpdateChatCommand(c *gin.Context) {
    id, err := parseUintParam(c.Param("id"))
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid command id"})
        return
    }
    var existing models.ChatCommand
    if err := s.DB.First(&existing, id).Error; err != nil {
        c.JSON(http.StatusNotFound, gin.H{"error": "command not found"})
        return
    }
    var req models.ChatCommand
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
        return
    }
    req.ID = existing.ID
    req.CreatedAt = existing.CreatedAt
    req.LastUsedAt = existing.LastUsedAt
    cmd, err := sanitizeChatCommand(req)
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }
    if err := s.DB.Save(&cmd).Error; err != nil {
        c.JSON(http.StatusConflict, gin.H{"error": "command save failed"})
        return
    }
    c.JSON(http.StatusOK, cmd)
}

func (s *Server) handleAdminDeleteChatCommand(c *gin.Context) {
    id, err := parseUintParam(c.Param("id"))
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid command id"})
        return
    }
    if err := s.DB.Delete(&models.ChatCommand{}, id).Error; err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": "delete failed"})
        return
    }
    c.JSON(http.StatusOK, gin.H{"ok": true})
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

type monthReliableRow struct {
    UserID        uint
    Username      string
    FavoriteColor string
    Count         int64
}

type spontaneousRow struct {
    Day        string
    UserID     uint
    Username   string
    CreatedAt  time.Time
    DeltaSec   int64
}

func (s *Server) monthlyRecapForDay(day string, viewerUserID uint) (gin.H, error) {
    dayTime, err := time.ParseInLocation("2006-01-02", day, s.Location)
    if err != nil {
        return nil, nil
    }
    monthStart := time.Date(dayTime.Year(), dayTime.Month(), 1, 0, 0, 0, 0, s.Location)
    nextMonthStart := monthStart.AddDate(0, 1, 0)
    if time.Now().In(s.Location).Before(nextMonthStart) {
        return nil, nil
    }

    monthPrefix := dayTime.Format("2006-01")
    var maxPhotoDay string
    if err := s.DB.Model(&models.Photo{}).
        Where("day LIKE ?", monthPrefix+"-%").
        Select("MAX(day)").Scan(&maxPhotoDay).Error; err != nil {
        return nil, err
    }
    if maxPhotoDay == "" || maxPhotoDay != day {
        return nil, nil
    }

    monthEnd := nextMonthStart.Add(-time.Second)
    startStr := monthStart.Format("2006-01-02")
    endStr := monthEnd.Format("2006-01-02")

    var yourMoments int64
    if err := s.DB.Model(&models.Photo{}).
        Where("user_id = ? AND prompt_only = ? AND day >= ? AND day <= ?", viewerUserID, true, startStr, endStr).
        Count(&yourMoments).Error; err != nil {
        return nil, err
    }

    var reliable monthReliableRow
    _ = s.DB.Table("photos").
        Select("photos.user_id as user_id, users.username as username, users.favorite_color as favorite_color, COUNT(*) as count").
        Joins("JOIN users ON users.id = photos.user_id").
        Where("photos.prompt_only = ? AND photos.day >= ? AND photos.day <= ?", true, startStr, endStr).
        Group("photos.user_id, users.username, users.favorite_color").
        Order("count DESC, users.username ASC").
        Limit(1).
        Scan(&reliable).Error

    spontaneous := make([]spontaneousRow, 0, 5)
    _ = s.DB.Table("photos").
        Select("photos.day as day, photos.user_id as user_id, users.username as username, photos.created_at as created_at, CAST((julianday(photos.created_at)-julianday(daily_prompts.triggered_at))*86400 AS INTEGER) as delta_sec").
        Joins("JOIN users ON users.id = photos.user_id").
        Joins("JOIN daily_prompts ON daily_prompts.day = photos.day").
        Where("photos.prompt_only = ? AND photos.day >= ? AND photos.day <= ? AND daily_prompts.triggered_at IS NOT NULL", true, startStr, endStr).
        Order("delta_sec ASC, photos.created_at ASC").
        Limit(5).
        Scan(&spontaneous).Error

    fastest := make([]gin.H, 0, len(spontaneous))
    for _, row := range spontaneous {
        minutes := row.DeltaSec / 60
        if minutes < 0 {
            minutes = 0
        }
        fastest = append(fastest, gin.H{
            "day":                 row.Day,
            "userId":              row.UserID,
            "username":            row.Username,
            "minutesAfterTrigger": minutes,
            "createdAt":           row.CreatedAt,
        })
    }

    monthLabel := germanMonthLabel(monthStart)
    recap := gin.H{
        "month":      monthPrefix,
        "monthLabel": monthLabel,
        "yourMoments": yourMoments,
        "topSpontaneous": fastest,
    }
    if reliable.UserID != 0 {
        recap["mostReliableUser"] = gin.H{
            "id":            reliable.UserID,
            "username":      reliable.Username,
            "favoriteColor": defaultColor(reliable.FavoriteColor),
            "count":         reliable.Count,
        }
    }
    return recap, nil
}

func germanMonthLabel(t time.Time) string {
    names := []string{
        "Januar", "Februar", "Maerz", "April", "Mai", "Juni",
        "Juli", "August", "September", "Oktober", "November", "Dezember",
    }
    idx := int(t.Month()) - 1
    if idx < 0 || idx >= len(names) {
        return t.Format("2006-01")
    }
    return fmt.Sprintf("%s %d", names[idx], t.Year())
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

func (s *Server) recordPushResult(result notify.SendResult, err error) {
    if s.Monitor == nil {
        return
    }
    s.Monitor.RecordPush(result.Sent, result.Failed, len(result.InvalidTokens), err != nil)
}

func (s *Server) specialMomentStatus(userID uint) (gin.H, error) {
    var latest models.SpecialMomentRequest
    err := s.DB.Where("user_id = ?", userID).Order("requested_at desc").First(&latest).Error
    if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
        return nil, err
    }
    now := time.Now().In(s.Location)
    if errors.Is(err, gorm.ErrRecordNotFound) || latest.ID == 0 {
        return gin.H{
            "canRequest":       true,
            "requestedThisWeek": false,
            "remainingSeconds": 0,
            "nextAllowedAt":    nil,
            "lastRequestedAt":  nil,
        }, nil
    }

    nextAllowed := latest.RequestedAt.In(s.Location).Add(7 * 24 * time.Hour)
    remaining := int64(nextAllowed.Sub(now).Seconds())
    if remaining < 0 {
        remaining = 0
    }
    canRequest := remaining == 0
    return gin.H{
        "canRequest":        canRequest,
        "requestedThisWeek": !canRequest,
        "remainingSeconds":  remaining,
        "nextAllowedAt":     nextAllowed,
        "lastRequestedAt":   latest.RequestedAt,
    }, nil
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

func normalizeSettings(settings models.AppSettings) models.AppSettings {
    if strings.TrimSpace(settings.ChatCommandValue) == "" {
        settings.ChatCommandValue = "-moment"
    }
    if strings.TrimSpace(settings.ChatCommandPushText) == "" {
        settings.ChatCommandPushText = "{user} hat einen Moment angefordert. Jetzt 10 Minuten posten."
    }
    if strings.TrimSpace(settings.ChatCommandEchoText) == "" {
        settings.ChatCommandEchoText = "Moment wurde von {user} angefordert."
    }
    if settings.UploadWindowMinutes <= 0 {
        settings.UploadWindowMinutes = 10
    }
    return settings
}

func normalizeCommandValue(v string) string {
    out := strings.ToLower(strings.TrimSpace(v))
    if out == "" {
        return ""
    }
    if !strings.HasPrefix(out, "-") {
        out = "-" + out
    }
    return out
}

func sanitizeChatCommand(in models.ChatCommand) (models.ChatCommand, error) {
    out := in
    out.Name = strings.TrimSpace(out.Name)
    out.Command = normalizeCommandValue(out.Command)
    out.Action = strings.TrimSpace(out.Action)
    out.PushText = strings.TrimSpace(out.PushText)
    out.ResponseText = strings.TrimSpace(out.ResponseText)
    if out.CooldownSecond < 0 {
        out.CooldownSecond = 0
    }
    if out.Name == "" {
        return out, errors.New("name required")
    }
    if out.Command == "" {
        return out, errors.New("command required")
    }
    switch out.Action {
    case "trigger_moment", "clear_chat", "broadcast_push", "send_chat_message":
    default:
        return out, errors.New("invalid action")
    }
    return out, nil
}

func defaultIfBlank(v string, fallback string) string {
    x := strings.TrimSpace(v)
    if x == "" {
        return fallback
    }
    return x
}

func renderCommandText(template string, username string) string {
    t := defaultIfBlank(template, "{user}")
    return strings.ReplaceAll(t, "{user}", username)
}
