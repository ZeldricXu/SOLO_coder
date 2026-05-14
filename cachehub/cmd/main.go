package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/cachehub/internal/pkg/alert"
	"github.com/cachehub/internal/pkg/api"
	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/cache_readwrite"
	"github.com/cachehub/internal/pkg/expiration"
	"github.com/cachehub/internal/pkg/monitoring"
	"github.com/cachehub/internal/pkg/preheating"
	"github.com/cachehub/internal/pkg/strategy"
	"github.com/cachehub/internal/pkg/synchronization"
	"github.com/sirupsen/logrus"
)

func main() {
	logger := logrus.New()
	logger.SetFormatter(&logrus.TextFormatter{
		FullTimestamp: true,
	})
	logger.SetLevel(logrus.InfoLevel)

	logger.Info("Starting CacheHub distributed cache management service...")

	cm := cache_manager.NewCacheManager(logger)
	sm := strategy.NewStrategyManager(cm, logger)
	rw := cache_readwrite.NewCacheReadWrite(cm, sm, logger)
	mm := monitoring.NewMonitoringManager(cm, logger)
	em := expiration.NewExpirationManager(cm, logger)
	pm := preheating.NewPreheatingManager(cm, logger)
	syncM := synchronization.NewSyncManager(cm, logger)
	am := alert.NewAlertManager(cm, mm, logger)

	apiServer := api.NewAPIServer(cm, rw, sm, mm, em, pm, syncM, am, logger)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go mm.Start(ctx, 10*time.Second)
	go em.Start(ctx, 30*time.Second)
	go syncM.Start(ctx, 5*time.Second)
	go am.Start(ctx, 15*time.Second)

	serverErr := make(chan error, 1)
	go func() {
		if err := apiServer.Run(":8080"); err != nil {
			serverErr <- err
		}
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case err := <-serverErr:
		logger.Errorf("API server error: %v", err)
	case sig := <-sigCh:
		logger.Infof("Received signal: %s, shutting down...", sig)
	}

	cancel()
	logger.Info("CacheHub service stopped")
}
