package processing

import (
	"container/list"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sync"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
)

type CacheKey string

type CacheEntry struct {
	Key        CacheKey
	Value      interface{}
	ExpiresAt  time.Time
	AccessedAt time.Time
	Size       int
}

type LRUCache struct {
	maxSize    int
	maxEntries int
	ttl        time.Duration
	items      map[CacheKey]*list.Element
	order      *list.List
	mu         sync.RWMutex
	stats      CacheStats
}

type CacheStats struct {
	Hits        int64 `json:"hits"`
	Misses      int64 `json:"misses"`
	Evictions   int64 `json:"evictions"`
	TotalSize   int   `json:"total_size"`
	EntryCount  int   `json:"entry_count"`
}

func NewLRUCache(maxSize int, maxEntries int, ttl time.Duration) *LRUCache {
	return &LRUCache{
		maxSize:    maxSize,
		maxEntries: maxEntries,
		ttl:        ttl,
		items:      make(map[CacheKey]*list.Element),
		order:      list.New(),
	}
}

func (c *LRUCache) Get(key CacheKey) (interface{}, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	elem, exists := c.items[key]
	if !exists {
		c.stats.Misses++
		return nil, false
	}

	entry := elem.Value.(*CacheEntry)

	if !entry.ExpiresAt.IsZero() && time.Now().After(entry.ExpiresAt) {
		c.stats.Misses++
		return nil, false
	}

	entry.AccessedAt = time.Now()
	c.order.MoveToFront(elem)
	c.stats.Hits++

	return entry.Value, true
}

func (c *LRUCache) Set(key CacheKey, value interface{}, size int) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, exists := c.items[key]; exists {
		entry := elem.Value.(*CacheEntry)
		c.stats.TotalSize -= entry.Size
		entry.Value = value
		entry.Size = size
		entry.AccessedAt = time.Now()
		if c.ttl > 0 {
			entry.ExpiresAt = time.Now().Add(c.ttl)
		}
		c.order.MoveToFront(elem)
		c.stats.TotalSize += size
		return
	}

	entry := &CacheEntry{
		Key:        key,
		Value:      value,
		AccessedAt: time.Now(),
		Size:       size,
	}
	if c.ttl > 0 {
		entry.ExpiresAt = time.Now().Add(c.ttl)
	}

	elem := c.order.PushFront(entry)
	c.items[key] = elem
	c.stats.TotalSize += size
	c.stats.EntryCount++

	c.evictIfNeeded()
}

func (c *LRUCache) Delete(key CacheKey) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, exists := c.items[key]; exists {
		entry := elem.Value.(*CacheEntry)
		c.stats.TotalSize -= entry.Size
		c.order.Remove(elem)
		delete(c.items, key)
		c.stats.EntryCount--
	}
}

func (c *LRUCache) evictIfNeeded() {
	for c.stats.EntryCount > c.maxEntries || (c.maxSize > 0 && c.stats.TotalSize > c.maxSize) {
		elem := c.order.Back()
		if elem == nil {
			break
		}

		entry := elem.Value.(*CacheEntry)
		c.stats.TotalSize -= entry.Size
		c.order.Remove(elem)
		delete(c.items, entry.Key)
		c.stats.EntryCount--
		c.stats.Evictions++
	}
}

func (c *LRUCache) CleanupExpired() int {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	expiredCount := 0

	for key, elem := range c.items {
		entry := elem.Value.(*CacheEntry)
		if !entry.ExpiresAt.IsZero() && now.After(entry.ExpiresAt) {
			c.stats.TotalSize -= entry.Size
			c.order.Remove(elem)
			delete(c.items, key)
			c.stats.EntryCount--
			expiredCount++
		}
	}

	return expiredCount
}

func (c *LRUCache) GetStats() CacheStats {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.stats
}

func (c *LRUCache) HitRate() float64 {
	stats := c.GetStats()
	total := stats.Hits + stats.Misses
	if total == 0 {
		return 0
	}
	return float64(stats.Hits) / float64(total)
}

func (c *LRUCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.items = make(map[CacheKey]*list.Element)
	c.order = list.New()
	c.stats = CacheStats{}
}

func GenerateCacheKey(data interface{}, ruleIDs []string, schemaName string) CacheKey {
	keyData := map[string]interface{}{
		"data":        data,
		"rule_ids":    ruleIDs,
		"schema_name": schemaName,
	}

	jsonData, _ := json.Marshal(keyData)
	hash := sha256.Sum256(jsonData)
	return CacheKey(hex.EncodeToString(hash[:]))
}

type CacheConfig struct {
	Enabled       bool          `json:"enabled"`
	MaxSizeMB     int           `json:"max_size_mb"`
	MaxEntries    int           `json:"max_entries"`
	TTLSeconds    int           `json:"ttl_seconds"`
	EnableWarmUp  bool          `json:"enable_warm_up"`
	WarmUpData    []interface{} `json:"-"`
}

type CachedProcessor struct {
	*DataProcessorImpl
	cache       *LRUCache
	cacheConfig CacheConfig
	logger      domain.Logger
	warmUpDone  bool
}

