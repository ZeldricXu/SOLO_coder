package ota

import (
	"context"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
)

type FirmwareRepository interface {
	Create(ctx context.Context, firmware *model.Firmware) error
	GetByID(ctx context.Context, id string) (*model.Firmware, error)
	GetByVersionAndDeviceType(ctx context.Context, version, deviceType string) (*model.Firmware, error)
	List(ctx context.Context, deviceType string, page, pageSize int) ([]model.Firmware, int64, error)
	Update(ctx context.Context, firmware *model.Firmware) error
	Delete(ctx context.Context, id string) error
}

type DeltaPackageRepository interface {
	Create(ctx context.Context, delta *model.DeltaPackage) error
	GetByID(ctx context.Context, id string) (*model.DeltaPackage, error)
	ListByDeviceType(ctx context.Context, deviceType string) ([]model.DeltaPackage, error)
}

type UpgradeTaskRepository interface {
	Create(ctx context.Context, task *model.OTAUpgradeTask) error
	GetByID(ctx context.Context, id string) (*model.OTAUpgradeTask, error)
	List(ctx context.Context, status string, page, pageSize int) ([]model.OTAUpgradeTask, int64, error)
	Update(ctx context.Context, task *model.OTAUpgradeTask) error
	UpdateStatus(ctx context.Context, id string, status string) error
	IncrementProgress(ctx context.Context, id string, success, failed, rollback int) error
}

type DeviceUpgradeRepository interface {
	Create(ctx context.Context, upgrade *model.OTADeviceUpgrade) error
	GetByID(ctx context.Context, id string) (*model.OTADeviceUpgrade, error)
	ListByTaskID(ctx context.Context, taskID string, page, pageSize int) ([]model.OTADeviceUpgrade, int64, error)
	UpdateStatus(ctx context.Context, id string, status, phase string, progress float64, errorMsg *string) error
	GetPendingByBatch(ctx context.Context, taskID string, batchNum int) ([]model.OTADeviceUpgrade, error)
}

type FirmwareService interface {
	CreateFirmware(ctx context.Context, req *CreateFirmwareRequest) (*model.Firmware, error)
	GetFirmware(ctx context.Context, id string) (*model.Firmware, error)
	ListFirmwares(ctx context.Context, deviceType string, page, pageSize int) ([]model.Firmware, int64, error)
	GenerateDeltaPackage(ctx context.Context, req *GenerateDeltaPackageRequest) (*model.DeltaPackage, error)
}

type UpgradeService interface {
	CreateUpgradeTask(ctx context.Context, req *CreateUpgradeTaskRequest) (*model.OTAUpgradeTask, error)
	StartUpgradeTask(ctx context.Context, taskID string) (*model.OTAUpgradeTask, error)
	GetTask(ctx context.Context, taskID string) (*model.OTAUpgradeTask, error)
	ListTasks(ctx context.Context, status string, page, pageSize int) ([]model.OTAUpgradeTask, int64, error)
	GetDeviceUpgrades(ctx context.Context, taskID string, page, pageSize int) ([]model.OTADeviceUpgrade, int64, error)
	ReportDeviceUpgradeStatus(ctx context.Context, upgradeID, status, phase string, progress float64, errorMsg *string) (*model.OTADeviceUpgrade, error)
}

type OTAService interface {
	FirmwareService
	UpgradeService
}

type EventPublisher interface {
	PublishUpgradeStarted(ctx context.Context, taskID string)
	PublishUpgradeCompleted(ctx context.Context, taskID string)
	PublishUpgradeFailed(ctx context.Context, taskID string, reason string)
	PublishDeviceUpgradeStatus(ctx context.Context, upgradeID, deviceID, status string)
}

type CreateFirmwareRequest struct {
	Name           string                 `json:"name"`
	Version        string                 `json:"version"`
	DeviceType     string                 `json:"device_type"`
	HardwareModel  string                 `json:"hardware_model"`
	FileSize       int64                  `json:"file_size"`
	FileURL        string                 `json:"file_url"`
	Checksum       string                 `json:"checksum"`
	Signature      string                 `json:"signature"`
	ReleaseNotes   string                 `json:"release_notes"`
	Metadata       map[string]interface{} `json:"metadata"`
}

type GenerateDeltaPackageRequest struct {
	FromVersion string `json:"from_version"`
	ToVersion   string `json:"to_version"`
	DeviceType  string `json:"device_type"`
}

type CreateUpgradeTaskRequest struct {
	FirmwareID       string                 `json:"firmware_id"`
	Name             string                 `json:"name"`
	Description      string                 `json:"description"`
	UpgradeType      string                 `json:"upgrade_type"`
	Strategy         string                 `json:"strategy"`
	DeviceIDs        []string               `json:"device_ids"`
	AutoRollback     *bool                  `json:"auto_rollback"`
	FailureThreshold *float64               `json:"failure_threshold"`
	ScheduledAt      *time.Time             `json:"scheduled_at"`
	Parameters       map[string]interface{} `json:"parameters"`
	Profile          string                 `json:"profile"`
}
