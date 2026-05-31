package config

import (
	"time"
)

type ConfigStatus string

const (
	ConfigStatusDraft     ConfigStatus = "draft"
	ConfigStatusPublished ConfigStatus = "published"
	ConfigStatusRolledBack ConfigStatus = "rolled_back"
	ConfigStatusArchived  ConfigStatus = "archived"
)

type Config struct {
	ID          string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ConfigKey   string                 `gorm:"type:varchar(256);index:idx_namespace_key;not null" json:"config_key"`
	Namespace   string                 `gorm:"type:varchar(64);index:idx_namespace_key;not null" json:"namespace"`
	Version     int                    `gorm:"not null" json:"version"`
	Value       map[string]interface{} `gorm:"type:jsonb;serializer:json;not null" json:"value"`
	Description string                 `gorm:"type:text" json:"description"`
	Status      ConfigStatus           `gorm:"type:varchar(32);index" json:"status"`
	Labels      map[string]string      `gorm:"type:jsonb;serializer:json" json:"labels"`
	CreatedBy   string                 `gorm:"type:varchar(64)" json:"created_by"`
	ApprovedBy  string                 `gorm:"type:varchar(64)" json:"approved_by,omitempty"`
	PublishedAt *time.Time             `json:"published_at,omitempty"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type ConfigSnapshot struct {
	ID          string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ConfigID    string                 `gorm:"type:varchar(64);index;not null" json:"config_id"`
	ConfigKey   string                 `gorm:"type:varchar(256)" json:"config_key"`
	Namespace   string                 `gorm:"type:varchar(64)" json:"namespace"`
	Version     int                    `json:"version"`
	Value       map[string]interface{} `gorm:"type:jsonb;serializer:json" json:"value"`
	Labels      map[string]string      `gorm:"type:jsonb;serializer:json" json:"labels"`
	SnapshotAt  time.Time              `json:"snapshot_at"`
	CreatedBy   string                 `gorm:"type:varchar(64)" json:"created_by"`
}

type RollbackHistory struct {
	ID            string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ConfigID      string    `gorm:"type:varchar(64);index;not null" json:"config_id"`
	FromVersion   int       `json:"from_version"`
	ToVersion     int       `json:"to_version"`
	Reason        string    `gorm:"type:text" json:"reason"`
	RolledBackBy  string    `gorm:"type:varchar(64)" json:"rolled_back_by"`
	RolledBackAt  time.Time `json:"rolled_back_at"`
}

type CreateConfigRequest struct {
	ConfigKey   string                 `json:"config_key" binding:"required"`
	Namespace   string                 `json:"namespace" binding:"required"`
	Value       map[string]interface{} `json:"value" binding:"required"`
	Description string                 `json:"description"`
	Labels      map[string]string      `json:"labels"`
}

type UpdateConfigRequest struct {
	Value       map[string]interface{} `json:"value" binding:"required"`
	Description string                 `json:"description"`
	Labels      map[string]string      `json:"labels"`
}

type PublishConfigRequest struct {
	ApprovedBy string `json:"approved_by"`
}

type RollbackConfigRequest struct {
	TargetVersion int    `json:"target_version" binding:"required"`
	Reason        string `json:"reason"`
}

type DiffResult struct {
	Added    map[string]interface{} `json:"added"`
	Removed  map[string]interface{} `json:"removed"`
	Modified map[string]DiffItem    `json:"modified"`
}

type DiffItem struct {
	OldValue interface{} `json:"old_value"`
	NewValue interface{} `json:"new_value"`
}
