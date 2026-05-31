package model

import (
	"encoding/json"
	"time"
)

type EntityStatus string

const (
	EntityStatusPending    EntityStatus = "pending"
	EntityStatusProvisioning EntityStatus = "provisioning"
	EntityStatusRunning    EntityStatus = "running"
	EntityStatusFailed     EntityStatus = "failed"
	EntityStatusCompleted  EntityStatus = "completed"
	EntityStatusStopped    EntityStatus = "stopped"
)

type EntityType string

const (
	EntityTypeResource EntityType = "resource"
	EntityTypeTask     EntityType = "task"
	EntityTypeDevice   EntityType = "device"
	EntityTypeConfig   EntityType = "config"
	EntityTypeModel    EntityType = "model"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Type       EntityType             `json:"type" gorm:"type:varchar(32);index"`
	Status     EntityStatus           `json:"status" gorm:"type:varchar(32);index"`
	Attributes map[string]interface{} `json:"attributes" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt  time.Time              `json:"updated_at" gorm:"index"`
}

func (e *Entity) TableName() string {
	return "entities"
}

type ConfigDefinition struct {
	ConfigID  string                 `json:"config_id" gorm:"primaryKey;type:varchar(64)"`
	Namespace string                 `json:"namespace" gorm:"type:varchar(64);index"`
	Version   int64                  `json:"version" gorm:"index"`
	Parameters map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Enabled   bool                   `json:"enabled" gorm:"default:true"`
	AppliedAt *time.Time             `json:"applied_at"`
	CreatedAt time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt time.Time              `json:"updated_at" gorm:"index"`
}

func (c *ConfigDefinition) TableName() string {
	return "config_definitions"
}

type RunPhase string

const (
	RunPhasePending    RunPhase = "pending"
	RunPhaseExecuting  RunPhase = "executing"
	RunPhaseValidating RunPhase = "validating"
	RunPhaseRollback   RunPhase = "rollback"
	RunPhaseCompleted  RunPhase = "completed"
	RunPhaseFailed     RunPhase = "failed"
)

type RunInstance struct {
	RunID        string     `json:"run_id" gorm:"primaryKey;type:varchar(64)"`
	EntityID     string     `json:"entity_id" gorm:"type:varchar(64);index"`
	Phase        RunPhase   `json:"phase" gorm:"type:varchar(32);index"`
	Progress     float64    `json:"progress" gorm:"type:decimal(5,4);default:0"`
	StartedAt    time.Time  `json:"started_at" gorm:"index"`
	CompletedAt  *time.Time `json:"completed_at"`
	ErrorDetail  *string    `json:"error_detail" gorm:"type:text"`
	TraceID      string     `json:"trace_id" gorm:"type:varchar(128);index"`
	RetryCount   int        `json:"retry_count" gorm:"default:0"`
	MaxRetries   int        `json:"max_retries" gorm:"default:3"`
}

func (r *RunInstance) TableName() string {
	return "run_instances"
}

type MetricSnapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey;type:varchar(64)"`
	Timestamp  time.Time              `json:"timestamp" gorm:"index"`
	Metrics    map[string]interface{} `json:"metrics" gorm:"type:jsonb"`
	Dimensions map[string]string      `json:"dimensions" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at" gorm:"index"`
}

func (m *MetricSnapshot) TableName() string {
	return "metric_snapshots"
}

type ApiResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

type ResourceRequest struct {
	Type   string                 `json:"type" binding:"required"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

type ResourceStatusResponse struct {
	ID       string      `json:"id"`
	Status   string      `json:"status"`
	Progress float64     `json:"progress,omitempty"`
	Phase    string      `json:"phase,omitempty"`
	Data     interface{} `json:"data,omitempty"`
}

type BatchOperation struct {
	Action string                 `json:"action" binding:"required,oneof=start stop delete restart"`
	ID     string                 `json:"id" binding:"required"`
	Params map[string]interface{} `json:"params"`
}

type BatchRequest struct {
	Operations []BatchOperation `json:"operations" binding:"required,min=1,max=100"`
}

type BatchResponse struct {
	BatchID string                   `json:"batch_id"`
	Results []BatchOperationResult   `json:"results"`
}

type BatchOperationResult struct {
	ID      string `json:"id"`
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
}

func ToJSON(v interface{}) string {
	b, _ := json.Marshal(v)
	return string(b)
}
