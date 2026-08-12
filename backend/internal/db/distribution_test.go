package db

import (
	"database/sql"
	"fmt"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"

	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
)

// These definitions were captured from sqlite_master after running the base
// revision 4890a27979c1570873ea52c1bfc4b2dc4864c633 against an empty database.
// Keep this fixture aligned with that revision instead of reducing the legacy
// tables: GORM makes migration decisions from the complete table definition.
const legacyUsersDDL = "CREATE TABLE `users` (`id` integer PRIMARY KEY AUTOINCREMENT,`username` text NOT NULL,`password_hash` text NOT NULL,`auth_version` integer NOT NULL DEFAULT 1,`email` text,`email_normalized` text,`email_verified_at` datetime,`pending_email` text,`pending_email_normalized` text,`pending_email_requested_at` datetime,`is_admin` numeric DEFAULT false,`favorite_color` text DEFAULT \"#1F5FBF\",`chat_push_enabled` numeric DEFAULT false,`poll_push_enabled` numeric DEFAULT false,`special_moment_push_enabled` numeric DEFAULT false,`invite_registration_push_enabled` numeric DEFAULT false,`photo_reaction_push_enabled` numeric DEFAULT false,`photo_fotomoji_push_enabled` numeric DEFAULT false,`photo_comment_push_enabled` numeric DEFAULT false,`bookmarked_photo_push_enabled` numeric DEFAULT false,`post_change_push_enabled` numeric DEFAULT false,`auto_subscribe_interacted_posts_enabled` numeric DEFAULT false,`own_post_number_in_push_enabled` numeric DEFAULT false,`post_number_in_push_enabled` numeric DEFAULT false,`yolo_mode_enabled` numeric DEFAULT false,`media_data_mode` text DEFAULT \"normal\",`media_format_preference` text DEFAULT \"auto\",`allow_photo_download` numeric DEFAULT false,`allow_community_nsfw_marking` numeric DEFAULT false,`show_nsfw_by_default` numeric DEFAULT false,`creative_post_mode` text DEFAULT \"none\",`location_feature_enabled` numeric DEFAULT false,`location_share_default_enabled` numeric DEFAULT false,`allow_community_post_promotion` numeric DEFAULT false,`community_contribution_push_enabled` numeric DEFAULT false,`avatar_path` text,`bio` text,`status_text` text,`status_emoji` text,`status_expires_at` datetime,`profile_visible` numeric DEFAULT false,`avatar_visible` numeric DEFAULT false,`bio_visible` numeric DEFAULT false,`status_visible` numeric DEFAULT false,`quiet_hours_enabled` numeric DEFAULT false,`quiet_hours_start` text DEFAULT \"22:00\",`quiet_hours_end` text DEFAULT \"07:00\",`hub_timeline_cleared_at` datetime,`hub_timeline_last_viewed_at` datetime,`diagnostics_consent_granted` numeric DEFAULT false,`diagnostics_consent_updated_at` datetime,`diagnostics_consent_source` text,`created_at` datetime)"

const legacyAppSettingsDDL = "CREATE TABLE `app_settings` (`id` integer PRIMARY KEY AUTOINCREMENT,`prompt_window_start_hour` integer DEFAULT 8,`prompt_window_end_hour` integer DEFAULT 20,`upload_window_minutes` integer DEFAULT 10,`feed_comment_preview_limit` integer DEFAULT 10,`prompt_notification_text` text DEFAULT \"Zeit fuer dein Daily Foto\",`max_upload_bytes` integer DEFAULT 0,`chat_message_max_length` integer DEFAULT 5000,`chat_message_unlimited` numeric DEFAULT false,`post_media_max_count` integer DEFAULT 6,`post_media_unlimited` numeric DEFAULT true,`chat_command_enabled` numeric DEFAULT false,`chat_command_value` text DEFAULT \"-moment\",`chat_command_trigger` numeric DEFAULT true,`chat_command_send_push` numeric DEFAULT true,`chat_command_push_text` text DEFAULT \"Sondermoment von {user}! Jetzt 10 Minuten posten.\",`chat_command_echo_chat` numeric DEFAULT true,`chat_command_echo_text` text DEFAULT \"Sondermoment wurde von {user} angefordert.\",`performance_tracking_enabled` numeric DEFAULT false,`performance_tracking_window_minutes` integer DEFAULT 30,`performance_tracking_one_shot` numeric DEFAULT false,`media_avif_disabled` numeric DEFAULT false,`media_avif_auto_paused` numeric DEFAULT false,`media_avif_auto_pause_reason` text,`media_derivative_background_paused` numeric DEFAULT false,`scheduler_auto_paused` numeric DEFAULT false,`scheduler_auto_pause_reason` text,`scheduler_auto_paused_at` datetime,`user_prompt_rules_json` text,`migration_enabled` numeric DEFAULT false,`migration_started_at` datetime,`migration_until` datetime,`migration_auto_off_enabled` numeric DEFAULT true,`migration_target_base_url` text,`migration_download_url` text,`migration_push_title` text,`migration_push_body` text,`migration_screen_title` text,`migration_screen_body` text,`migration_require_prompt_first` numeric DEFAULT true,`migration_callback_secret` text,`migration_expected_source` text,`migration_report_enabled` numeric DEFAULT false,`migration_report_target` text,`migration_report_secret` text,`migration_report_source` text,`migration_baseline_user_count` integer DEFAULT 0,`created_at` datetime,`updated_at` datetime)"

