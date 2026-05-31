package firmware_ota

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"edgescheduler/pkg/utils"
)

type FirmwareOTAHandler struct {
	service FirmwareOTAService
}

func NewFirmwareOTAHandler(service FirmwareOTAService) *FirmwareOTAHandler {
	return &FirmwareOTAHandler{
		service: service,
	}
}

func (h *FirmwareOTAHandler) RegisterRoutes(router *gin.RouterGroup) {
	ota := router.Group("/ota")
	{
		firmware := ota.Group("/firmware")
		{
			firmware.POST("/generate-diff", h.GenerateDifferentialFirmware)
			firmware.POST("/publish", h.PublishFirmware)
			firmware.GET("", h.ListFirmwares)
			firmware.GET("/:firmware_id", h.GetFirmware)
		}

		batches := ota.Group("/batches")
		{
			batches.POST("", h.CreateUpgradeBatch)
			batches.GET("", h.ListUpgradeBatches)
			batches.GET("/:batch_id", h.GetUpgradeBatch)
			batches.POST("/:batch_id/start", h.StartUpgradeBatch)
			batches.POST("/:batch_id/pause", h.PauseUpgradeBatch)
			batches.POST("/:batch_id/cancel", h.CancelUpgradeBatch)
			batches.POST("/:batch_id/rollback", h.RollbackUpgradeBatch)
		}

		upgrades := ota.Group("/upgrades")
		{
			upgrades.GET("", h.ListDeviceUpgrades)
			upgrades.GET("/:upgrade_id", h.GetDeviceUpgrade)
			upgrades.PUT("/:upgrade_id/status", h.UpdateDeviceUpgradeStatus)
			upgrades.POST("/:upgrade_id/retry", h.RetryDeviceUpgrade)
		}

		policies := ota.Group("/policies")
		{
			policies.POST("", h.CreateUpgradePolicy)
			policies.GET("", h.ListUpgradePolicies)
		}
	}
}

func (h *FirmwareOTAHandler) GenerateDifferentialFirmware(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req DiffGenerationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	firmware, err := h.service.GenerateDifferentialFirmware(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, firmware)
}

func (h *FirmwareOTAHandler) PublishFirmware(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req FirmwarePublishRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	firmware, err := h.service.PublishFirmware(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, firmware)
}

func (h *FirmwareOTAHandler) GetFirmware(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	firmwareID := c.Param("firmware_id")

	firmware, err := h.service.GetFirmware(ctx, firmwareID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, firmware)
}

func (h *FirmwareOTAHandler) ListFirmwares(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	deviceType := c.Query("device_type")
	status := FirmwareStatus(c.Query("status"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	firmwares, total, err := h.service.ListFirmwares(ctx, deviceType, status, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"firmwares": firmwares,
		"total":     total,
		"offset":    offset,
		"limit":     limit,
	})
}

func (h *FirmwareOTAHandler) CreateUpgradeBatch(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var req UpgradeBatchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	batch, err := h.service.CreateUpgradeBatch(ctx, &req)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, batch)
}

func (h *FirmwareOTAHandler) GetUpgradeBatch(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	batchID := c.Param("batch_id")

	batch, err := h.service.GetUpgradeBatch(ctx, batchID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, batch)
}

func (h *FirmwareOTAHandler) ListUpgradeBatches(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	batches, total, err := h.service.ListUpgradeBatches(ctx, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"batches": batches,
		"total":   total,
		"offset":  offset,
		"limit":   limit,
	})
}

func (h *FirmwareOTAHandler) StartUpgradeBatch(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	batchID := c.Param("batch_id")

	err := h.service.StartUpgradeBatch(ctx, batchID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"batch_id": batchID,
		"status":   "in_progress",
	})
}

func (h *FirmwareOTAHandler) PauseUpgradeBatch(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	batchID := c.Param("batch_id")

	err := h.service.PauseUpgradeBatch(ctx, batchID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"batch_id": batchID,
		"status":   "paused",
	})
}

func (h *FirmwareOTAHandler) CancelUpgradeBatch(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	batchID := c.Param("batch_id")

	err := h.service.CancelUpgradeBatch(ctx, batchID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"batch_id": batchID,
		"status":   "cancelled",
	})
}

func (h *FirmwareOTAHandler) RollbackUpgradeBatch(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	batchID := c.Param("batch_id")

	var body struct {
		Reason string `json:"reason"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		body.Reason = "manual rollback"
	}

	err := h.service.RollbackUpgradeBatch(ctx, batchID, body.Reason)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"batch_id": batchID,
		"status":   "rolling_back",
		"reason":   body.Reason,
	})
}

func (h *FirmwareOTAHandler) GetDeviceUpgrade(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	upgradeID := c.Param("upgrade_id")

	upgrade, err := h.service.GetDeviceUpgrade(ctx, upgradeID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusNotFound, err.Error())
		return
	}

	utils.SuccessResponse(c, upgrade)
}

func (h *FirmwareOTAHandler) ListDeviceUpgrades(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	deviceID := c.Query("device_id")
	batchID := c.Query("batch_id")
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	upgrades, total, err := h.service.ListDeviceUpgrades(ctx, deviceID, batchID, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"upgrades": upgrades,
		"total":    total,
		"offset":   offset,
		"limit":    limit,
	})
}

func (h *FirmwareOTAHandler) UpdateDeviceUpgradeStatus(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	upgradeID := c.Param("upgrade_id")

	var body struct {
		Phase    UpgradePhase `json:"phase" binding:"required"`
		Progress int          `json:"progress"`
		Error    string       `json:"error"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	err := h.service.UpdateDeviceUpgradeStatus(ctx, upgradeID, body.Phase, body.Progress, body.Error)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"upgrade_id": upgradeID,
		"updated":    true,
	})
}

func (h *FirmwareOTAHandler) RetryDeviceUpgrade(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())
	upgradeID := c.Param("upgrade_id")

	err := h.service.RetryDeviceUpgrade(ctx, upgradeID)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"upgrade_id": upgradeID,
		"retrying":   true,
	})
}

func (h *FirmwareOTAHandler) CreateUpgradePolicy(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	var policy UpgradePolicy
	if err := c.ShouldBindJSON(&policy); err != nil {
		utils.ValidationErrorResponse(c, err.Error())
		return
	}

	result, err := h.service.CreateUpgradePolicy(ctx, &policy)
	if err != nil {
		utils.ErrorResponse(c, http.StatusBadRequest, err.Error())
		return
	}

	utils.CreatedResponse(c, result)
}

func (h *FirmwareOTAHandler) ListUpgradePolicies(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	policies, total, err := h.service.ListUpgradePolicies(ctx, offset, limit)
	if err != nil {
		utils.ErrorResponse(c, http.StatusInternalServerError, err.Error())
		return
	}

	utils.SuccessResponse(c, gin.H{
		"policies": policies,
		"total":    total,
		"offset":   offset,
		"limit":    limit,
	})
}
