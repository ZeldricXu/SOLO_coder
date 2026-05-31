package core

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/edgeplatform/session306/internal/config"
	"github.com/edgeplatform/session306/internal/data"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/pkg/errors"
	"github.com/edgeplatform/session306/pkg/events"
	"github.com/edgeplatform/session306/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type ExecutionContext struct {
	TraceID    string
	EntityID   string
	RunID      string
	Namespace  string
	Config     map[string]interface{}
	StartTime  time.Time
	Attributes map[string]interface{}
	mu         sync.RWMutex
}

func (ec *ExecutionContext) Set(key string, value interface{}) {
	ec.mu.Lock()
	defer ec.mu.Unlock()
	ec.Attributes[key] = value
}

func (ec *ExecutionContext) Get(key string) (interface{}, bool) {
	ec.mu.RLock()
	defer ec.mu.RUnlock()
	v, ok := ec.Attributes[key]
	return v, ok
}

type ProcessingHandler func(ctx context.Context, execCtx *ExecutionContext, payload interface{}) (interface{}, error)

type CoreProcessor struct {
	da             *data.DataAccess
	configManager  *config.ConfigManager
	eventBus       events.EventBus
	entityRepo     data.EntityRepository
	runRepo        data.RunInstanceRepository
	logger         *zap.Logger
	semaphore      chan struct{}
	handlers       map[string]ProcessingHandler
	mu             sync.RWMutex
}

func NewCoreProcessor(
	da *data.DataAccess,
	cm *config.ConfigManager,
	eb events.EventBus,
	entityRepo data.EntityRepository,
	runRepo data.RunInstanceRepository,
	log *zap.Logger,
	maxConcurrency int,
) *CoreProcessor {
	if maxConcurrency <= 0 {
		maxConcurrency = 100
	}
	return &CoreProcessor{
		da:            da,
		configManager: cm,
		eventBus:      eb,
		entityRepo:    entityRepo,
		runRepo:       runRepo,
		logger:        log,
		semaphore:     make(chan struct{}, maxConcurrency),
		handlers:      make(map[string]ProcessingHandler),
	}
}

func (cp *CoreProcessor) RegisterHandler(resourceType string, handler ProcessingHandler) {
	cp.mu.Lock()
	defer cp.mu.Unlock()
	cp.handlers[resourceType] = handler
	cp.logger.Info("Handler registered", zap.String("resource_type", resourceType))
}

func (cp *CoreProcessor) getHandler(resourceType string) (ProcessingHandler, bool) {
	cp.mu.RLock()
	defer cp.mu.RUnlock()
	h, ok := cp.handlers[resourceType]
	return h, ok
}

