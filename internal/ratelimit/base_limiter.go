package ratelimit

import (
	"context"
	"fmt"
	"sync"

	"github.com/redis/go-redis/v9"
)

type BaseRateLimiter struct {
	client  RedisClient
	scripts map[string]*redis.Script
	mu      sync.Mutex
}

func NewBaseRateLimiter(client RedisClient) *BaseRateLimiter {
	return &BaseRateLimiter{
		client:  client,
		scripts: make(map[string]*redis.Script),
	}
}

func (b *BaseRateLimiter) RegisterScript(name string, script *redis.Script) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.scripts[name] = script
}

func (b *BaseRateLimiter) GetScript(name string) *redis.Script {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.scripts[name]
}

func (b *BaseRateLimiter) RunScript(ctx context.Context, script *redis.Script, keys []string, args ...interface{}) ([]interface{}, error) {
	b.mu.Lock()
	defer b.mu.Unlock()

	result, err := script.Run(ctx, b.client, keys, args...).Slice()
	if err != nil {
		return nil, fmt.Errorf("script execution failed: %w", err)
	}
	return result, nil
}

func (b *BaseRateLimiter) BuildKey(key string) string {
	return fmt.Sprintf("%s%s", keyPrefix, key)
}

func (b *BaseRateLimiter) Client() RedisClient {
	return b.client
}

func (b *BaseRateLimiter) fallbackAllow(opts LimitOptions) *LimitResult {
	capacity := opts.Capacity
	if capacity <= 0 {
		capacity = opts.Limit
	}
	if capacity <= 0 {
		capacity = 100
	}
	return &LimitResult{
		Allowed:   true,
		Remaining: capacity,
		Limit:     capacity,
	}
}
