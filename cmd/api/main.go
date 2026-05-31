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
	"github.com/gasestimator/platform/internal/business/address"
	"github.com/gasestimator/platform/internal/business/bridge"
	"github.com/gasestimator/platform/internal/business/chain"
	"github.com/gasestimator/platform/internal/business/event"
	"github.com/gasestimator/platform/internal/business/gas"
	"github.com/gasestimator/platform/internal/business/multisig"
	"github.com/gasestimator/platform/internal/business/tx"
	"github.com/gasestimator/platform/internal/business/zkp"
	"github.com/gasestimator/platform/internal/domain/model"
	"github.com/gasestimator/platform/internal/infrastructure/cache"
	"github.com/gasestimator/platform/internal/infrastructure/db"
	"github.com/gasestimator/platform/internal/infrastructure/logger"
	"github.com/gasestimator/platform/internal/interface/handler"
	"github.com/gasestimator/platform/internal/interface/middleware"
	"go.uber.org/zap"
)

func main() {
	logger.Init("info", "gas-estimator")
	defer logger.Sync()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	gormDB, err := db.NewPostgres(db.Config{
		Host:     getEnv("DB_HOST", "localhost"),
		Port:     getEnvInt("DB_PORT", 5432),
		User:     getEnv("DB_USER", "postgres"),
		Password: getEnv("DB_PASSWORD", "postgres"),
		DBName:   getEnv("DB_NAME", "gas_estimator"),
		SSLMode:  getEnv("DB_SSLMODE", "disable"),
		MaxIdle:  10,
		MaxOpen:  100,
	})
	if err != nil {
		log.Fatalf("failed to connect to database: %v", err)
	}

	repo := db.NewGormRepository(gormDB)
	if err := repo.AutoMigrate(); err != nil {
		log.Fatalf("failed to migrate database: %v", err)
	}

	redisClient := cache.NewRedis(cache.Config{
		Host:     getEnv("REDIS_HOST", "localhost"),
		Port:     getEnvInt("REDIS_PORT", 6379),
		Password: getEnv("REDIS_PASSWORD", ""),
		DB:       getEnvInt("REDIS_DB", 0),
		PoolSize: 50,
	})

	zkpRepo := repo.ZKPProof()
	zkpService := zkp.NewService(zkpRepo)
	zkp.SetDefaultRepo(zkpRepo)
	chainService := chain.NewService(repo.ChainRPCNode())
	gasService := gas.NewService(repo.GasEstimate())
	txService := tx.NewService(repo.Transaction())
	multisigService := multisig.NewService(repo.MultisigProposal(), repo.Transaction())
	eventService := event.NewService(repo.ContractEvent())
	bridgeService := bridge.NewService(repo.CrossChainTransfer())
	addressService := address.NewService(repo.HDWallet(), repo.DerivedAddress())

	gasService.SetChainService(chainService)
	txService.SetGasService(gasService)

	type redisAdapter struct {
		*cache.Client
	}

	txService.EnableL2Cache(&redisAdapter{redisClient}, "tx:")

	zkpService.Start(ctx)
	multisigService.Start(ctx)

	go txService.WarmUpCache(ctx)
	go gasService.CollectHistoricalData(ctx, "eth")
	go gasService.CollectHistoricalData(ctx, "bsc")
	go gasService.CollectHistoricalData(ctx, "polygon")

	eventService.RegisterHandler("Transfer", func(ctx context.Context, e *model.ContractEvent) error {
		logger.L().Info("Transfer event processed", zap.String("tx_hash", e.TxHash))
		return nil
	})

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()

	r.Use(middleware.Recovery())
	r.Use(middleware.CORS())
	r.Use(middleware.RequestID())
	r.Use(middleware.TraceID())
	r.Use(middleware.Timeout(30 * time.Second))
	r.Use(middleware.RateLimit(1000))
	r.Use(middleware.Logger())
	r.Use(middleware.ErrorHandler())

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"code":    200,
			"status":  "ok",
			"service": "gas-estimator-platform",
			"time":    time.Now().UTC().Format(time.RFC3339),
		})
	})

	h := handler.NewHandler(
		zkpService,
		txService,
		multisigService,
		eventService,
		bridgeService,
		gasService,
		chainService,
		addressService,
	)
	h.RegisterRoutes(&r.RouterGroup)

	port := getEnv("PORT", "8080")
	srv := &http.Server{
		Addr:         ":" + port,
		Handler:      r,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
	}

	go func() {
		logger.L().Info("server starting", zap.String("port", port))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.L().Fatal("server failed to start", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logger.L().Info("shutdown signal received")

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.L().Fatal("server forced to shutdown", zap.Error(err))
	}

	logger.L().Info("server exited")
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		var intValue int
		if _, err := fmt.Sscanf(value, "%d", &intValue); err == nil {
			return intValue
		}
	}
	return defaultValue
}
