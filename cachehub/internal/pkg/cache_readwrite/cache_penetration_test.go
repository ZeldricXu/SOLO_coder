package cache_readwrite

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/strategy"
	"github.com/cachehub/internal/pkg/testfixtures"
	"github.com/sirupsen/logrus"
)

func setupPenetrationTestEnvironment(t *testing.T) (*cache_manager.CacheManager, *strategy.StrategyManager, *CacheReadWrite, *testfixtures.TestDataBuilder) {
	logger := logrus.New()
	logger.SetLevel(logrus.WarnLevel)

	cm := cache_manager.NewCacheManager(logger)
	sm := strategy.NewStrategyManager(cm, logger)
	rw := NewCacheReadWrite(cm, sm, logger)
	builder := testfixtures.NewTestDataBuilder()

	instance := builder.BuildDefaultCacheInstance()
	err := cm.RegisterInstance(instance)
	if err != nil {
		t.Fatalf("Failed to register cache instance: %v", err)
	}

	return cm, sm, rw, builder
}

func TestHighConcurrencySameMissWithNullCache(t *testing.T) {
	_, _, rw, builder := setupPenetrationTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	rw.EnableNullCache()
	rw.SetNullCacheTTL(60)

	nonexistentKey := "nonexistent_key_999"

	concurrentCount := 100
	var missCount int32
	var nullMarkCount int32
	var wg sync.WaitGroup

	err := rw.MarkNull(cacheID, nonexistentKey)
	if err != nil {
		t.Fatalf("MarkNull failed: %v", err)
	}

	for i := 0; i < concurrentCount; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			isMarked, err := rw.IsNullMarked(cacheID, nonexistentKey)
			if err != nil {
				return
			}
			if isMarked {
				atomic.AddInt32(&nullMarkCount, 1)
				return
			}

			_, found, err := rw.Get(cacheID, nonexistentKey)
			if err != nil {
				return
			}
			if !found {
				atomic.AddInt32(&missCount, 1)
			}
		}()
	}

	wg.Wait()

	t.Logf("Null cache hits: %d, cache misses: %d, total: %d", nullMarkCount, missCount, concurrentCount)

	if nullMarkCount == 0 {
		t.Error("Expected some null cache hits in high concurrency")
	}
}

func TestHighConcurrencyMultipleMissKeys(t *testing.T) {
	_, _, rw, builder := setupPenetrationTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	rw.EnableNullCache()
	rw.SetNullCacheTTL(60)

	concurrentCount := 50
	keysPerGoroutine := 20
	var totalOperations int32
	var markSuccessCount int32
	var wg sync.WaitGroup

	for i := 0; i < concurrentCount; i++ {
		wg.Add(1)
		go func(goroutineID int) {
			defer wg.Done()

			for j := 0; j < keysPerGoroutine; j++ {
				key := "missing_key_" + string(rune('A'+goroutineID%26)) + "_" + string(rune('0'+j%10))

				_, found, err := rw.Get(cacheID, key)
				if err != nil {
					continue
				}

				atomic.AddInt32(&totalOperations, 1)

				if !found {
					err = rw.MarkNull(cacheID, key)
					if err == nil {
						atomic.AddInt32(&markSuccessCount, 1)
					}
				}
			}
		}(i)
	}

	wg.Wait()

	t.Logf("Total operations: %d, null marks successful: %d", totalOperations, markSuccessCount)

	if markSuccessCount == 0 {
		t.Error("Expected some null marks to succeed")
	}
}

