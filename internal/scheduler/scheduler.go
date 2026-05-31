package scheduler

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/models"
	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
)

type TaskStatus string

const (
	TaskStatusPending    TaskStatus = "pending"
	TaskStatusRunning    TaskStatus = "running"
	TaskStatusCompleted  TaskStatus = "completed"
	TaskStatusFailed     TaskStatus = "failed"
	TaskStatusPaused     TaskStatus = "paused"
	TaskStatusCancelled  TaskStatus = "cancelled"
	TaskStatusScheduled  TaskStatus = "scheduled"
)

type TaskType string

const (
	TaskTypeOneShot   TaskType = "oneshot"
	TaskTypeCron      TaskType = "cron"
	TaskTypeRecurring TaskType = "recurring"
)

type Task struct {
	ID             string                 `json:"id"`
	Name           string                 `json:"name"`
	Type           TaskType               `json:"type"`
	Status         TaskStatus             `json:"status"`
	Payload        map[string]interface{} `json:"payload"`
	CronExpression string                 `json:"cron_expression,omitempty"`
	Interval       time.Duration          `json:"interval,omitempty"`
	MaxRetries     int                    `json:"max_retries"`
	RetryCount     int                    `json:"retry_count"`
	Timeout        time.Duration          `json:"timeout"`
	Progress       float64                `json:"progress"`
	CreatedAt      time.Time              `json:"created_at"`
	StartedAt      *time.Time             `json:"started_at,omitempty"`
	CompletedAt    *time.Time             `json:"completed_at,omitempty"`
	LastRunAt      *time.Time             `json:"last_run_at,omitempty"`
	NextRunAt      *time.Time             `json:"next_run_at,omitempty"`
	Error          string                 `json:"error,omitempty"`
	Handler        TaskHandler            `json:"-"`
	CronEntryID    cron.EntryID           `json:"-"`
}

type TaskHandler func(ctx context.Context, task *Task) error

type TaskExecution struct {
	ExecutionID string    `json:"execution_id"`
	TaskID      string    `json:"task_id"`
	Status      TaskStatus `json:"status"`
	StartedAt   time.Time `json:"started_at"`
	EndedAt     time.Time `json:"ended_at,omitempty"`
	Error       string    `json:"error,omitempty"`
	Output      interface{} `json:"output,omitempty"`
}

type Scheduler struct {
	tasks          map[string]*Task
	executions     map[string][]*TaskExecution
	mu             sync.RWMutex
	cron           *cron.Cron
	workerPool     chan struct{}
	stopCh         chan struct{}
	wg             sync.WaitGroup
	maxWorkers     int
}

func NewScheduler(maxWorkers int) *Scheduler {
	return &Scheduler{
		tasks:      make(map[string]*Task),
		executions: make(map[string][]*TaskExecution),
		cron:       cron.New(),
		workerPool: make(chan struct{}, maxWorkers),
		stopCh:     make(chan struct{}),
		maxWorkers: maxWorkers,
	}
}

func (s *Scheduler) Start() {
	s.cron.Start()
}

func (s *Scheduler) Stop() {
	s.cron.Stop()
	close(s.stopCh)
	s.wg.Wait()
}

func (s *Scheduler) CreateTask(name string, taskType TaskType, handler TaskHandler, payload map[string]interface{}, opts ...TaskOption) (*Task, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	task := &Task{
		ID:         uuid.New().String(),
		Name:       name,
		Type:       taskType,
		Status:     TaskStatusPending,
		Payload:    payload,
		Handler:    handler,
		MaxRetries: 3,
		Timeout:    5 * time.Minute,
		CreatedAt:  time.Now(),
	}

	for _, opt := range opts {
		opt(task)
	}

	s.tasks[task.ID] = task
	s.executions[task.ID] = make([]*TaskExecution, 0)

	if task.Type == TaskTypeCron && task.CronExpression != "" {
		entryID, err := s.cron.AddFunc(task.CronExpression, func() {
			s.executeTask(task.ID)
		})
		if err != nil {
			return nil, fmt.Errorf("invalid cron expression: %w", err)
		}
		task.CronEntryID = entryID
		task.Status = TaskStatusScheduled
		entry := s.cron.Entry(entryID)
		task.NextRunAt = &entry.Next
	} else if task.Type == TaskTypeRecurring && task.Interval > 0 {
		entryID := s.cron.Schedule(cron.Every(task.Interval), cron.FuncJob(func() {
			s.executeTask(task.ID)
		}))
		task.CronEntryID = entryID
		task.Status = TaskStatusScheduled
		entry := s.cron.Entry(entryID)
		task.NextRunAt = &entry.Next
	}

	return task, nil
}

type TaskOption func(*Task)

func WithCronExpression(expr string) TaskOption {
	return func(t *Task) {
		t.CronExpression = expr
	}
}

func WithInterval(interval time.Duration) TaskOption {
	return func(t *Task) {
		t.Interval = interval
	}
}

func WithMaxRetries(max int) TaskOption {
	return func(t *Task) {
		t.MaxRetries = max
	}
}

func WithTimeout(timeout time.Duration) TaskOption {
	return func(t *Task) {
		t.Timeout = timeout
	}
}

