package worker

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestLRUCache_BasicOperations(t *testing.T) {
	config := CacheConfig{
		MaxSize: 100,
		TTL:     1 * time.Hour,
	}
	cache := NewLocalCache(config)

	err := cache.Set("key1", "value1")
	require.NoError(t, err)
	assert.Equal(t, 1, cache.Len())

	val, ok := cache.Get("key1")
	assert.True(t, ok, "key1 should exist")
	assert.Equal(t, "value1", val)

	err = cache.Set("key2", 42)
	require.NoError(t, err)
	assert.Equal(t, 2, cache.Len())

	val, ok = cache.Get("key2")
	assert.True(t, ok)
	assert.Equal(t, 42, val)

	_, ok = cache.Get("nonexistent")
	assert.False(t, ok, "nonexistent key should not exist")

	deleted := cache.Delete("key1")
	assert.True(t, deleted)
	assert.Equal(t, 1, cache.Len())

	_, ok = cache.Get("key1")
	assert.False(t, ok, "deleted key should not exist")

	deleted = cache.Delete("nonexistent")
	assert.False(t, deleted)
}

func TestLRUCache_Eviction_OldestRemoved(t *testing.T) {
	config := CacheConfig{
		MaxSize: 3,
		TTL:     1 * time.Hour,
	}
	cache := NewLocalCache(config)

	err := cache.Set("a", 1)
	require.NoError(t, err)
	err = cache.Set("b", 2)
	require.NoError(t, err)
	err = cache.Set("c", 3)
	require.NoError(t, err)
	assert.Equal(t, 3, cache.Len())

	_, ok := cache.Get("a")
	assert.True(t, ok)

	err = cache.Set("d", 4)
	require.NoError(t, err)
	assert.Equal(t, 3, cache.Len())

	_, ok = cache.Get("b")
	assert.False(t, ok, "b should be evicted (oldest unaccessed)")

	_, ok = cache.Get("a")
	assert.True(t, ok, "a should still exist")

	_, ok = cache.Get("c")
	assert.True(t, ok, "c should still exist")

	_, ok = cache.Get("d")
	assert.True(t, ok, "d should exist")
}

func TestLRUCache_HitRateStats(t *testing.T) {
	config := CacheConfig{
		MaxSize: 10,
		TTL:     1 * time.Hour,
	}
	cache := NewLocalCache(config)

	err := cache.Set("a", 1)
	require.NoError(t, err)
	err = cache.Set("b", 2)
	require.NoError(t, err)

	cache.Get("a")
	cache.Get("a")
	cache.Get("b")
	cache.Get("nonexistent")
	cache.Get("nonexistent")

	stats := cache.GetStats()
	assert.Equal(t, int64(3), stats.Hits, "should have 3 hits")
	assert.Equal(t, int64(2), stats.Misses, "should have 2 misses")
	assert.InDelta(t, 0.6, stats.HitRate(), 1e-9, "hit rate should be 3/5 = 0.6")

	cache.ResetStats()
	stats = cache.GetStats()
	assert.Equal(t, int64(0), stats.Hits)
	assert.Equal(t, int64(0), stats.Misses)
	assert.Equal(t, 0.0, stats.HitRate())
}

func TestLRUCache_PersistAndReload(t *testing.T) {
	tmpDir := t.TempDir()
	persistPath := filepath.Join(tmpDir, "cache.gob")

	config := CacheConfig{
		MaxSize:         10,
		TTL:             1 * time.Hour,
		PersistPath:     persistPath,
		PersistInterval: 1 * time.Hour,
	}
	cache := NewLocalCache(config)

	ctx := context.Background()
	err := cache.Start(ctx)
	require.NoError(t, err)

	err = cache.Set("persist1", "hello")
	require.NoError(t, err)
	err = cache.Set("persist2", 123.45)
	require.NoError(t, err)
	err = cache.Set("persist3", []int{1, 2, 3})
	require.NoError(t, err)

	err = cache.Stop()
	require.NoError(t, err)

	assert.FileExists(t, persistPath, "cache should be persisted to disk")

	newCache := NewLocalCache(config)
	err = newCache.Start(ctx)
	require.NoError(t, err)
	defer func() {
		_ = newCache.Stop()
	}()

	val, ok := newCache.Get("persist1")
	assert.True(t, ok, "persist1 should be loaded")
	assert.Equal(t, "hello", val)

	val, ok = newCache.Get("persist2")
	assert.True(t, ok, "persist2 should be loaded")
	assert.InDelta(t, 123.45, val.(float64), 1e-9)

	assert.Equal(t, 3, newCache.Len(), "should have 3 entries after reload")
}

