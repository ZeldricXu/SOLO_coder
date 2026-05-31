package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/gasestimator/platform/internal/business/address"
	"github.com/gasestimator/platform/internal/business/bridge"
	"github.com/gasestimator/platform/internal/business/chain"
	"github.com/gasestimator/platform/internal/business/event"
	"github.com/gasestimator/platform/internal/business/gas"
	"github.com/gasestimator/platform/internal/business/multisig"
	"github.com/gasestimator/platform/internal/business/tx"
	"github.com/gasestimator/platform/internal/business/zkp"
	"github.com/gasestimator/platform/internal/domain/model"
)

type Handler struct {
	zkpService      *zkp.Service
	txService       *tx.Service
	multisigService *multisig.Service
	eventService    *event.Service
	bridgeService   *bridge.Service
	gasService      *gas.Service
	chainService    *chain.Service
	addressService  *address.Service
}

func NewHandler(
	zkpService *zkp.Service,
	txService *tx.Service,
	multisigService *multisig.Service,
	eventService *event.Service,
	bridgeService *bridge.Service,
	gasService *gas.Service,
	chainService *chain.Service,
	addressService *address.Service,
) *Handler {
	return &Handler{
		zkpService:      zkpService,
		txService:       txService,
		multisigService: multisigService,
		eventService:    eventService,
		bridgeService:   bridgeService,
		gasService:      gasService,
		chainService:    chainService,
		addressService:  addressService,
	}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	api := r.Group("/api/v1")
	{
		h.registerZKPRoutes(api)
		h.registerTxRoutes(api)
		h.registerMultisigRoutes(api)
		h.registerEventRoutes(api)
		h.registerBridgeRoutes(api)
		h.registerGasRoutes(api)
		h.registerChainRoutes(api)
		h.registerAddressRoutes(api)
	}
}

func (h *Handler) registerZKPRoutes(api *gin.RouterGroup) {
	zkp := api.Group("/zkp")
	{
		zkp.POST("/verify", h.VerifyZKP)
		zkp.GET("/tasks/:id/status", h.GetZKPTaskStatus)
		zkp.GET("/pool-stats", h.GetZKPPoolStats)
		zkp.GET("/:id", h.GetZKPProof)
		zkp.GET("", h.ListZKPProofs)
	}
}

func (h *Handler) registerTxRoutes(api *gin.RouterGroup) {
	tx := api.Group("/transactions")
	{
		tx.POST("", h.CreateTransaction)
		tx.POST("/:id/sign", h.SignTransaction)
		tx.POST("/:id/submit", h.SubmitTransaction)
		tx.POST("/:id/optimize-gas", h.OptimizeGas)
		tx.POST("/:id/invalidate-cache", h.InvalidateTxCache)
		tx.GET("/cache-stats", h.GetTxCacheStats)
		tx.POST("/cache/warm-up", h.WarmUpTxCache)
		tx.GET("/:id", h.GetTransaction)
		tx.GET("", h.ListTransactions)
	}
}

func (h *Handler) registerMultisigRoutes(api *gin.RouterGroup) {
	ms := api.Group("/multisig")
	{
		ms.POST("/proposals", h.CreateMultisigProposal)
		ms.POST("/proposals/:id/approve", h.ApproveMultisigProposal)
		ms.POST("/proposals/:id/execute", h.ExecuteMultisigProposal)
		ms.GET("/scheduler-stats", h.GetMultisigSchedulerStats)
		ms.GET("/proposals/:id", h.GetMultisigProposal)
		ms.GET("/proposals", h.ListMultisigProposals)
	}
}

func (h *Handler) registerEventRoutes(api *gin.RouterGroup) {
	evt := api.Group("/events")
	{
		evt.GET("/:id", h.GetContractEvent)
		evt.GET("", h.ListContractEvents)
		evt.POST("/process-unprocessed", h.ProcessUnprocessedEvents)
	}
}

