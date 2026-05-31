package models

import (
	"time"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey"`
	Type       string                 `json:"type"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `json:"attributes" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type Config struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID        string     `json:"run_id" gorm:"primaryKey"`
	EntityID     string     `json:"entity_id"`
	Phase        string     `json:"phase"`
	Progress     float64    `json:"progress"`
	StartedAt    time.Time  `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at,omitempty"`
	ErrorDetail  string     `json:"error_detail,omitempty"`
}

type StatsSnapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics" gorm:"type:jsonb"`
	Dimensions map[string]string      `json:"dimensions" gorm:"type:jsonb"`
}

type ResourceRequest struct {
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

type ResourceResponse struct {
	ID     string `json:"id"`
	Status string `json:"status"`
}

type BatchOperation struct {
	Action string `json:"action"`
	ID     string `json:"id"`
}

type BatchRequest struct {
	Operations []BatchOperation `json:"operations"`
}

type BatchResponse struct {
	BatchID string                 `json:"batch_id"`
	Results []BatchOperationResult `json:"results"`
}

type BatchOperationResult struct {
	ID     string `json:"id"`
	Success bool   `json:"success"`
	Error  string `json:"error,omitempty"`
}

type APIResponse struct {
	Code    int         `json:"code"`
	Data    interface{} `json:"data,omitempty"`
	Message string      `json:"message,omitempty"`
}

type ExecutionContext struct {
	TraceID  string
	StartAt  time.Time
	Metadata map[string]interface{}
}

func NewExecutionContext(traceID string) *ExecutionContext {
	return &ExecutionContext{
		TraceID:  traceID,
		StartAt:  time.Now(),
		Metadata: make(map[string]interface{}),
	}
}

func (ctx *ExecutionContext) Cleanup() {
	ctx.Metadata = nil
}
