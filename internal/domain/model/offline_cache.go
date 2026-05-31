package model

import (
	"time"
)

type OfflineDataRecord struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	DeviceID    string                 `json:"device_id" gorm:"type:varchar(64);index"`
	DataType    string                 `json:"data_type" gorm:"type:varchar(64);index"`
	Payload     map[string]interface{} `json:"payload" gorm:"type:jsonb"`
	DataSize    int                    `json:"data_size"`
	Checksum    string                 `json:"checksum" gorm:"type:varchar(128)"`
	Status      string                 `json:"status" gorm:"type:varchar(32);index"`
	Priority    int                    `json:"priority" gorm:"default:0;index"`
	Strategy    string                 `json:"strategy" gorm:"type:varchar(32);default:'fifo'"`
	RetryCount  int                    `json:"retry_count" gorm:"default:0"`
	MaxRetry    int                    `json:"max_retry" gorm:"default:3"`
	NextRetryAt *time.Time             `json:"next_retry_at"`
	SyncedAt    *time.Time             `json:"synced_at"`
	ErrorMsg    *string                `json:"error_msg"`
	Timestamp   time.Time              `json:"timestamp" gorm:"index"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type SyncSession struct {
	ID           string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	DeviceID     string    `json:"device_id" gorm:"type:varchar(64);index"`
	SessionID    string    `json:"session_id" gorm:"type:varchar(128);index"`
	Strategy     string    `json:"strategy" gorm:"type:varchar(32);default:'fifo'"`
	Status       string    `json:"status" gorm:"type:varchar(32);index"`
	TotalRecords int       `json:"total_records"`
	SyncedCount  int       `json:"synced_count"`
	FailedCount  int       `json:"failed_count"`
	DataSize     int64     `json:"data_size"`
	StartTime    time.Time `json:"start_time"`
	EndTime      *time.Time `json:"end_time"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
}

const (
	OfflineStatusPending   = "pending"
	OfflineStatusSyncing   = "syncing"
	OfflineStatusSynced    = "synced"
	OfflineStatusFailed    = "failed"
	OfflineStatusDiscarded = "discarded"
)

const (
	DataTypeTelemetry = "telemetry"
	DataTypeEvent     = "event"
	DataTypeLog       = "log"
	DataTypeAlert     = "alert"
)
