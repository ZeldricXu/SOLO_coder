package ratelimit

import (
	"apigateway/models"
	"apigateway/testdata"
	"sync"
	"testing"
	"time"
)

func TestTokenBucket_Basic(t *testing.T) {
	tests := []struct {
		name           string
		capacity       int
		refillRate     int
		requestCount   int
		expectedAllow  int
		waitTime       time.Duration
	}{
		{
			name:          "Within capacity",
			capacity:      10,
			refillRate:    1,
			requestCount:  5,
			expectedAllow: 5,
			waitTime:      0,
		},
		{
			name:          "At capacity",
			capacity:      10,
			refillRate:    1,
			requestCount:  10,
			expectedAllow: 10,
			waitTime:      0,
		},
		{
			name:          "Exceed capacity",
			capacity:      5,
			refillRate:    1,
			requestCount:  10,
			expectedAllow: 5,
			waitTime:      0,
		},
		{
			name:          "After refill",
			capacity:      5,
			refillRate:    10,
			requestCount:  5,
			expectedAllow: 5,
			waitTime:      200 * time.Millisecond,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tb := NewTokenBucket(tt.capacity, tt.refillRate)

			if tt.waitTime > 0 {
				for i := 0; i < tt.capacity; i++ {
					if !tb.Consume() {
						t.Errorf("Expected to allow initial request %d", i)
					}
				}
				time.Sleep(tt.waitTime)
			}

			allowed := 0
			for i := 0; i < tt.requestCount; i++ {
				if tb.Consume() {
					allowed++
				}
			}

			if allowed != tt.expectedAllow {
				t.Errorf("Expected %d allowed requests, got %d", tt.expectedAllow, allowed)
			}
		})
	}
}

func TestTokenBucket_TryConsumeWithBurst(t *testing.T) {
	capacity := 5
	burst := 10
	tb := NewTokenBucket(capacity, 1)

	for i := 0; i < capacity; i++ {
		if !tb.Consume() {
			t.Errorf("Expected to allow initial request %d", i)
		}
	}

	if tb.Consume() {
		t.Error("Expected to reject after capacity exhausted")
	}

	if !tb.TryConsumeWithBurst(burst) {
		t.Error("Expected to allow with burst capacity")
	}
}

func TestLeakyBucket_Basic(t *testing.T) {
	tests := []struct {
		name           string
		capacity       int
		leakRate       int
		requestCount   int
		expectedAllow  int
	}{
		{
			name:          "Within capacity",
			capacity:      10,
			leakRate:      1,
			requestCount:  5,
			expectedAllow: 5,
		},
		{
			name:          "At capacity",
			capacity:      10,
			leakRate:      1,
			requestCount:  10,
			expectedAllow: 10,
		},
		{
			name:          "Exceed capacity",
			capacity:      5,
			leakRate:      1,
			requestCount:  10,
			expectedAllow: 5,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			lb := NewLeakyBucket(tt.capacity, tt.leakRate)

			allowed := 0
			for i := 0; i < tt.requestCount; i++ {
				if lb.AddWater() {
					allowed++
				}
			}

			if allowed != tt.expectedAllow {
				t.Errorf("Expected %d allowed requests, got %d", tt.expectedAllow, allowed)
			}
		})
	}
}

func TestSlidingWindow_Basic(t *testing.T) {
	tests := []struct {
		name           string
		windowSize     time.Duration
		limit          int
		requestCount   int
		expectedAllow  int
	}{
		{
			name:          "Within limit",
			windowSize:    time.Second,
			limit:         10,
			requestCount:  5,
			expectedAllow: 5,
		},
		{
			name:          "At limit",
			windowSize:    time.Second,
			limit:         10,
			requestCount:  10,
			expectedAllow: 10,
		},
		{
			name:          "Exceed limit",
			windowSize:    time.Second,
			limit:         5,
			requestCount:  10,
			expectedAllow: 5,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			sw := NewSlidingWindow(tt.windowSize, tt.limit)

			allowed := 0
			for i := 0; i < tt.requestCount; i++ {
				if sw.Allow() {
					allowed++
				}
			}

			if allowed != tt.expectedAllow {
				t.Errorf("Expected %d allowed requests, got %d", tt.expectedAllow, allowed)
			}
		})
	}
}

func TestSlidingWindow_WindowReset(t *testing.T) {
	windowSize := 100 * time.Millisecond
	limit := 5

	sw := NewSlidingWindow(windowSize, limit)

	for i := 0; i < limit; i++ {
		if !sw.Allow() {
			t.Errorf("Expected to allow request %d within limit", i)
		}
	}

	if sw.Allow() {
		t.Error("Expected to reject after limit exceeded")
	}

	time.Sleep(windowSize + 10*time.Millisecond)

	if !sw.Allow() {
		t.Error("Expected to allow after window reset")
	}
}

func TestRateLimiter_CheckRateLimit(t *testing.T) {
	scenarios := testdata.GetDefaultRateLimitScenarios()

	for _, scenario := range scenarios {
		t.Run(scenario.Name, func(t *testing.T) {
			rl := NewRateLimiter()
			routeID := "test_route"

			config := &models.RateLimitConfig{
				QPS:   scenario.QPS,
				Burst: scenario.Burst,
			}

			allowed := 0
			rejected := 0

			for i := 0; i < scenario.RequestCount; i++ {
				ok, err := rl.CheckRateLimit(routeID, config, "token_bucket")
				if err != nil {
					t.Errorf("Unexpected error: %v", err)
				}
				if ok {
					allowed++
				} else {
					rejected++
				}
			}

			if scenario.QPS > 0 {
				if allowed != scenario.ExpectedAllowed {
					t.Errorf("Expected %d allowed, got %d", scenario.ExpectedAllowed, allowed)
				}
				if rejected != scenario.ExpectedRejected {
					t.Errorf("Expected %d rejected, got %d", scenario.ExpectedRejected, rejected)
				}
			} else {
				if allowed != scenario.RequestCount {
					t.Errorf("Zero QPS should allow all, expected %d, got %d", scenario.RequestCount, allowed)
				}
			}
		})
	}
}

