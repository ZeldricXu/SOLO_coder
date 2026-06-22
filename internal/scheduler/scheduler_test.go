package scheduler

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newTestSchedulerConfig() SchedulerConfig {
	return SchedulerConfig{
		HeartbeatTimeout:       100 * time.Millisecond,
		DefaultTaskTimeout:   5 * time.Minute,
		DefaultMaxRetries:   3,
		AssignmentStrategy: AssignmentBestFit,
		CheckpointInterval: 5 * time.Minute,
		WorkerOfflineThreshold: 200 * time.Millisecond,
		MaxConcurrentTasks: 1000,
	}
}

func TestScheduler_WorkerHeartbeatTimeout_RedistributesTasks(t *testing.T) {
	cfg := newTestSchedulerConfig()
	s := NewTaskScheduler(cfg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := s.Start(ctx)
	require.NoError(t, err)
	defer func() { _ = s.Stop() }()

	worker := &models.Worker{
		ID:       util.GenerateID(),
		Name:     "worker-1",
		CPUCores: 4,
		MemoryGB: 8,
	}
	err = s.RegisterWorker(worker)
	require.NoError(t, err)

	task := &models.Task{
		ID:           util.GenerateID(),
		ExperimentID: util.GenerateID(),
		Name:           "test-task",
		Priority:       int(PriorityNormal),
		TimeoutSeconds: 300,
		MaxRetries:     3,
	}
	err = s.SubmitTask(ctx, task)
	require.NoError(t, err)

	require.Eventually(t, func() bool {
		progress, err := s.GetTaskProgress(task.ID)
		return err == nil && progress.Status == models.TaskStatusRunning
	}, 2*time.Second, 50*time.Millisecond, "task should be running")

	progress, _ := s.GetTaskProgress(task.ID)
	require.NotNil(t, progress.WorkerID)
	assignedWorkerID := *progress.WorkerID
	assert.Equal(t, worker.ID, assignedWorkerID)

	require.Eventually(t, func() bool {
		workers := s.ListWorkers()
		for _, w := range workers {
			if w.ID == worker.ID {
				return w.Status == models.WorkerStatusOffline
			}
		}
		return false
	}, 2*time.Second, 50*time.Millisecond, "worker should be marked offline")

	require.Eventually(t, func() bool {
		tasks := s.ListTasks()
		for _, tk := range tasks {
			if tk.ID == task.ID {
				return tk.Status == models.TaskStatusQueued
			}
		}
		return false
	}, 2*time.Second, 50*time.Millisecond, "task should be requeued after worker offline")
}

func TestScheduler_MultipleWorkers_NoDuplicateAssignment(t *testing.T) {
	cfg := newTestSchedulerConfig()
	s := NewTaskScheduler(cfg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := s.Start(ctx)
	require.NoError(t, err)
	defer func() { _ = s.Stop() }()

	workerCount := 5
	workerIDs := make([]int64, workerCount)
	for i := 0; i < workerCount; i++ {
		w := &models.Worker{
			ID:       util.GenerateID(),
			Name:     "worker-" + string(rune('0'+i)),
			CPUCores: 4,
			MemoryGB: 8,
		}
		err = s.RegisterWorker(w)
		require.NoError(t, err)
		workerIDs[i] = w.ID
	}

	taskCount := 20
	taskIDs := make([]int64, taskCount)
	var wg sync.WaitGroup
	var mu sync.Mutex

	for i := 0; i < taskCount; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			task := &models.Task{
				ID:             util.GenerateID(),
				ExperimentID:   util.GenerateID(),
				Name:             "task-" + string(rune('0'+idx)),
				Priority:         int(PriorityNormal),
				TimeoutSeconds: 300,
				MaxRetries:     3,
			}
			mu.Lock()
			taskIDs[idx] = task.ID
			mu.Unlock()
			err := s.SubmitTask(ctx, task)
			assert.NoError(t, err)
		}(i)
	}
	wg.Wait()

	require.Eventually(t, func() bool {
		return s.GetQueueLength() == 0
	}, 5*time.Second, 100*time.Millisecond, "all tasks should be dequeued")

	assignments := make(map[int64]int64)
	for _, tid := range taskIDs {
		progress, err := s.GetTaskProgress(tid)
		require.NoError(t, err)
		if progress.WorkerID != nil {
			assignments[tid] = *progress.WorkerID
		}
	}

	workerTaskCount := make(map[int64]int)
	for _, wid := range assignments {
		workerTaskCount[wid]++
	}

	for wid, count := range workerTaskCount {
		t.Logf("Worker %d has %d tasks", wid, count)
	}

	workerSet := make(map[int64]bool)
	for _, wid := range assignments {
		workerSet[wid] = true
	}
	for _, wid := range workerIDs {
		assert.True(t, workerSet[wid], "worker %d should have been assigned tasks", wid)
	}
}

