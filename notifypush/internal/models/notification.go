package models

import "time"

type NotifyStatus string

const (
	NotifyStatusPending    NotifyStatus = "pending"
	NotifyStatusQueued     NotifyStatus = "queued"
	NotifyStatusSending    NotifyStatus = "sending"
	NotifyStatusSent       NotifyStatus = "sent"
	NotifyStatusDelivered  NotifyStatus = "delivered"
	NotifyStatusFailed     NotifyStatus = "failed"
	NotifyStatusRetrying   NotifyStatus = "retrying"
	NotifyStatusCancelled  NotifyStatus = "cancelled"
)

type Notification struct {
	NotifyID    string         `json:"notify_id"`
	NotifyType  string         `json:"notify_type"`
	TemplateID  string         `json:"template_id"`
	Channel     string         `json:"channel"`
	Receiver    string         `json:"receiver"`
	Content     string         `json:"content"`
	Priority    int            `json:"priority"`
	CreatedAt   time.Time      `json:"created_at"`
	ScheduledAt time.Time      `json:"scheduled_at"`
	Status      NotifyStatus   `json:"status"`
	RetryCount  int            `json:"retry_count"`
	MaxRetries  int            `json:"max_retries"`
	Variables   map[string]string `json:"variables,omitempty"`
	BatchID     string         `json:"batch_id,omitempty"`
}
