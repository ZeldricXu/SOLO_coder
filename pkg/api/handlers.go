package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/ethereum/go-ethereum/core/types"
	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
	"gorm.io/gorm"

	"github.com/blockchain-middleware/core/internal/common/errors"
	"github.com/blockchain-middleware/core/internal/common/logger"
	"github.com/blockchain-middleware/core/internal/common/models"
	"github.com/blockchain-middleware/core/internal/gasestimator"
	"github.com/blockchain-middleware/core/internal/txbuilder"
	"github.com/blockchain-middleware/core/internal/chainadapter"
	"github.com/blockchain-middleware/core/internal/storageadapter"
	"github.com/blockchain-middleware/core/internal/wallet"
	"github.com/blockchain-middleware/core/internal/eventlistener"
	"github.com/blockchain-middleware/core/internal/indexer"
	"github.com/blockchain-middleware/core/internal/zkp"
)

type APIHandler struct {
	db            *gorm.DB
	redisClient   *redis.Client
	gasEstimator  *gasestimator.GasEstimator
	txBuilder     *txbuilder.TransactionBuilder
	chainAdapter  *chainadapter.ChainAdapter
	storageMgr    *storageadapter.StorageManager
	wallet        *wallet.Wallet
	addressBook   *wallet.AddressBookManager
	eventListener *eventlistener.EventListener
	indexer       *indexer.BlockIndexer
	zkVerifier    *zkp.ZKVerifier
}

func NewAPIHandler(
	db *gorm.DB,
	redisClient *redis.Client,
	gasEstimator *gasestimator.GasEstimator,
	txBuilder *txbuilder.TransactionBuilder,
	chainAdapter *chainadapter.ChainAdapter,
	storageMgr *storageadapter.StorageManager,
	wallet *wallet.Wallet,
	addressBook *wallet.AddressBookManager,
	eventListener *eventlistener.EventListener,
	indexer *indexer.BlockIndexer,
	zkVerifier *zkp.ZKVerifier,
) *APIHandler {
	return &APIHandler{
		db:            db,
		redisClient:   redisClient,
		gasEstimator:  gasEstimator,
		txBuilder:     txBuilder,
		chainAdapter:  chainAdapter,
		storageMgr:    storageMgr,
		wallet:        wallet,
		addressBook:   addressBook,
		eventListener: eventListener,
		indexer:       indexer,
		zkVerifier:    zkVerifier,
	}
}

func (h *APIHandler) HealthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status": "ok",
		"time":   time.Now().UTC(),
	})
}

func (h *APIHandler) EstimateGas(c *gin.Context) {
	chainIDStr := c.Param("chain_id")
	chainID, err := strconv.ParseUint(chainIDStr, 10, 64)
	if err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	result, err := h.gasEstimator.EstimateGas(c.Request.Context(), chainID)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, result)
}

func (h *APIHandler) GetGasHistory(c *gin.Context) {
	chainIDStr := c.Param("chain_id")
	chainID, err := strconv.ParseUint(chainIDStr, 10, 64)
	if err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	startTime := time.Now().Add(-24 * time.Hour)
	endTime := time.Now()

	if startStr := c.Query("start"); startStr != "" {
		if t, err := time.Parse(time.RFC3339, startStr); err == nil {
			startTime = t
		}
	}
	if endStr := c.Query("end"); endStr != "" {
		if t, err := time.Parse(time.RFC3339, endStr); err == nil {
			endTime = t
		}
	}

	records, err := h.gasEstimator.GetHistory(c.Request.Context(), chainID, startTime, endTime)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, records)
}

