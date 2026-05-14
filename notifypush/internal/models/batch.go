package models

import "time"

type BatchStatus string

const (
	BatchStatusPending    BatchStatus = "pending"
	BatchStatusProcessing BatchStatus = "processing"
	BatchStatusCompleted  BatchStatus = "completed"
	BatchStatusFailed     BatchStatus = "failed"
	BatchStatusCancelled  BatchStatus = "cancelled"
)

type BatchTask struct {
	BatchID     string      `json:"batch_id"`
	NotifyType  string      `json:"notify_type"`
	TemplateID  string      `json:"template_id"`
	Channel     string      `json:"channel"`
	Receivers   []string    `json:"receivers"`
	Variables   []map[string]string `json:"variables,omitempty"`
	BatchSize   int         `json:"batch_size"`
	TotalCount  int         `json:"total_count"`
	SentCount   int         `json:"sent_count"`
	SuccessCount int        `json:"success_count"`
	FailCount   int         `json:"fail_count"`
	Status      BatchStatus `json:"status"`
	Priority    int         `json:"priority"`
	ScheduledAt time.Time   `json:"scheduled_at"`
	CreatedAt   time.Time   `json:"created_at"`
	StartedAt   *time.Time  `json:"started_at,omitempty"`
	CompletedAt *time.Time  `json:"completed_at,omitempty"`
}
