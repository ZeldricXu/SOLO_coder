package scheduler

import (
	"context"
	"errors"
	"fmt"
	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"sync"
	"taskmanager/internal/logger"
	"taskmanager/pkg/models"
	"time"
)

type SchedulingStrategy interface {
	GetName() string
	GetCronExpr(task *models.Task) string
	ShouldExecute(task *models.Task, now time.Time) bool
	GetTimeout(task *models.Task) time.Duration
	GetMaxRetries(task *models.Task) int
	GetRetryDelay(task *models.Task, attempt int) time.Duration
}

type DefaultStrategy struct{}

func (s *DefaultStrategy) GetName() string                              { return "default" }
func (s *DefaultStrategy) GetCronExpr(task *models.Task) string          { return task.CronExpr }
func (s *DefaultStrategy) ShouldExecute(task *models.Task, now time.Time) bool { return true }
func (s *DefaultStrategy) GetTimeout(task *models.Task) time.Duration    { return 5 * time.Minute }
func (s *DefaultStrategy) GetMaxRetries(task *models.Task) int           { return 3 }
func (s *DefaultStrategy) GetRetryDelay(task *models.Task, attempt int) time.Duration {
	return time.Duration(1<<uint(attempt)) * time.Second
}

type BatchProcessingStrategy struct {
	BatchWindow time.Duration
	MinTasks    int
}

func (s *BatchProcessingStrategy) GetName() string { return "batch_processing" }
func (s *BatchProcessingStrategy) GetCronExpr(task *models.Task) string { return task.CronExpr }
func (s *BatchProcessingStrategy) ShouldExecute(task *models.Task, now time.Time) bool {
	return true
}
func (s *BatchProcessingStrategy) GetTimeout(task *models.Task) time.Duration { return 30 * time.Minute }
func (s *BatchProcessingStrategy) GetMaxRetries(task *models.Task) int      { return 1 }
func (s *BatchProcessingStrategy) GetRetryDelay(task *models.Task, attempt int) time.Duration {
	return 5 * time.Minute
}

type RealTimeStrategy struct{}

func (s *RealTimeStrategy) GetName() string                              { return "realtime" }
func (s *RealTimeStrategy) GetCronExpr(task *models.Task) string          { return task.CronExpr }
func (s *RealTimeStrategy) ShouldExecute(task *models.Task, now time.Time) bool { return true }
func (s *RealTimeStrategy) GetTimeout(task *models.Task) time.Duration    { return 30 * time.Second }
func (s *RealTimeStrategy) GetMaxRetries(task *models.Task) int           { return 5 }
func (s *RealTimeStrategy) GetRetryDelay(task *models.Task, attempt int) time.Duration {
	return time.Duration(attempt) * 500 * time.Millisecond
}

type IdempotentStrategy struct {
	DeduplicationWindow time.Duration
}

func (s *IdempotentStrategy) GetName() string { return "idempotent" }
func (s *IdempotentStrategy) GetCronExpr(task *models.Task) string { return task.CronExpr }
func (s *IdempotentStrategy) ShouldExecute(task *models.Task, now time.Time) bool {
	return true
}
func (s *IdempotentStrategy) GetTimeout(task *models.Task) time.Duration { return 10 * time.Minute }
func (s *IdempotentStrategy) GetMaxRetries(task *models.Task) int      { return 0 }
func (s *IdempotentStrategy) GetRetryDelay(task *models.Task, attempt int) time.Duration {
	return 0
}

type SchedulerConfig struct {
	DefaultStrategy     string            `json:"default_strategy"`
	Strategies          map[string]map[string]interface{} `json:"strategies"`
	ConcurrencyLimit    int               `json:"concurrency_limit"`
	QueueSize           int               `json:"queue_size"`
	DefaultTimeout      time.Duration     `json:"default_timeout"`
	EnableMetrics       bool              `json:"enable_metrics"`
}

type ConfigUpdateEvent struct {
	Config    *SchedulerConfig
	Timestamp time.Time
	ChangedBy string
}

type ConfigChangeListener func(event ConfigUpdateEvent)

type Scheduler struct {
	db            *gorm.DB
	cron          *cron.Cron
	taskMap       map[string]cron.EntryID
	mu            sync.RWMutex
	running       bool
	config        *SchedulerConfig
	strategies    map[string]SchedulingStrategy
	configMu      sync.RWMutex
	listeners     []ConfigChangeListener
	listenerMu    sync.RWMutex
	configVersion int64
}

func NewScheduler(db *gorm.DB) *Scheduler {
	s := &Scheduler{
		db:        db,
		cron:      cron.New(cron.WithSeconds()),
		taskMap:   make(map[string]cron.EntryID),
		strategies: make(map[string]SchedulingStrategy),
		config: &SchedulerConfig{
			DefaultStrategy:  "default",
			Strategies:       make(map[string]map[string]interface{}),
			ConcurrencyLimit: 10,
			QueueSize:        1000,
			DefaultTimeout:   5 * time.Minute,
			EnableMetrics:    true,
		},
	}
	s.registerDefaultStrategies()
	return s
}

