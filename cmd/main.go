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

	"notificationplatform/config"
	"notificationplatform/internal/common/cache"
	"notificationplatform/internal/common/database"
	"notificationplatform/internal/common/logger"
	"notificationplatform/internal/modules/adversarial"
	"notificationplatform/internal/modules/notification"
	"notificationplatform/internal/modules/promptexp"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

func main() {
	cfg := config.Load()

	logger.Init(cfg.Logger.Level)
	log := logger.Get()

	log.Info("starting notification service",
		zap.String("host", cfg.Server.Host),
		zap.Int("port", cfg.Server.Port),
	)

	database.Init(&cfg.Database)
	cache.Init(&cfg.Redis)

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()

	r.Use(gin.Recovery())
	r.Use(LoggingMiddleware())
	r.Use(CORSMiddleware())

	apiV1 := r.Group("/api/v1")

	notifHandler := notification.NewHandler()
	notifHandler.RegisterRoutes(apiV1)

	notifService := notification.NewService()
	notifService.Start()
	defer notifService.Stop()

	promptHandler := promptexp.NewHandler()
	promptHandler.RegisterRoutes(apiV1)

	adversarialHandler := adversarial.NewHandler()
	adversarialHandler.RegisterRoutes(apiV1)

	server := &http.Server{
		Addr:    fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port),
		Handler: r,
	}

	go func() {
		log.Info("HTTP server starting", zap.String("addr", server.Addr))
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal("server failed to start", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Info("shutdown signal received, gracefully shutting down...")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		log.Error("server forced to shutdown", zap.Error(err))
	}

	log.Info("notification service exited successfully")
}

func LoggingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		query := c.Request.URL.RawQuery

		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = fmt.Sprintf("trace_%d", time.Now().UnixNano())
		}

		ctx := logger.ToContext(c.Request.Context(), logger.Get().With(
			zap.String("trace_id", traceID),
			zap.String("path", path),
			zap.String("method", c.Request.Method),
		))
		c.Request = c.Request.WithContext(ctx)

		c.Next()

		latency := time.Since(start)
		statusCode := c.Writer.Status()
		clientIP := c.ClientIP()

		logger.FromContext(ctx).Info("request completed",
			zap.Int("status_code", statusCode),
			zap.Duration("latency", latency),
			zap.String("client_ip", clientIP),
			zap.String("query", query),
			zap.String("user_agent", c.Request.UserAgent()),
		)
	}
}

func CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Trace-ID")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}
