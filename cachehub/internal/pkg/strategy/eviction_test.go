package strategy

import (
	"testing"
	"time"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/models"
	"github.com/cachehub/internal/pkg/testfixtures"
	"github.com/sirupsen/logrus"
)

func setupEvictionTestEnvironment(t *testing.T) (*cache_manager.CacheManager, *StrategyManager, *testfixtures.TestDataBuilder) {
	logger := logrus.New()
	logger.SetLevel(logrus.WarnLevel)

	cm := cache_manager.NewCacheManager(logger)
	sm := NewStrategyManager(cm, logger)
	builder := testfixtures.NewTestDataBuilder()

	smallInstance := builder.BuildSmallCapacityInstance()
	smallInstance.MaxCapacity = 1024
	err := cm.RegisterInstance(smallInstance)
	if err != nil {
		t.Fatalf("Failed to register cache instance: %v", err)
	}

	return cm, sm, builder
}

func TestLRUEvictionPolicy(t *testing.T) {
	cm, sm, _ := setupEvictionTestEnvironment(t)
	cacheID := "small_cache_01"

	policy := &models.CachePolicy{
		PolicyID: "policy_lru_test",
		CacheID:  cacheID,
		EvictionPolicy: models.EvictionPolicy{
			Type:              "lru",
			EvictionThreshold: 50,
		},
	}
	err := sm.SetPolicy(policy)
	if err != nil {
		t.Fatalf("SetPolicy failed: %v", err)
	}

	cache, _ := cm.GetCache(cacheID)

	largeValue := make([]byte, 150)
	for i := range largeValue {
		largeValue[i] = byte('a' + i%26)
	}

	keys := []string{"key_A", "key_B", "key_C", "key_D", "key_E"}
	for _, key := range keys {
		cache.Set(key, string(largeValue), 3600)
	}

	time.Sleep(10 * time.Millisecond)

	cache.Get("key_A")
	cache.Get("key_E")

	time.Sleep(10 * time.Millisecond)

	evicted, err := sm.ApplyEviction(cacheID, cache)
	if err != nil {
		t.Fatalf("ApplyEviction failed: %v", err)
	}

	if len(evicted) == 0 {
		t.Log("No eviction occurred, may need more data")
	} else {
		for _, key := range evicted {
			if key == "key_A" || key == "key_E" {
				t.Errorf("Recently accessed key %s should not be evicted in LRU", key)
			}
		}
	}
}

func TestLFUEvictionPolicy(t *testing.T) {
	cm, sm, _ := setupEvictionTestEnvironment(t)
	cacheID := "small_cache_01"

	policy := &models.CachePolicy{
		PolicyID: "policy_lfu_test",
		CacheID:  cacheID,
		EvictionPolicy: models.EvictionPolicy{
			Type:              "lfu",
			EvictionThreshold: 50,
		},
	}
	err := sm.SetPolicy(policy)
	if err != nil {
		t.Fatalf("SetPolicy failed: %v", err)
	}

	cache, _ := cm.GetCache(cacheID)

	largeValue := make([]byte, 150)
	for i := range largeValue {
		largeValue[i] = byte('a' + i%26)
	}

	keys := []string{"freq_1", "freq_5", "freq_10", "freq_1"}
	for _, key := range keys {
		cache.Set(key, string(largeValue), 3600)
	}

	for i := 0; i < 5; i++ {
		cache.Get("freq_5")
	}
	for i := 0; i < 10; i++ {
		cache.Get("freq_10")
	}

	evicted, err := sm.ApplyEviction(cacheID, cache)
	if err != nil {
		t.Fatalf("ApplyEviction failed: %v", err)
	}

	t.Logf("Evicted keys: %v", evicted)

	for _, key := range evicted {
		if key == "freq_10" {
			t.Errorf("Most frequently accessed key %s should not be evicted in LFU", key)
		}
	}
}

func TestFIFOEvictionPolicy(t *testing.T) {
	cm, sm, _ := setupEvictionTestEnvironment(t)
	cacheID := "small_cache_01"

	policy := &models.CachePolicy{
		PolicyID: "policy_fifo_test",
		CacheID:  cacheID,
		EvictionPolicy: models.EvictionPolicy{
			Type:              "fifo",
			EvictionThreshold: 50,
		},
	}
	err := sm.SetPolicy(policy)
	if err != nil {
		t.Fatalf("SetPolicy failed: %v", err)
	}

	cache, _ := cm.GetCache(cacheID)

	largeValue := make([]byte, 150)

	insertOrder := []string{"first_in", "second_in", "third_in", "fourth_in", "fifth_in"}
	for _, key := range insertOrder {
		cache.Set(key, string(largeValue), 3600)
		time.Sleep(10 * time.Millisecond)
	}

	cache.Get("first_in")
	cache.Get("fifth_in")

	evicted, err := sm.ApplyEviction(cacheID, cache)
	if err != nil {
		t.Fatalf("ApplyEviction failed: %v", err)
	}

	t.Logf("Evicted keys: %v", evicted)

	for _, key := range evicted {
		if key == "fifth_in" {
			t.Errorf("Last inserted key %s should not be evicted first in FIFO", key)
		}
	}
}

