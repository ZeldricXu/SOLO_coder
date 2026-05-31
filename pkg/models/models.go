package models

import (
	"encoding/json"
	"time"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey"`
	Type       string                 `json:"type"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `json:"attributes" gorm:"serializer:json"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type Config struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters" gorm:"serializer:json"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID       string          `json:"run_id" gorm:"primaryKey"`
	EntityID    string          `json:"entity_id"`
	Phase       string          `json:"phase"`
	Progress    float64         `json:"progress"`
	StartedAt   time.Time       `json:"started_at"`
	CompletedAt *time.Time      `json:"completed_at,omitempty"`
	ErrorDetail json.RawMessage `json:"error_detail,omitempty" gorm:"serializer:json"`
}

type Snapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64       `json:"metrics" gorm:"serializer:json"`
	Dimensions map[string]string      `json:"dimensions" gorm:"serializer:json"`
}

type APIResponse struct {
	Code int         `json:"code"`
	Data interface{} `json:"data,omitempty"`
	Msg  string      `json:"msg,omitempty"`
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
	BatchID string                   `json:"batch_id"`
	Results []BatchOperationResult `json:"results"`
}

type BatchOperationResult struct {
	ID      string `json:"id"`
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
}

type StatusResponse struct {
	ID       string  `json:"id"`
	Status   string  `json:"status"`
	Progress float64 `json:"progress,omitempty"`
}
