package scheduler

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
	"go.uber.org/zap"
)

type TaskScheduler struct {
	mu                 sync.RWMutex
	config             SchedulerConfig
	queue              *PriorityQueue
	sharder            *Sharder
	workers            map[int64]*scheduledWorker
	tasks              map[int64]*scheduledTask
	trackers           map[int64]*TaskTracker
	assignments        map[int64]int64
	callbacks          []SchedulerEventCallback
	ctx                context.Context
	cancel             context.CancelFunc
	wg                 sync.WaitGroup
	workerLoadHistory  map[int64][]WorkerLoad
	isRunning          bool
	logger             *zap.Logger
}

func DefaultSchedulerConfig() SchedulerConfig {
	return SchedulerConfig{
		HeartbeatTimeout:       30 * time.Second,
		DefaultTaskTimeout:     10 * time.Minute,
		DefaultMaxRetries:      3,
		AssignmentStrategy:     AssignmentBestFit,
		CheckpointInterval:     5 * time.Minute,
		WorkerOfflineThreshold: 60 * time.Second,
		MaxConcurrentTasks:     1000,
	}
}

func NewTaskScheduler(config SchedulerConfig) *TaskScheduler {
	if config.HeartbeatTimeout <= 0 {
		config.HeartbeatTimeout = DefaultSchedulerConfig().HeartbeatTimeout
	}
	if config.DefaultTaskTimeout <= 0 {
		config.DefaultTaskTimeout = DefaultSchedulerConfig().DefaultTaskTimeout
	}
	if config.DefaultMaxRetries <= 0 {
		config.DefaultMaxRetries = DefaultSchedulerConfig().DefaultMaxRetries
	}
	if config.AssignmentStrategy == "" {
		config.AssignmentStrategy = DefaultSchedulerConfig().AssignmentStrategy
	}
	if config.CheckpointInterval <= 0 {
		config.CheckpointInterval = DefaultSchedulerConfig().CheckpointInterval
	}
	if config.WorkerOfflineThreshold <= 0 {
		config.WorkerOfflineThreshold = DefaultSchedulerConfig().WorkerOfflineThreshold
	}
	if config.MaxConcurrentTasks <= 0 {
		config.MaxConcurrentTasks = DefaultSchedulerConfig().MaxConcurrentTasks
	}

	logger, _ := zap.NewProduction()

	return &TaskScheduler{
		config:            config,
		queue:             NewPriorityQueue(),
		sharder:           NewSharder(),
		workers:           make(map[int64]*scheduledWorker),
		tasks:             make(map[int64]*scheduledTask),
		trackers:          make(map[int64]*TaskTracker),
		assignments:       make(map[int64]int64),
		workerLoadHistory: make(map[int64][]WorkerLoad),
		logger:            logger,
	}
}

func (s *TaskScheduler) Start(ctx context.Context) error {
	s.mu.Lock()
	if s.isRunning {
		s.mu.Unlock()
		return fmt.Errorf("scheduler is already running")
	}

	s.ctx, s.cancel = context.WithCancel(ctx)
	s.isRunning = true
	s.mu.Unlock()

	s.wg.Add(3)
	go s.workerMonitorLoop()
	go s.taskDispatcherLoop()
	go s.taskTimeoutMonitorLoop()

	return nil
}

func (s *TaskScheduler) Stop() error {
	s.mu.Lock()
	if !s.isRunning {
		s.mu.Unlock()
		return fmt.Errorf("scheduler is not running")
	}

	s.isRunning = false
	s.mu.Unlock()

	if s.cancel != nil {
		s.cancel()
	}

	s.wg.Wait()

	s.mu.Lock()
	for _, tracker := range s.trackers {
		tracker.Stop()
	}
	s.mu.Unlock()

	return nil
}

