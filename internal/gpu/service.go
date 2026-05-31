package gpu

import (
	"context"
	"sort"
	"strings"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"
	errors "session133/pkg/errors"
	"session133/pkg/utils"
)

type GPUSchedulerService struct {
	db            *gorm.DB
	logger        *zap.Logger
	pendingTasks  []*Task
	pendingMu     sync.Mutex
	schedulerStop chan struct{}
}

func NewGPUSchedulerService(db *gorm.DB, logger *zap.Logger) *GPUSchedulerService {
	s := &GPUSchedulerService{
		db:            db,
		logger:        logger,
		pendingTasks:  make([]*Task, 0),
		schedulerStop: make(chan struct{}),
	}

	if err := db.AutoMigrate(&GPU{}, &Task{}, &TaskEvent{}); err != nil {
		logger.Error("Failed to migrate GPU tables", zap.Error(err))
	}

	return s
}

func (s *GPUSchedulerService) StartScheduler(ctx context.Context) {
	go s.scheduleLoop(ctx)
}

func (s *GPUSchedulerService) StopScheduler() {
	close(s.schedulerStop)
}

func (s *GPUSchedulerService) scheduleLoop(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-s.schedulerStop:
			return
		case <-ticker.C:
			s.runScheduling()
		}
	}
}

func (s *GPUSchedulerService) runScheduling() {
	s.pendingMu.Lock()
	defer s.pendingMu.Unlock()

	if len(s.pendingTasks) == 0 {
		return
	}

	sort.Slice(s.pendingTasks, func(i, j int) bool {
		if s.pendingTasks[i].Priority != s.pendingTasks[j].Priority {
			return s.pendingTasks[i].Priority > s.pendingTasks[j].Priority
		}
		return s.pendingTasks[i].CreatedAt.Before(s.pendingTasks[j].CreatedAt)
	})

	for i := 0; i < len(s.pendingTasks); i++ {
		task := s.pendingTasks[i]
		availableGPUs := s.findAvailableGPUs(task)

		if len(availableGPUs) >= task.RequiredGPUs {
			err := s.assignGPUs(task, availableGPUs[:task.RequiredGPUs])
			if err != nil {
				s.logger.Error("Failed to assign GPUs to task", zap.String("task_id", task.ID), zap.Error(err))
				continue
			}

			s.pendingTasks = append(s.pendingTasks[:i], s.pendingTasks[i+1:]...)
			i--

			go s.startTask(task)
		}
	}
}

func (s *GPUSchedulerService) findAvailableGPUs(task *Task) []*GPU {
	var gpus []*GPU
	query := s.db.Where("status = ?", GPUStatusIdle)

	if task.RequiredMemory > 0 {
		query = query.Where("total_memory - used_memory >= ?", task.RequiredMemory)
	}

	for k, v := range task.GPULabels {
		query = query.Where("labels->>? = ?", k, v)
	}

	if err := query.Find(&gpus).Error; err != nil {
		s.logger.Error("Failed to query available GPUs", zap.Error(err))
		return nil
	}

	return gpus
}

func (s *GPUSchedulerService) assignGPUs(task *Task, gpus []*GPU) error {
	tx := s.db.Begin()
	if tx.Error != nil {
		return tx.Error
	}

	gpuIDs := make([]string, 0, len(gpus))
	for _, gpu := range gpus {
		gpuIDs = append(gpuIDs, gpu.ID)
		if err := tx.Model(gpu).Updates(map[string]interface{}{
			"status":           GPUStatusRunning,
			"used_memory":      gpu.UsedMemory + task.RequiredMemory,
			"current_task_id": task.ID,
		}).Error; err != nil {
			tx.Rollback()
			return err
		}
	}

	now := time.Now()
	task.AssignedGPUs = gpuIDs
	task.Status = TaskStatusRunning
	task.StartedAt = &now
	task.QueuedAt = &now

	if err := tx.Save(task).Error; err != nil {
		tx.Rollback()
		return err
	}

	event := &TaskEvent{
		ID:        utils.GenerateID("gpu_evt"),
		TaskID:    task.ID,
		EventType: "schedule",
		Status:    TaskStatusRunning,
		Detail:    "Task scheduled with GPUs: " + strings.Join(gpuIDs, ", "),
		CreatedAt: now,
	}

	if err := tx.Create(event).Error; err != nil {
		tx.Rollback()
		return err
	}

	return tx.Commit().Error
}

func (s *GPUSchedulerService) startTask(task *Task) {
	s.logger.Info("Starting GPU task", zap.String("task_id", task.ID), zap.Strings("gpus", task.AssignedGPUs))

	// Simulate task execution with timeout
	var timeout <-chan time.Time
	if task.Timeout > 0 {
		timeout = time.After(task.Timeout)
	}

	progressTicker := time.NewTicker(1 * time.Second)
	defer progressTicker.Stop()

	for {
		select {
		case <-timeout:
			s.completeTask(task, TaskStatusFailed, "Task timed out")
			return
		case <-progressTicker.C:
			// Simulate progress
			newProgress := task.Progress + 0.1
			if newProgress >= 1.0 {
				s.completeTask(task, TaskStatusCompleted, "")
				return
			}

			if err := s.db.Model(task).Update("progress", newProgress).Error; err != nil {
				s.logger.Error("Failed to update task progress", zap.String("task_id", task.ID), zap.Error(err))
			}
			task.Progress = newProgress
		}
	}
}

