package main

import (
	"context"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"model-inference-platform/internal/abtest"
	"model-inference-platform/internal/batcher"
	"model-inference-platform/internal/model"
	"model-inference-platform/internal/monitoring"
	"model-inference-platform/internal/notification"
	"model-inference-platform/internal/orchestrator"
	"model-inference-platform/internal/pkg/config"
	"model-inference-platform/internal/pkg/container"
	"model-inference-platform/internal/pkg/database"
	"model-inference-platform/internal/pkg/redis"
	"model-inference-platform/internal/pkg/triton"
	"model-inference-platform/internal/router"
	"model-inference-platform/internal/sdk"
	"model-inference-platform/internal/tenant"
	"model-inference-platform/internal/webhook"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/spf13/viper"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type Server struct {
	cfg               *config.Config
	logger            *zap.Logger
	db                *database.Database
	redisClient       *redis.Client
	tritonClient      triton.TritonClient
	containerManager  container.ContainerManager
	routerSDK         *sdk.RouterSDK

	modelRepo         *model.Repository
	orchestrator      *orchestrator.Orchestrator
	router            *router.Router
	batcher           *batcher.Batcher
	abTestManager     *abtest.ABTestManager
	monitor           *monitoring.Monitor
	driftMonitor      *monitoring.PredictionDistMonitor
	regMonitor        *monitoring.RegressionMonitor
	tenantManager     *tenant.Manager
	webhookManager    *webhook.WebhookManager
	notificationMgr   *notification.NotificationManager

	httpServer        *http.Server
}

func main() {
	logger, _ := zap.NewProduction(zap.AddStacktrace(zapcore.FatalLevel))
	defer logger.Sync()

	if err := loadConfig(); err != nil {
		logger.Fatal("Failed to load config", zap.Error(err))
	}

	var cfg config.Config
	if err := viper.Unmarshal(&cfg); err != nil {
		logger.Fatal("Failed to unmarshal config", zap.Error(err))
	}

	s := &Server{
		cfg:    &cfg,
		logger: logger,
	}

	if err := s.init(); err != nil {
		logger.Fatal("Failed to initialize server", zap.Error(err))
	}

	go s.startHTTPServer()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	s.shutdown()
}

