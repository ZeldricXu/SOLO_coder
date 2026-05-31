package scheduler

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/solocoder/tasktracker/internal/config"
	"github.com/solocoder/tasktracker/internal/models"
	"github.com/solocoder/tasktracker/internal/testfixtures"
)

func TestNewScheduler(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name         string
		cfg          Config
		expectWorker int
		expectQueue  int
		expectRetry  int
	}{
		{
			name:         "default values when zero",
			cfg:          Config{},
			expectWorker: 5,
			expectQueue:  100,
			expectRetry:  3,
		},
		{
			name:         "custom values",
			cfg:          Config{WorkerCount: 10, QueueSize: 200, MaxRetries: 5},
			expectWorker: 10,
			expectQueue:  200,
			expectRetry:  5,
		},
		{
			name:         "negative values get defaults",
			cfg:          Config{WorkerCount: -1, QueueSize: -5, MaxRetries: -3},
			expectWorker: 5,
			expectQueue:  100,
			expectRetry:  3,
		},
	}

	for _, tt := range tests {
		tt := tt
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			s := NewScheduler(tt.cfg, nil)
			assert.NotNil(t, s)
			assert.Equal(t, tt.expectWorker, s.workerCount)
			assert.Equal(t, tt.expectRetry, s.maxRetries)
			assert.Equal(t, tt.expectQueue, cap(s.taskQueue))
			assert.NotNil(t, s.tasks)
			assert.NotNil(t, s.runs)
			assert.NotNil(t, s.handlers)
			assert.NotNil(t, s.eventChan)
		})
	}
}

func TestScheduler_RegisterHandler(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1}, nil)

	handler := func(ctx context.Context, task *models.Task) error {
		return nil
	}

	s.RegisterHandler("test_type", handler)

	_, ok := s.getHandler("test_type")
	assert.True(t, ok)
}

func TestScheduler_Submit_BeforeStart(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1}, nil)
	task := testfixtures.NewTaskBuilder().Build()

	err := s.Submit(task)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "scheduler not running")
}

func TestScheduler_Submit_Success(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10}, nil)
	s.Start()
	defer s.Stop()

	task := testfixtures.NewTaskBuilder().WithEmptyID().Build()
	err := s.Submit(task)
	require.NoError(t, err)
	assert.NotEmpty(t, task.ID)
	assert.Equal(t, PhasePending, task.Status)
	assert.Equal(t, 1, task.Priority)
	assert.Equal(t, 3, task.MaxRetries)
}

func TestScheduler_Submit_WithCustomValues(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10}, nil)
	s.Start()
	defer s.Stop()

	task := testfixtures.NewTaskBuilder().
		WithID("custom_id").
		WithPriority(5).
		WithMaxRetries(10).
		Build()

	err := s.Submit(task)
	require.NoError(t, err)
	assert.Equal(t, "custom_id", task.ID)
	assert.Equal(t, 5, task.Priority)
	assert.Equal(t, 10, task.MaxRetries)
}

func TestScheduler_Submit_QueueFull(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 0, QueueSize: 2}, nil)
	s.Start()
	defer s.Stop()

	task1 := testfixtures.NewTaskBuilder().WithID("task_1").Build()
	task2 := testfixtures.NewTaskBuilder().WithID("task_2").Build()
	task3 := testfixtures.NewTaskBuilder().WithID("task_3").Build()

	err := s.Submit(task1)
	require.NoError(t, err)

	err = s.Submit(task2)
	require.NoError(t, err)

	err = s.Submit(task3)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "task queue is full")
}

func TestScheduler_GetTaskStatus(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1}, nil)
	s.Start()
	defer s.Stop()

	t.Run("task exists", func(t *testing.T) {
		task := testfixtures.NewTaskBuilder().WithID("exists").Build()
		err := s.Submit(task)
		require.NoError(t, err)

		found, err := s.GetTaskStatus("exists")
		require.NoError(t, err)
		assert.Equal(t, "exists", found.ID)
	})

	t.Run("task not found", func(t *testing.T) {
		found, err := s.GetTaskStatus("non_existent")
		assert.Nil(t, found)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "task not found")
	})
}

