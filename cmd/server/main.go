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
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/cache"
	infraconfig "session189/internal/infrastructure/config"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
	"session189/internal/interfaces/api"
	"session189/internal/interfaces/middleware"
	"session189/internal/modules/alerter"
	"session189/internal/modules/anomaly"
	"session189/internal/modules/core"
	"session189/internal/modules/gateway"
	"session189/internal/modules/notifier"
	"session189/internal/modules/profiling"
	"session189/internal/modules/scheduler"
	"session189/internal/modules/slo"
	"session189/internal/modules/storage"
	"session189/internal/modules/tracing"
	appconfig "session189/pkg/config"
	"session189/pkg/eventbus"
)

func main() {
	if err := run(); err != nil {
		fmt.Printf("Server failed: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := infraconfig.Load("")
	if err != nil {
		return fmt.Errorf("load config failed: %w", err)
	}

	if err := logger.Init(logger.Config{
		Level:      cfg.Logger.Level,
		Format:     cfg.Logger.Format,
		OutputPath: cfg.Logger.OutputPath,
	}); err != nil {
		return fmt.Errorf("init logger failed: %w", err)
	}
	defer logger.Sync()

	if err := database.Init(database.Config{
		DSN: cfg.Database.DSN(),
	}); err != nil {
		return fmt.Errorf("init database failed: %w", err)
	}
	defer database.Close()

	if err := cache.Init(cache.Config{
		Addr:     cfg.Redis.Addr(),
		Password: cfg.Redis.Password,
		DB:       cfg.Redis.DB,
		PoolSize: cfg.Redis.PoolSize,
	}); err != nil {
		return fmt.Errorf("init cache failed: %w", err)
	}
	defer cache.Close()

	bus := eventbus.New(4, 1000)
	defer bus.Close()

	configManager := appconfig.NewManager(bus)
	defer configManager.Close()

	baseProfiler := profiling.NewProfiler()
	dynamicProfiler := profiling.NewDynamicProfiler(baseProfiler, configManager, bus)
	defer dynamicProfiler.Close()

	flameGenerator := profiling.NewFlameGraphGenerator("")

	authManager := gateway.NewAuthManager(cfg.JWT.Secret, cfg.JWT.ExpireHours)
	tokenBucketLimiter := gateway.NewTokenBucketLimiter(cfg.RateLimit.RequestsPerSecond, cfg.RateLimit.BurstSize)
	fixedWindowLimiter := gateway.NewFixedWindowLimiter(cfg.RateLimit.RequestsPerMinute, time.Minute)
	slidingWindowLimiter := gateway.NewSlidingWindowLimiter(cfg.RateLimit.RequestsPerMinute, time.Minute)

	strategyRegistry := gateway.NewStrategyRegistry(configManager, bus)
	strategyRegistry.RegisterAuthStrategy(gateway.NewJWTAuthStrategy(authManager))
	strategyRegistry.RegisterAuthStrategy(gateway.NewAPIKeyAuthStrategy(authManager))
	strategyRegistry.RegisterRateLimitStrategy(gateway.NewTokenBucketStrategy(tokenBucketLimiter))
	strategyRegistry.RegisterRateLimitStrategy(gateway.NewFixedWindowStrategy(fixedWindowLimiter))
	strategyRegistry.RegisterRateLimitStrategy(gateway.NewSlidingWindowStrategy(slidingWindowLimiter))

	alertEngine := alerter.NewAlertEngine()

	notifier, err := notifier.NewNotifier()
	if err != nil {
		return fmt.Errorf("init notifier failed: %w", err)
	}

	alertEngine.SetNotifier(func(ctx context.Context, event *domain.AlertEvent) error {
		var rule domain.AlertRule
		if err := database.DB.WithContext(ctx).Where("rule_id = ?", event.RuleID).First(&rule).Error; err != nil {
			return err
		}
		return notifier.SendAlert(ctx, event, &rule)
	})

	backupManager := storage.NewBackupManager("./backups")
	restoreManager := storage.NewRestoreManager()

	anomalyDetector := anomaly.NewAnomalyDetector()

	taskExecutor := core.NewTaskExecutor(5)
	taskExecutor.RegisterDefaultHandlers(baseProfiler)

	baseScheduler := scheduler.NewScheduler()
	asyncScheduler := scheduler.NewAsyncScheduler(baseScheduler, bus,
		scheduler.WithWorkerCount(5),
		scheduler.WithQueueSize(100),
	)
	defer asyncScheduler.Close()

	asyncScheduler.SetTaskHandler(func(ctx context.Context, task *domain.Task) error {
		return taskExecutor.ExecuteTask(ctx, task)
	})

	sloMonitor := slo.NewSLOMonitor()
	budgetManager := slo.NewErrorBudgetManager()

	sampler := tracing.NewSampler()
	if err := sampler.LoadPolicies(); err != nil {
		logger.Warn("Failed to load sampling policies", zap.Error(err))
	}
	spanCollector := tracing.NewSpanCollector(1000, 5*time.Second, sampler)

	if err := alertEngine.Start(); err != nil {
		return fmt.Errorf("start alert engine failed: %w", err)
	}
	if err := baseScheduler.Start(); err != nil {
		return fmt.Errorf("start scheduler failed: %w", err)
	}
	if err := sloMonitor.Start(); err != nil {
		return fmt.Errorf("start slo monitor failed: %w", err)
	}
	spanCollector.Start()
	taskExecutor.Start()

	handler := api.NewHandler(
		baseProfiler,
		flameGenerator,
		alertEngine,
		notifier,
		backupManager,
		restoreManager,
		anomalyDetector,
		taskExecutor,
		baseScheduler,
		sloMonitor,
		budgetManager,
		spanCollector,
		sampler,
	)

	if cfg.Server.Mode == "release" {
		gin.SetMode(gin.ReleaseMode)
	}

	r := gin.New()
	r.Use(middleware.Recovery())
	r.Use(middleware.RequestID())
	r.Use(middleware.CORS())
	r.Use(middleware.RequestLogger())

	authMiddleware := middleware.NewAuthMiddleware(authManager)
	rateLimitMiddleware := middleware.NewRateLimitMiddleware(tokenBucketLimiter)

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok"})
	})

	apiV1 := r.Group("/api/v1")
	apiV1.Use(rateLimitMiddleware.Limit())
	apiV1.Use(authMiddleware.CombinedAuth())

	handler.RegisterRoutes(r.Group(""))

	srv := &http.Server{
		Addr:         cfg.Server.Addr(),
		Handler:      r,
		ReadTimeout:  time.Duration(cfg.Server.ReadTimeout) * time.Second,
		WriteTimeout: time.Duration(cfg.Server.WriteTimeout) * time.Second,
	}

	go func() {
		logger.Info("Server starting", zap.String("addr", srv.Addr))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Server failed to start", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logger.Info("Shutdown signal received")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	alertEngine.Stop()
	baseScheduler.Stop()
	sloMonitor.Stop()
	spanCollector.Stop()
	taskExecutor.Stop()
	baseProfiler.Stop()

	if err := srv.Shutdown(ctx); err != nil {
		logger.Fatal("Server forced to shutdown", zap.Error(err))
	}

	logger.Info("Server exited gracefully")
	return nil
}
