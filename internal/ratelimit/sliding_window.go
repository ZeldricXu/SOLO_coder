package ratelimit

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
)

type SlidingWindowStore interface {
	SlidingWindowAllow(ctx context.Context, key string, limit int64, window time.Duration) (*SlidingWindowResult, error)
}

type SlidingWindow struct {
	store SlidingWindowStore
	base  *BaseRateLimiter
}

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

func NewSlidingWindow(store SlidingWindowStore) *SlidingWindow {
	sw := &SlidingWindow{
		store: store,
	}

	if rs, ok := store.(*RedisStore); ok {
		sw.base = NewBaseRateLimiter(rs.client)
		sw.base.RegisterScript("sliding_window", slidingWindowScript)
	}

	return sw
}

func NewSlidingWindowWithBase(store SlidingWindowStore, base *BaseRateLimiter) *SlidingWindow {
	return &SlidingWindow{
		store: store,
		base:  base,
	}
}

func (sw *SlidingWindow) Check(ctx context.Context, key string, limit int64, window time.Duration) (bool, int64, int64, time.Duration) {
	if limit <= 0 {
		limit = 100
	}
	if window <= 0 {
		window = time.Minute
	}

	result, err := sw.store.SlidingWindowAllow(ctx, key, limit, window)
	if err != nil {
		return true, limit, limit, 0
	}

	return result.Allowed, result.Remaining, result.Limit, result.ResetAfter
}

func (sw *SlidingWindow) Allow(ctx context.Context, key string, opts LimitOptions) (*LimitResult, error) {
	if opts.Limit <= 0 {
		opts.Limit = 100
	}
	if opts.Window <= 0 {
		opts.Window = time.Minute
	}

	if sw.base != nil {
		return sw.allowViaBase(ctx, key, opts)
	}

	result, err := sw.store.SlidingWindowAllow(ctx, key, opts.Limit, opts.Window)
	if err != nil {
		return sw.fallbackAllow(opts), nil
	}

	return &LimitResult{
		Allowed:    result.Allowed,
		Remaining:  result.Remaining,
		Limit:      result.Limit,
		ResetAfter: result.ResetAfter,
	}, nil
}

func (sw *SlidingWindow) allowViaBase(ctx context.Context, key string, opts LimitOptions) (*LimitResult, error) {
	now := time.Now().UnixMilli()
	windowMs := opts.Window.Milliseconds()

	keys := []string{sw.base.BuildKey(key)}
	args := []interface{}{opts.Limit, windowMs, now}

	result, err := sw.base.RunScript(ctx, slidingWindowScript, keys, args...)
	if err != nil {
		return sw.fallbackAllow(opts), nil
	}

	allowed, _ := result[0].(int64)
	remaining, _ := result[1].(int64)
	limitVal, _ := result[2].(int64)
	resetAfterMs, _ := result[3].(int64)

	return &LimitResult{
		Allowed:    allowed == 1,
		Remaining:  remaining,
		Limit:      limitVal,
		ResetAfter: time.Duration(resetAfterMs) * time.Millisecond,
	}, nil
}

func (sw *SlidingWindow) fallbackAllow(opts LimitOptions) *LimitResult {
	limit := opts.Limit
	if limit <= 0 {
		limit = 100
	}
	return &LimitResult{
		Allowed:   true,
		Remaining: limit,
		Limit:     limit,
	}
}