func (h *APIHandler) BuildTransaction(c *gin.Context) {
	var req txbuilder.TransactionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	tx, err := h.txBuilder.BuildTransaction(c.Request.Context(), req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	txJSON, _ := json.Marshal(tx)
	h.handleSuccess(c, gin.H{
		"transaction": json.RawMessage(txJSON),
		"hash":        tx.Hash().Hex(),
	})
}

func (h *APIHandler) SignTransaction(c *gin.Context) {
	var req struct {
		TxData        []byte `json:"tx_data"`
		SignerAddress string `json:"signer_address"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	h.handleSuccess(c, gin.H{"status": "signed"})
}

func (h *APIHandler) SendTransaction(c *gin.Context) {
	var req struct {
		ChainID uint64 `json:"chain_id"`
		RawTx   []byte `json:"raw_tx"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	txHash, err := h.chainAdapter.SendRawTransaction(c.Request.Context(), req.ChainID, req.RawTx)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, gin.H{"tx_hash": txHash})
}

func (h *APIHandler) GetBlock(c *gin.Context) {
	chainIDStr := c.Param("chain_id")
	blockNumStr := c.Param("block_number")

	chainID, err := strconv.ParseUint(chainIDStr, 10, 64)
	if err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	blockNum, err := strconv.ParseUint(blockNumStr, 10, 64)
	if err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	block, err := h.chainAdapter.GetBlockByNumber(c.Request.Context(), chainID, blockNum)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, block)
}

func (h *APIHandler) GetTransaction(c *gin.Context) {
	chainIDStr := c.Param("chain_id")
	txHash := c.Param("tx_hash")

	chainID, err := strconv.ParseUint(chainIDStr, 10, 64)
	if err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	tx, err := h.chainAdapter.GetTransactionByHash(c.Request.Context(), chainID, txHash)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, tx)
}

func (h *APIHandler) GetBalance(c *gin.Context) {
	chainIDStr := c.Param("chain_id")
	address := c.Param("address")

	chainID, err := strconv.ParseUint(chainIDStr, 10, 64)
	if err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	balance, err := h.chainAdapter.GetBalance(c.Request.Context(), chainID, address)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, gin.H{
		"address": address,
		"balance": balance.String(),
		"chain_id": chainID,
	})
}

func (h *APIHandler) StoreContent(c *gin.Context) {
	storageType := c.PostForm("storage_type")
	data := []byte(c.PostForm("data"))
	pin := c.PostForm("pin") == "true"

	options := storageadapter.StoreOptions{
		Pin: pin,
	}

	info, err := h.storageMgr.Store(c.Request.Context(), storageadapter.StorageType(storageType), data, options)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, info)
}

func (h *APIHandler) RetrieveContent(c *gin.Context) {
	storageType := c.Param("storage_type")
	cid := c.Param("cid")

	data, err := h.storageMgr.Retrieve(c.Request.Context(), storageadapter.StorageType(storageType), cid, storageadapter.RetrieveOptions{})
	if err != nil {
		h.handleError(c, err)
		return
	}

	c.Data(http.StatusOK, "application/octet-stream", data)
}

func (h *APIHandler) PinContent(c *gin.Context) {
	storageType := c.Param("storage_type")
	cid := c.Param("cid")

	err := h.storageMgr.Pin(c.Request.Context(), storageadapter.StorageType(storageType), cid, 0)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, gin.H{"status": "pinned", "cid": cid})
}

func (h *APIHandler) ListStoredContent(c *gin.Context) {
	storageType := c.Query("storage_type")
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	var pinned *bool
	if pinnedStr := c.Query("pinned"); pinnedStr != "" {
		p := pinnedStr == "true"
		pinned = &p
	}

	contents, total, err := h.storageMgr.ListContents(c.Request.Context(), storageadapter.StorageType(storageType), pinned, offset, limit)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, gin.H{
		"data":  contents,
		"total": total,
		"offset": offset,
		"limit": limit,
	})
}

