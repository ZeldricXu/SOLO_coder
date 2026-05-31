package cache

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"go.uber.org/zap"
)

type Cache struct {
	mu        sync.RWMutex
	data      map[string]*domain.CacheEntry
	maxSize   int
	hits      int64
	misses    int64
	evictions int64
	logger    *zap.Logger
}

func NewCache(maxSize int, logger *zap.Logger) ports.DNSCache {
	if logger == nil {
		logger = zap.NewNop()
	}
	c := &Cache{
		data:    make(map[string]*domain.CacheEntry, maxSize),
		maxSize: maxSize,
		logger:  logger,
	}
	go c.startCleanup()
	return c
}

func (c *Cache) Get(ctx context.Context, key string) (*domain.DNSResponse, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	entry, exists := c.data[key]
	if !exists {
		c.misses++
		return nil, false
	}

	if time.Now().After(entry.Expiration) {
		c.misses++
		return nil, false
	}

	entry.HitCount++
	c.hits++

	resp, ok := entry.Value.(*domain.DNSResponse)
	if !ok {
		return nil, false
	}
	return resp, true
}

func (c *Cache) Set(ctx context.Context, key string, resp *domain.DNSResponse, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if len(c.data) >= c.maxSize {
		c.evictLRU()
	}

	c.data[key] = &domain.CacheEntry{
		Key:        key,
		Value:      resp,
		Expiration: time.Now().Add(ttl),
		HitCount:   0,
	}

	c.logger.Debug("dns cache entry set",
		zap.String("key", key),
		zap.Int("cache_size", len(c.data)),
		zap.Duration("ttl", ttl),
	)
}

func (c *Cache) Delete(ctx context.Context, key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.data, key)
}

func (c *Cache) Clear(ctx context.Context) {
	c.mu.Lock()
	defer c.mu.Unlock()
	oldSize := len(c.data)
	c.data = make(map[string]*domain.CacheEntry, c.maxSize)
	c.logger.Info("dns cache cleared",
		zap.Int("previous_size", oldSize),
	)
}

func (c *Cache) Stats(ctx context.Context) *domain.CacheStats {
	c.mu.RLock()
	defer c.mu.RUnlock()

	total := c.hits + c.misses
	hitRate := 0.0
	if total > 0 {
		hitRate = float64(c.hits) / float64(total)
	}

	return &domain.CacheStats{
		Hits:      c.hits,
		Misses:    c.misses,
		HitRate:   hitRate,
		Size:      len(c.data),
		MaxSize:   c.maxSize,
		Evictions: c.evictions,
	}
}

func (c *Cache) evictLRU() {
	var oldestKey string
	var oldestTime time.Time

	for k, v := range c.data {
		if oldestTime.IsZero() || v.Expiration.Before(oldestTime) {
			oldestTime = v.Expiration
			oldestKey = k
		}
	}

	if oldestKey != "" {
		delete(c.data, oldestKey)
		c.evictions++
		c.logger.Debug("dns cache evicted entry",
			zap.String("key", oldestKey),
		)
	}
}

func (c *Cache) startCleanup() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		c.mu.Lock()
		now := time.Now()
		for k, v := range c.data {
			if now.After(v.Expiration) {
				delete(c.data, k)
			}
		}
		c.mu.Unlock()
	}
}

func GenerateKey(domain, recordType string) string {
	h := sha256.New()
	h.Write([]byte(domain + ":" + recordType))
	return hex.EncodeToString(h.Sum(nil))
}
