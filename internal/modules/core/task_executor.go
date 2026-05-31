package core

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
	"session189/internal/modules/profiling"
)

type TaskHandler func(ctx context.Context, task *domain.Task) error

type TaskExecutor struct {
	handlers    map[domain.TaskType]TaskHandler
	workerCount int
	taskQueue   chan *domain.Task
	wg          sync.WaitGroup
	stopCh      chan struct{}
	mu          sync.RWMutex
}

func NewTaskExecutor(workerCount int) *TaskExecutor {
	if workerCount <= 0 {
		workerCount = 5
	}

	return &TaskExecutor{
		handlers:    make(map[domain.TaskType]TaskHandler),
		workerCount: workerCount,
		taskQueue:   make(chan *domain.Task, 1000),
		stopCh:      make(chan struct{}),
	}
}

func (e *TaskExecutor) RegisterHandler(taskType domain.TaskType, handler TaskHandler) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.handlers[taskType] = handler
}

func (e *TaskExecutor) Start() {
	for i := 0; i < e.workerCount; i++ {
		e.wg.Add(1)
		go e.worker(i)
	}

	go e.pollPendingTasks()

	logger.Info("Task executor started", zap.Int("workers", e.workerCount))
}

func (e *TaskExecutor) Stop() {
	close(e.stopCh)
	close(e.taskQueue)
	e.wg.Wait()
	logger.Info("Task executor stopped")
}

func (e *TaskExecutor) worker(id int) {
	defer e.wg.Done()

	logger.Debug("Worker started", zap.Int("worker_id", id))

	for task := range e.taskQueue {
		select {
		case <-e.stopCh:
			logger.Debug("Worker stopping", zap.Int("worker_id", id))
			return
		default:
			e.executeTask(task)
		}
	}
}

func (e *TaskExecutor) executeTask(task *domain.Task) {
	ctx := context.Background()

	e.mu.RLock()
	handler, exists := e.handlers[task.Type]
	e.mu.RUnlock()

	if !exists {
		e.failTask(ctx, task, fmt.Errorf("no handler registered for task type: %s", task.Type))
		return
	}

	e.updateTaskStatus(ctx, task, domain.TaskStatusRunning)

	taskCtx, cancel := context.WithCancel(ctx)
	if task.TimeoutSec > 0 {
		taskCtx, cancel = context.WithTimeout(ctx, time.Duration(task.TimeoutSec)*time.Second)
	}
	defer cancel()

	done := make(chan error, 1)
	go func() {
		done <- handler(taskCtx, task)
	}()

	select {
	case err := <-done:
		if err != nil {
			e.failTask(ctx, task, err)
		} else {
			e.completeTask(ctx, task)
		}
	case <-taskCtx.Done():
		if taskCtx.Err() == context.DeadlineExceeded {
			e.timeoutTask(ctx, task)
		} else {
			e.failTask(ctx, task, taskCtx.Err())
		}
	}
}

func (e *TaskExecutor) pollPendingTasks() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-e.stopCh:
			return
		case <-ticker.C:
			e.loadPendingTasks()
		}
	}
}

func (e *TaskExecutor) loadPendingTasks() {
	ctx := context.Background()
	var tasks []domain.Task

	if err := database.DB.WithContext(ctx).
		Where("status IN ?", []domain.TaskStatus{domain.TaskStatusPending, domain.TaskStatusPaused}).
		Where("scheduled_at IS NULL OR scheduled_at <= ?", time.Now()).
		Order("created_at ASC").
		Limit(100).
		Find(&tasks).Error; err != nil {
		logger.Error("Failed to load pending tasks", zap.Error(err))
		return
	}

	for i := range tasks {
		select {
		case e.taskQueue <- &tasks[i]:
		default:
			logger.Warn("Task queue is full, skipping task", zap.String("task_id", tasks[i].TaskID))
		}
	}
}

