package scheduler

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/pkg/models"
	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type TaskHandler func(ctx context.Context, payload map[string]interface{}) error

type Scheduler struct {
	db       *gorm.DB
	cron     *cron.Cron
	handlers map[string]TaskHandler
	entryIDs map[string]cron.EntryID
	mu       sync.RWMutex
	running  bool
}

func NewScheduler(db *gorm.DB) *Scheduler {
	return &Scheduler{
		db:       db,
		cron:     cron.New(),
		handlers: make(map[string]TaskHandler),
		entryIDs: make(map[string]cron.EntryID),
	}
}

func (s *Scheduler) RegisterHandler(taskType string, handler TaskHandler) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.handlers[taskType] = handler
}

func (s *Scheduler) CreateTask(ctx context.Context, tenantID, name, taskType, cronExpr string, payload map[string]interface{}) (*models.ScheduledTask, error) {
	if _, err := cron.ParseStandard(cronExpr); err != nil {
		return nil, fmt.Errorf("invalid cron expression: %w", err)
	}

	payloadBytes, _ := json.Marshal(payload)

	task := &models.ScheduledTask{
		ID:        fmt.Sprintf("sch_%s", uuid.New().String()[:8]),
		Name:      name,
		CronExpr:  cronExpr,
		TaskType:  taskType,
		Payload:   payloadBytes,
		Enabled:   true,
		NextRunAt: s.calculateNextRun(cronExpr),
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
		TenantID:  tenantID,
	}

	if err := s.db.WithContext(ctx).Create(task).Error; err != nil {
		logger.Error("failed to create scheduled task", zap.Error(err))
		return nil, err
	}

	if s.running {
		if err := s.scheduleTask(task); err != nil {
			logger.Error("failed to schedule task", zap.Error(err), zap.String("task_id", task.ID))
		}
	}

	return task, nil
}

func (s *Scheduler) calculateNextRun(cronExpr string) time.Time {
	sched, err := cron.ParseStandard(cronExpr)
	if err != nil {
		return time.Now().Add(1 * time.Hour)
	}
	return sched.Next(time.Now())
}

func (s *Scheduler) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.running {
		return nil
	}

	var tasks []*models.ScheduledTask
	if err := s.db.Where("enabled = ?", true).Find(&tasks).Error; err != nil {
		return err
	}

	for _, task := range tasks {
		if err := s.scheduleTask(task); err != nil {
			logger.Error("failed to schedule task", zap.Error(err), zap.String("task_id", task.ID))
		}
	}

	s.cron.Start()
	s.running = true
	logger.Info("scheduler started", zap.Int("tasks_scheduled", len(tasks)))

	return nil
}

func (s *Scheduler) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running {
		return
	}

	s.cron.Stop()
	s.running = false
	logger.Info("scheduler stopped")
}

func (s *Scheduler) scheduleTask(task *models.ScheduledTask) error {
	s.mu.RLock()
	handler, exists := s.handlers[task.TaskType]
	s.mu.RUnlock()

	if !exists {
		return fmt.Errorf("no handler registered for task type: %s", task.TaskType)
	}

	entryID, err := s.cron.AddFunc(task.CronExpr, func() {
		s.executeTask(task, handler)
	})
	if err != nil {
		return err
	}

	s.mu.Lock()
	s.entryIDs[task.ID] = entryID
	s.mu.Unlock()

	return nil
}

func (s *Scheduler) executeTask(task *models.ScheduledTask, handler TaskHandler) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	logger.Info("executing scheduled task",
		zap.String("task_id", task.ID),
		zap.String("task_name", task.Name),
		zap.String("task_type", task.TaskType),
	)

	var payload map[string]interface{}
	if err := json.Unmarshal(task.Payload, &payload); err != nil {
		payload = make(map[string]interface{})
	}

	start := time.Now()
	err := handler(ctx, payload)
	duration := time.Since(start)

	now := time.Now()
	updates := map[string]interface{}{
		"last_run_at": &now,
		"next_run_at": s.calculateNextRun(task.CronExpr),
		"updated_at":  now,
	}

	if err := s.db.Model(&models.ScheduledTask{}).Where("id = ?", task.ID).Updates(updates).Error; err != nil {
		logger.Error("failed to update task execution time", zap.Error(err))
	}

	if err != nil {
		logger.Error("scheduled task execution failed",
			zap.String("task_id", task.ID),
			zap.Error(err),
			zap.Duration("duration", duration),
		)
	} else {
		logger.Info("scheduled task executed successfully",
			zap.String("task_id", task.ID),
			zap.Duration("duration", duration),
		)
	}
}