func (s *TaskScheduler) RegisterWorker(worker *models.Worker) error {
	if worker == nil {
		return fmt.Errorf("worker cannot be nil")
	}
	if worker.ID <= 0 {
		worker.ID = util.GenerateID()
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.workers[worker.ID]; exists {
		return fmt.Errorf("worker %d already registered", worker.ID)
	}

	now := time.Now()
	sw := &scheduledWorker{
		worker:       worker,
		lastSeen:     now,
		capabilities: make(map[string]bool),
		load: WorkerLoad{
			WorkerID:     worker.ID,
			CurrentTasks: 0,
			TotalMemory:  worker.MemoryGB,
		},
	}

	worker.Status = models.WorkerStatusIdle
	worker.LastHeartbeatAt = &now
	worker.HeartbeatCount = 1

	s.workers[worker.ID] = sw
	s.emitEvent(EventWorkerRegistered, worker)

	return nil
}

func (s *TaskScheduler) UnregisterWorker(workerID int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	sw, exists := s.workers[workerID]
	if !exists {
		return fmt.Errorf("worker %d not found", workerID)
	}

	if sw.currentTask != nil {
		taskID := *sw.currentTask
		if err := s.reassignTask(taskID); err != nil {
			s.logger.Error("failed to reassign task", zap.Int64("taskID", taskID), zap.Error(err))
		}
	}

	sw.worker.Status = models.WorkerStatusOffline
	delete(s.workers, workerID)
	s.emitEvent(EventWorkerOffline, workerID)

	return nil
}

func (s *TaskScheduler) SubmitTask(ctx context.Context, task *models.Task) error {
	if task == nil {
		return fmt.Errorf("task cannot be nil")
	}
	if task.ID <= 0 {
		task.ID = util.GenerateID()
	}

	s.mu.Lock()
	if len(s.tasks) >= s.config.MaxConcurrentTasks {
		s.mu.Unlock()
		return fmt.Errorf("max concurrent tasks limit reached (%d)", s.config.MaxConcurrentTasks)
	}

	if _, exists := s.tasks[task.ID]; exists {
		s.mu.Unlock()
		return fmt.Errorf("task %d already submitted", task.ID)
	}

	if task.TimeoutSeconds <= 0 {
		task.TimeoutSeconds = int(s.config.DefaultTaskTimeout.Seconds())
	}
	if task.MaxRetries <= 0 {
		task.MaxRetries = s.config.DefaultMaxRetries
	}
	if task.Status == "" {
		task.Status = models.TaskStatusPending
	}

	st := &scheduledTask{
		task: task,
		progress: TaskProgress{
			TaskID:     task.ID,
			Status:     task.Status,
			RetryCount: task.RetryCount,
		},
		checkpoints: make([]TaskCheckpoint, 0),
	}

	s.tasks[task.ID] = st
	s.mu.Unlock()

	tracker := NewTaskTracker(task, TrackerConfig{
		MaxRetries:     task.MaxRetries,
		Timeout:        time.Duration(task.TimeoutSeconds) * time.Second,
		MaxCheckpoints: 100,
		OnTimeout:      s.handleTaskTimeout,
		OnRetry:        s.handleTaskRetry,
		OnComplete:     s.handleTaskComplete,
		OnFail:         s.handleTaskFail,
	})

	s.mu.Lock()
	s.trackers[task.ID] = tracker
	st.tracker = tracker
	s.mu.Unlock()

	var deadline *time.Time
	if task.TimeoutSeconds > 0 {
		d := time.Now().Add(time.Duration(task.TimeoutSeconds) * time.Second)
		deadline = &d
	}

	task.Status = models.TaskStatusQueued
	_, err := s.queue.Enqueue(task, deadline)
	if err != nil {
		s.mu.Lock()
		delete(s.tasks, task.ID)
		delete(s.trackers, task.ID)
		s.mu.Unlock()
		return fmt.Errorf("failed to enqueue task: %w", err)
	}

	s.emitEvent(EventTaskAssigned, task)
	return nil
}

func (s *TaskScheduler) CancelTask(taskID int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	st, exists := s.tasks[taskID]
	if !exists {
		return fmt.Errorf("task %d not found", taskID)
	}

	if s.queue.Contains(taskID) {
		s.queue.Cancel(taskID)
	}

	if tracker, ok := s.trackers[taskID]; ok {
		tracker.Cancel()
	}

	st.task.Status = models.TaskStatusCanceled
	st.progress.Status = models.TaskStatusCanceled

	if workerID, assigned := s.assignments[taskID]; assigned {
		if sw, wok := s.workers[workerID]; wok {
			sw.mu.Lock()
			sw.currentTask = nil
			sw.load.CurrentTasks = util.Max(0, sw.load.CurrentTasks-1)
			sw.mu.Unlock()
		}
		delete(s.assignments, taskID)
	}

	s.emitEvent(EventTaskCanceled, taskID)
	return nil
}

func (s *TaskScheduler) GetTaskProgress(taskID int64) (*TaskProgress, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	st, exists := s.tasks[taskID]
	if !exists {
		return nil, fmt.Errorf("task %d not found", taskID)
	}

	st.mu.RLock()
	defer st.mu.RUnlock()

	progress := st.progress
	if tracker, ok := s.trackers[taskID]; ok {
		progress = tracker.GetProgress()
	}

	return &progress, nil
}

func (s *TaskScheduler) GetWorkerLoad(workerID int64) (*WorkerLoad, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	sw, exists := s.workers[workerID]
	if !exists {
		return nil, fmt.Errorf("worker %d not found", workerID)
	}

	sw.mu.RLock()
	defer sw.mu.RUnlock()

	load := sw.load
	return &load, nil
}

func (s *TaskScheduler) Heartbeat(workerID int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	sw, exists := s.workers[workerID]
	if !exists {
		return fmt.Errorf("worker %d not found", workerID)
	}

	now := time.Now()
	sw.mu.Lock()
	sw.lastSeen = now
	sw.worker.LastHeartbeatAt = &now
	sw.worker.HeartbeatCount++

	if sw.worker.Status == models.WorkerStatusOffline {
		sw.worker.Status = models.WorkerStatusIdle
		s.emitEvent(EventWorkerReconnected, workerID)
	}
	sw.mu.Unlock()

	return nil
}

func (s *TaskScheduler) ReportTaskProgress(taskID int64, step int64, totalSteps int64, data models.Params) error {
	s.mu.RLock()
	tracker, exists := s.trackers[taskID]
	s.mu.RUnlock()

	if !exists {
		return fmt.Errorf("task %d not found", taskID)
	}

	if err := tracker.ReportProgress(step, totalSteps, data); err != nil {
		return err
	}

	s.mu.Lock()
	if st, ok := s.tasks[taskID]; ok {
		st.mu.Lock()
		st.progress = tracker.GetProgress()
		st.mu.Unlock()
	}
	s.mu.Unlock()

	return nil
}

func (s *TaskScheduler) CompleteTask(taskID int64, result *models.Result) error {
	s.mu.RLock()
	tracker, exists := s.trackers[taskID]
	s.mu.RUnlock()

	if !exists {
		return fmt.Errorf("task %d not found", taskID)
	}

	if err := tracker.Complete(result); err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if st, ok := s.tasks[taskID]; ok {
		st.mu.Lock()
		st.task.Status = models.TaskStatusCompleted
		st.progress = tracker.GetProgress()
		st.mu.Unlock()
	}

	if workerID, assigned := s.assignments[taskID]; assigned {
		if sw, wok := s.workers[workerID]; wok {
			sw.mu.Lock()
			sw.currentTask = nil
			sw.load.CurrentTasks = util.Max(0, sw.load.CurrentTasks-1)
			sw.worker.TasksCompleted++
			if sw.load.CurrentTasks == 0 {
				sw.worker.Status = models.WorkerStatusIdle
			}
			sw.mu.Unlock()
		}
		delete(s.assignments, taskID)
	}

	s.emitEvent(EventTaskCompleted, taskID)
	return nil
}

func (s *TaskScheduler) FailTask(taskID int64, err string) error {
	s.mu.RLock()
	tracker, exists := s.trackers[taskID]
	s.mu.RUnlock()

	if !exists {
		return fmt.Errorf("task %d not found", taskID)
	}

	trackerErr := tracker.Fail(err)

	s.mu.Lock()
	defer s.mu.Unlock()

	if st, ok := s.tasks[taskID]; ok {
		st.mu.Lock()
		st.task.ErrorMessage = err
		st.progress = tracker.GetProgress()
		st.mu.Unlock()
	}

	if workerID, assigned := s.assignments[taskID]; assigned {
		if sw, wok := s.workers[workerID]; wok {
			sw.mu.Lock()
			sw.currentTask = nil
			sw.load.CurrentTasks = util.Max(0, sw.load.CurrentTasks-1)
			sw.worker.TasksFailed++
			if sw.load.CurrentTasks == 0 {
				sw.worker.Status = models.WorkerStatusIdle
			}
			sw.mu.Unlock()
		}
		delete(s.assignments, taskID)
	}

	if trackerErr != nil {
		s.emitEvent(EventTaskFailed, taskID)
		return trackerErr
	}

	return nil
}

func (s *TaskScheduler) SaveCheckpoint(taskID int64, step int64, data models.Params, checksum string, filePath string) error {
	s.mu.RLock()
	tracker, exists := s.trackers[taskID]
	s.mu.RUnlock()

	if !exists {
		return fmt.Errorf("task %d not found", taskID)
	}

	cp, err := tracker.SaveCheckpoint(step, data, checksum, filePath)
	if err != nil {
		return err
	}

	s.mu.Lock()
	if st, ok := s.tasks[taskID]; ok {
		st.mu.Lock()
		st.checkpoints = append(st.checkpoints, *cp)
		st.progress.CheckpointCount = len(st.checkpoints)
		st.mu.Unlock()
	}
	s.mu.Unlock()

	s.emitEvent(EventCheckpointSaved, cp)
	return nil
}

func (s *TaskScheduler) RestoreCheckpoint(taskID int64) (*TaskCheckpoint, error) {
	s.mu.RLock()
	tracker, exists := s.trackers[taskID]
	s.mu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("task %d not found", taskID)
	}

	return tracker.RestoreCheckpoint()
}

