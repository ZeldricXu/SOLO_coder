package storage

import (
	"context"
	"errors"
	"fmt"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"io"
	"os"
	"path/filepath"
	"sync"
	"taskmanager/internal/logger"
	"taskmanager/pkg/models"
	"time"
)

type StorageClass string

const (
	StorageClassStandard          StorageClass = "standard"
	StorageClassInfrequentAccess  StorageClass = "infrequent_access"
	StorageClassArchive           StorageClass = "archive"
)

type AsyncOperationType string

const (
	OpTypeStore      AsyncOperationType = "store"
	OpTypeDelete     AsyncOperationType = "delete"
	OpTypeTransition AsyncOperationType = "transition"
	OpTypeGC         AsyncOperationType = "gc"
)

type AsyncResult struct {
	OperationID string
	Type        AsyncOperationType
	FileID      string
	Success     bool
	Error       string
	Result      interface{}
	CompletedAt time.Time
}

type AsyncCallback func(result AsyncResult)

type StorageEvent struct {
	EventType string
	Timestamp time.Time
	FileID    string
	Data      interface{}
}

type StorageManager struct {
	db            *gorm.DB
	basePath      string
	files         map[string]*models.StoredFile
	mu            sync.RWMutex
	storageUsed   int64
	maxStorage    int64

	asyncQueue     chan asyncTask
	workerCount    int
	workers        []*worker
	workersWG      sync.WaitGroup
	stopping       bool
	callbackMu     sync.RWMutex
	callbacks      map[string][]AsyncCallback
	globalCallbacks []AsyncCallback
	eventListeners []func(event StorageEvent)
	eventMu        sync.RWMutex
}

type asyncTask struct {
	operationID string
	opType      AsyncOperationType
	fileID      string
	ctx         context.Context
	taskFn      func() (interface{}, error)
	callback    AsyncCallback
}

type worker struct {
	id       int
	manager  *StorageManager
	stopCh   chan struct{}
}

func NewStorageManager(db *gorm.DB, basePath string, maxStorage int64) *StorageManager {
	sm := &StorageManager{
		db:         db,
		basePath:   basePath,
		files:      make(map[string]*models.StoredFile),
		maxStorage: maxStorage,
		asyncQueue: make(chan asyncTask, 10000),
		workerCount: 4,
		callbacks:  make(map[string][]AsyncCallback),
	}
	sm.startWorkers()
	sm.loadFiles()
	return sm
}

func NewStorageManagerWithWorkers(db *gorm.DB, basePath string, maxStorage int64, workerCount int) *StorageManager {
	if workerCount <= 0 {
		workerCount = 4
	}
	sm := &StorageManager{
		db:         db,
		basePath:   basePath,
		files:      make(map[string]*models.StoredFile),
		maxStorage: maxStorage,
		asyncQueue: make(chan asyncTask, 10000),
		workerCount: workerCount,
		callbacks:  make(map[string][]AsyncCallback),
	}
	sm.startWorkers()
	sm.loadFiles()
	return sm
}

func (sm *StorageManager) startWorkers() {
	sm.workers = make([]*worker, sm.workerCount)
	for i := 0; i < sm.workerCount; i++ {
		w := &worker{
			id:      i,
			manager: sm,
			stopCh:  make(chan struct{}),
		}
		sm.workers[i] = w
		sm.workersWG.Add(1)
		go w.run()
	}
	logger.Info("storage async workers started", zap.Int("count", sm.workerCount))
}

func (w *worker) run() {
	defer w.manager.workersWG.Done()
	logger.Debug("storage worker started", zap.Int("worker_id", w.id))
	for {
		select {
		case task, ok := <-w.manager.asyncQueue:
			if !ok {
				logger.Debug("storage worker queue closed", zap.Int("worker_id", w.id))
				return
			}
			w.processTask(task)
		case <-w.stopCh:
			logger.Debug("storage worker stopped", zap.Int("worker_id", w.id))
			return
		}
	}
}

