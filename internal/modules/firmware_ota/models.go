package firmware_ota

import (
	"time"

	"edgescheduler/internal/common/models"
)

type FirmwareStatus string
type UpgradePhase string
type UpgradeStatus string

const (
	FirmwareStatusDraft     FirmwareStatus = "draft"
	FirmwareStatusPublished FirmwareStatus = "published"
	FirmwareStatusArchived  FirmwareStatus = "archived"

	UpgradePhasePending    UpgradePhase = "pending"
	UpgradePhaseDownload   UpgradePhase = "downloading"
	UpgradePhaseInstall    UpgradePhase = "installing"
	UpgradePhaseVerify     UpgradePhase = "verifying"
	UpgradePhaseComplete   UpgradePhase = "completed"
	UpgradePhaseRollback   UpgradePhase = "rolling_back"
	UpgradePhaseFailed     UpgradePhase = "failed"

	UpgradeStatusQueued     UpgradeStatus = "queued"
	UpgradeStatusInProgress UpgradeStatus = "in_progress"
	UpgradeStatusSuccess    UpgradeStatus = "success"
	UpgradeStatusFailed     UpgradeStatus = "failed"
	UpgradeStatusRolledBack UpgradeStatus = "rolled_back"
	UpgradeStatusCancelled  UpgradeStatus = "cancelled"
)

type FirmwareImage struct {
	models.BaseModel
	FirmwareID    string         `gorm:"type:varchar(50);not null;uniqueIndex" json:"firmware_id"`
	Name          string         `gorm:"type:varchar(200);not null" json:"name"`
	Version       string         `gorm:"type:varchar(50);not null;index" json:"version"`
	DeviceType    string         `gorm:"type:varchar(100);not null;index" json:"device_type"`
	Status        FirmwareStatus `gorm:"type:varchar(20);index" json:"status"`
	Description   string         `gorm:"type:text" json:"description"`
	FileURL       string         `gorm:"type:varchar(500);not null" json:"file_url"`
	FileSize      int64          `gorm:"default:0" json:"file_size_bytes"`
	Checksum      string         `gorm:"type:varchar(100);not null" json:"checksum"`
	ChecksumType  string         `gorm:"type:varchar(20);default:sha256" json:"checksum_type"`
	IsDifferential bool          `gorm:"default:false" json:"is_differential"`
	BaseVersion   string         `gorm:"type:varchar(50)" json:"base_version,omitempty"`
	DiffPatchURL  string         `gorm:"type:varchar(500)" json:"diff_patch_url,omitempty"`
	DiffSize      int64          `gorm:"default:0" json:"diff_size_bytes,omitempty"`
	Metadata      map[string]interface{} `gorm:"type:jsonb" json:"metadata"`
	PublishedAt   *time.Time     `json:"published_at,omitempty"`
}

type UpgradeBatch struct {
	models.BaseModel
	BatchID     string       `gorm:"type:varchar(50);not null;uniqueIndex" json:"batch_id"`
	Name        string       `gorm:"type:varchar(200);not null" json:"name"`
	FirmwareID  string       `gorm:"type:varchar(50);not null;index" json:"firmware_id"`
	DeviceType  string       `gorm:"type:varchar(100);not null" json:"device_type"`
	TotalCount  int          `gorm:"default:0" json:"total_count"`
	SuccessCount int         `gorm:"default:0" json:"success_count"`
	FailedCount int          `gorm:"default:0" json:"failed_count"`
	RolledBackCount int     `gorm:"default:0" json:"rolled_back_count"`
	MaxFailures int          `gorm:"default:5" json:"max_allowed_failures"`
	AutoRollback bool        `gorm:"default:true" json:"auto_rollback_on_failure"`
	Strategy    string       `gorm:"type:varchar(50);default:gradual" json:"strategy"`
	GradualSteps int         `gorm:"default:4" json:"gradual_steps"`
	CurrentStep int          `gorm:"default:0" json:"current_step"`
	StepProgress float64     `gorm:"default:0" json:"step_progress"`
	Status      UpgradeStatus `gorm:"type:varchar(20);index" json:"status"`
	StartTime   *time.Time   `json:"start_time,omitempty"`
	EndTime     *time.Time   `json:"end_time,omitempty"`
	MaxConcurrent int        `gorm:"default:10" json:"max_concurrent_upgrades"`
}

