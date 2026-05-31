package scheduler

import (
	"context"
	"fmt"
	"github.com/solocoder/tasktracker/internal/config"
	"github.com/solocoder/tasktracker/internal/logger"
	"github.com/solocoder/tasktracker/internal/models"
	"sync"
	"time"

	"go.uber.org/zap"
)

const (
	PhasePending    = "pending"
	PhaseRunning    = "running"
	PhaseCompleted  = "completed"
	PhaseFailed     = "failed"
	PhaseRetry      = "retry"
	PhasePaused     = "paused"
)

type TaskHandler func(ctx context.Context, task *models.Task) error

type Scheduler struct {
	mu           sync.RWMutex
	tasks        map[string]*models.Task
	runs         map[string]*models.RunInstance
	handlers     map[string]TaskHandler
	taskQueue    chan *models.Task
	workerCount  int
	maxRetries   int
	running      bool
	cfgManager   *config.Manager
	eventChan    chan *models.Event
	wg           sync.WaitGroup
	ctx          context.Context
	cancel       context.CancelFunc
}

type Config struct {
	WorkerCount int `json:"worker_count"`
	QueueSize   int `json:"queue_size"`
	MaxRetries  int `json:"max_retries"`
}

func NewScheduler(cfg Config, cfgManager *config.Manager) *Scheduler {
	if cfg.WorkerCount <= 0 {
		cfg.WorkerCount = 5
	}
	if cfg.QueueSize <= 0 {
		cfg.QueueSize = 100
	}
	if cfg.MaxRetries <= 0 {
		cfg.MaxRetries = 3
	}

	ctx, cancel := context.WithCancel(context.Background())

	return &Scheduler{
		tasks:       make(map[string]*models.Task),
		runs:        make(map[string]*models.RunInstance),
		handlers:    make(map[string]TaskHandler),
		taskQueue:   make(chan *models.Task, cfg.QueueSize),
		workerCount: cfg.WorkerCount,
		maxRetries:  cfg.MaxRetries,
		cfgManager:  cfgManager,
		eventChan:   make(chan *models.Event, 1000),
		ctx:         ctx,
		cancel:      cancel,
	}
}

func (s *Scheduler) RegisterHandler(taskType string, handler TaskHandler) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.handlers[taskType] = handler
	logger.Info("Handler registered", logger.String("task_type", taskType))
}

func (s *Scheduler) Start() {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return
	}
	s.running = true
	s.mu.Unlock()

	logger.Info("Starting scheduler", logger.Int("worker_count", s.workerCount))

	for i := 0; i < s.workerCount; i++ {
		s.wg.Add(1)
		go s.worker(i)
	}

	go s.eventProcessor()
}

func (s *Scheduler) Stop() {
	s.mu.Lock()
	if !s.running {
		s.mu.Unlock()
		return
	}
	s.running = false
	s.mu.Unlock()

	s.cancel()
	close(s.taskQueue)
	s.wg.Wait()

	s.mu.Lock()
	eventChan := s.eventChan
	s.eventChan = nil
	s.mu.Unlock()

	if eventChan != nil {
		close(eventChan)
	}
	logger.Info("Scheduler stopped")
}

func (s *Scheduler) worker(id int) {
	defer s.wg.Done()
	log := logger.With(logger.Int("worker_id", id))
	log.Info("Worker started")

	for task := range s.taskQueue {
		s.processTask(task, log)
	}
}

func (s *Scheduler) processTask(task *models.Task, log *zap.Logger) {
	runID := fmt.Sprintf("run_%s", task.ID)
	now := time.Now()

	run := &models.RunInstance{
		RunID:     runID,
		EntityID:  task.ID,
		Phase:     PhaseRunning,
		Progress:  0,
		StartedAt: now,
	}

	s.mu.Lock()
	s.runs[runID] = run
	task.Status = PhaseRunning
	task.StartedAt = &now
	s.mu.Unlock()

	log = log.With(logger.String("task_id", task.ID), logger.String("run_id", runID))
	log.Info("Processing task")

	s.emitEvent("task.started", map[string]interface{}{
		"task_id": task.ID,
		"run_id":  runID,
	})

	handler, ok := s.getHandler(task.Type)
	if !ok {
		s.handleTaskError(task, run, fmt.Errorf("no handler registered for type: %s", task.Type), log)
		return
	}

	ctx, cancel := context.WithTimeout(s.ctx, s.getTaskTimeout(task))
	defer cancel()
	ctx = context.WithValue(ctx, "trace_id", runID)

	err := handler(ctx, task)
	if err != nil {
		s.handleTaskError(task, run, err, log)
		return
	}

	s.completeTask(task, run, log)
}

func (s *Scheduler) getHandler(taskType string) (TaskHandler, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	handler, ok := s.handlers[taskType]
	return handler, ok
}

func (s *Scheduler) getTaskTimeout(task *models.Task) time.Duration {
	timeout := 30 * time.Second
	if s.cfgManager != nil {
		if t, err := s.cfgManager.GetDuration("scheduler", "task_timeout"); err == nil {
			timeout = t
		}
	}
	return timeout
}

func (s *Scheduler) handleTaskError(task *models.Task, run *models.RunInstance, err error, log *zap.Logger) {
	now := time.Now()
	errorMsg := err.Error()

	s.mu.Lock()
	task.RetryCount++
	retryCount := task.RetryCount
	task.LastError = &errorMsg
	run.Phase = PhaseFailed
	run.ErrorDetail = &errorMsg
	run.CompletedAt = &now
	run.Progress = 1
	taskID := task.ID
	maxRetries := s.maxRetries
	shouldRetry := task.RetryCount < s.maxRetries && task.Status != PhasePaused
	if !shouldRetry {
		task.Status = PhaseFailed
	}
	s.mu.Unlock()

	log.Error("Task failed", logger.ErrorField(err), logger.Int("retry_count", retryCount))

	s.emitEvent("task.failed", map[string]interface{}{
		"task_id": taskID,
		"run_id":  run.RunID,
		"error":   errorMsg,
	})

	if shouldRetry {
		s.retryTask(task, taskID, retryCount, maxRetries, log)
	} else {
		s.emitEvent("task.failed_permanently", map[string]interface{}{
			"task_id": taskID,
			"error":   errorMsg,
		})
	}
}