func loadConfig() error {
	viper.SetConfigName("config")
	viper.SetConfigType("yaml")
	viper.AddConfigPath(".")
	viper.AddConfigPath("./config")

	viper.SetDefault("server.port", 8080)
	viper.SetDefault("server.read_timeout", "30s")
	viper.SetDefault("server.write_timeout", "30s")

	viper.SetDefault("database.host", "localhost")
	viper.SetDefault("database.port", 5432)
	viper.SetDefault("database.user", "postgres")
	viper.SetDefault("database.password", "postgres")
	viper.SetDefault("database.dbname", "inference_platform")
	viper.SetDefault("database.sslmode", "disable")

	viper.SetDefault("redis.host", "localhost")
	viper.SetDefault("redis.port", 6379)
	viper.SetDefault("redis.db", 0)

	viper.SetDefault("triton.grpc_host", "localhost")
	viper.SetDefault("triton.grpc_port", 8001)
	viper.SetDefault("triton.model_repository_path", "/models")

	viper.SetDefault("orchestrator.min_replicas", 1)
	viper.SetDefault("orchestrator.max_replicas", 10)
	viper.SetDefault("orchestrator.scale_up_threshold", 0.8)
	viper.SetDefault("orchestrator.scale_down_threshold", 0.2)
	viper.SetDefault("orchestrator.scale_up_delay", "60s")
	viper.SetDefault("orchestrator.scale_down_delay", "300s")
	viper.SetDefault("orchestrator.queue_depth_threshold", 10)
	viper.SetDefault("orchestrator.runtime_mode", "docker")
	viper.SetDefault("orchestrator.triton_image", "nvcr.io/nvidia/tritonserver:23.09-py3")
	viper.SetDefault("orchestrator.health_check_interval", "3s")
	viper.SetDefault("orchestrator.health_check_inference", true)
	viper.SetDefault("orchestrator.docker_network", "bridge")
	viper.SetDefault("orchestrator.model_repository_host_path", "/models")
	viper.SetDefault("orchestrator.triton_executable", "tritonserver")
	viper.SetDefault("orchestrator.docker_grpc_port_start", 8001)
	viper.SetDefault("orchestrator.docker_http_port_start", 8000)
	viper.SetDefault("orchestrator.process_grpc_port_start", 9001)
	viper.SetDefault("orchestrator.process_http_port_start", 9000)

	viper.SetDefault("batcher.max_batch_size", 32)
	viper.SetDefault("batcher.batch_window", "5ms")
	viper.SetDefault("batcher.max_queue_size", 1000)

	viper.SetDefault("tenant.default_gpu_quota", 2.0)
	viper.SetDefault("tenant.default_gpu_min", 0.5)

	viper.SetDefault("webhook.enabled", true)
	viper.SetDefault("webhook.auth_token", "")
	viper.SetDefault("webhook.auto_deploy_to_staging", false)
	viper.SetDefault("webhook.download_timeout", "300s")
	viper.SetDefault("webhook.max_workers", 3)

	viper.SetDefault("notification.dingtalk.enabled", false)
	viper.SetDefault("notification.dingtalk.webhook_url", "")
	viper.SetDefault("notification.dingtalk.secret", "")
	viper.SetDefault("notification.wechat_work.enabled", false)
	viper.SetDefault("notification.wechat_work.webhook_url", "")
	viper.SetDefault("notification.email.enabled", false)
	viper.SetDefault("notification.email.smtp_host", "")
	viper.SetDefault("notification.email.smtp_port", 587)
	viper.SetDefault("notification.email.username", "")
	viper.SetDefault("notification.email.password", "")

	return viper.ReadInConfig()
}

