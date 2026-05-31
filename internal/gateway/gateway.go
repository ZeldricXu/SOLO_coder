package gateway

import (
	"context"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/edgeplatform/session306/internal/config"
	"github.com/edgeplatform/session306/internal/device"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/internal/monitoring"
	"github.com/edgeplatform/session306/pkg/errors"
	"github.com/edgeplatform/session306/pkg/events"
	"github.com/edgeplatform/session306/pkg/utils"

	"github.com/gin-contrib/cors"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.uber.org/zap"
	"golang.org/x/time/rate"
)

type Protocol string

const (
	ProtocolHTTP   Protocol = "http"
	ProtocolMQTT   Protocol = "mqtt"
	ProtocolGRPC   Protocol = "grpc"
	ProtocolCoAP   Protocol = "coap"
)

type RouteDefinition struct {
	Path         string            `json:"path"`
	Method       string            `json:"method"`
	Backend      string            `json:"backend"`
	Protocol     Protocol          `json:"protocol"`
	AuthRequired bool              `json:"auth_required"`
	RateLimit    int               `json:"rate_limit"`
	Timeout      time.Duration     `json:"timeout"`
	Headers      map[string]string `json:"headers"`
}

type ServiceInstance struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Address  string `json:"address"`
	Port     int    `json:"port"`
	Protocol Protocol `json:"protocol"`
	Healthy  bool   `json:"healthy"`
	Weight   int    `json:"weight"`
}

type RateLimiter struct {
	limiter *rate.Limiter
	lastSeen time.Time
}

type APIGateway struct {
	configManager   *config.ConfigManager
	monitoring      *monitoring.MonitoringManager
	deviceManager   *device.DeviceManager
	eventBus        events.EventBus
	logger          *zap.Logger
	router          *gin.Engine
	routes          map[string]*RouteDefinition
	services        map[string][]*ServiceInstance
	rateLimiters    map[string]*RateLimiter
	rateLimitMu     sync.Mutex
	middlewares     []gin.HandlerFunc
	server          *http.Server
	port            string
	requestTimeout  time.Duration
	maxConcurrency  int64
	concurrencySem  chan struct{}
}

func NewAPIGateway(
	cm *config.ConfigManager,
	mm *monitoring.MonitoringManager,
	dm *device.DeviceManager,
	eb events.EventBus,
	log *zap.Logger,
) *APIGateway {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()

	return &APIGateway{
		configManager:  cm,
		monitoring:     mm,
		deviceManager:  dm,
		eventBus:       eb,
		logger:         log,
		router:         router,
		routes:         make(map[string]*RouteDefinition),
		services:       make(map[string][]*ServiceInstance),
		rateLimiters:   make(map[string]*RateLimiter),
		port:           ":8080",
		requestTimeout: 30 * time.Second,
		maxConcurrency: 1000,
		concurrencySem: make(chan struct{}, 1000),
	}
}

func (g *APIGateway) Start(ctx context.Context) error {
	g.router.Use(gin.Recovery())

	g.registerDefaultMiddlewares()
	g.registerDefaultRoutes()
	g.registerAPIRoutes()

	g.server = &http.Server{
		Addr:         g.port,
		Handler:      g.router,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := g.server.Shutdown(shutdownCtx); err != nil {
			g.logger.Error("Server shutdown error", zap.Error(err))
		}
	}()

	go g.cleanupRateLimiters(ctx)

	g.logger.Info("API Gateway started", zap.String("port", g.port))
	return g.server.ListenAndServe()
}

func (g *APIGateway) registerDefaultMiddlewares() {
	g.router.Use(g.requestIDMiddleware())
	g.router.Use(g.loggingMiddleware())
	g.router.Use(g.corsMiddleware())
	g.router.Use(g.concurrencyLimiterMiddleware())
	g.router.Use(g.rateLimiterMiddleware())
	g.router.Use(g.monitoringMiddleware())
}