func TestNullCacheTTLExpirationInConcurrency(t *testing.T) {
	cm, _, rw, builder := setupPenetrationTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	shortTTL := 1
	rw.EnableNullCache()
	rw.SetNullCacheTTL(shortTTL)

	testKey := "expiring_concurrent_key"

	err := rw.MarkNull(cacheID, testKey)
	if err != nil {
		t.Fatalf("MarkNull failed: %v", err)
	}

	initialCheckCount := 50
	var initialHits int32
	var wg1 sync.WaitGroup

	for i := 0; i < initialCheckCount; i++ {
		wg1.Add(1)
		go func() {
			defer wg1.Done()
			isMarked, _ := rw.IsNullMarked(cacheID, testKey)
			if isMarked {
				atomic.AddInt32(&initialHits, 1)
			}
		}()
	}

	wg1.Wait()

	t.Logf("Initial null cache hits: %d/%d", initialHits, initialCheckCount)

	if initialHits < int32(initialCheckCount/2) {
		t.Error("Expected majority of initial checks to find null mark")
	}

	time.Sleep(time.Duration(shortTTL+1) * time.Second)

	cache, _ := cm.GetCache(cacheID)
	nullKey := rw.buildNullKey(testKey)
	_, found := cache.Get(nullKey)
	if found {
		t.Error("Expected null cache to expire after TTL")
	}

	finalCheckCount := 30
	var finalHits int32
	var wg2 sync.WaitGroup

	for i := 0; i < finalCheckCount; i++ {
		wg2.Add(1)
		go func() {
			defer wg2.Done()
			isMarked, _ := rw.IsNullMarked(cacheID, testKey)
			if isMarked {
				atomic.AddInt32(&finalHits, 1)
			}
		}()
	}

	wg2.Wait()

	t.Logf("Final null cache hits after expiration: %d/%d", finalHits, finalCheckCount)

	if finalHits > int32(finalCheckCount/10) {
		t.Error("Expected very few null cache hits after expiration")
	}
}

func TestNullCacheDisableInHighConcurrency(t *testing.T) {
	_, _, rw, builder := setupPenetrationTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	rw.EnableNullCache()
	testKey := "toggle_key"

	err := rw.MarkNull(cacheID, testKey)
	if err != nil {
		t.Fatalf("MarkNull failed: %v", err)
	}

	checkCount := 100
	var hitsBefore int32
	var wg1 sync.WaitGroup

	for i := 0; i < checkCount; i++ {
		wg1.Add(1)
		go func() {
			defer wg1.Done()
			isMarked, _ := rw.IsNullMarked(cacheID, testKey)
			if isMarked {
				atomic.AddInt32(&hitsBefore, 1)
			}
		}()
	}

	wg1.Wait()

	t.Logf("Hits before disable: %d", hitsBefore)

	rw.DisableNullCache()

	var hitsAfter int32
	var wg2 sync.WaitGroup

	for i := 0; i < checkCount; i++ {
		wg2.Add(1)
		go func() {
			defer wg2.Done()
			isMarked, _ := rw.IsNullMarked(cacheID, testKey)
			if isMarked {
				atomic.AddInt32(&hitsAfter, 1)
			}
		}()
	}

	wg2.Wait()

	t.Logf("Hits after disable: %d", hitsAfter)

	if hitsAfter != 0 {
		t.Error("Expected zero null cache hits after disabling")
	}
}

func TestConcurrentMarkNullSameKey(t *testing.T) {
	_, _, rw, builder := setupPenetrationTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	rw.EnableNullCache()
	testKey := "concurrent_mark_key"

	concurrentCount := 200
	var successCount int32
	var errorCount int32
	var wg sync.WaitGroup

	for i := 0; i < concurrentCount; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			err := rw.MarkNull(cacheID, testKey)
			if err != nil {
				atomic.AddInt32(&errorCount, 1)
			} else {
				atomic.AddInt32(&successCount, 1)
			}
		}()
	}

	wg.Wait()

	t.Logf("Success: %d, Errors: %d", successCount, errorCount)

	if errorCount != 0 {
		t.Errorf("Expected no errors during concurrent MarkNull, got %d", errorCount)
	}

	isMarked, err := rw.IsNullMarked(cacheID, testKey)
	if err != nil {
		t.Fatalf("IsNullMarked failed: %v", err)
	}
	if !isMarked {
		t.Error("Expected key to be marked as null after concurrent marking")
	}
}

