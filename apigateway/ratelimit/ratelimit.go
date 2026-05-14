package ratelimit

import (
	"apigateway/models"
	"context"
	"fmt"
	"sync"
	"time"
)

type RateLimitAlgorithm interface {
	Name() string
	Allow() (bool, error)
	Reset() error
	GetStats() map[string]interface{}
}

type TokenBucket struct {
	capacity   int
	tokens     int
	refillRate int
	lastRefill time.Time
	mu         sync.Mutex
}

func NewTokenBucket(capacity, refillRate int) *TokenBucket {
	return &TokenBucket{
		capacity:   capacity,
		tokens:     capacity,
		refillRate: refillRate,
		lastRefill: time.Now(),
	}
}

func (tb *TokenBucket) Name() string {
	return "token_bucket"
}

func (tb *TokenBucket) Allow() (bool, error) {
	tb.mu.Lock()
	defer tb.mu.Unlock()

	now := time.Now()
	elapsed := now.Sub(tb.lastRefill).Seconds()
	newTokens := int(elapsed * float64(tb.refillRate))

	if newTokens > 0 {
		tb.tokens = tb.tokens + newTokens
		if tb.tokens > tb.capacity {
			tb.tokens = tb.capacity
		}
		tb.lastRefill = now
	}

	if tb.tokens > 0 {
		tb.tokens--
		return true, nil
	}

	return false, nil
}

func (tb *TokenBucket) Reset() error {
	tb.mu.Lock()
	defer tb.mu.Unlock()
	tb.tokens = tb.capacity
	tb.lastRefill = time.Now()
	return nil
}

func (tb *TokenBucket) GetStats() map[string]interface{} {
	tb.mu.Lock()
	defer tb.mu.Unlock()
	return map[string]interface{}{
		"algorithm":  "token_bucket",
		"capacity":   tb.capacity,
		"tokens":     tb.tokens,
		"refillRate": tb.refillRate,
	}
}

func (tb *TokenBucket) Consume() bool {
	allowed, _ := tb.Allow()
	return allowed
}

func (tb *TokenBucket) TryConsumeWithBurst(burst int) bool {
	tb.mu.Lock()
	defer tb.mu.Unlock()

	now := time.Now()
	elapsed := now.Sub(tb.lastRefill).Seconds()
	newTokens := int(elapsed * float64(tb.refillRate))

	if newTokens > 0 {
		tb.tokens = tb.tokens + newTokens
		if tb.tokens > tb.capacity {
			tb.tokens = tb.capacity
		}
		tb.lastRefill = now
	}

	if tb.tokens > 0 {
		tb.tokens--
		return true
	}

	effectiveBurst := burst
	if tb.capacity > effectiveBurst {
		effectiveBurst = tb.capacity
	}

	tb.tokens = effectiveBurst
	tb.tokens--
	return true
}

type LeakyBucket struct {
	capacity   int
	waterLevel int
	leakRate   int
	lastLeak   time.Time
	mu         sync.Mutex
}

func NewLeakyBucket(capacity, leakRate int) *LeakyBucket {
	return &LeakyBucket{
		capacity:   capacity,
		waterLevel: 0,
		leakRate:   leakRate,
		lastLeak:   time.Now(),
	}
}

func (lb *LeakyBucket) Name() string {
	return "leaky_bucket"
}

func (lb *LeakyBucket) Allow() (bool, error) {
	lb.mu.Lock()
	defer lb.mu.Unlock()

	now := time.Now()
	elapsed := now.Sub(lb.lastLeak).Seconds()
	leakedWater := int(elapsed * float64(lb.leakRate))

	if leakedWater > 0 {
		lb.waterLevel = lb.waterLevel - leakedWater
		if lb.waterLevel < 0 {
			lb.waterLevel = 0
		}
		lb.lastLeak = now
	}

	if lb.waterLevel < lb.capacity {
		lb.waterLevel++
		return true, nil
	}

	return false, nil
}

func (lb *LeakyBucket) Reset() error {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	lb.waterLevel = 0
	lb.lastLeak = time.Now()
	return nil
}

func (lb *LeakyBucket) GetStats() map[string]interface{} {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	return map[string]interface{}{
		"algorithm":  "leaky_bucket",
		"capacity":   lb.capacity,
		"waterLevel": lb.waterLevel,
		"leakRate":   lb.leakRate,
	}
}

func (lb *LeakyBucket) AddWater() bool {
	allowed, _ := lb.Allow()
	return allowed
}

type SlidingWindowAlgorithm struct {
	windowSize time.Duration
	limit      int
	buckets    map[int64]int
	mu         sync.RWMutex
}

func NewSlidingWindowAlgorithm(windowSize time.Duration, limit int) *SlidingWindowAlgorithm {
	return &SlidingWindowAlgorithm{
		windowSize: windowSize,
		limit:      limit,
		buckets:    make(map[int64]int),
	}
}

