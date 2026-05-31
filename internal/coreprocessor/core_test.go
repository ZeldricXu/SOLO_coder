package coreprocessor

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"taskflow/internal/testutils"
	"taskflow/pkg/models"
)

func TestResourcePool_AcquireAndRelease(t *testing.T) {
	pool := NewResourcePool(5)
	ctx := context.Background()

	res, err := pool.Acquire(ctx, "test-lease", time.Second*5)
	require.NoError(t, err)
	assert.NotNil(t, res)
	assert.True(t, res.InUse)
	assert.Equal(t, "test-lease", res.LeaseHolder)

	available := pool.GetAvailableCount()
	assert.Equal(t, 4, available)

	pool.Release(res)
	availableAfter := pool.GetAvailableCount()
	assert.Equal(t, 5, availableAfter)
	assert.False(t, res.InUse)
}

func TestResourcePool_AcquireTimeout(t *testing.T) {
	pool := NewResourcePool(1)
	ctx := context.Background()

	res1, err := pool.Acquire(ctx, "holder1", time.Second*5)
	require.NoError(t, err)
	assert.NotNil(t, res1)

	start := time.Now()
	res2, err := pool.Acquire(ctx, "holder2", time.Millisecond*100)

	elapsed := time.Since(start)
	assert.Error(t, err)
	assert.Nil(t, res2)
	assert.GreaterOrEqual(t, elapsed, time.Millisecond*100)
	assert.Less(t, elapsed, time.Millisecond*500)

	var resourceErr *models.ResourceAcquisitionError
	assert.ErrorAs(t, err, &resourceErr)
}

func TestResourcePool_BlockingAcquire(t *testing.T) {
	pool := NewResourcePool(1)
	ctx := context.Background()

	res, err := pool.Acquire(ctx, "holder1", time.Second*5)
	require.NoError(t, err)

	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		time.Sleep(time.Millisecond * 100)
		pool.Release(res)
	}()

	res2, err := pool.Acquire(ctx, "holder2", time.Second*5)
	wg.Wait()

	require.NoError(t, err)
	assert.NotNil(t, res2)
}

func TestResourcePool_ConcurrentAcquire(t *testing.T) {
	poolSize := 5
	pool := NewResourcePool(poolSize)
	ctx := context.Background()

	var wg sync.WaitGroup
	results := make(chan *Resource, poolSize*2)

	for i := 0; i < poolSize*2; i++ {
		wg.Add(1)
		go func(holderID int) {
			defer wg.Done()
			res, err := pool.Acquire(ctx, string(rune('A'+holderID)), time.Second*5)
			if err == nil {
				results <- res
				time.Sleep(time.Millisecond * 50)
				pool.Release(res)
			}
		}(i)
	}

	wg.Wait()
	close(results)

	count := 0
	for range results {
		count++
	}
	assert.Equal(t, poolSize*2, count)
}

func TestConfigManager_LoadDefault(t *testing.T) {
	manager := NewConfigManager()

	cfg := manager.LoadConfig("test-namespace")
	assert.NotNil(t, cfg)
	assert.Equal(t, "cfg_test-namespace", cfg.ConfigID)
	assert.Equal(t, 1, cfg.Version)
	assert.True(t, cfg.Enabled)
	assert.Equal(t, 30, cfg.Parameters["timeout"])
	assert.Equal(t, 3, cfg.Parameters["retries"])
}

func TestConfigManager_Update(t *testing.T) {
	manager := NewConfigManager()
	manager.LoadConfig("test-namespace")

	newParams := map[string]interface{}{
		"timeout": 60,
		"retries": 5,
		"new_key": "value",
	}

	updated := manager.UpdateConfig("test-namespace", newParams)
	assert.Equal(t, 2, updated.Version)
	assert.Equal(t, 60, updated.Parameters["timeout"])
	assert.Equal(t, "value", updated.Parameters["new_key"])
	assert.NotNil(t, updated.AppliedAt)

	loaded := manager.LoadConfig("test-namespace")
	assert.Equal(t, 2, loaded.Version)
	assert.Equal(t, 60, loaded.Parameters["timeout"])
}

func TestConfigManager_ConcurrentAccess(t *testing.T) {
	manager := NewConfigManager()
	var wg sync.WaitGroup

	for i := 0; i < 10; i++ {
		wg.Add(2)
		go func(n int) {
			defer wg.Done()
			namespace := testutils.NewTestDataFactory().CreateRandomString(8)
			manager.LoadConfig(namespace)
		}(i)

		go func(n int) {
			defer wg.Done()
			manager.UpdateConfig("shared", map[string]interface{}{
				"value": n,
			})
		}(i)
	}

	wg.Wait()
}

