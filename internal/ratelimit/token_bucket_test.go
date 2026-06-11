package ratelimit

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"DF1-56/internal/testutil"
)

type mockRedisClient struct {
	fail     bool
	failOnce bool
	data     map[string]interface{}
	mu       sync.Mutex
}

func newMockRedisClient() *mockRedisClient {
	return &mockRedisClient{
		data: make(map[string]interface{}),
	}
}

func (m *mockRedisClient) setFail(fail bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.fail = fail
}

func (m *mockRedisClient) setFailOnce(failOnce bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.failOnce = failOnce
}

func (m *mockRedisClient) shouldFail() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.fail {
		return true
	}
	if m.failOnce {
		m.failOnce = false
		return true
	}
	return false
}

func TestTokenBucket_NormalPath(t *testing.T) {
	t.Run("requests within rate limit are allowed", func(t *testing.T) {
		store := NewMemoryStore()
		tb := NewTokenBucket(store)

		capacity := int64(10)
		refillRate := int64(1)
		window := time.Second
		key := "test-token-bucket-normal"

		for i := 0; i < int(capacity); i++ {
			allowed, remaining, limit, _ := tb.Take(context.Background(), key, capacity, refillRate, window)
			assert.True(t, allowed, "request %d should be allowed", i)
			assert.Equal(t, capacity, limit)
			assert.Equal(t, capacity-int64(i)-1, remaining)
		}

		allowed, remaining, limit, resetAfter := tb.Take(context.Background(), key, capacity, refillRate, window)
		assert.False(t, allowed, "request over capacity should be rejected")
		assert.Equal(t, int64(0), remaining)
		assert.Equal(t, capacity, limit)
		assert.Greater(t, resetAfter, time.Duration(0))
	})

	t.Run("tokens refill after window", func(t *testing.T) {
		store := NewMemoryStore()
		tb := NewTokenBucket(store)

		capacity := int64(5)
		refillRate := int64(5)
		window := 100 * time.Millisecond
		key := "test-token-bucket-refill"

		for i := 0; i < int(capacity); i++ {
			allowed, _, _, _ := tb.Take(context.Background(), key, capacity, refillRate, window)
			assert.True(t, allowed, "request %d should be allowed", i)
		}

		allowed, _, _, _ := tb.Take(context.Background(), key, capacity, refillRate, window)
		assert.False(t, allowed, "should be rate limited")

		time.Sleep(window + 50*time.Millisecond)

		allowed, remaining, _, _ := tb.Take(context.Background(), key, capacity, refillRate, window)
		assert.True(t, allowed, "should allow after refill")
		assert.Greater(t, remaining, int64(0))
	})
}

type mockRedisScriptClient struct {
	*redis.Client
	mock *mockRedisClient
}

func (m *mockRedisScriptClient) Do(ctx context.Context, args ...interface{}) *redis.Cmd {
	if m.mock.shouldFail() {
		cmd := redis.NewCmd(ctx)
		cmd.SetErr(errors.New("redis connection failed"))
		return cmd
	}
	return m.Client.Do(ctx, args...)
}

type mockFailingTokenBucketStore struct {
	fail bool
}

func (m *mockFailingTokenBucketStore) TokenBucketTake(ctx context.Context, key string, capacity, refillRate int64, window time.Duration) (*TokenBucketResult, error) {
	if m.fail {
		return nil, errors.New("redis connection failed")
	}
	return &TokenBucketResult{
		Allowed:    true,
		Remaining:  capacity,
		Limit:      capacity,
		ResetAfter: window,
	}, nil
}

func TestTokenBucket_RedisFailure(t *testing.T) {
	t.Run("Redis failure degrades to local allow mode", func(t *testing.T) {
		mockStore := &mockFailingTokenBucketStore{fail: true}
		tb := NewTokenBucket(mockStore)

		capacity := int64(10)
		refillRate := int64(1)
		window := time.Second
		key := "test-token-bucket-redis-fail"

		for i := 0; i < 100; i++ {
			allowed, remaining, limit, _ := tb.Take(context.Background(), key, capacity, refillRate, window)
			assert.True(t, allowed, "request should be allowed when Redis fails")
			assert.Equal(t, capacity, remaining, "remaining should equal capacity in fallback mode")
			assert.Equal(t, capacity, limit)
		}
	})

	t.Run("Redis recovers and normal operation resumes", func(t *testing.T) {
		mockStore := &mockFailingTokenBucketStore{fail: true}
		tb := NewTokenBucket(mockStore)

		capacity := int64(5)
		refillRate := int64(1)
		window := time.Second
		key := "test-token-bucket-recovery"

		for i := 0; i < 50; i++ {
			allowed, _, _, _ := tb.Take(context.Background(), key, capacity, refillRate, window)
			assert.True(t, allowed, "should allow during Redis failure")
		}

		mockStore.fail = false
		memStore := NewMemoryStore()
		tb.store = memStore
		for i := 0; i < int(capacity); i++ {
			allowed, _, _, _ := tb.Take(context.Background(), key, capacity, refillRate, window)
			assert.True(t, allowed, "request %d should be allowed after recovery", i)
		}
	})
}

