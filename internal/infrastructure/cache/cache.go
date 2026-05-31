package cache

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/go-redis/redis/v9"
)

type L1Cache struct {
	mu       sync.RWMutex
	data     map[string]*cacheItem
	maxItems int
	ttl      time.Duration
}

type cacheItem struct {
	value     interface{}
	expiresAt time.Time
}

func NewL1Cache(maxEntries int, ttl time.Duration) *L1Cache {
	c := &L1Cache{
		data:     make(map[string]*cacheItem),
		maxItems: maxEntries,
		ttl:      ttl,
	}
	go c.cleanup()
	return c
}

func (c *L1Cache) Get(key string) (interface{}, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	item, ok := c.data[key]
	if !ok {
		return nil, false
	}

	if time.Now().After(item.expiresAt) {
		return nil, false
	}

	return item.value, true
}

func (c *L1Cache) Set(key string, value interface{}) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if len(c.data) >= c.maxItems {
		c.evictOldest()
	}

	c.data[key] = &cacheItem{
		value:     value,
		expiresAt: time.Now().Add(c.ttl),
	}
}

func (c *L1Cache) Delete(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.data, key)
}

func (c *L1Cache) evictOldest() {
	now := time.Now()
	for k, v := range c.data {
		if now.After(v.expiresAt) {
			delete(c.data, k)
			return
		}
	}
	for k := range c.data {
		delete(c.data, k)
		return
	}
}

func (c *L1Cache) cleanup() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		c.mu.Lock()
		now := time.Now()
		for k, v := range c.data {
			if now.After(v.expiresAt) {
				delete(c.data, k)
			}
		}
		c.mu.Unlock()
	}
}

func (c *L1Cache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.data = make(map[string]*cacheItem)
}

func (c *L1Cache) WarmUp(entries map[string]interface{}) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for k, v := range entries {
		if len(c.data) >= c.maxItems {
			break
		}
		c.data[k] = &cacheItem{
			value:     v,
			expiresAt: time.Now().Add(c.ttl),
		}
	}
}

type L2Cache struct {
	client *redis.Client
	ttl    time.Duration
}

func NewL2Cache(host string, port int, password string, db, poolSize int, ttl time.Duration) *L2Cache {
	client := redis.NewClient(&redis.Options{
		Addr:     fmt.Sprintf("%s:%d", host, port),
		Password: password,
		DB:       db,
		PoolSize: poolSize,
	})
	return &L2Cache{
		client: client,
		ttl:    ttl,
	}
}

func (c *L2Cache) Get(ctx context.Context, key string) (string, error) {
	return c.client.Get(ctx, key).Result()
}

func (c *L2Cache) Set(ctx context.Context, key string, value interface{}) error {
	return c.client.Set(ctx, key, value, c.ttl).Err()
}

func (c *L2Cache) SetWithTTL(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	return c.client.Set(ctx, key, value, ttl).Err()
}

func (c *L2Cache) Delete(ctx context.Context, key string) error {
	return c.client.Del(ctx, key).Err()
}

func (c *L2Cache) Pipeline() redis.Pipeliner {
	return c.client.Pipeline()
}

func (c *L2Cache) Close() error {
	return c.client.Close()
}

type MultiLevelCache struct {
	l1 *L1Cache
	l2 *L2Cache
}

func NewMultiLevelCache(l1 *L1Cache, l2 *L2Cache) *MultiLevelCache {
	return &MultiLevelCache{
		l1: l1,
		l2: l2,
	}
}

func (m *MultiLevelCache) Get(ctx context.Context, key string) (interface{}, bool) {
	if val, ok := m.l1.Get(key); ok {
		return val, true
	}

	val, err := m.l2.Get(ctx, key)
	if err == nil {
		m.l1.Set(key, val)
		return val, true
	}

	return nil, false
}

func (m *MultiLevelCache) Set(ctx context.Context, key string, value interface{}) {
	m.l1.Set(key, value)
	_ = m.l2.Set(ctx, key, value)
}

func (m *MultiLevelCache) Delete(ctx context.Context, key string) {
	m.l1.Delete(key)
	_ = m.l2.Delete(ctx, key)
}

func (m *MultiLevelCache) WarmUp(entries map[string]interface{}) {
	m.l1.WarmUp(entries)
}

func (m *MultiLevelCache) GetL1Size() int {
	m.l1.mu.RLock()
	defer m.l1.mu.RUnlock()
	return len(m.l1.data)
}

func (m *MultiLevelCache) GetL1MaxSize() int {
	return m.l1.maxItems
}

func (m *MultiLevelCache) InvalidateByPattern(ctx context.Context, pattern string, cacheType string) ([]string, int) {
	var invalidatedKeys []string
	count := 0

	m.l1.mu.Lock()
	for k := range m.l1.data {
		if matchPattern(k, pattern) {
			delete(m.l1.data, k)
			invalidatedKeys = append(invalidatedKeys, k)
			count++
		}
	}
	m.l1.mu.Unlock()

	if cacheType == "" || cacheType == "l2" || cacheType == "both" {
		var cursor uint64
		for {
			keys, nextCursor, err := m.l2.client.Scan(ctx, cursor, pattern, 100).Result()
			if err != nil {
				break
			}
			for _, k := range keys {
				if err := m.l2.client.Del(ctx, k).Err(); err == nil {
					invalidatedKeys = append(invalidatedKeys, k)
					count++
				}
			}
			cursor = nextCursor
			if cursor == 0 {
				break
			}
		}
	}

	return invalidatedKeys, count
}

func (m *MultiLevelCache) PingL2(ctx context.Context) bool {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	_, err := m.l2.client.Ping(ctx).Result()
	return err == nil
}

func matchPattern(key, pattern string) bool {
	if pattern == "*" {
		return true
	}
	if strings.HasSuffix(pattern, "*") {
		prefix := strings.TrimSuffix(pattern, "*")
		return strings.HasPrefix(key, prefix)
	}
	if strings.HasPrefix(pattern, "*") {
		suffix := strings.TrimPrefix(pattern, "*")
		return strings.HasSuffix(key, suffix)
	}
	if strings.Contains(pattern, "*") {
		parts := strings.Split(pattern, "*")
		if len(parts) == 2 {
			return strings.HasPrefix(key, parts[0]) && strings.HasSuffix(key, parts[1])
		}
	}
	return key == pattern
}