func (s *TaskScheduler) OnEvent(callback SchedulerEventCallback) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.callbacks = append(s.callbacks, callback)
}

func (s *TaskScheduler) emitEvent(event SchedulerEvent, payload interface{}) {
	s.mu.RLock()
	callbacks := make([]SchedulerEventCallback, len(s.callbacks))
	copy(callbacks, s.callbacks)
	s.mu.RUnlock()

	for _, cb := range callbacks {
		go cb(event, payload)
	}
}

func (s *TaskScheduler) workerMonitorLoop() {
	defer s.wg.Done()

	ticker := time.NewTicker(s.config.HeartbeatTimeout / 2)
	defer ticker.Stop()

	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			s.checkWorkerHealth()
		}
	}
}

func (s *TaskScheduler) checkWorkerHealth() {
	s.mu.RLock()
	workerIDs := make([]int64, 0, len(s.workers))
	for id := range s.workers {
		workerIDs = append(workerIDs, id)
	}
	s.mu.RUnlock()

	now := time.Now()
	for _, workerID := range workerIDs {
		s.mu.RLock()
		sw, exists := s.workers[workerID]
		s.mu.RUnlock()

		if !exists {
			continue
		}

		sw.mu.RLock()
		lastSeen := sw.lastSeen
		status := sw.worker.Status
		sw.mu.RUnlock()

		offlineDuration := now.Sub(lastSeen)
		if offlineDuration > s.config.WorkerOfflineThreshold && status != models.WorkerStatusOffline {
			s.handleWorkerOffline(workerID)
		}
	}
}

