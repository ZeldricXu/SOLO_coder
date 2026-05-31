package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/edgeplatform/session306/internal/config"
	"github.com/edgeplatform/session306/internal/core"
	"github.com/edgeplatform/session306/internal/data"
	"github.com/edgeplatform/session306/internal/device"
	"github.com/edgeplatform/session306/internal/gateway"
	"github.com/edgeplatform/session306/internal/inference"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/internal/monitoring"
	"github.com/edgeplatform/session306/internal/ota"
	"github.com/edgeplatform/session306/internal/rule_engine"
	"github.com/edgeplatform/session306/internal/storage"
	"github.com/edgeplatform/session306/pkg/events"

	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type App struct {
	logger           *zap.Logger
	dataAccess       *data.DataAccess
	configManager    *config.ConfigManager
	coreProcessor    *core.CoreProcessor
	ruleEngine       *rule_engine.RuleEngine
	inferenceSched   *inference.InferenceScheduler
	otaManager       *ota.OTAManager
	storageManager   *storage.StorageManager
	deviceManager    *device.DeviceManager
	monitoringMgr    *monitoring.MonitoringManager
	apiGateway       *gateway.APIGateway
	eventBus         events.EventBus
	cancel           context.CancelFunc
}

func main() {
	logger := initLogger()
	defer logger.Sync()

	logger.Info("Starting Edge Platform Service...")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	app := &App{
		logger: logger,
		cancel: cancel,
	}

	if err := app.init(ctx); err != nil {
		logger.Fatal("Failed to initialize application", zap.Error(err))
	}

	app.setupSignalHandler()

	if err := app.run(ctx); err != nil {
		logger.Fatal("Application run failed", zap.Error(err))
	}

	logger.Info("Application shutdown complete")
}

func initLogger() *zap.Logger {
	config := zap.NewProductionConfig()
	config.EncoderConfig.TimeKey = "timestamp"
	config.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	config.Level = zap.NewAtomicLevelAt(zap.InfoLevel)

	logger, err := config.Build()
	if err != nil {
		panic(err)
	}
	return logger
}

func (app *App) init(ctx context.Context) error {
	app.logger.Info("Initializing modules...")

	app.eventBus = events.NewInMemoryEventBus()

	app.logger.Info("Initializing data access layer...")
	dbConfig := data.DatabaseConfig{
		Host:            "localhost",
		Port:            5432,
		User:            "postgres",
		Password:        "postgres",
		DBName:          "edgeplatform",
		SSLMode:         "disable",
		MaxOpenConns:    100,
		MaxIdleConns:    25,
		ConnMaxLifetime: 1 * time.Hour,
		ConnMaxIdleTime: 30 * time.Minute,
	}
	redisConfig := data.RedisConfig{
		Host:         "localhost",
		Port:         6379,
		Password:     "",
		DB:           0,
		PoolSize:     100,
		MinIdleConns: 25,
		DialTimeout:  5 * time.Second,
		ReadTimeout:  3 * time.Second,
		WriteTimeout: 3 * time.Second,
	}

	da, err := data.NewDataAccess(dbConfig, redisConfig, app.logger)
	if err != nil {
		return err
	}
	app.dataAccess = da

	if err := app.runMigrations(da); err != nil {
		return err
	}

	app.logger.Info("Initializing monitoring manager...")
	metricRepo := data.NewMetricRepository(da)
	app.monitoringMgr = monitoring.NewMonitoringManager(da, app.eventBus, metricRepo, app.logger)
	if err := app.monitoringMgr.Start(ctx); err != nil {
		return err
	}

	app.logger.Info("Initializing config manager...")
	configRepo := data.NewConfigRepository(da)
	app.configManager = config.NewConfigManager(da, configRepo, app.eventBus, app.logger)

	app.logger.Info("Initializing core processor...")
	entityRepo := data.NewEntityRepository(da)
	runInstanceRepo := data.NewRunInstanceRepository(da)
	app.coreProcessor = core.NewCoreProcessor(
		da,
		app.configManager,
		app.eventBus,
		entityRepo,
		runInstanceRepo,
		app.logger,
		100,
	)

	app.logger.Info("Initializing device manager...")
	app.deviceManager = device.NewDeviceManager(da, app.eventBus, app.logger)
	if err := app.deviceManager.Start(ctx); err != nil {
		return err
	}

	app.logger.Info("Initializing rule engine...")
	app.ruleEngine = rule_engine.NewRuleEngine(da, app.eventBus, app.logger, 10)
	if err := app.ruleEngine.Start(ctx); err != nil {
		return err
	}

	app.logger.Info("Initializing inference scheduler...")
	app.inferenceSched = inference.NewInferenceScheduler(da, app.eventBus, app.logger, 5)
	if err := app.inferenceSched.Start(ctx); err != nil {
		return err
	}

	app.logger.Info("Initializing OTA manager...")
	app.otaManager = ota.NewOTAManager(da, app.configManager, app.eventBus, app.logger, 5)
	if err := app.otaManager.Start(ctx); err != nil {
		return err
	}

	app.logger.Info("Initializing storage manager...")
	app.storageManager = storage.NewStorageManager(da, app.eventBus, app.logger)
	if err := app.storageManager.Start(ctx); err != nil {
		return err
	}

	app.logger.Info("Initializing API gateway...")
	app.apiGateway = gateway.NewAPIGateway(
		app.configManager,
		app.monitoringMgr,
		app.deviceManager,
		app.eventBus,
		app.logger,
	)

	gateway.RegisterHandler("task", app.coreProcessor.Execute)

	app.logger.Info("All modules initialized successfully")
	return nil
}

func (app *App) runMigrations(da *data.DataAccess) error {
	app.logger.Info("Running database migrations...")

	err := da.DB().AutoMigrate(
		&model.Entity{},
		&model.ConfigDefinition{},
		&model.RunInstance{},
		&model.MetricSnapshot{},
		&model.Device{},
		&model.Rule{},
		&model.RuleCondition{},
		&model.RuleAction{},
		&model.AIModel{},
		&model.InferenceTask{},
		&model.Firmware{},
		&model.OTAJob{},
		&model.DeviceUpgrade{},
		&model.FileRecord{},
		&model.LifecyclePolicy{},
	)
	if err != nil {
		app.logger.Warn("Migration completed with warnings", zap.Error(err))
	} else {
		app.logger.Info("Database migrations completed successfully")
	}

	return nil
}

func (app *App) setupSignalHandler() {
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		sig := <-sigCh
		app.logger.Info("Received shutdown signal", zap.String("signal", sig.String()))
		app.shutdown()
	}()
}

func (app *App) shutdown() {
	app.logger.Info("Initiating graceful shutdown...")

	if app.cancel != nil {
		app.cancel()
	}

	if app.eventBus != nil {
		app.eventBus.Close()
	}

	if app.dataAccess != nil {
		app.dataAccess.Close()
	}

	app.logger.Info("Graceful shutdown completed")
}

func (app *App) run(ctx context.Context) error {
	app.logger.Info("Starting API gateway on :8080")

	errCh := make(chan error, 1)
	go func() {
		errCh <- app.apiGateway.Start(ctx)
	}()

	select {
	case <-ctx.Done():
		app.logger.Info("Context cancelled, shutting down...")
		return nil
	case err := <-errCh:
		if err != nil && err.Error() != "http: Server closed" {
			return err
		}
		return nil
	}
}
