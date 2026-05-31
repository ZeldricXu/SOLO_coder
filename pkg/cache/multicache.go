package cache

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"go.uber.org/zap"
	"sync"
	"time"

	"github.com/go-redis/redis/v8"
)

type CacheLevel string

const (
	LevelMemory CacheLevel = "memory"
	LevelRedis  CacheLevel = "redis"
	LevelDB     CacheLevel = "db"
)

type CacheEntry struct {
	Value      interface{}
	ExpiresAt  time.Time
	Version    int
	HitCount   int
	LastHit    time.Time
}

type CacheLoaderFunc func(ctx context.Context, key string) (interface{}, error)

type MultiLevelCache struct {
	memoryCache    map[string]*CacheEntry
	redisClient    *redis.Client
	redisPrefix    string
	memoryTTL      time.Duration
	redisTTL       time.Duration
	logger         *zap.Logger
	mu             sync.RWMutex
	loader         CacheLoaderFunc
	enablePenetrationProtection bool
	nullValueTTL   time.Duration
	stats          *CacheStats
	singleFlight   *SingleFlight
}

type CacheStats struct {
	Hits          map[CacheLevel]uint64
	Misses        uint64
	Evictions     uint64
	LoadErrors    uint64
	PenetrationBlocks uint64
	mu            sync.RWMutex
}

type SingleFlight struct {
	mu     sync.Mutex
	flight map[string]*flightCall
}

type flightCall struct {
	wg  sync.WaitGroup
	val interface{}
	err error
}

type CacheOption func(*MultiLevelCache)

func WithMemoryTTL(ttl time.Duration) CacheOption {
	return func(c *MultiLevelCache) {
		c.memoryTTL = ttl
	}
}

func WithRedisTTL(ttl time.Duration) CacheOption {
	return func(c *MultiLevelCache) {
		c.redisTTL = ttl
	}
}

func WithRedisClient(client *redis.Client, prefix string) CacheOption {
	return func(c *MultiLevelCache) {
		c.redisClient = client
		c.redisPrefix = prefix
	}
}

func WithPenetrationProtection(enable bool, nullTTL time.Duration) CacheOption {
	return func(c *MultiLevelCache) {
		c.enablePenetrationProtection = enable
		c.nullValueTTL = nullTTL
	}
}

func WithLoader(loader CacheLoaderFunc) CacheOption {
	return func(c *MultiLevelCache) {
		c.loader = loader
	}
}

func NewMultiLevelCache(logger *zap.Logger, opts ...CacheOption) *MultiLevelCache {
	c := &MultiLevelCache{
		memoryCache:  make(map[string]*CacheEntry),
		memoryTTL:    5 * time.Second,
		redisTTL:     30 * time.Second,
		logger:       logger,
		stats: &CacheStats{
			Hits: make(map[CacheLevel]uint64),
		},
		singleFlight: &SingleFlight{
			flight: make(map[string]*flightCall),
		},
	}

	for _, opt := range opts {
		opt(c)
	}

	go c.cleanupExpired()
	go c.logStats()

	return c
}

func (c *MultiLevelCache) Get(ctx context.Context, key string) (interface{}, CacheLevel, error) {
	return c.GetWithLoader(ctx, key, c.loader)
}

func (c *MultiLevelCache) GetWithLoader(ctx context.Context, key string, loader CacheLoaderFunc) (interface{}, CacheLevel, error) {
	value, level, hit, err := c.tryGetFromCache(ctx, key)
	if hit {
		return value, level, err
	}

	return c.singleFlightDo(key, func() (interface{}, error) {
		value, level, hit, err := c.tryGetFromCache(ctx, key)
		if hit {
			return value, err
		}

		if loader == nil {
			c.stats.mu.Lock()
			c.stats.Misses++
			c.stats.mu.Unlock()
			return nil, fmt.Errorf("cache miss and no loader provided")
		}

		c.logger.Debug("Cache miss, loading from source", zap.String("key", key))
		value, err = loader(ctx, key)
		if err != nil {
			c.stats.mu.Lock()
			c.stats.LoadErrors++
			c.stats.mu.Unlock()
			return nil, err
		}

		if value == nil && c.enablePenetrationProtection {
			c.setNullValue(ctx, key)
			c.stats.mu.Lock()
			c.stats.PenetrationBlocks++
			c.stats.mu.Unlock()
			return nil, nil
		}

		if err := c.Set(ctx, key, value); err != nil {
			c.logger.Warn("Failed to set cache after load", zap.Error(err), zap.String("key", key))
		}

		return value, nil
	})
}

