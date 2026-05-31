package prompt

import (
	"strconv"

	"github.com/gin-gonic/gin"
	errors "session133/pkg/errors"
	"session133/pkg/utils"
)

type Handler struct {
	service *PromptService
}

func NewHandler(service *PromptService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	prompts := r.Group("/prompts")
	{
		prompts.POST("", h.CreatePrompt)
		prompts.GET("", h.ListPrompts)
		prompts.GET("/:id", h.GetPrompt)
		prompts.PUT("/:id/status", h.UpdatePromptStatus)
		prompts.POST("/:id/versions", h.CreateNewVersion)
	}

	experiments := r.Group("/experiments")
	{
		experiments.POST("", h.CreateExperiment)
		experiments.GET("", h.ListExperiments)
		experiments.GET("/:id", h.GetExperiment)
		experiments.PUT("/:id/status", h.UpdateExperimentStatus)
		experiments.GET("/:id/results", h.GetExperimentResults)
		experiments.POST("/:id/record", h.RecordResult)
	}
}

func (h *Handler) CreatePrompt(c *gin.Context) {
	var req CreatePromptRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	prompt, err := h.service.CreatePrompt(c.Request.Context(), &req, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, prompt)
}

func (h *Handler) GetPrompt(c *gin.Context) {
	promptID := c.Param("id")
	prompt, err := h.service.GetPrompt(c.Request.Context(), promptID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, prompt)
}

func (h *Handler) ListPrompts(c *gin.Context) {
	namespace := c.Query("namespace")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	prompts, total, err := h.service.ListPrompts(c.Request.Context(), namespace, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, prompts, total, page, pageSize)
}

func (h *Handler) UpdatePromptStatus(c *gin.Context) {
	promptID := c.Param("id")
	var req struct {
		Status PromptStatus `json:"status" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	prompt, err := h.service.UpdatePromptStatus(c.Request.Context(), promptID, req.Status)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, prompt)
}

func (h *Handler) CreateNewVersion(c *gin.Context) {
	promptID := c.Param("id")
	var req struct {
		Content string `json:"content" binding:"required"`
		Version string `json:"version" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	prompt, err := h.service.CreateNewVersion(c.Request.Context(), promptID, req.Content, req.Version, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, prompt)
}

func (h *Handler) CreateExperiment(c *gin.Context) {
	var req CreateExperimentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	experiment, err := h.service.CreateExperiment(c.Request.Context(), &req, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, experiment)
}

func (h *Handler) GetExperiment(c *gin.Context) {
	experimentID := c.Param("id")
	experiment, err := h.service.GetExperiment(c.Request.Context(), experimentID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, experiment)
}

func (h *Handler) ListExperiments(c *gin.Context) {
	namespace := c.Query("namespace")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	experiments, total, err := h.service.ListExperiments(c.Request.Context(), namespace, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, experiments, total, page, pageSize)
}

func (h *Handler) UpdateExperimentStatus(c *gin.Context) {
	experimentID := c.Param("id")
	var req UpdateExperimentStatusRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	experiment, err := h.service.UpdateExperimentStatus(c.Request.Context(), experimentID, req.Status)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, experiment)
}

func (h *Handler) GetExperimentResults(c *gin.Context) {
	experimentID := c.Param("id")
	results, err := h.service.GetExperimentResults(c.Request.Context(), experimentID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, results)
}

func (h *Handler) RecordResult(c *gin.Context) {
	experimentID := c.Param("id")
	var req struct {
		PromptID string                 `json:"prompt_id" binding:"required"`
		Success  bool                   `json:"success"`
		Latency  float64                `json:"latency"`
		Metrics  map[string]float64     `json:"metrics"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	err := h.service.RecordExperimentResult(c.Request.Context(), experimentID, req.PromptID, req.Success, req.Latency, req.Metrics)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"message": "结果记录成功"})
}
