package storage

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/events"
	"github.com/solocoder/task-scheduler/internal/storage/providers"
)

type StorageManager struct {
	db              *database.Database
	eventBus        events.EventBus
	providerReg     contracts.ProviderRegistry
	backupQueue     contracts.BackupQueue
	retentionPolicy contracts.RetentionPolicy
	backupDir       string
	retentionDays   int
	waitChannels    map[string]chan struct{}
	waitMu          sync.RWMutex
}

func NewStorageManager(
	db *database.Database,
	eventBus events.EventBus,
	providerReg contracts.ProviderRegistry,
	backupQueue contracts.BackupQueue,
	retentionPolicy contracts.RetentionPolicy,
	backupDir string,
	retentionDays int,
) *StorageManager {
	sm := &StorageManager{
		db:              db,
		eventBus:        eventBus,
		providerReg:     providerReg,
		backupQueue:     backupQueue,
		retentionPolicy: retentionPolicy,
		backupDir:       backupDir,
		retentionDays:   retentionDays,
		waitChannels:    make(map[string]chan struct{}),
	}

	providerReg.RegisterProvider(providers.NewLocalStorageProvider(backupDir))

	go retentionPolicy.Start(context.Background())
	backupQueue.Start()

	sm.setupEventListeners()

	return sm
}

func NewStorageManagerWithDefaults(db *database.Database, eventBus events.EventBus, backupDir string, retentionDays int) *StorageManager {
	providerReg := NewProviderRegistry()
	backupQueue := NewBackupQueue(db, eventBus, providerReg, 3, 100)
	retentionPolicy := NewRetentionPolicy(db, providerReg)

	return NewStorageManager(
		db,
		eventBus,
		providerReg,
		backupQueue,
		retentionPolicy,
		backupDir,
		retentionDays,
	)
}

func (sm *StorageManager) setupEventListeners() {
	sm.eventBus.Subscribe(events.EventBackupComplete, func(ctx context.Context, event events.Event) error {
		sm.notifyWaiters(event.EntityID)
		return nil
	})

	sm.eventBus.Subscribe(events.EventBackupFailed, func(ctx context.Context, event events.Event) error {
		sm.notifyWaiters(event.EntityID)
		return nil
	})

	sm.eventBus.Subscribe(events.EventRestoreComplete, func(ctx context.Context, event events.Event) error {
		if restoreID, ok := event.Payload["restore_id"].(string); ok {
			sm.notifyWaiters(restoreID)
		}
		return nil
	})
}

func (sm *StorageManager) notifyWaiters(id string) {
	sm.waitMu.Lock()
	defer sm.waitMu.Unlock()

	if ch, exists := sm.waitChannels[id]; exists {
		close(ch)
		delete(sm.waitChannels, id)
	}
}

func (sm *StorageManager) RegisterProvider(provider contracts.StorageProvider) {
	sm.providerReg.RegisterProvider(provider)
}

func (sm *StorageManager) GetProvider(name string) (contracts.StorageProvider, error) {
	return sm.providerReg.GetProvider(name)
}

func (sm *StorageManager) CreateBackup(ctx context.Context, backupType contracts.BackupType, source string, description string, options map[string]interface{}) (*contracts.BackupRecord, error) {
	record, err := sm.CreateBackupAsync(ctx, backupType, source, description, options, nil)
	if err != nil {
		return nil, err
	}

	_, err = sm.WaitForBackup(ctx, record.ID, 1*time.Hour)
	return record, err
}