func (s *Server) init() error {
	var err error

	s.db, err = database.New(s.cfg.Database)
	if err != nil {
		return err
	}

	s.redisClient, err = redis.New(s.cfg.Redis)
	if err != nil {
		return err
	}

	s.tritonClient, err = triton.NewClient(s.cfg.Triton)
	if err != nil {
		return err
	}

	if s.cfg.Orchestrator.RuntimeMode == "docker" {
		dockerCfg := container.DockerConfig{
			Image:               s.cfg.Orchestrator.TritonImage,
			ModelRepositoryPath: s.cfg.Orchestrator.ModelRepositoryHostPath,
			Network:             s.cfg.Orchestrator.DockerNetwork,
			GRPCPortStart:       s.cfg.Orchestrator.DockerGRPCPortStart,
			HTTPPortStart:       s.cfg.Orchestrator.DockerHTTPPortStart,
			AutoRemove:          true,
			ContainerNamePrefix: "triton",
		}
		s.containerManager, err = container.NewDockerContainerManager(dockerCfg, s.logger)
		if err != nil {
			s.logger.Warn("Failed to create docker container manager, falling back to process mode", zap.Error(err))
		}
	}

	if s.containerManager == nil {
		processCfg := container.ProcessConfig{
			TritonExecutable:    s.cfg.Orchestrator.TritonExecutable,
			ModelRepositoryPath: s.cfg.Triton.ModelRepositoryPath,
			GRPCPortStart:       s.cfg.Orchestrator.ProcessGRPCPortStart,
			HTTPPortStart:       s.cfg.Orchestrator.ProcessHTTPPortStart,
		}
		s.containerManager = container.NewProcessContainerManager(processCfg, s.logger)
	}

	sdkCfg := sdk.RouterSDKConfig{
		RedisConfig:     s.cfg.Redis,
		Strategy:        sdk.StrategyLeastRequests,
		RefreshInterval: 5 * time.Second,
		MaxConnections:  100,
		CacheTTL:        3 * time.Second,
	}
	s.routerSDK, err = sdk.NewRouterSDK(sdkCfg, s.logger)
	if err != nil {
		s.logger.Warn("Failed to create router SDK", zap.Error(err))
	}

	s.notificationMgr = notification.NewNotificationManager(s.cfg.Notification, s.logger)

	s.modelRepo = model.NewRepository(s.db, s.tritonClient, s.cfg.Triton.ModelRepositoryPath)

	s.orchestrator = orchestrator.New(s.cfg.Orchestrator, s.db, s.redisClient, s.containerManager, s.logger)

	s.router = router.New(*s.cfg, s.orchestrator, s.redisClient, s.logger)

	s.batcher = batcher.New(s.cfg.Batcher, s.redisClient, s.tritonClient, s.logger)

	s.abTestManager = abtest.NewManager(s.db, s.redisClient, s.logger)

	s.monitor = monitoring.NewMonitor(s.db, s.redisClient, s.logger)

	s.driftMonitor = monitoring.NewPredictionDistMonitor(s.db, s.notificationMgr, s.logger)

	s.regMonitor = monitoring.NewRegressionMonitor(s.db, s.logger)

	s.tenantManager = tenant.NewManager(s.cfg.Tenant, s.db, s.logger)

	s.webhookManager = webhook.NewWebhookManager(s.cfg.Webhook, s.db, s.modelRepo, s.orchestrator, s.logger)

	ctx := context.Background()
	if err := s.orchestrator.Start(ctx); err != nil {
		s.logger.Warn("Failed to start orchestrator", zap.Error(err))
	}
	if err := s.router.Start(ctx); err != nil {
		s.logger.Warn("Failed to start router", zap.Error(err))
	}
	if err := s.batcher.Start(ctx); err != nil {
		s.logger.Warn("Failed to start batcher", zap.Error(err))
	}
	if err := s.abTestManager.Start(ctx); err != nil {
		s.logger.Warn("Failed to start abtest manager", zap.Error(err))
	}
	if err := s.monitor.Start(ctx); err != nil {
		s.logger.Warn("Failed to start monitor", zap.Error(err))
	}
	if err := s.driftMonitor.Start(ctx); err != nil {
		s.logger.Warn("Failed to start drift monitor", zap.Error(err))
	}
	if err := s.regMonitor.Start(ctx); err != nil {
		s.logger.Warn("Failed to start regression monitor", zap.Error(err))
	}

	return nil
}

func (s *Server) startHTTPServer() {
	r := gin.Default()

	r.Use(func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}
		c.Next()
	})

	v1 := r.Group("/api/v1")

	v1.GET("/health", s.healthCheck)
	v1.GET("/metrics", gin.WrapH(promhttp.Handler()))

	models := v1.Group("/models")
	{
		models.POST("", s.createModel)
		models.GET("", s.listModels)
		models.GET("/:name", s.getModel)
		models.POST("/:name/versions", s.createModelVersion)
		models.GET("/:name/versions", s.listModelVersions)
		models.GET("/:name/versions/:version", s.getModelVersion)
		models.POST("/:name/versions/:version/deploy", s.deployModelVersion)
	}

	infer := v1.Group("/infer")
	{
		infer.POST("", s.infer)
		infer.POST("/batch", s.batchInfer)
	}

	abtests := v1.Group("/abtests")
	{
		abtests.POST("", s.createABTest)
		abtests.POST("/v2", s.createABTestV2)
		abtests.GET("", s.listABTests)
		abtests.GET("/:id", s.getABTest)
		abtests.GET("/:id/analyze", s.analyzeABTest)
		abtests.POST("/:id/end", s.endABTest)
	}

	tenants := v1.Group("/tenants")
	{
		tenants.POST("", s.createNamespace)
		tenants.GET("", s.listNamespaces)
		tenants.GET("/:namespace", s.getNamespace)
		tenants.PUT("/:namespace/quota", s.updateNamespaceQuota)
		tenants.GET("/:namespace/usage", s.getNamespaceUsage)
	}

	metrics := v1.Group("/metrics")
	{
		metrics.GET("/business", s.getBusinessMetrics)
		metrics.DELETE("/business", s.resetBusinessMetrics)
	}

	webhooks := v1.Group("/webhooks")
	{
		webhooks.POST("/training-platform", s.handleTrainingWebhook)
		webhooks.GET("/tasks", s.listWebhookTasks)
		webhooks.GET("/tasks/:id", s.getWebhookTask)
	}

	admin := v1.Group("/admin")
	{
		admin.GET("/instances", s.listInstances)
		admin.POST("/instances/:id/terminate", s.terminateInstance)
		admin.GET("/alerts", s.getAlerts)
		admin.GET("/traces/:traceId", s.getTrace)
	}

	s.httpServer = &http.Server{
		Addr:    ":8080",
		Handler: r,
	}

	s.logger.Info("HTTP server starting", zap.Int("port", 8080))
	if err := s.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		s.logger.Fatal("Failed to start HTTP server", zap.Error(err))
	}
}