func NewCachedProcessor(
	baseProcessor *DataProcessorImpl,
	config CacheConfig,
	logger domain.Logger,
) *CachedProcessor {
	cp := &CachedProcessor{
		DataProcessorImpl: baseProcessor,
		cacheConfig:       config,
		logger:            logger,
	}

	if config.Enabled {
		maxSizeBytes := config.MaxSizeMB * 1024 * 1024
		ttl := time.Duration(config.TTLSeconds) * time.Second
		cp.cache = NewLRUCache(maxSizeBytes, config.MaxEntries, ttl)

		go cp.startCleanupLoop()
	}

	return cp
}

func (cp *CachedProcessor) startCleanupLoop() {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		if cp.cache != nil {
			expired := cp.cache.CleanupExpired()
			if expired > 0 {
				cp.logger.Debug("Cleaned up expired cache entries",
					domain.Int("expired_count", expired),
				)
			}
		}
	}
}

func (cp *CachedProcessor) WarmUp(ctx context.Context, warmUpData []interface{}) error {
	if !cp.cacheConfig.Enabled || cp.cache == nil {
		return nil
	}

	cp.logger.Info("Starting cache warm-up",
		domain.Int("data_count", len(warmUpData)),
	)

	for i, data := range warmUpData {
		rules := cp.getActiveRules()
		key := GenerateCacheKey(data, cp.getRuleIDs(rules), "")

		result, err := cp.DataProcessorImpl.Process(ctx, data, rules)
		if err != nil {
			cp.logger.Warn("Cache warm-up failed for item",
				domain.Int("index", i),
				domain.Error(err),
			)
			continue
		}

		size := estimateSize(result)
		cp.cache.Set(key, result, size)
	}

	cp.warmUpDone = true
	cp.logger.Info("Cache warm-up completed",
		domain.Int("entry_count", cp.cache.GetStats().EntryCount),
	)

	return nil
}

func (cp *CachedProcessor) Process(ctx context.Context, payload interface{}, rules []*TransformRule) (interface{}, error) {
	if !cp.cacheConfig.Enabled || cp.cache == nil {
		return cp.DataProcessorImpl.Process(ctx, payload, rules)
	}

	ruleIDs := make([]string, 0, len(rules))
	for _, r := range rules {
		ruleIDs = append(ruleIDs, r.ID)
	}

	key := GenerateCacheKey(payload, ruleIDs, "")

	if cached, exists := cp.cache.Get(key); exists {
		cp.logger.Debug("Cache hit",
			domain.String("key", string(key)[:16]+"..."),
		)
		return cached, nil
	}

	result, err := cp.DataProcessorImpl.Process(ctx, payload, rules)
	if err != nil {
		return nil, err
	}

	size := estimateSize(result)
	cp.cache.Set(key, result, size)

	cp.logger.Debug("Cache miss, stored result",
		domain.String("key", string(key)[:16]+"..."),
		domain.Int("size_bytes", size),
	)

	return result, nil
}

func (cp *CachedProcessor) ExecuteHandler(ctx context.Context, req *ProcessRequest) (*ProcessResult, error) {
	if !cp.cacheConfig.Enabled || cp.cache == nil {
		return cp.DataProcessorImpl.ExecuteHandler(ctx, req)
	}

	start := time.Now()

	var rules []*TransformRule
	for _, id := range req.RuleIDs {
		if rule, exists := cp.rules[id]; exists {
			rules = append(rules, rule)
		}
	}

	key := GenerateCacheKey(req.Payload, req.RuleIDs, req.SchemaName)

	if cached, exists := cp.cache.Get(key); exists {
		cp.logger.Debug("Cache hit for request",
			domain.String("trace_id", req.TraceID),
		)
		return &ProcessResult{
			TraceID:    req.TraceID,
			Output:     cached,
			DurationMs: time.Since(start).Milliseconds(),
		}, nil
	}

	result, err := cp.Process(ctx, req.Payload, rules)
	if err != nil {
		return nil, err
	}

	if req.SchemaName != "" {
		if schema, exists := cp.schemas[req.SchemaName]; exists {
			result, err = cp.Normalize(ctx, result, schema)
			if err != nil {
				return nil, err
			}
		}
	}

	size := estimateSize(result)
	cp.cache.Set(key, result, size)

	return &ProcessResult{
		TraceID:    req.TraceID,
		Output:     result,
		DurationMs: time.Since(start).Milliseconds(),
	}, nil
}

func (cp *CachedProcessor) GetCacheStats() CacheStats {
	if cp.cache == nil {
		return CacheStats{}
	}
	return cp.cache.GetStats()
}

func (cp *CachedProcessor) GetHitRate() float64 {
	if cp.cache == nil {
		return 0
	}
	return cp.cache.HitRate()
}

func (cp *CachedProcessor) InvalidateCache() {
	if cp.cache != nil {
		cp.cache.Clear()
		cp.logger.Info("Cache invalidated")
	}
}

func (cp *CachedProcessor) getActiveRules() []*TransformRule {
	rules := make([]*TransformRule, 0, len(cp.rules))
	for _, r := range cp.rules {
		if r.Enabled {
			rules = append(rules, r)
		}
	}
	return rules
}

func (cp *CachedProcessor) getRuleIDs(rules []*TransformRule) []string {
	ids := make([]string, 0, len(rules))
	for _, r := range rules {
		ids = append(ids, r.ID)
	}
	return ids
}

func estimateSize(v interface{}) int {
	data, err := json.Marshal(v)
	if err != nil {
		return 1024
	}
	return len(data)
}
