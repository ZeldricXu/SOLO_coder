package coreprocessor

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"taskflow/pkg/models"
)

type Resource struct {
	ID          string
	Type        string
	InUse       bool
	LeasedAt    time.Time
	LeaseHolder string
}

type ResourcePool struct {
	resources []*Resource
	mu        sync.Mutex
	cond      *sync.Cond
	maxSize   int
}

func NewResourcePool(maxSize int) *ResourcePool {
	p := &ResourcePool{
		resources: make([]*Resource, 0, maxSize),
		maxSize:   maxSize,
	}
	p.cond = sync.NewCond(&p.mu)

	for i := 0; i < maxSize; i++ {
		p.resources = append(p.resources, &Resource{
			ID:   fmt.Sprintf("res_%d", i),
			Type: "worker",
		})
	}

	return p
}

func (p *ResourcePool) Acquire(ctx context.Context, leaseHolder string, timeout time.Duration) (*Resource, error) {
	p.mu.Lock()
	defer p.mu.Unlock()

	if timeout <= 0 {
		for {
			if res := p.findAvailableLocked(); res != nil {
				p.leaseResourceLocked(res, leaseHolder)
				return res, nil
			}
			p.cond.Wait()

			select {
			case <-ctx.Done():
				return nil, &models.ResourceAcquisitionError{
					Message: fmt.Sprintf("context cancelled acquiring resource for %s", leaseHolder),
				}
			default:
			}
		}
	}

	deadline := time.Now().Add(timeout)
	for {
		if res := p.findAvailableLocked(); res != nil {
			p.leaseResourceLocked(res, leaseHolder)
			return res, nil
		}

		waitTime := time.Until(deadline)
		if waitTime <= 0 {
			return nil, &models.ResourceAcquisitionError{
				Message: fmt.Sprintf("timeout acquiring resource for %s", leaseHolder),
			}
		}

		timer := time.NewTimer(waitTime)
		go p.waitForTimeout(timer)
		p.cond.Wait()
		timer.Stop()

		if time.Now().After(deadline) {
			return nil, &models.ResourceAcquisitionError{
				Message: fmt.Sprintf("timeout acquiring resource for %s", leaseHolder),
			}
		}
	}
}

func (p *ResourcePool) waitForTimeout(timer *time.Timer) {
	<-timer.C
	p.cond.Broadcast()
}

func (p *ResourcePool) findAvailableLocked() *Resource {
	for _, res := range p.resources {
		if !res.InUse {
			return res
		}
	}
	return nil
}

func (p *ResourcePool) leaseResourceLocked(res *Resource, leaseHolder string) {
	res.InUse = true
	res.LeasedAt = time.Now()
	res.LeaseHolder = leaseHolder
}

func (p *ResourcePool) Release(res *Resource) {
	p.mu.Lock()
	defer p.mu.Unlock()

	res.InUse = false
	res.LeaseHolder = ""
	p.cond.Signal()
}

func (p *ResourcePool) GetAvailableCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()

	count := 0
	for _, res := range p.resources {
		if !res.InUse {
			count++
		}
	}
	return count
}

func (p *ResourcePool) GetTotalSize() int {
	return p.maxSize
}

type ConfigManager struct {
	configs map[string]*models.ConfigDefinition
	mu      sync.RWMutex
}

func NewConfigManager() *ConfigManager {
	return &ConfigManager{
		configs: make(map[string]*models.ConfigDefinition),
	}
}