func (s *Server) shutdown() {
	s.logger.Info("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if s.httpServer != nil {
		s.httpServer.Shutdown(ctx)
	}

	s.orchestrator.Stop()
	s.router.Stop()
	s.batcher.Stop()
	s.abTestManager.Stop()
	s.monitor.Stop()
	s.driftMonitor.Stop()
	s.regMonitor.Stop()
	s.tenantManager.Stop()

	if s.routerSDK != nil {
		s.routerSDK.Close()
	}

	s.db.Close()
	s.redisClient.Close()
	s.tritonClient.Close()

	s.logger.Info("Server shutdown complete")
}

func (s *Server) healthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status": "ok",
		"time":   time.Now().UTC(),
	})
}

func (s *Server) createModel(c *gin.Context) {
	var req struct {
		Name        string            `json:"name" binding:"required"`
		Namespace   string            `json:"namespace" binding:"required"`
		Description string            `json:"description"`
		Labels      map[string]string `json:"labels"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	model, err := s.modelRepo.CreateModel(c.Request.Context(), req.Namespace, req.Name, req.Description, req.Labels)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, model)
}

func (s *Server) listModels(c *gin.Context) {
	namespace := c.Query("namespace")
	if namespace == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "namespace is required"})
		return
	}

	models, err := s.modelRepo.ListModels(c.Request.Context(), namespace)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, models)
}

func (s *Server) getModel(c *gin.Context) {
	namespace := c.Query("namespace")
	name := c.Param("name")

	m, err := s.modelRepo.GetModel(c.Request.Context(), namespace, name)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "model not found"})
		return
	}

	c.JSON(http.StatusOK, m)
}

func (s *Server) createModelVersion(c *gin.Context) {
	modelName := c.Param("name")
	namespace := c.Request.FormValue("namespace")
	version := c.Request.FormValue("version")
	format := model.ModelFormat(c.Request.FormValue("format"))

	file, _, err := c.Request.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "file is required"})
		return
	}
	defer file.Close()

	model, err := s.modelRepo.GetModel(c.Request.Context(), namespace, modelName)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "model not found"})
		return
	}

	modelVersion, err := s.modelRepo.CreateModelVersion(
		c.Request.Context(), model.ID, version, format, file, "user", nil)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, modelVersion)
}

func (s *Server) listModelVersions(c *gin.Context) {
	namespace := c.Query("namespace")
	modelName := c.Param("name")

	model, err := s.modelRepo.GetModel(c.Request.Context(), namespace, modelName)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "model not found"})
		return
	}

	versions, err := s.modelRepo.ListModelVersions(c.Request.Context(), model.ID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, versions)
}

func (s *Server) getModelVersion(c *gin.Context) {
	namespace := c.Query("namespace")
	modelName := c.Param("name")
	version := c.Param("version")

	model, err := s.modelRepo.GetModel(c.Request.Context(), namespace, modelName)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "model not found"})
		return
	}

	modelVersion, err := s.modelRepo.GetModelVersion(c.Request.Context(), model.ID, version)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "version not found"})
		return
	}

	c.JSON(http.StatusOK, modelVersion)
}

func (s *Server) deployModelVersion(c *gin.Context) {
	namespace := c.Query("namespace")
	modelName := c.Param("name")
	version := c.Param("version")

	modelInfo, err := s.modelRepo.GetModel(c.Request.Context(), namespace, modelName)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "model not found"})
		return
	}

	modelVersion, err := s.modelRepo.GetModelVersion(c.Request.Context(), modelInfo.ID, version)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "version not found"})
		return
	}

	instance, err := s.orchestrator.CreateInstance(c.Request.Context(),
		modelInfo.Name, modelInfo.ID, version, namespace, modelVersion.GPUMemoryMB)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	s.modelRepo.UpdateModelVersionStatus(c.Request.Context(), modelVersion.ID, model.StatusDeploying)

	c.JSON(http.StatusAccepted, instance)
}

func (s *Server) infer(c *gin.Context) {
	var req struct {
		ModelName      string                 `json:"model_name" binding:"required"`
		Version        string                 `json:"version" binding:"required"`
		Namespace      string                 `json:"namespace" binding:"required"`
		Inputs         map[string]interface{} `json:"inputs" binding:"required"`
		TraceID        string                 `json:"trace_id"`
		TimeoutMs      int                    `json:"timeout_ms"`
		GroundTruth    interface{}            `json:"ground_truth,omitempty"`
		PredictedLabel interface{}            `json:"predicted_label,omitempty"`
		CustomLabels   map[string]string      `json:"custom_labels,omitempty"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	timeout := time.Duration(req.TimeoutMs) * time.Millisecond
	if timeout == 0 {
		timeout = 30 * time.Second
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), timeout)
	defer cancel()

	version, err := s.router.SelectABTestVersion(ctx, req.Namespace, req.ModelName, req.Version)
	if err != nil {
		version = req.Version
	}

	inputs := make([]*triton.InferenceTensor, 0, len(req.Inputs))
	for name, data := range req.Inputs {
		inputs = append(inputs, &triton.InferenceTensor{
			Name:  name,
			Shape: []int64{1},
			DType: "FP32",
			Data:  data,
		})
	}

	sdkReq := &sdk.SDKRequest{
		RequestID:  generateRequestID(),
		TraceID:    req.TraceID,
		ModelName:  req.ModelName,
		Version:    version,
		Namespace:  req.Namespace,
		Inputs:     inputs,
		Timeout:    timeout,
		MaxRetries: 2,
	}

	resp, err := s.routerSDK.Infer(ctx, sdkReq)
	if err != nil {
		s.monitor.RecordInference(&monitoring.InferenceLogEntry{
			RequestID:    sdkReq.RequestID,
			TraceID:      req.TraceID,
			ModelName:    req.ModelName,
			Version:      sdkReq.Version,
			Namespace:    req.Namespace,
			StatusCode:   http.StatusServiceUnavailable,
			ErrorMessage: err.Error(),
			GroundTruth:  req.GroundTruth,
			CustomLabels: req.CustomLabels,
		})

		c.JSON(http.StatusServiceUnavailable, gin.H{
			"error": err.Error(),
			"request_id": sdkReq.RequestID,
		})
		return
	}

	outputs := make(map[string]interface{})
	for _, out := range resp.Outputs {
		outputs[out.Name] = out.Data
	}

	latencyMs := resp.Latency.Milliseconds()

	s.monitor.RecordInference(&monitoring.InferenceLogEntry{
		RequestID:      sdkReq.RequestID,
		TraceID:        req.TraceID,
		ModelName:      req.ModelName,
		Version:        sdkReq.Version,
		Namespace:      req.Namespace,
		InstanceID:     resp.InstanceID,
		LatencyMs:      latencyMs,
		StatusCode:     http.StatusOK,
		Outputs:        outputs,
		GroundTruth:    req.GroundTruth,
		PredictedLabel: req.PredictedLabel,
		CustomLabels:   req.CustomLabels,
	})

	s.tenantManager.RecordInference(req.Namespace, latencyMs)

	c.JSON(http.StatusOK, gin.H{
		"request_id": sdkReq.RequestID,
		"outputs":    outputs,
		"latency_ms": latencyMs,
	})
}

