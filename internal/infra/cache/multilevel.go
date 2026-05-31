package cache

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	lru "github.com/hashicorp/golang-lru/v2"
	"github.com/go-redis/redis/v8"
)

var (
	ErrCacheMiss = errors.New("cache miss")
	ErrCacheEntryExpired = errors.New("cache entry expired")
)

type CacheEntry struct {
	Key       string
	Value     interface{}
	ExpiresAt time.Time
	HitCount  int
	Size      int
}

type CacheConfig struct {
	LocalCacheSize    int
	LocalTTL          time.Duration
	RedisAddr         string
	RedisPassword     string
	RedisDB           int
	DistributedTTL    time.Duration
	EnableWarming     bool
	WarmingKeys       []string
}

type MultiLevelCache struct {
	localCache    *lru.Cache[string, *CacheEntry]
	redisClient   *redis.Client
	localTTL      time.Duration
	distributedTTL time.Duration
	mutex         sync.RWMutex
	stats         *CacheStats
	ctx           context.Context
}

type CacheStats struct {
	LocalHits      int64
	LocalMisses    int64
	LocalEvictions int64
	RemoteHits     int64
	RemoteMisses   int64
	TotalRequests  int64
	LocalHitRate   float64
	RemoteHitRate  float64
	OverallHitRate float64
}

type CacheWarmer interface {
	Warm(ctx context.Context) error
}

func NewMultiLevelCache(config *CacheConfig) (*MultiLevelCache, error) {
	if config == nil {
		config = &CacheConfig{
			LocalCacheSize: 1000,
			LocalTTL:       5 * time.Minute,
			DistributedTTL: 30 * time.Minute,
		}
	}

	localCache, err := lru.New[string, *CacheEntry](config.LocalCacheSize)
	if err != nil {
		return nil, fmt.Errorf("failed to create local LRU cache: %w", err)
	}

	cache := &MultiLevelCache{
		localCache:     localCache,
		localTTL:       config.LocalTTL,
		distributedTTL: config.DistributedTTL,
		stats:          &CacheStats{},
		ctx:            context.Background(),
	}

	if config.RedisAddr != "" {
		cache.redisClient = redis.NewClient(&redis.Options{
			Addr:     config.RedisAddr,
			Password: config.RedisPassword,
			DB:       config.RedisDB,
		})
	}

	return cache, nil
}

func (c *MultiLevelCache) Get(ctx context.Context, key string) (interface{}, error) {
	c.stats.TotalRequests++

	value, err := c.getLocal(key)
	if err == nil {
		c.stats.LocalHits++
		c.updateHitRates()
		return value, nil
	}
	c.stats.LocalMisses++

	if c.redisClient != nil {
		value, err := c.getRemote(ctx, key)
		if err == nil {
			c.stats.RemoteHits++
			c.setLocal(key, value, c.localTTL)
			c.updateHitRates()
			return value, nil
		}
	}
	c.stats.RemoteMisses++
	c.updateHitRates()

	return nil, ErrCacheMiss
}

func (c *MultiLevelCache) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	if ttl <= 0 {
		ttl = c.localTTL
	}

	err := c.setLocal(key, value, ttl)
	if err != nil {
		return err
	}

	if c.redisClient != nil {
		if err := c.setRemote(ctx, key, value, ttl); err != nil {
			return err
		}
	}

	return nil
}

func (c *MultiLevelCache) Delete(ctx context.Context, key string) error {
	c.mutex.Lock()
	c.localCache.Remove(key)
	c.mutex.Unlock()

	if c.redisClient != nil {
		return c.redisClient.Del(ctx, key).Err()
	}

	return nil
}

func (c *MultiLevelCache) Invalidate(ctx context.Context, pattern string) error {
	c.mutex.Lock()
	defer c.mutex.Unlock()

	keys := c.localCache.Keys()
	for _, key := range keys {
		if matchPattern(key, pattern) {
			c.localCache.Remove(key)
			c.stats.LocalEvictions++
		}
	}

	if c.redisClient != nil {
		keys, err := c.redisClient.Keys(ctx, pattern).Result()
		if err != nil {
			return err
		}
		
		if len(keys) > 0 {
			return c.redisClient.Del(ctx, keys...).Err()
		}
	}

	return nil
}

