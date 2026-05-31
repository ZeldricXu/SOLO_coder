package gpu

import (
	"container/heap"
	"context"
	"errors"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/solocoder/logrotate/internal/domain"
)

type TaskStatus string

const (
	TaskStatusQueued    TaskStatus = "queued"
	TaskStatusRunning   TaskStatus = "running"
	TaskStatusCompleted TaskStatus = "completed"
	TaskStatusFailed    TaskStatus = "failed"
	TaskStatusPreempted TaskStatus = "preempted"
	TaskStatusCancelled TaskStatus = "cancelled"
)

type Priority int

const (
	PriorityLow    Priority = 0
	PriorityMedium Priority = 50
	PriorityHigh   Priority = 100
	PriorityUrgent Priority = 200
)

type Task struct {
	ID             string
	Name           string
	Priority       Priority
	RequiredMemory int64
	RequiredGPU    int
	Status         TaskStatus
	QueueTime      time.Time
	StartTime      *time.Time
	EndTime        *time.Time
	AssignedGPU    []string
	PreemptCount   int
	MaxPreemptions int
	TimeoutSeconds int
	Labels         map[string]string
	Metadata       map[string]interface{}
	RunFunc        func(ctx context.Context, resources []*GPU) error
	Result         interface{}
	Error          error
}

type GPU struct {
	ID          string
	NodeID      string
	DeviceIndex int
	TotalMemory int64
	UsedMemory  int64
	Utilization float64
	Status      string
	Labels      map[string]string
	CurrentTask *string
}

type TaskQueue []*Task

func (tq TaskQueue) Len() int { return len(tq) }

func (tq TaskQueue) Less(i, j int) bool {
	if tq[i].Priority != tq[j].Priority {
		return tq[i].Priority > tq[j].Priority
	}
	return tq[i].QueueTime.Before(tq[j].QueueTime)
}

func (tq TaskQueue) Swap(i, j int) {
	tq[i], tq[j] = tq[j], tq[i]
}

func (tq *TaskQueue) Push(x interface{}) {
	*tq = append(*tq, x.(*Task))
}

func (tq *TaskQueue) Pop() interface{} {
	old := *tq
	n := len(old)
	item := old[n-1]
	*tq = old[0 : n-1]
	return item
}

type SchedulerConfig struct {
	EnablePreemption    bool
	PreemptionThreshold float64
	MaxConcurrentTasks  int
	TaskTimeoutSeconds  int
}

type Scheduler struct {
	mu            sync.Mutex
	gpus          map[string]*GPU
	taskQueue     TaskQueue
	runningTasks  map[string]*Task
	completedTasks map[string]*Task
	config        SchedulerConfig
	ctx           context.Context
	cancel        context.CancelFunc
	notifyChan    chan struct{}
}

func NewScheduler(config SchedulerConfig) *Scheduler {
	if config.MaxConcurrentTasks <= 0 {
		config.MaxConcurrentTasks = 10
	}
	if config.PreemptionThreshold <= 0 {
		config.PreemptionThreshold = 0.9
	}

	ctx, cancel := context.WithCancel(context.Background())

	s := &Scheduler{
		gpus:           make(map[string]*GPU),
		taskQueue:      make(TaskQueue, 0),
		runningTasks:   make(map[string]*Task),
		completedTasks: make(map[string]*Task),
		config:         config,
		ctx:            ctx,
		cancel:         cancel,
		notifyChan:     make(chan struct{}, 100),
	}

	heap.Init(&s.taskQueue)
	go s.scheduleLoop()

	return s
}

func (s *Scheduler) AddGPU(gpu *GPU) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if gpu.ID == "" {
		gpu.ID = uuid.New().String()
	}

	if _, exists := s.gpus[gpu.ID]; exists {
		return fmt.Errorf("gpu %s already exists", gpu.ID)
	}

	s.gpus[gpu.ID] = gpu
	s.notify()
	return nil
}

func (s *Scheduler) RemoveGPU(gpuID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.gpus[gpuID]; !exists {
		return fmt.Errorf("gpu %s not found", gpuID)
	}

	delete(s.gpus, gpuID)
	return nil
}

func (s *Scheduler) GetGPU(gpuID string) (*GPU, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	gpu, ok := s.gpus[gpuID]
	return gpu, ok
}

func (s *Scheduler) ListGPUs() []*GPU {
	s.mu.Lock()
	defer s.mu.Unlock()

	gpus := make([]*GPU, 0, len(s.gpus))
	for _, g := range s.gpus {
		gpus = append(gpus, g)
	}

	sort.Slice(gpus, func(i, j int) bool {
		return gpus[i].AvailableMemory() > gpus[j].AvailableMemory()
	})

	return gpus
}

func (g *GPU) AvailableMemory() int64 {
	return g.TotalMemory - g.UsedMemory
}

