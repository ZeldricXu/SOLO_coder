package gasestimator

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/blockchain-middleware/core/internal/common/config"
	"github.com/blockchain-middleware/core/internal/common/logger"
)

type CacheEntry struct {
	Data      []byte
	ExpiresAt time.Time
}

type L1Cache struct {
	data  map[string]CacheEntry
	mu    sync.RWMutex
	ttl   time.Duration
	maxSize int
}

func NewL1Cache(ttl time.Duration, maxSize int) *L1Cache {
	cache := &L1Cache{
		data:    make(map[string]CacheEntry),
		ttl:     ttl,
		maxSize: maxSize,
	}
	go cache.startCleanup()
	return cache
}

func (c *L1Cache) Get(key string) ([]byte, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	entry, exists := c.data[key]
	if !exists {
		return nil, false
	}

	if time.Now().After(entry.ExpiresAt) {
		return nil, false
	}

	return entry.Data, true
}

func (c *L1Cache) Set(key string, data []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if len(c.data) >= c.maxSize {
		c.evictOldest()
	}

	c.data[key] = CacheEntry{
		Data:      data,
		ExpiresAt: time.Now().Add(c.ttl),
	}
}

func (c *L1Cache) Delete(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.data, key)
}

func (c *L1Cache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.data = make(map[string]CacheEntry)
}

func (c *L1Cache) evictOldest() {
	var oldestKey string
	var oldestTime time.Time

	for key, entry := range c.data {
		if oldestTime.IsZero() || entry.ExpiresAt.Before(oldestTime) {
			oldestKey = key
			oldestTime = entry.ExpiresAt
		}
	}

	if oldestKey != "" {
		delete(c.data, oldestKey)
	}
}

func (c *L1Cache) startCleanup() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		c.mu.Lock()
		now := time.Now()
		for key, entry := range c.data {
			if now.After(entry.ExpiresAt) {
				delete(c.data, key)
			}
		}
		c.mu.Unlock()
	}
}

func (c *L1Cache) Stats() (int, int) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.data), c.maxSize
}

type MultiLevelCache struct {
	l1         *L1Cache
	l2         *redis.Client
	l1TTL      time.Duration
	l2TTL      time.Duration
	stats      *CacheStats
	statsMutex sync.Mutex
}

type CacheStats struct {
	L1Hits      uint64
	L1Misses    uint64
	L2Hits      uint64
	L2Misses    uint64
	Evictions   uint64
	TotalRequests uint64
}

func NewMultiLevelCache(redisClient *redis.Client, l1TTL, l2TTL time.Duration, l1MaxSize int) *MultiLevelCache {
	return &MultiLevelCache{
		l1:    NewL1Cache(l1TTL, l1MaxSize),
		l2:    redisClient,
		l1TTL: l1TTL,
		l2TTL: l2TTL,
		stats: &CacheStats{},
	}
}

func (mc *MultiLevelCache) Get(ctx context.Context, key string) ([]byte, error) {
	mc.statsMutex.Lock()
	mc.stats.TotalRequests++
	mc.statsMutex.Unlock()

	data, found := mc.l1.Get(key)
	if found {
		mc.statsMutex.Lock()
		mc.stats.L1Hits++
		mc.statsMutex.Unlock()
		return data, nil
	}

	mc.statsMutex.Lock()
	mc.stats.L1Misses++
	mc.statsMutex.Unlock()

	if mc.l2 != nil {
		data, err := mc.l2.Get(ctx, key).Bytes()
		if err == nil {
			mc.statsMutex.Lock()
			mc.stats.L2Hits++
			mc.statsMutex.Unlock()

			mc.l1.Set(key, data)
			return data, nil
		}

		mc.statsMutex.Lock()
		mc.stats.L2Misses++
		mc.statsMutex.Unlock()
	}

	return nil, fmt.Errorf("cache miss")
}

