package handler

import (
	"crypto/md5"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"gamestats/event-access/config"
	"gamestats/event-access/model"
	"gamestats/event-access/storage"

	"github.com/gin-gonic/gin"
	"github.com/go-playground/validator/v10"
	"go.uber.org/zap"
)

type ConfigHandler struct {
	influxDB   *storage.InfluxDBClient
	mysql      *storage.MySQLClient
	logger     *zap.Logger
	config     *config.Config
	validator  *validator.Validate
	configCache sync.Map
	defaultSDKConfig *model.SDKConfig
}

func NewConfigHandler(
	influxDB *storage.InfluxDBClient,
	mysql *storage.MySQLClient,
	logger *zap.Logger,
	cfg *config.Config,
) *ConfigHandler {
	handler := &ConfigHandler{
		influxDB:  influxDB,
		mysql:     mysql,
		logger:    logger,
		config:    cfg,
		validator: validator.New(),
	}

	handler.initDefaultConfig()
	go handler.startConfigRefresh()

	return handler
}

func (h *ConfigHandler) initDefaultConfig() {
	defaultSettings := model.SDKSettings{
		BatchSize:           50,
		FlushIntervalMs:     5000,
		MaxRetries:          3,
		HeartbeatIntervalMs: 30000,
		EnableHeartbeat:     true,
		EnableLocalCache:    true,
	}

	defaultEvents := []model.EventConfigItem{
		{
			EventType:      "login",
			EventName:      "玩家登录",
			Description:    "玩家登录游戏事件",
			Enabled:        true,
			RequiredFields: map[string]string{"login_method": "登录方式", "device_type": "设备类型"},
			OptionalFields: map[string]string{"ip_region": "IP地区"},
		},
		{
			EventType:      "logout",
			EventName:      "玩家登出",
			Description:    "玩家登出游戏事件",
			Enabled:        true,
			RequiredFields: map[string]string{},
			OptionalFields: map[string]string{"session_duration": "会话时长", "reason": "登出原因"},
		},
		{
			EventType:      "payment",
			EventName:      "支付事件",
			Description:    "玩家付费事件",
			Enabled:        true,
			RequiredFields: map[string]string{"amount": "金额", "currency": "币种", "item_id": "商品ID"},
			OptionalFields: map[string]string{"payment_method": "支付方式"},
		},
		{
			EventType:      "heartbeat",
			EventName:      "心跳事件",
			Description:    "玩家心跳保活事件",
			Enabled:        true,
			RequiredFields: map[string]string{},
			OptionalFields: map[string]string{},
		},
	}

	h.defaultSDKConfig = &model.SDKConfig{
		Version:      "1.0.0",
		ConfigHash:   "",
		LastUpdated:  time.Now(),
		EventConfigs: defaultEvents,
		SDKSettings:  defaultSettings,
	}

	h.updateConfigHash(h.defaultSDKConfig)
}

func (h *ConfigHandler) updateConfigHash(cfg *model.SDKConfig) {
	jsonData, _ := json.Marshal(cfg.EventConfigs)
	settingsData, _ := json.Marshal(cfg.SDKSettings)
	hashData := append(jsonData, settingsData...)
	cfg.ConfigHash = fmt.Sprintf("%x", md5.Sum(hashData))
}

func (h *ConfigHandler) startConfigRefresh() {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		h.refreshConfigCache()
	}
}

func (h *ConfigHandler) refreshConfigCache() {
	h.logger.Debug("Refreshing config cache")
}