func TestRunManager_CreateAndUpdate(t *testing.T) {
	manager := NewRunManager()

	run := manager.CreateRun("entity_123")
	assert.NotNil(t, run)
	assert.NotEmpty(t, run.RunID)
	assert.Equal(t, "entity_123", run.EntityID)
	assert.Equal(t, models.RunPhasePending, run.Phase)
	assert.Equal(t, 0.0, run.Progress)

	updated, err := manager.UpdateRun(run.RunID, models.RunPhaseRunning, 0.5, "")
	require.NoError(t, err)
	assert.Equal(t, models.RunPhaseRunning, updated.Phase)
	assert.Equal(t, 0.5, updated.Progress)
	assert.NotNil(t, updated.StartedAt)

	completed, err := manager.UpdateRun(run.RunID, models.RunPhaseCompleted, 1.0, "")
	require.NoError(t, err)
	assert.Equal(t, models.RunPhaseCompleted, completed.Phase)
	assert.Equal(t, 1.0, completed.Progress)
	assert.NotNil(t, completed.CompletedAt)
}

func TestRunManager_UpdateNotFound(t *testing.T) {
	manager := NewRunManager()
	_, err := manager.UpdateRun("nonexistent", models.RunPhaseRunning, 0.5, "")
	assert.Error(t, err)
}

func TestRunManager_GetRun(t *testing.T) {
	manager := NewRunManager()

	run := manager.CreateRun("entity_1")
	retrieved, exists := manager.GetRun(run.RunID)
	assert.True(t, exists)
	assert.Equal(t, run.RunID, retrieved.RunID)

	_, exists = manager.GetRun("nonexistent")
	assert.False(t, exists)
}

func TestRunManager_GetActiveRuns(t *testing.T) {
	manager := NewRunManager()

	active1 := manager.CreateRun("e1")
	active2 := manager.CreateRun("e2")
	completed := manager.CreateRun("e3")

	manager.UpdateRun(active1.RunID, models.RunPhaseRunning, 0.5, "")
	manager.UpdateRun(completed.RunID, models.RunPhaseCompleted, 1.0, "")

	activeRuns := manager.GetActiveRuns()
	assert.GreaterOrEqual(t, len(activeRuns), 2)

	runIDs := map[string]bool{}
	for _, r := range activeRuns {
		runIDs[r.RunID] = true
	}
	assert.True(t, runIDs[active1.RunID])
	assert.True(t, runIDs[active2.RunID])
	assert.False(t, runIDs[completed.RunID])
}

func TestEventEmitter_OnAndEmit(t *testing.T) {
	emitter := NewEventEmitter()

	received := make(chan Event, 1)
	handler := func(e Event) {
		received <- e
	}

	emitter.On(EventTaskCompleted, handler)
	emitter.Emit(EventTaskCompleted, "test data")

	select {
	case event := <-received:
		assert.Equal(t, EventTaskCompleted, event.Type)
		assert.Equal(t, "test data", event.Data)
	case <-time.After(time.Second):
		t.Fatal("Timeout waiting for event")
	}
}

func TestEventEmitter_MultipleHandlers(t *testing.T) {
	emitter := NewEventEmitter()

	count1 := 0
	count2 := 0
	var mu sync.Mutex

	emitter.On(EventTaskStarted, func(e Event) {
		mu.Lock()
		defer mu.Unlock()
		count1++
	})
	emitter.On(EventTaskStarted, func(e Event) {
		mu.Lock()
		defer mu.Unlock()
		count2++
	})

	emitter.Emit(EventTaskStarted, nil)
	time.Sleep(time.Millisecond * 50)

	mu.Lock()
	defer mu.Unlock()
	assert.Equal(t, 1, count1)
	assert.Equal(t, 1, count2)
}

func TestEventEmitter_NoHandlers(t *testing.T) {
	emitter := NewEventEmitter()
	assert.NotPanics(t, func() {
		emitter.Emit("nonexistent", nil)
	})
}