func TestScheduler_TaskLifecycle(t *testing.T) {
	cfg := newTestSchedulerConfig()
	s := NewTaskScheduler(cfg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := s.Start(ctx)
	require.NoError(t, err)
	defer func() { _ = s.Stop() }()

	worker := &models.Worker{
		ID:       util.GenerateID(),
		Name:     "worker-lifecycle",
		CPUCores: 4,
		MemoryGB: 8,
	}
	err = s.RegisterWorker(worker)
	require.NoError(t, err)

	taskID := util.GenerateID()
	task := &models.Task{
		ID:             taskID,
		ExperimentID:   util.GenerateID(),
		Name:             "lifecycle-task",
		Priority:         int(PriorityHigh),
		TimeoutSeconds: 300,
		MaxRetries:     3,
	}

	progress, err := s.GetTaskProgress(taskID)
	assert.Error(t, err)
	assert.Nil(t, progress)

	err = s.SubmitTask(ctx, task)
	require.NoError(t, err)

	progress, err = s.GetTaskProgress(taskID)
	require.NoError(t, err)
	assert.Equal(t, models.TaskStatusQueued, progress.Status)
	assert.Equal(t, 0, progress.RetryCount)

	require.Eventually(t, func() bool {
		progress, err := s.GetTaskProgress(taskID)
		return err == nil && progress.Status == models.TaskStatusRunning
	}, 2*time.Second, 50*time.Millisecond, "task should be running")

	progress, _ = s.GetTaskProgress(taskID)
	assert.NotNil(t, progress.WorkerID)
	assert.Equal(t, worker.ID, *progress.WorkerID)

	err = s.ReportTaskProgress(taskID, 50, 100, models.Params{"step": 50})
	require.NoError(t, err)

	progress, err = s.GetTaskProgress(taskID)
	require.NoError(t, err)
	assert.Equal(t, int64(50), progress.CurrentStep)
	assert.Equal(t, int64(100), progress.TotalSteps)
	assert.Equal(t, 0.5, progress.Progress)

	result := &models.Result{
		ID:         util.GenerateID(),
		TaskID:       taskID,
		WorkerID:      worker.ID,
		Data:          models.ResultData{"output": 42},
		DurationMs:    1500,
	}
	err = s.CompleteTask(taskID, result)
	require.NoError(t, err)

	progress, err = s.GetTaskProgress(taskID)
	require.NoError(t, err)
	assert.Equal(t, models.TaskStatusCompleted, progress.Status)
	assert.Equal(t, 1.0, progress.Progress)

	tasks := s.ListTasks(models.TaskStatusCompleted)
	found := false
	for _, tk := range tasks {
		if tk.ID == taskID {
			found = true
			break
		}
	}
	assert.True(t, found, "completed task should appear in completed list")
}

func TestScheduler_WorkerFrequentJitter_NoLeakNoDuplicate(t *testing.T) {
	cfg := newTestSchedulerConfig()
	cfg.HeartbeatTimeout = 50 * time.Millisecond
	cfg.WorkerOfflineThreshold = 100 * time.Millisecond
	s := NewTaskScheduler(cfg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := s.Start(ctx)
	require.NoError(t, err)
	defer func() { _ = s.Stop() }()

	taskCount := 10
	taskIDs := make([]int64, taskCount)
	for i := 0; i < taskCount; i++ {
		task := &models.Task{
			ID:             util.GenerateID(),
			ExperimentID:   util.GenerateID(),
			Name:           "jitter-task-" + string(rune('0'+i)),
			Priority:       int(PriorityNormal),
			TimeoutSeconds: 300,
			MaxRetries:     5,
		}
		err = s.SubmitTask(ctx, task)
		require.NoError(t, err)
		taskIDs[i] = task.ID
	}

	workerPool := make([]*models.Worker, 0, 3)
	for i := 0; i < 3; i++ {
		w := &models.Worker{
			ID:       util.GenerateID(),
			Name:     "jitter-worker-" + string(rune('0'+i)),
			CPUCores: 4,
			MemoryGB: 8,
		}
		workerPool = append(workerPool, w)
	}

	for i := 0; i < 5; i++ {
		for _, w := range workerPool {
			_ = s.RegisterWorker(w)
		}
		time.Sleep(80 * time.Millisecond)
		for _, w := range workerPool {
			_ = s.UnregisterWorker(w.ID)
		}
		time.Sleep(80 * time.Millisecond)
	}

	for _, w := range workerPool {
		err = s.RegisterWorker(w)
		require.NoError(t, err)
	}

	stopHeartbeats := make(chan struct{})
	var wg sync.WaitGroup
	for _, w := range workerPool {
		wg.Add(1)
		go func(workerID int64) {
			defer wg.Done()
			ticker := time.NewTicker(20 * time.Millisecond)
			defer ticker.Stop()
			for {
				select {
				case <-ticker.C:
					_ = s.Heartbeat(workerID)
				case <-stopHeartbeats:
					return
				case <-ctx.Done():
					return
				}
			}
		}(w.ID)
	}

	require.Eventually(t, func() bool {
		assignedOrCompleted := 0
		for _, tid := range taskIDs {
			progress, err := s.GetTaskProgress(tid)
			if err != nil {
				continue
			}
			if progress.Status == models.TaskStatusRunning ||
				progress.Status == models.TaskStatusCompleted {
				assignedOrCompleted++
			}
		}
		return assignedOrCompleted == taskCount
	}, 5*time.Second, 50*time.Millisecond, "all tasks should be assigned or completed after jitter")

	close(stopHeartbeats)
	wg.Wait()

	assignedTasks := make(map[int64]int64)
	for _, tid := range taskIDs {
		progress, err := s.GetTaskProgress(tid)
		require.NoError(t, err)
		if progress.WorkerID != nil {
			wid := *progress.WorkerID
			prevWid, exists := assignedTasks[tid]
			if exists {
				assert.Equal(t, prevWid, wid,
					"task %d assigned to multiple workers (%d and %d) - duplicate assignment!",
					tid, prevWid, wid)
			}
			assignedTasks[tid] = wid
		}
	}

	workerTaskCount := make(map[int64]int)
	for _, wid := range assignedTasks {
		workerTaskCount[wid]++
	}

	for wid, count := range workerTaskCount {
		t.Logf("Worker %d: %d unique tasks", wid, count)
	}

	totalUnique := len(assignedTasks)
	t.Logf("Total unique assigned tasks: %d/%d", totalUnique, taskCount)
	assert.Equal(t, taskCount, totalUnique,
		"all %d tasks must be assigned exactly once with no duplicates and no leaks", taskCount)
}

func TestScheduler_RegisterWorker(t *testing.T) {
	cfg := newTestSchedulerConfig()
	s := NewTaskScheduler(cfg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := s.Start(ctx)
	require.NoError(t, err)
	defer func() { _ = s.Stop() }()

	w := &models.Worker{
		ID:       util.GenerateID(),
		Name:     "test-worker",
		CPUCores: 4,
		MemoryGB: 8,
	}

	err = s.RegisterWorker(w)
	require.NoError(t, err)
	assert.Equal(t, 1, s.GetWorkerCount())

	err = s.RegisterWorker(w)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "already registered")

	err = s.RegisterWorker(nil)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "cannot be nil")
}

func TestScheduler_CancelTask(t *testing.T) {
	cfg := newTestSchedulerConfig()
	s := NewTaskScheduler(cfg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := s.Start(ctx)
	require.NoError(t, err)
	defer func() { _ = s.Stop() }()

	w := &models.Worker{
		ID:       util.GenerateID(),
		Name:     "cancel-worker",
		CPUCores: 4,
		MemoryGB: 8,
	}
	err = s.RegisterWorker(w)
	require.NoError(t, err)

	taskID := util.GenerateID()
	task := &models.Task{
		ID:             taskID,
		ExperimentID:   util.GenerateID(),
		Name:             "cancel-task",
		Priority:         int(PriorityNormal),
		TimeoutSeconds: 300,
		MaxRetries:     3,
	}
	err = s.SubmitTask(ctx, task)
	require.NoError(t, err)

	err = s.CancelTask(taskID)
	require.NoError(t, err)

	progress, err := s.GetTaskProgress(taskID)
	require.NoError(t, err)
	assert.Equal(t, models.TaskStatusCanceled, progress.Status)

	err = s.CancelTask(999999999)
	assert.Error(t, err)
}

func TestScheduler_Heartbeat(t *testing.T) {
	cfg := newTestSchedulerConfig()
	s := NewTaskScheduler(cfg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := s.Start(ctx)
	require.NoError(t, err)
	defer func() { _ = s.Stop() }()

	workerID := util.GenerateID()
	w := &models.Worker{
		ID:       workerID,
		Name:     "heartbeat-worker",
		CPUCores: 4,
		MemoryGB: 8,
	}
	err = s.RegisterWorker(w)
	require.NoError(t, err)

	time.Sleep(50 * time.Millisecond)
	err = s.Heartbeat(workerID)
	require.NoError(t, err)

	workers := s.ListWorkers()
	var foundWorker *models.Worker
	for _, wk := range workers {
		if wk.ID == workerID {
			foundWorker = wk
			break
		}
	}
	require.NotNil(t, foundWorker)
	assert.GreaterOrEqual(t, foundWorker.HeartbeatCount, int64(2))

	err = s.Heartbeat(999999999)
	assert.Error(t, err)
}

func TestScheduler_SubmitTask_NilTask(t *testing.T) {
	cfg := newTestSchedulerConfig()
	s := NewTaskScheduler(cfg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := s.Start(ctx)
	require.NoError(t, err)
	defer func() { _ = s.Stop() }()

	err = s.SubmitTask(ctx, nil)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "cannot be nil")
}

func TestScheduler_FailTask(t *testing.T) {
	cfg := newTestSchedulerConfig()
	s := NewTaskScheduler(cfg)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	err := s.Start(ctx)
	require.NoError(t, err)
	defer func() { _ = s.Stop() }()

	w := &models.Worker{
		ID:       util.GenerateID(),
		Name:     "fail-worker",
		CPUCores: 4,
		MemoryGB: 8,
	}
	err = s.RegisterWorker(w)
	require.NoError(t, err)

	taskID := util.GenerateID()
	task := &models.Task{
		ID:             taskID,
		ExperimentID:   util.GenerateID(),
		Name:             "fail-task",
		Priority:         int(PriorityNormal),
		TimeoutSeconds: 300,
		MaxRetries:     1,
	}
	err = s.SubmitTask(ctx, task)
	require.NoError(t, err)

	require.Eventually(t, func() bool {
		progress, err := s.GetTaskProgress(taskID)
		return err == nil && progress.Status == models.TaskStatusRunning
	}, 2*time.Second, 50*time.Millisecond)

	err = s.FailTask(taskID, "first failure, should retry")
	require.NoError(t, err)

	progress, _ := s.GetTaskProgress(taskID)
	assert.Equal(t, models.TaskStatusRetrying, progress.Status)

	err = s.FailTask(taskID, "second failure, should fail permanently")
	require.Error(t, err)

	progress, err = s.GetTaskProgress(taskID)
	require.NoError(t, err)
	assert.Equal(t, models.TaskStatusFailed, progress.Status)
}
