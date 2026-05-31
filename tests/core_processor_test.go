package tests

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
	"session130/internal/config"
	"session130/internal/core"
	"session130/pkg/models"
)

type MockConfigManager struct {
	mock.Mock
}

func (m *MockConfigManager) GetConfig(namespace string) (*models.Config, error) {
	args := m.Called(namespace)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*models.Config), args.Error(1)
}

func TestNewProcessor(t *testing.T) {
	t.Run("create processor", func(t *testing.T) {
		p := core.NewProcessor()
		assert.NotNil(t, p)

		stats := p.GetStats()
		assert.NotNil(t, stats)
	})
}

func TestProcessor_ValidateParams(t *testing.T) {
	p := core.NewProcessor()

	t.Run("nil payload", func(t *testing.T) {
		err := p.validateParams(nil)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "payload is required")
	})

	t.Run("empty payload", func(t *testing.T) {
		err := p.validateParams(map[string]interface{}{})
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "type field is required")
	})

	t.Run("payload with type only", func(t *testing.T) {
		err := p.validateParams(map[string]interface{}{
			"type": "test",
		})
		assert.NoError(t, err)
	})

	t.Run("payload with extra fields", func(t *testing.T) {
		err := p.validateParams(map[string]interface{}{
			"type":  "test",
			"extra": "field",
			"num":   123,
		})
		assert.NoError(t, err)
	})

	t.Run("large payload", func(t *testing.T) {
		largePayload := make(map[string]interface{})
		for i := 0; i < 1000; i++ {
			largePayload[string(rune('a'+i%26))+string(rune('0'+i%10))] = i
		}
		largePayload["type"] = "test"

		err := p.validateParams(largePayload)
		assert.NoError(t, err)
	})
}

func TestProcessor_ResolvePoolName(t *testing.T) {
	p := core.NewProcessor()

	t.Run("pool name from params", func(t *testing.T) {
		name := p.resolvePoolName(map[string]interface{}{
			"pool_name": "custom_pool",
		})
		assert.Equal(t, "custom_pool", name)
	})

	t.Run("default pool name", func(t *testing.T) {
		name := p.resolvePoolName(map[string]interface{}{})
		assert.Equal(t, "default", name)
	})

	t.Run("nil params", func(t *testing.T) {
		name := p.resolvePoolName(nil)
		assert.Equal(t, "default", name)
	})

	t.Run("non-string pool name", func(t *testing.T) {
		name := p.resolvePoolName(map[string]interface{}{
			"pool_name": 123,
		})
		assert.Equal(t, "default", name)
	})
}

func TestProcessor_ExecuteHandler(t *testing.T) {
	p := core.NewProcessor()

	t.Run("nil payload validation error", func(t *testing.T) {
		req := &models.APIRequest{
			TraceID:   "test-trace-001",
			Namespace: "test",
			Payload:   nil,
		}

		resp, err := p.ExecuteHandler(context.Background(), req)
		assert.NoError(t, err)
		assert.Equal(t, 422, resp.Code)
		assert.Contains(t, resp.Message, "payload is required")
	})

	t.Run("missing type validation error", func(t *testing.T) {
		req := &models.APIRequest{
			TraceID:   "test-trace-002",
			Namespace: "test",
			Payload:   map[string]interface{}{},
		}

		resp, err := p.ExecuteHandler(context.Background(), req)
		assert.NoError(t, err)
		assert.Equal(t, 422, resp.Code)
		assert.Contains(t, resp.Message, "type field is required")
	})

	t.Run("config not found error", func(t *testing.T) {
		req := &models.APIRequest{
			TraceID:   "test-trace-003",
			Namespace: "nonexistent-namespace",
			Payload:   map[string]interface{}{"type": "test"},
		}

		resp, err := p.ExecuteHandler(context.Background(), req)
		assert.NoError(t, err)
		assert.Equal(t, 404, resp.Code)
		assert.Contains(t, resp.Message, "config not found")
	})

	t.Run("successful execution", func(t *testing.T) {
		req := &models.APIRequest{
			TraceID:   "test-trace-004",
			Namespace: "production",
			Payload: map[string]interface{}{
				"type": "job",
				"data": "test data",
			},
		}

		resp, err := p.ExecuteHandler(context.Background(), req)
		assert.NoError(t, err)
		assert.Equal(t, 200, resp.Code)
		assert.NotNil(t, resp.Data)
		assert.Equal(t, true, resp.Data["received"])
		assert.Equal(t, true, resp.Data["processed"])
	})

	t.Run("execution without trace ID", func(t *testing.T) {
		req := &models.APIRequest{
			Namespace: "production",
			Payload: map[string]interface{}{
				"type": "job",
			},
		}

		resp, err := p.ExecuteHandler(context.Background(), req)
		assert.NoError(t, err)
		assert.Equal(t, 200, resp.Code)
	})

	t.Run("execution with empty namespace", func(t *testing.T) {
		req := &models.APIRequest{
			TraceID:   "test-trace-005",
			Namespace: "",
			Payload: map[string]interface{}{
				"type": "job",
			},
		}

		resp, err := p.ExecuteHandler(context.Background(), req)
		assert.NoError(t, err)
		assert.Equal(t, 404, resp.Code)
	})
}

