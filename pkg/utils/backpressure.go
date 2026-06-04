package utils

import (
	"context"
	"sync"
	"time"
)

type BackPressure struct {
	mu           sync.RWMutex
	capacity     int
	current      int
	threshold    float64
	lastDropTime time.Time
	dropCount    int64
}

func NewBackPressure(capacity int) *BackPressure {
	return &BackPressure{
		capacity:  capacity,
		threshold: 0.8,
	}
}

func (bp *BackPressure) TryAcquire(ctx context.Context) bool {
	bp.mu.Lock()
	defer bp.mu.Unlock()

	if bp.current >= bp.capacity {
		bp.dropCount++
		bp.lastDropTime = time.Now()
		return false
	}

	load := float64(bp.current) / float64(bp.capacity)
	if load > bp.threshold {
		select {
		case <-ctx.Done():
			return false
		case <-time.After(time.Millisecond * time.Duration(load*100)):
		}
	}

	bp.current++
	return true
}

func (bp *BackPressure) Release() {
	bp.mu.Lock()
	defer bp.mu.Unlock()
	if bp.current > 0 {
		bp.current--
	}
}

func (bp *BackPressure) Drop() {
	bp.mu.Lock()
	defer bp.mu.Unlock()
	bp.dropCount++
	bp.lastDropTime = time.Now()
}

func (bp *BackPressure) Stats() map[string]interface{} {
	bp.mu.RLock()
	defer bp.mu.RUnlock()
	return map[string]interface{}{
		"capacity":  bp.capacity,
		"current":   bp.current,
		"load":      float64(bp.current) / float64(bp.capacity),
		"dropCount": bp.dropCount,
	}
}

type RateLimiter struct {
	tokens    float64
	capacity  float64
	rate      float64
	lastTime  time.Time
	mu        sync.Mutex
}

func NewRateLimiter(ratePerSec float64, capacity float64) *RateLimiter {
	return &RateLimiter{
		tokens:   capacity,
		capacity: capacity,
		rate:     ratePerSec,
		lastTime: time.Now(),
	}
}

func (rl *RateLimiter) Allow() bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	elapsed := now.Sub(rl.lastTime).Seconds()
	rl.tokens = min(rl.capacity, rl.tokens+elapsed*rl.rate)
	rl.lastTime = now

	if rl.tokens >= 1 {
		rl.tokens--
		return true
	}
	return false
}

func min(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}
