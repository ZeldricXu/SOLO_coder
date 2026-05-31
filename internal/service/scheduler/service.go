package scheduler

import (
	"container/heap"
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"gorm.io/gorm"

	"llmgateway/internal/domain/entity"
	"llmgateway/internal/infrastructure/config"
	"llmgateway/internal/infrastructure/database"
	"llmgateway/internal/infrastructure/logger"
	"llmgateway/pkg/utils"
)

type SchedulerMetrics struct {
	TasksSubmitted   int64     `json:"tasks_submitted"`
	TasksCompleted   int64     `json:"tasks_completed"`
	TasksFailed      int64     `json:"tasks_failed"`
	TasksPreempted   int64     `json:"tasks_preempted"`
	TasksCanceled    int64     `json:"tasks_canceled"`
	TotalWaitTime    int64     `json:"total_wait_time_ms"`
	TotalExecuteTime int64     `json:"total_execute_time_ms"`
	StartTime        time.Time `json:"start_time"`
}

type TaskEvent struct {
	ID        string                 `json:"id"`
	TaskID    string                 `json:"task_id"`
	EventType string                 `json:"event_type"`
	Timestamp time.Time              `json:"timestamp"`
	Details   map[string]interface{} `json:"details"`
}

type GPUHistorySample struct {
	Timestamp       time.Time         `json:"timestamp"`
	GPUID           string            `json:"gpu_id"`
	Utilization     float64           `json:"utilization"`
	MemoryUsedMB    int64             `json:"memory_used_mb"`
	MemoryTotalMB   int64             `json:"memory_total_mb"`
	RunningTasks    int               `json:"running_tasks"`
}

type Service struct {
	db                *gorm.DB
	gpuResources      map[string]*entity.GPUResource
	gpuResourcesMu    sync.RWMutex
	taskQueue         *PriorityQueue
	runningTasks      map[string]*entity.Task
	preemptionEnabled bool
	maxQueueSize      int
	mu                sync.Mutex
	taskCallbacks     map[string]func(*entity.Task)

	metrics           SchedulerMetrics
	metricsMu         sync.RWMutex
	events            []*TaskEvent
	eventsMu          sync.RWMutex
	maxEvents         int
	gpuHistory        map[string][]*GPUHistorySample
	gpuHistoryMu      sync.RWMutex
	maxHistorySamples int
	collectTicker     *time.Ticker
	stopCollect       chan struct{}
}

type PriorityQueue struct {
	tasks []*entity.Task
	mu    sync.Mutex
}

func (pq *PriorityQueue) Len() int { return len(pq.tasks) }

func (pq *PriorityQueue) Less(i, j int) bool {
	return pq.tasks[i].Priority > pq.tasks[j].Priority
}

func (pq *PriorityQueue) Swap(i, j int) {
	pq.tasks[i], pq.tasks[j] = pq.tasks[j], pq.tasks[i]
}

func (pq *PriorityQueue) Push(x interface{}) {
	task := x.(*entity.Task)
	pq.tasks = append(pq.tasks, task)
}

func (pq *PriorityQueue) Pop() interface{} {
	old := pq.tasks
	n := len(old)
	task := old[n-1]
	pq.tasks = old[0 : n-1]
	return task
}

func (pq *PriorityQueue) Peek() *entity.Task {
	if len(pq.tasks) == 0 {
		return nil
	}
	return pq.tasks[0]
}

