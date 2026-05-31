package models

import "time"

type EntityType string

const (
	EntityTypeTask     EntityType = "task"
	EntityTypePipeline EntityType = "pipeline"
	EntityTypeResource EntityType = "resource"
)

type EntityStatus string

const (
	EntityStatusActive   EntityStatus = "active"
	EntityStatusInactive EntityStatus = "inactive"
	EntityStatusPaused   EntityStatus = "paused"
	EntityStatusDeleted  EntityStatus = "deleted"
)

type Entity struct {
	ID         string                 `json:"id"`
	Type       EntityType             `json:"type"`
	Status     EntityStatus           `json:"status"`
	Attributes map[string]interface{} `json:"attributes"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type ConfigDefinition struct {
	ConfigID   string                 `json:"config_id"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  *time.Time             `json:"applied_at,omitempty"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type RunPhase string

const (
	RunPhasePending      RunPhase = "pending"
	RunPhaseInitializing RunPhase = "initializing"
	RunPhaseRunning      RunPhase = "running"
	RunPhaseFinalizing   RunPhase = "finalizing"
	RunPhaseCompleted    RunPhase = "completed"
	RunPhaseFailed       RunPhase = "failed"
	RunPhaseCancelled    RunPhase = "cancelled"
)

type RunInstance struct {
	RunID        string                 `json:"run_id"`
	EntityID     string                 `json:"entity_id"`
	Phase        RunPhase               `json:"phase"`
	Progress     float64                `json:"progress"`
	StartedAt    *time.Time             `json:"started_at,omitempty"`
	CompletedAt  *time.Time             `json:"completed_at,omitempty"`
	ErrorDetail  string                 `json:"error_detail,omitempty"`
	Metadata     map[string]interface{} `json:"metadata"`
	CreatedAt    time.Time              `json:"created_at"`
}

type ProcessingContext struct {
	TraceID     string
	RequestID   string
	StartedAt   time.Time
	Namespace   string
	Params      map[string]interface{}
	Metadata    map[string]interface{}
	Errors      []error
	Resources   []interface{}
}

type ProcessingResult struct {
	Success          bool        `json:"success"`
	Data             interface{} `json:"data,omitempty"`
	ErrorCode        int         `json:"error_code,omitempty"`
	ErrorMessage     string      `json:"error_message,omitempty"`
	ErrorDetails     interface{} `json:"error_details,omitempty"`
	RunID            string      `json:"run_id,omitempty"`
	ExecutionTimeMs  int64       `json:"execution_time_ms"`
}

type ValidationError struct {
	Message string                 `json:"message"`
	Details map[string]interface{} `json:"details,omitempty"`
}

func (e *ValidationError) Error() string {
	return e.Message
}

type TimeoutError struct {
	Message string `json:"message"`
}

func (e *TimeoutError) Error() string {
	return e.Message
}

type ResourceAcquisitionError struct {
	Message string `json:"message"`
}

func (e *ResourceAcquisitionError) Error() string {
	return e.Message
}
