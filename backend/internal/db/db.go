package db

import (
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
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
		&models.UserSession{},
		&models.AppSettings{},
		&models.MigrationUserStatus{},
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
		&models.PhotoBookmark{},
		&models.PhotoReaction{},
		&models.PhotoFotomoji{},
		&models.UserFotomojiTemplate{},
		&models.UserFotomojiTemplateVersion{},
		&models.PhotoComment{},
		&models.ChatMessage{},
		&models.ChatPollOption{},
		&models.ChatPollVote{},
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
	if err := ensureFotomojiTemplateVersionBackfill(database); err != nil {
		return nil, err
	}
	if err := ensurePhotoSearchIndex(database); err != nil {
		return nil, err
	}
	if err := ensurePhotoPublicNumbers(database); err != nil {
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

func ensurePhotoSearchIndex(database *gorm.DB) error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS photo_search_docs (
			photo_id INTEGER PRIMARY KEY,
			day TEXT NOT NULL,
			user_id INTEGER NOT NULL,
			caption TEXT NOT NULL DEFAULT '',
			comments TEXT NOT NULL DEFAULT '',
			hashtags TEXT NOT NULL DEFAULT '',
			body TEXT NOT NULL DEFAULT ''
		);`,
		`CREATE TABLE IF NOT EXISTS photo_search_terms (
			term TEXT NOT NULL,
			photo_id INTEGER NOT NULL,
			PRIMARY KEY (term, photo_id)
		);`,
		`CREATE INDEX IF NOT EXISTS idx_photo_search_terms_photo_id ON photo_search_terms(photo_id);`,
		`CREATE INDEX IF NOT EXISTS idx_photo_search_docs_day ON photo_search_docs(day);`,
	}
	for _, stmt := range stmts {
		if err := database.Exec(stmt).Error; err != nil {
			return err
		}
	}
	// Best effort: some sqlite builds ship without FTS5. The app search falls back to the token table either way.
	_ = database.Exec(`
CREATE VIRTUAL TABLE IF NOT EXISTS photo_search USING fts5(
    photo_id UNINDEXED,
    day UNINDEXED,
    user_id UNINDEXED,
    caption,
    comments,
    hashtags,
    body,
    tokenize = "unicode61 remove_diacritics 2 tokenchars '#_'"
);`).Error
	return nil
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
		PromptWindowStartHour:            8,
		PromptWindowEndHour:              20,
		UploadWindowMinutes:              10,
		PromptNotificationText:           "Zeit fuer dein Daily Foto",
		MaxUploadBytes:                   0,
		ChatCommandEnabled:               false,
		ChatCommandValue:                 "-moment",
		ChatCommandTrigger:               true,
		ChatCommandSendPush:              true,
		ChatCommandPushText:              "Sondermoment von {user}! Jetzt 10 Minuten posten.",
		ChatCommandEchoChat:              true,
		ChatCommandEchoText:              "Sondermoment wurde von {user} angefordert.",
		PerformanceTrackingEnabled:       false,
		PerformanceTrackingWindowMinutes: 30,
		PerformanceTrackingOneShot:       false,
		UserPromptRulesJSON:              `[{"id":"diagnostics_consent_v1","enabled":true,"triggerType":"app_version","title":"Diagnose & Performance teilen?","body":"Wenn du zustimmst, sendet die App bei Problemen und Ladezeiten technische Diagnosedaten. Das hilft uns, Fehler und Engpaesse schneller zu finden. Du kannst das jederzeit im Profil widerrufen.","confirmLabel":"Zustimmen","declineLabel":"Nicht teilen","cooldownHours":0,"priority":10}]`,
		MigrationAutoOffEnabled:          true,
		MigrationPushTitle:               "Daily umgezogen",
		MigrationPushBody:                "Bitte aktualisiere Daily und verbinde dich mit dem neuen Server.",
		MigrationScreenTitle:             "Daily ist umgezogen",
		MigrationScreenBody:              "Diese Instanz ist im Migrationsmodus. Bitte installiere die aktuelle App-Version und trage den neuen Server ein.",
		MigrationRequirePromptFirst:      true,
		MigrationReportEnabled:           false,
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
			PushText:     "Sondermoment von {user}! Jetzt 10 Minuten posten.",
			ResponseText: "Sondermoment wurde von {user} angefordert.",
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

func ensureFotomojiTemplateVersionBackfill(database *gorm.DB) error {
	var templates []models.UserFotomojiTemplate
	if err := database.
		Where("active_version_id = ? OR active_version_id IS NULL", 0).
		Find(&templates).Error; err != nil {
		return err
	}
	for _, tpl := range templates {
		path := strings.TrimSpace(tpl.FilePath)
		if path == "" {
			continue
		}
		createdAt := tpl.UpdatedAt
		if createdAt.IsZero() {
			createdAt = tpl.CreatedAt
		}
		if createdAt.IsZero() {
			createdAt = time.Now().UTC()
		}
		version := models.UserFotomojiTemplateVersion{
			UserID:    tpl.UserID,
			Emoji:     tpl.Emoji,
			FilePath:  path,
			CreatedAt: createdAt,
		}
		if err := database.Create(&version).Error; err != nil {
			return err
		}
		if err := database.Model(&models.UserFotomojiTemplate{}).
			Where("id = ?", tpl.ID).
			Update("active_version_id", version.ID).Error; err != nil {
			return err
		}
	}
	return nil
}

func ensurePhotoPublicNumbers(database *gorm.DB) error {
	var photos []models.Photo
	if err := database.Order("day asc, created_at asc, id asc").Find(&photos).Error; err != nil {
		return err
	}
	sequenceByDay := map[string]int{}
	for _, photo := range photos {
		day := strings.TrimSpace(photo.Day)
		if day == "" {
			continue
		}
		if number := strings.TrimSpace(photo.PublicNumber); number != "" {
			if seq, ok := parsePublicPhotoSequence(day, number); ok && seq > sequenceByDay[day] {
				sequenceByDay[day] = seq
			}
			continue
		}
		sequenceByDay[day]++
		number := formatPublicPhotoNumber(day, sequenceByDay[day])
		if err := database.Model(&models.Photo{}).Where("id = ?", photo.ID).Update("public_number", number).Error; err != nil {
			return err
		}
	}
	return nil
}

func formatPublicPhotoNumber(day string, seq int) string {
	parsed, err := time.Parse("2006-01-02", strings.TrimSpace(day))
	if err != nil {
		return ""
	}
	if seq < 1 {
		seq = 1
	}
	return fmt.Sprintf("%02d%02d%02d%03d", parsed.Year()%100, int(parsed.Month()), parsed.Day(), seq)
}

func parsePublicPhotoSequence(day string, publicNumber string) (int, bool) {
	prefix := formatPublicPhotoNumber(day, 0)
	if len(prefix) != 9 {
		return 0, false
	}
	prefix = prefix[:6]
	number := strings.TrimSpace(publicNumber)
	if len(number) != 9 || !strings.HasPrefix(number, prefix) {
		return 0, false
	}
	seq, err := strconv.Atoi(number[6:])
	if err != nil || seq < 1 {
		return 0, false
	}
	return seq, true
}
