package gateway

import (
	"testing"
	"time"
)

func TestSlidingWindowLimiter_Allow(t *testing.T) {
	limiter := NewSlidingWindowLimiter(3, 100*time.Millisecond)
	key := "test"

	if !limiter.Allow(key) {
		t.Error("expected first request to be allowed")
	}
	if !limiter.Allow(key) {
		t.Error("expected second request to be allowed")
	}
	if !limiter.Allow(key) {
		t.Error("expected third request to be allowed")
	}
	if limiter.Allow(key) {
		t.Error("expected fourth request to be blocked")
	}

	time.Sleep(150 * time.Millisecond)

	if !limiter.Allow(key) {
		t.Error("expected request to be allowed after window reset")
	}
}

func TestRateLimiterInterface(t *testing.T) {
	var limiter RateLimiter = NewSlidingWindowLimiter(10, time.Second)
	if limiter == nil {
		t.Error("SlidingWindowLimiter should implement RateLimiter interface")
	}
}
