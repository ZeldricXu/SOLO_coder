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

var tokenBucketScript = redis.NewScript(`
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local window = tonumber(ARGV[3])
local now = tonumber(ARGV[4])

local data = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(data[1])
local lastRefill = tonumber(data[2])

if tokens == nil then
    tokens = capacity
    lastRefill = now
end

local elapsed = now - lastRefill
local refillAmount = math.floor(elapsed / window) * refillRate

if refillAmount > 0 then
    tokens = math.min(capacity, tokens + refillAmount)
    lastRefill = lastRefill + math.floor(elapsed / window) * window
end

local allowed = 0
local remaining = tokens
local resetAfter = 0

if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
    remaining = tokens
    resetAfter = window - (now - lastRefill)
else
    remaining = 0
    resetAfter = window - (now - lastRefill)
end

redis.call('HMSET', key, 'tokens', tokens, 'last_refill', lastRefill)
redis.call('EXPIRE', key, math.ceil(window * 2 / 1000))

return {allowed, remaining, capacity, resetAfter}
`)

var slidingWindowScript = redis.NewScript(`
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local windowStart = now - window

redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)

local count = redis.call('ZCARD', key)
local allowed = 0
local remaining = 0
local resetAfter = 0

if count < limit then
    redis.call('ZADD', key, now, now .. ':' .. math.random())
    allowed = 1
    remaining = limit - count - 1
else
    remaining = 0
end

redis.call('EXPIRE', key, math.ceil(window * 2 / 1000))

local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
if #oldest > 0 then
    local oldestTime = tonumber(oldest[2])
    resetAfter = window - (now - oldestTime)
else
    resetAfter = window
end

return {allowed, remaining, limit, resetAfter}
`)

var concurrencyAcquireScript = redis.NewScript(`
local key = KEYS[1]
local maxConcurrent = tonumber(ARGV[1])
local requestID = ARGV[2]
local now = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

redis.call('ZREMRANGEBYSCORE', key, '-inf', now - ttl)

local current = redis.call('ZCARD', key)
local allowed = 0

if current < maxConcurrent then
    redis.call('ZADD', key, now, requestID)
    redis.call('EXPIRE', key, math.ceil(ttl / 1000))
    allowed = 1
end

local deduct = 0
if allowed == 1 then
    deduct = 1
end
return {allowed, maxConcurrent - current - deduct, maxConcurrent}
`)

var concurrencyReleaseScript = redis.NewScript(`
local key = KEYS[1]
local requestID = ARGV[1]

redis.call('ZREM', key, requestID)

return 1
`)

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
