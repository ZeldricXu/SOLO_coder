package tests

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"session130/internal/core"
)

func TestNewResourcePool(t *testing.T) {
	t.Run("normal initialization", func(t *testing.T) {
		pool := core.NewResourcePool(10, 5, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test", Type: "worker"}, nil
		})
		assert.NotNil(t, pool)

		stats := pool.Stats()
		assert.Equal(t, 10, stats["max_size"])
		assert.Equal(t, 5, stats["min_idle"])
		assert.Equal(t, 5, stats["current_size"])
		assert.Equal(t, int64(5), stats["total_created"])
	})

	t.Run("zero min idle", func(t *testing.T) {
		pool := core.NewResourcePool(10, 0, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})
		stats := pool.Stats()
		assert.Equal(t, 0, stats["current_size"])
	})

	t.Run("min idle greater than max size", func(t *testing.T) {
		pool := core.NewResourcePool(5, 10, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})
		stats := pool.Stats()
		assert.Equal(t, 5, stats["current_size"])
	})

	t.Run("factory error during prewarm", func(t *testing.T) {
		errorCount := int32(0)
		pool := core.NewResourcePool(10, 5, func() (*core.PooledResource, error) {
			if atomic.AddInt32(&errorCount, 1) <= 3 {
				return nil, errors.New("factory error")
			}
			return &core.PooledResource{ID: "test"}, nil
		})
		stats := pool.Stats()
		assert.Equal(t, 2, stats["current_size"])
	})
}

