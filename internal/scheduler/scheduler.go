package scheduler

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"techplatform/internal/dao"
	"techplatform/pkg/common"
	"techplatform/pkg/common/logger"
	"techplatform/pkg/common/utils"
	"techplatform/pkg/models"

	"github.com/robfig/cron/v3"
	"gorm.io/gorm"
)

type TaskType string

const (
	TaskTypeCron     TaskType = "cron"
	TaskTypeInterval TaskType = "interval"
	TaskTypeOnce     TaskType = "once"
)

type TaskPriority int

const (
	PriorityLow    TaskPriority = 1
	PriorityNormal TaskPriority = 5
	PriorityHigh   TaskPriority = 8
	PriorityUrgent TaskPriority = 10
)

type TaskStatus string

const (
	StatusPending  TaskStatus = "pending"
	StatusRunning  TaskStatus = "running"
	StatusSuccess  TaskStatus = "success"
	StatusFailed   TaskStatus = "failed"
	StatusPaused   TaskStatus = "paused"
	StatusCanceled TaskStatus = "canceled"
	StatusTimeout  TaskStatus = "timeout"
)

type Task struct {
	models.BaseModel
	Name        string       `json:"name" gorm:"index"`
	Type        TaskType     `json:"type"`
	Description string       `json:"description"`
	CronExpr    string       `json:"cron_expr"`
	Interval    int          `json:"interval"`
	Timeout     int          `json:"timeout"`
	Priority    TaskPriority `json:"priority"`
	MaxRetry    int          `json:"max_retry"`
	RetryCount  int          `json:"retry_count"`
	Handler     string       `json:"handler"`
	Params      string       `json:"params"`
	Status      TaskStatus   `json:"status" gorm:"index"`
	LastRunAt   *time.Time   `json:"last_run_at"`
	NextRunAt   *time.Time   `json:"next_run_at"`
	LastResult  string       `json:"last_result"`
	LastError   string       `json:"last_error"`
	Enabled     bool         `json:"enabled" gorm:"index"`
	CreatedBy   string       `json:"created_by"`
	cronID      cron.EntryID
}

type TaskExecution struct {
	models.BaseModel
	TaskID    string     `json:"task_id" gorm:"index"`
	TaskName  string     `json:"task_name"`
	Status    TaskStatus `json:"status" gorm:"index"`
	StartedAt time.Time  `json:"started_at"`
	EndedAt   *time.Time `json:"ended_at"`
	Duration  int64      `json:"duration"`
	Result    string     `json:"result"`
	Error     string     `json:"error"`
	Retry     int        `json:"retry"`
	Node      string     `json:"node"`
}

type TaskHandler func(ctx context.Context, params map[string]interface{}) (string, error)

type Scheduler struct {
	mu          sync.RWMutex
	db          *dao.DAO
	cron        *cron.Cron
	handlers    map[string]TaskHandler
	tasks       map[string]*Task
	running     map[string]context.CancelFunc
	workerPool  chan struct{}
	stopChan    chan struct{}
	started     bool
	config      SchedulerConfig
	metrics     *SchedulerMetrics
}

type SchedulerConfig struct {
	WorkerCount int
	QueueSize   int
}

type SchedulerMetrics struct {
	TotalTasks     int64 `json:"total_tasks"`
	RunningTasks   int   `json:"running_tasks"`
	SuccessCount   int64 `json:"success_count"`
	FailedCount    int64 `json:"failed_count"`
	TotalExecutions int64 `json:"total_executions"`
}

type SchedulerStats struct {
	*SchedulerMetrics
	PendingCount   int64 `json:"pending_count"`
	PausedCount    int64 `json:"paused_count"`
	AvgDuration    int64 `json:"avg_duration_ms"`
}

func NewScheduler(db *dao.DAO, config SchedulerConfig) *Scheduler {
	if config.WorkerCount <= 0 {
		config.WorkerCount = 5
	}
	if config.QueueSize <= 0 {
		config.QueueSize = 1000
	}

	s := &Scheduler{
		db:         db,
		cron:       cron.New(cron.WithSeconds()),
		handlers:   make(map[string]TaskHandler),
		tasks:      make(map[string]*Task),
		running:    make(map[string]context.CancelFunc),
		workerPool: make(chan struct{}, config.WorkerCount),
		stopChan:   make(chan struct{}),
		config:     config,
		metrics:    &SchedulerMetrics{},
	}

	db.AutoMigrate(&Task{}, &TaskExecution{})
	return s
}

