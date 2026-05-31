package storage

import (
	"context"
	"sync"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/events"
	"github.com/solocoder/task-scheduler/internal/logging"
)

type backupTask struct {
	record      *contracts.BackupRecord
	callback    contracts.BackupCallback
	providerName string
	options     map[string]interface{}
}

type restoreTask struct {
	restoreID   string
	backupID    string
	backupPath  string
	destination string
	options     map[string]interface{}
	callback    contracts.RestoreCallback
}

type BackupQueue struct {
	db              *database.Database
	eventBus        events.EventBus
	providerReg     contracts.ProviderRegistry
	backupQueue     chan *backupTask
	restoreQueue    chan *restoreTask
	cancelMap       map[string]chan struct{}
	cancelMu        sync.RWMutex
	stopCh          chan struct{}
	wg              sync.WaitGroup
	workerCount     int
	running         bool
	mu              sync.Mutex
}

func NewBackupQueue(
	db *database.Database,
	eventBus events.EventBus,
	providerReg contracts.ProviderRegistry,
	workerCount int,
	queueSize int,
) *BackupQueue {
	return &BackupQueue{
		db:           db,
		eventBus:     eventBus,
		providerReg:  providerReg,
		backupQueue:  make(chan *backupTask, queueSize),
		restoreQueue: make(chan *restoreTask, queueSize),
		cancelMap:    make(map[string]chan struct{}),
		stopCh:       make(chan struct{}),
		workerCount:  workerCount,
	}
}

func (q *BackupQueue) Start() {
	q.mu.Lock()
	defer q.mu.Unlock()

	if q.running {
		return
	}
	q.running = true

	for i := 0; i < q.workerCount; i++ {
		q.wg.Add(2)
		go q.backupWorker(i)
		go q.restoreWorker(i)
	}

	logging.Info(context.Background(), "Backup queue started",
		zap.Int("worker_count", q.workerCount))
}

func (q *BackupQueue) Stop() {
	q.mu.Lock()
	defer q.mu.Unlock()

	if !q.running {
		return
	}
	q.running = false

	close(q.stopCh)
	q.wg.Wait()
	close(q.backupQueue)
	close(q.restoreQueue)

	logging.Info(context.Background(), "Backup queue stopped")
}

func (q *BackupQueue) EnqueueBackup(record *contracts.BackupRecord, callback contracts.BackupCallback) {
	providerName := "local"
	if p, ok := record.Metadata["provider"].(string); ok {
		providerName = p
	}

	task := &backupTask{
		record:       record,
		callback:     callback,
		providerName: providerName,
		options:      record.Metadata,
	}

	select {
	case q.backupQueue <- task:
		q.cancelMu.Lock()
		q.cancelMap[record.ID] = make(chan struct{})
		q.cancelMu.Unlock()
	default:
		logging.Warn(context.Background(), "Backup queue is full, executing synchronously",
			zap.String("backup_id", record.ID))
		go q.executeBackup(task)
	}
}

func (q *BackupQueue) EnqueueRestore(
	restoreID string,
	backupID string,
	backupPath string,
	destination string,
	options map[string]interface{},
	callback contracts.RestoreCallback,
) {
	task := &restoreTask{
		restoreID:   restoreID,
		backupID:    backupID,
		backupPath:  backupPath,
		destination: destination,
		options:     options,
		callback:    callback,
	}

	select {
	case q.restoreQueue <- task:
		q.cancelMu.Lock()
		q.cancelMap[restoreID] = make(chan struct{})
		q.cancelMu.Unlock()
	default:
		logging.Warn(context.Background(), "Restore queue is full, executing synchronously",
			zap.String("restore_id", restoreID))
		go q.executeRestore(task)
	}
}

func (q *BackupQueue) CancelBackup(backupID string) bool {
	q.cancelMu.Lock()
	defer q.cancelMu.Unlock()

	if cancelCh, exists := q.cancelMap[backupID]; exists {
		close(cancelCh)
		delete(q.cancelMap, backupID)
		return true
	}
	return false
}

func (q *BackupQueue) CancelRestore(restoreID string) bool {
	q.cancelMu.Lock()
	defer q.cancelMu.Unlock()

	if cancelCh, exists := q.cancelMap[restoreID]; exists {
		close(cancelCh)
		delete(q.cancelMap, restoreID)
		return true
	}
	return false
}

