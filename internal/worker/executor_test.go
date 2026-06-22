package worker

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/df1-96/experiment/internal/compute"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func sphereObjective(x []float64) float64 {
	var sum float64
	for _, xi := range x {
		sum += xi * xi
	}
	return sum
}

func sphereGradient(x []float64, grad []float64) {
	for i, xi := range x {
		grad[i] = 2 * xi
	}
}

func makeSimpleTask(taskID string) *Task {
	return &Task{
		TaskID:       taskID,
		InitialPoint: []float64{1.0, 1.0},
		Objective:    sphereObjective,
		Gradient:     sphereGradient,
		OptimizerConfig: compute.OptimizerConfig{
			Type:         compute.Adam,
			MaxIter:      10,
			Tolerance:    1e-8,
			LearningRate: 0.05,
			Beta1:        0.9,
			Beta2:        0.999,
			Epsilon:      1e-8,
		},
	}
}

func TestExecutor_ConcurrentTasks(t *testing.T) {
	cache := NewLocalCache(CacheConfig{MaxSize: 100, TTL: 1 * time.Hour})
	executorConfig := ExecutorConfig{
		MaxParallelTasks: 2,
		ProgressInterval: 100 * time.Millisecond,
		TaskTimeout:      10 * time.Second,
	}

	var mu sync.Mutex
	var completedTasks []string
	var resultCount int32

	progressCb := func(p TaskProgress) {}
	resultCb := func(r TaskResult) {
		mu.Lock()
		completedTasks = append(completedTasks, r.TaskID)
		mu.Unlock()
		atomic.AddInt32(&resultCount, 1)
	}

	taskIDs := []string{"task1", "task2"}
	taskIdx := int32(-1)

	fetchTaskFunc := func(ctx context.Context, workerID string) (*Task, error) {
		idx := atomic.AddInt32(&taskIdx, 1)
		if idx >= int32(len(taskIDs)) {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(50 * time.Millisecond):
				return nil, nil
			}
		}
		return makeSimpleTask(taskIDs[idx]), nil
	}

	submitResultFunc := func(ctx context.Context, result TaskResult) error {
		return nil
	}

	executor := NewTaskExecutor(
		executorConfig,
		"test-worker",
		cache,
		progressCb,
		resultCb,
		fetchTaskFunc,
		submitResultFunc,
	)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	err := executor.Start(ctx)
	require.NoError(t, err)

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if atomic.LoadInt32(&resultCount) >= int32(len(taskIDs)) {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	err = executor.Stop()
	require.NoError(t, err)

	mu.Lock()
	defer mu.Unlock()
	assert.GreaterOrEqual(t, len(completedTasks), 1, "should complete at least 1 task")
}

func TestExecutor_TaskTimeout_Cancellation(t *testing.T) {
	cache := NewLocalCache(CacheConfig{MaxSize: 100, TTL: 1 * time.Hour})
	executorConfig := ExecutorConfig{
		MaxParallelTasks: 1,
		ProgressInterval: 50 * time.Millisecond,
		TaskTimeout:      100 * time.Millisecond,
	}

	var resultCount int32
	var mu sync.Mutex
	var results []TaskResult

	resultCb := func(r TaskResult) {
		mu.Lock()
		results = append(results, r)
		mu.Unlock()
		atomic.AddInt32(&resultCount, 1)
	}

	slowObj := func(x []float64) float64 {
		time.Sleep(2 * time.Second)
		return x[0]*x[0] + x[1]*x[1]
	}
	slowGrad := func(x []float64, grad []float64) {
		time.Sleep(2 * time.Second)
		grad[0] = 2 * x[0]
		grad[1] = 2 * x[1]
	}

	fetchCalled := false
	fetchTaskFunc := func(ctx context.Context, workerID string) (*Task, error) {
		if fetchCalled {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(50 * time.Millisecond):
				return nil, nil
			}
		}
		fetchCalled = true
		return &Task{
			TaskID:       "slow-task",
			InitialPoint: []float64{1.0, 2.0},
			Objective:    slowObj,
			Gradient:     slowGrad,
			Timeout:      100 * time.Millisecond,
			OptimizerConfig: compute.OptimizerConfig{
				Type:         compute.GradientDescent,
				MaxIter:      1000,
				Tolerance:    1e-8,
				LearningRate: 0.01,
			},
		}, nil
	}

	submitResultFunc := func(ctx context.Context, result TaskResult) error {
		return nil
	}

	executor := NewTaskExecutor(
		executorConfig,
		"test-worker",
		cache,
		nil,
		resultCb,
		fetchTaskFunc,
		submitResultFunc,
	)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	err := executor.Start(ctx)
	require.NoError(t, err)

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if atomic.LoadInt32(&resultCount) >= 1 {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	err = executor.Stop()
	require.NoError(t, err)

	mu.Lock()
	defer mu.Unlock()
	assert.GreaterOrEqual(t, len(results), 1, "should have at least 1 result")
}

