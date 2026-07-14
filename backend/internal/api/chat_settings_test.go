package api

import (
	"strings"
	"testing"

	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

func TestNormalizeSettingsAppliesDefaultChatLimit(t *testing.T) {
	settings := normalizeSettings(models.AppSettings{
		ChatMessageMaxLength: 0,
	})

	if settings.ChatMessageMaxLength != 5000 {
		t.Fatalf("ChatMessageMaxLength = %d, want 5000", settings.ChatMessageMaxLength)
	}
}

func TestNormalizeSettingsPreservesUnlimitedChatSetting(t *testing.T) {
	settings := normalizeSettings(models.AppSettings{
		ChatMessageMaxLength: -99,
		ChatMessageUnlimited: true,
	})

	if !settings.ChatMessageUnlimited {
		t.Fatalf("ChatMessageUnlimited = false, want true")
	}
	if settings.ChatMessageMaxLength != 5000 {
		t.Fatalf("ChatMessageMaxLength = %d, want normalized 5000", settings.ChatMessageMaxLength)
	}
}

func TestNormalizeSettingsAppliesDefaultPostMediaLimit(t *testing.T) {
	settings := normalizeSettings(models.AppSettings{
		PostMediaMaxCount: 0,
	})

	if settings.PostMediaMaxCount != 6 {
		t.Fatalf("PostMediaMaxCount = %d, want 6", settings.PostMediaMaxCount)
	}
}

func TestNormalizeSettingsPreservesUnlimitedPostMediaSetting(t *testing.T) {
	settings := normalizeSettings(models.AppSettings{
		PostMediaMaxCount:  -42,
		PostMediaUnlimited: true,
	})

	if !settings.PostMediaUnlimited {
		t.Fatalf("PostMediaUnlimited = false, want true")
	}
	if settings.PostMediaMaxCount != 6 {
		t.Fatalf("PostMediaMaxCount = %d, want normalized 6", settings.PostMediaMaxCount)
	}
}

func TestNormalizeSettingsMigratesLegacyPostMediaDefaultToUnlimited(t *testing.T) {
	settings := normalizeSettings(models.AppSettings{})

	if !settings.PostMediaUnlimited {
		t.Fatalf("PostMediaUnlimited = false, want true for legacy zero-value settings")
	}
	if settings.PostMediaMaxCount != 6 {
		t.Fatalf("PostMediaMaxCount = %d, want normalized 6", settings.PostMediaMaxCount)
	}
}

func TestChatMessageLengthUsesRuneCount(t *testing.T) {
	body := strings.Repeat("ä", 5001)
	if got := len([]rune(body)); got != 5001 {
		t.Fatalf("len([]rune(body)) = %d, want 5001", got)
	}
}
