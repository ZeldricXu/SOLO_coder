package entity

import (
	"time"
)

type GPUResource struct {
	ID               string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Node             string    `gorm:"type:varchar(128);not null" json:"node"`
	GPUIndex         int       `gorm:"not null" json:"gpu_index"`
	TotalMemoryMB    int64     `gorm:"not null" json:"total_memory_mb"`
	AvailableMemoryMB int64    `gorm:"not null" json:"available_memory_mb"`
	TotalComputeUnits int      `gorm:"not null" json:"total_compute_units"`
	AvailableComputeUnits int   `gorm:"not null" json:"available_compute_units"`
	Utilization      float64   `json:"utilization"`
	Status           string    `gorm:"type:varchar(64);not null" json:"status"`
	Healthy          bool      `gorm:"default:true" json:"healthy"`
	CreatedAt        time.Time `gorm:"not null" json:"created_at"`
	UpdatedAt        time.Time `gorm:"not null" json:"updated_at"`
}

type Task struct {
	ID               string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name             string    `gorm:"type:varchar(256);not null" json:"name"`
	Type             string    `gorm:"type:varchar(64);not null" json:"type"`
	Priority         int       `gorm:"not null;index" json:"priority"`
	Status           string    `gorm:"type:varchar(64);not null;index" json:"status"`
	MemoryRequiredMB int64     `gorm:"not null" json:"memory_required_mb"`
	ComputeRequired  int       `gorm:"not null" json:"compute_required"`
	GPUID            *string   `gorm:"type:varchar(64);index" json:"gpu_id,omitempty"`
	Preemptible      bool      `gorm:"default:false" json:"preemptible"`
	PreemptCount     int       `gorm:"default:0" json:"preempt_count"`
	Payload          map[string]interface{} `gorm:"type:jsonb" json:"payload,omitempty"`
	QueueTime        time.Time `json:"queue_time"`
	StartTime        *time.Time `json:"start_time,omitempty"`
	EndTime          *time.Time `json:"end_time,omitempty"`
	CreatedAt        time.Time `gorm:"not null" json:"created_at"`
	UpdatedAt        time.Time `gorm:"not null" json:"updated_at"`
}

type TaskQueue interface {
	Enqueue(task *Task) error
	Dequeue() (*Task, error)
	Peek() (*Task, error)
	Remove(taskID string) error
	Size() int
}

type SchedulerPolicy string

const (
	PolicyFIFO        SchedulerPolicy = "fifo"
	PolicyPriority    SchedulerPolicy = "priority"
	PolicyPreemptive  SchedulerPolicy = "preemptive"
)

type TaskStatus string

const (
	TaskStatusPending   TaskStatus = "pending"
	TaskStatusQueued    TaskStatus = "queued"
	TaskStatusRunning   TaskStatus = "running"
	TaskStatusPaused    TaskStatus = "paused"
	TaskStatusCompleted TaskStatus = "completed"
	TaskStatusFailed    TaskStatus = "failed"
	TaskStatusCanceled  TaskStatus = "canceled"
	TaskStatusPreempted TaskStatus = "preempted"
)
