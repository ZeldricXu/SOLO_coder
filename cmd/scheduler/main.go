package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"go.uber.org/zap"

	"github.com/df1-96/experiment/internal/app"
	"github.com/df1-96/experiment/internal/config"
	"github.com/df1-96/experiment/pkg/util"
)

func main() {
	var configPath string
	flag.StringVar(&configPath, "config", "", "Path to configuration file")
	flag.Parse()

	if err := run(configPath); err != nil {
		fmt.Fprintf(os.Stderr, "Scheduler failed: %v\n", err)
		os.Exit(1)
	}
}

func run(configPath string) error {
	cfg, err := config.Load(configPath)
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	if err := util.InitLogger(cfg.Log); err != nil {
		return fmt.Errorf("failed to initialize logger: %w", err)
	}
	defer func() {
		if err := util.Sync(); err != nil {
			fmt.Fprintf(os.Stderr, "Failed to sync logger: %v\n", err)
		}
	}()

	logger := util.With(zap.String("service", "scheduler"))

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	schedulerApp, err := app.NewSchedulerApp(cfg)
	if err != nil {
		return fmt.Errorf("failed to create scheduler app: %w", err)
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		sig := <-sigCh
		logger.Info("Received shutdown signal", zap.String("signal", sig.String()))
		cancel()

		go func() {
			select {
			case <-sigCh:
				logger.Warn("Received second signal, forcing exit")
				os.Exit(1)
			case <-ctx.Done():
			}
		}()
	}()

	if err := schedulerApp.Start(ctx); err != nil {
		return fmt.Errorf("failed to start scheduler app: %w", err)
	}

	<-ctx.Done()

	logger.Info("Shutting down scheduler...")

	if err := schedulerApp.Stop(); err != nil {
		logger.Error("Error during shutdown", zap.Error(err))
		return err
	}

	logger.Info("Scheduler shutdown completed successfully")
	return nil
}
