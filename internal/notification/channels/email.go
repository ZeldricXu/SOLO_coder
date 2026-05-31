package channels

import (
	"bytes"
	"context"
	"fmt"
	"html/template"
	"net/smtp"

	"github.com/solocoder/task-scheduler/internal/contracts"
)

type EmailChannel struct {
	SMTPHost    string
	SMTPPort    int
	Username    string
	Password    string
	FromAddress string
	Auth        smtp.Auth
}

func NewEmailChannel(host string, port int, username, password, from string) *EmailChannel {
	return &EmailChannel{
		SMTPHost:    host,
		SMTPPort:    port,
		Username:    username,
		Password:    password,
		FromAddress: from,
		Auth:        smtp.PlainAuth("", username, password, host),
	}
}

func (c *EmailChannel) GetType() contracts.ChannelType {
	return contracts.ChannelEmail
}

func (c *EmailChannel) HealthCheck(ctx context.Context) bool {
	return c.SMTPHost != "" && c.SMTPPort > 0
}

func (c *EmailChannel) Send(ctx context.Context, notification *contracts.Notification, template *contracts.NotificationTemplate) error {
	renderedContent, err := c.renderContent(template, notification.Data)
	if err != nil {
		return fmt.Errorf("template render failed: %w", err)
	}

	subject := notification.Subject
	if subject == "" && template.Subject != "" {
		subject, err = c.renderSubject(template, notification.Data)
		if err != nil {
			return fmt.Errorf("subject render failed: %w", err)
		}
	}

	addr := fmt.Sprintf("%s:%d", c.SMTPHost, c.SMTPPort)

	for _, recipient := range notification.Recipients {
		msg := fmt.Sprintf("From: %s\r\nTo: %s\r\nSubject: %s\r\nMIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n%s",
			c.FromAddress, recipient, subject, renderedContent)

		if err := smtp.SendMail(addr, c.Auth, c.FromAddress, []string{recipient}, []byte(msg)); err != nil {
			return fmt.Errorf("send email to %s failed: %w", recipient, err)
		}
	}

	return nil
}

func (c *EmailChannel) renderContent(template *contracts.NotificationTemplate, data map[string]interface{}) (string, error) {
	tpl, err := template.New("email_content").Parse(template.Content)
	if err != nil {
		return "", err
	}
	var buf bytes.Buffer
	if err := tpl.Execute(&buf, data); err != nil {
		return "", err
	}
	return buf.String(), nil
}

func (c *EmailChannel) renderSubject(template *contracts.NotificationTemplate, data map[string]interface{}) (string, error) {
	tpl, err := template.New("email_subject").Parse(template.Subject)
	if err != nil {
		return "", err
	}
	var buf bytes.Buffer
	if err := tpl.Execute(&buf, data); err != nil {
		return "", err
	}
	return buf.String(), nil
}
