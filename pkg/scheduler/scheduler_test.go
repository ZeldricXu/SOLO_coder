package scheduler

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/solocoder/logrotate/internal/domain"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func waitForCondition(t *testing.T, timeout time.Duration, condition func() bool, msg string) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timeout waiting for condition: %s", msg)
}

func TestNewScheduler(t *testing.T) {
	t.Run("default configuration", func(t *testing.T) {
		s := New()
		require.NotNil(t, s)
		assert.NotNil(t, s.tasks)
		assert.NotNil(t, s.handlers)
		assert.NotNil(t, s.cronJobs)
		assert.NotNil(t, s.taskQueue)
		assert.Equal(t, 10, s.maxWorkers)
		s.Stop()
	})

	t.Run("with custom max workers", func(t *testing.T) {
		s := New(WithMaxWorkers(50))
		require.NotNil(t, s)
		assert.Equal(t, 50, s.maxWorkers)
		s.Stop()
	})

	t.Run("with zero max workers", func(t *testing.T) {
		s := New(WithMaxWorkers(0))
		require.NotNil(t, s)
		assert.Equal(t, 0, s.maxWorkers)
		s.Stop()
	})
}

func TestScheduler_SubmitTask(t *testing.T) {
	t.Run("submit successful task", func(t *testing.T) {
		s := New(WithMaxWorkers(5))
		defer s.Stop()

		var executed atomic.Bool
		s.RegisterHandler("test", func(ctx context.Context, task *domain.Task) error {
			executed.Store(true)
			return nil
		})

		task := &domain.Task{
			Name:       "test-task",
			Type:       "test",
			MaxRetries: 0,
		}

		taskID, err := s.Submit(task)
		require.NoError(t, err)
		require.NotEmpty(t, taskID)

		waitForCondition(t, 5*time.Second, func() bool {
			return executed.Load()
		}, "task should be executed")

		savedTask, exists := s.GetTask(taskID)
		require.True(t, exists)
		assert.Equal(t, string(StatusCompleted), savedTask.Status)
		assert.NotNil(t, savedTask.CompletedAt)
	})

	t.Run("submit task with existing ID", func(t *testing.T) {
		s := New(WithMaxWorkers(5))
		defer s.Stop()

		customID := "custom-task-id-123"
		s.RegisterHandler("test", func(ctx context.Context, task *domain.Task) error {
			return nil
		})

		task := &domain.Task{
			ID:         customID,
			Name:       "test-task",
			Type:       "test",
			MaxRetries: 0,
		}

		taskID, err := s.Submit(task)
		require.NoError(t, err)
		assert.Equal(t, customID, taskID)

		savedTask, exists := s.GetTask(customID)
		require.True(t, exists)
		assert.Equal(t, customID, savedTask.ID)
	})

	t.Run("submit task without handler", func(t *testing.T) {
		s := New(WithMaxWorkers(5))
		defer s.Stop()

		task := &domain.Task{
			Name:       "test-task",
			Type:       "unknown-type",
			MaxRetries: 0,
		}

		taskID, err := s.Submit(task)
		require.NoError(t, err)
		require.NotEmpty(t, taskID)

		waitForCondition(t, 5*time.Second, func() bool {
			savedTask, _ := s.GetTask(taskID)
			return savedTask != nil && savedTask.Status == string(StatusFailed)
		}, "task should fail")

		savedTask, exists := s.GetTask(taskID)
		require.True(t, exists)
		assert.Equal(t, string(StatusFailed), savedTask.Status)
		assert.Contains(t, *savedTask.Error, "no handler for type")
	})
}