func (h *Handler) registerBridgeRoutes(api *gin.RouterGroup) {
	br := api.Group("/bridge")
	{
		br.POST("/lock", h.LockAsset)
		br.POST("/mint", h.MintAsset)
		br.GET("/transfers/:id", h.GetCrossChainTransfer)
		br.GET("/transfers/:id/verify-atomicity", h.VerifyAtomicity)
		br.GET("/transfers", h.ListCrossChainTransfers)
	}
}

func (h *Handler) registerGasRoutes(api *gin.RouterGroup) {
	gs := api.Group("/gas")
	{
		gs.POST("/estimate", h.EstimateGas)
		gs.GET("/prices", h.GetGasPrices)
		gs.GET("/:id", h.GetGasEstimate)
	}
}

func (h *Handler) registerChainRoutes(api *gin.RouterGroup) {
	ch := api.Group("/chain")
	{
		ch.GET("/block-number", h.GetBlockNumber)
		ch.GET("/blocks/:number", h.GetBlock)
		ch.GET("/transactions/:hash", h.GetChainTransaction)
		ch.POST("/submit-tx", h.SubmitChainTransaction)
		ch.GET("/balance/:address", h.GetBalance)
		ch.GET("/nodes", h.ListChainNodes)
		ch.POST("/nodes", h.AddChainNode)
	}
}

func (h *Handler) registerAddressRoutes(api *gin.RouterGroup) {
	addr := api.Group("/address")
	{
		addr.POST("/wallets", h.CreateWallet)
		addr.GET("/wallets", h.ListWallets)
		addr.GET("/wallets/:id", h.GetWallet)
		addr.POST("/derive", h.DeriveAddress)
		addr.GET("/derived/:id", h.GetDerivedAddress)
		addr.GET("/wallets/:walletId/addresses", h.ListAddressesByWallet)
		addr.PUT("/derived/:id/labels", h.UpdateAddressLabels)
	}
}

func parsePagination(c *gin.Context) (limit, offset int) {
	limit, _ = strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ = strconv.Atoi(c.DefaultQuery("offset", "0"))
	if limit > 100 {
		limit = 100
	}
	if limit < 1 {
		limit = 20
	}
	return
}

func (h *Handler) VerifyZKP(c *gin.Context) {
	var req zkp.VerifyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	resp, err := h.zkpService.Verify(c.Request.Context(), &req)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": resp})
}

func (h *Handler) GetZKPProof(c *gin.Context) {
	id := c.Param("id")
	proof, err := h.zkpService.GetByID(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": proof})
}

func (h *Handler) ListZKPProofs(c *gin.Context) {
	circuitID := c.Query("circuit_id")
	limit, offset := parsePagination(c)
	var verified *bool
	if v := c.Query("verified"); v != "" {
		b := v == "true"
		verified = &b
	}
	proofs, total, err := h.zkpService.List(c.Request.Context(), circuitID, verified, limit, offset)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": proofs, "total": total})
}

func (h *Handler) CreateTransaction(c *gin.Context) {
	var req tx.CreateTransactionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	tx, err := h.txService.CreateTransaction(c.Request.Context(), &req)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": tx})
}

func (h *Handler) SignTransaction(c *gin.Context) {
	var req tx.SignRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	req.TxID = c.Param("id")
	if err := h.txService.SignTransaction(c.Request.Context(), &req); err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "signature added"})
}

func (h *Handler) SubmitTransaction(c *gin.Context) {
	id := c.Param("id")
	txHash, err := h.txService.Submit(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": gin.H{"tx_hash": txHash}})
}

func (h *Handler) OptimizeGas(c *gin.Context) {
	id := c.Param("id")
	if err := h.txService.OptimizeGas(c.Request.Context(), id); err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "gas optimized"})
}