func TestProcessor_ProcessCore(t *testing.T) {
	p := core.NewProcessor()
	resource := &core.PooledResource{
		ID:   "res-001",
		Type: "worker",
		Data: make(map[string]interface{}),
	}

	t.Run("normal processing", func(t *testing.T) {
		payload := map[string]interface{}{
			"type": "job",
			"key":  "value",
		}
		rules := map[string]interface{}{
			"timeout": 30,
			"retries": 3,
		}

		result, err := p.processCore(payload, rules, resource)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Equal(t, true, result["received"])
		assert.Equal(t, true, result["processed"])
		assert.Equal(t, "res-001", result["resource_id"])
		assert.Equal(t, 30, result["timeout_applied"])
		assert.Equal(t, 3, result["retries_configured"])
		assert.Equal(t, "job", result["entity_type"])
	})

	t.Run("empty payload and rules", func(t *testing.T) {
		result, err := p.processCore(map[string]interface{}{}, map[string]interface{}{}, resource)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Equal(t, true, result["received"])
		assert.Nil(t, result["timeout_applied"])
		assert.Nil(t, result["retries_configured"])
	})

	t.Run("nil resource data", func(t *testing.T) {
		nilDataResource := &core.PooledResource{
			ID:   "res-002",
			Data: nil,
		}
		result, err := p.processCore(map[string]interface{}{"type": "test"}, map[string]interface{}{}, nilDataResource)
		assert.NoError(t, err)
		assert.NotNil(t, result)
	})

	t.Run("non-integer timeout", func(t *testing.T) {
		rules := map[string]interface{}{
			"timeout": "30",
		}
		result, err := p.processCore(map[string]interface{}{"type": "test"}, rules, resource)
		assert.NoError(t, err)
		assert.Nil(t, result["timeout_applied"])
	})
}

func TestProcessor_RegisterPool(t *testing.T) {
	p := core.NewProcessor()

	t.Run("register pool", func(t *testing.T) {
		p.RegisterPool("test_pool", core.PoolConfig{
			MaxSize: 10,
			MinIdle: 2,
			Type:    "worker",
		}, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		pool, exists := p.GetPool("test_pool")
		assert.True(t, exists)
		assert.NotNil(t, pool)
	})

	t.Run("register pool overwrites existing", func(t *testing.T) {
		p.RegisterPool("overwrite_pool", core.PoolConfig{
			MaxSize: 5,
			MinIdle: 1,
		}, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "v1"}, nil
		})

		p.RegisterPool("overwrite_pool", core.PoolConfig{
			MaxSize: 20,
			MinIdle: 5,
		}, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "v2"}, nil
		})

		pool, _ := p.GetPool("overwrite_pool")
		stats := pool.Stats()
		assert.Equal(t, 20, stats["max_size"])
	})
}

