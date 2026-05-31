package scheduler

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"techplatform/internal/testdata"
	"techplatform/pkg/common/utils"
	"techplatform/pkg/models"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type taskBuilder struct {
	task *Task
}

func newTaskBuilder() *taskBuilder {
	return &taskBuilder{
		task: &Task{
			BaseModel: models.BaseModel{ID: utils.GenerateUUID()},
			Name:      "test-task",
			Type:      TaskTypeCron,
			CronExpr:  "0 */5 * * * *",
			Handler:   "test_handler",
			Priority:  PriorityNormal,
			MaxRetry:  3,
			Enabled:   true,
			Status:    StatusPending,
			Timeout:   30,
		},
	}
}

func (b *taskBuilder) withName(name string) *taskBuilder {
	b.task.Name = name
	return b
}

func (b *taskBuilder) withType(t TaskType) *taskBuilder {
	b.task.Type = t
	return b
}

func (b *taskBuilder) withCronExpr(expr string) *taskBuilder {
	b.task.CronExpr = expr
	return b
}

func (b *taskBuilder) withInterval(seconds int) *taskBuilder {
	b.task.Type = TaskTypeInterval
	b.task.Interval = seconds
	return b
}

func (b *taskBuilder) withOnce() *taskBuilder {
	b.task.Type = TaskTypeOnce
	return b
}

func (b *taskBuilder) withHandler(name string) *taskBuilder {
	b.task.Handler = name
	return b
}

func (b *taskBuilder) withPriority(p TaskPriority) *taskBuilder {
	b.task.Priority = p
	return b
}

func (b *taskBuilder) withTimeout(seconds int) *taskBuilder {
	b.task.Timeout = seconds
	return b
}

func (b *taskBuilder) withMaxRetry(n int) *taskBuilder {
	b.task.MaxRetry = n
	return b
}

func (b *taskBuilder) withEnabled(enabled bool) *taskBuilder {
	b.task.Enabled = enabled
	return b
}

func (b *taskBuilder) withParams(params map[string]interface{}) *taskBuilder {
	b.task.Params = utils.ToJSON(params)
	return b
}

func (b *taskBuilder) build() *Task {
	return b.task
}

func setupScheduler(t *testing.T) (*Scheduler, func()) {
	t.Helper()
	dao, daoCleanup := testdata.NewTestDAO("")
	s := NewScheduler(dao, SchedulerConfig{WorkerCount: 3, QueueSize: 50})
	s.RegisterHandler("test_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		return "ok", nil
	})
	s.RegisterHandler("slow_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		time.Sleep(2 * time.Second)
		return "slow_ok", nil
	})
	s.RegisterHandler("fail_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		return "", errors.New("intentional failure")
	})
	s.RegisterHandler("panic_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		panic("intentional panic")
	})
	cleanup := func() {
		s.Stop()
		daoCleanup()
	}
	return s, cleanup
}

func TestNewScheduler(t *testing.T) {
	dao, daoCleanup := testdata.NewTestDAO("")
	defer daoCleanup()

	s := NewScheduler(dao, SchedulerConfig{WorkerCount: 5, QueueSize: 100})
	assert.NotNil(t, s)
	assert.NotNil(t, s.handlers)
	assert.NotNil(t, s.tasks)
	assert.NotNil(t, s.running)
	assert.Equal(t, 5, cap(s.workerPool))
}

func TestNewScheduler_DefaultConfig(t *testing.T) {
	dao, daoCleanup := testdata.NewTestDAO("")
	defer daoCleanup()

	s := NewScheduler(dao, SchedulerConfig{})
	assert.Equal(t, 5, s.config.WorkerCount)
	assert.Equal(t, 1000, s.config.QueueSize)
}

func TestRegisterHandler(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	s.RegisterHandler("custom_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		return "custom", nil
	})

	assert.Contains(t, s.handlers, "custom_handler")
}

func TestStart(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	err := s.Start()
	require.NoError(t, err)
	assert.True(t, s.started)
}

