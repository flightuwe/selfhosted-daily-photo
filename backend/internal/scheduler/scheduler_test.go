package scheduler

import (
	"testing"
	"time"

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
