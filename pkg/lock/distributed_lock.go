package lock

import (
	"context"
	"fmt"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/google/uuid"
)

type DistributedLock struct {
	client *redis.Client
}

type Lock struct {
	Key    string
	Value  string
	TTL    time.Duration
	ctx    context.Context
	client *redis.Client
}

func NewDistributedLock(client *redis.Client) *DistributedLock {
	return &DistributedLock{client: client}
}

func (dl *DistributedLock) Acquire(ctx context.Context, key string, ttl time.Duration) (*Lock, error) {
	value := uuid.New().String()

	ok, err := dl.client.SetNX(ctx, key, value, ttl).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to acquire lock: %w", err)
	}

	if !ok {
		return nil, fmt.Errorf("lock already held: %s", key)
	}

	return &Lock{
		Key:    key,
		Value:  value,
		TTL:    ttl,
		ctx:    ctx,
		client: dl.client,
	}, nil
}

func (dl *DistributedLock) TryAcquire(ctx context.Context, key string, ttl time.Duration, timeout time.Duration) (*Lock, error) {
	deadline := time.Now().Add(timeout)

	for time.Now().Before(deadline) {
		lock, err := dl.Acquire(ctx, key, ttl)
		if err == nil {
			return lock, nil
		}

		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}

	return nil, fmt.Errorf("timeout waiting for lock: %s", key)
}

func (l *Lock) Release() error {
	script := `
		if redis.call("get", KEYS[1]) == ARGV[1] then
			return redis.call("del", KEYS[1])
		else
			return 0
		end
	`

	result, err := l.client.Eval(l.ctx, script, []string{l.Key}, l.Value).Result()
	if err != nil {
		return fmt.Errorf("failed to release lock: %w", err)
	}

	if result.(int64) == 0 {
		return fmt.Errorf("lock expired or not owned")
	}

	return nil
}

func (l *Lock) Refresh(ttl time.Duration) error {
	script := `
		if redis.call("get", KEYS[1]) == ARGV[1] then
			return redis.call("pexpire", KEYS[1], ARGV[2])
		else
			return 0
		end
	`

	ttlMs := fmt.Sprintf("%d", ttl.Milliseconds())
	result, err := l.client.Eval(l.ctx, script, []string{l.Key}, l.Value, ttlMs).Result()
	if err != nil {
		return fmt.Errorf("failed to refresh lock: %w", err)
	}

	if result.(int64) == 0 {
		return fmt.Errorf("lock expired or not owned")
	}

	l.TTL = ttl
	return nil
}

func (dl *DistributedLock) IsLocked(ctx context.Context, key string) (bool, error) {
	result, err := dl.client.Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check lock: %w", err)
	}
	return result > 0, nil
}