func (s *GPUSchedulerService) completeTask(task *Task, status TaskStatus, errorDetail string) {
	tx := s.db.Begin()
	if tx.Error != nil {
		s.logger.Error("Failed to start transaction", zap.Error(err))
		return
	}

	now := time.Now()
	for _, gpuID := range task.AssignedGPUs {
		var gpu GPU
		if err := tx.Where("id = ?", gpuID).First(&gpu).Error; err != nil {
			s.logger.Error("Failed to find GPU", zap.String("gpu_id", gpuID), zap.Error(err))
			continue
		}

		if err := tx.Model(&gpu).Updates(map[string]interface{}{
			"status":           GPUStatusIdle,
			"used_memory":      gpu.UsedMemory - task.RequiredMemory,
			"current_task_id": "",
		}).Error; err != nil {
			s.logger.Error("Failed to free GPU", zap.String("gpu_id", gpuID), zap.Error(err))
		}
	}

	task.Status = status
	task.CompletedAt = &now
	if errorDetail != "" {
		task.ErrorDetail = &errorDetail
	}

	if err := tx.Save(task).Error; err != nil {
		tx.Rollback()
		s.logger.Error("Failed to update task", zap.String("task_id", task.ID), zap.Error(err))
		return
	}

	event := &TaskEvent{
		ID:        utils.GenerateID("gpu_evt"),
		TaskID:    task.ID,
		EventType: "complete",
		Status:    status,
		Detail:    errorDetail,
		CreatedAt: now,
	}

	if err := tx.Create(event).Error; err != nil {
		tx.Rollback()
		s.logger.Error("Failed to create event", zap.Error(err))
		return
	}

	if err := tx.Commit().Error; err != nil {
		s.logger.Error("Failed to commit transaction", zap.Error(err))
	}

	s.logger.Info("Task completed", zap.String("task_id", task.ID), zap.String("status", string(status)))
}

func (s *GPUSchedulerService) RegisterGPU(ctx context.Context, gpu *GPU) (*GPU, error) {
	if gpu.ID == "" {
		gpu.ID = utils.GenerateID("gpu")
	}
	now := time.Now()
	gpu.CreatedAt = now
	gpu.UpdatedAt = now
	gpu.Status = GPUStatusIdle

	if err := s.db.WithContext(ctx).Create(gpu).Error; err != nil {
		return nil, errors.InternalError(err.Error())
	}

	return gpu, nil
}

func (s *GPUSchedulerService) SubmitTask(ctx context.Context, req *CreateTaskRequest, userID string) (*Task, error) {
	var timeout time.Duration
	if req.Timeout != "" {
		var err error
		timeout, err = time.ParseDuration(req.Timeout)
		if err != nil {
			return nil, errors.InvalidParams("无效的超时时间格式")
		}
	}

	task := &Task{
		ID:             utils.GenerateID("task"),
		Name:           req.Name,
		Namespace:      req.Namespace,
		Description:    req.Description,
		Priority:       req.Priority,
		Status:         TaskStatusPending,
		Command:        req.Command,
		Image:          req.Image,
		EnvVars:        req.EnvVars,
		RequiredGPUs:   req.RequiredGPUs,
		RequiredMemory: req.RequiredMemory,
		GPULabels:      req.GPULabels,
		MaxRetryCount:  req.MaxRetryCount,
		Timeout:        timeout,
		UserID:         userID,
		Progress:       0,
		CreatedAt:      time.Now(),
		UpdatedAt:      time.Now(),
		Metadata:       req.Metadata,
	}

	if err := s.db.WithContext(ctx).Create(task).Error; err != nil {
		return nil, errors.InternalError(err.Error())
	}

	s.pendingMu.Lock()
	s.pendingTasks = append(s.pendingTasks, task)
	s.pendingMu.Unlock()

	s.addEvent(task.ID, "submit", TaskStatusPending, "Task submitted to queue")

	return task, nil
}