func (c *MultiLevelCache) Warm(ctx context.Context, warmupFunc func(ctx context.Context, cache *MultiLevelCache) error) error {
	if warmupFunc == nil {
		return nil
	}
	return warmupFunc(ctx, c)
}

func (c *MultiLevelCache) GetStats() *CacheStats {
	c.mutex.RLock()
	defer c.mutex.RUnlock()
	
	stats := &CacheStats{
		LocalHits:      c.stats.LocalHits,
		LocalMisses:    c.stats.LocalMisses,
		LocalEvictions: c.stats.LocalEvictions,
		RemoteHits:     c.stats.RemoteHits,
		RemoteMisses:   c.stats.RemoteMisses,
		TotalRequests:  c.stats.TotalRequests,
		LocalHitRate:   c.stats.LocalHitRate,
		RemoteHitRate:  c.stats.RemoteHitRate,
		OverallHitRate: c.stats.OverallHitRate,
	}
	
	return stats
}

func (c *MultiLevelCache) getLocal(key string) (interface{}, error) {
	c.mutex.RLock()
	entry, exists := c.localCache.Get(key)
	c.mutex.RUnlock()

	if !exists {
		return nil, ErrCacheMiss
	}

	if time.Now().After(entry.ExpiresAt) {
		c.mutex.Lock()
		c.localCache.Remove(key)
		c.stats.LocalEvictions++
		c.mutex.Unlock()
		return nil, ErrCacheEntryExpired
	}

	entry.HitCount++
	return entry.Value, nil
}

func (c *MultiLevelCache) setLocal(key string, value interface{}, ttl time.Duration) error {
	entry := &CacheEntry{
		Key:       key,
		Value:     value,
		ExpiresAt: time.Now().Add(ttl),
		Size:      calculateSize(value),
	}

	c.mutex.Lock()
	evicted := c.localCache.Add(key, entry)
	if evicted {
		c.stats.LocalEvictions++
	}
	c.mutex.Unlock()

	return nil
}

func (c *MultiLevelCache) getRemote(ctx context.Context, key string) (interface{}, error) {
	data, err := c.redisClient.Get(ctx, key).Bytes()
	if err != nil {
		return nil, ErrCacheMiss
	}

	var entry CacheEntry
	if err := json.Unmarshal(data, &entry); err != nil {
		return nil, err
	}

	if time.Now().After(entry.ExpiresAt) {
		c.redisClient.Del(ctx, key)
		return nil, ErrCacheEntryExpired
	}

	return entry.Value, nil
}

func (c *MultiLevelCache) setRemote(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	entry := &CacheEntry{
		Key:       key,
		Value:     value,
		ExpiresAt: time.Now().Add(ttl),
		Size:      calculateSize(value),
	}

	data, err := json.Marshal(entry)
	if err != nil {
		return err
	}

	return c.redisClient.Set(ctx, key, data, ttl).Err()
}

func (c *MultiLevelCache) updateHitRates() {
	if c.stats.TotalRequests == 0 {
		return
	}
	
	localTotal := c.stats.LocalHits + c.stats.LocalMisses
	remoteTotal := c.stats.RemoteHits + c.stats.RemoteMisses
	
	if localTotal > 0 {
		c.stats.LocalHitRate = float64(c.stats.LocalHits) / float64(localTotal)
	}
	
	if remoteTotal > 0 {
		c.stats.RemoteHitRate = float64(c.stats.RemoteHits) / float64(remoteTotal)
	}
	
	totalHits := c.stats.LocalHits + c.stats.RemoteHits
	c.stats.OverallHitRate = float64(totalHits) / float64(c.stats.TotalRequests)
}

func calculateSize(value interface{}) int {
	data, err := json.Marshal(value)
	if err != nil {
		return 0
	}
	return len(data)
}

func matchPattern(key, pattern string) bool {
	if pattern == "*" {
		return true
	}
	
	if len(pattern) == 0 || len(key) == 0 {
		return false
	}
	
	if pattern[len(pattern)-1] == '*' {
		prefix := pattern[:len(pattern)-1]
		return len(key) >= len(prefix) && key[:len(prefix)] == prefix
	}
	
	return key == pattern
}

func (c *MultiLevelCache) Close() error {
	if c.redisClient != nil {
		return c.redisClient.Close()
	}
	return nil
}