func NewService(cfg *config.SchedulerConfig) *Service {
	s := &Service{
		db:                database.DB(),
		gpuResources:      make(map[string]*entity.GPUResource),
		taskQueue:         &PriorityQueue{},
		runningTasks:      make(map[string]*entity.Task),
		preemptionEnabled: cfg.PreemptionEnabled,
		maxQueueSize:      cfg.MaxQueueSize,
		taskCallbacks:     make(map[string]func(*entity.Task)),
		metrics: SchedulerMetrics{
			StartTime: time.Now(),
		},
		events:            make([]*TaskEvent, 0, 1000),
		maxEvents:         1000,
		gpuHistory:        make(map[string][]*GPUHistorySample),
		maxHistorySamples: 100,
		stopCollect:       make(chan struct{}),
	}

	for _, gpuCfg := range cfg.GPUResources {
		gpu := &entity.GPUResource{
			ID:                  gpuCfg.ID,
			Node:                gpuCfg.Node,
			GPUIndex:            0,
			TotalMemoryMB:       gpuCfg.TotalMemory,
			AvailableMemoryMB:   gpuCfg.AvailableMemory,
			TotalComputeUnits:   gpuCfg.TotalCompute,
			AvailableComputeUnits: gpuCfg.AvailableCompute,
			Status:              "available",
			Healthy:             true,
			CreatedAt:           utils.Now(),
			UpdatedAt:           utils.Now(),
		}
		s.gpuResources[gpuCfg.ID] = gpu
		s.gpuHistory[gpuCfg.ID] = make([]*GPUHistorySample, 0, s.maxHistorySamples)
	}

	heap.Init(s.taskQueue)
	go s.schedulerLoop()
	go s.collectMetricsLoop()

	return s
}

func (s *Service) Stop() {
	if s.stopCollect != nil {
		close(s.stopCollect)
		s.stopCollect = nil
	}
}

func (s *Service) collectMetricsLoop() {
	s.collectTicker = time.NewTicker(5 * time.Second)
	defer s.collectTicker.Stop()

	for {
		select {
		case <-s.collectTicker.C:
			s.collectGPUMetrics()
		case <-s.stopCollect:
			return
		}
	}
}

func (s *Service) collectGPUMetrics() {
	s.gpuResourcesMu.RLock()
	defer s.gpuResourcesMu.RUnlock()

	s.gpuHistoryMu.Lock()
	defer s.gpuHistoryMu.Unlock()

	now := time.Now()
	runningCount := len(s.runningTasks)

	for gpuID, gpu := range s.gpuResources {
		sample := &GPUHistorySample{
			Timestamp:     now,
			GPUID:         gpuID,
			Utilization:   gpu.Utilization,
			MemoryUsedMB:  gpu.TotalMemoryMB - gpu.AvailableMemoryMB,
			MemoryTotalMB: gpu.TotalMemoryMB,
			RunningTasks:  runningCount,
		}

		history := s.gpuHistory[gpuID]
		if len(history) >= s.maxHistorySamples {
			history = history[1:]
		}
		s.gpuHistory[gpuID] = append(history, sample)
	}
}

func (s *Service) recordEvent(taskID, eventType string, details map[string]interface{}) {
	s.eventsMu.Lock()
	defer s.eventsMu.Unlock()

	event := &TaskEvent{
		ID:        utils.GenerateID("evt"),
		TaskID:    taskID,
		EventType: eventType,
		Timestamp: utils.Now(),
		Details:   details,
	}

	if len(s.events) >= s.maxEvents {
		s.events = s.events[1:]
	}
	s.events = append(s.events, event)
}

func (s *Service) GetMetrics() map[string]interface{} {
	s.metricsMu.RLock()
	defer s.metricsMu.RUnlock()

	uptime := time.Since(s.metrics.StartTime).Seconds()
	avgWaitTime := 0.0
	avgExecuteTime := 0.0
	throughput := 0.0

	if s.metrics.TasksCompleted > 0 {
		avgWaitTime = float64(s.metrics.TotalWaitTime) / float64(s.metrics.TasksCompleted)
		avgExecuteTime = float64(s.metrics.TotalExecuteTime) / float64(s.metrics.TasksCompleted)
	}
	if uptime > 0 {
		throughput = float64(s.metrics.TasksCompleted) / uptime
	}

	return map[string]interface{}{
		"tasks_submitted":     s.metrics.TasksSubmitted,
		"tasks_completed":     s.metrics.TasksCompleted,
		"tasks_failed":        s.metrics.TasksFailed,
		"tasks_preempted":     s.metrics.TasksPreempted,
		"tasks_canceled":      s.metrics.TasksCanceled,
		"uptime_seconds":      uptime,
		"avg_wait_time_ms":    avgWaitTime,
		"avg_execute_time_ms": avgExecuteTime,
		"throughput_tps":      throughput,
		"start_time":          s.metrics.StartTime,
	}
}

