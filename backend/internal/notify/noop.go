package notify

import "log"

type Sender interface {
    SendDailyPrompt(tokens []string, body string) error
    Name() string
}

type NoopSender struct{}

func NewNoop() *NoopSender { return &NoopSender{} }

func (n *NoopSender) SendDailyPrompt(tokens []string, body string) error {
    log.Printf("noop notify: %d tokens, body=%q", len(tokens), body)
    return nil
}

func (n *NoopSender) Name() string { return "noop" }
