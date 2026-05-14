package synchronization

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/models"
	"github.com/cachehub/internal/pkg/storage"
	"github.com/sirupsen/logrus"
)

type SyncOperation struct {
	ID          string      `json:"id"`
	Operation   string      `json:"operation"`
	Key         string      `json:"key"`
	Value       interface{} `json:"value"`
	TTL         int         `json:"ttl"`
	Timestamp   time.Time   `json:"timestamp"`
	RetryCount  int         `json:"retry_count"`
	MaxRetries  int         `json:"max_retries"`
	NextRetryAt time.Time   `json:"next_retry_at"`
	SourceID    string      `json:"source_id"`
	TargetID    string      `json:"target_id"`
	Version     int64       `json:"version"`
}

type SyncConfig struct {
	SourceCacheID  string
	TargetCacheIDs []string
	SyncMode       string
	Enabled        bool
	RetryMax       int
	RetryDelay     time.Duration
}

type SyncStats struct {
	TotalOps      int   `json:"total_ops"`
	SuccessOps    int   `json:"success_ops"`
	FailedOps     int   `json:"failed_ops"`
	RetriedOps    int   `json:"retried_ops"`
	PendingOps    int   `json:"pending_ops"`
	RedisQueueSize int64 `json:"redis_queue_size"`
}

type SyncPersistenceConfig struct {
	Enabled        bool
	RedisConfig    *storage.RedisQueueConfig
	FallbackToMemory bool
}

type SyncManager struct {
	cm                *cache_manager.CacheManager
	logger            *logrus.Logger
	configs           map[string]*SyncConfig
	pendingOps        map[string][]*SyncOperation
	retryQueue        map[string][]*SyncOperation
	stats             map[string]*SyncStats
	mu                sync.RWMutex
	stopCh            chan struct{}
	wg                sync.WaitGroup
	workerCount       int
	redisQueue        *storage.RedisQueue
	persistenceConfig *SyncPersistenceConfig
	useRedis          bool
	inFlightOps       map[string]*SyncOperation
	inFlightMu        sync.RWMutex
	totalProcessed    int64
	totalFailed       int64
}

func DefaultPersistenceConfig() *SyncPersistenceConfig {
	return &SyncPersistenceConfig{
		Enabled:          true,
		RedisConfig:      storage.DefaultRedisQueueConfig(),
		FallbackToMemory: true,
	}
}

func NewSyncManager(cm *cache_manager.CacheManager, logger *logrus.Logger) *SyncManager {
	return &SyncManager{
		cm:                cm,
		logger:            logger,
		configs:           make(map[string]*SyncConfig),
		pendingOps:        make(map[string][]*SyncOperation),
		retryQueue:        make(map[string][]*SyncOperation),
		stats:             make(map[string]*SyncStats),
		stopCh:            make(chan struct{}),
		workerCount:       3,
		persistenceConfig: DefaultPersistenceConfig(),
		useRedis:          false,
		inFlightOps:       make(map[string]*SyncOperation),
	}
}

func NewSyncManagerWithWorkers(cm *cache_manager.CacheManager, logger *logrus.Logger, workerCount int) *SyncManager {
	if workerCount <= 0 {
		workerCount = 1
	}
	return &SyncManager{
		cm:                cm,
		logger:            logger,
		configs:           make(map[string]*SyncConfig),
		pendingOps:        make(map[string][]*SyncOperation),
		retryQueue:        make(map[string][]*SyncOperation),
		stats:             make(map[string]*SyncStats),
		stopCh:            make(chan struct{}),
		workerCount:       workerCount,
		persistenceConfig: DefaultPersistenceConfig(),
		useRedis:          false,
		inFlightOps:       make(map[string]*SyncOperation),
	}
}

