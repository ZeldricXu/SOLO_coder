package models

import "time"

type Entity struct {
	ID         string                 `json:"id"`
	Type       string                 `json:"type"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `json:"attributes"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type Config struct {
	ConfigID   string                 `json:"config_id"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID       string    `json:"run_id"`
	EntityID    string    `json:"entity_id"`
	Phase       string    `json:"phase"`
	Progress    float64   `json:"progress"`
	StartedAt   time.Time `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	ErrorDetail *string    `json:"error_detail,omitempty"`
}

type MetricsSnapshot struct {
	SnapshotID string                 `json:"snapshot_id"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics"`
	Dimensions map[string]string      `json:"dimensions"`
}

type APIResponse struct {
	Code    int         `json:"code"`
	Data    interface{} `json:"data,omitempty"`
	Message string      `json:"message,omitempty"`
}

type ResourceCreateRequest struct {
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

type ResourceCreateResponse struct {
	ID     string `json:"id"`
	Status string `json:"status"`
}

type ResourceStatusResponse struct {
	ID       string  `json:"id"`
	Status   string  `json:"status"`
	Progress float64 `json:"progress"`
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
	Results []map[string]interface{} `json:"results"`
}
