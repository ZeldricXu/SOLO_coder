package main

import (
	"context"
	"encoding/json"
	"fmt"
	"gas-estimator/internal/blockchain"
	"gas-estimator/internal/bridge"
	"gas-estimator/internal/domain"
	"gas-estimator/internal/gas"
	"gas-estimator/internal/indexer"
	"gas-estimator/internal/infra/metrics"
	"gas-estimator/internal/multisig"
	"gas-estimator/internal/storage"
	"gas-estimator/internal/transaction"
	"gas-estimator/internal/wallet"
	"gas-estimator/pkg/config"
	"gas-estimator/pkg/models"
	"log"
	"math/big"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
)

type Application struct {
	config               *config.Config
	transactionService   domain.TransactionService
	walletService        domain.BatchWalletService
	blockchainService    domain.BlockchainService
	multisigCoord        *multisig.MultisigCoordinator
	gasEstimator         *gas.GasEstimator
	blockIndexer         *indexer.BlockIndexer
	crossChainBridge     *bridge.CrossChainBridge
	decentralizedStorage *storage.DecentralizedStorage
	metricsService       metrics.MetricsService
	server               *http.Server
}

func NewApplication(cfg *config.Config) *Application {
	app := &Application{
		config: cfg,
	}

	chainConfigs := make([]domain.ChainConfig, len(cfg.Chains))
	for i, chainCfg := range cfg.Chains {
		chainConfigs[i] = domain.ChainConfig{
			Name:         chainCfg.Name,
			ChainID:      chainCfg.ChainID,
			RPCURLs:      chainCfg.RPCURLs,
			WSURL:        chainCfg.WSURL,
			ExplorerURL:  chainCfg.ExplorerURL,
			NativeToken:  chainCfg.NativeToken,
			Confirmations: chainCfg.Confirmations,
		}
	}

	metricsConfig := &metrics.MetricsConfig{
		Namespace: "gas_estimator",
		Subsystem: "blockchain",
	}
	app.metricsService = metrics.NewMetrics(metricsConfig)

	baseBlockchainService := blockchain.NewEVMBlockchainService(chainConfigs)
	app.blockchainService = blockchain.NewMonitoredBlockchainAdapter(
		baseBlockchainService,
		app.metricsService,
	)

	if len(cfg.Chains) > 0 {
		baseTransactionService := transaction.NewEVMTransactionBuilder(big.NewInt(cfg.Chains[0].ChainID))
		
		cachedTxConfig := &transaction.CachedTransactionConfig{
			LocalCacheSize: 1000,
			LocalTTL:       5 * time.Minute,
			DistributedTTL: 30 * time.Minute,
			EnableWarming:  false,
		}
		
		cachedTransactionService, err := transaction.NewCachedTransactionBuilder(
			baseTransactionService,
			cachedTxConfig,
		)
		if err != nil {
			log.Printf("Warning: failed to create cached transaction builder: %v", err)
			app.transactionService = baseTransactionService
		} else {
			app.transactionService = cachedTransactionService
		}

		app.multisigCoord = multisig.NewMultisigCoordinator(
			multisig.ProposalConfig{
				Threshold: cfg.Multisig.Threshold,
				Signers:   cfg.Multisig.Signers,
			},
			cfg.Chains[0].ChainID,
		)
	}

	app.gasEstimator = gas.NewGasEstimator(app.blockchainService)
	app.blockIndexer = indexer.NewBlockIndexer(app.blockchainService)
	app.crossChainBridge = bridge.NewCrossChainBridge(app.blockchainService)
	app.decentralizedStorage = storage.NewDecentralizedStorage(cfg.Storage)

	baseWalletService, err := wallet.NewHDWallet("test mnemonic phrase for demo", "")
	if err != nil {
		log.Printf("Warning: failed to create HD wallet: %v", err)
	} else {
		app.walletService = wallet.NewBatchWallet(baseWalletService, 100)
	}

	return app
}

