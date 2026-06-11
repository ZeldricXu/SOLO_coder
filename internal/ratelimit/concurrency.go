package ratelimit

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

type ConcurrencyStore interface {
	ConcurrencyAcquire(ctx context.Context, key string, maxConcurrent int64, requestID string, ttl time.Duration) (*ConcurrencyAcquireResult, error)
}

type Concurrency struct {
	store ConcurrencyStore
	base  *BaseRateLimiter
}

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

func NewConcurrency(store ConcurrencyStore) *Concurrency {
	c := &Concurrency{
		store: store,
	}

	if rs, ok := store.(*RedisStore); ok {
		c.base = NewBaseRateLimiter(rs.client)
		c.base.RegisterScript("concurrency_acquire", concurrencyAcquireScript)
		c.base.RegisterScript("concurrency_release", concurrencyReleaseScript)
	}

	return c
}

func NewConcurrencyWithBase(store ConcurrencyStore, base *BaseRateLimiter) *Concurrency {
	return &Concurrency{
		store: store,
		base:  base,
	}
}

func (c *Concurrency) Acquire(ctx context.Context, key string, maxConcurrent int64) (bool, func(), error) {
	if maxConcurrent <= 0 {
		maxConcurrent = 100
	}

	requestID := fmt.Sprintf("%s:%d", key, time.Now().UnixNano())
	ttl := 5 * time.Minute

	result, err := c.store.ConcurrencyAcquire(ctx, key, maxConcurrent, requestID, ttl)
	if err != nil {
		return true, func() {}, nil
	}

	return result.Allowed, result.ReleaseFunc, nil
}

func (c *Concurrency) Allow(ctx context.Context, key string, opts LimitOptions) (*LimitResult, error) {
	if opts.MaxConcurrent <= 0 {
		opts.MaxConcurrent = 100
	}

	if c.base != nil {
		return c.allowViaBase(ctx, key, opts)
	}

	requestID := fmt.Sprintf("%s:%d", key, time.Now().UnixNano())
	ttl := 5 * time.Minute

	result, err := c.store.ConcurrencyAcquire(ctx, key, opts.MaxConcurrent, requestID, ttl)
	if err != nil {
		return c.fallbackAllow(opts), nil
	}

	var releaseFunc func()
	if result.Allowed {
		releaseFunc = result.ReleaseFunc
	}

	return &LimitResult{
		Allowed:     result.Allowed,
		Remaining:   result.Remaining,
		Limit:       result.Limit,
		ReleaseFunc: releaseFunc,
	}, nil
}

func (c *Concurrency) allowViaBase(ctx context.Context, key string, opts LimitOptions) (*LimitResult, error) {
	requestID := fmt.Sprintf("%s:%d", key, time.Now().UnixNano())
	ttl := 5 * time.Minute

	now := time.Now().UnixMilli()
	ttlMs := ttl.Milliseconds()

	keys := []string{c.base.BuildKey(key)}
	args := []interface{}{opts.MaxConcurrent, requestID, now, ttlMs}

	result, err := c.base.RunScript(ctx, concurrencyAcquireScript, keys, args...)
	if err != nil {
		return c.fallbackAllow(opts), nil
	}

	allowed, _ := result[0].(int64)
	remaining, _ := result[1].(int64)
	limit, _ := result[2].(int64)

	var releaseFunc func()
	if allowed == 1 {
		releaseFunc = func() {
			_, _ = c.base.RunScript(context.Background(), concurrencyReleaseScript, []string{c.base.BuildKey(key)}, requestID)
		}
	}

	return &LimitResult{
		Allowed:     allowed == 1,
		Remaining:   remaining,
		Limit:       limit,
		ReleaseFunc: releaseFunc,
	}, nil
}

func (c *Concurrency) fallbackAllow(opts LimitOptions) *LimitResult {
	maxConcurrent := opts.MaxConcurrent
	if maxConcurrent <= 0 {
		maxConcurrent = 100
	}
	return &LimitResult{
		Allowed:     true,
		Remaining:   maxConcurrent - 1,
		Limit:       maxConcurrent,
		ReleaseFunc: func() {},
	}
}
