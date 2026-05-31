package scheduler

import "time"

type TaskType string

const (
	TaskTypeCron     TaskType = "cron"
	TaskTypeInterval TaskType = "interval"
	TaskTypeOnce     TaskType = "once"
)

type TaskStatus string

const (
	TaskStatusPending   TaskStatus = "pending"
	TaskStatusRunning   TaskStatus = "running"
	TaskStatusPaused    TaskStatus = "paused"
	TaskStatusCompleted TaskStatus = "completed"
	TaskStatusFailed    TaskStatus = "failed"
)

type Task struct {
	ID         string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID   string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	Name       string                 `json:"name" gorm:"type:varchar(128)"`
	Type       TaskType               `json:"type" gorm:"type:varchar(32);index"`
	CronExpr   string                 `json:"cron_expr,omitempty" gorm:"type:varchar(64)"`
	Interval   int                    `json:"interval_seconds,omitempty"`
	Handler    string                 `json:"handler" gorm:"type:varchar(64);index"`
	Params     map[string]interface{} `json:"params" gorm:"type:jsonb"`
	Status     TaskStatus             `json:"status" gorm:"type:varchar(32);index"`
	MaxRetries int                    `json:"max_retries" gorm:"default:3"`
	RetryCount int                    `json:"retry_count" gorm:"default:0"`
	Timeout    int                    `json:"timeout_seconds" gorm:"default:3600"`
	LastRunAt  *time.Time             `json:"last_run_at"`
	NextRunAt  *time.Time             `json:"next_run_at"`
	LastError  *string                `json:"last_error" gorm:"type:text"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type TaskExecution struct {
	ID         string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TaskID     string                 `json:"task_id" gorm:"type:varchar(64);index"`
	TenantID   string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	Status     string                 `json:"status" gorm:"type:varchar(32);index"`
	StartedAt  time.Time              `json:"started_at"`
	FinishedAt *time.Time             `json:"finished_at"`
	Duration   int64                  `json:"duration_ms"`
	Result     map[string]interface{} `json:"result" gorm:"type:jsonb"`
	Error      *string                `json:"error" gorm:"type:text"`
	CreatedAt  time.Time              `json:"created_at"`
}

func (t *Task) TableName() string {
	return "scheduler_tasks"
}

func (e *TaskExecution) TableName() string {
	return "scheduler_task_executions"
}