func (cp *CoreProcessor) Execute(ctx context.Context, resourceType string, namespace string, payload interface{}, traceID string) (*model.ApiResponse, error) {
	if traceID == "" {
		traceID = utils.GenerateID("trace")
	}

	execCtx := &ExecutionContext{
		TraceID:    traceID,
		Namespace:  namespace,
		StartTime:  utils.NowUTC(),
		Attributes: make(map[string]interface{}),
	}

	ctx = context.WithValue(ctx, "trace_id", traceID)

	cp.logger.Debug("Starting execution",
		zap.String("trace_id", traceID),
		zap.String("resource_type", resourceType),
		zap.String("namespace", namespace),
	)

	timeout, err := cp.configManager.GetIntParameter(ctx, "system", "timeout")
	if err != nil {
		timeout = 30
	}

	var cancel context.CancelFunc
	ctx, cancel = context.WithTimeout(ctx, time.Duration(timeout)*time.Second)
	defer cancel()

	maxRetries, err := cp.configManager.GetIntParameter(ctx, "system", "retries")
	if err != nil {
		maxRetries = 3
	}

	validationResult, err := cp.configManager.ValidateConfig(ctx, namespace, nil)
	if err != nil {
		return cp.handleError(ctx, execCtx, err, 422)
	}
	if !validationResult.Valid {
		return &model.ApiResponse{
			Code:    422,
			Message: "参数验证失败",
			Data:    validationResult.Details,
		}, errors.NewValidationError("validation failed", validationResult.Details...)
	}

	cfg, err := cp.configManager.GetConfig(ctx, namespace)
	if err != nil {
		if _, ok := err.(*errors.AppError); !ok || err.(*errors.AppError).Code != errors.ErrCodeNotFound {
			return cp.handleError(ctx, execCtx, err, 500)
		}
		cfg = &model.ConfigDefinition{
			Parameters: cp.configManager.ApplyDefaults(ctx, namespace, nil),
		}
	}
	execCtx.Config = cfg.Parameters

	entity := &model.Entity{
		Type:       model.EntityType(resourceType),
		Status:     model.EntityStatusProvisioning,
		Attributes: map[string]interface{}{"payload": payload},
	}
	if err := cp.entityRepo.Create(ctx, entity); err != nil {
		return cp.handleError(ctx, execCtx, err, 500)
	}
	execCtx.EntityID = entity.ID

	run := &model.RunInstance{
		EntityID:   entity.ID,
		Phase:      model.RunPhasePending,
		Progress:   0,
		TraceID:    traceID,
		MaxRetries: maxRetries,
	}
	if err := cp.runRepo.Create(ctx, run); err != nil {
		return cp.handleError(ctx, execCtx, err, 500)
	}
	execCtx.RunID = run.RunID

	cp.semaphore <- struct{}{}
	defer func() { <-cp.semaphore }()

	var result interface{}
	var lastErr error

	for retry := 0; retry <= maxRetries; retry++ {
		if retry > 0 {
			_, _ = cp.runRepo.IncrementRetry(ctx, run.RunID)
			cp.logger.Info("Retrying execution",
				zap.String("run_id", run.RunID),
				zap.Int("retry", retry),
			)
			time.Sleep(time.Duration(retry) * time.Second)
		}

		result, lastErr = cp.executeWithTransaction(ctx, execCtx, resourceType, payload)
		if lastErr == nil {
			break
		}

		if appErr, ok := lastErr.(*errors.AppError); ok {
			if appErr.Code == errors.ErrCodeValidation || appErr.Code == errors.ErrCodeUnauthorized {
				break
			}
		}
	}

	cp.recordMetrics(ctx, execCtx)

	if lastErr != nil {
		_ = cp.runRepo.MarkComplete(ctx, run.RunID, stringPtr(lastErr.Error()))
		_ = cp.entityRepo.UpdateStatus(ctx, entity.ID, model.EntityStatusFailed)
		cp.publishEvent(ctx, events.EventTaskFailed, execCtx, map[string]interface{}{
			"error": lastErr.Error(),
		})
		return cp.handleError(ctx, execCtx, lastErr, 500)
	}

	_ = cp.runRepo.MarkComplete(ctx, run.RunID, nil)
	_ = cp.entityRepo.UpdateStatus(ctx, entity.ID, model.EntityStatusCompleted)
	cp.publishEvent(ctx, events.EventTaskCompleted, execCtx, map[string]interface{}{
		"result": result,
	})

	return &model.ApiResponse{
		Code: 200,
		Data: result,
	}, nil
}

func (cp *CoreProcessor) executeWithTransaction(ctx context.Context, execCtx *ExecutionContext, resourceType string, payload interface{}) (interface{}, error) {
	var result interface{}

	err := cp.da.WithTransaction(ctx, func(tx *gorm.DB) error {
		if err := cp.runRepo.UpdatePhase(ctx, execCtx.RunID, model.RunPhaseExecuting, 0.2); err != nil {
			return err
		}

		handler, ok := cp.getHandler(resourceType)
		if !ok {
			handler = cp.defaultHandler
		}

		var err error
		result, err = handler(ctx, execCtx, payload)
		if err != nil {
			if appErr, ok := err.(*errors.AppError); ok {
				if appErr.Code == errors.ErrCodeValidation {
					_ = cp.runRepo.UpdatePhase(ctx, execCtx.RunID, model.RunPhaseFailed, 1.0)
					return err
				}
			}
			_ = cp.runRepo.UpdatePhase(ctx, execCtx.RunID, model.RunPhaseRollback, 0.9)
			return err
		}

		if err := cp.runRepo.UpdatePhase(ctx, execCtx.RunID, model.RunPhaseValidating, 0.8); err != nil {
			return err
		}

		if err := cp.runRepo.UpdatePhase(ctx, execCtx.RunID, model.RunPhaseCompleted, 1.0); err != nil {
			return err
		}

		return nil
	})

	return result, err
}

