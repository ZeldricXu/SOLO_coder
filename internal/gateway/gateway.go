package gateway

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"DF1-56/internal/config"
	"DF1-56/internal/diagnostics"
	"DF1-56/internal/middleware"
	"DF1-56/internal/mirror"
	"DF1-56/internal/models"
	"DF1-56/internal/ratelimit"
	"DF1-56/internal/router"
	"DF1-56/internal/storage"
	"DF1-56/internal/telemetry"
)

type Config struct {
	Server         ServerConfig
	Admin        AdminConfig
	ETCD          config.ETCDConfig
	Redis         storage.RedisConfig
	PostgreSQL    storage.PostgresConfig
	Telemetry     telemetry.Config
	ValidAPIKeys  map[string]string
	EnabledMiddlewares []string
}

type ServerConfig struct {
	Host         string
	Port         int
	ReadTimeout  time.Duration
	WriteTimeout time.Duration
	IdleTimeout  time.Duration
	MaxHeaderBytes int
}

type AdminConfig struct {
	Host         string
	Port         int
	ReadTimeout  time.Duration
	WriteTimeout time.Duration
}

type ETCDConfig struct {
	Endpoints []string
	Username  string
	Password  string
}

type Gateway struct {
	cfg             *Config
	logger          *zap.Logger
	etcdClient     *config.ETCDClient
	configLoader   *config.ConfigLoader
	configStore    *config.ConfigStore
	configWatcher  *config.ConfigWatcher
	redisClient    *storage.RedisClient
	postgresClient *storage.PostgresClient
	telemetry      *telemetry.Telemetry
	routerManager  *router.RouterManager
	forwarder      *router.Forwarder
	loadBalancer   *router.LoadBalancer
	rateLimitManager *ratelimit.Manager
	grayManager    *mirror.GrayManager
	mirrorManager  *mirror.MirrorManager
	chainBuilder   *middleware.ChainBuilder
	diagnosticManager *diagnostics.DiagnosticManager
	httpServer     *http.Server
	adminServer    *http.Server
	middlewareChains map[string]*models.MiddlewareChain
	mu             sync.RWMutex
	wg             sync.WaitGroup
	stopCh         chan struct{}
	running        bool
}

func NewGateway(cfg *Config) (*Gateway, error) {
	if cfg == nil {
		return nil, fmt.Errorf("config cannot be nil")
	}

	logger, err := zap.NewProduction()
	if err != nil {
		return nil, fmt.Errorf("failed to create logger: %w", err)
	}

	g := &Gateway{
		cfg:              cfg,
		logger:           logger,
		configStore:      config.NewConfigStore(),
		diagnosticManager: diagnostics.NewDiagnosticManager(),
		middlewareChains: make(map[string]*models.MiddlewareChain),
		stopCh:           make(chan struct{}),
	}

	if err := g.initETCD(); err != nil {
		g.logger.Error("failed to init etcd", zap.Error(err))
	}

	if err := g.initStorage(); err != nil {
		g.logger.Error("failed to init storage", zap.Error(err))
	}

	if err := g.initTelemetry(); err != nil {
		g.logger.Error("failed to init telemetry", zap.Error(err))
	}

	g.initCoreModules()

	if err := g.initChainBuilder(); err != nil {
		return nil, fmt.Errorf("failed to init chain builder: %w", err)
	}

	if err := g.loadConfig(); err != nil {
		g.logger.Error("failed to load config", zap.Error(err))
	}

	if err := g.initConfigWatcher(); err != nil {
		g.logger.Error("failed to init config watcher", zap.Error(err))
	}

	if err := g.buildAllMiddlewareChains(); err != nil {
		return nil, fmt.Errorf("failed to build middleware chains: %w", err)
	}

	return g, nil
}

func (g *Gateway) initETCD() error {
	if g.cfg.ETCD.Endpoints == nil || len(g.cfg.ETCD.Endpoints) == 0 {
		g.logger.Warn("etcd endpoints not configured, skipping etcd initialization")
		return nil
	}

	client, err := config.NewETCDClient(g.cfg.ETCD.Endpoints, g.cfg.ETCD.Username, g.cfg.ETCD.Password)
	if err != nil {
		return fmt.Errorf("failed to create etcd client: %w", err)
	}

	g.etcdClient = client
	g.configLoader = config.NewConfigLoader(client)

	g.logger.Info("etcd client initialized", zap.Strings("endpoints", g.cfg.ETCD.Endpoints))

	return nil
}

