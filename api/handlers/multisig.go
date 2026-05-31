package handlers

import (
	"github.com/gin-gonic/gin"
	"github.com/solocoder/session147/internal/common/routing"
	"github.com/solocoder/session147/internal/multisig/domain"
)

func (h *Handler) CreateWallet(c *gin.Context) {
	var req domain.Wallet
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	wallet, err := h.multisigSvc.CreateWallet(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.created(c, wallet)
}

func (h *Handler) GetWallet(c *gin.Context) {
	id := c.Param("id")
	wallet, err := h.multisigSvc.GetWallet(c.Request.Context(), id)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, wallet)
}

func (h *Handler) ListWallets(c *gin.Context) {
	page, pageSize := getPagination(c)
	filter := getFilter(c)

	wallets, total, err := h.multisigSvc.ListWallets(c.Request.Context(), filter, page, pageSize)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.paginated(c, wallets, page, pageSize, total)
}

func (h *Handler) CreateProposal(c *gin.Context) {
	var req domain.CreateProposalRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	proposal, err := h.multisigSvc.CreateProposal(c.Request.Context(), &req, c.GetString("user_id"))
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.created(c, proposal)
}

func (h *Handler) GetProposal(c *gin.Context) {
	id := c.Param("id")
	proposal, err := h.multisigSvc.GetProposal(c.Request.Context(), id)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, proposal)
}

func (h *Handler) ListProposals(c *gin.Context) {
	page, pageSize := getPagination(c)
	filter := getFilter(c)

	proposals, total, err := h.multisigSvc.ListProposals(c.Request.Context(), filter, page, pageSize)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.paginated(c, proposals, page, pageSize, total)
}

func (h *Handler) SignProposal(c *gin.Context) {
	var req domain.SignProposalRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	proposal, err := h.multisigSvc.SignProposal(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.success(c, proposal)
}

func (h *Handler) ExecuteProposal(c *gin.Context) {
	var req domain.ExecuteProposalRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	txHash, err := h.multisigSvc.ExecuteProposal(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.success(c, gin.H{"tx_hash": txHash})
}

func (h *Handler) CancelProposal(c *gin.Context) {
	id := c.Param("id")
	if err := h.multisigSvc.CancelProposal(c.Request.Context(), id); err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, gin.H{"status": "cancelled"})
}

func (h *Handler) SetReadWriteMode(c *gin.Context) {
	var req struct {
		Mode string `json:"mode" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	var mode routing.ReadWriteMode
	switch req.Mode {
	case "read_only":
		mode = routing.ModeReadOnly
	case "write_only":
		mode = routing.ModeWriteOnly
	case "read_write":
		mode = routing.ModeReadWrite
	default:
		h.handleError(c, routing.ErrInvalidMode)
		return
	}

	h.multisigSvc.SetReadWriteMode(mode)
	h.success(c, gin.H{"mode": req.Mode, "status": "updated"})
}

func (h *Handler) GetRoutingStatus(c *gin.Context) {
	router := h.multisigSvc.GetRouter()
	status := map[string]interface{}{
		"mode":     router.GetMode(),
		"strategy": router.GetStrategy(),
		"nodes":    router.GetNodes(),
		"stats":    router.GetStats(),
	}
	h.success(c, status)
}
