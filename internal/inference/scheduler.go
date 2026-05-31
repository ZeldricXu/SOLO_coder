package inference

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/common/eventbus"
	"github.com/edgevision/edgevision/internal/common/logger"
	"github.com/edgevision/edgevision/internal/common/utils"
	"go.uber.org/zap"
)

type InferenceStrategy string

const (
	StrategyBatch    InferenceStrategy = "batch"
	StrategyRealtime InferenceStrategy = "realtime"
	StrategyLowPower InferenceStrategy = "low_power"
)

type InferenceConfig struct {
	Strategy     InferenceStrategy `json:"strategy"`
	BatchSize    int               `json:"batch_size"`
	TimeoutMs    int               `json:"timeout_ms"`
	MaxRetries   int               `json:"max_retries"`
	ModelVersion string            `json:"model_version"`
	GPUEnabled   bool              `json:"gpu_enabled"`
}

type DynamicConfig struct {
	mu           sync.RWMutex
	configs      map[string]InferenceConfig
	configEvents chan ConfigChangeEvent
}

type ConfigChangeEvent struct {
	Namespace string
	OldConfig InferenceConfig
	NewConfig InferenceConfig
}

type InferenceTask struct {
	TaskID       string                 `json:"task_id"`
	ModelID      string                 `json:"model_id"`
	InputData    map[string]interface{} `json:"input_data"`
	Namespace    string                 `json:"namespace"`
	Priority     int                    `json:"priority"`
	CallbackURL  string                 `json:"callback_url"`
	Status       string                 `json:"status"`
	Result       map[string]interface{} `json:"result,omitempty"`
	CreatedAt    time.Time              `json:"created_at"`
	CompletedAt  *time.Time             `json:"completed_at"`
}

type InferenceResult struct {
	TaskID    string                 `json:"task_id"`
	Output    map[string]interface{} `json:"output"`
	LatencyMs int64                  `json:"latency_ms"`
	Success   bool                   `json:"success"`
	Error     string                 `json:"error,omitempty"`
}

type Scheduler struct {
	taskQueue     chan InferenceTask
	workerPool    int
	configManager *DynamicConfig
	results       map[string]InferenceResult
	mu            sync.RWMutex
	wg            sync.WaitGroup
	ctx           context.Context
	cancel        context.CancelFunc
}

func NewDynamicConfig() *DynamicConfig {
	dc := &DynamicConfig{
		configs:      make(map[string]InferenceConfig),
		configEvents: make(chan ConfigChangeEvent, 100),
	}
	dc.configs["default"] = InferenceConfig{
		Strategy:     StrategyRealtime,
		BatchSize:    1,
		TimeoutMs:    5000,
		MaxRetries:   3,
		ModelVersion: "v1.0.0",
		GPUEnabled:   true,
	}
	return dc
}

func (dc *DynamicConfig) GetConfig(namespace string) InferenceConfig {
	dc.mu.RLock()
	defer dc.mu.RUnlock()
	if cfg, ok := dc.configs[namespace]; ok {
		return cfg
	}
	return dc.configs["default"]
}

func (dc *DynamicConfig) UpdateConfig(namespace string, newConfig InferenceConfig) {
	dc.mu.Lock()
	defer dc.mu.Unlock()
	oldConfig := dc.configs[namespace]
	dc.configs[namespace] = newConfig
	select {
	case dc.configEvents <- ConfigChangeEvent{
		Namespace: namespace,
		OldConfig: oldConfig,
		NewConfig: newConfig,
	}:
	default:
	}
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "config.inference.updated",
		Payload: map[string]interface{}{
			"namespace": namespace,
			"config":    newConfig,
		},
	})
	logger.Get().Info("Inference config updated",
		zap.String("namespace", namespace),
		zap.String("strategy", string(newConfig.Strategy)))
}

func (dc *DynamicConfig) ListConfigs() map[string]InferenceConfig {
	dc.mu.RLock()
	defer dc.mu.RUnlock()
	result := make(map[string]InferenceConfig)
	for k, v := range dc.configs {
		result[k] = v
	}
	return result
}

func (dc *DynamicConfig) Watch() <-chan ConfigChangeEvent {
	return dc.configEvents
}

func NewScheduler(workerPool int) *Scheduler {
	ctx, cancel := context.WithCancel(context.Background())
	return &Scheduler{
		taskQueue:     make(chan InferenceTask, 1000),
		workerPool:    workerPool,
		configManager: NewDynamicConfig(),
		results:       make(map[string]InferenceResult),
		ctx:           ctx,
		cancel:        cancel,
	}
}

func (s *Scheduler) GetConfigManager() *DynamicConfig {
	return s.configManager
}

