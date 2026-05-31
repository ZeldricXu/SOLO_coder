package api

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"session189/internal/modules/gateway"
	"session189/internal/modules/profiling"
	"session189/internal/modules/scheduler"
	appconfig "session189/pkg/config"
)

type EnhancedHandler struct {
	dynamicProfiler *profiling.DynamicProfiler
	strategyRegistry *gateway.StrategyRegistry
	asyncScheduler  *scheduler.AsyncScheduler
	configManager   appconfig.ConfigManager
}

func NewEnhancedHandler(
	dynamicProfiler *profiling.DynamicProfiler,
	strategyRegistry *gateway.StrategyRegistry,
	asyncScheduler *scheduler.AsyncScheduler,
	configManager appconfig.ConfigManager,
) *EnhancedHandler {
	return &EnhancedHandler{
		dynamicProfiler: dynamicProfiler,
		strategyRegistry: strategyRegistry,
		asyncScheduler:  asyncScheduler,
		configManager:   configManager,
	}
}

func (h *EnhancedHandler) RegisterEnhancedRoutes(r *gin.RouterGroup) {
	config := r.Group("/config")
	{
		config.GET("", h.ListConfig)
		config.POST("", h.SetConfig)
		config.DELETE("/:key", h.DeleteConfig)
	}

	profiling := r.Group("/profiling")
	{
		profiling.GET("/config", h.GetProfilingConfig)
		profiling.POST("/strategy", h.SetProfilingStrategy)
		profiling.POST("/cpu/dynamic", h.StartCPUProfileDynamic)
		profiling.POST("/memory/dynamic", h.StartMemoryProfileDynamic)
	}

	gateway := r.Group("/gateway")
	{
		gateway.GET("/strategies/auth", h.ListAuthStrategies)
		gateway.POST("/strategies/auth/:name", h.SetAuthStrategy)
		gateway.GET("/strategies/ratelimit", h.ListRateLimitStrategies)
		gateway.POST("/strategies/ratelimit/:name", h.SetRateLimitStrategy)
	}

	scheduler := r.Group("/scheduler")
	{
		scheduler.POST("/jobs/:id/trigger-async", h.TriggerJobAsync)
		scheduler.GET("/queue", h.GetQueueStatus)
	}
}

type ConfigItem struct {
	Key   string      `json:"key"`
	Value interface{} `json:"value"`
}

func (h *EnhancedHandler) ListConfig(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"message": "Config listing endpoint",
		"items":   []ConfigItem{},
	})
}

func (h *EnhancedHandler) SetConfig(c *gin.Context) {
	var req struct {
		Key   string      `json:"key" binding:"required"`
		Value interface{} `json:"value" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.configManager.Set(c.Request.Context(), req.Key, req.Value); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Config updated", "key": req.Key})
}

func (h *EnhancedHandler) DeleteConfig(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"message": "Config deletion not supported in memory backend"})
}

func (h *EnhancedHandler) GetProfilingConfig(c *gin.Context) {
	cfg := h.dynamicProfiler.Config()
	c.JSON(http.StatusOK, gin.H{
		"cpu_duration_seconds": cfg.CPUDuration.Seconds(),
		"memory_duration_seconds": cfg.MemoryDuration.Seconds(),
		"memory_rate": cfg.MemoryRate,
		"auto_enabled": cfg.AutoEnabled,
		"auto_interval_minutes": cfg.AutoInterval.Minutes(),
		"max_samples": cfg.MaxSamples,
		"retention_hours": cfg.RetentionHours,
		"current_strategy": cfg.CurrentStrategy,
	})
}

func (h *EnhancedHandler) SetProfilingStrategy(c *gin.Context) {
	var req struct {
		Strategy string `json:"strategy" binding:"required,oneof=default debug production low_impact"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	h.dynamicProfiler.ApplyStrategy(profiling.ProfileStrategy(req.Strategy))
	c.JSON(http.StatusOK, gin.H{
		"message":  "Strategy applied",
		"strategy": req.Strategy,
		"config":   h.dynamicProfiler.Config(),
	})
}

func (h *EnhancedHandler) StartCPUProfileDynamic(c *gin.Context) {
	sample, err := h.dynamicProfiler.StartCPUProfileWithConfig()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, sample)
}

func (h *EnhancedHandler) StartMemoryProfileDynamic(c *gin.Context) {
	sample, err := h.dynamicProfiler.StartMemoryProfileWithConfig()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, sample)
}

func (h *EnhancedHandler) ListAuthStrategies(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"strategies": h.strategyRegistry.ListAuthStrategies(),
		"current":    h.strategyRegistry.GetAuthStrategy().Name(),
	})
}

func (h *EnhancedHandler) SetAuthStrategy(c *gin.Context) {
	name := c.Param("name")
	if err := h.strategyRegistry.SetAuthStrategy(name); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"message":  "Auth strategy updated",
		"strategy": name,
	})
}

func (h *EnhancedHandler) ListRateLimitStrategies(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"strategies": h.strategyRegistry.ListRateLimitStrategies(),
		"current":    h.strategyRegistry.GetRateLimitStrategy().Name(),
	})
}

func (h *EnhancedHandler) SetRateLimitStrategy(c *gin.Context) {
	name := c.Param("name")
	if err := h.strategyRegistry.SetRateLimitStrategy(name); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"message":  "Rate limit strategy updated",
		"strategy": name,
	})
}

func (h *EnhancedHandler) TriggerJobAsync(c *gin.Context) {
	jobID := c.Param("id")
	createdBy := c.GetHeader("X-User-ID")
	if createdBy == "" {
		createdBy = "api"
	}

	taskID, err := h.asyncScheduler.TriggerJobAsync(jobID, createdBy, func(ctx context.Context, result scheduler.TaskResult) {
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusAccepted, gin.H{
		"message": "Job triggered asynchronously",
		"task_id": taskID,
		"job_id":  jobID,
	})
}

func (h *EnhancedHandler) GetQueueStatus(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"queue_size":   h.asyncScheduler.QueueSize(),
		"worker_count": h.asyncScheduler.WorkerCount(),
		"timestamp":    time.Now().Unix(),
	})
}