func (s *Scheduler) RegisterHandler(name string, handler TaskHandler) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.handlers[name] = handler
	logger.Info("Registered task handler: %s", name)
}

func (s *Scheduler) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.started {
		return errors.New("scheduler already started")
	}

	var tasks []Task
	if err := s.db.DB().Where("enabled = ?", true).Find(&tasks).Error; err != nil {
		return err
	}

	for i := range tasks {
		if err := s.scheduleTask(&tasks[i]); err != nil {
			logger.Warn("Failed to schedule task %s: %v", tasks[i].Name, err)
			continue
		}
		s.tasks[tasks[i].ID] = &tasks[i]
		s.metrics.TotalTasks++
	}

	s.cron.Start()
	s.started = true

	logger.Info("Scheduler started with %d tasks, %d workers", len(s.tasks), s.config.WorkerCount)
	return nil
}

func (s *Scheduler) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.started {
		return
	}

	close(s.stopChan)

	for taskID, cancel := range s.running {
		logger.Info("Canceling running task: %s", taskID)
		cancel()
	}

	ctx := s.cron.Stop()
	<-ctx.Done()

	s.started = false
	logger.Info("Scheduler stopped")
}

func (s *Scheduler) CreateTask(task *Task) (*Task, error) {
	if task.Name == "" {
		return nil, fmt.Errorf("%w: task name is required", common.ErrInvalidInput)
	}
	if task.Handler == "" {
		return nil, fmt.Errorf("%w: task handler is required", common.ErrInvalidInput)
	}

	s.mu.RLock()
	if _, exists := s.handlers[task.Handler]; !exists {
		s.mu.RUnlock()
		return nil, fmt.Errorf("%w: handler '%s' not registered", common.ErrInvalidInput, task.Handler)
	}
	s.mu.RUnlock()

	task.ID = utils.GenerateUUID()
	task.Status = StatusPending
	task.Enabled = true
	if task.MaxRetry <= 0 {
		task.MaxRetry = 3
	}
	if task.Priority == 0 {
		task.Priority = PriorityNormal
	}

	if err := s.db.DB().Create(task).Error; err != nil {
		return nil, err
	}

	s.mu.Lock()
	if s.started && task.Enabled {
		if err := s.scheduleTask(task); err != nil {
			s.mu.Unlock()
			return nil, err
		}
	}
	s.tasks[task.ID] = task
	s.metrics.TotalTasks++
	s.mu.Unlock()

	logger.Info("Task created: %s (type: %s)", task.Name, task.Type)
	return task, nil
}

func (s *Scheduler) scheduleTask(task *Task) error {
	switch task.Type {
	case TaskTypeCron:
		if task.CronExpr == "" {
			return fmt.Errorf("%w: cron expression required", common.ErrInvalidInput)
		}
		id, err := s.cron.AddFunc(task.CronExpr, func() {
			s.executeTask(task.ID)
		})
		if err != nil {
			return fmt.Errorf("invalid cron expression: %w", err)
		}
		task.cronID = id
		nextRun := s.cron.Entry(id).Next
		task.NextRunAt = &nextRun

	case TaskTypeInterval:
		if task.Interval <= 0 {
			return fmt.Errorf("%w: interval must be positive", common.ErrInvalidInput)
		}
		id := s.cron.Schedule(cron.Every(time.Duration(task.Interval)*time.Second), cron.FuncJob(func() {
			s.executeTask(task.ID)
		}))
		task.cronID = id
		nextRun := s.cron.Entry(id).Next
		task.NextRunAt = &nextRun

	case TaskTypeOnce:
		nextRun := time.Now().Add(time.Second)
		task.NextRunAt = &nextRun
		go func() {
			time.Sleep(time.Second)
			s.executeTask(task.ID)
		}()
	}

	return nil
}

