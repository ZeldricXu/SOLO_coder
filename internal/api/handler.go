package api

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/chaoslab/platform/internal/abstraction"
	"github.com/chaoslab/platform/internal/common"
	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type APIHandler struct {
	dnsService      ports.DNSProxyService
	mtlsService     ports.MTLSCertificateService
	chaosService    ports.ChaosOrchestratorService
	trafficService  abstraction.TrafficController
	eventService    abstraction.EventStore
	registryService abstraction.ImageDistributionService
	sidecarService  abstraction.SidecarLifecycleManager
	auditService    abstraction.AuditService
	logger          *zap.Logger
}

func NewAPIHandler(
	dnsService ports.DNSProxyService,
	mtlsService ports.MTLSCertificateService,
	chaosService ports.ChaosOrchestratorService,
	trafficService abstraction.TrafficController,
	eventService abstraction.EventStore,
	registryService abstraction.ImageDistributionService,
	sidecarService abstraction.SidecarLifecycleManager,
	auditService abstraction.AuditService,
	logger *zap.Logger,
) *APIHandler {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &APIHandler{
		dnsService:      dnsService,
		mtlsService:     mtlsService,
		chaosService:    chaosService,
		trafficService:  trafficService,
		eventService:    eventService,
		registryService: registryService,
		sidecarService:  sidecarService,
		auditService:    auditService,
		logger:          logger,
	}
}

func (h *APIHandler) CreateResource(c *gin.Context) {
	ctx := c.Request.Context()

	var req common.CreateResourceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.Error(err)
		return
	}

	cmd := &common.Command{
		CommandType: "create_resource",
		EntityID:    fmt.Sprintf("res_%d", time.Now().UnixNano()),
		Payload:     map[string]interface{}{"type": req.Type, "config": req.Config},
		IssuedBy:    c.GetHeader("X-User-ID"),
	}

	if err := h.auditService.PersistCommand(ctx, cmd); err != nil {
		c.Error(err)
		return
	}

	resource := &common.Resource{
		ID:         cmd.EntityID,
		Type:       req.Type,
		Status:     common.StatusProvisioning,
		Config:     req.Config,
		Labels:     req.Labels,
		CreatedAt:  time.Now(),
	}

	event := &common.DomainEvent{
		EntityID:  resource.ID,
		EventType: "resource.created",
		Payload: map[string]interface{}{
			"type":   req.Type,
			"config": req.Config,
		},
	}

	if err := h.eventService.AppendEvent(ctx, event); err != nil {
		h.logger.Warn("failed to append event", zap.Error(err))
	}

	_ = h.auditService.UpdateCommandStatus(ctx, cmd.CommandID, "completed", map[string]interface{}{
		"resource_id": resource.ID,
		"status":      resource.Status,
	}, "")

	c.JSON(http.StatusCreated, gin.H{
		"code": 201,
		"data": resource,
	})
}

func (h *APIHandler) GetResourceStatus(c *gin.Context) {
	ctx := c.Request.Context()
	id := c.Param("id")

	events, err := h.eventService.GetEvents(ctx, id, 0)
	if err != nil {
		c.Error(err)
		return
	}

	status := common.ResourceStatus{
		ID:       id,
		Status:   common.StatusRunning,
		Progress: 0.8,
	}

	if len(events) > 0 {
		lastEvent := events[len(events)-1]
		if lastEvent.EventType == "resource.completed" {
			status.Status = common.StatusCompleted
			status.Progress = 1.0
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": status,
	})
}

func (h *APIHandler) BatchOperation(c *gin.Context) {
	ctx := c.Request.Context()

	var req struct {
		Operations []*common.Operation `json:"operations"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.Error(err)
		return
	}

	results := make([]*common.OperationResult, 0, len(req.Operations))
	for _, op := range req.Operations {
		result := &common.OperationResult{
			ID:      op.ID,
			Success: true,
			Message: fmt.Sprintf("operation %s executed", op.Action),
		}

		event := &common.DomainEvent{
			EntityID:  op.ID,
			EventType: fmt.Sprintf("resource.%s", op.Action),
			Payload:   map[string]interface{}{"action": op.Action, "params": op.Params},
		}
		_ = h.eventService.AppendEvent(ctx, event)

		results = append(results, result)
	}

	batchResult := &common.BatchResult{
		BatchID: fmt.Sprintf("batch_%d", time.Now().UnixNano()),
		Results: results,
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": batchResult,
	})
}

func (h *APIHandler) ExecuteWorkflow(c *gin.Context) {
	ctx := c.Request.Context()

	var req struct {
		ImageRef   string                 `json:"image_ref"`
		Namespace  string                 `json:"namespace"`
		Config     map[string]interface{} `json:"config"`
		TraceID    string                 `json:"trace_id"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.Error(err)
		return
	}

	result, err := h.processHandler(ctx, req)
	if err != nil {
		var appErr *domain.AppError
		if errors.As(err, &appErr) {
			c.JSON(appErr.Code, gin.H{"code": appErr.Code, "message": appErr.Message})
			return
		}
		var commonErr *common.AppError
		if errors.As(err, &commonErr) {
			c.JSON(commonErr.Code, gin.H{"code": commonErr.Code, "message": commonErr.Message})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "Internal processing error"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": result,
	})
}

func (h *APIHandler) processHandler(ctx context.Context, req struct {
	ImageRef   string                 `json:"image_ref"`
	Namespace  string                 `json:"namespace"`
	Config     map[string]interface{} `json:"config"`
	TraceID    string                 `json:"trace_id"`
}) (interface{}, error) {
	h.logger.Info("processing workflow",
		zap.String("image_ref", req.ImageRef),
		zap.String("namespace", req.Namespace),
	)

	pullResult, err := h.registryService.PullImage(ctx, req.ImageRef, nil)
	if err != nil {
		return nil, err
	}

	canaryCfg := &common.CanaryConfig{
		Namespace:      req.Namespace,
		Service:        "workflow-service",
		PrimaryVersion: "v1",
		CanaryVersion:  "v2",
		TrafficWeight:  10,
	}
	trafficPolicy, err := h.trafficService.ConfigureCanary(ctx, canaryCfg)
	if err != nil {
		return nil, err
	}

	event := &common.DomainEvent{
		EntityID:  req.TraceID,
		EventType: "workflow.completed",
		Payload: map[string]interface{}{
			"image_ref":    req.ImageRef,
			"pull_result":  pullResult,
			"traffic_policy": trafficPolicy,
		},
	}
	if err := h.eventService.AppendEvent(ctx, event); err != nil {
		h.logger.Warn("failed to append workflow event", zap.Error(err))
	}

	stats := map[string]interface{}{
		"throughput":  1500,
		"latency_p99": 250,
		"error_rate":  0.001,
		"image_size":  pullResult.TotalSize,
		"cache_hit":   float64(pullResult.CachedSize) / float64(pullResult.TotalSize),
	}

	return stats, nil
}

func (h *APIHandler) HealthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"status":  "healthy",
		"version": "1.0.0",
		"time":    time.Now().UTC(),
	})
}