func (w *worker) processTask(task asyncTask) {
	var result AsyncResult
	result.OperationID = task.operationID
	result.Type = task.opType
	result.FileID = task.fileID
	result.CompletedAt = time.Now()

	data, err := task.taskFn()
	if err != nil {
		result.Success = false
		result.Error = err.Error()
		logger.Error("async storage operation failed",
			zap.String("op_id", task.operationID),
			zap.String("op_type", string(task.opType)),
			zap.Error(err),
		)
	} else {
		result.Success = true
		result.Result = data
		logger.Debug("async storage operation completed",
			zap.String("op_id", task.operationID),
			zap.String("op_type", string(task.opType)),
		)
	}

	if task.callback != nil {
		go task.callback(result)
	}

	w.manager.invokeCallbacks(task.fileID, result)
	w.manager.invokeGlobalCallbacks(result)
}

func (sm *StorageManager) submitTask(ctx context.Context, opType AsyncOperationType, fileID string, taskFn func() (interface{}, error), callback AsyncCallback) string {
	operationID := uuid.New().String()
	task := asyncTask{
		operationID: operationID,
		opType:      opType,
		fileID:      fileID,
		ctx:         ctx,
		taskFn:      taskFn,
		callback:    callback,
	}

	select {
	case sm.asyncQueue <- task:
		logger.Debug("async task submitted",
			zap.String("op_id", operationID),
			zap.String("op_type", string(opType)),
			zap.String("file_id", fileID),
		)
		return operationID
	default:
		logger.Error("async queue is full",
			zap.String("op_type", string(opType)),
			zap.String("file_id", fileID),
		)
		return ""
	}
}

func (sm *StorageManager) RegisterCallback(fileID string, callback AsyncCallback) {
	sm.callbackMu.Lock()
	defer sm.callbackMu.Unlock()
	sm.callbacks[fileID] = append(sm.callbacks[fileID], callback)
}

func (sm *StorageManager) UnregisterCallback(fileID string, callback AsyncCallback) {
	sm.callbackMu.Lock()
	defer sm.callbackMu.Unlock()
	callbacks := sm.callbacks[fileID]
	for i, cb := range callbacks {
		if fmt.Sprintf("%p", cb) == fmt.Sprintf("%p", callback) {
			sm.callbacks[fileID] = append(callbacks[:i], callbacks[i+1:]...)
			break
		}
	}
}

func (sm *StorageManager) RegisterGlobalCallback(callback AsyncCallback) {
	sm.callbackMu.Lock()
	defer sm.callbackMu.Unlock()
	sm.globalCallbacks = append(sm.globalCallbacks, callback)
}

func (sm *StorageManager) UnregisterGlobalCallback(callback AsyncCallback) {
	sm.callbackMu.Lock()
	defer sm.callbackMu.Unlock()
	for i, cb := range sm.globalCallbacks {
		if fmt.Sprintf("%p", cb) == fmt.Sprintf("%p", callback) {
			sm.globalCallbacks = append(sm.globalCallbacks[:i], sm.globalCallbacks[i+1:]...)
			break
		}
	}
}

func (sm *StorageManager) AddEventListener(listener func(event StorageEvent)) {
	sm.eventMu.Lock()
	defer sm.eventMu.Unlock()
	sm.eventListeners = append(sm.eventListeners, listener)
}

func (sm *StorageManager) RemoveEventListener(listener func(event StorageEvent)) {
	sm.eventMu.Lock()
	defer sm.eventMu.Unlock()
	for i, l := range sm.eventListeners {
		if fmt.Sprintf("%p", l) == fmt.Sprintf("%p", listener) {
			sm.eventListeners = append(sm.eventListeners[:i], sm.eventListeners[i+1:]...)
			break
		}
	}
}

func (sm *StorageManager) notifyEvent(eventType, fileID string, data interface{}) {
	event := StorageEvent{
		EventType: eventType,
		Timestamp: time.Now(),
		FileID:    fileID,
		Data:      data,
	}
	sm.eventMu.RLock()
	defer sm.eventMu.RUnlock()
	for _, listener := range sm.eventListeners {
		go listener(event)
	}
}

func (sm *StorageManager) invokeCallbacks(fileID string, result AsyncResult) {
	sm.callbackMu.RLock()
	defer sm.callbackMu.RUnlock()
	if callbacks, ok := sm.callbacks[fileID]; ok {
		for _, cb := range callbacks {
			go cb(result)
		}
	}
}

func (sm *StorageManager) invokeGlobalCallbacks(result AsyncResult) {
	sm.callbackMu.RLock()
	defer sm.callbackMu.RUnlock()
	for _, cb := range sm.globalCallbacks {
		go cb(result)
	}
}