func (mc *MultiLevelCache) Set(ctx context.Context, key string, data []byte) error {
	mc.l1.Set(key, data)

	if mc.l2 != nil {
		err := mc.l2.SetEX(ctx, key, data, mc.l2TTL).Err()
		if err != nil {
			logger.Log.Warn("Failed to set L2 cache", zap.String("key", key), zap.Error(err))
		}
	}

	return nil
}

func (mc *MultiLevelCache) Delete(ctx context.Context, key string) {
	mc.l1.Delete(key)
	if mc.l2 != nil {
		mc.l2.Del(ctx, key)
	}
}

func (mc *MultiLevelCache) Clear(ctx context.Context) {
	mc.l1.Clear()
}

func (mc *MultiLevelCache) GetStats() CacheStats {
	mc.statsMutex.Lock()
	defer mc.statsMutex.Unlock()
	return *mc.stats
}

func (mc *MultiLevelCache) ResetStats() {
	mc.statsMutex.Lock()
	defer mc.statsMutex.Unlock()
	mc.stats = &CacheStats{}
}

type CacheWarmer struct {
	cache       *MultiLevelCache
	estimator   *GasEstimator
	chainIDs    []uint64
	interval    time.Duration
	ctx         context.Context
	cancel      context.CancelFunc
}

func NewCacheWarmer(cache *MultiLevelCache, estimator *GasEstimator, chainIDs []uint64, interval time.Duration) *CacheWarmer {
	ctx, cancel := context.WithCancel(context.Background())
	return &CacheWarmer{
		cache:     cache,
		estimator: estimator,
		chainIDs:  chainIDs,
		interval:  interval,
		ctx:       ctx,
		cancel:    cancel,
	}
}

func (cw *CacheWarmer) Start() {
	logger.Log.Info("Starting cache warmer", zap.Duration("interval", cw.interval))

	go func() {
		cw.warmCache()

		ticker := time.NewTicker(cw.interval)
		defer ticker.Stop()

		for {
			select {
			case <-cw.ctx.Done():
				return
			case <-ticker.C:
				cw.warmCache()
			}
		}
	}()
}

func (cw *CacheWarmer) Stop() {
	cw.cancel()
	logger.Log.Info("Cache warmer stopped")
}

func (cw *CacheWarmer) warmCache() {
	for _, chainID := range cw.chainIDs {
		go func(id uint64) {
			cacheKey := fmt.Sprintf("gas:estimate:%d", id)

			result, err := cw.estimator.EstimateGasWithoutCache(cw.ctx, id)
			if err != nil {
				logger.Log.Warn("Cache warming failed", zap.Uint64("chain_id", id), zap.Error(err))
				return
			}

			data, err := json.Marshal(result)
			if err != nil {
				return
			}

			if err := cw.cache.Set(cw.ctx, cacheKey, data); err != nil {
				logger.Log.Warn("Failed to cache warmed data", zap.Uint64("chain_id", id), zap.Error(err))
			}

			logger.Log.Debug("Cache warmed", zap.Uint64("chain_id", id))
		}(chainID)
	}
}

type CacheInvalidationStrategy interface {
	ShouldInvalidate(ctx context.Context, key string, data interface{}) bool
	Invalidate(ctx context.Context, cache *MultiLevelCache, key string)
}

type BlockBasedInvalidation struct {
	lastBlock map[uint64]uint64
	mu        sync.RWMutex
}

func NewBlockBasedInvalidation() *BlockBasedInvalidation {
	return &BlockBasedInvalidation{
		lastBlock: make(map[uint64]uint64),
	}
}

func (bi *BlockBasedInvalidation) ShouldInvalidate(ctx context.Context, chainID uint64, currentBlock uint64) bool {
	bi.mu.RLock()
	last, exists := bi.lastBlock[chainID]
	bi.mu.RUnlock()

	if !exists {
		bi.mu.Lock()
		bi.lastBlock[chainID] = currentBlock
		bi.mu.Unlock()
		return false
	}

	return currentBlock > last
}

func (bi *BlockBasedInvalidation) UpdateBlock(chainID uint64, blockNumber uint64) {
	bi.mu.Lock()
	defer bi.mu.Unlock()
	bi.lastBlock[chainID] = blockNumber
}

