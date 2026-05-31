package config

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"session130/internal/logger"
	"session130/internal/metrics"
	"session130/pkg/models"
)

const (
	defaultL1TTL       = 5 * time.Minute
	defaultL1MaxSize   = 1000
	defaultL2TTL       = 30 * time.Minute
	defaultL2MaxSize   = 10000
	defaultCacheName   = "config_cache"
	ttlCleanupInterval = 1 * time.Minute
)

var (
	globalEventBus *EventBus
	eventBusOnce   sync.Once
)

func GetCacheEventBus() *EventBus {
	eventBusOnce.Do(func() {
		globalEventBus = GetEventBus()
	})
	return globalEventBus
}

type CacheLevel string

const (
	CacheLevelL1  CacheLevel = "l1"
	CacheLevelL2  CacheLevel = "l2"
	CacheLevelAll CacheLevel = "all"
)

type CacheEntry struct {
	Config    *models.Config
	ExpiresAt time.Time
	HitCount  int64
	LoadedAt  time.Time
}

type CacheInvalidationStrategy string

const (
	InvalidateOnWrite CacheInvalidationStrategy = "on_write"
	InvalidateTTL     CacheInvalidationStrategy = "ttl"
	InvalidateManual  CacheInvalidationStrategy = "manual"
)

type CacheConfig struct {
	L1Enabled            bool
	L1TTL                time.Duration
	L1MaxSize            int
	L2Enabled            bool
	L2TTL                time.Duration
	L2MaxSize            int
	InvalidationStrategy CacheInvalidationStrategy
	EnableMetrics        bool
}

func DefaultCacheConfig() CacheConfig {
	return CacheConfig{
		L1Enabled:            true,
		L1TTL:                defaultL1TTL,
		L1MaxSize:            defaultL1MaxSize,
		L2Enabled:            true,
		L2TTL:                defaultL2TTL,
		L2MaxSize:            defaultL2MaxSize,
		InvalidationStrategy: InvalidateOnWrite,
		EnableMetrics:        true,
	}
}

type L1Cache struct {
	mu      sync.RWMutex
	entries map[string]*CacheEntry
	maxSize int
	ttl     time.Duration
}

func NewL1Cache(maxSize int, ttl time.Duration) *L1Cache {
	return &L1Cache{
		entries: make(map[string]*CacheEntry, maxSize),
		maxSize: maxSize,
		ttl:     ttl,
	}
}

func (c *L1Cache) Get(namespace string) (*models.Config, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	entry, exists := c.entries[namespace]
	if !exists {
		return nil, false
	}

	if time.Now().After(entry.ExpiresAt) {
		return nil, false
	}

	entry.HitCount++
	return entry.Config, true
}

func (c *L1Cache) Set(cfg *models.Config) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if len(c.entries) >= c.maxSize {
		c.evictOldestLocked()
	}

	c.entries[cfg.Namespace] = &CacheEntry{
		Config:    cfg,
		ExpiresAt: time.Now().Add(c.ttl),
		HitCount:  0,
		LoadedAt:  time.Now(),
	}
}

func (c *L1Cache) Delete(namespace string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.entries, namespace)
}

func (c *L1Cache) evictOldestLocked() {
	var oldestKey string
	var oldestTime time.Time

	for key, entry := range c.entries {
		if oldestTime.IsZero() || entry.LoadedAt.Before(oldestTime) {
			oldestKey = key
			oldestTime = entry.LoadedAt
		}
	}

	if oldestKey != "" {
		delete(c.entries, oldestKey)
	}
}

func (c *L1Cache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries = make(map[string]*CacheEntry, c.maxSize)
}

func (c *L1Cache) GetStats() map[string]interface{} {
	c.mu.RLock()
	defer c.mu.RUnlock()

	totalHits := int64(0)
	for _, entry := range c.entries {
		totalHits += entry.HitCount
	}

	return map[string]interface{}{
		"size":       len(c.entries),
		"max_size":   c.maxSize,
		"ttl":        c.ttl.String(),
		"total_hits": totalHits,
	}
}

type L2CacheEntry struct {
	Config    []byte
	ExpiresAt time.Time
	Version   int
}

type L2Cache struct {
	mu      sync.RWMutex
	entries map[string]*L2CacheEntry
	maxSize int
	ttl     time.Duration
}

func NewL2Cache(maxSize int, ttl time.Duration) *L2Cache {
	return &L2Cache{
		entries: make(map[string]*L2CacheEntry, maxSize),
		maxSize: maxSize,
		ttl:     ttl,
	}
}

