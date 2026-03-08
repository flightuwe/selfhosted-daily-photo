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
        ticker := time.NewTicker(30 * time.Second)
        defer ticker.Stop()

        s.tick(onTrigger)
        for range ticker.C {
            s.tick(onTrigger)
        }
    }()
}

func (s *DailyPromptService) tick(onTrigger func(models.DailyPrompt, models.AppSettings)) {
    now := time.Now().In(s.Location)
    day := now.Format("2006-01-02")

    var existing models.DailyPrompt
    if err := s.DB.Where("day = ?", day).First(&existing).Error; err == nil && existing.TriggeredAt != nil {
        return
    }

    plan, err := s.EnsurePlanForDay(day)
    if err != nil {
        log.Printf("ensure plan failed: %v", err)
        return
    }
    if now.Before(plan.PlannedAt.In(s.Location)) {
        return
    }

    prompt, settings, err := s.TriggerNow()
    if err != nil {
        log.Printf("daily trigger failed: %v", err)
        return
    }
    if onTrigger != nil {
        onTrigger(prompt, settings)
    }
}

func (s *DailyPromptService) EnsurePlans(days int) ([]models.PromptPlan, error) {
    if days < 1 {
        days = 1
    }
    if days > 30 {
        days = 30
    }

    out := make([]models.PromptPlan, 0, days)
    now := time.Now().In(s.Location)
    for i := 0; i < days; i++ {
        day := now.AddDate(0, 0, i).Format("2006-01-02")
        plan, err := s.EnsurePlanForDay(day)
        if err != nil {
            return nil, err
        }
        out = append(out, plan)
    }
    return out, nil
}

func (s *DailyPromptService) EnsurePlanForDay(day string) (models.PromptPlan, error) {
    var plan models.PromptPlan
    if err := s.DB.Where("day = ?", day).First(&plan).Error; err == nil {
        return plan, nil
    }

    var settings models.AppSettings
    if err := s.DB.First(&settings).Error; err != nil {
        return models.PromptPlan{}, err
    }

    dayDate, err := time.ParseInLocation("2006-01-02", day, s.Location)
    if err != nil {
        return models.PromptPlan{}, err
    }

    spanHours := settings.PromptWindowEndHour - settings.PromptWindowStartHour
    if spanHours <= 0 {
        spanHours = 1
    }

    r := rand.New(rand.NewSource(time.Now().UnixNano()))
    start := time.Date(dayDate.Year(), dayDate.Month(), dayDate.Day(), settings.PromptWindowStartHour, 0, 0, 0, s.Location)
    planned := start.Add(time.Duration(r.Intn(spanHours*60)) * time.Minute)
    if day == time.Now().In(s.Location).Format("2006-01-02") && planned.Before(time.Now().In(s.Location)) {
        planned = time.Now().In(s.Location).Add(1 * time.Minute)
    }

    plan = models.PromptPlan{
        Day:       day,
        PlannedAt: planned,
        IsManual:  false,
    }
    if err := s.DB.Create(&plan).Error; err != nil {
        return models.PromptPlan{}, err
    }
    return plan, nil
}

func (s *DailyPromptService) SetPlanForDay(day string, plannedAt time.Time, manual bool) (models.PromptPlan, error) {
    plan, err := s.EnsurePlanForDay(day)
    if err != nil {
        return models.PromptPlan{}, err
    }
    plan.PlannedAt = plannedAt.In(s.Location)
    plan.IsManual = manual
    if err := s.DB.Save(&plan).Error; err != nil {
        return models.PromptPlan{}, err
    }
    return plan, nil
}

func (s *DailyPromptService) TriggerNow() (models.DailyPrompt, models.AppSettings, error) {
    return s.TriggerNowWithSource("scheduler", nil)
}

func (s *DailyPromptService) TriggerNowWithSource(source string, requestedBy *models.User) (models.DailyPrompt, models.AppSettings, error) {
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

    if source == "" {
        source = "scheduler"
    }
    prompt.TriggeredAt = &now
    prompt.UploadUntil = &until
    prompt.TriggerSource = source
    if requestedBy != nil {
        prompt.RequestedByID = &requestedBy.ID
        prompt.RequestedBy = requestedBy.Username
    } else {
        prompt.RequestedByID = nil
        prompt.RequestedBy = ""
    }
    if err := s.DB.Save(&prompt).Error; err != nil {
        return models.DailyPrompt{}, settings, err
    }

    return prompt, settings, nil
}
