package scheduler

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
	"github.com/solocoder/logrotate/internal/domain"
)

type TaskStatus string

const (
	StatusPending   TaskStatus = "pending"
	StatusRunning   TaskStatus = "running"
	StatusCompleted TaskStatus = "completed"
	StatusFailed    TaskStatus = "failed"
	StatusCancelled TaskStatus = "cancelled"
	StatusPaused    TaskStatus = "paused"
)

type TaskHandler func(ctx context.Context, task *domain.Task) error

type Scheduler struct {
	mu            sync.RWMutex
	tasks         map[string]*domain.Task
	cronJobs      map[string]cron.EntryID
	handlers      map[string]TaskHandler
	cron          *cron.Cron
	workerPool    chan struct{}
	maxWorkers    int
	taskQueue     chan *domain.Task
	statusChan    chan TaskStatus
	ctx           context.Context
	cancel        context.CancelFunc
	runningTasks  map[string]context.CancelFunc
	stopped       bool
}

type Option func(*Scheduler)

func WithMaxWorkers(n int) Option {
	return func(s *Scheduler) {
		s.maxWorkers = n
	}
}

func New(opts ...Option) *Scheduler {
	ctx, cancel := context.WithCancel(context.Background())

	s := &Scheduler{
		tasks:        make(map[string]*domain.Task),
		cronJobs:     make(map[string]cron.EntryID),
		handlers:     make(map[string]TaskHandler),
		cron:         cron.New(cron.WithSeconds()),
		maxWorkers:   10,
		taskQueue:    make(chan *domain.Task, 1000),
		statusChan:   make(chan TaskStatus, 100),
		ctx:          ctx,
		cancel:       cancel,
		runningTasks: make(map[string]context.CancelFunc),
	}

	for _, opt := range opts {
		opt(s)
	}

	s.workerPool = make(chan struct{}, s.maxWorkers)
	s.cron.Start()

	go s.dispatchLoop()

	return s
}

func (s *Scheduler) RegisterHandler(taskType string, handler TaskHandler) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.handlers[taskType] = handler
}

func (s *Scheduler) Submit(task *domain.Task) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.stopped {
		return "", fmt.Errorf("scheduler is stopped")
	}

	if task.ID == "" {
		task.ID = uuid.New().String()
	}
	task.Status = string(StatusPending)
	task.CreatedAt = time.Now()

	s.tasks[task.ID] = task

	select {
	case s.taskQueue <- task:
		return task.ID, nil
	default:
		return "", fmt.Errorf("task queue is full")
	}
}

func (s *Scheduler) SubmitCron(task *domain.Task, cronExpr string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.stopped {
		return "", fmt.Errorf("scheduler is stopped")
	}

	if task.ID == "" {
		task.ID = uuid.New().String()
	}
	task.Status = string(StatusPending)
	task.CreatedAt = time.Now()

	s.tasks[task.ID] = task

	handler, ok := s.handlers[task.Type]
	if !ok {
		return "", fmt.Errorf("no handler registered for task type: %s", task.Type)
	}

	entryID, err := s.cron.AddFunc(cronExpr, func() {
		taskCopy := *task
		taskCopy.ID = uuid.New().String()
		taskCopy.CreatedAt = time.Now()

		s.mu.Lock()
		s.tasks[taskCopy.ID] = &taskCopy
		s.mu.Unlock()

		s.taskQueue <- &taskCopy
	})
	if err != nil {
		return "", fmt.Errorf("invalid cron expression: %w", err)
	}

	s.cronJobs[task.ID] = entryID
	return task.ID, nil
}

func (s *Scheduler) dispatchLoop() {
	for {
		select {
		case <-s.ctx.Done():
			return
		case task := <-s.taskQueue:
			s.workerPool <- struct{}{}
			go s.executeTask(task)
		}
	}
}

