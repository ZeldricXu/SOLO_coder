package lifecycle

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

type LifecycleHandler struct {
	manager *Manager
}

func NewLifecycleHandler(manager *Manager) *LifecycleHandler {
	return &LifecycleHandler{manager: manager}
}

type RegisterRequest struct {
	Name            string                 `json:"name" binding:"required"`
	Type            string                 `json:"type" binding:"required"`
	Model           string                 `json:"model" binding:"required"`
	SerialNumber    string                 `json:"serial_number" binding:"required"`
	IPAddress       string                 `json:"ip_address"`
	FirmwareVersion string                 `json:"firmware_version"`
	Location        string                 `json:"location"`
	Tags            map[string]string      `json:"tags"`
	Attributes      map[string]interface{} `json:"attributes"`
}

func (h *LifecycleHandler) Register(c *gin.Context) {
	var req RegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	device := &DeviceInfo{
		Name:            req.Name,
		Type:            req.Type,
		Model:           req.Model,
		SerialNumber:    req.SerialNumber,
		IPAddress:       req.IPAddress,
		FirmwareVersion: req.FirmwareVersion,
		Location:        req.Location,
		Tags:            req.Tags,
		Attributes:      req.Attributes,
	}
	deviceID, secret, err := h.manager.Register(device)
	if err != nil {
		c.JSON(http.StatusConflict, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, gin.H{
		"device_id": deviceID,
		"secret":    secret,
		"message":   "device registered successfully",
	})
}

type ActivateRequest struct {
	DeviceID string `json:"device_id" binding:"required"`
	Secret   string `json:"secret" binding:"required"`
}

func (h *LifecycleHandler) Activate(c *gin.Context) {
	var req ActivateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	result, err := h.manager.Activate(req.DeviceID, req.Secret)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, result)
}

type HeartbeatRequest struct {
	DeviceID string `json:"device_id" binding:"required"`
	Token    string `json:"token" binding:"required"`
}

func (h *LifecycleHandler) Heartbeat(c *gin.Context) {
	var req HeartbeatRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if !h.manager.Authenticate(req.DeviceID, req.Token) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
		return
	}
	if err := h.manager.Heartbeat(req.DeviceID); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "heartbeat received"})
}

func (h *LifecycleHandler) GetDevice(c *gin.Context) {
	id := c.Param("id")
	device, exists := h.manager.Get(id)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
		return
	}
	c.JSON(http.StatusOK, device)
}

func (h *LifecycleHandler) ListDevices(c *gin.Context) {
	devices := h.manager.List()
	c.JSON(http.StatusOK, devices)
}

func (h *LifecycleHandler) UpdateDevice(c *gin.Context) {
	id := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if !h.manager.Update(id, updates) {
		c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "device updated successfully"})
}

type SetStatusRequest struct {
	Status string `json:"status" binding:"required"`
}

func (h *LifecycleHandler) SetStatus(c *gin.Context) {
	id := c.Param("id")
	var req SetStatusRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if !h.manager.SetStatus(id, DeviceStatus(req.Status)) {
		c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "device status updated"})
}

func (h *LifecycleHandler) Decommission(c *gin.Context) {
	id := c.Param("id")
	if !h.manager.Decommission(id) {
		c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "device decommissioned"})
}

func (h *LifecycleHandler) GetStats(c *gin.Context) {
	stats := h.manager.GetStats()
	c.JSON(http.StatusOK, stats)
}
