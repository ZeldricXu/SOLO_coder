package core

import (
	"context"
	"errors"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/events"
	"github.com/solocoder/task-scheduler/internal/logging"
	"github.com/solocoder/task-scheduler/internal/models"
)

type Handler struct {
	validator       contracts.ParameterValidator
	configLoader    contracts.DynamicConfigLoader
	ruleExtractor   contracts.RuleExtractor
	resourcePool    contracts.ResourcePool
	runManager      contracts.RunInstanceManager
	processor       contracts.TaskProcessor
	persister       contracts.ResultPersister
	eventPublisher  contracts.EventPublisher
	metrics         contracts.MetricsCollector
}

func NewHandler(
	validator contracts.ParameterValidator,
	configLoader contracts.DynamicConfigLoader,
	ruleExtractor contracts.RuleExtractor,
	resourcePool contracts.ResourcePool,
	runManager contracts.RunInstanceManager,
	processor contracts.TaskProcessor,
	persister contracts.ResultPersister,
	eventPublisher contracts.EventPublisher,
	metrics contracts.MetricsCollector,
) *Handler {
	h := &Handler{
		validator:       validator,
		configLoader:    configLoader,
		ruleExtractor:   ruleExtractor,
		resourcePool:    resourcePool,
		runManager:      runManager,
		processor:       processor,
		persister:       persister,
		eventPublisher:  eventPublisher,
		metrics:         metrics,
	}

	configLoader.AddChangeListener(h)

	return h
}

func NewHandlerWithDefaults(db *database.Database, eventBus events.EventBus, poolSize int) *Handler {
	return NewHandler(
		NewParameterValidator(),
		NewDynamicConfigLoader(db),
		NewRuleExtractor(),
		NewResourcePool(poolSize),
		NewRunInstanceManager(db, eventBus),
		NewTaskProcessor(),
		NewResultPersister(db),
		NewEventPublisher(eventBus),
		NewMetricsCollector(),
	)
}

func (h *Handler) OnConfigChanged(namespace string, oldConfig, newConfig *models.ConfigDefinition) {
	logging.Info(context.Background(), "Config changed",
		zap.String("namespace", namespace),
		zap.Any("old_config", oldConfig),
		zap.Any("new_config", newConfig))
}

func (h *Handler) OnSceneChanged(namespace string, oldScene, newScene contracts.SceneStrategy) {
	logging.Info(context.Background(), "Scene changed",
		zap.String("namespace", namespace),
		zap.String("old_scene", string(oldScene)),
		zap.String("new_scene", string(newScene)))

	if dynamicLoader, ok := h.configLoader.(*DynamicConfigLoader); ok {
		sceneConfig, err := dynamicLoader.GetSceneConfig(context.Background(), namespace, newScene)
		if err == nil && sceneConfig.PoolSize > 0 {
			_ = h.resourcePool.Resize(sceneConfig.PoolSize)
			logging.Info(context.Background(), "Resource pool resized",
				zap.Int("new_size", sceneConfig.PoolSize))
		}
	}
}