func (c *MultiLevelCache) tryGetFromCache(ctx context.Context, key string) (interface{}, CacheLevel, bool, error) {
	c.mu.RLock()
	entry, exists := c.memoryCache[key]
	c.mu.RUnlock()

	if exists && time.Now().Before(entry.ExpiresAt) {
		c.recordHit(LevelMemory, key, entry)
		return entry.Value, LevelMemory, true, nil
	}

	if exists {
		c.mu.Lock()
		delete(c.memoryCache, key)
		c.stats.Evictions++
		c.mu.Unlock()
	}

	if c.redisClient != nil {
		redisKey := c.redisKey(key)
		data, err := c.redisClient.Get(ctx, redisKey).Result()
		if err == nil {
			var entry CacheEntry
			if err := json.Unmarshal([]byte(data), &entry); err == nil {
				c.mu.Lock()
				c.memoryCache[key] = &entry
				c.mu.Unlock()
				c.recordHit(LevelRedis, key, &entry)
				return entry.Value, LevelRedis, true, nil
			}
		} else if err != redis.Nil {
			c.logger.Warn("Redis get error", zap.Error(err), zap.String("key", key))
		}
	}

	return nil, LevelDB, false, nil
}

func (c *MultiLevelCache) Set(ctx context.Context, key string, value interface{}) error {
	return c.SetWithTTL(ctx, key, value, c.memoryTTL, c.redisTTL)
}

func (c *MultiLevelCache) SetWithTTL(ctx context.Context, key string, value interface{}, memoryTTL, redisTTL time.Duration) error {
	now := time.Now()
	entry := &CacheEntry{
		Value:     value,
		ExpiresAt: now.Add(memoryTTL),
		LastHit:   now,
		HitCount:  0,
	}

	c.mu.Lock()
	c.memoryCache[key] = entry
	c.mu.Unlock()

	if c.redisClient != nil {
		entry.ExpiresAt = now.Add(redisTTL)
		data, err := json.Marshal(entry)
		if err == nil {
			redisKey := c.redisKey(key)
			if err := c.redisClient.Set(ctx, redisKey, data, redisTTL).Err(); err != nil {
				c.logger.Warn("Redis set error", zap.Error(err), zap.String("key", key))
			}
		}
	}

	c.logger.Debug("Cache set", zap.String("key", key))
	return nil
}

func (c *MultiLevelCache) setNullValue(ctx context.Context, key string) {
	entry := &CacheEntry{
		Value:     nil,
		ExpiresAt: time.Now().Add(c.nullValueTTL),
	}
	c.mu.Lock()
	c.memoryCache[key] = entry
	c.mu.Unlock()

	c.logger.Debug("Null value cached for penetration protection", zap.String("key", key))
}

func (c *MultiLevelCache) Delete(ctx context.Context, key string) error {
	c.mu.Lock()
	delete(c.memoryCache, key)
	c.mu.Unlock()

	if c.redisClient != nil {
		redisKey := c.redisKey(key)
		if err := c.redisClient.Del(ctx, redisKey).Err(); err != nil {
			c.logger.Warn("Redis delete error", zap.Error(err), zap.String("key", key))
			return err
		}
	}

	c.logger.Debug("Cache deleted", zap.String("key", key))
	return nil
}

func (c *MultiLevelCache) InvalidateByPattern(ctx context.Context, pattern string) error {
	c.mu.Lock()
	for k := range c.memoryCache {
		if matchesPattern(k, pattern) {
			delete(c.memoryCache, k)
		}
	}
	c.mu.Unlock()

	if c.redisClient != nil {
		redisPattern := c.redisKey(pattern)
		iter := c.redisClient.Scan(ctx, 0, redisPattern, 0).Iterator()
		for iter.Next(ctx) {
			c.redisClient.Del(ctx, iter.Val())
		}
		if err := iter.Err(); err != nil {
			c.logger.Warn("Redis scan error", zap.Error(err))
		}
	}

	c.logger.Debug("Cache invalidated by pattern", zap.String("pattern", pattern))
	return nil
}

