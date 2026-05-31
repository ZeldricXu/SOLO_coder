package handler

import (
	"github.com/edgevision/edgevision/internal/service"
	"github.com/edgevision/edgevision/pkg/errors"
	"github.com/gin-gonic/gin"
)

type DeviceHandler struct {
	deviceService *service.DeviceService
}

func NewDeviceHandler(deviceService *service.DeviceService) *DeviceHandler {
	return &DeviceHandler{
		deviceService: deviceService,
	}
}

func (h *DeviceHandler) Register(c *gin.Context) {
	var req service.RegisterDeviceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	device, err := h.deviceService.Register(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, device)
}

func (h *DeviceHandler) Activate(c *gin.Context) {
	deviceID := c.Param("id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	device, err := h.deviceService.Activate(c.Request.Context(), deviceID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, device)
}

func (h *DeviceHandler) Deactivate(c *gin.Context) {
	deviceID := c.Param("id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	device, err := h.deviceService.Deactivate(c.Request.Context(), deviceID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, device)
}

func (h *DeviceHandler) GetByID(c *gin.Context) {
	deviceID := c.Param("id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	device, err := h.deviceService.GetByID(c.Request.Context(), deviceID)
	if err != nil {
		Error(c, errors.NotFound("Device not found"))
		return
	}

	Success(c, device)
}

func (h *DeviceHandler) List(c *gin.Context) {
	page, pageSize := GetPagination(c)
	status := c.Query("status")
	deviceType := c.Query("type")

	devices, total, err := h.deviceService.List(c.Request.Context(), page, pageSize, status, deviceType)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, devices, total, page, pageSize)
}

func (h *DeviceHandler) Update(c *gin.Context) {
	deviceID := c.Param("id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	device, err := h.deviceService.Update(c.Request.Context(), deviceID, updates)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, device)
}

func (h *DeviceHandler) Delete(c *gin.Context) {
	deviceID := c.Param("id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	if err := h.deviceService.Delete(c.Request.Context(), deviceID); err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, gin.H{"message": "Device deleted successfully"})
}

func (h *DeviceHandler) Heartbeat(c *gin.Context) {
	deviceID := c.Param("id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	if err := h.deviceService.Heartbeat(c.Request.Context(), deviceID); err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, gin.H{"message": "Heartbeat received"})
}

func (h *DeviceHandler) GetEvents(c *gin.Context) {
	deviceID := c.Param("id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	page, pageSize := GetPagination(c)

	events, total, err := h.deviceService.GetEvents(c.Request.Context(), deviceID, page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, events, total, page, pageSize)
}

func (h *DeviceHandler) Batch(c *gin.Context) {
	var req struct {
		Operations []struct {
			Action string                 `json:"action"`
			ID     string                 `json:"id"`
			Params map[string]interface{} `json:"params"`
		} `json:"operations"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	results := make([]gin.H, 0, len(req.Operations))

	for _, op := range req.Operations {
		result := gin.H{"id": op.ID, "action": op.Action}

		switch op.Action {
		case "activate":
			device, err := h.deviceService.Activate(c.Request.Context(), op.ID)
			if err != nil {
				result["status"] = "failed"
				result["error"] = err.Error()
			} else {
				result["status"] = "success"
				result["data"] = device
			}
		case "deactivate":
			device, err := h.deviceService.Deactivate(c.Request.Context(), op.ID)
			if err != nil {
				result["status"] = "failed"
				result["error"] = err.Error()
			} else {
				result["status"] = "success"
				result["data"] = device
			}
		case "delete":
			err := h.deviceService.Delete(c.Request.Context(), op.ID)
			if err != nil {
				result["status"] = "failed"
				result["error"] = err.Error()
			} else {
				result["status"] = "success"
			}
		default:
			result["status"] = "failed"
			result["error"] = "Unknown action"
		}

		results = append(results, result)
	}

	Success(c, gin.H{
		"batch_id": "batch_" + c.GetString("trace_id"),
		"results":  results,
	})
}