func TestScheduler_TaskStatusTracking(t *testing.T) {
	s := New(WithMaxWorkers(2))
	defer s.Stop()

	var started chan struct{} = make(chan struct{})
	var allowComplete chan struct{} = make(chan struct{})

	s.RegisterHandler("status-test", func(ctx context.Context, task *domain.Task) error {
		close(started)
		<-allowComplete
		return nil
	})

	task := &domain.Task{
		Name:       "status-test-task",
		Type:       "status-test",
		MaxRetries: 0,
	}

	taskID, err := s.Submit(task)
	require.NoError(t, err)

	<-started

	status, exists := s.GetTaskStatus(taskID)
	require.True(t, exists)
	assert.Equal(t, StatusRunning, status)

	savedTask, _ := s.GetTask(taskID)
	assert.NotNil(t, savedTask.StartedAt)
	assert.Nil(t, savedTask.CompletedAt)

	close(allowComplete)

	waitForCondition(t, 5*time.Second, func() bool {
		status, _ := s.GetTaskStatus(taskID)
		return status == StatusCompleted
	}, "task should complete")

	status, _ = s.GetTaskStatus(taskID)
	assert.Equal(t, StatusCompleted, status)

	savedTask, _ = s.GetTask(taskID)
	assert.NotNil(t, savedTask.CompletedAt)
	assert.Nil(t, savedTask.Error)
}

func TestScheduler_TaskRetry(t *testing.T) {
	t.Run("retry on failure", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		attempts := atomic.Int32{}

		s.RegisterHandler("retry-test", func(ctx context.Context, task *domain.Task) error {
			currentAttempt := attempts.Add(1)
			if currentAttempt < 3 {
				return fmt.Errorf("temporary error, attempt %d", currentAttempt)
			}
			return nil
		})

		task := &domain.Task{
			Name:       "retry-task",
			Type:       "retry-test",
			MaxRetries: 3,
		}

		taskID, err := s.Submit(task)
		require.NoError(t, err)

		waitForCondition(t, 10*time.Second, func() bool {
			savedTask, _ := s.GetTask(taskID)
			return savedTask != nil && savedTask.Status == string(StatusCompleted)
		}, "task should complete after retries")

		savedTask, _ := s.GetTask(taskID)
		assert.Equal(t, string(StatusCompleted), savedTask.Status)
		assert.Equal(t, 2, savedTask.RetryCount)
		assert.Equal(t, int32(3), attempts.Load())
	})

	t.Run("max retries exceeded", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		attempts := atomic.Int32{}

		s.RegisterHandler("fail-test", func(ctx context.Context, task *domain.Task) error {
			attempts.Add(1)
			return errors.New("permanent error")
		})

		task := &domain.Task{
			Name:       "fail-task",
			Type:       "fail-test",
			MaxRetries: 2,
		}

		taskID, err := s.Submit(task)
		require.NoError(t, err)

		waitForCondition(t, 10*time.Second, func() bool {
			savedTask, _ := s.GetTask(taskID)
			return savedTask != nil && savedTask.Status == string(StatusFailed)
		}, "task should fail after retries")

		savedTask, _ := s.GetTask(taskID)
		assert.Equal(t, string(StatusFailed), savedTask.Status)
		assert.Equal(t, 2, savedTask.RetryCount)
		assert.Equal(t, int32(3), attempts.Load())
		assert.Contains(t, *savedTask.Error, "permanent error")
	})

	t.Run("no retries on first attempt success", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		attempts := atomic.Int32{}

		s.RegisterHandler("no-retry-test", func(ctx context.Context, task *domain.Task) error {
			attempts.Add(1)
			return nil
		})

		task := &domain.Task{
			Name:       "no-retry-task",
			Type:       "no-retry-test",
			MaxRetries: 5,
		}

		taskID, err := s.Submit(task)
		require.NoError(t, err)

		waitForCondition(t, 5*time.Second, func() bool {
			savedTask, _ := s.GetTask(taskID)
			return savedTask != nil && savedTask.Status == string(StatusCompleted)
		}, "task should complete")

		assert.Equal(t, int32(1), attempts.Load())
	})
}

