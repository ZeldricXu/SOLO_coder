package core

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"session154/internal/logger"
	"session154/pkg/models"
	"sync"
	"time"

	"go.uber.org/zap"
)

type TaskStatus string

const (
	TaskStatusPending    TaskStatus = "pending"
	TaskStatusRunning    TaskStatus = "running"
	TaskStatusCompleted  TaskStatus = "completed"
	TaskStatusFailed     TaskStatus = "failed"
	TaskStatusCancelled  TaskStatus = "cancelled"
	TaskStatusProvisioning TaskStatus = "provisioning"
)

type TaskPhase string

const (
	PhaseInitializing TaskPhase = "initializing"
	PhaseProcessing   TaskPhase = "processing"
	PhaseFinalizing   TaskPhase = "finalizing"
	PhaseCompleted    TaskPhase = "completed"
)

type Task struct {
	ID         string
	Type       string
	Status     TaskStatus
	Phase      TaskPhase
	Progress   float64
	Payload    map[string]interface{}
	Config     *models.Config
	Entity     *models.Entity
	RunInstance *models.RunInstance
	Context    context.Context
	CancelFunc context.CancelFunc
	StartedAt  time.Time
	Error      error
	Result     interface{}
	Timeout    time.Duration
	Retries    int
	MaxRetries int
	Labels     map[string]string
	mu         sync.Mutex
}

type TaskHandler func(ctx context.Context, task *Task) (interface{}, error)

type TaskOption func(*Task)

func WithTimeout(timeout time.Duration) TaskOption {
	return func(t *Task) { t.Timeout = timeout }
}

func WithRetries(maxRetries int) TaskOption {
	return func(t *Task) { t.MaxRetries = maxRetries }
}

func WithLabels(labels map[string]string) TaskOption {
	return func(t *Task) { t.Labels = labels }
}

func NewTask(taskType string, payload map[string]interface{}, opts ...TaskOption) *Task {
	t := &Task{
		ID:         generateID("rsc_"),
		Type:       taskType,
		Status:     TaskStatusPending,
		Phase:      PhaseInitializing,
		Progress:   0,
		Payload:    payload,
		Timeout:    30 * time.Second,
		MaxRetries: 3,
	}
	for _, opt := range opts {
		opt(t)
	}
	return t
}

func generateID(prefix string) string {
	b := make([]byte, 4)
	rand.Read(b)
	return prefix + hex.EncodeToString(b)
}

func (t *Task) SetStatus(status TaskStatus) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.Status = status
}

func (t *Task) SetProgress(progress float64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.Progress = progress
}

func (t *Task) SetPhase(phase TaskPhase) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.Phase = phase
}

func (t *Task) ToStatusResponse() *models.StatusResponse {
	t.mu.Lock()
	defer t.mu.Unlock()
	return &models.StatusResponse{
		ID:       t.ID,
		Status:   string(t.Status),
		Progress: t.Progress,
	}
}

type ValidationError struct {
	Details string
}

func (e *ValidationError) Error() string { return fmt.Sprintf("validation error: %s", e.Details) }

type TimeoutError struct{}

func (e *TimeoutError) Error() string { return "timeout" }

type TaskExecutor struct {
	handlers map[string]TaskHandler
	mu       sync.RWMutex
}

func NewTaskExecutor() *TaskExecutor {
	return &TaskExecutor{
		handlers: make(map[string]TaskHandler),
	}
}

func (e *TaskExecutor) Register(taskType string, handler TaskHandler) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.handlers[taskType] = handler
}

func (e *TaskExecutor) GetHandler(taskType string) (TaskHandler, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	h, ok := e.handlers[taskType]
	return h, ok
}

