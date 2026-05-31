package scheduler

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
	"time"

	"github.com/datatransform/platform/pkg/logger"
	"github.com/datatransform/platform/pkg/models"
	"github.com/datatransform/platform/pkg/service"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"
)

const (
	ServiceName = "scheduler"

	TaskStatusPending   = "pending"
	TaskStatusScheduled = "scheduled"
	TaskStatusRunning   = "running"
	TaskStatusCompleted = "completed"
	TaskStatusFailed    = "failed"

	defaultBatchSize      = 100
	defaultBatchTimeout   = 100 * time.Millisecond
	defaultMaxBatchWait   = 500 * time.Millisecond
)

var (
	ErrTaskNotFound      = service.NewErrorDetail("SCHED_001", "task not found", nil)
	ErrTaskAlreadyExists = service.NewErrorDetail("SCHED_002", "task already exists", nil)
	ErrInvalidCronExpr   = service.NewErrorDetail("SCHED_003", "invalid cron expression", nil)
	ErrTaskDisabled      = service.NewErrorDetail("SCHED_004", "task is disabled", nil)
	ErrBatchTimeout      = service.NewErrorDetail("SCHED_005", "batch operation timeout", nil)
)

type TaskStatus string

type TaskHandler func() error

type Task struct {
	ID          string
	Name        string
	CronExpr    string
	Handler     TaskHandler
	Enabled     bool
	LastRun     *time.Time
	NextRun     time.Time
	Status      TaskStatus
	RunCount    int64
	ErrorCount  int64
	TotalTimeMS int64
}

type TaskDefinition struct {
	Name     string
	CronExpr string
	Handler  TaskHandler
	Enabled  bool
}

type TaskRegistry interface {
	Register(def *TaskDefinition) (string, error)
	Unregister(taskID string)
	Get(taskID string) (*Task, bool)
	List() []*Task
}

type BatchOperationType string

const (
	BatchOperationTrigger  BatchOperationType = "trigger"
	BatchOperationEnable   BatchOperationType = "enable"
	BatchOperationDisable  BatchOperationType = "disable"
	BatchOperationRemove   BatchOperationType = "remove"
)

type BatchOperation struct {
	ID     string
	Action BatchOperationType
}

type BatchResult struct {
	ID      string
	Success bool
	Error   string
}

type BatchRequest struct {
	Operations []BatchOperation
}

type BatchResponse struct {
	BatchID     string
	Results     []BatchResult
	TotalCount  int
	SuccessCount int
	FailedCount int
	DurationMS  int64
}

type BatchingConfig struct {
	MaxBatchSize  int
	BatchTimeout  time.Duration
	MaxBatchWait  time.Duration
	AutoFlush     bool
}

type pendingBatch struct {
	operations []BatchOperation
	startTime  time.Time
}

type Scheduler struct {
	*service.BaseService

	cron        *cron.Cron
	tasks       map[string]*Task
	cronEntries map[cron.EntryID]string
	mu          sync.RWMutex

	batchingEnabled bool
	batchingConfig  BatchingConfig
	pendingBatches  map[string]*pendingBatch
	batchMutex      sync.Mutex
}

func NewScheduler() *Scheduler {
	return NewSchedulerWithBatching(BatchingConfig{
		MaxBatchSize: defaultBatchSize,
		BatchTimeout: defaultBatchTimeout,
		MaxBatchWait: defaultMaxBatchWait,
		AutoFlush:    false,
	})
}

func NewSchedulerWithBatching(config BatchingConfig) *Scheduler {
	if config.MaxBatchSize <= 0 {
		config.MaxBatchSize = defaultBatchSize
	}
	if config.BatchTimeout <= 0 {
		config.BatchTimeout = defaultBatchTimeout
	}
	if config.MaxBatchWait <= 0 {
		config.MaxBatchWait = defaultMaxBatchWait
	}

	return &Scheduler{
		BaseService:     service.NewBaseService(ServiceName),
		cron:            cron.New(cron.WithSeconds()),
		tasks:           make(map[string]*Task),
		cronEntries:     make(map[cron.EntryID]string),
		batchingEnabled: config.AutoFlush,
		batchingConfig:  config,
		pendingBatches:  make(map[string]*pendingBatch),
	}
}

func (s *Scheduler) Start() error {
	if err := s.ValidateStart(); err != nil {
		return err
	}

	logger.Info("starting scheduler",
		zap.String("service", ServiceName),
		zap.Bool("batching_enabled", s.batchingEnabled),
		zap.Int("max_batch_size", s.batchingConfig.MaxBatchSize),
	)

	s.cron.Start()
	s.SetRunning(true)
	return nil
}