func (m *ConfigManager) LoadConfig(namespace string) *models.ConfigDefinition {
	m.mu.RLock()
	cfg, exists := m.configs[namespace]
	m.mu.RUnlock()

	if exists {
		return cfg
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if cfg, exists := m.configs[namespace]; exists {
		return cfg
	}

	cfg = m.createDefaultConfig(namespace)
	m.configs[namespace] = cfg
	return cfg
}

func (m *ConfigManager) UpdateConfig(namespace string, parameters map[string]interface{}) *models.ConfigDefinition {
	m.mu.Lock()
	defer m.mu.Unlock()

	cfg, exists := m.configs[namespace]
	if !exists {
		cfg = m.createDefaultConfig(namespace)
		m.configs[namespace] = cfg
	}

	now := time.Now()
	cfg.Version++
	cfg.Parameters = parameters
	cfg.UpdatedAt = now
	cfg.AppliedAt = &now

	return cfg
}

func (m *ConfigManager) createDefaultConfig(namespace string) *models.ConfigDefinition {
	now := time.Now()
	return &models.ConfigDefinition{
		ConfigID:  fmt.Sprintf("cfg_%s", namespace),
		Namespace: namespace,
		Version:   1,
		Parameters: map[string]interface{}{
			"timeout": 30,
			"retries": 3,
		},
		Enabled:   true,
		AppliedAt: &now,
		CreatedAt: now,
		UpdatedAt: now,
	}
}

type RunManager struct {
	runs map[string]*models.RunInstance
	mu   sync.RWMutex
}

func NewRunManager() *RunManager {
	return &RunManager{
		runs: make(map[string]*models.RunInstance),
	}
}

func (m *RunManager) CreateRun(entityID string) *models.RunInstance {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	run := &models.RunInstance{
		RunID:     fmt.Sprintf("run_%d", now.UnixNano()),
		EntityID:  entityID,
		Phase:     models.RunPhasePending,
		Progress:  0,
		Metadata:  make(map[string]interface{}),
		CreatedAt: now,
	}

	m.runs[run.RunID] = run
	return run
}

func (m *RunManager) UpdateRun(runID string, phase models.RunPhase, progress float64, errorDetail string) (*models.RunInstance, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	run, exists := m.runs[runID]
	if !exists {
		return nil, fmt.Errorf("run %s not found", runID)
	}

	now := time.Now()
	run.Phase = phase
	run.Progress = progress

	if errorDetail != "" {
		run.ErrorDetail = errorDetail
	}

	if phase == models.RunPhaseRunning && run.StartedAt == nil {
		run.StartedAt = &now
	}

	if isTerminalPhase(phase) {
		run.CompletedAt = &now
	}

	return run, nil
}

func isTerminalPhase(phase models.RunPhase) bool {
	return phase == models.RunPhaseCompleted ||
		phase == models.RunPhaseFailed ||
		phase == models.RunPhaseCancelled
}

func (m *RunManager) GetRun(runID string) (*models.RunInstance, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	run, exists := m.runs[runID]
	return run, exists
}

func (m *RunManager) GetActiveRuns() []*models.RunInstance {
	m.mu.RLock()
	defer m.mu.RUnlock()

	active := make([]*models.RunInstance, 0, len(m.runs))
	for _, run := range m.runs {
		if !isTerminalPhase(run.Phase) {
			active = append(active, run)
		}
	}
	return active
}

type EventType string

const (
	EventTaskCompleted    EventType = "task.completed"
	EventTaskFailed       EventType = "task.failed"
	EventTaskStarted      EventType = "task.started"
	EventResourceReleased EventType = "resource.released"
)

type Event struct {
	Type      EventType
	Data      interface{}
	Timestamp time.Time
}

type EventHandler func(Event)

type EventEmitter struct {
	handlers map[EventType][]EventHandler
	mu       sync.RWMutex
}

func NewEventEmitter() *EventEmitter {
	return &EventEmitter{
		handlers: make(map[EventType][]EventHandler),
	}
}

func (e *EventEmitter) On(eventType EventType, handler EventHandler) {
	e.mu.Lock()
	defer e.mu.Unlock()

	e.handlers[eventType] = append(e.handlers[eventType], handler)
}

func (e *EventEmitter) Emit(eventType EventType, data interface{}) {
	e.mu.RLock()
	handlers, exists := e.handlers[eventType]
	if !exists {
		e.mu.RUnlock()
		return
	}

	event := Event{
		Type:      eventType,
		Data:      data,
		Timestamp: time.Now(),
	}
	e.mu.RUnlock()

	for _, handler := range handlers {
		go handler(event)
	}
}

type ProcessorFunc func(ctx context.Context, payload interface{}, config *models.ConfigDefinition) (interface{}, error)
type PersistenceFunc func(result interface{}) error

type CoreProcessor struct {
	resourcePool  *ResourcePool
	configManager *ConfigManager
	runManager    *RunManager
	eventEmitter  *EventEmitter
	processFunc   ProcessorFunc
	persistFunc   PersistenceFunc
	mu            sync.RWMutex
}

var (
	processorInstance *CoreProcessor
	processorOnce     sync.Once
)

func NewCoreProcessor() *CoreProcessor {
	return &CoreProcessor{
		resourcePool:  NewResourcePool(10),
		configManager: NewConfigManager(),
		runManager:    NewRunManager(),
		eventEmitter:  NewEventEmitter(),
	}
}

func GetCoreProcessor() *CoreProcessor {
	processorOnce.Do(func() {
		processorInstance = NewCoreProcessor()
	})
	return processorInstance
}

func (p *CoreProcessor) SetProcessorFunc(fn ProcessorFunc) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.processFunc = fn
}