func (s *Scheduler) registerDefaultStrategies() {
	s.strategies["default"] = &DefaultStrategy{}
	s.strategies["batch_processing"] = &BatchProcessingStrategy{
		BatchWindow: 5 * time.Minute,
		MinTasks:    10,
	}
	s.strategies["realtime"] = &RealTimeStrategy{}
	s.strategies["idempotent"] = &IdempotentStrategy{
		DeduplicationWindow: 1 * time.Hour,
	}
}

func (s *Scheduler) RegisterStrategy(name string, strategy SchedulingStrategy) {
	s.configMu.Lock()
	defer s.configMu.Unlock()
	s.strategies[name] = strategy
	logger.Info("scheduling strategy registered", zap.String("name", name))
}

func (s *Scheduler) UnregisterStrategy(name string) {
	s.configMu.Lock()
	defer s.configMu.Unlock()
	if name == "default" {
		return
	}
	delete(s.strategies, name)
	logger.Info("scheduling strategy unregistered", zap.String("name", name))
}

func (s *Scheduler) GetStrategy(name string) (SchedulingStrategy, error) {
	s.configMu.RLock()
	defer s.configMu.RUnlock()
	strategy, ok := s.strategies[name]
	if !ok {
		return nil, fmt.Errorf("strategy not found: %s", name)
	}
	return strategy, nil
}

func (s *Scheduler) ListStrategies() []string {
	s.configMu.RLock()
	defer s.configMu.RUnlock()
	names := make([]string, 0, len(s.strategies))
	for name := range s.strategies {
		names = append(names, name)
	}
	return names
}

func (s *Scheduler) GetConfig() *SchedulerConfig {
	s.configMu.RLock()
	defer s.configMu.RUnlock()
	configCopy := *s.config
	return &configCopy
}

func (s *Scheduler) UpdateConfig(newConfig *SchedulerConfig) error {
	s.configMu.Lock()
	defer s.configMu.Unlock()

	if newConfig == nil {
		return errors.New("config cannot be nil")
	}

	if newConfig.ConcurrencyLimit <= 0 {
		return errors.New("concurrency limit must be positive")
	}
	if newConfig.QueueSize <= 0 {
		return errors.New("queue size must be positive")
	}
	if newConfig.DefaultTimeout <= 0 {
		return errors.New("default timeout must be positive")
	}

	oldConfig := s.config
	s.config = newConfig
	s.configVersion++

	event := ConfigUpdateEvent{
		Config:    newConfig,
		Timestamp: time.Now(),
		ChangedBy: "system",
	}

	s.notifyConfigChange(event)
	logger.Info("scheduler config updated",
		zap.Int64("version", s.configVersion),
		zap.String("old_strategy", oldConfig.DefaultStrategy),
		zap.String("new_strategy", newConfig.DefaultStrategy),
		zap.Int("concurrency_limit", newConfig.ConcurrencyLimit),
	)
	return nil
}

func (s *Scheduler) UpdateConfigPartial(updates map[string]interface{}) error {
	s.configMu.Lock()
	defer s.configMu.Unlock()

	configCopy := *s.config

	if v, ok := updates["default_strategy"].(string); ok {
		if _, exists := s.strategies[v]; !exists {
			return fmt.Errorf("unknown strategy: %s", v)
		}
		configCopy.DefaultStrategy = v
	}
	if v, ok := updates["concurrency_limit"].(int); ok && v > 0 {
		configCopy.ConcurrencyLimit = v
	}
	if v, ok := updates["queue_size"].(int); ok && v > 0 {
		configCopy.QueueSize = v
	}
	if v, ok := updates["default_timeout"].(time.Duration); ok && v > 0 {
		configCopy.DefaultTimeout = v
	}
	if v, ok := updates["enable_metrics"].(bool); ok {
		configCopy.EnableMetrics = v
	}

	s.config = &configCopy
	s.configVersion++

	event := ConfigUpdateEvent{
		Config:    &configCopy,
		Timestamp: time.Now(),
		ChangedBy: "system",
	}
	s.notifyConfigChange(event)

	logger.Info("scheduler config partially updated", zap.Int64("version", s.configVersion))
	return nil
}

func (s *Scheduler) AddConfigChangeListener(listener ConfigChangeListener) {
	s.listenerMu.Lock()
	defer s.listenerMu.Unlock()
	s.listeners = append(s.listeners, listener)
}

func (s *Scheduler) RemoveConfigChangeListener(listener ConfigChangeListener) {
	s.listenerMu.Lock()
	defer s.listenerMu.Unlock()
	for i, l := range s.listeners {
		if fmt.Sprintf("%p", l) == fmt.Sprintf("%p", listener) {
			s.listeners = append(s.listeners[:i], s.listeners[i+1:]...)
			break
		}
	}
}

