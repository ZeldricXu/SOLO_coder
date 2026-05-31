package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/solocoder/session148/internal/application"
	"github.com/solocoder/session148/internal/domain"
	"github.com/solocoder/session148/internal/infrastructure/audit"
	"github.com/solocoder/session148/internal/infrastructure/config"
	"github.com/solocoder/session148/internal/infrastructure/dataaccess"
	"github.com/solocoder/session148/internal/infrastructure/federated"
	"github.com/solocoder/session148/internal/infrastructure/logging"
	"github.com/solocoder/session148/internal/infrastructure/masking"
	"github.com/solocoder/session148/internal/infrastructure/monitoring"
	"github.com/solocoder/session148/internal/infrastructure/storage"
	"github.com/solocoder/session148/internal/interfaces/api"
)

func main() {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	logger, err := logging.NewZapLogger(logging.LoggerConfig{
		Level:      "info",
		OutputPath: "./logs/app.log",
		MaxSizeMB:  100,
		MaxBackups: 5,
		MaxAgeDays: 30,
		Compress:   true,
	})
	if err != nil {
		log.Fatalf("Failed to initialize logger: %v", err)
	}
	defer logger.Sync()

	logger.Info("Starting application...")

	processor := application.NewDataProcessorService(logger)

	configMgr := config.NewFileConfigManager(config.ConfigManagerConfig{
		ConfigDir: "./config",
		Logger:    logger,
	})

	storageMgr, err := storage.NewLocalStorageManager(storage.StorageConfig{
		BackupDir: "./backups",
		Logger:    logger,
	})
	if err != nil {
		logger.Fatal("Failed to initialize storage manager", "error", err)
	}

	monitor := monitoring.NewInMemoryMonitor(monitoring.MonitorConfig{Logger: logger})
	monitor.RegisterNotifier("console", monitoring.NewConsoleNotifier(logger))
	monitor.AddRule(domain.AlertRule{
		ID:                    "rule_001",
		Name:                  "High error rate",
		Metric:                "request.processing_error",
		Threshold:             5,
		Operator:              ">",
		Enabled:               true,
		Severity:              "warning",
		NotificationChannel:   "console",
	})
	monitor.StartRuleEvaluator(ctx, 30*time.Second)

	auditTrail, err := audit.NewHashChainAuditTrail(audit.AuditConfig{
		StoragePath: "./audit",
		Logger:      logger,
	})
	if err != nil {
		logger.Fatal("Failed to initialize audit trail", "error", err)
	}

	masker := masking.NewRoleBasedMasker(masking.MaskingConfig{Logger: logger})

	dataAccessor, err := dataaccess.NewFileDataAccessor(dataaccess.DataAccessorConfig{
		DataDir: "./data",
		Logger:  logger,
	})
	if err != nil {
		logger.Fatal("Failed to initialize data accessor", "error", err)
	}

	flCoord := federated.NewFLCoordinator(federated.CoordinatorConfig{Logger: logger})
	flCoord.RegisterClient("client_001")
	flCoord.RegisterClient("client_002")
	flCoord.CreateModel("linear_regression", 10)

	appService := application.NewAppService(application.ServiceDeps{
		Logger:     logger,
		Processor:  processor,
		ConfigMgr:  configMgr,
		Storage:    storageMgr,
		Monitor:    monitor,
		Audit:      auditTrail,
		Masker:     masker,
		DataAccess: dataAccessor,
		FLCoord:    flCoord,
	})

	handler := api.NewAPIHandler(appService, logger)
	router := api.SetupRouter(handler)

	go func() {
		logger.Info("HTTP server starting on :8080")
		if err := router.Run(":8080"); err != nil {
			logger.Error("HTTP server stopped", "error", err)
			cancel()
		}
	}()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	logger.Info("Shutting down...")
	cancel()

	time.Sleep(2 * time.Second)
	logger.Info("Application stopped")
}