var legacyUserIndexes = []string{
	"CREATE INDEX `idx_users_email_normalized` ON `users`(`email_normalized`)",
	"CREATE INDEX `idx_users_email_verified_at` ON `users`(`email_verified_at`)",
	"CREATE INDEX `idx_users_hub_timeline_cleared_at` ON `users`(`hub_timeline_cleared_at`)",
	"CREATE INDEX `idx_users_hub_timeline_last_viewed_at` ON `users`(`hub_timeline_last_viewed_at`)",
	"CREATE INDEX `idx_users_pending_email_normalized` ON `users`(`pending_email_normalized`)",
	"CREATE INDEX `idx_users_profile_visible` ON `users`(`profile_visible`)",
	"CREATE UNIQUE INDEX `idx_users_username` ON `users`(`username`)",
	"CREATE UNIQUE INDEX idx_users_verified_email_unique\n\t\tON users(email_normalized) WHERE email_normalized <> ''",
}

var legacyUserColumns = []string{
	"id", "username", "password_hash", "auth_version", "email", "email_normalized", "email_verified_at",
	"pending_email", "pending_email_normalized", "pending_email_requested_at", "is_admin", "favorite_color",
	"chat_push_enabled", "poll_push_enabled", "special_moment_push_enabled", "invite_registration_push_enabled",
	"photo_reaction_push_enabled", "photo_fotomoji_push_enabled", "photo_comment_push_enabled",
	"bookmarked_photo_push_enabled", "post_change_push_enabled", "auto_subscribe_interacted_posts_enabled",
	"own_post_number_in_push_enabled", "post_number_in_push_enabled", "yolo_mode_enabled", "media_data_mode",
	"media_format_preference", "allow_photo_download", "allow_community_nsfw_marking", "show_nsfw_by_default",
	"creative_post_mode", "location_feature_enabled", "location_share_default_enabled", "allow_community_post_promotion",
	"community_contribution_push_enabled", "avatar_path", "bio", "status_text", "status_emoji", "status_expires_at",
	"profile_visible", "avatar_visible", "bio_visible", "status_visible", "quiet_hours_enabled", "quiet_hours_start",
	"quiet_hours_end", "hub_timeline_cleared_at", "hub_timeline_last_viewed_at", "diagnostics_consent_granted",
	"diagnostics_consent_updated_at", "diagnostics_consent_source", "created_at",
}

var legacyAppSettingsColumns = []string{
	"id", "prompt_window_start_hour", "prompt_window_end_hour", "upload_window_minutes",
	"feed_comment_preview_limit", "prompt_notification_text", "max_upload_bytes", "chat_message_max_length",
	"chat_message_unlimited", "post_media_max_count", "post_media_unlimited", "chat_command_enabled",
	"chat_command_value", "chat_command_trigger", "chat_command_send_push", "chat_command_push_text",
	"chat_command_echo_chat", "chat_command_echo_text", "performance_tracking_enabled",
	"performance_tracking_window_minutes", "performance_tracking_one_shot", "media_avif_disabled",
	"media_avif_auto_paused", "media_avif_auto_pause_reason", "media_derivative_background_paused",
	"scheduler_auto_paused", "scheduler_auto_pause_reason", "scheduler_auto_paused_at", "user_prompt_rules_json",
	"migration_enabled", "migration_started_at", "migration_until", "migration_auto_off_enabled",
	"migration_target_base_url", "migration_download_url", "migration_push_title", "migration_push_body",
	"migration_screen_title", "migration_screen_body", "migration_require_prompt_first", "migration_callback_secret",
	"migration_expected_source", "migration_report_enabled", "migration_report_target", "migration_report_secret",
	"migration_report_source", "migration_baseline_user_count", "created_at", "updated_at",
}

