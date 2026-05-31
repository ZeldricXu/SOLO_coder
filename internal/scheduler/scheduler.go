package scheduler

import (
	"context"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/events"
	"github.com/solocoder/task-scheduler/internal/logging"
	"github.com/solocoder/task-scheduler/internal/models"
)

type TaskStatus string

const (
	TaskStatusQueued     TaskStatus = "queued"
	TaskStatusScheduled  TaskStatus = "scheduled"
	TaskStatusRunning    TaskStatus = "running"
	TaskStatusPaused     TaskStatus = "paused"
	TaskStatusCompleted  TaskStatus = "completed"
	TaskStatusFailed     TaskStatus = "failed"
	TaskStatusCancelled  TaskStatus = "cancelled"
	TaskStatusTimedOut   TaskStatus = "timed_out"
)

type Task struct {
	ID             string                 `json:"id" gorm:"primaryKey;size:64"`
	Name           string                 `json:"name" gorm:"size:256"`
	Type           string                 `json:"type" gorm:"size:64;index"`
	Status         TaskStatus             `json:"status" gorm:"size:32;index"`
	Priority       int                    `json:"priority" gorm:"default:0;index"`
	Payload        map[string]interface{} `json:"payload" gorm:"type:jsonb"`
	Config         map[string]interface{} `json:"config" gorm:"type:jsonb"`
	Schedule       string                 `json:"schedule" gorm:"size:64"`
	ScheduledAt    *time.Time             `json:"scheduled_at" gorm:"index"`
	StartedAt      *time.Time             `json:"started_at"`
	CompletedAt    *time.Time             `json:"completed_at"`
	Timeout        time.Duration          `json:"timeout"`
	MaxRetries     int                    `json:"max_retries"`
	RetryCount     int                    `json:"retry_count" gorm:"default:0"`
	ErrorDetail    *string                `json:"error_detail" gorm:"type:text"`
	Progress       float64                `json:"progress" gorm:"default:0"`
	ParentTaskID   *string                `json:"parent_task_id" gorm:"size:64;index"`
	RunID          string                 `json:"run_id" gorm:"size:64;index"`
	Labels         map[string]string      `json:"labels" gorm:"type:jsonb"`
	Resource       *models.Resource       `json:"resource,omitempty" gorm:"-"`
	CreatedAt      time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt      time.Time              `json:"updated_at"`
}

type TaskResult struct {
	TaskID    string                 `json:"task_id"`
	Success   bool                   `json:"success"`
	Data      map[string]interface{} `json:"data,omitempty"`
	Error     string                 `json:"error,omitempty"`
	StartTime time.Time              `json:"start_time"`
	EndTime   time.Time              `json:"end_time"`
}

type TaskHandler func(ctx context.Context, task *Task) (map[string]interface{}, error)

type Scheduler struct {
	db             *database.Database
	eventBus       events.EventBus
	taskQueue      chan *Task
	stopCh         chan struct{}
	wg             sync.WaitGroup
	workerCount    int
	handlers       map[string]TaskHandler
	handlersMu     sync.RWMutex
	runningTasks   map[string]context.CancelFunc
	runningTasksMu sync.Mutex
	statusStore    map[string]*Task
	statusStoreMu  sync.RWMutex
	maxRetries     int
	defaultTimeout time.Duration
}

func NewScheduler(db *database.Database, eventBus events.EventBus, workerCount int, queueSize int, maxRetries int, defaultTimeout time.Duration) *Scheduler {
	s := &Scheduler{
		db:             db,
		eventBus:       eventBus,
		taskQueue:      make(chan *Task, queueSize),
		stopCh:         make(chan struct{}),
		workerCount:    workerCount,
		handlers:       make(map[string]TaskHandler),
		runningTasks:   make(map[string]context.CancelFunc),
		statusStore:    make(map[string]*Task),
		maxRetries:     maxRetries,
		defaultTimeout: defaultTimeout,
	}

	s.subscribeToEvents()
	return s
}

func (s *Scheduler) subscribeToEvents() {
	s.eventBus.Subscribe(events.EventTaskCancelled, func(ctx context.Context, event events.Event) error {
		if taskID, ok := event.Payload["task_id"].(string); ok {
			_ = s.CancelTask(ctx, taskID)
		}
		return nil
	})
}

func (s *Scheduler) RegisterHandler(taskType string, handler TaskHandler) {
	s.handlersMu.Lock()
	defer s.handlersMu.Unlock()
	s.handlers[taskType] = handler
}