func (c *MultiLevelCache) Warmup(ctx context.Context, keys []string, loader CacheLoaderFunc) error {
	c.logger.Info("Starting cache warmup", zap.Int("keys", len(keys)))

	for _, key := range keys {
		value, err := loader(ctx, key)
		if err != nil {
			c.logger.Warn("Warmup failed for key", zap.String("key", key), zap.Error(err))
			continue
		}
		if value != nil {
			c.Set(ctx, key, value)
		}
	}

	c.logger.Info("Cache warmup completed")
	return nil
}

func (c *MultiLevelCache) recordHit(level CacheLevel, key string, entry *CacheEntry) {
	c.stats.mu.Lock()
	c.stats.Hits[level]++
	c.stats.mu.Unlock()

	entry.HitCount++
	entry.LastHit = time.Now()
}

func (c *MultiLevelCache) cleanupExpired() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for {
		<-ticker.C
		now := time.Now()
		evicted := 0

		c.mu.Lock()
		for k, v := range c.memoryCache {
			if now.After(v.ExpiresAt) {
				delete(c.memoryCache, k)
				evicted++
			}
		}
		c.stats.Evictions += uint64(evicted)
		c.mu.Unlock()

		if evicted > 0 {
			c.logger.Debug("Expired cache entries cleaned", zap.Int("count", evicted))
		}
	}
}

func (c *MultiLevelCache) logStats() {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	for {
		<-ticker.C
		c.stats.mu.RLock()
		totalHits := uint64(0)
		for _, v := range c.stats.Hits {
			totalHits += v
		}
		total := totalHits + c.stats.Misses
		hitRate := 0.0
		if total > 0 {
			hitRate = float64(totalHits) / float64(total) * 100
		}
		c.logger.Info("Cache statistics",
			zap.Uint64("memory_hits", c.stats.Hits[LevelMemory]),
			zap.Uint64("redis_hits", c.stats.Hits[LevelRedis]),
			zap.Uint64("misses", c.stats.Misses),
			zap.Uint64("evictions", c.stats.Evictions),
			zap.Uint64("load_errors", c.stats.LoadErrors),
			zap.Uint64("penetration_blocks", c.stats.PenetrationBlocks),
			zap.Float64("hit_rate_pct", hitRate))
		c.stats.mu.RUnlock()
	}
}

func (c *MultiLevelCache) GetStats() *CacheStats {
	c.stats.mu.RLock()
	defer c.stats.mu.RUnlock()
	return &CacheStats{
		Hits:              map[CacheLevel]uint64{
			LevelMemory: c.stats.Hits[LevelMemory],
			LevelRedis:  c.stats.Hits[LevelRedis],
		},
		Misses:            c.stats.Misses,
		Evictions:         c.stats.Evictions,
		LoadErrors:        c.stats.LoadErrors,
		PenetrationBlocks: c.stats.PenetrationBlocks,
	}
}

func (c *MultiLevelCache) singleFlightDo(key string, fn func() (interface{}, error)) (interface{}, error) {
	c.singleFlight.mu.Lock()
	if call, ok := c.singleFlight.flight[key]; ok {
		c.singleFlight.mu.Unlock()
		call.wg.Wait()
		return call.val, call.err
	}

	call := &flightCall{}
	call.wg.Add(1)
	c.singleFlight.flight[key] = call
	c.singleFlight.mu.Unlock()

	call.val, call.err = fn()
	call.wg.Done()

	c.singleFlight.mu.Lock()
	delete(c.singleFlight.flight, key)
	c.singleFlight.mu.Unlock()

	return call.val, call.err
}

func (c *MultiLevelCache) redisKey(key string) string {
	hash := sha256.Sum256([]byte(key))
	return fmt.Sprintf("%s:%s", c.redisPrefix, hex.EncodeToString(hash[:])[:16])
}

func matchesPattern(key, pattern string) bool {
	return len(key) >= len(pattern) && (key == pattern || (len(pattern) > 0 && pattern[len(pattern)-1] == '*' &&
		len(key) >= len(pattern)-1 && key[:len(pattern)-1] == pattern[:len(pattern)-1]))
}
