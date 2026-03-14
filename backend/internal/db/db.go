package db

import (
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

func Connect(path string) (*gorm.DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, fmt.Errorf("create db dir: %w", err)
	}

	database, err := gorm.Open(sqlite.Open(path), &gorm.Config{})
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}

	if err := database.AutoMigrate(
		&models.User{},
		&models.InviteCode{},
		&models.DeviceToken{},
		&models.AppSettings{},
		&models.SchedulerLease{},
		&models.DailyDispatch{},
		&models.DailyPrompt{},
		&models.PromptPlan{},
		&models.DailyUserActivity{},
		&models.APIMinuteMetric{},
		&models.SystemMinuteMetric{},
		&models.DBQueryMinuteMetric{},
		&models.DailySpikeEvent{},
		&models.DailyTriggerAuditEvent{},
		&models.Photo{},
		&models.PhotoReaction{},
		&models.PhotoComment{},
		&models.ChatMessage{},
		&models.ChatCommand{},
		&models.SpecialMomentRequest{},
		&models.ClientDebugLog{},
		&models.UserReport{},
	); err != nil {
		return nil, fmt.Errorf("automigrate: %w", err)
	}

	if err := ensureDefaultSettings(database); err != nil {
		return nil, err
	}
	if err := configureSQLite(database); err != nil {
		return nil, err
	}
	if err := ensureDefaultChatCommands(database); err != nil {
		return nil, err
	}
	if err := ensureCapsulePrivateDisabled(database); err != nil {
		return nil, err
	}
	if err := ensureTriggerAuditRetention(database, 30); err != nil {
		return nil, err
	}
	if err := ensureDailyDispatchRetention(database, 30); err != nil {
		return nil, err
	}

	return database, nil
}

func configureSQLite(database *gorm.DB) error {
	sqlDB, err := database.DB()
	if err != nil {
		return err
	}
	if err := applySQLitePragmas(sqlDB); err != nil {
		return err
	}
	maxOpen := envInt("SQLITE_MAX_OPEN_CONNS", 1, 1, 2)
	maxIdle := envInt("SQLITE_MAX_IDLE_CONNS", 1, 1, 2)
	if maxIdle > maxOpen {
		maxIdle = maxOpen
	}
	sqlDB.SetMaxOpenConns(maxOpen)
	sqlDB.SetMaxIdleConns(maxIdle)
	sqlDB.SetConnMaxLifetime(15 * time.Minute)
	sqlDB.SetConnMaxIdleTime(5 * time.Minute)
	return nil
}

func applySQLitePragmas(sqlDB *sql.DB) error {
	pragmas := []string{
		"PRAGMA journal_mode=WAL;",
		"PRAGMA synchronous=NORMAL;",
		"PRAGMA temp_store=MEMORY;",
		"PRAGMA busy_timeout=7000;",
	}
	for _, stmt := range pragmas {
		if _, err := sqlDB.Exec(stmt); err != nil {
			return err
		}
	}
	return nil
}

func envInt(key string, fallback int, min int, max int) int {
	raw := os.Getenv(key)
	if raw == "" {
		return fallback
	}
	n, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	if n < min {
		return min
	}
	if n > max {
		return max
	}
	return n
}

func ensureDefaultSettings(database *gorm.DB) error {
	var count int64
	if err := database.Model(&models.AppSettings{}).Count(&count).Error; err != nil {
		return err
	}
	if count > 0 {
		return nil
	}

	s := models.AppSettings{
		PromptWindowStartHour:  8,
		PromptWindowEndHour:    20,
		UploadWindowMinutes:    10,
		PromptNotificationText: "Zeit fuer dein Daily Foto",
		MaxUploadBytes:         0,
		ChatCommandEnabled:     false,
		ChatCommandValue:       "-moment",
		ChatCommandTrigger:     true,
		ChatCommandSendPush:    true,
		ChatCommandPushText:    "{user} hat einen Moment angefordert. Jetzt 10 Minuten posten.",
		ChatCommandEchoChat:    true,
		ChatCommandEchoText:    "Moment wurde von {user} angefordert.",
		PerformanceTrackingEnabled: false,
		PerformanceTrackingWindowMinutes: 30,
		PerformanceTrackingOneShot: false,
		UserPromptRulesJSON:    `[{"id":"diagnostics_consent_v1","enabled":true,"triggerType":"app_version","title":"Diagnose & Performance teilen?","body":"Wenn du zustimmst, sendet die App bei Problemen und Ladezeiten technische Diagnosedaten. Das hilft uns, Fehler und Engpaesse schneller zu finden. Du kannst das jederzeit im Profil widerrufen.","confirmLabel":"Zustimmen","declineLabel":"Nicht teilen","cooldownHours":0,"priority":10}]`,
	}
	return database.Create(&s).Error
}

func ensureDefaultChatCommands(database *gorm.DB) error {
	defaults := []models.ChatCommand{
		{
			Name:         "Moment anfordern",
			Command:      "-moment",
			Action:       "trigger_moment",
			Enabled:      true,
			RequireAdmin: false,
			SendPush:     true,
			PostChat:     true,
			PushText:     "{user} hat einen Moment angefordert. Jetzt 10 Minuten posten.",
			ResponseText: "Moment wurde von {user} angefordert.",
		},
		{
			Name:         "Chat leeren",
			Command:      "-chatclear",
			Action:       "clear_chat",
			Enabled:      true,
			RequireAdmin: true,
			SendPush:     false,
			PostChat:     true,
			ResponseText: "Chat wurde von {user} geleert.",
		},
		{
			Name:         "Ping",
			Command:      "-ping",
			Action:       "send_chat_message",
			Enabled:      true,
			RequireAdmin: false,
			SendPush:     false,
			PostChat:     true,
			ResponseText: "Pong von Daily.",
		},
	}

	for _, cmd := range defaults {
		var existing models.ChatCommand
		err := database.Where("command = ?", cmd.Command).First(&existing).Error
		if err == nil {
			continue
		}
		if !errors.Is(err, gorm.ErrRecordNotFound) {
			return err
		}
		if err := database.Create(&cmd).Error; err != nil {
			return err
		}
	}
	return nil
}

func ensureCapsulePrivateDisabled(database *gorm.DB) error {
	result := database.Model(&models.Photo{}).
		Where("capsule_private = ?", true).
		Update("capsule_private", false)
	return result.Error
}

func ensureTriggerAuditRetention(database *gorm.DB, days int) error {
	if days < 1 {
		days = 30
	}
	cutoff := time.Now().AddDate(0, 0, -days)
	return database.Where("occurred_at < ?", cutoff).Delete(&models.DailyTriggerAuditEvent{}).Error
}

func ensureDailyDispatchRetention(database *gorm.DB, days int) error {
	if days < 1 {
		days = 30
	}
	cutoff := time.Now().AddDate(0, 0, -days)
	return database.Where("created_at < ?", cutoff).Delete(&models.DailyDispatch{}).Error
}
