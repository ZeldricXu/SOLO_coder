package ratelimit

import (
	"sync"
	"time"
)

type SlidingWindow struct {
	windowSize time.Duration
	limit      int
	buckets    map[int64]int
	mu         sync.RWMutex
}

func NewSlidingWindow(windowSize time.Duration, limit int) *SlidingWindow {
	return &SlidingWindow{
		windowSize: windowSize,
		limit:      limit,
		buckets:    make(map[int64]int),
	}
}

func (sw *SlidingWindow) Allow() bool {
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
		return false
	}

	sw.buckets[currentBucket]++
	return true
}

func (sw *SlidingWindow) cleanup(now time.Time) {
	cutoff := now.Add(-sw.windowSize).Unix()
	for bucket := range sw.buckets {
		if bucket < cutoff {
			delete(sw.buckets, bucket)
		}
	}
}

func (sw *SlidingWindow) GetCount() int {
	sw.mu.RLock()
	defer sw.mu.RUnlock()

	total := 0
	now := time.Now().Unix()
	cutoff := time.Duration(now) - sw.windowSize.Seconds()

	for bucket, count := range sw.buckets {
		if float64(bucket) >= cutoff {
			total += count
		}
	}
	return total
}

func (sw *SlidingWindow) Reset() {
	sw.mu.Lock()
	defer sw.mu.Unlock()
	sw.buckets = make(map[int64]int)
}

type DistributedSlidingWindow struct {
	store      RateLimitStore
	key        string
	windowSize time.Duration
	limit      int
}

func NewDistributedSlidingWindow(store RateLimitStore, key string, windowSize time.Duration, limit int) *DistributedSlidingWindow {
	return &DistributedSlidingWindow{
		store:      store,
		key:        key,
		windowSize: windowSize,
		limit:      limit,
	}
}

func (dsw *DistributedSlidingWindow) Allow() (bool, error) {
	state, err := dsw.store.Get(dsw.key)
	if err != nil {
		return true, nil
	}

	now := time.Now()
	if state == nil || now.Sub(state.WindowStart) > dsw.windowSize {
		newState := &RateLimitState{
			RequestCount: 1,
			WindowStart:  now,
		}
		dsw.store.Set(dsw.key, newState, dsw.windowSize)
		return true, nil
	}

	if state.RequestCount >= int64(dsw.limit) {
		return false, nil
	}

	_, err = dsw.store.Incr(dsw.key, "request_count")
	if err != nil {
		return true, nil
	}

	return true, nil
}

func (dsw *DistributedSlidingWindow) Reset() error {
	return dsw.store.Delete(dsw.key)
}

type DistributedTokenBucket struct {
	store      RateLimitStore
	key        string
	capacity   int
	refillRate int
}

func NewDistributedTokenBucket(store RateLimitStore, key string, capacity, refillRate int) *DistributedTokenBucket {
	return &DistributedTokenBucket{
		store:      store,
		key:        key,
		capacity:   capacity,
		refillRate: refillRate,
	}
}

func (dtb *DistributedTokenBucket) Allow() (bool, error) {
	state, err := dtb.store.Get(dtb.key)
	if err != nil {
		return true, nil
	}

	now := time.Now()

	if state == nil {
		state = &RateLimitState{
			Tokens:     int64(dtb.capacity),
			LastRefill: now,
		}
		dtb.store.Set(dtb.key, state, time.Minute)
	}

	elapsed := now.Sub(state.LastRefill).Seconds()
	newTokens := int(elapsed * float64(dtb.refillRate))

	if newTokens > 0 {
		state.Tokens = state.Tokens + int64(newTokens)
		if state.Tokens > int64(dtb.capacity) {
			state.Tokens = int64(dtb.capacity)
		}
		state.LastRefill = now
		dtb.store.Set(dtb.key, state, time.Minute)
	}

	if state.Tokens <= 0 {
		return false, nil
	}

	_, err = dtb.store.Decr(dtb.key, "tokens")
	if err != nil {
		return true, nil
	}

	return true, nil
}

func (dtb *DistributedTokenBucket) Reset() error {
	return dtb.store.Delete(dtb.key)
}

func (dtb *DistributedTokenBucket) GetTokens() (int64, error) {
	state, err := dtb.store.Get(dtb.key)
	if err != nil {
		return 0, err
	}
	if state == nil {
		return int64(dtb.capacity), nil
	}
	return state.Tokens, nil
}
