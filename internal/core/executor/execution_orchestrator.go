package executor

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/v2/internal/core/ports"
)

type ExecutionOrchestrator struct {
	requestValidator *RequestValidator
	configResolver   *ConfigResolver
	resourceManager  *ResourceManager
	resultPersister  *ResultPersister
	eventEmitter     *EventEmitter
	errorHandler     *ErrorHandler
	metrics          ports.MetricsRecorder
	processor        ports.TaskProcessor
	configLoader     ports.ConfigLoader
	logger           *zap.Logger
	cfg              Config
}

type OrchestratorDependencies struct {
	RequestValidator *RequestValidator
	ConfigResolver   *ConfigResolver
	ResourceManager  *ResourceManager
	ResultPersister  *ResultPersister
	EventEmitter     *EventEmitter
	ErrorHandler     *ErrorHandler
	Metrics          ports.MetricsRecorder
	Processor        ports.TaskProcessor
	ConfigLoader     ports.ConfigLoader
	Logger           *zap.Logger
}

func NewExecutionOrchestrator(cfg Config, deps OrchestratorDependencies) *ExecutionOrchestrator {
	return &ExecutionOrchestrator{
		requestValidator: deps.RequestValidator,
		configResolver:   deps.ConfigResolver,
		resourceManager:  deps.ResourceManager,
		resultPersister:  deps.ResultPersister,
		eventEmitter:     deps.EventEmitter,
		errorHandler:     deps.ErrorHandler,
		metrics:          deps.Metrics,
		processor:        deps.Processor,
		configLoader:     deps.ConfigLoader,
		logger:           deps.Logger,
		cfg:              cfg,
	}
}

func (o *ExecutionOrchestrator) Execute(
	ctx context.Context,
	req *ports.ProcessRequest,
) *ports.ProcessResult {
	startTime := time.Now()
	success := true

	defer func() {
		duration := time.Since(startTime)
		o.metrics.Record(duration, success)
		o.recordMetrics(ctx, req.TraceID)
	}()

	if err := o.requestValidator.Validate(req); err != nil {
		success = false
		return o.errorHandler.HandleValidationError(err)
	}

	rules, processCtx, cancel, err := o.configResolver.Resolve(ctx, req.Namespace)
	if err != nil {
		success = false
		return o.errorHandler.HandleGenericError(err, "config load failed")
	}
	defer cancel()

	release, err := o.resourceManager.Acquire(processCtx)
	if err != nil {
		success = false
		if o.errorHandler.IsTimeoutError(err) {
			return o.errorHandler.HandleTimeoutError(err)
		}
		return &ports.ProcessResult{
			Success:    false,
			Error:      err.Error(),
			StatusCode: 503,
		}
	}
	defer release()

	config, _ := o.configLoader.Load(ctx, req.Namespace)
	runID, err := o.resultPersister.CreateRunInstance(processCtx, req, config)
	if err != nil {
		success = false
		o.logger.Error("Failed to create run instance",
			zap.String("trace_id", req.TraceID),
			zap.Error(err))
	}

	_ = o.eventEmitter.EmitTaskStarted(processCtx, req.EntityID, runID, req.TraceID)

	processResult, err := o.processor.Process(processCtx, req.Payload, rules)
	if err != nil {
		success = false
		return o.errorHandler.HandleProcessingError(processCtx, err, runID, o.resultPersister)
	}

	if err := o.resultPersister.PersistResult(processCtx, processResult, req.EntityID); err != nil {
		success = false
		return o.errorHandler.HandleGenericError(err, "persist failed")
	}

	_ = o.resultPersister.UpdateRunPhase(processCtx, runID, "completed", "")

	_ = o.eventEmitter.EmitTaskCompleted(
		processCtx,
		req.EntityID,
		runID,
		processResult,
		time.Since(startTime).Seconds(),
		req.TraceID,
	)

	return &ports.ProcessResult{
		Success:    true,
		Data:       processResult,
		StatusCode: 200,
	}
}

func (o *ExecutionOrchestrator) UpdateProgress(
	ctx context.Context,
	runID string,
	progress float64,
) error {
	_ = o.eventEmitter.EmitProgressUpdate(ctx, runID, progress)
	return o.resultPersister.repo.UpdateProgress(ctx, runID, progress)
}

func (o *ExecutionOrchestrator) GetMetricsSnapshot() map[string]interface{} {
	return o.metrics.Snapshot()
}

func (o *ExecutionOrchestrator) recordMetrics(ctx context.Context, traceID string) {
	metrics := o.metrics.Snapshot()
	dimensions := map[string]string{
		"trace_id": traceID,
		"host":     o.cfg.Host,
		"region":   o.cfg.Region,
	}

	if err := o.resultPersister.RecordMetrics(ctx, metrics, dimensions); err != nil {
		o.logger.Error("Failed to record metrics snapshot", zap.Error(err))
	}
}