func (s *Scheduler) Stop() error {
	if err := s.ValidateStop(); err != nil {
		return err
	}

	logger.Info("stopping scheduler", zap.String("service", ServiceName))
	s.cron.Stop()
	s.SetRunning(false)
	return nil
}

func (s *Scheduler) EnableBatching(enabled bool) {
	s.batchMutex.Lock()
	defer s.batchMutex.Unlock()
	s.batchingEnabled = enabled
	logger.Info("batching mode changed", zap.Bool("enabled", enabled))
}

func (s *Scheduler) AddTask(def *TaskDefinition) (string, error) {
	if def == nil {
		return "", wrapError(ErrTaskAlreadyExists, "nil task definition")
	}

	if def.Name == "" {
		def.Name = "task_" + time.Now().Format("20060102150405")
	}

	if !def.Enabled {
		def.Enabled = true
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	entryID, err := s.cron.AddFunc(def.CronExpr, func() {
		s.executeTaskByName(def.Name)
	})

	if err != nil {
		logger.Error("failed to add task to cron",
			zap.String("task_name", def.Name),
			zap.Error(err),
		)
		return "", wrapError(ErrInvalidCronExpr, err.Error())
	}

	taskID := generateTaskID()
	task := &Task{
		ID:       taskID,
		Name:     def.Name,
		CronExpr: def.CronExpr,
		Handler:  def.Handler,
		Enabled:  def.Enabled,
		Status:   TaskStatusScheduled,
		NextRun:  s.cron.Entry(entryID).Next,
	}

	s.tasks[taskID] = task
	s.cronEntries[entryID] = taskID

	logger.Info("task registered",
		zap.String("task_id", taskID),
		zap.String("task_name", def.Name),
		zap.String("cron_expr", def.CronExpr),
	)

	return taskID, nil
}

func (s *Scheduler) RegisterTask(name string, cronExpr string, handler TaskHandler) (string, error) {
	return s.AddTask(&TaskDefinition{
		Name:     name,
		CronExpr: cronExpr,
		Handler:  handler,
		Enabled:  true,
	})
}

func (s *Scheduler) RemoveTask(taskID string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, exists := s.tasks[taskID]
	if !exists {
		return
	}

	for entryID, id := range s.cronEntries {
		if id == taskID {
			s.cron.Remove(entryID)
			delete(s.cronEntries, entryID)
			break
		}
	}

	delete(s.tasks, taskID)

	logger.Info("task removed",
		zap.String("task_id", taskID),
		zap.String("task_name", task.Name),
	)
}

func (s *Scheduler) GetTasks() []*Task {
	s.mu.RLock()
	defer s.mu.RUnlock()

	tasks := make([]*Task, 0, len(s.tasks))
	for _, task := range s.tasks {
		tasks = append(tasks, task)
	}

	return tasks
}

func (s *Scheduler) GetTask(taskID string) *Task {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return s.tasks[taskID]
}

func (s *Scheduler) TriggerTask(taskID string) error {
	s.mu.RLock()
	task, exists := s.tasks[taskID]
	s.mu.RUnlock()

	if !exists {
		return wrapError(ErrTaskNotFound, "task_id: "+taskID)
	}

	if !task.Enabled {
		return ErrTaskDisabled
	}

	go s.executeTask(taskID)
	return nil
}

func (s *Scheduler) EnableTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, exists := s.tasks[taskID]
	if !exists {
		return wrapError(ErrTaskNotFound, "task_id: "+taskID)
	}

	if task.Enabled {
		return nil
	}

	task.Enabled = true
	entryID, err := s.cron.AddFunc(task.CronExpr, func() {
		s.executeTask(taskID)
	})

	if err != nil {
		task.Enabled = false
		return wrapError(ErrInvalidCronExpr, err.Error())
	}

	s.cronEntries[entryID] = taskID
	task.NextRun = s.cron.Entry(entryID).Next
	task.Status = TaskStatusScheduled

	logger.Info("task enabled",
		zap.String("task_id", taskID),
		zap.String("task_name", task.Name),
	)

	return nil
}

func (s *Scheduler) DisableTask(taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	task, exists := s.tasks[taskID]
	if !exists {
		return wrapError(ErrTaskNotFound, "task_id: "+taskID)
	}

	if !task.Enabled {
		return nil
	}

	for entryID, id := range s.cronEntries {
		if id == taskID {
			s.cron.Remove(entryID)
			delete(s.cronEntries, entryID)
			break
		}
	}

	task.Enabled = false
	task.Status = TaskStatusPending

	logger.Info("task disabled",
		zap.String("task_id", taskID),
		zap.String("task_name", task.Name),
	)

	return nil
}

