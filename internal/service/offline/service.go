package offline

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
	"github.com/edgevision/edgevision/internal/domain/offline"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/edgevision/edgevision/pkg/utils"
	"go.uber.org/zap"
)

type Service struct {
	dataRecordRepo   offline.DataRecordRepository
	syncSessionRepo  offline.SyncSessionRepository
	cloudUploader    offline.CloudUploader
	eventPublisher   offline.EventPublisher
	strategyManager  offline.StrategyManager

	syncQueue   chan string
	workerCount int
	wg          sync.WaitGroup
	stopChan    chan struct{}
	running     bool
	mu          sync.Mutex
}

func NewService(
	dataRecordRepo offline.DataRecordRepository,
	syncSessionRepo offline.SyncSessionRepository,
	cloudUploader offline.CloudUploader,
	eventPublisher offline.EventPublisher,
	strategyManager offline.StrategyManager,
) *Service {
	if strategyManager == nil {
		strategyManager = offline.NewStrategyRegistry()
	}

	return &Service{
		dataRecordRepo:  dataRecordRepo,
		syncSessionRepo: syncSessionRepo,
		cloudUploader:   cloudUploader,
		eventPublisher:  eventPublisher,
		strategyManager: strategyManager,
		syncQueue:       make(chan string, 10000),
		workerCount:     5,
		stopChan:        make(chan struct{}),
	}
}

func (s *Service) GetStrategyManager() offline.StrategyManager {
	return s.strategyManager
}

func (s *Service) SetDeviceSyncStrategy(deviceID, strategyName string) error {
	return s.strategyManager.SetDeviceStrategy(deviceID, strategyName)
}

func (s *Service) GetDeviceSyncStrategy(deviceID string) string {
	return s.strategyManager.GetDeviceStrategy(deviceID)
}

func (s *Service) ListSyncStrategies() []string {
	return s.strategyManager.ListStrategies()
}

func (s *Service) SetStrategyConfig(strategyName string, batchSize int, timeout time.Duration) error {
	return s.strategyManager.SetStrategyConfig(strategyName, offline.StrategyConfig{
		BatchSize: batchSize,
		Timeout:   timeout,
		MaxRetry:  3,
	})
}

func (s *Service) Start(ctx context.Context) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.running {
		return
	}

	s.running = true

	for i := 0; i < s.workerCount; i++ {
		s.wg.Add(1)
		go s.syncWorker(ctx, i)
	}

	logger.Get().Info("Offline cache service started", zap.Int("workers", s.workerCount))
}

func (s *Service) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running {
		return
	}

	s.running = false
	close(s.stopChan)
	s.wg.Wait()
	close(s.syncQueue)

	logger.Get().Info("Offline cache service stopped")
}

func (s *Service) syncWorker(ctx context.Context, workerID int) {
	defer s.wg.Done()

	logger.Get().Debug("Sync worker started", zap.Int("worker_id", workerID))

	for {
		select {
		case <-s.stopChan:
			logger.Get().Debug("Sync worker stopped", zap.Int("worker_id", workerID))
			return
		case recordID, ok := <-s.syncQueue:
			if !ok {
				return
			}
			s.syncRecord(ctx, recordID)
		}
	}
}

func (s *Service) CacheData(ctx context.Context, req *offline.CacheDataRequest) (*model.OfflineDataRecord, error) {
	strategyName := s.strategyManager.GetDeviceStrategy(req.DeviceID)
	strategy, _ := s.strategyManager.GetStrategy(strategyName)

	payloadJSON := utils.ToJSON(req.Payload)
	record := &model.OfflineDataRecord{
		ID:         utils.GenerateID("odr"),
		DeviceID:   req.DeviceID,
		DataType:   req.DataType,
		Payload:    req.Payload,
		DataSize:   len(payloadJSON),
		Checksum:   utils.HashSHA256(payloadJSON),
		Status:     model.OfflineStatusPending,
		Priority:   req.Priority,
		Strategy:   strategyName,
		RetryCount: 0,
		MaxRetry:   3,
		Timestamp:  utils.Now(),
		CreatedAt:  utils.Now(),
		UpdatedAt:  utils.Now(),
	}

	if strategy != nil {
		config, _ := s.strategyManager.GetStrategyConfig(strategyName)
		record.MaxRetry = config.MaxRetry
	}

	if err := s.dataRecordRepo.Create(ctx, record); err != nil {
		logger.Get().Error("failed to cache offline data", zap.Error(err))
		return nil, err
	}

	return record, nil
}