func (s *TaskScheduler) handleWorkerOffline(workerID int64) {
	s.mu.Lock()
	sw, exists := s.workers[workerID]
	if !exists {
		s.mu.Unlock()
		return
	}

	sw.mu.Lock()
	sw.worker.Status = models.WorkerStatusOffline
	currentTask := sw.currentTask
	sw.mu.Unlock()

	var taskToReassign int64
	if currentTask != nil {
		taskToReassign = *currentTask
	}
	s.mu.Unlock()

	s.emitEvent(EventWorkerOffline, workerID)

	if taskToReassign > 0 {
		if err := s.reassignTask(taskToReassign); err != nil {
			s.logger.Error("failed to reassign task after worker offline",
				zap.Int64("taskID", taskToReassign),
				zap.Int64("workerID", workerID),
				zap.Error(err))
		}
	}
}

func (s *TaskScheduler) reassignTask(taskID int64) error {
	s.mu.Lock()
	st, exists := s.tasks[taskID]
	if !exists {
		s.mu.Unlock()
		return fmt.Errorf("task %d not found", taskID)
	}

	if workerID, assigned := s.assignments[taskID]; assigned {
		if sw, wok := s.workers[workerID]; wok {
			sw.mu.Lock()
			sw.currentTask = nil
			sw.load.CurrentTasks = util.Max(0, sw.load.CurrentTasks-1)
			sw.mu.Unlock()
		}
		delete(s.assignments, taskID)
	}

	st.mu.Lock()
	st.task.Status = models.TaskStatusQueued
	st.progress.Status = models.TaskStatusQueued
	st.mu.Unlock()

	if tracker, ok := s.trackers[taskID]; ok {
		tracker.Stop()
	}

	s.mu.Unlock()

	var deadline *time.Time
	if st.task.TimeoutSeconds > 0 {
		d := time.Now().Add(time.Duration(st.task.TimeoutSeconds) * time.Second)
		deadline = &d
	}

	_, err := s.queue.Enqueue(st.task, deadline)
	return err
}

