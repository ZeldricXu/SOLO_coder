package notification

import (
	"bytes"
	"context"
	"fmt"
	"html/template"
	"sync"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type DefaultNotifier struct {
	channels  map[string]interfaces.NotificationChannel
	templates map[string]*template.Template
	logger    *zap.Logger
	mu        sync.RWMutex
}

func NewDefaultNotifier() *DefaultNotifier {
	n := &DefaultNotifier{
		channels:  make(map[string]interfaces.NotificationChannel),
		templates: make(map[string]*template.Template),
		logger:    utils.GetLogger(),
	}
	n.loadDefaultTemplates()
	return n
}

func (n *DefaultNotifier) loadDefaultTemplates() {
	templates := map[string]string{
		"task_completed": `
			<div>
				<h2>任务完成通知</h2>
				<p>任务ID: {{.TaskID}}</p>
				<p>状态: {{.Status}}</p>
				<p>完成时间: {{.CompletedAt}}</p>
			</div>
		`,
		"task_failed": `
			<div>
				<h2>任务失败通知</h2>
				<p>任务ID: {{.TaskID}}</p>
				<p>错误信息: {{.Error}}</p>
				<p>发生时间: {{.FailedAt}}</p>
			</div>
		`,
		"alert": `
			<div>
				<h2>告警通知</h2>
				<p>告警级别: {{.Level}}</p>
				<p>告警内容: {{.Message}}</p>
				<p>发生时间: {{.Timestamp}}</p>
			</div>
		`,
		"budget_exhausted": `
			<div>
				<h2>隐私预算耗尽通知</h2>
				<p>剩余预算: {{.Remaining}}</p>
				<p>已使用预算: {{.Used}}</p>
				<p>总预算: {{.Total}}</p>
			</div>
		`,
	}

	for name, content := range templates {
		tpl, err := template.New(name).Parse(content)
		if err == nil {
			n.templates[name] = tpl
		}
	}
}

func (n *DefaultNotifier) AddChannel(channel interfaces.NotificationChannel) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.channels[channel.GetName()] = channel
	n.logger.Info("Notification channel added", zap.String("channel", channel.GetName()))
}

func (n *DefaultNotifier) Send(ctx context.Context, notification *interfaces.Notification) error {
	n.mu.RLock()
	defer n.mu.RUnlock()

	channel, exists := n.channels[notification.Channel]
	if !exists {
		return fmt.Errorf("channel %s not found", notification.Channel)
	}

	rendered, err := n.RenderTemplate(ctx, notification.TemplateID, notification.Data)
	if err != nil {
		n.logger.Warn("Template render failed, using raw data", zap.Error(err))
	} else {
		notification.Data["rendered_content"] = rendered
	}

	err = channel.Send(ctx, notification)
	if err != nil {
		n.logger.Error("Notification send failed",
			zap.String("channel", notification.Channel),
			zap.String("template", notification.TemplateID),
			zap.Error(err),
		)
		return err
	}

	n.logger.Info("Notification sent",
		zap.String("channel", notification.Channel),
		zap.String("template", notification.TemplateID),
		zap.Int("recipients", len(notification.Recipients)),
	)

	return nil
}

func (n *DefaultNotifier) RenderTemplate(ctx context.Context, templateID string, data map[string]interface{}) (string, error) {
	n.mu.RLock()
	defer n.mu.RUnlock()

	tpl, exists := n.templates[templateID]
	if !exists {
		return "", fmt.Errorf("template %s not found", templateID)
	}

	var buf bytes.Buffer
	if err := tpl.Execute(&buf, data); err != nil {
		return "", fmt.Errorf("template execution failed: %w", err)
	}

	return buf.String(), nil
}

func (n *DefaultNotifier) RegisterTemplate(templateID, content string) error {
	n.mu.Lock()
	defer n.mu.Unlock()

	tpl, err := template.New(templateID).Parse(content)
	if err != nil {
		return fmt.Errorf("template parse failed: %w", err)
	}

	n.templates[templateID] = tpl
	n.logger.Info("Template registered", zap.String("template_id", templateID))
	return nil
}

func (n *DefaultNotifier) RemoveTemplate(templateID string) {
	n.mu.Lock()
	defer n.mu.Unlock()
	delete(n.templates, templateID)
}

func (n *DefaultNotifier) BatchSend(ctx context.Context, notifications []*interfaces.Notification) error {
	var wg sync.WaitGroup
	errChan := make(chan error, len(notifications))

	for _, notification := range notifications {
		wg.Add(1)
		go func(notif *interfaces.Notification) {
			defer wg.Done()
			if err := n.Send(ctx, notif); err != nil {
				errChan <- err
			}
		}(notification)
	}

	wg.Wait()
	close(errChan)

	if len(errChan) > 0 {
		return <-errChan
	}

	return nil
}

type ConsoleChannel struct{}

func NewConsoleChannel() *ConsoleChannel {
	return &ConsoleChannel{}
}

func (c *ConsoleChannel) GetName() string {
	return "console"
}

func (c *ConsoleChannel) Send(ctx context.Context, notification *interfaces.Notification) error {
	fmt.Printf("[Console Notification] Channel: %s, Template: %s\n",
		notification.Channel, notification.TemplateID)
	fmt.Printf("Recipients: %v\n", notification.Recipients)
	if content, ok := notification.Data["rendered_content"].(string); ok {
		fmt.Printf("Content: %s\n", content)
	} else {
		fmt.Printf("Data: %v\n", notification.Data)
	}
	return nil
}

type EmailChannel struct {
	SMTPHost string
	SMTPPort int
	Username string
	Password string
}

func NewEmailChannel(host string, port int, username, password string) *EmailChannel {
	return &EmailChannel{
		SMTPHost: host,
		SMTPPort: port,
		Username: username,
		Password: password,
	}
}

func (e *EmailChannel) GetName() string {
	return "email"
}

func (e *EmailChannel) Send(ctx context.Context, notification *interfaces.Notification) error {
	logger := utils.GetLogger()
	logger.Info("Email notification simulated",
		zap.String("host", e.SMTPHost),
		zap.Int("port", e.SMTPPort),
		zap.String("from", e.Username),
		zap.Strings("to", notification.Recipients),
	)
	return nil
}

type SMSChannel struct {
	APIKey    string
	APISecret string
}

func NewSMSChannel(apiKey, apiSecret string) *SMSChannel {
	return &SMSChannel{
		APIKey:    apiKey,
		APISecret: apiSecret,
	}
}

func (s *SMSChannel) GetName() string {
	return "sms"
}

func (s *SMSChannel) Send(ctx context.Context, notification *interfaces.Notification) error {
	logger := utils.GetLogger()
	logger.Info("SMS notification simulated",
		zap.Strings("recipients", notification.Recipients),
	)
	return nil
}

type WebhookChannel struct {
	URL     string
	Headers map[string]string
}

func NewWebhookChannel(url string, headers map[string]string) *WebhookChannel {
	return &WebhookChannel{
		URL:     url,
		Headers: headers,
	}
}

func (w *WebhookChannel) GetName() string {
	return "webhook"
}

func (w *WebhookChannel) Send(ctx context.Context, notification *interfaces.Notification) error {
	logger := utils.GetLogger()
	logger.Info("Webhook notification simulated",
		zap.String("url", w.URL),
		zap.Any("data", notification.Data),
	)
	return nil
}