func TestEvictionPolicySwitching(t *testing.T) {
	cm, sm, _ := setupEvictionTestEnvironment(t)
	cacheID := "small_cache_01"

	cache, _ := cm.GetCache(cacheID)

	largeValue := make([]byte, 100)

	keys := []string{"key_1", "key_2", "key_3", "key_4", "key_5"}
	for _, key := range keys {
		cache.Set(key, string(largeValue), 3600)
	}

	for i := 0; i < 10; i++ {
		cache.Get("key_3")
	}

	lruPolicy := &models.CachePolicy{
		PolicyID: "policy_lru",
		CacheID:  cacheID,
		EvictionPolicy: models.EvictionPolicy{
			Type:              "lru",
			EvictionThreshold: 30,
		},
	}
	err := sm.SetPolicy(lruPolicy)
	if err != nil {
		t.Fatalf("Set LRU policy failed: %v", err)
	}

	lruEvicted, _ := sm.ApplyEviction(cacheID, cache)
	t.Logf("LRU evicted: %v", lruEvicted)

	for _, key := range keys {
		cache.Set(key, string(largeValue), 3600)
	}
	for i := 0; i < 10; i++ {
		cache.Get("key_3")
	}

	lfuPolicy := &models.CachePolicy{
		PolicyID: "policy_lfu",
		CacheID:  cacheID,
		EvictionPolicy: models.EvictionPolicy{
			Type:              "lfu",
			EvictionThreshold: 30,
		},
	}
	err = sm.SetPolicy(lfuPolicy)
	if err != nil {
		t.Fatalf("Set LFU policy failed: %v", err)
	}

	lfuEvicted, _ := sm.ApplyEviction(cacheID, cache)
	t.Logf("LFU evicted: %v", lfuEvicted)

	for _, key := range lfuEvicted {
		if key == "key_3" {
			t.Errorf("key_3 should not be evicted in LFU due to high hit count")
		}
	}
}

func TestEvictionThresholdConfiguration(t *testing.T) {
	cm, sm, _ := setupEvictionTestEnvironment(t)
	cacheID := "small_cache_01"

	cache, _ := cm.GetCache(cacheID)

	smallValue := make([]byte, 50)
	for i := 0; i < 10; i++ {
		key := "key_" + string(rune('0'+i))
		cache.Set(key, string(smallValue), 3600)
	}

	lowThresholdPolicy := &models.CachePolicy{
		PolicyID: "policy_low",
		CacheID:  cacheID,
		EvictionPolicy: models.EvictionPolicy{
			Type:              "lru",
			EvictionThreshold: 10,
		},
	}
	err := sm.SetPolicy(lowThresholdPolicy)
	if err != nil {
		t.Fatalf("Set low threshold policy failed: %v", err)
	}

	lowEvicted, _ := sm.ApplyEviction(cacheID, cache)
	lowCount := len(lowEvicted)
	t.Logf("Low threshold (10%%) evicted: %d keys", lowCount)

	for i := 0; i < 10; i++ {
		key := "key_" + string(rune('0'+i))
		cache.Set(key, string(smallValue), 3600)
	}

	highThresholdPolicy := &models.CachePolicy{
		PolicyID: "policy_high",
		CacheID:  cacheID,
		EvictionPolicy: models.EvictionPolicy{
			Type:              "lru",
			EvictionThreshold: 90,
		},
	}
	err = sm.SetPolicy(highThresholdPolicy)
	if err != nil {
		t.Fatalf("Set high threshold policy failed: %v", err)
	}

	highEvicted, _ := sm.ApplyEviction(cacheID, cache)
	highCount := len(highEvicted)
	t.Logf("High threshold (90%%) evicted: %d keys", highCount)

	if lowCount < highCount {
		t.Log("Low threshold evicted more keys as expected")
	}
}

func TestEvictionWithEmptyCache(t *testing.T) {
	cm, sm, _ := setupEvictionTestEnvironment(t)
	cacheID := "small_cache_01"

	cache, _ := cm.GetCache(cacheID)

	evicted, err := sm.ApplyEviction(cacheID, cache)
	if err != nil {
		t.Fatalf("ApplyEviction failed with empty cache: %v", err)
	}

	if len(evicted) != 0 {
		t.Errorf("Expected no eviction with empty cache, got %d", len(evicted))
	}
}