func (q *BackupQueue) backupWorker(id int) {
	defer q.wg.Done()
	logger := logging.GetDefaultLogger().With(zap.Int("backup_worker_id", id))

	for {
		select {
		case task := <-q.backupQueue:
			if task == nil {
				continue
			}
			logger.Info(context.Background(), "Processing backup task",
				zap.String("backup_id", task.record.ID))
			q.executeBackup(task)
		case <-q.stopCh:
			return
		}
	}
}

func (q *BackupQueue) restoreWorker(id int) {
	defer q.wg.Done()
	logger := logging.GetDefaultLogger().With(zap.Int("restore_worker_id", id))

	for {
		select {
		case task := <-q.restoreQueue:
			if task == nil {
				continue
			}
			logger.Info(context.Background(), "Processing restore task",
				zap.String("restore_id", task.restoreID))
			q.executeRestore(task)
		case <-q.stopCh:
			return
		}
	}
}

func (q *BackupQueue) executeBackup(task *backupTask) {
	ctx := context.Background()
	q.cancelMu.RLock()
	cancelCh, hasCancel := q.cancelMap[task.record.ID]
	q.cancelMu.RUnlock()

	if hasCancel {
		ctx, _ = context.WithCancel(ctx)
		go func() {
			<-cancelCh
			q.updateBackupStatus(task.record.ID, contracts.BackupStatusCancelled)
		}()
	}

	now := time.Now()
	task.record.Status = contracts.BackupStatusRunning
	task.record.StartedAt = &now
	task.record.UpdatedAt = now
	_ = q.db.DB.Save(task.record).Error

	event := events.NewEvent(events.EventBackupStarted, task.record.ID, map[string]interface{}{
		"backup_type": task.record.BackupType,
		"source":      task.record.Source,
	}, nil)
	_ = q.eventBus.Publish(ctx, event)

	provider, err := q.providerReg.GetProvider(task.providerName)
	if err != nil {
		q.handleBackupError(task.record, err.Error())
		if task.callback != nil {
			task.callback(ctx, task.record, err)
		}
		return
	}

	size, fileCount, err := provider.Backup(ctx, task.record.Source, task.record.Destination, task.options)
	if err != nil {
		q.handleBackupError(task.record, err.Error())
		if task.callback != nil {
			task.callback(ctx, task.record, err)
		}
		return
	}

	now = time.Now()
	task.record.Status = contracts.BackupStatusCompleted
	task.record.CompletedAt = &now
	task.record.Size = size
	task.record.FileCount = fileCount
	task.record.UpdatedAt = now
	_ = q.db.DB.Save(task.record).Error

	duration := now.Sub(*task.record.StartedAt).Seconds()
	completeEvent := events.NewEvent(events.EventBackupComplete, task.record.ID, map[string]interface{}{
		"size":     size,
		"duration": duration,
		"files":    fileCount,
	}, nil)
	_ = q.eventBus.Publish(ctx, completeEvent)

	q.cancelMu.Lock()
	delete(q.cancelMap, task.record.ID)
	q.cancelMu.Unlock()

	if task.callback != nil {
		task.callback(ctx, task.record, nil)
	}

	logging.Info(ctx, "Backup completed successfully",
		zap.String("backup_id", task.record.ID),
		zap.Int64("size", size),
		zap.Int("files", fileCount))
}

func (q *BackupQueue) executeRestore(task *restoreTask) {
	ctx := context.Background()
	q.cancelMu.RLock()
	cancelCh, hasCancel := q.cancelMap[task.restoreID]
	q.cancelMu.RUnlock()

	if hasCancel {
		ctx, _ = context.WithCancel(ctx)
		go func() {
			<-cancelCh
			q.updateRestoreStatus(task.restoreID, contracts.RestoreStatusCancelled)
		}()
	}

	startTime := time.Now()
	q.updateRestoreStatus(task.restoreID, contracts.RestoreStatusRunning)

	providerName := "local"
	if p, ok := task.options["provider"].(string); ok {
		providerName = p
	}

	provider, err := q.providerReg.GetProvider(providerName)
	if err != nil {
		q.handleRestoreError(task, err.Error())
		return
	}

	fileCount, totalSize, err := provider.Restore(ctx, task.backupPath, task.destination, task.options)
	if err != nil {
		q.handleRestoreError(task, err.Error())
		return
	}

	q.completeRestore(task, fileCount, totalSize, startTime)
}

