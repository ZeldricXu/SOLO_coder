package handler

import (
	"time"

	"github.com/edgevision/edgevision/internal/domain/aggregation"
	"github.com/edgevision/edgevision/internal/domain/offline"
	"github.com/edgevision/edgevision/internal/domain/ota"
	"github.com/edgevision/edgevision/pkg/errors"
	"github.com/gin-gonic/gin"
)

type EnhancementHandler struct {
	otaService        ota.OTAService
	offlineService    offline.OfflineService
	aggregationService aggregation.DataAggregationService
}

func NewEnhancementHandler(
	otaService ota.OTAService,
	offlineService offline.OfflineService,
	aggregationService aggregation.DataAggregationService,
) *EnhancementHandler {
	return &EnhancementHandler{
		otaService:         otaService,
		offlineService:     offlineService,
		aggregationService: aggregationService,
	}
}

func (h *EnhancementHandler) GetOTAConfig(c *gin.Context) {
	profile := c.DefaultQuery("profile", "default")

	configManager := h.otaService.(interface{ GetConfigManager() ota.ConfigManager }).GetConfigManager()
	config, err := configManager.GetConfig(c.Request.Context(), profile)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, config)
}

func (h *EnhancementHandler) SaveOTAConfig(c *gin.Context) {
	profile := c.Param("profile")
	if profile == "" {
		Error(c, errors.BadRequest("Profile is required"))
		return
	}

	var config ota.OTAConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		Error(c, errors.ValidationError("Invalid config", err.Error()))
		return
	}

	configManager := h.otaService.(interface{ GetConfigManager() ota.ConfigManager }).GetConfigManager()
	if err := configManager.SaveConfig(c.Request.Context(), profile, &config); err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, gin.H{"message": "Config saved successfully", "profile": profile})
}

func (h *EnhancementHandler) UpdateOTAConfig(c *gin.Context) {
	profile := c.Param("profile")
	if profile == "" {
		Error(c, errors.BadRequest("Profile is required"))
		return
	}

	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		Error(c, errors.ValidationError("Invalid updates", err.Error()))
		return
	}

	configManager := h.otaService.(interface{ GetConfigManager() ota.ConfigManager }).GetConfigManager()
	config, err := configManager.UpdateConfig(c.Request.Context(), profile, updates)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, config)
}

func (h *EnhancementHandler) ListOTAProfiles(c *gin.Context) {
	configManager := h.otaService.(interface{ GetConfigManager() ota.ConfigManager }).GetConfigManager()
	profiles, err := configManager.ListProfiles(c.Request.Context())
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, gin.H{"profiles": profiles})
}

func (h *EnhancementHandler) SetDeviceSyncStrategy(c *gin.Context) {
	deviceID := c.Param("device_id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	var req struct {
		Strategy string `json:"strategy"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request", err.Error()))
		return
	}

	if err := h.offlineService.SetDeviceSyncStrategy(deviceID, req.Strategy); err != nil {
		Error(c, errors.BadRequest(err.Error()))
		return
	}

	Success(c, gin.H{"message": "Strategy set successfully", "device_id": deviceID, "strategy": req.Strategy})
}

func (h *EnhancementHandler) GetDeviceSyncStrategy(c *gin.Context) {
	deviceID := c.Param("device_id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	strategy := h.offlineService.GetDeviceSyncStrategy(deviceID)
	Success(c, gin.H{"device_id": deviceID, "strategy": strategy})
}

func (h *EnhancementHandler) ListSyncStrategies(c *gin.Context) {
	strategies := h.offlineService.ListSyncStrategies()
	Success(c, gin.H{"strategies": strategies})
}

func (h *EnhancementHandler) SetStrategyConfig(c *gin.Context) {
	strategyName := c.Param("strategy")
	if strategyName == "" {
		Error(c, errors.BadRequest("Strategy name is required"))
		return
	}

	var req struct {
		BatchSize int           `json:"batch_size"`
		Timeout   int64         `json:"timeout_ms"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request", err.Error()))
		return
	}

	if err := h.offlineService.SetStrategyConfig(strategyName, req.BatchSize, time.Duration(req.Timeout)*time.Millisecond); err != nil {
		Error(c, errors.BadRequest(err.Error()))
		return
	}

	Success(c, gin.H{"message": "Strategy config updated successfully"})
}

func (h *EnhancementHandler) AggregateDataAsync(c *gin.Context) {
	streamID := c.Param("id")
	if streamID == "" {
		Error(c, errors.BadRequest("Stream ID is required"))
		return
	}

	task, err := h.aggregationService.AggregateDataAsync(c.Request.Context(), streamID, nil)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, task)
}

func (h *EnhancementHandler) GetAggregationTaskStatus(c *gin.Context) {
	taskID := c.Param("task_id")
	if taskID == "" {
		Error(c, errors.BadRequest("Task ID is required"))
		return
	}

	task, err := h.aggregationService.GetTaskStatus(c.Request.Context(), taskID)
	if err != nil {
		Error(c, errors.NotFound(err.Error()))
		return
	}

	Success(c, task)
}

func (h *EnhancementHandler) CancelAggregationTask(c *gin.Context) {
	taskID := c.Param("task_id")
	if taskID == "" {
		Error(c, errors.BadRequest("Task ID is required"))
		return
	}

	if err := h.aggregationService.CancelTask(c.Request.Context(), taskID); err != nil {
		Error(c, errors.BadRequest(err.Error()))
		return
	}

	Success(c, gin.H{"message": "Task cancelled successfully"})
}

func (h *EnhancementHandler) ListAggregationTasks(c *gin.Context) {
	streamID := c.Query("stream_id")
	status := c.Query("status")

	asyncManager := h.aggregationService.GetAsyncManager()
	tasks := asyncManager.ListTasks(c.Request.Context(), streamID, aggregation.AsyncTaskStatus(status))

	Success(c, gin.H{"tasks": tasks})
}