func (c *L2Cache) Get(namespace string) (*models.Config, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	entry, exists := c.entries[namespace]
	if !exists {
		return nil, false
	}

	if time.Now().After(entry.ExpiresAt) {
		return nil, false
	}

	var cfg models.Config
	if err := json.Unmarshal(entry.Config, &cfg); err != nil {
		return nil, false
	}

	return &cfg, true
}

func (c *L2Cache) Set(cfg *models.Config) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if len(c.entries) >= c.maxSize {
		c.evictOldestLocked()
	}

	data, err := json.Marshal(cfg)
	if err != nil {
		return err
	}

	c.entries[cfg.Namespace] = &L2CacheEntry{
		Config:    data,
		ExpiresAt: time.Now().Add(c.ttl),
		Version:   cfg.Version,
	}

	return nil
}

func (c *L2Cache) Delete(namespace string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.entries, namespace)
}

func (c *L2Cache) evictOldestLocked() {
	var oldestKey string
	var oldestExpire time.Time

	for key, entry := range c.entries {
		if oldestExpire.IsZero() || entry.ExpiresAt.Before(oldestExpire) {
			oldestKey = key
			oldestExpire = entry.ExpiresAt
		}
	}

	if oldestKey != "" {
		delete(c.entries, oldestKey)
	}
}

func (c *L2Cache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries = make(map[string]*L2CacheEntry, c.maxSize)
}

func (c *L2Cache) GetStats() map[string]interface{} {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return map[string]interface{}{
		"size":     len(c.entries),
		"max_size": c.maxSize,
		"ttl":      c.ttl.String(),
	}
}

type MultiLevelCache struct {
	l1        *L1Cache
	l2        *L2Cache
	config    CacheConfig
	hitCount  atomic.Int64
	missCount atomic.Int64
}

func NewMultiLevelCache(cfg CacheConfig) *MultiLevelCache {
	mlc := &MultiLevelCache{
		config: cfg,
	}

	if cfg.L1Enabled {
		mlc.l1 = NewL1Cache(cfg.L1MaxSize, cfg.L1TTL)
	}

	if cfg.L2Enabled {
		mlc.l2 = NewL2Cache(cfg.L2MaxSize, cfg.L2TTL)
	}

	return mlc
}

func (c *MultiLevelCache) Get(ctx context.Context, namespace string) (*models.Config, CacheLevel, error) {
	start := time.Now()

	if cfg, level, ok := c.tryGetFromCaches(namespace); ok {
		c.recordHit(level)
		c.emitGetEvent(namespace, cfg, level, true, start, nil)
		return cfg, level, nil
	}

	c.recordMiss()
	c.emitGetEvent(namespace, nil, "", false, start, nil)
	return nil, "", nil
}

func (c *MultiLevelCache) tryGetFromCaches(namespace string) (*models.Config, CacheLevel, bool) {
	if c.l1 != nil {
		if cfg, ok := c.l1.Get(namespace); ok {
			return cfg, CacheLevelL1, true
		}
	}

	if c.l2 != nil {
		if cfg, ok := c.l2.Get(namespace); ok {
			if c.l1 != nil {
				c.l1.Set(cfg)
			}
			return cfg, CacheLevelL2, true
		}
	}

	return nil, "", false
}

func (c *MultiLevelCache) Set(ctx context.Context, cfg *models.Config) error {
	start := time.Now()

	if c.l1 != nil {
		c.l1.Set(cfg)
	}

	if c.l2 != nil {
		if err := c.l2.Set(cfg); err != nil {
			c.emitSetEvent(cfg, start, err)
			return fmt.Errorf("l2 cache set failed: %w", err)
		}
	}

	c.emitSetEvent(cfg, start, nil)
	return nil
}

func (c *MultiLevelCache) Invalidate(ctx context.Context, namespace string, level CacheLevel) error {
	start := time.Now()

	c.deleteFromCaches(namespace, level)

	if c.config.EnableMetrics {
		metrics.Inc("config_cache_invalidation_total", map[string]string{
			"level": string(level),
		})
	}

	c.emitInvalidateEvent(namespace, level, start)
	return nil
}

func (c *MultiLevelCache) deleteFromCaches(namespace string, level CacheLevel) {
	switch level {
	case CacheLevelL1:
		if c.l1 != nil {
			c.l1.Delete(namespace)
		}
	case CacheLevelL2:
		if c.l2 != nil {
			c.l2.Delete(namespace)
		}
	case CacheLevelAll:
		if c.l1 != nil {
			c.l1.Delete(namespace)
		}
		if c.l2 != nil {
			c.l2.Delete(namespace)
		}
	}
}

