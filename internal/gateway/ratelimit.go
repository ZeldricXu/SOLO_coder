package gateway

import (
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

type RateLimiter interface {
	Allow(key string) bool
	Reset(key string)
}

type SlidingWindowLimiter struct {
	requests map[string][]time.Time
	mu       sync.Mutex
	limit    int
	window   time.Duration
}

func NewSlidingWindowLimiter(limit int, window time.Duration) *SlidingWindowLimiter {
	return &SlidingWindowLimiter{
		requests: make(map[string][]time.Time),
		limit:    limit,
		window:   window,
	}
}

func (rl *SlidingWindowLimiter) Allow(key string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	cutoff := now.Add(-rl.window)

	requests, ok := rl.requests[key]
	if !ok {
		rl.requests[key] = []time.Time{now}
		return true
	}

	valid := make([]time.Time, 0, len(requests))
	for _, t := range requests {
		if t.After(cutoff) {
			valid = append(valid, t)
		}
	}

	if len(valid) >= rl.limit {
		rl.requests[key] = valid
		return false
	}

	valid = append(valid, now)
	rl.requests[key] = valid
	return true
}

func (rl *SlidingWindowLimiter) Reset(key string) {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	delete(rl.requests, key)
}

func (rl *SlidingWindowLimiter) ResetAll() {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	rl.requests = make(map[string][]time.Time)
}

func (rl *SlidingWindowLimiter) CleanupExpired() {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	cutoff := now.Add(-rl.window)

	for key, requests := range rl.requests {
		valid := make([]time.Time, 0, len(requests))
		for _, t := range requests {
			if t.After(cutoff) {
				valid = append(valid, t)
			}
		}
		if len(valid) == 0 {
			delete(rl.requests, key)
		} else {
			rl.requests[key] = valid
		}
	}
}

func (rl *SlidingWindowLimiter) Stats() (keyCount int, totalRequests int) {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	keyCount = len(rl.requests)
	for _, reqs := range rl.requests {
		totalRequests += len(reqs)
	}
	return keyCount, totalRequests
}

type RateLimitMiddleware struct {
	limiter   RateLimiter
	keyFunc   func(ctx *gin.Context) string
	onLimited gin.HandlerFunc
}

func NewRateLimitMiddleware(limit int, window time.Duration) *RateLimitMiddleware {
	return &RateLimitMiddleware{
		limiter: NewSlidingWindowLimiter(limit, window),
		keyFunc: func(ctx *gin.Context) string {
			return ctx.ClientIP()
		},
		onLimited: defaultOnLimited,
	}
}

func (rlm *RateLimitMiddleware) WithLimiter(limiter RateLimiter) *RateLimitMiddleware {
	rlm.limiter = limiter
	return rlm
}

func (rlm *RateLimitMiddleware) WithKeyFunc(fn func(ctx *gin.Context) string) *RateLimitMiddleware {
	rlm.keyFunc = fn
	return rlm
}

func (rlm *RateLimitMiddleware) WithOnLimited(fn gin.HandlerFunc) *RateLimitMiddleware {
	rlm.onLimited = fn
	return rlm
}

func (rlm *RateLimitMiddleware) Limit() gin.HandlerFunc {
	return func(ctx *gin.Context) {
		key := rlm.keyFunc(ctx)
		if !rlm.limiter.Allow(key) {
			rlm.onLimited(ctx)
			return
		}
		ctx.Next()
	}
}

func defaultOnLimited(ctx *gin.Context) {
	ctx.JSON(http.StatusTooManyRequests, gin.H{
		"code": 429,
		"msg":  "rate limit exceeded",
	})
	ctx.Abort()
}