func (g *Gateway) initStorage() error {
	if g.cfg.Redis.Address != "" {
		redisClient, err := storage.NewRedisClient(g.cfg.Redis)
		if err != nil {
			return fmt.Errorf("failed to create redis client: %w", err)
		}
		g.redisClient = redisClient
		g.logger.Info("redis client initialized", zap.String("address", g.cfg.Redis.Address))
	}

	if g.cfg.PostgreSQL.Host != "" {
		pgClient, err := storage.NewPostgresClient(g.cfg.PostgreSQL)
		if err != nil {
			return fmt.Errorf("failed to create postgres client: %w", err)
		}
		g.postgresClient = pgClient

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := g.postgresClient.InitSchema(ctx); err != nil {
			g.logger.Warn("failed to init postgres schema", zap.Error(err))
		}

		g.logger.Info("postgres client initialized", zap.String("host", g.cfg.PostgreSQL.Host))
	}

	return nil
}

func (g *Gateway) initTelemetry() error {
	if g.cfg.Telemetry.ServiceName == "" {
		g.cfg.Telemetry.ServiceName = "api-gateway"
	}
	if g.cfg.Telemetry.ServiceVersion == "" {
		g.cfg.Telemetry.ServiceVersion = "1.0.0"
	}

	t, err := telemetry.NewTelemetry(g.cfg.Telemetry)
	if err != nil {
		return fmt.Errorf("failed to create telemetry: %w", err)
	}

	g.telemetry = t
	g.logger.Info("telemetry initialized",
		zap.Bool("metrics_enabled", g.cfg.Telemetry.MetricsEnabled),
		zap.Bool("tracing_enabled", g.cfg.Telemetry.TracingEnabled),
	)

	return nil
}

func (g *Gateway) initCoreModules() {
	g.routerManager = router.NewRouterManager()
	g.forwarder = router.NewForwarder()
	g.loadBalancer = router.NewLoadBalancer()
	g.grayManager = mirror.NewGrayManager()
	g.mirrorManager = mirror.NewMirrorManager(nil)

	if g.redisClient != nil {
		g.rateLimitManager = ratelimit.NewManager(g.redisClient.Client())
	}

	g.logger.Info("core modules initialized")
}

func (g *Gateway) initChainBuilder() error {
	cbCfg := middleware.ChainBuilderConfig{
		ConfigStore:       g.configStore,
		RateLimitManager:  g.rateLimitManager,
		GrayManager:       g.grayManager,
		MirrorManager:     g.mirrorManager,
		RouterManager:     g.routerManager,
		Forwarder:         g.forwarder,
		LoadBalancer:      g.loadBalancer,
		Telemetry:         g.telemetry,
		Logger:            g.logger,
		ValidAPIKeys:       g.cfg.ValidAPIKeys,
		EnabledMiddlewares: g.cfg.EnabledMiddlewares,
	}

	cb, err := middleware.NewChainBuilder(cbCfg)
	if err != nil {
		return err
	}

	g.chainBuilder = cb
	return nil
}

func (g *Gateway) loadConfig() error {
	if g.configLoader == nil {
		g.logger.Warn("config loader not available, skipping config loading")
		return nil
	}

	cfg, err := g.configLoader.LoadAll()
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	g.configStore.LoadAll(cfg)

	g.logger.Info("config loaded",
		zap.Int("routes", len(cfg.Routes)),
		zap.Int("rate_limits", len(cfg.RateLimits)),
		zap.Int("auths", len(cfg.Auths)),
		zap.Int("circuit_breakers", len(cfg.CircuitBreakers)),
		zap.Int("grays", len(cfg.Grays)),
		zap.Int("mirrors", len(cfg.Mirrors)),
		zap.Int("upstreams", len(cfg.Upstreams)),
	)

	return nil
}