func createLegacyDistributionTestDB(t *testing.T, path string) {
	t.Helper()
	database, err := sql.Open("sqlite3", path)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()
	statements := append([]string{
		legacyUsersDDL,
	}, legacyUserIndexes...)
	statements = append(statements,
		`INSERT INTO users(id, username, password_hash, auth_version, is_admin, favorite_color, created_at)
			VALUES (41, 'existing-user', 'existing-password-hash', 7, 1, '#123456', '2026-08-01T10:00:00Z')`,
		legacyAppSettingsDDL,
		`INSERT INTO app_settings(id, prompt_window_start_hour, prompt_window_end_hour, upload_window_minutes)
			VALUES (1, 9, 19, 12)`,
	)
	for _, statement := range statements {
		if _, err := database.Exec(statement); err != nil {
			if strings.Contains(strings.ToLower(err.Error()), "requires cgo") {
				t.Skipf("sqlite driver requires cgo in this environment: %v", err)
			}
			t.Fatalf("prepare legacy database: %v", err)
		}
	}
}

func sqliteColumnNames(t *testing.T, database *sql.DB, table string) []string {
	t.Helper()
	rows, err := database.Query(`SELECT name FROM pragma_table_info(?) ORDER BY cid`, table)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var names []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			t.Fatal(err)
		}
		names = append(names, name)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	sort.Strings(names)
	return names
}

func assertLegacyDistributionSchema(t *testing.T, database *sql.DB) {
	t.Helper()
	wantUserColumns := append(append([]string{}, legacyUserColumns...), "distribution_profile_id")
	sort.Strings(wantUserColumns)
	wantSettingsColumns := append([]string{}, legacyAppSettingsColumns...)
	sort.Strings(wantSettingsColumns)
	if got := sqliteColumnNames(t, database, "users"); !reflect.DeepEqual(got, wantUserColumns) {
		t.Fatalf("users columns changed outside distribution extension:\n got %v\nwant %v", got, wantUserColumns)
	}
	if got := sqliteColumnNames(t, database, "app_settings"); !reflect.DeepEqual(got, wantSettingsColumns) {
		t.Fatalf("app_settings schema changed:\n got %v\nwant %v", got, wantSettingsColumns)
	}

	rows, err := database.Query(`SELECT name FROM pragma_index_list('users') ORDER BY name`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var indexes []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			t.Fatal(err)
		}
		indexes = append(indexes, name)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	wantIndexes := []string{
		"idx_users_distribution_profile_id", "idx_users_email_normalized", "idx_users_email_verified_at",
		"idx_users_hub_timeline_cleared_at", "idx_users_hub_timeline_last_viewed_at",
		"idx_users_pending_email_normalized", "idx_users_profile_visible", "idx_users_username",
		"idx_users_verified_email_unique",
	}
	if !reflect.DeepEqual(indexes, wantIndexes) {
		t.Fatalf("users indexes changed outside distribution extension:\n got %v\nwant %v", indexes, wantIndexes)
	}

	var foreignKeyViolations int
	if err := database.QueryRow(`SELECT count(*) FROM pragma_foreign_key_check`).Scan(&foreignKeyViolations); err != nil {
		t.Fatal(err)
	}
	if foreignKeyViolations != 0 {
		t.Fatalf("foreign_key_check found %d violation(s)", foreignKeyViolations)
	}
	var integrity string
	if err := database.QueryRow(`PRAGMA integrity_check`).Scan(&integrity); err != nil {
		t.Fatal(err)
	}
	if integrity != "ok" {
		t.Fatalf("integrity_check = %q, want ok", integrity)
	}
}

func connectDistributionTestDB(t *testing.T, path string) *gorm.DB {
	t.Helper()
	database, err := Connect(path)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "requires cgo") {
			t.Skipf("sqlite driver requires cgo in this environment: %v", err)
		}
		t.Fatal(err)
	}
	return database
}

