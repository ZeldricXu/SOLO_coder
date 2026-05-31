package device_lifecycle

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"edgescheduler/pkg/utils"
)

type DeviceHandler struct {
	service DeviceService
}

func NewDeviceHandler(service DeviceService) *DeviceHandler {
	return &DeviceHandler{
		service: service,
	}
}

func (h *DeviceHandler) RegisterRoutes(router *gin.RouterGroup) {
	devices := router.Group("/devices")
	{
		devices.POST("", h.RegisterDevice)
		devices.POST("/activate", h.ActivateDevice)
		devices.POST("/heartbeat", h.ProcessHeartbeat)
		devices.GET("/:device_id", h.GetDevice)
		devices.GET("", h.ListDevices)
		devices.PUT("/:device_id/status", h.UpdateDeviceStatus)
		devices.POST("/:device_id/deactivate", h.DeactivateDevice)
		devices.DELETE("/:device_id", h.DeleteDevice)
	}
}

func (h *DeviceHandler) RegisterDevice(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req DeviceRegistrationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	device, err := h.service.RegisterDevice(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, gin.H{
		"id":         device.ID,
		"device_id":  device.DeviceID,
		"status":     device.Status,
		"auth_token": device.AuthToken,
	})
}

func (h *DeviceHandler) ActivateDevice(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req DeviceActivationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	device, err := h.service.ActivateDevice(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"device_id":    device.DeviceID,
		"status":       device.Status,
		"activated_at": device.ActivatedAt,
	})
}

func (h *DeviceHandler) ProcessHeartbeat(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req DeviceHeartbeatRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	status, err := h.service.ProcessHeartbeat(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, status)
}

func (h *DeviceHandler) GetDevice(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	device, err := h.service.GetDevice(ctx, deviceID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, device)
}

func (h *DeviceHandler) ListDevices(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	filters := map[string]interface{}{
		"status":   c.Query("status"),
		"type":     c.Query("type"),
		"location": c.Query("location"),
	}

	devices, total, err := h.service.ListDevices(ctx, filters, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"devices": devices,
		"total":   total,
		"offset":  offset,
		"limit":   limit,
	})
}

func (h *DeviceHandler) UpdateDeviceStatus(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	var body struct {
		Status string `json:"status" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	err := h.service.UpdateDeviceStatus(ctx, deviceID, DeviceStatus(body.Status))
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"device_id": deviceID,
		"status":    body.Status,
	})
}

func (h *DeviceHandler) DeactivateDevice(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	var body struct {
		Reason string `json:"reason"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		body.Reason = "manual"
	}

	err := h.service.DeactivateDevice(ctx, deviceID, body.Reason)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"device_id": deviceID,
		"status":    DeviceStatusDeactivated,
		"reason":    body.Reason,
	})
}

func (h *DeviceHandler) DeleteDevice(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	err := h.service.DeleteDevice(ctx, deviceID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"device_id": deviceID,
		"deleted":   true,
	})
}
