package inference

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	scheduler *Scheduler
}

func NewHandler(scheduler *Scheduler) *Handler {
	return &Handler{scheduler: scheduler}
}

type SubmitTaskRequest struct {
	ModelID     string                 `json:"model_id" binding:"required"`
	InputData   map[string]interface{} `json:"input_data" binding:"required"`
	Namespace   string                 `json:"namespace"`
	Priority    int                    `json:"priority"`
	CallbackURL string                 `json:"callback_url"`
}

type SubmitTaskResponse struct {
	TaskID string `json:"task_id"`
	Status string `json:"status"`
}

func (h *Handler) SubmitTask(c *gin.Context) {
	var req SubmitTaskRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	taskID, err := h.scheduler.SubmitTask(InferenceTask{
		ModelID:     req.ModelID,
		InputData:   req.InputData,
		Namespace:   req.Namespace,
		Priority:    req.Priority,
		CallbackURL: req.CallbackURL,
	})
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusAccepted, SubmitTaskResponse{
		TaskID: taskID,
		Status: "accepted",
	})
}

func (h *Handler) GetTaskStatus(c *gin.Context) {
	taskID := c.Param("id")
	task, exists := h.scheduler.GetTaskStatus(taskID)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "task not found"})
		return
	}
	c.JSON(http.StatusOK, task)
}

type UpdateConfigRequest struct {
	Strategy     string `json:"strategy" binding:"required,oneof=batch realtime low_power"`
	BatchSize    int    `json:"batch_size" binding:"min=1,max=100"`
	TimeoutMs    int    `json:"timeout_ms" binding:"min=100,max=60000"`
	MaxRetries   int    `json:"max_retries" binding:"min=0,max=10"`
	ModelVersion string `json:"model_version"`
	GPUEnabled   bool   `json:"gpu_enabled"`
}

func (h *Handler) UpdateConfig(c *gin.Context) {
	namespace := c.Param("namespace")
	var req UpdateConfigRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	config := InferenceConfig{
		Strategy:     InferenceStrategy(req.Strategy),
		BatchSize:    req.BatchSize,
		TimeoutMs:    req.TimeoutMs,
		MaxRetries:   req.MaxRetries,
		ModelVersion: req.ModelVersion,
		GPUEnabled:   req.GPUEnabled,
	}
	h.scheduler.configManager.UpdateConfig(namespace, config)
	c.JSON(http.StatusOK, gin.H{
		"message":   "config updated successfully",
		"namespace": namespace,
		"config":    config,
	})
}

func (h *Handler) GetConfig(c *gin.Context) {
	namespace := c.Param("namespace")
	config := h.scheduler.configManager.GetConfig(namespace)
	c.JSON(http.StatusOK, config)
}

func (h *Handler) ListConfigs(c *gin.Context) {
	configs := h.scheduler.configManager.ListConfigs()
	c.JSON(http.StatusOK, configs)
}
