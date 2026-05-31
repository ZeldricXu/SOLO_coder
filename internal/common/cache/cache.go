package cache

import (
	"context"
	"fmt"
	"sync"
	"time"

	"notificationplatform/config"

	"github.com/go-redis/redis/v8"
)

var (
	client *redis.Client
	once   sync.Once
)

func Init(cfg *config.RedisConfig) {
	once.Do(func() {
		client = redis.NewClient(&redis.Options{
			Addr:     fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
			Password: cfg.Password,
			DB:       cfg.DB,
		})

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()

		if err := client.Ping(ctx).Err(); err != nil {
			fmt.Printf("Warning: failed to connect to Redis: %v\n", err)
		}
	})
}

func GetClient() *redis.Client {
	return client
}

type LocalCache struct {
	data  map[string]*cacheEntry
	mu    sync.RWMutex
}

type cacheEntry struct {
	value      interface{}
	expiresAt  time.Time
}

func NewLocalCache() *LocalCache {
	return &LocalCache{
		data: make(map[string]*cacheEntry),
	}
}

func (c *LocalCache) Get(key string) (interface{}, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	entry, exists := c.data[key]
	if !exists {
		return nil, false
	}

	if time.Now().After(entry.expiresAt) {
		return nil, false
	}

	return entry.value, true
}

func (c *LocalCache) Set(key string, value interface{}, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.data[key] = &cacheEntry{
		value:     value,
		expiresAt: time.Now().Add(ttl),
	}
}

func (c *LocalCache) Delete(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	delete(c.data, key)
}

func (c *LocalCache) Cleanup() {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	for key, entry := range c.data {
		if now.After(entry.expiresAt) {
			delete(c.data, key)
		}
	}
}
