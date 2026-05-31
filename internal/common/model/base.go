package model

import (
	"time"
)

type BaseModel struct {
	ID        string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

type Entity struct {
	BaseModel
	Type       string                 `gorm:"type:varchar(32);index" json:"type"`
	Status     string                 `gorm:"type:varchar(32);index" json:"status"`
	Attributes map[string]interface{} `gorm:"type:jsonb" json:"attributes"`
}

type ConfigDefinition struct {
	BaseModel
	ConfigID   string                 `gorm:"type:varchar(64);index" json:"config_id"`
	Namespace  string                 `gorm:"type:varchar(64);index" json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `gorm:"type:jsonb" json:"parameters"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  *time.Time             `json:"applied_at"`
}

type RunInstance struct {
	BaseModel
	RunID       string                 `gorm:"type:varchar(64);index" json:"run_id"`
	EntityID    string                 `gorm:"type:varchar(64);index" json:"entity_id"`
	Phase       string                 `gorm:"type:varchar(32);index" json:"phase"`
	Progress    float64                `json:"progress"`
	StartedAt   *time.Time             `json:"started_at"`
	CompletedAt *time.Time             `json:"completed_at"`
	ErrorDetail string                 `gorm:"type:text" json:"error_detail"`
}

type StatsSnapshot struct {
	BaseModel
	SnapshotID string                 `gorm:"type:varchar(64);index" json:"snapshot_id"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]interface{} `gorm:"type:jsonb" json:"metrics"`
	Dimensions map[string]interface{} `gorm:"type:jsonb" json:"dimensions"`
}
