package gateway

import (
	"context"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"session189/internal/infrastructure/cache"
	"session189/internal/infrastructure/logger"
)

type RateLimiter interface {
	Allow(ctx context.Context, key string) (bool, int, time.Duration, error)
	Reset(ctx context.Context, key string) error
}

type baseLimiter struct {
	keyPrefix string
	limit     int
	window    time.Duration
}

func newBaseLimiter(keyPrefix string, limit int, window time.Duration) baseLimiter {
	return baseLimiter{keyPrefix: keyPrefix, limit: limit, window: window}
}

func (b *baseLimiter) redisKey(key string) string {
	return fmt.Sprintf("%s:%s", b.keyPrefix, key)
}

func (b *baseLimiter) fallbackAllow() bool { return true }

type TokenBucketLimiter struct {
	baseLimiter
	requestsPerSecond int
	burstSize         int
	mu                sync.Mutex
	localBuckets      map[string]*tokenBucket
}

type tokenBucket struct {
	tokens     float64
	capacity   float64
	refillRate float64
	lastUpdate time.Time
}

func NewTokenBucketLimiter(requestsPerSecond, burstSize int) *TokenBucketLimiter {
	return &TokenBucketLimiter{
		baseLimiter:       newBaseLimiter("ratelimit", burstSize, time.Second),
		requestsPerSecond: requestsPerSecond,
		burstSize:         burstSize,
		localBuckets:      make(map[string]*tokenBucket),
	}
}

func (l *TokenBucketLimiter) Allow(ctx context.Context, key string) (bool, int, time.Duration, error) {
	redisKey := l.redisKey(key)
	maxRequests := l.burstSize

	exists, _ := cache.Exists(ctx, redisKey)
	if !exists {
		pipe := cache.Client.Pipeline()
		pipe.Incr(ctx, redisKey)
		pipe.Expire(ctx, redisKey, l.window)
		if _, err := pipe.Exec(ctx); err != nil {
			return l.allowLocal(key), maxRequests - 1, 0, nil
		}
		return true, maxRequests - 1, l.window, nil
	}

	count, err := cache.Incr(ctx, redisKey)
	if err != nil {
		return l.allowLocal(key), maxRequests - 1, 0, nil
	}

	if count > int64(maxRequests) {
		ttl, _ := cache.TTL(ctx, redisKey)
		logger.Warn("Rate limit exceeded", zap.String("key", key), zap.Int64("count", count))
		return false, 0, ttl, nil
	}

	remaining := maxRequests - int(count)
	ttl, _ := cache.TTL(ctx, redisKey)
	return true, remaining, ttl, nil
}

func (l *TokenBucketLimiter) allowLocal(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	bucket, exists := l.localBuckets[key]
	if !exists {
		bucket = &tokenBucket{
			tokens:     float64(l.burstSize),
			capacity:   float64(l.burstSize),
			refillRate: float64(l.requestsPerSecond),
			lastUpdate: time.Now(),
		}
		l.localBuckets[key] = bucket
	}

	now := time.Now()
	elapsed := now.Sub(bucket.lastUpdate).Seconds()
	bucket.tokens = math.Min(bucket.capacity, bucket.tokens+elapsed*bucket.refillRate)
	bucket.lastUpdate = now

	if bucket.tokens >= 1 {
		bucket.tokens--
		return true
	}
	return false
}

func (l *TokenBucketLimiter) Reset(ctx context.Context, key string) error {
	return cache.Del(ctx, l.redisKey(key))
}

type FixedWindowLimiter struct{ baseLimiter }

func NewFixedWindowLimiter(limit int, window time.Duration) *FixedWindowLimiter {
	return &FixedWindowLimiter{baseLimiter: newBaseLimiter("fwlimiter", limit, window)}
}

func (l *FixedWindowLimiter) Allow(ctx context.Context, key string) (bool, int, time.Duration, error) {
	redisKey := l.redisKey(key)
	count, err := cache.Incr(ctx, redisKey)
	if err != nil {
		return l.fallbackAllow(), l.limit - 1, l.window, nil
	}
	if count == 1 {
		_ = cache.Expire(ctx, redisKey, l.window)
	}
	if count > int64(l.limit) {
		ttl, _ := cache.TTL(ctx, redisKey)
		return false, 0, ttl, nil
	}
	remaining := l.limit - int(count)
	ttl, _ := cache.TTL(ctx, redisKey)
	return true, remaining, ttl, nil
}

func (l *FixedWindowLimiter) Reset(ctx context.Context, key string) error {
	return cache.Del(ctx, l.redisKey(key))
}

type SlidingWindowLimiter struct{ baseLimiter }

func NewSlidingWindowLimiter(limit int, window time.Duration) *SlidingWindowLimiter {
	return &SlidingWindowLimiter{baseLimiter: newBaseLimiter("swlimiter", limit, window)}
}

func (l *SlidingWindowLimiter) Allow(ctx context.Context, key string) (bool, int, time.Duration, error) {
	redisKey := l.redisKey(key)
	now := time.Now().UnixNano()

	pipe := cache.Client.Pipeline()
	pipe.ZRemRangeByScore(ctx, redisKey, "0", fmt.Sprintf("%d", now-l.window.Nanoseconds()))
	pipe.ZCard(ctx, redisKey)
	pipe.ZAdd(ctx, redisKey, redis.Z{Score: float64(now), Member: now})
	pipe.Expire(ctx, redisKey, l.window)

	results, err := pipe.Exec(ctx)
	if err != nil {
		return l.fallbackAllow(), l.limit - 1, l.window, nil
	}

	if len(results) >= 2 {
		if countCmd, ok := results[1].(*redis.IntCmd); ok {
			count := countCmd.Val()
			if count >= int64(l.limit) {
				return false, 0, l.window, nil
			}
			remaining := l.limit - int(count) - 1
			return true, remaining, l.window, nil
		}
	}
	return true, l.limit - 1, l.window, nil
}

func (l *SlidingWindowLimiter) Reset(ctx context.Context, key string) error {
	return cache.Del(ctx, l.redisKey(key))
}