func (sm *StorageManager) CreateBackupAsync(ctx context.Context, backupType contracts.BackupType, source string, description string, options map[string]interface{}, callback contracts.BackupCallback) (*contracts.BackupRecord, error) {
	backupID := "backup_" + time.Now().Format("20060102150405")
	destination := fmt.Sprintf("%s/%s", backupType, backupID)

	providerName := "local"
	if p, ok := options["provider"].(string); ok {
		providerName = p
	}

	retentionDays := sm.retentionDays
	if r, ok := options["retention_days"].(int); ok {
		retentionDays = r
	}
	expiresAt := time.Now().AddDate(0, 0, retentionDays)

	record := &contracts.BackupRecord{
		ID:            backupID,
		BackupType:    backupType,
		Status:        contracts.BackupStatusPending,
		Description:   description,
		Source:        source,
		Destination:   destination,
		RetentionDays: retentionDays,
		ExpiresAt:     &expiresAt,
		Metadata:      options,
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
	}

	if err := sm.db.DB.Create(record).Error; err != nil {
		return nil, err
	}

	sm.waitMu.Lock()
	sm.waitChannels[backupID] = make(chan struct{})
	sm.waitMu.Unlock()

	sm.backupQueue.EnqueueBackup(record, callback)

	return record, nil
}

func (sm *StorageManager) RestoreBackup(ctx context.Context, req *contracts.RestoreRequest) (*contracts.RestoreResult, error) {
	restoreID, err := sm.RestoreBackupAsync(ctx, req, nil)
	if err != nil {
		return nil, err
	}

	return sm.WaitForRestore(ctx, restoreID, 1*time.Hour)
}

func (sm *StorageManager) RestoreBackupAsync(ctx context.Context, req *contracts.RestoreRequest, callback contracts.RestoreCallback) (string, error) {
	var record contracts.BackupRecord
	if err := sm.db.DB.Where("id = ?", req.BackupID).First(&record).Error; err != nil {
		return "", err
	}

	if record.Status != contracts.BackupStatusCompleted {
		return "", fmt.Errorf("backup is not in completed state: %s", record.Status)
	}

	restoreID := "restore_" + time.Now().Format("20060102150405")

	restoreRecord := &contracts.RestoreRecord{
		ID:          restoreID,
		BackupID:    req.BackupID,
		Status:      contracts.RestoreStatusPending,
		Source:      record.Destination,
		Destination: req.Destination,
		Options:     req.Options,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
	}

	if err := sm.db.DB.Create(restoreRecord).Error; err != nil {
		return "", err
	}

	sm.waitMu.Lock()
	sm.waitChannels[restoreID] = make(chan struct{})
	sm.waitMu.Unlock()

	sm.backupQueue.EnqueueRestore(
		restoreID,
		req.BackupID,
		record.Destination,
		req.Destination,
		req.Options,
		callback,
	)

	return restoreID, nil
}

func (sm *StorageManager) CancelBackup(ctx context.Context, backupID string) error {
	if !sm.backupQueue.CancelBackup(backupID) {
		return fmt.Errorf("backup not found or already completed: %s", backupID)
	}
	return nil
}

func (sm *StorageManager) CancelRestore(ctx context.Context, restoreID string) error {
	if !sm.backupQueue.CancelRestore(restoreID) {
		return fmt.Errorf("restore not found or already completed: %s", restoreID)
	}
	return nil
}

