package storage

import "github.com/solocoder/task-scheduler/internal/contracts"

type BackupType = contracts.BackupType
type BackupStatus = contracts.BackupStatus
type BackupRecord = contracts.BackupRecord
type RestoreRequest = contracts.RestoreRequest
type RestoreResult = contracts.RestoreResult
type StorageProvider = contracts.StorageProvider
type BackupService = contracts.BackupService
type BackupExecutor = contracts.BackupExecutor
type RetentionPolicy = contracts.RetentionPolicy
type ProviderRegistry = contracts.ProviderRegistry

const (
	BackupTypeFull         = contracts.BackupTypeFull
	BackupTypeIncremental  = contracts.BackupTypeIncremental
	BackupTypeDifferential = contracts.BackupTypeDifferential
)

const (
	BackupStatusPending   = contracts.BackupStatusPending
	BackupStatusRunning   = contracts.BackupStatusRunning
	BackupStatusCompleted = contracts.BackupStatusCompleted
	BackupStatusFailed    = contracts.BackupStatusFailed
	BackupStatusRestoring = contracts.BackupStatusRestoring
)
