package scheduler

import (
	"context"
	"fmt"
	"sort"
	"sync"
	"time"

	"loglevelplatform/internal/common/database"
	"loglevelplatform/internal/common/errors"
	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/common/models"
	"loglevelplatform/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type TaskHandler func(ctx context.Context, payload map[string]interface{}) (string, error)

type Service struct {
	db        *gorm.DB
	tasks     map[string]*models.ScheduledTask
	handlers  map[string]TaskHandler
	mu        sync.RWMutex
	running   bool
	stopChan  chan struct{}
	executing map[string]bool
}

var (
	instance *Service
	once     sync.Once
)

func NewService() *Service {
	once.Do(func() {
		instance = &Service{
			db:        database.GetDB(),
			tasks:     make(map[string]*models.ScheduledTask),
			handlers:  make(map[string]TaskHandler),
			stopChan:  make(chan struct{}),
			executing: make(map[string]bool),
		}
	})
	return instance
}

func (s *Service) RegisterHandler(taskType string, handler TaskHandler) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.handlers[taskType] = handler
}

func (s *Service) CreateTask(ctx context.Context, task *models.ScheduledTask) (*models.ScheduledTask, error) {
	log := logger.FromContext(ctx)

	if task.Name == "" {
		return nil, errors.NewValidationError("task name is required")
	}

	task.ID = utils.NewID("task")
	task.Status = "pending"
	task.CreatedAt = time.Now()
	task.UpdatedAt = time.Now()

	if err := s.db.Create(task).Error; err != nil {
		log.Error("failed to create scheduled task", zap.Error(err))
		return nil, err
	}

	s.mu.Lock()
	s.tasks[task.ID] = task
	s.mu.Unlock()

	log.Info("scheduled task created", zap.String("task_id", task.ID), zap.String("name", task.Name))
	return task, nil
}

func (s *Service) GetTask(ctx context.Context, id string) (*models.ScheduledTask, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if task, exists := s.tasks[id]; exists {
		return task, nil
	}

	var task models.ScheduledTask
	if err := s.db.Where("id = ?", id).First(&task).Error; err != nil {
		return nil, err
	}
	return &task, nil
}

func (s *Service) ListTasks(ctx context.Context, status string, limit, offset int) ([]models.ScheduledTask, int64, error) {
	var tasks []models.ScheduledTask
	var total int64

	query := s.db.Model(&models.ScheduledTask{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("created_at DESC").Limit(limit).Offset(offset).Find(&tasks).Error; err != nil {
		return nil, 0, err
	}

	return tasks, total, nil
}

func (s *Service) UpdateTask(ctx context.Context, id string, updates map[string]interface{}) (*models.ScheduledTask, error) {
	log := logger.FromContext(ctx)

	var task models.ScheduledTask
	if err := s.db.Where("id = ?", id).First(&task).Error; err != nil {
		return nil, err
	}

	updates["updated_at"] = time.Now()
	if err := s.db.Model(&task).Updates(updates).Error; err != nil {
		log.Error("failed to update task", zap.Error(err), zap.String("task_id", id))
		return nil, err
	}

	if err := s.db.Where("id = ?", id).First(&task).Error; err != nil {
		return nil, err
	}

	s.mu.Lock()
	s.tasks[id] = &task
	s.mu.Unlock()

	log.Info("task updated", zap.String("task_id", id))
	return &task, nil
}

func (s *Service) DeleteTask(ctx context.Context, id string) error {
	log := logger.FromContext(ctx)

	if err := s.db.Delete(&models.ScheduledTask{}, "id = ?", id).Error; err != nil {
		log.Error("failed to delete task", zap.Error(err), zap.String("task_id", id))
		return err
	}

	s.mu.Lock()
	delete(s.tasks, id)
	s.mu.Unlock()

	log.Info("task deleted", zap.String("task_id", id))
	return nil
}

func (s *Service) ExecuteTask(ctx context.Context, taskID string) (*models.TaskExecution, error) {
	log := logger.FromContext(ctx)

	s.mu.RLock()
	task, exists := s.tasks[taskID]
	s.mu.RUnlock()

	if !exists {
		var dbTask models.ScheduledTask
		if err := s.db.Where("id = ?", taskID).First(&dbTask).Error; err != nil {
			return nil, errors.NewNotFoundError("task not found")
		}
		task = &dbTask
	}

	if !task.Enabled {
		return nil, errors.NewValidationError("task is disabled")
	}

	s.mu.Lock()
	if s.executing[taskID] {
		s.mu.Unlock()
		return nil, errors.NewConflictError("task is already executing")
	}
	s.executing[taskID] = true
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.executing, taskID)
		s.mu.Unlock()
	}()

	if err := s.checkDependencies(ctx, task); err != nil {
		log.Warn("task dependencies not met", zap.String("task_id", taskID), zap.Error(err))
		return nil, err
	}

	execution := &models.TaskExecution{
		ID:        utils.NewID("exec"),
		TaskID:    taskID,
		Status:    "running",
		StartTime: time.Now(),
	}

	if err := s.db.Create(execution).Error; err != nil {
		log.Error("failed to create task execution", zap.Error(err))
		return nil, err
	}

	handler, exists := s.handlers[task.Type]
	if !exists {
		handler = s.defaultHandler
	}

	resultCh := make(chan struct {
		result string
		err    error
	}, 1)

	go func() {
		result, err := handler(ctx, task.Payload)
		resultCh <- struct {
			result string
			err    error
		}{result, err}
	}()

	var result string
	var err error

	select {
	case res := <-resultCh:
		result, err = res.result, res.err
	case <-time.After(time.Duration(task.TimeoutSeconds) * time.Second):
		err = errors.NewTimeoutError("task execution timed out")
	}

	now := time.Now()
	execution.EndTime = &now

	if err != nil {
		execution.Status = "failed"
		errMsg := err.Error()
		execution.ErrorMsg = &errMsg
	} else {
		execution.Status = "completed"
		execution.Result = result
	}

	if err := s.db.Save(execution).Error; err != nil {
		log.Error("failed to update task execution", zap.Error(err))
	}

	now2 := time.Now()
	task.LastRunAt = &now2
	task.Status = execution.Status
	if err := s.db.Save(task).Error; err != nil {
		log.Error("failed to update task status", zap.Error(err))
	}

	log.Info("task execution completed",
		zap.String("task_id", taskID),
		zap.String("execution_id", execution.ID),
		zap.String("status", execution.Status),
	)

	return execution, nil
}