func (p *CoreProcessor) SetPersistenceFunc(fn PersistenceFunc) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.persistFunc = fn
}

func (p *CoreProcessor) GetResourcePool() *ResourcePool {
	return p.resourcePool
}

func (p *CoreProcessor) GetConfigManager() *ConfigManager {
	return p.configManager
}

func (p *CoreProcessor) GetRunManager() *RunManager {
	return p.runManager
}

func (p *CoreProcessor) GetEventEmitter() *EventEmitter {
	return p.eventEmitter
}

type ExecuteRequest struct {
	TraceID   string
	Namespace string
	Params    map[string]interface{}
	Payload   interface{}
}

func (p *CoreProcessor) ExecuteHandler(ctx context.Context, req *ExecuteRequest) *models.ProcessingResult {
	startTime := time.Now()
	result := p.newSuccessResult()

	if err := p.validateParams(req.Params); err != nil {
		return p.buildValidationError(result, err, startTime)
	}

	config := p.configManager.LoadConfig(req.Namespace)
	run := p.createRun(req.TraceID)
	result.RunID = run.RunID

	p.runManager.UpdateRun(run.RunID, models.RunPhaseInitializing, 0.1, "")

	resource, err := p.resourcePool.Acquire(ctx, req.TraceID, p.getTimeout(config))
	if err != nil {
		return p.buildResourceError(result, run.RunID, err, startTime)
	}
	defer p.releaseResource(resource)

	p.runManager.UpdateRun(run.RunID, models.RunPhaseRunning, 0.3, "")

	processResult, err := p.executeProcessing(ctx, req.Payload, config)
	if err != nil {
		return p.buildProcessingError(result, run.RunID, err, startTime, ctx, req)
	}

	if err := p.executePersistence(processResult, run.RunID); err != nil {
		return p.buildPersistenceError(result, run.RunID, err, startTime)
	}

	return p.buildSuccessResult(result, run.RunID, processResult, req, startTime)
}

func (p *CoreProcessor) newSuccessResult() *models.ProcessingResult {
	return &models.ProcessingResult{
		ErrorCode:    200,
		ErrorMessage: "success",
	}
}

func (p *CoreProcessor) createRun(traceID string) *models.RunInstance {
	var entityID strings.Builder
	entityID.Grow(7 + len(traceID))
	entityID.WriteString("entity_")
	entityID.WriteString(traceID)
	return p.runManager.CreateRun(entityID.String())
}

func (p *CoreProcessor) releaseResource(resource *Resource) {
	p.resourcePool.Release(resource)
	p.eventEmitter.Emit(EventResourceReleased, resource.ID)
}

func (p *CoreProcessor) executeProcessing(ctx context.Context, payload interface{}, config *models.ConfigDefinition) (interface{}, error) {
	processCtx, cancel := context.WithTimeout(ctx, p.getProcessingTimeout(config))
	defer cancel()
	return p.processCore(processCtx, payload, config)
}

func (p *CoreProcessor) executePersistence(processResult interface{}, runID string) error {
	p.runManager.UpdateRun(runID, models.RunPhaseFinalizing, 0.8, "")
	if p.persistFunc == nil {
		return nil
	}
	return p.persistFunc(processResult)
}

func (p *CoreProcessor) buildValidationError(result *models.ProcessingResult, err error, startTime time.Time) *models.ProcessingResult {
	result.ErrorCode = 422
	result.ErrorMessage = "Validation failed"
	result.ErrorDetails = err.(*models.ValidationError).Details
	result.ExecutionTimeMs = time.Since(startTime).Milliseconds()
	return result
}