func TestScheduler_GetRunStatus(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1}, nil)
	run := testfixtures.NewRunInstanceBuilder().Build()

	s.mu.Lock()
	s.runs[run.RunID] = run
	s.mu.Unlock()

	t.Run("run exists", func(t *testing.T) {
		found, err := s.GetRunStatus(run.RunID)
		require.NoError(t, err)
		assert.Equal(t, run.RunID, found.RunID)
	})

	t.Run("run not found", func(t *testing.T) {
		found, err := s.GetRunStatus("non_existent")
		assert.Nil(t, found)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "run not found")
	})
}

func TestScheduler_ListTasks(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1}, nil)

	s.mu.Lock()
	s.tasks["t1"] = testfixtures.NewTaskBuilder().WithID("t1").WithStatus(PhasePending).Build()
	s.tasks["t2"] = testfixtures.NewTaskBuilder().WithID("t2").WithStatus(PhaseRunning).Build()
	s.tasks["t3"] = testfixtures.NewTaskBuilder().WithID("t3").WithStatus(PhaseCompleted).Build()
	s.mu.Unlock()

	t.Run("list all tasks", func(t *testing.T) {
		all := s.ListTasks("")
		assert.Len(t, all, 3)
	})

	t.Run("filter by status", func(t *testing.T) {
		pending := s.ListTasks(PhasePending)
		assert.Len(t, pending, 1)
		assert.Equal(t, "t1", pending[0].ID)
	})

	t.Run("filter by non-existent status", func(t *testing.T) {
		none := s.ListTasks("unknown")
		assert.Len(t, none, 0)
	})
}

func TestScheduler_CancelTask(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1}, nil)

	t.Run("cancel pending task", func(t *testing.T) {
		s.mu.Lock()
		s.tasks["cancel_me"] = testfixtures.NewTaskBuilder().WithID("cancel_me").WithStatus(PhasePending).Build()
		s.mu.Unlock()

		err := s.CancelTask("cancel_me")
		require.NoError(t, err)

		s.mu.RLock()
		assert.Equal(t, PhasePaused, s.tasks["cancel_me"].Status)
		s.mu.RUnlock()
	})

	t.Run("cancel running task", func(t *testing.T) {
		s.mu.Lock()
		s.tasks["cancel_running"] = testfixtures.NewTaskBuilder().WithID("cancel_running").WithStatus(PhaseRunning).Build()
		s.mu.Unlock()

		err := s.CancelTask("cancel_running")
		require.NoError(t, err)

		s.mu.RLock()
		assert.Equal(t, PhasePaused, s.tasks["cancel_running"].Status)
		s.mu.RUnlock()
	})

	t.Run("cancel completed task does nothing", func(t *testing.T) {
		s.mu.Lock()
		s.tasks["cancel_completed"] = testfixtures.NewTaskBuilder().WithID("cancel_completed").WithStatus(PhaseCompleted).Build()
		s.mu.Unlock()

		err := s.CancelTask("cancel_completed")
		require.NoError(t, err)

		s.mu.RLock()
		assert.Equal(t, PhaseCompleted, s.tasks["cancel_completed"].Status)
		s.mu.RUnlock()
	})

	t.Run("cancel non-existent task", func(t *testing.T) {
		err := s.CancelTask("non_existent")
		require.Error(t, err)
		assert.Contains(t, err.Error(), "task not found")
	})
}

func TestScheduler_TaskExecution_Success(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10}, nil)
	var executed bool

	s.RegisterHandler("success_task", func(ctx context.Context, task *models.Task) error {
		executed = true
		return nil
	})

	s.Start()
	defer s.Stop()

	task := testfixtures.NewTaskBuilder().WithID("success_1").WithType("success_task").Build()
	err := s.Submit(task)
	require.NoError(t, err)

	assert.Eventually(t, func() bool {
		s.mu.RLock()
		defer s.mu.RUnlock()
		return s.tasks["success_1"].Status == PhaseCompleted
	}, 2*time.Second, 50*time.Millisecond)

	assert.True(t, executed)
}