func (e *TaskExecutor) SubmitTask(ctx context.Context, task *domain.Task) (*domain.Task, error) {
	if task.TaskID == "" {
		task.TaskID = uuid.New().String()
	}
	if task.Status == "" {
		task.Status = domain.TaskStatusPending
	}
	task.CreatedAt = time.Now()
	task.UpdatedAt = time.Now()

	if err := database.DB.WithContext(ctx).Create(task).Error; err != nil {
		return nil, fmt.Errorf("create task failed: %w", err)
	}

	logger.Info("Task submitted",
		zap.String("task_id", task.TaskID),
		zap.String("type", string(task.Type)),
		zap.String("created_by", task.CreatedBy))

	return task, nil
}

func (e *TaskExecutor) ExecuteTask(ctx context.Context, task *domain.Task) error {
	select {
	case e.taskQueue <- task:
		return nil
	default:
		return fmt.Errorf("task queue is full")
	}
}

func (e *TaskExecutor) updateTaskStatus(ctx context.Context, task *domain.Task, status domain.TaskStatus) {
	now := time.Now()
	updates := map[string]interface{}{
		"status":     status,
		"updated_at": now,
	}

	if status == domain.TaskStatusRunning {
		updates["started_at"] = &now
	}
	if status == domain.TaskStatusCompleted || status == domain.TaskStatusFailed || status == domain.TaskStatusTimeout {
		updates["completed_at"] = &now
	}

	if err := database.DB.WithContext(ctx).Model(task).Updates(updates).Error; err != nil {
		logger.Error("Failed to update task status",
			zap.String("task_id", task.TaskID),
			zap.Error(err))
	}

	task.Status = status
	e.addTaskLog(ctx, task.TaskID, "INFO", fmt.Sprintf("Task status changed to %s", status))
}

func (e *TaskExecutor) completeTask(ctx context.Context, task *domain.Task) {
	task.Progress = 100
	e.updateTaskStatus(ctx, task, domain.TaskStatusCompleted)
	logger.Info("Task completed", zap.String("task_id", task.TaskID))
}

func (e *TaskExecutor) failTask(ctx context.Context, task *domain.Task, err error) {
	task.Error = err.Error()
	e.updateTaskStatus(ctx, task, domain.TaskStatusFailed)
	logger.Error("Task failed",
		zap.String("task_id", task.TaskID),
		zap.Error(err))

	if task.RetryCount < task.MaxRetry {
		task.RetryCount++
		go func() {
			time.Sleep(time.Second * time.Duration(5*task.RetryCount))
			task.Status = domain.TaskStatusPending
			task.Error = ""
			_ = database.DB.WithContext(ctx).Model(task).Updates(map[string]interface{}{
				"status":      domain.TaskStatusPending,
				"error":       "",
				"retry_count": task.RetryCount,
				"updated_at":  time.Now(),
			})
			logger.Info("Task retrying",
				zap.String("task_id", task.TaskID),
				zap.Int("attempt", int(task.RetryCount)))
		}()
	}
}

func (e *TaskExecutor) timeoutTask(ctx context.Context, task *domain.Task) {
	task.Error = "task timeout"
	e.updateTaskStatus(ctx, task, domain.TaskStatusTimeout)
	logger.Warn("Task timed out", zap.String("task_id", task.TaskID))
}

func (e *TaskExecutor) addTaskLog(ctx context.Context, taskID, level, message string) {
	log := &domain.TaskLog{
		LogID:     uuid.New().String(),
		TaskID:    taskID,
		Level:     level,
		Message:   message,
		Timestamp: time.Now(),
	}
	_ = database.DB.WithContext(ctx).Create(log).Error
}

func (e *TaskExecutor) GetTask(ctx context.Context, taskID string) (*domain.Task, error) {
	var task domain.Task
	if err := database.DB.WithContext(ctx).Where("task_id = ?", taskID).First(&task).Error; err != nil {
		return nil, fmt.Errorf("get task failed: %w", err)
	}
	return &task, nil
}

