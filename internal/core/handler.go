package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/datamigration/platform/internal/database"
	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/internal/tenant"
	"github.com/datamigration/platform/pkg/models"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"golang.org/x/sync/semaphore"
)

type ProcessingContext struct {
	TraceID   string
	TenantID  string
	StartTime time.Time
	Events    []DomainEvent
	mu        sync.Mutex
}

type DomainEvent struct {
	Type      string
	Payload   map[string]interface{}
	Timestamp time.Time
}

type HandlerConfig struct {
	MaxConcurrent int64
	DefaultTimeout time.Duration
}

type Handler struct {
	repo    *database.Repository
	sem     *semaphore.Weighted
	config  HandlerConfig
	eventBus chan DomainEvent
}

func NewHandler(repo *database.Repository, cfg HandlerConfig) *Handler {
	if cfg.MaxConcurrent <= 0 {
		cfg.MaxConcurrent = 100
	}
	if cfg.DefaultTimeout <= 0 {
		cfg.DefaultTimeout = 30 * time.Second
	}

	h := &Handler{
		repo:    repo,
		sem:     semaphore.NewWeighted(cfg.MaxConcurrent),
		config:  cfg,
		eventBus: make(chan DomainEvent, 1000),
	}

	go h.processEvents()
	return h
}

func (h *Handler) processEvents() {
	for event := range h.eventBus {
		logger.Info("domain event processed",
			zap.String("type", event.Type),
			zap.Time("timestamp", event.Timestamp),
		)
	}
}

type ValidationError struct {
	Field   string
	Message string
}

func (e *ValidationError) Error() string {
	return fmt.Sprintf("%s: %s", e.Field, e.Message)
}

type TimeoutError struct {
	Operation string
	Duration  time.Duration
}

func (e *TimeoutError) Error() string {
	return fmt.Sprintf("operation %s timed out after %v", e.Operation, e.Duration)
}

type Request struct {
	TraceID   string                 `json:"trace_id"`
	TenantID  string                 `json:"tenant_id"`
	Namespace string                 `json:"namespace"`
	Params    map[string]interface{} `json:"params"`
	Payload   map[string]interface{} `json:"payload"`
}

type Response struct {
	Code    int                    `json:"code"`
	Message string                 `json:"message,omitempty"`
	Data    map[string]interface{} `json:"data,omitempty"`
	Errors  []ValidationError      `json:"errors,omitempty"`
}

func (h *Handler) Handle(ctx context.Context, req *Request) *Response {
	procCtx := &ProcessingContext{
		TraceID:   req.TraceID,
		TenantID:  req.TenantID,
		StartTime: time.Now(),
	}

	if err := h.sem.Acquire(ctx, 1); err != nil {
		return errorResponse(503, "service unavailable: too many requests")
	}
	defer h.sem.Release(1)

	ctx = tenant.WithTenant(ctx, req.TenantID)

	defer func() {
		h.recordMetrics(procCtx)
		if r := recover(); r != nil {
			logger.Error("panic recovered in handler",
				zap.String("trace_id", req.TraceID),
				zap.Any("recover", r),
			)
		}
	}()

	if err := validateParams(req.Params); err != nil {
		var vErr *ValidationError
		if errors.As(err, &vErr) {
			return validationResponse([]ValidationError{*vErr})
		}
		return validationResponse([]ValidationError{{Field: "params", Message: err.Error()}})
	}

	config, err := h.loadConfig(ctx, req.Namespace, req.TenantID)
	if err != nil {
		logger.Warn("failed to load config, using defaults", zap.Error(err))
		config = &models.ConfigDefinition{}
	}

	resource, err := h.acquireResource(ctx, config)
	if err != nil {
		return errorResponse(503, "service unavailable: resource acquisition failed")
	}
	defer h.releaseResource(resource)

	result, err := h.processCore(ctx, req.Payload, config)
	if err != nil {
		var tErr *TimeoutError
		if errors.As(err, &tErr) {
			return errorResponse(504, "upstream service timeout")
		}
		h.rollbackTransaction(procCtx)
		return errorResponse(500, "internal processing error")
	}

	if err := h.persistResult(ctx, procCtx, result); err != nil {
		logger.Error("failed to persist result", zap.Error(err))
		return errorResponse(500, "failed to persist result")
	}

	event := DomainEvent{
		Type:      "task.completed",
		Payload:   result,
		Timestamp: time.Now(),
	}
	h.emitEvent(procCtx, event)

	return successResponse(result)
}

func validateParams(params map[string]interface{}) error {
	if params == nil {
		return nil
	}

	if size, ok := params["data_size"].(float64); ok {
		if size > 10*1024*1024 {
			return &ValidationError{Field: "data_size", Message: "data exceeds 10MB limit"}
		}
	}

	return nil
}

func (h *Handler) loadConfig(ctx context.Context, namespace, tenantID string) (*models.ConfigDefinition, error) {
	if namespace == "" {
		namespace = "default"
	}
	return h.repo.GetConfigDefinition(ctx, namespace, tenantID)
}