func (c *MultiLevelCache) Clear(ctx context.Context, level CacheLevel) error {
	start := time.Now()

	c.clearCaches(level)
	c.emitClearEvent(level, start)
	return nil
}

func (c *MultiLevelCache) clearCaches(level CacheLevel) {
	switch level {
	case CacheLevelL1:
		if c.l1 != nil {
			c.l1.Clear()
		}
	case CacheLevelL2:
		if c.l2 != nil {
			c.l2.Clear()
		}
	case CacheLevelAll:
		if c.l1 != nil {
			c.l1.Clear()
		}
		if c.l2 != nil {
			c.l2.Clear()
		}
	}
}

func (c *MultiLevelCache) PreWarm(ctx context.Context, configs []*models.Config) (int, error) {
	successCount := 0
	for _, cfg := range configs {
		if err := c.Set(ctx, cfg); err == nil {
			successCount++
		}
	}

	if c.config.EnableMetrics {
		metrics.IncBy("config_cache_prewarm_total", int64(successCount), nil)
	}

	return successCount, nil
}

func (c *MultiLevelCache) recordHit(level CacheLevel) {
	c.hitCount.Add(1)

	if c.config.EnableMetrics {
		metrics.Inc("config_cache_hits_total", map[string]string{
			"level": string(level),
		})
	}
}

func (c *MultiLevelCache) recordMiss() {
	c.missCount.Add(1)

	if c.config.EnableMetrics {
		metrics.Inc("config_cache_misses_total", nil)
	}
}

func (c *MultiLevelCache) emitGetEvent(namespace string, cfg *models.Config, level CacheLevel, hit bool, start time.Time, err error) {
	if c.config.EnableMetrics {
		metrics.Observe("config_cache_get_duration_seconds", time.Since(start).Seconds(), nil)
	}

	event := CacheEventData{
		Event:      CacheEventGet,
		Key:        namespace,
		Hit:        hit,
		CacheName:  defaultCacheName,
		CacheLevel: level,
		Timestamp:  time.Now(),
		Duration:   time.Since(start),
		Success:    err == nil,
	}
	if cfg != nil {
		event.Value = cfg
	}
	if err != nil {
		event.Error = err.Error()
	}

	GetCacheEventBus().Emit(event)
}

func (c *MultiLevelCache) emitSetEvent(cfg *models.Config, start time.Time, err error) {
	event := CacheEventData{
		Event:      CacheEventSet,
		Key:        cfg.Namespace,
		Value:      cfg,
		CacheName:  defaultCacheName,
		Timestamp:  time.Now(),
		Duration:   time.Since(start),
		Success:    err == nil,
	}
	if err != nil {
		event.Error = err.Error()
	}

	GetCacheEventBus().Emit(event)
}

func (c *MultiLevelCache) emitInvalidateEvent(namespace string, level CacheLevel, start time.Time) {
	GetCacheEventBus().Emit(CacheEventData{
		Event:      CacheEventInvalidate,
		Key:        namespace,
		CacheName:  defaultCacheName,
		CacheLevel: level,
		Timestamp:  time.Now(),
		Duration:   time.Since(start),
		Success:    true,
	})
}

func (c *MultiLevelCache) emitClearEvent(level CacheLevel, start time.Time) {
	GetCacheEventBus().Emit(CacheEventData{
		Event:      CacheEventClear,
		Key:        "*",
		CacheName:  defaultCacheName,
		CacheLevel: level,
		Timestamp:  time.Now(),
		Duration:   time.Since(start),
		Success:    true,
	})
}

func (c *MultiLevelCache) GetStats() map[string]interface{} {
	hits := c.hitCount.Load()
	misses := c.missCount.Load()
	total := hits + misses

	stats := map[string]interface{}{
		"enabled":      true,
		"strategy":     c.config.InvalidationStrategy,
		"total_hits":   hits,
		"total_misses": misses,
		"hit_rate":     float64(0),
	}

	if total > 0 {
		stats["hit_rate"] = float64(hits) / float64(total)
	}

	if c.l1 != nil {
		stats["l1"] = c.l1.GetStats()
	} else {
		stats["l1"] = map[string]interface{}{"enabled": false}
	}

	if c.l2 != nil {
		stats["l2"] = c.l2.GetStats()
	} else {
		stats["l2"] = map[string]interface{}{"enabled": false}
	}

	return stats
}

type CacheManager struct {
	cache     *MultiLevelCache
	configMgr *Manager
	config    CacheConfig
	stopChan  chan struct{}
	wg        sync.WaitGroup
}

var (
	cacheInstance *CacheManager
	cacheOnce     sync.Once
)

