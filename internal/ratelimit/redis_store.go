package ratelimit

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

const (
	keyPrefix = "ratelimit:"
)

type RedisClient interface {
	redis.Scripter
}

type RedisStore struct {
	client RedisClient
	mu     sync.Mutex
}

func NewRedisStore(client RedisClient) *RedisStore {
	return &RedisStore{
		client: client,
	}
}

func (s *RedisStore) buildKey(key string) string {
	return fmt.Sprintf("%s%s", keyPrefix, key)
}

type TokenBucketResult struct {
	Allowed    bool
	Remaining  int64
	Limit      int64
	ResetAfter time.Duration
}

func (s *RedisStore) TokenBucketTake(ctx context.Context, key string, capacity, refillRate int64, window time.Duration) (*TokenBucketResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now().UnixMilli()
	windowMs := window.Milliseconds()

	result, err := tokenBucketScript.Run(ctx, s.client, []string{s.buildKey(key)},
		capacity, refillRate, windowMs, now).Slice()
	if err != nil {
		return nil, fmt.Errorf("token bucket script failed: %w", err)
	}

	allowed, _ := result[0].(int64)
	remaining, _ := result[1].(int64)
	limit, _ := result[2].(int64)
	resetAfterMs, _ := result[3].(int64)

	return &TokenBucketResult{
		Allowed:    allowed == 1,
		Remaining:  remaining,
		Limit:      limit,
		ResetAfter: time.Duration(resetAfterMs) * time.Millisecond,
	}, nil
}

type SlidingWindowResult struct {
	Allowed    bool
	Remaining  int64
	Limit      int64
	ResetAfter time.Duration
}

func (s *RedisStore) SlidingWindowAllow(ctx context.Context, key string, limit int64, window time.Duration) (*SlidingWindowResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now().UnixMilli()
	windowMs := window.Milliseconds()

	result, err := slidingWindowScript.Run(ctx, s.client, []string{s.buildKey(key)},
		limit, windowMs, now).Slice()
	if err != nil {
		return nil, fmt.Errorf("sliding window script failed: %w", err)
	}

	allowed, _ := result[0].(int64)
	remaining, _ := result[1].(int64)
	limitVal, _ := result[2].(int64)
	resetAfterMs, _ := result[3].(int64)

	return &SlidingWindowResult{
		Allowed:    allowed == 1,
		Remaining:  remaining,
		Limit:      limitVal,
		ResetAfter: time.Duration(resetAfterMs) * time.Millisecond,
	}, nil
}

type ConcurrencyAcquireResult struct {
	Allowed    bool
	Remaining  int64
	Limit      int64
	ReleaseFunc func()
}

func (s *RedisStore) ConcurrencyAcquire(ctx context.Context, key string, maxConcurrent int64, requestID string, ttl time.Duration) (*ConcurrencyAcquireResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now().UnixMilli()
	ttlMs := ttl.Milliseconds()

	result, err := concurrencyAcquireScript.Run(ctx, s.client, []string{s.buildKey(key)},
		maxConcurrent, requestID, now, ttlMs).Slice()
	if err != nil {
		return nil, fmt.Errorf("concurrency acquire script failed: %w", err)
	}

	allowed, _ := result[0].(int64)
	remaining, _ := result[1].(int64)
	limit, _ := result[2].(int64)

	releaseFunc := func() {
		if allowed == 1 {
			_ = concurrencyReleaseScript.Run(context.Background(), s.client, []string{s.buildKey(key)}, requestID).Err()
		}
	}

	return &ConcurrencyAcquireResult{
		Allowed:     allowed == 1,
		Remaining:   remaining,
		Limit:       limit,
		ReleaseFunc: releaseFunc,
	}, nil
}