func (app *Application) setupRoutes() *gin.Engine {
	r := gin.Default()

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status":    "ok",
			"version":   "1.0.0",
			"timestamp": time.Now().Format(time.RFC3339),
		})
	})

	r.GET("/metrics", gin.WrapH(app.metricsService.GetHandler()))

	api := r.Group("/api/v1")

	app.registerTransactionRoutes(api)
	app.registerAddressRoutes(api)
	app.registerChainRoutes(api)
	app.registerMultisigRoutes(api)
	app.registerGasRoutes(api)
	app.registerIndexerRoutes(api)
	app.registerBridgeRoutes(api)
	app.registerStorageRoutes(api)

	return r
}

func (app *Application) registerTransactionRoutes(api *gin.RouterGroup) {
	txGroup := api.Group("/transactions")

	txGroup.POST("/build", func(c *gin.Context) {
		var req struct {
			To       string `json:"to"`
			Value    string `json:"value"`
			Data     string `json:"data"`
			Nonce    uint64 `json:"nonce"`
			GasLimit uint64 `json:"gas_limit"`
			GasPrice string `json:"gas_price"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		if app.transactionService == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "transaction service not initialized"})
			return
		}

		value, _ := new(big.Int).SetString(req.Value, 10)
		gasPrice, _ := new(big.Int).SetString(req.GasPrice, 10)

		params := domain.TransactionParams{
			To:       []byte(req.To),
			Value:    value,
			Data:     []byte(req.Data),
			Nonce:    req.Nonce,
			GasLimit: req.GasLimit,
			GasPrice: gasPrice,
		}

		tx, err := app.transactionService.Build(params)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusCreated, gin.H{
			"code": 201,
			"data": tx,
		})
	})

	txGroup.POST("/serialize", func(c *gin.Context) {
		var tx domain.Transaction
		if err := c.ShouldBindJSON(&tx); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		if app.transactionService == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "transaction service not initialized"})
			return
		}

		serialized, err := app.transactionService.Serialize(&tx)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{"serialized": serialized},
		})
	})
}

func (app *Application) registerAddressRoutes(api *gin.RouterGroup) {
	addrGroup := api.Group("/addresses")

	addrGroup.POST("/derive", func(c *gin.Context) {
		var req struct {
			Index uint32   `json:"index"`
			Label string   `json:"label"`
			Tags  []string `json:"tags"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		if app.walletService == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "wallet service not initialized"})
			return
		}

		addr, err := app.walletService.DeriveAddress(req.Index, req.Label, req.Tags)
		if err != nil {
			c.JSON(http.StatusConflict, gin.H{"error": err.Error(), "code": 409})
			return
		}

		c.JSON(http.StatusCreated, gin.H{
			"code": 201,
			"data": addr,
		})
	})

	addrGroup.POST("/derive/batch", func(c *gin.Context) {
		var req struct {
			Indices []uint32   `json:"indices"`
			Labels  []string   `json:"labels"`
			Tags    [][]string `json:"tags"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		if app.walletService == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "wallet service not initialized"})
			return
		}

		addresses, err := app.walletService.DeriveAddresses(req.Indices, req.Labels, req.Tags)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusCreated, gin.H{
			"code": 201,
			"data": addresses,
		})
	})

	addrGroup.GET("/:address", func(c *gin.Context) {
		address := c.Param("address")

		if app.walletService == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "wallet service not initialized"})
			return
		}

		addr, err := app.walletService.GetAddress(address)
		if err != nil {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": addr,
		})
	})

	addrGroup.POST("/get/batch", func(c *gin.Context) {
		var req struct {
			Addresses []string `json:"addresses"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		if app.walletService == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "wallet service not initialized"})
			return
		}

		addresses, err := app.walletService.GetAddresses(req.Addresses)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": addresses,
		})
	})

	addrGroup.GET("", func(c *gin.Context) {
		if app.walletService == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "wallet service not initialized"})
			return
		}

		addresses := app.walletService.ListAddresses()

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": addresses,
		})
	})

	addrGroup.POST("/addressbook", func(c *gin.Context) {
		var req struct {
			Address string   `json:"address"`
			Label   string   `json:"label"`
			Tags    []string `json:"tags"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		if app.walletService == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "wallet service not initialized"})
			return
		}

		err := app.walletService.AddToAddressBook(req.Address, req.Label, req.Tags)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusCreated, gin.H{
			"code": 201,
			"data": gin.H{"address": req.Address},
		})
	})

	addrGroup.POST("/addressbook/batch", func(c *gin.Context) {
		var req map[string]*domain.WalletAddress

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		if app.walletService == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "wallet service not initialized"})
			return
		}

		errors := app.walletService.AddToAddressBookBatch(req)

		if len(errors) > 0 {
			c.JSON(http.StatusMultiStatus, gin.H{
				"code": 207,
				"errors": errors,
			})
			return
		}

		c.JSON(http.StatusCreated, gin.H{
			"code": 201,
			"data": "success",
		})
	})
}

func (app *Application) registerChainRoutes(api *gin.RouterGroup) {
	chainGroup := api.Group("/chains")

	chainGroup.GET("/current", func(c *gin.Context) {
		currentChain := app.blockchainService.GetCurrentChain()

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{"chain": currentChain},
		})
	})

	chainGroup.POST("/switch", func(c *gin.Context) {
		var req struct {
			Chain string `json:"chain"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		err := app.blockchainService.SwitchChain(req.Chain)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{"current_chain": req.Chain},
		})
	})

	chainGroup.GET("/blocks/latest", func(c *gin.Context) {
		block, err := app.blockchainService.GetLatestBlock()
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": block,
		})
	})

	chainGroup.GET("/gas-price", func(c *gin.Context) {
		price, err := app.blockchainService.GetGasPrice()
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{"gas_price": price.String()},
		})
	})
}

