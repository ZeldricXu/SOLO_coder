package models

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type BaseModel struct {
	ID        string    `gorm:"type:varchar(36);primaryKey" json:"id"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"deleted_at,omitempty"`
}

func (m *BaseModel) BeforeCreate(tx *gorm.DB) error {
	if m.ID == "" {
		m.ID = uuid.New().String()
	}
	m.CreatedAt = time.Now().UTC()
	m.UpdatedAt = time.Now().UTC()
	return nil
}

func (m *BaseModel) BeforeUpdate(tx *gorm.DB) error {
	m.UpdatedAt = time.Now().UTC()
	return nil
}

type Entity struct {
	BaseModel
	Type       string                 `gorm:"type:varchar(50);not null;index" json:"type"`
	Status     string                 `gorm:"type:varchar(30);not null;index" json:"status"`
	Attributes map[string]interface{} `gorm:"type:jsonb" json:"attributes"`
}

type Config struct {
	BaseModel
	ConfigID   string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"config_id"`
	Namespace  string                 `gorm:"type:varchar(50);not null;index" json:"namespace"`
	Version    int                    `gorm:"not null;default:1" json:"version"`
	Parameters map[string]interface{} `gorm:"type:jsonb" json:"parameters"`
	Enabled    bool                   `gorm:"default:true" json:"enabled"`
	AppliedAt  *time.Time             `json:"applied_at,omitempty"`
}

type RunInstance struct {
	BaseModel
	RunID       string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"run_id"`
	EntityID    string                 `gorm:"type:varchar(36);not null;index" json:"entity_id"`
	Phase       string                 `gorm:"type:varchar(30);not null;index" json:"phase"`
	Progress    float64                `gorm:"default:0" json:"progress"`
	StartedAt   *time.Time             `json:"started_at,omitempty"`
	CompletedAt *time.Time             `json:"completed_at,omitempty"`
	ErrorDetail map[string]interface{} `gorm:"type:jsonb" json:"error_detail,omitempty"`
}

type Snapshot struct {
	BaseModel
	SnapshotID string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"snapshot_id"`
	Timestamp  time.Time              `gorm:"not null;index" json:"timestamp"`
	Metrics    map[string]interface{} `gorm:"type:jsonb" json:"metrics"`
	Dimensions map[string]interface{} `gorm:"type:jsonb" json:"dimensions"`
}

type APIResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

type BatchOperation struct {
	Action string                 `json:"action"`
	ID     string                 `json:"id"`
	Params map[string]interface{} `json:"params,omitempty"`
}

type BatchRequest struct {
	Operations []BatchOperation `json:"operations"`
}

type BatchResult struct {
	ID      string      `json:"id"`
	Success bool        `json:"success"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

type BatchResponse struct {
	BatchID string        `json:"batch_id"`
	Results []BatchResult `json:"results"`
}
