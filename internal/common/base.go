package common

import (
	"time"
)

type BaseEntity struct {
	ID        string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Type      string    `gorm:"type:varchar(32)" json:"type"`
	Status    string    `gorm:"type:varchar(32)" json:"status"`
	Attributes map[string]string `gorm:"type:jsonb;serializer:json" json:"attributes"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

type BaseConfig struct {
	ConfigID  string                 `gorm:"primaryKey;type:varchar(64)" json:"config_id"`
	Namespace string                 `gorm:"type:varchar(64);index" json:"namespace"`
	Version   int                    `json:"version"`
	Parameters map[string]interface{} `gorm:"type:jsonb;serializer:json" json:"parameters"`
	Enabled   bool                   `json:"enabled"`
	AppliedAt time.Time              `json:"applied_at"`
}

type BaseRun struct {
	RunID       string    `gorm:"primaryKey;type:varchar(64)" json:"run_id"`
	EntityID    string    `gorm:"type:varchar(64);index" json:"entity_id"`
	Phase       string    `gorm:"type:varchar(32)" json:"phase"`
	Progress    float64   `json:"progress"`
	StartedAt   time.Time `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	ErrorDetail *string   `json:"error_detail,omitempty"`
}

type BaseSnapshot struct {
	SnapshotID string                 `gorm:"primaryKey;type:varchar(64)" json:"snapshot_id"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `gorm:"type:jsonb;serializer:json" json:"metrics"`
	Dimensions map[string]string      `gorm:"type:jsonb;serializer:json" json:"dimensions"`
}
