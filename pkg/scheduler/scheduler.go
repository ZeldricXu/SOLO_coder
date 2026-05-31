package scheduler

import (
	"container/heap"
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type taskItem struct {
	task       *interfaces.Task
	status     *interfaces.TaskStatus
	submitTime int64
	watchers   []chan *interfaces.TaskStatus
}

type priorityQueue []*taskItem

func (pq priorityQueue) Len() int { return len(pq) }

func (pq priorityQueue) Less(i, j int) bool {
	return pq[i].task.Priority > pq[j].task.Priority
}

func (pq priorityQueue) Swap(i, j int) {
	pq[i], pq[j] = pq[j], pq[i]
}

func (pq *priorityQueue) Push(x interface{}) {
	item := x.(*taskItem)
	*pq = append(*pq, item)
}

func (pq *priorityQueue) Pop() interface{} {
	old := *pq
	n := len(old)
	item := old[n-1]
	old[n-1] = nil
	*pq = old[0 : n-1]
	return item
}

type TaskHandler func(ctx context.Context, task *interfaces.Task) error

type DefaultScheduler struct {
	tasks       map[string]*taskItem
	pq          priorityQueue
	handlers    map[string]TaskHandler
	logger      *zap.Logger
	mu          sync.RWMutex
	workerCount int
	maxRetries  int
	stopChan    chan struct{}
	isRunning   bool
}

func NewDefaultScheduler(workerCount, maxRetries int) *DefaultScheduler {
	s := &DefaultScheduler{
		tasks:       make(map[string]*taskItem),
		pq:          make(priorityQueue, 0),
		handlers:    make(map[string]TaskHandler),
		logger:      utils.GetLogger(),
		workerCount: workerCount,
		maxRetries:  maxRetries,
		stopChan:    make(chan struct{}),
	}

	heap.Init(&s.pq)
	s.startWorkers()

	return s
}

func (s *DefaultScheduler) RegisterHandler(taskType string, handler TaskHandler) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.handlers[taskType] = handler
	s.logger.Info("Task handler registered", zap.String("task_type", taskType))
}

func (s *DefaultScheduler) SubmitTask(ctx context.Context, task *interfaces.Task) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if task.ID == "" {
		task.ID = utils.GenerateID("task")
	}

	if _, exists := s.tasks[task.ID]; exists {
		return "", utils.ErrAlreadyExists
	}

	item := &taskItem{
		task: task,
		status: &interfaces.TaskStatus{
			TaskID:    task.ID,
			Status:    "pending",
			Progress:  0,
			StartedAt: time.Now().Unix(),
		},
		submitTime: time.Now().Unix(),
		watchers:   make([]chan *interfaces.TaskStatus, 0),
	}

	s.tasks[task.ID] = item
	heap.Push(&s.pq, item)

	s.logger.Info("Task submitted",
		zap.String("task_id", task.ID),
		zap.String("task_type", task.Type),
		zap.Int("priority", task.Priority),
	)

	return task.ID, nil
}

func (s *DefaultScheduler) GetTaskStatus(ctx context.Context, taskID string) (*interfaces.TaskStatus, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	item, exists := s.tasks[taskID]
	if !exists {
		return nil, utils.ErrNotFound
	}

	status := *item.status
	return &status, nil
}

func (s *DefaultScheduler) CancelTask(ctx context.Context, taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	item, exists := s.tasks[taskID]
	if !exists {
		return utils.ErrNotFound
	}

	if item.status.Status == "running" {
		item.status.Status = "cancelled"
		item.status.EndAt = time.Now().Unix()
		s.notifyWatchers(item)
	}

	s.logger.Info("Task cancelled", zap.String("task_id", taskID))
	return nil
}

func (s *DefaultScheduler) WatchTask(ctx context.Context, taskID string) (<-chan *interfaces.TaskStatus, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	item, exists := s.tasks[taskID]
	if !exists {
		return nil, utils.ErrNotFound
	}

	watcher := make(chan *interfaces.TaskStatus, 10)
	item.watchers = append(item.watchers, watcher)

	go func() {
		<-ctx.Done()
		s.removeWatcher(taskID, watcher)
	}()

	return watcher, nil
}

