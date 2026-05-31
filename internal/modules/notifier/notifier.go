package notifier

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/smtp"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
)

type NotificationChannel string

const (
	ChannelEmail  NotificationChannel = "email"
	ChannelSlack  NotificationChannel = "slack"
	ChannelWebhook NotificationChannel = "webhook"
	ChannelSMS    NotificationChannel = "sms"
	ChannelDingTalk NotificationChannel = "dingtalk"
)

type Notification struct {
	NotificationID string                 `json:"notification_id" gorm:"primaryKey;type:varchar(64)"`
	Channel        NotificationChannel    `json:"channel" gorm:"type:varchar(32);index"`
	Name           string                 `json:"name"`
	Config         map[string]interface{} `json:"config" gorm:"type:jsonb"`
	Enabled        bool                   `json:"enabled" gorm:"index"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
}

func (Notification) TableName() string {
	return "notifications"
}

type NotificationRecord struct {
	RecordID       string              `json:"record_id" gorm:"primaryKey;type:varchar(64)"`
	NotificationID string              `json:"notification_id" gorm:"type:varchar(64);index"`
	EventType      string              `json:"event_type" gorm:"index"`
	EventRefID     string              `json:"event_ref_id" gorm:"index"`
	Status         string              `json:"status" gorm:"index"`
	Content        string              `json:"content" gorm:"type:text"`
	Error          string              `json:"error,omitempty"`
	SentAt         *time.Time          `json:"sent_at,omitempty"`
	CreatedAt      time.Time           `json:"created_at"`
}

func (NotificationRecord) TableName() string {
	return "notification_records"
}

type Notifier struct {
	tmpl       *Template
	httpClient *http.Client
}

func NewNotifier() (*Notifier, error) {
	tmpl, err := NewTemplate()
	if err != nil {
		return nil, fmt.Errorf("init template failed: %w", err)
	}

	return &Notifier{
		tmpl: tmpl,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}, nil
}

func (n *Notifier) SendAlert(ctx context.Context, event *domain.AlertEvent, rule *domain.AlertRule) error {
	data := TemplateData{
		AlertEvent: event,
		AlertRule:  rule,
		Timestamp:  time.Now(),
	}

	notifications, err := n.getEnabledNotifications(rule.NotificationIDs)
	if err != nil {
		return fmt.Errorf("get notifications failed: %w", err)
	}

	for _, notification := range notifications {
		go func(notification Notification) {
			if err := n.sendToChannel(context.Background(), notification, data); err != nil {
				logger.Error("Failed to send notification",
					zap.String("notification_id", notification.NotificationID),
					zap.String("channel", string(notification.Channel)),
					zap.Error(err))
				n.recordFailure(notification.NotificationID, "alert", event.EventID, err.Error())
			} else {
				n.recordSuccess(notification.NotificationID, "alert", event.EventID)
			}
		}(notification)
	}

	return nil
}

func (n *Notifier) sendToChannel(ctx context.Context, notification Notification, data TemplateData) error {
	switch notification.Channel {
	case ChannelEmail:
		return n.sendEmail(ctx, notification, data)
	case ChannelSlack:
		return n.sendSlack(ctx, notification, data)
	case ChannelWebhook:
		return n.sendWebhook(ctx, notification, data)
	case ChannelDingTalk:
		return n.sendDingTalk(ctx, notification, data)
	default:
		return fmt.Errorf("unsupported channel: %s", notification.Channel)
	}
}

func (n *Notifier) sendEmail(ctx context.Context, notification Notification, data TemplateData) error {
	smtpHost, _ := notification.Config["smtp_host"].(string)
	smtpPort, _ := notification.Config["smtp_port"].(float64)
	username, _ := notification.Config["username"].(string)
	password, _ := notification.Config["password"].(string)
	from, _ := notification.Config["from"].(string)
	to, _ := notification.Config["to"].([]interface{})

	if smtpHost == "" || username == "" || password == "" {
		return fmt.Errorf("invalid email config")
	}

	content, err := n.tmpl.RenderAlertEmail(data)
	if err != nil {
		return err
	}

	auth := smtp.PlainAuth("", username, password, smtpHost)

	var toAddresses []string
	for _, addr := range to {
		toAddresses = append(toAddresses, fmt.Sprintf("%v", addr))
	}

	subject := fmt.Sprintf("[%s] %s", data.AlertEvent.Severity, data.AlertRule.Name)
	msg := fmt.Sprintf("From: %s\r\nTo: %s\r\nSubject: %s\r\nMIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n%s",
		from, toAddresses[0], subject, content)

	addr := fmt.Sprintf("%s:%d", smtpHost, int(smtpPort))
	return smtp.SendMail(addr, auth, from, toAddresses, []byte(msg))
}

func (n *Notifier) sendSlack(ctx context.Context, notification Notification, data TemplateData) error {
	webhookURL, _ := notification.Config["webhook_url"].(string)
	if webhookURL == "" {
		return fmt.Errorf("invalid slack config")
	}

	content, err := n.tmpl.RenderAlertSlack(data)
	if err != nil {
		return err
	}

	payload := map[string]interface{}{
		"text": content,
	}

	body, _ := json.Marshal(payload)
	req, err := http.NewRequestWithContext(ctx, "POST", webhookURL, bytes.NewBuffer(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := n.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		return fmt.Errorf("slack webhook returned status: %d", resp.StatusCode)
	}

	return nil
}

func (n *Notifier) sendWebhook(ctx context.Context, notification Notification, data TemplateData) error {
	webhookURL, _ := notification.Config["url"].(string)
	method, _ := notification.Config["method"].(string)
	if method == "" {
		method = "POST"
	}

	content, err := n.tmpl.RenderAlertWebhook(data)
	if err != nil {
		return err
	}

	req, err := http.NewRequestWithContext(ctx, method, webhookURL, bytes.NewBuffer([]byte(content)))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	if headers, ok := notification.Config["headers"].(map[string]interface{}); ok {
		for k, v := range headers {
			req.Header.Set(k, fmt.Sprintf("%v", v))
		}
	}

	resp, err := n.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		return fmt.Errorf("webhook returned status: %d", resp.StatusCode)
	}

	return nil
}

func (n *Notifier) sendDingTalk(ctx context.Context, notification Notification, data TemplateData) error {
	webhookURL, _ := notification.Config["webhook_url"].(string)
	if webhookURL == "" {
		return fmt.Errorf("invalid dingtalk config")
	}

	content, err := n.tmpl.RenderAlertSlack(data)
	if err != nil {
		return err
	}

	payload := map[string]interface{}{
		"msgtype": "text",
		"text": map[string]string{
			"content": content,
		},
	}

	body, _ := json.Marshal(payload)
	req, err := http.NewRequestWithContext(ctx, "POST", webhookURL, bytes.NewBuffer(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := n.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		return fmt.Errorf("dingtalk webhook returned status: %d", resp.StatusCode)
	}

	return nil
}

func (n *Notifier) getEnabledNotifications(ids []string) ([]Notification, error) {
	if len(ids) == 0 {
		return nil, nil
	}

	var notifications []Notification
	if err := database.DB.Where("notification_id IN ? AND enabled = ?", ids, true).Find(&notifications).Error; err != nil {
		return nil, err
	}
	return notifications, nil
}

func (n *Notifier) recordSuccess(notificationID, eventType, eventRefID string) {
	now := time.Now()
	record := &NotificationRecord{
		RecordID:       uuid.New().String(),
		NotificationID: notificationID,
		EventType:      eventType,
		EventRefID:     eventRefID,
		Status:         "success",
		SentAt:         &now,
		CreatedAt:      now,
	}
	_ = database.DB.Create(record).Error
}

func (n *Notifier) recordFailure(notificationID, eventType, eventRefID, errMsg string) {
	record := &NotificationRecord{
		RecordID:       uuid.New().String(),
		NotificationID: notificationID,
		EventType:      eventType,
		EventRefID:     eventRefID,
		Status:         "failed",
		Error:          errMsg,
		CreatedAt:      time.Now(),
	}
	_ = database.DB.Create(record).Error
}

func (n *Notifier) CreateNotification(ctx context.Context, notification *Notification) (*Notification, error) {
	notification.NotificationID = uuid.New().String()
	notification.CreatedAt = time.Now()
	notification.UpdatedAt = time.Now()

	if err := database.DB.WithContext(ctx).Create(notification).Error; err != nil {
		return nil, fmt.Errorf("create notification failed: %w", err)
	}

	logger.Info("Notification channel created",
		zap.String("notification_id", notification.NotificationID),
		zap.String("channel", string(notification.Channel)))

	return notification, nil
}

func (n *Notifier) UpdateNotification(ctx context.Context, notificationID string, updates map[string]interface{}) (*Notification, error) {
	var notification Notification
	if err := database.DB.WithContext(ctx).Where("notification_id = ?", notificationID).First(&notification).Error; err != nil {
		return nil, fmt.Errorf("notification not found: %w", err)
	}

	updates["updated_at"] = time.Now()
	if err := database.DB.WithContext(ctx).Model(&notification).Updates(updates).Error; err != nil {
		return nil, fmt.Errorf("update notification failed: %w", err)
	}

	if err := database.DB.WithContext(ctx).Where("notification_id = ?", notificationID).First(&notification).Error; err != nil {
		return nil, fmt.Errorf("reload notification failed: %w", err)
	}

	return &notification, nil
}

func (n *Notifier) DeleteNotification(ctx context.Context, notificationID string) error {
	if err := database.DB.WithContext(ctx).Where("notification_id = ?", notificationID).Delete(&Notification{}).Error; err != nil {
		return fmt.Errorf("delete notification failed: %w", err)
	}
	return nil
}

func (n *Notifier) GetNotification(ctx context.Context, notificationID string) (*Notification, error) {
	var notification Notification
	if err := database.DB.WithContext(ctx).Where("notification_id = ?", notificationID).First(&notification).Error; err != nil {
		return nil, fmt.Errorf("get notification failed: %w", err)
	}
	return &notification, nil
}

func (n *Notifier) ListNotifications(ctx context.Context, offset, limit int) ([]Notification, int64, error) {
	var notifications []Notification
	var total int64

	if err := database.DB.WithContext(ctx).Model(&Notification{}).Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count notifications failed: %w", err)
	}

	if err := database.DB.WithContext(ctx).Order("created_at DESC").Offset(offset).Limit(limit).Find(&notifications).Error; err != nil {
		return nil, 0, fmt.Errorf("list notifications failed: %w", err)
	}

	return notifications, total, nil
}