func (s *Scheduler) executeTask(task *domain.Task) {
	defer func() {
		<-s.workerPool
	}()

	handler, ok := s.handlers[task.Type]
	if !ok {
		s.updateTaskStatus(task, StatusFailed, fmt.Sprintf("no handler for type: %s", task.Type))
		return
	}

	s.updateTaskStatus(task, StatusRunning, "")

	taskCtx, cancel := context.WithCancel(s.ctx)
	s.mu.Lock()
	s.runningTasks[task.ID] = cancel
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.runningTasks, task.ID)
		s.mu.Unlock()
		cancel()
	}()

	if task.TimeoutSeconds > 0 {
		taskCtx, cancel = context.WithTimeout(taskCtx, time.Duration(task.TimeoutSeconds)*time.Second)
		defer cancel()
	}

	var err error
	for retry := 0; retry <= task.MaxRetries; retry++ {
		task.RetryCount = retry
		err = handler(taskCtx, task)
		if err == nil {
			s.updateTaskStatus(task, StatusCompleted, "")
			return
		}

		if retry < task.MaxRetries {
			select {
			case <-taskCtx.Done():
				s.updateTaskStatus(task, StatusFailed, fmt.Sprintf("task cancelled: %v", taskCtx.Err()))
				return
			case <-time.After(s.backoffDuration(retry)):
			}
		}
	}

	s.updateTaskStatus(task, StatusFailed, err.Error())
}

func (s *Scheduler) backoffDuration(retry int) time.Duration {
	return time.Duration(1<<uint(retry)) * time.Second
}

func (s *Scheduler) updateTaskStatus(task *domain.Task, status TaskStatus, errorMsg string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now()
	task.Status = string(status)

	if status == StatusRunning {
		task.StartedAt = &now
	} else if status == StatusCompleted || status == StatusFailed {
		task.CompletedAt = &now
		if errorMsg != "" {
			task.Error = &errorMsg
		}
	}

	if t, ok := s.tasks[task.ID]; ok {
		*t = *task
	}

	select {
	case s.statusChan <- status:
	default:
	}
}

func (s *Scheduler) GetTask(taskID string) (*domain.Task, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	task, ok := s.tasks[taskID]
	return task, ok
}

func (s *Scheduler) GetTaskStatus(taskID string) (TaskStatus, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	task, ok := s.tasks[taskID]
	if !ok {
		return "", false
	}
	return TaskStatus(task.Status), true
}

func (s *Scheduler) CancelTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, ok := s.tasks[taskID]
	if !ok {
		return fmt.Errorf("task not found: %s", taskID)
	}

	if cancel, ok := s.runningTasks[taskID]; ok {
		cancel()
	}

	task.Status = string(StatusCancelled)
	now := time.Now()
	task.CompletedAt = &now
	errMsg := "task cancelled by user"
	task.Error = &errMsg

	return nil
}

func (s *Scheduler) ListTasks(statusFilter ...TaskStatus) []*domain.Task {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var tasks []*domain.Task
	filterSet := make(map[TaskStatus]bool)
	for _, s := range statusFilter {
		filterSet[s] = true
	}

	for _, task := range s.tasks {
		if len(filterSet) == 0 || filterSet[TaskStatus(task.Status)] {
			tasks = append(tasks, task)
		}
	}
	return tasks
}

func (s *Scheduler) RemoveCron(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	entryID, ok := s.cronJobs[taskID]
	if !ok {
		return fmt.Errorf("cron job not found: %s", taskID)
	}

	s.cron.Remove(entryID)
	delete(s.cronJobs, taskID)
	return nil
}

func (s *Scheduler) Wait() {
	<-s.ctx.Done()
}

func (s *Scheduler) Stop() {
	s.mu.Lock()
	if s.stopped {
		s.mu.Unlock()
		return
	}
	s.stopped = true
	s.mu.Unlock()

	s.cancel()
	s.cron.Stop()
	close(s.taskQueue)
	close(s.statusChan)
}