func (sm *StorageManager) WaitForBackup(ctx context.Context, backupID string, timeout time.Duration) (*contracts.BackupRecord, error) {
	sm.waitMu.RLock()
	ch, exists := sm.waitChannels[backupID]
	sm.waitMu.RUnlock()

	if !exists {
		return sm.GetBackup(ctx, backupID)
	}

	select {
	case <-ch:
		return sm.GetBackup(ctx, backupID)
	case <-time.After(timeout):
		return nil, fmt.Errorf("timeout waiting for backup: %s", backupID)
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (sm *StorageManager) WaitForRestore(ctx context.Context, restoreID string, timeout time.Duration) (*contracts.RestoreResult, error) {
	sm.waitMu.RLock()
	ch, exists := sm.waitChannels[restoreID]
	sm.waitMu.RUnlock()

	if !exists {
		record, err := sm.GetRestore(ctx, restoreID)
		if err != nil {
			return nil, err
		}
		return &contracts.RestoreResult{
			RestoreID:     record.ID,
			BackupID:      record.BackupID,
			Success:       record.Status == contracts.RestoreStatusCompleted,
			FilesRestored: record.FilesRestored,
			TotalSize:     record.TotalSize,
		}, nil
	}

	select {
	case <-ch:
		record, err := sm.GetRestore(ctx, restoreID)
		if err != nil {
			return nil, err
		}
		return &contracts.RestoreResult{
			RestoreID:     record.ID,
			BackupID:      record.BackupID,
			Success:       record.Status == contracts.RestoreStatusCompleted,
			FilesRestored: record.FilesRestored,
			TotalSize:     record.TotalSize,
		}, nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("timeout waiting for restore: %s", restoreID)
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (sm *StorageManager) GetBackup(ctx context.Context, backupID string) (*contracts.BackupRecord, error) {
	var record contracts.BackupRecord
	err := sm.db.DB.WithContext(ctx).Where("id = ?", backupID).First(&record).Error
	return &record, err
}

func (sm *StorageManager) GetRestore(ctx context.Context, restoreID string) (*contracts.RestoreRecord, error) {
	var record contracts.RestoreRecord
	err := sm.db.DB.WithContext(ctx).Where("id = ?", restoreID).First(&record).Error
	return &record, err
}

func (sm *StorageManager) ListBackups(ctx context.Context, status contracts.BackupStatus, backupType contracts.BackupType, limit, offset int) ([]contracts.BackupRecord, int64, error) {
	var records []contracts.BackupRecord
	var total int64

	query := sm.db.DB.WithContext(ctx).Model(&contracts.BackupRecord{})
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if backupType != "" {
		query = query.Where("backup_type = ?", backupType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := query.Order("created_at DESC").
		Limit(limit).
		Offset(offset).
		Find(&records).Error

	return records, total, err
}

func (sm *StorageManager) ListRestores(ctx context.Context, status contracts.RestoreStatus, limit, offset int) ([]contracts.RestoreRecord, int64, error) {
	var records []contracts.RestoreRecord
	var total int64

	query := sm.db.DB.WithContext(ctx).Model(&contracts.RestoreRecord{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := query.Order("created_at DESC").
		Limit(limit).
		Offset(offset).
		Find(&records).Error

	return records, total, err
}

func (sm *StorageManager) DeleteBackup(ctx context.Context, backupID string) error {
	var record contracts.BackupRecord
	if err := sm.db.DB.Where("id = ?", backupID).First(&record).Error; err != nil {
		return err
	}

	providerName := "local"
	if p, ok := record.Metadata["provider"].(string); ok {
		providerName = p
	}

	if provider, err := sm.providerReg.GetProvider(providerName); err == nil {
		_ = provider.Delete(ctx, record.Destination)
	}

	return sm.db.DB.Delete(&record).Error
}

func (sm *StorageManager) ExportConfig(ctx context.Context, dest string) error {
	config := map[string]interface{}{
		"backup_dir":           sm.backupDir,
		"retention_policy_days": sm.retentionDays,
		"providers":            sm.providerReg.ListProviders(),
	}

	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return err
	}

	return os.WriteFile(dest, data, 0644)
}

func (sm *StorageManager) ImportConfig(ctx context.Context, src string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}

	var config map[string]interface{}
	if err := json.Unmarshal(data, &config); err != nil {
		return err
	}

	if backupDir, ok := config["backup_dir"].(string); ok {
		sm.backupDir = backupDir
	}
	if retentionDays, ok := config["retention_policy_days"].(int); ok {
		sm.retentionDays = retentionDays
	}

	return nil
}

func (sm *StorageManager) HealthCheck(ctx context.Context) error {
	for _, name := range sm.providerReg.ListProviders() {
		provider, err := sm.providerReg.GetProvider(name)
		if err != nil {
			continue
		}
		_, err = provider.List(ctx, "")
		if err != nil {
			return fmt.Errorf("provider %s health check failed: %w", name, err)
		}
	}
	return nil
}

func (sm *StorageManager) Close() {
	if sm.retentionPolicy != nil {
		sm.retentionPolicy.Stop()
	}
	if sm.backupQueue != nil {
		sm.backupQueue.Stop()
	}
}
