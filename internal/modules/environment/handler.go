package environment

import (
	"depguard/internal/common/response"
	"strconv"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	service *EnvironmentService
}

func NewHandler() *Handler {
	return &Handler{
		service: NewEnvironmentService(),
	}
}

type CreateEnvRequest struct {
	Name         string                 `json:"name" binding:"required,max=128"`
	Description  string                 `json:"description"`
	Type         string                 `json:"type" binding:"required"`
	OwnerID      string                 `json:"owner_id"`
	ProjectID    string                 `json:"project_id"`
	Config       map[string]interface{} `json:"config"`
	DurationHours int                  `json:"duration_hours" binding:"min=1,max=720"`
}

type ApproveRequest struct {
	ApproverID string `json:"approver_id" binding:"required"`
}

type ExtendRequest struct {
	Hours int `json:"hours" binding:"required,min=1,max=168"`
}

type CreateRequestRequest struct {
	ProjectID string                 `json:"project_id" binding:"required"`
	EnvType   string                 `json:"env_type" binding:"required"`
	Reason    string                 `json:"reason" binding:"required"`
	Config    map[string]interface{} `json:"config"`
}

func (h *Handler) CreateEnvironment(c *gin.Context) {
	var req CreateEnvRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	env, err := h.service.CreateEnvironment(req.Name, req.Description, req.Type, req.OwnerID, req.ProjectID, req.Config, req.DurationHours)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.SuccessCreated(c, env)
}

func (h *Handler) GetEnvironment(c *gin.Context) {
	envID := c.Param("env_id")
	env, err := h.service.GetEnvironment(envID)
	if err != nil {
		response.NotFound(c, "Environment not found")
		return
	}
	response.Success(c, env)
}

func (h *Handler) ListEnvironments(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	ownerID := c.Query("owner_id")
	status := c.Query("status")
	envType := c.Query("type")

	envs, total, err := h.service.ListEnvironments(page, pageSize, ownerID, status, envType)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"items": envs,
		"total": total,
		"page":  page,
		"size":  pageSize,
	})
}

func (h *Handler) StopEnvironment(c *gin.Context) {
	envID := c.Param("env_id")
	err := h.service.StopEnvironment(envID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, gin.H{"message": "Environment stopped"})
}

func (h *Handler) StartEnvironment(c *gin.Context) {
	envID := c.Param("env_id")
	err := h.service.StartEnvironment(envID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, gin.H{"message": "Environment started"})
}

func (h *Handler) DeleteEnvironment(c *gin.Context) {
	envID := c.Param("env_id")
	err := h.service.DeleteEnvironment(envID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, gin.H{"message": "Environment deleted"})
}

func (h *Handler) ExtendEnvironment(c *gin.Context) {
	envID := c.Param("env_id")
	var req ExtendRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	err := h.service.ExtendEnvironment(envID, req.Hours)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, gin.H{"message": "Environment extended"})
}

func (h *Handler) GetUsageStats(c *gin.Context) {
	envID := c.Param("env_id")
	startDate := c.Query("start_date")
	endDate := c.Query("end_date")

	stats, err := h.service.GetUsageStats(envID, startDate, endDate)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, stats)
}

func (h *Handler) GetAggregatedStats(c *gin.Context) {
	envID := c.Param("env_id")
	stats, err := h.service.GetAggregatedStats(envID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, stats)
}

func (h *Handler) CreateRequest(c *gin.Context) {
	var req CreateRequestRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	requesterID := c.GetHeader("X-User-ID")
	request, err := h.service.CreateRequest(requesterID, req.ProjectID, req.EnvType, req.Reason, req.Config)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.SuccessCreated(c, request)
}

func (h *Handler) ApproveRequest(c *gin.Context) {
	requestID := c.Param("request_id")
	var req ApproveRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	env, err := h.service.ApproveRequest(requestID, req.ApproverID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, env)
}

func (h *Handler) RejectRequest(c *gin.Context) {
	requestID := c.Param("request_id")
	var req ApproveRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	err := h.service.RejectRequest(requestID, req.ApproverID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, gin.H{"message": "Request rejected"})
}

func (h *Handler) ListRequests(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	requesterID := c.Query("requester_id")
	status := c.Query("status")

	requests, total, err := h.service.ListRequests(page, pageSize, requesterID, status)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"items": requests,
		"total": total,
		"page":  page,
		"size":  pageSize,
	})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	envs := r.Group("/environments")
	{
		envs.POST("", h.CreateEnvironment)
		envs.GET("", h.ListEnvironments)
		envs.GET("/:env_id", h.GetEnvironment)
		envs.POST("/:env_id/stop", h.StopEnvironment)
		envs.POST("/:env_id/start", h.StartEnvironment)
		envs.DELETE("/:env_id", h.DeleteEnvironment)
		envs.POST("/:env_id/extend", h.ExtendEnvironment)
		envs.GET("/:env_id/stats", h.GetUsageStats)
		envs.GET("/:env_id/stats/aggregated", h.GetAggregatedStats)
	}

	requests := r.Group("/requests")
	{
		requests.POST("", h.CreateRequest)
		requests.GET("", h.ListRequests)
		requests.POST("/:request_id/approve", h.ApproveRequest)
		requests.POST("/:request_id/reject", h.RejectRequest)
	}

	configs := r.Group("/configs")
	{
		configs.GET("", h.ListConfigs)
		configs.GET("/:config_key", h.GetConfig)
		configs.PUT("/:config_key", h.SetConfig)
		configs.DELETE("/:config_key", h.DeleteConfig)
		configs.GET("/change-logs", h.GetConfigChangeLogs)
		configs.POST("/reload", h.ReloadConfigs)
	}
}

func (h *Handler) ListConfigs(c *gin.Context) {
	configs, err := h.service.ListConfigs()
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, configs)
}

func (h *Handler) GetConfig(c *gin.Context) {
	configKey := c.Param("config_key")
	value := h.service.GetConfig(configKey)
	if value == nil {
		response.NotFound(c, "config not found")
		return
	}
	response.Success(c, gin.H{
		"config_key":   configKey,
		"config_value": value,
	})
}

type SetConfigRequest struct {
	ConfigValue map[string]interface{} `json:"config_value" binding:"required"`
	Description string                 `json:"description"`
}

func (h *Handler) SetConfig(c *gin.Context) {
	configKey := c.Param("config_key")
	var req SetConfigRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "invalid request body")
		return
	}

	userID := c.GetHeader("X-User-ID")
	if userID == "" {
		userID = "system"
	}

	config, err := h.service.SetConfig(configKey, req.ConfigValue, userID, req.Description)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, config)
}

func (h *Handler) DeleteConfig(c *gin.Context) {
	configKey := c.Param("config_key")
	userID := c.GetHeader("X-User-ID")
	if userID == "" {
		userID = "system"
	}

	err := h.service.DeleteConfig(configKey, userID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, nil)
}

func (h *Handler) GetConfigChangeLogs(c *gin.Context) {
	configKey := c.Query("config_key")
	limit := 20
	if l, ok := c.GetQuery("limit"); ok {
		if n, err := strconv.Atoi(l); err == nil && n > 0 && n <= 100 {
			limit = n
		}
	}

	logs, err := h.service.GetConfigChangeLogs(configKey, limit)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, logs)
}

func (h *Handler) ReloadConfigs(c *gin.Context) {
	count, err := h.service.ReloadConfigs()
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, gin.H{
		"message":        "configs reloaded",
		"configs_loaded": count,
	})
}
