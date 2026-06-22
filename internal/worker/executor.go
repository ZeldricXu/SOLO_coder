package worker

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/df1-96/experiment/internal/compute"
	"github.com/df1-96/experiment/pkg/util"
	"go.uber.org/zap"
)

type TaskExecutor struct {
	config          ExecutorConfig
	workerID        string
	mu              sync.RWMutex
	running         bool
	ctx             context.Context
	cancel          context.CancelFunc
	wg              sync.WaitGroup
	taskQueue       chan *Task
	runningTasks    map[string]*runningTask
	completedTasks  int64
	failedTasks     int64
	cache           *LocalCache
	progressCb      func(progress TaskProgress)
	resultCb        func(result TaskResult)
	fetchTaskFunc   func(ctx context.Context, workerID string) (*Task, error)
	submitResultFunc func(ctx context.Context, result TaskResult) error
}

func NewTaskExecutor(
	config ExecutorConfig,
	workerID string,
	cache *LocalCache,
	progressCb func(progress TaskProgress),
	resultCb func(result TaskResult),
	fetchTaskFunc func(ctx context.Context, workerID string) (*Task, error),
	submitResultFunc func(ctx context.Context, result TaskResult) error,
) *TaskExecutor {
	if config.MaxParallelTasks <= 0 {
		config.MaxParallelTasks = 1
	}
	if config.ProgressInterval <= 0 {
		config.ProgressInterval = 5 * time.Second
	}
	if config.TaskTimeout <= 0 {
		config.TaskTimeout = 30 * time.Minute
	}

	return &TaskExecutor{
		config:           config,
		workerID:         workerID,
		cache:            cache,
		progressCb:       progressCb,
		resultCb:         resultCb,
		fetchTaskFunc:    fetchTaskFunc,
		submitResultFunc: submitResultFunc,
		taskQueue:        make(chan *Task, config.MaxParallelTasks*2),
		runningTasks:     make(map[string]*runningTask),
	}
}

func (te *TaskExecutor) Start(ctx context.Context) error {
	te.mu.Lock()
	defer te.mu.Unlock()

	if te.running {
		return nil
	}

	te.ctx, te.cancel = context.WithCancel(ctx)
	te.running = true

	for i := int32(0); i < te.config.MaxParallelTasks; i++ {
		te.wg.Add(1)
		go te.workerLoop(i)
	}

	te.wg.Add(1)
	go te.taskFetcher()

	util.Info("task executor started",
		zap.String("worker_id", te.workerID),
		zap.Int32("max_parallel_tasks", te.config.MaxParallelTasks))

	return nil
}

func (te *TaskExecutor) Stop() error {
	te.mu.Lock()
	if !te.running {
		te.mu.Unlock()
		return nil
	}

	te.running = false
	te.cancel()
	te.mu.Unlock()

	te.wg.Wait()

	te.mu.Lock()
	close(te.taskQueue)
	completed := te.completedTasks
	failed := te.failedTasks
	te.mu.Unlock()

	util.Info("task executor stopped",
		zap.String("worker_id", te.workerID),
		zap.Int64("completed_tasks", completed),
		zap.Int64("failed_tasks", failed))

	return nil
}

func (te *TaskExecutor) taskFetcher() {
	defer te.wg.Done()

	for {
		select {
		case <-te.ctx.Done():
			return
		default:
		}

		te.mu.RLock()
		queueLen := len(te.taskQueue)
		runningCount := len(te.runningTasks)
		te.mu.RUnlock()

		if int32(queueLen+runningCount) >= te.config.MaxParallelTasks {
			time.Sleep(100 * time.Millisecond)
			continue
		}

		task, err := te.fetchTaskFunc(te.ctx, te.workerID)
		if err != nil {
			util.Warn("failed to fetch task",
				zap.String("worker_id", te.workerID),
				zap.Error(err))
			time.Sleep(5 * time.Second)
			continue
		}

		if task == nil {
			time.Sleep(1 * time.Second)
			continue
		}

		task.Status = TaskStatusQueued
		te.taskQueue <- task

		util.Info("task fetched and queued",
			zap.String("worker_id", te.workerID),
			zap.String("task_id", task.TaskID),
			zap.Int("queue_len", len(te.taskQueue)))
	}
}

