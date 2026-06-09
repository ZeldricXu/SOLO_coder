package ratelimit

import (
	"context"
	"time"
)

type TokenBucket struct {
	store *RedisStore
}

func NewTokenBucket(store *RedisStore) *TokenBucket {
	return &TokenBucket{
		store: store,
	}
}

func (tb *TokenBucket) Take(ctx context.Context, key string, capacity, refillRate int64, window time.Duration) (bool, int64, int64, time.Duration) {
	if capacity <= 0 {
		capacity = 100
	}
	if refillRate <= 0 {
		refillRate = 10
	}
	if window <= 0 {
		window = time.Second
	}

	result, err := tb.store.TokenBucketTake(ctx, key, capacity, refillRate, window)
	if err != nil {
		return true, capacity, capacity, 0
	}

	return result.Allowed, result.Remaining, result.Limit, result.ResetAfter
}