func (g *Gateway) initConfigWatcher() error {
	if g.etcdClient == nil {
		g.logger.Warn("etcd client not available, skipping config watcher")
		return nil
	}

	watcher := config.NewConfigWatcher(g.etcdClient)
	g.configWatcher = watcher

	onChange := func(key string, value []byte, deleted bool) {
		changeType := config.ChangeTypePut
		if deleted {
			changeType = config.ChangeTypeDelete
		}
		g.onConfigChange(changeType, key, value)
	}

	_ = watcher.Watch(config.EtcdKeyPrefixRoutes, onChange)
	_ = watcher.Watch(config.EtcdKeyPrefixRateLimits, onChange)
	_ = watcher.Watch(config.EtcdKeyPrefixAuths, onChange)
	_ = watcher.Watch(config.EtcdKeyPrefixCircuitBreakers, onChange)
	_ = watcher.Watch(config.EtcdKeyPrefixGrays, onChange)
	_ = watcher.Watch(config.EtcdKeyPrefixMirrors, onChange)
	_ = watcher.Watch(config.EtcdKeyPrefixUpstreams, onChange)

	g.logger.Info("config watcher started")
	return nil
}

func (g *Gateway) onConfigChange(changeType config.ChangeType, key string, value []byte) {
	g.logger.Info("config changed",
		zap.String("type", string(changeType)),
		zap.String("key", key),
	)

	if strings.HasPrefix(key, config.EtcdKeyPrefixRoutes) {
		id := strings.TrimPrefix(key, config.EtcdKeyPrefixRoutes)
		if changeType == config.ChangeTypeDelete {
			g.configStore.DeleteRoute(id)
		} else {
			var route models.Route
			if err := json.Unmarshal(value, &route); err == nil {
				g.configStore.SetRoute(id, &route)
			}
		}
		g.routerManager.ReloadRoutes(g.configStore.GetRoutes())
	} else if strings.HasPrefix(key, config.EtcdKeyPrefixRateLimits) {
		id := strings.TrimPrefix(key, config.EtcdKeyPrefixRateLimits)
		if changeType == config.ChangeTypeDelete {
			g.configStore.DeleteRateLimit(id)
		} else {
			var policy models.RateLimitPolicy
			if err := json.Unmarshal(value, &policy); err == nil {
				g.configStore.SetRateLimit(id, &policy)
			}
		}
	} else if strings.HasPrefix(key, config.EtcdKeyPrefixAuths) {
		id := strings.TrimPrefix(key, config.EtcdKeyPrefixAuths)
		if changeType == config.ChangeTypeDelete {
			g.configStore.DeleteAuth(id)
		} else {
			var policy models.AuthPolicy
			if err := json.Unmarshal(value, &policy); err == nil {
				g.configStore.SetAuth(id, &policy)
			}
		}
	} else if strings.HasPrefix(key, config.EtcdKeyPrefixCircuitBreakers) {
		id := strings.TrimPrefix(key, config.EtcdKeyPrefixCircuitBreakers)
		if changeType == config.ChangeTypeDelete {
			g.configStore.DeleteCircuitBreaker(id)
		} else {
			var policy models.CircuitBreakerPolicy
			if err := json.Unmarshal(value, &policy); err == nil {
				g.configStore.SetCircuitBreaker(id, &policy)
			}
		}
	} else if strings.HasPrefix(key, config.EtcdKeyPrefixGrays) {
		id := strings.TrimPrefix(key, config.EtcdKeyPrefixGrays)
		if changeType == config.ChangeTypeDelete {
			g.configStore.DeleteGray(id)
		} else {
			var policy models.GrayPolicy
			if err := json.Unmarshal(value, &policy); err == nil {
				g.configStore.SetGray(id, &policy)
			}
		}
	} else if strings.HasPrefix(key, config.EtcdKeyPrefixMirrors) {
		id := strings.TrimPrefix(key, config.EtcdKeyPrefixMirrors)
		if changeType == config.ChangeTypeDelete {
			g.configStore.DeleteMirror(id)
		} else {
			var policy models.MirrorPolicy
			if err := json.Unmarshal(value, &policy); err == nil {
				g.configStore.SetMirror(id, &policy)
			}
		}
	} else if strings.HasPrefix(key, config.EtcdKeyPrefixUpstreams) {
		id := strings.TrimPrefix(key, config.EtcdKeyPrefixUpstreams)
		if changeType == config.ChangeTypeDelete {
			g.configStore.DeleteUpstream(id)
		} else {
			var cluster models.UpstreamCluster
			if err := json.Unmarshal(value, &cluster); err == nil {
				g.configStore.SetUpstream(id, &cluster)
			}
		}
	}

	if err := g.buildAllMiddlewareChains(); err != nil {
		g.logger.Error("failed to rebuild middleware chains", zap.Error(err))
	}
}

