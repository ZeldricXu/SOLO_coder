package main

import (
	"context"
	"fmt"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"metricplatform/internal/api"
	"metricplatform/internal/config"
	"metricplatform/internal/models"
	"metricplatform/pkg/alertengine"
	"metricplatform/pkg/anomaly"
	"metricplatform/pkg/cache"
	"metricplatform/pkg/dataaccess"
	"metricplatform/pkg/logpipeline"
	"metricplatform/pkg/metrics"
	"metricplatform/pkg/scheduler"
	"metricplatform/pkg/slo"
	"metricplatform/pkg/storage"
	"metricplatform/pkg/tracing"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

type ConsoleNotifier struct {
	logger *zap.Logger
}

func (n *ConsoleNotifier) Send(ctx context.Context, alert *models.Alert) error {
	n.logger.Info("ALERT NOTIFICATION",
		zap.String("alert_id", alert.ID),
		zap.String("state", alert.State),
		zap.String("severity", alert.Severity),
		zap.String("message", alert.Message),
		zap.Float64("value", alert.Value))
	return nil
}

func main() {
	cfg := config.Load()

	logConfig := zap.NewProductionConfig()
	logConfig.Level = zap.NewAtomicLevelAt(zapcore.InfoLevel)
	if cfg.LogLevel == "debug" {
		logConfig.Level = zap.NewAtomicLevelAt(zapcore.DebugLevel)
	}
	logger, _ := logConfig.Build()
	defer logger.Sync()

	logger.Info("Starting Metric Platform Server...")

	dsn := fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=disable",
		cfg.DBHost, cfg.DBPort, cfg.DBUser, cfg.DBPassword, cfg.DBName)

	repo, err := dataaccess.NewRepository(dsn, logger)
	if err != nil {
		logger.Fatal("Failed to create repository", zap.Error(err))
	}

	if err := repo.Init(); err != nil {
		logger.Warn("Database initialization failed, running with in-memory mode", zap.Error(err))
	}

	migrations := []dataaccess.Migration{
		{
			Version: 1,
			Name:    "initial_schema",
			Up: func(db *gorm.DB) error {
				return db.AutoMigrate(
					&models.Entity{},
					&models.Config{},
					&models.RunInstance{},
					&models.MetricsSnapshot{},
				)
			},
			Down: func(db *gorm.DB) error {
				return db.Migrator().DropTable(
					&models.Entity{},
					&models.Config{},
					&models.RunInstance{},
					&models.MetricsSnapshot{},
				)
			},
		},
		{
			Version: 2,
			Name:    "add_alert_tables",
			Up: func(db *gorm.DB) error {
				return db.AutoMigrate(&models.AlertRule{}, &models.Alert{})
			},
			Down: func(db *gorm.DB) error {
				return db.Migrator().DropTable(&models.AlertRule{}, &models.Alert{})
			},
		},
	}

	repo.RegisterMigrations(migrations)
	if err := repo.Migrate(); err != nil {
		logger.Warn("Migration failed", zap.Error(err))
	}

	algorithms := []anomaly.DetectionAlgorithm{
		anomaly.AlgorithmZScore,
		anomaly.AlgorithmMovingAvg,
		anomaly.AlgorithmIQR,
		anomaly.AlgorithmSeasonal,
	}

	anomalyDetector := anomaly.NewDetector(algorithms, 3.0, 100, true, logger,
		anomaly.WithWorkers(4),
		anomaly.WithQueueSize(10000),
		anomaly.WithScalingPolicy(anomaly.ScalingPolicy{
			MinWorkers:        2,
			MaxWorkers:        16,
			ScaleUpThreshold:  0.7,
			ScaleDownThreshold: 0.3,
			ScaleUpFactor:    1.5,
			ScaleDownFactor:  0.7,
			CooldownPeriod:   2 * time.Minute,
			AvgWaitThreshold: 500 * time.Millisecond,
		}),
	)

	metricsCollector := metrics.NewCollector(10000, 1*time.Minute, anomalyDetector, logger)

	metricsCollector.AddAggregation("request_latency", "avg", metrics.AggregationAvg)
	metricsCollector.AddAggregation("request_latency", "p95", metrics.AggregationP95)
	metricsCollector.AddAggregation("request_latency", "p99", metrics.AggregationP99)
	metricsCollector.AddAggregation("error_rate", "avg", metrics.AggregationAvg)
	metricsCollector.AddAggregation("throughput", "sum", metrics.AggregationSum)

	notifiers := []alertengine.Notifier{&ConsoleNotifier{logger: logger}}
	alertEngine := alertengine.NewRuleEvaluator(metricsCollector, notifiers, logger,
		alertengine.WithWorkers(8),
		alertengine.WithQueueSize(10000),
		alertengine.WithNonBlocking(true),
	)

	sliCache := cache.NewMultiLevelCache(logger,
		cache.WithMemoryTTL(1*time.Minute),
		cache.WithRedisTTL(5*time.Minute),
		cache.WithPenetrationProtection(true, 30*time.Second),
	)

	statusCache := cache.NewMultiLevelCache(logger,
		cache.WithMemoryTTL(30*time.Second),
		cache.WithRedisTTL(2*time.Minute),
		cache.WithPenetrationProtection(true, 10*time.Second),
	)

	sloMonitor := slo.NewMonitor(metricsCollector, alertEngine, logger,
		slo.WithCacheEnabled(true),
		slo.WithSliCache(sliCache),
		slo.WithStatusCache(statusCache),
	)

	logPipeline := logpipeline.NewPipeline(10000, 4, logger)
	logPipeline.AddFilter(logpipeline.LevelFilter("info"))
	logPipeline.AddParser(logpipeline.JSONParser())
	logPipeline.SetRouter(logpipeline.LevelRouter())
	logPipeline.AddOutput("error", 1000)
	logPipeline.AddOutput("warning", 1000)
	logPipeline.AddOutput("default", 1000)

	storageConfig := storage.BackupConfig{
		BackupDir:     "./backups",
		RetentionDays: 30,
		Compress:      true,
		Encrypt:       false,
	}
	storageMgr, err := storage.NewManager(repo, storageConfig, logger)
	if err != nil {
		logger.Fatal("Failed to create storage manager", zap.Error(err))
	}

	traceCollector := tracing.NewCollector(10000, 4, repo, logger)
	traceCollector.SetSamplingConfig(&models.SamplingConfig{
		Service:           "default",
		DefaultSampleRate: 0.1,
		TailSampling:      true,
		TailWaitDuration:  30 * time.Second,
		Rules: []models.SamplingRule{
			{
				AttributeKey:   "http.status_code",
				AttributeValue: "500",
				Operator:       "equals",
				SampleRate:     1.0,
			},
			{
				AttributeKey:   "error",
				AttributeValue: "true",
				Operator:       "equals",
				SampleRate:     1.0,
			},
		},
	})

	scheduler := scheduler.NewScheduler(repo, logger)

	scheduler.RegisterHandler("metrics_cleanup", func(ctx context.Context, task *models.Task, run *models.TaskRun) error {
		logger.Info("Running metrics cleanup task")
		return nil
	})

	scheduler.RegisterHandler("slo_evaluation", func(ctx context.Context, task *models.Task, run *models.TaskRun) error {
		logger.Info("Running SLO evaluation task")
		return nil
	})

	if err := scheduler.LoadTasks(); err != nil {
		logger.Warn("Failed to load tasks", zap.Error(err))
	}

	handler := api.NewHandler(
		repo,
		alertEngine,
		sloMonitor,
		anomalyDetector,
		metricsCollector,
		logPipeline,
		storageMgr,
		traceCollector,
		scheduler,
		logger,
	)

	gin.SetMode(gin.ReleaseMode)
	r := gin.Default()

	handler.RegisterRoutes(r)

	alertEngine.Start()
	sloMonitor.Start()
	anomalyDetector.Start()
	metricsCollector.Start()
	logPipeline.Start()
	traceCollector.Start()
	scheduler.Start()

	defer func() {
		alertEngine.Stop()
		sloMonitor.Stop()
		anomalyDetector.Stop()
		metricsCollector.Stop()
		logPipeline.Stop()
		traceCollector.Stop()
		scheduler.Stop()
		repo.Close()
	}()

	server := &http.Server{
		Addr:    fmt.Sprintf(":%d", cfg.ServerPort),
		Handler: r,
	}

	go func() {
		logger.Info("Server starting", zap.Int("port", cfg.ServerPort))
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Server failed to start", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		logger.Fatal("Server forced to shutdown", zap.Error(err))
	}

	logger.Info("Server exited gracefully")
}
