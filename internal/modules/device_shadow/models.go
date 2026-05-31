package device_shadow

import (
	"time"

	"edgescheduler/internal/common/models"
)

type ShadowSyncStatus string
type ShadowVersionConflictAction string

const (
	ShadowSyncStatusSynced     ShadowSyncStatus = "synced"
	ShadowSyncStatusOutOfSync  ShadowSyncStatus = "out_of_sync"
	ShadowSyncStatusPending    ShadowSyncStatus = "pending"
	ShadowSyncStatusConflicted ShadowSyncStatus = "conflicted"
	ShadowSyncStatusSyncing    ShadowSyncStatus = "syncing"

	ConflictActionMerge  ShadowVersionConflictAction = "merge"
	ConflictActionLatest ShadowVersionConflictAction = "use_latest"
	ConflictActionManual ShadowVersionConflictAction = "manual"
)

type DeviceShadow struct {
	models.BaseModel
	ShadowID      string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"shadow_id"`
	DeviceID      string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"device_id"`
	Version       int                    `gorm:"default:1;index" json:"version"`
	Desired       map[string]interface{} `gorm:"type:jsonb" json:"desired"`
	Reported      map[string]interface{} `gorm:"type:jsonb" json:"reported"`
	Delta         map[string]interface{} `gorm:"type:jsonb" json:"delta"`
	Metadata      map[string]interface{} `gorm:"type:jsonb" json:"metadata"`
	SyncStatus    ShadowSyncStatus       `gorm:"type:varchar(20);index" json:"sync_status"`
	ConflictAction ShadowVersionConflictAction `gorm:"type:varchar(20);default:'merge'" json:"conflict_action"`
	LastSyncedAt  *time.Time             `json:"last_synced_at,omitempty"`
	LastDesiredUpdate *time.Time         `json:"last_desired_update,omitempty"`
	LastReportedUpdate *time.Time        `json:"last_reported_update,omitempty"`
}

type ShadowOperationLog struct {
	models.BaseModel
	LogID         string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"log_id"`
	DeviceID      string                 `gorm:"type:varchar(50);not null;index" json:"device_id"`
	Operation     string                 `gorm:"type:varchar(50);index" json:"operation"`
	OldVersion    int                    `json:"old_version"`
	NewVersion    int                    `json:"new_version"`
	Source        string                 `gorm:"type:varchar(50)" json:"source"`
	Changes       map[string]interface{} `gorm:"type:jsonb" json:"changes"`
	OldDesired    map[string]interface{} `gorm:"type:jsonb" json:"old_desired,omitempty"`
	NewDesired    map[string]interface{} `gorm:"type:jsonb" json:"new_desired,omitempty"`
	OldReported   map[string]interface{} `gorm:"type:jsonb" json:"old_reported,omitempty"`
	NewReported   map[string]interface{} `gorm:"type:jsonb" json:"new_reported,omitempty"`
}

type ShadowVersionHistory struct {
	models.BaseModel
	HistoryID     string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"history_id"`
	DeviceID      string                 `gorm:"type:varchar(50);not null;index" json:"device_id"`
	Version       int                    `gorm:"index" json:"version"`
	Desired       map[string]interface{} `gorm:"type:jsonb" json:"desired"`
	Reported      map[string]interface{} `gorm:"type:jsonb" json:"reported"`
	CreatedAt     time.Time              `gorm:"index" json:"timestamp"`
	Source        string                 `gorm:"type:varchar(50)" json:"source"`
}

type ShadowUpdateRequest struct {
	DeviceID string                 `json:"device_id" binding:"required"`
	Desired  map[string]interface{} `json:"desired"`
	Reported map[string]interface{} `json:"reported"`
	Source   string                 `json:"source"`
	Version  int                    `json:"version"`
}

type ShadowQueryRequest struct {
	DeviceID   string `json:"device_id" binding:"required"`
	IncludeDelta bool `json:"include_delta"`
	IncludeMetadata bool `json:"include_metadata"`
}