func TestScheduler_TaskTimeout(t *testing.T) {
	s := New(WithMaxWorkers(2))
	defer s.Stop()

	s.RegisterHandler("timeout-test", func(ctx context.Context, task *domain.Task) error {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(5 * time.Second):
			return nil
		}
	})

	task := &domain.Task{
		Name:           "timeout-task",
		Type:           "timeout-test",
		MaxRetries:     0,
		TimeoutSeconds: 1,
	}

	taskID, err := s.Submit(task)
	require.NoError(t, err)

	waitForCondition(t, 3*time.Second, func() bool {
		savedTask, _ := s.GetTask(taskID)
		return savedTask != nil && savedTask.Status == string(StatusFailed)
	}, "task should timeout")

	savedTask, _ := s.GetTask(taskID)
	assert.Equal(t, string(StatusFailed), savedTask.Status)
	assert.Contains(t, *savedTask.Error, "context deadline exceeded")
}

func TestScheduler_CancelTask(t *testing.T) {
	t.Run("cancel running task", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		taskStarted := make(chan struct{})
		taskCancelled := make(chan struct{})

		s.RegisterHandler("cancel-test", func(ctx context.Context, task *domain.Task) error {
			close(taskStarted)
			select {
			case <-ctx.Done():
				close(taskCancelled)
				return ctx.Err()
			case <-time.After(10 * time.Second):
				return errors.New("task completed without cancellation")
			}
		})

		task := &domain.Task{
			Name:       "cancel-task",
			Type:       "cancel-test",
			MaxRetries: 0,
		}

		taskID, err := s.Submit(task)
		require.NoError(t, err)

		<-taskStarted

		err = s.CancelTask(taskID)
		require.NoError(t, err)

		<-taskCancelled

		waitForCondition(t, 2*time.Second, func() bool {
			savedTask, _ := s.GetTask(taskID)
			return savedTask != nil && savedTask.Status == string(StatusCancelled)
		}, "task should be cancelled")

		savedTask, _ := s.GetTask(taskID)
		assert.Equal(t, string(StatusCancelled), savedTask.Status)
		assert.Contains(t, *savedTask.Error, "task cancelled by user")
	})

	t.Run("cancel non-existent task", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		err := s.CancelTask("non-existent-id")
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "task not found")
	})

	t.Run("cancel already completed task", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		s.RegisterHandler("fast-task", func(ctx context.Context, task *domain.Task) error {
			return nil
		})

		task := &domain.Task{
			Name:       "fast-task",
			Type:       "fast-task",
			MaxRetries: 0,
		}

		taskID, err := s.Submit(task)
		require.NoError(t, err)

		waitForCondition(t, 5*time.Second, func() bool {
			savedTask, _ := s.GetTask(taskID)
			return savedTask != nil && savedTask.Status == string(StatusCompleted)
		}, "task should complete")

		err = s.CancelTask(taskID)
		assert.NoError(t, err)

		savedTask, _ := s.GetTask(taskID)
		assert.Equal(t, string(StatusCancelled), savedTask.Status)
	})
}

func TestScheduler_ListTasks(t *testing.T) {
	s := New(WithMaxWorkers(5))
	defer s.Stop()

	s.RegisterHandler("test", func(ctx context.Context, task *domain.Task) error {
		<-ctx.Done()
		return nil
	})

	for i := 0; i < 5; i++ {
		_, _ = s.Submit(&domain.Task{
			Name:       fmt.Sprintf("task-%d", i),
			Type:       "test",
			MaxRetries: 0,
		})
	}

	time.Sleep(100 * time.Millisecond)

	t.Run("list all tasks", func(t *testing.T) {
		allTasks := s.ListTasks()
		assert.Equal(t, 5, len(allTasks))
	})

	t.Run("list running tasks", func(t *testing.T) {
		runningTasks := s.ListTasks(StatusRunning)
		assert.GreaterOrEqual(t, len(runningTasks), 1)
		for _, task := range runningTasks {
			assert.Equal(t, string(StatusRunning), task.Status)
		}
	})

	t.Run("list with multiple filters", func(t *testing.T) {
		filteredTasks := s.ListTasks(StatusPending, StatusRunning)
		assert.Equal(t, 5, len(filteredTasks))
	})
}

