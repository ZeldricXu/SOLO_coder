package main

import (
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"projectservice/config"
	"projectservice/internal/handler"
	"projectservice/internal/infrastructure/cache"
	"projectservice/internal/infrastructure/database"
	"projectservice/internal/infrastructure/logger"
	"projectservice/internal/infrastructure/monitor"
	"projectservice/internal/service"
)

func main() {
	log, err := logger.New("info", "json", "stdout")
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to initialize logger: %v\n", err)
		os.Exit(1)
	}
	defer log.Sync()

	log.Info("Starting ProjectService...")

	cfg := &config.Config{
		Server: config.ServerConfig{
			Host:         "0.0.0.0",
			Port:         8080,
			ReadTimeout:  30 * time.Second,
			WriteTimeout: 30 * time.Second,
			MaxBodySize:  10 * 1024 * 1024,
		},
		Database: config.DatabaseConfig{
			Host:     "localhost",
			Port:     5432,
			User:     "postgres",
			Password: "postgres",
			DBName:   "projectservice",
			SSLMode:  "disable",
			PoolSize: 10,
		},
		Cache: config.CacheConfig{
			Redis: config.RedisConfig{
				Host:     "localhost",
				Port:     6379,
				Password: "",
				DB:       0,
				PoolSize: 10,
			},
			L1: config.L1CacheConfig{
				MaxEntries: 1000,
				TTL:        5 * time.Minute,
			},
		},
		Logger: config.LoggerConfig{
			Level:  "info",
			Format: "json",
			Output: "stdout",
		},
		Prometheus: config.PrometheusConfig{
			Enabled: true,
			Path:    "/metrics",
		},
	}

	metrics := monitor.NewMetrics()

	db, err := database.New(
		cfg.Database.Host,
		cfg.Database.Port,
		cfg.Database.User,
		cfg.Database.Password,
		cfg.Database.DBName,
		cfg.Database.SSLMode,
		cfg.Database.PoolSize,
	)
	if err != nil {
		log.Fatalw("Failed to connect to database", "error", err)
	}

	if err := db.AutoMigrate(); err != nil {
		log.Warnw("Database migration failed, continuing without migration", "error", err)
	}

	l1Cache := cache.NewL1Cache(cfg.Cache.L1.MaxEntries, cfg.Cache.L1.TTL)
	l2Cache := cache.NewL2Cache(
		cfg.Cache.Redis.Host,
		cfg.Cache.Redis.Port,
		cfg.Cache.Redis.Password,
		cfg.Cache.Redis.DB,
		cfg.Cache.Redis.PoolSize,
		5*time.Minute,
	)
	defer l2Cache.Close()

	mlCache := cache.NewMultiLevelCache(l1Cache, l2Cache)

	vulnService := service.NewVulnerabilityService(db.DB, mlCache, log, metrics)
	scaffoldService := service.NewScaffoldService(db.DB, log, metrics)
	envService := service.NewEnvironmentService(db.DB, log, metrics)
	qualityService := service.NewQualityService(db.DB, log, metrics)
	flagService := service.NewFeatureFlagService(db.DB, log, metrics)
	catalogService := service.NewCatalogService(db.DB, log, metrics)
	apiContractService := service.NewAPIContractService(db.DB, log, metrics)
	docService := service.NewDocumentService(db.DB, log, metrics)

	baseHandler := handler.NewHandler(metrics)

	handlers := handler.NewHandlers(
		baseHandler,
		vulnService,
		scaffoldService,
		envService,
		qualityService,
		flagService,
		catalogService,
		apiContractService,
		docService,
	)

	router := handler.SetupRouter(handlers)

	addr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)

	go func() {
		log.Infow("Server starting", "address", addr)
		if err := router.Run(addr); err != nil {
			log.Fatalw("Server failed to start", "error", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Info("Shutting down server...")

	log.Info("Server stopped")
}