func TestScheduler_TaskExecution_NoHandler(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10, MaxRetries: 1}, nil)
	s.Start()
	defer s.Stop()

	task := testfixtures.NewTaskBuilder().WithID("no_handler").WithType("unknown_type").WithMaxRetries(1).Build()
	err := s.Submit(task)
	require.NoError(t, err)

	assert.Eventually(t, func() bool {
		s.mu.RLock()
		defer s.mu.RUnlock()
		return s.tasks["no_handler"].Status == PhaseFailed
	}, 2*time.Second, 50*time.Millisecond)

	s.mu.RLock()
	defer s.mu.RUnlock()
	assert.Equal(t, 1, s.tasks["no_handler"].RetryCount)
	assert.NotNil(t, s.tasks["no_handler"].LastError)
	assert.Contains(t, *s.tasks["no_handler"].LastError, "no handler registered")
}

func TestScheduler_TaskExecution_RetryMechanism(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10}, nil)
	attempts := 0

	s.RegisterHandler("retry_task", func(ctx context.Context, task *models.Task) error {
		attempts++
		if attempts < 3 {
			return errors.New("temporary failure")
		}
		return nil
	})

	s.Start()
	defer s.Stop()

	task := testfixtures.NewTaskBuilder().WithID("retry_1").WithType("retry_task").WithMaxRetries(3).Build()
	err := s.Submit(task)
	require.NoError(t, err)

	assert.Eventually(t, func() bool {
		s.mu.RLock()
		defer s.mu.RUnlock()
		return s.tasks["retry_1"].Status == PhaseCompleted
	}, 5*time.Second, 100*time.Millisecond)

	assert.Equal(t, 3, attempts)
}

func TestScheduler_TaskExecution_PermanentFailure(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10, MaxRetries: 2}, nil)
	attempts := 0

	s.RegisterHandler("always_fail", func(ctx context.Context, task *models.Task) error {
		attempts++
		return errors.New("permanent failure")
	})

	s.Start()
	defer s.Stop()

	task := testfixtures.NewTaskBuilder().WithID("perm_fail").WithType("always_fail").WithMaxRetries(2).Build()
	err := s.Submit(task)
	require.NoError(t, err)

	assert.Eventually(t, func() bool {
		s.mu.RLock()
		defer s.mu.RUnlock()
		return s.tasks["perm_fail"].Status == PhaseFailed
	}, 5*time.Second, 100*time.Millisecond)

	assert.Equal(t, 2, attempts)
}

func TestScheduler_TaskExecution_Timeout(t *testing.T) {
	t.Parallel()

	cfgManager := config.NewManager("test")
	cfg := testfixtures.NewConfigBuilder().
		WithConfigID("scheduler").
		WithParams(map[string]interface{}{"task_timeout": "100ms"}).
		Build()
	err := cfgManager.Set(cfg)
	require.NoError(t, err)

	s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10}, cfgManager)

	s.RegisterHandler("slow_task", func(ctx context.Context, task *models.Task) error {
		select {
		case <-time.After(500 * time.Millisecond):
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	})

	s.Start()
	defer s.Stop()

	task := testfixtures.NewTaskBuilder().WithID("timeout_1").WithType("slow_task").WithMaxRetries(1).Build()
	err = s.Submit(task)
	require.NoError(t, err)

	assert.Eventually(t, func() bool {
		s.mu.RLock()
		defer s.mu.RUnlock()
		return s.tasks["timeout_1"].Status == PhaseFailed
	}, 3*time.Second, 100*time.Millisecond)
}

func TestScheduler_TransactionRollback_OnError(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10}, nil)

	var mu sync.Mutex
	var completedSteps []string

	s.RegisterHandler("rollback_test", func(ctx context.Context, task *models.Task) error {
		mu.Lock()
		completedSteps = append(completedSteps, "step1")
		mu.Unlock()

		return errors.New("error at step2")
	})

	s.Start()
	defer s.Stop()

	task := testfixtures.NewTaskBuilder().WithID("rollback_1").WithType("rollback_test").WithMaxRetries(1).Build()
	err := s.Submit(task)
	require.NoError(t, err)

	assert.Eventually(t, func() bool {
		s.mu.RLock()
		defer s.mu.RUnlock()
		return s.tasks["rollback_1"].Status == PhaseFailed
	}, 2*time.Second, 50*time.Millisecond)

	s.mu.RLock()
	run := s.runs["run_rollback_1"]
	s.mu.RUnlock()

	assert.NotNil(t, run)
	assert.Equal(t, PhaseFailed, run.Phase)
	assert.NotNil(t, run.ErrorDetail)
	assert.NotNil(t, run.CompletedAt)
	assert.Equal(t, 1.0, run.Progress)

	mu.Lock()
	defer mu.Unlock()
	assert.Contains(t, completedSteps, "step1")
}

