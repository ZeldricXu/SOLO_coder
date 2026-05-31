package shadow

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

type ShadowHandler struct {
	manager *Manager
}

func NewShadowHandler(manager *Manager) *ShadowHandler {
	return &ShadowHandler{manager: manager}
}

func (h *ShadowHandler) GetShadow(c *gin.Context) {
	deviceID := c.Param("device_id")
	shadow, exists := h.manager.Get(deviceID)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "device shadow not found"})
		return
	}
	c.JSON(http.StatusOK, shadow)
}

type UpdateStateRequest struct {
	State map[string]interface{} `json:"state" binding:"required"`
}

func (h *ShadowHandler) UpdateReported(c *gin.Context) {
	deviceID := c.Param("device_id")
	var req UpdateStateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if err := h.manager.UpdateReported(deviceID, req.State); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "reported state updated"})
}

func (h *ShadowHandler) UpdateDesired(c *gin.Context) {
	deviceID := c.Param("device_id")
	var req UpdateStateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if err := h.manager.UpdateDesired(deviceID, req.State); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "desired state updated"})
}

func (h *ShadowHandler) GetDelta(c *gin.Context) {
	deviceID := c.Param("device_id")
	delta, exists := h.manager.GetDelta(deviceID)
	if !exists {
		c.JSON(http.StatusOK, gin.H{"delta": nil})
		return
	}
	c.JSON(http.StatusOK, gin.H{"delta": delta})
}

func (h *ShadowHandler) DeleteShadow(c *gin.Context) {
	deviceID := c.Param("device_id")
	if !h.manager.Delete(deviceID) {
		c.JSON(http.StatusNotFound, gin.H{"error": "device shadow not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "device shadow deleted"})
}

func (h *ShadowHandler) ListDevices(c *gin.Context) {
	devices := h.manager.ListDevices()
	c.JSON(http.StatusOK, gin.H{"devices": devices})
}

type MergeRequest struct {
	Patch map[string]interface{} `json:"patch" binding:"required"`
}

func (h *ShadowHandler) Merge(c *gin.Context) {
	deviceID := c.Param("device_id")
	var req MergeRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if err := h.manager.Merge(deviceID, req.Patch); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "shadow merged successfully"})
}