func TestTokenBucket_Concurrency(t *testing.T) {
	t.Run("high concurrency maintains correct token count", func(t *testing.T) {
		store := NewMemoryStore()
		tb := NewTokenBucket(store)

		capacity := int64(100)
		refillRate := int64(100)
		window := time.Second
		key := "test-token-bucket-concurrent"

		numGoroutines := 10
		requestsPerGoroutine := 50
		totalRequests := numGoroutines * requestsPerGoroutine

		var allowedCount int64
		var rejectedCount int64
		var wg sync.WaitGroup

		for i := 0; i < numGoroutines; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for j := 0; j < requestsPerGoroutine; j++ {
					allowed, _, _, _ := tb.Take(context.Background(), key, capacity, refillRate, window)
					if allowed {
						atomic.AddInt64(&allowedCount, 1)
					} else {
						atomic.AddInt64(&rejectedCount, 1)
					}
				}
			}()
		}

		wg.Wait()

		assert.Equal(t, int64(totalRequests), allowedCount+rejectedCount, "total requests should match")
		assert.LessOrEqual(t, allowedCount, capacity, "allowed count should not exceed capacity")
		assert.GreaterOrEqual(t, rejectedCount, int64(0), "rejected count should be non-negative")

		t.Logf("Allowed: %d, Rejected: %d, Capacity: %d", allowedCount, rejectedCount, capacity)
	})

	t.Run("concurrent access with different keys does not interfere", func(t *testing.T) {
		store := NewMemoryStore()
		tb := NewTokenBucket(store)

		capacity := int64(50)
		refillRate := int64(10)
		window := time.Second

		numKeys := 5
		numGoroutinesPerKey := 4
		requestsPerGoroutine := 25

		var wg sync.WaitGroup
		allowedCounts := make([]int64, numKeys)

		for k := 0; k < numKeys; k++ {
			keyID := k
			for g := 0; g < numGoroutinesPerKey; g++ {
				wg.Add(1)
				go func() {
					defer wg.Done()
					key := testutil.StringPtr("concurrent-key-" + string(rune('A'+keyID)))
					for j := 0; j < requestsPerGoroutine; j++ {
						allowed, _, _, _ := tb.Take(context.Background(), *key, capacity, refillRate, window)
						if allowed {
							atomic.AddInt64(&allowedCounts[keyID], 1)
						}
					}
				}()
			}
		}

		wg.Wait()

		for k := 0; k < numKeys; k++ {
			assert.LessOrEqual(t, allowedCounts[k], capacity, "key %d: allowed count should not exceed capacity", k)
			t.Logf("Key %d: Allowed = %d", k, allowedCounts[k])
		}
	})
}

func TestSlidingWindow_NormalPath(t *testing.T) {
	t.Run("requests within window are allowed", func(t *testing.T) {
		store := NewMemoryStore()
		sw := NewSlidingWindow(store)

		limit := int64(10)
		window := time.Second
		key := "test-sliding-window-normal"

		for i := 0; i < int(limit); i++ {
			allowed, remaining, lim, _ := sw.Check(context.Background(), key, limit, window)
			assert.True(t, allowed, "request %d should be allowed", i)
			assert.Equal(t, limit, lim)
			assert.Equal(t, limit-int64(i)-1, remaining)
		}

		allowed, remaining, _, _ := sw.Check(context.Background(), key, limit, window)
		assert.False(t, allowed, "request over limit should be rejected")
		assert.Equal(t, int64(0), remaining)
	})
}

func TestConcurrency_NormalPath(t *testing.T) {
	t.Run("concurrent acquire respects max concurrent limit", func(t *testing.T) {
		store := NewMemoryStore()

		maxConcurrent := int64(5)
		key := "test-concurrency-normal"
		ttl := 5 * time.Minute

		var releaseFuncs []func()
		for i := 0; i < int(maxConcurrent); i++ {
			requestID := fmt.Sprintf("req-%d", i)
			result, err := store.ConcurrencyAcquire(context.Background(), key, maxConcurrent, requestID, ttl)
			require.NoError(t, err)
			assert.True(t, result.Allowed, "acquire %d should be allowed", i)
			releaseFuncs = append(releaseFuncs, result.ReleaseFunc)
		}

		result, err := store.ConcurrencyAcquire(context.Background(), key, maxConcurrent, "req-excess", ttl)
		require.NoError(t, err)
		assert.False(t, result.Allowed, "should not allow more than max concurrent")

		releaseFuncs[0]()
		result, err = store.ConcurrencyAcquire(context.Background(), key, maxConcurrent, "req-after-release", ttl)
		require.NoError(t, err)
		assert.True(t, result.Allowed, "should allow after release")
		result.ReleaseFunc()
	})
}
