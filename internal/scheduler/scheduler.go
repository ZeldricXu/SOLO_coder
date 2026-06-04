package scheduler

import (
	"context"
	"fmt"
	"time"

	"github.com/distributed-task-scheduler/internal/config"
	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/internal/storage"
	"github.com/distributed-task-scheduler/pkg/cron"
	"github.com/distributed-task-scheduler/pkg/lock"
	"github.com/google/uuid"
)

type Scheduler struct {
	db      *storage.Database
	redis   *storage.RedisClient
	locker  *lock.DistributedLock
	cfg     config.SchedulerConfig
	nodeID  string
	ctx     context.Context
	cancel  context.CancelFunc
	jobChan chan *models.Task
}

func NewScheduler(db *storage.Database, redis *storage.RedisClient, cfg config.SchedulerConfig, nodeID string) *Scheduler {
	ctx, cancel := context.WithCancel(context.Background())
	return &Scheduler{
		db:      db,
		redis:   redis,
		locker:  lock.NewDistributedLock(redis.Client),
		cfg:     cfg,
		nodeID:  nodeID,
		ctx:     ctx,
		cancel:  cancel,
		jobChan: make(chan *models.Task, 1000),
	}
}

func (s *Scheduler) JobChannel() <-chan *models.Task {
	return s.jobChan
}

func (s *Scheduler) Start() {
	go s.triggerLoop()
	go s.scanLoop()
}

func (s *Scheduler) Stop() {
	s.cancel()
	close(s.jobChan)
}

func (s *Scheduler) triggerLoop() {
	ticker := time.NewTicker(s.cfg.TriggerInterval)
	defer ticker.Stop()

	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			s.triggerDueTasks()
		}
	}
}

func (s *Scheduler) scanLoop() {
	ticker := time.NewTicker(s.cfg.TaskScanInterval)
	defer ticker.Stop()

	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			s.updateNextRunTimes()
		}
	}
}

func (s *Scheduler) triggerDueTasks() {
	now := time.Now()
	shard := s.getNodeShard()

	query := `
		SELECT * FROM tasks 
		WHERE status = 'active' 
		AND next_run_at <= $1 
		AND next_run_at IS NOT NULL
		AND abs(hashtext(id) % $2) = $3
		FOR UPDATE SKIP LOCKED
	`

	var tasks []models.Task
	err := s.db.Select(&tasks, query, now, s.cfg.ShardCount, shard)
	if err != nil {
		fmt.Printf("Failed to query due tasks: %v\n", err)
		return
	}

	for _, task := range tasks {
		lockKey := fmt.Sprintf("task:lock:%s", task.ID)
		lock, err := s.locker.Acquire(s.ctx, lockKey, s.cfg.LockTTL)
		if err != nil {
			continue
		}

		go s.processTask(&task, lock)
	}
}

func (s *Scheduler) processTask(task *models.Task, lock *lock.Lock) {
	defer lock.Release()

	s.createExecution(task)

	s.calculateNextRunTime(task)

	updateQuery := `
		UPDATE tasks 
		SET next_run_at = $2, last_run_at = $3, updated_at = NOW()
		WHERE id = $1
	`
	_, err := s.db.Exec(updateQuery, task.ID, task.NextRunAt, task.LastRunAt)
	if err != nil {
		fmt.Printf("Failed to update task next run time: %v\n", err)
	}

	select {
	case s.jobChan <- task:
	default:
		fmt.Printf("Job channel full, dropping task: %s\n", task.ID)
	}
}

func (s *Scheduler) createExecution(task *models.Task) *models.Execution {
	execution := &models.Execution{
		ID:           uuid.New().String(),
		TaskID:       task.ID,
		Namespace:    task.Namespace,
		Status:       models.ExecutionStatusPending,
		InputPayload: task.Payload,
		CreatedAt:    time.Now(),
	}

	query := `
		INSERT INTO executions (id, task_id, namespace, status, input_payload, created_at)
		VALUES ($1, $2, $3, $4, $5, $6)
	`

	_, err := s.db.Exec(query, execution.ID, execution.TaskID, execution.Namespace,
		execution.Status, execution.InputPayload, execution.CreatedAt)
	if err != nil {
		fmt.Printf("Failed to create execution: %v\n", err)
	}

	return execution
}

func (s *Scheduler) calculateNextRunTime(task *models.Task) {
	now := time.Now()
	task.LastRunAt = &now

	switch task.Type {
	case models.TaskTypeCron:
		schedule, err := cron.Parse(task.CronExpression)
		if err == nil {
			next := schedule.Next(now)
			task.NextRunAt = &next
		}
	case models.TaskTypeDelay:
		if task.IntervalSeconds > 0 {
			next := now.Add(time.Duration(task.IntervalSeconds) * time.Second)
			task.NextRunAt = &next
		} else {
			task.Status = models.TaskTypeOneShot
			task.NextRunAt = nil
		}
	case models.TaskTypeOneShot:
		task.Status = models.TaskStatusDisabled
		task.NextRunAt = nil
	}
}

func (s *Scheduler) updateNextRunTimes() {
	query := `
		SELECT * FROM tasks 
		WHERE status = 'active' 
		AND (next_run_at IS NULL OR next_run_at > NOW())
		LIMIT 1000
	`

	var tasks []models.Task
	err := s.db.Select(&tasks, query)
	if err != nil {
		fmt.Printf("Failed to scan tasks: %v\n", err)
		return
	}

	for _, task := range tasks {
		if task.NextRunAt == nil {
			s.calculateNextRunTime(&task)
			updateQuery := `UPDATE tasks SET next_run_at = $2, updated_at = NOW() WHERE id = $1`
			s.db.Exec(updateQuery, task.ID, task.NextRunAt)
		}
	}
}

func (s *Scheduler) getNodeShard() int {
	return cron.GetShard(s.nodeID, s.cfg.ShardCount)
}

func (s *Scheduler) TriggerTaskNow(taskID string) (*models.Execution, error) {
	var task models.Task
	err := s.db.Get(&task, "SELECT * FROM tasks WHERE id = $1", taskID)
	if err != nil {
		return nil, fmt.Errorf("task not found: %w", err)
	}

	if task.Status != models.TaskStatusActive && task.Status != models.TaskStatusPaused {
		return nil, fmt.Errorf("task is not triggerable")
	}

	execution := s.createExecution(&task)

	go func() {
		select {
		case s.jobChan <- &task:
		default:
			fmt.Printf("Job channel full, dropping manual trigger: %s\n", task.ID)
		}
	}()

	return execution, nil
}
