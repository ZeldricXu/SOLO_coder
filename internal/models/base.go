package models

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type BaseModel struct {
	ID        string         `gorm:"primaryKey;type:uuid" json:"id"`
	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"deleted_at,omitempty"`
}

func (m *BaseModel) BeforeCreate(tx *gorm.DB) error {
	if m.ID == "" {
		m.ID = uuid.New().String()
	}
	return nil
}

type Entity struct {
	BaseModel
	Type       string                 `json:"type"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `gorm:"type:jsonb" json:"attributes"`
}

type Config struct {
	BaseModel
	ConfigID   string                 `json:"config_id"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `gorm:"type:jsonb" json:"parameters"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  *time.Time             `json:"applied_at"`
}

type RunInstance struct {
	BaseModel
	RunID       string                 `json:"run_id"`
	EntityID    string                 `json:"entity_id"`
	Phase       string                 `json:"phase"`
	Progress    float64                `json:"progress"`
	StartedAt   *time.Time             `json:"started_at"`
	CompletedAt *time.Time             `json:"completed_at"`
	ErrorDetail map[string]interface{} `gorm:"type:jsonb" json:"error_detail,omitempty"`
}

type MetricsSnapshot struct {
	BaseModel
	SnapshotID string                 `json:"snapshot_id"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]interface{} `gorm:"type:jsonb" json:"metrics"`
	Dimensions map[string]string      `gorm:"type:jsonb" json:"dimensions"`
}