func (s *Scheduler) retryTask(task *models.Task, taskID string, retryCount int, maxRetries int, log *zap.Logger) {
	s.mu.Lock()
	if task.Status == PhasePaused {
		s.mu.Unlock()
		log.Info("Task cancelled during retry, skipping", logger.String("task_id", taskID))
		return
	}
	task.Status = PhaseRetry
	s.mu.Unlock()

	delay := time.Duration(retryCount) * time.Second
	log.Info("Retrying task", logger.Int("retry_count", retryCount), logger.String("delay", delay.String()))

	go func(taskID string, retryCount int, delay time.Duration) {
		select {
		case <-time.After(delay):
			s.mu.RLock()
			running := s.running
			s.mu.RUnlock()
			if !running {
				return
			}
			select {
			case s.taskQueue <- task:
				s.emitEvent("task.retried", map[string]interface{}{
					"task_id":     taskID,
					"retry_count": retryCount,
				})
			case <-s.ctx.Done():
			}
		case <-s.ctx.Done():
		}
	}(taskID, retryCount, delay)
}

func (s *Scheduler) completeTask(task *models.Task, run *models.RunInstance, log *zap.Logger) {
	now := time.Now()

	s.mu.Lock()
	task.Status = PhaseCompleted
	task.CompletedAt = &now
	run.Phase = PhaseCompleted
	run.CompletedAt = &now
	run.Progress = 1
	s.mu.Unlock()

	log.Info("Task completed")

	s.emitEvent("task.completed", map[string]interface{}{
		"task_id": task.ID,
		"run_id":  run.RunID,
	})
}

func (s *Scheduler) Submit(task *models.Task) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running {
		return fmt.Errorf("scheduler not running")
	}

	if task.ID == "" {
		task.ID = fmt.Sprintf("task_%d", time.Now().UnixNano())
	}
	if task.MaxRetries <= 0 {
		task.MaxRetries = s.maxRetries
	}
	if task.Status == "" {
		task.Status = PhasePending
	}
	if task.Priority <= 0 {
		task.Priority = 1
	}

	task.CreatedAt = time.Now()
	s.tasks[task.ID] = task

	select {
	case s.taskQueue <- task:
		s.emitEvent("task.submitted", map[string]interface{}{
			"task_id": task.ID,
			"type":    task.Type,
		})
		return nil
	default:
		return fmt.Errorf("task queue is full")
	}
}

func (s *Scheduler) GetTaskStatus(taskID string) (*models.Task, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	task, ok := s.tasks[taskID]
	if !ok {
		return nil, fmt.Errorf("task not found: %s", taskID)
	}
	return task, nil
}

func (s *Scheduler) GetRunStatus(runID string) (*models.RunInstance, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	run, ok := s.runs[runID]
	if !ok {
		return nil, fmt.Errorf("run not found: %s", runID)
	}
	return run, nil
}

func (s *Scheduler) ListTasks(status string) []*models.Task {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]*models.Task, 0)
	for _, task := range s.tasks {
		if status == "" || task.Status == status {
			result = append(result, task)
		}
	}
	return result
}

func (s *Scheduler) CancelTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, ok := s.tasks[taskID]
	if !ok {
		return fmt.Errorf("task not found: %s", taskID)
	}

	if task.Status == PhaseRunning || task.Status == PhasePending {
		task.Status = PhasePaused
		s.emitEvent("task.cancelled", map[string]interface{}{
			"task_id": taskID,
		})
	}
	return nil
}

func (s *Scheduler) emitEvent(eventType string, data map[string]interface{}) {
	event := &models.Event{
		Type:      eventType,
		Data:      data,
		Timestamp: time.Now(),
		TraceID:   fmt.Sprintf("evt_%d", time.Now().UnixNano()),
	}

	s.mu.RLock()
	running := s.running
	eventChan := s.eventChan
	s.mu.RUnlock()

	if !running || eventChan == nil {
		return
	}

	select {
	case eventChan <- event:
	default:
		logger.Warn("Event channel full, dropping event", logger.String("event_type", eventType))
	}
}

func (s *Scheduler) eventProcessor() {
	for event := range s.eventChan {
		logger.Debug("Event emitted",
			logger.String("event_type", event.Type),
			logger.String("trace_id", event.TraceID),
		)
	}
}

func (s *Scheduler) GetStats() map[string]interface{} {
	s.mu.RLock()
	defer s.mu.RUnlock()

	stats := map[string]interface{}{
		"total_tasks":   len(s.tasks),
		"total_runs":    len(s.runs),
		"pending":       0,
		"running":       0,
		"completed":     0,
		"failed":        0,
		"queue_size":    len(s.taskQueue),
		"worker_count":  s.workerCount,
	}

	for _, task := range s.tasks {
		switch task.Status {
		case PhasePending:
			stats["pending"] = stats["pending"].(int) + 1
		case PhaseRunning, PhaseRetry:
			stats["running"] = stats["running"].(int) + 1
		case PhaseCompleted:
			stats["completed"] = stats["completed"].(int) + 1
		case PhaseFailed:
			stats["failed"] = stats["failed"].(int) + 1
		}
	}

	return stats
}