func TestScheduler_CronJobs(t *testing.T) {
	t.Run("submit cron job", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		executions := atomic.Int32{}
		s.RegisterHandler("cron-test", func(ctx context.Context, task *domain.Task) error {
			executions.Add(1)
			return nil
		})

		task := &domain.Task{
			Name:       "cron-task",
			Type:       "cron-test",
			MaxRetries: 0,
		}

		taskID, err := s.SubmitCron(task, "* * * * * *")
		require.NoError(t, err)
		require.NotEmpty(t, taskID)

		_, exists := s.GetTask(taskID)
		assert.True(t, exists)

		time.Sleep(2 * time.Second)
		assert.GreaterOrEqual(t, int(executions.Load()), 1)
	})

	t.Run("invalid cron expression", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		s.RegisterHandler("cron-test", func(ctx context.Context, task *domain.Task) error {
			return nil
		})

		task := &domain.Task{
			Name:       "invalid-cron",
			Type:       "cron-test",
			MaxRetries: 0,
		}

		_, err := s.SubmitCron(task, "invalid-expression")
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "invalid cron expression")
	})

	t.Run("no handler for cron task", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		task := &domain.Task{
			Name:       "no-handler-cron",
			Type:       "unknown-type",
			MaxRetries: 0,
		}

		_, err := s.SubmitCron(task, "* * * * * *")
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "no handler registered for task type")
	})

	t.Run("remove cron job", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		executions := atomic.Int32{}
		s.RegisterHandler("cron-remove-test", func(ctx context.Context, task *domain.Task) error {
			executions.Add(1)
			return nil
		})

		task := &domain.Task{
			Name:       "cron-remove-task",
			Type:       "cron-remove-test",
			MaxRetries: 0,
		}

		taskID, err := s.SubmitCron(task, "* * * * * *")
		require.NoError(t, err)

		time.Sleep(1500 * time.Millisecond)

		err = s.RemoveCron(taskID)
		require.NoError(t, err)

		executionsBefore := executions.Load()
		time.Sleep(2 * time.Second)
		executionsAfter := executions.Load()

		assert.Equal(t, executionsBefore, executionsAfter, "no more executions after cron removal")
	})

	t.Run("remove non-existent cron job", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		err := s.RemoveCron("non-existent-id")
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "cron job not found")
	})
}

func TestScheduler_ConcurrentTasks(t *testing.T) {
	s := New(WithMaxWorkers(10))
	defer s.Stop()

	var executedTasks atomic.Int32
	var mu sync.Mutex
	var taskIDs []string

	s.RegisterHandler("concurrent-test", func(ctx context.Context, task *domain.Task) error {
		time.Sleep(10 * time.Millisecond)
		executedTasks.Add(1)
		return nil
	})

	numTasks := 100
	var wg sync.WaitGroup
	wg.Add(numTasks)

	for i := 0; i < numTasks; i++ {
		go func(i int) {
			defer wg.Done()
			task := &domain.Task{
				Name:       fmt.Sprintf("concurrent-task-%d", i),
				Type:       "concurrent-test",
				MaxRetries: 0,
			}

			taskID, err := s.Submit(task)
			if err == nil {
				mu.Lock()
				taskIDs = append(taskIDs, taskID)
				mu.Unlock()
			}
		}(i)
	}

	wg.Wait()

	waitForCondition(t, 10*time.Second, func() bool {
		return int(executedTasks.Load()) == numTasks
	}, "all tasks should be executed")

	assert.Equal(t, numTasks, int(executedTasks.Load()))
	assert.Equal(t, numTasks, len(taskIDs))

	for _, taskID := range taskIDs {
		savedTask, exists := s.GetTask(taskID)
		require.True(t, exists)
		assert.Equal(t, string(StatusCompleted), savedTask.Status)
	}
}

