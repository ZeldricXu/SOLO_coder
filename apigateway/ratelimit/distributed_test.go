package ratelimit

import (
	"sync"
	"testing"
	"time"
)

func TestDistributedTokenBucket_Basic(t *testing.T) {
	store := NewMockRedisStore()
	key := "test_distributed_tb"
	capacity := 10
	refillRate := 1

	dtb := NewDistributedTokenBucket(store, key, capacity, refillRate)

	allowed := 0
	for i := 0; i < capacity; i++ {
		ok, err := dtb.Allow()
		if err != nil {
			t.Errorf("Unexpected error: %v", err)
		}
		if ok {
			allowed++
		}
	}

	if allowed != capacity {
		t.Errorf("Expected %d allowed requests, got %d", capacity, allowed)
	}

	ok, _ := dtb.Allow()
	if ok {
		t.Error("Expected to reject after capacity exhausted")
	}
}

func TestDistributedTokenBucket_MultiInstance(t *testing.T) {
	sharedStore := NewMockRedisStore()
	key := "multi_instance_key"
	capacity := 20
	refillRate := 10

	numInstances := 3
	requestsPerInstance := 10

	var wg sync.WaitGroup
	var mu sync.Mutex
	totalAllowed := 0

	wg.Add(numInstances)

	for instance := 0; instance < numInstances; instance++ {
		go func(instID int) {
			defer wg.Done()

			dtb := NewDistributedTokenBucket(sharedStore, key, capacity, refillRate)

			for i := 0; i < requestsPerInstance; i++ {
				ok, err := dtb.Allow()
				if err != nil {
					t.Logf("Instance %d error: %v", instID, err)
					continue
				}
				if ok {
					mu.Lock()
					totalAllowed++
					mu.Unlock()
				}
			}
		}(instance)
	}

	wg.Wait()

	if totalAllowed > capacity+refillRate {
		t.Errorf("Total allowed %d exceeds capacity %d (with possible refill)", totalAllowed, capacity)
	}

	if totalAllowed < capacity {
		t.Logf("Note: With concurrent access, may allow less due to race conditions")
	}
}

func TestDistributedSlidingWindow_Basic(t *testing.T) {
	store := NewMockRedisStore()
	key := "test_distributed_sw"
	windowSize := time.Second
	limit := 5

	dsw := NewDistributedSlidingWindow(store, key, windowSize, limit)

	allowed := 0
	for i := 0; i < limit; i++ {
		ok, err := dsw.Allow()
		if err != nil {
			t.Errorf("Unexpected error: %v", err)
		}
		if ok {
			allowed++
		}
	}

	if allowed != limit {
		t.Errorf("Expected %d allowed requests, got %d", limit, allowed)
	}

	ok, _ := dsw.Allow()
	if ok {
		t.Error("Expected to reject after limit exceeded")
	}
}

func TestDistributedSlidingWindow_MultiInstance(t *testing.T) {
	sharedStore := NewMockRedisStore()
	key := "multi_instance_sw"
	windowSize := 5 * time.Second
	limit := 30

	numInstances := 5
	requestsPerInstance := 10

	var wg sync.WaitGroup
	var mu sync.Mutex
	totalAllowed := 0

	wg.Add(numInstances)

	for instance := 0; instance < numInstances; instance++ {
		go func(instID int) {
			defer wg.Done()

			dsw := NewDistributedSlidingWindow(sharedStore, key, windowSize, limit)

			for i := 0; i < requestsPerInstance; i++ {
				ok, err := dsw.Allow()
				if err != nil {
					t.Logf("Instance %d error: %v", instID, err)
					continue
				}
				if ok {
					mu.Lock()
					totalAllowed++
					mu.Unlock()
				}
			}
		}(instance)
	}

	wg.Wait()

	if totalAllowed > limit+5 {
		t.Errorf("Total allowed %d exceeds limit %d (with tolerance)", totalAllowed, limit)
	}
}

func TestRedisFailure_Degradation(t *testing.T) {
	store := NewMockRedisStore()
	store.SetAllowFallback(true)

	key := "test_failover"
	capacity := 10
	refillRate := 1

	dtb := NewDistributedTokenBucket(store, key, capacity, refillRate)

	for i := 0; i < 5; i++ {
		_, err := dtb.Allow()
		if err != nil {
			t.Errorf("Should work before failure: %v", err)
		}
	}

	store.SetFailureMode(true)

	for i := 0; i < 20; i++ {
		ok, err := dtb.Allow()
		if err != nil {
			t.Logf("Expected fallback mode, got error: %v", err)
		}
		if !ok {
			t.Log("In fallback mode, should allow all requests")
		}
	}

	store.SetFailureMode(false)

	tokens, _ := dtb.GetTokens()
	t.Logf("Tokens after recovery: %d", tokens)
}

func TestRedisFailure_NoFallback(t *testing.T) {
	store := NewMockRedisStore()
	store.SetAllowFallback(false)

	key := "test_no_fallback"
	capacity := 10
	refillRate := 1

	dtb := NewDistributedTokenBucket(store, key, capacity, refillRate)

	store.SetFailureMode(true)

	for i := 0; i < 5; i++ {
		_, err := dtb.Allow()
		if err == nil {
			t.Error("Expected Redis error when fallback disabled")
		}
		if !IsRedisError(err) {
			t.Errorf("Expected RedisError type, got: %T", err)
		}
	}
}

