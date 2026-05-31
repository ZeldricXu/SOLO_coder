package api

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"github.com/solocoder/session148/internal/application"
	"github.com/solocoder/session148/internal/domain"
	apperr "github.com/solocoder/session148/pkg/errors"
	"github.com/solocoder/session148/pkg/utils"
)

type APIHandler struct {
	appService *application.AppService
	logger     domain.Logger
}

func NewAPIHandler(appService *application.AppService, logger domain.Logger) *APIHandler {
	return &APIHandler{
		appService: appService,
		logger:     logger,
	}
}

type CreateResourceRequest struct {
	Type   string                 `json:"type" binding:"required"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

type CreateResourceResponse struct {
	ID     string `json:"id"`
	Status string `json:"status"`
}

func (h *APIHandler) CreateResource(c *gin.Context) {
	var req CreateResourceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errorResponse(apperr.NewValidationError("invalid request", err.Error())))
		return
	}

	ctx := c.Request.Context()
	traceID := utils.NewTraceID()

	id, status, err := h.appService.CreateResource(ctx, req.Type, req.Config, req.Labels)
	if err != nil {
		h.logger.WithTraceID(traceID).Error("create resource failed", "error", err)
		c.JSON(http.StatusInternalServerError, errorResponse(err))
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"code": 201,
		"data": CreateResourceResponse{
			ID:     id,
			Status: status,
		},
	})
}

func (h *APIHandler) GetResourceStatus(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	status, err := h.appService.GetResourceStatus(ctx, id)
	if err != nil {
		c.JSON(http.StatusNotFound, errorResponse(err))
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": status,
	})
}

type BatchRequest struct {
	Operations []application.BatchOperation `json:"operations" binding:"required"`
}

type BatchResponse struct {
	BatchID string                  `json:"batch_id"`
	Results []application.BatchResult `json:"results"`
}

func (h *APIHandler) ExecuteBatch(c *gin.Context) {
	var req BatchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errorResponse(apperr.NewValidationError("invalid request", err.Error())))
		return
	}

	ctx := c.Request.Context()
	batchID, results, err := h.appService.ExecuteBatch(ctx, req.Operations)
	if err != nil {
		c.JSON(http.StatusInternalServerError, errorResponse(err))
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": BatchResponse{
			BatchID: batchID,
			Results: results,
		},
	})
}

type ProcessRequest struct {
	Namespace string                 `json:"namespace" binding:"required"`
	Payload   map[string]interface{} `json:"payload" binding:"required"`
	UserID    string                 `json:"user_id"`
}

func (h *APIHandler) ProcessData(c *gin.Context) {
	var req ProcessRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errorResponse(apperr.NewValidationError("invalid request", err.Error())))
		return
	}

	traceID := utils.NewTraceID()
	ctx := c.Request.Context()

	appReq := application.ProcessRequest{
		TraceID:   traceID,
		Namespace: req.Namespace,
		Payload:   req.Payload,
		UserID:    req.UserID,
	}

	result, err := h.appService.ExecuteHandler(ctx, appReq)
	if err != nil {
		h.logger.WithTraceID(traceID).Error("process data failed", "error", err)
		c.JSON(http.StatusInternalServerError, errorResponse(err))
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": result,
	})
}

func (h *APIHandler) GetRunStatus(c *gin.Context) {
	runID := c.Param("id")

	ctx := c.Request.Context()
	run, err := h.appService.GetRunStatus(ctx, runID)
	if err != nil {
		c.JSON(http.StatusNotFound, errorResponse(err))
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": run,
	})
}

func (h *APIHandler) CreateBackup(c *gin.Context) {
	ctx := c.Request.Context()
	backup, err := h.appService.BackupData(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, errorResponse(err))
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"code": 201,
		"data": backup,
	})
}

type RestoreRequest struct {
	BackupID string `json:"backup_id" binding:"required"`
	Dest     string `json:"dest" binding:"required"`
}

func (h *APIHandler) RestoreBackup(c *gin.Context) {
	var req RestoreRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errorResponse(apperr.NewValidationError("invalid request", err.Error())))
		return
	}

	ctx := c.Request.Context()
	if err := h.appService.RestoreData(ctx, req.BackupID, req.Dest); err != nil {
		c.JSON(http.StatusInternalServerError, errorResponse(err))
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{"status": "restored"},
	})
}

func (h *APIHandler) ListBackups(c *gin.Context) {
	ctx := c.Request.Context()
	backups, err := h.appService.ListBackups(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, errorResponse(err))
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": backups,
	})
}

func (h *APIHandler) VerifyAudit(c *gin.Context) {
	ctx := c.Request.Context()
	valid, violations, err := h.appService.VerifyAuditIntegrity(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, errorResponse(err))
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"valid":      valid,
			"violations": violations,
		},
	})
}

func (h *APIHandler) GetMetrics(c *gin.Context) {
	ctx := c.Request.Context()
	snapshot, err := h.appService.GetMetricsSnapshot(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, errorResponse(err))
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": snapshot,
	})
}

type MaskedDataRequest struct {
	RecordID string           `json:"record_id" binding:"required"`
	User     *domain.User `json:"user" binding:"required"`
}

func (h *APIHandler) GetMaskedData(c *gin.Context) {
	var req MaskedDataRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errorResponse(apperr.NewValidationError("invalid request", err.Error())))
		return
	}

	ctx := c.Request.Context()
	masked, err := h.appService.GetMaskedData(ctx, req.RecordID, req.User)
	if err != nil {
		c.JSON(http.StatusInternalServerError, errorResponse(err))
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": masked,
	})
}

type MigrateRequest struct {
	TargetVersion int `json:"target_version"`
}

func (h *APIHandler) MigrateSchema(c *gin.Context) {
	var req MigrateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, errorResponse(apperr.NewValidationError("invalid request", err.Error())))
		return
	}

	ctx := c.Request.Context()
	if err := h.appService.MigrateSchema(ctx, req.TargetVersion); err != nil {
		c.JSON(http.StatusInternalServerError, errorResponse(err))
		return
	}

	version, _ := h.appService.GetSchemaVersion(ctx)
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{"current_version": version},
	})
}

func (h *APIHandler) GetSchemaVersion(c *gin.Context) {
	ctx := c.Request.Context()
	version, err := h.appService.GetSchemaVersion(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, errorResponse(err))
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{"version": version},
	})
}

func (h *APIHandler) HealthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"status":  "healthy",
		"version": "1.0.0",
	})
}

func errorResponse(err error) gin.H {
	if appErr, ok := err.(*apperr.AppError); ok {
		return gin.H{
			"code":    toHTTPStatus(appErr.Code),
			"error":   appErr.Message,
			"details": appErr.Details,
		}
	}
	return gin.H{
		"code":  500,
		"error": err.Error(),
	}
}

func toHTTPStatus(code apperr.ErrorCode) int {
	switch code {
	case apperr.ErrCodeValidation:
		return 400
	case apperr.ErrCodeNotFound:
		return 404
	case apperr.ErrCodePermission:
		return 403
	case apperr.ErrCodeConflict:
		return 409
	case apperr.ErrCodeTimeout:
		return 504
	default:
		return 500
	}
}