func (te *TaskExecutor) workerLoop(workerID int32) {
	defer te.wg.Done()

	executorID := fmt.Sprintf("exec-%d", workerID)

	for {
		select {
		case <-te.ctx.Done():
			return
		case task, ok := <-te.taskQueue:
			if !ok {
				return
			}
			te.executeTask(task, executorID)
		}
	}
}

func (te *TaskExecutor) executeTask(task *Task, executorID string) {
	taskCtx, cancel := context.WithCancel(te.ctx)
	if task.Timeout > 0 {
		taskCtx, cancel = context.WithTimeout(te.ctx, task.Timeout)
	}
	defer cancel()

	execCtx := &TaskExecutionContext{
		ctx:        taskCtx,
		cancel:     cancel,
		task:       task,
		progress:   make(chan TaskProgress, 100),
		result:     make(chan TaskResult, 1),
		startedAt:  time.Now(),
		executorID: executorID,
	}

	te.mu.Lock()
	te.runningTasks[task.TaskID] = &runningTask{ctx: execCtx}
	te.mu.Unlock()

	defer func() {
		te.mu.Lock()
		delete(te.runningTasks, task.TaskID)
		te.mu.Unlock()
	}()

	task.Status = TaskStatusRunning

	util.Info("task execution started",
		zap.String("worker_id", te.workerID),
		zap.String("task_id", task.TaskID),
		zap.String("executor_id", executorID))

	go te.reportProgress(taskCtx, execCtx)

	var result TaskResult
	if te.cache != nil {
		cacheKey := te.cache.GenerateCacheKey(task.TaskID, task.ParameterCombinations)
		if cached, ok := te.cache.Get(cacheKey); ok {
			if cachedResult, ok := cached.(TaskResult); ok {
				cachedResult.CacheHit = true
				cachedResult.TaskID = task.TaskID
				result = cachedResult

				util.Info("task result found in cache",
					zap.String("worker_id", te.workerID),
					zap.String("task_id", task.TaskID))
			}
		}
	}

	if result.Status == TaskStatusUnspecified {
		result = te.runOptimization(taskCtx, task, execCtx.progress)
	}

	result.CompletedAt = time.Now()
	result.DurationMs = time.Since(execCtx.startedAt).Milliseconds()

	select {
	case execCtx.result <- result:
	default:
	}

	te.mu.Lock()
	if result.Status == TaskStatusCompleted {
		te.completedTasks++
	} else {
		te.failedTasks++
	}
	te.mu.Unlock()

	if te.cache != nil && result.Status == TaskStatusCompleted {
		cacheKey := te.cache.GenerateCacheKey(task.TaskID, task.ParameterCombinations)
		if err := te.cache.Set(cacheKey, result); err != nil {
			util.Warn("failed to cache task result",
				zap.String("task_id", task.TaskID),
				zap.Error(err))
		}
	}

	if te.submitResultFunc != nil {
		if err := te.submitResultFunc(taskCtx, result); err != nil {
			util.Warn("failed to submit task result",
				zap.String("task_id", task.TaskID),
				zap.Error(err))
		}
	}

	if te.resultCb != nil {
		te.resultCb(result)
	}

	util.Info("task execution finished",
		zap.String("worker_id", te.workerID),
		zap.String("task_id", task.TaskID),
		zap.String("status", result.Status.String()),
		zap.Int64("duration_ms", result.DurationMs),
		zap.Bool("cache_hit", result.CacheHit))
}

