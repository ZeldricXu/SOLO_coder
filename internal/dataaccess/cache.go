package dataaccess

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"session154/internal/logger"
	"sync"
	"time"

	"github.com/go-redis/redis/v8"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type CacheStrategy string

const (
	CacheStrategyWriteThrough CacheStrategy = "write_through"
	CacheStrategyWriteBehind  CacheStrategy = "write_behind"
	CacheStrategyCacheAside   CacheStrategy = "cache_aside"
)

type EvictionPolicy string

const (
	EvictionLRU  EvictionPolicy = "lru"
	EvictionLFU  EvictionPolicy = "lfu"
	EvictionFIFO EvictionPolicy = "fifo"
	EvictionTTL  EvictionPolicy = "ttl"
)

type CacheConfig struct {
	DefaultTTL      time.Duration
	MaxSize         int
	Strategy        CacheStrategy
	EvictionPolicy  EvictionPolicy
	EnableMetrics   bool
	RedisConfig     *redis.Options
}

type CacheEntry struct {
	Key        string
	Value      interface{}
	ExpiresAt  time.Time
	CreatedAt  time.Time
	AccessedAt time.Time
	AccessCount int
	Size       int
}

type Cache interface {
	Get(ctx context.Context, key string) (interface{}, error)
	Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error
	Delete(ctx context.Context, key string) error
	Exists(ctx context.Context, key string) bool
	Clear(ctx context.Context) error
	Close() error
}

type InMemoryCache struct {
	entries    map[string]*CacheEntry
	config     CacheConfig
	mu         sync.RWMutex
	lruList    []string
	lfuCounts  map[string]int
	fifoQueue  []string
	metrics    *CacheMetrics
}

func NewInMemoryCache(config CacheConfig) *InMemoryCache {
	if config.DefaultTTL == 0 {
		config.DefaultTTL = 5 * time.Minute
	}
	if config.MaxSize == 0 {
		config.MaxSize = 10000
	}

	cache := &InMemoryCache{
		entries:   make(map[string]*CacheEntry),
		config:    config,
		lfuCounts: make(map[string]int),
		metrics:   &CacheMetrics{},
	}

	if config.EnableMetrics {
		go cache.startCleanupWorker()
	}

	return cache
}

func (c *InMemoryCache) Get(ctx context.Context, key string) (interface{}, error) {
	c.mu.RLock()
	entry, ok := c.entries[key]
	c.mu.RUnlock()

	if !ok {
		c.metrics.IncrMiss()
		return nil, errors.New("cache miss")
	}

	if !entry.ExpiresAt.IsZero() && time.Now().After(entry.ExpiresAt) {
		c.mu.Lock()
		delete(c.entries, key)
		c.mu.Unlock()
		c.metrics.IncrMiss()
		return nil, errors.New("cache expired")
	}

	c.mu.Lock()
	entry.AccessedAt = time.Now()
	entry.AccessCount++
	c.updateLRU(key)
	c.lfuCounts[key]++
	c.mu.Unlock()

	c.metrics.IncrHit()
	return entry.Value, nil
}

func (c *InMemoryCache) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	if ttl == 0 {
		ttl = c.config.DefaultTTL
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	if len(c.entries) >= c.config.MaxSize {
		c.evict()
	}

	size := estimateSize(value)
	entry := &CacheEntry{
		Key:        key,
		Value:      value,
		CreatedAt:  time.Now(),
		AccessedAt: time.Now(),
		AccessCount: 1,
		Size:       size,
	}

	if ttl > 0 {
		entry.ExpiresAt = time.Now().Add(ttl)
	}

	c.entries[key] = entry
	c.lruList = append(c.lruList, key)
	c.lfuCounts[key] = 1
	c.fifoQueue = append(c.fifoQueue, key)

	c.metrics.IncrSet()
	return nil
}

func (c *InMemoryCache) Delete(ctx context.Context, key string) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	delete(c.entries, key)
	c.removeFromLRU(key)
	delete(c.lfuCounts, key)
	c.removeFromFIFO(key)

	c.metrics.IncrDelete()
	return nil
}

func (c *InMemoryCache) Exists(ctx context.Context, key string) bool {
	c.mu.RLock()
	defer c.mu.RUnlock()

	entry, ok := c.entries[key]
	if !ok {
		return false
	}

	if !entry.ExpiresAt.IsZero() && time.Now().After(entry.ExpiresAt) {
		return false
	}

	return true
}

func (c *InMemoryCache) Clear(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.entries = make(map[string]*CacheEntry)
	c.lruList = nil
	c.lfuCounts = make(map[string]int)
	c.fifoQueue = nil

	return nil
}

