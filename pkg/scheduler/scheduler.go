package scheduler

import (
	"context"
	"go.uber.org/zap"
	"metricplatform/internal/models"
	"metricplatform/pkg/dataaccess"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
)

type TaskHandler func(ctx context.Context, task *models.Task, run *models.TaskRun) error

type Scheduler struct {
	repo        *dataaccess.Repository
	tasks       map[string]*models.Task
	cron        *cron.Cron
	handlers    map[string]TaskHandler
	runningJobs map[string]cron.EntryID
	logger      *zap.Logger
	mu          sync.RWMutex
	ctx         context.Context
	cancel      context.CancelFunc
}

func NewScheduler(repo *dataaccess.Repository, logger *zap.Logger) *Scheduler {
	ctx, cancel := context.WithCancel(context.Background())
	return &Scheduler{
		repo:        repo,
		tasks:       make(map[string]*models.Task),
		cron:        cron.New(cron.WithSeconds()),
		handlers:    make(map[string]TaskHandler),
		runningJobs: make(map[string]cron.EntryID),
		logger:      logger,
		ctx:         ctx,
		cancel:      cancel,
	}
}

func (s *Scheduler) RegisterHandler(taskType string, handler TaskHandler) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.handlers[taskType] = handler
	s.logger.Info("Task handler registered", zap.String("type", taskType))
}

func (s *Scheduler) AddTask(task *models.Task) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if task.ID == "" {
		task.ID = uuid.New().String()
	}

	if task.CronExpr == "" {
		task.CronExpr = "@every 1h"
	}

	if task.Status == "" {
		task.Status = "active"
	}

	if err := s.repo.SaveTask(task); err != nil {
		return err
	}

	s.tasks[task.ID] = task

	if task.Status == "active" {
		if err := s.scheduleTask(task); err != nil {
			return err
		}
	}

	s.logger.Info("Task added", zap.String("task_id", task.ID), zap.String("name", task.Name), zap.String("cron", task.CronExpr))
	return nil
}

func (s *Scheduler) scheduleTask(task *models.Task) error {
	entryID, err := s.cron.AddFunc(task.CronExpr, func() {
		s.executeTask(task.ID)
	})
	if err != nil {
		return err
	}

	s.runningJobs[task.ID] = entryID

	nextRun := s.cron.Entry(entryID).Next
	task.NextRunAt = &nextRun
	if err := s.repo.UpdateTask(task); err != nil {
		s.logger.Error("Failed to update task next run time", zap.Error(err))
	}

	return nil
}

func (s *Scheduler) executeTask(taskID string) {
	s.mu.RLock()
	task, exists := s.tasks[taskID]
	handler, hasHandler := s.handlers[task.Type]
	s.mu.RUnlock()

	if !exists || task.Status != "active" {
		return
	}

	run := &models.TaskRun{
		TaskID:   taskID,
		Status:   "running",
		Progress: 0.0,
	}

	if err := s.repo.SaveTaskRun(run); err != nil {
		s.logger.Error("Failed to create task run", zap.Error(err))
		return
	}

	s.logger.Info("Task execution started",
		zap.String("task_id", taskID),
		zap.String("run_id", run.ID),
		zap.String("task_type", task.Type))

	ctx, cancel := context.WithTimeout(s.ctx, 1*time.Hour)
	defer cancel()

	var runErr error
	if hasHandler {
		runErr = handler(ctx, task, run)
	} else {
		runErr = s.defaultHandler(ctx, task, run)
	}

	now := time.Now()
	run.CompletedAt = &now

	if runErr != nil {
		errMsg := runErr.Error()
		run.Status = "failed"
		run.Error = &errMsg
		s.logger.Error("Task execution failed",
			zap.String("task_id", taskID),
			zap.String("run_id", run.ID),
			zap.Error(runErr))
	} else {
		run.Status = "completed"
		run.Progress = 1.0
		s.logger.Info("Task execution completed",
			zap.String("task_id", taskID),
			zap.String("run_id", run.ID))
	}

	if err := s.repo.UpdateTaskRun(run); err != nil {
		s.logger.Error("Failed to update task run", zap.Error(err))
	}

	s.mu.Lock()
	lastRun := now
	task.LastRunAt = &lastRun
	if entryID, exists := s.runningJobs[taskID]; exists {
		nextRun := s.cron.Entry(entryID).Next
		task.NextRunAt = &nextRun
	}
	s.mu.Unlock()

	if err := s.repo.UpdateTask(task); err != nil {
		s.logger.Error("Failed to update task", zap.Error(err))
	}
}

