package handlers

import (
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/solocoder/session147/internal/common/eventbus"
	"github.com/solocoder/session147/internal/gasestimator/domain"
)

func (h *Handler) EstimateGas(c *gin.Context) {
	var req domain.EstimateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	result, err := h.gasSvc.EstimateGas(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.success(c, result)
}

func (h *Handler) GetGasPrice(c *gin.Context) {
	chainID, _ := strconv.ParseInt(c.Param("chain_id"), 10, 64)
	estimate, err := h.gasSvc.GetCurrentGasPrice(c.Request.Context(), chainID)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, estimate)
}

func (h *Handler) GetGasHistory(c *gin.Context) {
	chainID, _ := strconv.ParseInt(c.Param("chain_id"), 10, 64)
	timeWindow := c.DefaultQuery("window", "1h")

	stats, err := h.gasSvc.GetHistoricalStats(c.Request.Context(), chainID, timeWindow)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, stats)
}

func (h *Handler) SetAlertThreshold(c *gin.Context) {
	var req struct {
		AlertType string  `json:"alert_type" binding:"required"`
		Threshold float64 `json:"threshold" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	h.gasSvc.SetAlertThreshold(req.AlertType, req.Threshold)
	h.success(c, gin.H{
		"alert_type": req.AlertType,
		"threshold":  req.Threshold,
		"status":     "updated",
	})
}

func (h *Handler) RegisterNotificationChannel(c *gin.Context) {
	var req eventbus.NotificationChannel
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	if req.ID == "" {
		req.ID = "channel_" + strconv.FormatInt(int64(len(h.eventBus.ListChannels())+1), 10)
	}

	h.gasSvc.RegisterNotificationChannel(&req)
	h.success(c, gin.H{
		"channel_id": req.ID,
		"name":       req.Name,
		"status":     "registered",
	})
}
