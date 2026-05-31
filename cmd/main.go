package main

import (
	"os"
	"os/signal"
	"syscall"

	"go.uber.org/zap"

	"edgescheduler/internal/api"
	"edgescheduler/internal/common/config"
	"edgescheduler/internal/common/logger"
)

func main() {
	if err := config.Load(); err != nil {
		panic("failed to load config: " + err.Error())
	}

	cfg := config.GetConfig()

	if err := logger.Init(cfg.App.LogLevel); err != nil {
		panic("failed to init logger: " + err.Error())
	}

	logger.Info("Starting EdgeScheduler...",
		zap.String("version", "1.0.0"),
		zap.String("environment", cfg.App.Environment),
	)

	server := api.NewServer(cfg)
	if err := server.Init(cfg); err != nil {
		logger.Fatal("Failed to initialize server", zap.Error(err))
	}

	if err := server.Start(); err != nil {
		logger.Fatal("Failed to start server", zap.Error(err))
	}

	logger.Info("EdgeScheduler is running",
		zap.String("host", cfg.Server.Host),
		zap.String("port", cfg.Server.Port),
	)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("Shutting down EdgeScheduler...")

	if err := server.Stop(); err != nil {
		logger.Error("Error during server shutdown", zap.Error(err))
	}

	logger.Info("EdgeScheduler stopped")
}
