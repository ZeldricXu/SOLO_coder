package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"

	"github.com/blockchain-middleware/core/internal/common/config"
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
	"github.com/blockchain-middleware/core/pkg/api"
)

func main() {
	logger.EnsureLogDir()

	if err := config.Load("config.yaml"); err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	if err := logger.Init(config.AppConfig.Server.Mode); err != nil {
		log.Fatalf("Failed to init logger: %v", err)
	}
	defer logger.Sync()

	logger.Log.Info("Starting blockchain middleware service...")

	db, err := gorm.Open(postgres.Open(config.AppConfig.Database.DSN()), &gorm.Config{})
	if err != nil {
		logger.Log.Fatal("Failed to connect to database", zap.Error(err))
	}

	if err := db.AutoMigrate(
		&models.Entity{},
		&models.Config{},
		&models.RunInstance{},
		&models.StatsSnapshot{},
		&models.GasPriceRecord{},
		&models.AddressBook{},
		&models.EventSubscription{},
		&models.IndexedBlock{},
		&models.IndexedTransaction{},
		&models.ZKPProof{},
		&models.StoredContent{},
		&zkp.CircuitInfo{},
	); err != nil {
		logger.Log.Fatal("Failed to migrate database", zap.Error(err))
	}

	redisClient := redis.NewClient(&redis.Options{
		Addr:     config.AppConfig.Redis.Addr(),
		Password: config.AppConfig.Redis.Password,
		DB:       config.AppConfig.Redis.DB,
	})

	if err := redisClient.Ping(context.Background()).Err(); err != nil {
		logger.Log.Warn("Failed to connect to Redis", zap.Error(err))
	}

	chainAdapter := chainadapter.NewChainAdapter()
	if err := chainAdapter.InitializeFromConfig(); err != nil {
		logger.Log.Warn("Failed to initialize chain adapter", zap.Error(err))
	}
	defer chainAdapter.Close()

	gasEstimator := gasestimator.NewGasEstimator(db, redisClient)
	for chainID := range config.AppConfig.Chains {
		chainConfig := config.AppConfig.Chains[chainID]
		gasEstimator.RegisterChainRPC(chainConfig.ChainID, chainAdapter)
	}

	txBuilder := txbuilder.NewTransactionBuilder()

	storageMgr := storageadapter.NewStorageManager(db)
	storageMgr.InitializeFromConfig()

	hdWallet, err := wallet.InitializeFromConfig(db)
	if err != nil {
		logger.Log.Warn("Failed to initialize wallet", zap.Error(err))
	}

	addressBook := wallet.NewAddressBookManager(db)

	eventListener := eventlistener.NewEventListener(db, chainAdapter)
	if err := eventListener.LoadActiveSubscriptions(); err != nil {
		logger.Log.Warn("Failed to load event subscriptions", zap.Error(err))
	}
	if err := eventListener.Start(context.Background()); err != nil {
		logger.Log.Warn("Failed to start event listener", zap.Error(err))
	}
	defer eventListener.Stop()

	var blockIndexer *indexer.BlockIndexer
	if len(config.AppConfig.Chains) > 0 {
		for _, chainConfig := range config.AppConfig.Chains {
			blockIndexer = indexer.NewBlockIndexer(db, chainAdapter, chainConfig.ChainID)
			if err := blockIndexer.Start(); err != nil {
				logger.Log.Warn("Failed to start block indexer", zap.Error(err))
			}
			break
		}
	}
	if blockIndexer != nil {
		defer blockIndexer.Stop()
	}

	zkVerifier := zkp.NewZKVerifier(db)
	if err := zkVerifier.Initialize(); err != nil {
		logger.Log.Warn("Failed to initialize ZKP verifier", zap.Error(err))
	}

	handler := api.NewAPIHandler(
		db,
		redisClient,
		gasEstimator,
		txBuilder,
		chainAdapter,
		storageMgr,
		hdWallet,
		addressBook,
		eventListener,
		blockIndexer,
		zkVerifier,
	)

	if config.AppConfig.Server.Mode == "production" {
		gin.SetMode(gin.ReleaseMode)
	}

	r := gin.Default()

	r.Use(func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}
		c.Next()
	})

	api.SetupRoutes(r, handler)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if config.AppConfig.Metrics.Enabled {
		metricsConfig := chainadapter.MetricsConfig{
			Enabled: config.AppConfig.Metrics.Enabled,
			Port:    config.AppConfig.Metrics.Port,
			Path:    config.AppConfig.Metrics.Path,
		}
		if err := chainAdapter.StartMetricsServer(metricsConfig); err != nil {
			logger.Log.Warn("Failed to start metrics server", zap.Error(err))
		}
		defer chainAdapter.StopMetricsServer(ctx)
	}

	port := config.AppConfig.Server.Port
	srv := &http.Server{
		Addr:    fmt.Sprintf(":%d", port),
		Handler: r,
	}

	go func() {
		logger.Log.Info("Server starting", zap.Int("port", port))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Log.Fatal("Failed to start server", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logger.Log.Info("Shutdown signal received")

	if err := srv.Shutdown(ctx); err != nil {
		logger.Log.Fatal("Server forced to shutdown", zap.Error(err))
	}

	logger.Log.Info("Server exiting")
}