func (s *Service) GetPendingRecords(ctx context.Context, deviceID string, limit int) ([]model.OfflineDataRecord, error) {
	records, err := s.dataRecordRepo.GetPending(ctx, deviceID, limit)
	if err != nil {
		return nil, err
	}

	if deviceID != "" {
		strategyName := s.strategyManager.GetDeviceStrategy(deviceID)
		strategy, ok := s.strategyManager.GetStrategy(strategyName)
		if ok {
			batchSize := strategy.GetBatchSize(ctx)
			records = strategy.SelectNext(ctx, records, batchSize)
		}
	}

	return records, nil
}

func (s *Service) ListRecords(ctx context.Context, deviceID, status string, page, pageSize int) ([]model.OfflineDataRecord, int64, error) {
	return s.dataRecordRepo.List(ctx, deviceID, status, page, pageSize)
}

func (s *Service) SyncData(ctx context.Context, deviceID string) (*model.SyncSession, error) {
	strategyName := s.strategyManager.GetDeviceStrategy(deviceID)
	strategy, ok := s.strategyManager.GetStrategy(strategyName)

	var limit = 1000
	if ok {
		limit = strategy.GetBatchSize(ctx) * 10
	}

	pendingRecords, err := s.dataRecordRepo.GetPending(ctx, deviceID, limit)
	if err != nil {
		return nil, err
	}

	if len(pendingRecords) == 0 {
		return nil, nil
	}

	if ok {
		batchSize := strategy.GetBatchSize(ctx)
		pendingRecords = strategy.SelectNext(ctx, pendingRecords, batchSize)
	}

	session := &model.SyncSession{
		ID:           utils.GenerateID("sync"),
		DeviceID:     deviceID,
		SessionID:    utils.GenerateTraceID(),
		Strategy:     strategyName,
		Status:       model.OfflineStatusSyncing,
		TotalRecords: len(pendingRecords),
		StartTime:    utils.Now(),
		CreatedAt:    utils.Now(),
		UpdatedAt:    utils.Now(),
	}

	if err := s.syncSessionRepo.Create(ctx, session); err != nil {
		return nil, err
	}

	for _, record := range pendingRecords {
		if ok && !strategy.ShouldProcess(ctx, record) {
			continue
		}

		select {
		case s.syncQueue <- record.ID:
		default:
			logger.Get().Warn("Sync queue is full, dropping record", zap.String("record_id", record.ID))
		}
	}

	return session, nil
}

func (s *Service) NetworkRestored(ctx context.Context, deviceID string) {
	logger.Get().Info("Network restored, starting sync for device", zap.String("device_id", deviceID))
	_, _ = s.SyncData(ctx, deviceID)
}

