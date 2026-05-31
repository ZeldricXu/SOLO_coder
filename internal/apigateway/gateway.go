package apigateway

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"session316/internal/config"
	"session316/internal/logger"
	"session316/internal/models"
	"session316/pkg/errors"
	"session316/pkg/utils"
)

const (
	RequestStateInitialized = "initialized"
	RequestStateValidating  = "validating"
	RequestStateLoading     = "loading_config"
	RequestStateAcquiring   = "acquiring_resource"
	RequestStateProcessing  = "processing"
	RequestStatePersisting  = "persisting"
	RequestStateCompleted   = "completed"
	RequestStateRollingBack = "rolling_back"
	RequestStateFailed      = "failed"
	RequestStateCleanup     = "cleanup"
)

type Gateway struct {
	cfg          *config.APIConfig
	routes       map[string]*Route
	routesMu     sync.RWMutex
	resourcePool chan struct{}
	metrics      *GatewayMetrics
	requestStates map[string]string
	statesMu     sync.RWMutex
}

type Route struct {
	Path         string
	Method       string
	Protocol     string
	Target       string
	Transform    string
	AuthRequired bool
	Handler      func(c *gin.Context) error
}

type GatewayMetrics struct {
	TotalRequests   int64
	SuccessRequests int64
	FailedRequests  int64
	TotalLatency    time.Duration
	mu              sync.Mutex
}

type RequestContext struct {
	TraceID    string
	StartTime  time.Time
	Namespace  string
	Config     *models.Config
	RollbackFn []func() error
}

func NewGateway(cfg *config.APIConfig) *Gateway {
	g := &Gateway{
		cfg:           cfg,
		routes:        make(map[string]*Route),
		resourcePool:  make(chan struct{}, 100),
		metrics:       &GatewayMetrics{},
		requestStates: make(map[string]string),
	}

	for i := 0; i < 100; i++ {
		g.resourcePool <- struct{}{}
	}

	for _, route := range cfg.Routes {
		g.AddRoute(&Route{
			Path:         route.Path,
			Method:       route.Method,
			Protocol:     route.Protocol,
			Target:       route.Target,
			Transform:    route.Transform,
			AuthRequired: route.AuthRequired,
		})
	}

	return g
}

func (g *Gateway) transitionState(traceID string, fromState, toState string) bool {
	g.statesMu.Lock()
	defer g.statesMu.Unlock()

	currentState, exists := g.requestStates[traceID]
	if !exists {
		if fromState == "" && toState == RequestStateInitialized {
			g.requestStates[traceID] = toState
			logger.Debug("State transition",
				zap.String("trace_id", traceID),
				zap.String("from", "none"),
				zap.String("to", toState))
			return true
		}
		logger.Warn("State transition failed: request not found",
			zap.String("trace_id", traceID),
			zap.String("expected_from", fromState),
			zap.String("to", toState))
		return false
	}

	if currentState != fromState {
		logger.Error("Invalid state transition",
			zap.String("trace_id", traceID),
			zap.String("current", currentState),
			zap.String("expected", fromState),
			zap.String("to", toState))
		return false
	}

	g.requestStates[traceID] = toState
	logger.Debug("State transition",
		zap.String("trace_id", traceID),
		zap.String("from", fromState),
		zap.String("to", toState))
	return true
}

func (g *Gateway) getState(traceID string) string {
	g.statesMu.RLock()
	defer g.statesMu.RUnlock()
	if state, exists := g.requestStates[traceID]; exists {
		return state
	}
	return ""
}

func (g *Gateway) removeState(traceID string) {
	g.statesMu.Lock()
	defer g.statesMu.Unlock()
	delete(g.requestStates, traceID)
}

func (g *Gateway) AddRoute(route *Route) {
	g.routesMu.Lock()
	defer g.routesMu.Unlock()
	key := route.Method + ":" + route.Path
	g.routes[key] = route
}

func (g *Gateway) GetRoute(method, path string) (*Route, bool) {
	g.routesMu.RLock()
	defer g.routesMu.RUnlock()
	key := method + ":" + path
	route, exists := g.routes[key]
	return route, exists
}

