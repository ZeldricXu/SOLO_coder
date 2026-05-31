package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/dataplatform/engine/internal/api"
	"github.com/dataplatform/engine/internal/common/config"
	"github.com/dataplatform/engine/internal/domain"
	"github.com/dataplatform/engine/internal/domain/adversarial"
	"github.com/dataplatform/engine/internal/domain/gateway"
	"github.com/dataplatform/engine/internal/domain/gpu"
	"github.com/dataplatform/engine/internal/domain/logger"
	"github.com/dataplatform/engine/internal/domain/notification"
	"github.com/dataplatform/engine/internal/domain/processing"
	"github.com/dataplatform/engine/internal/domain/prompt"
	"github.com/dataplatform/engine/internal/domain/scheduler"
	"github.com/dataplatform/engine/internal/domain/storage"
	"github.com/gin-gonic/gin"
)

func main() {
	cfg := config.Load()

	logLevel := domain.LogLevelInfo
	switch cfg.Log.Level {
	case "debug":
		logLevel = domain.LogLevelDebug
	case "warn":
		logLevel = domain.LogLevelWarn
	case "error":
		logLevel = domain.LogLevelError
	}

	appLogger := logger.New(logLevel, cfg.Log.Format, cfg.Log.Output, cfg.Log.FilePath)
	defer appLogger.Sync()

	appLogger.Info("Starting data platform engine",
		domain.String("host", cfg.Server.Host),
		domain.Int("port", cfg.Server.Port),
	)

	gpuResourceManager := gpu.NewGPUResourceManager(
		cfg.GPU.NodeID,
		cfg.GPU.DeviceIndices,
		cfg.GPU.VRAMPerDeviceMB,
	)

	persistencePath := filepath.Join(cfg.Storage.LocalPath, "gpu_tasks")
	persistenceStore, err := gpu.NewFilePersistenceStore(persistencePath)
	if err != nil {
		appLogger.Warn("Failed to create GPU persistence store, running without persistence",
			domain.Error(err),
		)
	}

	var persistenceScheduler *gpu.PersistenceScheduler
	var gpuScheduler domain.GPUScheduler

	if persistenceStore != nil {
		persistenceScheduler = gpu.NewPersistenceScheduler(
			gpuResourceManager,
			cfg.GPU.PreemptionEnabled,
			4,
			persistenceStore,
			true,
			appLogger,
		)
		gpuScheduler = persistenceScheduler

		recoveredCount, err := persistenceScheduler.Recover(context.Background())
		if err != nil {
			appLogger.Warn("Failed to recover GPU tasks", domain.Error(err))
		} else {
			appLogger.Info("GPU task recovery complete",
				domain.Int("recovered_count", recoveredCount),
			)
		}
	} else {
		baseScheduler := gpu.NewGPUScheduler(
			gpuResourceManager,
			cfg.GPU.PreemptionEnabled,
			4,
			appLogger,
		)
		gpuScheduler = baseScheduler
	}
	defer gpuScheduler.Shutdown(context.Background())

	baseProcessor := processing.NewDataProcessor(appLogger)
	baseProcessor.RegisterRule(&processing.TransformRule{
		ID:      "rename_fields",
		Name:    "Rename Fields",
		Type:    "rename",
		Enabled: true,
		Config: map[string]interface{}{
			"mappings": map[string]interface{}{
				"old_name": "new_name",
			},
		},
	})

	baseProcessor.RegisterSchema(&processing.Schema{
		Name:    "standard",
		Version: "1.0",
		Fields: []*processing.FieldSchema{
			{Name: "id", Type: "string", Required: true},
			{Name: "name", Type: "string", Required: true},
			{Name: "status", Type: "string", Required: false, Default: "active"},
		},
	})

	cacheConfig := processing.CacheConfig{
		Enabled:    true,
		MaxSizeMB:  100,
		MaxEntries: 10000,
		TTLSeconds: 3600,
	}
	cachedProcessor := processing.NewCachedProcessor(baseProcessor, cacheConfig, appLogger)

	var processor processing.DataProcessor = baseProcessor
	if cachedProcessor != nil {
		processor = cachedProcessor
	}

	loadBalancer := gateway.NewRoundRobinLoadBalancer()

	configPath := filepath.Join(cfg.Storage.LocalPath, "gateway_config.json")
	configManager, err := gateway.NewDynamicConfigManager(configPath, 10, appLogger)
	if err != nil {
		appLogger.Warn("Failed to create dynamic config manager, using static config",
			domain.Error(err),
		)
	}

	var inferenceGateway domain.InferenceGateway
	var dynamicGateway *gateway.DynamicGateway

	if configManager != nil {
		dynamicGateway, err = gateway.NewDynamicGateway(
			configManager,
			loadBalancer,
			appLogger,
		)
		if err != nil {
			appLogger.Warn("Failed to create dynamic gateway, falling back to static",
				domain.Error(err),
			)
		} else {
			inferenceGateway = dynamicGateway
		}
	}

	if inferenceGateway == nil {
		staticGateway := gateway.NewInferenceGatewayImpl(
			loadBalancer,
			cfg.Gateway.CircuitThreshold,
			time.Duration(cfg.Gateway.CircuitTimeoutMs)*time.Millisecond,
			appLogger,
		)
		inferenceGateway = staticGateway

		_ = staticGateway.RegisterProvider(gateway.NewHTTPModelProvider(&gateway.ProviderConfig{
			Name:       "openai",
			BaseURL:    "https://api.openai.com/v1",
			APIKey:     os.Getenv("OPENAI_API_KEY"),
			Models:     []string{"gpt-3.5-turbo", "gpt-4"},
			Priority:   1,
			Weight:     50,
			TimeoutMs:  cfg.Gateway.DefaultTimeoutMs,
			MaxRetries: cfg.Gateway.DefaultMaxRetries,
		}))
	}

	adversarialGenerator := adversarial.NewAdversarialGeneratorImpl(inferenceGateway, appLogger)

	taskScheduler := scheduler.NewTaskSchedulerImpl(appLogger)
	taskScheduler.Start()
	defer taskScheduler.Shutdown(context.Background())

	_, _ = taskScheduler.Schedule(context.Background(), &scheduler.ScheduledJob{
		Name:     "heartbeat",
		Type:     scheduler.JobTypeInterval,
		IntervalMs: 60000,
		Handler: func(ctx context.Context, job *scheduler.ScheduledJob) error {
			appLogger.Debug("Heartbeat", domain.String("job_id", job.ID))
			return nil
		},
	})

	_, _ = taskScheduler.Schedule(context.Background(), &scheduler.ScheduledJob{
		Name:     "cache_cleanup",
		Type:     scheduler.JobTypeInterval,
		IntervalMs: 300000,
		Handler: func(ctx context.Context, job *scheduler.ScheduledJob) error {
			if cachedProcessor != nil {
				stats := cachedProcessor.GetCacheStats()
				appLogger.Info("Cache stats",
					domain.Float64("hit_rate", cachedProcessor.GetHitRate()),
					domain.Int64("hits", stats.Hits),
					domain.Int64("misses", stats.Misses),
					domain.Int("entries", stats.EntryCount),
				)
			}
			return nil
		},
	})

	notifier := notification.NewNotifierImpl(appLogger)
	notifier.AddChannel(notification.NewConsoleChannel())

	storageBackend, err := storage.NewLocalStorageBackend(cfg.Storage.LocalPath)
	if err != nil {
		log.Fatalf("Failed to initialize storage: %v", err)
	}
	storageManager := storage.NewStorageManagerImpl(storageBackend, appLogger)

	promptManager := prompt.NewPromptExperimentManagerImpl(appLogger)

	if os.Getenv("GIN_MODE") == "release" {
		gin.SetMode(gin.ReleaseMode)
	}

	r := gin.Default()

	r.Use(func(c *gin.Context) {
		start := time.Now()
		c.Next()
		appLogger.Info("HTTP request",
			domain.String("method", c.Request.Method),
			domain.String("path", c.Request.URL.Path),
			domain.Int("status", c.Writer.Status()),
			domain.Int64("duration_ms", time.Since(start).Milliseconds()),
		)
	})

	handler := api.NewAPIHandler(
		gpuScheduler,
		persistenceScheduler,
		processor,
		cachedProcessor,
		inferenceGateway,
		dynamicGateway,
		configManager,
		adversarialGenerator,
		taskScheduler,
		appLogger,
		notifier,
		storageManager,
		promptManager,
	)
	handler.RegisterRoutes(r)

	addr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)
	srv := &http.Server{
		Addr:         addr,
		Handler:      r,
		ReadTimeout:  cfg.Server.ReadTimeout,
		WriteTimeout: cfg.Server.WriteTimeout,
	}

	go func() {
		appLogger.Info("Server listening", domain.String("addr", addr))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			appLogger.Fatal("Server failed to start", domain.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	appLogger.Info("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if dynamicGateway != nil {
		dynamicGateway.Shutdown()
	}

	if err := srv.Shutdown(ctx); err != nil {
		appLogger.Fatal("Server forced to shutdown", domain.Error(err))
	}

	appLogger.Info("Server exiting")
}