func (g *Gateway) buildAllMiddlewareChains() error {
	g.mu.Lock()
	defer g.mu.Unlock()

	routes := g.configStore.GetRoutes()

	newChains := make(map[string]*models.MiddlewareChain)

	for id, route := range routes {
		if !route.Enabled {
			continue
		}

		chain, err := g.chainBuilder.Build(route)
		if err != nil {
			g.logger.Error("failed to build middleware chain",
				zap.String("route_id", id),
				zap.Error(err),
			)
			continue
		}

		newChains[id] = chain

		if err := g.routerManager.AddRoute(route); err != nil {
			g.logger.Error("failed to add route to router",
				zap.String("route_id", id),
				zap.Error(err),
			)
		}
	}

	for _, policy := range g.configStore.GetGrays() {
		g.grayManager.AddPolicy(policy)
	}

	for _, policy := range g.configStore.GetMirrors() {
		g.mirrorManager.AddPolicy(policy)
	}

	g.middlewareChains = newChains

	g.logger.Info("middleware chains built", zap.Int("count", len(newChains)))

	return nil
}

func (g *Gateway) buildMiddlewareChain(route *models.Route) (*models.MiddlewareChain, error) {
	return g.chainBuilder.Build(route)
}

func (g *Gateway) Start() error {
	g.mu.Lock()
	if g.running {
		g.mu.Unlock()
		return fmt.Errorf("gateway is already running")
	}
	g.running = true
	g.mu.Unlock()

	if err := g.startHTTPServer(); err != nil {
		return fmt.Errorf("failed to start http server: %w", err)
	}

	if err := g.startAdminServer(); err != nil {
		return fmt.Errorf("failed to start admin server: %w", err)
	}

	g.diagnosticManager.Start()

	g.logger.Info("gateway started",
		zap.String("server_host", g.cfg.Server.Host),
		zap.Int("server_port", g.cfg.Server.Port),
		zap.String("admin_host", g.cfg.Admin.Host),
		zap.Int("admin_port", g.cfg.Admin.Port),
	)

	return nil
}

func (g *Gateway) startHTTPServer() error {
	addr := fmt.Sprintf("%s:%d", g.cfg.Server.Host, g.cfg.Server.Port)

	g.httpServer = &http.Server{
		Addr:           addr,
		Handler:        g,
		ReadTimeout:  g.cfg.Server.ReadTimeout,
		WriteTimeout: g.cfg.Server.WriteTimeout,
		IdleTimeout:  g.cfg.Server.IdleTimeout,
		MaxHeaderBytes: g.cfg.Server.MaxHeaderBytes,
	}

	g.wg.Add(1)
	go func() {
		defer g.wg.Done()

		g.logger.Info("starting http server", zap.String("addr", addr))

		if err := g.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			g.logger.Fatal("http server error", zap.Error(err))
		}
	}()

	return nil
}

func (g *Gateway) startAdminServer() error {
	addr := fmt.Sprintf("%s:%d", g.cfg.Admin.Host, g.cfg.Admin.Port)

	mux := http.NewServeMux()

	mux.HandleFunc("/health", g.handleHealth)
	mux.HandleFunc("/metrics", g.handleMetrics)
	mux.HandleFunc("/api/v1/routes", g.handleRoutes)
	mux.HandleFunc("/api/v1/routes/", g.handleRouteByID)
	mux.HandleFunc("/api/v1/config", g.handleConfig)
	mux.HandleFunc("/api/v1/config/reload", g.handleConfigReload)
	mux.HandleFunc("/api/v1/diagnostics/traffic", g.handleDiagnosticsTraffic)
	mux.HandleFunc("/api/v1/diagnostics/status", g.handleDiagnosticsStatus)

	g.adminServer = &http.Server{
		Addr:         addr,
		Handler:        mux,
		ReadTimeout:  g.cfg.Admin.ReadTimeout,
		WriteTimeout: g.cfg.Admin.WriteTimeout,
	}

	g.wg.Add(1)
	go func() {
		defer g.wg.Done()

		g.logger.Info("starting admin server", zap.String("addr", addr))

		if err := g.adminServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			g.logger.Fatal("admin server error", zap.Error(err))
		}
	}()

	return nil
}

