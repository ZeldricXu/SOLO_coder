package main

import (
	"context"
	"os"
	"os/signal"
	"strconv"
	"syscall"

	"github.com/edgevision/edgevision/api/router"
	"github.com/edgevision/edgevision/internal/container"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/config"
	"github.com/edgevision/edgevision/internal/infrastructure/database"
	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/edgevision/edgevision/internal/infrastructure/mqtt"
	wal_impl "github.com/edgevision/edgevision/internal/infrastructure/wal"
	"github.com/edgevision/edgevision/internal/service"
	"go.uber.org/zap"
)

func main() {
	cfg := config.Load()

	logger.Init(&cfg.Logger)
	defer logger.Sync()

	db, err := database.New(cfg.Database)
	if err != nil {
		logger.Get().Fatal("Failed to connect to database", zap.Error(err))
	}
	defer db.Close()

	redisClient, err := cache.New(cfg.Cache)
	if err != nil {
		logger.Get().Fatal("Failed to connect to redis", zap.Error(err))
	}
	defer redisClient.Close()

	walInst, err := wal_impl.NewWAL(&cfg.WAL)
	if err != nil {
		logger.Get().Fatal("Failed to initialize WAL", zap.Error(err))
	}
	if walInst != nil {
		defer walInst.Close()
	}

	mqttClient, err := mqtt.NewClient(&cfg.MQTT)
	if err != nil {
		logger.Get().Warn("Failed to connect to MQTT", zap.Error(err))
	}
	if mqttClient != nil {
		defer mqttClient.Disconnect()
	}

	cacheInst := cache.NewCache(redisClient.Client)

	diContainer := container.NewContainer(db.DB, redisClient.Client, mqttClient, walInst, cacheInst)
	diContainer.Init(context.Background())
	defer diContainer.Close(context.Background())

	deviceService := service.NewDeviceService(db.DB, redisClient.Client, walInst)
	inferenceService := service.NewInferenceService(db.DB, redisClient.Client, walInst, mqttClient)
	shadowService := service.NewDeviceShadowService(db.DB, redisClient.Client, walInst, mqttClient)
	protocolService := service.NewProtocolService(db.DB, redisClient.Client, walInst)
	ruleService := service.NewRuleEngineService(db.DB, redisClient.Client, walInst, mqttClient)

	ruleService.Start()
	defer ruleService.Stop()

	r := router.NewRouter(
		deviceService,
		diContainer.GetOTAService(),
		diContainer.GetOfflineService(),
		diContainer.GetAggregationService(),
		inferenceService,
		shadowService,
		protocolService,
		ruleService,
	)

	addr := cfg.Server.Host + ":" + strconv.Itoa(cfg.Server.Port)
	go func() {
		logger.Get().Info("Starting server", zap.String("addr", addr))
		if err := r.Run(addr); err != nil {
			logger.Get().Fatal("Failed to start server", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Get().Info("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), cfg.Server.ReadTimeout)
	defer cancel()

	if err := r.Engine().Shutdown(ctx); err != nil {
		logger.Get().Fatal("Server forced to shutdown", zap.Error(err))
	}

	logger.Get().Info("Server exiting")
}
