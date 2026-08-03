package main

import (
	"context"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
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
	cfg.AppVersion = config.ResolveAppVersion(cfg.AppVersion, buildVersion)
	closeLogFile := configureBackendLogging(cfg.ForensicBackendLogPath)
	defer closeLogFile()
	log.Printf("startup: version=%s public_base_url=%s scheduler_enabled=%t", cfg.AppVersion, cfg.PublicBaseURL, cfg.SchedulerEnabled)

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
	hostName, _ := os.Hostname()
	promptService := &scheduler.DailyPromptService{
		DB:             database,
		Location:       location,
		ServerInstance: hostName,
		LeaseTimeout:   time.Duration(cfg.SchedulerLeaseTimeoutSec) * time.Second,
	}
	monitor := api.NewMonitor(database, location)
	server := &api.Server{
		DB:          database,
		Config:      cfg,
		Auth:        auth.NewManager(cfg.JWTSecret, cfg.TokenTTL),
		Store:       store,
		Notifier:    notifier,
		Prompt:      promptService,
		Location:    location,
		Monitor:     monitor,
		FeedCache:   api.NewFeedDayCache(12 * time.Second),
		FeedLimiter: api.NewFeedPollLimiter(28, 30*time.Second),
	}
	if err := server.EnsureHubVersionSystemEvent(time.Now().In(location)); err != nil {
		log.Printf("hub system event bootstrap failed: %v", err)
	}
	runtimeCtx, runtimeCancel := context.WithCancel(context.Background())
	defer runtimeCancel()
	go server.RunAutoBookmarkCleanupLoop(runtimeCtx, 30*time.Minute)
	go server.RunMediaDerivativeLoop(runtimeCtx, 5*time.Second)
	if fixed, cleanupErr := server.CleanupInvalidPromptOnlyPhotosRecent(14); cleanupErr != nil {
		log.Printf("prompt cleanup failed: %v", cleanupErr)
	} else if fixed > 0 {
		log.Printf("prompt cleanup fixed invalid prompt_only rows: %d", fixed)
	}

	promptService.Start(cfg.SchedulerEnabled, func(prompt models.DailyPrompt, settings models.AppSettings) {
		server.TrackDailyPromptSpikeIfEnabled(prompt)
		created, _, reserveErr := promptService.ReserveDispatch(prompt.Day, promptService.DispatchKindDailyPromptPush(), "scheduler", "")
		if reserveErr != nil {
			log.Printf("dispatch reserve failed: %v", reserveErr)
			return
		}
		if !created {
			log.Printf("dispatch dedupe: daily prompt push already sent/reserved for day=%s", prompt.Day)
			return
		}
		var rows []models.DeviceToken
		if err := database.Find(&rows).Error; err != nil {
			log.Printf("device token query failed: %v", err)
			promptService.MarkDispatchResult(prompt.Day, promptService.DispatchKindDailyPromptPush(), "failed", 0, 0, err.Error())
			return
		}
		tokens := make([]string, 0, len(rows))
		for _, t := range rows {
			tokens = append(tokens, t.Token)
		}
		result, err := notifier.SendDailyPrompt(tokens, settings.PromptNotificationText)
		dispatchStatus := "sent"
		if err != nil {
			dispatchStatus = "failed"
		}
		promptService.MarkDispatchResult(prompt.Day, promptService.DispatchKindDailyPromptPush(), dispatchStatus, int64(result.Sent), int64(result.Failed), errorString(err))
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
	httpServer := &http.Server{
		Addr:    cfg.Address,
		Handler: r,
	}

	serverErrCh := make(chan error, 1)
	go func() {
		log.Printf("listening on %s", cfg.Address)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			serverErrCh <- err
		}
		close(serverErrCh)
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-sigCh:
		log.Printf("shutdown signal received: %s", sig.String())
	case err := <-serverErrCh:
		if err != nil {
			log.Fatalf("server run: %v", err)
		}
		return
	}
	runtimeCancel()

	if err := promptService.ReleaseLease(); err != nil {
		log.Printf("scheduler lease release on shutdown failed: %v", err)
	} else {
		log.Printf("scheduler lease released")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := httpServer.Shutdown(ctx); err != nil {
		log.Printf("http shutdown failed: %v", err)
	}
}

func errorString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
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

func configureBackendLogging(path string) func() {
	path = strings.TrimSpace(path)
	if path == "" {
		return func() {}
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		log.Printf("backend log dir create failed (%s): %v", dir, err)
		return func() {}
	}
	file, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		log.Printf("backend log open failed (%s): %v", path, err)
		return func() {}
	}
	log.SetOutput(io.MultiWriter(os.Stdout, file))
	return func() {
		_ = file.Close()
	}
}