func (s *Scheduler) executeTask(taskID string) {
	s.mu.RLock()
	task, exists := s.tasks[taskID]
	s.mu.RUnlock()

	if !exists || !task.Enabled {
		return
	}

	s.workerPool <- struct{}{}
	defer func() { <-s.workerPool }()

	s.mu.Lock()
	if _, running := s.running[taskID]; running {
		s.mu.Unlock()
		logger.Warn("Task %s is already running, skipping", task.Name)
		return
	}

	ctx, cancel := context.WithCancel(context.Background())
	timeout := task.Timeout
	if timeout > 0 {
		ctx, cancel = context.WithTimeout(ctx, time.Duration(timeout)*time.Second)
	}
	s.running[taskID] = cancel
	s.metrics.RunningTasks++
	s.metrics.TotalExecutions++
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.running, taskID)
		s.metrics.RunningTasks--
		s.mu.Unlock()
		cancel()
	}()

	execution := &TaskExecution{
		BaseModel: models.BaseModel{
			ID: utils.GenerateUUID(),
		},
		TaskID:    task.ID,
		TaskName:  task.Name,
		Status:    StatusRunning,
		StartedAt: time.Now(),
		Retry:     task.RetryCount,
	}
	s.db.DB().Create(execution)

	now := time.Now()
	task.LastRunAt = &now
	task.Status = StatusRunning
	s.db.DB().Save(task)

	s.mu.RLock()
	handler := s.handlers[task.Handler]
	s.mu.RUnlock()

	var params map[string]interface{}
	if task.Params != "" {
		utils.FromJSON(task.Params, &params)
	}

	resultChan := make(chan struct {
		result string
		err    error
	}, 1)

	go func() {
		defer func() {
			if r := recover(); r != nil {
				resultChan <- struct {
					result string
					err    error
				}{"", fmt.Errorf("panic: %v", r)}
			}
		}()
		result, err := handler(ctx, params)
		resultChan <- struct {
			result string
			err    error
		}{result, err}
	}()

	select {
	case <-ctx.Done():
		execution.Status = StatusTimeout
		execution.Error = ctx.Err().Error()
		task.Status = StatusFailed
		task.LastError = ctx.Err().Error()
		s.metrics.FailedCount++

	case res := <-resultChan:
		endTime := time.Now()
		execution.EndedAt = &endTime
		execution.Duration = endTime.Sub(execution.StartedAt).Milliseconds()

		if res.err != nil {
			execution.Status = StatusFailed
			execution.Error = res.err.Error()
			task.Status = StatusFailed
			task.LastError = res.err.Error()
			s.metrics.FailedCount++

			if task.RetryCount < task.MaxRetry {
				task.RetryCount++
				logger.Warn("Task %s failed, retry %d/%d: %v", task.Name, task.RetryCount, task.MaxRetry, res.err)
				go func() {
					time.Sleep(time.Duration(task.RetryCount*2) * time.Second)
					s.executeTask(taskID)
				}()
			}
		} else {
			execution.Status = StatusSuccess
			execution.Result = res.result
			task.Status = StatusSuccess
			task.LastResult = res.result
			task.RetryCount = 0
			s.metrics.SuccessCount++
			logger.Info("Task %s completed successfully in %dms", task.Name, execution.Duration)
		}
	}

	s.db.DB().Save(execution)

	if task.Type == TaskTypeCron || task.Type == TaskTypeInterval {
		entry := s.cron.Entry(task.cronID)
		nextRun := entry.Next
		task.NextRunAt = &nextRun
	} else if task.Type == TaskTypeOnce {
		task.Enabled = false
	}

	s.db.DB().Save(task)
	s.updateCache(task)
}

func (s *Scheduler) updateCache(task *Task) {
	cacheKey := fmt.Sprintf("task:%s", task.ID)
	s.db.Cache().Set(context.Background(), cacheKey, task, 5*time.Minute)
}

func (s *Scheduler) GetTask(taskID string) (*Task, error) {
	s.mu.RLock()
	if task, exists := s.tasks[taskID]; exists {
		s.mu.RUnlock()
		return task, nil
	}
	s.mu.RUnlock()

	var task Task
	if err := s.db.DB().First(&task, "id = ?", taskID).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, common.ErrNotFound
		}
		return nil, err
	}
	return &task, nil
}