func TestDistributionMigrationSeedsExactlyOneDefaultIdempotently(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "app.db")
	for run := 0; run < 2; run++ {
		database := connectDistributionTestDB(t, dbPath)
		var profiles []models.DistributionProfile
		if err := database.Order("id asc").Find(&profiles).Error; err != nil {
			t.Fatalf("list profiles run %d: %v", run+1, err)
		}
		if len(profiles) != 1 {
			t.Fatalf("profile count run %d = %d, want 1", run+1, len(profiles))
		}
		profile := profiles[0]
		if !profile.Enabled || !profile.IsDefault {
			t.Fatalf("seeded profile must be enabled default: %+v", profile)
		}
		if profile.ReleaseIndexURL != officialDistributionReleaseIndexURL {
			t.Fatalf("release index = %q", profile.ReleaseIndexURL)
		}
		if profile.ExpectedPackageName != officialDistributionPackageName {
			t.Fatalf("package name = %q", profile.ExpectedPackageName)
		}
		if profile.ExpectedSigningCertSHA256 != officialDistributionSigningCertSHA256 {
			t.Fatalf("signing fingerprint = %q", profile.ExpectedSigningCertSHA256)
		}
		sqlDB, err := database.DB()
		if err != nil {
			t.Fatal(err)
		}
		if err := sqlDB.Close(); err != nil {
			t.Fatal(err)
		}
	}
}

func TestDistributionMigrationPreservesExistingUsersAndSettingsAndIsIdempotent(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "legacy-app.db")
	createLegacyDistributionTestDB(t, dbPath)

	for run := 1; run <= 2; run++ {
		database := connectDistributionTestDB(t, dbPath)
		var user models.User
		if err := database.First(&user, 41).Error; err != nil {
			t.Fatalf("load existing user after migration %d: %v", run, err)
		}
		if user.Username != "existing-user" || user.PasswordHash != "existing-password-hash" ||
			user.AuthVersion != 7 || !user.IsAdmin || user.FavoriteColor != "#123456" {
			t.Fatalf("existing user changed after migration %d: %+v", run, user)
		}
		if user.DistributionProfileID != nil {
			t.Fatalf("existing user unexpectedly assigned after migration %d: %v", run, *user.DistributionProfileID)
		}

		var settings models.AppSettings
		if err := database.First(&settings, 1).Error; err != nil {
			t.Fatalf("load existing settings after migration %d: %v", run, err)
		}
		if settings.PromptWindowStartHour != 9 || settings.PromptWindowEndHour != 19 || settings.UploadWindowMinutes != 12 {
			t.Fatalf("existing settings changed after migration %d: %+v", run, settings)
		}

		var profiles, defaults, auditRows int64
		if err := database.Model(&models.DistributionProfile{}).Count(&profiles).Error; err != nil {
			t.Fatal(err)
		}
		if err := database.Model(&models.DistributionProfile{}).Where("is_default = ? AND enabled = ?", true, true).Count(&defaults).Error; err != nil {
			t.Fatal(err)
		}
		if err := database.Model(&models.DistributionAuditEvent{}).Count(&auditRows).Error; err != nil {
			t.Fatal(err)
		}
		if profiles != 1 || defaults != 1 || auditRows != 0 {
			t.Fatalf("migration %d counts profiles=%d defaults=%d audit=%d", run, profiles, defaults, auditRows)
		}
		sqlDB, err := database.DB()
		if err != nil {
			t.Fatal(err)
		}
		assertLegacyDistributionSchema(t, sqlDB)
		if err := database.Exec(`INSERT INTO users(username, password_hash, auth_version, created_at)
			VALUES ('existing-user', 'duplicate-password-hash', 1, '2026-08-02T10:00:00Z')`).Error; err == nil {
			t.Fatal("username unique index accepted a duplicate username")
		}
		if err := sqlDB.Close(); err != nil {
			t.Fatal(err)
		}
	}
}

func TestDistributionDefaultUniqueIndexAndTransactionalSwitch(t *testing.T) {
	database := connectDistributionTestDB(t, filepath.Join(t.TempDir(), "app.db"))
	second := models.DistributionProfile{
		Name:                "Selfhosted Beta",
		Enabled:             false,
		SourceMode:          "disabled",
		Channel:             "beta",
		ExpectedPackageName: officialDistributionPackageName,
	}
	if err := database.Create(&second).Error; err != nil {
		t.Fatal(err)
	}

	invalidDefault := models.DistributionProfile{
		Name:                "Invalid second default",
		Enabled:             false,
		IsDefault:           true,
		SourceMode:          "disabled",
		Channel:             "internal",
		ExpectedPackageName: officialDistributionPackageName,
	}
	if err := database.Create(&invalidDefault).Error; err == nil {
		t.Fatal("partial unique index accepted a second default profile")
	}

	if err := SetDefaultDistributionProfile(database, second.ID); err != nil {
		t.Fatalf("switch default: %v", err)
	}
	var defaults []models.DistributionProfile
	if err := database.Where("is_default = ?", true).Find(&defaults).Error; err != nil {
		t.Fatal(err)
	}
	if len(defaults) != 1 || defaults[0].ID != second.ID || !defaults[0].Enabled {
		t.Fatalf("unexpected defaults after switch: %+v", defaults)
	}
	if err := database.Model(&models.DistributionProfile{}).Where("id = ?", second.ID).Update("enabled", false).Error; err == nil {
		t.Fatal("database trigger allowed disabling the default profile")
	}
}

