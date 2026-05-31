package models

import (
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

type ConfigEntry struct {
	ConfigID  string                 `json:"config_id" gorm:"primaryKey"`
	Namespace string                 `json:"namespace"`
	Version   int                    `json:"version"`
	Params    map[string]interface{} `json:"parameters" gorm:"serializer:json"`
	Enabled   bool                   `json:"enabled"`
	AppliedAt time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID       string    `json:"run_id" gorm:"primaryKey"`
	EntityID    string    `json:"entity_id"`
	Phase       string    `json:"phase"`
	Progress    float64   `json:"progress"`
	StartedAt   time.Time `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	ErrorDetail string    `json:"error_detail,omitempty"`
}

type MetricSnapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics" gorm:"serializer:json"`
	Dimensions map[string]string      `json:"dimensions" gorm:"serializer:json"`
}

type TimeSeriesPoint struct {
	Timestamp time.Time              `json:"ts"`
	Tags      map[string]string      `json:"tags,omitempty"`
	Fields    map[string]interface{} `json:"fields"`
}

type APIResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
	Error   string      `json:"error,omitempty"`
}

func SuccessResponse(data interface{}) *APIResponse {
	return &APIResponse{
		Code: 200,
		Data: data,
	}
}

func CreatedResponse(data interface{}) *APIResponse {
	return &APIResponse{
		Code: 201,
		Data: data,
	}
}

func ErrorResponse(code int, message string) *APIResponse {
	return &APIResponse{
		Code:  code,
		Error: message,
	}
}
