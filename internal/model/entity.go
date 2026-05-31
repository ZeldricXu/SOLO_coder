package model

import (
	"time"
)

type Entity struct {
	ID         string                 `gorm:"primaryKey;column:id" json:"id"`
	Type       string                 `gorm:"column:type;index" json:"type"`
	Status     string                 `gorm:"column:status;index" json:"status"`
	Attributes map[string]interface{} `gorm:"column:attributes;type:jsonb" json:"attributes"`
	CreatedAt  time.Time              `gorm:"column:created_at" json:"created_at"`
	UpdatedAt  time.Time              `gorm:"column:updated_at" json:"updated_at"`
}

func (Entity) TableName() string {
	return "entities"
}

type ConfigDefinition struct {
	ConfigID   string                 `gorm:"primaryKey;column:config_id" json:"config_id"`
	Namespace  string                 `gorm:"column:namespace;index" json:"namespace"`
	Version    int                    `gorm:"column:version" json:"version"`
	Parameters map[string]interface{} `gorm:"column:parameters;type:jsonb" json:"parameters"`
	Enabled    bool                   `gorm:"column:enabled" json:"enabled"`
	AppliedAt  *time.Time             `gorm:"column:applied_at" json:"applied_at"`
	CreatedAt  time.Time              `gorm:"column:created_at" json:"created_at"`
	UpdatedAt  time.Time              `gorm:"column:updated_at" json:"updated_at"`
}

func (ConfigDefinition) TableName() string {
	return "config_definitions"
}

type RunInstance struct {
	RunID       string     `gorm:"primaryKey;column:run_id" json:"run_id"`
	EntityID    string     `gorm:"column:entity_id;index" json:"entity_id"`
	Phase       string     `gorm:"column:phase" json:"phase"`
	Progress    float64    `gorm:"column:progress" json:"progress"`
	StartedAt   time.Time  `gorm:"column:started_at" json:"started_at"`
	CompletedAt *time.Time `gorm:"column:completed_at" json:"completed_at"`
	ErrorDetail *string    `gorm:"column:error_detail" json:"error_detail"`
}

func (RunInstance) TableName() string {
	return "run_instances"
}

type StatSnapshot struct {
	SnapshotID string                 `gorm:"primaryKey;column:snapshot_id" json:"snapshot_id"`
	Timestamp  time.Time              `gorm:"column:timestamp;index" json:"timestamp"`
	Metrics    map[string]interface{} `gorm:"column:metrics;type:jsonb" json:"metrics"`
	Dimensions map[string]string      `gorm:"column:dimensions;type:jsonb" json:"dimensions"`
}

func (StatSnapshot) TableName() string {
	return "stat_snapshots"
}

type Pagination struct {
	Page     int `json:"page"`
	PageSize int `json:"page_size"`
	Total    int64 `json:"total"`
}

type APIResponse struct {
	Code    int         `json:"code"`
	Data    interface{} `json:"data,omitempty"`
	Message string      `json:"message,omitempty"`
	Error   *ErrorDetail `json:"error,omitempty"`
}

type ErrorDetail struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	Details string `json:"details,omitempty"`
}

type PaginatedResponse struct {
	Code       int         `json:"code"`
	Data       interface{} `json:"data,omitempty"`
	Pagination Pagination  `json:"pagination"`
	Message    string      `json:"message,omitempty"`
}