func (s *Scheduler) ListTasks(page, pageSize int, status TaskStatus) (*models.PageResult, error) {
	var tasks []Task
	var total int64

	query := s.db.DB().Model(&Task{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&tasks).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Items:    tasks,
	}, nil
}

func (s *Scheduler) UpdateTask(taskID string, updates map[string]interface{}) (*Task, error) {
	task, err := s.GetTask(taskID)
	if err != nil {
		return nil, err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if enabled, ok := updates["enabled"].(bool); ok {
		if enabled && !task.Enabled {
			if err := s.scheduleTask(task); err != nil {
				return nil, err
			}
		} else if !enabled && task.Enabled && task.cronID > 0 {
			s.cron.Remove(task.cronID)
			task.cronID = 0
		}
		task.Enabled = enabled
	}

	if name, ok := updates["name"].(string); ok {
		task.Name = name
	}
	if cronExpr, ok := updates["cron_expr"].(string); ok {
		task.CronExpr = cronExpr
		if task.Enabled && task.Type == TaskTypeCron && task.cronID > 0 {
			s.cron.Remove(task.cronID)
			s.scheduleTask(task)
		}
	}
	if interval, ok := updates["interval"].(int); ok {
		task.Interval = interval
	}
	if params, ok := updates["params"].(string); ok {
		task.Params = params
	}

	if err := s.db.DB().Save(task).Error; err != nil {
		return nil, err
	}

	s.tasks[taskID] = task
	return task, nil
}

func (s *Scheduler) DeleteTask(taskID string) error {
	task, err := s.GetTask(taskID)
	if err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if task.cronID > 0 {
		s.cron.Remove(task.cronID)
	}

	if cancel, running := s.running[taskID]; running {
		cancel()
	}

	if err := s.db.DB().Delete(&Task{}, "id = ?", taskID).Error; err != nil {
		return err
	}

	delete(s.tasks, taskID)
	s.metrics.TotalTasks--
	s.db.InvalidateCache(context.Background(), fmt.Sprintf("task:%s", taskID))

	logger.Info("Task deleted: %s", task.Name)
	return nil
}

func (s *Scheduler) RunTaskNow(taskID string) error {
	task, err := s.GetTask(taskID)
	if err != nil {
		return err
	}

	if !task.Enabled {
		return fmt.Errorf("%w: task is disabled", common.ErrInvalidInput)
	}

	go s.executeTask(taskID)
	logger.Info("Task triggered manually: %s", task.Name)
	return nil
}

func (s *Scheduler) PauseTask(taskID string) error {
	_, err := s.UpdateTask(taskID, map[string]interface{}{"enabled": false})
	return err
}

func (s *Scheduler) ResumeTask(taskID string) error {
	_, err := s.UpdateTask(taskID, map[string]interface{}{"enabled": true})
	return err
}

func (s *Scheduler) GetTaskExecutions(taskID string, page, pageSize int) (*models.PageResult, error) {
	var executions []TaskExecution
	var total int64

	query := s.db.DB().Model(&TaskExecution{}).Where("task_id = ?", taskID)
	query.Count(&total)

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("started_at DESC").Find(&executions).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Items:    executions,
	}, nil
}

func (s *Scheduler) GetStats() *SchedulerStats {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var pendingCount, pausedCount int64
	var avgDuration int64

	s.db.DB().Model(&Task{}).Where("status = ?", StatusPending).Count(&pendingCount)
	s.db.DB().Model(&Task{}).Where("status = ?", StatusPaused).Count(&pausedCount)

	rows, _ := s.db.DB().Model(&TaskExecution{}).Where("status = ?", StatusSuccess).Select("AVG(duration)").Rows()
	if rows.Next() {
		rows.Scan(&avgDuration)
	}
	rows.Close()

	return &SchedulerStats{
		SchedulerMetrics: s.metrics,
		PendingCount:     pendingCount,
		PausedCount:      pausedCount,
		AvgDuration:      avgDuration,
	}
}