func (g *APIGateway) requestIDMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		traceID := c.GetHeader("X-Trace-ID")
		if traceID == "" {
			traceID = uuid.New().String()
		}
		c.Set("trace_id", traceID)
		c.Header("X-Trace-ID", traceID)
		c.Next()
	}
}

func (g *APIGateway) loggingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		traceID := c.GetString("trace_id")

		g.logger.Debug("Request started",
			zap.String("trace_id", traceID),
			zap.String("method", c.Request.Method),
			zap.String("path", c.Request.URL.Path),
			zap.String("client_ip", c.ClientIP()),
		)

		c.Next()

		duration := time.Since(start)
		status := c.Writer.Status()

		if status >= 400 {
			g.logger.Warn("Request completed with error",
				zap.String("trace_id", traceID),
				zap.Int("status", status),
				zap.Duration("duration", duration),
				zap.String("error", c.Errors.ByType(gin.ErrorTypePrivate).String()),
			)
		} else {
			g.logger.Debug("Request completed",
				zap.String("trace_id", traceID),
				zap.Int("status", status),
				zap.Duration("duration", duration),
			)
		}
	}
}

func (g *APIGateway) corsMiddleware() gin.HandlerFunc {
	return cors.New(cors.Config{
		AllowOrigins:     []string{"*"},
		AllowMethods:     []string{"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"},
		AllowHeaders:     []string{"Origin", "Content-Type", "Accept", "Authorization", "X-Trace-ID", "X-Device-ID"},
		ExposeHeaders:    []string{"Content-Length", "X-Trace-ID"},
		AllowCredentials: true,
		MaxAge:           12 * time.Hour,
	})
}

func (g *APIGateway) concurrencyLimiterMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		select {
		case g.concurrencySem <- struct{}{}:
			defer func() { <-g.concurrencySem }()
			c.Next()
		default:
			g.monitoring.IncrementCounter("errors_total", map[string]string{
				"module":     "gateway",
				"error_type": "too_many_requests",
			}, 1)
			c.JSON(http.StatusTooManyRequests, gin.H{
				"code":    429,
				"message": "服务繁忙，请稍后重试",
			})
			c.Abort()
		}
	}
}

func (g *APIGateway) rateLimiterMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		clientIP := c.ClientIP()
		limiter := g.getRateLimiter(clientIP)

		if !limiter.Allow() {
			g.monitoring.IncrementCounter("errors_total", map[string]string{
				"module":     "gateway",
				"error_type": "rate_limited",
			}, 1)
			c.JSON(http.StatusTooManyRequests, gin.H{
				"code":    429,
				"message": "请求过于频繁，请稍后重试",
			})
			c.Abort()
			return
		}
		c.Next()
	}
}

func (g *APIGateway) getRateLimiter(clientIP string) *rate.Limiter {
	g.rateLimitMu.Lock()
	defer g.rateLimitMu.Unlock()

	limiter, exists := g.rateLimiters[clientIP]
	if !exists {
		limiter = &RateLimiter{
			limiter:  rate.NewLimiter(rate.Limit(100), 200),
			lastSeen: time.Now(),
		}
		g.rateLimiters[clientIP] = limiter
	}
	limiter.lastSeen = time.Now()
	return limiter.limiter
}

func (g *APIGateway) cleanupRateLimiters(ctx context.Context) {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			g.rateLimitMu.Lock()
			for ip, limiter := range g.rateLimiters {
				if time.Since(limiter.lastSeen) > 10*time.Minute {
					delete(g.rateLimiters, ip)
				}
			}
			g.rateLimitMu.Unlock()
		}
	}
}

func (g *APIGateway) monitoringMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		duration := time.Since(start)
		g.monitoring.RecordRequest(c.Request.Method, c.Request.URL.Path, c.Writer.Status(), duration)
	}
}

