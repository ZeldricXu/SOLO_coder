package executor_test

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/distributed-task-scheduler/internal/config"
	"github.com/distributed-task-scheduler/internal/executor"
	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/test/testkit"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newTestPool(handler executor.TaskHandler) *executor.ExecutorPool {
	cfg := config.ExecutorConfig{
		MaxConcurrency:    2,
		WorkerPoolSize:    4,
		TaskTimeout:       5 * time.Second,
		GracefulShutdown:  3 * time.Second,
		IsolationStrategy: "none",
	}
	return executor.NewExecutorPool(nil, cfg, "test-node", handler)
}

func newTestPoolWithNamespaceIsolation() *executor.ExecutorPool {
	cfg := config.ExecutorConfig{
		MaxConcurrency:    2,
		WorkerPoolSize:    4,
		TaskTimeout:       5 * time.Second,
		GracefulShutdown:  3 * time.Second,
		IsolationStrategy: "namespace",
	}
	return executor.NewExecutorPool(nil, cfg, "test-node", nil)
}

func TestPool_SubmitAndExecute(t *testing.T) {
	var executed int32
	handler := func(ctx context.Context, task *models.Task, executionID string) ([]byte, error) {
		atomic.AddInt32(&executed, 1)
		return []byte(`{"ok": true}`), nil
	}

	pool := newTestPool(handler)
	defer pool.Shutdown()

	task := testkit.NewTaskBuilder().Build()
	err := pool.Submit(task, "exec-1")
	require.NoError(t, err)

	time.Sleep(200 * time.Millisecond)
	assert.Equal(t, int32(1), atomic.LoadInt32(&executed))
}

func TestPool_MultipleTasks(t *testing.T) {
	var executed int32
	handler := func(ctx context.Context, task *models.Task, executionID string) ([]byte, error) {
		atomic.AddInt32(&executed, 1)
		return nil, nil
	}

	pool := newTestPool(handler)
	defer pool.Shutdown()

	for i := 0; i < 10; i++ {
		task := testkit.NewTaskBuilder().Build()
		err := pool.Submit(task, testkit.NewExecutionBuilder().Build().ID)
		require.NoError(t, err)
	}

	assert.Eventually(t, func() bool {
		return atomic.LoadInt32(&executed) == 10
	}, 2*time.Second, 50*time.Millisecond)
}

func TestPool_TaskTimeout(t *testing.T) {
	var started int32
	handler := func(ctx context.Context, task *models.Task, executionID string) ([]byte, error) {
		atomic.AddInt32(&started, 1)
		<-ctx.Done()
		return nil, ctx.Err()
	}

	cfg := config.ExecutorConfig{
		MaxConcurrency:    10,
		WorkerPoolSize:    4,
		TaskTimeout:       500 * time.Millisecond,
		GracefulShutdown:  2 * time.Second,
		IsolationStrategy: "none",
	}
	pool := executor.NewExecutorPool(nil, cfg, "test-node", handler)
	defer pool.Shutdown()

	task := testkit.NewTaskBuilder().Build()
	err := pool.Submit(task, "exec-timeout")
	require.NoError(t, err)

	assert.Eventually(t, func() bool {
		return atomic.LoadInt32(&started) == 1
	}, 1*time.Second, 50*time.Millisecond)
}

func TestPool_ConcurrentSubmission(t *testing.T) {
	var executed int32
	handler := func(ctx context.Context, task *models.Task, executionID string) ([]byte, error) {
		time.Sleep(10 * time.Millisecond)
		atomic.AddInt32(&executed, 1)
		return nil, nil
	}

	pool := newTestPool(handler)
	defer pool.Shutdown()

	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			task := testkit.NewTaskBuilder().Build()
			pool.Submit(task, testkit.NewExecutionBuilder().Build().ID)
		}()
	}
	wg.Wait()

	assert.Eventually(t, func() bool {
		return atomic.LoadInt32(&executed) == 20
	}, 3*time.Second, 50*time.Millisecond)
}