func (e *TaskExecutor) Execute(ctx context.Context, task *Task) error {
	handler, ok := e.GetHandler(task.Type)
	if !ok {
		return fmt.Errorf("no handler registered for task type: %s", task.Type)
	}

	traceID := ctx.Value("traceId")
	if traceID == nil {
		traceID = generateID("trace_")
	}

	ctx = context.WithValue(ctx, "traceId", traceID)

	if err := e.validateParams(task.Payload); err != nil {
		logger.Warn("parameter validation failed", zap.String("trace_id", traceID.(string)), zap.Error(err))
		task.Error = &ValidationError{Details: err.Error()}
		task.SetStatus(TaskStatusFailed)
		return task.Error
	}

	config, err := e.loadConfig(ctx, task.Type)
	if err != nil {
		logger.Warn("load config failed", zap.String("trace_id", traceID.(string)), zap.Error(err))
		task.Error = err
		task.SetStatus(TaskStatusFailed)
		return err
	}
	task.Config = config

	ctx, cancel := context.WithTimeout(ctx, task.Timeout)
	defer cancel()
	task.Context = ctx
	task.CancelFunc = cancel
	task.StartedAt = time.Now()
	task.SetStatus(TaskStatusRunning)
	task.SetPhase(PhaseProcessing)

	logger.Info("task started", zap.String("task_id", task.ID), zap.String("trace_id", traceID.(string)))

	var result interface{}
	var execErr error

	for task.Retries <= task.MaxRetries {
		select {
		case <-ctx.Done():
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				task.Error = &TimeoutError{}
				logger.Warn("task timed out", zap.String("task_id", task.ID))
			} else {
				task.Error = ctx.Err()
				logger.Warn("task cancelled", zap.String("task_id", task.ID))
			}
			execErr = task.Error
			break
		default:
			result, execErr = handler(ctx, task)
			if execErr == nil {
				task.Result = result
				task.SetProgress(1.0)
				task.SetPhase(PhaseFinalizing)
				if err := e.persistResult(task); err != nil {
					logger.Error("persist result failed", zap.String("task_id", task.ID), zap.Error(err))
				}
				e.emitEvent("task.completed", task)
				task.SetStatus(TaskStatusCompleted)
				task.SetPhase(PhaseCompleted)
				logger.Info("task completed", zap.String("task_id", task.ID), zap.Float64("progress", task.Progress))
				return nil
			}
			task.Retries++
			logger.Warn("task execution failed, retrying", zap.String("task_id", task.ID), zap.Int("retry", task.Retries), zap.Error(execErr))
			time.Sleep(time.Duration(task.Retries) * time.Second)
		}
		if execErr != nil {
			break
		}
	}

	task.Error = execErr
	task.SetStatus(TaskStatusFailed)
	e.rollbackTransaction(ctx, task)
	e.recordMetrics(ctx, task)
	logger.Error("task failed", zap.String("task_id", task.ID), zap.Error(execErr))

	return execErr
}

func (e *TaskExecutor) validateParams(params map[string]interface{}) error {
	if params == nil {
		return fmt.Errorf("params cannot be nil")
	}
	return nil
}

func (e *TaskExecutor) loadConfig(ctx context.Context, namespace string) (*models.Config, error) {
	return &models.Config{
		ConfigID:  "cfg_" + namespace,
		Namespace: namespace,
		Version:   1,
		Parameters: map[string]interface{}{
			"timeout": 30,
			"retries": 3,
		},
		Enabled:   true,
		AppliedAt: time.Now(),
	}, nil
}

func (e *TaskExecutor) persistResult(task *Task) error {
	return nil
}

func (e *TaskExecutor) rollbackTransaction(ctx context.Context, task *Task) {
	logger.Info("rolling back transaction", zap.String("task_id", task.ID))
}

func (e *TaskExecutor) recordMetrics(ctx context.Context, task *Task) {
	duration := time.Since(task.StartedAt).Seconds()
	logger.Info("recording metrics", zap.String("task_id", task.ID), zap.Float64("duration_seconds", duration))
}

func (e *TaskExecutor) emitEvent(eventType string, task *Task) {
	logger.Info("event emitted", zap.String("event_type", eventType), zap.String("task_id", task.ID))
}
