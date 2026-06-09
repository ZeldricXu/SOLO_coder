package ratelimit

import (
	"context"
	"fmt"
	"time"
)

type ConcurrencyStore interface {
	ConcurrencyAcquire(ctx context.Context, key string, maxConcurrent int64, requestID string, ttl time.Duration) (*ConcurrencyAcquireResult, error)
}

type Concurrency struct {
	store ConcurrencyStore
}

func NewConcurrency(store ConcurrencyStore) *Concurrency {
	return &Concurrency{
		store: store,
	}
}

func (c *Concurrency) Acquire(ctx context.Context, key string, maxConcurrent int64) (bool, func(), error) {
	if maxConcurrent <= 0 {
		maxConcurrent = 100
	}

	requestID := fmt.Sprintf("%s:%d", key, time.Now().UnixNano())
	ttl := 5 * time.Minute

	result, err := c.store.ConcurrencyAcquire(ctx, key, maxConcurrent, requestID, ttl)
	if err != nil {
		return true, func() {}, nil
	}

	return result.Allowed, result.ReleaseFunc, nil
}