func TestStart_DoubleStart(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	err := s.Start()
	require.NoError(t, err)

	err = s.Start()
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "already started")
}

func TestStop_WithoutStart(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	s.Stop()
	assert.False(t, s.started)
}

func TestCreateTask(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := newTaskBuilder().withName("test-cron-task").build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)
	assert.NotEmpty(t, created.ID)
	assert.Equal(t, "test-cron-task", created.Name)
	assert.Equal(t, StatusPending, created.Status)
	assert.True(t, created.Enabled)
	assert.Equal(t, 3, created.MaxRetry)
}

func TestCreateTask_NoName(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := newTaskBuilder().withName("").build()
	_, err := s.CreateTask(task)
	assert.Error(t, err)
}

func TestCreateTask_NoHandler(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := newTaskBuilder().withHandler("").build()
	_, err := s.CreateTask(task)
	assert.Error(t, err)
}

func TestCreateTask_UnregisteredHandler(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := newTaskBuilder().withHandler("nonexistent").build()
	_, err := s.CreateTask(task)
	assert.Error(t, err)
}

func TestCreateTask_IntervalType(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := newTaskBuilder().
		withName("interval-task").
		withInterval(60).
		build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)
	assert.Equal(t, TaskTypeInterval, created.Type)
	assert.Equal(t, 60, created.Interval)
}

func TestCreateTask_OnceType(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := newTaskBuilder().
		withName("once-task").
		withOnce().
		build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)
	assert.Equal(t, TaskTypeOnce, created.Type)
}

func TestGetTask(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := newTaskBuilder().withName("get-test").build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	found, err := s.GetTask(created.ID)
	require.NoError(t, err)
	assert.Equal(t, created.Name, found.Name)
}

func TestGetTask_NotFound(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	_, err := s.GetTask("nonexistent-id")
	assert.Error(t, err)
}

func TestListTasks(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	for i := 0; i < 5; i++ {
		task := newTaskBuilder().withName(fmt.Sprintf("list-task-%d", i)).build()
		_, err := s.CreateTask(task)
		require.NoError(t, err)
	}

	result, err := s.ListTasks(1, 3, "")
	require.NoError(t, err)
	assert.Equal(t, int64(5), result.Total)
	assert.Equal(t, 3, len(result.Items.([]Task)))
}

func TestListTasks_FilterByStatus(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := newTaskBuilder().withName("pending-task").build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)
	created.Status = StatusFailed
	s.db.DB().Save(created)

	result, err := s.ListTasks(1, 10, StatusFailed)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, result.Total, int64(1))
}

func TestUpdateTask(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := newTaskBuilder().withName("update-test").build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	updated, err := s.UpdateTask(created.ID, map[string]interface{}{
		"name": "updated-name",
	})
	require.NoError(t, err)
	assert.Equal(t, "updated-name", updated.Name)
}

func TestUpdateTask_EnableDisable(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()
	s.Start()

	task := newTaskBuilder().withName("toggle-test").build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	_, err = s.UpdateTask(created.ID, map[string]interface{}{"enabled": false})
	require.NoError(t, err)

	found, _ := s.GetTask(created.ID)
	assert.False(t, found.Enabled)

	_, err = s.UpdateTask(created.ID, map[string]interface{}{"enabled": true})
	require.NoError(t, err)

	found, _ = s.GetTask(created.ID)
	assert.True(t, found.Enabled)
}

func TestUpdateTask_NotFound(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	_, err := s.UpdateTask("nonexistent", map[string]interface{}{"name": "x"})
	assert.Error(t, err)
}

func TestDeleteTask(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := newTaskBuilder().withName("delete-test").build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	err = s.DeleteTask(created.ID)
	require.NoError(t, err)

	_, err = s.GetTask(created.ID)
	assert.Error(t, err)
}

func TestDeleteTask_NotFound(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	err := s.DeleteTask("nonexistent")
	assert.Error(t, err)
}

