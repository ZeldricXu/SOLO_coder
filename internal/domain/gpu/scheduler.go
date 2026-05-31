package gpu

import (
	"context"
	"sort"
	"sync"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
	"github.com/google/uuid"
)

type StateChangeHook func(ctx context.Context, task *GPUTask, oldStatus, newStatus domain.ResourceStatus)

type GPUSchedulerImpl struct {
	resourceManager   *GPUResourceManagerImpl
	taskQueue         PriorityQueue
	runningTasks      map[string]*GPUTask
	completedTasks    map[string]*GPUTask
	mu                sync.RWMutex
	preemptionEnabled bool
	workerCount       int
	taskCh            chan *GPUTask
	stopCh            chan struct{}
	logger            domain.Logger
	stateHooks        []StateChangeHook
	shutdown          bool
}

func NewGPUScheduler(
	resourceManager *GPUResourceManagerImpl,
	preemptionEnabled bool,
	workerCount int,
	logger domain.Logger,
) *GPUSchedulerImpl {
	s := &GPUSchedulerImpl{
		resourceManager:   resourceManager,
		taskQueue:         make(PriorityQueue, 0),
		runningTasks:      make(map[string]*GPUTask),
		completedTasks:    make(map[string]*GPUTask),
		preemptionEnabled: preemptionEnabled,
		workerCount:       workerCount,
		taskCh:            make(chan *GPUTask, 100),
		stopCh:            make(chan struct{}),
		logger:            logger,
		shutdown:          false,
	}

	heap.Init(&s.taskQueue)
	go s.run()

	return s
}

func (s *GPUSchedulerImpl) AddStateChangeHook(hook StateChangeHook) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.stateHooks = append(s.stateHooks, hook)
}

func (s *GPUSchedulerImpl) notifyStateChange(task *GPUTask, oldStatus, newStatus domain.ResourceStatus) {
	if len(s.stateHooks) == 0 {
		return
	}

	taskCopy := *task
	hooks := make([]StateChangeHook, len(s.stateHooks))
	copy(hooks, s.stateHooks)

	go func() {
		ctx := context.Background()
		for _, hook := range hooks {
			func() {
				defer func() {
					if r := recover(); r != nil {
						s.logger.Error("State change hook panicked",
							domain.Any("panic", r),
						)
					}
				}()
				hook(ctx, &taskCopy, oldStatus, newStatus)
			}()
		}
	}()
}

func (s *GPUSchedulerImpl) SubmitTask(ctx context.Context, task *GPUTask) (*GPUTask, error) {
	if task == nil {
		return nil, errors.New(errors.ErrCodeValidation, "task cannot be nil")
	}
	if task.Name == "" {
		return nil, errors.New(errors.ErrCodeValidation, "task name cannot be empty")
	}
	if task.VRAMRequired == 0 {
		return nil, errors.New(errors.ErrCodeValidation, "task VRAM requirement cannot be zero")
	}

	s.mu.Lock()

	if s.shutdown {
		s.mu.Unlock()
		return nil, errors.New(errors.ErrCodeUnavailable, "scheduler is shutting down")
	}

	oldStatus := task.Status
	task.ID = uuid.New().String()
	task.Status = domain.StatusPending
	task.SubmittedAt = time.Now()

	taskCopy := *task
	heap.Push(&s.taskQueue, &taskCopy)

	s.logger.Info("GPU task submitted",
		domain.String("task_id", taskCopy.ID),
		domain.String("name", taskCopy.Name),
		domain.Int("priority", int(taskCopy.Priority)),
		domain.Int("queue_size", s.taskQueue.Len()),
	)

	s.notifyStateChange(&taskCopy, oldStatus, taskCopy.Status)
	s.mu.Unlock()

	go s.trySchedule()
	*task = taskCopy
	return task, nil
}

func (s *GPUSchedulerImpl) trySchedule() {
	s.mu.Lock()
	if s.shutdown {
		s.mu.Unlock()
		return
	}

	for s.taskQueue.Len() > 0 {
		task := s.taskQueue[0]

		req := &GPUResourceRequest{
			TaskID:        task.ID,
			MinVRAM:       task.VRAMRequired,
			PreferredVRAM: task.VRAMRequired,
		}

		resource, err := s.resourceManager.Acquire(context.Background(), req)
		if err == nil {
			heap.Pop(&s.taskQueue)
			oldStatus := task.Status
			task.ResourceID = resource.ID
			task.Status = domain.StatusRunning
			now := time.Now()
			task.StartedAt = &now

			taskCopy := *task
			s.runningTasks[task.ID] = &taskCopy

			s.logger.Info("GPU task scheduled",
				domain.String("task_id", task.ID),
				domain.String("resource_id", resource.ID),
			)

			s.notifyStateChange(task, oldStatus, task.Status)
			s.mu.Unlock()

			go s.executeTask(taskCopy)

			s.mu.Lock()
			if s.shutdown {
				s.mu.Unlock()
				return
			}
			continue
		}

		if s.preemptionEnabled && task.Priority == PriorityCritical {
			preempted := s.tryPreempt(task.VRAMRequired)
			if preempted {
				continue
			}
		}

		break
	}

	s.mu.Unlock()
}