func (s *Scheduler) PauseTask(ctx context.Context, taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if entryID, exists := s.entryIDs[taskID]; exists {
		s.cron.Remove(entryID)
		delete(s.entryIDs, taskID)
	}

	return s.db.WithContext(ctx).Model(&models.ScheduledTask{}).
		Where("id = ?", taskID).
		Updates(map[string]interface{}{
			"enabled":    false,
			"updated_at": time.Now(),
		}).Error
}

func (s *Scheduler) ResumeTask(ctx context.Context, taskID string) error {
	var task models.ScheduledTask
	if err := s.db.WithContext(ctx).Where("id = ?", taskID).First(&task).Error; err != nil {
		return err
	}

	if err := s.db.WithContext(ctx).Model(&task).Updates(map[string]interface{}{
		"enabled":     true,
		"next_run_at": s.calculateNextRun(task.CronExpr),
		"updated_at":  time.Now(),
	}).Error; err != nil {
		return err
	}

	if s.running {
		return s.scheduleTask(&task)
	}

	return nil
}

func (s *Scheduler) DeleteTask(ctx context.Context, taskID string) error {
	s.mu.Lock()
	if entryID, exists := s.entryIDs[taskID]; exists {
		s.cron.Remove(entryID)
		delete(s.entryIDs, taskID)
	}
	s.mu.Unlock()

	return s.db.WithContext(ctx).Where("id = ?", taskID).Delete(&models.ScheduledTask{}).Error
}

func (s *Scheduler) GetTask(ctx context.Context, taskID string) (*models.ScheduledTask, error) {
	var task models.ScheduledTask
	if err := s.db.WithContext(ctx).Where("id = ?", taskID).First(&task).Error; err != nil {
		return nil, err
	}
	return &task, nil
}

func (s *Scheduler) ListTasks(ctx context.Context, tenantID string, page, pageSize int) ([]*models.ScheduledTask, int64, error) {
	var tasks []*models.ScheduledTask
	var total int64

	query := s.db.WithContext(ctx).Model(&models.ScheduledTask{})
	if tenantID != "" {
		query = query.Where("tenant_id = ?", tenantID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}

	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&tasks).Error; err != nil {
		return nil, 0, err
	}

	return tasks, total, nil
}

func (s *Scheduler) UpdateTask(ctx context.Context, taskID string, updates map[string]interface{}) error {
	s.mu.Lock()
	if entryID, exists := s.entryIDs[taskID]; exists {
		s.cron.Remove(entryID)
		delete(s.entryIDs, taskID)
	}
	s.mu.Unlock()

	updates["updated_at"] = time.Now()

	if cronExpr, ok := updates["cron_expr"].(string); ok {
		updates["next_run_at"] = s.calculateNextRun(cronExpr)
	}

	if err := s.db.WithContext(ctx).Model(&models.ScheduledTask{}).
		Where("id = ?", taskID).
		Updates(updates).Error; err != nil {
		return err
	}

	var task models.ScheduledTask
	if err := s.db.WithContext(ctx).Where("id = ?", taskID).First(&task).Error; err != nil {
		return err
	}

	if s.running && task.Enabled {
		return s.scheduleTask(&task)
	}

	return nil
}

func (s *Scheduler) TriggerNow(ctx context.Context, taskID string) error {
	var task models.ScheduledTask
	if err := s.db.WithContext(ctx).Where("id = ?", taskID).First(&task).Error; err != nil {
		return err
	}

	s.mu.RLock()
	handler, exists := s.handlers[task.TaskType]
	s.mu.RUnlock()

	if !exists {
		return fmt.Errorf("no handler registered for task type: %s", task.TaskType)
	}

	go s.executeTask(&task, handler)
	return nil
}

func (s *Scheduler) IsRunning() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.running
}