func (sw *SlidingWindowAlgorithm) Name() string {
	return "sliding_window"
}

func (sw *SlidingWindowAlgorithm) Allow() (bool, error) {
	sw.mu.Lock()
	defer sw.mu.Unlock()

	now := time.Now()
	currentBucket := now.Unix()

	sw.cleanup(now)

	total := 0
	for bucket, count := range sw.buckets {
		if time.Duration(currentBucket-bucket)*time.Second <= sw.windowSize {
			total += count
		}
	}

	if total >= sw.limit {
		return false, nil
	}

	sw.buckets[currentBucket]++
	return true, nil
}

func (sw *SlidingWindowAlgorithm) cleanup(now time.Time) {
	cutoff := now.Add(-sw.windowSize).Unix()
	for bucket := range sw.buckets {
		if bucket < cutoff {
			delete(sw.buckets, bucket)
		}
	}
}

func (sw *SlidingWindowAlgorithm) Reset() error {
	sw.mu.Lock()
	defer sw.mu.Unlock()
	sw.buckets = make(map[int64]int)
	return nil
}

func (sw *SlidingWindowAlgorithm) GetStats() map[string]interface{} {
	sw.mu.RLock()
	defer sw.mu.RUnlock()

	total := 0
	now := time.Now().Unix()
	for bucket, count := range sw.buckets {
		if time.Duration(now-bucket)*time.Second <= sw.windowSize {
			total += count
		}
	}

	return map[string]interface{}{
		"algorithm":  "sliding_window",
		"windowSize": sw.windowSize.String(),
		"limit":      sw.limit,
		"current":    total,
	}
}

func (sw *SlidingWindowAlgorithm) AllowLocal() bool {
	allowed, _ := sw.Allow()
	return allowed
}

func (sw *SlidingWindowAlgorithm) GetCount() int {
	sw.mu.RLock()
	defer sw.mu.RUnlock()

	total := 0
	now := time.Now().Unix()
	cutoff := float64(now) - sw.windowSize.Seconds()

	for bucket, count := range sw.buckets {
		if float64(bucket) >= cutoff {
			total += count
		}
	}
	return total
}

type RateLimiter struct {
	algorithms    map[string]RateLimitAlgorithm
	localBuckets  map[string]*TokenBucket
	localLeaky    map[string]*LeakyBucket
	localWindows  map[string]*SlidingWindowAlgorithm
	distributed   map[string]*DistributedTokenBucket
	distWindows   map[string]*DistributedSlidingWindow
	store         RateLimitStore
	bucketsMu     sync.RWMutex
	defaultAlgo   string
}

func NewRateLimiter() *RateLimiter {
	return &RateLimiter{
		algorithms:   make(map[string]RateLimitAlgorithm),
		localBuckets: make(map[string]*TokenBucket),
		localLeaky:   make(map[string]*LeakyBucket),
		localWindows: make(map[string]*SlidingWindowAlgorithm),
		distributed:  make(map[string]*DistributedTokenBucket),
		distWindows:  make(map[string]*DistributedSlidingWindow),
		defaultAlgo:  models.AlgorithmTokenBucket,
	}
}

func NewRateLimiterWithStore(store RateLimitStore) *RateLimiter {
	return &RateLimiter{
		algorithms:   make(map[string]RateLimitAlgorithm),
		localBuckets: make(map[string]*TokenBucket),
		localLeaky:   make(map[string]*LeakyBucket),
		localWindows: make(map[string]*SlidingWindowAlgorithm),
		distributed:  make(map[string]*DistributedTokenBucket),
		distWindows:  make(map[string]*DistributedSlidingWindow),
		store:        store,
		defaultAlgo:  models.AlgorithmTokenBucket,
	}
}

func (rl *RateLimiter) SetDefaultAlgorithm(algo string) {
	rl.bucketsMu.Lock()
	defer rl.bucketsMu.Unlock()
	rl.defaultAlgo = algo
}

func (rl *RateLimiter) SetStore(store RateLimitStore) {
	rl.bucketsMu.Lock()
	defer rl.bucketsMu.Unlock()
	rl.store = store
}

