package model

import "time"

type OTAStatus string

const (
	OTAStatusPending      OTAStatus = "pending"
	OTAStatusGenerating   OTAStatus = "generating"
	OTAStatusReady        OTAStatus = "ready"
	OTAStatusRollingOut   OTAStatus = "rolling_out"
	OTAStatusPaused       OTAStatus = "paused"
	OTAStatusCompleted    OTAStatus = "completed"
	OTAStatusFailed       OTAStatus = "failed"
	OTAStatusRollingBack  OTAStatus = "rolling_back"
	OTAStatusRolledBack   OTAStatus = "rolled_back"
)

type DeviceUpgradeStatus string

const (
	DeviceUpgradePending    DeviceUpgradeStatus = "pending"
	DeviceUpgradeDownloading DeviceUpgradeStatus = "downloading"
	DeviceUpgradeInstalling DeviceUpgradeStatus = "installing"
	DeviceUpgradeSuccess    DeviceUpgradeStatus = "success"
	DeviceUpgradeFailed     DeviceUpgradeStatus = "failed"
	DeviceUpgradeRolledBack DeviceUpgradeStatus = "rolled_back"
)

type Firmware struct {
	FirmwareID      string            `json:"firmware_id" gorm:"primaryKey;type:varchar(64)"`
	Name            string            `json:"name" gorm:"type:varchar(128)"`
	Version         string            `json:"version" gorm:"type:varchar(32);index"`
	PreviousVersion string            `json:"previous_version" gorm:"type:varchar(32);index"`
	DeviceType      string            `json:"device_type" gorm:"type:varchar(64);index"`
	SizeBytes       int64             `json:"size_bytes"`
	Checksum        string            `json:"checksum" gorm:"type:varchar(64)"`
	DownloadURL     string            `json:"download_url" gorm:"type:varchar(512)"`
	DiffURL         string            `json:"diff_url" gorm:"type:varchar(512)"`
	DiffSizeBytes   int64             `json:"diff_size_bytes"`
	DiffChecksum    string            `json:"diff_checksum" gorm:"type:varchar(64)"`
	IsDelta         bool              `json:"is_delta" gorm:"default:false"`
	Metadata        map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt       time.Time         `json:"created_at" gorm:"index"`
	UpdatedAt       time.Time         `json:"updated_at" gorm:"index"`
}

func (f *Firmware) TableName() string {
	return "firmwares"
}

type OTAJob struct {
	JobID           string              `json:"job_id" gorm:"primaryKey;type:varchar(64)"`
	FirmwareID      string              `json:"firmware_id" gorm:"type:varchar(64);index"`
	Name            string              `json:"name" gorm:"type:varchar(128)"`
	Description     string              `json:"description" gorm:"type:varchar(512)"`
	Status          OTAStatus           `json:"status" gorm:"type:varchar(32);index"`
	DeviceIDs       []string            `json:"device_ids" gorm:"type:jsonb"`
	DeviceFilters   map[string]string   `json:"device_filters" gorm:"type:jsonb"`
	TotalDevices    int                 `json:"total_devices"`
	SuccessCount    int                 `json:"success_count"`
	FailedCount     int                 `json:"failed_count"`
	RolledBackCount int                 `json:"rolled_back_count"`
	CurrentBatch    int                 `json:"current_batch" gorm:"default:0"`
	TotalBatches    int                 `json:"total_batches" gorm:"default:1"`
	BatchSize       int                 `json:"batch_size" gorm:"default:100"`
	AutoRollback    bool                `json:"auto_rollback" gorm:"default:true"`
	FailureThreshold float64            `json:"failure_threshold" gorm:"type:decimal(5,4);default:0.1"`
	ForceUpdate     bool                `json:"force_update" gorm:"default:false"`
	StartAt         *time.Time          `json:"start_at"`
	CompletedAt     *time.Time          `json:"completed_at"`
	CreatedAt       time.Time           `json:"created_at" gorm:"index"`
	UpdatedAt       time.Time           `json:"updated_at" gorm:"index"`
}

func (j *OTAJob) TableName() string {
	return "ota_jobs"
}

type DeviceUpgrade struct {
	UpgradeID    string              `json:"upgrade_id" gorm:"primaryKey;type:varchar(64)"`
	JobID        string              `json:"job_id" gorm:"type:varchar(64);index"`
	DeviceID     string              `json:"device_id" gorm:"type:varchar(64);index"`
	Status       DeviceUpgradeStatus `json:"status" gorm:"type:varchar(32);index"`
	FromVersion  string              `json:"from_version" gorm:"type:varchar(32)"`
	ToVersion    string              `json:"to_version" gorm:"type:varchar(32)"`
	IsDelta      bool                `json:"is_delta" gorm:"default:false"`
	DownloadProgress float64          `json:"download_progress" gorm:"type:decimal(5,4);default:0"`
	InstallProgress float64          `json:"install_progress" gorm:"type:decimal(5,4);default:0"`
	ErrorDetail  *string             `json:"error_detail" gorm:"type:text"`
	DownloadedAt *time.Time          `json:"downloaded_at"`
	InstalledAt  *time.Time          `json:"installed_at"`
	RolledBackAt *time.Time          `json:"rolled_back_at"`
	CreatedAt    time.Time           `json:"created_at" gorm:"index"`
	UpdatedAt    time.Time           `json:"updated_at" gorm:"index"`
}

func (u *DeviceUpgrade) TableName() string {
	return "device_upgrades"
}

type OTAJobCreateRequest struct {
	FirmwareID      string            `json:"firmware_id" binding:"required"`
	Name            string            `json:"name" binding:"required"`
	Description     string            `json:"description"`
	DeviceIDs       []string          `json:"device_ids"`
	DeviceFilters   map[string]string `json:"device_filters"`
	TotalBatches    int               `json:"total_batches" binding:"min=1"`
	BatchSize       int               `json:"batch_size" binding:"min=1"`
	AutoRollback    bool              `json:"auto_rollback"`
	FailureThreshold float64          `json:"failure_threshold" binding:"min=0,max=1"`
	ForceUpdate     bool              `json:"force_update"`
	StartAt         *time.Time        `json:"start_at"`
}