func TestRunTaskNow(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()
	s.Start()

	var executed atomic.Bool
	s.RegisterHandler("run_now_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		executed.Store(true)
		return "executed", nil
	})

	task := newTaskBuilder().
		withName("run-now-test").
		withHandler("run_now_handler").
		build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	err = s.RunTaskNow(created.ID)
	require.NoError(t, err)

	assert.Eventually(t, func() bool { return executed.Load() }, 5*time.Second, 100*time.Millisecond)
}

func TestRunTaskNow_DisabledTask(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()
	s.Start()

	task := newTaskBuilder().
		withName("disabled-task").
		build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	_, err = s.UpdateTask(created.ID, map[string]interface{}{"enabled": false})
	require.NoError(t, err)

	err = s.RunTaskNow(created.ID)
	assert.Error(t, err)
}

func TestPauseResume(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()
	s.Start()

	task := newTaskBuilder().withName("pause-test").build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	err = s.PauseTask(created.ID)
	require.NoError(t, err)
	found, _ := s.GetTask(created.ID)
	assert.False(t, found.Enabled)

	err = s.ResumeTask(created.ID)
	require.NoError(t, err)
	found, _ = s.GetTask(created.ID)
	assert.True(t, found.Enabled)
}

func TestGetTaskExecutions(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := newTaskBuilder().withName("exec-test").build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	exec := &TaskExecution{
		TaskID:    created.ID,
		TaskName:  created.Name,
		Status:    StatusSuccess,
		StartedAt: time.Now(),
		Result:    "test result",
	}
	s.db.DB().Create(exec)

	result, err := s.GetTaskExecutions(created.ID, 1, 10)
	require.NoError(t, err)
	assert.Equal(t, int64(1), result.Total)
}

func TestGetStats(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	for i := 0; i < 3; i++ {
		task := newTaskBuilder().withName(fmt.Sprintf("stats-task-%d", i)).build()
		_, err := s.CreateTask(task)
		require.NoError(t, err)
	}

	stats := s.GetStats()
	assert.NotNil(t, stats)
	assert.GreaterOrEqual(t, stats.TotalTasks, int64(3))
}

func TestTaskExecution_Success(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()
	s.Start()

	s.RegisterHandler("success_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		return "success_result", nil
	})

	task := newTaskBuilder().
		withName("success-task").
		withHandler("success_handler").
		build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	err = s.RunTaskNow(created.ID)
	require.NoError(t, err)

	time.Sleep(500 * time.Millisecond)

	found, _ := s.GetTask(created.ID)
	assert.Equal(t, StatusSuccess, found.Status)
	assert.Equal(t, "success_result", found.LastResult)
}

func TestTaskExecution_Failure(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()
	s.Start()

	task := newTaskBuilder().
		withName("fail-task").
		withHandler("fail_handler").
		withMaxRetry(1).
		build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	err = s.RunTaskNow(created.ID)
	require.NoError(t, err)

	time.Sleep(1 * time.Second)

	found, _ := s.GetTask(created.ID)
	assert.Equal(t, StatusFailed, found.Status)
	assert.Contains(t, found.LastError, "intentional failure")
}

func TestTaskExecution_Panic(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()
	s.Start()

	task := newTaskBuilder().
		withName("panic-task").
		withHandler("panic_handler").
		withMaxRetry(0).
		build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	err = s.RunTaskNow(created.ID)
	require.NoError(t, err)

	time.Sleep(1 * time.Second)

	found, _ := s.GetTask(created.ID)
	assert.Equal(t, StatusFailed, found.Status)
}

func TestTaskExecution_Timeout(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()
	s.Start()

	s.RegisterHandler("timeout_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(10 * time.Second):
			return "done", nil
		}
	})

	task := newTaskBuilder().
		withName("timeout-task").
		withHandler("timeout_handler").
		withTimeout(1).
		withMaxRetry(0).
		build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	err = s.RunTaskNow(created.ID)
	require.NoError(t, err)

	time.Sleep(3 * time.Second)

	found, _ := s.GetTask(created.ID)
	assert.Equal(t, StatusFailed, found.Status)
}