func (s *GPUSchedulerImpl) tryPreempt(requiredVRAM uint64) bool {
	var preemptibleTasks []*GPUTask
	for _, task := range s.runningTasks {
		if task.Preemptible && task.Priority < PriorityCritical {
			taskCopy := *task
			preemptibleTasks = append(preemptibleTasks, &taskCopy)
		}
	}

	sort.Slice(preemptibleTasks, func(i, j int) bool {
		return preemptibleTasks[i].Priority < preemptibleTasks[j].Priority
	})

	var freedVRAM uint64
	for _, task := range preemptibleTasks {
		if freedVRAM >= requiredVRAM {
			break
		}

		oldStatus := task.Status
		task.Status = domain.StatusPreempted
		now := time.Now()
		task.CompletedAt = &now
		task.Error = "preempted by higher priority task"

		s.resourceManager.Release(context.Background(), task.ResourceID)
		delete(s.runningTasks, task.ID)
		s.completedTasks[task.ID] = task
		s.notifyStateChange(task, oldStatus, task.Status)

		freedVRAM += task.VRAMRequired

		s.logger.Info("GPU task preempted",
			domain.String("task_id", task.ID),
		)
	}

	return freedVRAM >= requiredVRAM
}

func (s *GPUSchedulerImpl) executeTask(task GPUTask) {
	defer func() {
		if r := recover(); r != nil {
			s.logger.Error("Task execution panicked",
				domain.String("task_id", task.ID),
				domain.Any("panic", r),
			)
		}

		s.mu.Lock()
		s.resourceManager.Release(context.Background(), task.ResourceID)
		delete(s.runningTasks, task.ID)
		s.completedTasks[task.ID] = &task
		s.notifyStateChange(&task, domain.StatusRunning, task.Status)
		s.mu.Unlock()

		s.logger.Info("GPU task completed",
			domain.String("task_id", task.ID),
			domain.String("status", string(task.Status)),
		)

		go s.trySchedule()
	}()

	time.Sleep(100 * time.Millisecond)

	s.mu.Lock()
	task.Status = domain.StatusCompleted
	now := time.Now()
	task.CompletedAt = &now
	task.Result = map[string]interface{}{
		"success": true,
	}
	s.mu.Unlock()
}

func (s *GPUSchedulerImpl) CancelTask(ctx context.Context, taskID string) error {
	if taskID == "" {
		return errors.New(errors.ErrCodeValidation, "task ID cannot be empty")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if task, exists := s.runningTasks[taskID]; exists {
		oldStatus := task.Status
		task.Status = domain.StatusFailed
		now := time.Now()
		task.CompletedAt = &now
		task.Error = "cancelled by user"
		s.resourceManager.Release(ctx, task.ResourceID)
		delete(s.runningTasks, taskID)
		s.completedTasks[taskID] = task
		s.notifyStateChange(task, oldStatus, task.Status)
		return nil
	}

	for i, task := range s.taskQueue {
		if task.ID == taskID {
			oldStatus := task.Status
			heap.Remove(&s.taskQueue, i)
			task.Status = domain.StatusFailed
			task.Error = "cancelled by user"
			s.completedTasks[taskID] = task
			s.notifyStateChange(task, oldStatus, task.Status)
			return nil
		}
	}

	return errors.New(errors.ErrCodeNotFound, "task not found")
}

func (s *GPUSchedulerImpl) GetTaskStatus(ctx context.Context, taskID string) (*GPUTask, error) {
	if taskID == "" {
		return nil, errors.New(errors.ErrCodeValidation, "task ID cannot be empty")
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	if task, exists := s.runningTasks[taskID]; exists {
		taskCopy := *task
		return &taskCopy, nil
	}

	if task, exists := s.completedTasks[taskID]; exists {
		taskCopy := *task
		return &taskCopy, nil
	}

	for _, task := range s.taskQueue {
		if task.ID == taskID {
			taskCopy := *task
			return &taskCopy, nil
		}
	}

	return nil, errors.New(errors.ErrCodeNotFound, "task not found")
}

func (s *GPUSchedulerImpl) PreemptTasks(ctx context.Context, minVRAM uint64) ([]*GPUTask, error) {
	if minVRAM == 0 {
		return nil, errors.New(errors.ErrCodeValidation, "minVRAM cannot be zero")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	var preempted []*GPUTask
	var freedVRAM uint64

	for _, task := range s.runningTasks {
		if freedVRAM >= minVRAM {
			break
		}
		if task.Preemptible {
			oldStatus := task.Status
			task.Status = domain.StatusPreempted
			now := time.Now()
			task.CompletedAt = &now
			task.Error = "preempted"
			s.resourceManager.Release(ctx, task.ResourceID)
			delete(s.runningTasks, task.ID)
			s.completedTasks[task.ID] = task
			s.notifyStateChange(task, oldStatus, task.Status)

			taskCopy := *task
			preempted = append(preempted, &taskCopy)
			freedVRAM += task.VRAMRequired
		}
	}

	return preempted, nil
}

func (s *GPUSchedulerImpl) GetAvailableResources(ctx context.Context) ([]*GPUResource, error) {
	return s.resourceManager.List(ctx)
}

func (s *GPUSchedulerImpl) run() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			go s.trySchedule()
		case <-s.stopCh:
			return
		}
	}
}

func (s *GPUSchedulerImpl) Shutdown(ctx context.Context) error {
	s.mu.Lock()
	s.shutdown = true
	s.mu.Unlock()

	close(s.stopCh)
	return nil
}

func (s *GPUSchedulerImpl) GetQueueSize() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.taskQueue.Len()
}

func (s *GPUSchedulerImpl) GetRunningCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.runningTasks)
}

func (s *GPUSchedulerImpl) GetCompletedCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.completedTasks)
}
