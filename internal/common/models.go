package common

import (
	"encoding/json"
	"time"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Type       string                 `json:"type" gorm:"type:varchar(32);index"`
	Status     string                 `json:"status" gorm:"type:varchar(32);index"`
	Attributes map[string]interface{} `json:"attributes" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
	TenantID   string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
}

type Config struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey;type:varchar(64)"`
	Namespace  string                 `json:"namespace" gorm:"type:varchar(64);index"`
	Version    int                    `json:"version" gorm:"index"`
	Parameters map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Enabled    bool                   `json:"enabled" gorm:"index"`
	AppliedAt  *time.Time             `json:"applied_at"`
	TenantID   string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type RunInstance struct {
	RunID        string                 `json:"run_id" gorm:"primaryKey;type:varchar(64)"`
	EntityID     string                 `json:"entity_id" gorm:"type:varchar(64);index"`
	Phase        string                 `json:"phase" gorm:"type:varchar(32);index"`
	Progress     float64                `json:"progress" gorm:"type:float"`
	StartedAt    time.Time              `json:"started_at"`
	CompletedAt  *time.Time             `json:"completed_at"`
	ErrorDetail  *string                `json:"error_detail" gorm:"type:text"`
	Metadata     map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	TenantID     string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
}

type Snapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey;type:varchar(64)"`
	Timestamp  time.Time              `json:"timestamp" gorm:"index"`
	Metrics    map[string]float64     `json:"metrics" gorm:"type:jsonb"`
	Dimensions map[string]string      `json:"dimensions" gorm:"type:jsonb"`
	TenantID   string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
}

type BaseResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

func (e *Entity) TableName() string {
	return "entities"
}

func (c *Config) TableName() string {
	return "configs"
}

func (r *RunInstance) TableName() string {
	return "run_instances"
}

func (s *Snapshot) TableName() string {
	return "snapshots"
}

func NewEntity(id, entityType, tenantID string, attributes map[string]interface{}) *Entity {
	if attributes == nil {
		attributes = make(map[string]interface{})
	}
	now := time.Now().UTC()
	return &Entity{
		ID:         id,
		Type:       entityType,
		Status:     "pending",
		Attributes: attributes,
		CreatedAt:  now,
		UpdatedAt:  now,
		TenantID:   tenantID,
	}
}

func (e *Entity) ToJSON() (string, error) {
	b, err := json.Marshal(e)
	if err != nil {
		return "", err
	}
	return string(b), nil
}
