package email

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net/mail"
	"strings"
	"time"

	gomail "github.com/wneessen/go-mail"
)

type SMTPConfig struct {
	Host, TLSMode, AuthMode, Username, Password string
	Port                                        int
}

type Message struct {
	FromName, FromAddress, ReplyTo, To, Subject, Text, HTML string
}

type Sender interface {
	Check(context.Context, SMTPConfig) error
	Send(context.Context, SMTPConfig, Message) error
}

type GoMailSender struct{}

func (GoMailSender) Check(ctx context.Context, cfg SMTPConfig) error {
	client, err := smtpClient(cfg)
	if err != nil {
		return err
	}
	if err := client.DialWithContext(ctx); err != nil {
		return err
	}
	return client.Close()
}

func (GoMailSender) Send(ctx context.Context, cfg SMTPConfig, message Message) error {
	client, err := smtpClient(cfg)
	if err != nil {
		return err
	}
	msg := gomail.NewMsg()
	if err := msg.FromFormat(message.FromName, message.FromAddress); err != nil {
		return err
	}
	if err := msg.To(message.To); err != nil {
		return err
	}
	if strings.TrimSpace(message.ReplyTo) != "" {
		if err := msg.ReplyTo(message.ReplyTo); err != nil {
			return err
		}
	}
	msg.Subject(message.Subject)
	msg.SetBodyString(gomail.TypeTextPlain, message.Text)
	msg.AddAlternativeString(gomail.TypeTextHTML, message.HTML)
	msg.SetDate()
	msg.SetMessageID()
	msg.SetGenHeader("Auto-Submitted", "auto-generated")
	msg.SetGenHeader("X-Auto-Response-Suppress", "All")
	return client.DialAndSendWithContext(ctx, msg)
}

func smtpClient(cfg SMTPConfig) (*gomail.Client, error) {
	host := strings.TrimSpace(cfg.Host)
	if host == "" || cfg.Port < 1 || cfg.Port > 65535 {
		return nil, errors.New("invalid SMTP host or port")
	}
	mode := strings.ToLower(strings.TrimSpace(cfg.TLSMode))
	if mode != "starttls" && mode != "implicit" {
		return nil, errors.New("only STARTTLS or implicit TLS is allowed")
	}
	opts := []gomail.Option{
		gomail.WithPort(cfg.Port),
		gomail.WithTimeout(15 * time.Second),
		gomail.WithTLSConfig(&tls.Config{ServerName: host, MinVersion: tls.VersionTLS12}),
		gomail.WithUsername(cfg.Username),
		gomail.WithPassword(cfg.Password),
	}
	if mode == "implicit" {
		opts = append(opts, gomail.WithSSL())
	} else {
		opts = append(opts, gomail.WithTLSPolicy(gomail.TLSMandatory))
	}
	switch strings.ToLower(strings.TrimSpace(cfg.AuthMode)) {
	case "", "auto":
		opts = append(opts, gomail.WithSMTPAuth(gomail.SMTPAuthAutoDiscover))
	case "plain":
		opts = append(opts, gomail.WithSMTPAuth(gomail.SMTPAuthPlain))
	case "login":
		opts = append(opts, gomail.WithSMTPAuth(gomail.SMTPAuthLogin))
	default:
		return nil, errors.New("unsupported SMTP authentication mode")
	}
	return gomail.NewClient(host, opts...)
}

func NormalizeAddress(value string) (display, normalized string, err error) {
	value = strings.TrimSpace(value)
	if value == "" || strings.ContainsAny(value, "\r\n") || len(value) > 254 {
		return "", "", errors.New("invalid email address")
	}
	parsed, err := mail.ParseAddress(value)
	if err != nil || parsed.Name != "" || !strings.EqualFold(parsed.Address, value) {
		return "", "", errors.New("invalid email address")
	}
	parts := strings.Split(parsed.Address, "@")
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return "", "", errors.New("invalid email address")
	}
	normalized = strings.ToLower(parsed.Address)
	return parsed.Address, normalized, nil
}

func SanitizeError(err error) string {
	if err == nil {
		return ""
	}
	text := strings.ToLower(err.Error())
	switch {
	case strings.Contains(text, "auth"), strings.Contains(text, "535"):
		return "SMTP authentication failed"
	case strings.Contains(text, "tls"), strings.Contains(text, "certificate"):
		return "TLS negotiation or certificate validation failed"
	case strings.Contains(text, "timeout"), strings.Contains(text, "deadline"):
		return "SMTP connection timed out"
	case strings.Contains(text, "lookup"), strings.Contains(text, "no such host"):
		return "SMTP host could not be resolved"
	default:
		return fmt.Sprintf("SMTP operation failed (%T)", err)
	}
}
