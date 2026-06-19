package models

import (
	"database/sql/driver"
	"encoding/json"
	"errors"
	"time"

	"gorm.io/gorm"
)

type ExperimentStatus string

const (
	ExperimentStatusPending   ExperimentStatus = "pending"
	ExperimentStatusRunning   ExperimentStatus = "running"
	ExperimentStatusCompleted ExperimentStatus = "completed"
	ExperimentStatusFailed    ExperimentStatus = "failed"
	ExperimentStatusCanceled  ExperimentStatus = "canceled"
)

type TaskStatus string

const (
	TaskStatusPending   TaskStatus = "pending"
	TaskStatusQueued    TaskStatus = "queued"
	TaskStatusRunning   TaskStatus = "running"
	TaskStatusCompleted TaskStatus = "completed"
	TaskStatusFailed    TaskStatus = "failed"
	TaskStatusRetrying  TaskStatus = "retrying"
	TaskStatusCanceled  TaskStatus = "canceled"
	TaskStatusTimeout   TaskStatus = "timeout"
)

type WorkerStatus string

const (
	WorkerStatusIdle     WorkerStatus = "idle"
	WorkerStatusRunning  WorkerStatus = "running"
	WorkerStatusOffline  WorkerStatus = "offline"
	WorkerStatusDisabled WorkerStatus = "disabled"
)

type Params map[string]interface{}

func (p Params) Value() (driver.Value, error) {
	return json.Marshal(p)
}

func (p *Params) Scan(value interface{}) error {
	bytes, ok := value.([]byte)
	if !ok {
		return errors.New("failed to unmarshal Params value")
	}
	return json.Unmarshal(bytes, p)
}

type ResultData map[string]interface{}

func (r ResultData) Value() (driver.Value, error) {
	return json.Marshal(r)
}

func (r *ResultData) Scan(value interface{}) error {
	bytes, ok := value.([]byte)
	if !ok {
		return errors.New("failed to unmarshal ResultData value")
	}
	return json.Unmarshal(bytes, r)
}

type Experiment struct {
	ID          int64            `gorm:"primaryKey;column:id" json:"id"`
	Name        string           `gorm:"column:name;size:255;not null;index" json:"name"`
	Description string           `gorm:"column:description;type:text" json:"description"`
	Status      ExperimentStatus `gorm:"column:status;size:32;not null;default:pending;index" json:"status"`
	Params      Params           `gorm:"column:params;type:jsonb" json:"params"`
	Config      Params           `gorm:"column:config;type:jsonb" json:"config"`
	CreatedBy   int64            `gorm:"column:created_by;index" json:"created_by"`
	StartTime   *time.Time       `gorm:"column:start_time;index" json:"start_time"`
	EndTime     *time.Time       `gorm:"column:end_time;index" json:"end_time"`
	CreatedAt   time.Time        `gorm:"column:created_at;autoCreateTime;index" json:"created_at"`
	UpdatedAt   time.Time        `gorm:"column:updated_at;autoUpdateTime" json:"updated_at"`
	DeletedAt   gorm.DeletedAt   `gorm:"column:deleted_at;index" json:"-"`

	Tasks []Task `gorm:"foreignKey:ExperimentID" json:"tasks,omitempty"`
}

func (Experiment) TableName() string {
	return "experiments"
}

type Task struct {
	ID             int64      `gorm:"primaryKey;column:id" json:"id"`
	ExperimentID   int64      `gorm:"column:experiment_id;not null;index" json:"experiment_id"`
	Name           string     `gorm:"column:name;size:255;not null" json:"name"`
	Status         TaskStatus `gorm:"column:status;size:32;not null;default:pending;index" json:"status"`
	ParamsHash     string     `gorm:"column:params_hash;size:64;index" json:"params_hash"`
	Params         Params     `gorm:"column:params;type:jsonb" json:"params"`
	Priority       int        `gorm:"column:priority;default:0;index" json:"priority"`
	RetryCount     int        `gorm:"column:retry_count;default:0" json:"retry_count"`
	MaxRetries     int        `gorm:"column:max_retries;default:3" json:"max_retries"`
	WorkerID       *int64     `gorm:"column:worker_id;index" json:"worker_id"`
	TimeoutSeconds int        `gorm:"column:timeout_seconds;default:600" json:"timeout_seconds"`
	StartTime      *time.Time `gorm:"column:start_time;index" json:"start_time"`
	EndTime        *time.Time `gorm:"column:end_time;index" json:"end_time"`
	ErrorMessage   string     `gorm:"column:error_message;type:text" json:"error_message"`
	CreatedAt      time.Time  `gorm:"column:created_at;autoCreateTime;index" json:"created_at"`
	UpdatedAt      time.Time  `gorm:"column:updated_at;autoUpdateTime;index" json:"updated_at"`
	DeletedAt      gorm.DeletedAt `gorm:"column:deleted_at;index" json:"-"`

	Experiment *Experiment  `gorm:"foreignKey:ExperimentID" json:"experiment,omitempty"`
	Worker     *Worker      `gorm:"foreignKey:WorkerID" json:"worker,omitempty"`
	Chunks     []TaskChunk  `gorm:"foreignKey:TaskID" json:"chunks,omitempty"`
	Results    []Result     `gorm:"foreignKey:TaskID" json:"results,omitempty"`
	Checkpoints []Checkpoint `gorm:"foreignKey:TaskID" json:"checkpoints,omitempty"`
}

func (Task) TableName() string {
	return "tasks"
}

