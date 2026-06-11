package ratelimit

import (
	"context"
	"sync"
	"time"
)

type memoryTokenBucket struct {
	tokens     int64
	lastRefill int64
	mu         sync.Mutex
}

type MemoryStore struct {
	tokenBuckets    map[string]*memoryTokenBucket
	slidingWindows  map[string][]int64
	concurrencySlots map[string]map[string]int64
	mu              sync.Mutex
}

func NewMemoryStore() *MemoryStore {
	return &MemoryStore{
		tokenBuckets:    make(map[string]*memoryTokenBucket),
		slidingWindows:  make(map[string][]int64),
		concurrencySlots: make(map[string]map[string]int64),
	}
}

func (m *MemoryStore) TokenBucketTake(ctx context.Context, key string, capacity, refillRate int64, window time.Duration) (*TokenBucketResult, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	tb, exists := m.tokenBuckets[key]
	if !exists {
		tb = &memoryTokenBucket{
			tokens:     capacity,
			lastRefill: time.Now().UnixMilli(),
		}
		m.tokenBuckets[key] = tb
	}

	now := time.Now().UnixMilli()
	windowMs := window.Milliseconds()

	elapsed := now - tb.lastRefill
	refillAmount := (elapsed / windowMs) * refillRate

	if refillAmount > 0 {
		tb.tokens = min(capacity, tb.tokens+refillAmount)
		tb.lastRefill += (elapsed / windowMs) * windowMs
	}

	var allowed bool
	var remaining int64
	var resetAfter int64

	if tb.tokens >= 1 {
		tb.tokens--
		allowed = true
		remaining = tb.tokens
		resetAfter = windowMs - (now - tb.lastRefill)
	} else {
		allowed = false
		remaining = 0
		resetAfter = windowMs - (now - tb.lastRefill)
	}

	return &TokenBucketResult{
		Allowed:    allowed,
		Remaining:  remaining,
		Limit:      capacity,
		ResetAfter: time.Duration(resetAfter) * time.Millisecond,
	}, nil
}

func (m *MemoryStore) SlidingWindowAllow(ctx context.Context, key string, limit int64, window time.Duration) (*SlidingWindowResult, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now().UnixMilli()
	windowMs := window.Milliseconds()
	windowStart := now - windowMs

	windows, exists := m.slidingWindows[key]
	if !exists {
		windows = make([]int64, 0)
	}

	filtered := make([]int64, 0, len(windows))
	for _, ts := range windows {
		if ts > windowStart {
			filtered = append(filtered, ts)
		}
	}

	var allowed bool
	var remaining int64
	var resetAfter int64

	if int64(len(filtered)) < limit {
		filtered = append(filtered, now)
		allowed = true
		remaining = limit - int64(len(filtered))
	} else {
		allowed = false
		remaining = 0
	}

	m.slidingWindows[key] = filtered

	if len(filtered) > 0 {
		oldest := filtered[0]
		resetAfter = windowMs - (now - oldest)
	} else {
		resetAfter = windowMs
	}

	return &SlidingWindowResult{
		Allowed:    allowed,
		Remaining:  remaining,
		Limit:      limit,
		ResetAfter: time.Duration(resetAfter) * time.Millisecond,
	}, nil
}

func (m *MemoryStore) ConcurrencyAcquire(ctx context.Context, key string, maxConcurrent int64, requestID string, ttl time.Duration) (*ConcurrencyAcquireResult, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now().UnixMilli()
	ttlMs := ttl.Milliseconds()

	slots, exists := m.concurrencySlots[key]
	if !exists {
		slots = make(map[string]int64)
		m.concurrencySlots[key] = slots
	}

	for reqID, ts := range slots {
		if now-ts > ttlMs {
			delete(slots, reqID)
		}
	}

	var allowed bool
	var remaining int64

	if int64(len(slots)) < maxConcurrent {
		slots[requestID] = now
		allowed = true
		remaining = maxConcurrent - int64(len(slots))
	} else {
		allowed = false
		remaining = 0
	}

	releaseFunc := func() {
		m.mu.Lock()
		defer m.mu.Unlock()
		delete(slots, requestID)
	}

	return &ConcurrencyAcquireResult{
		Allowed:     allowed,
		Remaining:   remaining,
		Limit:       maxConcurrent,
		ReleaseFunc: releaseFunc,
	}, nil
}

func (m *MemoryStore) Allow(ctx context.Context, key string, opts LimitOptions) (*LimitResult, error) {
	if opts.MaxConcurrent > 0 {
		return m.allowConcurrency(ctx, key, opts)
	}
	if opts.Capacity > 0 || opts.RefillRate > 0 {
		return m.allowTokenBucket(ctx, key, opts)
	}
	return m.allowSlidingWindow(ctx, key, opts)
}

func (m *MemoryStore) allowTokenBucket(ctx context.Context, key string, opts LimitOptions) (*LimitResult, error) {
	result, err := m.TokenBucketTake(ctx, key, opts.Capacity, opts.RefillRate, opts.Window)
	if err != nil {
		return &LimitResult{Allowed: true, Remaining: opts.Capacity, Limit: opts.Capacity}, nil
	}
	return &LimitResult{
		Allowed:    result.Allowed,
		Remaining:  result.Remaining,
		Limit:      result.Limit,
		ResetAfter: result.ResetAfter,
	}, nil
}

func (m *MemoryStore) allowSlidingWindow(ctx context.Context, key string, opts LimitOptions) (*LimitResult, error) {
	result, err := m.SlidingWindowAllow(ctx, key, opts.Limit, opts.Window)
	if err != nil {
		return &LimitResult{Allowed: true, Remaining: opts.Limit, Limit: opts.Limit}, nil
	}
	return &LimitResult{
		Allowed:    result.Allowed,
		Remaining:  result.Remaining,
		Limit:      result.Limit,
		ResetAfter: result.ResetAfter,
	}, nil
}

func (m *MemoryStore) allowConcurrency(ctx context.Context, key string, opts LimitOptions) (*LimitResult, error) {
	result, err := m.ConcurrencyAcquire(ctx, key, opts.MaxConcurrent, "", 5*time.Minute)
	if err != nil {
		return &LimitResult{Allowed: true, Remaining: opts.MaxConcurrent - 1, Limit: opts.MaxConcurrent, ReleaseFunc: func() {}}, nil
	}
	return &LimitResult{
		Allowed:     result.Allowed,
		Remaining:   result.Remaining,
		Limit:       result.Limit,
		ReleaseFunc: result.ReleaseFunc,
	}, nil
}

func min(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}