func (s *Service) GetTaskEvents(taskID string, limit int) []*TaskEvent {
	s.eventsMu.RLock()
	defer s.eventsMu.RUnlock()

	result := make([]*TaskEvent, 0, limit)
	count := 0

	for i := len(s.events) - 1; i >= 0 && count < limit; i-- {
		event := s.events[i]
		if taskID == "" || event.TaskID == taskID {
			result = append(result, event)
			count++
		}
	}

	return result
}

func (s *Service) GetGPUHistory(gpuID string, minutes int) []*GPUHistorySample {
	s.gpuHistoryMu.RLock()
	defer s.gpuHistoryMu.RUnlock()

	history, exists := s.gpuHistory[gpuID]
	if !exists {
		return nil
	}

	cutoff := time.Now().Add(-time.Duration(minutes) * time.Minute)
	result := make([]*GPUHistorySample, 0)

	for i := len(history) - 1; i >= 0; i-- {
		if history[i].Timestamp.After(cutoff) {
			result = append([]*GPUHistorySample{history[i]}, result...)
		} else {
			break
		}
	}

	return result
}

func (s *Service) GetAllGPUHistory(minutes int) map[string][]*GPUHistorySample {
	s.gpuHistoryMu.RLock()
	defer s.gpuHistoryMu.RUnlock()

	result := make(map[string][]*GPUHistorySample)
	cutoff := time.Now().Add(-time.Duration(minutes) * time.Minute)

	for gpuID, history := range s.gpuHistory {
		samples := make([]*GPUHistorySample, 0)
		for i := len(history) - 1; i >= 0; i-- {
			if history[i].Timestamp.After(cutoff) {
				samples = append([]*GPUHistorySample{history[i]}, samples...)
			} else {
				break
			}
		}
		result[gpuID] = samples
	}

	return result
}

func (s *Service) GetPrometheusMetrics() string {
	metrics := s.GetMetrics()
	queueStats := s.GetQueueStats()

	var sb strings.Builder

	sb.WriteString("# HELP llmgateway_scheduler_tasks_submitted Total number of tasks submitted\n")
	sb.WriteString("# TYPE llmgateway_scheduler_tasks_submitted counter\n")
	sb.WriteString(fmt.Sprintf("llmgateway_scheduler_tasks_submitted %d\n", int64(metrics["tasks_submitted"].(float64))))

	sb.WriteString("# HELP llmgateway_scheduler_tasks_completed Total number of tasks completed\n")
	sb.WriteString("# TYPE llmgateway_scheduler_tasks_completed counter\n")
	sb.WriteString(fmt.Sprintf("llmgateway_scheduler_tasks_completed %d\n", int64(metrics["tasks_completed"].(float64))))

	sb.WriteString("# HELP llmgateway_scheduler_tasks_failed Total number of tasks failed\n")
	sb.WriteString("# TYPE llmgateway_scheduler_tasks_failed counter\n")
	sb.WriteString(fmt.Sprintf("llmgateway_scheduler_tasks_failed %d\n", int64(metrics["tasks_failed"].(float64))))

	sb.WriteString("# HELP llmgateway_scheduler_tasks_queued Number of queued tasks\n")
	sb.WriteString("# TYPE llmgateway_scheduler_tasks_queued gauge\n")
	sb.WriteString(fmt.Sprintf("llmgateway_scheduler_tasks_queued %d\n", queueStats["queued_tasks"].(int)))

	sb.WriteString("# HELP llmgateway_scheduler_tasks_running Number of running tasks\n")
	sb.WriteString("# TYPE llmgateway_scheduler_tasks_running gauge\n")
	sb.WriteString(fmt.Sprintf("llmgateway_scheduler_tasks_running %d\n", queueStats["running_tasks"].(int)))

	s.gpuResourcesMu.RLock()
	defer s.gpuResourcesMu.RUnlock()

	sb.WriteString("# HELP llmgateway_gpu_utilization GPU utilization percentage\n")
	sb.WriteString("# TYPE llmgateway_gpu_utilization gauge\n")
	for gpuID, gpu := range s.gpuResources {
		sb.WriteString(fmt.Sprintf("llmgateway_gpu_utilization{gpu_id=\"%s\",node=\"%s\"} %.2f\n",
			gpuID, gpu.Node, gpu.Utilization*100))
	}

	sb.WriteString("# HELP llmgateway_gpu_memory_used_bytes GPU memory used in bytes\n")
	sb.WriteString("# TYPE llmgateway_gpu_memory_used_bytes gauge\n")
	for gpuID, gpu := range s.gpuResources {
		memoryUsed := (gpu.TotalMemoryMB - gpu.AvailableMemoryMB) * 1024 * 1024
		sb.WriteString(fmt.Sprintf("llmgateway_gpu_memory_used_bytes{gpu_id=\"%s\",node=\"%s\"} %d\n",
			gpuID, gpu.Node, memoryUsed))
	}

	return sb.String()
}