func (s *Server) batchInfer(c *gin.Context) {
	var req struct {
		ModelName      string                 `json:"model_name" binding:"required"`
		Version        string                 `json:"version" binding:"required"`
		Namespace      string                 `json:"namespace" binding:"required"`
		Inputs         map[string]interface{} `json:"inputs" binding:"required"`
		TraceID        string                 `json:"trace_id"`
		GroundTruth    interface{}            `json:"ground_truth,omitempty"`
		PredictedLabel interface{}            `json:"predicted_label,omitempty"`
		CustomLabels   map[string]string      `json:"custom_labels,omitempty"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	batchReq := &batcher.BatchRequest{
		RequestID:  generateRequestID(),
		TraceID:    req.TraceID,
		ModelName:  req.ModelName,
		Version:    req.Version,
		Namespace:  req.Namespace,
		Inputs:     req.Inputs,
		ResponseCh: make(chan *batcher.BatchResponse, 1),
		Timestamp:  time.Now(),
	}

	ctx, cancel := context.WithTimeout(c.Request.Context(), 60*time.Second)
	defer cancel()

	resp, err := s.batcher.Submit(ctx, batchReq)
	if err != nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": err.Error()})
		return
	}

	s.monitor.RecordInference(&monitoring.InferenceLogEntry{
		RequestID:      batchReq.RequestID,
		TraceID:        req.TraceID,
		ModelName:      req.ModelName,
		Version:        req.Version,
		Namespace:      req.Namespace,
		BatchSize:      resp.BatchSize,
		LatencyMs:      resp.LatencyMs,
		GroundTruth:    req.GroundTruth,
		PredictedLabel: req.PredictedLabel,
		CustomLabels:   req.CustomLabels,
		StatusCode: func() int {
			if resp.Error != "" {
				return http.StatusInternalServerError
			}
			return http.StatusOK
		}(),
		ErrorMessage: resp.Error,
		Outputs:      resp.Outputs,
	})

	c.JSON(http.StatusOK, gin.H{
		"request_id": batchReq.RequestID,
		"outputs":    resp.Outputs,
		"latency_ms": resp.LatencyMs,
		"batch_size": resp.BatchSize,
	})
}

func (s *Server) createABTest(c *gin.Context) {
	var req struct {
		Name          string `json:"name" binding:"required"`
		ModelName     string `json:"model_name" binding:"required"`
		Namespace     string `json:"namespace" binding:"required"`
		VersionA      string `json:"version_a" binding:"required"`
		VersionB      string `json:"version_b" binding:"required"`
		TrafficSplitA int    `json:"traffic_split_a" binding:"required"`
		TrafficSplitB int    `json:"traffic_split_b" binding:"required"`
		PrimaryMetric string `json:"primary_metric"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	testReq := &abtest.CreateTestRequest{
		Name:           req.Name,
		ModelName:      req.ModelName,
		Namespace:      req.Namespace,
		VersionA:       req.VersionA,
		VersionB:       req.VersionB,
		TrafficSplitA:  req.TrafficSplitA,
		TrafficSplitB:  req.TrafficSplitB,
		SplitStrategy:  abtest.SplitStrategyTraffic,
		PrimaryMetric:  req.PrimaryMetric,
		CreatedBy:      "user",
	}

	test, err := s.abTestManager.CreateTest(c.Request.Context(), testReq)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, test)
}