func NewSyncManagerWithPersistence(cm *cache_manager.CacheManager, logger *logrus.Logger, persistenceConfig *SyncPersistenceConfig) *SyncManager {
	if persistenceConfig == nil {
		persistenceConfig = DefaultPersistenceConfig()
	}

	sm := &SyncManager{
		cm:                cm,
		logger:            logger,
		configs:           make(map[string]*SyncConfig),
		pendingOps:        make(map[string][]*SyncOperation),
		retryQueue:        make(map[string][]*SyncOperation),
		stats:             make(map[string]*SyncStats),
		stopCh:            make(chan struct{}),
		workerCount:       3,
		persistenceConfig: persistenceConfig,
		useRedis:          false,
		inFlightOps:       make(map[string]*SyncOperation),
	}

	if persistenceConfig.Enabled {
		redisQueue, err := storage.NewRedisQueue(persistenceConfig.RedisConfig, logger)
		if err == nil {
			sm.redisQueue = redisQueue
			sm.useRedis = true
			logger.Info("Redis persistence enabled for sync queue")
		} else if !persistenceConfig.FallbackToMemory {
			logger.Fatalf("Redis persistence required but connection failed: %v", err)
		} else {
			logger.Warn("Redis unavailable, using in-memory fallback for sync queue")
		}
	}

	return sm
}

func (sm *SyncManager) getOrCreateStats(sourceID string) *SyncStats {
	stats, exists := sm.stats[sourceID]
	if !exists {
		stats = &SyncStats{}
		sm.stats[sourceID] = stats
	}
	return stats
}

func (sm *SyncManager) GetStats(sourceID string) *SyncStats {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	stats := sm.getOrCreateStats(sourceID)
	
	if sm.useRedis {
		if queueSize, err := sm.redisQueue.QueueSize(sourceID + ":pending"); err == nil {
			stats.RedisQueueSize = queueSize
		}
		stats.PendingOps = int(stats.RedisQueueSize)
	} else {
		stats.PendingOps = len(sm.pendingOps[sourceID]) + len(sm.retryQueue[sourceID])
	}

	result := *stats
	return &result
}

func (sm *SyncManager) ResetStats(sourceID string) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	sm.stats[sourceID] = &SyncStats{}
	sm.totalProcessed = 0
	sm.totalFailed = 0
}

func (sm *SyncManager) GetAllStats() map[string]*SyncStats {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	result := make(map[string]*SyncStats)
	for id, stats := range sm.stats {
		newStats := &SyncStats{
			TotalOps:   stats.TotalOps,
			SuccessOps: stats.SuccessOps,
			FailedOps:  stats.FailedOps,
			RetriedOps: stats.RetriedOps,
		}
		
		if sm.useRedis {
			if queueSize, err := sm.redisQueue.QueueSize(id + ":pending"); err == nil {
				newStats.RedisQueueSize = queueSize
			}
			newStats.PendingOps = int(newStats.RedisQueueSize)
		} else {
			newStats.PendingOps = len(sm.pendingOps[id]) + len(sm.retryQueue[id])
		}
		
		result[id] = newStats
	}
	return result
}

func (sm *SyncManager) Start(ctx context.Context, interval time.Duration) {
	sm.recoverPendingOps()

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	sm.logger.Infof("Sync manager started, interval: %v, workers: %d, redis: %v", 
		interval, sm.workerCount, sm.useRedis)

	for i := 0; i < sm.workerCount; i++ {
		sm.wg.Add(1)
		go sm.workerLoop(ctx)
	}

	for {
		select {
		case <-ctx.Done():
			sm.logger.Info("Sync manager stopped")
			sm.wg.Wait()
			return
		case <-sm.stopCh:
			sm.logger.Info("Sync manager stopped via stop channel")
			sm.wg.Wait()
			return
		case <-ticker.C:
			sm.ProcessPendingOps()
		}
	}
}

func (sm *SyncManager) Stop() {
	close(sm.stopCh)
	if sm.redisQueue != nil {
		sm.redisQueue.Close()
	}
}

func (sm *SyncManager) recoverPendingOps() {
	if !sm.useRedis || sm.redisQueue == nil {
		return
	}

	sm.logger.Info("Recovering pending sync operations from Redis...")
	queues, err := sm.redisQueue.ListQueues("*:pending")
	if err != nil {
		sm.logger.Warnf("Failed to list Redis queues: %v", err)
		return
	}

	recovered := 0
	for _, queueKey := range queues {
		sm.logger.Infof("Found pending queue: %s", queueKey)
		for {
			data, err := sm.redisQueue.DequeueNonBlocking(queueKey)
			if err != nil || data == nil {
				break
			}
			
			op, err := deserializeOp(data)
			if err != nil {
				continue
			}
			
			sm.enqueueToMemory(op)
			recovered++
		}
	}

	retryQueues, _ := sm.redisQueue.ListQueues("*:retry")
	for _, retryKey := range retryQueues {
		fields, err := sm.redisQueue.GetAllHashFields(retryKey)
		if err == nil {
			for field, jsonStr := range fields {
				op, err := deserializeOp([]byte(jsonStr))
				if err == nil {
					sm.inFlightOps[field] = op
					sm.redisQueue.EnqueueToSet(field, jsonStr, float64(op.NextRetryAt.Unix()))
					recovered++
				}
			}
		}
	}

	if recovered > 0 {
		sm.logger.Infof("Recovered %d pending sync operations from Redis", recovered)
	}
}