func TestScheduler_StartStop_Idempotent(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1}, nil)

	assert.NotPanics(t, func() {
		s.Start()
		s.Start()
	})

	assert.NotPanics(t, func() {
		s.Stop()
		s.Stop()
	})
}

func TestScheduler_GetStats(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 3}, nil)

	s.mu.Lock()
	s.tasks["t1"] = testfixtures.NewTaskBuilder().WithID("t1").WithStatus(PhasePending).Build()
	s.tasks["t2"] = testfixtures.NewTaskBuilder().WithID("t2").WithStatus(PhasePending).Build()
	s.tasks["t3"] = testfixtures.NewTaskBuilder().WithID("t3").WithStatus(PhaseRunning).Build()
	s.tasks["t4"] = testfixtures.NewTaskBuilder().WithID("t4").WithStatus(PhaseCompleted).Build()
	s.tasks["t5"] = testfixtures.NewTaskBuilder().WithID("t5").WithStatus(PhaseFailed).Build()
	s.tasks["t6"] = testfixtures.NewTaskBuilder().WithID("t6").WithStatus(PhaseRetry).Build()
	s.mu.Unlock()

	stats := s.GetStats()

	assert.Equal(t, 6, stats["total_tasks"])
	assert.Equal(t, 2, stats["pending"])
	assert.Equal(t, 2, stats["running"])
	assert.Equal(t, 1, stats["completed"])
	assert.Equal(t, 1, stats["failed"])
	assert.Equal(t, 3, stats["worker_count"])
}

func TestScheduler_ConcurrentSubmit(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 2, QueueSize: 100}, nil)
	s.RegisterHandler("concurrent", func(ctx context.Context, task *models.Task) error {
		return nil
	})
	s.Start()
	defer s.Stop()

	var wg sync.WaitGroup
	numTasks := 50

	for i := 0; i < numTasks; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			task := testfixtures.NewTaskBuilder().
				WithID(string(rune('a'+idx%26)) + string(rune('0'+idx/26))).
				WithType("concurrent").
				Build()
			_ = s.Submit(task)
		}(i)
	}

	wg.Wait()

	assert.Eventually(t, func() bool {
		stats := s.GetStats()
		return stats["total_tasks"] == numTasks
	}, 3*time.Second, 100*time.Millisecond)
}

func TestScheduler_ContextPropagation(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10}, nil)
	var receivedTraceID string

	s.RegisterHandler("ctx_test", func(ctx context.Context, task *models.Task) error {
		receivedTraceID, _ = ctx.Value("trace_id").(string)
		return nil
	})

	s.Start()
	defer s.Stop()

	task := testfixtures.NewTaskBuilder().WithID("ctx_1").WithType("ctx_test").Build()
	err := s.Submit(task)
	require.NoError(t, err)

	assert.Eventually(t, func() bool {
		return receivedTraceID != ""
	}, 2*time.Second, 50*time.Millisecond)

	assert.Contains(t, receivedTraceID, "run_ctx_1")
}

func TestScheduler_EventEmission(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10}, nil)

	s.RegisterHandler("event_test", func(ctx context.Context, task *models.Task) error {
		return nil
	})

	events := make([]*models.Event, 0)
	done := make(chan bool)

	go func() {
		for event := range s.eventChan {
			events = append(events, event)
			if len(events) >= 2 {
				done <- true
				return
			}
		}
	}()

	s.Start()
	defer s.Stop()

	task := testfixtures.NewTaskBuilder().WithID("event_1").WithType("event_test").Build()
	err := s.Submit(task)
	require.NoError(t, err)

	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("timeout waiting for events")
	}

	eventTypes := make([]string, len(events))
	for i, e := range events {
		eventTypes[i] = e.Type
	}

	assert.Contains(t, eventTypes, "task.submitted")
	assert.Contains(t, eventTypes, "task.started")
}

