package dao

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"techplatform/pkg/common"
	"techplatform/pkg/common/logger"
	"techplatform/pkg/common/utils"

	"github.com/go-redis/redis/v8"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

type CacheStrategy string

const (
	CacheStrategyWriteThrough CacheStrategy = "write_through"
	CacheStrategyWriteBack    CacheStrategy = "write_back"
	CacheStrategyCacheAside   CacheStrategy = "cache_aside"
)

type Cache interface {
	Get(ctx context.Context, key string) (string, error)
	Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error
	Delete(ctx context.Context, key string) error
	Exists(ctx context.Context, key string) (bool, error)
	Expire(ctx context.Context, key string, ttl time.Duration) error
	Incr(ctx context.Context, key string) (int64, error)
	Decr(ctx context.Context, key string) (int64, error)
	HGet(ctx context.Context, key, field string) (string, error)
	HSet(ctx context.Context, key, field string, value interface{}) error
	HGetAll(ctx context.Context, key string) (map[string]string, error)
	LPush(ctx context.Context, key string, value interface{}) error
	LPop(ctx context.Context, key string) (string, error)
	ZAdd(ctx context.Context, key string, score float64, value string) error
	ZRange(ctx context.Context, key string, start, stop int64) ([]string, error)
	ZRevRange(ctx context.Context, key string, start, stop int64) ([]string, error)
}

type InMemoryCache struct {
	mu    sync.RWMutex
	data  map[string]cacheItem
	maxSize int
}

type cacheItem struct {
	value      string
	expireAt   time.Time
}

func NewInMemoryCache(maxSize int) *InMemoryCache {
	c := &InMemoryCache{
		data:    make(map[string]cacheItem),
		maxSize: maxSize,
	}
	go c.startCleanup()
	return c
}

func (c *InMemoryCache) startCleanup() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		c.cleanupExpired()
	}
}

func (c *InMemoryCache) cleanupExpired() {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	for k, v := range c.data {
		if !v.expireAt.IsZero() && v.expireAt.Before(now) {
			delete(c.data, k)
		}
	}
}

func (c *InMemoryCache) evictIfNeeded() {
	if len(c.data) >= c.maxSize {
		var oldestKey string
		var oldestTime time.Time
		for k, v := range c.data {
			if oldestTime.IsZero() || v.expireAt.Before(oldestTime) {
				oldestKey = k
				oldestTime = v.expireAt
			}
		}
		if oldestKey != "" {
			delete(c.data, oldestKey)
		}
	}
}

func (c *InMemoryCache) Get(ctx context.Context, key string) (string, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	item, ok := c.data[key]
	if !ok {
		return "", common.ErrCacheMiss
	}
	if !item.expireAt.IsZero() && item.expireAt.Before(time.Now()) {
		return "", common.ErrCacheMiss
	}
	return item.value, nil
}

func (c *InMemoryCache) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.evictIfNeeded()
	var valStr string
	switch v := value.(type) {
	case string:
		valStr = v
	case []byte:
		valStr = string(v)
	default:
		b, err := json.Marshal(value)
		if err != nil {
			return err
		}
		valStr = string(b)
	}
	item := cacheItem{
		value:    valStr,
		expireAt: time.Now().Add(ttl),
	}
	if ttl <= 0 {
		item.expireAt = time.Time{}
	}
	c.data[key] = item
	return nil
}

func (c *InMemoryCache) Delete(ctx context.Context, key string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.data, key)
	return nil
}

func (c *InMemoryCache) Exists(ctx context.Context, key string) (bool, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	_, ok := c.data[key]
	return ok, nil
}

func (c *InMemoryCache) Expire(ctx context.Context, key string, ttl time.Duration) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if item, ok := c.data[key]; ok {
		item.expireAt = time.Now().Add(ttl)
		c.data[key] = item
	}
	return nil
}