type TaskChunk struct {
	ID         int64      `gorm:"primaryKey;column:id" json:"id"`
	TaskID     int64      `gorm:"column:task_id;not null;index" json:"task_id"`
	Index      int        `gorm:"column:chunk_index;not null" json:"index"`
	Total      int        `gorm:"column:total_chunks;not null" json:"total"`
	Status     TaskStatus `gorm:"column:status;size:32;not null;default:pending;index" json:"status"`
	StartRange int64      `gorm:"column:start_range" json:"start_range"`
	EndRange   int64      `gorm:"column:end_range" json:"end_range"`
	WorkerID   *int64     `gorm:"column:worker_id;index" json:"worker_id"`
	StartTime  *time.Time `gorm:"column:start_time" json:"start_time"`
	EndTime    *time.Time `gorm:"column:end_time" json:"end_time"`
	CreatedAt  time.Time  `gorm:"column:created_at;autoCreateTime" json:"created_at"`
	UpdatedAt  time.Time  `gorm:"column:updated_at;autoUpdateTime" json:"updated_at"`

	Task   *Task  `gorm:"foreignKey:TaskID" json:"task,omitempty"`
	Worker *Worker `gorm:"foreignKey:WorkerID" json:"worker,omitempty"`
}

func (TaskChunk) TableName() string {
	return "task_chunks"
}

type Worker struct {
	ID               int64        `gorm:"primaryKey;column:id" json:"id"`
	Name             string       `gorm:"column:name;size:255;not null;uniqueIndex" json:"name"`
	Status           WorkerStatus `gorm:"column:status;size:32;not null;default:idle;index" json:"status"`
	Host             string       `gorm:"column:host;size:255" json:"host"`
	Port             int          `gorm:"column:port" json:"port"`
	Version          string       `gorm:"column:version;size:64" json:"version"`
	CPUCores         int          `gorm:"column:cpu_cores" json:"cpu_cores"`
	MemoryGB         int          `gorm:"column:memory_gb" json:"memory_gb"`
	CurrentTaskID    *int64       `gorm:"column:current_task_id;index" json:"current_task_id"`
	LastHeartbeatAt  *time.Time   `gorm:"column:last_heartbeat_at;index" json:"last_heartbeat_at"`
	HeartbeatCount   int64        `gorm:"column:heartbeat_count;default:0" json:"heartbeat_count"`
	TasksCompleted   int64        `gorm:"column:tasks_completed;default:0" json:"tasks_completed"`
	TasksFailed      int64        `gorm:"column:tasks_failed;default:0" json:"tasks_failed"`
	CreatedAt        time.Time    `gorm:"column:created_at;autoCreateTime" json:"created_at"`
	UpdatedAt        time.Time    `gorm:"column:updated_at;autoUpdateTime" json:"updated_at"`
	DeletedAt        gorm.DeletedAt `gorm:"column:deleted_at;index" json:"-"`

	CurrentTask *Task `gorm:"foreignKey:CurrentTaskID" json:"current_task,omitempty"`
}

func (Worker) TableName() string {
	return "workers"
}

type Checkpoint struct {
	ID        int64      `gorm:"primaryKey;column:id" json:"id"`
	TaskID    int64      `gorm:"column:task_id;not null;index" json:"task_id"`
	WorkerID  int64      `gorm:"column:worker_id;not null;index" json:"worker_id"`
	Step      int64      `gorm:"column:step;not null;index" json:"step"`
	Data      Params     `gorm:"column:data;type:jsonb" json:"data"`
	Checksum  string     `gorm:"column:checksum;size:64" json:"checksum"`
	FilePath  string     `gorm:"column:file_path;size:512" json:"file_path"`
	CreatedAt time.Time  `gorm:"column:created_at;autoCreateTime;index" json:"created_at"`

	Task   *Task   `gorm:"foreignKey:TaskID" json:"task,omitempty"`
	Worker *Worker `gorm:"foreignKey:WorkerID" json:"worker,omitempty"`
}

func (Checkpoint) TableName() string {
	return "checkpoints"
}

type Result struct {
	ID         int64      `gorm:"primaryKey;column:id" json:"id"`
	TaskID     int64      `gorm:"column:task_id;not null;index" json:"task_id"`
	WorkerID   int64      `gorm:"column:worker_id;not null;index" json:"worker_id"`
	ChunkID    *int64     `gorm:"column:chunk_id;index" json:"chunk_id"`
	Data       ResultData `gorm:"column:data;type:jsonb" json:"data"`
	Checksum   string     `gorm:"column:checksum;size:64" json:"checksum"`
	FilePath   string     `gorm:"column:file_path;size:512" json:"file_path"`
	DurationMs int64      `gorm:"column:duration_ms" json:"duration_ms"`
	Iteration  int64      `gorm:"column:iteration;default:0" json:"iteration"`
	CreatedAt  time.Time  `gorm:"column:created_at;autoCreateTime;index" json:"created_at"`

	Task   *Task      `gorm:"foreignKey:TaskID" json:"task,omitempty"`
	Worker *Worker    `gorm:"foreignKey:WorkerID" json:"worker,omitempty"`
	Chunk  *TaskChunk `gorm:"foreignKey:ChunkID" json:"chunk,omitempty"`
}

func (Result) TableName() string {
	return "results"
}

func AutoMigrate(db *gorm.DB) error {
	return db.AutoMigrate(
		&Experiment{},
		&Task{},
		&TaskChunk{},
		&Worker{},
		&Checkpoint{},
		&Result{},
	)
}
