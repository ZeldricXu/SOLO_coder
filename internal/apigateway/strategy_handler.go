package apigateway

import (
	"time"

	"github.com/gin-gonic/gin"
	"session133/pkg/utils"
)

type StrategyHandler struct {
	adaptiveLimiter *AdaptiveRateLimiter
}

func NewStrategyHandler(adaptiveLimiter *AdaptiveRateLimiter) *StrategyHandler {
	return &StrategyHandler{
		adaptiveLimiter: adaptiveLimiter,
	}
}

func (h *StrategyHandler) RegisterRoutes(r *gin.RouterGroup) {
	strategy := r.Group("/ratelimit/strategy")
	{
		strategy.GET("", h.GetCurrentStrategy)
		strategy.GET("/available", h.ListAvailableStrategies)
		strategy.PUT("", h.SetStrategy)
		strategy.GET("/config", h.GetConfig)
		strategy.PUT("/config", h.UpdateConfig)
	}
}

func (h *StrategyHandler) GetCurrentStrategy(c *gin.Context) {
	strategy := h.adaptiveLimiter.GetCurrentStrategy()
	utils.Success(c, gin.H{
		"current_strategy": strategy,
	})
}

func (h *StrategyHandler) ListAvailableStrategies(c *gin.Context) {
	strategies := h.adaptiveLimiter.ListAvailableStrategies()
	utils.Success(c, gin.H{
		"available_strategies": strategies,
	})
}

func (h *StrategyHandler) SetStrategy(c *gin.Context) {
	var req struct {
		Strategy string `json:"strategy" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, err)
		return
	}

	if err := h.adaptiveLimiter.SetStrategy(req.Strategy); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{
		"message":          "限流策略切换成功（运行时生效）",
		"current_strategy": h.adaptiveLimiter.GetCurrentStrategy(),
	})
}

func (h *StrategyHandler) GetConfig(c *gin.Context) {
	config := h.adaptiveLimiter.GetConfig()
	utils.Success(c, gin.H{
		"strategy": config.Strategy,
		"limit":    config.Limit,
		"window":   config.Window,
		"burst":    config.Burst,
	})
}

func (h *StrategyHandler) UpdateConfig(c *gin.Context) {
	var req struct {
		Strategy string  `json:"strategy"`
		Limit    int     `json:"limit"`
		WindowMs int64   `json:"window_ms"`
		Burst    int     `json:"burst"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, err)
		return
	}

	currentConfig := h.adaptiveLimiter.GetConfig()
	updatedConfig := &RateLimitConfig{
		Strategy:    currentConfig.Strategy,
		Limit:       currentConfig.Limit,
		Window:      currentConfig.Window,
		Burst:       currentConfig.Burst,
		HeaderKey:   currentConfig.HeaderKey,
		RedisPrefix: currentConfig.RedisPrefix,
	}

	if req.Strategy != "" {
		updatedConfig.Strategy = RateLimitStrategy(req.Strategy)
	}
	if req.Limit > 0 {
		updatedConfig.Limit = req.Limit
	}
	if req.WindowMs > 0 {
		updatedConfig.Window = time.Duration(req.WindowMs) * time.Millisecond
	}
	if req.Burst > 0 {
		updatedConfig.Burst = req.Burst
	}

	h.adaptiveLimiter.UpdateConfig(updatedConfig)

	utils.Success(c, gin.H{
		"message": "限流配置更新成功（热更新已生效）",
		"config": gin.H{
			"strategy": updatedConfig.Strategy,
			"limit":    updatedConfig.Limit,
			"window":   updatedConfig.Window,
			"burst":    updatedConfig.Burst,
		},
	})
}
