package offline_cache

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"edgescheduler/internal/common/database"
	"edgescheduler/internal/common/eventbus"
	"edgescheduler/internal/common/logger"
	"edgescheduler/pkg/utils"
)

type OfflineCacheService interface {
	CacheData(ctx context.Context, req *CacheRequest) (*CachedData, error)
	GetCachedData(ctx context.Context, deviceID, dataType string, limit int) ([]CachedData, int64, error)
	StartSync(ctx context.Context, deviceID string) (*SyncJob, error)
	GetSyncJob(ctx context.Context, jobID string) (*SyncJob, error)
	CheckNetworkStatus(ctx context.Context) bool
	SetNetworkStatus(online bool)
	StartAutoSync(ctx context.Context, syncInterval time.Duration)
	GetPendingCount(ctx context.Context, deviceID string) (int64, error)
	DeleteSyncedData(ctx context.Context, beforeTime time.Time) (int64, error)
}

type offlineCacheServiceImpl struct {
	db            *gorm.DB
	eventBus      eventbus.EventBus
	isOnline      bool
	networkMu     sync.RWMutex
	syncTriggerCh chan string
}

func NewOfflineCacheService() OfflineCacheService {
	return &offlineCacheServiceImpl{
		db:            database.GetDB(),
		eventBus:      eventbus.GetEventBus(),
		isOnline:      true,
		syncTriggerCh: make(chan string, 100),
	}
}

func NewOfflineCacheServiceWithDeps(db *gorm.DB, eb eventbus.EventBus) OfflineCacheService {
	return &offlineCacheServiceImpl{
		db:            db,
		eventBus:      eb,
		isOnline:      true,
		syncTriggerCh: make(chan string, 100),
	}
}

func (s *offlineCacheServiceImpl) CacheData(ctx context.Context, req *CacheRequest) (*CachedData, error) {
	logger.Debug("Caching data",
		zap.String("device_id", req.DeviceID),
		zap.String("data_type", req.DataType),
	)

	payloadJSON := utils.ToJSON(req.Payload)
	sizeBytes := int64(len(payloadJSON))

	cachedData := &CachedData{
		CacheKey:  utils.GenerateID("cache"),
		DeviceID:  req.DeviceID,
		DataType:  req.DataType,
		Payload:   req.Payload,
		Status:    CacheStatusPending,
		SizeBytes: sizeBytes,
	}

	if req.TTLSeconds > 0 {
		expiresAt := time.Now().UTC().Add(time.Duration(req.TTLSeconds) * time.Second)
		cachedData.ExpiresAt = &expiresAt
	}

	operation := func() error {
		return s.db.Create(cachedData).Error
	}

	if err := utils.RetryWithBackoff(operation, 3, 100*time.Millisecond); err != nil {
		return nil, fmt.Errorf("failed to cache data after retries: %w", err)
	}

	s.eventBus.Publish(ctx, eventbus.EventDataCached, map[string]interface{}{
		"cache_key":  cachedData.CacheKey,
		"device_id":  cachedData.DeviceID,
		"data_type":  cachedData.DataType,
		"size_bytes": cachedData.SizeBytes,
	}, "offline_cache")

	if s.isOnline {
		select {
		case s.syncTriggerCh <- req.DeviceID:
		default:
		}
	}

	return cachedData, nil
}

