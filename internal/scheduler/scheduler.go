package scheduler

import (
	"context"
	"errors"
	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"sync"
	"taskmanager/internal/logger"
	"taskmanager/pkg/models"
	"time"
)

type Scheduler struct {
	db        *gorm.DB
	cron      *cron.Cron
	taskMap   map[string]cron.EntryID
	mu        sync.RWMutex
	running   bool
}

func NewScheduler(db *gorm.DB) *Scheduler {
	return &Scheduler{
		db:      db,
		cron:    cron.New(cron.WithSeconds()),
		taskMap: make(map[string]cron.EntryID),
	}
}

func (s *Scheduler) Start() {
	if s.running {
		return
	}
	s.running = true
	s.cron.Start()
	if err := s.loadTasks(); err != nil {
		logger.Error("load tasks failed", zap.Error(err))
	}
	logger.Info("scheduler started")
}

func (s *Scheduler) Stop() {
	if !s.running {
		return
	}
	s.running = false
	ctx := s.cron.Stop()
	<-ctx.Done()
	logger.Info("scheduler stopped")
}

func (s *Scheduler) loadTasks() error {
	var tasks []models.Task
	if err := s.db.Where("enabled = ?", true).Find(&tasks).Error; err != nil {
		return err
	}
	for _, task := range tasks {
		if err := s.scheduleTask(&task); err != nil {
			logger.Error("schedule task failed", zap.String("task_id", task.ID), zap.Error(err))
		}
	}
	return nil
}

func (s *Scheduler) scheduleTask(task *models.Task) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if entryID, ok := s.taskMap[task.ID]; ok {
		s.cron.Remove(entryID)
		delete(s.taskMap, task.ID)
	}
	entryID, err := s.cron.AddFunc(task.CronExpr, func() {
		s.executeTask(task)
	})
	if err != nil {
		return err
	}
	s.taskMap[task.ID] = entryID
	entry := s.cron.Entry(entryID)
	next := entry.Next
	task.NextRun = &next
	if err := s.db.Save(task).Error; err != nil {
		logger.Error("update task next run failed", zap.Error(err))
	}
	return nil
}

func (s *Scheduler) executeTask(task *models.Task) {
	logger.Info("executing task", zap.String("task_id", task.ID), zap.String("task_name", task.Name))
	runID := uuid.New().String()
	now := time.Now()
	run := &models.RunInstance{
		RunID:     runID,
		EntityID:  task.ID,
		Phase:     "running",
		Progress:  0,
		StartedAt: now,
	}
	if err := s.db.Create(run).Error; err != nil {
		logger.Error("create run instance failed", zap.Error(err))
		return
	}
	task.LastRun = &now
	task.Status = "running"
	if err := s.db.Save(task).Error; err != nil {
		logger.Error("update task status failed", zap.Error(err))
	}
	simulateTaskExecution(task, run)
	completedAt := time.Now()
	run.CompletedAt = &completedAt
	run.Phase = "completed"
	run.Progress = 1.0
	if err := s.db.Save(run).Error; err != nil {
		logger.Error("update run instance failed", zap.Error(err))
	}
	task.Status = "idle"
	entry := s.cron.Entry(s.taskMap[task.ID])
	next := entry.Next
	task.NextRun = &next
	if err := s.db.Save(task).Error; err != nil {
		logger.Error("update task failed", zap.Error(err))
	}
	logger.Info("task executed", zap.String("task_id", task.ID), zap.String("run_id", runID))
}

func simulateTaskExecution(task *models.Task, run *models.RunInstance) {
	for i := 0; i <= 100; i += 20 {
		run.Progress = float64(i) / 100.0
		time.Sleep(100 * time.Millisecond)
	}
}

func (s *Scheduler) CreateTask(ctx context.Context, task *models.Task) error {
	if task.ID == "" {
		task.ID = uuid.New().String()
	}
	task.CreatedAt = time.Now()
	task.UpdatedAt = time.Now()
	task.Status = "idle"
	if err := s.db.Create(task).Error; err != nil {
		return err
	}
	if task.Enabled {
		return s.scheduleTask(task)
	}
	return nil
}

func (s *Scheduler) GetTask(ctx context.Context, id string) (*models.Task, error) {
	var task models.Task
	if err := s.db.First(&task, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &task, nil
}

func (s *Scheduler) ListTasks(ctx context.Context) ([]models.Task, error) {
	var tasks []models.Task
	if err := s.db.Find(&tasks).Error; err != nil {
		return nil, err
	}
	return tasks, nil
}

func (s *Scheduler) UpdateTask(ctx context.Context, task *models.Task) error {
	existing, err := s.GetTask(ctx, task.ID)
	if err != nil {
		return err
	}
	task.UpdatedAt = time.Now()
	if err := s.db.Save(task).Error; err != nil {
		return err
	}
	if task.Enabled != existing.Enabled || task.CronExpr != existing.CronExpr {
		if task.Enabled {
			return s.scheduleTask(task)
		} else {
			s.mu.Lock()
			defer s.mu.Unlock()
			if entryID, ok := s.taskMap[task.ID]; ok {
				s.cron.Remove(entryID)
				delete(s.taskMap, task.ID)
			}
		}
	}
	return nil
}

func (s *Scheduler) DeleteTask(ctx context.Context, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if entryID, ok := s.taskMap[id]; ok {
		s.cron.Remove(entryID)
		delete(s.taskMap, id)
	}
	if err := s.db.Delete(&models.Task{}, "id = ?", id).Error; err != nil {
		return err
	}
	return nil
}

func (s *Scheduler) TriggerTask(ctx context.Context, id string) error {
	var task models.Task
	if err := s.db.First(&task, "id = ?", id).Error; err != nil {
		return err
	}
	if !task.Enabled {
		return errors.New("task is disabled")
	}
	go s.executeTask(&task)
	return nil
}

func (s *Scheduler) GetRunHistory(ctx context.Context, taskID string, limit int) ([]models.RunInstance, error) {
	var runs []models.RunInstance
	if err := s.db.Where("entity_id = ?", taskID).Order("started_at desc").Limit(limit).Find(&runs).Error; err != nil {
		return nil, err
	}
	return runs, nil
}