func (s *TaskScheduler) taskDispatcherLoop() {
	defer s.wg.Done()

	for {
		select {
		case <-s.ctx.Done():
			return
		default:
			qt, err := s.queue.TryDequeue()
			if err != nil {
				s.logger.Error("error dequeuing task", zap.Error(err))
				time.Sleep(100 * time.Millisecond)
				continue
			}

			if qt == nil {
				time.Sleep(100 * time.Millisecond)
				continue
			}

			if err := s.assignTask(qt.Task); err != nil {
				s.logger.Error("failed to assign task",
					zap.Int64("taskID", qt.Task.ID),
					zap.Error(err))

				if qt.Task.RetryCount < qt.Task.MaxRetries {
					qt.Task.RetryCount++
					var deadline *time.Time
					if qt.Task.TimeoutSeconds > 0 {
						d := time.Now().Add(time.Duration(qt.Task.TimeoutSeconds) * time.Second)
						deadline = &d
					}
					_, _ = s.queue.Enqueue(qt.Task, deadline)
				}
			}
		}
	}
}

func (s *TaskScheduler) assignTask(task *models.Task) error {
	s.mu.RLock()
	workers := make([]*scheduledWorker, 0, len(s.workers))
	for _, sw := range s.workers {
		sw.mu.RLock()
		if sw.worker.Status == models.WorkerStatusIdle || sw.load.CurrentTasks < sw.worker.CPUCores {
			workers = append(workers, sw)
		}
		sw.mu.RUnlock()
	}
	s.mu.RUnlock()

	if len(workers) == 0 {
		return fmt.Errorf("no available workers")
	}

	selectedWorker := s.selectWorker(workers, task)
	if selectedWorker == nil {
		return fmt.Errorf("no suitable worker found")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.assignments[task.ID]; exists {
		return fmt.Errorf("task %d already assigned", task.ID)
	}

	selectedWorker.mu.Lock()
	selectedWorker.currentTask = &task.ID
	selectedWorker.load.CurrentTasks++
	if selectedWorker.load.CurrentTasks > 0 {
		selectedWorker.worker.Status = models.WorkerStatusRunning
	}
	selectedWorker.worker.CurrentTaskID = &task.ID
	selectedWorker.load.Score = s.calculateWorkerScore(selectedWorker)

	if history, ok := s.workerLoadHistory[selectedWorker.worker.ID]; ok {
		s.workerLoadHistory[selectedWorker.worker.ID] = append(history, selectedWorker.load)
		if len(s.workerLoadHistory[selectedWorker.worker.ID]) > 100 {
			s.workerLoadHistory[selectedWorker.worker.ID] = s.workerLoadHistory[selectedWorker.worker.ID][1:]
		}
	} else {
		s.workerLoadHistory[selectedWorker.worker.ID] = []WorkerLoad{selectedWorker.load}
	}
	selectedWorker.mu.Unlock()

	s.assignments[task.ID] = selectedWorker.worker.ID

	if st, ok := s.tasks[task.ID]; ok {
		st.mu.Lock()
		st.task.Status = models.TaskStatusRunning
		st.task.WorkerID = &selectedWorker.worker.ID
		st.task.StartTime = nil
		now := time.Now()
		st.task.StartTime = &now
		st.progress.Status = models.TaskStatusRunning
		st.progress.WorkerID = &selectedWorker.worker.ID
		st.progress.StartTime = now
		st.progress.LastUpdate = now
		st.mu.Unlock()
	}

	if tracker, ok := s.trackers[task.ID]; ok {
		_ = tracker.Start(s.ctx, selectedWorker.worker.ID)
	}

	s.emitEvent(EventTaskStarted, task)
	return nil
}

func (s *TaskScheduler) selectWorker(workers []*scheduledWorker, task *models.Task) *scheduledWorker {
	if len(workers) == 0 {
		return nil
	}

	switch s.config.AssignmentStrategy {
	case AssignmentLoadBalance:
		return s.selectByLoadBalance(workers)
	case AssignmentCapability:
		return s.selectByCapability(workers, task)
	case AssignmentLocality:
		return s.selectByLocality(workers, task)
	case AssignmentBestFit:
		return s.selectByBestFit(workers, task)
	default:
		return s.selectByBestFit(workers, task)
	}
}

func (s *TaskScheduler) selectByLoadBalance(workers []*scheduledWorker) *scheduledWorker {
	var best *scheduledWorker
	minLoad := int(^uint(0) >> 1)

	for _, sw := range workers {
		sw.mu.RLock()
		load := sw.load.CurrentTasks
		sw.mu.RUnlock()

		if load < minLoad {
			minLoad = load
			best = sw
		}
	}

	return best
}

func (s *TaskScheduler) selectByCapability(workers []*scheduledWorker, task *models.Task) *scheduledWorker {
	var best *scheduledWorker
	var bestScore float64

	requiredMemory := 1
	if mem, ok := task.Params["required_memory_gb"]; ok {
		if m, ok := mem.(int); ok {
			requiredMemory = m
		}
	}

	for _, sw := range workers {
		sw.mu.RLock()
		availableMemory := sw.worker.MemoryGB - sw.load.TotalMemory
		cpuCores := sw.worker.CPUCores
		load := sw.load.CurrentTasks
		sw.mu.RUnlock()

		if availableMemory < requiredMemory {
			continue
		}

		score := float64(cpuCores) / (float64(load + 1))
		if score > bestScore {
			bestScore = score
			best = sw
		}
	}

	if best == nil {
		return s.selectByLoadBalance(workers)
	}

	return best
}

func (s *TaskScheduler) selectByLocality(workers []*scheduledWorker, task *models.Task) *scheduledWorker {
	taskLocation := ""
	if loc, ok := task.Params["location"]; ok {
		if l, ok := loc.(string); ok {
			taskLocation = l
		}
	}

	if taskLocation == "" {
		return s.selectByBestFit(workers, task)
	}

	for _, sw := range workers {
		sw.mu.RLock()
		workerLocation := sw.location
		load := sw.load.CurrentTasks
		sw.mu.RUnlock()

		if workerLocation == taskLocation && load == 0 {
			return sw
		}
	}

	return s.selectByBestFit(workers, task)
}

func (s *TaskScheduler) selectByBestFit(workers []*scheduledWorker, task *models.Task) *scheduledWorker {
	var best *scheduledWorker
	var bestScore float64 = -1

	for _, sw := range workers {
		score := s.calculateAssignmentScore(sw, task)
		if score > bestScore {
			bestScore = score
			best = sw
		}
	}

	return best
}

func (s *TaskScheduler) calculateAssignmentScore(sw *scheduledWorker, task *models.Task) float64 {
	sw.mu.RLock()
	defer sw.mu.RUnlock()

	loadFactor := 1.0 / (float64(sw.load.CurrentTasks) + 1)
	cpuFactor := float64(sw.worker.CPUCores) / 16.0
	memoryFactor := float64(sw.worker.MemoryGB) / 64.0

	reliabilityFactor := 1.0
	if sw.worker.TasksCompleted+sw.worker.TasksFailed > 0 {
		reliabilityFactor = float64(sw.worker.TasksCompleted) / float64(sw.worker.TasksCompleted+sw.worker.TasksFailed)
	}

	score := loadFactor*0.4 + cpuFactor*0.25 + memoryFactor*0.2 + reliabilityFactor*0.15

	sw.load.Score = score
	return score
}

func (s *TaskScheduler) calculateWorkerScore(sw *scheduledWorker) float64 {
	return s.calculateAssignmentScore(sw, &models.Task{})
}

func (s *TaskScheduler) taskTimeoutMonitorLoop() {
	defer s.wg.Done()

	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			s.checkTaskTimeouts()
		}
	}
}

