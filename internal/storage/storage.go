package storage

import (
	"context"
	"errors"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"os"
	"path/filepath"
	"sync"
	"taskmanager/internal/logger"
	"taskmanager/pkg/models"
	"time"
)

const (
	StorageClassStandard = "standard"
	StorageClassInfrequentAccess = "ia"
	StorageClassArchive = "archive"
)

type StorageManager struct {
	db         *gorm.DB
	basePath   string
	gcTicker   *time.Ticker
	stopped    chan struct{}
	wg         sync.WaitGroup
}

func NewStorageManager(db *gorm.DB, basePath string) *StorageManager {
	return &StorageManager{
		db:       db,
		basePath: basePath,
		stopped:  make(chan struct{}),
	}
}

func (sm *StorageManager) Start() error {
	if err := os.MkdirAll(sm.basePath, 0755); err != nil {
		return err
	}
	sm.gcTicker = time.NewTicker(1 * time.Hour)
	sm.wg.Add(1)
	go sm.runGC()
	logger.Info("storage manager started", zap.String("base_path", sm.basePath))
	return nil
}

func (sm *StorageManager) Stop() {
	close(sm.stopped)
	sm.wg.Wait()
	if sm.gcTicker != nil {
		sm.gcTicker.Stop()
	}
	logger.Info("storage manager stopped")
}

func (sm *StorageManager) runGC() {
	defer sm.wg.Done()
	for {
		select {
		case <-sm.gcTicker.C:
			sm.collectExpired()
			sm.transitionStorageClass()
		case <-sm.stopped:
			return
		}
	}
}

func (sm *StorageManager) collectExpired() {
	var files []models.StoredFile
	now := time.Now()
	if err := sm.db.Where("expire_at IS NOT NULL AND expire_at < ?", now).Find(&files).Error; err != nil {
		logger.Error("find expired files failed", zap.Error(err))
		return
	}
	for _, f := range files {
		if err := sm.deleteFile(f.Path); err != nil {
			logger.Warn("delete expired file failed", zap.String("file", f.Path), zap.Error(err))
		}
		if err := sm.db.Delete(&f).Error; err != nil {
			logger.Error("delete file record failed", zap.Error(err))
		}
		logger.Info("expired file collected", zap.String("file_id", f.ID), zap.String("name", f.Name))
	}
}

func (sm *StorageManager) transitionStorageClass() {
	var files []models.StoredFile
	thirtyDaysAgo := time.Now().AddDate(0, 0, -30)
	ninetyDaysAgo := time.Now().AddDate(0, 0, -90)
	if err := sm.db.Where("storage_class = ? AND last_accessed < ?", StorageClassStandard, thirtyDaysAgo).Find(&files).Error; err != nil {
		logger.Error("find files for ia transition failed", zap.Error(err))
		return
	}
	for _, f := range files {
		f.StorageClass = StorageClassInfrequentAccess
		f.UpdatedAt = time.Now()
		if err := sm.db.Save(&f).Error; err != nil {
			logger.Error("update storage class failed", zap.Error(err))
		}
	}
	if err := sm.db.Where("storage_class = ? AND last_accessed < ?", StorageClassInfrequentAccess, ninetyDaysAgo).Find(&files).Error; err != nil {
		logger.Error("find files for archive transition failed", zap.Error(err))
		return
	}
	for _, f := range files {
		f.StorageClass = StorageClassArchive
		f.UpdatedAt = time.Now()
		if err := sm.db.Save(&f).Error; err != nil {
			logger.Error("update storage class failed", zap.Error(err))
		}
	}
}

func (sm *StorageManager) StoreFile(ctx context.Context, name string, content []byte, contentType string, ttl *time.Duration) (*models.StoredFile, error) {
	fileID := uuid.New().String()
	relPath := filepath.Join(fileID[:2], fileID[2:4], fileID)
	fullPath := filepath.Join(sm.basePath, relPath)
	if err := os.MkdirAll(filepath.Dir(fullPath), 0755); err != nil {
		return nil, err
	}
	if err := os.WriteFile(fullPath, content, 0644); err != nil {
		return nil, err
	}
	fileInfo, err := os.Stat(fullPath)
	if err != nil {
		_ = os.Remove(fullPath)
		return nil, err
	}
	now := time.Now()
	storedFile := &models.StoredFile{
		ID:           fileID,
		Name:         name,
		Path:         relPath,
		Size:         fileInfo.Size(),
		ContentType:  contentType,
		StorageClass: StorageClassStandard,
		LastAccessed: now,
		CreatedAt:    now,
	}
	if ttl != nil {
		expireAt := now.Add(*ttl)
		storedFile.ExpireAt = &expireAt
	}
	if err := sm.db.Create(storedFile).Error; err != nil {
		_ = os.Remove(fullPath)
		return nil, err
	}
	logger.Info("file stored", zap.String("file_id", fileID), zap.String("name", name), zap.Int64("size", storedFile.Size))
	return storedFile, nil
}

