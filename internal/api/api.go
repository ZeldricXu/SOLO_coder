package api

import (
	"context"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"edgescheduler/internal/common/config"
	"edgescheduler/internal/common/database"
	"edgescheduler/internal/common/logger"
	"edgescheduler/internal/modules/data_aggregation"
	"edgescheduler/internal/modules/device_lifecycle"
	"edgescheduler/internal/modules/device_shadow"
	"edgescheduler/internal/modules/edge_inference"
	"edgescheduler/internal/modules/edge_rules"
	"edgescheduler/internal/modules/firmware_ota"
	"edgescheduler/internal/modules/offline_cache"
	"edgescheduler/internal/modules/protocol_adapter"
	"edgescheduler/pkg/utils"
)

type Server struct {
	router *gin.Engine
	server *http.Server
	ctx    context.Context
	cancel context.CancelFunc

	deviceLifecycle *device_lifecycle.Module
	edgeInference   *edge_inference.Module
	offlineCache    *offline_cache.Module
	dataAggregation *data_aggregation.Module
	protocolAdapter *protocol_adapter.Module
	firmwareOTA     *firmware_ota.Module
	deviceShadow    *device_shadow.Module
	edgeRules       *edge_rules.Module
}

func NewServer(cfg *config.Config) *Server {
	ctx, cancel := context.WithCancel(context.Background())

	return &Server{
		ctx:    ctx,
		cancel: cancel,
	}
}

func (s *Server) Init(cfg *config.Config) error {
	logger.Info("Initializing EdgeScheduler API server")

	gin.SetMode(cfg.Server.Mode)
	s.router = gin.New()
	s.router.Use(gin.Recovery())
	s.router.Use(requestLogger())
	s.router.Use(corsMiddleware())

	api := s.router.Group("/api/v1")

	api.GET("/health", s.healthCheck)

	if err := s.initModules(cfg); err != nil {
		return err
	}

	s.registerModuleRoutes(api)

	s.server = &http.Server{
		Addr:         cfg.Server.Host + ":" + cfg.Server.Port,
		Handler:      s.router,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	return nil
}

func (s *Server) initModules(cfg *config.Config) error {
	if err := database.Init(cfg); err != nil {
		return err
	}

	if err := database.RunMigrations(
		&device_lifecycle.Device{},
		&device_lifecycle.DeviceAuth{},
		&device_lifecycle.DeviceHeartbeat{},
		&edge_inference.AIModel{},
		&edge_inference.ModelDeployment{},
		&edge_inference.InferenceTask{},
		&offline_cache.CachedData{},
		&offline_cache.SyncJob{},
		&data_aggregation.AggregationRule{},
		&data_aggregation.AggregationResult{},
		&protocol_adapter.ProtocolDriver{},
		&protocol_adapter.DeviceProtocolConfig{},
		&protocol_adapter.ForwardRule{},
		&firmware_ota.FirmwareImage{},
		&firmware_ota.UpgradeBatch{},
		&firmware_ota.DeviceUpgrade{},
		&firmware_ota.UpgradePolicy{},
		&device_shadow.DeviceShadow{},
		&device_shadow.ShadowOperationLog{},
		&device_shadow.ShadowVersionHistory{},
		&edge_rules.Rule{},
		&edge_rules.RuleExecutionLog{},
	); err != nil {
		return err
	}

	s.deviceLifecycle = device_lifecycle.NewModule()
	s.edgeInference = edge_inference.NewModule()
	s.offlineCache = offline_cache.NewModule()
	s.dataAggregation = data_aggregation.NewModule()
	s.protocolAdapter = protocol_adapter.NewModule()
	s.firmwareOTA = firmware_ota.NewModule()
	s.deviceShadow = device_shadow.NewModule()
	s.edgeRules = edge_rules.NewModule()

	if err := s.deviceLifecycle.Init(s.ctx, cfg); err != nil {
		return err
	}
	if err := s.edgeInference.Init(s.ctx, cfg); err != nil {
		return err
	}
	if err := s.offlineCache.Init(s.ctx, cfg); err != nil {
		return err
	}
	if err := s.dataAggregation.Init(s.ctx, cfg); err != nil {
		return err
	}
	if err := s.protocolAdapter.Init(s.ctx, cfg); err != nil {
		return err
	}
	if err := s.firmwareOTA.Init(s.ctx, cfg); err != nil {
		return err
	}
	if err := s.deviceShadow.Init(s.ctx, cfg); err != nil {
		return err
	}
	if err := s.edgeRules.Init(s.ctx, cfg); err != nil {
		return err
	}

	logger.Info("All modules initialized successfully")
	return nil
}

func (s *Server) registerModuleRoutes(api *gin.RouterGroup) {
	s.deviceLifecycle.RegisterRoutes(api)
	s.edgeInference.RegisterRoutes(api)
	s.offlineCache.RegisterRoutes(api)
	s.dataAggregation.RegisterRoutes(api)
	s.protocolAdapter.RegisterRoutes(api)
	s.firmwareOTA.RegisterRoutes(api)
	s.deviceShadow.RegisterRoutes(api)
	s.edgeRules.RegisterRoutes(api)
}

func (s *Server) Start() error {
	logger.Info("Starting EdgeScheduler server",
		zap.String("addr", s.server.Addr),
	)

	go func() {
		if err := s.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Server failed to start", zap.Error(err))
		}
	}()

	return nil
}

func (s *Server) Stop() error {
	logger.Info("Stopping EdgeScheduler server")

	s.cancel()

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer shutdownCancel()

	if err := s.server.Shutdown(shutdownCtx); err != nil {
		return err
	}

	return nil
}

func (s *Server) healthCheck(c *gin.Context) {
	ctx := utils.WithTraceID(c.Request.Context(), utils.GenerateTraceID())

	dbStatus := "healthy"
	if err := database.GetDB().WithContext(ctx).Exec("SELECT 1").Error; err != nil {
		dbStatus = "unhealthy"
	}

	utils.SuccessResponse(c, gin.H{
		"status":    "healthy",
		"service":   "edgescheduler",
		"version":   "1.0.0",
		"timestamp": time.Now().UTC(),
		"database":  dbStatus,
	})
}

func requestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		method := c.Request.Method

		c.Next()

		latency := time.Since(start)
		statusCode := c.Writer.Status()
		clientIP := c.ClientIP()

		logger.Info("HTTP request",
			zap.String("method", method),
			zap.String("path", path),
			zap.Int("status", statusCode),
			zap.String("client_ip", clientIP),
			zap.Duration("latency", latency),
		)
	}
}

func corsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}
