package redis

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

const lockValue = "1"

func (r *RedisClient) AcquireLock(key string, ttl time.Duration) (bool, error) {
	if key == "" {
		return false, fmt.Errorf("lock key is required")
	}
	if ttl <= 0 {
		return false, fmt.Errorf("ttl must be positive")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	ok, err := r.client.SetNX(ctx, key, lockValue, ttl).Result()
	if err != nil {
		return false, fmt.Errorf("failed to acquire lock: %w", err)
	}
	return ok, nil
}

func (r *RedisClient) ReleaseLock(key string) error {
	if key == "" {
		return fmt.Errorf("lock key is required")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	script := redis.NewScript(`
		if redis.call("get", KEYS[1]) == ARGV[1] then
			return redis.call("del", KEYS[1])
		else
			return 0
		end
	`)

	result, err := script.Run(ctx, r.client, []string{key}, lockValue).Result()
	if err != nil {
		return fmt.Errorf("failed to release lock: %w", err)
	}

	if result.(int64) == 0 {
		return fmt.Errorf("lock not found or already released")
	}
	return nil
}

func (r *RedisClient) SetCounter(key string, value int64, ttl time.Duration) error {
	if key == "" {
		return fmt.Errorf("counter key is required")
	}
	if value < 0 {
		return fmt.Errorf("counter value cannot be negative")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	pipe := r.client.TxPipeline()
	pipe.Set(ctx, key, value, ttl)
	if ttl > 0 {
		pipe.Expire(ctx, key, ttl)
	}

	_, err := pipe.Exec(ctx)
	if err != nil {
		return fmt.Errorf("failed to set counter: %w", err)
	}
	return nil
}

func (r *RedisClient) IncrCounter(key string, ttl time.Duration) (int64, error) {
	if key == "" {
		return 0, fmt.Errorf("counter key is required")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	script := redis.NewScript(`
		local current = redis.call("incr", KEYS[1])
		if current == 1 and ARGV[1] ~= "0" then
			redis.call("pexpire", KEYS[1], ARGV[1])
		end
		return current
	`)

	ttlMs := int64(ttl.Milliseconds())
	result, err := script.Run(ctx, r.client, []string{key}, fmt.Sprintf("%d", ttlMs)).Result()
	if err != nil {
		return 0, fmt.Errorf("failed to increment counter: %w", err)
	}

	return result.(int64), nil
}

func (r *RedisClient) GetCounter(key string) (int64, error) {
	if key == "" {
		return 0, fmt.Errorf("counter key is required")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	val, err := r.client.Get(ctx, key).Int64()
	if err != nil {
		if err == redis.Nil {
			return 0, nil
		}
		return 0, fmt.Errorf("failed to get counter: %w", err)
	}
	return val, nil
}
