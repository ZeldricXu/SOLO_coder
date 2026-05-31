package handler

import (
	"net/http"
	"strconv"

	"projectservice/internal/model"
	"projectservice/internal/service"

	"github.com/gin-gonic/gin"
)

type EnvironmentHandler struct {
	*Handler
	service *service.EnvironmentService
}

func NewEnvironmentHandler(h *Handler, svc *service.EnvironmentService) *EnvironmentHandler {
	return &EnvironmentHandler{
		Handler: h,
		service: svc,
	}
}

func (h *EnvironmentHandler) CreateEnvironment(c *gin.Context) {
	var req model.CreateEnvironmentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	env, err := h.service.CreateEnvironment(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "CREATE_ERROR", "Failed to create environment", err.Error())
		return
	}

	h.CreatedResponse(c, env)
}

func (h *EnvironmentHandler) GetEnvironment(c *gin.Context) {
	envID := c.Param("env_id")

	env, err := h.service.GetEnvironment(c.Request.Context(), envID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Environment not found", err.Error())
		return
	}

	h.SuccessResponse(c, env)
}

func (h *EnvironmentHandler) GetEnvironmentStatus(c *gin.Context) {
	envID := c.Param("env_id")

	status, err := h.service.GetEnvironmentStatus(c.Request.Context(), envID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Environment not found", err.Error())
		return
	}

	h.SuccessResponse(c, status)
}

func (h *EnvironmentHandler) ListEnvironments(c *gin.Context) {
	owner := c.Query("owner")
	projectID := c.Query("project_id")
	status := c.Query("status")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	envs, total, err := h.service.ListEnvironments(c.Request.Context(), owner, projectID, status, page, pageSize)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "QUERY_ERROR", "Failed to list environments", err.Error())
		return
	}

	h.PaginatedResponse(c, envs, page, pageSize, total)
}

func (h *EnvironmentHandler) UpdateEnvironmentStatus(c *gin.Context) {
	envID := c.Param("env_id")

	var req struct {
		Status string `json:"status" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	if err := h.service.UpdateEnvironmentStatus(c.Request.Context(), envID, req.Status); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Environment not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Status updated successfully"})
}

func (h *EnvironmentHandler) DeleteEnvironment(c *gin.Context) {
	envID := c.Param("env_id")

	if err := h.service.DeleteEnvironment(c.Request.Context(), envID); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Environment not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Environment deleted successfully"})
}

func (h *EnvironmentHandler) ReclaimExpiredEnvironments(c *gin.Context) {
	reclaimed, err := h.service.ReclaimExpiredEnvironments(c.Request.Context())
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "RECLAIM_ERROR", "Failed to reclaim environments", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{
		"message":     "Reclaim completed",
		"reclaimed":   len(reclaimed),
		"reclaim_ids": reclaimed,
	})
}

func (h *EnvironmentHandler) GetUsageStatistics(c *gin.Context) {
	envID := c.Query("environment_id")
	resourceType := c.Query("resource_type")
	startTime := c.Query("start_time")
	endTime := c.Query("end_time")

	req := &model.UsageStatisticsRequest{
		EnvironmentID: envID,
		ResourceType:  resourceType,
		StartTime:     startTime,
		EndTime:       endTime,
	}

	stats, err := h.service.GetUsageStatistics(c.Request.Context(), req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "STAT_ERROR", "Failed to get usage statistics", err.Error())
		return
	}

	h.SuccessResponse(c, stats)
}

func (h *EnvironmentHandler) ExtendTTL(c *gin.Context) {
	envID := c.Param("env_id")

	var req struct {
		Hours int `json:"hours" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	if err := h.service.ExtendTTL(c.Request.Context(), envID, req.Hours); err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "TTL_ERROR", "Failed to extend TTL", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "TTL extended successfully"})
}

// ===== 监控增强接口

func (h *EnvironmentHandler) GetEnvironmentHealth(c *gin.Context) {
	envID := c.Param("env_id")

	health, err := h.service.GetEnvironmentHealth(c.Request.Context(), envID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Environment not found", err.Error())
		return
	}

	h.SuccessResponse(c, health)
}

func (h *EnvironmentHandler) GetEnvironmentTiming(c *gin.Context) {
	envID := c.Param("env_id")
	operation := c.Query("operation")

	if operation == "" {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "operation query param is required", "")
		return
	}

	timing, err := h.service.GetEnvironmentTiming(envID, operation)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "No timing data", err.Error())
		return
	}

	h.SuccessResponse(c, timing)
}

func (h *EnvironmentHandler) GetResourceUsageSummary(c *gin.Context) {
	envID := c.Query("environment_id")
	resourceType := c.Query("resource_type")

	if envID == "" {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "environment_id query param is required", "")
		return
	}

	summary, err := h.service.GetResourceUsageSummary(c.Request.Context(), envID, resourceType)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "No usage records found", err.Error())
		return
	}

	h.SuccessResponse(c, summary)
}

func (h *EnvironmentHandler) GetEnvironmentStats(c *gin.Context) {
	stats, err := h.service.GetEnvironmentStats(c.Request.Context())
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "STATS_ERROR", "Failed to get environment stats", err.Error())
		return
	}

	h.SuccessResponse(c, stats)
}

func (h *EnvironmentHandler) GetLifecycleEvents(c *gin.Context) {
	envID := c.Query("env_id")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))
	if limit <= 0 {
		limit = 50
	}

	events := h.service.GetLifecycleEvents(envID, limit)
	h.SuccessResponse(c, gin.H{
		"events": events,
		"count":  len(events),
	})
}
