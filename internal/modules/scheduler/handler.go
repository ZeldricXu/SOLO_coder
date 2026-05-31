package scheduler

import (
	"net/http"
	"strconv"

	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/common/models"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"
)

type Handler struct {
	service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{service: service}
}

func (h *Handler) CreateTask(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	var task models.ScheduledTask
	if err := c.ShouldBindJSON(&task); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	result, err := h.service.CreateTask(ctx, &task)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"code": 201,
		"data": result,
	})
}

func (h *Handler) GetTask(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	id := c.Param("id")
	task, err := h.service.GetTask(ctx, id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{
			"code":  404,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": task,
	})
}

func (h *Handler) ListTasks(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	status := c.Query("status")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	tasks, total, err := h.service.ListTasks(ctx, status, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"items": tasks,
			"total": total,
			"limit": limit,
			"offset": offset,
		},
	})
}

func (h *Handler) UpdateTask(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	id := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	result, err := h.service.UpdateTask(ctx, id, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": result,
	})
}

func (h *Handler) DeleteTask(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	id := c.Param("id")
	if err := h.service.DeleteTask(ctx, id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "deleted successfully",
	})
}

func (h *Handler) ExecuteTask(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	id := c.Param("id")
	execution, err := h.service.ExecuteTask(ctx, id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": execution,
	})
}

func (h *Handler) GetExecutions(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	taskID := c.Query("task_id")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	executions, err := h.service.GetTaskExecutions(ctx, taskID, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": executions,
	})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	scheduler := r.Group("/scheduler")
	{
		scheduler.POST("/tasks", h.CreateTask)
		scheduler.GET("/tasks", h.ListTasks)
		scheduler.GET("/tasks/:id", h.GetTask)
		scheduler.PUT("/tasks/:id", h.UpdateTask)
		scheduler.DELETE("/tasks/:id", h.DeleteTask)
		scheduler.POST("/tasks/:id/execute", h.ExecuteTask)
		scheduler.GET("/executions", h.GetExecutions)
	}
}
