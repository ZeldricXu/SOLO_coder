package cache

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/models"
	"github.com/google/uuid"
)

type CacheEntry struct {
	Key        string
	Value      interface{}
	CreatedAt  time.Time
	ExpiresAt  time.Time
	AccessedAt time.Time
	AccessCount int64
	Size       int64
}

type CacheStats struct {
	Hits        int64
	Misses      int64
	Evictions   int64
	TotalSize   int64
	EntryCount  int
	HitRate     float64
}

type EvictionPolicy string

const (
	EvictionLRU  EvictionPolicy = "lru"
	EvictionLFU  EvictionPolicy = "lfu"
	EvictionFIFO EvictionPolicy = "fifo"
	EvictionTTL  EvictionPolicy = "ttl"
)

type Cache struct {
	entries        map[string]*CacheEntry
	maxSize        int64
	currentSize    int64
	defaultTTL     time.Duration
	evictionPolicy EvictionPolicy
	mu             sync.RWMutex
	stats          CacheStats
	stopCh         chan struct{}
	wg             sync.WaitGroup
}

func NewCache(maxSize int64, defaultTTL time.Duration, policy EvictionPolicy) *Cache {
	c := &Cache{
		entries:        make(map[string]*CacheEntry),
		maxSize:        maxSize,
		defaultTTL:     defaultTTL,
		evictionPolicy: policy,
		stopCh:         make(chan struct{}),
	}

	if defaultTTL > 0 {
		c.wg.Add(1)
		go c.cleanupExpired()
	}

	return c
}

func (c *Cache) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	if ttl <= 0 {
		ttl = c.defaultTTL
	}

	size := estimateSize(value)

	c.mu.Lock()
	defer c.mu.Unlock()

	if c.currentSize+size > c.maxSize {
		c.evict(size)
	}

	now := time.Now()
	entry := &CacheEntry{
		Key:         key,
		Value:       value,
		CreatedAt:   now,
		ExpiresAt:   now.Add(ttl),
		AccessedAt:  now,
		AccessCount: 0,
		Size:        size,
	}

	if existing, ok := c.entries[key]; ok {
		c.currentSize -= existing.Size
	}

	c.entries[key] = entry
	c.currentSize += size

	return nil
}

func (c *Cache) Get(ctx context.Context, key string) (interface{}, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	entry, ok := c.entries[key]
	if !ok {
		c.stats.Misses++
		return nil, false
	}

	if time.Now().After(entry.ExpiresAt) {
		delete(c.entries, key)
		c.currentSize -= entry.Size
		c.stats.Misses++
		return nil, false
	}

	entry.AccessedAt = time.Now()
	entry.AccessCount++
	c.stats.Hits++

	return entry.Value, true
}

func (c *Cache) Delete(ctx context.Context, key string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	entry, ok := c.entries[key]
	if !ok {
		return false
	}

	delete(c.entries, key)
	c.currentSize -= entry.Size
	return true
}

func (c *Cache) Exists(ctx context.Context, key string) bool {
	c.mu.RLock()
	defer c.mu.RUnlock()

	entry, ok := c.entries[key]
	if !ok {
		return false
	}

	return !time.Now().After(entry.ExpiresAt)
}

func (c *Cache) evict(neededSize int64) {
	switch c.evictionPolicy {
	case EvictionLRU:
		c.evictLRU(neededSize)
	case EvictionLFU:
		c.evictLFU(neededSize)
	case EvictionFIFO:
		c.evictFIFO(neededSize)
	case EvictionTTL:
		c.evictTTL(neededSize)
	default:
		c.evictLRU(neededSize)
	}
}

func (c *Cache) evictLRU(neededSize int64) {
	for c.currentSize+neededSize > c.maxSize && len(c.entries) > 0 {
		var oldestKey string
		var oldestTime time.Time

		for key, entry := range c.entries {
			if oldestKey == "" || entry.AccessedAt.Before(oldestTime) {
				oldestKey = key
				oldestTime = entry.AccessedAt
			}
		}

		if oldestKey != "" {
			entry := c.entries[oldestKey]
			delete(c.entries, oldestKey)
			c.currentSize -= entry.Size
			c.stats.Evictions++
		}
	}
}

func (c *Cache) evictLFU(neededSize int64) {
	for c.currentSize+neededSize > c.maxSize && len(c.entries) > 0 {
		var leastKey string
		var leastCount int64 = -1

		for key, entry := range c.entries {
			if leastCount == -1 || entry.AccessCount < leastCount {
				leastKey = key
				leastCount = entry.AccessCount
			}
		}

		if leastKey != "" {
			entry := c.entries[leastKey]
			delete(c.entries, leastKey)
			c.currentSize -= entry.Size
			c.stats.Evictions++
		}
	}
}