func (g *Gateway) Stop() error {
	g.mu.Lock()
	if !g.running {
		g.mu.Unlock()
		return nil
	}
	g.running = false
	g.mu.Unlock()

	close(g.stopCh)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if g.httpServer != nil {
		if err := g.httpServer.Shutdown(ctx); err != nil {
			g.logger.Error("failed to shutdown http server", zap.Error(err))
		}
	}

	if g.adminServer != nil {
		if err := g.adminServer.Shutdown(ctx); err != nil {
			g.logger.Error("failed to shutdown admin server", zap.Error(err))
		}
	}

	if g.chainBuilder != nil {
		g.chainBuilder.Stop()
	}

	if g.diagnosticManager != nil {
		g.diagnosticManager.Stop()
	}

	if g.configWatcher != nil {
		g.configWatcher.Stop()
	}

	if g.etcdClient != nil {
		if err := g.etcdClient.Close(); err != nil {
			g.logger.Error("failed to close etcd client", zap.Error(err))
		}
	}

	if g.redisClient != nil {
		if err := g.redisClient.Close(); err != nil {
			g.logger.Error("failed to close redis client", zap.Error(err))
		}
	}

	if g.postgresClient != nil {
		if err := g.postgresClient.Close(); err != nil {
			g.logger.Error("failed to close postgres client", zap.Error(err))
		}
	}

	if g.telemetry != nil {
		g.telemetry.Shutdown()
	}

	g.wg.Wait()

	_ = g.logger.Sync()

	g.logger.Info("gateway stopped")

	return nil
}

func (g *Gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	ctx := models.NewGatewayContext(w, r)

	if err := g.handleRequest(ctx); err != nil {
		g.logger.Error("request handling failed",
			zap.String("request_id", ctx.RequestID),
			zap.Error(err),
		)
		g.sendError(ctx, http.StatusInternalServerError, err.Error())
	}

	duration := time.Since(ctx.StartTime)
	statusCode := http.StatusOK

	if recorder, ok := ctx.Response.(interface{ StatusCode() int }); ok {
		statusCode = recorder.StatusCode()
	}

	routeID := ""
	if ctx.Route != nil {
		routeID = ctx.Route.ID
	}

	if g.telemetry != nil {
		g.telemetry.RecordRequest(ctx.Request.Method, ctx.Request.URL.Path, routeID, statusCode, duration)
	}

	rateLimited := false
	if v, ok := ctx.Get(string(models.ContextKeyRateLimit)); ok {
		if rl, ok := v.(bool); ok {
			rateLimited = rl
		}
	}

	circuitBroken := false
	if v, ok := ctx.Get(string(models.ContextKeyCircuitBroken)); ok {
		if cb, ok := v.(bool); ok {
			circuitBroken = cb
		}
	}

	errorType := ""
	if v, ok := ctx.Get("error_type"); ok {
		if et, ok := v.(string); ok {
			errorType = et
		}
	}

	g.diagnosticManager.Record(&diagnostics.RequestMetric{
		Timestamp:          time.Now(),
		Path:               ctx.Request.URL.Path,
		RouteID:            routeID,
		Method:             ctx.Request.Method,
		StatusCode:         statusCode,
		Latency:            duration,
		RateLimitRejected:  rateLimited,
		CircuitBreakerOpen: circuitBroken,
		ErrorType:          errorType,
	})

	if g.postgresClient != nil {
		go g.saveAuditLog(ctx)
	}
}

func (g *Gateway) handleRequest(ctx *models.GatewayContext) error {
	route, pathParams := g.routerManager.MatchRoute(ctx.Request.Method, ctx.Request.URL.Path)
	if route == nil {
		g.logger.Warn("route not found",
			zap.String("method", ctx.Request.Method),
			zap.String("path", ctx.Request.URL.Path),
		)
		g.sendError(ctx, http.StatusNotFound, "route not found")
		return nil
	}

	ctx.Route = route
	ctx.PathParams = pathParams

	g.mu.RLock()
	chain, exists := g.middlewareChains[route.ID]
	g.mu.RUnlock()

	if !exists {
		g.logger.Warn("middleware chain not found", zap.String("route_id", route.ID))
		g.sendError(ctx, http.StatusInternalServerError, "middleware chain not found")
		return nil
	}

	handler := chain.Then(func(ctx *models.GatewayContext) error {
		return nil
	})

	return handler(ctx)
}

