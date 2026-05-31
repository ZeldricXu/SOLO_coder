package core

import (
	"context"
	"session154/internal/logger"
	"sync"
	"time"

	"go.uber.org/zap"
)

type Scheduler struct {
	executor    *TaskExecutor
	taskQueue   chan *Task
	taskStore   map[string]*Task
	workerCount int
	wg          sync.WaitGroup
	ctx         context.Context
	cancel      context.CancelFunc
	mu          sync.RWMutex
	running     bool
}

func NewScheduler(workerCount int, queueSize int) *Scheduler {
	return &Scheduler{
		executor:    NewTaskExecutor(),
		taskQueue:   make(chan *Task, queueSize),
		taskStore:   make(map[string]*Task),
		workerCount: workerCount,
	}
}

func (s *Scheduler) Register(taskType string, handler TaskHandler) {
	s.executor.Register(taskType, handler)
}

func (s *Scheduler) Start(ctx context.Context) {
	s.ctx, s.cancel = context.WithCancel(ctx)
	s.running = true

	for i := 0; i < s.workerCount; i++ {
		s.wg.Add(1)
		go s.worker(i)
	}

	logger.Info("scheduler started", zap.Int("worker_count", s.workerCount))
}

func (s *Scheduler) Stop() {
	s.running = false
	s.cancel()
	close(s.taskQueue)
	s.wg.Wait()
	logger.Info("scheduler stopped")
}

func (s *Scheduler) worker(id int) {
	defer s.wg.Done()
	logger.Debug("worker started", zap.Int("worker_id", id))

	for task := range s.taskQueue {
		if task == nil {
			continue
		}

		logger.Debug("worker processing task", zap.Int("worker_id", id), zap.String("task_id", task.ID))

		ctx := context.WithValue(s.ctx, "workerId", id)
		if err := s.executor.Execute(ctx, task); err != nil {
			logger.Error("task execution failed", zap.String("task_id", task.ID), zap.Error(err))
		}
	}

	logger.Debug("worker stopped", zap.Int("worker_id", id))
}

func (s *Scheduler) Submit(task *Task) error {
	if !s.running {
		return nil
	}

	s.mu.Lock()
	s.taskStore[task.ID] = task
	s.mu.Unlock()

	select {
	case s.taskQueue <- task:
		task.SetStatus(TaskStatusPending)
		logger.Info("task submitted", zap.String("task_id", task.ID), zap.String("task_type", task.Type))
		return nil
	case <-time.After(5 * time.Second):
		return nil
	}
}

func (s *Scheduler) GetTask(taskID string) (*Task, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	task, ok := s.taskStore[taskID]
	return task, ok
}

func (s *Scheduler) CancelTask(taskID string) bool {
	s.mu.RLock()
	task, ok := s.taskStore[taskID]
	s.mu.RUnlock()

	if !ok {
		return false
	}

	if task.CancelFunc != nil {
		task.CancelFunc()
		task.SetStatus(TaskStatusCancelled)
		logger.Info("task cancelled", zap.String("task_id", task.ID))
		return true
	}

	return false
}

func (s *Scheduler) ListTasks() []*Task {
	s.mu.RLock()
	defer s.mu.RUnlock()
	tasks := make([]*Task, 0, len(s.taskStore))
	for _, t := range s.taskStore {
		tasks = append(tasks, t)
	}
	return tasks
}

type BatchProcessor struct {
	scheduler *Scheduler
	mu        sync.Mutex
}

type BatchOperation struct {
	Action string
	ID     string
	Task   *Task
}

func NewBatchProcessor(scheduler *Scheduler) *BatchProcessor {
	return &BatchProcessor{scheduler: scheduler}
}

func (bp *BatchProcessor) Process(ctx context.Context, operations []BatchOperation) (string, []BatchResult) {
	batchID := generateID("batch_")
	results := make([]BatchResult, 0, len(operations))

	for _, op := range operations {
		result := BatchResult{ID: op.ID, Success: false}

		switch op.Action {
		case "start":
			if op.Task != nil {
				if err := bp.scheduler.Submit(op.Task); err == nil {
					result.Success = true
					result.Message = "task submitted"
				} else {
					result.Message = "failed to submit task"
				}
			}
		case "cancel":
			if bp.scheduler.CancelTask(op.ID) {
				result.Success = true
				result.Message = "task cancelled"
			} else {
				result.Message = "task not found"
			}
		default:
			result.Message = "unknown action"
		}

		results = append(results, result)
	}

	return batchID, results
}

type BatchResult struct {
	ID      string `json:"id"`
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
}