func (s *Scheduler) defaultHandler(ctx context.Context, task *models.Task, run *models.TaskRun) error {
	s.logger.Debug("Default handler executing", zap.String("task_id", task.ID))

	for i := 0; i <= 10; i++ {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}

		run.Progress = float64(i) / 10.0
		if err := s.repo.UpdateTaskRun(run); err != nil {
			s.logger.Warn("Failed to update task progress", zap.Error(err))
		}
		time.Sleep(100 * time.Millisecond)
	}

	run.Output = map[string]interface{}{
		"result":   "success",
		"executed": time.Now().Format(time.RFC3339),
	}

	return nil
}

func (s *Scheduler) Start() {
	s.cron.Start()
	s.logger.Info("Scheduler started")
}

func (s *Scheduler) Stop() {
	s.cancel()
	s.cron.Stop()
	s.logger.Info("Scheduler stopped")
}

func (s *Scheduler) PauseTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, exists := s.tasks[taskID]
	if !exists {
		return nil
	}

	if entryID, exists := s.runningJobs[taskID]; exists {
		s.cron.Remove(entryID)
		delete(s.runningJobs, taskID)
	}

	task.Status = "paused"
	if err := s.repo.UpdateTask(task); err != nil {
		return err
	}

	s.logger.Info("Task paused", zap.String("task_id", taskID))
	return nil
}

func (s *Scheduler) ResumeTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, exists := s.tasks[taskID]
	if !exists {
		return nil
	}

	task.Status = "active"
	if err := s.repo.UpdateTask(task); err != nil {
		return err
	}

	if err := s.scheduleTask(task); err != nil {
		return err
	}

	s.logger.Info("Task resumed", zap.String("task_id", taskID))
	return nil
}

func (s *Scheduler) RemoveTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if entryID, exists := s.runningJobs[taskID]; exists {
		s.cron.Remove(entryID)
		delete(s.runningJobs, taskID)
	}

	delete(s.tasks, taskID)
	s.logger.Info("Task removed", zap.String("task_id", taskID))
	return nil
}

func (s *Scheduler) GetTaskStatus(taskID string) (*models.Task, []models.TaskRun, error) {
	s.mu.RLock()
	task, exists := s.tasks[taskID]
	s.mu.RUnlock()

	if !exists {
		return nil, nil, nil
	}

	var runs []models.TaskRun
	db := s.repo.GetDB()
	if err := db.Where("task_id = ?", taskID).Order("started_at DESC").Limit(10).Find(&runs).Error; err != nil {
		return nil, nil, err
	}

	return task, runs, nil
}

func (s *Scheduler) GetAllTasks() ([]models.Task, error) {
	return s.repo.GetTasks()
}

func (s *Scheduler) GetTaskRun(runID string) (*models.TaskRun, error) {
	var run models.TaskRun
	db := s.repo.GetDB()
	result := db.First(&run, "id = ?", runID)
	if result.Error != nil {
		return nil, result.Error
	}
	return &run, nil
}

func (s *Scheduler) UpdateTaskProgress(runID string, progress float64) error {
	run, err := s.GetTaskRun(runID)
	if err != nil {
		return err
	}

	run.Progress = progress
	return s.repo.UpdateTaskRun(run)
}

func (s *Scheduler) LoadTasks() error {
	tasks, err := s.repo.GetTasks()
	if err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	for i := range tasks {
		task := &tasks[i]
		s.tasks[task.ID] = task

		if task.Status == "active" {
			if err := s.scheduleTask(task); err != nil {
				s.logger.Error("Failed to schedule task", zap.String("task_id", task.ID), zap.Error(err))
			}
		}
	}

	s.logger.Info("Tasks loaded", zap.Int("count", len(tasks)))
	return nil
}
