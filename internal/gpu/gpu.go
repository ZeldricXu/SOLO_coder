package gpu

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
)

type TaskPriority int

const (
	PriorityLow      TaskPriority = 0
	PriorityNormal   TaskPriority = 50
	PriorityHigh     TaskPriority = 100
	PriorityCritical TaskPriority = 200
)

type GPUStatus string

const (
	GPUStatusIdle    GPUStatus = "idle"
	GPUStatusRunning GPUStatus = "running"
	GPUStatusOffline GPUStatus = "offline"
)

type GPU struct {
	ID          string            `gorm:"primaryKey;type:varchar(64)" json:"id"`
	NodeID      string            `gorm:"type:varchar(64);index;not null" json:"node_id"`
	Index       int               `json:"index"`
	Name        string            `gorm:"type:varchar(128)" json:"name"`
	Type        string            `gorm:"type:varchar(64)" json:"type"`
	TotalMemory int64             `json:"total_memory"`
	UsedMemory  int64             `json:"used_memory"`
	Status      GPUStatus         `gorm:"type:varchar(32);index" json:"status"`
	CurrentTaskID string          `gorm:"type:varchar(64)" json:"current_task_id,omitempty"`
	Labels      map[string]string `gorm:"type:jsonb;serializer:json" json:"labels"`
	CreatedAt   time.Time         `json:"created_at"`
	UpdatedAt   time.Time         `json:"updated_at"`
}

type Task struct {
	ID            string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name          string                 `gorm:"type:varchar(128);not null" json:"name"`
	Namespace     string                 `gorm:"type:varchar(64);index;not null" json:"namespace"`
	Description   string                 `gorm:"type:text" json:"description"`
	Priority      TaskPriority           `gorm:"index" json:"priority"`
	Status        TaskStatus             `gorm:"type:varchar(32);index" json:"status"`
	Command       string                 `gorm:"type:text" json:"command"`
	Image         string                 `gorm:"type:varchar(256)" json:"image"`
	EnvVars       map[string]string      `gorm:"type:jsonb;serializer:json" json:"env_vars"`
	RequiredGPUs  int                    `json:"required_gpus"`
	RequiredMemory int64                 `json:"required_memory"`
	GPULabels     map[string]string      `gorm:"type:jsonb;serializer:json" json:"gpu_labels"`
	AssignedGPUs  []string               `gorm:"type:jsonb;serializer:json" json:"assigned_gpus,omitempty"`
	MaxRetryCount int                   `json:"max_retry_count"`
	RetryCount    int                   `json:"retry_count"`
	Timeout       time.Duration         `json:"timeout"`
	UserID        string                `gorm:"type:varchar(64)" json:"user_id"`
	Progress      float64               `json:"progress"`
	ErrorDetail   *string               `json:"error_detail,omitempty"`
	QueuedAt      *time.Time            `json:"queued_at,omitempty"`
	StartedAt     *time.Time            `json:"started_at,omitempty"`
	CompletedAt   *time.Time            `json:"completed_at,omitempty"`
	CreatedAt     time.Time             `json:"created_at"`
	UpdatedAt     time.Time             `json:"updated_at"`
	Metadata      map[string]interface{} `gorm:"type:jsonb;serializer:json" json:"metadata"`
}

type TaskEvent struct {
	ID        string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	TaskID    string    `gorm:"type:varchar(64);index;not null" json:"task_id"`
	EventType string    `gorm:"type:varchar(32);not null" json:"event_type"`
	Status    TaskStatus `gorm:"type:varchar(32)" json:"status"`
	Detail    string    `gorm:"type:text" json:"detail"`
	CreatedAt time.Time `json:"created_at"`
}

type CreateTaskRequest struct {
	Name          string                 `json:"name" binding:"required"`
	Namespace     string                 `json:"namespace" binding:"required"`
	Description   string                 `json:"description"`
	Priority      TaskPriority           `json:"priority"`
	Command       string                 `json:"command" binding:"required"`
	Image         string                 `json:"image"`
	EnvVars       map[string]string      `json:"env_vars"`
	RequiredGPUs  int                    `json:"required_gpus" binding:"required,min=1"`
	RequiredMemory int64                 `json:"required_memory"`
	GPULabels     map[string]string      `json:"gpu_labels"`
	MaxRetryCount int                   `json:"max_retry_count"`
	Timeout       string                 `json:"timeout"`
	Metadata      map[string]interface{} `json:"metadata"`
}