func (sm *SyncManager) workerLoop(ctx context.Context) {
	defer sm.wg.Done()
	for {
		select {
		case <-ctx.Done():
			return
		case <-sm.stopCh:
			return
		default:
			if sm.useRedis {
				sm.processRedisQueue()
			}
			time.Sleep(100 * time.Millisecond)
		}
	}
}

func (sm *SyncManager) processRedisQueue() {
	if !sm.useRedis {
		return
	}

	sm.mu.RLock()
	configs := make(map[string]*SyncConfig)
	for k, v := range sm.configs {
		configs[k] = v
	}
	sm.mu.RUnlock()

	for sourceID, config := range configs {
		if !config.Enabled {
			continue
		}

		queueName := sourceID + ":pending"
		data, err := sm.redisQueue.DequeueNonBlocking(queueName)
		if err != nil || data == nil {
			continue
		}

		op, err := deserializeOp(data)
		if err != nil {
			continue
		}

		sm.mu.Lock()
		stats := sm.getOrCreateStats(sourceID)
		stats.TotalOps++
		sm.mu.Unlock()

		sm.executeSyncOpWithRetry(sourceID, config, op)
	}

	for sourceID, config := range configs {
		if !config.Enabled {
			continue
		}
		sm.processRedisRetryQueue(sourceID, config)
	}
}

func (sm *SyncManager) processRedisRetryQueue(sourceID string, config *SyncConfig) {
	if !sm.useRedis {
		return
	}

	now := time.Now().Unix()
	members, err := sm.redisQueue.GetFromSetByScore(sourceID+":retry", 0, float64(now), 100)
	if err != nil || len(members) == 0 {
		return
	}

	for _, member := range members {
		op, err := deserializeOp([]byte(member))
		if err != nil {
			continue
		}

		sm.redisQueue.RemoveFromSet(sourceID+":retry", member)

		sm.mu.Lock()
		stats := sm.getOrCreateStats(sourceID)
		stats.RetriedOps++
		sm.mu.Unlock()

		sm.executeSyncOpWithRetry(sourceID, config, op)
	}
}

func (sm *SyncManager) RegisterSyncConfig(config *SyncConfig) error {
	if config.SourceCacheID == "" {
		return errors.New("source_cache_id is required")
	}
	if len(config.TargetCacheIDs) == 0 {
		return errors.New("at least one target cache is required")
	}

	sm.mu.Lock()
	defer sm.mu.Unlock()

	sm.configs[config.SourceCacheID] = config
	sm.logger.Infof("Sync config registered: %s -> %v", config.SourceCacheID, config.TargetCacheIDs)
	return nil
}

func (sm *SyncManager) RemoveSyncConfig(sourceCacheID string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if _, exists := sm.configs[sourceCacheID]; !exists {
		return errors.New("sync config not found")
	}

	delete(sm.configs, sourceCacheID)
	
	if sm.useRedis {
		sm.redisQueue.ClearQueue(sourceCacheID + ":pending")
		sm.redisQueue.ClearQueue(sourceCacheID + ":retry")
	}
	
	sm.logger.Infof("Sync config removed for source: %s", sourceCacheID)
	return nil
}

func (sm *SyncManager) GetSyncConfig(sourceCacheID string) (*SyncConfig, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	config, exists := sm.configs[sourceCacheID]
	if !exists {
		return nil, errors.New("sync config not found")
	}
	return config, nil
}

func (sm *SyncManager) enqueueToRedis(op *SyncOperation) error {
	if !sm.useRedis {
		return errors.New("redis not available")
	}
	return sm.redisQueue.Enqueue(op.SourceID+":pending", op)
}

func (sm *SyncManager) enqueueToMemory(op *SyncOperation) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	ops, exists := sm.pendingOps[op.SourceID]
	if !exists {
		ops = make([]*SyncOperation, 0)
	}
	ops = append(ops, op)
	if len(ops) > 10000 {
		ops = ops[len(ops)-10000:]
	}
	sm.pendingOps[op.SourceID] = ops
}