func TestResourcePoolAcquire(t *testing.T) {
	t.Run("acquire from prewarmed pool", func(t *testing.T) {
		pool := core.NewResourcePool(10, 5, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		res, err := pool.Acquire(context.Background())
		assert.NoError(t, err)
		assert.NotNil(t, res)

		stats := pool.Stats()
		assert.Equal(t, 4, stats["current_size"])
	})

	t.Run("acquire creates new resource when needed", func(t *testing.T) {
		pool := core.NewResourcePool(10, 1, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		_, _ = pool.Acquire(context.Background())
		res, err := pool.Acquire(context.Background())
		assert.NoError(t, err)
		assert.NotNil(t, res)

		stats := pool.Stats()
		assert.Equal(t, int64(2), stats["total_created"])
	})

	t.Run("acquire with timeout", func(t *testing.T) {
		pool := core.NewResourcePool(1, 0, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		_, _ = pool.Acquire(context.Background())

		ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
		defer cancel()

		res, err := pool.Acquire(ctx)
		assert.Error(t, err)
		assert.Nil(t, res)
	})

	t.Run("acquire with cancelled context", func(t *testing.T) {
		pool := core.NewResourcePool(10, 5, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		ctx, cancel := context.WithCancel(context.Background())
		cancel()

		res, err := pool.Acquire(ctx)
		assert.Error(t, err)
		assert.Nil(t, res)
	})

	t.Run("acquire respects max size", func(t *testing.T) {
		pool := core.NewResourcePool(2, 0, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		res1, err1 := pool.Acquire(context.Background())
		res2, err2 := pool.Acquire(context.Background())

		assert.NoError(t, err1)
		assert.NoError(t, err2)
		assert.NotNil(t, res1)
		assert.NotNil(t, res2)

		stats := pool.Stats()
		assert.Equal(t, int64(2), stats["total_created"])
	})
}

func TestResourcePoolRelease(t *testing.T) {
	t.Run("release nil resource", func(t *testing.T) {
		pool := core.NewResourcePool(10, 5, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		assert.NotPanics(t, func() {
			pool.Release(nil)
		})
	})

	t.Run("release and reuse resource", func(t *testing.T) {
		pool := core.NewResourcePool(10, 1, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		res, _ := pool.Acquire(context.Background())
		pool.Release(res)

		stats := pool.Stats()
		assert.Equal(t, 1, stats["current_size"])
		assert.Equal(t, int64(1), stats["total_reused"])
	})

	t.Run("release when pool is full", func(t *testing.T) {
		pool := core.NewResourcePool(1, 0, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		res, _ := pool.Acquire(context.Background())
		pool.Release(res)
		pool.Release(res)

		stats := pool.Stats()
		assert.Equal(t, 1, stats["current_size"])
	})
}

func TestResourcePoolConcurrent(t *testing.T) {
	t.Run("concurrent acquire and release", func(t *testing.T) {
		pool := core.NewResourcePool(50, 10, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		var wg sync.WaitGroup
		iterations := 1000

		for i := 0; i < iterations; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				res, err := pool.Acquire(context.Background())
				if err == nil {
					time.Sleep(time.Millisecond)
					pool.Release(res)
				}
			}()
		}

		wg.Wait()

		stats := pool.Stats()
		t.Logf("Stats: %+v", stats)
		assert.Greater(t, stats["total_created"], int64(0))
	})

	t.Run("concurrent acquire with timeout", func(t *testing.T) {
		pool := core.NewResourcePool(5, 0, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		var wg sync.WaitGroup
		successCount := int32(0)
		timeoutCount := int32(0)

		for i := 0; i < 20; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
				defer cancel()
				res, err := pool.Acquire(ctx)
				if err == nil {
					atomic.AddInt32(&successCount, 1)
					time.Sleep(20 * time.Millisecond)
					pool.Release(res)
				} else {
					atomic.AddInt32(&timeoutCount, 1)
				}
			}()
		}

		wg.Wait()

		t.Logf("Success: %d, Timeout: %d", successCount, timeoutCount)
		assert.Greater(t, successCount, int32(0))
	})

	t.Run("concurrent stats read", func(t *testing.T) {
		pool := core.NewResourcePool(100, 50, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		var wg sync.WaitGroup

		for i := 0; i < 100; i++ {
			wg.Add(2)
			go func() {
				defer wg.Done()
				res, _ := pool.Acquire(context.Background())
				if res != nil {
					pool.Release(res)
				}
			}()
			go func() {
				defer wg.Done()
				stats := pool.Stats()
				assert.NotNil(t, stats)
			}()
		}

		wg.Wait()
	})
}

func TestResourcePoolEdgeCases(t *testing.T) {
	t.Run("empty pool acquire", func(t *testing.T) {
		pool := core.NewResourcePool(10, 0, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		res, err := pool.Acquire(context.Background())
		assert.NoError(t, err)
		assert.NotNil(t, res)
	})

	t.Run("factory always fails", func(t *testing.T) {
		pool := core.NewResourcePool(10, 0, func() (*core.PooledResource, error) {
			return nil, errors.New("factory always fails")
		})

		ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
		defer cancel()

		res, err := pool.Acquire(ctx)
		assert.Error(t, err)
		assert.Nil(t, res)
	})

	t.Run("large max size", func(t *testing.T) {
		pool := core.NewResourcePool(10000, 100, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		stats := pool.Stats()
		assert.Equal(t, 100, stats["current_size"])
	})

	t.Run("single resource pool", func(t *testing.T) {
		pool := core.NewResourcePool(1, 1, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		res1, err1 := pool.Acquire(context.Background())
		assert.NoError(t, err1)
		assert.NotNil(t, res1)

		go func() {
			time.Sleep(50 * time.Millisecond)
			pool.Release(res1)
		}()

		res2, err2 := pool.Acquire(context.Background())
		assert.NoError(t, err2)
		assert.NotNil(t, res2)
	})
}

func TestResourcePoolClose(t *testing.T) {
	t.Run("close pool", func(t *testing.T) {
		pool := core.NewResourcePool(10, 5, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		assert.NotPanics(t, func() {
			pool.Close()
		})
	})
}

func TestResourcePoolWithData(t *testing.T) {
	t.Run("resource data modification", func(t *testing.T) {
		pool := core.NewResourcePool(10, 1, func() (*core.PooledResource, error) {
			return &core.PooledResource{
				ID:   "test",
				Type: "worker",
				Data: make(map[string]interface{}),
			}, nil
		})

		res, _ := pool.Acquire(context.Background())
		res.Data["key"] = "value"
		pool.Release(res)

		res2, _ := pool.Acquire(context.Background())
		assert.Equal(t, "value", res2.Data["key"])
	})
}
