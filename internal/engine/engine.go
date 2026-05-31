package engine

import (
	"errors"
	"sync"
	"time"

	"github.com/enterprise/config-platform/internal/config"
	"github.com/enterprise/config-platform/internal/faultinjection"
	"github.com/enterprise/config-platform/internal/logging"
	"github.com/enterprise/config-platform/internal/monitoring"
	"github.com/enterprise/config-platform/pkg/types"
	"github.com/enterprise/config-platform/pkg/utils"
	"go.uber.org/zap"
)

type ProcessingContext struct {
	TraceID    string
	StartTime  time.Time
	Namespace  string
	EntityID   string
	Attributes map[string]interface{}
}

type HandlerFunc func(*ProcessingContext, map[string]interface{}) (interface{}, error)

type Engine struct {
	handlers      map[string]HandlerFunc
	resourcePool  chan struct{}
	configManager *config.Manager
	metrics       *monitoring.Manager
	faultManager  *faultinjection.Manager
	mu            sync.RWMutex
}

var (
	instance *Engine
	once     sync.Once
)

func GetEngine() *Engine {
	once.Do(func() {
		instance = &Engine{
			handlers:      make(map[string]HandlerFunc),
			resourcePool:  make(chan struct{}, 100),
			configManager: config.GetManager(),
			metrics:       monitoring.GetManager(),
			faultManager:  faultinjection.GetManager(),
		}
		for i := 0; i < 100; i++ {
			instance.resourcePool <- struct{}{}
		}
	})
	return instance
}

func (e *Engine) RegisterHandler(name string, handler HandlerFunc) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.handlers[name] = handler
}

func (e *Engine) GetHandler(name string) (HandlerFunc, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	handler, exists := e.handlers[name]
	return handler, exists
}

func (e *Engine) initContext(traceID, namespace string) *ProcessingContext {
	if traceID == "" {
		traceID = utils.GenerateID("trace")
	}
	return &ProcessingContext{
		TraceID:   traceID,
		StartTime: time.Now(),
		Namespace: namespace,
		EntityID:  utils.GenerateID("ent"),
	}
}

func (e *Engine) validateParams(params map[string]interface{}) error {
	if params == nil {
		return errors.New("params cannot be nil")
	}
	return nil
}

func (e *Engine) acquireResource(timeout time.Duration) error {
	select {
	case <-e.resourcePool:
		return nil
	case <-time.After(timeout):
		return errors.New("resource acquisition timeout")
	}
}

func (e *Engine) releaseResource() {
	select {
	case e.resourcePool <- struct{}{}:
	default:
	}
}

func (e *Engine) processCore(ctx *ProcessingContext, payload map[string]interface{}, rules map[string]interface{}) (interface{}, error) {
	handlerName, ok := rules["handler"].(string)
	if !ok {
		handlerName = "default"
	}

	handler, exists := e.GetHandler(handlerName)
	if !exists {
		return e.defaultHandler(ctx, payload)
	}

	return handler(ctx, payload)
}

func (e *Engine) defaultHandler(ctx *ProcessingContext, payload map[string]interface{}) (interface{}, error) {
	result := make(map[string]interface{})
	result["processed"] = true
	result["trace_id"] = ctx.TraceID
	result["entity_id"] = ctx.EntityID
	result["timestamp"] = time.Now().UTC()
	result["payload"] = payload
	return result, nil
}

func (e *Engine) persistResult(result interface{}) error {
	logging.Info("Persisting result", zap.Any("result", result))
	return nil
}

func (e *Engine) emitEvent(eventType string, data interface{}) {
	logging.Info("Event emitted", zap.String("event", eventType), zap.Any("data", data))
}

func (e *Engine) rollbackTransaction(ctx *ProcessingContext) {
	logging.Warn("Rolling back transaction", zap.String("trace_id", ctx.TraceID))
}

func (e *Engine) recordMetrics(ctx *ProcessingContext, status string, duration time.Duration) {
	e.metrics.RecordHTTPRequest("POST", "/api/v1/execute", 200, duration)
}

type ExecuteRequest struct {
	TraceID   string                 `json:"trace_id"`
	Namespace string                 `json:"namespace"`
	Params    map[string]interface{} `json:"params"`
	Payload   map[string]interface{} `json:"payload"`
}

type ExecuteResponse struct {
	TraceID string      `json:"trace_id"`
	Result  interface{} `json:"result"`
	Status  string      `json:"status"`
}

func (e *Engine) Execute(req ExecuteRequest) (*ExecuteResponse, error) {
	ctx := e.initContext(req.TraceID, req.Namespace)
	startTime := time.Now()

	e.metrics.IncrementCounter("engine_requests_total", map[string]string{"namespace": req.Namespace})

	if faultScenario, active := e.faultManager.GetActiveFault("engine", "execute", "local"); active {
		e.faultManager.ApplyFault(faultScenario)
		e.metrics.RecordFaultInjection(string(faultScenario.FaultType), string(faultScenario.Scope.Type))

		if faultScenario.FaultType == faultinjection.FaultTypeError {
			return nil, errors.New(faultScenario.Config.ErrorMessage)
		}
	}

	if err := e.validateParams(req.Params); err != nil {
		e.recordMetrics(ctx, "validation_error", time.Since(startTime))
		return nil, err
	}

	cfg, err := e.configManager.LoadConfig(req.Namespace)
	if err != nil {
		cfg = &types.ConfigDefinition{
			Parameters: map[string]interface{}{
				"timeout": 30,
				"retries": 3,
			},
		}
	}

	timeout := time.Duration(30) * time.Second
	if t, ok := cfg.Parameters["timeout"].(int); ok {
		timeout = time.Duration(t) * time.Second
	}

	if err := e.acquireResource(timeout); err != nil {
		e.recordMetrics(ctx, "busy", time.Since(startTime))
		return nil, errors.New("service busy, please try again later")
	}
	defer e.releaseResource()

	result, err := e.processCore(ctx, req.Payload, cfg.Parameters)
	if err != nil {
		e.rollbackTransaction(ctx)
		e.recordMetrics(ctx, "error", time.Since(startTime))
		return nil, err
	}

	e.persistResult(result)
	e.emitEvent("task.completed", map[string]interface{}{
		"trace_id": ctx.TraceID,
		"result":   result,
	})

	duration := time.Since(startTime)
	e.recordMetrics(ctx, "success", duration)

	return &ExecuteResponse{
		TraceID: ctx.TraceID,
		Result:  result,
		Status:  "success",
	}, nil
}

func (e *Engine) SetPoolSize(size int) {
	e.mu.Lock()
	defer e.mu.Unlock()

	currentSize := len(e.resourcePool)
	if size > currentSize {
		for i := 0; i < size-currentSize; i++ {
			select {
			case e.resourcePool <- struct{}{}:
			default:
			}
		}
	} else if size < currentSize {
		for i := 0; i < currentSize-size; i++ {
			select {
			case <-e.resourcePool:
			default:
			}
		}
	}
}

func (e *Engine) GetPoolStats() (available, total int) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return len(e.resourcePool), cap(e.resourcePool)
}