func (s *Scheduler) ExecuteTask(taskID string) error {
	s.mu.RLock()
	task, ok := s.tasks[taskID]
	s.mu.RUnlock()

	if !ok {
		return errors.New("task not found")
	}

	if task.Type != TaskTypeOneShot {
		return errors.New("only one-shot tasks can be executed manually")
	}

	go s.executeTask(taskID)
	return nil
}

func (s *Scheduler) executeTask(taskID string) {
	s.mu.RLock()
	task, ok := s.tasks[taskID]
	s.mu.RUnlock()

	if !ok {
		return
	}

	select {
	case s.workerPool <- struct{}{}:
		defer func() { <-s.workerPool }()
	case <-s.stopCh:
		return
	}

	s.wg.Add(1)
	defer s.wg.Done()

	execution := &TaskExecution{
		ExecutionID: uuid.New().String(),
		TaskID:      taskID,
		Status:      TaskStatusRunning,
		StartedAt:   time.Now(),
	}

	s.mu.Lock()
	task.Status = TaskStatusRunning
	now := time.Now()
	task.StartedAt = &now
	s.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), task.Timeout)
	defer cancel()

	err := task.Handler(ctx, task)

	execution.EndedAt = time.Now()

	s.mu.Lock()
	defer s.mu.Unlock()

	if err != nil {
		execution.Status = TaskStatusFailed
		execution.Error = err.Error()
		task.Error = err.Error()

		if task.RetryCount < task.MaxRetries {
			task.RetryCount++
			nextRetry := time.Now().Add(time.Second * time.Duration(task.RetryCount*2))
			task.NextRunAt = &nextRetry
			go func() {
				time.Sleep(time.Until(nextRetry))
				s.executeTask(taskID)
			}()
		} else {
			task.Status = TaskStatusFailed
			completedAt := time.Now()
			task.CompletedAt = &completedAt
		}
	} else {
		execution.Status = TaskStatusCompleted
		task.Status = TaskStatusCompleted
		completedAt := time.Now()
		task.CompletedAt = &completedAt
		task.Progress = 1.0
	}

	lastRun := time.Now()
	task.LastRunAt = &lastRun
	s.executions[taskID] = append(s.executions[taskID], execution)

	if task.Type != TaskTypeOneShot {
		entry := s.cron.Entry(task.CronEntryID)
		if entry.Valid() {
			task.NextRunAt = &entry.Next
		}
	}
}

func (s *Scheduler) GetTask(taskID string) (*Task, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	task, ok := s.tasks[taskID]
	if !ok {
		return nil, errors.New("task not found")
	}
	return task, nil
}

func (s *Scheduler) GetTaskStatus(taskID string) (TaskStatus, float64, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	task, ok := s.tasks[taskID]
	if !ok {
		return "", 0, errors.New("task not found")
	}
	return task.Status, task.Progress, nil
}

func (s *Scheduler) ListTasks() []*Task {
	s.mu.RLock()
	defer s.mu.RUnlock()

	tasks := make([]*Task, 0, len(s.tasks))
	for _, t := range s.tasks {
		tasks = append(tasks, t)
	}
	return tasks
}

func (s *Scheduler) CancelTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, ok := s.tasks[taskID]
	if !ok {
		return errors.New("task not found")
	}

	if task.CronEntryID != 0 {
		s.cron.Remove(task.CronEntryID)
	}

	task.Status = TaskStatusCancelled
	return nil
}

func (s *Scheduler) PauseTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, ok := s.tasks[taskID]
	if !ok {
		return errors.New("task not found")
	}

	if task.CronEntryID != 0 {
		s.cron.Remove(task.CronEntryID)
	}

	task.Status = TaskStatusPaused
	return nil
}

func (s *Scheduler) ResumeTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, ok := s.tasks[taskID]
	if !ok {
		return errors.New("task not found")
	}

	if task.Type == TaskTypeCron && task.CronExpression != "" {
		entryID, err := s.cron.AddFunc(task.CronExpression, func() {
			s.executeTask(taskID)
		})
		if err != nil {
			return err
		}
		task.CronEntryID = entryID
		task.Status = TaskStatusScheduled
	} else if task.Type == TaskTypeRecurring && task.Interval > 0 {
		entryID := s.cron.Schedule(cron.Every(task.Interval), cron.FuncJob(func() {
			s.executeTask(taskID)
		}))
		task.CronEntryID = entryID
		task.Status = TaskStatusScheduled
	}

	return nil
}

func (s *Scheduler) UpdateProgress(taskID string, progress float64) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, ok := s.tasks[taskID]
	if !ok {
		return errors.New("task not found")
	}

	if progress < 0 {
		progress = 0
	}
	if progress > 1 {
		progress = 1
	}
	task.Progress = progress
	return nil
}

func (s *Scheduler) GetExecutions(taskID string) ([]*TaskExecution, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	execs, ok := s.executions[taskID]
	if !ok {
		return nil, errors.New("task not found")
	}
	return execs, nil
}

func (s *Scheduler) ToEntity() *models.Entity {
	return &models.Entity{
		ID:        uuid.New().String(),
		Type:      "scheduler",
		Status:    "active",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}
