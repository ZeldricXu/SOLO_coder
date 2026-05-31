package protocol_adapter

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"edgescheduler/pkg/utils"
)

type ProtocolAdapterHandler struct {
	service ProtocolAdapterService
}

func NewProtocolAdapterHandler(service ProtocolAdapterService) *ProtocolAdapterHandler {
	return &ProtocolAdapterHandler{
		service: service,
	}
}

func (h *ProtocolAdapterHandler) RegisterRoutes(router *gin.RouterGroup) {
	pa := router.Group("/protocol")
	{
		drivers := pa.Group("/drivers")
		{
			drivers.POST("", h.LoadDriver)
			drivers.GET("", h.ListDrivers)
			drivers.GET("/:driver_id", h.GetDriver)
			drivers.DELETE("/:driver_id", h.UnloadDriver)
		}

		configs := pa.Group("/configs")
		{
			configs.POST("", h.CreateDeviceConfig)
			configs.GET("", h.ListDeviceConfigs)
			configs.GET("/device/:device_id", h.GetDeviceConfig)
			configs.PUT("/:config_id", h.UpdateDeviceConfig)
			configs.DELETE("/:config_id", h.DeleteDeviceConfig)
			configs.POST("/:config_id/start", h.StartConnection)
			configs.POST("/:config_id/stop", h.StopConnection)
		}

		rules := pa.Group("/forward-rules")
		{
			rules.POST("", h.CreateForwardRule)
			rules.GET("", h.ListForwardRules)
			rules.DELETE("/:rule_id", h.DeleteForwardRule)
		}
	}
}

func (h *ProtocolAdapterHandler) LoadDriver(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req DriverLoadRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	driver, err := h.service.LoadDriver(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, driver)
}

func (h *ProtocolAdapterHandler) GetDriver(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	driverID := c.Param("driver_id")

	driver, err := h.service.GetDriver(ctx, driverID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, driver)
}

func (h *ProtocolAdapterHandler) ListDrivers(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	protocol := ProtocolType(c.Query("protocol"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	drivers, total, err := h.service.ListDrivers(ctx, protocol, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"drivers": drivers,
		"total":   total,
		"offset":  offset,
		"limit":   limit,
	})
}

func (h *ProtocolAdapterHandler) UnloadDriver(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	driverID := c.Param("driver_id")

	err := h.service.UnloadDriver(ctx, driverID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"driver_id": driverID,
		"unloaded":  true,
	})
}

func (h *ProtocolAdapterHandler) CreateDeviceConfig(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req DeviceConfigRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	config, err := h.service.CreateDeviceConfig(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, config)
}

func (h *ProtocolAdapterHandler) GetDeviceConfig(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	config, err := h.service.GetDeviceConfig(ctx, deviceID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, config)
}

func (h *ProtocolAdapterHandler) ListDeviceConfigs(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	configs, total, err := h.service.ListDeviceConfigs(ctx, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"configs": configs,
		"total":   total,
		"offset":  offset,
		"limit":   limit,
	})
}

func (h *ProtocolAdapterHandler) UpdateDeviceConfig(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	configID := c.Param("config_id")

	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	err := h.service.UpdateDeviceConfig(ctx, configID, updates)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"config_id": configID,
		"updated":   true,
	})
}

func (h *ProtocolAdapterHandler) DeleteDeviceConfig(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	configID := c.Param("config_id")

	err := h.service.DeleteDeviceConfig(ctx, configID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"config_id": configID,
		"deleted":   true,
	})
}

func (h *ProtocolAdapterHandler) StartConnection(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	configID := c.Param("config_id")

	err := h.service.StartConnection(ctx, configID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"config_id": configID,
		"status":    "connecting",
	})
}

func (h *ProtocolAdapterHandler) StopConnection(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	configID := c.Param("config_id")

	err := h.service.StopConnection(ctx, configID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"config_id": configID,
		"status":    "disconnected",
	})
}

func (h *ProtocolAdapterHandler) CreateForwardRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var rule ForwardRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	result, err := h.service.CreateForwardRule(ctx, &rule)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, result)
}

func (h *ProtocolAdapterHandler) ListForwardRules(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	rules, total, err := h.service.ListForwardRules(ctx, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"rules":  rules,
		"total":  total,
		"offset": offset,
		"limit":  limit,
	})
}

func (h *ProtocolAdapterHandler) DeleteForwardRule(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	ruleID := c.Param("rule_id")

	err := h.service.DeleteForwardRule(ctx, ruleID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"rule_id": ruleID,
		"deleted": true,
	})
}
