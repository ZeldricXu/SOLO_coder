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
	"go.uber.org/zap/zapcore"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"

	"session133/internal/apigateway"
	"session133/internal/config"
	"session133/internal/database"
	"session133/internal/featurestore"
	"session133/internal/gpu"
	"session133/internal/model"
	"session133/internal/monitoring"
	"session133/internal/prompt"
	"session133/internal/storage"
)

type Config struct {
	ServerAddr string
	DBHost     string
	DBPort     string
	DBUser     string
	DBPassword string
	DBName     string
	JWTSecret  string
	RedisAddr  string
	StorageDir string
}

func loadConfig() *Config {
	return &Config{
		ServerAddr: getEnv("SERVER_ADDR", ":8080"),
		DBHost:     getEnv("DB_HOST", "localhost"),
		DBPort:     getEnv("DB_PORT", "5432"),
		DBUser:     getEnv("DB_USER", "postgres"),
		DBPassword: getEnv("DB_PASSWORD", "postgres"),
		DBName:     getEnv("DB_NAME", "platform"),
		JWTSecret:  getEnv("JWT_SECRET", "your-secret-key-change-in-production"),
		RedisAddr:  getEnv("REDIS_ADDR", "localhost:6379"),
		StorageDir: getEnv("STORAGE_DIR", "./data"),
	}
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func main() {
	cfg := loadConfig()

	logger, _ := zap.NewDevelopment()
	if os.Getenv("ENV") == "production" {
		cfg := zap.NewProductionConfig()
		cfg.EncoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
		logger, _ = cfg.Build()
	}
	defer logger.Sync()

	dsn := fmt.Sprintf("host=%s port=%s user=%s password=%s dbname=%s sslmode=disable",
		cfg.DBHost, cfg.DBPort, cfg.DBUser, cfg.DBPassword, cfg.DBName)

	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		logger.Fatal("Failed to connect to database", zap.Error(err))
	}

	dbPool := database.NewConnectionPool(db, &database.Config{
		MaxOpenConns:    100,
		MaxIdleConns:    10,
		ConnMaxLifetime: time.Hour,
		ConnMaxIdleTime: 30 * time.Minute,
	}, logger)

	r := gin.Default()

	authService := apigateway.NewAuthService(dbPool.GetDB(), logger, cfg.JWTSecret)
	rateLimiter := apigateway.NewRateLimiter(&apigateway.RateLimitConfig{
		Strategy:    apigateway.StrategyTokenBucket,
		Limit:       100,
		Burst:       200,
		Window:      time.Minute,
	}, nil, logger)

	apiGatewayMiddleware := apigateway.NewMiddleware(authService, rateLimiter, logger)

	r.Use(apiGatewayMiddleware.RequestID())
	r.Use(apiGatewayMiddleware.CORS())
	r.Use(apiGatewayMiddleware.Logger())
	r.Use(gin.Recovery())

	api := r.Group("/api/v1")

	authHandler := apigateway.NewHandler(authService, rateLimiter)
	authHandler.RegisterRoutes(api)

	modelService := model.NewModelService(dbPool.GetDB(), logger)
	modelHandler := model.NewHandler(modelService)
	modelHandler.RegisterRoutes(api)

	promptService := prompt.NewPromptService(dbPool.GetDB(), logger)
	promptHandler := prompt.NewHandler(promptService)
	promptHandler.RegisterRoutes(api)

	configService := config.NewConfigService(dbPool.GetDB(), logger)
	configHandler := config.NewHandler(configService)
	configHandler.RegisterRoutes(api)

	localAdapter := storage.NewLocalAdapter(cfg.StorageDir, logger)
	storageService := storage.NewStorageService(localAdapter, dbPool.GetDB(), logger)
	storageHandler := storage.NewHandler(storageService)
	storageHandler.RegisterRoutes(api)

	monitoringService := monitoring.NewMonitoringService(dbPool.GetDB(), logger)
	monitoringHandler := monitoring.NewHandler(monitoringService)
	monitoringService.StartRuleEvaluator(context.Background())
	monitoringHandler.RegisterRoutes(api)

	gpuScheduler := gpu.NewGPUSchedulerService(dbPool.GetDB(), logger)
	gpuHandler := gpu.NewHandler(gpuScheduler)
	gpuScheduler.StartScheduler(context.Background())
	gpuHandler.RegisterRoutes(api)

	featureStore := featurestore.NewFeatureStoreService(dbPool.GetDB(), logger)
	featureStoreHandler := featurestore.NewHandler(featureStore)
	featureStoreHandler.RegisterRoutes(api)

	srv := &http.Server{
		Addr:    cfg.ServerAddr,
		Handler: r,
	}

	go func() {
		logger.Info("Server starting", zap.String("addr", cfg.ServerAddr))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Failed to start server", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logger.Info("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	gpuScheduler.StopScheduler()
	monitoringService.StopRuleEvaluator()

	if err := srv.Shutdown(ctx); err != nil {
		logger.Fatal("Server forced to shutdown", zap.Error(err))
	}

	logger.Info("Server exiting")
}