func TestScheduler_DefaultValuesOnSubmit(t *testing.T) {
	t.Parallel()

	s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10, MaxRetries: 3}, nil)
	s.Start()
	defer s.Stop()

	t.Run("empty ID gets generated", func(t *testing.T) {
		task := testfixtures.NewTaskBuilder().WithEmptyID().Build()
		err := s.Submit(task)
		require.NoError(t, err)
		assert.NotEmpty(t, task.ID)
	})

	t.Run("zero priority gets default 1", func(t *testing.T) {
		task := testfixtures.NewTaskBuilder().WithID("prio_test").Build()
		task.Priority = 0
		err := s.Submit(task)
		require.NoError(t, err)
		assert.Equal(t, 1, task.Priority)
	})

	t.Run("zero max retries gets scheduler default", func(t *testing.T) {
		task := testfixtures.NewTaskBuilder().WithID("retry_default").Build()
		task.MaxRetries = 0
		err := s.Submit(task)
		require.NoError(t, err)
		assert.Equal(t, 3, task.MaxRetries)
	})

	t.Run("empty status gets pending", func(t *testing.T) {
		task := testfixtures.NewTaskBuilder().WithID("status_default").Build()
		task.Status = ""
		err := s.Submit(task)
		require.NoError(t, err)
		assert.Equal(t, PhasePending, task.Status)
	})
}

func TestScheduler_ConcurrentSafety(t *testing.T) {
	t.Parallel()

	t.Run("cancel task during execution prevents retry", func(t *testing.T) {
		s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10, MaxRetries: 3}, nil)
		started := make(chan bool)
		block := make(chan bool)

		s.RegisterHandler("cancel_test", func(ctx context.Context, task *models.Task) error {
			close(started)
			<-block
			return errors.New("simulated error")
		})

		s.Start()
		defer s.Stop()

		task := testfixtures.NewTaskBuilder().WithID("cancel_during_retry").WithType("cancel_test").Build()
		err := s.Submit(task)
		require.NoError(t, err)

		<-started

		err = s.CancelTask("cancel_during_retry")
		require.NoError(t, err)

		close(block)

		assert.Eventually(t, func() bool {
			s.mu.RLock()
			defer s.mu.RUnlock()
			status := s.tasks["cancel_during_retry"].Status
			return status == PhasePaused || status == PhaseFailed
		}, 2*time.Second, 50*time.Millisecond)

		s.mu.RLock()
		defer s.mu.RUnlock()
		assert.NotEqual(t, PhaseRetry, s.tasks["cancel_during_retry"].Status)
	})

	t.Run("stop scheduler during retry does not panic", func(t *testing.T) {
		s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10, MaxRetries: 3}, nil)
		attempts := 0

		s.RegisterHandler("stop_test", func(ctx context.Context, task *models.Task) error {
			attempts++
			return errors.New("always fail")
		})

		s.Start()

		task := testfixtures.NewTaskBuilder().WithID("stop_during_retry").WithType("stop_test").WithMaxRetries(5).Build()
		err := s.Submit(task)
		require.NoError(t, err)

		time.Sleep(100 * time.Millisecond)

		assert.NotPanics(t, func() {
			s.Stop()
		})
	})

	t.Run("emit event after stop does not panic", func(t *testing.T) {
		s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10}, nil)
		s.Start()
		s.Stop()

		assert.NotPanics(t, func() {
			s.emitEvent("test.event", map[string]interface{}{"key": "value"})
		})
	})

	t.Run("start stop multiple times is safe", func(t *testing.T) {
		s := NewScheduler(Config{WorkerCount: 1, QueueSize: 10}, nil)

		assert.NotPanics(t, func() {
			s.Start()
			s.Start()
			s.Stop()
			s.Stop()
			s.Start()
			s.Stop()
		})
	})
}
