package models

import (
	"time"
)

type TaskType string
type TaskStatus string
type ExecutionStatus string
type WorkerStatus string

const (
	TaskTypeCron     TaskType = "cron"
	TaskTypeDelay    TaskType = "delay"
	TaskTypeDag      TaskType = "dag"
	TaskTypeOneShot  TaskType = "one_shot"

	TaskStatusActive   TaskStatus = "active"
	TaskStatusPaused   TaskStatus = "paused"
	TaskStatusDisabled TaskStatus = "disabled"
	TaskStatusDeleted  TaskStatus = "deleted"

	ExecutionStatusPending   ExecutionStatus = "pending"
	ExecutionStatusRunning   ExecutionStatus = "running"
	ExecutionStatusSuccess   ExecutionStatus = "success"
	ExecutionStatusFailed    ExecutionStatus = "failed"
	ExecutionStatusRetrying  ExecutionStatus = "retrying"
	ExecutionStatusDeadLetter ExecutionStatus = "dead_letter"

	WorkerStatusHealthy   WorkerStatus = "healthy"
	WorkerStatusUnhealthy WorkerStatus = "unhealthy"
	WorkerStatusOffline   WorkerStatus = "offline"
)

type Task struct {
	ID                string         `db:"id"`
	Namespace         string         `db:"namespace"`
	Name              string         `db:"name"`
	Type              TaskType       `db:"type"`
	Description       string         `db:"description"`
	CronExpression    string         `db:"cron_expression"`
	DelaySeconds      int            `db:"delay_seconds"`
	IntervalSeconds   int            `db:"interval_seconds"`
	Payload           []byte         `db:"payload"`
	CallbackURL       string         `db:"callback_url"`
	TimeoutSeconds    int            `db:"timeout_seconds"`
	MaxRetries        int            `db:"max_retries"`
	RetryBackoff      string         `db:"retry_backoff"`
	Dependencies      []string       `db:"dependencies"`
	DagID             string         `db:"dag_id"`
	Priority          int            `db:"priority"`
	Status            TaskStatus     `db:"status"`
	CreatedBy         string         `db:"created_by"`
	CreatedAt         time.Time      `db:"created_at"`
	UpdatedAt         time.Time      `db:"updated_at"`
	NextRunAt         *time.Time     `db:"next_run_at"`
	LastRunAt         *time.Time     `db:"last_run_at"`
	Tags              []string       `db:"tags"`
}

type Execution struct {
	ID              string          `db:"id"`
	TaskID          string          `db:"task_id"`
	Namespace       string          `db:"namespace"`
	Status          ExecutionStatus `db:"status"`
	WorkerID        string          `db:"worker_id"`
	NodeID          string          `db:"node_id"`
	InputPayload    []byte          `db:"input_payload"`
	OutputPayload   []byte          `db:"output_payload"`
	StartTime       *time.Time      `db:"start_time"`
	EndTime         *time.Time      `db:"end_time"`
	DurationMs      int64           `db:"duration_ms"`
	RetryCount      int             `db:"retry_count"`
	ErrorMessage    string          `db:"error_message"`
	TraceID         string          `db:"trace_id"`
	SpanID          string          `db:"span_id"`
	CreatedAt       time.Time       `db:"created_at"`
	ParentExecutionID string        `db:"parent_execution_id"`
}

type TaskLog struct {
	ID            string    `db:"id"`
	ExecutionID   string    `db:"execution_id"`
	TaskID        string    `db:"task_id"`
	Namespace     string    `db:"namespace"`
	LogLevel      string    `db:"log_level"`
	Message       string    `db:"message"`
	Timestamp     time.Time `db:"timestamp"`
	Sequence      int64     `db:"sequence"`
}

type DAG struct {
	ID          string    `db:"id"`
	Namespace   string    `db:"namespace"`
	Name        string    `db:"name"`
	Description string    `db:"description"`
	Nodes       []byte    `db:"nodes"`
	Edges       []byte    `db:"edges"`
	CreatedBy   string    `db:"created_by"`
	CreatedAt   time.Time `db:"created_at"`
	UpdatedAt   time.Time `db:"updated_at"`
}

type Worker struct {
	ID              string       `db:"id"`
	Namespace       string       `db:"namespace"`
	Hostname        string       `db:"hostname"`
	GRPCAddr        string       `db:"grpc_addr"`
	HTTPAddr        string       `db:"http_addr"`
	Status          WorkerStatus `db:"status"`
	LastHeartbeat   time.Time    `db:"last_heartbeat"`
	RegisteredAt    time.Time    `db:"registered_at"`
	UnhealthyCount  int          `db:"unhealthy_count"`
	Capabilities    []string     `db:"capabilities"`
	CurrentLoad     int          `db:"current_load"`
	MaxLoad         int          `db:"max_load"`
}

type Namespace struct {
	ID          string    `db:"id"`
	Name        string    `db:"name"`
	Description string    `db:"description"`
	CreatedBy   string    `db:"created_by"`
	CreatedAt   time.Time `db:"created_at"`
}

type AuditLog struct {
	ID         string    `db:"id"`
	Namespace  string    `db:"namespace"`
	Actor      string    `db:"actor"`
	Action     string    `db:"action"`
	Resource   string    `db:"resource"`
	ResourceID string    `db:"resource_id"`
	OldValue   []byte    `db:"old_value"`
	NewValue   []byte    `db:"new_value"`
	IPAddress  string    `db:"ip_address"`
	CreatedAt  time.Time `db:"created_at"`
}

type DeadLetter struct {
	ID              string    `db:"id"`
	ExecutionID     string    `db:"execution_id"`
	TaskID          string    `db:"task_id"`
	Namespace       string    `db:"namespace"`
	ErrorMessage    string    `db:"error_message"`
	OriginalStatus  string    `db:"original_status"`
	Payload         []byte    `db:"payload"`
	CreatedAt       time.Time `db:"created_at"`
	Replayed        bool      `db:"replayed"`
	ReplayedAt      *time.Time `db:"replayed_at"`
	ReplayedBy      string    `db:"replayed_by"`
}
