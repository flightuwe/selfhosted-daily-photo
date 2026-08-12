package db

import (
	"database/sql"
	"fmt"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

type distributionPreflightFixtureState int

const (
	distributionSchemaAbsent distributionPreflightFixtureState = iota
	distributionProfilesOnly
	distributionAssignmentComplete
)

type legacyDatabaseSnapshot struct {
	UsersRootPage int
	UserValues    []any
	TableCounts   map[string]int64
	UserIndexes   []string
	SchemaSQL     map[string]string
}

func createProductionLikeDistributionFixture(t *testing.T, path string, state distributionPreflightFixtureState) legacyDatabaseSnapshot {
	t.Helper()
	createLegacyDistributionTestDB(t, path)
	if state != distributionSchemaAbsent {
		fixtureDB, err := gorm.Open(sqlite.Open(sqliteDSN(path)), &gorm.Config{})
		if err != nil {
			t.Fatal(err)
		}
		if err := fixtureDB.AutoMigrate(&models.DistributionProfile{}); err != nil {
			if strings.Contains(strings.ToLower(err.Error()), "requires cgo") {
				t.Skipf("sqlite driver requires cgo in this environment: %v", err)
			}
			t.Fatal(err)
		}
		sqlDB, err := fixtureDB.DB()
		if err != nil {
			t.Fatal(err)
		}
		if err := sqlDB.Close(); err != nil {
			t.Fatal(err)
		}
	}

	database := openFixtureSQLite(t, path)
	defer database.Close()
	statements := []string{
		`CREATE TABLE legacy_user_notes (
			id INTEGER PRIMARY KEY,
			user_id INTEGER NOT NULL,
			body TEXT NOT NULL,
			CONSTRAINT fk_legacy_user_notes_user FOREIGN KEY(user_id) REFERENCES users(id) ON UPDATE CASCADE ON DELETE RESTRICT
		)`,
		`CREATE INDEX idx_legacy_user_notes_user_id ON legacy_user_notes(user_id)`,
		`INSERT INTO legacy_user_notes(id, user_id, body) VALUES (501, 41, 'preserve-note')`,
		`CREATE TABLE legacy_user_flags (
			id INTEGER PRIMARY KEY,
			user_id INTEGER NOT NULL,
			flag TEXT NOT NULL,
			CONSTRAINT fk_legacy_user_flags_user FOREIGN KEY(user_id) REFERENCES users(id) ON UPDATE CASCADE ON DELETE RESTRICT
		)`,
		`CREATE UNIQUE INDEX idx_legacy_user_flags_user_flag ON legacy_user_flags(user_id, flag)`,
		`INSERT INTO legacy_user_flags(id, user_id, flag) VALUES (601, 41, 'preserve-flag')`,
	}
	for _, statement := range statements {
		if _, err := database.Exec(statement); err != nil {
			t.Fatalf("prepare production-like fixture: %v", err)
		}
	}
	if state == distributionAssignmentComplete {
		if _, err := database.Exec(`ALTER TABLE users
			ADD COLUMN distribution_profile_id INTEGER CONSTRAINT fk_users_distribution_profile REFERENCES distribution_profiles(id)
			ON UPDATE CASCADE
			ON DELETE RESTRICT`); err != nil {
			t.Fatal(err)
		}
	}
	return captureLegacyDatabaseSnapshot(t, database)
}

func openFixtureSQLite(t *testing.T, path string) *sql.DB {
	t.Helper()
	database, err := sql.Open("sqlite3", sqliteDSN(path))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := database.Exec(`PRAGMA foreign_keys=ON`); err != nil {
		database.Close()
		if strings.Contains(strings.ToLower(err.Error()), "requires cgo") {
			t.Skipf("sqlite driver requires cgo in this environment: %v", err)
		}
		t.Fatal(err)
	}
	return database
}

func captureLegacyDatabaseSnapshot(t *testing.T, database *sql.DB) legacyDatabaseSnapshot {
	t.Helper()
	snapshot := legacyDatabaseSnapshot{TableCounts: map[string]int64{}, SchemaSQL: map[string]string{}}
	if err := database.QueryRow(`SELECT rootpage FROM sqlite_master WHERE type = 'table' AND name = 'users'`).Scan(&snapshot.UsersRootPage); err != nil {
		t.Fatal(err)
	}
	var id, authVersion int64
	var username, passwordHash, favoriteColor, createdAt string
	var isAdmin bool
	var assignment sql.NullInt64
	query := `SELECT id, username, password_hash, auth_version, is_admin, favorite_color, created_at`
	if hasSQLiteColumn(t, database, "users", "distribution_profile_id") {
		query += `, distribution_profile_id`
	} else {
		query += `, NULL`
	}
	if err := database.QueryRow(query+` FROM users WHERE id = 41`).Scan(
		&id, &username, &passwordHash, &authVersion, &isAdmin, &favoriteColor, &createdAt, &assignment,
	); err != nil {
		t.Fatal(err)
	}
	snapshot.UserValues = []any{id, username, passwordHash, authVersion, isAdmin, favoriteColor, createdAt, assignment.Valid}
	for _, table := range []string{"users", "app_settings", "legacy_user_notes", "legacy_user_flags"} {
		var count int64
		if err := database.QueryRow(fmt.Sprintf(`SELECT count(*) FROM %q`, table)).Scan(&count); err != nil {
			t.Fatal(err)
		}
		snapshot.TableCounts[table] = count
	}
	rows, err := database.Query(`SELECT name FROM pragma_index_list('users') ORDER BY name`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			t.Fatal(err)
		}
		snapshot.UserIndexes = append(snapshot.UserIndexes, name)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	schemaRows, err := database.Query(`SELECT name, sql FROM sqlite_master
		WHERE type IN ('table', 'index')
		AND tbl_name IN ('users', 'app_settings', 'legacy_user_notes', 'legacy_user_flags')
		AND NOT (type = 'table' AND name = 'users')
		AND sql IS NOT NULL
		ORDER BY name`)
	if err != nil {
		t.Fatal(err)
	}
	defer schemaRows.Close()
	for schemaRows.Next() {
		var name, ddl string
		if err := schemaRows.Scan(&name, &ddl); err != nil {
			t.Fatal(err)
		}
		snapshot.SchemaSQL[name] = ddl
	}
	if err := schemaRows.Err(); err != nil {
		t.Fatal(err)
	}
	return snapshot
}

func hasSQLiteColumn(t *testing.T, database *sql.DB, table, column string) bool {
	t.Helper()
	var count int
	if err := database.QueryRow(`SELECT count(*) FROM pragma_table_info(?) WHERE name = ?`, table, column).Scan(&count); err != nil {
		t.Fatal(err)
	}
	return count == 1
}

func assertProductionLikeDistributionMigration(t *testing.T, path string, before legacyDatabaseSnapshot) {
	t.Helper()
	database := openFixtureSQLite(t, path)
	defer database.Close()
	after := captureLegacyDatabaseSnapshot(t, database)
	if after.UsersRootPage != before.UsersRootPage {
		t.Fatalf("users rootpage changed from %d to %d; table was rebuilt", before.UsersRootPage, after.UsersRootPage)
	}
	if !reflect.DeepEqual(after.UserValues, before.UserValues) {
		t.Fatalf("existing user changed: before=%v after=%v", before.UserValues, after.UserValues)
	}
	if !reflect.DeepEqual(after.TableCounts, before.TableCounts) {
		t.Fatalf("legacy row counts changed: before=%v after=%v", before.TableCounts, after.TableCounts)
	}
	for name, beforeDDL := range before.SchemaSQL {
		if afterDDL, ok := after.SchemaSQL[name]; !ok || afterDDL != beforeDDL {
			t.Fatalf("legacy schema object %s changed: before=%q after=%q", name, beforeDDL, afterDDL)
		}
	}
	for _, beforeIndex := range before.UserIndexes {
		found := false
		for _, afterIndex := range after.UserIndexes {
			if afterIndex == beforeIndex {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("legacy users index %s was removed", beforeIndex)
		}
	}
	for table, want := range map[string][]any{
		"legacy_user_notes": {int64(501), int64(41), "preserve-note"},
		"legacy_user_flags": {int64(601), int64(41), "preserve-flag"},
	} {
		var id, userID int64
		var value string
		if err := database.QueryRow(fmt.Sprintf(`SELECT id, user_id, %s FROM %s`, map[string]string{
			"legacy_user_notes": "body", "legacy_user_flags": "flag",
		}[table], table)).Scan(&id, &userID, &value); err != nil {
			t.Fatal(err)
		}
		if got := []any{id, userID, value}; !reflect.DeepEqual(got, want) {
			t.Fatalf("%s row changed: got=%v want=%v", table, got, want)
		}
	}
	if !hasSQLiteColumn(t, database, "users", "distribution_profile_id") {
		t.Fatal("distribution_profile_id was not added")
	}
	var assigned int64
	if err := database.QueryRow(`SELECT count(*) FROM users WHERE distribution_profile_id IS NOT NULL`).Scan(&assigned); err != nil {
		t.Fatal(err)
	}
	if assigned != 0 {
		t.Fatalf("existing users were assigned during migration: %d", assigned)
	}
	assertDistributionAssignmentForeignKey(t, database)
	var usersDDL string
	if err := database.QueryRow(`SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'users'`).Scan(&usersDDL); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(strings.ToLower(usersDDL), "constraint fk_users_distribution_profile") {
		t.Fatalf("named assignment constraint missing from users DDL: %s", usersDDL)
	}
	var assignmentIndex int
	if err := database.QueryRow(`SELECT count(*) FROM pragma_index_list('users') WHERE name = 'idx_users_distribution_profile_id'`).Scan(&assignmentIndex); err != nil {
		t.Fatal(err)
	}
	if assignmentIndex != 1 {
		t.Fatalf("distribution assignment index count=%d", assignmentIndex)
	}
	var integrity string
	if err := database.QueryRow(`PRAGMA integrity_check`).Scan(&integrity); err != nil || integrity != "ok" {
		t.Fatalf("integrity_check=%q err=%v", integrity, err)
	}
	var foreignKeyViolations int
	if err := database.QueryRow(`SELECT count(*) FROM pragma_foreign_key_check`).Scan(&foreignKeyViolations); err != nil || foreignKeyViolations != 0 {
		t.Fatalf("foreign_key_check=%d err=%v", foreignKeyViolations, err)
	}
	var profiles, defaults, auditRows int64
	for query, target := range map[string]*int64{
		`SELECT count(*) FROM distribution_profiles`:                                      &profiles,
		`SELECT count(*) FROM distribution_profiles WHERE enabled = 1 AND is_default = 1`: &defaults,
		`SELECT count(*) FROM distribution_profile_audit`:                                 &auditRows,
	} {
		if err := database.QueryRow(query).Scan(target); err != nil {
			t.Fatal(err)
		}
	}
	if profiles != 1 || defaults != 1 || auditRows != 0 {
		t.Fatalf("profiles=%d defaults=%d audit=%d", profiles, defaults, auditRows)
	}
}

func assertDistributionAssignmentForeignKey(t *testing.T, database *sql.DB) {
	t.Helper()
	rows, err := database.Query(`PRAGMA foreign_key_list('users')`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var matches int
	for rows.Next() {
		var id, sequence int
		var table, from, to, onUpdate, onDelete, match string
		if err := rows.Scan(&id, &sequence, &table, &from, &to, &onUpdate, &onDelete, &match); err != nil {
			t.Fatal(err)
		}
		if from != "distribution_profile_id" {
			continue
		}
		matches++
		if table != "distribution_profiles" || to != "id" || !strings.EqualFold(onUpdate, "CASCADE") || !strings.EqualFold(onDelete, "RESTRICT") {
			t.Fatalf("unexpected assignment foreign key: table=%s to=%s update=%s delete=%s", table, to, onUpdate, onDelete)
		}
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if matches != 1 {
		t.Fatalf("assignment foreign key count=%d", matches)
	}
}

func TestDistributionAssignmentPreflightPreservesProductionLikeLegacySchema(t *testing.T) {
	tests := []struct {
		name  string
		state distributionPreflightFixtureState
	}{
		{name: "profiles and assignment absent", state: distributionSchemaAbsent},
		{name: "profiles exist and assignment absent", state: distributionProfilesOnly},
		{name: "complete assignment schema exists", state: distributionAssignmentComplete},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "production-like.db")
			before := createProductionLikeDistributionFixture(t, path, tc.state)
			for run := 1; run <= 2; run++ {
				database := connectDistributionTestDB(t, path)
				sqlDB, err := database.DB()
				if err != nil {
					t.Fatal(err)
				}
				if err := sqlDB.Close(); err != nil {
					t.Fatal(err)
				}
				assertProductionLikeDistributionMigration(t, path, before)
			}
		})
	}
}

func TestDistributionAssignmentPreflightFailsClosedOnIncompatibleExistingColumn(t *testing.T) {
	tests := []struct {
		name      string
		columnDDL string
	}{
		{name: "foreign key missing", columnDDL: `ALTER TABLE users ADD COLUMN distribution_profile_id INTEGER`},
		{name: "constraint name differs", columnDDL: `ALTER TABLE users
			ADD COLUMN distribution_profile_id INTEGER CONSTRAINT fk_users_wrong_distribution_profile REFERENCES distribution_profiles(id)
			ON UPDATE CASCADE
			ON DELETE RESTRICT`},
		{name: "foreign key actions differ", columnDDL: `ALTER TABLE users
			ADD COLUMN distribution_profile_id INTEGER CONSTRAINT fk_users_distribution_profile REFERENCES distribution_profiles(id)
			ON UPDATE NO ACTION
			ON DELETE CASCADE`},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "incompatible.db")
			before := createProductionLikeDistributionFixture(t, path, distributionProfilesOnly)
			database := openFixtureSQLite(t, path)
			if _, err := database.Exec(tc.columnDDL); err != nil {
				t.Fatal(err)
			}
			before = captureLegacyDatabaseSnapshot(t, database)
			if err := database.Close(); err != nil {
				t.Fatal(err)
			}

			connected, err := Connect(path)
			if err == nil {
				sqlDB, _ := connected.DB()
				if sqlDB != nil {
					_ = sqlDB.Close()
				}
				t.Fatal("incompatible assignment schema was accepted")
			}
			if !strings.Contains(err.Error(), "manual schema repair required before startup") {
				t.Fatalf("unexpected fail-closed error: %v", err)
			}

			afterDB := openFixtureSQLite(t, path)
			defer afterDB.Close()
			after := captureLegacyDatabaseSnapshot(t, afterDB)
			if after.UsersRootPage != before.UsersRootPage || !reflect.DeepEqual(after.UserValues, before.UserValues) ||
				!reflect.DeepEqual(after.TableCounts, before.TableCounts) || !reflect.DeepEqual(after.UserIndexes, before.UserIndexes) ||
				!reflect.DeepEqual(after.SchemaSQL, before.SchemaSQL) {
				t.Fatalf("fail-closed preflight changed legacy state: before=%+v after=%+v", before, after)
			}
			var auditTableCount int
			if err := afterDB.QueryRow(`SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'distribution_profile_audit'`).Scan(&auditTableCount); err != nil {
				t.Fatal(err)
			}
			if auditTableCount != 0 {
				t.Fatal("general AutoMigrate ran after incompatible assignment schema")
			}
		})
	}
}