func TestRedisPartialFailure(t *testing.T) {
	store := NewMockRedisStore()
	store.SetAllowFallback(true)

	key := "test_partial"
	capacity := 10
	refillRate := 1

	dtb := NewDistributedTokenBucket(store, key, capacity, refillRate)

	store.SetFailNext(3)

	failCount := 0
	successCount := 0

	for i := 0; i < 10; i++ {
		ok, err := dtb.Allow()
		if err != nil {
			failCount++
		} else if ok {
			successCount++
		}
	}

	if failCount < 3 {
		t.Errorf("Expected at least 3 failures, got %d", failCount)
	}

	if successCount < 5 {
		t.Errorf("Expected at least 5 successful requests, got %d", successCount)
	}
}

func TestDistributedStateConsistency(t *testing.T) {
	store := NewMockRedisStore()
	key := "test_consistency"
	capacity := 100
	refillRate := 0

	dtb1 := NewDistributedTokenBucket(store, key, capacity, refillRate)
	dtb2 := NewDistributedTokenBucket(store, key, capacity, refillRate)

	for i := 0; i < 30; i++ {
		if i%2 == 0 {
			dtb1.Allow()
		} else {
			dtb2.Allow()
		}
	}

	tokens1, _ := dtb1.GetTokens()
	tokens2, _ := dtb2.GetTokens()

	if tokens1 != tokens2 {
		t.Errorf("State inconsistency: instance1 has %d tokens, instance2 has %d", tokens1, tokens2)
	}

	expected := capacity - 30
	if tokens1 != int64(expected) {
		t.Errorf("Expected %d tokens remaining, got %d", expected, tokens1)
	}
}

func TestDistributedRateLimit_Reset(t *testing.T) {
	store := NewMockRedisStore()
	key := "test_reset"
	capacity := 5
	refillRate := 1

	dtb := NewDistributedTokenBucket(store, key, capacity, refillRate)

	for i := 0; i < capacity; i++ {
		dtb.Allow()
	}

	ok, _ := dtb.Allow()
	if ok {
		t.Error("Should be rate limited before reset")
	}

	err := dtb.Reset()
	if err != nil {
		t.Errorf("Reset error: %v", err)
	}

	ok, _ = dtb.Allow()
	if !ok {
		t.Error("Should allow after reset")
	}
}

func TestSharedStore_MultipleKeys(t *testing.T) {
	store := NewMockRedisStore()

	key1 := "route_user"
	key2 := "route_order"

	dtb1 := NewDistributedTokenBucket(store, key1, 10, 1)
	dtb2 := NewDistributedTokenBucket(store, key2, 5, 1)

	for i := 0; i < 10; i++ {
		ok1, _ := dtb1.Allow()
		ok2, _ := dtb2.Allow()

		if i < 10 && !ok1 {
			t.Errorf("Route1 should allow request %d", i)
		}
		if i < 5 && !ok2 {
			t.Errorf("Route2 should allow request %d", i)
		}
		if i >= 5 && ok2 {
			t.Errorf("Route2 should reject request %d", i)
		}
	}
}

func TestConcurrentDistributedAccess(t *testing.T) {
	store := NewMockRedisStore()
	key := "concurrent_test"
	capacity := 50
	refillRate := 0

	numGoroutines := 10
	requestsPerGoroutine := 10

	var wg sync.WaitGroup
	var mu sync.Mutex
	allowedCount := 0

	wg.Add(numGoroutines)

	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			dtb := NewDistributedTokenBucket(store, key, capacity, refillRate)

			for j := 0; j < requestsPerGoroutine; j++ {
				ok, err := dtb.Allow()
				if err != nil {
					continue
				}
				if ok {
					mu.Lock()
					allowedCount++
					mu.Unlock()
				}
			}
		}()
	}

	wg.Wait()

	if allowedCount > capacity+5 {
		t.Errorf("Allowed %d exceeds capacity %d (with tolerance)", allowedCount, capacity)
	}

	t.Logf("Concurrently allowed: %d (capacity: %d)", allowedCount, capacity)
}

func TestDistributedSlidingWindow_TimeWindow(t *testing.T) {
	store := NewMockRedisStore()
	key := "test_time_window"
	windowSize := 200 * time.Millisecond
	limit := 5

	dsw := NewDistributedSlidingWindow(store, key, windowSize, limit)

	for i := 0; i < limit; i++ {
		ok, _ := dsw.Allow()
		if !ok {
			t.Errorf("Should allow request %d within window", i)
		}
	}

	ok, _ := dsw.Allow()
	if ok {
		t.Error("Should reject after limit within window")
	}

	time.Sleep(windowSize + 50*time.Millisecond)

	for i := 0; i < limit; i++ {
		ok, _ := dsw.Allow()
		if !ok {
			t.Errorf("Should allow request %d in new window", i)
		}
	}
}

func TestDistributedAlgorithms_Comparison(t *testing.T) {
	store := NewMockRedisStore()
	key := "comparison_test"

	capacity := 20
	refillRate := 0
	windowSize := 1 * time.Second
	limit := 20

	dtb := NewDistributedTokenBucket(store, key+"_tb", capacity, refillRate)
	dsw := NewDistributedSlidingWindow(store, key+"_sw", windowSize, limit)

	tbAllowed := 0
	swAllowed := 0

	for i := 0; i < 30; i++ {
		ok, _ := dtb.Allow()
		if ok {
			tbAllowed++
		}

		ok, _ = dsw.Allow()
		if ok {
			swAllowed++
		}
	}

	if tbAllowed != capacity {
		t.Errorf("Token bucket: expected %d, got %d", capacity, tbAllowed)
	}

	if swAllowed != limit {
		t.Errorf("Sliding window: expected %d, got %d", limit, swAllowed)
	}
}