func (c *InMemoryCache) Incr(ctx context.Context, key string) (int64, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	item, ok := c.data[key]
	if !ok {
		c.data[key] = cacheItem{value: "1", expireAt: time.Time{}}
		return 1, nil
	}
	var n int64
	fmt.Sscanf(item.value, "%d", &n)
	n++
	item.value = fmt.Sprintf("%d", n)
	c.data[key] = item
	return n, nil
}

func (c *InMemoryCache) Decr(ctx context.Context, key string) (int64, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	item, ok := c.data[key]
	if !ok {
		c.data[key] = cacheItem{value: "0", expireAt: time.Time{}}
		return 0, nil
	}
	var n int64
	fmt.Sscanf(item.value, "%d", &n)
	n--
	item.value = fmt.Sprintf("%d", n)
	c.data[key] = item
	return n, nil
}

func (c *InMemoryCache) HGet(ctx context.Context, key, field string) (string, error) {
	data, err := c.HGetAll(ctx, key)
	if err != nil {
		return "", err
	}
	v, ok := data[field]
	if !ok {
		return "", common.ErrCacheMiss
	}
	return v, nil
}

func (c *InMemoryCache) HSet(ctx context.Context, key, field string, value interface{}) error {
	data, err := c.HGetAll(ctx, key)
	if err != nil && err != common.ErrCacheMiss {
		return err
	}
	if data == nil {
		data = make(map[string]string)
	}
	var valStr string
	switch v := value.(type) {
	case string:
		valStr = v
	default:
		b, _ := json.Marshal(value)
		valStr = string(b)
	}
	data[field] = valStr
	b, _ := json.Marshal(data)
	return c.Set(ctx, key, string(b), 0)
}

func (c *InMemoryCache) HGetAll(ctx context.Context, key string) (map[string]string, error) {
	val, err := c.Get(ctx, key)
	if err != nil {
		return nil, err
	}
	var data map[string]string
	if err := json.Unmarshal([]byte(val), &data); err != nil {
		return nil, err
	}
	return data, nil
}

func (c *InMemoryCache) LPush(ctx context.Context, key string, value interface{}) error {
	return c.Set(ctx, key, value, 0)
}

func (c *InMemoryCache) LPop(ctx context.Context, key string) (string, error) {
	v, err := c.Get(ctx, key)
	if err != nil {
		return "", err
	}
	c.Delete(ctx, key)
	return v, nil
}

func (c *InMemoryCache) ZAdd(ctx context.Context, key string, score float64, value string) error {
	return c.Set(ctx, key, value, 0)
}

func (c *InMemoryCache) ZRange(ctx context.Context, key string, start, stop int64) ([]string, error) {
	v, err := c.Get(ctx, key)
	if err != nil {
		return nil, err
	}
	return []string{v}, nil
}

func (c *InMemoryCache) ZRevRange(ctx context.Context, key string, start, stop int64) ([]string, error) {
	return c.ZRange(ctx, key, start, stop)
}

type RedisCache struct {
	client *redis.Client
	ctx    context.Context
}

func NewRedisCache(addr, password string, db int) *RedisCache {
	client := redis.NewClient(&redis.Options{
		Addr:     addr,
		Password: password,
		DB:       db,
	})
	return &RedisCache{
		client: client,
		ctx:    context.Background(),
	}
}

func (r *RedisCache) Get(ctx context.Context, key string) (string, error) {
	val, err := r.client.Get(ctx, key).Result()
	if err == redis.Nil {
		return "", common.ErrCacheMiss
	}
	return val, err
}

func (r *RedisCache) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	return r.client.Set(ctx, key, value, ttl).Err()
}

func (r *RedisCache) Delete(ctx context.Context, key string) error {
	return r.client.Del(ctx, key).Err()
}

func (r *RedisCache) Exists(ctx context.Context, key string) (bool, error) {
	n, err := r.client.Exists(ctx, key).Result()
	return n > 0, err
}

