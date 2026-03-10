package scheduler

import (
    crand "crypto/rand"
    "errors"
    "log"
    "math/big"
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

    planned := plannedAtForDay(dayDate, settings, time.Now().In(s.Location), s.Location)

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

func (s *DailyPromptService) RefreshAutoPlans(days int) error {
    if days < 1 {
        days = 1
    }
    if days > 60 {
        days = 60
    }

    var settings models.AppSettings
    if err := s.DB.First(&settings).Error; err != nil {
        return err
    }

    now := time.Now().In(s.Location)
    for i := 0; i < days; i++ {
        dayDate := now.AddDate(0, 0, i)
        day := dayDate.Format("2006-01-02")

        var prompt models.DailyPrompt
        if err := s.DB.Where("day = ?", day).First(&prompt).Error; err == nil && prompt.TriggeredAt != nil {
            continue
        }

        planned := plannedAtForDay(dayDate, settings, now, s.Location)

        var plan models.PromptPlan
        err := s.DB.Where("day = ?", day).First(&plan).Error
        if err == nil {
            if plan.IsManual {
                continue
            }
            plan.PlannedAt = planned
            plan.IsManual = false
            if saveErr := s.DB.Save(&plan).Error; saveErr != nil {
                return saveErr
            }
            continue
        }
        if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
            return err
        }

        newPlan := models.PromptPlan{
            Day:       day,
            PlannedAt: planned,
            IsManual:  false,
        }
        if createErr := s.DB.Create(&newPlan).Error; createErr != nil {
            return createErr
        }
    }
    return nil
}

func plannedAtForDay(dayDate time.Time, settings models.AppSettings, now time.Time, loc *time.Location) time.Time {
    startHour := settings.PromptWindowStartHour
    endHour := settings.PromptWindowEndHour

    if startHour < 0 || startHour > 23 {
        startHour = 8
    }
    if endHour < 1 || endHour > 24 {
        endHour = 20
    }
    if endHour <= startHour {
        endHour = startHour + 1
        if endHour > 24 {
            startHour = 23
            endHour = 24
        }
    }

    spanMinutes := (endHour - startHour) * 60
    offsetMinutes := randomOffsetMinutes(spanMinutes)

    start := time.Date(dayDate.Year(), dayDate.Month(), dayDate.Day(), startHour, 0, 0, 0, loc)
    planned := start.Add(time.Duration(offsetMinutes) * time.Minute)

    if dayDate.Format("2006-01-02") == now.Format("2006-01-02") && planned.Before(now) {
        planned = now.Add(1 * time.Minute)
    }
    return planned
}

func randomOffsetMinutes(max int) int {
    if max <= 1 {
        return 0
    }
    n, err := crand.Int(crand.Reader, big.NewInt(int64(max)))
    if err != nil {
        return int(time.Now().UnixNano() % int64(max))
    }
    return int(n.Int64())
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