func (sm *StorageManager) Stop() {
	sm.stopping = true
	for _, w := range sm.workers {
		close(w.stopCh)
	}
	close(sm.asyncQueue)
	sm.workersWG.Wait()
	logger.Info("storage manager stopped")
}

func (sm *StorageManager) QueueLength() int {
	return len(sm.asyncQueue)
}

func (sm *StorageManager) WorkerCount() int {
	return sm.workerCount
}

func (sm *StorageManager) loadFiles() {
	var files []models.StoredFile
	if err := sm.db.Find(&files).Error; err != nil {
		logger.Error("load stored files failed", zap.Error(err))
		return
	}
	sm.mu.Lock()
	defer sm.mu.Unlock()
	for _, f := range files {
		sm.files[f.ID] = &f
	}
	logger.Info("stored files loaded", zap.Int("count", len(files)))
}

func (sm *StorageManager) StoreFileAsync(ctx context.Context, name, contentType string, content []byte, ttl time.Duration, storageClass StorageClass, callback AsyncCallback) (string, error) {
	if name == "" {
		return "", errors.New("file name is required")
	}
	if content == nil {
		return "", errors.New("content is required")
	}
	if int64(len(content))+sm.storageUsed > sm.maxStorage {
		return "", errors.New("storage capacity exceeded")
	}
	if ttl > 0 && ttl < time.Minute {
		return "", errors.New("TTL must be at least 1 minute")
	}

	fileID := uuid.New().String()
	sm.submitTask(ctx, OpTypeStore, fileID, func() (interface{}, error) {
		return sm.storeFileInternal(fileID, name, contentType, content, ttl, storageClass)
	}, callback)

	sm.notifyEvent("file_storing", fileID, map[string]interface{}{
		"name":       name,
		"size":       len(content),
		"async":      true,
	})
	return fileID, nil
}

func (sm *StorageManager) StoreFile(ctx context.Context, name, contentType string, content []byte, ttl time.Duration, storageClass StorageClass) (*models.StoredFile, error) {
	if name == "" {
		return nil, errors.New("file name is required")
	}
	if content == nil {
		return nil, errors.New("content is required")
	}
	if int64(len(content))+sm.storageUsed > sm.maxStorage {
		return nil, errors.New("storage capacity exceeded")
	}
	if ttl > 0 && ttl < time.Minute {
		return nil, errors.New("TTL must be at least 1 minute")
	}

	fileID := uuid.New().String()
	result, err := sm.storeFileInternal(fileID, name, contentType, content, ttl, storageClass)
	if err != nil {
		return nil, err
	}

	sm.notifyEvent("file_stored", fileID, result)
	return result.(*models.StoredFile), nil
}

func (sm *StorageManager) storeFileInternal(fileID, name, contentType string, content []byte, ttl time.Duration, storageClass StorageClass) (interface{}, error) {
	filePath := filepath.Join(sm.basePath, fileID)
	if err := os.WriteFile(filePath, content, 0644); err != nil {
		return nil, fmt.Errorf("write file failed: %w", err)
	}

	file := &models.StoredFile{
		ID:           fileID,
		Name:         name,
		ContentType:  contentType,
		Size:         int64(len(content)),
		Path:         filePath,
		StorageClass: string(storageClass),
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		LastAccessed: time.Now(),
	}

	if ttl > 0 {
		expireAt := time.Now().Add(ttl)
		file.ExpireAt = &expireAt
	}

	if err := sm.db.Create(file).Error; err != nil {
		os.Remove(filePath)
		return nil, fmt.Errorf("save file metadata failed: %w", err)
	}

	sm.mu.Lock()
	sm.files[fileID] = file
	sm.storageUsed += file.Size
	sm.mu.Unlock()

	return file, nil
}

func (sm *StorageManager) GetFileAsync(ctx context.Context, id string, callback AsyncCallback) (string, error) {
	if callback == nil {
		return "", errors.New("callback is required for async operation")
	}
	operationID := sm.submitTask(ctx, OpTypeStore, id, func() (interface{}, error) {
		return sm.getFileInternal(id)
	}, callback)
	return operationID, nil
}

