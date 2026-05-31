package dnsproxy

import (
	"sync"
	"time"

	"session130/internal/logger"
	"session130/internal/metrics"
)

type CacheManager struct {
	mu    sync.RWMutex
	cache map[string]*DnsCacheEntry
}

var (
	cacheInstance *CacheManager
	cacheOnce     sync.Once
)

func NewCacheManager() *CacheManager {
	return &CacheManager{
		cache: make(map[string]*DnsCacheEntry),
	}
}

func GetCacheManager() *CacheManager {
	cacheOnce.Do(func() {
		cacheInstance = NewCacheManager()
	})
	return cacheInstance
}

func (c *CacheManager) Get(domain string, recordType RecordType) (*DnsCacheEntry, bool) {
	key := buildCacheKey(domain, recordType)

	c.mu.RLock()
	entry, exists := c.cache[key]
	c.mu.RUnlock()

	if !exists {
		metrics.Inc("dns_cache_misses_total", map[string]string{"type": recordTypeToString(recordType)})
		return nil, false
	}

	if time.Now().After(entry.ExpiresAt) {
		c.deleteExpiredEntry(key, recordType)
		return nil, false
	}

	entry.HitCount++
	metrics.Inc("dns_cache_hits_total", map[string]string{"type": recordTypeToString(recordType)})
	return entry, true
}

func (c *CacheManager) deleteExpiredEntry(key string, recordType RecordType) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, exists := c.cache[key]; exists {
		delete(c.cache, key)
		metrics.Inc("dns_cache_expired_total", map[string]string{"type": recordTypeToString(recordType)})
	}
}

func (c *CacheManager) Put(domain string, recordType RecordType, records []string, ttl int64) {
	key := buildCacheKey(domain, recordType)

	entry := &DnsCacheEntry{
		ID:         "cache_" + domain + "_" + recordTypeToString(recordType),
		Domain:     domain,
		RecordType: recordType,
		RecordData: records,
		TTL:        ttl,
		ExpiresAt:  time.Now().Add(time.Duration(ttl) * time.Second),
		CreatedAt:  time.Now(),
		HitCount:   0,
	}

	c.mu.Lock()
	c.cache[key] = entry
	c.mu.Unlock()

	logger.Debug("", "DNS cache entry added", map[string]interface{}{
		"domain":      domain,
		"record_type": recordTypeToString(recordType),
		"ttl":         ttl,
	})
}

func (c *CacheManager) Invalidate(domain string, recordType RecordType) {
	key := buildCacheKey(domain, recordType)
	c.mu.Lock()
	delete(c.cache, key)
	c.mu.Unlock()
	metrics.Inc("dns_cache_invalidations_total", map[string]string{"type": recordTypeToString(recordType)})
	logger.Debug("", "DNS cache invalidated", map[string]interface{}{
		"domain":      domain,
		"record_type": recordTypeToString(recordType),
	})
}

func (c *CacheManager) CleanExpired() int {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	cleaned := 0
	for key, entry := range c.cache {
		if now.After(entry.ExpiresAt) {
			delete(c.cache, key)
			cleaned++
		}
	}

	if cleaned > 0 {
		metrics.Inc("dns_cache_cleaned_total", nil)
		logger.Info("", "Cleaned expired DNS cache entries", map[string]interface{}{
			"count": cleaned,
		})
	}
	return cleaned
}

func (c *CacheManager) GetStats() map[string]interface{} {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return map[string]interface{}{
		"total_entries": len(c.cache),
	}
}

func buildCacheKey(domain string, recordType RecordType) string {
	return domain + ":" + recordTypeToString(recordType)
}
