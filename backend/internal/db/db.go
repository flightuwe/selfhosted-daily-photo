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

	database, err := gorm.Open(sqlite.Open(sqliteDSN(path)), &gorm.Config{})
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}

	if err := database.AutoMigrate(&models.DistributionProfile{}); err != nil {
		return nil, fmt.Errorf("automigrate distribution profiles: %w", err)
	}
	if err := ensureUserDistributionProfilePreflight(database); err != nil {
		return nil, err
	}

	if err := database.AutoMigrate(
		&models.User{},
		&models.DistributionAuditEvent{},
		&models.DistributionRollout{},
		&models.DistributionClientState{},
		&models.InviteCode{},
		&models.DeviceToken{},
		&models.UserSession{},
		&models.AppSettings{},
		&models.EmailSettings{},
		&models.EmailAction{},
		&models.EmailDelivery{},
		&models.EmailRateLimit{},
		&models.NewsletterSubscription{},
		&models.UserPromptState{},
		&models.MigrationUserStatus{},
		&models.SchedulerLease{},
		&models.DailyDispatch{},
		&models.DailyPrompt{},
		&models.PromptPlan{},
		&models.DailyUserActivity{},
		&models.SyncRevision{},
		&models.APIMinuteMetric{},
		&models.SyncCapabilityMetric{},
		&models.MediaDerivative{},
		&models.MediaDeliveryMetric{},
		&models.SystemMinuteMetric{},
		&models.DBQueryMinuteMetric{},
		&models.DailySpikeEvent{},
		&models.DailyTriggerAuditEvent{},
		&models.Photo{},
		&models.PhotoAttachment{},
		&models.PhotoBookmark{},
		&models.PhotoMark{},
		&models.PhotoPaint{},
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
		&models.HubSystemEvent{},
		&models.ClientDebugLog{},
		&models.UserReport{},
	); err != nil {
		return nil, fmt.Errorf("automigrate: %w", err)
	}
	if err := verifyUserDistributionProfileSchema(database); err != nil {
		return nil, err
	}
	if err := database.Exec(`CREATE INDEX IF NOT EXISTS idx_users_distribution_profile_id
		ON users(distribution_profile_id)`).Error; err != nil {
		return nil, fmt.Errorf("create distribution assignment index: %w", err)
	}

	if err := ensureDistributionSchema(database); err != nil {
		return nil, err
	}
	if err := ensureDistributionRollout(database); err != nil {
		return nil, err
	}
	if err := ensureDefaultSettings(database); err != nil {
		return nil, err
	}
	if err := ensureEmailSchema(database); err != nil {
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
	if err := ensureCalendarQueryIndexes(database); err != nil {
		return nil, err
	}
	if err := ensurePhotoPublicNumbers(database); err != nil {
		return nil, err
	}
	if err := ensurePhotoAttachmentAuthors(database); err != nil {
		return nil, err
	}

	return database, nil
}

func ensureDistributionRollout(database *gorm.DB) error {
	var count int64
	if err := database.Model(&models.DistributionRollout{}).Where("id = ?", 1).Count(&count).Error; err != nil {
		return fmt.Errorf("inspect distribution rollout: %w", err)
	}
	if count > 0 {
		return nil
	}
	return database.Create(&models.DistributionRollout{ID: 1, Enabled: false, Revision: 1}).Error
}

type sqliteForeignKey struct {
	ID       int    `gorm:"column:id"`
	Sequence int    `gorm:"column:seq"`
	Table    string `gorm:"column:table"`
	From     string `gorm:"column:from"`
	To       string `gorm:"column:to"`
	OnUpdate string `gorm:"column:on_update"`
	OnDelete string `gorm:"column:on_delete"`
}