func (s *Scheduler) Start() {
	logging.Info(context.Background(), "Starting scheduler", zap.Int("worker_count", s.workerCount))

	for i := 0; i < s.workerCount; i++ {
		s.wg.Add(1)
		go s.worker(i)
	}

	go s.scheduleLoop()
}

func (s *Scheduler) Stop() {
	logging.Info(context.Background(), "Stopping scheduler", nil)
	close(s.stopCh)

	s.runningTasksMu.Lock()
	for _, cancel := range s.runningTasks {
		cancel()
	}
	s.runningTasksMu.Unlock()

	s.wg.Wait()
	close(s.taskQueue)
	logging.Info(context.Background(), "Scheduler stopped", nil)
}

func (s *Scheduler) scheduleLoop() {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-s.stopCh:
			return
		case <-ticker.C:
			s.scheduleDueTasks()
		}
	}
}

func (s *Scheduler) scheduleDueTasks() {
	var tasks []Task
	now := time.Now()

	err := s.db.DB.Where(`
		status = ? 
		AND (scheduled_at IS NULL OR scheduled_at <= ?)
		AND retry_count < max_retries
	`, TaskStatusQueued, now).
		Order("priority DESC, created_at ASC").
		Limit(100).
		Find(&tasks).Error

	if err != nil {
		logging.Error(context.Background(), "Failed to query due tasks", zap.Error(err))
		return
	}

	for i := range tasks {
		task := &tasks[i]
		select {
		case s.taskQueue <- task:
			_ = s.updateTaskStatus(task.ID, TaskStatusScheduled, nil)
		default:
			logging.Warn(context.Background(), "Task queue is full, deferring task", zap.String("task_id", task.ID))
		}
	}
}

func (s *Scheduler) worker(id int) {
	defer s.wg.Done()
	logger := logging.GetDefaultLogger().With(zap.Int("worker_id", id))

	for task := range s.taskQueue {
		if task == nil {
			continue
		}
		s.executeTask(task, logger)
	}
}

func (s *Scheduler) executeTask(task *Task, logger logging.Logger) {
	ctx := context.Background()
	ctx = context.WithValue(ctx, "traceID", "trace_"+task.ID)

	s.statusStoreMu.Lock()
	s.statusStore[task.ID] = task
	s.statusStoreMu.Unlock()

	if err := s.updateTaskStatus(task.ID, TaskStatusRunning, nil); err != nil {
		logger.Error(ctx, "Failed to update task status to running", zap.Error(err), zap.String("task_id", task.ID))
	}

	taskCtx, cancel := context.WithTimeout(ctx, task.Timeout)
	s.runningTasksMu.Lock()
	s.runningTasks[task.ID] = cancel
	s.runningTasksMu.Unlock()

	defer func() {
		cancel()
		s.runningTasksMu.Lock()
		delete(s.runningTasks, task.ID)
		s.runningTasksMu.Unlock()

		if r := recover(); r != nil {
			logger.Error(ctx, "Task panicked", zap.Any("panic", r), zap.String("task_id", task.ID))
			_ = s.handleTaskFailure(task, "task panicked")
		}
	}()

	_ = s.UpdateProgress(task.ID, 0.1)

	s.handlersMu.RLock()
	handler, exists := s.handlers[task.Type]
	s.handlersMu.RUnlock()

	startTime := time.Now()

	if !exists {
		_ = s.handleTaskFailure(task, "no handler registered for task type: "+task.Type)
		return
	}

	result, err := handler(taskCtx, task)

	_ = s.UpdateProgress(task.ID, 0.9)

	if err != nil {
		logger.Error(ctx, "Task execution failed", zap.Error(err), zap.String("task_id", task.ID))
		_ = s.handleTaskFailure(task, err.Error())
		return
	}

	_ = s.handleTaskSuccess(task, result, startTime)
}

func (s *Scheduler) handleTaskSuccess(task *Task, result map[string]interface{}, startTime time.Time) error {
	now := time.Now()
	taskResult := &TaskResult{
		TaskID:    task.ID,
		Success:   true,
		Data:      result,
		StartTime: startTime,
		EndTime:   now,
	}

	updates := map[string]interface{}{
		"status":       TaskStatusCompleted,
		"progress":     1.0,
		"completed_at": now,
		"updated_at":   now,
	}

	if err := s.db.DB.Model(task).Updates(updates).Error; err != nil {
		return err
	}

	s.statusStoreMu.Lock()
	task.Status = TaskStatusCompleted
	task.Progress = 1.0
	s.statusStore[task.ID] = task
	s.statusStoreMu.Unlock()

	event := events.NewEvent(events.EventTaskCompleted, task.ID, map[string]interface{}{
		"result": taskResult,
		"task":   task,
	}, nil)
	_ = s.eventBus.Publish(context.Background(), event)

	return nil
}

