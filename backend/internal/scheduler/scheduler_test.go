package scheduler

import (
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/yosho/selfhosted-bereal/backend/internal/db"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

func TestPlannedAtForFutureDayUsesWholeWindow(t *testing.T) {
	loc := time.UTC
	settings := models.AppSettings{PromptWindowStartHour: 8, PromptWindowEndHour: 20}
	now := time.Date(2026, 3, 12, 13, 0, 30, 0, loc)
	dayDate := time.Date(2026, 3, 13, 0, 0, 0, 0, loc)

	for i := 0; i < 40; i++ {
		got := plannedAtForDay(dayDate, settings, now, loc)
		if got.Hour() < 8 || got.Hour() > 19 {
			t.Fatalf("plannedAtForDay() future day hour = %d, want between 8 and 19", got.Hour())
		}
	}
}

func TestPlannedAtForTodayUsesRemainingWindow(t *testing.T) {
	loc := time.UTC
	settings := models.AppSettings{PromptWindowStartHour: 8, PromptWindowEndHour: 20}
	now := time.Date(2026, 3, 12, 13, 5, 30, 0, loc)
	dayDate := time.Date(2026, 3, 12, 0, 0, 0, 0, loc)

	for i := 0; i < 40; i++ {
		got := plannedAtForDay(dayDate, settings, now, loc)
		minAllowed := time.Date(2026, 3, 12, 13, 6, 0, 0, loc)
		maxAllowed := time.Date(2026, 3, 12, 20, 0, 0, 0, loc)
		if got.Before(minAllowed) {
			t.Fatalf("plannedAtForDay() = %v, want >= %v", got, minAllowed)
		}
		if got.After(maxAllowed) {
			t.Fatalf("plannedAtForDay() = %v, want <= %v", got, maxAllowed)
		}
	}
}

func TestPlannedAtForTodayAfterWindowReturnsWindowEnd(t *testing.T) {
	loc := time.UTC
	settings := models.AppSettings{PromptWindowStartHour: 8, PromptWindowEndHour: 20}
	now := time.Date(2026, 3, 12, 20, 30, 0, 0, loc)
	dayDate := time.Date(2026, 3, 12, 0, 0, 0, 0, loc)

	got := plannedAtForDay(dayDate, settings, now, loc)
	want := time.Date(2026, 3, 12, 20, 0, 0, 0, loc)
	if !got.Equal(want) {
		t.Fatalf("plannedAtForDay() = %v, want %v", got, want)
	}
}

func TestTriggerNowWithSourceAndMeta_AllowsDailyAfterSpecial(t *testing.T) {
	svc := newTestPromptService(t)
	user := models.User{Username: "noah", PasswordHash: "x"}
	if err := svc.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}

	if _, _, err := svc.TriggerNowWithSourceAndMeta("special_request", &user, TriggerAttemptMeta{AttemptType: "special"}); err != nil {
		t.Fatalf("special trigger failed: %v", err)
	}
	if _, _, err := svc.TriggerNowWithSourceAndMeta("scheduler", nil, TriggerAttemptMeta{AttemptType: "scheduler"}); err != nil {
		t.Fatalf("daily trigger after special should be allowed, got: %v", err)
	}

	day := time.Now().In(svc.Location).Format("2006-01-02")
	specialTriggered, err := svc.hasTriggeredKindForDay(day, "special")
	if err != nil {
		t.Fatalf("special trigger check failed: %v", err)
	}
	if !specialTriggered {
		t.Fatal("expected special trigger event for today")
	}
	dailyTriggered, err := svc.hasTriggeredKindForDay(day, "daily")
	if err != nil {
		t.Fatalf("daily trigger check failed: %v", err)
	}
	if !dailyTriggered {
		t.Fatal("expected daily trigger event for today")
	}
}

func TestTriggerNowWithSourceAndMeta_BlocksSecondDailyAfterSpecialOverwrite(t *testing.T) {
	svc := newTestPromptService(t)
	user := models.User{Username: "noah", PasswordHash: "x"}
	if err := svc.DB.Create(&user).Error; err != nil {
		t.Fatalf("create user: %v", err)
	}

	if _, _, err := svc.TriggerNowWithSourceAndMeta("scheduler", nil, TriggerAttemptMeta{AttemptType: "scheduler"}); err != nil {
		t.Fatalf("first daily trigger failed: %v", err)
	}
	if _, _, err := svc.TriggerNowWithSourceAndMeta("special_request", &user, TriggerAttemptMeta{AttemptType: "special"}); err != nil {
		t.Fatalf("special trigger after daily failed: %v", err)
	}
	_, _, err := svc.TriggerNowWithSourceAndMeta("scheduler", nil, TriggerAttemptMeta{AttemptType: "scheduler"})
	if !errors.Is(err, ErrAlreadyTriggeredToday) {
		t.Fatalf("second daily trigger should be blocked with ErrAlreadyTriggeredToday, got: %v", err)
	}
}

func newTestPromptService(t *testing.T) *DailyPromptService {
	t.Helper()
	dbPath := filepath.Join(t.TempDir(), "app.db")
	database, err := db.Connect(dbPath)
	if err != nil {
		t.Fatalf("db connect failed: %v", err)
	}
	return &DailyPromptService{
		DB:             database,
		Location:       time.UTC,
		ServerInstance: "test-instance",
	}
}
