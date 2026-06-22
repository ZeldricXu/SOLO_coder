package cache

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/featureflag/sdk"
)

type MemoryCache struct {
	mu       sync.RWMutex
	data     map[string]*featureflag.SwitchSnapshot
	ttl      time.Duration
	maxSize  int
	timestamps map[string]time.Time
}

func NewMemoryCache(ttl time.Duration, maxSize int) *MemoryCache {
	mc := &MemoryCache{
		data:       make(map[string]*featureflag.SwitchSnapshot),
		ttl:        ttl,
		maxSize:    maxSize,
		timestamps: make(map[string]time.Time),
	}

	if ttl > 0 {
		go mc.cleanup()
	}

	return mc
}

func (m *MemoryCache) Get(ctx context.Context, key string) (*featureflag.SwitchSnapshot, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	sw, ok := m.data[key]
	if !ok {
		return nil, false
	}

	if m.ttl > 0 {
		if ts, ok := m.timestamps[key]; ok {
			if time.Since(ts) > m.ttl {
				return nil, false
			}
		}
	}

	return sw, true
}

func (m *MemoryCache) Set(ctx context.Context, key string, value *featureflag.SwitchSnapshot) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.maxSize > 0 && len(m.data) >= m.maxSize {
		var oldestKey string
		var oldestTime time.Time
		for k, t := range m.timestamps {
			if oldestKey == "" || t.Before(oldestTime) {
				oldestKey = k
				oldestTime = t
			}
		}
		if oldestKey != "" {
			delete(m.data, oldestKey)
			delete(m.timestamps, oldestKey)
		}
	}

	m.data[key] = value
	m.timestamps[key] = time.Now()
	return nil
}

func (m *MemoryCache) Delete(ctx context.Context, key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	delete(m.data, key)
	delete(m.timestamps, key)
	return nil
}

func (m *MemoryCache) GetAll(ctx context.Context) (map[string]*featureflag.SwitchSnapshot, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make(map[string]*featureflag.SwitchSnapshot, len(m.data))
	for k, v := range m.data {
		result[k] = v
	}
	return result, nil
}

func (m *MemoryCache) SetAll(ctx context.Context, switches map[string]*featureflag.SwitchSnapshot) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.data = make(map[string]*featureflag.SwitchSnapshot, len(switches))
	m.timestamps = make(map[string]time.Time, len(switches))
	now := time.Now()

	for k, v := range switches {
		m.data[k] = v
		m.timestamps[k] = now
	}
	return nil
}

func (m *MemoryCache) Clear(ctx context.Context) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.data = make(map[string]*featureflag.SwitchSnapshot)
	m.timestamps = make(map[string]time.Time)
	return nil
}

func (m *MemoryCache) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.data = nil
	m.timestamps = nil
	return nil
}

func (m *MemoryCache) cleanup() {
	ticker := time.NewTicker(m.ttl)
	defer ticker.Stop()

	for range ticker.C {
		m.mu.Lock()
		now := time.Now()
		for k, t := range m.timestamps {
			if now.Sub(t) > m.ttl {
				delete(m.data, k)
				delete(m.timestamps, k)
			}
		}
		m.mu.Unlock()
	}
}

type RedisCache struct {
	client    *redis.Client
	keyPrefix string
	ttl       time.Duration
	mu        sync.Mutex
}

type RedisCacheOptions struct {
	Addr      string
	Password  string
	DB        int
	KeyPrefix string
	TTL       time.Duration
	PoolSize  int
}

func NewRedisCache(opts *RedisCacheOptions) *RedisCache {
	if opts == nil {
		opts = &RedisCacheOptions{
			Addr:      "localhost:6379",
			KeyPrefix: "ff:",
			TTL:       5 * time.Minute,
			PoolSize:  100,
		}
	}

	client := redis.NewClient(&redis.Options{
		Addr:     opts.Addr,
		Password: opts.Password,
		DB:       opts.DB,
		PoolSize: opts.PoolSize,
	})

	return &RedisCache{
		client:    client,
		keyPrefix: opts.KeyPrefix,
		ttl:       opts.TTL,
	}
}

func (r *RedisCache) Get(ctx context.Context, key string) (*featureflag.SwitchSnapshot, bool) {
	cacheKey := r.keyPrefix + key
	data, err := r.client.Get(ctx, cacheKey).Bytes()
	if err == redis.Nil {
		return nil, false
	}
	if err != nil {
		return nil, false
	}

	var sw featureflag.SwitchSnapshot
	if err := json.Unmarshal(data, &sw); err != nil {
		return nil, false
	}

	return &sw, true
}

func (r *RedisCache) Set(ctx context.Context, key string, value *featureflag.SwitchSnapshot) error {
	cacheKey := r.keyPrefix + key
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}

	return r.client.Set(ctx, cacheKey, data, r.ttl).Err()
}

func (r *RedisCache) Delete(ctx context.Context, key string) error {
	cacheKey := r.keyPrefix + key
	return r.client.Del(ctx, cacheKey).Err()
}

func (r *RedisCache) GetAll(ctx context.Context) (map[string]*featureflag.SwitchSnapshot, error) {
	pattern := r.keyPrefix + "*"
	keys, err := r.client.Keys(ctx, pattern).Result()
	if err != nil {
		return nil, err
	}

	result := make(map[string]*featureflag.SwitchSnapshot, len(keys))
	for _, key := range keys {
		data, err := r.client.Get(ctx, key).Bytes()
		if err != nil {
			continue
		}
		var sw featureflag.SwitchSnapshot
		if err := json.Unmarshal(data, &sw); err != nil {
			continue
		}
		originalKey := key[len(r.keyPrefix):]
		result[originalKey] = &sw
	}

	return result, nil
}

func (r *RedisCache) SetAll(ctx context.Context, switches map[string]*featureflag.SwitchSnapshot) error {
	pipe := r.client.TxPipeline()

	pattern := r.keyPrefix + "*"
	existingKeys, err := r.client.Keys(ctx, pattern).Result()
	if err == nil && len(existingKeys) > 0 {
		pipe.Del(ctx, existingKeys...)
	}

	for k, v := range switches {
		cacheKey := r.keyPrefix + k
		data, err := json.Marshal(v)
		if err != nil {
			continue
		}
		pipe.Set(ctx, cacheKey, data, r.ttl)
	}

	_, err = pipe.Exec(ctx)
	return err
}

func (r *RedisCache) Clear(ctx context.Context) error {
	pattern := r.keyPrefix + "*"
	keys, err := r.client.Keys(ctx, pattern).Result()
	if err != nil {
		return err
	}
	if len(keys) > 0 {
		return r.client.Del(ctx, keys...).Err()
	}
	return nil
}

func (r *RedisCache) Close() error {
	return r.client.Close()
}

func NewCacheBackend(cacheType string, ttl time.Duration, maxSize int, redisOpts *RedisCacheOptions) (featureflag.CacheBackend, error) {
	switch cacheType {
	case "memory", "":
		return NewMemoryCache(ttl, maxSize), nil
	case "redis":
		return NewRedisCache(redisOpts), nil
	default:
		return nil, fmt.Errorf("unsupported cache type: %s", cacheType)
	}
}
