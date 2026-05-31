package model

import (
	"github.com/gin-gonic/gin"
	"session133/pkg/utils"
)

type ConfigHandler struct {
	configManager *DynamicConfigManager
}

func NewConfigHandler(configManager *DynamicConfigManager) *ConfigHandler {
	return &ConfigHandler{
		configManager: configManager,
	}
}

func (h *ConfigHandler) RegisterRoutes(r *gin.RouterGroup) {
	config := r.Group("/config")
	{
		config.GET("", h.GetCurrentConfig)
		config.GET("/scenario", h.GetCurrentScenario)
		config.PUT("/scenario", h.SetScenario)
		config.GET("/all", h.GetAllConfigs)
		config.GET("/:scenario", h.GetConfigForScenario)
		config.PUT("/:scenario", h.UpdateConfig)
		config.PATCH("/:scenario", h.PatchConfig)
	}
}

func (h *ConfigHandler) GetCurrentConfig(c *gin.Context) {
	config := h.configManager.GetConfig()
	scenario := h.configManager.GetCurrentScenario()
	utils.Success(c, gin.H{
		"scenario": scenario,
		"config":   config,
	})
}

func (h *ConfigHandler) GetCurrentScenario(c *gin.Context) {
	scenario := h.configManager.GetCurrentScenario()
	utils.Success(c, gin.H{"scenario": scenario})
}

func (h *ConfigHandler) SetScenario(c *gin.Context) {
	var req struct {
		Scenario string `json:"scenario" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, err)
		return
	}

	h.configManager.SetScenario(ScenarioType(req.Scenario))
	utils.Success(c, gin.H{
		"message":  "场景切换成功",
		"scenario": h.configManager.GetCurrentScenario(),
	})
}

func (h *ConfigHandler) GetAllConfigs(c *gin.Context) {
	configs := h.configManager.ExportAllConfigs()
	utils.Success(c, configs)
}

func (h *ConfigHandler) GetConfigForScenario(c *gin.Context) {
	scenario := c.Param("scenario")
	config, err := h.configManager.GetConfigForScenario(ScenarioType(scenario))
	if err != nil {
		utils.Error(c, err)
		return
	}
	utils.Success(c, gin.H{
		"scenario": scenario,
		"config":   config,
	})
}

func (h *ConfigHandler) UpdateConfig(c *gin.Context) {
	scenario := c.Param("scenario")
	var config ModelConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		utils.Error(c, err)
		return
	}

	if err := h.configManager.UpdateConfig(ScenarioType(scenario), config); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{
		"message": "配置更新成功（热更新已生效）",
	})
}

func (h *ConfigHandler) PatchConfig(c *gin.Context) {
	scenario := c.Param("scenario")
	var patches map[string]interface{}
	if err := c.ShouldBindJSON(&patches); err != nil {
		utils.Error(c, err)
		return
	}

	if err := h.configManager.PatchConfig(ScenarioType(scenario), patches); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{
		"message": "配置部分更新成功（热更新已生效）",
		"applied_patches": patches,
	})
}
