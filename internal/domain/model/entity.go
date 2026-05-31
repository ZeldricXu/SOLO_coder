package model

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
	Version    int64                  `json:"version" gorm:"default:1"`
}

func (e *Entity) MarshalJSON() ([]byte, error) {
	type Alias Entity
	return json.Marshal(&struct {
		*Alias
	}{
		Alias: (*Alias)(e),
	})
}

type Config struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey;type:varchar(64)"`
	Namespace  string                 `json:"namespace" gorm:"type:varchar(64);index"`
	Version    int                    `json:"version" gorm:"index"`
	Parameters map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Enabled    bool                   `json:"enabled" gorm:"default:true"`
	AppliedAt  *time.Time             `json:"applied_at"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type RunInstance struct {
	RunID       string                 `json:"run_id" gorm:"primaryKey;type:varchar(64)"`
	EntityID    string                 `json:"entity_id" gorm:"type:varchar(64);index"`
	Phase       string                 `json:"phase" gorm:"type:varchar(32);index"`
	Progress    float64                `json:"progress" gorm:"default:0"`
	StartedAt   time.Time              `json:"started_at"`
	CompletedAt *time.Time             `json:"completed_at"`
	ErrorDetail *string                `json:"error_detail"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type Snapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey;type:varchar(64)"`
	Timestamp  time.Time              `json:"timestamp" gorm:"index"`
	Metrics    map[string]float64     `json:"metrics" gorm:"type:jsonb"`
	Dimensions map[string]string      `json:"dimensions" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at"`
}

const (
	StatusPending    = "pending"
	StatusRunning    = "running"
	StatusSuccess    = "success"
	StatusFailed     = "failed"
	StatusRollback   = "rollback"
	StatusCancelled  = "cancelled"
	StatusCompleted  = "completed"
	StatusInProgress = "in_progress"
)

const (
	PhasePending     = "pending"
	PhaseDownloading = "downloading"
	PhaseInstalling  = "installing"
	PhaseVerifying   = "verifying"
	PhaseExecuting   = "executing"
	PhaseCompleted   = "completed"
	PhaseRollback    = "rollback"
	PhaseFailed      = "failed"
)