func TestDistributionDefaultSwitchRollsBackCompletelyOnFailure(t *testing.T) {
	database := connectDistributionTestDB(t, filepath.Join(t.TempDir(), "app.db"))
	var original models.DistributionProfile
	if err := database.Where("is_default = ?", true).First(&original).Error; err != nil {
		t.Fatal(err)
	}
	next := models.DistributionProfile{Name: "Rollback target", Enabled: false, SourceMode: "disabled", Channel: "stable", ExpectedPackageName: officialDistributionPackageName}
	if err := database.Create(&next).Error; err != nil {
		t.Fatal(err)
	}
	if err := database.Model(&models.DistributionProfile{}).Where("id = ?", next.ID).UpdateColumn("enabled", false).Error; err != nil {
		t.Fatal(err)
	}
	if err := database.First(&next, next.ID).Error; err != nil {
		t.Fatal(err)
	}
	if next.Enabled || next.IsDefault {
		t.Fatalf("rollback target precondition not stored: %+v", next)
	}
	trigger := fmt.Sprintf(`CREATE TRIGGER fail_distribution_default_switch
		BEFORE UPDATE OF is_default ON distribution_profiles
		WHEN NEW.id = %d AND NEW.is_default = 1
		BEGIN SELECT RAISE(ABORT, 'injected default switch failure'); END`, next.ID)
	if err := database.Exec(trigger).Error; err != nil {
		t.Fatal(err)
	}
	defer database.Exec(`DROP TRIGGER IF EXISTS fail_distribution_default_switch`)

	if err := SetDefaultDistributionProfile(database, next.ID); err == nil {
		t.Fatal("injected default switch failure was ignored")
	}
	var storedOriginal, storedNext models.DistributionProfile
	if err := database.First(&storedOriginal, original.ID).Error; err != nil {
		t.Fatal(err)
	}
	if err := database.First(&storedNext, next.ID).Error; err != nil {
		t.Fatal(err)
	}
	if !storedOriginal.IsDefault || !storedOriginal.Enabled || storedNext.IsDefault || storedNext.Enabled {
		t.Fatalf("failed switch was not rolled back: original=%+v next=%+v", storedOriginal, storedNext)
	}
	var defaults int64
	if err := database.Model(&models.DistributionProfile{}).Where("is_default = ? AND enabled = ?", true, true).Count(&defaults).Error; err != nil {
		t.Fatal(err)
	}
	if defaults != 1 {
		t.Fatalf("active default count after failed switch = %d, want 1", defaults)
	}
}

func TestDistributionProfileAssignmentUsesRestrictForeignKey(t *testing.T) {
	database := connectDistributionTestDB(t, filepath.Join(t.TempDir(), "app.db"))
	profile := models.DistributionProfile{
		Name:                "Assigned",
		Enabled:             true,
		SourceMode:          "manifest",
		Channel:             "stable",
		ReleaseIndexURL:     "https://example.org/index.json",
		ExpectedPackageName: officialDistributionPackageName,
	}
	if err := database.Create(&profile).Error; err != nil {
		t.Fatal(err)
	}
	user := models.User{
		Username:              "distribution-user",
		PasswordHash:          "test",
		AuthVersion:           1,
		DistributionProfileID: &profile.ID,
	}
	if err := database.Create(&user).Error; err != nil {
		t.Fatal(err)
	}
	if err := database.Delete(&profile).Error; err == nil {
		t.Fatal("assigned distribution profile was deleted despite RESTRICT foreign key")
	}
	var stored models.User
	if err := database.First(&stored, user.ID).Error; err != nil {
		t.Fatal(err)
	}
	if stored.DistributionProfileID == nil || *stored.DistributionProfileID != profile.ID {
		t.Fatalf("profile assignment changed after rejected delete: %+v", stored.DistributionProfileID)
	}
	unassigned := models.User{Username: "invalid-assignment-user", PasswordHash: "test", AuthVersion: 1}
	if err := database.Create(&unassigned).Error; err != nil {
		t.Fatal(err)
	}
	if err := database.Model(&models.User{}).Where("id = ?", unassigned.ID).Update("distribution_profile_id", 999999).Error; err == nil {
		t.Fatal("database foreign key accepted a nonexistent distribution profile")
	}
}
