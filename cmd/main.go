package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"llmgateway/internal/api"
	"llmgateway/internal/infrastructure/cache"
	"llmgateway/internal/infrastructure/config"
	"llmgateway/internal/infrastructure/database"
	"llmgateway/internal/infrastructure/logger"
	"llmgateway/internal/service/adversarial"
	"llmgateway/internal/service/document"
	"llmgateway/internal/service/evaluation"
	"llmgateway/internal/service/feature_store"
	"llmgateway/internal/service/gateway"
	"llmgateway/internal/service/model_registry"
	"llmgateway/internal/service/prompt_eval"
	"llmgateway/internal/service/scheduler"
)

var (
	version   = "dev"
	commit    = "none"
	buildTime = "unknown"
)

func main() {
	showVersion := flag.Bool("version", false, "Show version information")
	flag.Parse()

	if *showVersion {
		fmt.Printf("LLMGateway v%s\n", version)
		fmt.Printf("  Commit: %s\n", commit)
		fmt.Printf("  Build Time: %s\n", buildTime)
		os.Exit(0)
	}

	cfgPath := os.Getenv("CONFIG_PATH")
	if cfgPath == "" {
		cfgPath = "config.yaml"
	}
	if len(os.Args) > 1 && os.Args[1] != "--version" {
		cfgPath = os.Args[1]
	}

	cfg, err := config.Load(cfgPath)
	if err != nil {
		fmt.Printf("Failed to load config: %v\n", err)
		os.Exit(1)
	}

	logCfg := logger.Config{
		Level:  cfg.Log.Level,
		Format: cfg.Log.Format,
		Output: cfg.Log.Output,
	}
	if err := logger.Init(logCfg); err != nil {
		fmt.Printf("Failed to init logger: %v\n", err)
		os.Exit(1)
	}
	defer logger.Sync()

	logger.Info("Starting LLMGateway...",
		"version", version,
		"commit", commit,
		"build_time", buildTime,
	)

	dbCfg := database.Config{
		DSN:          cfg.Database.DSN(),
		MaxOpenConns: cfg.Database.MaxOpenConns,
		MaxIdleConns: cfg.Database.MaxIdleConns,
	}
	if err := database.Init(dbCfg); err != nil {
		logger.Fatal("Failed to init database", "error", err)
	}

	if err := database.AutoMigrate(); err != nil {
		logger.Fatal("Failed to migrate database", "error", err)
	}

	cacheCfg := cache.Config{
		Addr:     cfg.Redis.Addr(),
		Password: cfg.Redis.Password,
		DB:       cfg.Redis.DB,
		PoolSize: cfg.Redis.PoolSize,
	}
	if err := cache.Init(cacheCfg); err != nil {
		logger.Fatal("Failed to init cache", "error", err)
	}
	defer cache.Close()

	modelRegistry := model_registry.NewService()

	gatewaySvc, err := gateway.NewService(cfg)
	if err != nil {
		logger.Fatal("Failed to init gateway service", "error", err)
	}

	schedulerCfg := &config.SchedulerConfig{
		GPUResources:      cfg.Scheduler.GPUResources,
		PreemptionEnabled: cfg.Scheduler.PreemptionEnabled,
		MaxQueueSize:      cfg.Scheduler.MaxQueueSize,
	}
	schedulerSvc := scheduler.NewService(schedulerCfg)
	defer schedulerSvc.Stop()

	promptEvalSvc := prompt_eval.NewService()
	evaluationSvc := evaluation.NewService()
	adversarialSvc := adversarial.NewService()
	documentSvc := document.NewService()
	featureStoreSvc := feature_store.NewService()

	r := api.SetupRouter(
		cfg,
		modelRegistry,
		gatewaySvc,
		schedulerSvc,
		promptEvalSvc,
		evaluationSvc,
		adversarialSvc,
		documentSvc,
		featureStoreSvc,
	)

	addr := fmt.Sprintf(":%d", cfg.Server.Port)
	srv := &http.Server{
		Addr:    addr,
		Handler: r,
	}

	go func() {
		logger.Info("Server starting", "addr", addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Failed to start server", "error", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		logger.Error("Server forced to shutdown", "error", err)
	}

	logger.Info("Server exited")
}