func ensureUserDistributionProfilePreflight(database *gorm.DB) error {
	var usersTableCount int64
	if err := database.Raw(`SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'users'`).
		Scan(&usersTableCount).Error; err != nil {
		return fmt.Errorf("inspect users table: %w", err)
	}
	if usersTableCount == 0 {
		return nil
	}

	var columnCount int64
	if err := database.Raw(`SELECT count(*) FROM pragma_table_info('users') WHERE name = 'distribution_profile_id'`).
		Scan(&columnCount).Error; err != nil {
		return fmt.Errorf("inspect users distribution assignment column: %w", err)
	}
	if columnCount > 1 {
		return fmt.Errorf("users.distribution_profile_id schema is ambiguous; manual schema repair required before startup")
	}
	if columnCount == 0 {
		if err := database.Exec(`ALTER TABLE users
			ADD COLUMN distribution_profile_id INTEGER CONSTRAINT fk_users_distribution_profile REFERENCES distribution_profiles(id)
			ON UPDATE CASCADE
			ON DELETE RESTRICT`).Error; err != nil {
			return fmt.Errorf("add users distribution assignment column: %w", err)
		}
	}

	if err := verifyUserDistributionProfileSchema(database); err != nil {
		return err
	}
	return nil
}

func verifyUserDistributionProfileSchema(database *gorm.DB) error {
	var columnCount int64
	if err := database.Raw(`SELECT count(*) FROM pragma_table_info('users') WHERE name = 'distribution_profile_id'`).
		Scan(&columnCount).Error; err != nil {
		return fmt.Errorf("verify users distribution assignment column: %w", err)
	}

	var foreignKeys []sqliteForeignKey
	if err := database.Raw(`PRAGMA foreign_key_list('users')`).Scan(&foreignKeys).Error; err != nil {
		return fmt.Errorf("verify users distribution assignment foreign key: %w", err)
	}
	matchingForeignKeys := 0
	validForeignKey := false
	for _, foreignKey := range foreignKeys {
		if !strings.EqualFold(foreignKey.From, "distribution_profile_id") {
			continue
		}
		matchingForeignKeys++
		validForeignKey = strings.EqualFold(foreignKey.Table, "distribution_profiles") &&
			strings.EqualFold(foreignKey.To, "id") &&
			strings.EqualFold(foreignKey.OnUpdate, "CASCADE") &&
			strings.EqualFold(foreignKey.OnDelete, "RESTRICT")
	}

	var usersDDL string
	if err := database.Raw(`SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'users'`).
		Scan(&usersDDL).Error; err != nil {
		return fmt.Errorf("verify users table definition: %w", err)
	}
	normalizedDDL := strings.ToLower(usersDDL)
	hasNamedConstraint := false
	for _, constraintToken := range []string{
		"constraint fk_users_distribution_profile",
		"constraint `fk_users_distribution_profile`",
		`constraint "fk_users_distribution_profile"`,
		"constraint [fk_users_distribution_profile]",
	} {
		if strings.Contains(normalizedDDL, constraintToken) {
			hasNamedConstraint = true
			break
		}
	}
	if columnCount != 1 || matchingForeignKeys != 1 || !validForeignKey || !hasNamedConstraint {
		return fmt.Errorf("users.distribution_profile_id must use named foreign key fk_users_distribution_profile referencing distribution_profiles(id) ON UPDATE CASCADE ON DELETE RESTRICT; manual schema repair required before startup (columns=%d matching_foreign_keys=%d valid_foreign_key=%t named_constraint=%t)",
			columnCount, matchingForeignKeys, validForeignKey, hasNamedConstraint)
	}
	return nil
}

func sqliteDSN(path string) string {
	separator := "?"
	if strings.Contains(path, "?") {
		separator = "&"
	}
	return path + separator + "_foreign_keys=on"
}

const (
	officialDistributionProjectURL        = "https://code.harzcloud.de/daily-harzcloud/daily"
	officialDistributionReleaseIndexURL   = "https://releases.daily.harzcloud.de/index.json"
	officialDistributionReleasePageURL    = "https://code.harzcloud.de/daily-harzcloud/daily/releases"
	officialDistributionPackageName       = "com.selfhosted.daily"
	officialDistributionSigningCertSHA256 = "72e05a43a7be5837d83c922ad3496782499547fd94a5efa431dec712df6d4138"
)