func (s *Scheduler) BatchAddTasks(definitions []*TaskDefinition) *BatchResponse {
	startTime := time.Now()
	batchID := generateBatchID()
	results := make([]BatchResult, 0, len(definitions))
	successCount := 0

	for _, def := range definitions {
		taskID, err := s.AddTask(def)
		result := BatchResult{ID: def.Name}

		if err != nil {
			result.Success = false
			result.Error = err.Error()
		} else {
			result.Success = true
			result.ID = taskID
			successCount++
		}
		results = append(results, result)
	}

	duration := time.Since(startTime).Milliseconds()

	logger.Info("batch add completed",
		zap.String("batch_id", batchID),
		zap.Int("total", len(definitions)),
		zap.Int("success", successCount),
		zap.Int64("duration_ms", duration),
	)

	return &BatchResponse{
		BatchID:      batchID,
		Results:      results,
		TotalCount:   len(definitions),
		SuccessCount: successCount,
		FailedCount:  len(definitions) - successCount,
		DurationMS:   duration,
	}
}

func (s *Scheduler) ExecuteBatch(request *BatchRequest) *BatchResponse {
	startTime := time.Now()
	batchID := generateBatchID()
	results := make([]BatchResult, 0, len(request.Operations))
	successCount := 0

	for _, op := range request.Operations {
		result := BatchResult{ID: op.ID}
		var err error

		switch op.Action {
		case BatchOperationTrigger:
			err = s.TriggerTask(op.ID)
		case BatchOperationEnable:
			err = s.EnableTask(op.ID)
		case BatchOperationDisable:
			err = s.DisableTask(op.ID)
		case BatchOperationRemove:
			s.RemoveTask(op.ID)
			err = nil
		default:
			err = fmt.Errorf("unknown batch operation: %s", op.Action)
		}

		if err != nil {
			result.Success = false
			result.Error = err.Error()
		} else {
			result.Success = true
			successCount++
		}
		results = append(results, result)
	}

	duration := time.Since(startTime).Milliseconds()

	logger.Info("batch operation completed",
		zap.String("batch_id", batchID),
		zap.Int("total", len(request.Operations)),
		zap.Int("success", successCount),
		zap.Int64("duration_ms", duration),
	)

	return &BatchResponse{
		BatchID:      batchID,
		Results:      results,
		TotalCount:   len(request.Operations),
		SuccessCount: successCount,
		FailedCount:  len(request.Operations) - successCount,
		DurationMS:   duration,
	}
}

func (s *Scheduler) AddToBatch(groupID string, operation BatchOperation) error {
	if !s.batchingEnabled {
		return fmt.Errorf("batching is not enabled")
	}

	s.batchMutex.Lock()
	defer s.batchMutex.Unlock()

	batch, exists := s.pendingBatches[groupID]
	if !exists {
		batch = &pendingBatch{
			operations: make([]BatchOperation, 0),
			startTime:  time.Now(),
		}
		s.pendingBatches[groupID] = batch
	}

	batch.operations = append(batch.operations, operation)

	if len(batch.operations) >= s.batchingConfig.MaxBatchSize {
		go s.flushBatch(groupID)
	}

	return nil
}

func (s *Scheduler) FlushBatch(groupID string) *BatchResponse {
	s.batchMutex.Lock()
	batch, exists := s.pendingBatches[groupID]
	if !exists || len(batch.operations) == 0 {
		s.batchMutex.Unlock()
		return &BatchResponse{
			BatchID:    generateBatchID(),
			TotalCount: 0,
		}
	}

	operations := make([]BatchOperation, len(batch.operations))
	copy(operations, batch.operations)
	delete(s.pendingBatches, groupID)
	s.batchMutex.Unlock()

	return s.ExecuteBatch(&BatchRequest{Operations: operations})
}

func (s *Scheduler) flushBatch(groupID string) {
	s.batchMutex.Lock()
	batch, exists := s.pendingBatches[groupID]
	if !exists || len(batch.operations) == 0 {
		s.batchMutex.Unlock()
		return
	}

	operations := make([]BatchOperation, len(batch.operations))
	copy(operations, batch.operations)
	delete(s.pendingBatches, groupID)
	s.batchMutex.Unlock()

	s.ExecuteBatch(&BatchRequest{Operations: operations})
}

