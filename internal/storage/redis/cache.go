package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

func (r *RedisClient) Get(key string) (string, error) {
	if key == "" {
		return "", fmt.Errorf("cache key is required")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	val, err := r.client.Get(ctx, key).Result()
	if err != nil {
		if err == redis.Nil {
			return "", nil
		}
		return "", fmt.Errorf("failed to get cache: %w", err)
	}
	return val, nil
}

func (r *RedisClient) Set(key string, value interface{}, ttl time.Duration) error {
	if key == "" {
		return fmt.Errorf("cache key is required")
	}
	if value == nil {
		return fmt.Errorf("cache value cannot be nil")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	var val string
	switch v := value.(type) {
	case string:
		val = v
	case []byte:
		val = string(v)
	default:
		jsonBytes, err := json.Marshal(value)
		if err != nil {
			return fmt.Errorf("failed to marshal cache value: %w", err)
		}
		val = string(jsonBytes)
	}

	if err := r.client.Set(ctx, key, val, ttl).Err(); err != nil {
		return fmt.Errorf("failed to set cache: %w", err)
	}
	return nil
}

func (r *RedisClient) Delete(key string) error {
	if key == "" {
		return fmt.Errorf("cache key is required")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	if err := r.client.Del(ctx, key).Err(); err != nil {
		return fmt.Errorf("failed to delete cache: %w", err)
	}
	return nil
}

func (r *RedisClient) Exists(key string) (bool, error) {
	if key == "" {
		return false, fmt.Errorf("cache key is required")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	count, err := r.client.Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check cache existence: %w", err)
	}
	return count > 0, nil
}

func (r *RedisClient) HGetAll(key string) (map[string]string, error) {
	if key == "" {
		return nil, fmt.Errorf("hash key is required")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	result, err := r.client.HGetAll(ctx, key).Result()
	if err != nil {
		if err == redis.Nil {
			return make(map[string]string), nil
		}
		return nil, fmt.Errorf("failed to get hash: %w", err)
	}
	return result, nil
}

func (r *RedisClient) HSet(key string, field string, value interface{}) error {
	if key == "" {
		return fmt.Errorf("hash key is required")
	}
	if field == "" {
		return fmt.Errorf("hash field is required")
	}
	if value == nil {
		return fmt.Errorf("hash value cannot be nil")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	var val string
	switch v := value.(type) {
	case string:
		val = v
	case []byte:
		val = string(v)
	default:
		jsonBytes, err := json.Marshal(value)
		if err != nil {
			return fmt.Errorf("failed to marshal hash value: %w", err)
		}
		val = string(jsonBytes)
	}

	if err := r.client.HSet(ctx, key, field, val).Err(); err != nil {
		return fmt.Errorf("failed to set hash field: %w", err)
	}
	return nil
}
