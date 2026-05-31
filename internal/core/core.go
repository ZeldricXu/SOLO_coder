package core

import (
	"context"
	"fmt"
	"sync"
	"time"

	"session130/internal/config"
	"session130/internal/logger"
	"session130/internal/metrics"
	"session130/internal/topology"
	"session130/internal/tracing"
	"session130/pkg/models"
)

type ValidationError struct {
	Details string
}

func (e *ValidationError) Error() string {
	return e.Details
}

type TimeoutError struct{}

func (e *TimeoutError) Error() string {
	return "timeout"
}

type PooledResource struct {
	ID        string
	Type      string
	Timestamp time.Time
	Data      map[string]interface{}
}

type ResourcePool struct {
	mu        sync.Mutex
	resources chan *PooledResource
	maxSize   int
	minIdle   int
	factory   func() (*PooledResource, error)
	created   int64
	reused    int64
}

func NewResourcePool(maxSize, minIdle int, factory func() (*PooledResource, error)) *ResourcePool {
	pool := &ResourcePool{
		resources: make(chan *PooledResource, maxSize),
		maxSize:   maxSize,
		minIdle:   minIdle,
		factory:   factory,
	}

	pool.preWarm()
	return pool
}

func (p *ResourcePool) preWarm() {
	for i := 0; i < p.minIdle; i++ {
		if res, err := p.factory(); err == nil {
			p.resources <- res
			p.created++
		}
	}
}

func (p *ResourcePool) Acquire(ctx context.Context) (*PooledResource, error) {
	select {
	case res := <-p.resources:
		return p.handleReused(res), nil
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}

	if res, ok := p.tryCreateNew(); ok {
		return res, nil
	}

	select {
	case res := <-p.resources:
		return p.handleReused(res), nil
	case <-ctx.Done():
		metrics.Inc("resource_pool_timeouts_total", nil)
		return nil, ctx.Err()
	}
}

func (p *ResourcePool) handleReused(res *PooledResource) *PooledResource {
	p.mu.Lock()
	p.reused++
	p.mu.Unlock()
	metrics.Inc("resource_pool_hits_total", nil)
	return res
}

func (p *ResourcePool) tryCreateNew() (*PooledResource, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.created >= int64(p.maxSize) {
		return nil, false
	}

	res, err := p.factory()
	if err != nil {
		return nil, false
	}

	p.created++
	metrics.Inc("resource_pool_created_total", nil)
	return res, true
}

func (p *ResourcePool) Release(res *PooledResource) {
	if res == nil {
		return
	}
	res.Timestamp = time.Now()
	select {
	case p.resources <- res:
	default:
	}
}

func (p *ResourcePool) Stats() map[string]interface{} {
	p.mu.Lock()
	defer p.mu.Unlock()
	return map[string]interface{}{
		"max_size":      p.maxSize,
		"min_idle":      p.minIdle,
		"current_size":  len(p.resources),
		"total_created": p.created,
		"total_reused":  p.reused,
		"available":     len(p.resources),
	}
}

func (p *ResourcePool) Close() {
	close(p.resources)
}

type Processor struct {
	mu            sync.RWMutex
	configMgr     *config.Manager
	resourcePools map[string]*ResourcePool
	poolConfigs   map[string]PoolConfig
}

type PoolConfig struct {
	MaxSize int
	MinIdle int
	Type    string
}

var (
	instance *Processor
	once     sync.Once
)

func NewProcessor() *Processor {
	return &Processor{
		configMgr:     config.GetManager(),
		resourcePools: make(map[string]*ResourcePool),
		poolConfigs:   make(map[string]PoolConfig),
	}
}

func GetProcessor() *Processor {
	once.Do(func() {
		instance = NewProcessor()
	})
	return instance
}

func (p *Processor) RegisterPool(name string, cfg PoolConfig, factory func() (*PooledResource, error)) {
	p.mu.Lock()
	defer p.mu.Unlock()

	if existing, exists := p.resourcePools[name]; exists {
		existing.Close()
	}

	p.resourcePools[name] = NewResourcePool(cfg.MaxSize, cfg.MinIdle, factory)
	p.poolConfigs[name] = cfg
	logger.Info("", "Resource pool registered", map[string]interface{}{
		"name":     name,
		"max_size": cfg.MaxSize,
		"min_idle": cfg.MinIdle,
	})
}

func (p *Processor) GetPool(name string) (*ResourcePool, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	pool, exists := p.resourcePools[name]
	return pool, exists
}

func (p *Processor) ExecuteHandler(ctx context.Context, request *models.APIRequest) (*models.APIResponse, error) {
	traceID := p.ensureTraceID(request.TraceID)

	span := tracing.NewSpan(traceID, "core", "execute_handler")
	defer p.finalizeSpan(span)

	logger.Info(traceID, "starting request processing", map[string]interface{}{
		"namespace":   request.Namespace,
		"entity_type": request.EntityType,
	})

	if err := p.validateParams(request.Payload); err != nil {
		return p.handleValidationError(traceID, span, err), nil
	}

	cfg, err := p.configMgr.GetConfig(request.Namespace)
	if err != nil {
		return p.handleConfigError(traceID, span, request.Namespace, err), nil
	}

	poolName := p.resolvePoolName(cfg.Parameters)
	pool := p.ensurePoolExists(poolName)

	resource, resp := p.acquireResource(ctx, pool, poolName, traceID, span)
	if resp != nil {
		return resp, nil
	}
	defer pool.Release(resource)

	result, err := p.processCore(request.Payload, cfg.Parameters, resource)
	if err != nil {
		return p.handleProcessingError(traceID, span, err), nil
	}

	if err := p.persistResult(result); err != nil {
		return p.handlePersistenceError(traceID, span, err), nil
	}

	p.emitEvent("task.completed", result)
	metrics.Inc("requests_processed", map[string]string{
		"namespace": request.Namespace,
		"status":    "success",
	})
	logger.Info(traceID, "request processed successfully", nil)

	return p.buildSuccessResponse(result), nil
}