func (c *InMemoryCache) Close() error {
	return c.Clear(context.Background())
}

func (c *InMemoryCache) evict() {
	switch c.config.EvictionPolicy {
	case EvictionLRU:
		c.evictLRU()
	case EvictionLFU:
		c.evictLFU()
	case EvictionFIFO:
		c.evictFIFO()
	case EvictionTTL:
		c.evictTTL()
	default:
		c.evictLRU()
	}
}

func (c *InMemoryCache) evictLRU() {
	if len(c.lruList) > 0 {
		key := c.lruList[0]
		delete(c.entries, key)
		c.lruList = c.lruList[1:]
		delete(c.lfuCounts, key)
		c.removeFromFIFO(key)
	}
}

func (c *InMemoryCache) evictLFU() {
	if len(c.lfuCounts) == 0 {
		return
	}

	minCount := int(^uint(0) >> 1)
	minKey := ""
	for key, count := range c.lfuCounts {
		if count < minCount {
			minCount = count
			minKey = key
		}
	}

	if minKey != "" {
		delete(c.entries, minKey)
		delete(c.lfuCounts, minKey)
		c.removeFromLRU(minKey)
		c.removeFromFIFO(minKey)
	}
}

func (c *InMemoryCache) evictFIFO() {
	if len(c.fifoQueue) > 0 {
		key := c.fifoQueue[0]
		delete(c.entries, key)
		c.fifoQueue = c.fifoQueue[1:]
		delete(c.lfuCounts, key)
		c.removeFromLRU(key)
	}
}

func (c *InMemoryCache) evictTTL() {
	now := time.Now()
	for key, entry := range c.entries {
		if !entry.ExpiresAt.IsZero() && now.After(entry.ExpiresAt) {
			delete(c.entries, key)
			delete(c.lfuCounts, key)
			c.removeFromLRU(key)
			c.removeFromFIFO(key)
		}
	}
}

func (c *InMemoryCache) updateLRU(key string) {
	for i, k := range c.lruList {
		if k == key {
			c.lruList = append(c.lruList[:i], c.lruList[i+1:]...)
			break
		}
	}
	c.lruList = append(c.lruList, key)
}

func (c *InMemoryCache) removeFromLRU(key string) {
	for i, k := range c.lruList {
		if k == key {
			c.lruList = append(c.lruList[:i], c.lruList[i+1:]...)
			return
		}
	}
}

func (c *InMemoryCache) removeFromFIFO(key string) {
	for i, k := range c.fifoQueue {
		if k == key {
			c.fifoQueue = append(c.fifoQueue[:i], c.fifoQueue[i+1:]...)
			return
		}
	}
}

func (c *InMemoryCache) startCleanupWorker() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		c.mu.Lock()
		c.evictTTL()
		c.mu.Unlock()
	}
}

func (c *InMemoryCache) GetMetrics() CacheMetrics {
	return *c.metrics
}

type RedisCache struct {
	client *redis.Client
	config CacheConfig
}

func NewRedisCache(config CacheConfig) (*RedisCache, error) {
	if config.RedisConfig == nil {
		return nil, errors.New("redis config is required")
	}

	client := redis.NewClient(config.RedisConfig)
	if err := client.Ping(context.Background()).Err(); err != nil {
		return nil, err
	}

	return &RedisCache{
		client: client,
		config: config,
	}, nil
}

func (c *RedisCache) Get(ctx context.Context, key string) (interface{}, error) {
	data, err := c.client.Get(ctx, key).Bytes()
	if err == redis.Nil {
		return nil, errors.New("cache miss")
	}
	if err != nil {
		return nil, err
	}

	var value interface{}
	if err := json.Unmarshal(data, &value); err != nil {
		return data, nil
	}

	return value, nil
}

func (c *RedisCache) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	if ttl == 0 {
		ttl = c.config.DefaultTTL
	}

	data, err := json.Marshal(value)
	if err != nil {
		return err
	}

	return c.client.Set(ctx, key, data, ttl).Err()
}

func (c *RedisCache) Delete(ctx context.Context, key string) error {
	return c.client.Del(ctx, key).Err()
}

func (c *RedisCache) Exists(ctx context.Context, key string) bool {
	result, err := c.client.Exists(ctx, key).Result()
	return err == nil && result > 0
}

func (c *RedisCache) Clear(ctx context.Context) error {
	return c.client.FlushDB(ctx).Err()
}

func (c *RedisCache) Close() error {
	return c.client.Close()
}

