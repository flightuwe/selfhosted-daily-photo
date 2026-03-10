package notify

import "log"

type SendResult struct {
    Requested     int
    Sent          int
    Failed        int
    InvalidTokens []string
}

type Message struct {
    Title  string
    Body   string
    Type   string
    Action string
}

type Sender interface {
    Send(tokens []string, message Message) (SendResult, error)
    SendDailyPrompt(tokens []string, body string) (SendResult, error)
    Name() string
}

type NoopSender struct{}

func NewNoop() *NoopSender { return &NoopSender{} }

func (n *NoopSender) Send(tokens []string, message Message) (SendResult, error) {
    log.Printf("noop notify: %d tokens, type=%q title=%q body=%q", len(tokens), message.Type, message.Title, message.Body)
    return SendResult{
        Requested: len(tokens),
        Sent:      len(tokens),
    }, nil
}

func (n *NoopSender) SendDailyPrompt(tokens []string, body string) (SendResult, error) {
    return n.Send(tokens, Message{
        Title:  "Daily Moment",
        Body:   body,
        Type:   "daily_prompt",
        Action: "open_camera",
    })
}

func (n *NoopSender) Name() string { return "noop" }