func (s *Service) schedulerLoop() {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()

	for range ticker.C {
		s.processQueue()
	}
}

func (s *Service) processQueue() {
	s.mu.Lock()
	defer s.mu.Unlock()

	for s.taskQueue.Len() > 0 {
		task := s.taskQueue.Peek()
		if task == nil {
			break
		}

		gpu, err := s.findSuitableGPU(task)
		if err != nil {
			if s.preemptionEnabled {
				if preempted := s.tryPreempt(task); preempted {
					continue
				}
			}
			break
		}

		heap.Pop(s.taskQueue)
		s.allocateGPU(gpu, task)
		go s.executeTask(task, gpu)
	}
}

func (s *Service) findSuitableGPU(task *entity.Task) (*entity.GPUResource, error) {
	s.gpuResourcesMu.RLock()
	defer s.gpuResourcesMu.RUnlock()

	for _, gpu := range s.gpuResources {
		if !gpu.Healthy || gpu.Status != "available" {
			continue
		}
		if gpu.AvailableMemoryMB >= task.MemoryRequiredMB &&
			gpu.AvailableComputeUnits >= task.ComputeRequired {
			return gpu, nil
		}
	}
	return nil, errors.New("no suitable GPU available")
}

func (s *Service) tryPreempt(newTask *entity.Task) bool {
	s.gpuResourcesMu.RLock()
	defer s.gpuResourcesMu.RUnlock()

	var lowestPriorityTask *entity.Task
	var lowestPriorityGPU *entity.GPUResource

	for _, gpu := range s.gpuResources {
		for _, runningTask := range s.runningTasks {
			if runningTask.GPUID != nil && *runningTask.GPUID == gpu.ID {
				if runningTask.Preemptible && runningTask.Priority < newTask.Priority {
					if lowestPriorityTask == nil || runningTask.Priority < lowestPriorityTask.Priority {
						lowestPriorityTask = runningTask
						lowestPriorityGPU = gpu
					}
				}
			}
		}
	}

	if lowestPriorityTask != nil {
		s.preemptTask(lowestPriorityTask, lowestPriorityGPU)
		return true
	}
	return false
}

func (s *Service) preemptTask(task *entity.Task, gpu *entity.GPUResource) {
	logger.Info("preempting task", "task_id", task.ID, "gpu_id", gpu.ID)

	task.Status = string(entity.TaskStatusPreempted)
	task.PreemptCount++
	task.UpdatedAt = utils.Now()

	s.releaseGPU(gpu, task)
	delete(s.runningTasks, task.ID)

	s.metricsMu.Lock()
	s.metrics.TasksPreempted++
	s.metricsMu.Unlock()

	s.recordEvent(task.ID, "preempted", map[string]interface{}{
		"gpu_id":        gpu.ID,
		"preempt_count": task.PreemptCount,
	})

	task.Status = string(entity.TaskStatusQueued)
	task.QueueTime = utils.Now()
	heap.Push(s.taskQueue, task)

	if callback, ok := s.taskCallbacks[task.ID]; ok {
		callback(task)
	}
}

