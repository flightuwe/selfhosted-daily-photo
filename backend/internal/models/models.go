package models

import "time"

type User struct {
    ID           uint      `gorm:"primaryKey" json:"id"`
    Username     string    `gorm:"uniqueIndex;size:64;not null" json:"username"`
    PasswordHash string    `gorm:"not null" json:"-"`
    IsAdmin      bool      `gorm:"default:false" json:"isAdmin"`
    FavoriteColor string   `gorm:"size:7;default:'#1F5FBF'" json:"favoriteColor"`
    CreatedAt    time.Time `json:"createdAt"`
}

type DeviceToken struct {
    ID        uint      `gorm:"primaryKey"`
    UserID    uint      `gorm:"index;not null"`
    Token     string    `gorm:"uniqueIndex;size:255;not null"`
    CreatedAt time.Time
}

type AppSettings struct {
    ID                     uint      `gorm:"primaryKey"`
    PromptWindowStartHour  int       `gorm:"default:8"`
    PromptWindowEndHour    int       `gorm:"default:20"`
    UploadWindowMinutes    int       `gorm:"default:10"`
    PromptNotificationText string    `gorm:"size:255;default:'Zeit fuer dein Daily Foto'"`
    MaxUploadBytes         int64     `gorm:"default:0"`
    CreatedAt              time.Time
    UpdatedAt              time.Time
}

type DailyPrompt struct {
    ID             uint       `gorm:"primaryKey"`
    Day            string     `gorm:"uniqueIndex;size:10;not null"`
    TriggeredAt    *time.Time
    UploadUntil    *time.Time
    NotificationID string     `gorm:"size:64"`
    CreatedAt      time.Time
    UpdatedAt      time.Time
}

type PromptPlan struct {
    ID        uint      `gorm:"primaryKey"`
    Day       string    `gorm:"uniqueIndex;size:10;not null"`
    PlannedAt time.Time `gorm:"not null"`
    IsManual  bool      `gorm:"default:false"`
    CreatedAt time.Time
    UpdatedAt time.Time
}

type Photo struct {
    ID         uint      `gorm:"primaryKey" json:"id"`
    UserID     uint      `gorm:"index;not null" json:"userId"`
    User       User      `json:"user"`
    Day        string    `gorm:"index;size:10;not null" json:"day"`
    PromptOnly bool      `gorm:"default:false" json:"promptOnly"`
    FilePath   string    `gorm:"size:255;not null" json:"filePath"`
    SecondPath string    `gorm:"size:255" json:"secondPath"`
    Caption    string    `gorm:"size:255" json:"caption"`
    CreatedAt  time.Time `json:"createdAt"`
}

type ChatMessage struct {
    ID        uint      `gorm:"primaryKey" json:"id"`
    UserID    uint      `gorm:"index;not null" json:"userId"`
    User      User      `json:"user"`
    Body      string    `gorm:"size:500;not null" json:"body"`
    CreatedAt time.Time `json:"createdAt"`
}
