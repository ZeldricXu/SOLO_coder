package entity

import (
	"time"
)

type Provider struct {
	ID         string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name       string                 `gorm:"type:varchar(128);not null;uniqueIndex" json:"name"`
	Type       string                 `gorm:"type:varchar(64);not null" json:"type"`
	BaseURL    string                 `gorm:"type:varchar(512);not null" json:"base_url"`
	APIKey     string                 `gorm:"type:varchar(512)" json:"api_key,omitempty"`
	Timeout    int                    `json:"timeout"`
	MaxRetries int                    `json:"max_retries"`
	Enabled    bool                   `gorm:"default:true" json:"enabled"`
	Priority   int                    `json:"priority"`
	Weight     int                    `json:"weight"`
	Config     map[string]interface{} `gorm:"type:jsonb" json:"config,omitempty"`
	CreatedAt  time.Time              `gorm:"not null" json:"created_at"`
	UpdatedAt  time.Time              `gorm:"not null" json:"updated_at"`
}

type ModelEndpoint struct {
	ID         string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ModelID    string    `gorm:"type:varchar(64);not null;index" json:"model_id"`
	ProviderID string    `gorm:"type:varchar(64);not null;index" json:"provider_id"`
	Endpoint   string    `gorm:"type:varchar(512);not null" json:"endpoint"`
	Status     string    `gorm:"type:varchar(64);not null" json:"status"`
	Healthy    bool      `gorm:"default:true" json:"healthy"`
	LatencyP50 int64     `json:"latency_p50"`
	LatencyP99 int64     `json:"latency_p99"`
	ErrorRate  float64   `json:"error_rate"`
	CreatedAt  time.Time `gorm:"not null" json:"created_at"`
	UpdatedAt  time.Time `gorm:"not null" json:"updated_at"`
}

type InferenceRequest struct {
	RequestID  string                 `json:"request_id"`
	ModelID    string                 `json:"model_id"`
	Provider   string                 `json:"provider,omitempty"`
	Input      map[string]interface{} `json:"input"`
	Parameters map[string]interface{} `json:"parameters,omitempty"`
	Stream     bool                   `json:"stream,omitempty"`
	Priority   int                    `json:"priority,omitempty"`
	TraceID    string                 `json:"trace_id,omitempty"`
}

type InferenceResponse struct {
	RequestID string                 `json:"request_id"`
	ModelID   string                 `json:"model_id"`
	Provider  string                 `json:"provider"`
	Output    map[string]interface{} `json:"output"`
	Latency   int64                  `json:"latency_ms"`
	Tokens    InferenceTokens        `json:"tokens"`
	Error     string                 `json:"error,omitempty"`
}

type InferenceTokens struct {
	Prompt     int `json:"prompt"`
	Completion int `json:"completion"`
	Total      int `json:"total"`
}