func (g *Gateway) ExecuteHandler(c *gin.Context) {
	traceID := utils.GenerateTraceID()
	ctx := &RequestContext{
		TraceID:   traceID,
		StartTime: time.Now(),
	}

	g.transitionState(traceID, "", RequestStateInitialized)

	var resource struct{}
	var resourceAcquired bool

	defer func() {
		if resourceAcquired {
			g.transitionState(traceID, g.getState(traceID), RequestStateCleanup)
			g.releaseResource(ctx, resource)
			resourceAcquired = false
		}
		g.recordMetrics(ctx)
		g.cleanup(ctx)
		g.removeState(traceID)
	}()

	if !g.transitionState(traceID, RequestStateInitialized, RequestStateValidating) {
		g.handleError(c, errors.InternalError(nil, "状态机状态异常"))
		return
	}
	if err := g.validateParams(c); err != nil {
		g.transitionState(traceID, RequestStateValidating, RequestStateFailed)
		g.handleError(c, err)
		return
	}

	if !g.transitionState(traceID, RequestStateValidating, RequestStateLoading) {
		g.handleError(c, errors.InternalError(nil, "状态机状态异常"))
		return
	}
	cfg, err := g.loadConfig(c.GetString("namespace"))
	if err != nil {
		g.transitionState(traceID, RequestStateLoading, RequestStateFailed)
		g.handleError(c, err)
		return
	}
	ctx.Config = cfg

	if !g.transitionState(traceID, RequestStateLoading, RequestStateAcquiring) {
		g.handleError(c, errors.InternalError(nil, "状态机状态异常"))
		return
	}
	resource, err = g.acquireResource(ctx)
	if err != nil {
		g.transitionState(traceID, RequestStateAcquiring, RequestStateFailed)
		g.handleError(c, err)
		return
	}
	resourceAcquired = true

	if !g.transitionState(traceID, RequestStateAcquiring, RequestStateProcessing) {
		g.handleError(c, errors.InternalError(nil, "状态机状态异常"))
		return
	}
	result, err := g.processCore(c, cfg)
	if err != nil {
		g.transitionState(traceID, RequestStateProcessing, RequestStateRollingBack)
		g.rollbackTransaction(ctx)
		g.transitionState(traceID, RequestStateRollingBack, RequestStateFailed)
		g.handleError(c, err)
		return
	}

	if !g.transitionState(traceID, RequestStateProcessing, RequestStatePersisting) {
		g.handleError(c, errors.InternalError(nil, "状态机状态异常"))
		return
	}
	if err := g.persistResult(ctx, result); err != nil {
		g.transitionState(traceID, RequestStatePersisting, RequestStateRollingBack)
		g.rollbackTransaction(ctx)
		g.transitionState(traceID, RequestStateRollingBack, RequestStateFailed)
		g.handleError(c, err)
		return
	}

	if !g.transitionState(traceID, RequestStatePersisting, RequestStateCompleted) {
		g.handleError(c, errors.InternalError(nil, "状态机状态异常"))
		return
	}

	g.emitEvent("task.completed", g.buildEvent(ctx, result))

	g.metrics.mu.Lock()
	g.metrics.SuccessRequests++
	g.metrics.mu.Unlock()

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "success",
		Data:    result,
	})
}

func (g *Gateway) validateParams(c *gin.Context) error {
	if c.Request.ContentLength > 0 {
		body, err := io.ReadAll(c.Request.Body)
		if err != nil {
			return errors.ValidationError("body", "无法读取请求体")
		}
		c.Request.Body = io.NopCloser(bytes.NewBuffer(body))

		if len(body) == 0 {
			return errors.ValidationError("body", "请求体不能为空")
		}

		contentType := c.GetHeader("Content-Type")
		if strings.Contains(contentType, "application/json") {
			var js json.RawMessage
			if err := json.Unmarshal(body, &js); err != nil {
				return errors.ValidationError("body", "JSON格式无效")
			}
		}
	}

	return nil
}