func (sm *SyncManager) QueueSyncOp(sourceCacheID string, op *SyncOperation) {
	op.SourceID = sourceCacheID
	op.ID = generateOpID()
	op.Version = time.Now().UnixNano()

	if sm.useRedis {
		if err := sm.enqueueToRedis(op); err != nil {
			sm.enqueueToMemory(op)
		}
	} else {
		sm.enqueueToMemory(op)
	}

	sm.logger.Debugf("Queued sync op for %s: %s %s", sourceCacheID, op.Operation, op.Key)
}

func (sm *SyncManager) ProcessPendingOps() {
	if sm.useRedis {
		return
	}

	sm.mu.RLock()
	configs := make(map[string]*SyncConfig)
	for k, v := range sm.configs {
		configs[k] = v
	}
	sm.mu.RUnlock()

	for sourceID, config := range configs {
		if !config.Enabled {
			continue
		}

		sm.ProcessRetryQueue(sourceID, config)

		sm.mu.Lock()
		ops, exists := sm.pendingOps[sourceID]
		if exists {
			sm.pendingOps[sourceID] = make([]*SyncOperation, 0)
		}
		sm.mu.Unlock()

		if !exists || len(ops) == 0 {
			continue
		}

		sm.logger.Infof("Processing %d pending sync ops for source: %s", len(ops), sourceID)

		for _, op := range ops {
			sm.mu.Lock()
			stats := sm.getOrCreateStats(sourceID)
			stats.TotalOps++
			sm.mu.Unlock()

			sm.executeSyncOpWithRetry(sourceID, config, op)
		}
	}
}

func (sm *SyncManager) ProcessRetryQueue(sourceID string, config *SyncConfig) {
	if sm.useRedis {
		sm.processRedisRetryQueue(sourceID, config)
		return
	}

	sm.mu.Lock()
	retryOps, exists := sm.retryQueue[sourceID]
	if !exists || len(retryOps) == 0 {
		sm.mu.Unlock()
		return
	}

	now := time.Now()
	dueOps := make([]*SyncOperation, 0)
	remainingOps := make([]*SyncOperation, 0)

	for _, op := range retryOps {
		if now.After(op.NextRetryAt) {
			dueOps = append(dueOps, op)
		} else {
			remainingOps = append(remainingOps, op)
		}
	}

	sm.retryQueue[sourceID] = remainingOps
	sm.mu.Unlock()

	if len(dueOps) > 0 {
		sm.logger.Infof("Processing %d retry ops for source: %s", len(dueOps), sourceID)
		for _, op := range dueOps {
			sm.mu.Lock()
			stats := sm.getOrCreateStats(sourceID)
			stats.RetriedOps++
			sm.mu.Unlock()

			sm.executeSyncOpWithRetry(sourceID, config, op)
		}
	}
}

func (sm *SyncManager) scheduleRetry(sourceID string, config *SyncConfig, op *SyncOperation) {
	maxRetries := config.RetryMax
	if maxRetries <= 0 {
		maxRetries = 3
	}
	retryDelay := config.RetryDelay
	if retryDelay <= 0 {
		retryDelay = 1 * time.Second
	}

	if op.RetryCount < maxRetries {
		op.RetryCount++
		op.NextRetryAt = time.Now().Add(retryDelay * time.Duration(1<<uint(op.RetryCount-1)))

		if sm.useRedis {
			jsonData, _ := json.Marshal(op)
			sm.redisQueue.EnqueueToSet(sourceID+":retry", string(jsonData), float64(op.NextRetryAt.Unix()))
		} else {
			sm.mu.Lock()
			retryQueue, exists := sm.retryQueue[sourceID]
			if !exists {
				retryQueue = make([]*SyncOperation, 0)
			}
			sm.retryQueue[sourceID] = append(retryQueue, op)
			sm.mu.Unlock()
		}

		sm.logger.Warnf("Sync failed for target %s, retry %d/%d scheduled at %v",
			op.TargetID, op.RetryCount, maxRetries, op.NextRetryAt)
	} else {
		sm.mu.Lock()
		stats := sm.getOrCreateStats(sourceID)
		stats.FailedOps++
		sm.mu.Unlock()
		sm.totalFailed++

		sm.logger.Errorf("Sync failed for target %s after %d retries: key=%s",
			op.TargetID, maxRetries, op.Key)
	}
}