func (g *APIGateway) authMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		deviceID := c.GetHeader("X-Device-ID")

		if authHeader == "" {
			c.JSON(http.StatusUnauthorized, gin.H{
				"code":    401,
				"message": "未提供认证信息",
			})
			c.Abort()
			return
		}

		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || parts[0] != "Bearer" {
			c.JSON(http.StatusUnauthorized, gin.H{
				"code":    401,
				"message": "认证格式错误",
			})
			c.Abort()
			return
		}

		token := parts[1]

		if deviceID != "" {
			ctx := c.Request.Context()
			valid, err := g.deviceManager.ValidateSession(ctx, deviceID, token)
			if err != nil || !valid {
				c.JSON(http.StatusUnauthorized, gin.H{
					"code":    401,
					"message": "认证失败",
				})
				c.Abort()
				return
			}
			c.Set("device_id", deviceID)
		} else {
			if token != "admin-token" {
				c.JSON(http.StatusUnauthorized, gin.H{
					"code":    401,
					"message": "认证失败",
				})
				c.Abort()
				return
			}
		}

		c.Set("authenticated", true)
		c.Next()
	}
}

func (g *APIGateway) registerDefaultRoutes() {
	g.router.GET("/health", g.healthCheckHandler)
	g.router.GET("/metrics", g.metricsHandler)
}

func (g *APIGateway) registerAPIRoutes() {
	api := g.router.Group("/api/v1")
	{
		resources := api.Group("/resources")
		{
			resources.POST("", g.authMiddleware(), g.createResourceHandler)
			resources.GET("/:id/status", g.authMiddleware(), g.getResourceStatusHandler)
			resources.POST("/batch", g.authMiddleware(), g.batchOperationHandler)
		}

		configs := api.Group("/configs")
		{
			configs.GET("", g.authMiddleware(), g.listConfigsHandler)
			configs.POST("", g.authMiddleware(), g.createConfigHandler)
			configs.GET("/:namespace", g.authMiddleware(), g.getConfigHandler)
			configs.PUT("/:namespace", g.authMiddleware(), g.updateConfigHandler)
			configs.DELETE("/:namespace", g.authMiddleware(), g.deleteConfigHandler)
			configs.POST("/:namespace/validate", g.authMiddleware(), g.validateConfigHandler)
		}

		devices := api.Group("/devices")
		{
			devices.POST("/register", g.registerDeviceHandler)
			devices.POST("/activate", g.activateDeviceHandler)
			devices.POST("/authenticate", g.authenticateDeviceHandler)
			devices.POST("/heartbeat", g.authMiddleware(), g.heartbeatHandler)
			devices.GET("", g.authMiddleware(), g.listDevicesHandler)
			devices.GET("/:id", g.authMiddleware(), g.getDeviceHandler)
			devices.DELETE("/:id", g.authMiddleware(), g.deleteDeviceHandler)
		}

		rules := api.Group("/rules")
		{
			rules.POST("", g.authMiddleware(), g.createRuleHandler)
			rules.GET("", g.authMiddleware(), g.listRulesHandler)
			rules.POST("/evaluate", g.authMiddleware(), g.evaluateRuleHandler)
		}

		inference := api.Group("/inference")
		{
			inference.POST("/models", g.authMiddleware(), g.registerModelHandler)
			inference.GET("/models", g.authMiddleware(), g.listModelsHandler)
			inference.POST("/tasks", g.authMiddleware(), g.submitInferenceTaskHandler)
			inference.GET("/tasks/:id", g.authMiddleware(), g.getInferenceTaskHandler)
		}

		ota := api.Group("/ota")
		{
			ota.POST("/firmware", g.authMiddleware(), g.uploadFirmwareHandler)
			ota.GET("/firmware", g.authMiddleware(), g.listFirmwareHandler)
			ota.POST("/jobs", g.authMiddleware(), g.createOTAJobHandler)
			ota.GET("/jobs", g.authMiddleware(), g.listOTAJobsHandler)
			ota.GET("/jobs/:id", g.authMiddleware(), g.getOTAJobHandler)
		}

		storage := api.Group("/storage")
		{
			storage.POST("/files", g.authMiddleware(), g.uploadFileHandler)
			storage.GET("/files/:id", g.authMiddleware(), g.downloadFileHandler)
			storage.DELETE("/files/:id", g.authMiddleware(), g.deleteFileHandler)
			storage.GET("/files", g.authMiddleware(), g.listFilesHandler)
			storage.POST("/policies", g.authMiddleware(), g.createLifecyclePolicyHandler)
		}

		monitoring := api.Group("/monitoring")
		{
			monitoring.GET("/metrics/json", g.authMiddleware(), g.getMetricsJSONHandler)
			monitoring.GET("/snapshots", g.authMiddleware(), g.getSnapshotsHandler)
			monitoring.GET("/health", g.authMiddleware(), g.getHealthHandler)
		}
	}
}

