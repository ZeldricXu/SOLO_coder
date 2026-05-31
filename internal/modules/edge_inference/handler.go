package edge_inference

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"edgescheduler/pkg/utils"
)

type InferenceHandler struct {
	service InferenceService
}

func NewInferenceHandler(service InferenceService) *InferenceHandler {
	return &InferenceHandler{
		service: service,
	}
}

func (h *InferenceHandler) RegisterRoutes(router *gin.RouterGroup) {
	inference := router.Group("/inference")
	{
		models := inference.Group("/models")
		{
			models.POST("", h.RegisterModel)
			models.GET("", h.ListModels)
			models.GET("/:model_id", h.GetModel)
			models.POST("/deploy", h.DeployModel)
		}

		tasks := inference.Group("/tasks")
		{
			tasks.POST("", h.CreateTask)
			tasks.GET("", h.ListTasks)
			tasks.GET("/:task_id", h.GetTask)
			tasks.GET("/:task_id/status", h.GetTaskStatus)
		}
	}
}

func (h *InferenceHandler) RegisterModel(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var model AIModel
	if err := c.ShouldBindJSON(&model); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	result, err := h.service.RegisterModel(ctx, &model)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, result)
}

func (h *InferenceHandler) ListModels(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	models, total, err := h.service.ListModels(ctx, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"models": models,
		"total":  total,
		"offset": offset,
		"limit":  limit,
	})
}

func (h *InferenceHandler) GetModel(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	modelID := c.Param("model_id")

	model, err := h.service.GetModel(ctx, modelID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, model)
}

func (h *InferenceHandler) DeployModel(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req ModelDeployRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	deployment, err := h.service.DeployModel(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, deployment)
}

func (h *InferenceHandler) CreateTask(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req InferenceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	task, err := h.service.CreateInferenceTask(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, gin.H{
		"task_id": task.TaskID,
		"status":  task.Status,
	})
}

func (h *InferenceHandler) ListTasks(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	filters := map[string]interface{}{
		"status":    c.Query("status"),
		"device_id": c.Query("device_id"),
		"model_id":  c.Query("model_id"),
	}

	tasks, total, err := h.service.ListTasks(ctx, filters, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"tasks":  tasks,
		"total":  total,
		"offset": offset,
		"limit":  limit,
	})
}

func (h *InferenceHandler) GetTask(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	taskID := c.Param("task_id")

	task, err := h.service.GetTask(ctx, taskID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, task)
}

func (h *InferenceHandler) GetTaskStatus(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	taskID := c.Param("task_id")

	task, err := h.service.GetTask(ctx, taskID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"task_id":  task.TaskID,
		"status":   task.Status,
		"progress": map[TaskStatus]float64{
			TaskStatusPending:   0,
			TaskStatusRunning:   0.5,
			TaskStatusCompleted: 1.0,
			TaskStatusFailed:    1.0,
		}[task.Status],
	})
}