func (s *Scheduler) SubmitTask(task *Task) (string, error) {
	if task.Name == "" {
		return "", errors.New("task name is required")
	}
	if task.RequiredMemory <= 0 {
		return "", errors.New("required memory must be positive")
	}
	if task.RequiredGPU <= 0 {
		task.RequiredGPU = 1
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	task.ID = uuid.New().String()
	task.Status = TaskStatusQueued
	task.QueueTime = time.Now()

	heap.Push(&s.taskQueue, task)
	s.notify()

	return task.ID, nil
}

func (s *Scheduler) CancelTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, running := s.runningTasks[taskID]
	if running {
		task.Status = TaskStatusCancelled
		delete(s.runningTasks, taskID)
		s.completedTasks[taskID] = task
		s.releaseGPUs(task.AssignedGPU)
		return nil
	}

	for i, t := range s.taskQueue {
		if t.ID == taskID {
			t.Status = TaskStatusCancelled
			heap.Remove(&s.taskQueue, i)
			return nil
		}
	}

	return fmt.Errorf("task %s not found", taskID)
}

func (s *Scheduler) GetTaskStatus(taskID string) (TaskStatus, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if task, ok := s.runningTasks[taskID]; ok {
		return task.Status, true
	}

	if task, ok := s.completedTasks[taskID]; ok {
		return task.Status, true
	}

	for _, t := range s.taskQueue {
		if t.ID == taskID {
			return t.Status, true
		}
	}

	return "", false
}

func (s *Scheduler) GetTask(taskID string) (*Task, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if task, ok := s.runningTasks[taskID]; ok {
		return task, true
	}

	if task, ok := s.completedTasks[taskID]; ok {
		return task, true
	}

	for _, t := range s.taskQueue {
		if t.ID == taskID {
			return t, true
		}
	}

	return nil, false
}

func (s *Scheduler) scheduleLoop() {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-s.ctx.Done():
			return
		case <-s.notifyChan:
			s.trySchedule()
		case <-ticker.C:
			s.trySchedule()
		}
	}
}

func (s *Scheduler) trySchedule() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.taskQueue.Len() == 0 {
		return
	}

	if len(s.runningTasks) >= s.config.MaxConcurrentTasks {
		return
	}

	totalGPUMemory := int64(0)
	availableGPUMemory := int64(0)
	for _, gpu := range s.gpus {
		totalGPUMemory += gpu.TotalMemory
		availableGPUMemory += gpu.AvailableMemory()
	}

	utilization := float64(totalGPUMemory-availableGPUMemory) / float64(totalGPUMemory)

	task := s.taskQueue[0]
	availableGPUs := s.findAvailableGPUs(task.RequiredMemory, task.RequiredGPU)

	if len(availableGPUs) >= task.RequiredGPU {
		heap.Pop(&s.taskQueue)
		s.runTask(task, availableGPUs[:task.RequiredGPU])
		return
	}

	if s.config.EnablePreemption && utilization > s.config.PreemptionThreshold {
		preemptedTask := s.findPreemptableTask(task.Priority)
		if preemptedTask != nil {
			s.preemptTask(preemptedTask)
		}
	}
}

func (s *Scheduler) findAvailableGPUs(requiredMemory int64, count int) []*GPU {
	var available []*GPU

	for _, gpu := range s.gpus {
		if gpu.Status == "available" && gpu.AvailableMemory() >= requiredMemory && gpu.CurrentTask == nil {
			available = append(available, gpu)
		}
	}

	sort.Slice(available, func(i, j int) bool {
		return available[i].AvailableMemory() < available[j].AvailableMemory()
	})

	return available
}

func (s *Scheduler) findPreemptableTask(newPriority Priority) *Task {
	var lowestPriorityTask *Task

	for _, task := range s.runningTasks {
		if task.Priority < newPriority && task.PreemptCount < task.MaxPreemptions {
			if lowestPriorityTask == nil || task.Priority < lowestPriorityTask.Priority {
				lowestPriorityTask = task
			}
		}
	}

	return lowestPriorityTask
}

func (s *Scheduler) preemptTask(task *Task) {
	task.Status = TaskStatusPreempted
	task.PreemptCount++
	task.EndTime = nil

	s.releaseGPUs(task.AssignedGPU)
	delete(s.runningTasks, task.ID)

	task.QueueTime = time.Now()
	task.Status = TaskStatusQueued
	heap.Push(&s.taskQueue, task)
}

func (s *Scheduler) runTask(task *Task, gpus []*GPU) {
	now := time.Now()
	task.Status = TaskStatusRunning
	task.StartTime = &now

	for _, gpu := range gpus {
		gpu.UsedMemory += task.RequiredMemory
		gpu.CurrentTask = &task.ID
		task.AssignedGPU = append(task.AssignedGPU, gpu.ID)
	}

	s.runningTasks[task.ID] = task

	go s.executeTask(task)
}