func (c *Cache) evictFIFO(neededSize int64) {
	for c.currentSize+neededSize > c.maxSize && len(c.entries) > 0 {
		var oldestKey string
		var oldestTime time.Time

		for key, entry := range c.entries {
			if oldestKey == "" || entry.CreatedAt.Before(oldestTime) {
				oldestKey = key
				oldestTime = entry.CreatedAt
			}
		}

		if oldestKey != "" {
			entry := c.entries[oldestKey]
			delete(c.entries, oldestKey)
			c.currentSize -= entry.Size
			c.stats.Evictions++
		}
	}
}

func (c *Cache) evictTTL(neededSize int64) {
	now := time.Now()
	for c.currentSize+neededSize > c.maxSize && len(c.entries) > 0 {
		var earliestKey string
		var earliestExpiry time.Time

		for key, entry := range c.entries {
			if earliestKey == "" || entry.ExpiresAt.Before(earliestExpiry) {
				earliestKey = key
				earliestExpiry = entry.ExpiresAt
			}
		}

		if earliestKey != "" {
			entry := c.entries[earliestKey]
			delete(c.entries, earliestKey)
			c.currentSize -= entry.Size
			c.stats.Evictions++
		}
	}
}

func (c *Cache) cleanupExpired() {
	defer c.wg.Done()

	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-c.stopCh:
			return
		case <-ticker.C:
			c.removeExpired()
		}
	}
}

func (c *Cache) removeExpired() {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	for key, entry := range c.entries {
		if now.After(entry.ExpiresAt) {
			delete(c.entries, key)
			c.currentSize -= entry.Size
		}
	}
}

func (c *Cache) GetStats() CacheStats {
	c.mu.RLock()
	defer c.mu.RUnlock()

	stats := c.stats
	stats.TotalSize = c.currentSize
	stats.EntryCount = len(c.entries)

	total := stats.Hits + stats.Misses
	if total > 0 {
		stats.HitRate = float64(stats.Hits) / float64(total)
	}

	return stats
}

func (c *Cache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.entries = make(map[string]*CacheEntry)
	c.currentSize = 0
}

func (c *Cache) Keys() []string {
	c.mu.RLock()
	defer c.mu.RUnlock()

	keys := make([]string, 0, len(c.entries))
	for k := range c.entries {
		keys = append(keys, k)
	}
	return keys
}

func (c *Cache) Close() {
	close(c.stopCh)
	c.wg.Wait()
}

func estimateSize(value interface{}) int64 {
	switch v := value.(type) {
	case []byte:
		return int64(len(v))
	case string:
		return int64(len(v))
	case int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64:
		return 8
	case float32, float64:
		return 8
	case bool:
		return 1
	case map[string]interface{}:
		return int64(len(v) * 64)
	case []interface{}:
		return int64(len(v) * 16)
	default:
		return 128
	}
}

type MultiLevelCache struct {
	levels []*Cache
}

func NewMultiLevelCache(sizes []int64, ttls []time.Duration, policies []EvictionPolicy) *MultiLevelCache {
	mlc := &MultiLevelCache{
		levels: make([]*Cache, len(sizes)),
	}

	for i := range sizes {
		ttl := time.Duration(0)
		if i < len(ttls) {
			ttl = ttls[i]
		}
		policy := EvictionLRU
		if i < len(policies) {
			policy = policies[i]
		}
		mlc.levels[i] = NewCache(sizes[i], ttl, policy)
	}

	return mlc
}

func (mlc *MultiLevelCache) Get(ctx context.Context, key string) (interface{}, bool) {
	for i, level := range mlc.levels {
		if value, ok := level.Get(ctx, key); ok {
			for j := 0; j < i; j++ {
				mlc.levels[j].Set(ctx, key, value, mlc.levels[j].defaultTTL)
			}
			return value, true
		}
	}
	return nil, false
}

func (mlc *MultiLevelCache) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	for _, level := range mlc.levels {
		level.Set(ctx, key, value, ttl)
	}
	return nil
}

func (mlc *MultiLevelCache) Delete(ctx context.Context, key string) {
	for _, level := range mlc.levels {
		level.Delete(ctx, key)
	}
}

func (c *Cache) ToEntity() *models.Entity {
	return &models.Entity{
		ID:        uuid.New().String(),
		Type:      "cache",
		Status:    "active",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}

type CacheLoaderFunc func(ctx context.Context, key string) (interface{}, error)

type LoadingCache struct {
	*Cache
	loader CacheLoaderFunc
}

func NewLoadingCache(maxSize int64, defaultTTL time.Duration, policy EvictionPolicy, loader CacheLoaderFunc) *LoadingCache {
	return &LoadingCache{
		Cache:  NewCache(maxSize, defaultTTL, policy),
		loader: loader,
	}
}

func (lc *LoadingCache) Get(ctx context.Context, key string) (interface{}, error) {
	if value, ok := lc.Cache.Get(ctx, key); ok {
		return value, nil
	}

	if lc.loader == nil {
		return nil, errors.New("cache miss and no loader configured")
	}

	value, err := lc.loader(ctx, key)
	if err != nil {
		return nil, err
	}

	lc.Cache.Set(ctx, key, value, lc.Cache.defaultTTL)
	return value, nil
}
