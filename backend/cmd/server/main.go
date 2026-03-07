package main

import (
    "log"
    "os"
    "strings"
    "time"

    "github.com/yosho/selfhosted-bereal/backend/internal/api"
    "github.com/yosho/selfhosted-bereal/backend/internal/auth"
    "github.com/yosho/selfhosted-bereal/backend/internal/config"
    "github.com/yosho/selfhosted-bereal/backend/internal/db"
    "github.com/yosho/selfhosted-bereal/backend/internal/models"
    "github.com/yosho/selfhosted-bereal/backend/internal/notify"
    "github.com/yosho/selfhosted-bereal/backend/internal/scheduler"
    "github.com/yosho/selfhosted-bereal/backend/internal/storage"
    "gorm.io/gorm"
)

func main() {
    cfg := config.Load()

    location, err := time.LoadLocation(cfg.Timezone)
    if err != nil {
        log.Fatalf("load timezone: %v", err)
    }

    database, err := db.Connect(cfg.DatabasePath)
    if err != nil {
        log.Fatalf("db connect: %v", err)
    }

    ensureBootstrapAdmin(database)

    store, err := storage.NewLocalStore(cfg.UploadDir)
    if err != nil {
        log.Fatalf("storage: %v", err)
    }

    notifier := notify.NewNoop()
    promptService := &scheduler.DailyPromptService{DB: database, Location: location}
    server := &api.Server{
        DB:       database,
        Config:   cfg,
        Auth:     auth.NewManager(cfg.JWTSecret, cfg.TokenTTL),
        Store:    store,
        Notifier: notifier,
        Prompt:   promptService,
        Location: location,
    }

    promptService.Start(cfg.SchedulerEnabled, func(_ models.DailyPrompt, settings models.AppSettings) {
        var rows []models.DeviceToken
        if err := database.Find(&rows).Error; err != nil {
            log.Printf("device token query failed: %v", err)
            return
        }
        tokens := make([]string, 0, len(rows))
        for _, t := range rows {
            tokens = append(tokens, t.Token)
        }
        if err := notifier.SendDailyPrompt(tokens, settings.PromptNotificationText); err != nil {
            log.Printf("notify failed: %v", err)
        }
    })

    r := server.Router()
    log.Printf("listening on %s", cfg.Address)
    if err := r.Run(cfg.Address); err != nil {
        log.Fatalf("server run: %v", err)
    }
}

func ensureBootstrapAdmin(database *gorm.DB) {
    username := strings.ToLower(os.Getenv("BOOTSTRAP_ADMIN_USER"))
    password := os.Getenv("BOOTSTRAP_ADMIN_PASSWORD")
    if username == "" || password == "" {
        return
    }

    var existing models.User
    if err := database.Where("username = ?", username).First(&existing).Error; err == nil {
        return
    }

    hash, err := auth.HashPassword(password)
    if err != nil {
        log.Printf("bootstrap admin hash failed: %v", err)
        return
    }

    admin := models.User{Username: username, PasswordHash: hash, IsAdmin: true}
    if err := database.Create(&admin).Error; err != nil {
        log.Printf("bootstrap admin create failed: %v", err)
    }
}