func TestCoreProcessor_ExecuteHandler_Success(t *testing.T) {
	processor := NewCoreProcessor()

	processor.SetProcessorFunc(func(ctx context.Context, payload interface{}, config *models.ConfigDefinition) (interface{}, error) {
		return map[string]interface{}{
			"result": "processed",
		}, nil
	})

	processor.SetPersistenceFunc(func(result interface{}) error {
		return nil
	})

	req := &ExecuteRequest{
		TraceID:   "trace-001",
		Namespace: "test-ns",
		Params: map[string]interface{}{
			"action": "process",
		},
		Payload: "test payload",
	}

	result := processor.ExecuteHandler(context.Background(), req)

	assert.True(t, result.Success)
	assert.Equal(t, 200, result.ErrorCode)
	assert.NotEmpty(t, result.RunID)
	assert.GreaterOrEqual(t, result.ExecutionTimeMs, int64(0))

	data, ok := result.Data.(map[string]interface{})
	require.True(t, ok)
	assert.Equal(t, "processed", data["result"])
}

func TestCoreProcessor_ExecuteHandler_ValidationError(t *testing.T) {
	processor := NewCoreProcessor()

	req := &ExecuteRequest{
		TraceID:   "trace-002",
		Namespace: "test-ns",
		Params:    map[string]interface{}{},
		Payload:   "test",
	}

	result := processor.ExecuteHandler(context.Background(), req)

	assert.False(t, result.Success)
	assert.Equal(t, 422, result.ErrorCode)
	assert.Contains(t, result.ErrorMessage, "Validation")
	assert.NotNil(t, result.ErrorDetails)
}

func TestCoreProcessor_ExecuteHandler_NilParams(t *testing.T) {
	processor := NewCoreProcessor()

	req := &ExecuteRequest{
		TraceID:   "trace-003",
		Namespace: "test-ns",
		Params:    nil,
		Payload:   "test",
	}

	result := processor.ExecuteHandler(context.Background(), req)

	assert.False(t, result.Success)
	assert.Equal(t, 422, result.ErrorCode)
}

func TestCoreProcessor_ExecuteHandler_ProcessingTimeout(t *testing.T) {
	processor := NewCoreProcessor()

	processor.SetProcessorFunc(func(ctx context.Context, payload interface{}, config *models.ConfigDefinition) (interface{}, error) {
		time.Sleep(time.Millisecond * 200)
		return "result", nil
	})

	configManager := processor.GetConfigManager()
	configManager.UpdateConfig("timeout-ns", map[string]interface{}{
		"timeout":            0,
		"processing_timeout": 0,
		"retries":            0,
	})

	req := &ExecuteRequest{
		TraceID:   "trace-004",
		Namespace: "timeout-ns",
		Params: map[string]interface{}{
			"action": "process",
		},
		Payload: "test",
	}

	result := processor.ExecuteHandler(context.Background(), req)

	assert.Equal(t, 200, result.ErrorCode)
}

func TestCoreProcessor_ExecuteHandler_PersistenceError(t *testing.T) {
	processor := NewCoreProcessor()

	processor.SetProcessorFunc(func(ctx context.Context, payload interface{}, config *models.ConfigDefinition) (interface{}, error) {
		return "ok", nil
	})

	processor.SetPersistenceFunc(func(result interface{}) error {
		return assert.AnError
	})

	req := &ExecuteRequest{
		TraceID:   "trace-005",
		Namespace: "test-ns",
		Params: map[string]interface{}{
			"action": "process",
		},
		Payload: "test",
	}

	result := processor.ExecuteHandler(context.Background(), req)

	assert.False(t, result.Success)
	assert.Equal(t, 500, result.ErrorCode)
}

func TestCoreProcessor_ExecuteHandler_ResourceRelease(t *testing.T) {
	processor := NewCoreProcessor()
	pool := processor.GetResourcePool()

	processor.SetProcessorFunc(func(ctx context.Context, payload interface{}, config *models.ConfigDefinition) (interface{}, error) {
		return "ok", nil
	})

	initialAvailable := pool.GetAvailableCount()

	req := &ExecuteRequest{
		TraceID:   "trace-006",
		Namespace: "test-ns",
		Params: map[string]interface{}{
			"action": "process",
		},
		Payload: "test",
	}

	result := processor.ExecuteHandler(context.Background(), req)

	assert.True(t, result.Success)
	assert.Equal(t, initialAvailable, pool.GetAvailableCount())
}