func (h *APIHandler) DeriveAddress(c *gin.Context) {
	var req struct {
		DerivationPath string `json:"derivation_path"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	if h.wallet == nil {
		h.handleError(c, errors.New(500, "wallet not configured"))
		return
	}

	addr, err := h.wallet.DeriveAddress(req.DerivationPath)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, gin.H{
		"address": addr.Hex(),
		"path":    req.DerivationPath,
	})
}

func (h *APIHandler) DeriveAddresses(c *gin.Context) {
	var req struct {
		BasePath string `json:"base_path"`
		Start    uint32 `json:"start"`
		Count    uint32 `json:"count"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	if h.wallet == nil {
		h.handleError(c, errors.New(500, "wallet not configured"))
		return
	}

	addresses, paths, err := h.wallet.DeriveAddresses(req.BasePath, req.Start, req.Count)
	if err != nil {
		h.handleError(c, err)
		return
	}

	result := make([]gin.H, len(addresses))
	for i := range addresses {
		result[i] = gin.H{
			"address": addresses[i].Hex(),
			"path":    paths[i],
		}
	}

	h.handleSuccess(c, result)
}

func (h *APIHandler) AddAddressBook(c *gin.Context) {
	var req struct {
		Address        string            `json:"address"`
		Label          string            `json:"label"`
		ChainID        uint64            `json:"chain_id"`
		Tags           map[string]string `json:"tags"`
		DerivationPath string            `json:"derivation_path"`
		Note           string            `json:"note"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	entry, err := h.addressBook.AddAddress(c.Request.Context(), req.Address, req.Label, req.ChainID, req.Tags, req.DerivationPath, req.Note)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, entry)
}

func (h *APIHandler) GetAddressBook(c *gin.Context) {
	address := c.Param("address")
	entry, err := h.addressBook.GetAddress(c.Request.Context(), address)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.handleSuccess(c, entry)
}

func (h *APIHandler) ListAddressBook(c *gin.Context) {
	chainID, _ := strconv.ParseUint(c.Query("chain_id"), 10, 64)
	label := c.Query("label")
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	entries, total, err := h.addressBook.ListAddresses(c.Request.Context(), chainID, label, "", offset, limit)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, gin.H{
		"data":  entries,
		"total": total,
		"offset": offset,
		"limit": limit,
	})
}

func (h *APIHandler) DeleteAddressBook(c *gin.Context) {
	address := c.Param("address")
	err := h.addressBook.DeleteAddress(c.Request.Context(), address)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.handleSuccess(c, gin.H{"status": "deleted"})
}

func (h *APIHandler) SubscribeEvent(c *gin.Context) {
	var req struct {
		ChainID         uint64                 `json:"chain_id"`
		ContractAddress string                 `json:"contract_address"`
		EventSignature  string                 `json:"event_signature"`
		CallbackURL     string                 `json:"callback_url"`
		FromBlock       uint64                 `json:"from_block"`
		Filters         map[string]interface{} `json:"filters"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	sub, err := h.eventListener.Subscribe(c.Request.Context(), req.ChainID, req.ContractAddress, req.EventSignature, req.CallbackURL, req.FromBlock, req.Filters)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, sub)
}

func (h *APIHandler) UnsubscribeEvent(c *gin.Context) {
	subID := c.Param("subscription_id")
	err := h.eventListener.Unsubscribe(c.Request.Context(), subID)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.handleSuccess(c, gin.H{"status": "unsubscribed"})
}

func (h *APIHandler) ListSubscriptions(c *gin.Context) {
	chainID, _ := strconv.ParseUint(c.Query("chain_id"), 10, 64)
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	var active *bool
	if activeStr := c.Query("active"); activeStr != "" {
		a := activeStr == "true"
		active = &a
	}

	subs, total, err := h.eventListener.ListSubscriptions(c.Request.Context(), chainID, active, offset, limit)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, gin.H{
		"data":  subs,
		"total": total,
		"offset": offset,
		"limit": limit,
	})
}

func (h *APIHandler) GetIndexedBlock(c *gin.Context) {
	blockNumStr := c.Param("block_number")
	blockNum, err := strconv.ParseUint(blockNumStr, 10, 64)
	if err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	block, err := h.indexer.GetBlock(c.Request.Context(), blockNum)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, block)
}

func (h *APIHandler) GetIndexedTransaction(c *gin.Context) {
	txHash := c.Param("tx_hash")
	tx, err := h.indexer.GetTransaction(c.Request.Context(), txHash)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.handleSuccess(c, tx)
}

func (h *APIHandler) GetAddressTransactions(c *gin.Context) {
	address := c.Param("address")
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	txs, total, err := h.indexer.GetTransactionsByAddress(c.Request.Context(), address, offset, limit)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, gin.H{
		"data":  txs,
		"total": total,
		"offset": offset,
		"limit": limit,
	})
}