type CacheMetrics struct {
	Hits      int64 `json:"hits"`
	Misses    int64 `json:"misses"`
	Sets      int64 `json:"sets"`
	Deletes   int64 `json:"deletes"`
	Evictions int64 `json:"evictions"`
	mu        sync.Mutex
}

func (m *CacheMetrics) IncrHit()       { m.mu.Lock(); defer m.mu.Unlock(); m.Hits++ }
func (m *CacheMetrics) IncrMiss()      { m.mu.Lock(); defer m.mu.Unlock(); m.Misses++ }
func (m *CacheMetrics) IncrSet()       { m.mu.Lock(); defer m.mu.Unlock(); m.Sets++ }
func (m *CacheMetrics) IncrDelete()    { m.mu.Lock(); defer m.mu.Unlock(); m.Deletes++ }
func (m *CacheMetrics) IncrEviction()  { m.mu.Lock(); defer m.mu.Unlock(); m.Evictions++ }

func (m *CacheMetrics) HitRate() float64 {
	m.mu.Lock()
	defer m.mu.Unlock()
	total := m.Hits + m.Misses
	if total == 0 {
		return 0
	}
	return float64(m.Hits) / float64(total)
}

type CacheInvalidationListener interface {
	OnInvalidate(ctx context.Context, keys []string) error
}

type CacheManager struct {
	cache          Cache
	config         CacheConfig
	invalidationCh chan []string
	listeners      []CacheInvalidationListener
	invalidations  map[string]time.Time
	mu             sync.RWMutex
}

func NewCacheManager(cache Cache, config CacheConfig) *CacheManager {
	cm := &CacheManager{
		cache:          cache,
		config:         config,
		invalidationCh: make(chan []string, 1000),
		invalidations:  make(map[string]time.Time),
	}

	go cm.processInvalidations()
	return cm
}

func (cm *CacheManager) Get(ctx context.Context, key string) (interface{}, error) {
	return cm.cache.Get(ctx, key)
}

func (cm *CacheManager) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	return cm.cache.Set(ctx, key, value, ttl)
}

func (cm *CacheManager) Delete(ctx context.Context, key string) error {
	return cm.cache.Delete(ctx, key)
}

func (cm *CacheManager) Invalidate(ctx context.Context, keys ...string) error {
	cm.mu.Lock()
	now := time.Now()
	for _, key := range keys {
		cm.invalidations[key] = now
	}
	cm.mu.Unlock()

	select {
	case cm.invalidationCh <- keys:
	default:
	}

	for _, listener := range cm.listeners {
		if err := listener.OnInvalidate(ctx, keys); err != nil {
			logger.Error("invalidation listener error", zap.Error(err))
		}
	}

	return nil
}

func (cm *CacheManager) processInvalidations() {
	for keys := range cm.invalidationCh {
		ctx := context.Background()
		for _, key := range keys {
			if err := cm.cache.Delete(ctx, key); err != nil {
				logger.Warn("failed to invalidate cache key", zap.String("key", key), zap.Error(err))
			}
		}
	}
}

func (cm *CacheManager) AddListener(listener CacheInvalidationListener) {
	cm.mu.Lock()
	defer cm.mu.Unlock()
	cm.listeners = append(cm.listeners, listener)
}

func (cm *CacheManager) BatchGet(ctx context.Context, keys []string) (map[string]interface{}, error) {
	results := make(map[string]interface{})
	for _, key := range keys {
		if value, err := cm.cache.Get(ctx, key); err == nil {
			results[key] = value
		}
	}
	return results, nil
}

func (cm *CacheManager) BatchSet(ctx context.Context, items map[string]interface{}, ttl time.Duration) error {
	for key, value := range items {
		if err := cm.cache.Set(ctx, key, value, ttl); err != nil {
			return err
		}
	}
	return nil
}

type CachedRepository struct {
	db      *gorm.DB
	cache   *CacheManager
	config  CacheConfig
}

func NewCachedRepository(db *gorm.DB, cache *CacheManager, config CacheConfig) *CachedRepository {
	return &CachedRepository{
		db:     db,
		cache:  cache,
		config: config,
	}
}

func (r *CachedRepository) FindByID(ctx context.Context, id string, result interface{}) error {
	cacheKey := fmt.Sprintf("entity:%s", id)

	if r.config.Strategy == CacheStrategyCacheAside || r.config.Strategy == CacheStrategyWriteThrough {
		if cached, err := r.cache.Get(ctx, cacheKey); err == nil {
			return mapToStruct(cached, result)
		}
	}

	if err := r.db.WithContext(ctx).First(result, "id = ?", id).Error; err != nil {
		return err
	}

	if r.config.Strategy == CacheStrategyCacheAside || r.config.Strategy == CacheStrategyWriteThrough {
		r.cache.Set(ctx, cacheKey, result, r.config.DefaultTTL)
	}

	return nil
}