func (s *Service) allocateGPU(gpu *entity.GPUResource, task *entity.Task) {
	s.gpuResourcesMu.Lock()
	defer s.gpuResourcesMu.Unlock()

	gpu.AvailableMemoryMB -= task.MemoryRequiredMB
	gpu.AvailableComputeUnits -= task.ComputeRequired
	gpu.Utilization = float64(gpu.TotalComputeUnits-gpu.AvailableComputeUnits) / float64(gpu.TotalComputeUnits)
	gpu.UpdatedAt = utils.Now()

	task.GPUID = &gpu.ID
	task.Status = string(entity.TaskStatusRunning)
	startTime := utils.Now()
	task.StartTime = &startTime
	task.UpdatedAt = utils.Now()

	waitTime := startTime.Sub(task.QueueTime).Milliseconds()

	s.runningTasks[task.ID] = task
	s.db.Save(task)
	s.db.Save(gpu)

	s.metricsMu.Lock()
	s.metrics.TotalWaitTime += waitTime
	s.metricsMu.Unlock()

	s.recordEvent(task.ID, "allocated", map[string]interface{}{
		"gpu_id":      gpu.ID,
		"wait_time_ms": waitTime,
	})

	logger.Info("task allocated to GPU", "task_id", task.ID, "gpu_id", gpu.ID, "wait_time_ms", waitTime)
}

func (s *Service) releaseGPU(gpu *entity.GPUResource, task *entity.Task) {
	s.gpuResourcesMu.Lock()
	defer s.gpuResourcesMu.Unlock()

	gpu.AvailableMemoryMB += task.MemoryRequiredMB
	gpu.AvailableComputeUnits += task.ComputeRequired
	gpu.Utilization = float64(gpu.TotalComputeUnits-gpu.AvailableComputeUnits) / float64(gpu.TotalComputeUnits)
	gpu.UpdatedAt = utils.Now()

	s.db.Save(gpu)
}

func (s *Service) executeTask(task *entity.Task, gpu *entity.GPUResource) {
	defer func() {
		if r := recover(); r != nil {
			errMsg := fmt.Sprintf("panic: %v", r)
			logger.Error("task panic recovered", "task_id", task.ID, "panic", errMsg)
			task.Status = string(entity.TaskStatusFailed)
			task.EndTime = utils.NowPtr()
			task.UpdatedAt = utils.Now()
			s.releaseGPU(gpu, task)
			delete(s.runningTasks, task.ID)
			s.db.Save(task)

			s.metricsMu.Lock()
			s.metrics.TasksFailed++
			s.metricsMu.Unlock()

			s.recordEvent(task.ID, "failed", map[string]interface{}{
				"error": errMsg,
			})
		}
	}()

	logger.Info("executing task", "task_id", task.ID, "gpu_id", gpu.ID)

	s.recordEvent(task.ID, "started", map[string]interface{}{
		"gpu_id": gpu.ID,
	})

	if callback, ok := s.taskCallbacks[task.ID]; ok {
		callback(task)
	}

	time.Sleep(1 * time.Second)

	task.Status = string(entity.TaskStatusCompleted)
	endTime := utils.Now()
	task.EndTime = &endTime
	task.UpdatedAt = endTime

	executeTime := int64(0)
	if task.StartTime != nil {
		executeTime = endTime.Sub(*task.StartTime).Milliseconds()
	}

	s.releaseGPU(gpu, task)
	delete(s.runningTasks, task.ID)
	s.db.Save(task)

	s.metricsMu.Lock()
	s.metrics.TasksCompleted++
	s.metrics.TotalExecuteTime += executeTime
	s.metricsMu.Unlock()

	s.recordEvent(task.ID, "completed", map[string]interface{}{
		"execute_time_ms": executeTime,
	})

	logger.Info("task completed", "task_id", task.ID, "execute_time_ms", executeTime)

	if callback, ok := s.taskCallbacks[task.ID]; ok {
		callback(task)
	}
}

type SubmitTaskRequest struct {
	Name             string                 `json:"name" binding:"required"`
	Type             string                 `json:"type" binding:"required"`
	Priority         int                    `json:"priority"`
	MemoryRequiredMB int64                  `json:"memory_required_mb" binding:"required"`
	ComputeRequired  int                    `json:"compute_required" binding:"required"`
	Preemptible      bool                   `json:"preemptible"`
	Payload          map[string]interface{} `json:"payload"`
}

