package lock_test

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/distributed-task-scheduler/pkg/lock"
	"github.com/go-redis/redis/v8"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func setupRedis(t *testing.T) *redis.Client {
	t.Helper()
	client := redis.NewClient(&redis.Options{
		Addr: "localhost:6379",
		DB:   15,
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	err := client.Ping(ctx).Err()
	if err != nil {
		t.Skip("Redis not available, skipping lock test")
	}
	client.FlushDB(ctx)
	return client
}

func TestAcquireAndRelease(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	l, err := dl.Acquire(ctx, "test-lock-1", 10*time.Second)
	require.NoError(t, err)
	assert.Equal(t, "test-lock-1", l.Key)
	assert.NotEmpty(t, l.Value)

	err = l.Release()
	assert.NoError(t, err)

	l2, err := dl.Acquire(ctx, "test-lock-1", 10*time.Second)
	require.NoError(t, err)
	l2.Release()
}

func TestAcquire_AlreadyHeld(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	l, err := dl.Acquire(ctx, "test-lock-2", 10*time.Second)
	require.NoError(t, err)
	defer l.Release()

	_, err = dl.Acquire(ctx, "test-lock-2", 10*time.Second)
	assert.Error(t, err)
}

func TestRelease_WrongOwner(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	l, err := dl.Acquire(ctx, "test-lock-3", 10*time.Second)
	require.NoError(t, err)

	client.Del(ctx, "test-lock-3")

	err = l.Release()
	assert.Error(t, err)
}

func TestLock_Expiry(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	l, err := dl.Acquire(ctx, "test-lock-expiry", 1*time.Second)
	require.NoError(t, err)

	l.Release()

	_, err = dl.Acquire(ctx, "test-lock-expiry", 10*time.Second)
	require.NoError(t, err)
}

func TestLock_TTLExpires(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	l, err := dl.Acquire(ctx, "test-lock-ttl", 1*time.Second)
	require.NoError(t, err)

	time.Sleep(2 * time.Second)

	l2, err := dl.Acquire(ctx, "test-lock-ttl", 10*time.Second)
	require.NoError(t, err)
	l2.Release()

	l.Release()
}

func TestLock_Refresh(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	l, err := dl.Acquire(ctx, "test-lock-refresh", 2*time.Second)
	require.NoError(t, err)
	defer l.Release()

	err = l.Refresh(5 * time.Second)
	require.NoError(t, err)

	assert.Equal(t, 5*time.Second, l.TTL)
}

func TestLock_RefreshExpired(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	l, err := dl.Acquire(ctx, "test-lock-refresh-exp", 1*time.Second)
	require.NoError(t, err)

	time.Sleep(2 * time.Second)

	err = l.Refresh(5 * time.Second)
	assert.Error(t, err)
}

func TestTryAcquire_Success(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	l, err := dl.TryAcquire(ctx, "test-lock-try", 10*time.Second, 5*time.Second)
	require.NoError(t, err)
	l.Release()
}

func TestTryAcquire_Timeout(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	l, err := dl.Acquire(ctx, "test-lock-try-timeout", 10*time.Second)
	require.NoError(t, err)
	defer l.Release()

	_, err = dl.TryAcquire(ctx, "test-lock-try-timeout", 10*time.Second, 500*time.Millisecond)
	assert.Error(t, err)
}

func TestIsLocked(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	locked, err := dl.IsLocked(ctx, "test-lock-check")
	require.NoError(t, err)
	assert.False(t, locked)

	l, err := dl.Acquire(ctx, "test-lock-check", 10*time.Second)
	require.NoError(t, err)

	locked, err = dl.IsLocked(ctx, "test-lock-check")
	require.NoError(t, err)
	assert.True(t, locked)

	l.Release()

	locked, err = dl.IsLocked(ctx, "test-lock-check")
	require.NoError(t, err)
	assert.False(t, locked)
}

func TestConcurrentAcquire_OnlyOneWinner(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	var winners int32
	var wg sync.WaitGroup

	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			l, err := dl.Acquire(ctx, "test-lock-concurrent", 10*time.Second)
			if err == nil {
				atomic.AddInt32(&winners, 1)
				time.Sleep(100 * time.Millisecond)
				l.Release()
			}
		}(i)
	}
	wg.Wait()

	assert.Equal(t, int32(1), atomic.LoadInt32(&winners))
}

func TestConcurrentAcquire_SequentialAfterRelease(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)
	ctx := context.Background()

	var acquired int32
	var wg sync.WaitGroup

	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for attempt := 0; attempt < 50; attempt++ {
				l, err := dl.Acquire(ctx, "test-lock-seq", 10*time.Second)
				if err == nil {
					atomic.AddInt32(&acquired, 1)
					l.Release()
					return
				}
				time.Sleep(50 * time.Millisecond)
			}
		}()
	}
	wg.Wait()

	assert.Equal(t, int32(5), atomic.LoadInt32(&acquired))
}

func TestLock_ContextCancellation(t *testing.T) {
	client := setupRedis(t)
	defer client.Close()
	dl := lock.NewDistributedLock(client)

	l1, err := dl.Acquire(context.Background(), "test-lock-ctx", 10*time.Second)
	require.NoError(t, err)
	defer l1.Release()

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err = dl.TryAcquire(ctx, "test-lock-ctx", 10*time.Second, 5*time.Second)
	assert.Error(t, err)
}
