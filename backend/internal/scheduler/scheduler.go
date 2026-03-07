package scheduler

import (
    "log"
    "math/rand"
    "time"

    "github.com/yosho/selfhosted-bereal/backend/internal/models"
    "gorm.io/gorm"
)

type DailyPromptService struct {
    DB       *gorm.DB
    Location *time.Location
}

func (s *DailyPromptService) Start(enabled bool, onTrigger func(models.DailyPrompt, models.AppSettings)) {
    if !enabled {
        log.Println("scheduler disabled")
        return
    }

    go func() {
        randSrc := rand.New(rand.NewSource(time.Now().UnixNano()))
        for {
            wait := s.waitDuration(randSrc)
            time.Sleep(wait)

            prompt, settings, err := s.TriggerNow()
            if err != nil {
                log.Printf("daily trigger failed: %v", err)
                continue
            }

            if onTrigger != nil {
                onTrigger(prompt, settings)
            }
        }
    }()
}

func (s *DailyPromptService) waitDuration(r *rand.Rand) time.Duration {
    now := time.Now().In(s.Location)
    var settings models.AppSettings
    if err := s.DB.First(&settings).Error; err != nil {
        return time.Hour
    }

    target := time.Date(now.Year(), now.Month(), now.Day(), settings.PromptWindowStartHour, 0, 0, 0, s.Location)
    if now.Hour() >= settings.PromptWindowEndHour {
        target = target.Add(24 * time.Hour)
    }

    spanHours := settings.PromptWindowEndHour - settings.PromptWindowStartHour
    if spanHours <= 0 {
        spanHours = 1
    }

    target = target.Add(time.Duration(r.Intn(spanHours*60)) * time.Minute)
    if target.Before(now) {
        target = target.Add(24 * time.Hour)
    }

    return target.Sub(now)
}

func (s *DailyPromptService) TriggerNow() (models.DailyPrompt, models.AppSettings, error) {
    var settings models.AppSettings
    if err := s.DB.First(&settings).Error; err != nil {
        return models.DailyPrompt{}, settings, err
    }

    now := time.Now().In(s.Location)
    day := now.Format("2006-01-02")
    until := now.Add(time.Duration(settings.UploadWindowMinutes) * time.Minute)

    prompt := models.DailyPrompt{Day: day}
    if err := s.DB.Where("day = ?", day).FirstOrCreate(&prompt).Error; err != nil {
        return models.DailyPrompt{}, settings, err
    }

    prompt.TriggeredAt = &now
    prompt.UploadUntil = &until
    if err := s.DB.Save(&prompt).Error; err != nil {
        return models.DailyPrompt{}, settings, err
    }

    return prompt, settings, nil
}