func ensureDistributionSchema(database *gorm.DB) error {
	if err := database.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_distribution_profiles_single_default
		ON distribution_profiles(is_default) WHERE is_default = 1`).Error; err != nil {
		return fmt.Errorf("create distribution default index: %w", err)
	}
	if err := database.Exec(`CREATE TRIGGER IF NOT EXISTS trg_distribution_default_enabled_insert
		BEFORE INSERT ON distribution_profiles
		WHEN NEW.is_default = 1 AND NEW.enabled != 1
		BEGIN SELECT RAISE(ABORT, 'default distribution profile must be enabled'); END`).Error; err != nil {
		return fmt.Errorf("create distribution default insert trigger: %w", err)
	}
	if err := database.Exec(`CREATE TRIGGER IF NOT EXISTS trg_distribution_default_enabled_update
		BEFORE UPDATE OF is_default, enabled ON distribution_profiles
		WHEN NEW.is_default = 1 AND NEW.enabled != 1
		BEGIN SELECT RAISE(ABORT, 'default distribution profile must be enabled'); END`).Error; err != nil {
		return fmt.Errorf("create distribution default update trigger: %w", err)
	}
	if err := database.Exec(`CREATE TRIGGER IF NOT EXISTS trg_distribution_audit_append_only_update
		BEFORE UPDATE ON distribution_profile_audit
		BEGIN SELECT RAISE(ABORT, 'distribution audit is append-only'); END`).Error; err != nil {
		return fmt.Errorf("create distribution audit update trigger: %w", err)
	}
	if err := database.Exec(`CREATE TRIGGER IF NOT EXISTS trg_distribution_audit_append_only_delete
		BEFORE DELETE ON distribution_profile_audit
		BEGIN SELECT RAISE(ABORT, 'distribution audit is append-only'); END`).Error; err != nil {
		return fmt.Errorf("create distribution audit delete trigger: %w", err)
	}
	if err := ensureDefaultDistributionProfile(database); err != nil {
		return fmt.Errorf("ensure default distribution profile: %w", err)
	}
	return nil
}

func ensureDefaultDistributionProfile(database *gorm.DB) error {
	return database.Transaction(func(tx *gorm.DB) error {
		var defaults []models.DistributionProfile
		if err := tx.Where("is_default = ?", true).Find(&defaults).Error; err != nil {
			return err
		}
		if len(defaults) > 1 {
			return fmt.Errorf("distribution invariant violated: %d default profiles", len(defaults))
		}
		if len(defaults) == 1 {
			if defaults[0].Enabled {
				return nil
			}
			return tx.Model(&models.DistributionProfile{}).
				Where("id = ?", defaults[0].ID).
				Update("enabled", true).Error
		}

		var official models.DistributionProfile
		err := tx.Where("release_index_url = ?", officialDistributionReleaseIndexURL).First(&official).Error
		if err == nil {
			return tx.Model(&models.DistributionProfile{}).
				Where("id = ?", official.ID).
				Updates(map[string]any{"enabled": true, "is_default": true}).Error
		}
		if !errors.Is(err, gorm.ErrRecordNotFound) {
			return err
		}

		official = models.DistributionProfile{
			Name:                      "Harzcloud Stable",
			Enabled:                   true,
			IsDefault:                 true,
			SourceMode:                "manifest",
			Channel:                   "stable",
			ProjectURL:                officialDistributionProjectURL,
			ReleaseIndexURL:           officialDistributionReleaseIndexURL,
			ReleasePageURL:            officialDistributionReleasePageURL,
			ExpectedPackageName:       officialDistributionPackageName,
			ExpectedSigningCertSHA256: officialDistributionSigningCertSHA256,
		}
		return tx.Create(&official).Error
	})
}

func SetDefaultDistributionProfile(database *gorm.DB, profileID uint, incrementTargetRevision bool) error {
	if profileID == 0 {
		return gorm.ErrRecordNotFound
	}
	return database.Transaction(func(tx *gorm.DB) error {
		var next models.DistributionProfile
		if err := tx.First(&next, profileID).Error; err != nil {
			return err
		}
		if err := tx.Model(&models.DistributionProfile{}).
			Where("is_default = ? AND id <> ?", true, profileID).
			Updates(map[string]any{"is_default": false, "revision": gorm.Expr("revision + 1")}).Error; err != nil {
			return err
		}
		targetUpdates := map[string]any{"enabled": true, "is_default": true}
		if incrementTargetRevision {
			targetUpdates["revision"] = gorm.Expr("revision + 1")
		}
		if err := tx.Model(&models.DistributionProfile{}).
			Where("id = ?", profileID).
			Updates(targetUpdates).Error; err != nil {
			return err
		}
		var activeDefaults int64
		if err := tx.Model(&models.DistributionProfile{}).
			Where("is_default = ? AND enabled = ?", true, true).
			Count(&activeDefaults).Error; err != nil {
			return err
		}
		if activeDefaults != 1 {
			return fmt.Errorf("distribution invariant violated: active defaults=%d", activeDefaults)
		}
		return nil
	})
}

func ensureEmailSchema(database *gorm.DB) error {
	if err := database.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_users_verified_email_unique
		ON users(email_normalized) WHERE email_normalized <> ''`).Error; err != nil {
		return err
	}
	if err := database.Model(&models.User{}).Where("auth_version = 0").Update("auth_version", 1).Error; err != nil {
		return err
	}
	var count int64
	if err := database.Model(&models.EmailSettings{}).Count(&count).Error; err != nil {
		return err
	}
	if count == 0 {
		return database.Create(&models.EmailSettings{Port: 587, TLSMode: "starttls", AuthMode: "auto", FromName: "Daily"}).Error
	}
	return nil
}

