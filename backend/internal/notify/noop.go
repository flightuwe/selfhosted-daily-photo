package notify

import "log"

type SendResult struct {
    Requested     int
    Sent          int
    Failed        int
    InvalidTokens []string
}

type Sender interface {
    SendDailyPrompt(tokens []string, body string) (SendResult, error)
    Name() string
}

type NoopSender struct{}

func NewNoop() *NoopSender { return &NoopSender{} }

func (n *NoopSender) SendDailyPrompt(tokens []string, body string) (SendResult, error) {
    log.Printf("noop notify: %d tokens, body=%q", len(tokens), body)
    return SendResult{
        Requested: len(tokens),
        Sent:      len(tokens),
    }, nil
}

func (n *NoopSender) Name() string { return "noop" }
