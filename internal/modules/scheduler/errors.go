package scheduler

import "errors"

var (
	ErrQueueFull       = errors.New("task queue is full")
	ErrJobNotFound     = errors.New("job not found")
	ErrInvalidSchedule = errors.New("invalid schedule expression")
	ErrTaskTimeout     = errors.New("task execution timeout")
	ErrSchedulerClosed = errors.New("scheduler is closed")
)