func (s *GPUSchedulerService) CancelTask(ctx context.Context, taskID string, userID string) error {
	var task Task
	if err := s.db.WithContext(ctx).Where("id = ?", taskID).First(&task).Error; err != nil {
		return errors.NotFound("任务不存在")
	}

	if task.Status == TaskStatusCompleted || task.Status == TaskStatusFailed || task.Status == TaskStatusCancelled {
		return errors.InvalidParams("任务已完成或已取消")
	}

	if task.UserID != userID {
		return errors.Forbidden("无权限取消此任务")
	}

	s.pendingMu.Lock()
	for i, t := range s.pendingTasks {
		if t.ID == taskID {
			s.pendingTasks = append(s.pendingTasks[:i], s.pendingTasks[i+1:]...)
			break
		}
	}
	s.pendingMu.Unlock()

	if task.Status == TaskStatusRunning {
		for _, gpuID := range task.AssignedGPUs {
			s.db.Model(&GPU{}).Where("id = ?", gpuID).Updates(map[string]interface{}{
				"status":           GPUStatusIdle,
				"used_memory":      gorm.Expr("used_memory - ?", task.RequiredMemory),
				"current_task_id": "",
			})
		}
	}

	now := time.Now()
	task.Status = TaskStatusCancelled
	task.CompletedAt = &now

	if err := s.db.WithContext(ctx).Save(&task).Error; err != nil {
		return errors.InternalError(err.Error())
	}

	s.addEvent(taskID, "cancel", TaskStatusCancelled, "Task cancelled by user")

	return nil
}

func (s *GPUSchedulerService) PreemptTask(ctx context.Context, highPriorityTaskID string) error {
	var highPriorityTask Task
	if err := s.db.WithContext(ctx).Where("id = ?", highPriorityTaskID).First(&highPriorityTask).Error; err != nil {
		return errors.NotFound("高优先级任务不存在")
	}

	var runningTasks []Task
	if err := s.db.WithContext(ctx).Where("status = ?", TaskStatusRunning).
		Where("priority < ?", highPriorityTask.Priority).
		Order("priority asc, created_at asc").
		Find(&runningTasks).Error; err != nil {
		return errors.InternalError(err.Error())
	}

	requiredGPUs := highPriorityTask.RequiredGPUs
	preempted := 0

	for _, task := range runningTasks {
		if preempted >= requiredGPUs {
			break
		}

		for _, gpuID := range task.AssignedGPUs {
			s.db.Model(&GPU{}).Where("id = ?", gpuID).Updates(map[string]interface{}{
				"status":           GPUStatusIdle,
				"used_memory":      gorm.Expr("used_memory - ?", task.RequiredMemory),
				"current_task_id": "",
			})
		}

		now := time.Now()
		task.Status = TaskStatusPaused
		task.CompletedAt = &now
		s.db.Save(&task)

		s.addEvent(task.ID, "preempt", TaskStatusPaused, "Task preempted by higher priority task: "+highPriorityTaskID)
		s.pendingMu.Lock()
		task.Status = TaskStatusPending
		s.pendingTasks = append(s.pendingTasks, &task)
		s.pendingMu.Unlock()

		preempted += task.RequiredGPUs
	}

	return nil
}

func (s *GPUSchedulerService) GetTask(ctx context.Context, taskID string) (*Task, error) {
	var task Task
	if err := s.db.WithContext(ctx).Where("id = ?", taskID).First(&task).Error; err != nil {
		return nil, errors.NotFound("任务不存在")
	}
	return &task, nil
}

func (s *GPUSchedulerService) ListTasks(ctx context.Context, namespace string, status TaskStatus, page, pageSize int) ([]*Task, int64, error) {
	var tasks []*Task
	var total int64

	query := s.db.WithContext(ctx).Model(&Task{})
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, errors.InternalError(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("priority desc, created_at desc").Find(&tasks).Error; err != nil {
		return nil, 0, errors.InternalError(err.Error())
	}

	return tasks, total, nil
}

func (s *GPUSchedulerService) GetGPU(ctx context.Context, gpuID string) (*GPU, error) {
	var gpu GPU
	if err := s.db.WithContext(ctx).Where("id = ?", gpuID).First(&gpu).Error; err != nil {
		return nil, errors.NotFound("GPU不存在")
	}
	return &gpu, nil
}

func (s *GPUSchedulerService) ListGPUs(ctx context.Context, nodeID string, status GPUStatus, page, pageSize int) ([]*GPU, int64, error) {
	var gpus []*GPU
	var total int64

	query := s.db.WithContext(ctx).Model(&GPU{})
	if nodeID != "" {
		query = query.Where("node_id = ?", nodeID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, errors.InternalError(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("node_id, index").Find(&gpus).Error; err != nil {
		return nil, 0, errors.InternalError(err.Error())
	}

	return gpus, total, nil
}

func (s *GPUSchedulerService) GetTaskEvents(ctx context.Context, taskID string) ([]*TaskEvent, error) {
	var events []*TaskEvent
	if err := s.db.WithContext(ctx).Where("task_id = ?", taskID).Order("created_at desc").Find(&events).Error; err != nil {
		return nil, errors.InternalError(err.Error())
	}
	return events, nil
}

func (s *GPUSchedulerService) addEvent(taskID, eventType string, status TaskStatus, detail string) {
	event := &TaskEvent{
		ID:        utils.GenerateID("gpu_evt"),
		TaskID:    taskID,
		EventType: eventType,
		Status:    status,
		Detail:    detail,
		CreatedAt: time.Now(),
	}
	s.db.Create(event)
}