func (sm *SyncManager) executeSyncOpWithRetry(sourceID string, config *SyncConfig, op *SyncOperation) {
	for _, targetID := range config.TargetCacheIDs {
		targetOp := *op
		targetOp.TargetID = targetID

		success := sm.executeSingleTargetSync(sourceID, targetID, &targetOp)
		if success {
			sm.mu.Lock()
			stats := sm.getOrCreateStats(sourceID)
			stats.SuccessOps++
			sm.mu.Unlock()
			sm.totalProcessed++
		} else {
			sm.scheduleRetry(sourceID, config, &targetOp)
		}
	}
}

func (sm *SyncManager) executeSingleTargetSync(sourceID, targetID string, op *SyncOperation) bool {
	cache, err := sm.cm.GetCache(targetID)
	if err != nil {
		sm.logger.Warnf("Sync failed for target %s: %v", targetID, err)
		return false
	}

	switch op.Operation {
	case "set":
		cache.Set(op.Key, op.Value, op.TTL)
		sm.logger.Debugf("Synced set: %s -> %s, key: %s", sourceID, targetID, op.Key)
	case "delete":
		cache.Delete(op.Key)
		sm.logger.Debugf("Synced delete: %s -> %s, key: %s", sourceID, targetID, op.Key)
	}

	return true
}

func (sm *SyncManager) executeSyncOp(sourceID string, targetIDs []string, op *SyncOperation) {
	for _, targetID := range targetIDs {
		success := sm.executeSingleTargetSync(sourceID, targetID, op)
		if success {
			sm.mu.Lock()
			stats := sm.getOrCreateStats(sourceID)
			stats.SuccessOps++
			stats.TotalOps++
			sm.mu.Unlock()
			sm.totalProcessed++
		}
	}
}

func (sm *SyncManager) GetRetryQueueSize(sourceID string) int {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	if sm.useRedis {
		if size, err := sm.redisQueue.SetSize(sourceID + ":retry"); err == nil {
			return int(size)
		}
		return 0
	}
	return len(sm.retryQueue[sourceID])
}

func (sm *SyncManager) ClearRetryQueue(sourceID string) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if sm.useRedis {
		sm.redisQueue.ClearQueue(sourceID + ":retry")
	}
	sm.retryQueue[sourceID] = make([]*SyncOperation, 0)
}

func (sm *SyncManager) SyncSet(sourceCacheID, key string, value interface{}, ttl int) {
	sm.mu.RLock()
	config, exists := sm.configs[sourceCacheID]
	sm.mu.RUnlock()

	if !exists || !config.Enabled {
		return
	}

	op := &SyncOperation{
		Operation: "set",
		Key:       key,
		Value:     value,
		TTL:       ttl,
		Timestamp: time.Now(),
	}

	if config.SyncMode == "realtime" {
		sm.executeSyncOp(sourceCacheID, config.TargetCacheIDs, op)
	} else {
		sm.QueueSyncOp(sourceCacheID, op)
	}
}

func (sm *SyncManager) SyncDelete(sourceCacheID, key string) {
	sm.mu.RLock()
	config, exists := sm.configs[sourceCacheID]
	sm.mu.RUnlock()

	if !exists || !config.Enabled {
		return
	}

	op := &SyncOperation{
		Operation: "delete",
		Key:       key,
		Timestamp: time.Now(),
	}

	if config.SyncMode == "realtime" {
		sm.executeSyncOp(sourceCacheID, config.TargetCacheIDs, op)
	} else {
		sm.QueueSyncOp(sourceCacheID, op)
	}
}

func (sm *SyncManager) FullSync(sourceCacheID string) (int, error) {
	sm.mu.RLock()
	config, exists := sm.configs[sourceCacheID]
	sm.mu.RUnlock()

	if !exists {
		return 0, errors.New("sync config not found")
	}

	sourceCache, err := sm.cm.GetCache(sourceCacheID)
	if err != nil {
		return 0, err
	}

	items := sourceCache.GetAll()
	count := 0

	for _, item := range items {
		for _, targetID := range config.TargetCacheIDs {
			targetCache, err := sm.cm.GetCache(targetID)
			if err != nil {
				continue
			}
			targetCache.Set(item.Key, item.Value, int(time.Until(item.ExpireAt).Seconds()))
			count++
		}
	}

	sm.logger.Infof("Full sync completed from %s: %d items synced", sourceCacheID, count)
	return count, nil
}