func (s *Service) syncRecord(ctx context.Context, recordID string) {
	record, err := s.dataRecordRepo.GetByID(ctx, recordID)
	if err != nil {
		return
	}

	if record.Status != model.OfflineStatusPending && record.Status != model.OfflineStatusFailed {
		return
	}

	strategyName := record.Strategy
	if strategyName == "" {
		strategyName = s.strategyManager.GetDeviceStrategy(record.DeviceID)
	}
	strategy, _ := s.strategyManager.GetStrategy(strategyName)

	if strategy != nil && !strategy.ShouldProcess(ctx, *record) {
		return
	}

	if strategy != nil {
		strategy.OnProcess(ctx, *record)
	}

	if err := s.dataRecordRepo.UpdateStatus(ctx, recordID, model.OfflineStatusSyncing, nil); err != nil {
		return
	}

	success := s.cloudUploader.Upload(ctx, record)

	if success {
		if err := s.dataRecordRepo.MarkAsSynced(ctx, recordID); err != nil {
			return
		}
		s.eventPublisher.PublishDataSynced(ctx, recordID, record.DeviceID)
		if strategy != nil {
			strategy.OnSuccess(ctx, *record)
		}
	} else {
		if err := s.dataRecordRepo.IncrementRetry(ctx, recordID); err != nil {
			return
		}

		record, _ = s.dataRecordRepo.GetByID(ctx, recordID)
		if record != nil && record.RetryCount >= record.MaxRetry {
			errorMsg := fmt.Sprintf("max retry count reached (%d)", record.MaxRetry)
			_ = s.dataRecordRepo.UpdateStatus(ctx, recordID, model.OfflineStatusFailed, &errorMsg)
			if strategy != nil {
				strategy.OnFailure(ctx, *record, fmt.Errorf(errorMsg))
			}
		}
	}
}

func (s *Service) GetSyncSessions(ctx context.Context, deviceID string, page, pageSize int) ([]model.SyncSession, int64, error) {
	return s.syncSessionRepo.ListByDeviceID(ctx, deviceID, page, pageSize)
}

func (s *Service) GetStats(ctx context.Context, deviceID string) (*offline.SyncStats, error) {
	allRecords, _, err := s.dataRecordRepo.List(ctx, deviceID, "", 1, 10000)
	if err != nil {
		return nil, err
	}

	stats := &offline.SyncStats{}
	var totalDataSize int64

	for _, record := range allRecords {
		totalDataSize += int64(record.DataSize)

		switch record.Status {
		case model.OfflineStatusPending:
			stats.TotalPending++
		case model.OfflineStatusSynced:
			stats.TotalSynced++
		case model.OfflineStatusFailed:
			stats.TotalFailed++
		}
	}

	stats.TotalDataSize = totalDataSize
	stats.AvgSyncLatency = 5000

	return stats, nil
}

type cloudUploader struct{}

func NewCloudUploader() *cloudUploader {
	return &cloudUploader{}
}

func (u *cloudUploader) Upload(ctx context.Context, record *model.OfflineDataRecord) bool {
	time.Sleep(100 * time.Millisecond)
	return true
}

type eventPublisher struct {
	cache *cache.Cache
}

func NewEventPublisher(cache *cache.Cache) *eventPublisher {
	return &eventPublisher{cache: cache}
}

func (p *eventPublisher) PublishDataSynced(ctx context.Context, recordID, deviceID string) {
	if p.cache == nil {
		return
	}

	event := map[string]interface{}{
		"event":     "data.synced",
		"record_id": recordID,
		"device_id": deviceID,
		"timestamp": time.Now().Unix(),
	}

	_ = p.cache.Publish(ctx, fmt.Sprintf("offline:synced:%s", deviceID), utils.ToJSON(event))
}

func (p *eventPublisher) PublishSyncCompleted(ctx context.Context, sessionID, deviceID string) {
	if p.cache == nil {
		return
	}

	event := map[string]interface{}{
		"event":      "sync.completed",
		"session_id": sessionID,
		"device_id":  deviceID,
		"timestamp":  time.Now().Unix(),
	}

	_ = p.cache.Publish(ctx, fmt.Sprintf("offline:sync:%s", deviceID), utils.ToJSON(event))
}

func (p *eventPublisher) PublishSyncFailed(ctx context.Context, sessionID, deviceID, reason string) {
	if p.cache == nil {
		return
	}

	event := map[string]interface{}{
		"event":      "sync.failed",
		"session_id": sessionID,
		"device_id":  deviceID,
		"reason":     reason,
		"timestamp":  time.Now().Unix(),
	}

	_ = p.cache.Publish(ctx, fmt.Sprintf("offline:sync:%s", deviceID), utils.ToJSON(event))
}
