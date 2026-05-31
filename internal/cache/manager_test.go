package cache

import (
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"taskflow/internal/testutils"
	"taskflow/pkg/models"
)

func TestLRUCache_BasicGetSet(t *testing.T) {
	cache := NewLRUCache(100, time.Hour)

	cache.Set("key1", "value1", 0)
	value, exists := cache.Get("key1")

	assert.True(t, exists)
	assert.Equal(t, "value1", value)
}

func TestLRUCache_MissingKey(t *testing.T) {
	cache := NewLRUCache(100, time.Hour)

	_, exists := cache.Get("nonexistent")
	assert.False(t, exists)
}

func TestLRUCache_Overwrite(t *testing.T) {
	cache := NewLRUCache(100, time.Hour)

	cache.Set("key1", "value1", 0)
	cache.Set("key1", "value2", 0)

	value, exists := cache.Get("key1")
	assert.True(t, exists)
	assert.Equal(t, "value2", value)
}

func TestLRUCache_Eviction(t *testing.T) {
	maxSize := 5
	cache := NewLRUCache(maxSize, time.Hour)

	for i := 0; i < 10; i++ {
		key := testutils.NewTestDataFactory().CreateRandomString(8)
		cache.Set(key, i, 0)
	}

	stats := cache.GetStats()
	assert.Equal(t, maxSize, stats.Size)
}

func TestLRUCache_LRUOrder(t *testing.T) {
	cache := NewLRUCache(3, time.Hour)

	cache.Set("a", 1, 0)
	cache.Set("b", 2, 0)
	cache.Set("c", 3, 0)

	cache.Get("a")
	cache.Set("d", 4, 0)

	_, existsA := cache.Get("a")
	_, existsB := cache.Get("b")

	assert.True(t, existsA, "'a' should be retained as it was recently accessed")
	assert.False(t, existsB, "'b' should be evicted as it's least recently used")
}

func TestLRUCache_TTLExpiration(t *testing.T) {
	cache := NewLRUCache(100, time.Millisecond*50)

	cache.Set("key1", "value1", time.Millisecond*50)

	_, exists := cache.Get("key1")
	assert.True(t, exists)

	time.Sleep(time.Millisecond * 100)

	_, existsAfter := cache.Get("key1")
	assert.False(t, existsAfter, "Expired key should be removed")
}

func TestLRUCache_Delete(t *testing.T) {
	cache := NewLRUCache(100, time.Hour)

	cache.Set("key1", "value1", 0)
	deleted := cache.Delete("key1")

	assert.True(t, deleted)
	_, exists := cache.Get("key1")
	assert.False(t, exists)
}

func TestLRUCache_DeleteMissing(t *testing.T) {
	cache := NewLRUCache(100, time.Hour)
	deleted := cache.Delete("nonexistent")
	assert.False(t, deleted)
}

func TestLRUCache_TagInvalidation(t *testing.T) {
	cache := NewLRUCache(100, time.Hour)

	cache.SetWithTags("user:1", "data1", 0, []string{"users", "user:1"})
	cache.SetWithTags("user:2", "data2", 0, []string{"users", "user:2"})
	cache.SetWithTags("config", "data3", 0, []string{"configs"})

	cache.InvalidateTag("users")

	_, exists1 := cache.Get("user:1")
	_, exists2 := cache.Get("user:2")
	_, existsConfig := cache.Get("config")

	assert.False(t, exists1)
	assert.False(t, exists2)
	assert.True(t, existsConfig)
}

func TestLRUCache_PatternInvalidation(t *testing.T) {
	cache := NewLRUCache(100, time.Hour)

	cache.Set("user:1", "data1", 0)
	cache.Set("user:2", "data2", 0)
	cache.Set("config:main", "data3", 0)

	cache.InvalidatePattern("^user:.*")

	_, exists1 := cache.Get("user:1")
	_, exists2 := cache.Get("user:2")
	_, existsConfig := cache.Get("config:main")

	assert.False(t, exists1)
	assert.False(t, exists2)
	assert.True(t, existsConfig)
}