func (h *Handler) ExecuteHandler(ctx context.Context, req *contracts.ProcessRequest) *contracts.ProcessResult {
	traceCtx := models.NewTraceContext(req.TraceID)
	processCtx := context.WithValue(ctx, "traceID", traceCtx.TraceID)

	startTime := time.Now()
	success := true
	var result *contracts.ProcessResult

	defer func() {
		duration := time.Since(startTime)
		h.metrics.Record(duration, success)
		h.recordMetrics(processCtx, traceCtx)
	}()

	if err := h.validator.Validate(req.Params); err != nil {
		success = false
		return &contracts.ProcessResult{
			Success:    false,
			Error:      err.Error(),
			StatusCode: 422,
		}
	}

	config, err := h.configLoader.LoadConfig(processCtx, req.Namespace)
	if err != nil {
		success = false
		return &contracts.ProcessResult{
			Success:    false,
			Error:      fmt.Sprintf("config load failed: %v", err),
			StatusCode: 500,
		}
	}

	scene := contracts.SceneDefault
	if req.Scene != "" {
		scene = contracts.SceneStrategy(req.Scene)
	} else {
		scene = h.configLoader.GetCurrentScene(processCtx, req.Namespace)
	}

	rules := h.ruleExtractor.ExtractRulesForScene(config, scene)
	processCtx, cancel := context.WithTimeout(processCtx, rules.Timeout)
	defer cancel()

	if _, err := h.resourcePool.Acquire(processCtx); err != nil {
		success = false
		if errors.Is(err, context.DeadlineExceeded) {
			return &contracts.ProcessResult{
				Success:    false,
				Error:      "上游服务响应超时",
				StatusCode: 504,
			}
		}
		return &contracts.ProcessResult{
			Success:    false,
			Error:      "resource acquisition failed",
			StatusCode: 503,
		}
	}
	defer h.resourcePool.Release()

	runID := h.runManager.Create(processCtx, req, config)

	processResult, err := h.processor.Process(processCtx, req.Payload, rules)
	if err != nil {
		success = false
		h.runManager.UpdatePhase(processCtx, runID, models.PhaseFailed, err.Error())

		var verr *contracts.ValidationError
		var terr *contracts.TimeoutError
		switch {
		case errors.As(err, &verr):
			return &contracts.ProcessResult{
				Success:    false,
				Error:      verr.Error(),
				StatusCode: 422,
			}
		case errors.As(err, &terr):
			return &contracts.ProcessResult{
				Success:    false,
				Error:      terr.Message,
				StatusCode: 504,
			}
		default:
			h.rollbackTransaction(processCtx, traceCtx)
			return &contracts.ProcessResult{
				Success:    false,
				Error:      "内部处理错误",
				StatusCode: 500,
			}
		}
	}

	if err := h.persister.Persist(processCtx, processResult, req.EntityID); err != nil {
		success = false
		return &contracts.ProcessResult{
			Success:    false,
			Error:      fmt.Sprintf("persist failed: %v", err),
			StatusCode: 500,
		}
	}

	h.runManager.UpdatePhase(processCtx, runID, models.PhaseCompleted, "")

	h.eventPublisher.PublishTaskCompleted(
		processCtx,
		req.EntityID,
		processResult,
		runID,
		time.Since(startTime).Seconds(),
	)

	result = &contracts.ProcessResult{
		Success:    true,
		Data:       processResult,
		StatusCode: 200,
	}
	return result
}

func (h *Handler) rollbackTransaction(ctx context.Context, traceCtx *models.TraceContext) {
	logging.Warn(ctx, "Transaction rollback triggered", zap.String("trace_id", traceCtx.TraceID))
}

func (h *Handler) recordMetrics(ctx context.Context, traceCtx *models.TraceContext) {
	metrics := h.metrics.Snapshot()
	dimensions := map[string]string{
		"trace_id": traceCtx.TraceID,
		"host":     "node-1",
		"region":   "cn-east",
	}

	snapshot := &models.MetricsSnapshot{
		SnapshotID: "snap_" + time.Now().Format("20060102150405"),
		Timestamp:  time.Now(),
		Metrics:    metrics,
		Dimensions: dimensions,
		CreatedAt:  time.Now(),
	}

	logging.Info(ctx, "Metrics recorded", zap.Any("metrics", metrics))
}

func (h *Handler) GetMetricsSnapshot() map[string]interface{} {
	return h.metrics.Snapshot()
}

func (h *Handler) UpdateProgress(ctx context.Context, runID string, progress float64) error {
	return h.runManager.UpdateProgress(ctx, runID, progress)
}

type TaskExecutor struct {
	handler *Handler
}

func NewTaskExecutor(handler *Handler) *TaskExecutor {
	return &TaskExecutor{handler: handler}
}

func (e *TaskExecutor) Execute(ctx context.Context, req *contracts.ProcessRequest) *contracts.ProcessResult {
	return e.handler.ExecuteHandler(ctx, req)
}

func (e *TaskExecutor) GetHandler() *Handler {
	return e.handler
}
