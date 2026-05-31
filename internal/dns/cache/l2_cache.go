package cache

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"github.com/go-redis/redis/v8"
	"go.uber.org/zap"
)

type L2Cache struct {
	client   *redis.Client
	mu       sync.RWMutex
	hits     int64
	misses   int64
	evictions int64
	keyPrefix string
	logger   *zap.Logger
}

func NewL2Cache(redisAddr, keyPrefix string, logger *zap.Logger) ports.DNSCache {
	if logger == nil {
		logger = zap.NewNop()
	}
	client := redis.NewClient(&redis.Options{
		Addr: redisAddr,
	})

	return &L2Cache{
		client:    client,
		keyPrefix: keyPrefix,
		logger:    logger,
	}
}

func NewL2CacheWithClient(client *redis.Client, keyPrefix string, logger *zap.Logger) ports.DNSCache {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &L2Cache{
		client:    client,
		keyPrefix: keyPrefix,
		logger:    logger,
	}
}

func (c *L2Cache) buildKey(key string) string {
	return fmt.Sprintf("%s:%s", c.keyPrefix, key)
}

func (c *L2Cache) Get(ctx context.Context, key string) (*domain.DNSResponse, bool) {
	cacheKey := c.buildKey(key)

	data, err := c.client.Get(ctx, cacheKey).Result()
	if err == redis.Nil {
		atomic.AddInt64(&c.misses, 1)
		return nil, false
	}
	if err != nil {
		c.logger.Warn("l2 cache get error",
			zap.String("key", key),
			zap.Error(err),
		)
		atomic.AddInt64(&c.misses, 1)
		return nil, false
	}

	var resp domain.DNSResponse
	if err := json.Unmarshal([]byte(data), &resp); err != nil {
		c.logger.Warn("l2 cache unmarshal error",
			zap.String("key", key),
			zap.Error(err),
		)
		atomic.AddInt64(&c.misses, 1)
		return nil, false
	}

	atomic.AddInt64(&c.hits, 1)
	return &resp, true
}

func (c *L2Cache) Set(ctx context.Context, key string, resp *domain.DNSResponse, ttl time.Duration) {
	cacheKey := c.buildKey(key)

	data, err := json.Marshal(resp)
	if err != nil {
		c.logger.Warn("l2 cache marshal error",
			zap.String("key", key),
			zap.Error(err),
		)
		return
	}

	if err := c.client.Set(ctx, cacheKey, data, ttl).Err(); err != nil {
		c.logger.Warn("l2 cache set error",
			zap.String("key", key),
			zap.Error(err),
		)
		return
	}

	c.logger.Debug("l2 cache entry set",
		zap.String("key", key),
		zap.Duration("ttl", ttl),
	)
}

func (c *L2Cache) Delete(ctx context.Context, key string) {
	cacheKey := c.buildKey(key)
	if err := c.client.Del(ctx, cacheKey).Err(); err != nil {
		c.logger.Warn("l2 cache delete error",
			zap.String("key", key),
			zap.Error(err),
		)
	}
}

func (c *L2Cache) Clear(ctx context.Context) {
	iter := c.client.Scan(ctx, 0, c.buildKey("*"), 0).Iterator()
	for iter.Next(ctx) {
		if err := c.client.Del(ctx, iter.Val()).Err(); err != nil {
			c.logger.Warn("l2 cache clear error",
				zap.String("key", iter.Val()),
				zap.Error(err),
			)
		}
	}
	if err := iter.Err(); err != nil {
		c.logger.Warn("l2 cache scan error",
			zap.Error(err),
		)
	}

	c.logger.Info("l2 cache cleared")
}

func (c *L2Cache) Stats(ctx context.Context) *domain.CacheStats {
	c.mu.RLock()
	defer c.mu.RUnlock()

	hits := atomic.LoadInt64(&c.hits)
	misses := atomic.LoadInt64(&c.misses)
	total := hits + misses
	hitRate := 0.0
	if total > 0 {
		hitRate = float64(hits) / float64(total)
	}

	size := 0
	iter := c.client.Scan(ctx, 0, c.buildKey("*"), 0).Iterator()
	for iter.Next(ctx) {
		size++
	}

	return &domain.CacheStats{
		Hits:      hits,
		Misses:    misses,
		HitRate:   hitRate,
		Size:      size,
		MaxSize:   0,
		Evictions: atomic.LoadInt64(&c.evictions),
	}
}