func (s *Service) SubmitTask(ctx context.Context, req *SubmitTaskRequest) (*entity.Task, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.taskQueue.Len() >= s.maxQueueSize {
		return nil, errors.New("task queue is full")
	}

	now := utils.Now()
	task := &entity.Task{
		ID:               utils.GenerateID("task"),
		Name:             req.Name,
		Type:             req.Type,
		Priority:         req.Priority,
		Status:           string(entity.TaskStatusQueued),
		MemoryRequiredMB: req.MemoryRequiredMB,
		ComputeRequired:  req.ComputeRequired,
		Preemptible:      req.Preemptible,
		Payload:          req.Payload,
		QueueTime:        now,
		CreatedAt:        now,
		UpdatedAt:        now,
	}

	if err := s.db.Create(task).Error; err != nil {
		return nil, fmt.Errorf("failed to create task: %w", err)
	}

	heap.Push(s.taskQueue, task)

	s.metricsMu.Lock()
	s.metrics.TasksSubmitted++
	s.metricsMu.Unlock()

	s.recordEvent(task.ID, "submitted", map[string]interface{}{
		"name":     req.Name,
		"type":     req.Type,
		"priority": req.Priority,
		"memory_mb": req.MemoryRequiredMB,
		"compute":  req.ComputeRequired,
	})

	logger.Info("task submitted", "task_id", task.ID, "priority", task.Priority)

	return task, nil
}

func (s *Service) GetTask(taskID string) (*entity.Task, error) {
	var task entity.Task
	if err := s.db.Where("id = ?", taskID).First(&task).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("task not found")
		}
		return nil, fmt.Errorf("failed to get task: %w", err)
	}
	return &task, nil
}

func (s *Service) ListTasks(page, pageSize int, status, taskType string) ([]entity.Task, int64, error) {
	var tasks []entity.Task
	var total int64

	query := s.db.Model(&entity.Task{})

	if status != "" {
		query = query.Where("status = ?", status)
	}
	if taskType != "" {
		query = query.Where("type = ?", taskType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to count tasks: %w", err)
	}

	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&tasks).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to list tasks: %w", err)
	}

	return tasks, total, nil
}

func (s *Service) CancelTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, err := s.GetTask(taskID)
	if err != nil {
		return err
	}

	if task.Status == string(entity.TaskStatusRunning) {
		if gpuID := task.GPUID; gpuID != nil {
			if gpu, ok := s.gpuResources[*gpuID]; ok {
				s.releaseGPU(gpu, task)
			}
		}
		delete(s.runningTasks, taskID)
	}

	task.Status = string(entity.TaskStatusCanceled)
	task.EndTime = utils.NowPtr()
	task.UpdatedAt = utils.Now()

	if err := s.db.Save(task).Error; err != nil {
		return err
	}

	s.metricsMu.Lock()
	s.metrics.TasksCanceled++
	s.metricsMu.Unlock()

	s.recordEvent(taskID, "canceled", map[string]interface{}{
		"previous_status": task.Status,
	})

	return nil
}

func (s *Service) ListGPUs() ([]entity.GPUResource, error) {
	s.gpuResourcesMu.RLock()
	defer s.gpuResourcesMu.RUnlock()

	gpus := make([]entity.GPUResource, 0, len(s.gpuResources))
	for _, gpu := range s.gpuResources {
		gpus = append(gpus, *gpu)
	}
	return gpus, nil
}

func (s *Service) GetGPU(gpuID string) (*entity.GPUResource, error) {
	s.gpuResourcesMu.RLock()
	defer s.gpuResourcesMu.RUnlock()

	gpu, ok := s.gpuResources[gpuID]
	if !ok {
		return nil, errors.New("GPU not found")
	}
	return gpu, nil
}

func (s *Service) RegisterTaskCallback(taskID string, callback func(*entity.Task)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.taskCallbacks[taskID] = callback
}

func (s *Service) GetQueueStats() map[string]interface{} {
	s.mu.Lock()
	defer s.mu.Unlock()

	return map[string]interface{}{
		"queued_tasks":   s.taskQueue.Len(),
		"running_tasks":  len(s.runningTasks),
		"max_queue_size": s.maxQueueSize,
	}
}