type DeviceUpgrade struct {
	models.BaseModel
	UpgradeID   string         `gorm:"type:varchar(50);not null;uniqueIndex" json:"upgrade_id"`
	BatchID     string         `gorm:"type:varchar(50);not null;index" json:"batch_id"`
	DeviceID    string         `gorm:"type:varchar(50);not null;index" json:"device_id"`
	FirmwareID  string         `gorm:"type:varchar(50);not null;index" json:"firmware_id"`
	CurrentVersion string      `gorm:"type:varchar(50)" json:"current_version"`
	TargetVersion string       `gorm:"type:varchar(50)" json:"target_version"`
	Phase       UpgradePhase   `gorm:"type:varchar(20);index" json:"phase"`
	Status      UpgradeStatus  `gorm:"type:varchar(20);index" json:"status"`
	Progress    int            `gorm:"default:0" json:"progress_percent"`
	UseDifferential bool       `gorm:"default:true" json:"use_differential"`
	DownloadStartedAt *time.Time `json:"download_started_at,omitempty"`
	DownloadCompletedAt *time.Time `json:"download_completed_at,omitempty"`
	InstallStartedAt *time.Time  `json:"install_started_at,omitempty"`
	InstallCompletedAt *time.Time `json:"install_completed_at,omitempty"`
	ErrorDetail string         `gorm:"type:text" json:"error_detail,omitempty"`
	RetryCount  int            `gorm:"default:0" json:"retry_count"`
	MaxRetries  int            `gorm:"default:3" json:"max_retries"`
	RollbackReason string       `gorm:"type:text" json:"rollback_reason,omitempty"`
}

type UpgradePolicy struct {
	models.BaseModel
	PolicyID    string         `gorm:"type:varchar(50);not null;uniqueIndex" json:"policy_id"`
	Name        string         `gorm:"type:varchar(200);not null" json:"name"`
	DeviceType  string         `gorm:"type:varchar(100);index" json:"device_type,omitempty"`
	TimeWindowStart string      `gorm:"type:varchar(10)" json:"time_window_start,omitempty"`
	TimeWindowEnd string        `gorm:"type:varchar(10)" json:"time_window_end,omitempty"`
	MaxBandwidth int           `gorm:"default:0" json:"max_bandwidth_kbps"`
	RetryPolicy map[string]interface{} `gorm:"type:jsonb" json:"retry_policy"`
	Enabled     bool           `gorm:"default:true" json:"enabled"`
}

type DiffGenerationRequest struct {
	Name          string                 `json:"name" binding:"required"`
	Version       string                 `json:"version" binding:"required"`
	DeviceType    string                 `json:"device_type" binding:"required"`
	BaseVersion   string                 `json:"base_version" binding:"required"`
	FileURL       string                 `json:"file_url" binding:"required"`
	FileSize      int64                  `json:"file_size_bytes"`
	Checksum      string                 `json:"checksum"`
	Description   string                 `json:"description"`
	Metadata      map[string]interface{} `json:"metadata"`
}

type UpgradeBatchRequest struct {
	Name          string   `json:"name" binding:"required"`
	FirmwareID    string   `json:"firmware_id" binding:"required"`
	DeviceIDs     []string `json:"device_ids" binding:"required,min=1"`
	Strategy      string   `json:"strategy"`
	MaxFailures   int      `json:"max_allowed_failures"`
	AutoRollback  bool     `json:"auto_rollback_on_failure"`
	MaxConcurrent int      `json:"max_concurrent_upgrades"`
	GradualSteps  int      `json:"gradual_steps"`
}

type FirmwarePublishRequest struct {
	FirmwareID string `json:"firmware_id" binding:"required"`
}
