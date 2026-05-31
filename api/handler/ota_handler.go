package handler

import (
	"github.com/edgevision/edgevision/internal/domain/ota"
	"github.com/edgevision/edgevision/pkg/errors"
	"github.com/gin-gonic/gin"
)

type OTAHandler struct {
	otaService ota.OTAService
}

func NewOTAHandler(otaService ota.OTAService) *OTAHandler {
	return &OTAHandler{
		otaService: otaService,
	}
}

func (h *OTAHandler) CreateFirmware(c *gin.Context) {
	var req ota.CreateFirmwareRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	firmware, err := h.otaService.CreateFirmware(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, firmware)
}

func (h *OTAHandler) GenerateDeltaPackage(c *gin.Context) {
	var req ota.GenerateDeltaPackageRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	delta, err := h.otaService.GenerateDeltaPackage(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, delta)
}

func (h *OTAHandler) CreateUpgradeTask(c *gin.Context) {
	var req ota.CreateUpgradeTaskRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	task, err := h.otaService.CreateUpgradeTask(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, task)
}

func (h *OTAHandler) StartUpgradeTask(c *gin.Context) {
	taskID := c.Param("id")
	if taskID == "" {
		Error(c, errors.BadRequest("Task ID is required"))
		return
	}

	task, err := h.otaService.StartUpgradeTask(c.Request.Context(), taskID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, task)
}

func (h *OTAHandler) GetTask(c *gin.Context) {
	taskID := c.Param("id")
	if taskID == "" {
		Error(c, errors.BadRequest("Task ID is required"))
		return
	}

	task, err := h.otaService.GetTask(c.Request.Context(), taskID)
	if err != nil {
		Error(c, errors.NotFound("Task not found"))
		return
	}

	Success(c, task)
}

func (h *OTAHandler) ListTasks(c *gin.Context) {
	page, pageSize := GetPagination(c)
	status := c.Query("status")

	tasks, total, err := h.otaService.ListTasks(c.Request.Context(), page, pageSize, status)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, tasks, total, page, pageSize)
}

func (h *OTAHandler) GetTaskStatus(c *gin.Context) {
	taskID := c.Param("id")
	if taskID == "" {
		Error(c, errors.BadRequest("Task ID is required"))
		return
	}

	task, err := h.otaService.GetTask(c.Request.Context(), taskID)
	if err != nil {
		Error(c, errors.NotFound("Task not found"))
		return
	}

	Success(c, gin.H{
		"id":       task.ID,
		"status":   task.Status,
		"progress": task.Progress,
		"success_count":  task.SuccessCount,
		"failed_count":   task.FailedCount,
		"rollback_count": task.RollbackCount,
		"total_devices":  task.TotalDevices,
	})
}

func (h *OTAHandler) GetDeviceUpgrades(c *gin.Context) {
	taskID := c.Param("id")
	if taskID == "" {
		Error(c, errors.BadRequest("Task ID is required"))
		return
	}

	page, pageSize := GetPagination(c)

	upgrades, total, err := h.otaService.GetDeviceUpgrades(c.Request.Context(), taskID, page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, upgrades, total, page, pageSize)
}

func (h *OTAHandler) ReportDeviceStatus(c *gin.Context) {
	upgradeID := c.Param("id")
	if upgradeID == "" {
		Error(c, errors.BadRequest("Upgrade ID is required"))
		return
	}

	var req struct {
		Status   string  `json:"status"`
		Phase    string  `json:"phase"`
		Progress float64 `json:"progress"`
		Error    string  `json:"error"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	var errorMsg *string
	if req.Error != "" {
		errorMsg = &req.Error
	}

	upgrade, err := h.otaService.ReportDeviceUpgradeStatus(c.Request.Context(), upgradeID, req.Status, req.Phase, req.Progress, errorMsg)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, upgrade)
}
