package gpu

import (
	"strconv"

	"github.com/gin-gonic/gin"
	errors "session133/pkg/errors"
	"session133/pkg/utils"
)

type Handler struct {
	service *GPUSchedulerService
}

func NewHandler(service *GPUSchedulerService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	gpu := r.Group("/gpu")
	{
		tasks := gpu.Group("/tasks")
		{
			tasks.POST("", h.SubmitTask)
			tasks.GET("", h.ListTasks)
			tasks.GET("/:id", h.GetTask)
			tasks.DELETE("/:id", h.CancelTask)
			tasks.POST("/:id/preempt", h.PreemptTask)
			tasks.GET("/:id/events", h.GetTaskEvents)
		}

		gpus := gpu.Group("/gpus")
		{
			gpus.POST("", h.RegisterGPU)
			gpus.GET("", h.ListGPUs)
			gpus.GET("/:id", h.GetGPU)
		}
	}
}

func (h *Handler) SubmitTask(c *gin.Context) {
	var req CreateTaskRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	task, err := h.service.SubmitTask(c.Request.Context(), &req, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, task)
}

func (h *Handler) GetTask(c *gin.Context) {
	taskID := c.Param("id")
	task, err := h.service.GetTask(c.Request.Context(), taskID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, task)
}

func (h *Handler) ListTasks(c *gin.Context) {
	namespace := c.Query("namespace")
	status := TaskStatus(c.Query("status"))
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	tasks, total, err := h.service.ListTasks(c.Request.Context(), namespace, status, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, tasks, total, page, pageSize)
}

func (h *Handler) CancelTask(c *gin.Context) {
	taskID := c.Param("id")
	userID := c.GetString("user_id")

	if err := h.service.CancelTask(c.Request.Context(), taskID, userID); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"message": "任务取消成功"})
}

func (h *Handler) PreemptTask(c *gin.Context) {
	taskID := c.Param("id")

	if err := h.service.PreemptTask(c.Request.Context(), taskID); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"message": "任务抢占成功"})
}

func (h *Handler) GetTaskEvents(c *gin.Context) {
	taskID := c.Param("id")
	events, err := h.service.GetTaskEvents(c.Request.Context(), taskID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, events)
}

func (h *Handler) RegisterGPU(c *gin.Context) {
	var gpu GPU
	if err := c.ShouldBindJSON(&gpu); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	createdGPU, err := h.service.RegisterGPU(c.Request.Context(), &gpu)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, createdGPU)
}

func (h *Handler) GetGPU(c *gin.Context) {
	gpuID := c.Param("id")
	gpu, err := h.service.GetGPU(c.Request.Context(), gpuID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gpu)
}

func (h *Handler) ListGPUs(c *gin.Context) {
	nodeID := c.Query("node_id")
	status := GPUStatus(c.Query("status"))
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	gpus, total, err := h.service.ListGPUs(c.Request.Context(), nodeID, status, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, gpus, total, page, pageSize)
}