func (r *RedisCache) Expire(ctx context.Context, key string, ttl time.Duration) error {
	return r.client.Expire(ctx, key, ttl).Err()
}

func (r *RedisCache) Incr(ctx context.Context, key string) (int64, error) {
	return r.client.Incr(ctx, key).Result()
}

func (r *RedisCache) Decr(ctx context.Context, key string) (int64, error) {
	return r.client.Decr(ctx, key).Result()
}

func (r *RedisCache) HGet(ctx context.Context, key, field string) (string, error) {
	return r.client.HGet(ctx, key, field).Result()
}

func (r *RedisCache) HSet(ctx context.Context, key, field string, value interface{}) error {
	return r.client.HSet(ctx, key, field, value).Err()
}

func (r *RedisCache) HGetAll(ctx context.Context, key string) (map[string]string, error) {
	return r.client.HGetAll(ctx, key).Result()
}

func (r *RedisCache) LPush(ctx context.Context, key string, value interface{}) error {
	return r.client.LPush(ctx, key, value).Err()
}

func (r *RedisCache) LPop(ctx context.Context, key string) (string, error) {
	return r.client.LPop(ctx, key).Result()
}

func (r *RedisCache) ZAdd(ctx context.Context, key string, score float64, value string) error {
	return r.client.ZAdd(ctx, key, &redis.Z{Score: score, Member: value}).Err()
}

func (r *RedisCache) ZRange(ctx context.Context, key string, start, stop int64) ([]string, error) {
	return r.client.ZRange(ctx, key, start, stop).Result()
}

func (r *RedisCache) ZRevRange(ctx context.Context, key string, start, stop int64) ([]string, error) {
	return r.client.ZRevRange(ctx, key, start, stop).Result()
}

type DAO struct {
	db             *gorm.DB
	cache          Cache
	cacheStrategy  CacheStrategy
	cacheTTL       time.Duration
}

type DAOConfig struct {
	DBPath         string
	CacheType      string
	RedisAddr      string
	RedisPassword  string
	RedisDB        int
	CacheStrategy  CacheStrategy
	CacheTTL       time.Duration
	MaxCacheSize   int
}

func NewDAO(cfg DAOConfig) (*DAO, error) {
	db, err := gorm.Open(sqlite.Open(cfg.DBPath), &gorm.Config{})
	if err != nil {
		return nil, fmt.Errorf("failed to connect database: %w", err)
	}

	var cache Cache
	if cfg.CacheType == "redis" {
		cache = NewRedisCache(cfg.RedisAddr, cfg.RedisPassword, cfg.RedisDB)
	} else {
		if cfg.MaxCacheSize <= 0 {
			cfg.MaxCacheSize = 10000
		}
		cache = NewInMemoryCache(cfg.MaxCacheSize)
	}

	if cfg.CacheStrategy == "" {
		cfg.CacheStrategy = CacheStrategyCacheAside
	}
	if cfg.CacheTTL <= 0 {
		cfg.CacheTTL = time.Hour
	}

	dao := &DAO{
		db:            db,
		cache:         cache,
		cacheStrategy: cfg.CacheStrategy,
		cacheTTL:      cfg.CacheTTL,
	}

	logger.Info("DAO initialized with cache strategy: %s", cfg.CacheStrategy)
	return dao, nil
}

func (d *DAO) DB() *gorm.DB {
	return d.db
}

func (d *DAO) Cache() Cache {
	return d.cache
}