func (te *TaskExecutor) runOptimization(ctx context.Context, task *Task, progressChan chan<- TaskProgress) TaskResult {
	result := TaskResult{
		TaskID: task.TaskID,
		Status: TaskStatusRunning,
	}

	if task.Objective == nil {
		result.Status = TaskStatusFailed
		result.ErrorMessage = "objective function is nil"
		return result
	}

	if task.Gradient == nil {
		result.Status = TaskStatusFailed
		result.ErrorMessage = "gradient function is nil"
		return result
	}

	dim := len(task.InitialPoint)
	if dim == 0 {
		result.Status = TaskStatusFailed
		result.ErrorMessage = "initial point is empty"
		return result
	}

	engine := compute.NewEngine(dim, task.Objective, task.Gradient)
	config := task.OptimizerConfig

	progressTicker := time.NewTicker(te.config.ProgressInterval)
	defer progressTicker.Stop()

	var (
		currentX    = make([]float64, dim)
		currentF    float64
		iterCount   int64
		err         error
	)
	copy(currentX, task.InitialPoint)

	optimizeCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	done := make(chan struct{})
	go func() {
		defer close(done)
		currentX, currentF, err = engine.Optimize(task.InitialPoint, config)
	}()

	for {
		select {
		case <-optimizeCtx.Done():
			result.Status = TaskStatusCancelled
			result.ErrorMessage = "task cancelled"
			result.CurrentX = currentX
			result.CurrentF = currentF
			result.Iterations = iterCount
			return result
		case <-done:
			if err != nil {
				result.Status = TaskStatusFailed
				result.ErrorMessage = err.Error()
				result.Iterations = iterCount
				return result
			}

			result.Status = TaskStatusCompleted
			result.OptimalPoint = currentX
			result.OptimalValue = currentF
			result.Iterations = iterCount
			return result
		case <-progressTicker.C:
			iterCount++
			progress := float64(iterCount) / float64(config.MaxIter)
			if progress > 1.0 {
				progress = 1.0
			}

			tp := TaskProgress{
				TaskID:      task.TaskID,
				Status:      TaskStatusRunning,
				CurrentIter: iterCount,
				CurrentX:    make([]float64, dim),
				CurrentF:    task.Objective(currentX),
				Progress:    progress,
				Timestamp:   time.Now(),
			}
			copy(tp.CurrentX, currentX)

			select {
			case progressChan <- tp:
			default:
			}

			if te.progressCb != nil {
				te.progressCb(tp)
			}
		}
	}
}

func (te *TaskExecutor) reportProgress(ctx context.Context, execCtx *TaskExecutionContext) {
	ticker := time.NewTicker(te.config.ProgressInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case progress, ok := <-execCtx.progress:
			if !ok {
				return
			}
			if te.progressCb != nil {
				te.progressCb(progress)
			}
		case <-ticker.C:
		}
	}
}

func (te *TaskExecutor) CancelTask(taskID string) error {
	te.mu.RLock()
	rt, ok := te.runningTasks[taskID]
	te.mu.RUnlock()

	if !ok {
		return fmt.Errorf("task %s not found", taskID)
	}

	rt.mu.Lock()
	defer rt.mu.Unlock()

	if rt.ctx != nil && rt.ctx.cancel != nil {
		rt.ctx.cancel()
	}

	util.Info("task cancelled",
		zap.String("worker_id", te.workerID),
		zap.String("task_id", taskID))

	return nil
}

func (te *TaskExecutor) GetActiveTaskCount() int {
	te.mu.RLock()
	defer te.mu.RUnlock()
	return len(te.runningTasks)
}

func (te *TaskExecutor) GetPendingTaskCount() int {
	te.mu.RLock()
	defer te.mu.RUnlock()
	return len(te.taskQueue)
}

func (te *TaskExecutor) GetCompletedCount() int64 {
	te.mu.RLock()
	defer te.mu.RUnlock()
	return te.completedTasks
}

func (te *TaskExecutor) GetFailedCount() int64 {
	te.mu.RLock()
	defer te.mu.RUnlock()
	return te.failedTasks
}

func (te *TaskExecutor) GetRunningTasks() []string {
	te.mu.RLock()
	defer te.mu.RUnlock()

	taskIDs := make([]string, 0, len(te.runningTasks))
	for id := range te.runningTasks {
		taskIDs = append(taskIDs, id)
	}
	return taskIDs
}

func (te *TaskExecutor) SubmitTask(task *Task) error {
	te.mu.RLock()
	running := te.running
	te.mu.RUnlock()

	if !running {
		return fmt.Errorf("executor is not running")
	}

	select {
	case te.taskQueue <- task:
		task.Status = TaskStatusQueued
		return nil
	default:
		return fmt.Errorf("task queue is full")
	}
}

func (te *TaskExecutor) IsRunning() bool {
	te.mu.RLock()
	defer te.mu.RUnlock()
	return te.running
}
