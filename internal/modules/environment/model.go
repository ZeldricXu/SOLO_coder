package environment

import (
	"depguard/internal/common/model"
	"time"
)

type Environment struct {
	model.BaseModel
	EnvID       string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"env_id"`
	Name        string                 `gorm:"type:varchar(128);not null" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	Type        string                 `gorm:"type:varchar(32);index;not null" json:"type"`
	Status      string                 `gorm:"type:varchar(32);index;not null" json:"status"`
	Config      map[string]interface{} `gorm:"type:jsonb" json:"config"`
	Resources   map[string]interface{} `gorm:"type:jsonb" json:"resources"`
	OwnerID     string                 `gorm:"type:varchar(64);index" json:"owner_id"`
	ProjectID   string                 `gorm:"type:varchar(64);index" json:"project_id"`
	ExpiresAt   *time.Time             `json:"expires_at"`
	StartedAt   *time.Time             `json:"started_at"`
	StoppedAt   *time.Time             `json:"stopped_at"`
	AutoRecycle bool                   `gorm:"default:true" json:"auto_recycle"`
	AccessURL   string                 `gorm:"type:varchar(512)" json:"access_url"`
}

type UsageStats struct {
	model.BaseModel
	EnvID        string  `gorm:"type:varchar(64);index" json:"env_id"`
	Date         string  `gorm:"type:varchar(16);index" json:"date"`
	CPUUsage     float64 `json:"cpu_usage"`
	MemoryUsage  float64 `json:"memory_usage"`
	StorageUsage float64 `json:"storage_usage"`
	NetworkIn    int64   `json:"network_in"`
	NetworkOut   int64   `json:"network_out"`
	UptimeMinutes int64  `json:"uptime_minutes"`
	Cost         float64 `json:"cost"`
}

type RecyclePolicy struct {
	model.BaseModel
	PolicyID      string `gorm:"type:varchar(64);uniqueIndex;not null" json:"policy_id"`
	Name          string `gorm:"type:varchar(128);not null" json:"name"`
	EnvType       string `gorm:"type:varchar(32);index" json:"env_type"`
	IdleMinutes   int    `gorm:"default:120" json:"idle_minutes"`
	MaxLifetime   int    `gorm:"default:1440" json:"max_lifetime_minutes"`
	AutoRecycle   bool   `gorm:"default:true" json:"auto_recycle"`
	NotifyBefore  int    `gorm:"default:30" json:"notify_before_minutes"`
	Enabled       bool   `gorm:"default:true" json:"enabled"`
}

type DynamicConfig struct {
	model.BaseModel
	ConfigKey    string                 `gorm:"type:varchar(128);uniqueIndex;not null" json:"config_key"`
	ConfigValue  map[string]interface{} `gorm:"type:jsonb;not null" json:"config_value"`
	Description  string                 `gorm:"type:varchar(512)" json:"description"`
	IsActive     bool                   `gorm:"default:true" json:"is_active"`
	Version      int                    `gorm:"default:1" json:"version"`
	LastModifiedBy string               `gorm:"type:varchar(64)" json:"last_modified_by"`
}

type ConfigChangeLog struct {
	model.BaseModel
	ConfigKey    string                 `gorm:"type:varchar(128);index" json:"config_key"`
	OldValue     map[string]interface{} `gorm:"type:jsonb" json:"old_value"`
	NewValue     map[string]interface{} `gorm:"type:jsonb" json:"new_value"`
	ChangeType   string                 `gorm:"type:varchar(32)" json:"change_type"`
	ChangedBy    string                 `gorm:"type:varchar(64)" json:"changed_by"`
	ChangeReason string                 `gorm:"type:varchar(512)" json:"change_reason"`
}

type EnvironmentRequest struct {
	model.BaseModel
	RequestID   string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"request_id"`
	RequesterID string                 `gorm:"type:varchar(64);index" json:"requester_id"`
	ProjectID   string                 `gorm:"type:varchar(64);index" json:"project_id"`
	EnvType     string                 `gorm:"type:varchar(32);index" json:"env_type"`
	Config      map[string]interface{} `gorm:"type:jsonb" json:"config"`
	Reason      string                 `gorm:"type:text" json:"reason"`
	Status      string                 `gorm:"type:varchar(32);index" json:"status"`
	ApproverID  string                 `gorm:"type:varchar(64)" json:"approver_id"`
	ApprovedAt  *time.Time             `json:"approved_at"`
	ExpiresAt   *time.Time             `json:"expires_at"`
}