func TestPool_NamespaceIsolation(t *testing.T) {
	pool := newTestPoolWithNamespaceIsolation()
	defer pool.Shutdown()

	var ns1Count, ns2Count int32
	handler := func(ctx context.Context, task *models.Task, executionID string) ([]byte, error) {
		if task.Namespace == "ns-1" {
			atomic.AddInt32(&ns1Count, 1)
		} else {
			atomic.AddInt32(&ns2Count, 1)
		}
		time.Sleep(100 * time.Millisecond)
		return nil, nil
	}

	cfg := config.ExecutorConfig{
		MaxConcurrency:    1,
		WorkerPoolSize:    4,
		TaskTimeout:       5 * time.Second,
		GracefulShutdown:  3 * time.Second,
		IsolationStrategy: "namespace",
	}
	isolatedPool := executor.NewExecutorPool(nil, cfg, "test-node", handler)
	defer isolatedPool.Shutdown()

	for i := 0; i < 3; i++ {
		task1 := testkit.NewTaskBuilder().WithNamespace("ns-1").Build()
		task2 := testkit.NewTaskBuilder().WithNamespace("ns-2").Build()
		isolatedPool.Submit(task1, testkit.NewExecutionBuilder().Build().ID)
		isolatedPool.Submit(task2, testkit.NewExecutionBuilder().Build().ID)
	}

	assert.Eventually(t, func() bool {
		return atomic.LoadInt32(&ns1Count) == 3 && atomic.LoadInt32(&ns2Count) == 3
	}, 3*time.Second, 50*time.Millisecond)
}

func TestPool_GracefulShutdown(t *testing.T) {
	var completed int32
	handler := func(ctx context.Context, task *models.Task, executionID string) ([]byte, error) {
		time.Sleep(500 * time.Millisecond)
		atomic.AddInt32(&completed, 1)
		return nil, nil
	}

	cfg := config.ExecutorConfig{
		MaxConcurrency:    10,
		WorkerPoolSize:    4,
		TaskTimeout:       10 * time.Second,
		GracefulShutdown:  5 * time.Second,
		IsolationStrategy: "none",
	}
	pool := executor.NewExecutorPool(nil, cfg, "test-node", handler)

	for i := 0; i < 3; i++ {
		task := testkit.NewTaskBuilder().Build()
		pool.Submit(task, testkit.NewExecutionBuilder().Build().ID)
	}

	time.Sleep(100 * time.Millisecond)

	pool.Shutdown()

	assert.Equal(t, int32(3), atomic.LoadInt32(&completed), "all tasks should complete before shutdown")
}

func TestPool_RunningCount(t *testing.T) {
	handler := func(ctx context.Context, task *models.Task, executionID string) ([]byte, error) {
		time.Sleep(300 * time.Millisecond)
		return nil, nil
	}

	pool := newTestPool(handler)
	defer pool.Shutdown()

	for i := 0; i < 3; i++ {
		task := testkit.NewTaskBuilder().Build()
		pool.Submit(task, testkit.NewExecutionBuilder().Build().ID)
	}

	time.Sleep(50 * time.Millisecond)

	running := pool.RunningCount()
	assert.GreaterOrEqual(t, running, 1, "at least one task should be running")

	assert.Eventually(t, func() bool {
		return pool.RunningCount() == 0
	}, 2*time.Second, 50*time.Millisecond, "all tasks should eventually finish")
}

func TestPool_SubmitAfterShutdown(t *testing.T) {
	handler := func(ctx context.Context, task *models.Task, executionID string) ([]byte, error) {
		return nil, nil
	}

	pool := newTestPool(handler)
	pool.Shutdown()

	task := testkit.NewTaskBuilder().Build()
	err := pool.Submit(task, "exec-after-shutdown")
	assert.Error(t, err, "submit after shutdown should fail")
}