func (sm *StorageManager) GetFile(ctx context.Context, id string) (*models.StoredFile, []byte, error) {
	var storedFile models.StoredFile
	if err := sm.db.First(&storedFile, "id = ?", id).Error; err != nil {
		return nil, nil, err
	}
	fullPath := filepath.Join(sm.basePath, storedFile.Path)
	content, err := os.ReadFile(fullPath)
	if err != nil {
		return nil, nil, err
	}
	storedFile.LastAccessed = time.Now()
	if err := sm.db.Save(&storedFile).Error; err != nil {
		logger.Warn("update last accessed failed", zap.Error(err))
	}
	return &storedFile, content, nil
}

func (sm *StorageManager) DeleteFile(ctx context.Context, id string) error {
	var storedFile models.StoredFile
	if err := sm.db.First(&storedFile, "id = ?", id).Error; err != nil {
		return err
	}
	if err := sm.deleteFile(storedFile.Path); err != nil {
		logger.Warn("delete physical file failed", zap.Error(err))
	}
	return sm.db.Delete(&storedFile).Error
}

func (sm *StorageManager) deleteFile(relPath string) error {
	fullPath := filepath.Join(sm.basePath, relPath)
	if err := os.Remove(fullPath); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

func (sm *StorageManager) ListFiles(ctx context.Context, prefix string, limit int) ([]models.StoredFile, error) {
	var files []models.StoredFile
	query := sm.db.Order("created_at desc")
	if prefix != "" {
		query = query.Where("name LIKE ?", prefix+"%")
	}
	if limit > 0 {
		query = query.Limit(limit)
	}
	if err := query.Find(&files).Error; err != nil {
		return nil, err
	}
	return files, nil
}

func (sm *StorageManager) UpdateTTL(ctx context.Context, id string, ttl time.Duration) error {
	var storedFile models.StoredFile
	if err := sm.db.First(&storedFile, "id = ?", id).Error; err != nil {
		return err
	}
	expireAt := time.Now().Add(ttl)
	storedFile.ExpireAt = &expireAt
	storedFile.UpdatedAt = time.Now()
	return sm.db.Save(&storedFile).Error
}

func (sm *StorageManager) GetStorageStats(ctx context.Context) (map[string]interface{}, error) {
	type Result struct {
		StorageClass string
		Count        int64
		TotalSize    int64
	}
	var results []Result
	if err := sm.db.Model(&models.StoredFile{}).
		Select("storage_class, count(*) as count, sum(size) as total_size").
		Group("storage_class").Scan(&results).Error; err != nil {
		return nil, err
	}
	stats := make(map[string]interface{})
	for _, r := range results {
		stats[r.StorageClass] = map[string]interface{}{
			"count":     r.Count,
			"total_size": r.TotalSize,
		}
	}
	var totalCount int64
	var totalSize int64
	if err := sm.db.Model(&models.StoredFile{}).Count(&totalCount).Error; err != nil {
		return nil, err
	}
	if err := sm.db.Model(&models.StoredFile{}).Select("COALESCE(sum(size), 0)").Scan(&totalSize).Error; err != nil {
		return nil, err
	}
	stats["total"] = map[string]interface{}{
		"count":     totalCount,
		"total_size": totalSize,
	}
	return stats, nil
}

func (sm *StorageManager) CreateStorageClassTransition(ctx context.Context, id string, targetClass string) error {
	var storedFile models.StoredFile
	if err := sm.db.First(&storedFile, "id = ?", id).Error; err != nil {
		return err
	}
	validClasses := map[string]bool{
		StorageClassStandard:       true,
		StorageClassInfrequentAccess: true,
		StorageClassArchive:        true,
	}
	if !validClasses[targetClass] {
		return errors.New("invalid storage class")
	}
	storedFile.StorageClass = targetClass
	storedFile.UpdatedAt = time.Now()
	return sm.db.Save(&storedFile).Error
}
