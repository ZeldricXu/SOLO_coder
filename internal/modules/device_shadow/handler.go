package device_shadow

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"edgescheduler/pkg/utils"
)

type DeviceShadowHandler struct {
	service DeviceShadowService
}

func NewDeviceShadowHandler(service DeviceShadowService) *DeviceShadowHandler {
	return &DeviceShadowHandler{
		service: service,
	}
}

func (h *DeviceShadowHandler) RegisterRoutes(router *gin.RouterGroup) {
	shadow := router.Group("/shadow")
	{
		shadow.GET("/:device_id", h.GetShadow)
		shadow.PUT("/:device_id/desired", h.UpdateDesiredState)
		shadow.PUT("/:device_id/reported", h.UpdateReportedState)
		shadow.DELETE("/:device_id", h.DeleteShadow)
		shadow.GET("", h.ListShadows)
		shadow.POST("/:device_id/sync", h.SyncShadow)
		shadow.POST("/:device_id/resolve-conflict", h.ResolveConflict)

		shadow.GET("/:device_id/logs", h.GetOperationLogs)
		shadow.GET("/:device_id/history", h.GetVersionHistory)
		shadow.POST("/:device_id/rollback", h.RollbackToVersion)
	}
}

func (h *DeviceShadowHandler) GetShadow(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	shadow, err := h.service.GetOrCreateShadow(ctx, deviceID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, shadow)
}

func (h *DeviceShadowHandler) UpdateDesiredState(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	var body struct {
		Desired map[string]interface{} `json:"desired" binding:"required"`
		Source  string                 `json:"source"`
		Version int                    `json:"version"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	req := &ShadowUpdateRequest{
		DeviceID: deviceID,
		Desired:  body.Desired,
		Source:   body.Source,
		Version:  body.Version,
	}

	shadow, err := h.service.UpdateDesiredState(ctx, req)
	if err != nil {
		if err.Error()[:16] == "version conflict" {
			utils.ErrorResponse(c, http.StatusConflict, err.Error())
			return
		}
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, shadow)
}

func (h *DeviceShadowHandler) UpdateReportedState(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	var body struct {
		Reported map[string]interface{} `json:"reported" binding:"required"`
		Source   string                 `json:"source"`
		Version  int                    `json:"version"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	req := &ShadowUpdateRequest{
		DeviceID: deviceID,
		Reported: body.Reported,
		Source:   body.Source,
		Version:  body.Version,
	}

	shadow, err := h.service.UpdateReportedState(ctx, req)
	if err != nil {
		if err.Error()[:16] == "version conflict" {
			utils.ErrorResponse(c, http.StatusConflict, err.Error())
			return
		}
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, shadow)
}

func (h *DeviceShadowHandler) DeleteShadow(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	err := h.service.DeleteShadow(ctx, deviceID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"device_id": deviceID,
		"deleted":   true,
	})
}

func (h *DeviceShadowHandler) ListShadows(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	status := ShadowSyncStatus(c.Query("sync_status"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	shadows, total, err := h.service.ListShadows(ctx, status, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"shadows": shadows,
		"total":   total,
		"offset":  offset,
		"limit":   limit,
	})
}

func (h *DeviceShadowHandler) SyncShadow(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	shadow, err := h.service.SyncShadow(ctx, deviceID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"device_id": deviceID,
		"status":    "syncing",
		"version":   shadow.Version,
	})
}

func (h *DeviceShadowHandler) ResolveConflict(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	var body struct {
		Action     ShadowVersionConflictAction `json:"action" binding:"required"`
		Resolution map[string]interface{}      `json:"resolution"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	shadow, err := h.service.ResolveConflict(ctx, deviceID, body.Action, body.Resolution)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, shadow)
}

func (h *DeviceShadowHandler) GetOperationLogs(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))

	logs, total, err := h.service.GetOperationLogs(ctx, deviceID, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"logs":   logs,
		"total":  total,
		"offset": offset,
		"limit":  limit,
	})
}

func (h *DeviceShadowHandler) GetVersionHistory(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	history, total, err := h.service.GetVersionHistory(ctx, deviceID, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"history": history,
		"total":   total,
		"offset":  offset,
		"limit":   limit,
	})
}

func (h *DeviceShadowHandler) RollbackToVersion(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	deviceID := c.Param("device_id")

	var body struct {
		Version int `json:"version" binding:"required"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	shadow, err := h.service.RollbackToVersion(ctx, deviceID, body.Version)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, shadow)
}
