package scheduler

import (
	"context"
	"time"
)

type JobType string

const (
	JobTypeOnce    JobType = "once"
	JobTypeCron    JobType = "cron"
	JobTypeInterval JobType = "interval"
)

type JobStatus string

const (
	JobStatusActive   JobStatus = "active"
	JobStatusPaused   JobStatus = "paused"
	JobStatusComplete JobStatus = "complete"
	JobStatusFailed   JobStatus = "failed"
)

type ScheduledJob struct {
	ID           string                 `json:"id"`
	Name         string                 `json:"name"`
	Type         JobType                `json:"type"`
	CronExpr     string                 `json:"cron_expr,omitempty"`
	IntervalMs   int64                  `json:"interval_ms,omitempty"`
	Payload      map[string]interface{} `json:"payload"`
	Status       JobStatus              `json:"status"`
	LastRunAt    *time.Time             `json:"last_run_at,omitempty"`
	NextRunAt    *time.Time             `json:"next_run_at,omitempty"`
	Handler      JobHandler             `json:"-"`
	CreatedAt    time.Time              `json:"created_at"`
}

type JobHandler func(ctx context.Context, job *ScheduledJob) error

type JobExecution struct {
	ID        string    `json:"id"`
	JobID     string    `json:"job_id"`
	StartedAt time.Time `json:"started_at"`
	EndedAt   *time.Time `json:"ended_at,omitempty"`
	Status    string    `json:"status"`
	Error     string    `json:"error,omitempty"`
}