func (s *Server) listABTests(c *gin.Context) {
	namespace := c.Query("namespace")
	tests := s.abTestManager.GetActiveTests(namespace)
	c.JSON(http.StatusOK, tests)
}

func (s *Server) getABTest(c *gin.Context) {
	testID := c.Param("id")
	result, err := s.abTestManager.AnalyzeTest(c.Request.Context(), testID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "test not found"})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (s *Server) analyzeABTest(c *gin.Context) {
	testID := c.Param("id")
	result, err := s.abTestManager.AnalyzeTest(c.Request.Context(), testID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "test not found"})
		return
	}
	c.JSON(http.StatusOK, result)
}

func (s *Server) endABTest(c *gin.Context) {
	testID := c.Param("id")
	if err := s.abTestManager.EndTest(c.Request.Context(), testID); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "ended"})
}

func (s *Server) createNamespace(c *gin.Context) {
	var req struct {
		Name         string                 `json:"name" binding:"required"`
		DisplayName  string                 `json:"display_name"`
		Description  string                 `json:"description"`
		GPUQuotaMin  float64                `json:"gpu_quota_min"`
		GPUQuotaMax  float64                `json:"gpu_quota_max"`
		Metadata     map[string]interface{} `json:"metadata"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	ns, err := s.tenantManager.CreateNamespace(c.Request.Context(),
		req.Name, req.DisplayName, req.Description,
		req.GPUQuotaMin, req.GPUQuotaMax, req.Metadata)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, ns)
}

func (s *Server) listNamespaces(c *gin.Context) {
	namespaces, err := s.tenantManager.ListNamespaces(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, namespaces)
}

func (s *Server) getNamespace(c *gin.Context) {
	name := c.Param("namespace")
	ns, err := s.tenantManager.GetNamespace(c.Request.Context(), name)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "namespace not found"})
		return
	}

	allocation := s.tenantManager.GetAllocation(name)
	c.JSON(http.StatusOK, gin.H{
		"namespace":  ns,
		"allocation": allocation,
	})
}

func (s *Server) updateNamespaceQuota(c *gin.Context) {
	name := c.Param("namespace")
	var req struct {
		GPUQuotaMin float64 `json:"gpu_quota_min"`
		GPUQuotaMax float64 `json:"gpu_quota_max"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := s.tenantManager.UpdateNamespaceQuota(c.Request.Context(),
		name, req.GPUQuotaMin, req.GPUQuotaMax); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "updated"})
}

