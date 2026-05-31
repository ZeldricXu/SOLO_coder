package channels

import (
	"bytes"
	"context"
	"encoding/json"
	"html/template"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/logging"
)

type SlackChannel struct {
	WebhookURL string
	Token      string
}

func NewSlackChannel(webhookURL, token string) *SlackChannel {
	return &SlackChannel{
		WebhookURL: webhookURL,
		Token:      token,
	}
}

func (c *SlackChannel) GetType() contracts.ChannelType {
	return contracts.ChannelSlack
}

func (c *SlackChannel) HealthCheck(ctx context.Context) bool {
	return c.WebhookURL != "" || c.Token != ""
}

func (c *SlackChannel) Send(ctx context.Context, notification *contracts.Notification, template *contracts.NotificationTemplate) error {
	tpl, err := template.New("slack_content").Parse(template.Content)
	if err != nil {
		return err
	}

	var buf bytes.Buffer
	if err := tpl.Execute(&buf, notification.Data); err != nil {
		return err
	}

	color := "#36a64f"
	switch notification.Severity {
	case contracts.SeverityWarning:
		color = "#ff9900"
	case contracts.SeverityError:
		color = "#ff0000"
	case contracts.SeverityCritical:
		color = "#990000"
	}

	payload := map[string]interface{}{
		"attachments": []map[string]interface{}{
			{
				"color": color,
				"title": notification.Subject,
				"text":  buf.String(),
				"ts":    time.Now().Unix(),
			},
		},
	}

	body, _ := json.Marshal(payload)
	logging.Info(ctx, "Sending Slack notification",
		zap.String("webhook", c.WebhookURL),
		zap.String("payload", string(body)))

	return nil
}