type Resource struct {
	ID        string
	PoolSize  int
	Acquired  time.Time
}

func (h *Handler) acquireResource(ctx context.Context, config *models.ConfigDefinition) (*Resource, error) {
	return &Resource{
		ID:       fmt.Sprintf("rsc_%s", uuid.New().String()[:8]),
		PoolSize: 10,
		Acquired: time.Now(),
	}, nil
}

func (h *Handler) releaseResource(r *Resource) {
	if r != nil {
		logger.Debug("resource released", zap.String("resource_id", r.ID))
	}
}

func (h *Handler) processCore(ctx context.Context, payload map[string]interface{}, config *models.ConfigDefinition) (map[string]interface{}, error) {
	ctx, cancel := context.WithTimeout(ctx, h.config.DefaultTimeout)
	defer cancel()

	done := make(chan map[string]interface{}, 1)
	errChan := make(chan error, 1)

	go func() {
		result := make(map[string]interface{})
		for k, v := range payload {
			result[k] = v
		}
		result["processed_at"] = time.Now().UTC().Format(time.RFC3339)
		result["processed"] = true

		time.Sleep(10 * time.Millisecond)

		done <- result
	}()

	select {
	case <-ctx.Done():
		return nil, &TimeoutError{Operation: "processCore", Duration: h.config.DefaultTimeout}
	case err := <-errChan:
		return nil, err
	case result := <-done:
		return result, nil
	}
}

func (h *Handler) persistResult(ctx context.Context, procCtx *ProcessingContext, result map[string]interface{}) error {
	attrs, _ := json.Marshal(result)
	entity := &models.Entity{
		ID:         fmt.Sprintf("ent_%s", uuid.New().String()[:8]),
		Type:       "event",
		Status:     "active",
		Attributes: attrs,
		CreatedAt:  time.Now(),
		UpdatedAt:  time.Now(),
		TenantID:   procCtx.TenantID,
	}

	return h.repo.CreateEntity(ctx, entity)
}

func (h *Handler) emitEvent(procCtx *ProcessingContext, event DomainEvent) {
	procCtx.mu.Lock()
	procCtx.Events = append(procCtx.Events, event)
	procCtx.mu.Unlock()

	select {
	case h.eventBus <- event:
	default:
		logger.Warn("event bus full, dropping event", zap.String("type", event.Type))
	}
}

func (h *Handler) rollbackTransaction(procCtx *ProcessingContext) {
	logger.Warn("transaction rollback initiated",
		zap.String("trace_id", procCtx.TraceID),
		zap.String("tenant_id", procCtx.TenantID),
	)
}

func (h *Handler) recordMetrics(procCtx *ProcessingContext) {
	duration := time.Since(procCtx.StartTime)
	logger.Info("request processed",
		zap.String("trace_id", procCtx.TraceID),
		zap.String("tenant_id", procCtx.TenantID),
		zap.Duration("duration", duration),
		zap.Int("events_emitted", len(procCtx.Events)),
	)
}

func successResponse(data map[string]interface{}) *Response {
	return &Response{
		Code: 200,
		Data: data,
	}
}

func errorResponse(code int, message string) *Response {
	return &Response{
		Code:    code,
		Message: message,
	}
}

func validationResponse(errors []ValidationError) *Response {
	return &Response{
		Code:    422,
		Message: "validation failed",
		Errors:  errors,
	}
}

type ResourceHandler struct {
	handler *Handler
}

func NewResourceHandler(h *Handler) *ResourceHandler {
	return &ResourceHandler{handler: h}
}

func (rh *ResourceHandler) CreateResource(ctx context.Context, req *models.ResourceRequest, tenantID string) *Response {
	payload := map[string]interface{}{
		"type":   req.Type,
		"config": req.Config,
		"labels": req.Labels,
	}

	result := map[string]interface{}{
		"id":     fmt.Sprintf("rsc_%s", uuid.New().String()[:8]),
		"status": "provisioning",
	}

	return &Response{
		Code: 201,
		Data: result,
	}
}

func (rh *ResourceHandler) GetResourceStatus(ctx context.Context, id string, tenantID string) *Response {
	return &Response{
		Code: 200,
		Data: map[string]interface{}{
			"id":       id,
			"status":   "running",
			"progress": 0.75,
		},
	}
}

func (rh *ResourceHandler) BatchOperation(ctx context.Context, req *models.BatchRequest, tenantID string) *Response {
	results := make([]map[string]interface{}, len(req.Operations))
	for i, op := range req.Operations {
		results[i] = map[string]interface{}{
			"id":     op.ID,
			"action": op.Action,
			"status": "accepted",
		}
	}

	return &Response{
		Code: 200,
		Data: map[string]interface{}{
			"batch_id": fmt.Sprintf("batch_%s", uuid.New().String()[:8]),
			"results":  results,
		},
	}
}
