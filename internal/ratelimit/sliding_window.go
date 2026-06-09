package ratelimit

import (
	"context"
	"time"
)

type SlidingWindowStore interface {
	SlidingWindowAllow(ctx context.Context, key string, limit int64, window time.Duration) (*SlidingWindowResult, error)
}

type SlidingWindow struct {
	store SlidingWindowStore
}

func NewSlidingWindow(store SlidingWindowStore) *SlidingWindow {
	return &SlidingWindow{
		store: store,
	}
}

func (sw *SlidingWindow) Allow(ctx context.Context, key string, limit int64, window time.Duration) (bool, int64, int64, time.Duration) {
	if limit <= 0 {
		limit = 100
	}
	if window <= 0 {
		window = time.Minute
	}

	result, err := sw.store.SlidingWindowAllow(ctx, key, limit, window)
	if err != nil {
		return true, limit, limit, 0
	}

	return result.Allowed, result.Remaining, result.Limit, result.ResetAfter
}
