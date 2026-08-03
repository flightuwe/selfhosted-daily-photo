package config

import (
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Address                  string
	DatabasePath             string
	UploadDir                string
	JWTSecret                string
	TokenTTL                 time.Duration
	RefreshTokenTTL          time.Duration
	AllowedOrigins           []string
	PublicBaseURL            string
	AdminBaseURL             string
	Timezone                 string
	SchedulerEnabled         bool
	SchedulerLeaseTimeoutSec int
	AppVersion               string
	FCMEnabled               bool
	FCMProjectID             string
	FCMServiceAccountFile    string
	ForensicBackendLogPath   string
	ForensicGatewayLogPath   string
	MigrationReportEnabled   bool
	MigrationReportTarget    string
	MigrationReportSecret    string
	MigrationReportSource    string
	MediaRenditionsEnabled   bool
	MediaAVIFEnabled         bool
	MediaDerivativeMaxBytes  int64
	RenditionLogOffsetPath   string
}

func Load() Config {
	publicBaseURL := getEnv("PUBLIC_BASE_URL", "http://localhost:8080")
	adminBaseURL := getEnv("ADMIN_BASE_URL", "")
	allowedOrigins := withDerivedOrigins(
		splitCSV(getEnv("CORS_ORIGINS", "*")),
		publicBaseURL,
		adminBaseURL,
	)
	return Config{
		Address:                  getEnv("APP_ADDRESS", ":8080"),
		DatabasePath:             getEnv("DB_PATH", "./data/app.db"),
		UploadDir:                getEnv("UPLOAD_DIR", "./data/uploads"),
		JWTSecret:                getEnv("JWT_SECRET", "dev-secret-change-me"),
		TokenTTL:                 time.Duration(getInt("TOKEN_TTL_HOURS", 24)) * time.Hour,
		RefreshTokenTTL:          time.Duration(getInt("REFRESH_TOKEN_TTL_DAYS", 3650)) * 24 * time.Hour,
		AllowedOrigins:           allowedOrigins,
		PublicBaseURL:            publicBaseURL,
		AdminBaseURL:             adminBaseURL,
		Timezone:                 getEnv("APP_TIMEZONE", "Europe/Berlin"),
		SchedulerEnabled:         getBool("SCHEDULER_ENABLED", true),
		SchedulerLeaseTimeoutSec: getInt("SCHEDULER_LEASE_TIMEOUT_SEC", 60),
		AppVersion:               getEnv("APP_VERSION", "dev"),
		FCMEnabled:               getBool("FCM_ENABLED", false),
		FCMProjectID:             getEnv("FCM_PROJECT_ID", ""),
		FCMServiceAccountFile:    getEnv("FCM_SERVICE_ACCOUNT_FILE", ""),
		ForensicBackendLogPath:   getEnv("FORENSIC_BACKEND_LOG_PATH", "/app/logs/backend.log"),
		ForensicGatewayLogPath:   getEnv("FORENSIC_GATEWAY_LOG_PATH", "/app/gateway-logs/access.log"),
		MigrationReportEnabled:   getBool("MIGRATION_REPORT_ENABLED", false),
		MigrationReportTarget:    getEnv("MIGRATION_REPORT_TARGET", ""),
		MigrationReportSecret:    getEnv("MIGRATION_REPORT_SECRET", ""),
		MigrationReportSource:    getEnv("MIGRATION_REPORT_SOURCE", ""),
		MediaRenditionsEnabled:   getBool("MEDIA_RENDITIONS_ENABLED", true),
		MediaAVIFEnabled:         getBool("MEDIA_AVIF_ENABLED", false),
		MediaDerivativeMaxBytes:  getInt64("MEDIA_DERIVATIVE_MAX_BYTES", 5*1024*1024*1024),
		RenditionLogOffsetPath:   getEnv("RENDITION_LOG_OFFSET_PATH", "/app/data/rendition-gateway.offset"),
	}
}

func ResolveAppVersion(configured string, build string) string {
	configured = strings.TrimSpace(configured)
	build = strings.TrimSpace(build)
	if build == "" || strings.EqualFold(build, "dev") {
		if configured == "" {
			return "dev"
		}
		return configured
	}
	if isPlaceholderAppVersion(configured) {
		return build
	}
	return configured
}

func isPlaceholderAppVersion(value string) bool {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "", "dev", "unknown", "latest", "migration-prep":
		return true
	default:
		return false
	}
}

func getEnv(key, fallback string) string {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	return v
}

func splitCSV(v string) []string {
	parts := strings.Split(v, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = normalizeOriginCandidate(p)
		if p != "" {
			out = append(out, p)
		}
	}
	if len(out) == 0 {
		return []string{"*"}
	}
	return out
}

func withDerivedOrigins(origins []string, publicBaseURL string, adminBaseURL string) []string {
	if containsWildcardOrigin(origins) {
		return origins
	}
	out := append([]string{}, origins...)
	if origin := originFromBaseURL(publicBaseURL); origin != "" {
		out = appendUnique(out, origin)
	}
	if origin := originFromBaseURL(adminBaseURL); origin != "" {
		out = appendUnique(out, origin)
	}
	if len(out) == 0 {
		return []string{"*"}
	}
	return out
}

func containsWildcardOrigin(origins []string) bool {
	for _, origin := range origins {
		if strings.TrimSpace(origin) == "*" {
			return true
		}
	}
	return false
}

func appendUnique(items []string, value string) []string {
	target := normalizeOriginCandidate(value)
	if target == "" {
		return items
	}
	for _, item := range items {
		if strings.EqualFold(normalizeOriginCandidate(item), target) {
			return items
		}
	}
	return append(items, target)
}

func originFromBaseURL(raw string) string {
	clean := normalizeOriginCandidate(raw)
	if clean == "" {
		return ""
	}
	u, err := url.Parse(clean)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return ""
	}
	return u.Scheme + "://" + u.Host
}

func normalizeOriginCandidate(raw string) string {
	clean := strings.TrimSpace(raw)
	if clean == "" {
		return ""
	}
	if len(clean) >= 2 {
		if (strings.HasPrefix(clean, "\"") && strings.HasSuffix(clean, "\"")) ||
			(strings.HasPrefix(clean, "'") && strings.HasSuffix(clean, "'")) {
			clean = strings.TrimSpace(clean[1 : len(clean)-1])
		}
	}
	if clean == "*" {
		return clean
	}
	if strings.Contains(clean, "://") {
		u, err := url.Parse(clean)
		if err == nil && u.Scheme != "" && u.Host != "" {
			return u.Scheme + "://" + u.Host
		}
	}
	return strings.TrimRight(clean, "/")
}

func getInt(key string, fallback int) int {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return fallback
	}
	return n
}

func getInt64(key string, fallback int64) int64 {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n < 0 {
		return fallback
	}
	return n
}

func getBool(key string, fallback bool) bool {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		return fallback
	}
	return b
}