func (g *APIGateway) RegisterRoute(def *RouteDefinition) {
	key := def.Method + ":" + def.Path
	g.routes[key] = def
	g.logger.Info("Route registered", zap.String("path", def.Path), zap.String("method", def.Method))
}

func (g *APIGateway) RegisterService(name string, instance *ServiceInstance) {
	g.services[name] = append(g.services[name], instance)
	g.logger.Info("Service registered", zap.String("name", name), zap.String("address", instance.Address))
}

func (g *APIGateway) healthCheckHandler(c *gin.Context) {
	ctx := c.Request.Context()
	health, err := g.monitoring.GetHealth(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "健康检查失败",
			"error":   err.Error(),
		})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": health,
	})
}

func (g *APIGateway) metricsHandler(c *gin.Context) {
	registry := g.monitoring.GetRegistry()
	h := gin.WrapH(prometheusHandler(registry))
	h(c)
}

func (g *APIGateway) createResourceHandler(c *gin.Context) {
	var req struct {
		Type   string                 `json:"type" binding:"required"`
		Config map[string]interface{} `json:"config"`
		Labels map[string]string      `json:"labels"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "请求参数错误",
			"error":   err.Error(),
		})
		return
	}

	ctx := c.Request.Context()
	traceID := c.GetString("trace_id")

	handler, exists := g.getHandlerForType(req.Type)
	if !exists {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "不支持的资源类型",
		})
		return
	}

	result, err := handler(ctx, req.Type, "default", req.Config, traceID)
	if err != nil {
		appErr, ok := err.(*errors.AppError)
		if ok {
			c.JSON(http.StatusUnprocessableEntity, gin.H{
				"code":    appErr.Code,
				"message": appErr.Message,
				"details": appErr.Details,
			})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "处理失败",
			"error":   err.Error(),
		})
		return
	}

	g.eventBus.Publish(ctx, events.Event{
		ID:        utils.GenerateID("evt"),
		Type:      events.EventResourceCreated,
		Source:    "gateway",
		Timestamp: time.Now(),
		TraceID:   traceID,
		Payload: map[string]interface{}{
			"result": result,
		},
	})

	c.JSON(http.StatusCreated, result)
}

func (g *APIGateway) getResourceStatusHandler(c *gin.Context) {
	id := c.Param("id")

	ctx := c.Request.Context()
	status, err := g.getResourceStatus(ctx, id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{
			"code":    404,
			"message": "资源不存在",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": status,
	})
}

func (g *APIGateway) batchOperationHandler(c *gin.Context) {
	var req struct {
		Operations []struct {
			Action string `json:"action" binding:"required"`
			ID     string `json:"id" binding:"required"`
		} `json:"operations" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "请求参数错误",
		})
		return
	}

	ctx := c.Request.Context()
	results := make([]map[string]interface{}, 0, len(req.Operations))

	for _, op := range req.Operations {
		result := g.executeBatchOperation(ctx, op.Action, op.ID)
		results = append(results, result)
	}

	batchID := utils.GenerateID("batch")
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"batch_id": batchID,
			"results":  results,
		},
	})
}

