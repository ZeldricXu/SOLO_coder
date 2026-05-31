package api

import (
	"github.com/gin-gonic/gin"
)

func SetupRoutes(r *gin.Engine, handler *APIHandler) {
	api := r.Group("/api/v1")
	{
		api.GET("/health", handler.HealthCheck)

		api.POST("/resources", handler.CreateResource)
		api.GET("/resources/:id/status", handler.GetResourceStatus)
		api.POST("/resources/batch", handler.BatchOperations)

		gas := api.Group("/gas")
		{
			gas.GET("/estimate/:chain_id", handler.EstimateGas)
			gas.GET("/history/:chain_id", handler.GetGasHistory)
			gas.GET("/cache/stats", handler.GetGasCacheStats)
			gas.POST("/cache/invalidate/:chain_id", handler.InvalidateGasCache)
			gas.POST("/cache/clear", handler.ClearGasCache)
			gas.POST("/cache/warmer/start", handler.StartCacheWarmer)
			gas.POST("/cache/warmer/stop", handler.StopCacheWarmer)
		}

		transactions := api.Group("/transactions")
		{
			transactions.POST("/build", handler.BuildTransaction)
			transactions.POST("/sign", handler.SignTransaction)
			transactions.POST("/send", handler.SendTransaction)
			transactions.POST("/build/batch", handler.BuildBatchTransactions)
			transactions.POST("/sign/batch", handler.SignBatchTransactions)
			transactions.POST("/send/batch", handler.SendBatchTransactions)
			transactions.POST("/batcher/start", handler.StartRequestBatcher)
			transactions.POST("/batcher/stop", handler.StopRequestBatcher)
			transactions.GET("/batcher/stats", handler.GetBatcherStats)
		}

		chain := api.Group("/chain")
		{
			chain.GET("/block/:chain_id/:block_number", handler.GetBlock)
			chain.GET("/transaction/:chain_id/:tx_hash", handler.GetTransaction)
			chain.GET("/balance/:chain_id/:address", handler.GetBalance)
			chain.GET("/health/:chain_id", handler.GetChainHealth)
			chain.GET("/health", handler.GetAllChainHealth)
		}

		storage := api.Group("/storage")
		{
			storage.POST("/store", handler.StoreContent)
			storage.GET("/retrieve/:storage_type/:cid", handler.RetrieveContent)
			storage.POST("/pin/:storage_type/:cid", handler.PinContent)
			storage.GET("/contents", handler.ListStoredContent)
		}

		wallet := api.Group("/wallet")
		{
			wallet.POST("/derive", handler.DeriveAddress)
			wallet.POST("/derive/batch", handler.DeriveAddresses)
		}

		addressBook := api.Group("/address-book")
		{
			addressBook.POST("", handler.AddAddressBook)
			addressBook.GET("/:address", handler.GetAddressBook)
			addressBook.GET("", handler.ListAddressBook)
			addressBook.DELETE("/:address", handler.DeleteAddressBook)
		}

		events := api.Group("/events")
		{
			events.POST("/subscribe", handler.SubscribeEvent)
			events.DELETE("/subscribe/:subscription_id", handler.UnsubscribeEvent)
			events.GET("/subscriptions", handler.ListSubscriptions)
		}

		indexer := api.Group("/indexer")
		{
			indexer.GET("/block/:block_number", handler.GetIndexedBlock)
			indexer.GET("/transaction/:tx_hash", handler.GetIndexedTransaction)
			indexer.GET("/address/:address/transactions", handler.GetAddressTransactions)
			indexer.GET("/status", handler.GetIndexingStatus)
		}

		zkp := api.Group("/zkp")
		{
			zkp.POST("/verify", handler.VerifyProof)
			zkp.POST("/verify/batch", handler.VerifyBatchProofs)
			zkp.POST("/circuits", handler.RegisterCircuit)
			zkp.GET("/proofs/:proof_id", handler.GetProof)
			zkp.GET("/stats", handler.GetZKPStats)
		}
	}
}
