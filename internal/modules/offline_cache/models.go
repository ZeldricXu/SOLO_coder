package offline_cache

import (
	"time"

	"edgescheduler/internal/common/models"
)

type CacheStatus string
type SyncStatus string

const (
	CacheStatusPending  CacheStatus = "pending"
	CacheStatusSynced   CacheStatus = "synced"
	CacheStatusFailed   CacheStatus = "failed"

	SyncStatusIdle      SyncStatus = "idle"
	SyncStatusSyncing   SyncStatus = "syncing"
	SyncStatusCompleted SyncStatus = "completed"
	SyncStatusFailed    SyncStatus = "failed"
)

type CachedData struct {
	models.BaseModel
	CacheKey    string                 `gorm:"type:varchar(100);not null;index" json:"cache_key"`
	DeviceID  string                 `gorm:"type:varchar(50);not null;index" json:"device_id"`
	DataType  string                 `gorm:"type:varchar(50);not null;index" json:"data_type"`
	Payload   map[string]interface{} `gorm:"type:jsonb;not null" json:"payload"`
	Status    CacheStatus            `gorm:"type:varchar(20);index" json:"status"`
	RetryCount int                   `gorm:"default:0" json:"retry_count"`
	SizeBytes int64                  `json:"size_bytes"`
	ExpiresAt *time.Time            `json:"expires_at,omitempty"`
	SyncedAt  *time.Time            `json:"synced_at,omitempty"`
	ErrorMsg  string                `gorm:"type:text" json:"error_msg,omitempty"`
}

type SyncJob struct {
	models.BaseModel
	JobID     string     `gorm:"type:varchar(50);not null;uniqueIndex" json:"job_id"`
	DeviceID   string     `gorm:"type:varchar(50);not null;index" json:"device_id"`
	Status    SyncStatus `gorm:"type:varchar(20);index" json:"status"`
	TotalCount int      `json:"total_count"`
	SyncedCount int     `json:"synced_count"`
	FailedCount int     `json:"failed_count"`
	StartedAt *time.Time `json:"started_at,omitempty"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
}

type CacheRequest struct {
	DeviceID string                 `json:"device_id" binding:"required"`
	DataType string                 `json:"data_type" binding:"required"`
	Payload  map[string]interface{} `json:"payload" binding:"required"`
	TTLSeconds int                 `json:"ttl_seconds"`
}

type SyncResponse struct {
	JobID       string `json:"job_id"`
	Status      string `json:"status"`
	TotalCount  int    `json:"total_count"`
	SyncedCount int    `json:"synced_count"`
	FailedCount int    `json:"failed_count"`
}
