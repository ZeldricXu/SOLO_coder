package channels

import (
	"bytes"
	"context"
	"html/template"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/logging"
)

type SMSChannel struct {
	APIKey    string
	APISecret string
	BaseURL   string
}

func NewSMSChannel(apiKey, apiSecret, baseURL string) *SMSChannel {
	return &SMSChannel{
		APIKey:    apiKey,
		APISecret: apiSecret,
		BaseURL:   baseURL,
	}
}

func (c *SMSChannel) GetType() contracts.ChannelType {
	return contracts.ChannelSMS
}

func (c *SMSChannel) HealthCheck(ctx context.Context) bool {
	return c.APIKey != ""
}

func (c *SMSChannel) Send(ctx context.Context, notification *contracts.Notification, template *contracts.NotificationTemplate) error {
	tpl, err := template.New("sms_content").Parse(template.Content)
	if err != nil {
		return err
	}

	var buf bytes.Buffer
	if err := tpl.Execute(&buf, notification.Data); err != nil {
		return err
	}
	content := buf.String()

	logging.Info(ctx, "Sending SMS notification",
		zap.Strings("recipients", notification.Recipients),
		zap.String("content", content))

	return nil
}
