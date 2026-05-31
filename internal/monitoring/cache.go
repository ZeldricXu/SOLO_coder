package monitoring

import (
	"context"
	"sync"
	"time"
)

type MetricsCache struct {
	mu         sync.RWMutex
	cache      map[string]cacheEntry
	hotMetrics map[string]struct{}
	ttl        time.Duration
	maxSize    int
}

type cacheEntry struct {
	Value     float64
	Timestamp time.Time
	Accesses  int64
}

func NewMetricsCache(ttl time.Duration, maxSize int) *MetricsCache {
	if ttl <= 0 {
		ttl = 5 * time.Minute
	}
	if maxSize <= 0 {
		maxSize = 10000
	}
	cache := &MetricsCache{
		cache:      make(map[string]cacheEntry),
		hotMetrics: make(map[string]struct{}),
		ttl:        ttl,
		maxSize:    maxSize,
	}
	return cache
}

func (c *MetricsCache) Set(name string, value float64) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if len(c.cache) >= c.maxSize {
		c.evictLRU()
	}

	entry := cacheEntry{
		Value:     value,
		Timestamp: time.Now(),
		Accesses:  1,
	}
	c.cache[name] = entry
}

func (c *MetricsCache) Get(name string) (float64, bool) {
	c.mu.RLock()
	entry, ok := c.cache[name]
	if !ok {
		c.mu.RUnlock()
		return 0, false
	}

	if time.Since(entry.Timestamp) > c.ttl {
		c.mu.RUnlock()
		c.mu.Lock()
		delete(c.cache, name)
		c.mu.Unlock()
		return 0, false
	}

	c.mu.RUnlock()
	c.mu.Lock()
	entry.Accesses++
	c.cache[name] = entry
	if entry.Accesses >= 10 {
		c.hotMetrics[name] = struct{}{}
	}
	c.mu.Unlock()

	return entry.Value, true
}

func (c *MetricsCache) Invalidate(name string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.cache, name)
	delete(c.hotMetrics, name)
}

func (c *MetricsCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.cache = make(map[string]cacheEntry)
	c.hotMetrics = make(map[string]struct{})
}

func (c *MetricsCache) evictLRU() {
	var oldestKey string
	var oldestTime time.Time
	for k, v := range c.cache {
		if oldestKey == "" || v.Timestamp.Before(oldestTime) {
			oldestKey = k
			oldestTime = v.Timestamp
		}
	}
	if oldestKey != "" {
		delete(c.cache, oldestKey)
		delete(c.hotMetrics, oldestKey)
	}
}

func (c *MetricsCache) HotMetrics() []string {
	c.mu.RLock()
	defer c.mu.RUnlock()
	result := make([]string, 0, len(c.hotMetrics))
	for k := range c.hotMetrics {
		result = append(result, k)
	}
	return result
}

type CacheWarmer interface {
	WarmUp(ctx context.Context, metrics []string) error
}

type SimpleWarmer struct {
	source func(string) (float64, error)
}

func NewSimpleWarmer(source func(string) (float64, error)) *SimpleWarmer {
	return &SimpleWarmer{source: source}
}

func (w *SimpleWarmer) WarmUp(ctx context.Context, metrics []string) error {
	for _, name := range metrics {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
			if value, err := w.source(name); err == nil {
				_ = value
			}
		}
	}
	return nil
}

type AlertManagerWithCache struct {
	*AlertManager
	cache       *MetricsCache
	warmer      CacheWarmer
	warmedUp    bool
	warmUpMutex sync.Once
}

func NewAlertManagerWithCache(cache *MetricsCache, warmer CacheWarmer, notifiers ...Notifier) *AlertManagerWithCache {
	if cache == nil {
		cache = NewMetricsCache(5*time.Minute, 10000)
	}
	return &AlertManagerWithCache{
		AlertManager: NewAlertManager(notifiers...),
		cache:        cache,
		warmer:       warmer,
	}
}

func (a *AlertManagerWithCache) RecordMetric(name string, value float64) {
	a.cache.Set(name, value)
	a.AlertManager.RecordMetric(name, value)
}

func (a *AlertManagerWithCache) GetMetric(name string) (float64, bool) {
	if value, ok := a.cache.Get(name); ok {
		return value, true
	}
	return a.AlertManager.GetMetric(name)
}

func (a *AlertManagerWithCache) WarmUp(ctx context.Context, hotMetrics []string) error {
	var err error
	a.warmUpMutex.Do(func() {
		if a.warmer != nil {
			err = a.warmer.WarmUp(ctx, hotMetrics)
		}
		a.warmedUp = true
	})
	return err
}

func (a *AlertManagerWithCache) IsWarmedUp() bool {
	return a.warmedUp
}

func (a *AlertManagerWithCache) InvalidateCache(metrics ...string) {
	if len(metrics) == 0 {
		a.cache.Clear()
		return
	}
	for _, name := range metrics {
		a.cache.Invalidate(name)
	}
}

func (a *AlertManagerWithCache) CacheStats() map[string]interface{} {
	hotMetrics := a.cache.HotMetrics()
	return map[string]interface{}{
		"hot_metrics_count": len(hotMetrics),
		"hot_metrics":       hotMetrics,
		"warmed_up":         a.warmedUp,
	}
}