func (sm *SyncManager) CheckConsistency(sourceCacheID string) (map[string]bool, error) {
	sm.mu.RLock()
	config, exists := sm.configs[sourceCacheID]
	sm.mu.RUnlock()

	if !exists {
		return nil, errors.New("sync config not found")
	}

	sourceCache, err := sm.cm.GetCache(sourceCacheID)
	if err != nil {
		return nil, err
	}

	sourceKeys := make(map[string]bool)
	for _, k := range sourceCache.GetKeys() {
		sourceKeys[k] = true
	}

	consistency := make(map[string]bool)

	for _, targetID := range config.TargetCacheIDs {
		targetCache, err := sm.cm.GetCache(targetID)
		if err != nil {
			continue
		}

		targetKeys := make(map[string]bool)
		for _, k := range targetCache.GetKeys() {
			targetKeys[k] = true
		}

		isConsistent := len(sourceKeys) == len(targetKeys)
		if isConsistent {
			for k := range sourceKeys {
				if !targetKeys[k] {
					isConsistent = false
					break
				}
			}
		}

		consistency[targetID] = isConsistent
	}

	return consistency, nil
}

func (sm *SyncManager) GetQueueSize(sourceID string) int {
	if sm.useRedis {
		size, _ := sm.redisQueue.QueueSize(sourceID + ":pending")
		return int(size)
	}
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return len(sm.pendingOps[sourceID])
}

func (sm *SyncManager) ClearQueue(sourceID string) {
	if sm.useRedis {
		sm.redisQueue.ClearQueue(sourceID + ":pending")
	}
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.pendingOps[sourceID] = make([]*SyncOperation, 0)
}

func generateOpID() string {
	return "op_" + time.Now().Format("20060102150405.000000000")
}

func deserializeOp(data []byte) (*SyncOperation, error) {
	var op SyncOperation
	err := json.Unmarshal(data, &op)
	if err != nil {
		return nil, err
	}
	return &op, nil
}

type SyncManagerOptions struct {
	WorkerCount       int
	PersistenceConfig *SyncPersistenceConfig
	CacheManager      *cache_manager.CacheManager
	Logger            *logrus.Logger
}

func NewSyncManagerWithOptions(opts *SyncManagerOptions) *SyncManager {
	if opts == nil {
		opts = &SyncManagerOptions{}
	}
	if opts.WorkerCount <= 0 {
		opts.WorkerCount = 3
	}
	if opts.Logger == nil {
		opts.Logger = logrus.New()
	}
	if opts.PersistenceConfig == nil {
		opts.PersistenceConfig = DefaultPersistenceConfig()
	}

	sm := &SyncManager{
		cm:                opts.CacheManager,
		logger:            opts.Logger,
		configs:           make(map[string]*SyncConfig),
		pendingOps:        make(map[string][]*SyncOperation),
		retryQueue:        make(map[string][]*SyncOperation),
		stats:             make(map[string]*SyncStats),
		stopCh:            make(chan struct{}),
		workerCount:       opts.WorkerCount,
		persistenceConfig: opts.PersistenceConfig,
		useRedis:          false,
		inFlightOps:       make(map[string]*SyncOperation),
	}

	if opts.PersistenceConfig.Enabled {
		redisQueue, err := storage.NewRedisQueue(opts.PersistenceConfig.RedisConfig, opts.Logger)
		if err == nil {
			sm.redisQueue = redisQueue
			sm.useRedis = true
			opts.Logger.Info("Redis persistence enabled for sync queue")
		} else if !opts.PersistenceConfig.FallbackToMemory {
			opts.Logger.Fatalf("Redis persistence required but connection failed: %v", err)
		} else {
			opts.Logger.Warn("Redis unavailable, using in-memory fallback for sync queue")
		}
	}

	return sm
}

func (sm *SyncManager) IsPersistenceEnabled() bool {
	return sm.useRedis
}

func (sm *SyncManager) GetPersistenceStats() map[string]interface{} {
	stats := map[string]interface{}{
		"persistence_enabled": sm.useRedis,
		"total_processed":     sm.totalProcessed,
		"total_failed":        sm.totalFailed,
	}

	if sm.useRedis {
		stats["redis_available"] = sm.redisQueue.IsAvailable()
	}

	return stats
}
