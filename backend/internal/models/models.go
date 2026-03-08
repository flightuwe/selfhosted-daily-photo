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

type InviteCode struct {
    ID        uint       `gorm:"primaryKey" json:"id"`
    UserID    uint       `gorm:"index;not null" json:"userId"`
    User      User       `json:"user"`
    Code      string     `gorm:"uniqueIndex;size:24;not null" json:"code"`
    UsedByID  *uint      `gorm:"index" json:"usedById"`
    UsedAt    *time.Time `json:"usedAt"`
    Active    bool       `gorm:"default:true;index" json:"active"`
    CreatedAt time.Time  `json:"createdAt"`
    UpdatedAt time.Time  `json:"updatedAt"`
}

type DeviceToken struct {
    ID        uint      `gorm:"primaryKey"`
    UserID    uint      `gorm:"index;not null"`
    Token     string    `gorm:"uniqueIndex;size:255;not null"`
    CreatedAt time.Time
}

type AppSettings struct {
    ID                     uint      `gorm:"primaryKey" json:"id"`
    PromptWindowStartHour  int       `gorm:"default:8" json:"promptWindowStartHour"`
    PromptWindowEndHour    int       `gorm:"default:20" json:"promptWindowEndHour"`
    UploadWindowMinutes    int       `gorm:"default:10" json:"uploadWindowMinutes"`
    PromptNotificationText string    `gorm:"size:255;default:'Zeit fuer dein Daily Foto'" json:"promptNotificationText"`
    MaxUploadBytes         int64     `gorm:"default:0" json:"maxUploadBytes"`
    ChatCommandEnabled     bool      `gorm:"default:false" json:"chatCommandEnabled"`
    ChatCommandValue       string    `gorm:"size:64;default:'-moment'" json:"chatCommandValue"`
    ChatCommandTrigger     bool      `gorm:"default:true" json:"chatCommandTrigger"`
    ChatCommandSendPush    bool      `gorm:"default:true" json:"chatCommandSendPush"`
    ChatCommandPushText    string    `gorm:"size:255;default:'{user} hat einen Moment angefordert. Jetzt 10 Minuten posten.'" json:"chatCommandPushText"`
    ChatCommandEchoChat    bool      `gorm:"default:true" json:"chatCommandEchoChat"`
    ChatCommandEchoText    string    `gorm:"size:255;default:'Moment wurde von {user} angefordert.'" json:"chatCommandEchoText"`
    CreatedAt              time.Time `json:"createdAt"`
    UpdatedAt              time.Time `json:"updatedAt"`
}

type DailyPrompt struct {
    ID             uint       `gorm:"primaryKey"`
    Day            string     `gorm:"uniqueIndex;size:10;not null"`
    TriggeredAt    *time.Time
    UploadUntil    *time.Time
    TriggerSource  string     `gorm:"size:32;default:'scheduler'"`
    RequestedByID  *uint
    RequestedBy    string     `gorm:"size:64"`
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

type ChatCommand struct {
    ID             uint      `gorm:"primaryKey" json:"id"`
    Name           string    `gorm:"size:64;not null" json:"name"`
    Command        string    `gorm:"uniqueIndex;size:64;not null" json:"command"`
    Action         string    `gorm:"size:32;not null" json:"action"`
    Enabled        bool      `gorm:"default:true" json:"enabled"`
    RequireAdmin   bool      `gorm:"default:false" json:"requireAdmin"`
    SendPush       bool      `gorm:"default:false" json:"sendPush"`
    PostChat       bool      `gorm:"default:true" json:"postChat"`
    PushText       string    `gorm:"size:255" json:"pushText"`
    ResponseText   string    `gorm:"size:255" json:"responseText"`
    CooldownSecond int       `gorm:"default:0" json:"cooldownSecond"`
    LastUsedAt     *time.Time `json:"lastUsedAt"`
    CreatedAt      time.Time `json:"createdAt"`
    UpdatedAt      time.Time `json:"updatedAt"`
}

type SpecialMomentRequest struct {
    ID          uint      `gorm:"primaryKey" json:"id"`
    UserID      uint      `gorm:"index;not null" json:"userId"`
    User        User      `json:"user"`
    RequestedAt time.Time `gorm:"index;not null" json:"requestedAt"`
    CreatedAt   time.Time `json:"createdAt"`
}
