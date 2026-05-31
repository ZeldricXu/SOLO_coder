package models

import (
	"time"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Type       string                 `json:"type" gorm:"type:varchar(32);index"`
	Status     string                 `json:"status" gorm:"type:varchar(32);index"`
	Attributes map[string]interface{} `json:"attributes" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type Config struct {
	ConfigID  string                 `json:"config_id" gorm:"primaryKey;type:varchar(64)"`
	Namespace string                 `json:"namespace" gorm:"type:varchar(64);index"`
	Version   int                    `json:"version" gorm:"index"`
	Parameters map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Enabled   bool                   `json:"enabled" gorm:"index"`
	AppliedAt time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID       string    `json:"run_id" gorm:"primaryKey;type:varchar(64)"`
	EntityID    string    `json:"entity_id" gorm:"type:varchar(64);index"`
	Phase       string    `json:"phase" gorm:"type:varchar(32);index"`
	Progress    float64   `json:"progress"`
	StartedAt   time.Time `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	ErrorDetail *string   `json:"error_detail,omitempty"`
}

type Metrics struct {
	Throughput   int     `json:"throughput"`
	LatencyP99   int     `json:"latency_p99"`
	ErrorRate    float64 `json:"error_rate"`
}

type Dimensions struct {
	Host   string `json:"host"`
	Region string `json:"region"`
}

type Snapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey;type:varchar(64)"`
	Timestamp  time.Time              `json:"timestamp" gorm:"index"`
	Metrics    Metrics                `json:"metrics" gorm:"embedded;embeddedPrefix:metrics_"`
	Dimensions Dimensions             `json:"dimensions" gorm:"embedded;embeddedPrefix:dimensions_"`
}

type Resource struct {
	ID     string                 `json:"id"`
	Type   string                 `json:"type"`
	Status string                 `json:"status"`
	Config map[string]interface{} `json:"config,omitempty"`
	Labels map[string]string      `json:"labels,omitempty"`
}

type BatchOperation struct {
	Action string `json:"action"`
	ID     string `json:"id"`
}

type BatchRequest struct {
	Operations []BatchOperation `json:"operations"`
}

type BatchResult struct {
	ID     string `json:"id"`
	Status string `json:"status"`
	Error  string `json:"error,omitempty"`
}

type BatchResponse struct {
	BatchID string        `json:"batch_id"`
	Results []BatchResult `json:"results"`
}

type APIResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

const (
	StatusActive       = "active"
	StatusInactive     = "inactive"
	StatusPending      = "pending"
	StatusProvisioning = "provisioning"
	StatusRunning      = "running"
	StatusCompleted    = "completed"
	StatusFailed       = "failed"
	StatusRollback     = "rollback"

	PhaseInitializing = "initializing"
	PhaseProcessing   = "processing"
	PhaseFinalizing   = "finalizing"
	PhaseCompleted    = "completed"
	PhaseFailed       = "failed"
)