func (s *Scheduler) notifyConfigChange(event ConfigUpdateEvent) {
	s.listenerMu.RLock()
	defer s.listenerMu.RUnlock()
	for _, listener := range s.listeners {
		go listener(event)
	}
}

func (s *Scheduler) GetTaskStrategy(task *models.Task) SchedulingStrategy {
	strategyName := s.config.DefaultStrategy
	if task.Parameters != nil {
		if s, ok := task.Parameters["strategy"].(string); ok {
			if _, exists := s.strategies[s]; exists {
				strategyName = s
			}
		}
	}
	strategy, _ := s.GetStrategy(strategyName)
	return strategy
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
	logger.Info("scheduler started with dynamic config",
		zap.String("default_strategy", s.config.DefaultStrategy),
		zap.Int("concurrency_limit", s.config.ConcurrencyLimit),
	)
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

	strategy := s.GetTaskStrategy(task)
	cronExpr := strategy.GetCronExpr(task)

	entryID, err := s.cron.AddFunc(cronExpr, func() {
		strategy := s.GetTaskStrategy(task)
		if !strategy.ShouldExecute(task, time.Now()) {
			logger.Debug("task skipped by strategy",
				zap.String("task_id", task.ID),
				zap.String("strategy", strategy.GetName()),
			)
			return
		}
		s.executeTask(task, strategy)
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

	logger.Info("task scheduled",
		zap.String("task_id", task.ID),
		zap.String("cron_expr", cronExpr),
		zap.String("strategy", strategy.GetName()),
	)
	return nil
}

func (s *Scheduler) executeTask(task *models.Task, strategy SchedulingStrategy) {
	logger.Info("executing task",
		zap.String("task_id", task.ID),
		zap.String("task_name", task.Name),
		zap.String("strategy", strategy.GetName()),
	)

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

	maxRetries := strategy.GetMaxRetries(task)
	var lastErr error
	success := false

	for attempt := 0; attempt <= maxRetries; attempt++ {
		if attempt > 0 {
			delay := strategy.GetRetryDelay(task, attempt)
			logger.Info("retrying task",
				zap.String("task_id", task.ID),
				zap.Int("attempt", attempt),
				zap.Duration("delay", delay),
			)
			time.Sleep(delay)
		}

		timeout := strategy.GetTimeout(task)
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
		err := s.runTaskWithTimeout(ctx, task)
		cancel()

		if err == nil {
			success = true
			break
		}
		lastErr = err
		logger.Warn("task execution failed",
			zap.String("task_id", task.ID),
			zap.Int("attempt", attempt),
			zap.Error(err),
		)
	}

	completedAt := time.Now()
	run.CompletedAt = &completedAt
	run.Progress = 1.0

	if success {
		run.Phase = "completed"
	} else {
		run.Phase = "failed"
		if lastErr != nil {
			errMsg := lastErr.Error()
			run.ErrorDetail = &errMsg
		}
	}

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

	logger.Info("task execution finished",
		zap.String("task_id", task.ID),
		zap.String("run_id", runID),
		zap.String("status", run.Phase),
		zap.String("strategy", strategy.GetName()),
	)
}

func (s *Scheduler) runTaskWithTimeout(ctx context.Context, task *models.Task) error {
	done := make(chan error, 1)
	go func() {
		for i := 0; i <= 100; i += 20 {
			select {
			case <-ctx.Done():
				done <- ctx.Err()
				return
			default:
				time.Sleep(50 * time.Millisecond)
			}
		}
		done <- nil
	}()

	select {
	case err := <-done:
		return err
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (s *Scheduler) CreateTask(ctx context.Context, task *models.Task) error {
	if task.ID == "" {
		task.ID = uuid.New().String()
	}
	task.CreatedAt = time.Now()
	task.UpdatedAt = time.Now()
	task.Status = "idle"

	if task.Parameters == nil {
		task.Parameters = make(map[string]interface{})
	}
	if _, ok := task.Parameters["strategy"]; !ok {
		task.Parameters["strategy"] = s.config.DefaultStrategy
	}

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
	strategy := s.GetTaskStrategy(&task)
	go s.executeTask(&task, strategy)
	return nil
}

func (s *Scheduler) GetRunHistory(ctx context.Context, taskID string, limit int) ([]models.RunInstance, error) {
	var runs []models.RunInstance
	if err := s.db.Where("entity_id = ?", taskID).Order("started_at desc").Limit(limit).Find(&runs).Error; err != nil {
		return nil, err
	}
	return runs, nil
}

func (s *Scheduler) GetConfigVersion() int64 {
	s.configMu.RLock()
	defer s.configMu.RUnlock()
	return s.configVersion
}