func (g *APIGateway) getHandlerForType(resourceType string) (func(context.Context, string, string, interface{}, string) (*model.ApiResponse, error), bool) {
	handlerFunc, ok := handlerRegistry[resourceType]
	if !ok {
		return nil, false
	}
	return handlerFunc, true
}

func (g *APIGateway) getResourceStatus(ctx context.Context, id string) (map[string]interface{}, error) {
	return map[string]interface{}{
		"id":       id,
		"status":   "completed",
		"progress": 1.0,
	}, nil
}

func (g *APIGateway) executeBatchOperation(ctx context.Context, action, id string) map[string]interface{} {
	return map[string]interface{}{
		"id":     id,
		"action": action,
		"status": "success",
	}
}

func (g *APIGateway) listConfigsHandler(c *gin.Context) {
	ctx := c.Request.Context()
	namespace := c.Query("namespace")
	offset := 0
	limit := 100

	configs, total, err := g.configManager.ListConfigs(ctx, namespace, offset, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "获取配置列表失败",
		})
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"items": configs,
			"total": total,
		},
	})
}

func (g *APIGateway) createConfigHandler(c *gin.Context) {
	var req struct {
		Namespace  string                 `json:"namespace" binding:"required"`
		Parameters map[string]interface{} `json:"parameters" binding:"required"`
		Enabled    bool                   `json:"enabled"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "请求参数错误",
		})
		return
	}

	ctx := c.Request.Context()
	cfg, err := g.configManager.CreateConfig(ctx, req.Namespace, req.Parameters, req.Enabled)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "创建配置失败",
			"error":   err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"code": 201,
		"data": cfg,
	})
}

func (g *APIGateway) getConfigHandler(c *gin.Context) {
	namespace := c.Param("namespace")
	ctx := c.Request.Context()

	cfg, err := g.configManager.GetConfig(ctx, namespace)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{
			"code":    404,
			"message": "配置不存在",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": cfg,
	})
}

func (g *APIGateway) updateConfigHandler(c *gin.Context) {
	namespace := c.Param("namespace")
	var req struct {
		Parameters map[string]interface{} `json:"parameters" binding:"required"`
		Enabled    bool                   `json:"enabled"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "请求参数错误",
		})
		return
	}

	ctx := c.Request.Context()
	cfg, err := g.configManager.UpdateConfig(ctx, namespace, req.Parameters, req.Enabled)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "更新配置失败",
			"error":   err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": cfg,
	})
}

func (g *APIGateway) deleteConfigHandler(c *gin.Context) {
	namespace := c.Param("namespace")
	ctx := c.Request.Context()

	if err := g.configManager.DeleteConfig(ctx, namespace); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "删除配置失败",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "删除成功",
	})
}