func (sm *StorageManager) GetFile(ctx context.Context, id string) (*models.StoredFile, []byte, error) {
	file, err := sm.getFileInternal(id)
	if err != nil {
		return nil, nil, err
	}
	fileData := file.(*models.StoredFile)

	sm.mu.Lock()
	fileData.LastAccessed = time.Now()
	sm.files[id] = fileData
	sm.mu.Unlock()

	if err := sm.db.Save(fileData).Error; err != nil {
		logger.Error("update file access time failed", zap.Error(err))
	}

	content, err := os.ReadFile(fileData.Path)
	if err != nil {
		return nil, nil, fmt.Errorf("read file content failed: %w", err)
	}

	sm.notifyEvent("file_accessed", id, fileData)
	return fileData, content, nil
}

func (sm *StorageManager) getFileInternal(id string) (interface{}, error) {
	sm.mu.RLock()
	file, exists := sm.files[id]
	sm.mu.RUnlock()
	if !exists {
		return nil, errors.New("file not found")
	}
	return file, nil
}

func (sm *StorageManager) DeleteFileAsync(ctx context.Context, id string, callback AsyncCallback) (string, error) {
	operationID := sm.submitTask(ctx, OpTypeDelete, id, func() (interface{}, error) {
		return nil, sm.deleteFileInternal(id)
	}, callback)
	sm.notifyEvent("file_deleting", id, nil)
	return operationID, nil
}

func (sm *StorageManager) DeleteFile(ctx context.Context, id string) error {
	err := sm.deleteFileInternal(id)
	if err == nil {
		sm.notifyEvent("file_deleted", id, nil)
	}
	return err
}

func (sm *StorageManager) deleteFileInternal(id string) error {
	sm.mu.RLock()
	file, exists := sm.files[id]
	sm.mu.RUnlock()
	if !exists {
		return errors.New("file not found")
	}

	if err := os.Remove(file.Path); err != nil {
		logger.Warn("remove file failed", zap.String("path", file.Path), zap.Error(err))
	}

	if err := sm.db.Delete(file).Error; err != nil {
		return fmt.Errorf("delete file metadata failed: %w", err)
	}

	sm.mu.Lock()
	delete(sm.files, id)
	sm.storageUsed -= file.Size
	sm.mu.Unlock()

	return nil
}

func (sm *StorageManager) ListFiles(ctx context.Context, prefix string, offset, limit int) ([]models.StoredFile, int64, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	var files []models.StoredFile
	var total int64
	query := sm.db.Model(&models.StoredFile{})
	if prefix != "" {
		query = query.Where("name LIKE ?", prefix+"%")
	}
	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}
	if err := query.Order("created_at desc").Offset(offset).Limit(limit).Find(&files).Error; err != nil {
		return nil, 0, err
	}
	return files, total, nil
}

func (sm *StorageManager) UpdateTTL(ctx context.Context, id string, ttl time.Duration) error {
	if ttl > 0 && ttl < time.Minute {
		return errors.New("TTL must be at least 1 minute")
	}

	sm.mu.Lock()
	defer sm.mu.Unlock()

	file, exists := sm.files[id]
	if !exists {
		return errors.New("file not found")
	}

	if ttl > 0 {
		expireAt := time.Now().Add(ttl)
		file.ExpireAt = &expireAt
	} else {
		file.ExpireAt = nil
	}
	file.UpdatedAt = time.Now()

	if err := sm.db.Save(file).Error; err != nil {
		return err
	}
	return nil
}

func (sm *StorageManager) UpdateStorageClass(ctx context.Context, id string, storageClass StorageClass) error {
	validClasses := map[StorageClass]bool{
		StorageClassStandard:         true,
		StorageClassInfrequentAccess: true,
		StorageClassArchive:          true,
	}
	if !validClasses[storageClass] {
		return errors.New("invalid storage class")
	}

	sm.mu.Lock()
	defer sm.mu.Unlock()

	file, exists := sm.files[id]
	if !exists {
		return errors.New("file not found")
	}

	file.StorageClass = string(storageClass)
	file.UpdatedAt = time.Now()

	if err := sm.db.Save(file).Error; err != nil {
		return err
	}

	sm.notifyEvent("storage_class_updated", id, map[string]string{
		"new_class": string(storageClass),
	})
	return nil
}