func TestScheduler_ConcurrentSubmitAndCancel(t *testing.T) {
	s := New(WithMaxWorkers(5))
	defer s.Stop()

	blocker := make(chan struct{})

	s.RegisterHandler("long-running", func(ctx context.Context, task *domain.Task) error {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-blocker:
			return nil
		}
	})

	var wg sync.WaitGroup
	numOperations := 50

	wg.Add(numOperations * 2)

	for i := 0; i < numOperations; i++ {
		go func(i int) {
			defer wg.Done()
			task := &domain.Task{
				ID:         fmt.Sprintf("task-%d", i),
				Name:       fmt.Sprintf("task-%d", i),
				Type:       "long-running",
				MaxRetries: 0,
			}
			_, _ = s.Submit(task)
		}(i)

		go func(i int) {
			defer wg.Done()
			time.Sleep(5 * time.Millisecond)
			_ = s.CancelTask(fmt.Sprintf("task-%d", i))
		}(i)
	}

	wg.Wait()

	close(blocker)

	allTasks := s.ListTasks()
	for _, task := range allTasks {
		assert.Contains(t, []string{
			string(StatusCompleted),
			string(StatusFailed),
			string(StatusCancelled),
		}, task.Status)
	}
}

func TestScheduler_QueueFull(t *testing.T) {
	s := &Scheduler{
		tasks:        make(map[string]*domain.Task),
		cronJobs:     make(map[string]cron.EntryID),
		handlers:     make(map[string]TaskHandler),
		cron:         cron.New(),
		maxWorkers:   1,
		taskQueue:    make(chan *domain.Task, 1),
		statusChan:   make(chan TaskStatus, 10),
		ctx:          context.Background(),
		cancel:       func() {},
		runningTasks: make(map[string]context.CancelFunc),
	}

	s.handlers["test"] = func(ctx context.Context, task *domain.Task) error {
		return nil
	}

	task1 := &domain.Task{
		ID:   "task-1",
		Name: "task-1",
		Type: "test",
	}

	task2 := &domain.Task{
		ID:   "task-2",
		Name: "task-2",
		Type: "test",
	}

	task3 := &domain.Task{
		ID:   "task-3",
		Name: "task-3",
		Type: "test",
	}

	_, err := s.Submit(task1)
	require.NoError(t, err)

	_, err = s.Submit(task2)
	require.NoError(t, err)

	_, err = s.Submit(task3)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "task queue is full")
}

func TestScheduler_GetNonExistentTask(t *testing.T) {
	s := New(WithMaxWorkers(2))
	defer s.Stop()

	_, exists := s.GetTask("non-existent-id")
	assert.False(t, exists)

	_, exists = s.GetTaskStatus("non-existent-id")
	assert.False(t, exists)
}

func TestScheduler_Stop(t *testing.T) {
	t.Run("stop stops dispatching", func(t *testing.T) {
		s := New(WithMaxWorkers(2))

		var executed atomic.Int32
		s.RegisterHandler("test", func(ctx context.Context, task *domain.Task) error {
			executed.Add(1)
			return nil
		})

		s.Stop()

		_, err := s.Submit(&domain.Task{
			Name:       "after-stop",
			Type:       "test",
			MaxRetries: 0,
		})

		assert.Error(t, err)
		assert.Contains(t, err.Error(), "scheduler is stopped")
	})

	t.Run("stop is idempotent", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		assert.NotPanics(t, func() {
			s.Stop()
			s.Stop()
			s.Stop()
		})
	})
}

func TestScheduler_EmptyTaskID(t *testing.T) {
	s := New(WithMaxWorkers(2))
	defer s.Stop()

	s.RegisterHandler("test", func(ctx context.Context, task *domain.Task) error {
		return nil
	})

	task := &domain.Task{
		ID:         "",
		Name:       "auto-id-task",
		Type:       "test",
		MaxRetries: 0,
	}

	taskID, err := s.Submit(task)
	require.NoError(t, err)
	require.NotEmpty(t, taskID)

	_, err = uuid.Parse(taskID)
	assert.NoError(t, err, "task ID should be a valid UUID")
}