func (cp *CoreProcessor) defaultHandler(ctx context.Context, execCtx *ExecutionContext, payload interface{}) (interface{}, error) {
	cp.logger.Debug("Using default handler", zap.String("trace_id", execCtx.TraceID))

	normalized, err := cp.normalizeData(payload)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "data normalization failed")
	}

	transformed := cp.transformData(normalized, execCtx.Config)

	standardized := cp.standardizeData(transformed)

	return standardized, nil
}

func (cp *CoreProcessor) normalizeData(data interface{}) (map[string]interface{}, error) {
	switch v := data.(type) {
	case map[string]interface{}:
		result := make(map[string]interface{})
		for k, val := range v {
			result[normalizeKey(k)] = val
		}
		return result, nil
	default:
		return map[string]interface{}{"value": data}, nil
	}
}

func (cp *CoreProcessor) transformData(data map[string]interface{}, config map[string]interface{}) map[string]interface{} {
	result := make(map[string]interface{})

	for k, v := range data {
		result[k] = v
	}

	if transformRules, ok := config["transform_rules"].(map[string]interface{}); ok {
		for field, rule := range transformRules {
			if ruleStr, ok := rule.(string); ok {
				if val, exists := data[field]; exists {
					result[field] = applyTransform(val, ruleStr)
				}
			}
		}
	}

	return result
}

func (cp *CoreProcessor) standardizeData(data map[string]interface{}) map[string]interface{} {
	result := make(map[string]interface{})

	for k, v := range data {
		result[k] = standardizeValue(v)
	}

	result["_normalized"] = true
	result["_timestamp"] = utils.NowUTC()

	return result
}

func normalizeKey(key string) string {
	key = strings.ToLower(key)
	key = strings.ReplaceAll(key, " ", "_")
	key = strings.ReplaceAll(key, "-", "_")
	return key
}

func applyTransform(value interface{}, rule string) interface{} {
	switch rule {
	case "uppercase":
		if s, ok := value.(string); ok {
			return strings.ToUpper(s)
		}
	case "lowercase":
		if s, ok := value.(string); ok {
			return strings.ToLower(s)
		}
	case "trim":
		if s, ok := value.(string); ok {
			return strings.TrimSpace(s)
		}
	}
	return value
}

func standardizeValue(v interface{}) interface{} {
	switch val := v.(type) {
	case int:
		return float64(val)
	case int32:
		return float64(val)
	case int64:
		return float64(val)
	default:
		return v
	}
}

func (cp *CoreProcessor) handleError(ctx context.Context, execCtx *ExecutionContext, err error, defaultCode int) (*model.ApiResponse, error) {
	cp.logger.Error("Execution error",
		zap.String("trace_id", execCtx.TraceID),
		zap.Error(err),
		zap.Duration("duration", time.Since(execCtx.StartTime)),
	)

	if appErr, ok := err.(*errors.AppError); ok {
		switch appErr.Code {
		case errors.ErrCodeValidation:
			return &model.ApiResponse{
				Code:    422,
				Message: appErr.Message,
				Data:    appErr.Details,
			}, err
		case errors.ErrCodeUnauthorized:
			return &model.ApiResponse{
				Code:    401,
				Message: appErr.Message,
			}, err
		case errors.ErrCodeNotFound:
			return &model.ApiResponse{
				Code:    404,
				Message: appErr.Message,
			}, err
		case errors.ErrCodeTimeout:
			return &model.ApiResponse{
				Code:    504,
				Message: "上游服务响应超时",
			}, err
		}
	}

	if ctx.Err() == context.DeadlineExceeded {
		return &model.ApiResponse{
			Code:    504,
			Message: "上游服务响应超时",
		}, errors.NewTimeoutError("timeout exceeded")
	}

	return &model.ApiResponse{
		Code:    defaultCode,
		Message: "内部处理错误",
	}, err
}

