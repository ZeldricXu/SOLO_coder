package handlers

import (
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/solocoder/session147/internal/txbuilder/domain"
)

func (h *Handler) BuildTransaction(c *gin.Context) {
	var req domain.BuildRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	tx, err := h.txSvc.BuildTransaction(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.created(c, tx)
}

func (h *Handler) SignTransaction(c *gin.Context) {
	var req domain.SignRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	tx, err := h.txSvc.SignTransaction(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.success(c, tx)
}

func (h *Handler) BroadcastTransaction(c *gin.Context) {
	id := c.Param("id")
	result, err := h.txSvc.BroadcastTransaction(c.Request.Context(), id)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.success(c, result)
}

func (h *Handler) GetTransaction(c *gin.Context) {
	id := c.Param("id")
	tx, err := h.txSvc.GetTransaction(c.Request.Context(), id)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, tx)
}

func (h *Handler) ListTransactions(c *gin.Context) {
	page, pageSize := getPagination(c)
	filter := getFilter(c)

	txs, total, err := h.txSvc.ListTransactions(c.Request.Context(), filter, page, pageSize)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.paginated(c, txs, page, pageSize, total)
}

func (h *Handler) CancelTransaction(c *gin.Context) {
	id := c.Param("id")
	if err := h.txSvc.CancelTransaction(c.Request.Context(), id); err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, gin.H{"status": "cancelled"})
}

func (h *Handler) ListPlugins(c *gin.Context) {
	plugins := h.txSvc.ListPlugins()
	h.success(c, plugins)
}

func (h *Handler) EnablePlugin(c *gin.Context) {
	pluginID := c.Param("id")
	if err := h.txSvc.EnablePlugin(pluginID); err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, gin.H{
		"plugin_id": pluginID,
		"status":    "enabled",
	})
}

func (h *Handler) DisablePlugin(c *gin.Context) {
	pluginID := c.Param("id")
	if err := h.txSvc.DisablePlugin(pluginID); err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, gin.H{
		"plugin_id": pluginID,
		"status":    "disabled",
	})
}
