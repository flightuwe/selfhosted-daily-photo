package db

import (
    "errors"
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
        &models.PromptPlan{},
        &models.Photo{},
        &models.ChatMessage{},
        &models.ChatCommand{},
        &models.SpecialMomentRequest{},
    ); err != nil {
        return nil, fmt.Errorf("automigrate: %w", err)
    }

    if err := ensureDefaultSettings(database); err != nil {
        return nil, err
    }
    if err := ensureDefaultChatCommands(database); err != nil {
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