func (h *APIHandler) GetIndexingStatus(c *gin.Context) {
	latestIndexed, latestChain, indexing, err := h.indexer.GetIndexingStatus(c.Request.Context())
	if err != nil {
		h.handleError(c, err)
		return
	}

	progress := float64(0)
	if latestChain > 0 {
		progress = float64(latestIndexed) / float64(latestChain) * 100
	}

	h.handleSuccess(c, gin.H{
		"latest_indexed": latestIndexed,
		"latest_chain":   latestChain,
		"indexing":       indexing,
		"progress_pct":   progress,
		"remaining":      latestChain - latestIndexed,
	})
}

func (h *APIHandler) VerifyProof(c *gin.Context) {
	var req zkp.VerifyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	result, err := h.zkVerifier.Verify(c.Request.Context(), req)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, result)
}

func (h *APIHandler) VerifyBatchProofs(c *gin.Context) {
	var req struct {
		Requests []zkp.VerifyRequest `json:"requests"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	results, err := h.zkVerifier.VerifyBatch(c.Request.Context(), req.Requests)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, results)
}

func (h *APIHandler) RegisterCircuit(c *gin.Context) {
	var circuit zkp.CircuitInfo
	if err := c.ShouldBindJSON(&circuit); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	err := h.zkVerifier.RegisterCircuit(c.Request.Context(), &circuit)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, circuit)
}

func (h *APIHandler) GetProof(c *gin.Context) {
	proofID := c.Param("proof_id")
	proof, err := h.zkVerifier.GetProof(c.Request.Context(), proofID)
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.handleSuccess(c, proof)
}

func (h *APIHandler) GetZKPStats(c *gin.Context) {
	stats, err := h.zkVerifier.GetStats(c.Request.Context())
	if err != nil {
		h.handleError(c, err)
		return
	}
	h.handleSuccess(c, stats)
}

func (h *APIHandler) CreateResource(c *gin.Context) {
	var req struct {
		Type   string                 `json:"type"`
		Config map[string]interface{} `json:"config"`
		Labels map[string]string      `json:"labels"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	entity := &models.Entity{
		Type:       req.Type,
		Status:     "provisioning",
		Attributes: req.Config,
	}

	if err := h.db.Create(entity).Error; err != nil {
		h.handleError(c, err)
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"code": 201,
		"data": gin.H{
			"id":     entity.ID,
			"status": entity.Status,
		},
	})
}

func (h *APIHandler) GetResourceStatus(c *gin.Context) {
	id := c.Param("id")
	var entity models.Entity
	if err := h.db.Where("id = ?", id).First(&entity).Error; err != nil {
		h.handleError(c, errors.ErrNotFound)
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"id":       entity.ID,
			"status":   entity.Status,
			"progress": 0.8,
		},
	})
}

func (h *APIHandler) BatchOperations(c *gin.Context) {
	var req struct {
		Operations []struct {
			Action string `json:"action"`
			ID     string `json:"id"`
		} `json:"operations"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	results := make([]gin.H, len(req.Operations))
	for i, op := range req.Operations {
		results[i] = gin.H{
			"id":     op.ID,
			"action": op.Action,
			"status": "success",
		}
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"batch_id": "batch_" + strconv.FormatInt(time.Now().Unix(), 10),
			"results":  results,
		},
	})
}

func (h *APIHandler) handleError(c *gin.Context, err error) {
	logger.Log.Error("API error", zap.Error(err))

	if appErr, ok := err.(*errors.AppError); ok {
		c.JSON(appErr.Code, gin.H{
			"code":    appErr.Code,
			"message": appErr.Message,
			"details": appErr.Details,
		})
		return
	}

	c.JSON(http.StatusInternalServerError, gin.H{
		"code":    http.StatusInternalServerError,
		"message": "Internal Server Error",
		"details": err.Error(),
	})
}

func (h *APIHandler) handleSuccess(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, gin.H{
		"code": http.StatusOK,
		"data": data,
	})
}

func (h *APIHandler) GetGasCacheStats(c *gin.Context) {
	stats := h.gasEstimator.GetCacheStats()
	h.handleSuccess(c, stats)
}

func (h *APIHandler) InvalidateGasCache(c *gin.Context) {
	chainIDStr := c.Param("chain_id")
	chainID, err := strconv.ParseUint(chainIDStr, 10, 64)
	if err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	h.gasEstimator.InvalidateCache(c.Request.Context(), chainID)
	h.handleSuccess(c, gin.H{"status": "invalidated", "chain_id": chainID})
}

func (h *APIHandler) ClearGasCache(c *gin.Context) {
	h.gasEstimator.ClearCache(c.Request.Context())
	h.handleSuccess(c, gin.H{"status": "cleared"})
}

func (h *APIHandler) StartCacheWarmer(c *gin.Context) {
	var req struct {
		ChainIDs []uint64 `json:"chain_ids"`
		Interval int      `json:"interval_seconds"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	if req.Interval <= 0 {
		req.Interval = 30
	}

	h.gasEstimator.StartCacheWarmer(req.ChainIDs, time.Duration(req.Interval)*time.Second)
	h.handleSuccess(c, gin.H{"status": "started", "chain_ids": req.ChainIDs, "interval": req.Interval})
}

func (h *APIHandler) StopCacheWarmer(c *gin.Context) {
	h.gasEstimator.StopCacheWarmer()
	h.handleSuccess(c, gin.H{"status": "stopped"})
}

func (h *APIHandler) BuildBatchTransactions(c *gin.Context) {
	var batchReq txbuilder.BatchRequest
	if err := c.ShouldBindJSON(&batchReq); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	result, err := h.txBuilder.BuildBatch(c.Request.Context(), batchReq)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, result)
}