func (g *Gateway) loadConfig(namespace string) (*models.Config, error) {
	if namespace == "" {
		namespace = "default"
	}

	return &models.Config{
		ConfigID:  utils.GenerateConfigID(),
		Namespace: namespace,
		Version:   1,
		Parameters: map[string]interface{}{
			"timeout": g.cfg.Timeout,
			"retries": g.cfg.Retries,
		},
		Enabled:   true,
		AppliedAt: time.Now(),
	}, nil
}

func (g *Gateway) acquireResource(ctx *RequestContext) (struct{}, error) {
	select {
	case resource := <-g.resourcePool:
		logger.Info("Resource acquired",
			zap.String("trace_id", ctx.TraceID),
			zap.Int("pool_remaining", len(g.resourcePool)),
		)
		return resource, nil
	case <-time.After(time.Duration(g.cfg.Timeout) * time.Second):
		return struct{}{}, errors.New(errors.ErrCodeResourceExhausted, "资源池耗尽，无法获取连接")
	}
}

func (g *Gateway) releaseResource(ctx *RequestContext, resource struct{}) {
	g.resourcePool <- resource
	logger.Info("Resource released",
		zap.String("trace_id", ctx.TraceID),
		zap.Int("pool_remaining", len(g.resourcePool)),
	)
}

func (g *Gateway) processCore(c *gin.Context, cfg *models.Config) (map[string]interface{}, error) {
	var payload map[string]interface{}
	if err := c.ShouldBindJSON(&payload); err != nil && c.Request.ContentLength > 0 {
		return nil, errors.Wrap(err, errors.ErrCodeValidation, "解析请求负载失败")
	}

	rules, _ := cfg.Parameters["rules"].(map[string]interface{})

	result := map[string]interface{}{
		"processed":    true,
		"timestamp":    time.Now().UTC(),
		"trace_id":     c.GetString("trace_id"),
		"input":        payload,
		"rules_applied": rules,
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), time.Duration(g.cfg.Timeout)*time.Second)
	defer cancel()

	select {
	case <-ctx.Done():
		if ctx.Err() == context.DeadlineExceeded {
			return nil, errors.TimeoutError("core_processing")
		}
		return nil, errors.Wrap(ctx.Err(), errors.ErrCodeInternal, "请求被取消")
	default:
	}

	logger.Info("Core processing completed",
		zap.String("trace_id", c.GetString("trace_id")),
		zap.Any("result_keys", getKeys(result)),
	)

	return result, nil
}

func (g *Gateway) persistResult(ctx *RequestContext, result map[string]interface{}) error {
	ctx.RollbackFn = append(ctx.RollbackFn, func() error {
		logger.Info("Rollback persist", zap.String("trace_id", ctx.TraceID))
		return nil
	})

	logger.Info("Result persisted",
		zap.String("trace_id", ctx.TraceID),
		zap.Any("result", result),
	)
	return nil
}

func (g *Gateway) emitEvent(eventType string, event map[string]interface{}) {
	logger.Info("Event emitted",
		zap.String("event_type", eventType),
		zap.Any("event", event),
	)
}

func (g *Gateway) buildEvent(ctx *RequestContext, result map[string]interface{}) map[string]interface{} {
	return map[string]interface{}{
		"event_id":   utils.GenerateUUID(),
		"event_type": "task.completed",
		"trace_id":   ctx.TraceID,
		"timestamp":  time.Now().UTC(),
		"data":       result,
	}
}

func (g *Gateway) rollbackTransaction(ctx *RequestContext) {
	logger.Warn("Executing rollback", zap.String("trace_id", ctx.TraceID))
	for i := len(ctx.RollbackFn) - 1; i >= 0; i-- {
		if err := ctx.RollbackFn[i](); err != nil {
			logger.Error("Rollback step failed",
				zap.String("trace_id", ctx.TraceID),
				zap.Int("step", i),
				zap.Error(err),
			)
		}
	}
}

func (g *Gateway) handleError(c *gin.Context, err error) {
	g.metrics.mu.Lock()
	g.metrics.FailedRequests++
	g.metrics.mu.Unlock()

	var appErr *errors.AppError
	if e, ok := err.(*errors.AppError); ok {
		appErr = e
	} else {
		appErr = errors.InternalError(err, "request_processing")
	}

	logger.Error("Request failed",
		zap.String("trace_id", c.GetString("trace_id")),
		zap.Int("error_code", int(appErr.Code)),
		zap.String("error_message", appErr.Message),
		zap.Error(err),
	)

	c.JSON(appErr.HTTPStatus(), appErr)
}

