package domain

import (
	"time"
)

type ScheduleStatus string

const (
	ScheduleStatusActive   ScheduleStatus = "active"
	ScheduleStatusPaused   ScheduleStatus = "paused"
	ScheduleStatusDisabled ScheduleStatus = "disabled"
)

type Schedule struct {
	ScheduleID string                 `json:"schedule_id" gorm:"primaryKey;type:varchar(64)"`
	Name       string                 `json:"name"`
	CronExpr   string                 `json:"cron_expr"`
	EntityID   string                 `json:"entity_id" gorm:"type:varchar(64);index"`
	Payload    map[string]interface{} `json:"payload" gorm:"type:jsonb"`
	Status     ScheduleStatus         `json:"status" gorm:"type:varchar(32);index"`
	LastRunAt  *time.Time             `json:"last_run_at,omitempty"`
	NextRunAt  *time.Time             `json:"next_run_at,omitempty"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

func (Schedule) TableName() string {
	return "schedules"
}

type ScheduleLog struct {
	LogID        string     `json:"log_id" gorm:"primaryKey;type:varchar(64)"`
	ScheduleID   string     `json:"schedule_id" gorm:"type:varchar(64);index"`
	RunID        string     `json:"run_id" gorm:"type:varchar(64);index"`
	TriggeredAt  time.Time  `json:"triggered_at"`
	CompletedAt  *time.Time `json:"completed_at,omitempty"`
	Success      bool       `json:"success"`
	ErrorMessage *string    `json:"error_message,omitempty" gorm:"type:text"`
}

func (ScheduleLog) TableName() string {
	return "schedule_logs"
}
