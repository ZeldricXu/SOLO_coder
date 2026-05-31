package protocol

import (
	"net/http"
	"time"

	"github.com/edgevision/edgevision/internal/common/utils"
	"github.com/gin-gonic/gin"
)

type ProtocolHandler struct {
	adapter *Adapter
}

func NewProtocolHandler(adapter *Adapter) *ProtocolHandler {
	return &ProtocolHandler{adapter: adapter}
}

type ConnectDriverRequest struct {
	Protocol string                 `json:"protocol" binding:"required"`
	Config   map[string]interface{} `json:"config" binding:"required"`
}

func (h *ProtocolHandler) ConnectDriver(c *gin.Context) {
	var req ConnectDriverRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	err := h.adapter.ConnectDriver(ProtocolType(req.Protocol), req.Config)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "driver connected successfully"})
}

func (h *ProtocolHandler) ListDrivers(c *gin.Context) {
	drivers := h.adapter.ListDrivers()
	c.JSON(http.StatusOK, gin.H{"drivers": drivers})
}

type ConvertAsyncRequest struct {
	Protocol     string                 `json:"protocol" binding:"required"`
	SourceDevice string                 `json:"source_device"`
	RawPayload   string                 `json:"raw_payload" binding:"required"`
	Metadata     map[string]interface{} `json:"metadata"`
	CallbackURL  string                 `json:"callback_url"`
}

type ConvertAsyncResponse struct {
	TaskID string `json:"task_id"`
	Status string `json:"status"`
}

func (h *ProtocolHandler) ConvertAsync(c *gin.Context) {
	var req ConvertAsyncRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	rawData := RawData{
		DataID:       utils.GenerateID("data"),
		Protocol:     ProtocolType(req.Protocol),
		SourceDevice: req.SourceDevice,
		RawPayload:   []byte(req.RawPayload),
		ReceivedAt:   time.Now().UTC(),
		Metadata:     req.Metadata,
	}
	var callback func(StandardizedData, error)
	if req.CallbackURL != "" {
		callback = func(data StandardizedData, err error) {
		}
	}
	taskID := h.adapter.ConvertAsync(rawData, callback)
	c.JSON(http.StatusAccepted, ConvertAsyncResponse{
		TaskID: taskID,
		Status: "queued",
	})
}

func (h *ProtocolHandler) GetConversionStatus(c *gin.Context) {
	taskID := c.Param("id")
	task, exists := h.adapter.GetTaskStatus(taskID)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "task not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"task_id":     task.TaskID,
		"status":      task.Status,
		"created_at":  task.CreatedAt,
		"completed_at": task.CompletedAt,
		"result":      task.Result,
		"error":       task.Error,
	})
}

type ReadAsyncRequest struct {
	Protocol string `json:"protocol" binding:"required"`
	Address  string `json:"address" binding:"required"`
}

func (h *ProtocolHandler) ReadAsync(c *gin.Context) {
	var req ReadAsyncRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	taskID := h.adapter.ReadAsync(ProtocolType(req.Protocol), req.Address, func(result interface{}, err error) {
	})
	c.JSON(http.StatusAccepted, gin.H{
		"task_id": taskID,
		"status":  "processing",
	})
}