func (g *Gateway) sendError(ctx *models.GatewayContext, statusCode int, message string) {
	ctx.Response.Header().Set("Content-Type", "application/json")
	ctx.Response.WriteHeader(statusCode)
	body := fmt.Sprintf(`{"error":"%s","code":%d,"request_id":"%s"}`,
		message, statusCode, ctx.RequestID)
	_, _ = ctx.Response.Write([]byte(body))
}

func (g *Gateway) saveAuditLog(ctx *models.GatewayContext) {
	auditLog := &models.AuditLog{
		ID:             uuid.New().String(),
		Timestamp:      time.Now(),
		RequestID:      ctx.RequestID,
		TraceID:        ctx.TraceID,
		UserID:         ctx.UserID,
		APIKey:         "",
		ClientIP:       ctx.ClientIP,
		Method:         ctx.Request.Method,
		Path:           ctx.Request.URL.Path,
		RouteID:        "",
		Upstream:       "",
		StatusCode:     200,
		Duration:       time.Since(ctx.StartTime).Milliseconds(),
		Error:          "",
		RateLimited:    false,
		CircuitBroken:  false,
		GrayVersion:    "",
	}

	if ctx.Route != nil {
		auditLog.RouteID = ctx.Route.ID
		auditLog.Upstream = ctx.Route.UpstreamURL
		if ctx.Route.UpstreamCluster != "" {
			auditLog.Upstream = ctx.Route.UpstreamCluster
		}
	}

	if v, ok := ctx.Get(string(models.ContextKeyGrayVersion)); ok {
		if grayVersion, ok := v.(string); ok {
			auditLog.GrayVersion = grayVersion
		}
	}

	if v, ok := ctx.Get(string(models.ContextKeyRateLimit)); ok {
		if rateLimited, ok := v.(bool); ok {
			auditLog.RateLimited = rateLimited
		}
	}

	ctx2, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	if err := g.postgresClient.CreateAuditLog(ctx2, auditLog); err != nil {
		g.logger.Error("failed to save audit log", zap.Error(err))
	}
}

