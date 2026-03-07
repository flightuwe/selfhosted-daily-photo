package db

import (
    "fmt"
    "os"
    "path/filepath"

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
        &models.DeviceToken{},
        &models.AppSettings{},
        &models.DailyPrompt{},
        &models.Photo{},
    ); err != nil {
        return nil, fmt.Errorf("automigrate: %w", err)
    }

    if err := ensureDefaultSettings(database); err != nil {
        return nil, err
    }

    return database, nil
}

func ensureDefaultSettings(database *gorm.DB) error {
    var count int64
    if err := database.Model(&models.AppSettings{}).Count(&count).Error; err != nil {
        return err
    }
    if count > 0 {
        return nil
    }

    s := models.AppSettings{}
    return database.Create(&s).Error
}