func (s *Server) getNamespaceUsage(c *gin.Context) {
	name := c.Param("namespace")
	startDate := c.Query("start_date")
	endDate := c.Query("end_date")

	usage, err := s.tenantManager.GetUsageReport(c.Request.Context(), name, startDate, endDate)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, usage)
}

func (s *Server) listInstances(c *gin.Context) {
	instances := s.orchestrator.GetAllInstances()
	c.JSON(http.StatusOK, instances)
}

func (s *Server) terminateInstance(c *gin.Context) {
	instanceID := c.Param("id")
	if err := s.orchestrator.TerminateInstance(c.Request.Context(), instanceID); err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "terminated"})
}

func (s *Server) getAlerts(c *gin.Context) {
	namespace := c.Query("namespace")
	alerts := s.driftMonitor.GetRecentAlerts(namespace, 50)
	c.JSON(http.StatusOK, alerts)
}

func (s *Server) getTrace(c *gin.Context) {
	traceID := c.Param("traceId")
	trace, ok := s.monitor.GetTrace(traceID)
	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"error": "trace not found"})
		return
	}
	c.JSON(http.StatusOK, trace)
}

func generateRequestID() string {
	return "req_" + time.Now().Format("20060102150405") + "_" + randomString(8)
}

func randomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = letters[time.Now().UnixNano()%int64(len(letters))]
		time.Sleep(1 * time.Nanosecond)
	}
	return string(b)
}

func (s *Server) handleTrainingWebhook(c *gin.Context) {
	authToken := c.GetHeader("X-Webhook-Token")

	var payload webhook.TrainingPlatformPayload
	if err := c.ShouldBindJSON(&payload); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	resp, err := s.webhookManager.HandleTrainingPlatformWebhook(c.Request.Context(), &payload, authToken)
	if err != nil {
		c.JSON(http.StatusUnauthorized, resp)
		return
	}

	c.JSON(http.StatusAccepted, resp)
}