func TestTaskExecution_WithParams(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()
	s.Start()

	var receivedParams map[string]interface{}
	s.RegisterHandler("param_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		receivedParams = params
		return "params_received", nil
	})

	task := newTaskBuilder().
		withName("param-task").
		withHandler("param_handler").
		withParams(map[string]interface{}{"key": "value", "num": 42}).
		build()
	created, err := s.CreateTask(task)
	require.NoError(t, err)

	err = s.RunTaskNow(created.ID)
	require.NoError(t, err)

	assert.Eventually(t, func() bool {
		return receivedParams != nil
	}, 3*time.Second, 100*time.Millisecond)
}

func TestConcurrentTaskCreation(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	var wg sync.WaitGroup
	const goroutines = 20
	createdIDs := make(chan string, goroutines)

	for i := 0; i < goroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			task := newTaskBuilder().
				withName(fmt.Sprintf("concurrent-task-%d", idx)).
				build()
			created, err := s.CreateTask(task)
			if err == nil {
				createdIDs <- created.ID
			}
		}(i)
	}

	wg.Wait()
	close(createdIDs)

	count := 0
	for range createdIDs {
		count++
	}
	assert.Equal(t, goroutines, count)
}

func TestConcurrentTaskExecution(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()
	s.Start()

	var execCount atomic.Int32
	s.RegisterHandler("concurrent_exec_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		execCount.Add(1)
		return "concurrent", nil
	})

	taskIDs := make([]string, 5)
	for i := 0; i < 5; i++ {
		task := newTaskBuilder().
			withName(fmt.Sprintf("concurrent-exec-%d", i)).
			withHandler("concurrent_exec_handler").
			build()
		created, err := s.CreateTask(task)
		require.NoError(t, err)
		taskIDs[i] = created.ID
	}

	for _, id := range taskIDs {
		s.RunTaskNow(id)
	}

	assert.Eventually(t, func() bool {
		return execCount.Load() >= 5
	}, 10*time.Second, 200*time.Millisecond)
}

func TestSchedulerLifecycle(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	err := s.Start()
	require.NoError(t, err)
	assert.True(t, s.started)

	s.Stop()
	assert.False(t, s.started)

	s2 := NewScheduler(s.db, SchedulerConfig{WorkerCount: 3})
	s2.RegisterHandler("test_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		return "ok", nil
	})
	err = s2.Start()
	require.NoError(t, err)
	s2.Stop()
}

func TestWorkerPoolLimits(t *testing.T) {
	dao, daoCleanup := testdata.NewTestDAO("")
	defer daoCleanup()

	s := NewScheduler(dao, SchedulerConfig{WorkerCount: 2, QueueSize: 10})

	var running atomic.Int32
	var maxRunning atomic.Int32

	s.RegisterHandler("limited_handler", func(ctx context.Context, params map[string]interface{}) (string, error) {
		cur := running.Add(1)
		for {
			old := maxRunning.Load()
			if cur <= old || maxRunning.CompareAndSwap(old, cur) {
				break
			}
		}
		time.Sleep(200 * time.Millisecond)
		running.Add(-1)
		return "limited", nil
	})

	s.Start()
	defer s.Stop()

	for i := 0; i < 5; i++ {
		task := newTaskBuilder().
			withName(fmt.Sprintf("pool-task-%d", i)).
			withHandler("limited_handler").
			withMaxRetry(0).
			build()
		created, err := s.CreateTask(task)
		require.NoError(t, err)
		s.RunTaskNow(created.ID)
	}

	time.Sleep(500 * time.Millisecond)
	assert.LessOrEqual(t, maxRunning.Load(), int32(2))
}

func TestCreateTask_DefaultPriority(t *testing.T) {
	s, cleanup := setupScheduler(t)
	defer cleanup()

	task := &Task{
		Name:     "default-priority",
		Handler:  "test_handler",
		Type:     TaskTypeCron,
		CronExpr: "0 */5 * * * *",
	}
	created, err := s.CreateTask(task)
	require.NoError(t, err)
	assert.Equal(t, PriorityNormal, created.Priority)
	assert.Equal(t, 3, created.MaxRetry)
}