func (g *Gateway) recordMetrics(ctx *RequestContext) {
	latency := time.Since(ctx.StartTime)
	g.metrics.mu.Lock()
	defer g.metrics.mu.Unlock()
	g.metrics.TotalRequests++
	g.metrics.TotalLatency += latency

	logger.Info("Metrics recorded",
		zap.String("trace_id", ctx.TraceID),
		zap.Duration("latency", latency),
	)
}

func (g *Gateway) cleanup(ctx *RequestContext) {
	logger.Debug("Cleanup completed", zap.String("trace_id", ctx.TraceID))
}

func (g *Gateway) GetMetrics() GatewayMetrics {
	g.metrics.mu.Lock()
	defer g.metrics.mu.Unlock()
	return *g.metrics
}

func (g *Gateway) ResetMetrics() {
	g.metrics.mu.Lock()
	defer g.metrics.mu.Unlock()
	g.metrics = &GatewayMetrics{}
}

func (g *Gateway) RegisterRoutes(r *gin.Engine) {
	api := r.Group("/api/v1")
	{
		api.POST("/resources", g.CreateResource)
		api.GET("/resources/:id/status", g.GetResourceStatus)
		api.POST("/resources/batch", g.BatchOperation)
		api.POST("/execute", g.ExecuteHandler)
	}
}

func (g *Gateway) CreateResource(c *gin.Context) {
	var req models.Resource
	if err := c.ShouldBindJSON(&req); err != nil {
		appErr := errors.ValidationError("request", err.Error())
		c.JSON(appErr.HTTPStatus(), appErr)
		return
	}

	req.ID = utils.GenerateResourceID()
	req.Status = models.StatusProvisioning

	logger.Info("Resource created",
		zap.String("resource_id", req.ID),
		zap.String("type", req.Type),
	)

	c.JSON(http.StatusCreated, models.APIResponse{
		Code:    http.StatusCreated,
		Message: "Resource created",
		Data:    req,
	})
}

func (g *Gateway) GetResourceStatus(c *gin.Context) {
	id := c.Param("id")
	if id == "" {
		appErr := errors.ValidationError("id", "资源ID不能为空")
		c.JSON(appErr.HTTPStatus(), appErr)
		return
	}

	status := map[string]interface{}{
		"id":       id,
		"status":   models.StatusRunning,
		"progress": 0.8,
	}

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "success",
		Data:    status,
	})
}

func (g *Gateway) BatchOperation(c *gin.Context) {
	var req models.BatchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		appErr := errors.ValidationError("request", err.Error())
		c.JSON(appErr.HTTPStatus(), appErr)
		return
	}

	batchID := utils.GenerateBatchID()
	var results []models.BatchResult

	for _, op := range req.Operations {
		result := models.BatchResult{
			ID:     op.ID,
			Status: models.StatusCompleted,
		}
		if op.Action == "invalid" {
			result.Status = models.StatusFailed
			result.Error = "无效操作"
		}
		results = append(results, result)
	}

	logger.Info("Batch operation completed",
		zap.String("batch_id", batchID),
		zap.Int("operation_count", len(req.Operations)),
	)

	c.JSON(http.StatusOK, models.APIResponse{
		Code:    http.StatusOK,
		Message: "Batch operation completed",
		Data: models.BatchResponse{
			BatchID: batchID,
			Results: results,
		},
	})
}

func (g *Gateway) TransformProtocol(input map[string]interface{}, sourceProto, targetProto string) (map[string]interface{}, error) {
	output := make(map[string]interface{})

	switch targetProto {
	case "grpc":
		output["@type"] = "grpc_request"
		output["payload"] = input
	case "rest":
		output = input
	case "graphql":
		output["query"] = input
	default:
		output = input
	}

	output["_transformed"] = true
	output["_source"] = sourceProto
	output["_target"] = targetProto
	output["_timestamp"] = time.Now().UTC()

	return output, nil
}

func getKeys(m map[string]interface{}) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	return keys
}