func (s *Scheduler) handleTaskFailure(task *Task, errorMsg string) error {
	now := time.Now()
	task.RetryCount++

	updates := map[string]interface{}{
		"retry_count":  task.RetryCount,
		"error_detail": errorMsg,
		"updated_at":   now,
	}

	if task.RetryCount >= task.MaxRetries {
		updates["status"] = TaskStatusFailed
		updates["completed_at"] = now

		s.statusStoreMu.Lock()
		task.Status = TaskStatusFailed
		s.statusStore[task.ID] = task
		s.statusStoreMu.Unlock()

		event := events.NewEvent(events.EventTaskFailed, task.ID, map[string]interface{}{
			"error": errorMsg,
			"task":  task,
		}, nil)
		_ = s.eventBus.Publish(context.Background(), event)
	} else {
		updates["status"] = TaskStatusQueued
		delay := time.Duration(task.RetryCount) * 10 * time.Second
		nextRun := now.Add(delay)
		updates["scheduled_at"] = &nextRun

		s.statusStoreMu.Lock()
		task.Status = TaskStatusQueued
		s.statusStore[task.ID] = task
		s.statusStoreMu.Unlock()

		logging.Warn(context.Background(), "Task scheduled for retry",
			zap.String("task_id", task.ID),
			zap.Int("retry_count", task.RetryCount),
			zap.Time("next_run", nextRun))
	}

	return s.db.DB.Model(task).Updates(updates).Error
}

func (s *Scheduler) SubmitTask(ctx context.Context, task *Task) (string, error) {
	if task.ID == "" {
		task.ID = "task_" + time.Now().Format("20060102150405") + "_" + randomString(6)
	}
	if task.Status == "" {
		task.Status = TaskStatusQueued
	}
	if task.MaxRetries == 0 {
		task.MaxRetries = s.maxRetries
	}
	if task.Timeout == 0 {
		task.Timeout = s.defaultTimeout
	}
	if task.RunID == "" {
		task.RunID = "run_" + task.ID
	}
	task.CreatedAt = time.Now()
	task.UpdatedAt = time.Now()

	if err := s.db.DB.Create(task).Error; err != nil {
		return "", err
	}

	s.statusStoreMu.Lock()
	s.statusStore[task.ID] = task
	s.statusStoreMu.Unlock()

	event := events.NewEvent(events.EventTaskCreated, task.ID, map[string]interface{}{
		"task": task,
	}, nil)
	_ = s.eventBus.Publish(ctx, event)

	return task.ID, nil
}

func (s *Scheduler) GetTaskStatus(ctx context.Context, taskID string) (*Task, error) {
	s.statusStoreMu.RLock()
	if task, exists := s.statusStore[taskID]; exists {
		s.statusStoreMu.RUnlock()
		return task, nil
	}
	s.statusStoreMu.RUnlock()

	var task Task
	err := s.db.DB.WithContext(ctx).Where("id = ?", taskID).First(&task).Error
	if err != nil {
		return nil, err
	}

	s.statusStoreMu.Lock()
	s.statusStore[taskID] = &task
	s.statusStoreMu.Unlock()

	return &task, nil
}