func (h *Handler) GetTransaction(c *gin.Context) {
	id := c.Param("id")
	tx, err := h.txService.GetByID(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": tx})
}

func (h *Handler) ListTransactions(c *gin.Context) {
	chainID := c.Query("chain_id")
	address := c.Query("address")
	status := c.Query("status")
	limit, offset := parsePagination(c)
	txs, total, err := h.txService.List(c.Request.Context(), chainID, address, status, limit, offset)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": txs, "total": total})
}

func (h *Handler) CreateMultisigProposal(c *gin.Context) {
	var req multisig.CreateProposalRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	proposal, err := h.multisigService.CreateProposal(c.Request.Context(), &req)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": proposal})
}

func (h *Handler) ApproveMultisigProposal(c *gin.Context) {
	var req multisig.ApproveProposalRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	req.ProposalID = c.Param("id")
	proposal, err := h.multisigService.ApproveProposal(c.Request.Context(), &req)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": proposal})
}

func (h *Handler) ExecuteMultisigProposal(c *gin.Context) {
	id := c.Param("id")
	txID, err := h.multisigService.ExecuteProposal(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": gin.H{"transaction_id": txID}})
}

func (h *Handler) GetMultisigProposal(c *gin.Context) {
	id := c.Param("id")
	proposal, err := h.multisigService.GetByID(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": proposal})
}

func (h *Handler) ListMultisigProposals(c *gin.Context) {
	walletID := c.Query("wallet_id")
	status := c.Query("status")
	proposals, err := h.multisigService.ListByWalletID(c.Request.Context(), walletID, status)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": proposals})
}

func (h *Handler) GetContractEvent(c *gin.Context) {
	id := c.Param("id")
	evt, err := h.eventService.GetByID(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": evt})
}

func (h *Handler) ListContractEvents(c *gin.Context) {
	chainID := c.Query("chain_id")
	contractAddress := c.Query("contract_address")
	eventName := c.Query("event_name")
	limit, offset := parsePagination(c)
	events, total, err := h.eventService.List(c.Request.Context(), chainID, contractAddress, eventName, limit, offset)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": events, "total": total})
}

func (h *Handler) ProcessUnprocessedEvents(c *gin.Context) {
	if err := h.eventService.ProcessUnprocessed(c.Request.Context()); err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "processed"})
}

func (h *Handler) LockAsset(c *gin.Context) {
	var req bridge.LockAssetRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	transfer, err := h.bridgeService.LockAsset(c.Request.Context(), &req)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": transfer})
}

func (h *Handler) MintAsset(c *gin.Context) {
	var req bridge.MintAssetRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	transfer, err := h.bridgeService.MintAsset(c.Request.Context(), &req)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": transfer})
}

func (h *Handler) GetCrossChainTransfer(c *gin.Context) {
	id := c.Param("id")
	transfer, err := h.bridgeService.GetByID(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": transfer})
}

func (h *Handler) VerifyAtomicity(c *gin.Context) {
	id := c.Param("id")
	verified, err := h.bridgeService.VerifyAtomicity(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": gin.H{"verified": verified}})
}

func (h *Handler) ListCrossChainTransfers(c *gin.Context) {
	status := c.Query("status")
	limit, offset := parsePagination(c)
	transfers, total, err := h.bridgeService.List(c.Request.Context(), status, limit, offset)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": transfers, "total": total})
}

func (h *Handler) EstimateGas(c *gin.Context) {
	var req gas.EstimateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	estimate, err := h.gasService.EstimateDetailed(c.Request.Context(), &req)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": estimate})
}

func (h *Handler) GetGasPrices(c *gin.Context) {
	chainID := c.Query("chain_id")
	prices, err := h.gasService.GetLatestPrices(c.Request.Context(), chainID)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": prices})
}

func (h *Handler) GetGasEstimate(c *gin.Context) {
	id := c.Param("id")
	est, err := h.gasService.GetByID(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": est})
}

func (h *Handler) GetBlockNumber(c *gin.Context) {
	chainID := c.Query("chain_id")
	block, err := h.chainService.GetBlockNumber(c.Request.Context(), chainID)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": gin.H{"block_number": block}})
}

func (h *Handler) GetBlock(c *gin.Context) {
	chainID := c.Query("chain_id")
	numStr := c.Param("number")
	num, _ := strconv.ParseUint(numStr, 10, 64)
	block, err := h.chainService.GetBlock(c.Request.Context(), chainID, num)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": block})
}

func (h *Handler) GetChainTransaction(c *gin.Context) {
	chainID := c.Query("chain_id")
	hash := c.Param("hash")
	tx, err := h.chainService.GetTransaction(c.Request.Context(), chainID, hash)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": tx})
}

func (h *Handler) SubmitChainTransaction(c *gin.Context) {
	var req chain.SubmitTxRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	txHash, err := h.chainService.SubmitTransaction(c.Request.Context(), &req)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": gin.H{"tx_hash": txHash}})
}

func (h *Handler) GetBalance(c *gin.Context) {
	chainID := c.Query("chain_id")
	addr := c.Param("address")
	balance, err := h.chainService.GetBalance(c.Request.Context(), chainID, addr)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": gin.H{"balance": balance}})
}

func (h *Handler) ListChainNodes(c *gin.Context) {
	chainID := c.Query("chain_id")
	nodes, err := h.chainService.GetNodes(c.Request.Context(), chainID)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": nodes})
}

func (h *Handler) AddChainNode(c *gin.Context) {
	var node model.ChainRPCNode
	if err := c.ShouldBindJSON(&node); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	if err := h.chainService.AddNode(c.Request.Context(), &node); err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "node added", "data": node})
}

func (h *Handler) CreateWallet(c *gin.Context) {
	var req address.CreateWalletRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	wallet, err := h.addressService.CreateWallet(c.Request.Context(), &req)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": wallet})
}

func (h *Handler) ListWallets(c *gin.Context) {
	userID := c.Query("user_id")
	wallets, err := h.addressService.ListWallets(c.Request.Context(), userID)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": wallets})
}

func (h *Handler) GetWallet(c *gin.Context) {
	id := c.Param("id")
	wallet, err := h.addressService.GetWallet(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": wallet})
}

func (h *Handler) DeriveAddress(c *gin.Context) {
	var req address.DeriveAddressRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	addr, err := h.addressService.DeriveAddress(c.Request.Context(), &req)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": addr})
}

func (h *Handler) GetDerivedAddress(c *gin.Context) {
	id := c.Param("id")
	addr, err := h.addressService.GetAddress(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": addr})
}

func (h *Handler) ListAddressesByWallet(c *gin.Context) {
	walletID := c.Param("walletId")
	chainID := c.Query("chain_id")
	addrs, err := h.addressService.ListAddressesByWallet(c.Request.Context(), walletID, chainID)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": addrs})
}

func (h *Handler) UpdateAddressLabels(c *gin.Context) {
	id := c.Param("id")
	var body struct {
		Labels []string `json:"labels"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
		return
	}
	addr, err := h.addressService.UpdateAddressLabels(c.Request.Context(), id, body.Labels)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": addr})
}

func (h *Handler) GetZKPTaskStatus(c *gin.Context) {
	id := c.Param("id")
	status, err := h.zkpService.GetTaskStatus(c.Request.Context(), id)
	if err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": status})
}

func (h *Handler) GetZKPPoolStats(c *gin.Context) {
	stats := h.zkpService.GetPoolStats()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": stats})
}

func (h *Handler) GetTxCacheStats(c *gin.Context) {
	stats := h.txService.GetCacheStats()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": stats})
}

func (h *Handler) InvalidateTxCache(c *gin.Context) {
	id := c.Param("id")
	h.txService.InvalidateCache(c.Request.Context(), id)
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "cache invalidated"})
}

func (h *Handler) WarmUpTxCache(c *gin.Context) {
	if err := h.txService.WarmUpCache(c.Request.Context()); err != nil {
		c.Error(err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "cache warm-up initiated"})
}

func (h *Handler) GetMultisigSchedulerStats(c *gin.Context) {
	stats := h.multisigService.GetSchedulerStats()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": stats})
}
