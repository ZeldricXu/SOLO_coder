package models

import (
	"time"
)

type Resource struct {
	ID         string                 `gorm:"primaryKey" json:"id"`
	Type       string                 `gorm:"index" json:"type"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `gorm:"serializer:json" json:"attributes"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type ConfigRecord struct {
	ConfigID   string                 `gorm:"primaryKey" json:"config_id"`
	Namespace  string                 `gorm:"index" json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `gorm:"serializer:json" json:"parameters"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  *time.Time             `json:"applied_at"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type RunInstance struct {
	RunID        string    `gorm:"primaryKey" json:"run_id"`
	EntityID     string    `gorm:"index" json:"entity_id"`
	Phase        string    `json:"phase"`
	Progress     float64   `json:"progress"`
	StartedAt    time.Time `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at"`
	ErrorDetail  *string   `json:"error_detail"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
}

type MetricsSnapshot struct {
	SnapshotID string                 `gorm:"primaryKey" json:"snapshot_id"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]interface{} `gorm:"serializer:json" json:"metrics"`
	Dimensions map[string]string      `gorm:"serializer:json" json:"dimensions"`
	CreatedAt  time.Time              `json:"created_at"`
}

type BaseResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

func SuccessResponse(data interface{}) BaseResponse {
	return BaseResponse{Code: 200, Data: data}
}

func CreatedResponse(data interface{}) BaseResponse {
	return BaseResponse{Code: 201, Data: data}
}

func ErrorResponse(code int, message string) BaseResponse {
	return BaseResponse{Code: code, Message: message}
}