func (d *DAO) GetWithCache(ctx context.Context, key string, dest interface{}, loader func() (interface{}, error)) error {
	cacheKey := utils.GenerateCacheKey(common.CacheKeyPrefix, key)

	if d.cacheStrategy == CacheStrategyCacheAside || d.cacheStrategy == CacheStrategyWriteThrough {
		val, err := d.cache.Get(ctx, cacheKey)
		if err == nil {
			if err := utils.FromJSON(val, dest); err == nil {
				logger.Debug("Cache hit for key: %s", cacheKey)
				return nil
			}
		}
		logger.Debug("Cache miss for key: %s", cacheKey)
	}

	data, err := loader()
	if err != nil {
		return err
	}

	if d.cacheStrategy == CacheStrategyCacheAside || d.cacheStrategy == CacheStrategyWriteThrough {
		if err := d.cache.Set(ctx, cacheKey, data, d.cacheTTL); err != nil {
			logger.Warn("Failed to set cache for key: %s, error: %v", cacheKey, err)
		}
	}

	b, _ := json.Marshal(data)
	return utils.FromJSON(string(b), dest)
}

func (d *DAO) InvalidateCache(ctx context.Context, keys ...string) {
	for _, key := range keys {
		cacheKey := utils.GenerateCacheKey(common.CacheKeyPrefix, key)
		if err := d.cache.Delete(ctx, cacheKey); err != nil {
			logger.Warn("Failed to invalidate cache for key: %s, error: %v", cacheKey, err)
		}
		logger.Debug("Cache invalidated for key: %s", cacheKey)
	}
}

func (d *DAO) UpdateWithCache(ctx context.Context, key string, data interface{}, saver func() error) error {
	if d.cacheStrategy == CacheStrategyWriteThrough {
		cacheKey := utils.GenerateCacheKey(common.CacheKeyPrefix, key)
		if err := d.cache.Set(ctx, cacheKey, data, d.cacheTTL); err != nil {
			logger.Warn("Write-through cache update failed: %v", err)
		}
	}

	if err := saver(); err != nil {
		return err
	}

	if d.cacheStrategy == CacheStrategyCacheAside {
		d.InvalidateCache(ctx, key)
	}

	return nil
}

func (d *DAO) AutoMigrate(models ...interface{}) error {
	return d.db.AutoMigrate(models...)
}

func (d *DAO) Close() error {
	sqlDB, err := d.db.DB()
	if err != nil {
		return err
	}
	return sqlDB.Close()
}

type CacheStats struct {
	HitCount   int64 `json:"hit_count"`
	MissCount  int64 `json:"miss_count"`
	EvictCount int64 `json:"evict_count"`
	TotalKeys  int   `json:"total_keys"`
}

func (d *DAO) GetStats(ctx context.Context) *CacheStats {
	hitKey := utils.GenerateCacheKey(common.CacheKeyPrefix, "stats:hit")
	missKey := utils.GenerateCacheKey(common.CacheKeyPrefix, "stats:miss")
	evictKey := utils.GenerateCacheKey(common.CacheKeyPrefix, "stats:evict")

	hitCount, _ := d.cache.Incr(ctx, hitKey)
	hitCount--
	missCount, _ := d.cache.Incr(ctx, missKey)
	missCount--
	evictCount, _ := d.cache.Incr(ctx, evictKey)
	evictCount--

	return &CacheStats{
		HitCount:   hitCount,
		MissCount:  missCount,
		EvictCount: evictCount,
	}
}

func (d *DAO) BatchInvalidate(ctx context.Context, pattern string) error {
	logger.Info("Batch invalidating cache with pattern: %s", pattern)
	return nil
}

func (d *DAO) WarmUpCache(ctx context.Context, keys []string, loader func(key string) (interface{}, error)) error {
	logger.Info("Warming up cache with %d keys", len(keys))
	for _, key := range keys {
		data, err := loader(key)
		if err != nil {
			logger.Warn("Failed to warm up cache for key: %s, error: %v", key, err)
			continue
		}
		cacheKey := utils.GenerateCacheKey(common.CacheKeyPrefix, key)
		if err := d.cache.Set(ctx, cacheKey, data, d.cacheTTL); err != nil {
			logger.Warn("Failed to set cache during warmup: %v", err)
		}
	}
	logger.Info("Cache warmup completed")
	return nil
}