func (s *Scheduler) AutoFlushLoop() {
	if !s.batchingEnabled {
		return
	}

	ticker := time.NewTicker(s.batchingConfig.MaxBatchWait)
	defer ticker.Stop()

	for range ticker.C {
		if !s.IsRunning() {
			return
		}

		s.batchMutex.Lock()
		groupsToFlush := make([]string, 0)
		for groupID, batch := range s.pendingBatches {
			if len(batch.operations) > 0 && time.Since(batch.startTime) >= s.batchingConfig.MaxBatchWait {
				groupsToFlush = append(groupsToFlush, groupID)
			}
		}
		s.batchMutex.Unlock()

		for _, groupID := range groupsToFlush {
			go s.FlushBatch(groupID)
		}
	}
}

func (s *Scheduler) executeTaskByName(name string) {
	s.mu.RLock()
	var taskID string
	for id, task := range s.tasks {
		if task.Name == name {
			taskID = id
			break
		}
	}
	s.mu.RUnlock()

	if taskID != "" {
		s.executeTask(taskID)
	}
}

func (s *Scheduler) executeTask(taskID string) {
	s.mu.RLock()
	task, exists := s.tasks[taskID]
	s.mu.RUnlock()

	if !exists || !task.Enabled {
		return
	}

	logger.Info("task execution started",
		zap.String("task_id", taskID),
		zap.String("task_name", task.Name),
	)

	s.mu.Lock()
	task.Status = TaskStatusRunning
	s.mu.Unlock()

	startTime := time.Now()
	err := task.Handler()
	executionTime := time.Since(startTime)

	now := time.Now()
	s.mu.Lock()
	task.LastRun = &now
	task.RunCount++
	task.TotalTimeMS += executionTime.Milliseconds()

	if err != nil {
		task.Status = TaskStatusFailed
		task.ErrorCount++
		logger.Error("task execution failed",
			zap.String("task_id", taskID),
			zap.String("task_name", task.Name),
			zap.Duration("execution_time", executionTime),
			zap.Error(err),
		)
	} else {
		task.Status = TaskStatusCompleted
		logger.Info("task execution completed",
			zap.String("task_id", taskID),
			zap.String("task_name", task.Name),
			zap.Duration("execution_time", executionTime),
		)
	}

	s.updateNextRun(task)
	s.mu.Unlock()
}

func (s *Scheduler) updateNextRun(task *Task) {
	for entryID, taskID := range s.cronEntries {
		if taskID == task.ID {
			entry := s.cron.Entry(entryID)
			if entry.Valid() {
				task.NextRun = entry.Next
			}
			return
		}
	}
}

func (s *Scheduler) Publish(eventType string, entity models.Entity) {
	logger.Info("event published",
		zap.String("event_type", eventType),
		zap.String("entity_id", entity.ID),
	)
}

func (s *Scheduler) Listen(eventChan <-chan models.Entity) {
	go func() {
		for entity := range eventChan {
			s.Publish("event.received", entity)
		}
		logger.Info("event listener stopped")
	}()
}

func (s *Scheduler) Stats() map[string]interface{} {
	s.mu.RLock()
	defer s.mu.RUnlock()

	stats := map[string]interface{}{
		"running":          s.IsRunning(),
		"total_tasks":      len(s.tasks),
		"enabled_tasks":    0,
		"disabled_tasks":   0,
		"total_executions": int64(0),
		"total_errors":     int64(0),
		"total_time_ms":    int64(0),
		"batching_enabled": s.batchingEnabled,
	}

	for _, task := range s.tasks {
		if task.Enabled {
			stats["enabled_tasks"] = stats["enabled_tasks"].(int) + 1
		} else {
			stats["disabled_tasks"] = stats["disabled_tasks"].(int) + 1
		}
		stats["total_executions"] = stats["total_executions"].(int64) + task.RunCount
		stats["total_errors"] = stats["total_errors"].(int64) + task.ErrorCount
		stats["total_time_ms"] = stats["total_time_ms"].(int64) + task.TotalTimeMS
	}

	if stats["total_executions"].(int64) > 0 {
		stats["avg_execution_time_ms"] = stats["total_time_ms"].(int64) / stats["total_executions"].(int64)
	}

	s.batchMutex.Lock()
	defer s.batchMutex.Unlock()
	stats["pending_batches"] = len(s.pendingBatches)

	return stats
}

func generateTaskID() string {
	b := make([]byte, 8)
	rand.Read(b)
	return fmt.Sprintf("task_%s_%s", time.Now().Format("20060102150405"), hex.EncodeToString(b)[:6])
}

func generateBatchID() string {
	b := make([]byte, 6)
	rand.Read(b)
	return fmt.Sprintf("batch_%s_%s", time.Now().Format("20060102150405"), hex.EncodeToString(b)[:4])
}

func wrapError(base *service.ErrorDetail, detail string) *service.ErrorDetail {
	return service.NewErrorDetail(base.Code, base.Message+": "+detail, base.Cause)
}
