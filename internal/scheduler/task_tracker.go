package scheduler

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
)

type TaskTracker struct {
	mu                 sync.RWMutex
	task               *models.Task
	totalSteps         int64
	currentStep        int64
	status             models.TaskStatus
	progress           float64
	startTime          time.Time
	lastUpdateTime     time.Time
	estimatedEndTime   *time.Time
	checkpoints        []TaskCheckpoint
	maxCheckpoints     int
	maxRetries         int
	currentRetry       int
	timeoutDuration    time.Duration
	timeoutTimer       *time.Timer
	onTimeout          func(int64)
	onRetry            func(int64, int)
	onComplete         func(int64, *models.Result)
	onFail             func(int64, string)
	isTimedOut         bool
	isCanceled         bool
	cancelChan         chan struct{}
	completedSteps     []int64
	failedSteps        map[int64]string
	workerID           *int64
	stepStartTime      map[int64]time.Time
	stepDurations      map[int64]time.Duration
	avgStepDuration    time.Duration
}

type TrackerConfig struct {
	MaxRetries      int
	Timeout         time.Duration
	MaxCheckpoints  int
	TotalSteps      int64
	OnTimeout       func(int64)
	OnRetry         func(int64, int)
	OnComplete      func(int64, *models.Result)
	OnFail          func(int64, string)
}

func NewTaskTracker(task *models.Task, config TrackerConfig) *TaskTracker {
	if config.MaxCheckpoints <= 0 {
		config.MaxCheckpoints = 100
	}
	if config.MaxRetries <= 0 {
		config.MaxRetries = task.MaxRetries
	}
	if config.Timeout <= 0 {
		config.Timeout = time.Duration(task.TimeoutSeconds) * time.Second
	}

	tt := &TaskTracker{
		task:            task,
		totalSteps:      config.TotalSteps,
		status:          models.TaskStatusPending,
		startTime:       time.Now(),
		lastUpdateTime:  time.Now(),
		checkpoints:     make([]TaskCheckpoint, 0, config.MaxCheckpoints),
		maxCheckpoints:  config.MaxCheckpoints,
		maxRetries:      config.MaxRetries,
		timeoutDuration: config.Timeout,
		onTimeout:       config.OnTimeout,
		onRetry:         config.OnRetry,
		onComplete:      config.OnComplete,
		onFail:          config.OnFail,
		cancelChan:      make(chan struct{}, 1),
		failedSteps:     make(map[int64]string),
		stepStartTime:   make(map[int64]time.Time),
		stepDurations:   make(map[int64]time.Duration),
	}

	return tt
}

func (tt *TaskTracker) Start(ctx context.Context, workerID int64) error {
	tt.mu.Lock()
	defer tt.mu.Unlock()

	if tt.isCanceled {
		return fmt.Errorf("task %d has been canceled", tt.task.ID)
	}
	if tt.status == models.TaskStatusRunning {
		return fmt.Errorf("task %d is already running", tt.task.ID)
	}

	tt.status = models.TaskStatusRunning
	tt.startTime = time.Now()
	tt.lastUpdateTime = time.Now()
	tt.workerID = &workerID

	if tt.timeoutDuration > 0 {
		tt.timeoutTimer = time.AfterFunc(tt.timeoutDuration, tt.handleTimeout)
	}

	return nil
}

func (tt *TaskTracker) Stop() {
	tt.mu.Lock()
	defer tt.mu.Unlock()

	if tt.timeoutTimer != nil {
		tt.timeoutTimer.Stop()
		tt.timeoutTimer = nil
	}
}

func (tt *TaskTracker) Cancel() {
	tt.mu.Lock()
	defer tt.mu.Unlock()

	if !tt.isCanceled {
		tt.isCanceled = true
		tt.status = models.TaskStatusCanceled
		select {
		case tt.cancelChan <- struct{}{}:
		default:
		}
		close(tt.cancelChan)
	}

	tt.Stop()
}

func (tt *TaskTracker) ReportProgress(step int64, totalSteps int64, data models.Params) error {
	tt.mu.Lock()
	defer tt.mu.Unlock()

	if tt.isCanceled {
		return fmt.Errorf("task %d has been canceled", tt.task.ID)
	}
	if tt.isTimedOut {
		return fmt.Errorf("task %d has timed out", tt.task.ID)
	}

	now := time.Now()
	tt.currentStep = step
	tt.totalSteps = totalSteps
	tt.lastUpdateTime = now

	if _, exists := tt.stepStartTime[step]; !exists {
		tt.stepStartTime[step] = now
	}

	if totalSteps > 0 {
		tt.progress = float64(step) / float64(totalSteps)
	}

	if step > 0 {
		if prevStart, exists := tt.stepStartTime[step-1]; exists {
			tt.stepDurations[step-1] = now.Sub(prevStart)
			tt.updateAvgDuration()
		}
	}

	if tt.avgStepDuration > 0 && totalSteps > step {
		remainingSteps := totalSteps - step
		remainingTime := tt.avgStepDuration * time.Duration(remainingSteps)
		estimated := now.Add(remainingTime)
		tt.estimatedEndTime = &estimated
	}

	tt.completedSteps = append(tt.completedSteps, step)

	if tt.timeoutTimer != nil {
		tt.timeoutTimer.Reset(tt.timeoutDuration)
	}

	return nil
}