func (app *Application) registerMultisigRoutes(api *gin.RouterGroup) {
	msGroup := api.Group("/multisig")

	msGroup.POST("/proposals", func(c *gin.Context) {
		var req struct {
			Transaction domain.Transaction `json:"transaction"`
			ID          string             `json:"id"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		if app.multisigCoord == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "multisig coordinator not initialized"})
			return
		}

		proposal, err := app.multisigCoord.CreateProposal(&req.Transaction, req.ID)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusCreated, gin.H{
			"code": 201,
			"data": proposal,
		})
	})

	msGroup.GET("/proposals/:id", func(c *gin.Context) {
		proposalID := c.Param("id")

		if app.multisigCoord == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "multisig coordinator not initialized"})
			return
		}

		proposal, err := app.multisigCoord.GetProposal(proposalID)
		if err != nil {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": proposal,
		})
	})

	msGroup.GET("/proposals", func(c *gin.Context) {
		status := c.Query("status")

		if app.multisigCoord == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "multisig coordinator not initialized"})
			return
		}

		proposals := app.multisigCoord.ListProposals(status)

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": proposals,
		})
	})

	msGroup.POST("/proposals/:id/sign", func(c *gin.Context) {
		proposalID := c.Param("id")
		var req struct {
			Signer string `json:"signer"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		if app.multisigCoord == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "multisig coordinator not initialized"})
			return
		}

		status, signed, required, err := app.multisigCoord.GetProposalStatus(proposalID)
		if err != nil {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{
				"status":         status,
				"signed_count":   signed,
				"required_count": required,
			},
		})
	})

	msGroup.POST("/proposals/:id/execute", func(c *gin.Context) {
		proposalID := c.Param("id")

		if app.multisigCoord == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "multisig coordinator not initialized"})
			return
		}

		tx, err := app.multisigCoord.ExecuteProposal(proposalID)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": tx,
		})
	})
}

