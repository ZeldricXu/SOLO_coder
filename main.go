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

	"loglevelplatform/internal/common/config"
	"loglevelplatform/internal/common/database"
	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/modules/anomaly_detection"
	"loglevelplatform/internal/modules/core_processing"
	"loglevelplatform/internal/modules/data_access"
	"loglevelplatform/internal/modules/log_level"
	"loglevelplatform/internal/modules/log_pipeline"
	"loglevelplatform/internal/modules/metrics_aggregation"
	"loglevelplatform/internal/modules/monitoring"
	"loglevelplatform/internal/modules/notification"
	"loglevelplatform/internal/modules/profiling"
	"loglevelplatform/internal/modules/scheduler"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

func main() {
	cfg := config.Load()

	if err := logger.Init(cfg.Logger.Level); err != nil {
		log.Fatalf("Failed to initialize logger: %v", err)
	}

	log := logger.GetLogger()
	log.Info("starting loglevelplatform service",
		zap.String("version", "1.0.0"),
		zap.Int("port", cfg.Server.Port),
	)

	if err := database.Init(&cfg.Database); err != nil {
		log.Warn("failed to initialize database, running in-memory mode", zap.Error(err))
	} else {
		log.Info("database initialized successfully")
	}

	if err := database.InitRedis(&cfg.Redis); err != nil {
		log.Warn("failed to initialize redis, running without redis cache", zap.Error(err))
	} else {
		log.Info("redis initialized successfully")
	}

	monitoringService := monitoring.NewService()
	logLevelService := log_level.NewService()
	schedulerService := scheduler.NewService()
	coreProcessingService := core_processing.NewService()
	metricsAggregationService := metrics_aggregation.NewService()
	profilingService := profiling.NewService()
	notificationService := notification.NewService()
	logPipelineService := log_pipeline.NewService()
	anomalyDetectionService := anomaly_detection.NewService()
	dataAccessService := data_access.NewService(data_access.CacheConfig{
		DefaultTTL: cfg.Cache.DefaultTTL,
		MaxEntries: cfg.Cache.MaxEntries,
		Strategy:   data_access.CacheStrategyCacheAside,
		EnableRedis: cfg.Redis.Host != "localhost" || cfg.Redis.Port != 6379,
	})

	go schedulerService.Start()
	go metricsAggregationService.Start()
	go notificationService.Start()
	go logPipelineService.Start()

	ctx := context.Background()
	if err := logLevelService.LoadConfigs(ctx); err != nil {
		log.Warn("failed to load log level configs", zap.Error(err))
	}

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()

	r.Use(gin.Recovery())
	r.Use(requestLogger(monitoringService))
	r.Use(corsMiddleware())

	api := r.Group("/api/v1")

	monitoringHandler := monitoring.NewHandler(monitoringService)
	monitoringHandler.RegisterRoutes(api)

	logLevelHandler := log_level.NewHandler(logLevelService)
	logLevelHandler.RegisterRoutes(api)

	schedulerHandler := scheduler.NewHandler(schedulerService)
	schedulerHandler.RegisterRoutes(api)

	coreProcessingHandler := core_processing.NewHandler(coreProcessingService)
	coreProcessingHandler.RegisterRoutes(api)

	metricsAggregationHandler := metrics_aggregation.NewHandler(metricsAggregationService)
	metricsAggregationHandler.RegisterRoutes(api)

	profilingHandler := profiling.NewHandler(profilingService)
	profilingHandler.RegisterRoutes(api)

	notificationHandler := notification.NewHandler(notificationService)
	notificationHandler.RegisterRoutes(api)

	logPipelineHandler := log_pipeline.NewHandler(logPipelineService)
	logPipelineHandler.RegisterRoutes(api)

	anomalyDetectionHandler := anomaly_detection.NewHandler(anomalyDetectionService)
	anomalyDetectionHandler.RegisterRoutes(api)

	dataAccessHandler := data_access.NewHandler(dataAccessService)
	dataAccessHandler.RegisterRoutes(api)

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status":    "ok",
			"version":   "1.0.0",
			"timestamp": time.Now().Format(time.RFC3339),
		})
	})

	server := &http.Server{
		Addr:         fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port),
		Handler:      r,
		ReadTimeout:  cfg.Server.ReadTimeout,
		WriteTimeout: cfg.Server.WriteTimeout,
	}

	go func() {
		log.Info("HTTP server starting", zap.String("addr", server.Addr))
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("HTTP server failed to start", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Info("shutting down service...")

	dataAccessService.Shutdown()
	schedulerService.Stop()
	metricsAggregationService.Stop()
	notificationService.Stop()
	logPipelineService.Stop()

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Error("HTTP server shutdown error", zap.Error(err))
	}

	log.Info("service shutdown complete")
}

func requestLogger(monitoringSvc *monitoring.Service) gin.HandlerFunc {
	return func(c *gin.Context) {
		startTime := time.Now()
		path := c.Request.URL.Path

		c.Next()

		duration := time.Since(startTime)
		status := c.Writer.Status()

		monitoringSvc.RecordHTTPRequest(c.Request.Method, path, status, duration)

		log := logger.GetLogger()
		log.Info("HTTP request",
			zap.String("method", c.Request.Method),
			zap.String("path", path),
			zap.Int("status", status),
			zap.Duration("duration", duration),
			zap.String("client_ip", c.ClientIP()),
		)
	}
}

func corsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}
