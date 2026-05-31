package router

import (
	"github.com/gin-gonic/gin"
	"github.com/solocoder/session147/api/handlers"
	"github.com/solocoder/session147/api/middleware"
)

func SetupRouter(h *handlers.Handler) *gin.Engine {
	r := gin.Default()

	r.Use(middleware.CORSMiddleware())
	r.Use(middleware.RequestLogger())
	r.Use(middleware.ErrorHandler())

	api := r.Group("/api/v1")
	{
		health := api.Group("/health")
		{
			health.GET("", func(c *gin.Context) {
				c.JSON(200, gin.H{"status": "ok"})
			})
		}

		auth := api.Group("")
		auth.Use(middleware.AuthMiddleware())
		{
			multisig := auth.Group("/multisig")
			{
				multisig.POST("/wallets", h.CreateWallet)
				multisig.GET("/wallets", h.ListWallets)
				multisig.GET("/wallets/:id", h.GetWallet)

				multisig.POST("/proposals", h.CreateProposal)
				multisig.GET("/proposals", h.ListProposals)
				multisig.GET("/proposals/:id", h.GetProposal)
				multisig.POST("/proposals/sign", h.SignProposal)
				multisig.POST("/proposals/execute", h.ExecuteProposal)
				multisig.POST("/proposals/:id/cancel", h.CancelProposal)

				multisig.POST("/routing/mode", h.SetReadWriteMode)
				multisig.GET("/routing/status", h.GetRoutingStatus)
			}

			gas := auth.Group("/gas")
			{
				gas.POST("/estimate", h.EstimateGas)
				gas.GET("/price/:chain_id", h.GetGasPrice)
				gas.GET("/history/:chain_id", h.GetGasHistory)

				gas.POST("/alerts/threshold", h.SetAlertThreshold)
				gas.POST("/notifications/channels", h.RegisterNotificationChannel)
			}

			tx := auth.Group("/transactions")
			{
				tx.POST("/build", h.BuildTransaction)
				tx.POST("/sign", h.SignTransaction)
				tx.POST("/:id/broadcast", h.BroadcastTransaction)
				tx.GET("/:id", h.GetTransaction)
				tx.GET("", h.ListTransactions)
				tx.POST("/:id/cancel", h.CancelTransaction)

				tx.GET("/plugins", h.ListPlugins)
				tx.POST("/plugins/:id/enable", h.EnablePlugin)
				tx.POST("/plugins/:id/disable", h.DisablePlugin)
			}

			indexer := auth.Group("/indexer")
			{
				indexer.POST("/start", h.StartIndexer)
				indexer.POST("/:chain_id/stop", h.StopIndexer)
				indexer.GET("/:chain_id/status", h.GetIndexerStatus)
				indexer.GET("/blocks/:chain_id/:block_num", h.GetBlock)
			}

			zkp := auth.Group("/zkp")
			{
				zkp.POST("/verify", h.VerifyProof)
				zkp.POST("/circuits", h.RegisterCircuit)
				zkp.GET("/proofs/:id", h.GetProof)
			}

			hdwallet := auth.Group("/hdwallet")
			{
				hdwallet.POST("/wallets", h.CreateHDWallet)
				hdwallet.POST("/derive", h.DeriveAddresses)
				hdwallet.POST("/addressbook", h.AddAddressBook)
			}

			storage := auth.Group("/storage")
			{
				storage.POST("/store", h.StoreContent)
				storage.POST("/retrieve", h.RetrieveContent)
				storage.POST("/pin", h.PinContent)
			}

			events := auth.Group("/events")
			{
				events.POST("/subscriptions", h.CreateSubscription)
				events.GET("/subscriptions", h.ListSubscriptions)
				events.POST("/subscriptions/:id/pause", h.PauseSubscription)
				events.POST("/subscriptions/:id/resume", h.ResumeSubscription)
				events.DELETE("/subscriptions/:id", h.DeleteSubscription)
			}

			chain := auth.Group("/chain")
			{
				chain.GET("", h.ListChains)
				chain.GET("/blocks/:chain_id/:block_num", h.GetChainBlock)
				chain.GET("/balance/:chain_id/:address", h.GetChainBalance)
				chain.GET("/transactions/:chain_id/:hash", h.GetChainTx)
			}

			bridge := auth.Group("/bridge")
			{
				bridge.POST("/initiate", h.InitiateBridge)
				bridge.GET("/transactions", h.ListBridgeTxs)
				bridge.GET("/transactions/:id", h.GetBridgeTx)
				bridge.POST("/transactions/:id/refund", h.RefundBridge)
			}
		}
	}

	return r
}
