package channels

import (
	"context"
	"encoding/json"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/logging"
)

type WebhookChannel struct {
	WebhookURL string
	Secret     string
}

func NewWebhookChannel(url, secret string) *WebhookChannel {
	return &WebhookChannel{
		WebhookURL: url,
		Secret:     secret,
	}
}

func (c *WebhookChannel) GetType() contracts.ChannelType {
	return contracts.ChannelWebhook
}

func (c *WebhookChannel) HealthCheck(ctx context.Context) bool {
	return c.WebhookURL != ""
}

func (c *WebhookChannel) Send(ctx context.Context, notification *contracts.Notification, template *contracts.NotificationTemplate) error {
	payload := map[string]interface{}{
		"notification_id": notification.ID,
		"template_id":     notification.TemplateID,
		"severity":        notification.Severity,
		"recipients":      notification.Recipients,
		"data":            notification.Data,
		"timestamp":       time.Now(),
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	logging.Info(ctx, "Sending webhook notification",
		zap.String("url", c.WebhookURL),
		zap.String("payload", string(body)))

	return nil
}
