package handlers

import (
	"strconv"

	"github.com/gin-gonic/gin"
	indexerdomain "github.com/solocoder/session147/internal/indexer/domain"
	zkpdomain "github.com/solocoder/session147/internal/zkp/domain"
	hdwalldomain "github.com/solocoder/session147/internal/hdwallet/domain"
	storagedomain "github.com/solocoder/session147/internal/storage/domain"
	eventdomain "github.com/solocoder/session147/internal/eventlistener/domain"
	bridgedomain "github.com/solocoder/session147/internal/bridge/domain"
)

func (h *Handler) StartIndexer(c *gin.Context) {
	var req indexerdomain.IndexConfig
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	if err := h.indexerSvc.Start(c.Request.Context(), &req); err != nil {
		h.handleError(c, err)
		return
	}

	h.success(c, gin.H{"status": "started"})
}

func (h *Handler) StopIndexer(c *gin.Context) {
	chainID, _ := strconv.ParseInt(c.Param("chain_id"), 10, 64)
	if err := h.indexerSvc.Stop(c.Request.Context(), chainID); err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, gin.H{"status": "stopped"})
}

func (h *Handler) GetIndexerStatus(c *gin.Context) {
	chainID, _ := strconv.ParseInt(c.Param("chain_id"), 10, 64)
	status, err := h.indexerSvc.GetIndexStatus(c.Request.Context(), chainID)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, status)
}

func (h *Handler) GetBlock(c *gin.Context) {
	chainID, _ := strconv.ParseInt(c.Param("chain_id"), 10, 64)
	blockNum, _ := strconv.ParseUint(c.Param("block_num"), 10, 64)

	block, err := h.indexerSvc.GetBlock(c.Request.Context(), chainID, blockNum)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, block)
}

func (h *Handler) VerifyProof(c *gin.Context) {
	var req zkpdomain.VerifyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	result, err := h.zkpSvc.VerifyProof(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.success(c, result)
}

func (h *Handler) RegisterCircuit(c *gin.Context) {
	var circuit zkpdomain.Circuit
	if err := c.ShouldBindJSON(&circuit); err != nil {
		h.handleError(c, err)
		return
	}

	result, err := h.zkpSvc.RegisterCircuit(c.Request.Context(), &circuit)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.created(c, result)
}

func (h *Handler) GetProof(c *gin.Context) {
	id := c.Param("id")
	proof, err := h.zkpSvc.GetProof(c.Request.Context(), id)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, proof)
}

func (h *Handler) CreateHDWallet(c *gin.Context) {
	var req struct {
		Name     string `json:"name" binding:"required"`
		Password string `json:"password"`
		CoinType int    `json:"coin_type" binding:"required"`
		Network  string `json:"network"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	wallet, err := h.hdSvc.CreateWallet(c.Request.Context(), req.Name, req.Password, req.CoinType, req.Network)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.created(c, wallet)
}

func (h *Handler) DeriveAddresses(c *gin.Context) {
	var req hdwalldomain.DeriveAddressRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	addresses, err := h.hdSvc.DeriveAddresses(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.success(c, addresses)
}

func (h *Handler) AddAddressBook(c *gin.Context) {
	var req hdwalldomain.AddAddressBookRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	entry, err := h.hdSvc.AddAddressBookEntry(c.Request.Context(), &req, c.GetString("user_id"))
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.created(c, entry)
}

func (h *Handler) StoreContent(c *gin.Context) {
	var req storagedomain.StoreRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	result, err := h.storageSvc.Store(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.created(c, result)
}

func (h *Handler) RetrieveContent(c *gin.Context) {
	var req storagedomain.RetrieveRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	data, err := h.storageSvc.Retrieve(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	c.Data(200, "application/octet-stream", data)
}

func (h *Handler) PinContent(c *gin.Context) {
	var req storagedomain.PinRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	op, err := h.storageSvc.Pin(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.success(c, op)
}

func (h *Handler) CreateSubscription(c *gin.Context) {
	var req eventdomain.CreateSubscriptionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	sub, err := h.eventSvc.CreateSubscription(c.Request.Context(), &req, c.GetString("user_id"))
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.created(c, sub)
}

func (h *Handler) ListSubscriptions(c *gin.Context) {
	page, pageSize := getPagination(c)
	filter := getFilter(c)

	subs, total, err := h.eventSvc.ListSubscriptions(c.Request.Context(), filter, page, pageSize)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.paginated(c, subs, page, pageSize, total)
}

func (h *Handler) PauseSubscription(c *gin.Context) {
	id := c.Param("id")
	if err := h.eventSvc.PauseSubscription(c.Request.Context(), id); err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, gin.H{"status": "paused"})
}

func (h *Handler) ResumeSubscription(c *gin.Context) {
	id := c.Param("id")
	if err := h.eventSvc.ResumeSubscription(c.Request.Context(), id); err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, gin.H{"status": "resumed"})
}

func (h *Handler) DeleteSubscription(c *gin.Context) {
	id := c.Param("id")
	if err := h.eventSvc.DeleteSubscription(c.Request.Context(), id); err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, gin.H{"status": "deleted"})
}

func (h *Handler) GetChainBlock(c *gin.Context) {
	chainID, _ := strconv.ParseInt(c.Param("chain_id"), 10, 64)
	blockNum, _ := strconv.ParseUint(c.Param("block_num"), 10, 64)

	block, err := h.chainSvc.GetBlock(c.Request.Context(), chainID, blockNum)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, block)
}

func (h *Handler) GetChainBalance(c *gin.Context) {
	chainID, _ := strconv.ParseInt(c.Param("chain_id"), 10, 64)
	address := c.Param("address")

	balance, err := h.chainSvc.GetBalance(c.Request.Context(), chainID, address)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, balance)
}

func (h *Handler) GetChainTx(c *gin.Context) {
	chainID, _ := strconv.ParseInt(c.Param("chain_id"), 10, 64)
	hash := c.Param("hash")

	tx, err := h.chainSvc.GetTransaction(c.Request.Context(), chainID, hash)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, tx)
}

func (h *Handler) ListChains(c *gin.Context) {
	chains := h.chainSvc.ListChains()
	h.success(c, chains)
}

func (h *Handler) InitiateBridge(c *gin.Context) {
	var req bridgedomain.BridgeRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, err)
		return
	}

	tx, err := h.bridgeSvc.InitiateBridge(c.Request.Context(), &req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.created(c, tx)
}

func (h *Handler) GetBridgeTx(c *gin.Context) {
	id := c.Param("id")
	tx, err := h.bridgeSvc.GetTransaction(c.Request.Context(), id)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, tx)
}

func (h *Handler) ListBridgeTxs(c *gin.Context) {
	page, pageSize := getPagination(c)
	filter := getFilter(c)

	txs, total, err := h.bridgeSvc.ListTransactions(c.Request.Context(), filter, page, pageSize)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.paginated(c, txs, page, pageSize, total)
}

func (h *Handler) RefundBridge(c *gin.Context) {
	id := c.Param("id")
	if err := h.bridgeSvc.RefundTransaction(c.Request.Context(), id); err != nil {
		h.handleError(c, err)
		return
	}
	h.success(c, gin.H{"status": "refunded"})
}
