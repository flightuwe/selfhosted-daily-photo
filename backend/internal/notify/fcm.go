package notify

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

const fcmScope = "https://www.googleapis.com/auth/firebase.messaging"

type FCMSender struct {
	projectID string
	client    *http.Client
	tokenSrc  oauth2.TokenSource
}

func NewFCMSender(projectID, serviceAccountFile string) (*FCMSender, error) {
	if projectID == "" {
		return nil, fmt.Errorf("missing FCM project id")
	}
	if serviceAccountFile == "" {
		return nil, fmt.Errorf("missing FCM service account file")
	}

	credentialsJSON, err := os.ReadFile(serviceAccountFile)
	if err != nil {
		return nil, fmt.Errorf("read FCM service account: %w", err)
	}
	jwtCfg, err := google.JWTConfigFromJSON(credentialsJSON, fcmScope)
	if err != nil {
		return nil, fmt.Errorf("parse FCM credentials: %w", err)
	}

	return &FCMSender{
		projectID: projectID,
		client: &http.Client{
			Timeout: 12 * time.Second,
		},
		tokenSrc: jwtCfg.TokenSource(context.Background()),
	}, nil
}

func (s *FCMSender) Name() string { return "fcm" }

func (s *FCMSender) SendDailyPrompt(tokens []string, body string) (SendResult, error) {
	result := SendResult{Requested: len(tokens)}
	if len(tokens) == 0 {
		return result, nil
	}

	accessToken, err := s.tokenSrc.Token()
	if err != nil {
		return result, fmt.Errorf("fcm token source: %w", err)
	}

	url := fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", s.projectID)
	var firstErr error
	for _, t := range tokens {
		payload := map[string]any{
			"message": map[string]any{
				"token": t,
				"notification": map[string]string{
					"title": "Daily Moment",
					"body":  body,
				},
				"data": map[string]string{
					"type":   "daily_prompt",
					"action": "open_camera",
					"body":   body,
				},
				"android": map[string]any{
					"priority": "HIGH",
				},
			},
		}
		raw, _ := json.Marshal(payload)
		req, _ := http.NewRequest(http.MethodPost, url, bytes.NewReader(raw))
		req.Header.Set("Authorization", "Bearer "+accessToken.AccessToken)
		req.Header.Set("Content-Type", "application/json")

		resp, err := s.client.Do(req)
		if err != nil {
			result.Failed++
			if firstErr == nil {
				firstErr = fmt.Errorf("fcm request failed: %w", err)
			}
			continue
		}
		respBody, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		if resp.StatusCode >= 300 {
			result.Failed++
			raw := string(respBody)
			if strings.Contains(raw, "UNREGISTERED") ||
				strings.Contains(raw, "registration-token-not-registered") ||
				strings.Contains(raw, "Requested entity was not found") ||
				strings.Contains(raw, "invalid registration token") {
				result.InvalidTokens = append(result.InvalidTokens, t)
			}
			if firstErr == nil {
				firstErr = fmt.Errorf("fcm response %d: %s", resp.StatusCode, raw)
			}
			continue
		}
		result.Sent++
	}
	return result, firstErr
}
