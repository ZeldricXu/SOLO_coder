package prompt

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
)

type TaskStatus string

const (
	TaskStatusPending   TaskStatus = "pending"
	TaskStatusRunning   TaskStatus = "running"
	TaskStatusCompleted TaskStatus = "completed"
	TaskStatusFailed    TaskStatus = "failed"
	TaskStatusCancelled TaskStatus = "cancelled"
)

type TaskType string

const (
	TaskTypeABTestAnalysis   TaskType = "ab_test_analysis"
	TaskTypePromptEvaluation TaskType = "prompt_evaluation"
	TaskTypeVersionCleanup   TaskType = "version_cleanup"
	TaskTypeMetricAggregation TaskType = "metric_aggregation"
	TaskTypeReportGeneration TaskType = "report_generation"
)

type AsyncTask struct {
	ID            string                 `json:"id"`
	Type          TaskType               `json:"type"`
	Status        TaskStatus             `json:"status"`
	Payload       map[string]interface{} `json:"payload"`
	Result        map[string]interface{} `json:"result,omitempty"`
	Error         string                 `json:"error,omitempty"`
	CreatedAt     time.Time              `json:"created_at"`
	StartedAt     *time.Time             `json:"started_at,omitempty"`
	CompletedAt   *time.Time             `json:"completed_at,omitempty"`
	RetryCount    int                    `json:"retry_count"`
	MaxRetries    int                    `json:"max_retries"`
	CallbackURL   string                 `json:"callback_url,omitempty"`
	Timeout       time.Duration          `json:"timeout"`
}

type TaskEventHandler interface {
	OnTaskCreated(task *AsyncTask)
	OnTaskStarted(task *AsyncTask)
	OnTaskCompleted(task *AsyncTask)
	OnTaskFailed(task *AsyncTask)
}

type AsyncTaskManager struct {
	tasks        map[string]*AsyncTask
	taskQueue    chan *AsyncTask
	workerCount  int
	handlers     []TaskEventHandler
	logger       *zap.Logger
	mu           sync.RWMutex
	wg           sync.WaitGroup
	quit         chan struct{}
	processor    func(ctx context.Context, task *AsyncTask) error
}

func NewAsyncTaskManager(workerCount int, logger *zap.Logger) *AsyncTaskManager {
	return &AsyncTaskManager{
		tasks:       make(map[string]*AsyncTask),
		taskQueue:   make(chan *AsyncTask, 1000),
		workerCount: workerCount,
		logger:      logger,
		quit:        make(chan struct{}),
	}
}

func (m *AsyncTaskManager) SetProcessor(processor func(ctx context.Context, task *AsyncTask) error) {
	m.processor = processor
}

func (m *AsyncTaskManager) RegisterHandler(handler TaskEventHandler) {
	m.handlers = append(m.handlers, handler)
}

func (m *AsyncTaskManager) SubmitTask(taskType TaskType, payload map[string]interface{}, maxRetries int, callbackURL string) (*AsyncTask, error) {
	if m.processor == nil {
		return nil, fmt.Errorf("task processor not set")
	}

	task := &AsyncTask{
		ID:          uuid.New().String(),
		Type:        taskType,
		Status:      TaskStatusPending,
		Payload:     payload,
		CreatedAt:   time.Now(),
		MaxRetries:  maxRetries,
		CallbackURL: callbackURL,
		Timeout:     5 * time.Minute,
	}

	m.mu.Lock()
	m.tasks[task.ID] = task
	m.mu.Unlock()

	for _, h := range m.handlers {
		go h.OnTaskCreated(task)
	}

	m.logger.Info("Task submitted",
		zap.String("task_id", task.ID),
		zap.String("type", string(task.Type)),
	)

	select {
	case m.taskQueue <- task:
	default:
		return nil, fmt.Errorf("task queue is full")
	}

	return task, nil
}

func (m *AsyncTaskManager) Start(ctx context.Context) {
	for i := 0; i < m.workerCount; i++ {
		m.wg.Add(1)
		go m.worker(ctx, i)
	}
	m.logger.Info("Async task manager started", zap.Int("workers", m.workerCount))
}

func (m *AsyncTaskManager) Stop() {
	close(m.quit)
	m.wg.Wait()
	m.logger.Info("Async task manager stopped")
}

