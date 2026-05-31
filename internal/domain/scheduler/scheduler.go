package scheduler

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
)

type TaskSchedulerImpl struct {
	cron        *cron.Cron
	jobs        map[string]*ScheduledJob
	executions  map[string][]*JobExecution
	entries     map[string]cron.EntryID
	mu          sync.RWMutex
	logger      domain.Logger
	intervalCtx map[string]context.CancelFunc
}

func NewTaskSchedulerImpl(logger domain.Logger) *TaskSchedulerImpl {
	return &TaskSchedulerImpl{
		cron:        cron.New(),
		jobs:        make(map[string]*ScheduledJob),
		executions:  make(map[string][]*JobExecution),
		entries:     make(map[string]cron.EntryID),
		logger:      logger,
		intervalCtx: make(map[string]context.CancelFunc),
	}
}

func (s *TaskSchedulerImpl) Schedule(ctx context.Context, job *ScheduledJob) (string, error) {
	if job == nil {
		return "", errors.New(errors.ErrCodeValidation, "job cannot be nil")
	}
	if job.Name == "" {
		return "", errors.New(errors.ErrCodeValidation, "job name required")
	}
	if job.Handler == nil {
		return "", errors.New(errors.ErrCodeValidation, "job handler required")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	job.ID = uuid.New().String()
	job.Status = JobStatusActive
	job.CreatedAt = time.Now()

	switch job.Type {
	case JobTypeCron:
		if job.CronExpr == "" {
			return "", errors.New(errors.ErrCodeValidation, "cron expression required for cron jobs")
		}
		entryID, err := s.cron.AddFunc(job.CronExpr, func() {
			s.executeJob(job)
		})
		if err != nil {
			return "", errors.Wrap(err, errors.ErrCodeValidation, "invalid cron expression")
		}
		s.entries[job.ID] = entryID
		entry := s.cron.Entry(entryID)
		nextTime := entry.Next
		job.NextRunAt = &nextTime

	case JobTypeInterval:
		if job.IntervalMs <= 0 {
			return "", errors.New(errors.ErrCodeValidation, "interval must be positive")
		}
		go s.runIntervalJob(job)

	case JobTypeOnce:
		go s.executeJob(job)

	default:
		return "", errors.New(errors.ErrCodeValidation,
			fmt.Sprintf("unknown job type: %s", job.Type))
	}

	s.jobs[job.ID] = job
	s.logger.Info("Job scheduled",
		domain.String("job_id", job.ID),
		domain.String("name", job.Name),
		domain.String("type", string(job.Type)),
	)

	return job.ID, nil
}

func (s *TaskSchedulerImpl) executeJob(job *ScheduledJob) {
	ctx := context.Background()
	execution := &JobExecution{
		ID:        uuid.New().String(),
		JobID:     job.ID,
		StartedAt: time.Now(),
		Status:    "running",
	}

	s.logger.Info("Job execution started",
		domain.String("job_id", job.ID),
		domain.String("execution_id", execution.ID),
	)

	err := job.Handler(ctx, job)

	now := time.Now()
	execution.EndedAt = &now

	if err != nil {
		execution.Status = "failed"
		execution.Error = err.Error()
		s.logger.Error("Job execution failed",
			domain.String("job_id", job.ID),
			domain.Error(err),
		)
	} else {
		execution.Status = "completed"
		s.logger.Info("Job execution completed",
			domain.String("job_id", job.ID),
		)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	job.LastRunAt = &now
	if job.Type == JobTypeOnce {
		job.Status = JobStatusComplete
	}

	s.executions[job.ID] = append(s.executions[job.ID], execution)
}

func (s *TaskSchedulerImpl) runIntervalJob(job *ScheduledJob) {
	ctx, cancel := context.WithCancel(context.Background())

	s.mu.Lock()
	s.intervalCtx[job.ID] = cancel
	s.mu.Unlock()

	ticker := time.NewTicker(time.Duration(job.IntervalMs) * time.Millisecond)
	defer ticker.Stop()

	nextTime := time.Now().Add(time.Duration(job.IntervalMs) * time.Millisecond)
	job.NextRunAt = &nextTime

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.executeJob(job)
			nextTime := time.Now().Add(time.Duration(job.IntervalMs) * time.Millisecond)

			s.mu.Lock()
			job.NextRunAt = &nextTime
			s.mu.Unlock()
		}
	}
}

func (s *TaskSchedulerImpl) Unschedule(ctx context.Context, jobID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	job, exists := s.jobs[jobID]
	if !exists {
		return errors.New(errors.ErrCodeNotFound, "job not found")
	}

	if entryID, exists := s.entries[jobID]; exists {
		s.cron.Remove(entryID)
		delete(s.entries, jobID)
	}

	if cancel, exists := s.intervalCtx[jobID]; exists {
		cancel()
		delete(s.intervalCtx, jobID)
	}

	job.Status = JobStatusPaused
	s.logger.Info("Job unscheduled", domain.String("job_id", jobID))
	return nil
}

func (s *TaskSchedulerImpl) List(ctx context.Context) ([]*ScheduledJob, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	jobs := make([]*ScheduledJob, 0, len(s.jobs))
	for _, job := range s.jobs {
		jobs = append(jobs, job)
	}

	return jobs, nil
}

func (s *TaskSchedulerImpl) Trigger(ctx context.Context, jobID string) error {
	s.mu.RLock()
	job, exists := s.jobs[jobID]
	s.mu.RUnlock()

	if !exists {
		return errors.New(errors.ErrCodeNotFound, "job not found")
	}

	go s.executeJob(job)
	return nil
}

func (s *TaskSchedulerImpl) Start() {
	s.cron.Start()
	s.logger.Info("Task scheduler started")
}

func (s *TaskSchedulerImpl) Shutdown(ctx context.Context) error {
	s.cron.Stop()

	s.mu.Lock()
	defer s.mu.Unlock()

	for _, cancel := range s.intervalCtx {
		cancel()
	}

	s.logger.Info("Task scheduler shutdown")
	return nil
}

func (s *TaskSchedulerImpl) GetJobExecutions(jobID string) ([]*JobExecution, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	executions, exists := s.executions[jobID]
	if !exists {
		return nil, errors.New(errors.ErrCodeNotFound, "job not found")
	}

	return executions, nil
}