func TestProcessor_GetPool(t *testing.T) {
	p := core.NewProcessor()

	t.Run("get existing pool", func(t *testing.T) {
		p.RegisterPool("existing_pool", core.PoolConfig{
			MaxSize: 10,
			MinIdle: 1,
		}, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		pool, exists := p.GetPool("existing_pool")
		assert.True(t, exists)
		assert.NotNil(t, pool)
	})

	t.Run("get non-existing pool", func(t *testing.T) {
		pool, exists := p.GetPool("nonexistent_pool")
		assert.False(t, exists)
		assert.Nil(t, pool)
	})
}

func TestProcessor_Concurrent(t *testing.T) {
	p := core.NewProcessor()

	t.Run("concurrent execution", func(t *testing.T) {
		var wg sync.WaitGroup
		successCount := int32(0)
		iterations := 100

		for i := 0; i < iterations; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				req := &models.APIRequest{
					TraceID:   string(rune(idx)),
					Namespace: "production",
					Payload: map[string]interface{}{
						"type": "job",
						"idx":  idx,
					},
				}
				resp, err := p.ExecuteHandler(context.Background(), req)
				if err == nil && resp.Code == 200 {
					atomic.AddInt32(&successCount, 1)
				}
			}(i)
		}

		wg.Wait()
		assert.Greater(t, successCount, int32(0))
		t.Logf("Successful executions: %d/%d", successCount, iterations)
	})

	t.Run("concurrent pool registration and access", func(t *testing.T) {
		var wg sync.WaitGroup

		for i := 0; i < 50; i++ {
			wg.Add(2)
			go func(idx int) {
				defer wg.Done()
				poolName := string(rune('a' + idx%26))
				p.RegisterPool(poolName, core.PoolConfig{
					MaxSize: 5,
					MinIdle: 1,
				}, func() (*core.PooledResource, error) {
					return &core.PooledResource{ID: "test"}, nil
				})
			}(i)

			go func(idx int) {
				defer wg.Done()
				poolName := string(rune('a' + idx%26))
				p.GetPool(poolName)
			}(i)
		}

		wg.Wait()
	})

	t.Run("concurrent stats access", func(t *testing.T) {
		var wg sync.WaitGroup

		for i := 0; i < 100; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				stats := p.GetStats()
				assert.NotNil(t, stats)
			}()
		}

		wg.Wait()
	})
}

func TestProcessor_EdgeCases(t *testing.T) {
	p := core.NewProcessor()

	t.Run("very long trace ID", func(t *testing.T) {
		longTraceID := make([]byte, 10000)
		for i := range longTraceID {
			longTraceID[i] = 'a'
		}

		req := &models.APIRequest{
			TraceID:   string(longTraceID),
			Namespace: "production",
			Payload:   map[string]interface{}{"type": "job"},
		}

		resp, err := p.ExecuteHandler(context.Background(), req)
		assert.NoError(t, err)
		assert.Equal(t, 200, resp.Code)
	})

	t.Run("special characters in namespace", func(t *testing.T) {
		req := &models.APIRequest{
			TraceID:   "test",
			Namespace: "!@#$%^&*()",
			Payload:   map[string]interface{}{"type": "job"},
		}

		resp, err := p.ExecuteHandler(context.Background(), req)
		assert.NoError(t, err)
		assert.Equal(t, 404, resp.Code)
	})

	t.Run("unicode characters in payload", func(t *testing.T) {
		req := &models.APIRequest{
			TraceID:   "test",
			Namespace: "production",
			Payload: map[string]interface{}{
				"type":  "job",
				"unicode": "你好世界 🌍",
				"emoji":   "✅🔥",
			},
		}

		resp, err := p.ExecuteHandler(context.Background(), req)
		assert.NoError(t, err)
		assert.Equal(t, 200, resp.Code)
	})

	t.Run("cancelled context", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		cancel()

		req := &models.APIRequest{
			TraceID:   "test",
			Namespace: "production",
			Payload:   map[string]interface{}{"type": "job"},
		}

		resp, err := p.ExecuteHandler(ctx, req)
		assert.NoError(t, err)
		assert.Equal(t, 200, resp.Code)
	})
}

func TestProcessor_EnsureTraceID(t *testing.T) {
	p := core.NewProcessor()

	t.Run("empty trace ID generates new", func(t *testing.T) {
		newID := p.ensureTraceID("")
		assert.NotEmpty(t, newID)
	})

	t.Run("existing trace ID preserved", func(t *testing.T) {
		existingID := "existing-trace-id-123"
		result := p.ensureTraceID(existingID)
		assert.Equal(t, existingID, result)
	})

	t.Run("whitespace trace ID", func(t *testing.T) {
		result := p.ensureTraceID("   ")
		assert.Equal(t, "   ", result)
	})
}

func TestProcessor_GetStats(t *testing.T) {
	p := core.NewProcessor()

	t.Run("empty stats", func(t *testing.T) {
		stats := p.GetStats()
		assert.NotNil(t, stats)
		assert.Contains(t, stats, "pools")
	})

	t.Run("stats after registration", func(t *testing.T) {
		p.RegisterPool("stats_pool", core.PoolConfig{
			MaxSize: 10,
			MinIdle: 2,
		}, func() (*core.PooledResource, error) {
			return &core.PooledResource{ID: "test"}, nil
		})

		stats := p.GetStats()
		pools := stats["pools"].(map[string]interface{})
		assert.Contains(t, pools, "stats_pool")
	})
}