// Attachments predating community posts could only be created by the post
// owner. Preserve that fact while adding explicit attribution.
func ensurePhotoAttachmentAuthors(database *gorm.DB) error {
	return database.Exec(`UPDATE photo_attachments
		SET user_id = (SELECT user_id FROM photos WHERE photos.id = photo_attachments.photo_id)
		WHERE user_id = 0`).Error
}

func configureSQLite(database *gorm.DB) error {
	sqlDB, err := database.DB()
	if err != nil {
		return err
	}
	if err := applySQLitePragmas(sqlDB); err != nil {
		return err
	}
	// WAL permits a reader beside the short writer transactions. Two connections
	// keep a slow read-only dashboard query from serialising every API read;
	// writes remain deliberately bounded to this tiny pool.
	maxOpen := envInt("SQLITE_MAX_OPEN_CONNS", 2, 1, 2)
	maxIdle := envInt("SQLITE_MAX_IDLE_CONNS", 2, 1, 2)
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
		"PRAGMA foreign_keys=ON;",
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
	sqlDB, err := database.DB()
	if err != nil {
		return err
	}
	_, _ = sqlDB.Exec(`
CREATE VIRTUAL TABLE IF NOT EXISTS photo_search USING fts5(
    photo_id UNINDEXED,
    day UNINDEXED,
    user_id UNINDEXED,
    caption,
    comments,
    hashtags,
    body,
    tokenize = "unicode61 remove_diacritics 2 tokenchars '#_'"
);`)
	return nil
}

// ensureCalendarQueryIndexes supports the two visibility branches used by the
// complete calendar index. Keeping them explicit also makes EXPLAIN QUERY PLAN
// verifyable in the database test suite after future query changes.
func ensureCalendarQueryIndexes(database *gorm.DB) error {
	stmts := []string{
		`CREATE INDEX IF NOT EXISTS idx_photos_calendar_user_day ON photos(user_id, day DESC);`,
		`CREATE INDEX IF NOT EXISTS idx_photos_calendar_visibility_day ON photos(capsule_visible_at, day DESC, user_id);`,
	}
	for _, stmt := range stmts {
		if err := database.Exec(stmt).Error; err != nil {
			return err
		}
	}
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
		number := ""
		if photo.PublicNumber != nil {
			number = strings.TrimSpace(*photo.PublicNumber)
		}
		if number != "" {
			if seq, ok := parsePublicPhotoSequence(day, number); ok && seq > sequenceByDay[day] {
				sequenceByDay[day] = seq
			}
			continue
		}
		sequenceByDay[day]++
		number = formatPublicPhotoNumber(day, sequenceByDay[day])
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