func TestLRUCache_Clear(t *testing.T) {
	cache := NewLRUCache(100, time.Hour)
	factory := testutils.NewTestDataFactory()
	entries := factory.CreateCacheEntries(50)

	for _, e := range entries {
		cache.Set(e.Key, e.Value, 0)
	}

	cache.Clear()
	stats := cache.GetStats()
	assert.Equal(t, 0, stats.Size)
	assert.Equal(t, 0, stats.TotalHits)
}

func TestLRUCache_GetStats(t *testing.T) {
	maxSize := 100
	defaultTTL := time.Hour
	cache := NewLRUCache(maxSize, defaultTTL)

	cache.Set("key1", "value1", 0)
	cache.Get("key1")
	cache.Get("key1")

	stats := cache.GetStats()
	assert.Equal(t, 1, stats.Size)
	assert.Equal(t, maxSize, stats.MaxSize)
	assert.Equal(t, models.CacheStrategyLRU, stats.Strategy)
	assert.Equal(t, int(defaultTTL.Seconds()), stats.DefaultTTL)
	assert.Equal(t, 2, stats.TotalHits)
}

func TestLRUCache_ConcurrentAccess(t *testing.T) {
	cache := NewLRUCache(1000, time.Hour)
	factory := testutils.NewTestDataFactory()
	entries := factory.CreateCacheEntries(100)

	var wg sync.WaitGroup

	for _, e := range entries {
		wg.Add(1)
		go func(key string, value interface{}) {
			defer wg.Done()
			cache.Set(key, value, 0)
		}(e.Key, e.Value)
	}

	for _, e := range entries {
		wg.Add(1)
		go func(key string) {
			defer wg.Done()
			cache.Get(key)
		}(e.Key)
	}

	wg.Wait()
	stats := cache.GetStats()
	assert.Equal(t, len(entries), stats.Size)
}

func TestLRUCache_ConcurrentReadWrite(t *testing.T) {
	cache := NewLRUCache(100, time.Hour)
	done := make(chan bool)

	go func() {
		for i := 0; i < 1000; i++ {
			key := testutils.NewTestDataFactory().CreateRandomString(8)
			cache.Set(key, i, 0)
		}
		done <- true
	}()

	go func() {
		for i := 0; i < 1000; i++ {
			key := testutils.NewTestDataFactory().CreateRandomString(8)
			cache.Get(key)
		}
		done <- true
	}()

	<-done
	<-done
}

func TestLFUCache_BasicGetSet(t *testing.T) {
	cache := NewLFUCache(100, time.Hour)

	cache.Set("key1", "value1", 0)
	value, exists := cache.Get("key1")

	assert.True(t, exists)
	assert.Equal(t, "value1", value)
}

func TestLFUCache_Eviction(t *testing.T) {
	cache := NewLFUCache(3, time.Hour)

	cache.Set("a", 1, 0)
	cache.Set("b", 2, 0)
	cache.Set("c", 3, 0)

	cache.Get("a")
	cache.Get("a")
	cache.Get("b")

	cache.Set("d", 4, 0)

	_, existsC := cache.Get("c")
	assert.False(t, existsC, "'c' should be evicted (least frequently used)")

	_, existsA := cache.Get("a")
	assert.True(t, existsA, "'a' should be retained")
}

func TestLFUCache_TagInvalidation(t *testing.T) {
	cache := NewLFUCache(100, time.Hour)

	cache.SetWithTags("key1", "value1", 0, []string{"tag1"})
	cache.SetWithTags("key2", "value2", 0, []string{"tag2"})

	cache.InvalidateTag("tag1")

	_, exists1 := cache.Get("key1")
	_, exists2 := cache.Get("key2")

	assert.False(t, exists1)
	assert.True(t, exists2)
}

func TestLFUCache_Clear(t *testing.T) {
	cache := NewLFUCache(100, time.Hour)

	cache.Set("key1", "value1", 0)
	cache.Set("key2", "value2", 0)

	cache.Clear()
	stats := cache.GetStats()
	assert.Equal(t, 0, stats.Size)
}

func TestFIFOCache_BasicGetSet(t *testing.T) {
	cache := NewFIFOCache(100, time.Hour)

	cache.Set("key1", "value1", 0)
	value, exists := cache.Get("key1")

	assert.True(t, exists)
	assert.Equal(t, "value1", value)
}