func (h *ConfigHandler) GetSDKConfig(c *gin.Context) {
	var req model.SDKConfigRequest

	if err := c.ShouldBindQuery(&req); err != nil {
		h.logger.Warn("Invalid SDK config request", zap.Error(err))
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, "Invalid request parameters"))
		return
	}

	if err := h.validator.Struct(req); err != nil {
		h.logger.Warn("SDK config request validation failed", zap.Error(err))
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	cachedConfig, found := h.configCache.Load(req.GameID)
	if found {
		cfg := cachedConfig.(*model.SDKConfig)
		h.logger.Info("SDK config served from cache", zap.String("game_id", req.GameID))
		c.JSON(http.StatusOK, model.NewSuccessResponse(cfg))
		return
	}

	dbConfig, err := h.loadConfigFromDB(req.GameID)
	if err != nil {
		h.logger.Debug("Using default SDK config", 
			zap.String("game_id", req.GameID), 
			zap.Error(err))
		
		defaultConfig := *h.defaultSDKConfig
		defaultConfig.GameID = req.GameID
		
		c.JSON(http.StatusOK, model.NewSuccessResponse(defaultConfig))
		return
	}

	h.configCache.Store(req.GameID, dbConfig)
	h.logger.Info("SDK config loaded from database", zap.String("game_id", req.GameID))
	c.JSON(http.StatusOK, model.NewSuccessResponse(dbConfig))
}

func (h *ConfigHandler) loadConfigFromDB(gameID string) (*model.SDKConfig, error) {
	eventConfigs, err := h.mysql.GetEventConfigs(h.config.Context(), gameID)
	if err != nil {
		return nil, err
	}

	if len(eventConfigs) == 0 {
		return nil, fmt.Errorf("no event configs found for game: %s", gameID)
	}

	configItems := make([]model.EventConfigItem, 0, len(eventConfigs))
	for _, cfg := range eventConfigs {
		if cfg.RequiredFields == nil {
			cfg.RequiredFields = map[string]string{}
		}
		if cfg.OptionalFields == nil {
			cfg.OptionalFields = map[string]string{}
		}

		configItems = append(configItems, model.EventConfigItem{
			EventType:      cfg.EventType,
			EventName:      cfg.EventName,
			Description:    cfg.Description,
			Enabled:        cfg.IsActive,
			RequiredFields: cfg.RequiredFields,
			OptionalFields: cfg.OptionalFields,
		})
	}

	sdkConfig := &model.SDKConfig{
		Version:      "1.0.0",
		GameID:       gameID,
		LastUpdated:  time.Now(),
		EventConfigs: configItems,
		SDKSettings: model.SDKSettings{
			BatchSize:           50,
			FlushIntervalMs:     5000,
			MaxRetries:          3,
			HeartbeatIntervalMs: 30000,
			EnableHeartbeat:     true,
			EnableLocalCache:    true,
		},
	}

	h.updateConfigHash(sdkConfig)
	return sdkConfig, nil
}

func (h *ConfigHandler) CreateEventConfig(c *gin.Context) {
	var req model.EventConfigCreateRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		h.logger.Warn("Invalid event config create request", zap.Error(err))
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, "Invalid request body"))
		return
	}

	if err := h.validator.Struct(req); err != nil {
		h.logger.Warn("Event config validation failed", zap.Error(err))
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	eventConfig := &model.EventConfig{
		GameID:         req.GameID,
		EventType:      strings.ToLower(strings.TrimSpace(req.EventType)),
		EventName:      req.EventName,
		Description:    req.Description,
		RequiredFields: req.RequiredFields,
		OptionalFields: req.OptionalFields,
		IsActive:       req.IsActive,
	}

	if eventConfig.RequiredFields == nil {
		eventConfig.RequiredFields = map[string]string{}
	}
	if eventConfig.OptionalFields == nil {
		eventConfig.OptionalFields = map[string]string{}
	}

	if err := h.mysql.CreateEventConfig(h.config.Context(), eventConfig); err != nil {
		h.logger.Error("Failed to create event config", zap.Error(err))
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, "Failed to create event config"))
		return
	}

	h.configCache.Delete(req.GameID)

	h.logger.Info("Event config created", 
		zap.String("game_id", req.GameID),
		zap.String("event_type", req.EventType))
	c.JSON(http.StatusOK, model.NewSuccessResponse(eventConfig))
}

