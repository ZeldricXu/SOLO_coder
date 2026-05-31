package domain

import (
	"time"
)

type TaskStatus string

const (
	TaskStatusPending   TaskStatus = "pending"
	TaskStatusRunning   TaskStatus = "running"
	TaskStatusPaused    TaskStatus = "paused"
	TaskStatusCompleted TaskStatus = "completed"
	TaskStatusFailed    TaskStatus = "failed"
	TaskStatusCancelled TaskStatus = "cancelled"
	TaskStatusTimeout   TaskStatus = "timeout"
)

type TaskType string

const (
	TaskTypeBackup    TaskType = "backup"
	TaskTypeRestore   TaskType = "restore"
	TaskTypeProfiling TaskType = "profiling"
	TaskTypeAnalysis  TaskType = "analysis"
	TaskTypeReport    TaskType = "report"
	TaskTypeCustom    TaskType = "custom"
)

type Task struct {
	TaskID      string                 `json:"task_id" gorm:"primaryKey;type:varchar(64)"`
	Name        string                 `json:"name"`
	Type        TaskType               `json:"type" gorm:"type:varchar(32);index"`
	Status      TaskStatus             `json:"status" gorm:"type:varchar(32);index"`
	Description string                 `json:"description"`
	Parameters  map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Result      map[string]interface{} `json:"result" gorm:"type:jsonb"`
	Error       string                 `json:"error,omitempty"`
	Progress    int32                  `json:"progress"`
	CreatedBy   string                 `json:"created_by"`
	ScheduledAt *time.Time             `json:"scheduled_at,omitempty" gorm:"index"`
	StartedAt   *time.Time             `json:"started_at,omitempty"`
	CompletedAt *time.Time             `json:"completed_at,omitempty"`
	TimeoutSec  int32                  `json:"timeout_sec"`
	RetryCount  int32                  `json:"retry_count"`
	MaxRetry    int32                  `json:"max_retry"`
	CreatedAt   time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

func (Task) TableName() string {
	return "tasks"
}

type TaskLog struct {
	LogID     string    `json:"log_id" gorm:"primaryKey;type:varchar(64)"`
	TaskID    string    `json:"task_id" gorm:"type:varchar(64);index"`
	Level     string    `json:"level" gorm:"type:varchar(16)"`
	Message   string    `json:"message" gorm:"type:text"`
	Timestamp time.Time `json:"timestamp" gorm:"index"`
}

func (TaskLog) TableName() string {
	return "task_logs"
}