func (app *Application) registerGasRoutes(api *gin.RouterGroup) {
	gasGroup := api.Group("/gas")

	gasGroup.POST("/estimate", func(c *gin.Context) {
		var req struct {
			ChainID     string                  `json:"chain_id"`
			Transaction *domain.Transaction     `json:"transaction"`
			Urgency     string                  `json:"urgency"`
			Config      *gas.GasEstimateConfig  `json:"config,omitempty"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		var estimate *models.GasEstimate
		var err error

		if req.Config != nil {
			estimate, err = app.gasEstimator.EstimateGasWithConfig(req.ChainID, req.Transaction, *req.Config)
		} else {
			estimate, err = app.gasEstimator.EstimateGas(req.ChainID, req.Transaction, req.Urgency)
		}

		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": estimate,
		})
	})

	gasGroup.GET("/network/:chain_id/status", func(c *gin.Context) {
		chainID := c.Param("chain_id")

		status, err := app.gasEstimator.GetNetworkStatus(chainID)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": status,
		})
	})

	gasGroup.GET("/network/:chain_id/trend", func(c *gin.Context) {
		chainID := c.Param("chain_id")

		trend, ratio, err := app.gasEstimator.CalculateFeeTrend(chainID)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{
				"trend": trend,
				"ratio": ratio,
			},
		})
	})

	gasGroup.GET("/history/:chain_id", func(c *gin.Context) {
		chainID := c.Param("chain_id")
		limit := 100

		history, err := app.gasEstimator.GetHistoricalData(chainID, limit)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": history,
		})
	})
}

func (app *Application) registerIndexerRoutes(api *gin.RouterGroup) {
	idxGroup := api.Group("/indexer")

	idxGroup.POST("/blocks/:chain_id/index", func(c *gin.Context) {
		chainID := c.Param("chain_id")
		var req struct {
			Count int `json:"count"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			req.Count = 10
		}

		err := app.blockIndexer.IndexLatestBlocks(chainID, req.Count)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{"indexed_count": req.Count},
		})
	})

	idxGroup.GET("/blocks/:chain_id/:number", func(c *gin.Context) {
		chainID := c.Param("chain_id")
		var blockNumber uint64
		fmt.Sscanf(c.Param("number"), "%d", &blockNumber)

		block, err := app.blockIndexer.GetBlockByNumber(chainID, blockNumber)
		if err != nil {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": block,
		})
	})

	idxGroup.GET("/info", func(c *gin.Context) {
		info := app.blockIndexer.GetIndexInfo()

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": info,
		})
	})
}