func TestHighConcurrencyMixedOperations(t *testing.T) {
	_, _, rw, builder := setupPenetrationTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	rw.EnableNullCache()
	rw.SetNullCacheTTL(30)

	existingKeys := []string{"existing_1", "existing_2", "existing_3"}
	for _, key := range existingKeys {
		rw.Set(cacheID, key, "value_"+key, 3600)
	}

	concurrentCount := 100
	operationsPerGoroutine := 20
	var hitCount int32
	var missCount int32
	var nullMarkCount int32
	var setCount int32
	var wg sync.WaitGroup

	for i := 0; i < concurrentCount; i++ {
		wg.Add(1)
		go func(goroutineID int) {
			defer wg.Done()

			for j := 0; j < operationsPerGoroutine; j++ {
				opType := (goroutineID + j) % 4

				switch opType {
				case 0:
					key := existingKeys[(goroutineID+j)%len(existingKeys)]
					_, found, _ := rw.Get(cacheID, key)
					if found {
						atomic.AddInt32(&hitCount, 1)
					}

				case 1:
					key := "missing_" + string(rune('A'+goroutineID%26)) + "_" + string(rune('0'+j%10))
					_, found, _ := rw.Get(cacheID, key)
					if !found {
						atomic.AddInt32(&missCount, 1)
						rw.MarkNull(cacheID, key)
					}

				case 2:
					key := "null_mark_check_" + string(rune('0'+j%10))
					isMarked, _ := rw.IsNullMarked(cacheID, key)
					if isMarked {
						atomic.AddInt32(&nullMarkCount, 1)
					}

				case 3:
					key := "new_key_" + string(rune('A'+goroutineID%26))
					rw.Set(cacheID, key, goroutineID, 3600)
					atomic.AddInt32(&setCount, 1)
				}
			}
		}(i)
	}

	wg.Wait()

	t.Logf("Hits: %d, Misses: %d, NullMarkChecks: %d, Sets: %d",
		hitCount, missCount, nullMarkCount, setCount)

	if hitCount == 0 {
		t.Error("Expected some cache hits")
	}
	if missCount == 0 {
		t.Error("Expected some cache misses")
	}
}

func TestCachePenetrationProtectionEffectiveness(t *testing.T) {
	_, _, rw, builder := setupPenetrationTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	attackKey := "malicious_key"
	totalRequests := 500

	rw.EnableNullCache()
	rw.SetNullCacheTTL(60)

	err := rw.MarkNull(cacheID, attackKey)
	if err != nil {
		t.Fatalf("MarkNull failed: %v", err)
	}

	var protectedCount int32
	var missCount int32
	var wg sync.WaitGroup

	for i := 0; i < totalRequests; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			isMarked, err := rw.IsNullMarked(cacheID, attackKey)
			if err != nil {
				return
			}

			if isMarked {
				atomic.AddInt32(&protectedCount, 1)
				return
			}

			_, found, _ := rw.Get(cacheID, attackKey)
			if !found {
				atomic.AddInt32(&missCount, 1)
			}
		}()
	}

	wg.Wait()

	protectionRate := float64(protectedCount) / float64(totalRequests) * 100

	t.Logf("Total requests: %d, Protected by null cache: %d, Actual misses: %d, Protection rate: %.2f%%",
		totalRequests, protectedCount, missCount, protectionRate)

	expectedProtectionRate := 90.0
	if protectionRate < expectedProtectionRate {
		t.Logf("Warning: Protection rate is below %.1f%%", expectedProtectionRate)
	}

	if protectedCount == 0 {
		t.Error("Null cache protection should catch some requests")
	}
}