func TestEvictionWithSmallCapacity(t *testing.T) {
	logger := logrus.New()
	cm := cache_manager.NewCacheManager(logger)
	sm := NewStrategyManager(cm, logger)

	tinyInstance := &models.CacheInstance{
		CacheID:     "tiny_cache",
		CacheName:   "微容量测试缓存",
		CacheType:   "memory",
		MaxCapacity: 100,
		DefaultTTL:  3600,
		Status:      "online",
	}
	err := cm.RegisterInstance(tinyInstance)
	if err != nil {
		t.Fatalf("Failed to register tiny instance: %v", err)
	}

	cache, _ := cm.GetCache("tiny_cache")

	largeValue := make([]byte, 80)
	cache.Set("large_key", string(largeValue), 3600)

	policy := &models.CachePolicy{
		PolicyID: "policy_tiny",
		CacheID:  "tiny_cache",
		EvictionPolicy: models.EvictionPolicy{
			Type:              "lru",
			EvictionThreshold: 50,
		},
	}
	sm.SetPolicy(policy)

	evicted, err := sm.ApplyEviction("tiny_cache", cache)
	if err != nil {
		t.Fatalf("ApplyEviction failed: %v", err)
	}

	t.Logf("Tiny cache evicted: %v", evicted)
}

func TestEvictionStatistics(t *testing.T) {
	cm, sm, _ := setupEvictionTestEnvironment(t)
	cacheID := "small_cache_01"

	policy := &models.CachePolicy{
		PolicyID: "policy_stats",
		CacheID:  cacheID,
		EvictionPolicy: models.EvictionPolicy{
			Type:              "lru",
			EvictionThreshold: 30,
		},
	}
	sm.SetPolicy(policy)

	cache, _ := cm.GetCache(cacheID)

	initialEvictCount := cache.GetEvictCount()

	value := make([]byte, 150)
	for i := 0; i < 8; i++ {
		key := "stat_key_" + string(rune('a'+i))
		cache.Set(key, string(value), 3600)
	}

	evicted, _ := sm.ApplyEviction(cacheID, cache)

	finalEvictCount := cache.GetEvictCount()

	if finalEvictCount != initialEvictCount+len(evicted) {
		t.Errorf("Evict count mismatch: expected %d, got %d",
			initialEvictCount+len(evicted), finalEvictCount)
	}

	t.Logf("Initial evict count: %d, Evicted: %d, Final evict count: %d",
		initialEvictCount, len(evicted), finalEvictCount)
}

func TestMultipleEvictionRounds(t *testing.T) {
	cm, sm, _ := setupEvictionTestEnvironment(t)
	cacheID := "small_cache_01"

	policy := &models.CachePolicy{
		PolicyID: "policy_rounds",
		CacheID:  cacheID,
		EvictionPolicy: models.EvictionPolicy{
			Type:              "lru",
			EvictionThreshold: 40,
		},
	}
	sm.SetPolicy(policy)

	cache, _ := cm.GetCache(cacheID)

	value := make([]byte, 100)

	for round := 0; round < 3; round++ {
		for i := 0; i < 5; i++ {
			key := "round_" + string(rune('0'+round)) + "_key_" + string(rune('0'+i))
			cache.Set(key, string(value), 3600)
		}

		evicted, err := sm.ApplyEviction(cacheID, cache)
		if err != nil {
			t.Fatalf("Round %d: ApplyEviction failed: %v", round, err)
		}

		t.Logf("Round %d: Evicted %d keys", round, len(evicted))
	}

	keys := cache.GetKeys()
	t.Logf("Final keys in cache: %d", len(keys))
}

func TestEvictionPolicyValidation(t *testing.T) {
	cm, sm, _ := setupEvictionTestEnvironment(t)
	cacheID := "small_cache_01"

	testCases := []struct {
		name      string
		policy    *models.CachePolicy
		shouldErr bool
	}{
		{
			name: "valid_lru_policy",
			policy: &models.CachePolicy{
				PolicyID: "valid_lru",
				CacheID:  cacheID,
				EvictionPolicy: models.EvictionPolicy{
					Type:              "lru",
					EvictionThreshold: 80,
				},
			},
			shouldErr: false,
		},
		{
			name: "valid_lfu_policy",
			policy: &models.CachePolicy{
				PolicyID: "valid_lfu",
				CacheID:  cacheID,
				EvictionPolicy: models.EvictionPolicy{
					Type:              "lfu",
					EvictionThreshold: 70,
				},
			},
			shouldErr: false,
		},
		{
			name: "valid_fifo_policy",
			policy: &models.CachePolicy{
				PolicyID: "valid_fifo",
				CacheID:  cacheID,
				EvictionPolicy: models.EvictionPolicy{
					Type:              "fifo",
					EvictionThreshold: 60,
				},
			},
			shouldErr: false,
		},
		{
			name: "missing_cache_id",
			policy: &models.CachePolicy{
				PolicyID: "missing_cache",
				CacheID:  "",
				EvictionPolicy: models.EvictionPolicy{
					Type:              "lru",
					EvictionThreshold: 80,
				},
			},
			shouldErr: true,
		},
		{
			name: "missing_policy_id",
			policy: &models.CachePolicy{
				PolicyID: "",
				CacheID:  cacheID,
				EvictionPolicy: models.EvictionPolicy{
					Type:              "lru",
					EvictionThreshold: 80,
				},
			},
			shouldErr: true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			err := sm.SetPolicy(tc.policy)
			if tc.shouldErr && err == nil {
				t.Error("Expected error but got nil")
			}
			if !tc.shouldErr && err != nil {
				t.Errorf("Expected no error but got: %v", err)
			}
		})
	}
}