func (app *Application) registerBridgeRoutes(api *gin.RouterGroup) {
	bridgeGroup := api.Group("/bridge")

	bridgeGroup.POST("/messages", func(c *gin.Context) {
		var req struct {
			SourceChain      string `json:"source_chain"`
			DestinationChain string `json:"destination_chain"`
			SourceAddress    string `json:"source_address"`
			DestAddress      string `json:"dest_address"`
			Amount           string `json:"amount"`
			TokenAddress     string `json:"token_address"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		amount, _ := new(big.Int).SetString(req.Amount, 10)

		message, err := app.crossChainBridge.CreateBridgeMessage(
			req.SourceChain,
			req.DestinationChain,
			req.SourceAddress,
			req.DestAddress,
			amount,
			req.TokenAddress,
		)

		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusCreated, gin.H{
			"code": 201,
			"data": message,
		})
	})

	bridgeGroup.POST("/messages/:id/lock", func(c *gin.Context) {
		messageID := c.Param("id")

		err := app.crossChainBridge.LockAssets(messageID)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		message, _ := app.crossChainBridge.GetMessage(messageID)

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": message,
		})
	})

	bridgeGroup.POST("/messages/:id/mint", func(c *gin.Context) {
		messageID := c.Param("id")

		err := app.crossChainBridge.MintAssets(messageID)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		message, _ := app.crossChainBridge.GetMessage(messageID)

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": message,
		})
	})

	bridgeGroup.POST("/messages/:id/execute", func(c *gin.Context) {
		messageID := c.Param("id")

		err := app.crossChainBridge.ExecuteBridge(messageID)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		message, _ := app.crossChainBridge.GetMessage(messageID)

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": message,
		})
	})

	bridgeGroup.GET("/messages/:id", func(c *gin.Context) {
		messageID := c.Param("id")

		message, err := app.crossChainBridge.GetMessage(messageID)
		if err != nil {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": message,
		})
	})

	bridgeGroup.GET("/messages", func(c *gin.Context) {
		status := c.Query("status")

		messages := app.crossChainBridge.ListMessages(status)

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": messages,
		})
	})

	bridgeGroup.GET("/stats", func(c *gin.Context) {
		stats := app.crossChainBridge.GetBridgeStats()

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": stats,
		})
	})
}

func (app *Application) registerStorageRoutes(api *gin.RouterGroup) {
	storageGroup := api.Group("/storage")

	storageGroup.POST("/store", func(c *gin.Context) {
		var req struct {
			Data     string   `json:"data"`
			Networks []string `json:"networks"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		content, err := app.decentralizedStorage.Store([]byte(req.Data), req.Networks)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusCreated, gin.H{
			"code": 201,
			"data": content,
		})
	})

	storageGroup.GET("/retrieve/:cid", func(c *gin.Context) {
		cid := c.Param("cid")

		content, err := app.decentralizedStorage.Retrieve(cid)
		if err != nil {
			c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": content,
		})
	})

	storageGroup.POST("/pin/:cid", func(c *gin.Context) {
		cid := c.Param("cid")
		var req struct {
			Network string `json:"network"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			req.Network = "ipfs"
		}

		err := app.decentralizedStorage.Pin(cid, req.Network)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{"pinned": true},
		})
	})

	storageGroup.POST("/unpin/:cid", func(c *gin.Context) {
		cid := c.Param("cid")
		var req struct {
			Network string `json:"network"`
		}

		if err := c.ShouldBindJSON(&req); err != nil {
			req.Network = "ipfs"
		}

		err := app.decentralizedStorage.Unpin(cid, req.Network)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{"unpinned": true},
		})
	})

	storageGroup.GET("/contents", func(c *gin.Context) {
		contents := app.decentralizedStorage.ListContents()

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": contents,
		})
	})

	storageGroup.GET("/stats", func(c *gin.Context) {
		stats := app.decentralizedStorage.GetStorageStats()

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": stats,
		})
	})
}

func (app *Application) Start() error {
	router := app.setupRoutes()

	serverAddr := fmt.Sprintf(":%d", app.config.Server.Port)

	app.server = &http.Server{
		Addr:         serverAddr,
		Handler:      router,
		ReadTimeout:  time.Duration(app.config.Server.ReadTimeout) * time.Second,
		WriteTimeout: time.Duration(app.config.Server.WriteTimeout) * time.Second,
	}

	log.Printf("Starting server on %s", serverAddr)
	log.Printf("Metrics available at http://localhost%s/metrics", serverAddr)

	go func() {
		if err := app.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()

	return nil
}

func (app *Application) Stop() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	log.Println("Shutting down server...")

	return app.server.Shutdown(ctx)
}

func main() {
	defaultConfig := &config.Config{
		Server: config.ServerConfig{
			Port:         8080,
			ReadTimeout:  30,
			WriteTimeout: 30,
			Mode:         "debug",
		},
		Chains: []config.ChainConfig{
			{
				Name:         "ethereum",
				ChainID:      1,
				RPCURLs:      []string{"http://localhost:8545"},
				NativeToken:  "ETH",
				Confirmations: 12,
			},
			{
				Name:         "polygon",
				ChainID:      137,
				RPCURLs:      []string{"http://localhost:8546"},
				NativeToken:  "MATIC",
				Confirmations: 12,
			},
		},
		Storage: config.StorageConfig{
			IPFSEndpoints: []string{"http://localhost:5001"},
			Timeout:       30,
		},
		Multisig: config.MultisigConfig{
			Threshold:    2,
			TotalSigners: 3,
			Signers:      []string{"signer1", "signer2", "signer3"},
		},
	}

	configPath := "config.json"
	if len(os.Args) > 1 {
		configPath = os.Args[1]
	}

	if _, err := os.Stat(configPath); err == nil {
		cfg, err := config.Load(configPath)
		if err == nil {
			defaultConfig = cfg
		}
	}

	app := NewApplication(defaultConfig)

	if err := app.Start(); err != nil {
		log.Fatalf("Failed to start application: %v", err)
	}

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	<-sigChan

	if err := app.Stop(); err != nil {
		log.Fatalf("Failed to stop application: %v", err)
	}

	log.Println("Application stopped successfully")
}

func init() {
	json.Marshal(struct{}{})
}