func (s *DefaultScheduler) startWorkers() {
	s.isRunning = true
	for i := 0; i < s.workerCount; i++ {
		go s.worker(i)
	}
	s.logger.Info("Scheduler workers started", zap.Int("worker_count", s.workerCount))
}

func (s *DefaultScheduler) worker(id int) {
	s.logger.Debug("Worker started", zap.Int("worker_id", id))

	for {
		select {
		case <-s.stopChan:
			s.logger.Debug("Worker stopped", zap.Int("worker_id", id))
			return
		default:
			taskItem := s.getNextTask()
			if taskItem == nil {
				time.Sleep(100 * time.Millisecond)
				continue
			}

			s.executeTask(taskItem)
		}
	}
}

func (s *DefaultScheduler) getNextTask() *taskItem {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.pq.Len() == 0 {
		return nil
	}

	item := heap.Pop(&s.pq).(*taskItem)

	if item.status.Status == "cancelled" {
		return nil
	}

	item.status.Status = "running"
	s.notifyWatchers(item)

	return item
}

func (s *DefaultScheduler) executeTask(item *taskItem) {
	ctx := context.Background()

	handler, exists := s.handlers[item.task.Type]
	if !exists {
		s.updateTaskStatus(item, "failed", 1.0, fmt.Sprintf("no handler for task type: %s", item.task.Type))
		return
	}

	s.logger.Info("Executing task",
		zap.String("task_id", item.task.ID),
		zap.String("task_type", item.task.Type),
	)

	var err error
	for retry := 0; retry <= s.maxRetries; retry++ {
		err = handler(ctx, item.task)
		if err == nil {
			s.updateTaskStatus(item, "completed", 1.0, "")
			s.logger.Info("Task completed", zap.String("task_id", item.task.ID))
			return
		}

		if !utils.IsRetryable(err) {
			break
		}

		s.logger.Warn("Task failed, retrying",
			zap.String("task_id", item.task.ID),
			zap.Int("retry", retry+1),
			zap.Error(err),
		)

		progress := float64(retry+1) / float64(s.maxRetries+1) * 0.5
		s.updateTaskStatus(item, "running", progress, err.Error())

		time.Sleep(time.Duration(retry+1) * time.Second)
	}

	s.updateTaskStatus(item, "failed", 1.0, err.Error())
	s.logger.Error("Task failed",
		zap.String("task_id", item.task.ID),
		zap.Error(err),
	)
}

func (s *DefaultScheduler) updateTaskStatus(item *taskItem, status string, progress float64, errMsg string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	item.status.Status = status
	item.status.Progress = progress
	item.status.Error = errMsg
	if status == "completed" || status == "failed" || status == "cancelled" {
		item.status.EndAt = time.Now().Unix()
	}

	s.notifyWatchers(item)
}

func (s *DefaultScheduler) notifyWatchers(item *taskItem) {
	status := *item.status
	for _, watcher := range item.watchers {
		select {
		case watcher <- &status:
		default:
		}
	}

	if status.Status == "completed" || status.Status == "failed" || status.Status == "cancelled" {
		for _, watcher := range item.watchers {
			close(watcher)
		}
		item.watchers = nil
	}
}

func (s *DefaultScheduler) removeWatcher(taskID string, watcher chan *interfaces.TaskStatus) {
	s.mu.Lock()
	defer s.mu.Unlock()

	item, exists := s.tasks[taskID]
	if !exists {
		return
	}

	for i, w := range item.watchers {
		if w == watcher {
			item.watchers = append(item.watchers[:i], item.watchers[i+1:]...)
			break
		}
	}
}

func (s *DefaultScheduler) Stop() {
	if s.isRunning {
		close(s.stopChan)
		s.isRunning = false
		s.logger.Info("Scheduler stopped")
	}
}