func (s *Service) checkDependencies(ctx context.Context, task *models.ScheduledTask) error {
	if len(task.DependsOn) == 0 {
		return nil
	}

	for _, depID := range task.DependsOn {
		var depTask models.ScheduledTask
		if err := s.db.Where("id = ?", depID).First(&depTask).Error; err != nil {
			return errors.NewNotFoundError(fmt.Sprintf("dependency task %s not found", depID))
		}

		var lastExec models.TaskExecution
		err := s.db.Where("task_id = ?", depID).Order("start_time DESC").First(&lastExec).Error
		if err != nil || lastExec.Status != "completed" {
			return errors.NewValidationError(fmt.Sprintf("dependency task %s not completed successfully", depID))
		}
	}
	return nil
}

func (s *Service) defaultHandler(ctx context.Context, payload map[string]interface{}) (string, error) {
	log := logger.FromContext(ctx)
	log.Info("executing default task handler", zap.Any("payload", payload))
	return fmt.Sprintf("default handler processed: %v", payload), nil
}

func (s *Service) GetTaskExecutions(ctx context.Context, taskID string, limit int) ([]models.TaskExecution, error) {
	var executions []models.TaskExecution
	query := s.db.Order("start_time DESC").Limit(limit)
	if taskID != "" {
		query = query.Where("task_id = ?", taskID)
	}
	if err := query.Find(&executions).Error; err != nil {
		return nil, err
	}
	return executions, nil
}

func (s *Service) Start() {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return
	}
	s.running = true
	s.mu.Unlock()

	go s.schedulerLoop()
	logger.Info("scheduler started")
}

func (s *Service) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running {
		s.running = false
		close(s.stopChan)
		logger.Info("scheduler stopped")
	}
}

func (s *Service) schedulerLoop() {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-s.stopChan:
			return
		case <-ticker.C:
			s.checkDueTasks()
		}
	}
}

func (s *Service) checkDueTasks() {
	s.mu.RLock()
	defer s.mu.RUnlock()

	now := time.Now()
	var dueTasks []*models.ScheduledTask

	for _, task := range s.tasks {
		if !task.Enabled {
			continue
		}
		if task.NextRunAt == nil || task.NextRunAt.Before(now) {
			dueTasks = append(dueTasks, task)
		}
	}

	sort.Slice(dueTasks, func(i, j int) bool {
		return len(dueTasks[i].DependsOn) < len(dueTasks[j].DependsOn)
	})

	for _, task := range dueTasks {
		go func(t *models.ScheduledTask) {
			ctx := context.Background()
			_, _ = s.ExecuteTask(ctx, t.ID)
		}(task)

		nextRun := now.Add(60 * time.Second)
		task.NextRunAt = &nextRun
	}
}

func (s *Service) LoadTasks(ctx context.Context) error {
	var tasks []models.ScheduledTask
	if err := s.db.Find(&tasks).Error; err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range tasks {
		s.tasks[tasks[i].ID] = &tasks[i]
	}

	logger.Info("loaded scheduled tasks", zap.Int("count", len(tasks)))
	return nil
}