func TestTokenBucket_Concurrent(t *testing.T) {
	capacity := 100
	refillRate := 10
	numGoroutines := 10
	requestsPerGoroutine := 20

	tb := NewTokenBucket(capacity, refillRate)

	var wg sync.WaitGroup
	var mu sync.Mutex
	allowedCount := 0

	wg.Add(numGoroutines)
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			for j := 0; j < requestsPerGoroutine; j++ {
				if tb.Consume() {
					mu.Lock()
					allowedCount++
					mu.Unlock()
				}
			}
		}()
	}

	wg.Wait()

	if allowedCount < capacity {
		t.Errorf("Expected at least %d allowed requests, got %d", capacity, allowedCount)
	}
}

func TestLeakyBucket_Concurrent(t *testing.T) {
	capacity := 50
	leakRate := 1
	numGoroutines := 10
	requestsPerGoroutine := 10

	lb := NewLeakyBucket(capacity, leakRate)

	var wg sync.WaitGroup
	var mu sync.Mutex
	allowedCount := 0

	wg.Add(numGoroutines)
	for i := 0; i < numGoroutines; i++ {
		go func() {
			defer wg.Done()
			for j := 0; j < requestsPerGoroutine; j++ {
				if lb.AddWater() {
					mu.Lock()
					allowedCount++
					mu.Unlock()
				}
			}
		}()
	}

	wg.Wait()

	if allowedCount != capacity {
		t.Errorf("Expected exactly %d allowed requests, got %d", capacity, allowedCount)
	}
}

func TestRateLimiter_AlgorithmSwitch(t *testing.T) {
	rl := NewRateLimiter()
	routeID := "test_algo_switch"
	config := &models.RateLimitConfig{
		QPS:   5,
		Burst: 2,
	}

	for i := 0; i < 7; i++ {
		allowed, err := rl.CheckRateLimit(routeID, config, "token_bucket")
		if err != nil {
			t.Errorf("Token bucket error: %v", err)
		}
		if i < 7 && !allowed {
			t.Errorf("Token bucket should allow burst: request %d", i)
		}
	}

	rl.ResetRateLimit(routeID)

	for i := 0; i < 7; i++ {
		allowed, err := rl.CheckRateLimit(routeID, config, "leaky_bucket")
		if err != nil {
			t.Errorf("Leaky bucket error: %v", err)
		}
		if i < 2 && !allowed {
			t.Errorf("Leaky bucket should allow up to burst: request %d", i)
		}
	}
}

func TestRateLimiter_GetStatus(t *testing.T) {
	rl := NewRateLimiter()
	routeID := "test_status"

	config := &models.RateLimitConfig{
		QPS:   10,
		Burst: 5,
	}

	for i := 0; i < 5; i++ {
		_, err := rl.CheckRateLimit(routeID, config, "token_bucket")
		if err != nil {
			t.Errorf("Unexpected error: %v", err)
		}
	}

	status, exists := rl.GetTokenBucketStatus(routeID)
	if !exists {
		t.Error("Expected token bucket to exist")
	}

	if status["capacity"] != 15 {
		t.Errorf("Expected capacity 15, got %v", status["capacity"])
	}
}

func TestTokenBucket_EdgeCases(t *testing.T) {
	t.Run("Zero capacity", func(t *testing.T) {
		tb := NewTokenBucket(0, 1)
		if tb.Consume() {
			t.Error("Zero capacity should reject all")
		}
	})

	t.Run("Zero refill rate", func(t *testing.T) {
		tb := NewTokenBucket(5, 0)
		for i := 0; i < 5; i++ {
			if !tb.Consume() {
				t.Errorf("Expected to allow request %d", i)
			}
		}
		if tb.Consume() {
			t.Error("Expected to reject after capacity with zero refill")
		}
	})

	t.Run("Negative values clamped", func(t *testing.T) {
		tb := NewTokenBucket(-1, -1)
		for i := 0; i < 3; i++ {
			tb.Consume()
		}
	})
}

func TestLeakyBucket_EdgeCases(t *testing.T) {
	t.Run("Zero capacity", func(t *testing.T) {
		lb := NewLeakyBucket(0, 1)
		if lb.AddWater() {
			t.Error("Zero capacity should reject all")
		}
	})

	t.Run("Zero leak rate", func(t *testing.T) {
		lb := NewLeakyBucket(5, 0)
		for i := 0; i < 5; i++ {
			if !lb.AddWater() {
				t.Errorf("Expected to allow request %d", i)
			}
		}
		if lb.AddWater() {
			t.Error("Expected to reject after capacity")
		}
	})
}

func TestRateLimiter_Reset(t *testing.T) {
	rl := NewRateLimiter()
	routeID := "test_reset"

	config := &models.RateLimitConfig{
		QPS:   5,
		Burst: 0,
	}

	for i := 0; i < 5; i++ {
		_, err := rl.CheckRateLimit(routeID, config, "token_bucket")
		if err != nil {
			t.Errorf("Unexpected error: %v", err)
		}
	}

	allowed, _ := rl.CheckRateLimit(routeID, config, "token_bucket")
	if allowed {
		t.Error("Should be rate limited after 5 requests")
	}

	rl.ResetRateLimit(routeID)

	allowed, _ = rl.CheckRateLimit(routeID, config, "token_bucket")
	if !allowed {
		t.Error("Should allow after reset")
	}
}
