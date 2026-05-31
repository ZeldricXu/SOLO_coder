package api

import (
	"context"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/core"
	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/logging"
	"github.com/solocoder/task-scheduler/internal/models"
	"github.com/solocoder/task-scheduler/internal/scheduler"
)

type APIHandler struct {
	db        *database.Database
	scheduler *scheduler.Scheduler
	executor  *core.TaskExecutor
}

func NewAPIHandler(db *database.Database, sched *scheduler.Scheduler, executor *core.TaskExecutor) *APIHandler {
	return &APIHandler{
		db:        db,
		scheduler: sched,
		executor:  executor,
	}
}

type CreateResourceRequest struct {
	Type   string                 `json:"type" binding:"required"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

type CreateResourceResponse struct {
	Code    int    `json:"code"`
	Message string `json:"message,omitempty"`
	Data    struct {
		ID     string `json:"id"`
		Status string `json:"status"`
	} `json:"data"`
}

type ResourceStatusResponse struct {
	Code    int    `json:"code"`
	Message string `json:"message,omitempty"`
	Data    struct {
		ID       string  `json:"id"`
		Type     string  `json:"type"`
		Status   string  `json:"status"`
		Progress float64 `json:"progress"`
	} `json:"data"`
}

type BatchOperationRequest struct {
	Operations []models.BatchAction `json:"operations" binding:"required"`
}

type BatchOperationResponse struct {
	Code    int                `json:"code"`
	Message string             `json:"message,omitempty"`
	Data    BatchOperationData `json:"data"`
}

type BatchOperationData struct {
	BatchID string             `json:"batch_id"`
	Results []models.BatchResult `json:"results"`
}

func (h *APIHandler) CreateResource(c *gin.Context) {
	ctx := buildContext(c)
	var req CreateResourceRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, CreateResourceResponse{
			Code:    400,
			Message: "Invalid request parameters: " + err.Error(),
		})
		return
	}

	resource := &models.Resource{
		ID:        "rsc_" + time.Now().Format("20060102150405"),
		Type:      req.Type,
		Status:    "provisioning",
		Config:    req.Config,
		Labels:    req.Labels,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	if err := h.db.DB.WithContext(ctx).Create(resource).Error; err != nil {
		logging.Error(ctx, "Failed to create resource", zap.Error(err))
		c.JSON(http.StatusInternalServerError, CreateResourceResponse{
			Code:    500,
			Message: "Failed to create resource",
		})
		return
	}

	task := &scheduler.Task{
		ID:       "task_" + resource.ID,
		Type:     req.Type,
		Status:   scheduler.TaskStatusPending,
		Resource: resource,
		Payload:  req.Config,
	}

	if err := h.scheduler.Submit(ctx, task); err != nil {
		logging.Error(ctx, "Failed to submit task", zap.Error(err))
	}

	resp := CreateResourceResponse{Code: 201}
	resp.Data.ID = resource.ID
	resp.Data.Status = resource.Status

	c.JSON(http.StatusCreated, resp)

	logging.Info(ctx, "Resource created",
		zap.String("resource_id", resource.ID),
		zap.String("type", resource.Type))
}

func (h *APIHandler) GetResourceStatus(c *gin.Context) {
	ctx := buildContext(c)
	id := c.Param("id")

	var resource models.Resource
	if err := h.db.DB.WithContext(ctx).Where("id = ?", id).First(&resource).Error; err != nil {
		c.JSON(http.StatusNotFound, ResourceStatusResponse{
			Code:    404,
			Message: "Resource not found",
		})
		return
	}

	var runInstance models.RunInstance
	progress := 0.0
	h.db.DB.WithContext(ctx).
		Where("entity_id = ?", id).
		Order("created_at DESC").
		First(&runInstance)

	if runInstance.RunID != "" {
		progress = runInstance.Progress
	}

	resp := ResourceStatusResponse{Code: 200}
	resp.Data.ID = resource.ID
	resp.Data.Type = resource.Type
	resp.Data.Status = resource.Status
	resp.Data.Progress = progress

	c.JSON(http.StatusOK, resp)
}

func (h *APIHandler) BatchOperation(c *gin.Context) {
	ctx := buildContext(c)
	var req BatchOperationRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, BatchOperationResponse{
			Code:    400,
			Message: "Invalid request parameters: " + err.Error(),
		})
		return
	}

	batchID := "batch_" + time.Now().Format("20060102150405")
	results := make([]models.BatchResult, 0, len(req.Operations))

	for _, op := range req.Operations {
		result := models.BatchResult{ID: op.ID, Success: true}

		switch op.Action {
		case "stop":
			err := h.scheduler.CancelTask(ctx, "task_"+op.ID)
			if err != nil {
				result.Success = false
				result.Error = err.Error()
			}
			if result.Success {
				h.db.DB.WithContext(ctx).Model(&models.Resource{}).
					Where("id = ?", op.ID).
					Update("status", "stopped")
			}
		case "pause":
			err := h.scheduler.PauseTask(ctx, "task_"+op.ID)
			if err != nil {
				result.Success = false
				result.Error = err.Error()
			}
			if result.Success {
				h.db.DB.WithContext(ctx).Model(&models.Resource{}).
					Where("id = ?", op.ID).
					Update("status", "paused")
			}
		case "resume":
			err := h.scheduler.ResumeTask(ctx, "task_"+op.ID)
			if err != nil {
				result.Success = false
				result.Error = err.Error()
			}
			if result.Success {
				h.db.DB.WithContext(ctx).Model(&models.Resource{}).
					Where("id = ?", op.ID).
					Update("status", "running")
			}
		case "delete":
			err := h.db.DB.WithContext(ctx).Where("id = ?", op.ID).Delete(&models.Resource{}).Error
			if err != nil {
				result.Success = false
				result.Error = err.Error()
			}
		default:
			result.Success = false
			result.Error = "Unknown action: " + op.Action
		}

		results = append(results, result)
	}

	batchOp := &models.BatchOperation{
		BatchID:    batchID,
		Operations: req.Operations,
		Results:    results,
		Status:     "completed",
		CreatedAt:  time.Now(),
		UpdatedAt:  time.Now(),
	}
	h.db.DB.WithContext(ctx).Create(batchOp)

	resp := BatchOperationResponse{Code: 200}
	resp.Data.BatchID = batchID
	resp.Data.Results = results

	c.JSON(http.StatusOK, resp)

	logging.Info(ctx, "Batch operation completed",
		zap.String("batch_id", batchID),
		zap.Int("operation_count", len(req.Operations)))
}

func (h *APIHandler) ListResources(c *gin.Context) {
	ctx := buildContext(c)

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	resourceType := c.Query("type")
	status := c.Query("status")

	offset := (page - 1) * pageSize

	query := h.db.DB.WithContext(ctx).Model(&models.Resource{})
	if resourceType != "" {
		query = query.Where("type = ?", resourceType)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	var total int64
	query.Count(&total)

	var resources []models.Resource
	err := query.Order("created_at DESC").
		Limit(pageSize).
		Offset(offset).
		Find(&resources).Error

	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "Failed to list resources",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"items":      resources,
			"total":      total,
			"page":       page,
			"page_size":  pageSize,
			"total_page": (total + int64(pageSize) - 1) / int64(pageSize),
		},
	})
}

func (h *APIHandler) UpdateLogLevel(c *gin.Context) {
	ctx := buildContext(c)

	var req struct {
		Level string `json:"level" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "Invalid request: " + err.Error(),
		})
		return
	}

	if err := logging.SetLevelString(req.Level); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "Invalid log level: " + err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "Log level updated to " + req.Level,
	})

	logging.Info(ctx, "Log level updated", zap.String("level", req.Level))
}

func (h *APIHandler) HealthCheck(c *gin.Context) {
	ctx := buildContext(c)

	status := "healthy"
	components := make(map[string]string)

	if err := h.db.HealthCheck(ctx); err != nil {
		status = "unhealthy"
		components["database"] = "unhealthy: " + err.Error()
	} else {
		components["database"] = "healthy"
	}

	schedStats := h.scheduler.GetStatsCompat()
	components["scheduler"] = "healthy"
	components["scheduler_pending"] = strconv.FormatInt(schedStats.PendingTasks, 10)
	components["scheduler_running"] = strconv.FormatInt(schedStats.RunningTasks, 10)

	c.JSON(http.StatusOK, gin.H{
		"code":       200,
		"status":     status,
		"components": components,
		"timestamp":  time.Now().UTC(),
	})
}

func buildContext(c *gin.Context) context.Context {
	traceID := c.GetHeader("X-Trace-ID")
	if traceID == "" {
		traceID = "trace_" + time.Now().Format("20060102150405")
	}
	ctx := context.WithValue(c.Request.Context(), "traceID", traceID)
	ctx = context.WithValue(ctx, "requestID", c.GetHeader("X-Request-ID"))
	return ctx
}
