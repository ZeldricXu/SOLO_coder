package contracts

import (
	"context"
	"time"
)

type BackupType string

const (
	BackupTypeFull         BackupType = "full"
	BackupTypeIncremental BackupType = "incremental"
	BackupTypeDifferential BackupType = "differential"
)

type BackupStatus string

const (
	BackupStatusPending   BackupStatus = "pending"
	BackupStatusRunning   BackupStatus = "running"
	BackupStatusCompleted BackupStatus = "completed"
	BackupStatusFailed    BackupStatus = "failed"
	BackupStatusRestoring BackupStatus = "restoring"
	BackupStatusCancelled BackupStatus = "cancelled"
)

type RestoreStatus string

const (
	RestoreStatusPending   RestoreStatus = "pending"
	RestoreStatusRunning   RestoreStatus = "running"
	RestoreStatusCompleted RestoreStatus = "completed"
	RestoreStatusFailed    RestoreStatus = "failed"
	RestoreStatusCancelled RestoreStatus = "cancelled"
)

type BackupRecord struct {
	ID            string                 `json:"id" gorm:"primaryKey;size:64"`
	BackupType    BackupType           `json:"backup_type" gorm:"size:32;index"`
	Status        BackupStatus         `json:"status" gorm:"size:32;index"`
	Description   string               `json:"description" gorm:"size:512"`
	Source        string               `json:"source" gorm:"size:256"`
	Destination   string               `json:"destination" gorm:"size:512"`
	Size          int64                `json:"size"`
	FileCount     int                  `json:"file_count"`
	Checksum      string               `json:"checksum" gorm:"size:128"`
	EncryptionKey string               `json:"encryption_key,omitempty"`
	RetentionDays int                  `json:"retention_days"`
	ExpiresAt     *time.Time           `json:"expires_at"`
	ErrorDetail   *string              `json:"error_detail,omitempty"`
	Metadata      map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	StartedAt     *time.Time           `json:"started_at"`
	CompletedAt   *time.Time           `json:"completed_at"`
	CreatedAt     time.Time            `json:"created_at" gorm:"index"`
	UpdatedAt     time.Time            `json:"updated_at"`
}

type RestoreRecord struct {
	ID            string                 `json:"id" gorm:"primaryKey;size:64"`
	BackupID      string                 `json:"backup_id" gorm:"size:64;index"`
	Status        RestoreStatus          `json:"status" gorm:"size:32;index"`
	Source        string                 `json:"source" gorm:"size:512"`
	Destination   string                 `json:"destination" gorm:"size:512"`
	FilesRestored int                    `json:"files_restored"`
	TotalSize     int64                  `json:"total_size"`
	Options       map[string]interface{} `json:"options" gorm:"type:jsonb"`
	ErrorDetail   *string                `json:"error_detail,omitempty"`
	StartedAt     *time.Time             `json:"started_at"`
	CompletedAt   *time.Time             `json:"completed_at"`
	CreatedAt     time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt     time.Time              `json:"updated_at"`
}

type RestoreRequest struct {
	BackupID    string                 `json:"backup_id"`
	Destination string                 `json:"destination"`
	Options     map[string]interface{} `json:"options"`
	Overwrite   bool                   `json:"overwrite"`
}

type RestoreResult struct {
	RestoreID     string    `json:"restore_id"`
	BackupID      string    `json:"backup_id"`
	Success       bool      `json:"success"`
	FilesRestored int     `json:"files_restored"`
	TotalSize     int64     `json:"total_size"`
	Error         string    `json:"error,omitempty"`
	StartTime     time.Time `json:"start_time"`
	EndTime       time.Time `json:"end_time"`
}

type BackupCallback func(ctx context.Context, record *BackupRecord, err error)
type RestoreCallback func(ctx context.Context, restoreID string, result *RestoreResult, err error)

type StorageProvider interface {
	Name() string
	Backup(ctx context.Context, source, destination string, options map[string]interface{}) (int64, int, error)
	Restore(ctx context.Context, backupPath, destination string, options map[string]interface{}) (int, int64, error)
	Delete(ctx context.Context, path string) error
	List(ctx context.Context, prefix string) ([]string, error)
	Exists(ctx context.Context, path string) (bool, error)
	GetMetadata(ctx context.Context, path string) (map[string]interface{}, error)
}

type ProviderRegistry interface {
	RegisterProvider(provider StorageProvider)
	GetProvider(name string) (StorageProvider, error)
	ListProviders() []string
}

type AsyncBackupService interface {
	ProviderRegistry
	CreateBackup(ctx context.Context, backupType BackupType, source string, description string, options map[string]interface{}) (*BackupRecord, error)
	CreateBackupAsync(ctx context.Context, backupType BackupType, source string, description string, options map[string]interface{}, callback BackupCallback) (*BackupRecord, error)
	RestoreBackup(ctx context.Context, req *RestoreRequest) (*RestoreResult, error)
	RestoreBackupAsync(ctx context.Context, req *RestoreRequest, callback RestoreCallback) (string, error)
	CancelBackup(ctx context.Context, backupID string) error
	CancelRestore(ctx context.Context, restoreID string) error
	GetBackup(ctx context.Context, backupID string) (*BackupRecord, error)
	GetRestore(ctx context.Context, restoreID string) (*RestoreRecord, error)
	ListBackups(ctx context.Context, status BackupStatus, backupType BackupType, limit, offset int) ([]BackupRecord, int64, error)
	ListRestores(ctx context.Context, status RestoreStatus, limit, offset int) ([]RestoreRecord, int64, error)
	DeleteBackup(ctx context.Context, backupID string) error
	HealthCheck(ctx context.Context) error
	WaitForBackup(ctx context.Context, backupID string, timeout time.Duration) (*BackupRecord, error)
	WaitForRestore(ctx context.Context, restoreID string, timeout time.Duration) (*RestoreResult, error)
}

type BackupExecutor interface {
	ExecuteBackup(ctx context.Context, record *BackupRecord, providerName string, options map[string]interface{})
	ExecuteRestore(ctx context.Context, restoreID string, backupID string, backupPath, destination string, options map[string]interface{})
}

type BackupQueue interface {
	EnqueueBackup(record *BackupRecord, callback BackupCallback)
	EnqueueRestore(restoreID string, backupID string, backupPath string, destination string, options map[string]interface{}, callback RestoreCallback)
	CancelBackup(backupID string) bool
	CancelRestore(restoreID string) bool
	Start()
	Stop()
}

type RetentionPolicy interface {
	Start(ctx context.Context)
	Stop()
	CleanupExpired(ctx context.Context)
}