func TestFIFOCache_Eviction(t *testing.T) {
	cache := NewFIFOCache(3, time.Hour)

	cache.Set("a", 1, 0)
	cache.Set("b", 2, 0)
	cache.Set("c", 3, 0)

	cache.Get("a")
	cache.Get("a")
	cache.Get("b")

	cache.Set("d", 4, 0)

	_, existsA := cache.Get("a")
	assert.False(t, existsA, "'a' should be evicted (first in)")
}

func TestFIFOCache_HitsNotAffectingOrder(t *testing.T) {
	cache := NewFIFOCache(3, time.Hour)

	cache.Set("a", 1, 0)
	cache.Set("b", 2, 0)
	cache.Set("c", 3, 0)

	cache.Get("a")
	cache.Get("a")
	cache.Get("a")

	cache.Set("d", 4, 0)

	_, existsA := cache.Get("a")
	assert.False(t, existsA, "'a' should still be evicted first in FIFO despite most hits")
}

func TestFIFOCache_TTLExpiration(t *testing.T) {
	cache := NewFIFOCache(100, time.Millisecond*50)

	cache.Set("key1", "value1", time.Millisecond*50)
	time.Sleep(time.Millisecond * 100)

	_, exists := cache.Get("key1")
	assert.False(t, exists)
}

func TestFIFOCache_TagAndPattern(t *testing.T) {
	cache := NewFIFOCache(100, time.Hour)

	cache.SetWithTags("session:1", "data1", 0, []string{"sessions"})
	cache.SetWithTags("session:2", "data2", 0, []string{"sessions"})
	cache.SetWithTags("user:1", "data3", 0, []string{"users"})

	cache.InvalidatePattern("^session:.*")

	_, exists1 := cache.Get("session:1")
	_, exists2 := cache.Get("session:2")
	_, existsUser := cache.Get("user:1")

	assert.False(t, exists1)
	assert.False(t, exists2)
	assert.True(t, existsUser)
}

func TestCacheManagerFactory_Create(t *testing.T) {
	factory := &CacheManagerFactory{}

	lru, err := factory.Create(models.CacheStrategyLRU, 100, time.Hour)
	require.NoError(t, err)
	assert.NotNil(t, lru)
	assert.Equal(t, models.CacheStrategyLRU, lru.GetStats().Strategy)

	lfu, err := factory.Create(models.CacheStrategyLFU, 100, time.Hour)
	require.NoError(t, err)
	assert.NotNil(t, lfu)
	assert.Equal(t, models.CacheStrategyLFU, lfu.GetStats().Strategy)

	fifo, err := factory.Create(models.CacheStrategyFIFO, 100, time.Hour)
	require.NoError(t, err)
	assert.NotNil(t, fifo)
	assert.Equal(t, models.CacheStrategyFIFO, fifo.GetStats().Strategy)

	_, err = factory.Create("invalid", 100, time.Hour)
	assert.Error(t, err)
}

func TestGetCache_Singleton(t *testing.T) {
	ResetCache()
	cache1 := GetCache()
	cache2 := GetCache()

	assert.Same(t, cache1, cache2)
}

func TestSetCacheInstance(t *testing.T) {
	customCache := NewLRUCache(50, time.Minute)
	SetCacheInstance(customCache)

	cache := GetCache()
	assert.Equal(t, 50, cache.GetStats().MaxSize)
}

func TestLRUCache_ResourceRelease(t *testing.T) {
	cache := NewLRUCache(10, time.Hour)

	for i := 0; i < 100; i++ {
		key := testutils.NewTestDataFactory().CreateRandomString(8)
		cache.Set(key, make([]byte, 1024*1024), 0)
	}

	stats := cache.GetStats()
	assert.Equal(t, 10, stats.Size)
}

func TestLRUCache_MultipleInvalidations(t *testing.T) {
	cache := NewLRUCache(100, time.Hour)

	for i := 0; i < 10; i++ {
		key := testutils.NewTestDataFactory().CreateRandomString(8)
		cache.SetWithTags(key, i, 0, []string{"group1", "group2"})
	}

	cache.InvalidateTag("group1")
	stats := cache.GetStats()
	assert.Equal(t, 0, stats.Size)

	cache.InvalidateTag("group2")
	stats2 := cache.GetStats()
	assert.Equal(t, 0, stats2.Size)
}