func TestLRUCache_SetOverwrite(t *testing.T) {
	config := CacheConfig{
		MaxSize: 10,
		TTL:     1 * time.Hour,
	}
	cache := NewLocalCache(config)

	err := cache.Set("key", "original")
	require.NoError(t, err)

	err = cache.Set("key", "updated")
	require.NoError(t, err)
	assert.Equal(t, 1, cache.Len())

	val, ok := cache.Get("key")
	assert.True(t, ok)
	assert.Equal(t, "updated", val)
}

func TestLRUCache_TTLExpiry(t *testing.T) {
	config := CacheConfig{
		MaxSize: 10,
		TTL:     50 * time.Millisecond,
	}
	cache := NewLocalCache(config)

	err := cache.SetWithTTL("ttl_key", "temp_value", 50*time.Millisecond)
	require.NoError(t, err)

	val, ok := cache.Get("ttl_key")
	assert.True(t, ok)
	assert.Equal(t, "temp_value", val)

	time.Sleep(100 * time.Millisecond)

	_, ok = cache.Get("ttl_key")
	assert.False(t, ok, "expired key should not be found")
}

func TestLRUCache_Has(t *testing.T) {
	config := CacheConfig{
		MaxSize: 10,
		TTL:     1 * time.Hour,
	}
	cache := NewLocalCache(config)

	assert.False(t, cache.Has("missing"))

	err := cache.Set("present", 1)
	require.NoError(t, err)
	assert.True(t, cache.Has("present"))
}

func TestLRUCache_Clear(t *testing.T) {
	config := CacheConfig{
		MaxSize: 10,
		TTL:     1 * time.Hour,
	}
	cache := NewLocalCache(config)

	for i := 0; i < 5; i++ {
		key := string(rune('a' + i))
		_ = cache.Set(key, i)
	}
	assert.Equal(t, 5, cache.Len())

	cache.Clear()
	assert.Equal(t, 0, cache.Len())
}

func TestLRUCache_Keys(t *testing.T) {
	config := CacheConfig{
		MaxSize: 10,
		TTL:     1 * time.Hour,
	}
	cache := NewLocalCache(config)

	_ = cache.Set("k1", 1)
	_ = cache.Set("k2", 2)
	_ = cache.Set("k3", 3)

	keys := cache.Keys()
	assert.Len(t, keys, 3)
	assert.Contains(t, keys, "k1")
	assert.Contains(t, keys, "k2")
	assert.Contains(t, keys, "k3")
}

func TestLRUCache_GenerateCacheKey(t *testing.T) {
	config := CacheConfig{MaxSize: 10}
	cache := NewLocalCache(config)

	key1 := cache.GenerateCacheKey("task1", map[string]float64{"a": 1.0})
	key2 := cache.GenerateCacheKey("task1", map[string]float64{"a": 1.0})
	key3 := cache.GenerateCacheKey("task2", map[string]float64{"a": 1.0})

	assert.Equal(t, key1, key2, "same inputs should generate same key")
	assert.NotEqual(t, key1, key3, "different inputs should generate different keys")
	assert.NotEmpty(t, key1)
}

func TestLRUCache_GetOrCompute(t *testing.T) {
	config := CacheConfig{
		MaxSize: 10,
		TTL:     1 * time.Hour,
	}
	cache := NewLocalCache(config)

	callCount := 0
	computeFn := func() (interface{}, error) {
		callCount++
		return "computed_value", nil
	}

	val, err := cache.GetOrCompute("compute_key", computeFn)
	require.NoError(t, err)
	assert.Equal(t, "computed_value", val)
	assert.Equal(t, 1, callCount)

	val, err = cache.GetOrCompute("compute_key", computeFn)
	require.NoError(t, err)
	assert.Equal(t, "computed_value", val)
	assert.Equal(t, 1, callCount, "should not recompute on cache hit")
}

func TestLRUCache_DefaultConfig(t *testing.T) {
	config := CacheConfig{}
	cache := NewLocalCache(config)

	assert.Equal(t, 10000, cache.config.MaxSize)
	assert.Equal(t, 24*time.Hour, cache.config.TTL)
	assert.Equal(t, 5*time.Minute, cache.config.PersistInterval)
}

func TestCacheStats_HitRateEmpty(t *testing.T) {
	stats := CacheStats{}
	assert.Equal(t, 0.0, stats.HitRate())
}