func (s *TaskScheduler) checkTaskTimeouts() {
	s.mu.RLock()
	taskIDs := make([]int64, 0, len(s.tasks))
	for id := range s.tasks {
		taskIDs = append(taskIDs, id)
	}
	s.mu.RUnlock()

	for _, taskID := range taskIDs {
		s.mu.RLock()
		tracker, exists := s.trackers[taskID]
		s.mu.RUnlock()

		if !exists {
			continue
		}

		if tracker.IsTimedOut() && tracker.GetStatus() != models.TaskStatusTimeout {
			s.handleTaskTimeout(taskID)
		}
	}
}

func (s *TaskScheduler) handleTaskTimeout(taskID int64) {
	s.mu.Lock()
	st, exists := s.tasks[taskID]
	if !exists {
		s.mu.Unlock()
		return
	}

	st.mu.Lock()
	st.task.Status = models.TaskStatusTimeout
	st.progress.Status = models.TaskStatusTimeout
	st.mu.Unlock()

	if workerID, assigned := s.assignments[taskID]; assigned {
		if sw, wok := s.workers[workerID]; wok {
			sw.mu.Lock()
			sw.currentTask = nil
			sw.load.CurrentTasks = util.Max(0, sw.load.CurrentTasks-1)
			if sw.load.CurrentTasks == 0 {
				sw.worker.Status = models.WorkerStatusIdle
			}
			sw.mu.Unlock()
		}
		delete(s.assignments, taskID)
	}
	s.mu.Unlock()

	s.emitEvent(EventTaskTimeout, taskID)
}