func (m *AsyncTaskManager) worker(ctx context.Context, workerID int) {
	defer m.wg.Done()

	m.logger.Debug("Worker started", zap.Int("worker_id", workerID))

	for {
		select {
		case <-ctx.Done():
			m.logger.Debug("Worker stopped due to context cancel", zap.Int("worker_id", workerID))
			return
		case <-m.quit:
			m.logger.Debug("Worker stopped", zap.Int("worker_id", workerID))
			return
		case task := <-m.taskQueue:
			m.processTask(ctx, task, workerID)
		}
	}
}

func (m *AsyncTaskManager) processTask(ctx context.Context, task *AsyncTask, workerID int) {
	m.mu.Lock()
	task.Status = TaskStatusRunning
	now := time.Now()
	task.StartedAt = &now
	m.mu.Unlock()

	for _, h := range m.handlers {
		go h.OnTaskStarted(task)
	}

	m.logger.Debug("Processing task",
		zap.String("task_id", task.ID),
		zap.Int("worker_id", workerID),
	)

	taskCtx, cancel := context.WithTimeout(ctx, task.Timeout)
	defer cancel()

	err := m.processor(taskCtx, task)

	m.mu.Lock()
	now = time.Now()
	task.CompletedAt = &now

	if err != nil {
		task.Error = err.Error()
		if task.RetryCount < task.MaxRetries {
			task.RetryCount++
			task.Status = TaskStatusPending
			m.logger.Warn("Task failed, retrying",
				zap.String("task_id", task.ID),
				zap.Int("retry", task.RetryCount),
				zap.Error(err),
			)
			m.mu.Unlock()
			m.taskQueue <- task
			return
		}
		task.Status = TaskStatusFailed
		m.logger.Error("Task failed permanently",
			zap.String("task_id", task.ID),
			zap.Error(err),
		)
	} else {
		task.Status = TaskStatusCompleted
		m.logger.Info("Task completed",
			zap.String("task_id", task.ID),
			zap.String("type", string(task.Type)),
		)
	}

	m.mu.Unlock()

	if err == nil {
		for _, h := range m.handlers {
			go h.OnTaskCompleted(task)
		}
	} else {
		for _, h := range m.handlers {
			go h.OnTaskFailed(task)
		}
	}

	if task.CallbackURL != "" {
		go m.sendCallback(task)
	}
}

func (m *AsyncTaskManager) sendCallback(task *AsyncTask) {
	payload, _ := json.Marshal(task)
	m.logger.Info("Sending callback",
		zap.String("task_id", task.ID),
		zap.String("callback_url", task.CallbackURL),
		zap.String("payload", string(payload)),
	)
}

func (m *AsyncTaskManager) GetTask(taskID string) (*AsyncTask, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	task, exists := m.tasks[taskID]
	if !exists {
		return nil, fmt.Errorf("task %s not found", taskID)
	}
	return task, nil
}

func (m *AsyncTaskManager) ListTasks(status TaskStatus, taskType TaskType, limit, offset int) []*AsyncTask {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var result []*AsyncTask
	for _, task := range m.tasks {
		if status != "" && task.Status != status {
			continue
		}
		if taskType != "" && task.Type != taskType {
			continue
		}
		result = append(result, task)
	}

	if offset > 0 && offset < len(result) {
		result = result[offset:]
	}
	if limit > 0 && limit < len(result) {
		result = result[:limit]
	}

	return result
}

func (m *AsyncTaskManager) CancelTask(taskID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	task, exists := m.tasks[taskID]
	if !exists {
		return fmt.Errorf("task %s not found", taskID)
	}

	if task.Status == TaskStatusPending {
		task.Status = TaskStatusCancelled
		m.logger.Info("Task cancelled", zap.String("task_id", taskID))
		return nil
	}

	return fmt.Errorf("cannot cancel task in status %s", task.Status)
}

func (m *AsyncTaskManager) CleanupOldTasks(maxAge time.Duration) int {
	m.mu.Lock()
	defer m.mu.Unlock()

	deleted := 0
	cutoff := time.Now().Add(-maxAge)
	for id, task := range m.tasks {
		if task.CompletedAt != nil && task.CompletedAt.Before(cutoff) {
			delete(m.tasks, id)
			deleted++
		}
	}

	if deleted > 0 {
		m.logger.Info("Cleaned up old tasks", zap.Int("deleted", deleted))
	}
	return deleted
}