func TestCoreProcessor_ConcurrentExecution(t *testing.T) {
	processor := NewCoreProcessor()
	pool := processor.GetResourcePool()

	processor.SetProcessorFunc(func(ctx context.Context, payload interface{}, config *models.ConfigDefinition) (interface{}, error) {
		time.Sleep(time.Millisecond * 10)
		return payload, nil
	})

	processor.SetPersistenceFunc(func(result interface{}) error {
		return nil
	})

	var wg sync.WaitGroup
	results := make(chan *models.ProcessingResult, 20)

	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			req := &ExecuteRequest{
				TraceID:   testutils.NewTestDataFactory().CreateRandomString(10),
				Namespace: "test-ns",
				Params: map[string]interface{}{
					"action": "process",
				},
				Payload: idx,
			}
			result := processor.ExecuteHandler(context.Background(), req)
			results <- result
		}(i)
	}

	go func() {
		wg.Wait()
		close(results)
	}()

	successCount := 0
	for result := range results {
		if result.Success {
			successCount++
		}
	}

	assert.Equal(t, 20, successCount)
	assert.Equal(t, pool.GetTotalSize(), pool.GetAvailableCount())
}

func TestCoreProcessor_ResourceAcquisitionFailure(t *testing.T) {
	processor := NewCoreProcessor()
	pool := processor.GetResourcePool()

	var heldResources []*Resource
	for i := 0; i < pool.GetTotalSize(); i++ {
		res, _ := pool.Acquire(context.Background(), "holder", time.Second*5)
		heldResources = append(heldResources, res)
	}

	configManager := processor.GetConfigManager()
	configManager.UpdateConfig("resource-ns", map[string]interface{}{
		"resource_timeout": 0,
		"retries":          0,
	})

	req := &ExecuteRequest{
		TraceID:   "trace-007",
		Namespace: "resource-ns",
		Params: map[string]interface{}{
			"action": "process",
		},
		Payload: "test",
	}

	result := processor.ExecuteHandler(context.Background(), req)

	assert.Equal(t, 503, result.ErrorCode)

	for _, res := range heldResources {
		pool.Release(res)
	}
}

func TestCoreProcessor_ValidationError_Details(t *testing.T) {
	processor := NewCoreProcessor()

	req := &ExecuteRequest{
		TraceID:   "trace-008",
		Namespace: "test-ns",
		Params:    map[string]interface{}{},
		Payload:   "test",
	}

	result := processor.ExecuteHandler(context.Background(), req)

	details, ok := result.ErrorDetails.(map[string]interface{})
	require.True(t, ok)
	assert.Equal(t, "required", details["action"])
}

func TestCoreProcessor_EventEmission(t *testing.T) {
	processor := NewCoreProcessor()
	emitter := processor.GetEventEmitter()

	eventReceived := make(chan bool, 1)
	emitter.On(EventTaskCompleted, func(e Event) {
		eventReceived <- true
	})

	processor.SetProcessorFunc(func(ctx context.Context, payload interface{}, config *models.ConfigDefinition) (interface{}, error) {
		return "ok", nil
	})

	req := &ExecuteRequest{
		TraceID:   "trace-009",
		Namespace: "test-ns",
		Params: map[string]interface{}{
			"action": "process",
		},
		Payload: "test",
	}

	result := processor.ExecuteHandler(context.Background(), req)
	assert.True(t, result.Success)

	select {
	case <-eventReceived:
	case <-time.After(time.Second):
		t.Fatal("TaskCompleted event not emitted")
	}
}

func TestCoreProcessor_GetSingleton(t *testing.T) {
	p1 := GetCoreProcessor()
	p2 := GetCoreProcessor()
	assert.Same(t, p1, p2)
}

func TestCoreProcessor_RunInstanceCreation(t *testing.T) {
	processor := NewCoreProcessor()
	runManager := processor.GetRunManager()

	processor.SetProcessorFunc(func(ctx context.Context, payload interface{}, config *models.ConfigDefinition) (interface{}, error) {
		return "ok", nil
	})

	req := &ExecuteRequest{
		TraceID:   "trace-010",
		Namespace: "test-ns",
		Params: map[string]interface{}{
			"action": "process",
		},
		Payload: "test",
	}

	result := processor.ExecuteHandler(context.Background(), req)
	assert.NotEmpty(t, result.RunID)

	run, exists := runManager.GetRun(result.RunID)
	assert.True(t, exists)
	assert.Equal(t, models.RunPhaseCompleted, run.Phase)
	assert.Equal(t, 1.0, run.Progress)
}