func (rl *RateLimiter) getAlgorithmForKey(routeID string, config *models.RateLimitConfig) RateLimitAlgorithm {
	algorithm := config.Algorithm
	if algorithm == "" {
		algorithm = rl.defaultAlgo
	}

	distributed := config.Distributed ||
		algorithm == models.AlgorithmDistributedTokenBucket ||
		algorithm == models.AlgorithmDistributedSlidingWindow

	rl.bucketsMu.Lock()
	defer rl.bucketsMu.Unlock()

	switch algorithm {
	case models.AlgorithmTokenBucket, "":
		if _, exists := rl.localBuckets[routeID]; !exists {
			capacity := config.QPS + config.Burst
			if capacity < config.Burst {
				capacity = config.Burst
			}
			rl.localBuckets[routeID] = NewTokenBucket(capacity, config.QPS)
		}
		return rl.localBuckets[routeID]

	case models.AlgorithmLeakyBucket:
		if _, exists := rl.localLeaky[routeID]; !exists {
			rl.localLeaky[routeID] = NewLeakyBucket(config.Burst+config.QPS, config.QPS)
		}
		return rl.localLeaky[routeID]

	case models.AlgorithmSlidingWindow:
		if _, exists := rl.localWindows[routeID]; !exists {
			windowSize := time.Duration(config.WindowSize) * time.Second
			if windowSize <= 0 {
				windowSize = time.Second
			}
			rl.localWindows[routeID] = NewSlidingWindowAlgorithm(windowSize, config.QPS)
		}
		return rl.localWindows[routeID]

	case models.AlgorithmDistributedTokenBucket:
		if distributed && rl.store != nil {
			if _, exists := rl.distributed[routeID]; !exists {
				capacity := config.QPS + config.Burst
				if capacity < config.Burst {
					capacity = config.Burst
				}
				rl.distributed[routeID] = NewDistributedTokenBucket(rl.store, routeID, capacity, config.QPS)
			}
			return rl.distributed[routeID]
		}
		if _, exists := rl.localBuckets[routeID]; !exists {
			capacity := config.QPS + config.Burst
			if capacity < config.Burst {
				capacity = config.Burst
			}
			rl.localBuckets[routeID] = NewTokenBucket(capacity, config.QPS)
		}
		return rl.localBuckets[routeID]

	case models.AlgorithmDistributedSlidingWindow:
		if distributed && rl.store != nil {
			if _, exists := rl.distWindows[routeID]; !exists {
				windowSize := time.Duration(config.WindowSize) * time.Second
				if windowSize <= 0 {
					windowSize = time.Second
				}
				rl.distWindows[routeID] = NewDistributedSlidingWindow(rl.store, routeID, windowSize, config.QPS)
			}
			return rl.distWindows[routeID]
		}
		if _, exists := rl.localWindows[routeID]; !exists {
			windowSize := time.Duration(config.WindowSize) * time.Second
			if windowSize <= 0 {
				windowSize = time.Second
			}
			rl.localWindows[routeID] = NewSlidingWindowAlgorithm(windowSize, config.QPS)
		}
		return rl.localWindows[routeID]

	default:
		if _, exists := rl.localBuckets[routeID]; !exists {
			capacity := config.QPS + config.Burst
			rl.localBuckets[routeID] = NewTokenBucket(capacity, config.QPS)
		}
		return rl.localBuckets[routeID]
	}
}

func (rl *RateLimiter) CheckRateLimit(routeID string, config *models.RateLimitConfig, algorithm string) (bool, error) {
	if config == nil || config.QPS <= 0 {
		return true, nil
	}

	if algorithm != "" {
		config.Algorithm = algorithm
	}

	algo := rl.getAlgorithmForKey(routeID, config)
	return algo.Allow()
}

func (rl *RateLimiter) CreateTokenBucket(key string, qps, burst int) {
	rl.bucketsMu.Lock()
	defer rl.bucketsMu.Unlock()

	capacity := qps + burst
	if capacity < burst {
		capacity = burst
	}

	rl.localBuckets[key] = NewTokenBucket(capacity, qps)
}

func (rl *RateLimiter) CreateLeakyBucket(key string, rate, burst int) {
	rl.bucketsMu.Lock()
	defer rl.bucketsMu.Unlock()

	rl.localLeaky[key] = NewLeakyBucket(burst, rate)
}

func (rl *RateLimiter) ResetRateLimit(routeID string) {
	rl.bucketsMu.Lock()
	defer rl.bucketsMu.Unlock()

	if tb, exists := rl.localBuckets[routeID]; exists {
		tb.Reset()
	}
	if lb, exists := rl.localLeaky[routeID]; exists {
		lb.Reset()
	}
	if sw, exists := rl.localWindows[routeID]; exists {
		sw.Reset()
	}

	delete(rl.localBuckets, routeID)
	delete(rl.localLeaky, routeID)
	delete(rl.localWindows, routeID)
	delete(rl.distributed, routeID)
	delete(rl.distWindows, routeID)
}

func (rl *RateLimiter) GetTokenBucketStatus(routeID string) (map[string]interface{}, bool) {
	rl.bucketsMu.RLock()
	defer rl.bucketsMu.RUnlock()

	tb, exists := rl.localBuckets[routeID]
	if !exists {
		return nil, false
	}
	return tb.GetStats(), true
}