func (s *Scheduler) executeTask(task *Task) {
	defer func() {
		if r := recover(); r != nil {
			s.completeTask(task, fmt.Errorf("panic: %v", r))
		}
	}()

	ctx := s.ctx
	if task.TimeoutSeconds > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(s.ctx, time.Duration(task.TimeoutSeconds)*time.Second)
		defer cancel()
	}

	assignedGPUs := make([]*GPU, 0, len(task.AssignedGPU))
	for _, id := range task.AssignedGPU {
		if gpu, ok := s.gpus[id]; ok {
			assignedGPUs = append(assignedGPUs, gpu)
		}
	}

	var err error
	if task.RunFunc != nil {
		err = task.RunFunc(ctx, assignedGPUs)
	}

	s.completeTask(task, err)
}

func (s *Scheduler) completeTask(task *Task, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now()
	task.EndTime = &now

	if err != nil {
		task.Status = TaskStatusFailed
		task.Error = err
	} else {
		task.Status = TaskStatusCompleted
	}

	s.releaseGPUs(task.AssignedGPU)

	delete(s.runningTasks, task.ID)
	s.completedTasks[task.ID] = task

	s.notify()
}

func (s *Scheduler) releaseGPUs(gpuIDs []string) {
	for _, id := range gpuIDs {
		if gpu, ok := s.gpus[id]; ok {
			if gpu.CurrentTask != nil {
				var task *Task
				if t, exists := s.runningTasks[*gpu.CurrentTask]; exists {
					task = t
				}
				if task != nil {
					gpu.UsedMemory -= task.RequiredMemory
				} else {
					gpu.UsedMemory = 0
				}
			}
			gpu.CurrentTask = nil
		}
	}
}

func (s *Scheduler) notify() {
	select {
	case s.notifyChan <- struct{}{}:
	default:
	}
}

func (s *Scheduler) GetQueueStats() map[string]interface{} {
	s.mu.Lock()
	defer s.mu.Unlock()

	queuedByPriority := make(map[string]int)
	for _, t := range s.taskQueue {
		key := fmt.Sprintf("priority_%d", t.Priority)
		queuedByPriority[key]++
	}

	return map[string]interface{}{
		"queued":          len(s.taskQueue),
		"running":         len(s.runningTasks),
		"completed":       len(s.completedTasks),
		"queued_by_priority": queuedByPriority,
	}
}

func (s *Scheduler) GetGPUStats() map[string]interface{} {
	s.mu.Lock()
	defer s.mu.Unlock()

	totalMemory := int64(0)
	usedMemory := int64(0)
	availableCount := 0

	for _, gpu := range s.gpus {
		totalMemory += gpu.TotalMemory
		usedMemory += gpu.UsedMemory
		if gpu.CurrentTask == nil {
			availableCount++
		}
	}

	return map[string]interface{}{
		"total_gpus":      len(s.gpus),
		"available_gpus":  availableCount,
		"total_memory":    totalMemory,
		"used_memory":     usedMemory,
		"free_memory":     totalMemory - usedMemory,
		"utilization_pct": float64(usedMemory) / float64(totalMemory) * 100,
	}
}

func (s *Scheduler) Stop() {
	s.cancel()
}

func (s *Scheduler) ListQueuedTasks() []*Task {
	s.mu.Lock()
	defer s.mu.Unlock()

	tasks := make([]*Task, len(s.taskQueue))
	copy(tasks, s.taskQueue)
	return tasks
}

func (s *Scheduler) ListRunningTasks() []*Task {
	s.mu.Lock()
	defer s.mu.Unlock()

	tasks := make([]*Task, 0, len(s.runningTasks))
	for _, t := range s.runningTasks {
		tasks = append(tasks, t)
	}
	return tasks
}

func (s *Scheduler) ListCompletedTasks(limit int) []*Task {
	s.mu.Lock()
	defer s.mu.Unlock()

	tasks := make([]*Task, 0, len(s.completedTasks))
	for _, t := range s.completedTasks {
		tasks = append(tasks, t)
	}

	sort.Slice(tasks, func(i, j int) bool {
		if tasks[i].EndTime == nil {
			return false
		}
		if tasks[j].EndTime == nil {
			return true
		}
		return tasks[i].EndTime.After(*tasks[j].EndTime)
	})

	if limit > 0 && limit < len(tasks) {
		tasks = tasks[:limit]
	}

	return tasks
}

func (s *Scheduler) UpdateGPU(gpuID string, utilization float64) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	gpu, ok := s.gpus[gpuID]
	if !ok {
		return fmt.Errorf("gpu %s not found", gpuID)
	}

	gpu.Utilization = utilization
	return nil
}

func (t *Task) Duration() time.Duration {
	if t.StartTime == nil {
		return 0
	}
	end := t.EndTime
	if end == nil {
		now := time.Now()
		end = &now
	}
	return end.Sub(*t.StartTime)
}

func (t *Task) IsTimeout() bool {
	if t.TimeoutSeconds <= 0 || t.StartTime == nil {
		return false
	}
	return time.Since(*t.StartTime) > time.Duration(t.TimeoutSeconds)*time.Second
}