func (s *TaskScheduler) handleTaskRetry(taskID int64, retryCount int) {
	s.mu.Lock()
	if st, ok := s.tasks[taskID]; ok {
		st.mu.Lock()
		st.task.RetryCount = retryCount
		st.progress.RetryCount = retryCount
		st.mu.Unlock()
	}
	s.mu.Unlock()
}

func (s *TaskScheduler) handleTaskComplete(taskID int64, result *models.Result) {
}

func (s *TaskScheduler) handleTaskFail(taskID int64, err string) {
}

func (s *TaskScheduler) GetWorkerCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.workers)
}

func (s *TaskScheduler) GetTaskCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.tasks)
}

func (s *TaskScheduler) GetQueueLength() int {
	return s.queue.Length()
}

func (s *TaskScheduler) ListWorkers() []*models.Worker {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]*models.Worker, 0, len(s.workers))
	for _, sw := range s.workers {
		sw.mu.RLock()
		result = append(result, sw.worker)
		sw.mu.RUnlock()
	}
	return result
}

func (s *TaskScheduler) ListTasks(status ...models.TaskStatus) []*models.Task {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]*models.Task, 0, len(s.tasks))
	for _, st := range s.tasks {
		st.mu.RLock()
		if len(status) == 0 {
			result = append(result, st.task)
		} else {
			for _, s := range status {
				if st.task.Status == s {
					result = append(result, st.task)
					break
				}
			}
		}
		st.mu.RUnlock()
	}
	return result
}

func (s *TaskScheduler) GetSharder() *Sharder {
	return s.sharder
}

func (s *TaskScheduler) GetConfig() SchedulerConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.config
}
