package scheduler

import (
	crand "crypto/rand"
	"encoding/json"
	"errors"
	"log"
	"math/big"
	"os"
	"strings"
	"time"

	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type DailyPromptService struct {
	DB       *gorm.DB
	Location *time.Location
	ServerInstance string
}

var ErrAlreadyTriggeredToday = errors.New("already_triggered_today")

type TriggerAttemptMeta struct {
	RequestID    string
	AttemptType  string
	ServerInstance string
	Meta         map[string]any
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
	if err := s.DB.Where("day = ?", day).First(&existing).Error; err == nil {
		if existing.TriggeredAt != nil {
			return
		}
	} else if !errors.Is(err, gorm.ErrRecordNotFound) {
		log.Printf("scheduler day-check failed: %v", err)
		return
	}

	plan, err := s.EnsurePlanForDay(day)
	if err != nil {
		log.Printf("ensure plan failed: %v", err)
		return
	}
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err == nil {
		_, windowEnd := promptWindowBounds(time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, s.Location), settings, s.Location)
		if !now.Before(windowEnd) {
			return
		}
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
	start, end := promptWindowBounds(dayDate, settings, loc)

	if dayDate.Format("2006-01-02") == now.Format("2006-01-02") {
		remainingStart := now.In(loc).Truncate(time.Minute)
		if remainingStart.Before(now.In(loc)) {
			remainingStart = remainingStart.Add(time.Minute)
		}
		if remainingStart.After(start) {
			start = remainingStart
		}
		if !start.Before(end) {
			return end
		}
	}

	spanMinutes := int(end.Sub(start).Minutes())
	offsetMinutes := randomOffsetMinutes(spanMinutes)
	return start.Add(time.Duration(offsetMinutes) * time.Minute)
}

func promptWindowBounds(dayDate time.Time, settings models.AppSettings, loc *time.Location) (time.Time, time.Time) {
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

	start := time.Date(dayDate.Year(), dayDate.Month(), dayDate.Day(), startHour, 0, 0, 0, loc)
	end := time.Date(dayDate.Year(), dayDate.Month(), dayDate.Day(), endHour, 0, 0, 0, loc)
	return start, end
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
	return s.TriggerNowWithSourceAndMeta("scheduler", nil, TriggerAttemptMeta{
		AttemptType: "scheduler",
	})
}

func (s *DailyPromptService) TriggerNowWithSource(source string, requestedBy *models.User) (models.DailyPrompt, models.AppSettings, error) {
	return s.TriggerNowWithSourceAndMeta(source, requestedBy, TriggerAttemptMeta{})
}

func (s *DailyPromptService) TriggerNowWithSourceAndMeta(source string, requestedBy *models.User, meta TriggerAttemptMeta) (models.DailyPrompt, models.AppSettings, error) {
	var settings models.AppSettings
	if err := s.DB.First(&settings).Error; err != nil {
		return models.DailyPrompt{}, settings, err
	}

	now := time.Now().In(s.Location)
	day := now.Format("2006-01-02")
	until := now.Add(time.Duration(settings.UploadWindowMinutes) * time.Minute)

	if source == "" {
		source = "scheduler"
	}
	attemptType := strings.TrimSpace(meta.AttemptType)
	if attemptType == "" {
		switch source {
		case "scheduler":
			attemptType = "scheduler"
		case "admin_reset":
			attemptType = "reset"
		case "chat_command":
			attemptType = "chat"
		case "special_request":
			attemptType = "special"
		default:
			attemptType = "admin"
		}
	}

	serverInstance := strings.TrimSpace(meta.ServerInstance)
	if serverInstance == "" {
		serverInstance = strings.TrimSpace(s.ServerInstance)
	}
	if serverInstance == "" {
		if host, hostErr := os.Hostname(); hostErr == nil {
			serverInstance = strings.TrimSpace(host)
		}
	}
	if serverInstance == "" {
		serverInstance = "unknown"
	}

	auditEvent := models.DailyTriggerAuditEvent{
		Day:          day,
		OccurredAt:   now,
		RequestID:    strings.TrimSpace(meta.RequestID),
		Source:       source,
		AttemptType:  attemptType,
		Result:       "failed",
		Reason:       "unknown",
		ServerInstance: serverInstance,
	}
	if requestedBy != nil {
		auditEvent.ActorUserID = &requestedBy.ID
		auditEvent.ActorUsername = requestedBy.Username
	}
	if len(meta.Meta) > 0 {
		if payload, err := json.Marshal(meta.Meta); err == nil {
			auditEvent.MetaJSON = string(payload)
		}
	}

	var prompt models.DailyPrompt
	var resultErr error
	if err := s.DB.Transaction(func(tx *gorm.DB) error {
		findErr := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("day = ?", day).First(&prompt).Error
		if findErr != nil {
			if errors.Is(findErr, gorm.ErrRecordNotFound) {
				prompt = models.DailyPrompt{
					Day: day,
				}
				if createErr := tx.Create(&prompt).Error; createErr != nil {
					return createErr
				}
			} else {
				return findErr
			}
		}

		auditEvent.BeforeTriggeredAt = prompt.TriggeredAt
		auditEvent.BeforeTriggerSource = strings.TrimSpace(prompt.TriggerSource)

		if source != "admin_reset" && prompt.TriggeredAt != nil {
			auditEvent.AfterTriggeredAt = prompt.TriggeredAt
			auditEvent.AfterTriggerSource = strings.TrimSpace(prompt.TriggerSource)
			auditEvent.Result = "blocked"
			auditEvent.Reason = "already_triggered_today"
			return ErrAlreadyTriggeredToday
		}

		updates := map[string]any{
			"triggered_at":    &now,
			"upload_until":    &until,
			"trigger_source":  source,
			"updated_at":      now,
		}
		if requestedBy != nil {
			updates["requested_by_id"] = &requestedBy.ID
			updates["requested_by"] = requestedBy.Username
		} else {
			updates["requested_by_id"] = nil
			updates["requested_by"] = ""
		}

		var res *gorm.DB
		if source == "admin_reset" {
			res = tx.Model(&models.DailyPrompt{}).Where("id = ?", prompt.ID).Updates(updates)
		} else {
			// Guard against concurrent trigger races: only one update may win.
			res = tx.Model(&models.DailyPrompt{}).Where("id = ? AND triggered_at IS NULL", prompt.ID).Updates(updates)
			if res.Error == nil && res.RowsAffected == 0 {
				if loadErr := tx.Where("id = ?", prompt.ID).First(&prompt).Error; loadErr == nil {
					auditEvent.AfterTriggeredAt = prompt.TriggeredAt
					auditEvent.AfterTriggerSource = strings.TrimSpace(prompt.TriggerSource)
				}
				auditEvent.Result = "blocked"
				auditEvent.Reason = "race_lost"
				return ErrAlreadyTriggeredToday
			}
		}
		if res.Error != nil {
			return res.Error
		}
		if readErr := tx.Where("id = ?", prompt.ID).First(&prompt).Error; readErr != nil {
			return readErr
		}
		auditEvent.AfterTriggeredAt = prompt.TriggeredAt
		auditEvent.AfterTriggerSource = strings.TrimSpace(prompt.TriggerSource)
		auditEvent.Result = "triggered"
		auditEvent.Reason = "ok"
		return nil
	}); err != nil {
		resultErr = err
	}

	if resultErr != nil {
		errMsg := strings.TrimSpace(resultErr.Error())
		if errors.Is(resultErr, ErrAlreadyTriggeredToday) {
			if auditEvent.Result == "" || auditEvent.Result == "failed" {
				auditEvent.Result = "blocked"
				if auditEvent.Reason == "" || auditEvent.Reason == "unknown" {
					auditEvent.Reason = "already_triggered_today"
				}
			}
		} else if strings.Contains(strings.ToLower(errMsg), "database is locked") {
			auditEvent.Result = "failed"
			auditEvent.Reason = "db_locked"
		} else {
			auditEvent.Result = "failed"
			auditEvent.Reason = "unknown"
		}
		auditEvent.ErrorMessage = errMsg
	}
	if auditErr := s.DB.Create(&auditEvent).Error; auditErr != nil {
		log.Printf("trigger audit write failed: %v", auditErr)
	}

	if resultErr != nil {
		return models.DailyPrompt{}, settings, resultErr
	}

	return prompt, settings, nil
}