func (tt *TaskTracker) Complete(result *models.Result) error {
	tt.mu.Lock()
	defer tt.mu.Unlock()

	if tt.isCanceled {
		return fmt.Errorf("task %d has been canceled", tt.task.ID)
	}

	tt.status = models.TaskStatusCompleted
	tt.progress = 1.0
	tt.currentStep = tt.totalSteps
	tt.lastUpdateTime = time.Now()

	now := time.Now()
	if len(tt.completedSteps) > 0 {
		lastStep := tt.completedSteps[len(tt.completedSteps)-1]
		if prevStart, exists := tt.stepStartTime[lastStep]; exists {
			tt.stepDurations[lastStep] = now.Sub(prevStart)
			tt.updateAvgDuration()
		}
	}

	tt.Stop()

	if tt.onComplete != nil {
		tt.onComplete(tt.task.ID, result)
	}

	return nil
}

func (tt *TaskTracker) Fail(errMsg string) error {
	tt.mu.Lock()
	defer tt.mu.Unlock()

	if tt.isCanceled {
		return fmt.Errorf("task %d has been canceled", tt.task.ID)
	}

	tt.currentRetry++

	if tt.currentStep > 0 {
		tt.failedSteps[tt.currentStep] = errMsg
	}

	if tt.currentRetry <= tt.maxRetries {
		tt.status = models.TaskStatusRetrying

		if tt.onRetry != nil {
			tt.onRetry(tt.task.ID, tt.currentRetry)
		}

		if tt.timeoutTimer != nil {
			tt.timeoutTimer.Reset(tt.timeoutDuration)
		}

		return nil
	}

	tt.status = models.TaskStatusFailed
	tt.Stop()

	if tt.onFail != nil {
		tt.onFail(tt.task.ID, errMsg)
	}

	return fmt.Errorf("task %d failed after %d retries: %s", tt.task.ID, tt.currentRetry, errMsg)
}

func (tt *TaskTracker) SaveCheckpoint(step int64, data models.Params, checksum string, filePath string) (*TaskCheckpoint, error) {
	tt.mu.Lock()
	defer tt.mu.Unlock()

	if tt.isCanceled {
		return nil, fmt.Errorf("task %d has been canceled", tt.task.ID)
	}

	cp := TaskCheckpoint{
		TaskID:    tt.task.ID,
		Step:      step,
		Data:      data,
		Checksum:  checksum,
		FilePath:  filePath,
		CreatedAt: time.Now(),
	}

	tt.checkpoints = append(tt.checkpoints, cp)

	if len(tt.checkpoints) > tt.maxCheckpoints {
		tt.checkpoints = tt.checkpoints[1:]
	}

	return &cp, nil
}

func (tt *TaskTracker) RestoreCheckpoint() (*TaskCheckpoint, error) {
	tt.mu.RLock()
	defer tt.mu.RUnlock()

	if len(tt.checkpoints) == 0 {
		return nil, fmt.Errorf("no checkpoints available for task %d", tt.task.ID)
	}

	latest := tt.checkpoints[len(tt.checkpoints)-1]
	return &latest, nil
}

func (tt *TaskTracker) RestoreFromCheckpoint(step int64) (*TaskCheckpoint, error) {
	tt.mu.RLock()
	defer tt.mu.RUnlock()

	for i := len(tt.checkpoints) - 1; i >= 0; i-- {
		if tt.checkpoints[i].Step <= step {
			return &tt.checkpoints[i], nil
		}
	}

	if len(tt.checkpoints) > 0 {
		return &tt.checkpoints[0], nil
	}

	return nil, fmt.Errorf("no checkpoint found for task %d at or before step %d", tt.task.ID, step)
}

func (tt *TaskTracker) GetProgress() TaskProgress {
	tt.mu.RLock()
	defer tt.mu.RUnlock()

	return TaskProgress{
		TaskID:          tt.task.ID,
		Status:          tt.status,
		Progress:        tt.progress,
		CurrentStep:     tt.currentStep,
		TotalSteps:      tt.totalSteps,
		StartTime:       tt.startTime,
		EstimatedEnd:    tt.estimatedEndTime,
		WorkerID:        tt.workerID,
		LastUpdate:      tt.lastUpdateTime,
		CheckpointCount: len(tt.checkpoints),
		RetryCount:      tt.currentRetry,
	}
}

