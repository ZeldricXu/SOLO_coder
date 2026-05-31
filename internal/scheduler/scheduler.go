package scheduler

import (
	"context"
	"sync"
	"time"

	"github.com/parking-platform/platform/pkg/models"
	"github.com/parking-platform/platform/pkg/utils"
)

type TaskExecutor interface {
	Execute(ctx context.Context, task *models.Task) error
}

type NoopExecutor struct{}

func (e *NoopExecutor) Execute(ctx context.Context, task *models.Task) error { return nil }

type Scheduler struct {
	mu        sync.RWMutex
	tasks     map[string]*models.Task
	executor  TaskExecutor
	running   map[string]context.CancelFunc
}

func NewScheduler(executor TaskExecutor) *Scheduler {
	if executor == nil {
		executor = &NoopExecutor{}
	}
	return &Scheduler{
		tasks:    make(map[string]*models.Task),
		executor: executor,
		running:  make(map[string]context.CancelFunc),
	}
}

func (s *Scheduler) AddTask(name string, dependencies []string) *models.Task {
	s.mu.Lock()
	defer s.mu.Unlock()
	task := &models.Task{
		ID:           utils.GenerateID("task"),
		Name:         name,
		Status:       "pending",
		Dependencies: append([]string(nil), dependencies...),
		Retries:      0,
		CreatedAt:    utils.Now(),
	}
	s.tasks[task.ID] = task
	return task
}

func (s *Scheduler) ListTasks() []*models.Task {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]*models.Task, 0, len(s.tasks))
	for _, t := range s.tasks {
		result = append(result, t)
	}
	return result
}

func (s *Scheduler) GetTask(id string) (*models.Task, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	t, ok := s.tasks[id]
	return t, ok
}

func (s *Scheduler) dependenciesReady(task *models.Task) bool {
	for _, depID := range task.Dependencies {
		dep, ok := s.tasks[depID]
		if !ok || dep.Status != "completed" {
			return false
		}
	}
	return true
}

func (s *Scheduler) Run(ctx context.Context, taskID string) error {
	s.mu.Lock()
	task, ok := s.tasks[taskID]
	if !ok {
		s.mu.Unlock()
		return ErrTaskNotFound
	}
	if task.Status == "running" {
		s.mu.Unlock()
		return ErrTaskRunning
	}
	s.mu.Unlock()

	for {
		s.mu.RLock()
		ready := s.dependenciesReady(task)
		s.mu.RUnlock()
		if ready {
			break
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}

	ctx, cancel := context.WithCancel(ctx)
	s.mu.Lock()
	task.Status = "running"
	s.running[taskID] = cancel
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.running, taskID)
		s.mu.Unlock()
		cancel()
	}()

	err := s.executor.Execute(ctx, task)
	s.mu.Lock()
	defer s.mu.Unlock()
	if err != nil {
		task.Status = "failed"
		task.Retries++
		return err
	}
	task.Status = "completed"
	return nil
}

func (s *Scheduler) RunAll(ctx context.Context) error {
	s.mu.RLock()
	ids := make([]string, 0, len(s.tasks))
	for id := range s.tasks {
		ids = append(ids, id)
	}
	s.mu.RUnlock()

	for _, id := range ids {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
			go s.Run(ctx, id)
		}
	}
	return nil
}

func (s *Scheduler) Cancel(taskID string) {
	s.mu.Lock()
	if cancel, ok := s.running[taskID]; ok {
		cancel()
		delete(s.running, taskID)
	}
	if task, ok := s.tasks[taskID]; ok {
		task.Status = "cancelled"
	}
	s.mu.Unlock()
}

func (s *Scheduler) CancelAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for id, cancel := range s.running {
		cancel()
		delete(s.running, id)
		if task, ok := s.tasks[id]; ok {
			task.Status = "cancelled"
		}
	}
}

var (
	ErrTaskNotFound = &schedError{"task not found"}
	ErrTaskRunning  = &schedError{"task already running"}
)

type schedError struct {
	msg string
}

func (e *schedError) Error() string { return e.msg }
