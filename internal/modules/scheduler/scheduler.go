package scheduler

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
	apperrors "session189/pkg/errors"
)

type TaskHandler func(ctx context.Context, task *domain.Task) error

type ScheduledJob struct {
	JobID      string                 `json:"job_id" gorm:"primaryKey;type:varchar(64)"`
	Name       string                 `json:"name"`
	CronExpr   string                 `json:"cron_expr"`
	TaskType   domain.TaskType        `json:"task_type" gorm:"type:varchar(32)"`
	Parameters map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Enabled    bool                   `json:"enabled" gorm:"index"`
	LastRunAt  *time.Time             `json:"last_run_at,omitempty"`
	NextRunAt  *time.Time             `json:"next_run_at,omitempty"`
	CreatedBy  string                 `json:"created_by"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

func (ScheduledJob) TableName() string { return "scheduled_jobs" }

type Scheduler struct {
	cron       *cron.Cron
	jobEntries map[string]cron.EntryID
	mu         sync.RWMutex
	taskHandler TaskHandler
}

func NewScheduler() *Scheduler {
	c := cron.New(
		cron.WithSeconds(),
		cron.WithChain(
			cron.SkipIfStillRunning(cron.DefaultLogger),
			cron.Recover(cron.DefaultLogger),
		),
	)
	return &Scheduler{
		cron:       c,
		jobEntries: make(map[string]cron.EntryID),
	}
}

func (s *Scheduler) SetTaskHandler(handler TaskHandler) {
	s.taskHandler = handler
}

func (s *Scheduler) Start() error {
	jobs, err := s.listEnabledJobs()
	if err != nil {
		return apperrors.Internal("list enabled jobs failed", err)
	}

	for _, job := range jobs {
		jobCopy := job
		if err := s.scheduleJob(&jobCopy); err != nil {
			logger.Error("Failed to schedule job", zap.String("job_id", job.JobID), zap.Error(err))
			continue
		}
	}

	s.cron.Start()
	logger.Info("Scheduler started", zap.Int("job_count", len(s.jobEntries)))
	return nil
}

func (s *Scheduler) Stop() {
	s.cron.Stop()
	logger.Info("Scheduler stopped")
}

func (s *Scheduler) scheduleJob(job *ScheduledJob) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if entryID, exists := s.jobEntries[job.JobID]; exists {
		s.cron.Remove(entryID)
		delete(s.jobEntries, job.JobID)
	}

	entryID, err := s.cron.AddFunc(job.CronExpr, func() {
		s.executeJob(job)
	})
	if err != nil {
		return apperrors.Internal("add cron job failed", err)
	}

	s.jobEntries[job.JobID] = entryID

	nextTime := s.cron.Entry(entryID).Next
	job.NextRunAt = &nextTime
	_ = database.DB.Model(job).Update("next_run_at", nextTime).Error

	logger.Info("Job scheduled",
		zap.String("job_id", job.JobID),
		zap.String("name", job.Name),
		zap.String("cron", job.CronExpr),
		zap.Time("next_run", nextTime))

	return nil
}

func (s *Scheduler) executeJob(job *ScheduledJob) {
	ctx := context.Background()
	now := time.Now()

	logger.Info("Executing scheduled job",
		zap.String("job_id", job.JobID),
		zap.String("name", job.Name))

	task := s.createTaskFromJob(job, fmt.Sprintf("%s-%d", job.Name, now.Unix()), "scheduler")
	if err := database.DB.Create(task).Error; err != nil {
		logger.Error("Failed to create task from job",
			zap.String("job_id", job.JobID),
			zap.Error(err))
		return
	}

	s.updateJobRunTimes(job, now)
	s.dispatchTask(ctx, task, job.JobID)
}

func (s *Scheduler) CreateJob(ctx context.Context, job *ScheduledJob) (*ScheduledJob, error) {
	job.JobID = uuid.New().String()
	job.CreatedAt = time.Now()
	job.UpdatedAt = time.Now()

	if _, err := cron.ParseStandard(job.CronExpr); err != nil {
		return nil, apperrors.InvalidInput(fmt.Sprintf("invalid cron expression: %v", err))
	}

	if err := database.DB.WithContext(ctx).Create(job).Error; err != nil {
		return nil, apperrors.Internal("create job failed", err)
	}

	if job.Enabled {
		if err := s.scheduleJob(job); err != nil {
			return job, err
		}
	}

	logger.Info("Job created", zap.String("job_id", job.JobID), zap.String("name", job.Name))
	return job, nil
}

func (s *Scheduler) UpdateJob(ctx context.Context, jobID string, updates map[string]interface{}) (*ScheduledJob, error) {
	var job ScheduledJob
	if err := database.DB.WithContext(ctx).Where("job_id = ?", jobID).First(&job).Error; err != nil {
		return nil, apperrors.Internal("job not found", err)
	}

	updates["updated_at"] = time.Now()
	if err := database.DB.WithContext(ctx).Model(&job).Updates(updates).Error; err != nil {
		return nil, apperrors.Internal("update job failed", err)
	}

	if err := database.DB.WithContext(ctx).Where("job_id = ?", jobID).First(&job).Error; err != nil {
		return nil, apperrors.Internal("reload job failed", err)
	}

	if job.Enabled {
		if err := s.scheduleJob(&job); err != nil {
			return &job, err
		}
	} else {
		s.removeJobEntry(jobID)
	}

	return &job, nil
}

func (s *Scheduler) DeleteJob(ctx context.Context, jobID string) error {
	s.removeJobEntry(jobID)

	if err := database.DB.WithContext(ctx).Where("job_id = ?", jobID).Delete(&ScheduledJob{}).Error; err != nil {
		return apperrors.Internal("delete job failed", err)
	}

	logger.Info("Job deleted", zap.String("job_id", jobID))
	return nil
}

func (s *Scheduler) GetJob(ctx context.Context, jobID string) (*ScheduledJob, error) {
	var job ScheduledJob
	if err := database.DB.WithContext(ctx).Where("job_id = ?", jobID).First(&job).Error; err != nil {
		return nil, apperrors.Internal("get job failed", err)
	}
	return &job, nil
}

func (s *Scheduler) ListJobs(ctx context.Context, offset, limit int) ([]ScheduledJob, int64, error) {
	var jobs []ScheduledJob
	var total int64

	if err := database.DB.WithContext(ctx).Model(&ScheduledJob{}).Count(&total).Error; err != nil {
		return nil, 0, apperrors.Internal("count jobs failed", err)
	}

	if err := database.DB.WithContext(ctx).Order("created_at DESC").Offset(offset).Limit(limit).Find(&jobs).Error; err != nil {
		return nil, 0, apperrors.Internal("list jobs failed", err)
	}

	return jobs, total, nil
}

func (s *Scheduler) TriggerJob(ctx context.Context, jobID string) (*domain.Task, error) {
	var job ScheduledJob
	if err := database.DB.WithContext(ctx).Where("job_id = ?", jobID).First(&job).Error; err != nil {
		return nil, apperrors.Internal("job not found", err)
	}

	now := time.Now()
	task := s.createTaskFromJob(&job, fmt.Sprintf("%s-manual-%d", job.Name, now.Unix()), "manual")
	if err := database.DB.WithContext(ctx).Create(task).Error; err != nil {
		return nil, apperrors.Internal("create task failed", err)
	}

	s.dispatchTask(ctx, task, jobID)
	logger.Info("Job triggered manually", zap.String("job_id", jobID), zap.String("task_id", task.TaskID))
	return task, nil
}

func (s *Scheduler) listEnabledJobs() ([]ScheduledJob, error) {
	var jobs []ScheduledJob
	if err := database.DB.Where("enabled = ?", true).Find(&jobs).Error; err != nil {
		return nil, err
	}
	return jobs, nil
}

func (s *Scheduler) removeJobEntry(jobID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if entryID, exists := s.jobEntries[jobID]; exists {
		s.cron.Remove(entryID)
		delete(s.jobEntries, jobID)
	}
}

func (s *Scheduler) createTaskFromJob(job *ScheduledJob, name string, createdBy string) *domain.Task {
	now := time.Now()
	return &domain.Task{
		TaskID:     uuid.New().String(),
		Name:       name,
		Type:       job.TaskType,
		Status:     domain.TaskStatusPending,
		Parameters: job.Parameters,
		CreatedBy:  createdBy,
		CreatedAt:  now,
		UpdatedAt:  now,
	}
}

func (s *Scheduler) updateJobRunTimes(job *ScheduledJob, now time.Time) {
	job.LastRunAt = &now
	if entryID, exists := s.jobEntries[job.JobID]; exists {
		nextTime := s.cron.Entry(entryID).Next
		job.NextRunAt = &nextTime
	}
	_ = database.DB.Model(job).Updates(map[string]interface{}{
		"last_run_at": job.LastRunAt,
		"next_run_at": job.NextRunAt,
	}).Error
}

func (s *Scheduler) dispatchTask(ctx context.Context, task *domain.Task, jobID string) {
	if s.taskHandler != nil {
		go func() {
			if err := s.taskHandler(ctx, task); err != nil {
				logger.Error("Task execution failed",
					zap.String("task_id", task.TaskID),
					zap.String("job_id", jobID),
					zap.Error(err))
			}
		}()
	}
}