func (s *offlineCacheServiceImpl) GetCachedData(ctx context.Context, deviceID, dataType string, limit int) ([]CachedData, int64, error) {
	var data []CachedData
	var total int64

	query := s.db.Model(&CachedData{}).Where("device_id = ?", deviceID)
	if dataType != "" {
		query = query.Where("data_type = ?", dataType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("created_at DESC").Limit(limit).Find(&data).Error; err != nil {
		return nil, 0, err
	}

	return data, total, nil
}

func (s *offlineCacheServiceImpl) StartSync(ctx context.Context, deviceID string) (*SyncJob, error) {
	logger.Info("Starting sync job", zap.String("device_id", deviceID))

	var pendingCount int64
	s.db.Model(&CachedData{}).Where("device_id = ? AND status = ?", deviceID, CacheStatusPending).Count(&pendingCount)

	if pendingCount == 0 {
		return nil, errors.New("no pending data to sync")
	}

	now := time.Now().UTC()
	job := &SyncJob{
		JobID:      utils.GenerateID("sync"),
		DeviceID:   deviceID,
		Status:     SyncStatusSyncing,
		TotalCount: int(pendingCount),
		StartedAt:  &now,
	}

	if err := s.db.Create(job).Error; err != nil {
		return nil, fmt.Errorf("failed to create sync job: %w", err)
	}

	go s.executeSync(ctx, job)

	return job, nil
}

func (s *offlineCacheServiceImpl) executeSync(ctx context.Context, job *SyncJob) {
	var pendingItems []CachedData
	batchSize := 100
	syncedCount := 0
	failedCount := 0

	for {
		result := s.db.Where("device_id = ? AND status = ?", job.DeviceID, CacheStatusPending).
			Order("created_at ASC").
			Limit(batchSize).
			Find(&pendingItems)

		if result.Error != nil || len(pendingItems) == 0 {
			break
		}

		now := time.Now().UTC()
		for _, item := range pendingItems {
			syncedCount++
			item.Status = CacheStatusSynced
			item.SyncedAt = &now
			s.db.Save(&item)

			s.eventBus.Publish(ctx, eventbus.EventDataSynced, map[string]interface{}{
				"cache_key": item.CacheKey,
				"device_id": item.DeviceID,
				"data_type": item.DataType,
			}, "offline_cache")
		}

		job.SyncedCount = syncedCount
		job.FailedCount = failedCount
		s.db.Save(job)

		if len(pendingItems) < batchSize {
			break
		}
	}

	now := time.Now().UTC()
	job.Status = SyncStatusCompleted
	job.CompletedAt = &now
	s.db.Save(job)

	logger.Info("Sync job completed",
		zap.String("job_id", job.JobID),
		zap.Int("synced_count", syncedCount),
		zap.Int("failed_count", failedCount),
	)
}

func (s *offlineCacheServiceImpl) GetSyncJob(ctx context.Context, jobID string) (*SyncJob, error) {
	var job SyncJob
	if err := s.db.Where("job_id = ?", jobID).First(&job).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("sync job not found")
		}
		return nil, err
	}
	return &job, nil
}

func (s *offlineCacheServiceImpl) CheckNetworkStatus(ctx context.Context) bool {
	s.networkMu.RLock()
	defer s.networkMu.RUnlock()
	return s.isOnline
}

func (s *offlineCacheServiceImpl) SetNetworkStatus(online bool) {
	s.networkMu.Lock()
	defer s.networkMu.Unlock()
	oldStatus := s.isOnline
	s.isOnline = online

	if !oldStatus && online {
		ctx := context.Background()
		s.eventBus.Publish(ctx, eventbus.EventNetworkRestored, map[string]interface{}{
			"timestamp": time.Now().UTC(),
		}, "offline_cache")

		logger.Info("Network restored, triggering sync for all devices")

		var devices []string
		s.db.Model(&CachedData{}).
			Where("status = ?", CacheStatusPending).
			Distinct("device_id").
			Pluck("device_id", &devices)

		for _, deviceID := range devices {
			select {
			case s.syncTriggerCh <- deviceID:
			default:
			}
		}
	}
}

func (s *offlineCacheServiceImpl) StartAutoSync(ctx context.Context, syncInterval time.Duration) {
	logger.Info("Starting auto sync service", zap.Duration("interval", syncInterval))

	ticker := time.NewTicker(syncInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			logger.Info("Auto sync service stopped")
			return
		case deviceID := <-s.syncTriggerCh:
			if s.isOnline {
				_, _ = s.StartSync(ctx, deviceID)
			}
		case <-ticker.C:
			if s.isOnline {
				var devices []string
				s.db.Model(&CachedData{}).
					Where("status = ?", CacheStatusPending).
					Distinct("device_id").
					Pluck("device_id", &devices)

				for _, deviceID := range devices {
					_, _ = s.StartSync(ctx, deviceID)
				}
			}
		}
	}
}

func (s *offlineCacheServiceImpl) GetPendingCount(ctx context.Context, deviceID string) (int64, error) {
	var count int64
	err := s.db.Model(&CachedData{}).
		Where("device_id = ? AND status = ?", deviceID, CacheStatusPending).
		Count(&count).Error
	return count, err
}

func (s *offlineCacheServiceImpl) DeleteSyncedData(ctx context.Context, beforeTime time.Time) (int64, error) {
	result := s.db.Where("status = ? AND created_at < ?", CacheStatusSynced, beforeTime).
		Delete(&CachedData{})
	return result.RowsAffected, result.Error
}