func TestExecutor_CacheHit_AvoidsRecompute(t *testing.T) {
	cache := NewLocalCache(CacheConfig{MaxSize: 100, TTL: 1 * time.Hour})

	var evalCount int32

	countingObj := func(x []float64) float64 {
		atomic.AddInt32(&evalCount, 1)
		return sphereObjective(x)
	}
	countingGrad := func(x []float64, grad []float64) {
		sphereGradient(x, grad)
	}

	var emptyParams []map[string]float64
	cacheKey := cache.GenerateCacheKey("cache-task", emptyParams)
	cachedResult := TaskResult{
		TaskID:       "cache-task",
		Status:       TaskStatusCompleted,
		OptimalPoint: []float64{0.0, 0.0},
		OptimalValue: 0.0,
		Iterations:   10,
	}
	_ = cache.Set(cacheKey, cachedResult)

	executorConfig := ExecutorConfig{
		MaxParallelTasks: 1,
		ProgressInterval: 100 * time.Millisecond,
		TaskTimeout:      10 * time.Second,
	}

	var resultCbMu sync.Mutex
	var gotCacheHit bool
	var gotResult bool
	resultCb := func(r TaskResult) {
		resultCbMu.Lock()
		if r.CacheHit {
			gotCacheHit = true
		}
		gotResult = true
		resultCbMu.Unlock()
	}

	fetchCalled := false
	fetchTaskFunc := func(ctx context.Context, workerID string) (*Task, error) {
		if fetchCalled {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(50 * time.Millisecond):
				return nil, nil
			}
		}
		fetchCalled = true
		return &Task{
			TaskID:               "cache-task",
			InitialPoint:         []float64{5.0, 5.0},
			Objective:            countingObj,
			Gradient:             countingGrad,
			ParameterCombinations: emptyParams,
			OptimizerConfig: compute.OptimizerConfig{
				Type:         compute.Adam,
				MaxIter:      10,
				Tolerance:    1e-8,
				LearningRate: 0.05,
				Beta1:        0.9,
				Beta2:        0.999,
				Epsilon:      1e-8,
			},
		}, nil
	}

	submitResultFunc := func(ctx context.Context, result TaskResult) error {
		return nil
	}

	executor := NewTaskExecutor(
		executorConfig,
		"test-worker",
		cache,
		nil,
		resultCb,
		fetchTaskFunc,
		submitResultFunc,
	)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	err := executor.Start(ctx)
	require.NoError(t, err)

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		resultCbMu.Lock()
		done := gotResult
		resultCbMu.Unlock()
		if done {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	err = executor.Stop()
	require.NoError(t, err)

	resultCbMu.Lock()
	defer resultCbMu.Unlock()
	assert.True(t, gotCacheHit, "result should come from cache")
	assert.Equal(t, int32(0), atomic.LoadInt32(&evalCount), "objective should not be evaluated when cache hit")
}

func TestExecutor_NewTaskExecutor_Defaults(t *testing.T) {
	executorConfig := ExecutorConfig{}
	executor := NewTaskExecutor(
		executorConfig,
		"test",
		nil,
		nil,
		nil,
		nil,
		nil,
	)

	assert.Equal(t, int32(1), executor.config.MaxParallelTasks)
	assert.Equal(t, 5*time.Second, executor.config.ProgressInterval)
	assert.Equal(t, 30*time.Minute, executor.config.TaskTimeout)
}

func TestExecutor_SubmitTask_NotRunning(t *testing.T) {
	executor := NewTaskExecutor(
		ExecutorConfig{},
		"test",
		nil,
		nil,
		nil,
		nil,
		nil,
	)

	task := &Task{TaskID: "test-task"}
	err := executor.SubmitTask(task)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "not running")
}

func TestExecutor_GetCounts_Initial(t *testing.T) {
	executor := NewTaskExecutor(
		ExecutorConfig{MaxParallelTasks: 2},
		"test",
		nil,
		nil,
		nil,
		nil,
		nil,
	)

	assert.Equal(t, int64(0), executor.GetCompletedCount())
	assert.Equal(t, int64(0), executor.GetFailedCount())
	assert.Equal(t, 0, executor.GetActiveTaskCount())
	assert.Equal(t, 0, executor.GetPendingTaskCount())
	assert.Empty(t, executor.GetRunningTasks())
	assert.False(t, executor.IsRunning())
}

func TestExecutor_CancelTask_NotFound(t *testing.T) {
	executor := NewTaskExecutor(
		ExecutorConfig{},
		"test",
		nil,
		nil,
		nil,
		nil,
		nil,
	)

	err := executor.CancelTask("nonexistent")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "not found")
}

func TestExecutor_StartStop(t *testing.T) {
	executor := NewTaskExecutor(
		ExecutorConfig{MaxParallelTasks: 1},
		"test-worker",
		nil,
		nil,
		nil,
		func(ctx context.Context, workerID string) (*Task, error) {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(50 * time.Millisecond):
				return nil, nil
			}
		},
		nil,
	)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := executor.Start(ctx)
	require.NoError(t, err)
	assert.True(t, executor.IsRunning())

	err = executor.Start(ctx)
	require.NoError(t, err, "starting already running executor should be no-op")

	err = executor.Stop()
	require.NoError(t, err)
	assert.False(t, executor.IsRunning())

	err = executor.Stop()
	require.NoError(t, err, "stopping already stopped executor should be no-op")
}