func (s *Scheduler) Start() {
	for i := 0; i < s.workerPool; i++ {
		s.wg.Add(1)
		go s.worker(i)
	}
	go s.watchConfigChanges()
	logger.Get().Info("Inference scheduler started", zap.Int("workers", s.workerPool))
}

func (s *Scheduler) watchConfigChanges() {
	for {
		select {
		case event := <-s.configManager.Watch():
			logger.Get().Info("Detected config change, applying at runtime",
				zap.String("namespace", event.Namespace),
				zap.String("old_strategy", string(event.OldConfig.Strategy)),
				zap.String("new_strategy", string(event.NewConfig.Strategy)))
		case <-s.ctx.Done():
			return
		}
	}
}

func (s *Scheduler) SubmitTask(task InferenceTask) (string, error) {
	if task.Namespace == "" {
		task.Namespace = "default"
	}
	task.TaskID = utils.GenerateID("task")
	task.Status = "pending"
	task.CreatedAt = time.Now().UTC()
	select {
	case s.taskQueue <- task:
		logger.Get().Info("Task submitted", zap.String("task_id", task.TaskID))
		return task.TaskID, nil
	case <-s.ctx.Done():
		return "", errors.New("scheduler stopped")
	default:
		return "", errors.New("task queue is full")
	}
}

func (s *Scheduler) worker(id int) {
	defer s.wg.Done()
	for {
		select {
		case task := <-s.taskQueue:
			s.processTask(task)
		case <-s.ctx.Done():
			return
		}
	}
}

func (s *Scheduler) processTask(task InferenceTask) {
	cfg := s.configManager.GetConfig(task.Namespace)
	startTime := time.Now()
	task.Status = "running"
	logger.Get().Debug("Processing task",
		zap.String("task_id", task.TaskID),
		zap.String("strategy", string(cfg.Strategy)))
	result := InferenceResult{
		TaskID: task.TaskID,
	}
	var err error
	switch cfg.Strategy {
	case StrategyBatch:
		err = s.executeBatchInference(task, cfg)
	case StrategyRealtime:
		err = s.executeRealtimeInference(task, cfg)
	case StrategyLowPower:
		err = s.executeLowPowerInference(task, cfg)
	default:
		err = fmt.Errorf("unknown strategy: %s", cfg.Strategy)
	}
	latency := time.Since(startTime).Milliseconds()
	result.LatencyMs = latency
	if err != nil {
		result.Success = false
		result.Error = err.Error()
		task.Status = "failed"
	} else {
		result.Success = true
		result.Output = map[string]interface{}{
			"detections": []interface{}{
				map[string]interface{}{"class": "person", "confidence": 0.95},
			},
			"inference_time_ms": latency,
			"model_version":     cfg.ModelVersion,
		}
		task.Status = "completed"
		task.Result = result.Output
	}
	completedAt := time.Now().UTC()
	task.CompletedAt = &completedAt
	s.mu.Lock()
	s.results[task.TaskID] = result
	s.mu.Unlock()
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "inference.task.completed",
		Payload: map[string]interface{}{
			"task_id": task.TaskID,
			"success": result.Success,
			"latency": latency,
		},
	})
	logger.Get().Info("Task completed",
		zap.String("task_id", task.TaskID),
		zap.Bool("success", result.Success),
		zap.Int64("latency_ms", latency))
}

func (s *Scheduler) executeBatchInference(task InferenceTask, cfg InferenceConfig) error {
	time.Sleep(time.Duration(cfg.TimeoutMs/2) * time.Millisecond)
	return nil
}

func (s *Scheduler) executeRealtimeInference(task InferenceTask, cfg InferenceConfig) error {
	time.Sleep(time.Duration(cfg.TimeoutMs/10) * time.Millisecond)
	return nil
}

func (s *Scheduler) executeLowPowerInference(task InferenceTask, cfg InferenceConfig) error {
	time.Sleep(time.Duration(cfg.TimeoutMs/5) * time.Millisecond)
	return nil
}

func (s *Scheduler) GetTaskStatus(taskID string) (*InferenceTask, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, t := range s.results {
		if t.TaskID == taskID {
			return &InferenceTask{
				TaskID:      taskID,
				Status:      "completed",
				CompletedAt: utils.NowPtr(),
				Result:      t.Output,
			}, true
		}
	}
	return nil, false
}

func (s *Scheduler) GetResult(taskID string) (*InferenceResult, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result, exists := s.results[taskID]
	return &result, exists
}

func (s *Scheduler) Stop() {
	s.cancel()
	close(s.taskQueue)
	s.wg.Wait()
	logger.Get().Info("Inference scheduler stopped")
}