type TimeBasedInvalidation struct {
	interval time.Duration
	lastInvalidate map[string]time.Time
	mu             sync.RWMutex
}

func NewTimeBasedInvalidation(interval time.Duration) *TimeBasedInvalidation {
	return &TimeBasedInvalidation{
		interval:       interval,
		lastInvalidate: make(map[string]time.Time),
	}
}

func (ti *TimeBasedInvalidation) ShouldInvalidate(key string) bool {
	ti.mu.RLock()
	last, exists := ti.lastInvalidate[key]
	ti.mu.RUnlock()

	if !exists {
		ti.mu.Lock()
		ti.lastInvalidate[key] = time.Now()
		ti.mu.Unlock()
		return false
	}

	return time.Since(last) > ti.interval
}

func (ti *TimeBasedInvalidation) MarkInvalidated(key string) {
	ti.mu.Lock()
	defer ti.mu.Unlock()
	ti.lastInvalidate[key] = time.Now()
}

type CacheInvalidator struct {
	blockInvalidation *BlockBasedInvalidation
	timeInvalidation  *TimeBasedInvalidation
	cache             *MultiLevelCache
}

func NewCacheInvalidator(cache *MultiLevelCache) *CacheInvalidator {
	return &CacheInvalidator{
		blockInvalidation: NewBlockBasedInvalidation(),
		timeInvalidation:  NewTimeBasedInvalidation(30 * time.Second),
		cache:             cache,
	}
}

func (ci *CacheInvalidator) OnNewBlock(ctx context.Context, chainID uint64, blockNumber uint64) {
	if ci.blockInvalidation.ShouldInvalidate(ctx, chainID, blockNumber) {
		key := fmt.Sprintf("gas:estimate:%d", chainID)
		ci.cache.Delete(ctx, key)
		ci.blockInvalidation.UpdateBlock(chainID, blockNumber)
		logger.Log.Debug("Cache invalidated due to new block",
			zap.Uint64("chain_id", chainID),
			zap.Uint64("block_number", blockNumber))
	}
}

func (ci *CacheInvalidator) ManualInvalidate(ctx context.Context, chainID uint64) {
	key := fmt.Sprintf("gas:estimate:%d", chainID)
	ci.cache.Delete(ctx, key)
	logger.Log.Info("Cache manually invalidated", zap.Uint64("chain_id", chainID))
}

func (ci *CacheInvalidator) GetStats() map[string]interface{} {
	stats := ci.cache.GetStats()
	l1HitRate := float64(0)
	if stats.L1Hits+stats.L1Misses > 0 {
		l1HitRate = float64(stats.L1Hits) / float64(stats.L1Hits+stats.L1Misses) * 100
	}

	l2HitRate := float64(0)
	if stats.L2Hits+stats.L2Misses > 0 {
		l2HitRate = float64(stats.L2Hits) / float64(stats.L2Hits+stats.L2Misses) * 100
	}

	l1Size, l1Max := ci.cache.l1.Stats()

	return map[string]interface{}{
		"l1": map[string]interface{}{
			"hits":      stats.L1Hits,
			"misses":    stats.L1Misses,
			"hit_rate":  l1HitRate,
			"size":      l1Size,
			"max_size":  l1Max,
		},
		"l2": map[string]interface{}{
			"hits":     stats.L2Hits,
			"misses":   stats.L2Misses,
			"hit_rate": l2HitRate,
		},
		"total_requests": stats.TotalRequests,
		"evictions":      stats.Evictions,
	}
}

func (ge *GasEstimator) EstimateGasWithoutCache(ctx context.Context, chainID uint64) (*GasEstimateResult, error) {
	ge.mu.RLock()
	rpc, exists := ge.chainRPC[chainID]
	ge.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	result, err := ge.calculateGasEstimate(ctx, chainID, rpc)
	if err != nil {
		logger.Log.Error("Gas estimation failed", zap.Uint64("chain_id", chainID), zap.Error(err))
		return nil, errors.ErrGasEstimationFailed
	}

	return result, nil
}
