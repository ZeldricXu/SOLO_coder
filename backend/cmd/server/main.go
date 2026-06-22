package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/featureflag/platform/internal/api"
	"github.com/featureflag/platform/internal/config"
	"github.com/featureflag/platform/internal/dao"
	"github.com/featureflag/platform/internal/middleware"
	"github.com/featureflag/platform/internal/service"
	"github.com/featureflag/platform/pkg/logger"
)

func main() {
	if err := config.Load(); err != nil {
		panic(fmt.Sprintf("load config error: %v", err))
	}

	if err := logger.Init(&logger.Config{
		Level:      "info",
		MaxSize:    100,
		MaxBackups: 3,
		MaxAge:     30,
		Compress:   true,
		Console:    true,
	}); err != nil {
		panic(fmt.Sprintf("init logger error: %v", err))
	}
	defer logger.Sync()

	if err := dao.InitDB(); err != nil {
		logger.Fatalf("init db error: %v", err)
	}
	defer dao.CloseDB()

	var kafkaProducer *service.KafkaProducer
	if len(config.AppConfig.Kafka.Brokers) > 0 {
		kafkaProducer = service.NewKafkaProducer()
		defer kafkaProducer.Close()
		logger.Info("kafka producer initialized")
	}

	gin.SetMode(config.AppConfig.Server.Mode)
	r := gin.New()

	r.Use(middleware.Recovery())
	r.Use(middleware.CORS())
	r.Use(middleware.RequestID())
	r.Use(middleware.Logger())
	r.Use(middleware.Auth())
	r.Use(middleware.RateLimit(1000, time.Minute))

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status":  "ok",
			"version": "1.0.0",
			"time":    time.Now().Format(time.RFC3339),
		})
	})

	apiV1 := r.Group("/api/v1")
	{
		switchHandler := api.NewSwitchHandler()
		switchHandler.Init(kafkaProducer)
		switchHandler.RegisterRoutes(apiV1)
	}

	autoRollbackService := service.NewAutoRollbackService()
	autoRollbackService.SetSwitchService(switchHandler.switchService)
	autoRollbackService.SetKafkaProducer(kafkaProducer)
	if config.AppConfig.AutoRollback.Enabled {
		autoRollbackService.Start()
		defer autoRollbackService.Stop()
	}

	scheduleService := service.NewScheduleService()
	scheduleService.SetSwitchService(switchHandler.switchService)
	scheduleService.Start()
	defer scheduleService.Stop()

	addr := fmt.Sprintf(":%d", config.AppConfig.Server.Port)
	srv := &http.Server{
		Addr:    addr,
		Handler: r,
	}

	go func() {
		logger.Infof("server starting on %s", addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatalf("server error: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logger.Info("shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		logger.Fatalf("server shutdown error: %v", err)
	}

	logger.Info("server exited")
}
