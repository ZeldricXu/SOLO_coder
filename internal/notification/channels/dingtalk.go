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

type DingTalkChannel struct {
	WebhookURL string
	Secret     string
}

func NewDingTalkChannel(webhookURL, secret string) *DingTalkChannel {
	return &DingTalkChannel{
		WebhookURL: webhookURL,
		Secret:     secret,
	}
}

func (c *DingTalkChannel) GetType() contracts.ChannelType {
	return contracts.ChannelDingTalk
}

func (c *DingTalkChannel) HealthCheck(ctx context.Context) bool {
	return c.WebhookURL != ""
}

func (c *DingTalkChannel) Send(ctx context.Context, notification *contracts.Notification, template *contracts.NotificationTemplate) error {
	tpl, err := template.New("dingtalk_content").Parse(template.Content)
	if err != nil {
		return err
	}

	var buf bytes.Buffer
	if err := tpl.Execute(&buf, notification.Data); err != nil {
		return err
	}

	title := notification.Subject
	if title == "" && template.Subject != "" {
		titleTpl, _ := template.New("dingtalk_title").Parse(template.Subject)
		var titleBuf bytes.Buffer
		_ = titleTpl.Execute(&titleBuf, notification.Data)
		title = titleBuf.String()
	}

	payload := map[string]interface{}{
		"msgtype": "markdown",
		"markdown": map[string]interface{}{
			"title": title,
			"text":  buf.String(),
		},
		"at": map[string]interface{}{
			"atMobiles": notification.Recipients,
			"isAtAll":   false,
		},
	}

	body, _ := json.Marshal(payload)
	logging.Info(ctx, "Sending DingTalk notification",
		zap.String("webhook", c.WebhookURL),
		zap.String("payload", string(body)))

	return nil
}