func TestNullCacheKeyCollision(t *testing.T) {
	_, _, rw, builder := setupPenetrationTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	rw.EnableNullCache()

	testCases := []string{
		"test_key",
		"__null__:test_key",
		"normal_key",
		"",
		"key_with_special_chars!@#$%",
		"key with spaces",
	}

	var wg sync.WaitGroup
	for _, key := range testCases {
		wg.Add(1)
		go func(k string) {
			defer wg.Done()

			err := rw.MarkNull(cacheID, k)
			if err != nil {
				return
			}

			nullKey := rw.buildNullKey(k)
			if nullKey == k {
				t.Errorf("Null key should not equal original key: %s", k)
			}

			isMarked, _ := rw.IsNullMarked(cacheID, k)
			if !isMarked {
				t.Errorf("Expected key '%s' to be marked as null", k)
			}
		}(key)
	}

	wg.Wait()
}

func TestHighConcurrencyClearNullMark(t *testing.T) {
	_, _, rw, builder := setupPenetrationTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	rw.EnableNullCache()

	testKey := "clear_concurrent_key"
	err := rw.MarkNull(cacheID, testKey)
	if err != nil {
		t.Fatalf("MarkNull failed: %v", err)
	}

	clearCount := 50
	checkCount := 50
	var clearSuccess int32
	var clearError int32
	var stillMarked int32
	var wg sync.WaitGroup

	for i := 0; i < clearCount; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			err := rw.ClearNullMark(cacheID, testKey)
			if err != nil {
				atomic.AddInt32(&clearError, 1)
			} else {
				atomic.AddInt32(&clearSuccess, 1)
			}
		}()
	}

	for i := 0; i < checkCount; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			isMarked, _ := rw.IsNullMarked(cacheID, testKey)
			if isMarked {
				atomic.AddInt32(&stillMarked, 1)
			}
		}()
	}

	wg.Wait()

	t.Logf("Clear success: %d, Clear errors: %d, Still marked: %d",
		clearSuccess, clearError, stillMarked)

	if clearError != 0 {
		t.Errorf("Expected no errors during concurrent clear, got %d", clearError)
	}
}

func TestNullCacheWithMixedKeysConcurrency(t *testing.T) {
	_, _, rw, builder := setupPenetrationTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	rw.EnableNullCache()

	nullKeys := []string{"null_key_1", "null_key_2", "null_key_3"}
	actualKeys := []string{"actual_key_1", "actual_key_2", "actual_key_3"}

	for _, key := range nullKeys {
		rw.MarkNull(cacheID, key)
	}

	for _, key := range actualKeys {
		rw.Set(cacheID, key, "value_"+key, 3600)
	}

	concurrentCount := 80
	operationsPerGoroutine := 30
	var nullHit int32
	var actualHit int32
	var totalMiss int32
	var wg sync.WaitGroup

	for i := 0; i < concurrentCount; i++ {
		wg.Add(1)
		go func(goroutineID int) {
			defer wg.Done()

			for j := 0; j < operationsPerGoroutine; j++ {
				keyType := (goroutineID + j) % 3

				switch keyType {
				case 0:
					key := nullKeys[(goroutineID+j)%len(nullKeys)]
					isMarked, _ := rw.IsNullMarked(cacheID, key)
					if isMarked {
						atomic.AddInt32(&nullHit, 1)
					}

				case 1:
					key := actualKeys[(goroutineID+j)%len(actualKeys)]
					_, found, _ := rw.Get(cacheID, key)
					if found {
						atomic.AddInt32(&actualHit, 1)
					}

				case 2:
					key := "random_" + string(rune('A'+goroutineID%26)) + "_" + string(rune('0'+j%10))
					_, found, _ := rw.Get(cacheID, key)
					if !found {
						atomic.AddInt32(&totalMiss, 1)
						rw.MarkNull(cacheID, key)
					}
				}
			}
		}(i)
	}

	wg.Wait()

	t.Logf("Null cache hits: %d, Actual hits: %d, Random misses: %d",
		nullHit, actualHit, totalMiss)

	if nullHit == 0 {
		t.Error("Expected null cache hits")
	}
	if actualHit == 0 {
		t.Error("Expected actual cache hits")
	}
}