func (g *Gateway) handleHealth(w http.ResponseWriter, r *http.Request) {
	status := map[string]interface{}{
		"status":    "healthy",
		"timestamp": time.Now().UTC(),
	}

	if g.etcdClient != nil {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		status["etcd"] = "healthy"
		if err := g.etcdClient.Client().Sync(ctx); err != nil {
			status["etcd"] = "unhealthy"
			status["status"] = "degraded"
		}
	}

	if g.redisClient != nil {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		status["redis"] = "healthy"
		if err := g.redisClient.Ping(ctx); err != nil {
			status["redis"] = "unhealthy"
			status["status"] = "degraded"
		}
	}

	if g.postgresClient != nil {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		status["postgres"] = "healthy"
		if err := g.postgresClient.Ping(ctx); err != nil {
			status["postgres"] = "unhealthy"
			status["status"] = "degraded"
		}
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(status)
}

func (g *Gateway) handleMetrics(w http.ResponseWriter, r *http.Request) {
	if g.telemetry != nil {
		g.telemetry.MetricsHandler().ServeHTTP(w, r)
	}
}

func (g *Gateway) handleRoutes(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		g.listRoutes(w, r)
	case http.MethodPost:
		g.addRoute(w, r)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (g *Gateway) listRoutes(w http.ResponseWriter, r *http.Request) {
	routes := g.configStore.GetRoutes()

	result := make([]*models.Route, 0, len(routes))
	for _, route := range routes {
		result = append(result, route)
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(result)
}

func (g *Gateway) addRoute(w http.ResponseWriter, r *http.Request) {
	var route models.Route
	if err := json.NewDecoder(r.Body).Decode(&route); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	if route.ID == "" {
		route.ID = uuid.New().String()
	}
	route.CreatedAt = time.Now()
	route.UpdatedAt = time.Now()

	g.configStore.SetRoute(route.ID, &route)

	if err := g.routerManager.AddRoute(&route); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	chain, err := g.buildMiddlewareChain(&route)
	if err != nil {
		g.logger.Error("failed to build middleware chain", zap.Error(err))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	g.mu.Lock()
	g.middlewareChains[route.ID] = chain
	g.mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(route)
}

func (g *Gateway) handleRouteByID(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Path[len("/api/v1/routes/"):]
	if id == "" {
		http.Error(w, "route id is required", http.StatusBadRequest)
		return
	}

	switch r.Method {
	case http.MethodGet:
		g.getRoute(w, r, id)
	case http.MethodPut:
		g.updateRoute(w, r, id)
	case http.MethodDelete:
		g.deleteRoute(w, r, id)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (g *Gateway) getRoute(w http.ResponseWriter, r *http.Request, id string) {
	route, exists := g.configStore.GetRoute(id)
	if !exists {
		http.Error(w, "route not found", http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(route)
}

func (g *Gateway) updateRoute(w http.ResponseWriter, r *http.Request, id string) {
	var route models.Route
	if err := json.NewDecoder(r.Body).Decode(&route); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	route.ID = id
	route.UpdatedAt = time.Now()

	existing, exists := g.configStore.GetRoute(id)
	if exists {
		route.CreatedAt = existing.CreatedAt
	} else {
		route.CreatedAt = time.Now()
	}

	g.configStore.SetRoute(id, &route)

	if err := g.routerManager.UpdateRoute(&route); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	chain, err := g.buildMiddlewareChain(&route)
	if err != nil {
		g.logger.Error("failed to rebuild middleware chain", zap.Error(err))
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	g.mu.Lock()
	g.middlewareChains[id] = chain
	g.mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(route)
}

func (g *Gateway) deleteRoute(w http.ResponseWriter, r *http.Request, id string) {
	if err := g.routerManager.RemoveRoute(id); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	g.configStore.DeleteRoute(id)

	g.mu.Lock()
	delete(g.middlewareChains, id)
	g.mu.Unlock()

	w.WriteHeader(http.StatusNoContent)
}

func (g *Gateway) handleConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	cfg := g.configStore.ToGatewayConfig()

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(cfg)
}

func (g *Gateway) handleConfigReload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if err := g.loadConfig(); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	if err := g.buildAllMiddlewareChains(); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "success", "message": "config reloaded"})
}

func (g *Gateway) Logger() *zap.Logger {
	return g.logger
}

func (g *Gateway) handleDiagnosticsTraffic(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	filter, err := g.parseDiagnosticFilter(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	summary, err := g.diagnosticManager.Query(filter)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(summary)
}

func (g *Gateway) handleDiagnosticsStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	status := map[string]interface{}{
		"buffer_count":  g.diagnosticManager.GetBufferCount(),
		"bucket_count":  g.diagnosticManager.GetBucketCount(),
		"bucket_size":   10,
		"retention":     3600,
		"timestamp":     time.Now().UTC(),
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(status)
}

func (g *Gateway) parseDiagnosticFilter(r *http.Request) (*diagnostics.DiagnosticFilter, error) {
	filter := &diagnostics.DiagnosticFilter{}

	if r.Method == http.MethodPost {
		if err := json.NewDecoder(r.Body).Decode(filter); err != nil {
			return nil, fmt.Errorf("invalid filter: %w", err)
		}
		return filter, nil
	}

	query := r.URL.Query()

	if startTimeStr := query.Get("start_time"); startTimeStr != "" {
		if t, err := time.Parse(time.RFC3339, startTimeStr); err == nil {
			filter.StartTime = t
		}
	}

	if endTimeStr := query.Get("end_time"); endTimeStr != "" {
		if t, err := time.Parse(time.RFC3339, endTimeStr); err == nil {
			filter.EndTime = t
		}
	}

	filter.Path = query.Get("path")
	filter.RouteID = query.Get("route_id")
	filter.Method = query.Get("method")
	filter.ErrorType = query.Get("error_type")
	filter.StatusClass = query.Get("status_class")

	if stepStr := query.Get("step"); stepStr != "" {
		var step int
		if _, err := fmt.Sscanf(stepStr, "%d", &step); err == nil {
			filter.Step = step
		}
	}

	return filter, nil
}

func GetOutboundIP() (string, error) {
	conn, err := net.Dial("udp", "8.8.8.8.8:80")
	if err != nil {
		return "", err
	}
	defer conn.Close()

	localAddr := conn.LocalAddr().(*net.UDPAddr)
	return localAddr.IP.String(), nil
}