func (s *Server) listWebhookTasks(c *gin.Context) {
	limit := 50
	if limitStr := c.Query("limit"); limitStr != "" {
		if l, err := strconv.Atoi(limitStr); err == nil && l > 0 {
			limit = l
		}
	}

	tasks := s.webhookManager.ListTasks(c.Request.Context(), limit)
	c.JSON(http.StatusOK, tasks)
}

func (s *Server) getWebhookTask(c *gin.Context) {
	taskID := c.Param("id")
	task, err := s.webhookManager.GetTaskStatus(c.Request.Context(), taskID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "task not found"})
		return
	}
	c.JSON(http.StatusOK, task)
}

func (s *Server) createABTestV2(c *gin.Context) {
	var req struct {
		Name               string                 `json:"name" binding:"required"`
		ModelName          string                 `json:"model_name" binding:"required"`
		Namespace          string                 `json:"namespace" binding:"required"`
		VersionA           string                 `json:"version_a" binding:"required"`
		VersionB           string                 `json:"version_b" binding:"required"`
		TrafficSplitA      int                    `json:"traffic_split_a"`
		TrafficSplitB      int                    `json:"traffic_split_b"`
		SplitStrategy      string                 `json:"split_strategy" binding:"required"`
		FeatureRule        *abtest.FeatureCondition `json:"feature_rule,omitempty"`
		TimeWindow         *abtest.TimeWindow      `json:"time_window,omitempty"`
		PrimaryMetric      string                 `json:"primary_metric"`
		SignificanceLevel  float64                `json:"significance_level"`
		MinSampleSize      int64                  `json:"min_sample_size"`
		OwnerEmail         string                 `json:"owner_email"`
		OwnerDingtalk      string                 `json:"owner_dingtalk"`
		OwnerWechat        string                 `json:"owner_wechat"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	testReq := &abtest.CreateTestRequest{
		Name:              req.Name,
		ModelName:         req.ModelName,
		Namespace:         req.Namespace,
		VersionA:          req.VersionA,
		VersionB:          req.VersionB,
		TrafficSplitA:     req.TrafficSplitA,
		TrafficSplitB:     req.TrafficSplitB,
		SplitStrategy:     abtest.SplitStrategy(req.SplitStrategy),
		FeatureRule:       req.FeatureRule,
		TimeWindow:        req.TimeWindow,
		PrimaryMetric:     req.PrimaryMetric,
		SignificanceLevel: req.SignificanceLevel,
		MinSampleSize:     req.MinSampleSize,
		OwnerEmail:        req.OwnerEmail,
		OwnerDingtalk:     req.OwnerDingtalk,
		OwnerWechat:       req.OwnerWechat,
		CreatedBy:         "api",
	}

	test, err := s.abTestManager.CreateTest(c.Request.Context(), testReq)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, test)
}

func (s *Server) getBusinessMetrics(c *gin.Context) {
	namespace := c.Query("namespace")
	modelName := c.Query("model_name")
	version := c.Query("version")

	if namespace == "" || modelName == "" || version == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "namespace, model_name and version are required"})
		return
	}

	metrics, err := s.monitor.GetBusinessMetrics(c.Request.Context(), namespace, modelName, version)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"namespace":  namespace,
		"model_name": modelName,
		"version":    version,
		"metrics":    metrics,
	})
}

func (s *Server) resetBusinessMetrics(c *gin.Context) {
	var req struct {
		Namespace string `json:"namespace" binding:"required"`
		ModelName string `json:"model_name" binding:"required"`
		Version   string `json:"version" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	s.monitor.ResetBusinessMetrics(req.Namespace, req.ModelName, req.Version)
	c.JSON(http.StatusOK, gin.H{"status": "reset"})
}
