package config

import (
	"strconv"

	"github.com/gin-gonic/gin"
	errors "session133/pkg/errors"
	"session133/pkg/utils"
)

type Handler struct {
	service *ConfigService
}

func NewHandler(service *ConfigService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	configs := r.Group("/configs")
	{
		configs.POST("", h.CreateConfig)
		configs.GET("", h.ListConfigs)
		configs.GET("/:id", h.GetConfig)
		configs.PUT("/:id", h.UpdateConfig)
		configs.DELETE("/:id", h.DeleteConfig)
		configs.POST("/:id/publish", h.PublishConfig)
		configs.POST("/:id/rollback", h.RollbackConfig)
		configs.GET("/:id/history", h.ListVersions)
		configs.GET("/:id/rollback-history", h.GetRollbackHistory)
		configs.GET("/diff", h.DiffConfigs)
		configs.GET("/published", h.GetPublishedConfig)
	}
}

func (h *Handler) CreateConfig(c *gin.Context) {
	var req CreateConfigRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	config, err := h.service.CreateConfig(c.Request.Context(), &req, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, config)
}

func (h *Handler) GetConfig(c *gin.Context) {
	configID := c.Param("id")
	config, err := h.service.GetConfig(c.Request.Context(), configID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, config)
}

func (h *Handler) GetPublishedConfig(c *gin.Context) {
	configKey := c.Query("config_key")
	namespace := c.Query("namespace")

	if configKey == "" || namespace == "" {
		utils.Error(c, errors.InvalidParams("config_key 和 namespace 是必填参数"))
		return
	}

	config, err := h.service.GetPublishedConfig(c.Request.Context(), configKey, namespace)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, config)
}

func (h *Handler) ListConfigs(c *gin.Context) {
	namespace := c.Query("namespace")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	configs, total, err := h.service.ListConfigs(c.Request.Context(), namespace, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, configs, total, page, pageSize)
}

func (h *Handler) ListVersions(c *gin.Context) {
	configID := c.Param("id")
	config, err := h.service.GetConfig(c.Request.Context(), configID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	versions, total, err := h.service.ListConfigVersions(c.Request.Context(), config.ConfigKey, config.Namespace, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, versions, total, page, pageSize)
}

func (h *Handler) UpdateConfig(c *gin.Context) {
	configID := c.Param("id")
	var req UpdateConfigRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	config, err := h.service.UpdateConfig(c.Request.Context(), configID, &req, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, config)
}

func (h *Handler) PublishConfig(c *gin.Context) {
	configID := c.Param("id")
	var req PublishConfigRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	config, err := h.service.PublishConfig(c.Request.Context(), configID, &req)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, config)
}

func (h *Handler) RollbackConfig(c *gin.Context) {
	configID := c.Param("id")
	var req RollbackConfigRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	config, err := h.service.RollbackConfig(c.Request.Context(), configID, &req, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, config)
}

func (h *Handler) DeleteConfig(c *gin.Context) {
	configID := c.Param("id")
	if err := h.service.DeleteConfig(c.Request.Context(), configID); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"message": "配置删除成功"})
}

func (h *Handler) DiffConfigs(c *gin.Context) {
	configKey := c.Query("config_key")
	namespace := c.Query("namespace")
	version1, _ := strconv.Atoi(c.Query("version1"))
	version2, _ := strconv.Atoi(c.Query("version2"))

	if configKey == "" || namespace == "" || version1 == 0 || version2 == 0 {
		utils.Error(c, errors.InvalidParams("config_key, namespace, version1, version2 都是必填参数"))
		return
	}

	diff, err := h.service.DiffConfigs(c.Request.Context(), configKey, namespace, version1, version2)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, diff)
}

func (h *Handler) GetRollbackHistory(c *gin.Context) {
	configID := c.Param("id")
	history, err := h.service.GetRollbackHistory(c.Request.Context(), configID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, history)
}