func (cp *CoreProcessor) publishEvent(ctx context.Context, eventType events.EventType, execCtx *ExecutionContext, payload map[string]interface{}) {
	event := events.Event{
		ID:        utils.GenerateID("evt"),
		Type:      eventType,
		Source:    "core_processor",
		Timestamp: utils.NowUTC(),
		TraceID:   execCtx.TraceID,
		Payload: utils.MergeMaps(payload, map[string]interface{}{
			"entity_id": execCtx.EntityID,
			"run_id":    execCtx.RunID,
		}),
	}
	if err := cp.eventBus.Publish(ctx, event); err != nil {
		cp.logger.Warn("Failed to publish event",
			zap.String("event_type", string(eventType)),
			zap.Error(err),
		)
	}
}

func (cp *CoreProcessor) recordMetrics(ctx context.Context, execCtx *ExecutionContext) {
	duration := time.Since(execCtx.StartTime)
	execCtx.Set("duration_ms", duration.Milliseconds())

	cp.logger.Debug("Execution metrics",
		zap.String("trace_id", execCtx.TraceID),
		zap.Duration("duration", duration),
	)
}

func (cp *CoreProcessor) GetStatus(ctx context.Context, entityID string) (*model.ResourceStatusResponse, error) {
	entity, err := cp.entityRepo.GetByID(ctx, entityID)
	if err != nil {
		return nil, err
	}

	runs, _, err := cp.runRepo.ListByEntity(ctx, entityID, 0, 1)
	if err != nil {
		return nil, err
	}

	resp := &model.ResourceStatusResponse{
		ID:     entity.ID,
		Status: string(entity.Status),
	}

	if len(runs) > 0 {
		resp.Progress = runs[0].Progress
		resp.Phase = string(runs[0].Phase)
	}

	return resp, nil
}

func (cp *CoreProcessor) BatchOperation(ctx context.Context, operations []model.BatchOperation) (*model.BatchResponse, error) {
	batchID := utils.GenerateID("batch")
	results := make([]model.BatchOperationResult, 0, len(operations))

	maxConcurrent, _ := cp.configManager.GetIntParameter(ctx, "system", "max_concurrent")
	if maxConcurrent <= 0 {
		maxConcurrent = 10
	}

	sem := make(chan struct{}, maxConcurrent)
	var wg sync.WaitGroup
	mu := sync.Mutex{}

	for _, op := range operations {
		wg.Add(1)
		sem <- struct{}{}

		go func(op model.BatchOperation) {
			defer wg.Done()
			defer func() { <-sem }()

			result := model.BatchOperationResult{
				ID:      op.ID,
				Success: true,
			}

			var err error
			switch op.Action {
			case "start":
				err = cp.entityRepo.UpdateStatus(ctx, op.ID, model.EntityStatusRunning)
			case "stop":
				err = cp.entityRepo.UpdateStatus(ctx, op.ID, model.EntityStatusStopped)
			case "restart":
				err = cp.entityRepo.UpdateStatus(ctx, op.ID, model.EntityStatusProvisioning)
			case "delete":
				err = cp.entityRepo.Delete(ctx, op.ID)
			default:
				err = errors.NewValidationError(fmt.Sprintf("unknown action: %s", op.Action))
			}

			if err != nil {
				result.Success = false
				result.Message = err.Error()
			}

			mu.Lock()
			results = append(results, result)
			mu.Unlock()
		}(op)
	}

	wg.Wait()

	return &model.BatchResponse{
		BatchID: batchID,
		Results: results,
	}, nil
}

func stringPtr(s string) *string {
	return &s
}