func (g *APIGateway) validateConfigHandler(c *gin.Context) {
	namespace := c.Param("namespace")
	var req struct {
		Parameters map[string]interface{} `json:"parameters" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "请求参数错误",
		})
		return
	}

	ctx := c.Request.Context()
	result, err := g.configManager.ValidateConfig(ctx, namespace, req.Parameters)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "校验失败",
			"error":   err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": result,
	})
}

func (g *APIGateway) registerDeviceHandler(c *gin.Context) {
	var req model.DeviceRegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "请求参数错误",
		})
		return
	}

	ctx := c.Request.Context()
	device, err := g.deviceManager.Register(ctx, &req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "注册失败",
			"error":   err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"code": 201,
		"data": device,
	})
}

func (g *APIGateway) activateDeviceHandler(c *gin.Context) {
	var req model.DeviceActivateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "请求参数错误",
		})
		return
	}

	ctx := c.Request.Context()
	device, err := g.deviceManager.Activate(ctx, &req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "激活失败",
			"error":   err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": device,
	})
}

func (g *APIGateway) authenticateDeviceHandler(c *gin.Context) {
	var req model.DeviceAuthRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "请求参数错误",
		})
		return
	}

	ctx := c.Request.Context()
	token, err := g.deviceManager.Authenticate(ctx, &req)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{
			"code":    401,
			"message": "认证失败",
			"error":   err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"token": token,
		},
	})
}

func (g *APIGateway) heartbeatHandler(c *gin.Context) {
	deviceID := c.GetString("device_id")
	var req model.DeviceHeartbeatRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "请求参数错误",
		})
		return
	}

	ctx := c.Request.Context()
	if err := g.deviceManager.Heartbeat(ctx, deviceID, &req); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "心跳失败",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"timestamp": time.Now().UTC(),
		},
	})
}

func (g *APIGateway) listDevicesHandler(c *gin.Context) {
	ctx := c.Request.Context()
	devices, _, err := g.deviceManager.List(ctx, 0, 100)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "获取设备列表失败",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": devices,
	})
}

func (g *APIGateway) getDeviceHandler(c *gin.Context) {
	id := c.Param("id")
	ctx := c.Request.Context()

	device, err := g.deviceManager.Get(ctx, id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{
			"code":    404,
			"message": "设备不存在",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": device,
	})
}

func (g *APIGateway) deleteDeviceHandler(c *gin.Context) {
	id := c.Param("id")
	ctx := c.Request.Context()

	if err := g.deviceManager.Deactivate(ctx, id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "注销失败",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "注销成功",
	})
}

func (g *APIGateway) createRuleHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) listRulesHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) evaluateRuleHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) registerModelHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) listModelsHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) submitInferenceTaskHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) getInferenceTaskHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) uploadFirmwareHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) listFirmwareHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) createOTAJobHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) listOTAJobsHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) getOTAJobHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) uploadFileHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) downloadFileHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) deleteFileHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) listFilesHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) createLifecyclePolicyHandler(c *gin.Context) {
	c.JSON(http.StatusNotImplemented, gin.H{
		"code":    501,
		"message": "功能开发中",
	})
}

func (g *APIGateway) getMetricsJSONHandler(c *gin.Context) {
	ctx := c.Request.Context()
	jsonData, err := g.monitoring.ExportJSON(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "获取指标失败",
		})
		return
	}

	c.Header("Content-Type", "application/json")
	c.String(http.StatusOK, jsonData)
}

func (g *APIGateway) getSnapshotsHandler(c *gin.Context) {
	ctx := c.Request.Context()

	startTime := time.Now().Add(-24 * time.Hour)
	endTime := time.Now()

	snapshots, total, err := g.monitoring.QuerySnapshots(ctx, startTime, endTime, nil, 0, 100)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "查询快照失败",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"items": snapshots,
			"total": total,
		},
	})
}

func (g *APIGateway) getHealthHandler(c *gin.Context) {
	ctx := c.Request.Context()
	health, err := g.monitoring.GetHealth(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "健康检查失败",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": health,
	})
}

func prometheusHandler(registry *prometheus.Registry) http.Handler {
	return promhttp.HandlerFor(registry, promhttp.HandlerOpts{})
}

var handlerRegistry = make(map[string]func(context.Context, string, string, interface{}, string) (*model.ApiResponse, error))

func RegisterHandler(resourceType string, handler func(context.Context, string, string, interface{}, string) (*model.ApiResponse, error)) {
	handlerRegistry[resourceType] = handler
}

func init() {
	RegisterHandler("task", func(ctx context.Context, rt, ns string, payload interface{}, tid string) (*model.ApiResponse, error) {
		return &model.ApiResponse{
			Code: 201,
			Data: map[string]interface{}{
				"id":     utils.GenerateID("rsc"),
				"status": "provisioning",
			},
		}, nil
	})
}


