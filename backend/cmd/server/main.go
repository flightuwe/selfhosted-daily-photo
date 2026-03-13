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

var buildVersion = "dev"

func main() {
    cfg := config.Load()
    if (cfg.AppVersion == "" || strings.EqualFold(cfg.AppVersion, "dev")) && buildVersion != "" && !strings.EqualFold(buildVersion, "dev") {
        cfg.AppVersion = buildVersion
    }

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

    notifier := notify.Sender(notify.NewNoop())
    if cfg.FCMEnabled {
        fcmSender, fcmErr := notify.NewFCMSender(cfg.FCMProjectID, cfg.FCMServiceAccountFile)
        if fcmErr != nil {
            log.Printf("FCM init failed, fallback to noop: %v", fcmErr)
        } else {
            notifier = fcmSender
            log.Printf("notifications: provider=%s", notifier.Name())
        }
    } else {
        log.Printf("notifications: provider=%s", notifier.Name())
    }
    promptService := &scheduler.DailyPromptService{DB: database, Location: location}
	monitor := api.NewMonitor(database, location)
	server := &api.Server{
		DB:       database,
		Config:   cfg,
		Auth:     auth.NewManager(cfg.JWTSecret, cfg.TokenTTL),
		Store:    store,
		Notifier: notifier,
		Prompt:   promptService,
		Location: location,
		Monitor:  monitor,
		FeedCache:   api.NewFeedDayCache(12 * time.Second),
		FeedLimiter: api.NewFeedPollLimiter(28, 30*time.Second),
	}
    if fixed, cleanupErr := server.CleanupInvalidPromptOnlyPhotosRecent(14); cleanupErr != nil {
        log.Printf("prompt cleanup failed: %v", cleanupErr)
    } else if fixed > 0 {
        log.Printf("prompt cleanup fixed invalid prompt_only rows: %d", fixed)
    }

	promptService.Start(cfg.SchedulerEnabled, func(_ models.DailyPrompt, settings models.AppSettings) {
		now := time.Now().In(location)
		monitor.MarkDailySpike(now.Format("2006-01-02"), now, 30*time.Minute)
		var rows []models.DeviceToken
		if err := database.Find(&rows).Error; err != nil {
			log.Printf("device token query failed: %v", err)
            return
        }
        tokens := make([]string, 0, len(rows))
        for _, t := range rows {
            tokens = append(tokens, t.Token)
        }
        result, err := notifier.SendDailyPrompt(tokens, settings.PromptNotificationText)
        monitor.RecordPush(result.Sent, result.Failed, len(result.InvalidTokens), err != nil)
        if len(result.InvalidTokens) > 0 {
            if dbErr := database.Where("token IN ?", result.InvalidTokens).Delete(&models.DeviceToken{}).Error; dbErr != nil {
                log.Printf("failed to remove invalid tokens: %v", dbErr)
            }
        }
        if err != nil {
            log.Printf("notify failed: %v", err)
        }
        if result.Failed > 0 || len(result.InvalidTokens) > 0 {
            log.Printf("notify summary: requested=%d sent=%d failed=%d invalid_removed=%d", result.Requested, result.Sent, result.Failed, len(result.InvalidTokens))
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
