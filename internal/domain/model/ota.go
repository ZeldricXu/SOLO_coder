package model

import (
	"time"
)

type Firmware struct {
	ID           string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name         string                 `json:"name" gorm:"type:varchar(128)"`
	Version      string                 `json:"version" gorm:"type:varchar(64);index"`
	DeviceType   string                 `json:"device_type" gorm:"type:varchar(64);index"`
	HardwareModel string                `json:"hardware_model" gorm:"type:varchar(64)"`
	FileSize     int64                  `json:"file_size"`
	FileURL      string                 `json:"file_url" gorm:"type:varchar(512)"`
	Checksum     string                 `json:"checksum" gorm:"type:varchar(128)"`
	Signature    string                 `json:"signature" gorm:"type:varchar(512)"`
	ReleaseNotes string                 `json:"release_notes" gorm:"type:text"`
	Metadata     map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	IsActive     bool                   `json:"is_active" gorm:"default:true"`
	ReleasedAt   *time.Time             `json:"released_at"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

type DeltaPackage struct {
	ID               string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	FromVersion      string                 `json:"from_version" gorm:"type:varchar(64);index"`
	ToVersion        string                 `json:"to_version" gorm:"type:varchar(64);index"`
	DeviceType       string                 `json:"device_type" gorm:"type:varchar(64);index"`
	FileSize         int64                  `json:"file_size"`
	FileURL          string                 `json:"file_url" gorm:"type:varchar(512)"`
	Checksum         string                 `json:"checksum" gorm:"type:varchar(128)"`
	CompressionAlgo  string                 `json:"compression_algo" gorm:"type:varchar(32)"`
	DiffAlgo         string                 `json:"diff_algo" gorm:"type:varchar(32)"`
	Status           string                 `json:"status" gorm:"type:varchar(32);index"`
	Metadata         map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt        time.Time              `json:"created_at"`
	UpdatedAt        time.Time              `json:"updated_at"`
}

type OTAUpgradeTask struct {
	ID             string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	FirmwareID     string                 `json:"firmware_id" gorm:"type:varchar(64);index"`
	DeltaPackageID *string                `json:"delta_package_id" gorm:"type:varchar(64)"`
	Name           string                 `json:"name" gorm:"type:varchar(128)"`
	Description    string                 `json:"description" gorm:"type:text"`
	UpgradeType    string                 `json:"upgrade_type" gorm:"type:varchar(32)"`
	Strategy       string                 `json:"strategy" gorm:"type:varchar(32)"`
	Profile        string                 `json:"profile" gorm:"type:varchar(64);default:'default'"`
	Status         string                 `json:"status" gorm:"type:varchar(32);index"`
	TotalDevices   int                    `json:"total_devices"`
	SuccessCount   int                    `json:"success_count"`
	FailedCount    int                    `json:"failed_count"`
	RollbackCount  int                    `json:"rollback_count"`
	Progress       float64                `json:"progress"`
	AutoRollback   bool                   `json:"auto_rollback" gorm:"default:true"`
	FailureThreshold float64              `json:"failure_threshold" gorm:"default:0.1"`
	Parameters     map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	ScheduledAt    *time.Time             `json:"scheduled_at"`
	StartedAt      *time.Time             `json:"started_at"`
	CompletedAt    *time.Time             `json:"completed_at"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
}

type OTADeviceUpgrade struct {
	ID             string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TaskID         string                 `json:"task_id" gorm:"type:varchar(64);index"`
	DeviceID       string                 `json:"device_id" gorm:"type:varchar(64);index"`
	FirmwareID     string                 `json:"firmware_id" gorm:"type:varchar(64)"`
	FromVersion    string                 `json:"from_version" gorm:"type:varchar(64)"`
	ToVersion      string                 `json:"to_version" gorm:"type:varchar(64)"`
	Status         string                 `json:"status" gorm:"type:varchar(32);index"`
	Phase          string                 `json:"phase" gorm:"type:varchar(32)"`
	Progress       float64                `json:"progress"`
	ErrorCode      *string                `json:"error_code"`
	ErrorMessage   *string                `json:"error_message"`
	RetryCount     int                    `json:"retry_count" gorm:"default:0"`
	BatchNumber    int                    `json:"batch_number"`
	DownloadedAt   *time.Time             `json:"downloaded_at"`
	InstalledAt    *time.Time             `json:"installed_at"`
	CompletedAt    *time.Time             `json:"completed_at"`
	Metadata       map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
}

const (
	OTAStatusDraft     = "draft"
	OTAStatusPending   = "pending"
	OTAStatusRunning   = "running"
	OTAStatusPaused    = "paused"
	OTAStatusCompleted = "completed"
	OTAStatusFailed    = "failed"
	OTAStatusRollback  = "rollback"
)

const (
	OTAUpgradeTypeFull  = "full"
	OTAUpgradeTypeDelta = "delta"
)

const (
	OTAStrategyAllAtOnce    = "all_at_once"
	OTAStrategyBatch        = "batch"
	OTAStrategyCanary       = "canary"
)