func (tt *TaskTracker) GetStatus() models.TaskStatus {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	return tt.status
}

func (tt *TaskTracker) IsRunning() bool {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	return tt.status == models.TaskStatusRunning
}

func (tt *TaskTracker) IsCompleted() bool {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	return tt.status == models.TaskStatusCompleted
}

func (tt *TaskTracker) IsFailed() bool {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	return tt.status == models.TaskStatusFailed
}

func (tt *TaskTracker) IsTimedOut() bool {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	return tt.isTimedOut
}

func (tt *TaskTracker) IsCanceled() bool {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	return tt.isCanceled
}

func (tt *TaskTracker) GetElapsedTime() time.Duration {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	return time.Since(tt.startTime)
}

func (tt *TaskTracker) GetRemainingTime() (time.Duration, error) {
	tt.mu.RLock()
	defer tt.mu.RUnlock()

	if tt.estimatedEndTime == nil {
		return 0, fmt.Errorf("no estimated end time available")
	}

	remaining := time.Until(*tt.estimatedEndTime)
	if remaining < 0 {
		return 0, nil
	}
	return remaining, nil
}

func (tt *TaskTracker) GetAvgStepDuration() time.Duration {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	return tt.avgStepDuration
}

func (tt *TaskTracker) GetStepDuration(step int64) (time.Duration, bool) {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	d, ok := tt.stepDurations[step]
	return d, ok
}

func (tt *TaskTracker) GetCheckpoints() []TaskCheckpoint {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	result := make([]TaskCheckpoint, len(tt.checkpoints))
	copy(result, tt.checkpoints)
	return result
}

func (tt *TaskTracker) GetRetryCount() int {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	return tt.currentRetry
}

func (tt *TaskTracker) GetMaxRetries() int {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	return tt.maxRetries
}

func (tt *TaskTracker) ResetTimeout() {
	tt.mu.Lock()
	defer tt.mu.Unlock()

	if tt.timeoutTimer != nil {
		tt.timeoutTimer.Reset(tt.timeoutDuration)
	}
}

func (tt *TaskTracker) SetTimeout(timeout time.Duration) {
	tt.mu.Lock()
	defer tt.mu.Unlock()

	tt.timeoutDuration = timeout
	if tt.timeoutTimer != nil {
		tt.timeoutTimer.Reset(timeout)
	}
}

func (tt *TaskTracker) WaitForCompletion(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-tt.cancelChan:
			return fmt.Errorf("task %d canceled", tt.task.ID)
		default:
			tt.mu.RLock()
			status := tt.status
			tt.mu.RUnlock()

			if status == models.TaskStatusCompleted {
				return nil
			}
			if status == models.TaskStatusFailed {
				return fmt.Errorf("task %d failed", tt.task.ID)
			}
			if status == models.TaskStatusTimeout {
				return fmt.Errorf("task %d timed out", tt.task.ID)
			}

			time.Sleep(100 * time.Millisecond)
		}
	}
}

func (tt *TaskTracker) RetryWithBackoff(ctx context.Context, fn func() error) error {
	return util.Do(ctx, fn,
		util.WithMaxRetries(tt.maxRetries-tt.currentRetry),
		util.WithBaseDelay(100*time.Millisecond),
		util.WithMaxDelay(30*time.Second),
	)
}

func (tt *TaskTracker) handleTimeout() {
	tt.mu.Lock()
	defer tt.mu.Unlock()

	if tt.isCanceled || tt.isTimedOut || tt.status == models.TaskStatusCompleted {
		return
	}

	tt.isTimedOut = true
	tt.status = models.TaskStatusTimeout

	if tt.onTimeout != nil {
		tt.onTimeout(tt.task.ID)
	}
}

func (tt *TaskTracker) updateAvgDuration() {
	if len(tt.stepDurations) == 0 {
		return
	}

	var total time.Duration
	for _, d := range tt.stepDurations {
		total += d
	}
	tt.avgStepDuration = total / time.Duration(len(tt.stepDurations))
}

func (tt *TaskTracker) GetFailedSteps() map[int64]string {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	result := make(map[int64]string, len(tt.failedSteps))
	for k, v := range tt.failedSteps {
		result[k] = v
	}
	return result
}

func (tt *TaskTracker) GetCompletedSteps() []int64 {
	tt.mu.RLock()
	defer tt.mu.RUnlock()
	result := make([]int64, len(tt.completedSteps))
	copy(result, tt.completedSteps)
	return result
}