func NewCacheManager(cfg CacheConfig, configMgr *Manager) *CacheManager {
	cm := &CacheManager{
		cache:     NewMultiLevelCache(cfg),
		configMgr: configMgr,
		config:    cfg,
		stopChan:  make(chan struct{}),
	}

	if cfg.InvalidationStrategy == InvalidateTTL {
		go cm.startTTLCleanup()
	}

	return cm
}

func GetCacheManager() *CacheManager {
	cacheOnce.Do(func() {
		cacheInstance = NewCacheManager(DefaultCacheConfig(), GetManager())
	})
	return cacheInstance
}

func (cm *CacheManager) GetConfig(ctx context.Context, namespace string) (*models.Config, CacheLevel, error) {
	if cm.cache == nil {
		cfg, err := cm.configMgr.GetConfig(namespace)
		return cfg, "", err
	}

	cfg, level, err := cm.cache.Get(ctx, namespace)
	if err != nil {
		return nil, "", err
	}

	if cfg != nil {
		return cfg, level, nil
	}

	cfg, err = cm.configMgr.GetConfig(namespace)
	if err != nil {
		return nil, "", err
	}

	if setErr := cm.cache.Set(ctx, cfg); setErr != nil {
		logger.Warn("", "cache set failed", map[string]interface{}{
			"namespace": namespace,
			"error":     setErr.Error(),
		})
	}

	return cfg, "", nil
}

func (cm *CacheManager) CreateConfig(ctx context.Context, namespace string, params map[string]interface{}) (*models.Config, error) {
	cfg, err := cm.configMgr.CreateConfig(namespace, params)
	if err != nil {
		return nil, err
	}

	if cm.config.InvalidationStrategy == InvalidateOnWrite {
		cm.cache.Invalidate(ctx, namespace, CacheLevelAll)
		cm.cache.Set(ctx, cfg)
	}

	return cfg, nil
}

func (cm *CacheManager) UpdateConfig(ctx context.Context, namespace string, params map[string]interface{}) (*models.Config, error) {
	cfg, err := cm.configMgr.UpdateConfig(namespace, params)
	if err != nil {
		return nil, err
	}

	if cm.config.InvalidationStrategy == InvalidateOnWrite {
		cm.cache.Invalidate(ctx, namespace, CacheLevelAll)
		cm.cache.Set(ctx, cfg)
	}

	return cfg, nil
}

func (cm *CacheManager) DeleteConfig(ctx context.Context, namespace string) error {
	err := cm.configMgr.DeleteConfig(namespace)
	if err != nil {
		return err
	}

	cm.cache.Invalidate(ctx, namespace, CacheLevelAll)
	return nil
}

func (cm *CacheManager) RollbackConfig(ctx context.Context, namespace string, targetVersion int) (*models.Config, error) {
	cfg, err := cm.configMgr.Rollback(namespace, targetVersion)
	if err != nil {
		return nil, err
	}

	if cm.config.InvalidationStrategy == InvalidateOnWrite {
		cm.cache.Invalidate(ctx, namespace, CacheLevelAll)
		cm.cache.Set(ctx, cfg)
	}

	return cfg, nil
}

func (cm *CacheManager) PreWarmCache(ctx context.Context) (int, error) {
	namespaces := cm.configMgr.ListNamespaces()
	configs := make([]*models.Config, 0, len(namespaces))

	for _, ns := range namespaces {
		cfg, err := cm.configMgr.GetConfig(ns)
		if err == nil {
			configs = append(configs, cfg)
		}
	}

	return cm.cache.PreWarm(ctx, configs)
}

func (cm *CacheManager) Invalidate(ctx context.Context, namespace string, level CacheLevel) error {
	return cm.cache.Invalidate(ctx, namespace, level)
}

func (cm *CacheManager) InvalidateAll(ctx context.Context, level CacheLevel) error {
	return cm.cache.Clear(ctx, level)
}

func (cm *CacheManager) GetStats() map[string]interface{} {
	return cm.cache.GetStats()
}

func (cm *CacheManager) startTTLCleanup() {
	ticker := time.NewTicker(ttlCleanupInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			cm.cleanupExpired()
		case <-cm.stopChan:
			return
		}
	}
}

func (cm *CacheManager) cleanupExpired() {
	if cm.config.EnableMetrics {
		metrics.Inc("config_cache_ttl_cleanup_total", nil)
	}
}

func (cm *CacheManager) Shutdown() {
	close(cm.stopChan)
	cm.wg.Wait()
}

func GenerateCacheKey(namespace string, version int) string {
	h := sha256.New()
	h.Write([]byte(fmt.Sprintf("%s:%d", namespace, version)))
	return hex.EncodeToString(h.Sum(nil))
}
