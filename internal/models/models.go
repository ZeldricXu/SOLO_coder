package models

import (
	"encoding/json"
	"time"
)

type EntityStatus string

const (
	EntityStatusPending   EntityStatus = "pending"
	EntityStatusRunning   EntityStatus = "running"
	EntityStatusCompleted EntityStatus = "completed"
	EntityStatusFailed    EntityStatus = "failed"
)

type CoreEntity struct {
	ID         string                 `json:"id" gorm:"primaryKey;size:64"`
	Type       string                 `json:"type" gorm:"size:64;index"`
	Status     EntityStatus           `json:"status" gorm:"size:32;index"`
	Attributes map[string]interface{} `json:"attributes" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt  time.Time              `json:"updated_at" gorm:"index"`
}

func (c *CoreEntity) MarshalBinary() ([]byte, error) {
	return json.Marshal(c)
}

func (c *CoreEntity) UnmarshalBinary(data []byte) error {
	return json.Unmarshal(data, c)
}

type ConfigDefinition struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey;size:64"`
	Namespace  string                 `json:"namespace" gorm:"size:64;index:idx_namespace_version,unique"`
	Version    int                    `json:"version" gorm:"index:idx_namespace_version,unique"`
	Parameters map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Enabled    bool                   `json:"enabled" gorm:"default:true;index"`
	AppliedAt  time.Time              `json:"applied_at"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type RunPhase string

const (
	PhasePending    RunPhase = "pending"
	PhaseQueued     RunPhase = "queued"
	PhaseExecuting  RunPhase = "executing"
	PhaseFinalizing RunPhase = "finalizing"
	PhaseCompleted  RunPhase = "completed"
	PhaseFailed     RunPhase = "failed"
	PhaseCancelled  RunPhase = "cancelled"
	PhaseTimedOut   RunPhase = "timed_out"
)

type RunInstance struct {
	RunID       string    `json:"run_id" gorm:"primaryKey;size:64"`
	EntityID    string    `json:"entity_id" gorm:"size:64;index"`
	ConfigID    string    `json:"config_id" gorm:"size:64;index"`
	Phase       RunPhase  `json:"phase" gorm:"size:32;index"`
	Progress    float64   `json:"progress" gorm:"default:0"`
	StartedAt   time.Time `json:"started_at" gorm:"index"`
	CompletedAt *time.Time `json:"completed_at"`
	ErrorDetail *string   `json:"error_detail" gorm:"type:text"`
	TraceID     string    `json:"trace_id" gorm:"size:128;index"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

type MetricsSnapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey;size:64"`
	Timestamp  time.Time              `json:"timestamp" gorm:"index"`
	Metrics    map[string]interface{} `json:"metrics" gorm:"type:jsonb"`
	Dimensions map[string]string      `json:"dimensions" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at"`
}

type Resource struct {
	ID     string                 `json:"id" gorm:"primaryKey;size:64"`
	Type   string                 `json:"type" gorm:"size:64;index"`
	Status string                 `json:"status" gorm:"size:32;index"`
	Config map[string]interface{} `json:"config" gorm:"type:jsonb"`
	Labels map[string]string      `json:"labels" gorm:"type:jsonb"`
	CreatedAt time.Time           `json:"created_at"`
	UpdatedAt time.Time           `json:"updated_at"`
}

type BatchOperation struct {
	BatchID   string                 `json:"batch_id" gorm:"primaryKey;size:64"`
	Operations []BatchAction         `json:"operations" gorm:"type:jsonb"`
	Results   []BatchResult          `json:"results" gorm:"type:jsonb"`
	Status    string                 `json:"status" gorm:"size:32;index"`
	CreatedAt time.Time              `json:"created_at"`
	UpdatedAt time.Time              `json:"updated_at"`
}

type BatchAction struct {
	Action string `json:"action"`
	ID     string `json:"id"`
}

type BatchResult struct {
	ID      string `json:"id"`
	Success bool   `json:"success"`
	Error   string `json:"error,omitempty"`
}

type TraceContext struct {
	TraceID    string
	RequestID  string
	UserID     string
	Tags       map[string]string
	StartTime  time.Time
}

func NewTraceContext(traceID string) *TraceContext {
	return &TraceContext{
		TraceID:   traceID,
		StartTime: time.Now(),
		Tags:      make(map[string]string),
	}
}