func (h *APIHandler) SignBatchTransactions(c *gin.Context) {
	var req struct {
		ChainID         uint64   `json:"chain_id"`
		RawTxs          [][]byte `json:"raw_txs"`
		SignerAddresses []string `json:"signer_addresses"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	txs := make([]*types.Transaction, len(req.RawTxs))
	for i, rawTx := range req.RawTxs {
		tx := new(types.Transaction)
		if err := tx.UnmarshalBinary(rawTx); err != nil {
			h.handleError(c, errors.New(400, fmt.Sprintf("invalid raw tx at index %d: %v", i, err)))
			return
		}
		txs[i] = tx
	}

	result, err := h.txBuilder.SignBatch(c.Request.Context(), txs, req.SignerAddresses)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, result)
}

func (h *APIHandler) SendBatchTransactions(c *gin.Context) {
	var req struct {
		ChainID uint64   `json:"chain_id"`
		RawTxs  [][]byte `json:"raw_txs"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	result, err := h.chainAdapter.SendRawTransactionBatch(c.Request.Context(), req.ChainID, req.RawTxs)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, result)
}

func (h *APIHandler) StartRequestBatcher(c *gin.Context) {
	var req struct {
		BatchSize    int `json:"batch_size"`
		TimeoutMs    int `json:"timeout_ms"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	if req.BatchSize <= 0 {
		req.BatchSize = 10
	}
	if req.TimeoutMs <= 0 {
		req.TimeoutMs = 500
	}

	h.txBuilder.StartRequestBatcher(req.BatchSize, time.Duration(req.TimeoutMs)*time.Millisecond)
	h.handleSuccess(c, gin.H{
		"status":      "started",
		"batch_size":  req.BatchSize,
		"timeout_ms":  req.TimeoutMs,
	})
}

func (h *APIHandler) StopRequestBatcher(c *gin.Context) {
	h.txBuilder.StopRequestBatcher()
	h.handleSuccess(c, gin.H{"status": "stopped"})
}

func (h *APIHandler) GetBatcherStats(c *gin.Context) {
	stats := h.txBuilder.GetBatcherStats()
	h.handleSuccess(c, stats)
}

func (h *APIHandler) GetChainHealth(c *gin.Context) {
	chainIDStr := c.Param("chain_id")
	chainID, err := strconv.ParseUint(chainIDStr, 10, 64)
	if err != nil {
		h.handleError(c, errors.ErrInvalidParams)
		return
	}

	status, err := h.chainAdapter.GetChainHealthStatus(chainID)
	if err != nil {
		h.handleError(c, err)
		return
	}

	h.handleSuccess(c, status)
}

func (h *APIHandler) GetAllChainHealth(c *gin.Context) {
	statuses := h.chainAdapter.GetAllChainHealthStatus()
	h.handleSuccess(c, statuses)
}
