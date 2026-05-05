package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"gamestats/event-access/config"
	"gamestats/event-access/handler"
	"gamestats/event-access/middleware"
	"gamestats/event-access/storage"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

func main() {
	cfg := config.Load()

	logger := initLogger(cfg)
	defer logger.Sync()

	zap.ReplaceGlobals(logger)

	influxClient, err := storage.NewInfluxDBClient(cfg.InfluxDB)
	if err != nil {
		logger.Fatal("Failed to initialize InfluxDB client", zap.Error(err))
	}
	defer influxClient.Close()

	mysqlClient, err := storage.NewMySQLClient(cfg.MySQL)
	if err != nil {
		logger.Fatal("Failed to initialize MySQL client", zap.Error(err))
	}
	defer mysqlClient.Close()

	gin.SetMode(cfg.Server.Mode)
	router := gin.New()

	router.Use(middleware.RequestID())
	router.Use(middleware.Logger(logger))
	router.Use(middleware.Recovery())
	router.Use(middleware.CORS())

	eventHandler := handler.NewEventHandler(influxClient, mysqlClient, logger, cfg)
	statsHandler := handler.NewStatsHandler(influxClient, mysqlClient, logger)
	configHandler := handler.NewConfigHandler(influxClient, mysqlClient, logger, cfg)

	api := router.Group("/api/v1")
	{
		events := api.Group("/events")
		{
			events.POST("/report", eventHandler.ReportEvents)
			events.GET("/:event_id", eventHandler.GetEvent)
		}

		stats := api.Group("/stats")
		{
			stats.GET("/online", statsHandler.GetOnlineStats)
			stats.GET("/trend", statsHandler.GetTrend)
		}

		api.POST("/heartbeat", eventHandler.Heartbeat)

		config := api.Group("/config")
		{
			config.GET("/sdk", configHandler.GetSDKConfig)
			config.GET("/events", configHandler.ListEventConfigs)
			config.POST("/events", configHandler.CreateEventConfig)
			config.GET("/events/:game_id/:event_type", configHandler.GetEventConfig)
			config.PUT("/events/:game_id/:event_type", configHandler.UpdateEventConfig)
			config.DELETE("/events/:game_id/:event_type", configHandler.DeleteEventConfig)
			config.POST("/cache/clear", configHandler.ClearConfigCache)
		}
	}

	router.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status":    "ok",
			"timestamp": time.Now().UTC().Format(time.RFC3339),
		})
	})

	srv := &http.Server{
		Addr:         fmt.Sprintf(":%d", cfg.Server.Port),
		Handler:      router,
		ReadTimeout:  cfg.Server.ReadTimeout,
		WriteTimeout: cfg.Server.WriteTimeout,
	}

	go func() {
		logger.Info("Server starting", zap.Int("port", cfg.Server.Port))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Failed to start server", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		logger.Fatal("Server forced to shutdown", zap.Error(err))
	}

	logger.Info("Server exited successfully")
}

func initLogger(cfg *config.Config) *zap.Logger {
	var logger *zap.Logger
	var err error

	if cfg.Server.Mode == gin.DebugMode {
		logger, err = zap.NewDevelopment()
	} else {
		loggerConfig := zap.NewProductionConfig()
		loggerConfig.OutputPaths = []string{"stdout", cfg.Log.FilePath}
		logger, err = loggerConfig.Build()
	}

	if err != nil {
		panic(fmt.Sprintf("Failed to initialize logger: %v", err))
	}

	return logger
}
