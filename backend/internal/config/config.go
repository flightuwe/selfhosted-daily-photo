package config

import (
    "os"
    "strconv"
    "time"
)

type Config struct {
    Address          string
    DatabasePath     string
    UploadDir        string
    JWTSecret        string
    TokenTTL         time.Duration
    AllowedOrigins   []string
    PublicBaseURL    string
    Timezone         string
    SchedulerEnabled bool
}

func Load() Config {
    return Config{
        Address:          getEnv("APP_ADDRESS", ":8080"),
        DatabasePath:     getEnv("DB_PATH", "./data/app.db"),
        UploadDir:        getEnv("UPLOAD_DIR", "./data/uploads"),
        JWTSecret:        getEnv("JWT_SECRET", "dev-secret-change-me"),
        TokenTTL:         time.Duration(getInt("TOKEN_TTL_HOURS", 72)) * time.Hour,
        AllowedOrigins:   splitCSV(getEnv("CORS_ORIGINS", "*")),
        PublicBaseURL:    getEnv("PUBLIC_BASE_URL", "http://localhost:8080"),
        Timezone:         getEnv("APP_TIMEZONE", "Europe/Berlin"),
        SchedulerEnabled: getBool("SCHEDULER_ENABLED", true),
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
    out := []string{}
    start := 0
    for i := 0; i < len(v); i++ {
        if v[i] == ',' {
            if start < i {
                out = append(out, v[start:i])
            }
            start = i + 1
        }
    }
    if start < len(v) {
        out = append(out, v[start:])
    }
    if len(out) == 0 {
        return []string{"*"}
    }
    return out
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