func (p *Processor) ensureTraceID(traceID string) string {
	if traceID == "" {
		return tracing.GenerateTraceID()
	}
	return traceID
}

func (p *Processor) finalizeSpan(span *tracing.Span) {
	span.EndTime = time.Now()
	tracing.RecordSpan(span)
	topology.RecordSpan(span)
}

func (p *Processor) handleValidationError(traceID string, span *tracing.Span, err error) *models.APIResponse {
	logger.Warn(traceID, "parameter validation failed", map[string]interface{}{
		"error": err.Error(),
	})
	span.Status = "error"
	return &models.APIResponse{
		Code:    422,
		Message: err.Error(),
	}
}

func (p *Processor) handleConfigError(traceID string, span *tracing.Span, namespace string, err error) *models.APIResponse {
	logger.Warn(traceID, "config not found", map[string]interface{}{
		"namespace": namespace,
		"error":     err.Error(),
	})
	span.Status = "error"
	return &models.APIResponse{
		Code:    404,
		Message: fmt.Sprintf("config not found: %v", err),
	}
}

func (p *Processor) resolvePoolName(params map[string]interface{}) string {
	if pn, ok := params["pool_name"].(string); ok {
		return pn
	}
	return "default"
}

func (p *Processor) ensurePoolExists(poolName string) *ResourcePool {
	pool, exists := p.GetPool(poolName)
	if !exists {
		p.RegisterPool(poolName, PoolConfig{
			MaxSize: 100,
			MinIdle: 10,
			Type:    "worker",
		}, func() (*PooledResource, error) {
			return &PooledResource{
				ID:   "res_" + tracing.GenerateTraceID(),
				Type: poolName,
				Data: make(map[string]interface{}),
			}, nil
		})
		pool, _ = p.GetPool(poolName)
	}
	return pool
}

func (p *Processor) acquireResource(ctx context.Context, pool *ResourcePool, poolName, traceID string, span *tracing.Span) (*PooledResource, *models.APIResponse) {
	acquireCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	resource, err := pool.Acquire(acquireCtx)
	if err != nil {
		logger.Error(traceID, "resource acquisition timeout", map[string]interface{}{
			"pool": poolName,
		})
		span.Status = "error"
		metrics.Inc("requests_failed_total", map[string]string{"reason": "resource_timeout"})
		return nil, &models.APIResponse{
			Code:    504,
			Message: "upstream service timeout",
		}
	}
	return resource, nil
}

func (p *Processor) handleProcessingError(traceID string, span *tracing.Span, err error) *models.APIResponse {
	logger.Error(traceID, "core processing failed", map[string]interface{}{
		"error": err.Error(),
	})
	span.Status = "error"
	return &models.APIResponse{
		Code:    500,
		Message: "internal processing error",
	}
}

func (p *Processor) handlePersistenceError(traceID string, span *tracing.Span, err error) *models.APIResponse {
	logger.Error(traceID, "failed to persist result", map[string]interface{}{
		"error": err.Error(),
	})
	span.Status = "error"
	return &models.APIResponse{
		Code:    500,
		Message: "failed to persist result",
	}
}

func (p *Processor) buildSuccessResponse(result map[string]interface{}) *models.APIResponse {
	return &models.APIResponse{
		Code:    200,
		Message: "success",
		Data:    result,
	}
}

func (p *Processor) validateParams(params map[string]interface{}) error {
	if params == nil {
		return &ValidationError{Details: "payload is required"}
	}
	if _, ok := params["type"]; !ok {
		return &ValidationError{Details: "type field is required"}
	}
	return nil
}

func (p *Processor) processCore(payload map[string]interface{}, rules map[string]interface{}, resource *PooledResource) (map[string]interface{}, error) {
	result := make(map[string]interface{})

	result["received"] = true
	result["timestamp"] = time.Now().Format(time.RFC3339)
	result["processed"] = true
	result["resource_id"] = resource.ID
	result["resource_reused"] = resource.Timestamp.Unix() > 0

	if timeout, ok := rules["timeout"].(int); ok {
		result["timeout_applied"] = timeout
	}

	if retries, ok := rules["retries"].(int); ok {
		result["retries_configured"] = retries
	}

	if entityType, ok := payload["type"].(string); ok {
		result["entity_type"] = entityType
	}

	if resource.Data != nil {
		resource.Data["last_used"] = time.Now().Format(time.RFC3339)
	}

	return result, nil
}

func (p *Processor) persistResult(result map[string]interface{}) error {
	logger.Debug("", "persisting result", map[string]interface{}{
		"result_keys": len(result),
	})
	return nil
}

func (p *Processor) emitEvent(eventType string, data map[string]interface{}) {
	logger.Debug("", "event emitted", map[string]interface{}{
		"event_type": eventType,
	})

	metrics.Inc("events_emitted", map[string]string{
		"type": eventType,
	})
}

func (p *Processor) GetStats() map[string]interface{} {
	p.mu.RLock()
	defer p.mu.RUnlock()

	pools := make(map[string]interface{})
	for name, pool := range p.resourcePools {
		pools[name] = pool.Stats()
	}

	return map[string]interface{}{
		"pools": pools,
	}
}

func (p *Processor) Execute(ctx context.Context, request *models.APIRequest) (*models.APIResponse, error) {
	return p.ExecuteHandler(ctx, request)
}

func Execute(ctx context.Context, request *models.APIRequest) (*models.APIResponse, error) {
	return GetProcessor().ExecuteHandler(ctx, request)
}
