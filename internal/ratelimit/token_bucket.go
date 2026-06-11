package ratelimit

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
)

type TokenBucketStore interface {
	TokenBucketTake(ctx context.Context, key string, capacity, refillRate int64, window time.Duration) (*TokenBucketResult, error)
}

type TokenBucket struct {
	store TokenBucketStore
	base  *BaseRateLimiter
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

func NewTokenBucket(store TokenBucketStore) *TokenBucket {
	tb := &TokenBucket{
		store: store,
	}

	if rs, ok := store.(*RedisStore); ok {
		tb.base = NewBaseRateLimiter(rs.client)
		tb.base.RegisterScript("token_bucket", tokenBucketScript)
	}

	return tb
}

func NewTokenBucketWithBase(store TokenBucketStore, base *BaseRateLimiter) *TokenBucket {
	return &TokenBucket{
		store: store,
		base:  base,
	}
}

func (tb *TokenBucket) Take(ctx context.Context, key string, capacity, refillRate int64, window time.Duration) (bool, int64, int64, time.Duration) {
	if capacity <= 0 {
		capacity = 100
	}
	if refillRate <= 0 {
		refillRate = 10
	}
	if window <= 0 {
		window = time.Second
	}

	result, err := tb.store.TokenBucketTake(ctx, key, capacity, refillRate, window)
	if err != nil {
		return true, capacity, capacity, 0
	}

	return result.Allowed, result.Remaining, result.Limit, result.ResetAfter
}

func (tb *TokenBucket) Allow(ctx context.Context, key string, opts LimitOptions) (*LimitResult, error) {
	if opts.Capacity <= 0 {
		opts.Capacity = 100
	}
	if opts.RefillRate <= 0 {
		opts.RefillRate = 10
	}
	if opts.Window <= 0 {
		opts.Window = time.Second
	}

	if tb.base != nil {
		return tb.allowViaBase(ctx, key, opts)
	}

	result, err := tb.store.TokenBucketTake(ctx, key, opts.Capacity, opts.RefillRate, opts.Window)
	if err != nil {
		return tb.fallbackAllow(opts), nil
	}

	return &LimitResult{
		Allowed:    result.Allowed,
		Remaining:  result.Remaining,
		Limit:      result.Limit,
		ResetAfter: result.ResetAfter,
	}, nil
}

func (tb *TokenBucket) allowViaBase(ctx context.Context, key string, opts LimitOptions) (*LimitResult, error) {
	now := time.Now().UnixMilli()
	windowMs := opts.Window.Milliseconds()

	keys := []string{tb.base.BuildKey(key)}
	args := []interface{}{opts.Capacity, opts.RefillRate, windowMs, now}

	result, err := tb.base.RunScript(ctx, tokenBucketScript, keys, args...)
	if err != nil {
		return tb.fallbackAllow(opts), nil
	}

	allowed, _ := result[0].(int64)
	remaining, _ := result[1].(int64)
	limit, _ := result[2].(int64)
	resetAfterMs, _ := result[3].(int64)

	return &LimitResult{
		Allowed:    allowed == 1,
		Remaining:  remaining,
		Limit:      limit,
		ResetAfter: time.Duration(resetAfterMs) * time.Millisecond,
	}, nil
}

func (tb *TokenBucket) fallbackAllow(opts LimitOptions) *LimitResult {
	return &LimitResult{
		Allowed:   true,
		Remaining: opts.Capacity,
		Limit:     opts.Capacity,
	}
}