func (sm *StorageManager) GetStats() *models.StorageStats {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	var standard, infrequent, archive int64
	for _, f := range sm.files {
		switch f.StorageClass {
		case string(StorageClassInfrequentAccess):
			infrequent += f.Size
		case string(StorageClassArchive):
			archive += f.Size
		default:
			standard += f.Size
		}
	}

	return &models.StorageStats{
		Total:      sm.maxStorage,
		Used:       sm.storageUsed,
		Available:  sm.maxStorage - sm.storageUsed,
		FileCount:  int64(len(sm.files)),
		Standard:   standard,
		Infrequent: infrequent,
		Archive:    archive,
	}
}

func (sm *StorageManager) CollectExpiredAsync(callback AsyncCallback) (string, error) {
	operationID := sm.submitTask(context.Background(), OpTypeGC, "", func() (interface{}, error) {
		expired := sm.collectExpiredInternal()
		return expired, nil
	}, callback)
	return operationID, nil
}

func (sm *StorageManager) CollectExpired() []string {
	return sm.collectExpiredInternal()
}

func (sm *StorageManager) collectExpiredInternal() []string {
	sm.mu.RLock()
	var expiredIDs []string
	now := time.Now()
	for _, f := range sm.files {
		if f.ExpireAt != nil && now.After(*f.ExpireAt) {
			expiredIDs = append(expiredIDs, f.ID)
		}
	}
	sm.mu.RUnlock()

	for _, id := range expiredIDs {
		if err := sm.deleteFileInternal(id); err != nil {
			logger.Error("delete expired file failed", zap.String("file_id", id), zap.Error(err))
		}
	}

	if len(expiredIDs) > 0 {
		sm.notifyEvent("files_expired", "", map[string]interface{}{
			"count": len(expiredIDs),
			"ids":   expiredIDs,
		})
	}

	return expiredIDs
}

func (sm *StorageManager) TransitionStorageClassesAsync(callback AsyncCallback) (string, error) {
	operationID := sm.submitTask(context.Background(), OpTypeTransition, "", func() (interface{}, error) {
		count := sm.transitionStorageClassesInternal()
		return count, nil
	}, callback)
	return operationID, nil
}

func (sm *StorageManager) TransitionStorageClasses() int {
	return sm.transitionStorageClassesInternal()
}

func (sm *StorageManager) transitionStorageClassesInternal() int {
	sm.mu.RLock()
	var toInfrequent, toArchive []*models.StoredFile
	now := time.Now()
	for _, f := range sm.files {
		sinceLastAccess := now.Sub(f.LastAccessed)
		switch f.StorageClass {
		case string(StorageClassStandard):
			if sinceLastAccess > 30*24*time.Hour {
				toInfrequent = append(toInfrequent, f)
			}
		case string(StorageClassInfrequentAccess):
			if sinceLastAccess > 90*24*time.Hour {
				toArchive = append(toArchive, f)
			}
		}
	}
	sm.mu.RUnlock()

	transitioned := 0
	for _, f := range toInfrequent {
		f.StorageClass = string(StorageClassInfrequentAccess)
		f.UpdatedAt = now
		if err := sm.db.Save(f).Error; err != nil {
			logger.Error("transition to infrequent access failed", zap.String("file_id", f.ID), zap.Error(err))
			continue
		}
		transitioned++
		sm.notifyEvent("storage_class_updated", f.ID, map[string]string{
			"new_class": string(StorageClassInfrequentAccess),
		})
	}
	for _, f := range toArchive {
		f.StorageClass = string(StorageClassArchive)
		f.UpdatedAt = now
		if err := sm.db.Save(f).Error; err != nil {
			logger.Error("transition to archive failed", zap.String("file_id", f.ID), zap.Error(err))
			continue
		}
		transitioned++
		sm.notifyEvent("storage_class_updated", f.ID, map[string]string{
			"new_class": string(StorageClassArchive),
		})
	}

	return transitioned
}

func (sm *StorageManager) StreamFile(ctx context.Context, id string, w io.Writer) error {
	sm.mu.RLock()
	file, exists := sm.files[id]
	sm.mu.RUnlock()
	if !exists {
		return errors.New("file not found")
	}

	f, err := os.Open(file.Path)
	if err != nil {
		return fmt.Errorf("open file failed: %w", err)
	}
	defer f.Close()

	if _, err := io.Copy(w, f); err != nil {
		return fmt.Errorf("stream file failed: %w", err)
	}

	return nil
}
