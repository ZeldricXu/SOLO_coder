package entity

import (
	"time"
)

type BaseEntity struct {
	ID        string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Type      string    `gorm:"type:varchar(64);not null" json:"type"`
	Status    string    `gorm:"type:varchar(64);not null;index" json:"status"`
	CreatedAt time.Time `gorm:"not null" json:"created_at"`
	UpdatedAt time.Time `gorm:"not null" json:"updated_at"`
}

type Config struct {
	ConfigID   string                 `gorm:"primaryKey;type:varchar(64)" json:"config_id"`
	Namespace  string                 `gorm:"type:varchar(128);not null;index" json:"namespace"`
	Version    int                    `gorm:"not null;index" json:"version"`
	Parameters map[string]interface{} `gorm:"type:jsonb" json:"parameters"`
	Enabled    bool                   `gorm:"default:true" json:"enabled"`
	AppliedAt  time.Time              `gorm:"not null" json:"applied_at"`
	CreatedAt  time.Time              `gorm:"not null" json:"created_at"`
}

type RunInstance struct {
	RunID       string     `gorm:"primaryKey;type:varchar(64)" json:"run_id"`
	EntityID    string     `gorm:"type:varchar(64);not null;index" json:"entity_id"`
	Phase       string     `gorm:"type:varchar(64);not null;index" json:"phase"`
	Progress    float64    `gorm:"type:decimal(5,4)" json:"progress"`
	StartedAt   time.Time  `gorm:"not null" json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	ErrorDetail *string    `gorm:"type:text" json:"error_detail,omitempty"`
	CreatedAt   time.Time  `gorm:"not null" json:"created_at"`
	UpdatedAt   time.Time  `gorm:"not null" json:"updated_at"`
}

type Snapshot struct {
	SnapshotID string                 `gorm:"primaryKey;type:varchar(64)" json:"snapshot_id"`
	Timestamp  time.Time              `gorm:"not null;index" json:"timestamp"`
	Metrics    map[string]interface{} `gorm:"type:jsonb;not null" json:"metrics"`
	Dimensions map[string]string      `gorm:"type:jsonb" json:"dimensions,omitempty"`
	CreatedAt  time.Time              `gorm:"not null" json:"created_at"`
}