func TestScheduler_TaskParameters(t *testing.T) {
	s := New(WithMaxWorkers(2))
	defer s.Stop()

	var receivedParams map[string]interface{}
	var mu sync.Mutex

	s.RegisterHandler("params-test", func(ctx context.Context, task *domain.Task) error {
		mu.Lock()
		defer mu.Unlock()
		receivedParams = task.Parameters
		return nil
	})

	params := map[string]interface{}{
		"key1": "value1",
		"key2": 42,
		"key3": true,
		"nested": map[string]string{
			"inner": "value",
		},
	}

	task := &domain.Task{
		Name:       "params-task",
		Type:       "params-test",
		Parameters: params,
		MaxRetries: 0,
	}

	taskID, err := s.Submit(task)
	require.NoError(t, err)

	waitForCondition(t, 5*time.Second, func() bool {
		mu.Lock()
		defer mu.Unlock()
		return receivedParams != nil
	}, "task should be executed")

	assert.Equal(t, params, receivedParams)

	savedTask, _ := s.GetTask(taskID)
	assert.Equal(t, params, savedTask.Parameters)
}

func TestScheduler_ExternalDependencyFailure(t *testing.T) {
	t.Run("handler panics gracefully", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		s.RegisterHandler("panic-task", func(ctx context.Context, task *domain.Task) error {
			panic("simulated panic in handler")
		})

		task := &domain.Task{
			Name:       "panic-task",
			Type:       "panic-task",
			MaxRetries: 0,
		}

		taskID, err := s.Submit(task)
		require.NoError(t, err)

		waitForCondition(t, 5*time.Second, func() bool {
			savedTask, _ := s.GetTask(taskID)
			return savedTask != nil && savedTask.Status == string(StatusFailed)
		}, "task should fail")

		savedTask, _ := s.GetTask(taskID)
		assert.Equal(t, string(StatusFailed), savedTask.Status)
	})

	t.Run("context cancellation propagation", func(t *testing.T) {
		s := New(WithMaxWorkers(2))
		defer s.Stop()

		ctx, cancel := context.WithCancel(context.Background())

		s.RegisterHandler("ctx-test", func(taskCtx context.Context, task *domain.Task) error {
			select {
			case <-taskCtx.Done():
				return taskCtx.Err()
			case <-time.After(5 * time.Second):
				return nil
			}
		})

		task := &domain.Task{
			Name:       "ctx-task",
			Type:       "ctx-test",
			MaxRetries: 0,
		}

		s.ctx = ctx
		s.cancel = cancel

		taskID, err := s.Submit(task)
		require.NoError(t, err)

		time.Sleep(100 * time.Millisecond)
		cancel()

		waitForCondition(t, 2*time.Second, func() bool {
			savedTask, _ := s.GetTask(taskID)
			return savedTask != nil && savedTask.Status != string(StatusRunning)
		}, "task should complete or fail")
	})
}

func TestScheduler_BackoffDuration(t *testing.T) {
	s := New(WithMaxWorkers(2))
	defer s.Stop()

	testCases := []struct {
		retry    int
		expected time.Duration
	}{
		{0, 1 * time.Second},
		{1, 2 * time.Second},
		{2, 4 * time.Second},
		{3, 8 * time.Second},
		{4, 16 * time.Second},
		{5, 32 * time.Second},
	}

	for _, tc := range testCases {
		t.Run(fmt.Sprintf("retry_%d", tc.retry), func(t *testing.T) {
			assert.Equal(t, tc.expected, s.backoffDuration(tc.retry))
		})
	}
}

func BenchmarkScheduler_SubmitTask(b *testing.B) {
	s := New(WithMaxWorkers(10))
	defer s.Stop()

	s.RegisterHandler("bench", func(ctx context.Context, task *domain.Task) error {
		return nil
	})

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		task := &domain.Task{
			Name:       fmt.Sprintf("bench-%d", i),
			Type:       "bench",
			MaxRetries: 0,
		}
		_, _ = s.Submit(task)
	}
}

func BenchmarkScheduler_ConcurrentSubmit(b *testing.B) {
	s := New(WithMaxWorkers(50))
	defer s.Stop()

	s.RegisterHandler("concurrent-bench", func(ctx context.Context, task *domain.Task) error {
		return nil
	})

	b.SetParallelism(20)
	b.RunParallel(func(pb *testing.PB) {
		i := 0
		for pb.Next() {
			task := &domain.Task{
				Name:       fmt.Sprintf("bench-%d", i),
				Type:       "concurrent-bench",
				MaxRetries: 0,
			}
			_, _ = s.Submit(task)
			i++
		}
	})
}