func (e *TaskExecutor) ListTasks(ctx context.Context, status domain.TaskStatus, createdBy string, offset, limit int) ([]domain.Task, int64, error) {
	var tasks []domain.Task
	var total int64

	query := database.DB.WithContext(ctx).Model(&domain.Task{})
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if createdBy != "" {
		query = query.Where("created_by = ?", createdBy)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count tasks failed: %w", err)
	}

	if err := query.Order("created_at DESC").Offset(offset).Limit(limit).Find(&tasks).Error; err != nil {
		return nil, 0, fmt.Errorf("list tasks failed: %w", err)
	}

	return tasks, total, nil
}

func (e *TaskExecutor) CancelTask(ctx context.Context, taskID string) error {
	var task domain.Task
	if err := database.DB.WithContext(ctx).Where("task_id = ?", taskID).First(&task).Error; err != nil {
		return fmt.Errorf("task not found: %w", err)
	}

	if task.Status == domain.TaskStatusRunning || task.Status == domain.TaskStatusPending {
		e.updateTaskStatus(ctx, &task, domain.TaskStatusCancelled)
		logger.Info("Task cancelled", zap.String("task_id", taskID))
	}

	return nil
}

func (e *TaskExecutor) GetTaskLogs(ctx context.Context, taskID string, offset, limit int) ([]domain.TaskLog, int64, error) {
	var logs []domain.TaskLog
	var total int64

	query := database.DB.WithContext(ctx).Model(&domain.TaskLog{}).Where("task_id = ?", taskID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count task logs failed: %w", err)
	}

	if err := query.Order("timestamp DESC").Offset(offset).Limit(limit).Find(&logs).Error; err != nil {
		return nil, 0, fmt.Errorf("list task logs failed: %w", err)
	}

	return logs, total, nil
}

func (e *TaskExecutor) UpdateProgress(ctx context.Context, taskID string, progress int32, message string) error {
	updates := map[string]interface{}{
		"progress":   progress,
		"updated_at": time.Now(),
	}

	if err := database.DB.WithContext(ctx).Model(&domain.Task{}).
		Where("task_id = ?", taskID).Updates(updates).Error; err != nil {
		return fmt.Errorf("update progress failed: %w", err)
	}

	if message != "" {
		e.addTaskLog(ctx, taskID, "INFO", message)
	}

	return nil
}

func (e *TaskExecutor) RegisterDefaultHandlers(profiler *profiling.Profiler) {
	e.RegisterHandler(domain.TaskTypeProfiling, func(ctx context.Context, task *domain.Task) error {
		logger.Info("Executing profiling task", zap.String("task_id", task.TaskID))
		duration := 30 * time.Second
		if d, ok := task.Parameters["duration_seconds"].(float64); ok {
			duration = time.Duration(d) * time.Second
		}
		if profiler != nil {
			_, err := profiler.StartCPUProfile(duration)
			return err
		}
		return nil
	})

	e.RegisterHandler(domain.TaskTypeBackup, func(ctx context.Context, task *domain.Task) error {
		logger.Info("Executing backup task", zap.String("task_id", task.TaskID))
		return nil
	})

	e.RegisterHandler(domain.TaskTypeRestore, func(ctx context.Context, task *domain.Task) error {
		logger.Info("Executing restore task", zap.String("task_id", task.TaskID))
		return nil
	})

	e.RegisterHandler(domain.TaskTypeAnalysis, func(ctx context.Context, task *domain.Task) error {
		logger.Info("Executing analysis task", zap.String("task_id", task.TaskID))
		return nil
	})

	e.RegisterHandler(domain.TaskTypeReport, func(ctx context.Context, task *domain.Task) error {
		logger.Info("Executing report task", zap.String("task_id", task.TaskID))
		return nil
	})
}