func (s *Scheduler) ListTasks(ctx context.Context, status TaskStatus, limit, offset int) ([]Task, int64, error) {
	var tasks []Task
	var total int64

	query := s.db.DB.WithContext(ctx).Model(&Task{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	err := query.Order("created_at DESC").
		Limit(limit).
		Offset(offset).
		Find(&tasks).Error

	return tasks, total, err
}

func (s *Scheduler) CancelTask(ctx context.Context, taskID string) error {
	s.runningTasksMu.Lock()
	if cancel, exists := s.runningTasks[taskID]; exists {
		cancel()
	}
	s.runningTasksMu.Unlock()

	updates := map[string]interface{}{
		"status":     TaskStatusCancelled,
		"updated_at": time.Now(),
	}

	err := s.db.DB.Model(&Task{}).
		Where("id = ?", taskID).
		Updates(updates).Error

	if err == nil {
		s.statusStoreMu.Lock()
		if task, exists := s.statusStore[taskID]; exists {
			task.Status = TaskStatusCancelled
		}
		s.statusStoreMu.Unlock()

		event := events.NewEvent(events.EventTaskCancelled, taskID, nil, nil)
		_ = s.eventBus.Publish(ctx, event)
	}

	return err
}

func (s *Scheduler) UpdateProgress(taskID string, progress float64) error {
	s.statusStoreMu.Lock()
	if task, exists := s.statusStore[taskID]; exists {
		task.Progress = progress
	}
	s.statusStoreMu.Unlock()

	return s.db.DB.Model(&Task{}).
		Where("id = ?", taskID).
		Updates(map[string]interface{}{
			"progress":   progress,
			"updated_at": time.Now(),
		}).Error
}

func (s *Scheduler) updateTaskStatus(taskID string, status TaskStatus, errorDetail *string) error {
	updates := map[string]interface{}{
		"status":     status,
		"updated_at": time.Now(),
	}
	if status == TaskStatusRunning {
		updates["started_at"] = time.Now()
	}
	if errorDetail != nil {
		updates["error_detail"] = *errorDetail
	}

	s.statusStoreMu.Lock()
	if task, exists := s.statusStore[taskID]; exists {
		task.Status = status
	}
	s.statusStoreMu.Unlock()

	return s.db.DB.Model(&Task{}).
		Where("id = ?", taskID).
		Updates(updates).Error
}

func (s *Scheduler) PauseTask(ctx context.Context, taskID string) error {
	return s.updateTaskStatus(taskID, TaskStatusPaused, nil)
}

func (s *Scheduler) ResumeTask(ctx context.Context, taskID string) error {
	var task Task
	if err := s.db.DB.Where("id = ?", taskID).First(&task).Error; err != nil {
		return err
	}

	if task.Status == TaskStatusPaused {
		_ = s.updateTaskStatus(taskID, TaskStatusQueued, nil)
		select {
		case s.taskQueue <- &task:
		default:
			return gorm.ErrInvalidTransaction
		}
	}
	return nil
}

func (s *Scheduler) GetRunningTasks() []string {
	s.runningTasksMu.Lock()
	defer s.runningTasksMu.Unlock()

	ids := make([]string, 0, len(s.runningTasks))
	for id := range s.runningTasks {
		ids = append(ids, id)
	}
	return ids
}

func (s *Scheduler) GetStats() map[string]interface{} {
	var statusCounts []struct {
		Status string `json:"status"`
		Count  int64  `json:"count"`
	}

	s.db.DB.Model(&Task{}).
		Select("status, COUNT(*) as count").
		Group("status").
		Scan(&statusCounts)

	stats := make(map[string]interface{})
	for _, sc := range statusCounts {
		stats[sc.Status] = sc.Count
	}

	s.runningTasksMu.Lock()
	stats["running_count"] = len(s.runningTasks)
	s.runningTasksMu.Unlock()

	stats["queue_size"] = len(s.taskQueue)

	return stats
}

type SchedulerStats struct {
	PendingTasks int64
	RunningTasks int64
	CompletedTasks int64
	FailedTasks int64
}

type TaskProcessor func(ctx context.Context, task *Task) error

func NewSchedulerSimple(db *database.Database, eventBus events.EventBus, workerCount int) *Scheduler {
	return NewScheduler(db, eventBus, workerCount, 1000, 3, 5*time.Minute)
}

func (s *Scheduler) Start(ctx context.Context) {
	s.Start()
}

func (s *Scheduler) RegisterProcessor(taskType string, processor TaskProcessor) {
	handler := func(ctx context.Context, task *Task) (map[string]interface{}, error) {
		err := processor(ctx, task)
		if err != nil {
			return nil, err
		}
		return map[string]interface{}{"processed": true}, nil
	}
	s.RegisterHandler(taskType, handler)
}

func (s *Scheduler) Submit(ctx context.Context, task *Task) error {
	_, err := s.SubmitTask(ctx, task)
	return err
}

func (s *Scheduler) GetStatsCompat() SchedulerStats {
	stats := s.GetStats()
	
	pending, _ := stats[string(TaskStatusQueued)].(int64)
	running, _ := stats["running_count"].(int)
	completed, _ := stats[string(TaskStatusCompleted)].(int64)
	failed, _ := stats[string(TaskStatusFailed)].(int64)
	
	return SchedulerStats{
		PendingTasks: pending,
		RunningTasks: int64(running),
		CompletedTasks: completed,
		FailedTasks: failed,
	}
}

func randomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = letters[time.Now().UnixNano()%int64(len(letters))]
	}
	return string(b)
}
