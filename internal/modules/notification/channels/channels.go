package channels

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"notificationplatform/internal/common/logger"
	"notificationplatform/internal/common/models"

	"go.uber.org/zap"
)

type Channel interface {
	Send(ctx context.Context, recipient, title, content string, metadata map[string]string) error
	Name() models.NotificationChannel
}

type EmailChannel struct {
	SMTPHost string
	SMTPPort int
	Username string
	Password string
	Sender   string
}

func NewEmailChannel() *EmailChannel {
	return &EmailChannel{
		SMTPHost: "smtp.example.com",
		SMTPPort: 587,
		Sender:   "noreply@example.com",
	}
}

func (e *EmailChannel) Send(ctx context.Context, recipient, title, content string, metadata map[string]string) error {
	log := logger.FromContext(ctx).With(
		zap.String("channel", string(models.ChannelEmail)),
		zap.String("recipient", recipient),
		zap.String("title", title),
	)

	log.Info("sending email notification")

	log.Debug("email content", zap.Int("content_length", len(content)))

	return nil
}

func (e *EmailChannel) Name() models.NotificationChannel {
	return models.ChannelEmail
}

type SMSChannel struct {
	APIKey    string
	APISecret string
	APIURL    string
}

func NewSMSChannel() *SMSChannel {
	return &SMSChannel{
		APIURL: "https://sms.example.com/api/send",
	}
}

func (s *SMSChannel) Send(ctx context.Context, recipient, title, content string, metadata map[string]string) error {
	log := logger.FromContext(ctx).With(
		zap.String("channel", string(models.ChannelSMS)),
		zap.String("recipient", recipient),
	)

	log.Info("sending SMS notification", zap.Int("content_length", len(content)))

	return nil
}

func (s *SMSChannel) Name() models.NotificationChannel {
	return models.ChannelSMS
}

type WebhookChannel struct {
	HTTPClient *http.Client
}

func NewWebhookChannel() *WebhookChannel {
	return &WebhookChannel{
		HTTPClient: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

type WebhookPayload struct {
	Title     string            `json:"title"`
	Content   string            `json:"content"`
	Timestamp time.Time         `json:"timestamp"`
	Metadata  map[string]string `json:"metadata,omitempty"`
}

func (w *WebhookChannel) Send(ctx context.Context, recipient, title, content string, metadata map[string]string) error {
	log := logger.FromContext(ctx).With(
		zap.String("channel", string(models.ChannelWebhook)),
		zap.String("url", recipient),
	)

	log.Info("sending webhook notification", zap.Int("content_length", len(content)))

	payload := WebhookPayload{
		Title:     title,
		Content:   content,
		Timestamp: time.Now(),
		Metadata:  metadata,
	}

	body, err := json.Marshal(payload)
	if err != nil {
		log.Error("failed to marshal webhook payload", zap.Error(err))
		return fmt.Errorf("marshal payload: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, recipient, bytes.NewBuffer(body))
	if err != nil {
		log.Error("failed to create webhook request", zap.Error(err))
		return fmt.Errorf("create request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")

	resp, err := w.HTTPClient.Do(req)
	if err != nil {
		log.Warn("webhook request failed", zap.Error(err))
		return fmt.Errorf("send request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		respBody, _ := io.ReadAll(resp.Body)
		log.Warn("webhook returned error status",
			zap.Int("status_code", resp.StatusCode),
			zap.String("response", string(respBody)),
		)
		return fmt.Errorf("webhook error: %d", resp.StatusCode)
	}

	log.Debug("webhook sent successfully", zap.Int("status_code", resp.StatusCode))
	return nil
}

func (w *WebhookChannel) Name() models.NotificationChannel {
	return models.ChannelWebhook
}

type DingtalkChannel struct {
	WebhookURL string
	Secret     string
}

func NewDingtalkChannel() *DingtalkChannel {
	return &DingtalkChannel{}
}

type DingtalkMessage struct {
	MsgType string                    `json:"msgtype"`
	Text    *DingtalkTextContent      `json:"text,omitempty"`
	Markdown *DingtalkMarkdownContent `json:"markdown,omitempty"`
}

type DingtalkTextContent struct {
	Content string `json:"content"`
}

type DingtalkMarkdownContent struct {
	Title string `json:"title"`
	Text  string `json:"text"`
}

func (d *DingtalkChannel) Send(ctx context.Context, recipient, title, content string, metadata map[string]string) error {
	log := logger.FromContext(ctx).With(
		zap.String("channel", string(models.ChannelDingtalk)),
		zap.String("webhook", recipient),
	)

	log.Info("sending DingTalk notification", zap.String("title", title))

	msg := DingtalkMessage{
		MsgType: "markdown",
		Markdown: &DingtalkMarkdownContent{
			Title: title,
			Text:  fmt.Sprintf("## %s\n\n%s", title, content),
		},
	}

	body, err := json.Marshal(msg)
	if err != nil {
		log.Error("failed to marshal DingTalk message", zap.Error(err))
		return fmt.Errorf("marshal message: %w", err)
	}

	log.Debug("DingTalk payload", zap.String("payload", string(body)))

	return nil
}

func (d *DingtalkChannel) Name() models.NotificationChannel {
	return models.ChannelDingtalk
}

type WechatChannel struct {
	CorpID     string
	CorpSecret string
	AgentID    int
}

func NewWechatChannel() *WechatChannel {
	return &WechatChannel{}
}

type WechatMessage struct {
	ToUser  string                `json:"touser"`
	MsgType string                `json:"msgtype"`
	AgentID int                   `json:"agentid"`
	Text    *WechatTextContent    `json:"text,omitempty"`
	Markdown *WechatMarkdownContent `json:"markdown,omitempty"`
}

type WechatTextContent struct {
	Content string `json:"content"`
}

type WechatMarkdownContent struct {
	Content string `json:"content"`
}

func (w *WechatChannel) Send(ctx context.Context, recipient, title, content string, metadata map[string]string) error {
	log := logger.FromContext(ctx).With(
		zap.String("channel", string(models.ChannelWechat)),
		zap.String("recipient", recipient),
	)

	log.Info("sending WeChat notification", zap.String("title", title))

	msg := WechatMessage{
		ToUser:  recipient,
		MsgType: "markdown",
		AgentID: w.AgentID,
		Markdown: &WechatMarkdownContent{
			Content: fmt.Sprintf("## %s\n\n%s", title, content),
		},
	}

	body, err := json.Marshal(msg)
	if err != nil {
		log.Error("failed to marshal WeChat message", zap.Error(err))
		return fmt.Errorf("marshal message: %w", err)
	}

	log.Debug("WeChat payload", zap.String("payload", string(body)))

	return nil
}

func (w *WechatChannel) Name() models.NotificationChannel {
	return models.ChannelWechat
}
