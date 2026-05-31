package notification

import (
	"context"
	"time"
)

type NotificationPriority int

const (
	PriorityLow NotificationPriority = iota
	PriorityNormal
	PriorityHigh
	PriorityCritical
)

type Notification struct {
	ID         string                 `json:"id"`
	Title      string                 `json:"title"`
	Message    string                 `json:"message"`
	Priority   NotificationPriority   `json:"priority"`
	Channels   []string               `json:"channels"`
	Data       map[string]interface{} `json:"data"`
	Suppress   *SuppressRule          `json:"suppress,omitempty"`
	CreatedAt  time.Time              `json:"created_at"`
}

type SuppressRule struct {
	Key       string        `json:"key"`
	Duration  time.Duration `json:"duration"`
	Threshold int           `json:"threshold"`
}

type NotificationChannel interface {
	Name() string
	Send(ctx context.Context, notification *Notification) error
}

type NotificationStatus string

const (
	StatusPending    NotificationStatus = "pending"
	StatusSent       NotificationStatus = "sent"
	StatusSuppressed NotificationStatus = "suppressed"
	StatusFailed     NotificationStatus = "failed"
)