func (r *CachedRepository) Create(ctx context.Context, entity interface{}) error {
	if err := r.db.WithContext(ctx).Create(entity).Error; err != nil {
		return err
	}

	if r.config.Strategy == CacheStrategyWriteThrough {
		id := getEntityID(entity)
		cacheKey := fmt.Sprintf("entity:%s", id)
		r.cache.Set(ctx, cacheKey, entity, r.config.DefaultTTL)
	}

	return nil
}

func (r *CachedRepository) Update(ctx context.Context, entity interface{}) error {
	if err := r.db.WithContext(ctx).Save(entity).Error; err != nil {
		return err
	}

	id := getEntityID(entity)
	cacheKey := fmt.Sprintf("entity:%s", id)

	switch r.config.Strategy {
	case CacheStrategyWriteThrough:
		r.cache.Set(ctx, cacheKey, entity, r.config.DefaultTTL)
	case CacheStrategyCacheAside, CacheStrategyWriteBehind:
		r.cache.Invalidate(ctx, cacheKey)
	}

	return nil
}

func (r *CachedRepository) Delete(ctx context.Context, id string, entity interface{}) error {
	if err := r.db.WithContext(ctx).Delete(entity, "id = ?", id).Error; err != nil {
		return err
	}

	cacheKey := fmt.Sprintf("entity:%s", id)
	r.cache.Invalidate(ctx, cacheKey)

	return nil
}

func (r *CachedRepository) Query(ctx context.Context, query string, args []interface{}, result interface{}, cacheTTL time.Duration) error {
	cacheKey := generateQueryCacheKey(query, args)

	if cached, err := r.cache.Get(ctx, cacheKey); err == nil {
		return mapToStruct(cached, result)
	}

	if err := r.db.WithContext(ctx).Raw(query, args...).Scan(result).Error; err != nil {
		return err
	}

	r.cache.Set(ctx, cacheKey, result, cacheTTL)
	return nil
}

func mapToStruct(from interface{}, to interface{}) error {
	data, err := json.Marshal(from)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, to)
}

func getEntityID(entity interface{}) string {
	type entityWithID struct {
		ID string `json:"id"`
	}
	data, _ := json.Marshal(entity)
	var e entityWithID
	json.Unmarshal(data, &e)
	return e.ID
}

func generateQueryCacheKey(query string, args []interface{}) string {
	h := sha256.New()
	h.Write([]byte(query))
	for _, arg := range args {
		h.Write([]byte(fmt.Sprintf("%v", arg)))
	}
	return "query:" + hex.EncodeToString(h.Sum(nil))
}

func estimateSize(value interface{}) int {
	switch v := value.(type) {
	case string:
		return len(v)
	case []byte:
		return len(v)
	default:
		data, _ := json.Marshal(value)
		return len(data)
	}
}

type MultiLevelCache struct {
	levels []Cache
}

func NewMultiLevelCache(levels ...Cache) *MultiLevelCache {
	return &MultiLevelCache{levels: levels}
}

func (mc *MultiLevelCache) Get(ctx context.Context, key string) (interface{}, error) {
	for i, cache := range mc.levels {
		if value, err := cache.Get(ctx, key); err == nil {
			for j := 0; j < i; j++ {
				mc.levels[j].Set(ctx, key, value, 0)
			}
			return value, nil
		}
	}
	return nil, errors.New("cache miss")
}

func (mc *MultiLevelCache) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	for _, cache := range mc.levels {
		if err := cache.Set(ctx, key, value, ttl); err != nil {
			logger.Warn("failed to set cache level", zap.Error(err))
		}
	}
	return nil
}

func (mc *MultiLevelCache) Delete(ctx context.Context, key string) error {
	for _, cache := range mc.levels {
		cache.Delete(ctx, key)
	}
	return nil
}

func (mc *MultiLevelCache) Exists(ctx context.Context, key string) bool {
	for _, cache := range mc.levels {
		if cache.Exists(ctx, key) {
			return true
		}
	}
	return false
}

func (mc *MultiLevelCache) Clear(ctx context.Context) error {
	for _, cache := range mc.levels {
		cache.Clear(ctx)
	}
	return nil
}

func (mc *MultiLevelCache) Close() error {
	for _, cache := range mc.levels {
		cache.Close()
	}
	return nil
}