func (rl *RateLimiter) GetLeakyBucketStatus(routeID string) (map[string]interface{}, bool) {
	rl.bucketsMu.RLock()
	defer rl.bucketsMu.RUnlock()

	lb, exists := rl.localLeaky[routeID]
	if !exists {
		return nil, false
	}
	return lb.GetStats(), true
}

func (rl *RateLimiter) GetAlgorithmStats(routeID string) (map[string]interface{}, bool) {
	rl.bucketsMu.RLock()
	defer rl.bucketsMu.RUnlock()

	if tb, exists := rl.localBuckets[routeID]; exists {
		return tb.GetStats(), true
	}
	if lb, exists := rl.localLeaky[routeID]; exists {
		return lb.GetStats(), true
	}
	if sw, exists := rl.localWindows[routeID]; exists {
		return sw.GetStats(), true
	}
	return nil, false
}

func (rl *RateLimiter) GetStore() RateLimitStore {
	rl.bucketsMu.RLock()
	defer rl.bucketsMu.RUnlock()
	return rl.store
}

func (rl *RateLimiter) CheckRateLimitWithContext(ctx context.Context, routeID string, config *models.RateLimitConfig) (bool, error) {
	if config == nil || config.QPS <= 0 {
		return true, nil
	}

	algorithm := config.Algorithm
	if algorithm == "" {
		algorithm = rl.defaultAlgo
	}

	distributed := config.Distributed ||
		algorithm == models.AlgorithmDistributedTokenBucket ||
		algorithm == models.AlgorithmDistributedSlidingWindow

	if distributed && rl.store != nil {
		state, err := rl.store.Get(ctx, routeID)
		if err != nil && IsRedisError(err) {
			return true, nil
		}

		now := time.Now()

		if state == nil {
			state = &RateLimitState{
				Tokens:      int64(config.QPS + config.Burst),
				LastRefill:  now,
				WindowStart: now,
			}
			rl.store.Set(ctx, routeID, state, time.Minute)
		}

		switch algorithm {
		case models.AlgorithmDistributedTokenBucket:
			elapsed := now.Sub(state.LastRefill).Seconds()
			newTokens := int(elapsed * float64(config.QPS))
			if newTokens > 0 {
				state.Tokens = state.Tokens + int64(newTokens)
				maxTokens := int64(config.QPS + config.Burst)
				if state.Tokens > maxTokens {
					state.Tokens = maxTokens
				}
				state.LastRefill = now
				rl.store.Set(ctx, routeID, state, time.Minute)
			}

			if state.Tokens <= 0 {
				return false, nil
			}
			rl.store.Decr(ctx, routeID, "tokens")
			return true, nil

		case models.AlgorithmDistributedSlidingWindow:
			windowSize := time.Duration(config.WindowSize) * time.Second
			if windowSize <= 0 {
				windowSize = time.Second
			}

			if now.Sub(state.WindowStart) > windowSize {
				state.WindowStart = now
				state.RequestCount = 1
				rl.store.Set(ctx, routeID, state, windowSize)
				return true, nil
			}

			if state.RequestCount >= int64(config.QPS) {
				return false, nil
			}

			rl.store.Incr(ctx, routeID, "request_count")
			return true, nil
		}
	}

	return rl.CheckRateLimit(routeID, config, algorithm)
}

func (rl *RateLimiter) CheckDistributed(ctx context.Context, routeID string, config *models.RateLimitConfig) (bool, error) {
	if rl.store == nil {
		return rl.CheckRateLimit(routeID, config, config.Algorithm)
	}
	return rl.CheckRateLimitWithContext(ctx, routeID, config)
}

func GetDefaultRateLimitConfig() models.RateLimitConfig {
	return models.RateLimitConfig{
		QPS:         100,
		Burst:       20,
		Algorithm:   models.AlgorithmTokenBucket,
		WindowSize:  1,
		Distributed: false,
	}
}

func ValidateRateLimitConfig(config *models.RateLimitConfig) error {
	if config == nil {
		return fmt.Errorf("rate limit config is nil")
	}

	if config.QPS < 0 {
		return fmt.Errorf("QPS cannot be negative")
	}

	if config.Burst < 0 {
		return fmt.Errorf("burst cannot be negative")
	}

	if config.WindowSize < 0 {
		return fmt.Errorf("window size cannot be negative")
	}

	validAlgorithms := map[string]bool{
		models.AlgorithmTokenBucket:              true,
		models.AlgorithmLeakyBucket:              true,
		models.AlgorithmSlidingWindow:            true,
		models.AlgorithmDistributedTokenBucket:   true,
		models.AlgorithmDistributedSlidingWindow: true,
		"": true,
	}

	if !validAlgorithms[config.Algorithm] {
		return fmt.Errorf("unsupported rate limit algorithm: %s", config.Algorithm)
	}

	return nil
}