func (h *ConfigHandler) UpdateEventConfig(c *gin.Context) {
	gameID := c.Param("game_id")
	eventType := strings.ToLower(strings.TrimSpace(c.Param("event_type")))

	var req model.EventConfigUpdateRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		h.logger.Warn("Invalid event config update request", zap.Error(err))
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, "Invalid request body"))
		return
	}

	existingConfig, err := h.mysql.GetEventConfig(h.config.Context(), gameID, eventType)
	if err != nil {
		h.logger.Warn("Event config not found", 
			zap.String("game_id", gameID),
			zap.String("event_type", eventType),
			zap.Error(err))
		c.JSON(http.StatusNotFound, model.NewErrorResponse(404, "Event config not found"))
		return
	}

	if req.EventName != "" {
		existingConfig.EventName = req.EventName
	}
	if req.Description != "" {
		existingConfig.Description = req.Description
	}
	if req.RequiredFields != nil {
		existingConfig.RequiredFields = req.RequiredFields
	}
	if req.OptionalFields != nil {
		existingConfig.OptionalFields = req.OptionalFields
	}
	if req.IsActive != nil {
		existingConfig.IsActive = *req.IsActive
	}

	if err := h.mysql.UpdateEventConfig(h.config.Context(), existingConfig); err != nil {
		h.logger.Error("Failed to update event config", zap.Error(err))
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, "Failed to update event config"))
		return
	}

	h.configCache.Delete(gameID)

	h.logger.Info("Event config updated", 
		zap.String("game_id", gameID),
		zap.String("event_type", eventType))
	c.JSON(http.StatusOK, model.NewSuccessResponse(existingConfig))
}

func (h *ConfigHandler) DeleteEventConfig(c *gin.Context) {
	gameID := c.Param("game_id")
	eventType := strings.ToLower(strings.TrimSpace(c.Param("event_type")))

	if err := h.mysql.DeleteEventConfig(h.config.Context(), gameID, eventType); err != nil {
		h.logger.Error("Failed to delete event config", 
			zap.String("game_id", gameID),
			zap.String("event_type", eventType),
			zap.Error(err))
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, "Failed to delete event config"))
		return
	}

	h.configCache.Delete(gameID)

	h.logger.Info("Event config deleted", 
		zap.String("game_id", gameID),
		zap.String("event_type", eventType))
	c.JSON(http.StatusOK, model.NewSuccessResponse(map[string]string{
		"status": "deleted",
	}))
}

func (h *ConfigHandler) ListEventConfigs(c *gin.Context) {
	gameID := c.Query("game_id")
	if gameID == "" {
		gameID = "default"
	}

	eventConfigs, err := h.mysql.GetEventConfigs(h.config.Context(), gameID)
	if err != nil {
		h.logger.Warn("Failed to list event configs", 
			zap.String("game_id", gameID),
			zap.Error(err))
		c.JSON(http.StatusOK, model.NewSuccessResponse([]model.EventConfig{}))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(eventConfigs))
}

func (h *ConfigHandler) GetEventConfig(c *gin.Context) {
	gameID := c.Param("game_id")
	eventType := strings.ToLower(strings.TrimSpace(c.Param("event_type")))

	eventConfig, err := h.mysql.GetEventConfig(h.config.Context(), gameID, eventType)
	if err != nil {
		h.logger.Warn("Event config not found", 
			zap.String("game_id", gameID),
			zap.String("event_type", eventType),
			zap.Error(err))
		c.JSON(http.StatusNotFound, model.NewErrorResponse(404, "Event config not found"))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(eventConfig))
}

func (h *ConfigHandler) ClearConfigCache(c *gin.Context) {
	gameID := c.Query("game_id")
	
	if gameID != "" {
		h.configCache.Delete(gameID)
		h.logger.Info("Config cache cleared for game", zap.String("game_id", gameID))
	} else {
		h.configCache = sync.Map{}
		h.logger.Info("All config cache cleared")
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(map[string]string{
		"status": "cache_cleared",
	}))
}