func (p *CoreProcessor) buildResourceError(result *models.ProcessingResult, runID string, err error, startTime time.Time) *models.ProcessingResult {
	p.runManager.UpdateRun(runID, models.RunPhaseFailed, 0, err.Error())
	result.ErrorCode = 503
	result.ErrorMessage = "Resource acquisition timeout"
	result.ExecutionTimeMs = time.Since(startTime).Milliseconds()
	return result
}

func (p *CoreProcessor) buildProcessingError(result *models.ProcessingResult, runID string, err error, startTime time.Time, ctx context.Context, req *ExecuteRequest) *models.ProcessingResult {
	p.runManager.UpdateRun(runID, models.RunPhaseFailed, 0.5, err.Error())

	switch e := err.(type) {
	case *models.TimeoutError:
		result.ErrorCode = 504
		result.ErrorMessage = "Upstream service timeout"
	case *models.ValidationError:
		result.ErrorCode = 422
		result.ErrorMessage = "Validation failed"
		result.ErrorDetails = e.Details
	default:
		result.ErrorCode = 500
		result.ErrorMessage = "Internal processing error"
	}

	p.rollbackTransaction(ctx, req)
	result.ExecutionTimeMs = time.Since(startTime).Milliseconds()
	return result
}

func (p *CoreProcessor) buildPersistenceError(result *models.ProcessingResult, runID string, err error, startTime time.Time) *models.ProcessingResult {
	p.runManager.UpdateRun(runID, models.RunPhaseFailed, 0.85, err.Error())
	result.ErrorCode = 500
	result.ErrorMessage = "Persistence failed"
	result.ExecutionTimeMs = time.Since(startTime).Milliseconds()
	return result
}

func (p *CoreProcessor) buildSuccessResult(result *models.ProcessingResult, runID string, processResult interface{}, req *ExecuteRequest, startTime time.Time) *models.ProcessingResult {
	p.runManager.UpdateRun(runID, models.RunPhaseCompleted, 1.0, "")
	result.Success = true
	result.Data = processResult
	result.ErrorCode = 200

	p.eventEmitter.Emit(EventTaskCompleted, map[string]interface{}{
		"run_id":    runID,
		"result":    processResult,
		"trace_id":  req.TraceID,
		"namespace": req.Namespace,
	})

	result.ExecutionTimeMs = time.Since(startTime).Milliseconds()
	return result
}

func (p *CoreProcessor) validateParams(params map[string]interface{}) error {
	if params == nil {
		return newValidationError("params cannot be nil", "params", "required")
	}

	if _, exists := params["action"]; !exists {
		return newValidationError("missing required parameter: action", "action", "required")
	}

	return nil
}

func newValidationError(message, field, reason string) *models.ValidationError {
	return &models.ValidationError{
		Message: message,
		Details: map[string]interface{}{
			field: reason,
		},
	}
}

func (p *CoreProcessor) processCore(ctx context.Context, payload interface{}, config *models.ConfigDefinition) (interface{}, error) {
	select {
	case <-ctx.Done():
		return nil, &models.TimeoutError{Message: "processing timed out"}
	default:
	}

	if p.processFunc != nil {
		return p.processFunc(ctx, payload, config)
	}

	return map[string]interface{}{
		"processed": true,
		"payload":   payload,
		"config_id": config.ConfigID,
		"version":   config.Version,
	}, nil
}

func (p *CoreProcessor) rollbackTransaction(ctx context.Context, req *ExecuteRequest) {
}

func (p *CoreProcessor) getTimeout(config *models.ConfigDefinition) time.Duration {
	if timeout, ok := config.Parameters["resource_timeout"].(int); ok {
		return time.Duration(timeout) * time.Second
	}
	return 5 * time.Second
}

func (p *CoreProcessor) getProcessingTimeout(config *models.ConfigDefinition) time.Duration {
	if timeout, ok := config.Parameters["processing_timeout"].(int); ok {
		return time.Duration(timeout) * time.Second
	}
	if timeout, ok := config.Parameters["timeout"].(int); ok {
		return time.Duration(timeout) * time.Second
	}
	return 30 * time.Second
}