func (q *BackupQueue) handleBackupError(record *contracts.BackupRecord, errorMsg string) {
	now := time.Now()
	record.Status = contracts.BackupStatusFailed
	record.ErrorDetail = &errorMsg
	record.CompletedAt = &now
	record.UpdatedAt = now
	_ = q.db.DB.Save(record).Error

	event := events.NewEvent(events.EventBackupFailed, record.ID, map[string]interface{}{
		"error": errorMsg,
	}, nil)
	_ = q.eventBus.Publish(context.Background(), event)

	q.cancelMu.Lock()
	delete(q.cancelMap, record.ID)
	q.cancelMu.Unlock()

	logging.Error(context.Background(), "Backup failed",
		zap.String("backup_id", record.ID),
		zap.String("error", errorMsg))
}

func (q *BackupQueue) handleRestoreError(task *restoreTask, errorMsg string) {
	now := time.Now()
	_ = q.db.DB.Model(&contracts.RestoreRecord{}).
		Where("id = ?", task.restoreID).
		Updates(map[string]interface{}{
			"status":       contracts.RestoreStatusFailed,
			"error_detail": errorMsg,
			"completed_at": now,
			"updated_at":   now,
		})

	q.cancelMu.Lock()
	delete(q.cancelMap, task.restoreID)
	q.cancelMu.Unlock()

	if task.callback != nil {
		result := &contracts.RestoreResult{
			RestoreID: task.restoreID,
			BackupID:  task.backupID,
			Success:   false,
			Error:     errorMsg,
			EndTime:   now,
		}
		task.callback(context.Background(), task.restoreID, result, nil)
	}

	logging.Error(context.Background(), "Restore failed",
		zap.String("restore_id", task.restoreID),
		zap.String("error", errorMsg))
}

func (q *BackupQueue) completeRestore(task *restoreTask, fileCount int, totalSize int64, startTime time.Time) {
	now := time.Now()
	_ = q.db.DB.Model(&contracts.RestoreRecord{}).
		Where("id = ?", task.restoreID).
		Updates(map[string]interface{}{
			"status":         contracts.RestoreStatusCompleted,
			"files_restored": fileCount,
			"total_size":     totalSize,
			"completed_at":   now,
			"updated_at":     now,
		})

	q.cancelMu.Lock()
	delete(q.cancelMap, task.restoreID)
	q.cancelMu.Unlock()

	event := events.NewEvent(events.EventRestoreComplete, task.backupID, map[string]interface{}{
		"restore_id":    task.restoreID,
		"files_restored": fileCount,
		"total_size":    totalSize,
		"duration":      now.Sub(startTime).Seconds(),
	}, nil)
	_ = q.eventBus.Publish(context.Background(), event)

	if task.callback != nil {
		result := &contracts.RestoreResult{
			RestoreID:     task.restoreID,
			BackupID:      task.backupID,
			Success:       true,
			FilesRestored: fileCount,
			TotalSize:     totalSize,
			StartTime:     startTime,
			EndTime:       now,
		}
		task.callback(context.Background(), task.restoreID, result, nil)
	}

	logging.Info(context.Background(), "Restore completed successfully",
		zap.String("restore_id", task.restoreID),
		zap.Int("files_restored", fileCount),
		zap.Int64("total_size", totalSize))
}

func (q *BackupQueue) updateBackupStatus(backupID string, status contracts.BackupStatus) {
	_ = q.db.DB.Model(&contracts.BackupRecord{}).
		Where("id = ?", backupID).
		Updates(map[string]interface{}{
			"status":     status,
			"updated_at": time.Now(),
		})
}

func (q *BackupQueue) updateRestoreStatus(restoreID string, status contracts.RestoreStatus) {
	_ = q.db.DB.Model(&contracts.RestoreRecord{}).
		Where("id = ?", restoreID).
		Updates(map[string]interface{}{
			"status":     status,
			"updated_at": time.Now(),
		})
}
