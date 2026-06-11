package ratelimit

import (
	"context"
	"time"
)

type RateLimiter interface {
	Allow(ctx context.Context, key string, opts LimitOptions) (*LimitResult, error)
}

type LimitOptions struct {
	Capacity     int64
	Limit        int64
	RefillRate   int64
	Window       time.Duration
	MaxConcurrent int64
}

type LimitResult struct {
	Allowed    bool
	Remaining  int64
	Limit      int64
	ResetAfter time.Duration
	ReleaseFunc func()
}
