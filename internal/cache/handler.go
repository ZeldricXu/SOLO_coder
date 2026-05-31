package cache

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

type CacheHandler struct {
	manager *Manager
}

func NewCacheHandler(manager *Manager) *CacheHandler {
	return &CacheHandler{manager: manager}
}

type StoreRequest struct {
	Data      interface{} `json:"data" binding:"required"`
	DataType  string      `json:"data_type" binding:"required"`
	DeviceID  string      `json:"device_id" binding:"required"`
	TTLSeconds int64      `json:"ttl_seconds"`
}

func (h *CacheHandler) Store(c *gin.Context) {
	var req StoreRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	id, err := h.manager.Store(req.Data, req.DataType, req.DeviceID, req.TTLSeconds)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, gin.H{
		"id":      id,
		"message": "data cached successfully",
	})
}

func (h *CacheHandler) Get(c *gin.Context) {
	id := c.Param("id")
	entry, exists := h.manager.Get(id)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "entry not found or expired"})
		return
	}
	c.JSON(http.StatusOK, entry)
}

func (h *CacheHandler) Delete(c *gin.Context) {
	id := c.Param("id")
	if err := h.manager.Delete(id); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "entry deleted successfully"})
}

func (h *CacheHandler) ListByDevice(c *gin.Context) {
	deviceID := c.Param("device_id")
	entries := h.manager.ListByDevice(deviceID)
	c.JSON(http.StatusOK, entries)
}

func (h *CacheHandler) GetStats(c *gin.Context) {
	stats := h.manager.GetStats()
	c.JSON(http.StatusOK, stats)
}

type SetNetworkStatusRequest struct {
	Online bool `json:"online" binding:"required"`
}

func (h *CacheHandler) SetNetworkStatus(c *gin.Context) {
	var req SetNetworkStatusRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	h.manager.SetNetworkStatus(req.Online)
	c.JSON(http.StatusOK, gin.H{
		"message": "network status updated",
		"online":  req.Online,
	})
}
