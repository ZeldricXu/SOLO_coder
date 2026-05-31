package cache

import (
	"sync"
	"time"
)

type CacheEntry struct {
	Value     interface{}
	ExpiresAt time.Time
}

type TTLCache struct {
	items sync.Map
	ttl   time.Duration
}

func NewTTLCache(ttl time.Duration) *TTLCache {
	return &TTLCache{
		ttl: ttl,
	}
}

func (c *TTLCache) Get(key string) (interface{}, bool) {
	raw, exists := c.items.Load(key)
	if !exists {
		return nil, false
	}

	entry := raw.(*CacheEntry)
	if time.Now().After(entry.ExpiresAt) {
		c.items.Delete(key)
		return nil, false
	}

	return entry.Value, true
}

func (c *TTLCache) Set(key string, value interface{}) {
	c.items.Store(key, &CacheEntry{
		Value:     value,
		ExpiresAt: time.Now().Add(c.ttl),
	})
}

func (c *TTLCache) Delete(key string) {
	c.items.Delete(key)
}

func (c *TTLCache) DeleteWithPrefix(prefix string) {
	c.items.Range(func(key, value interface{}) bool {
		k := key.(string)
		if len(k) >= len(prefix) && k[:len(prefix)] == prefix {
			c.items.Delete(key)
		}
		return true
	})
}

func (c *TTLCache) Clear() {
	c.items.Range(func(key, value interface{}) bool {
		c.items.Delete(key)
		return true
	})
}
