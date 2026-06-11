package middleware

import (
	"fmt"
	"net/http"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"DF1-56/internal/auth"
	"DF1-56/internal/circuitbreaker"
	"DF1-56/internal/config"
	"DF1-56/internal/mirror"
	"DF1-56/internal/models"
	"DF1-56/internal/ratelimit"
	"DF1-56/internal/router"
	"DF1-56/internal/telemetry"
)

type statusRecorder struct {
	http.ResponseWriter
	statusCode int
}

func newStatusRecorder(w http.ResponseWriter) *statusRecorder {
	return &statusRecorder{
		ResponseWriter: w,
		statusCode:     http.StatusOK,
	}
}

func (r *statusRecorder) WriteHeader(code int) {
	r.statusCode = code
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) StatusCode() int {
	return r.statusCode
}

const (
	MiddlewareTracing      = "tracing"
	MiddlewareRateLimit    = "ratelimit"
	MiddlewareAuth         = "auth"
	MiddlewareGray         = "gray"
	MiddlewareCircuitBreaker = "circuitbreaker"
	MiddlewareForward      = "forward"
	MiddlewareMirror       = "mirror"
)

type ChainBuilder struct {
	configStore      *config.ConfigStore
	rateLimitManager *ratelimit.Manager
	grayManager      *mirror.GrayManager
	mirrorManager    *mirror.MirrorManager
	routerManager    *router.RouterManager
	forwarder        *router.Forwarder
	loadBalancer     *router.LoadBalancer
	circuitBreakers  map[string]*circuitbreaker.CircuitBreaker
	healthCheckers   map[string]*circuitbreaker.HealthChecker
	telemetry        *telemetry.Telemetry
	logger           *zap.Logger
	validAPIKeys     map[string]string
	enabledMiddlewares map[string]bool
}

type ChainBuilderConfig struct {
	ConfigStore       *config.ConfigStore
	RateLimitManager  *ratelimit.Manager
	GrayManager       *mirror.GrayManager
	MirrorManager     *mirror.MirrorManager
	RouterManager     *router.RouterManager
	Forwarder         *router.Forwarder
	LoadBalancer      *router.LoadBalancer
	Telemetry         *telemetry.Telemetry
	Logger            *zap.Logger
	ValidAPIKeys      map[string]string
	EnabledMiddlewares []string
}

func NewChainBuilder(cfg ChainBuilderConfig) (*ChainBuilder, error) {
	if cfg.ConfigStore == nil {
		return nil, fmt.Errorf("config store cannot be nil")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger cannot be nil")
	}

	enabled := make(map[string]bool)
	if len(cfg.EnabledMiddlewares) == 0 {
		enabled[MiddlewareTracing] = true
		enabled[MiddlewareRateLimit] = true
		enabled[MiddlewareAuth] = true
		enabled[MiddlewareGray] = true
		enabled[MiddlewareCircuitBreaker] = true
		enabled[MiddlewareForward] = true
		enabled[MiddlewareMirror] = true
	} else {
		for _, m := range cfg.EnabledMiddlewares {
			enabled[m] = true
		}
	}

	return &ChainBuilder{
		configStore:        cfg.ConfigStore,
		rateLimitManager:   cfg.RateLimitManager,
		grayManager:        cfg.GrayManager,
		mirrorManager:      cfg.MirrorManager,
		routerManager:      cfg.RouterManager,
		forwarder:          cfg.Forwarder,
		loadBalancer:       cfg.LoadBalancer,
		circuitBreakers:    make(map[string]*circuitbreaker.CircuitBreaker),
		healthCheckers:     make(map[string]*circuitbreaker.HealthChecker),
		telemetry:          cfg.Telemetry,
		logger:             cfg.Logger,
		validAPIKeys:       cfg.ValidAPIKeys,
		enabledMiddlewares: enabled,
	}, nil
}

func (cb *ChainBuilder) Build(route *models.Route) (*models.MiddlewareChain, error) {
	if route == nil {
		return nil, fmt.Errorf("route cannot be nil")
	}

	chain := models.NewMiddlewareChain()

	if cb.enabledMiddlewares[MiddlewareTracing] {
		chain.Use(cb.newTracingMiddleware())
	}

	if cb.enabledMiddlewares[MiddlewareRateLimit] && route.RateLimitPolicy != "" {
		chain.Use(cb.newRateLimitMiddleware(route.RateLimitPolicy))
	}

	if cb.enabledMiddlewares[MiddlewareAuth] && route.AuthPolicy != "" {
		authMiddleware, err := cb.newAuthMiddleware(route.AuthPolicy)
		if err != nil {
			return nil, fmt.Errorf("failed to build auth middleware: %w", err)
		}
		chain.Use(authMiddleware)
	}

	if cb.enabledMiddlewares[MiddlewareGray] && route.GrayPolicy != "" {
		chain.Use(cb.newGrayMiddleware(route.GrayPolicy))
	}

	if cb.enabledMiddlewares[MiddlewareCircuitBreaker] && route.CircuitBreaker != "" {
		cb.getOrCreateCircuitBreaker(route.CircuitBreaker)
		chain.Use(cb.newCircuitBreakerMiddleware(route.CircuitBreaker))
	}

	if cb.enabledMiddlewares[MiddlewareForward] {
		chain.Use(cb.newForwardMiddleware())
	}

	if cb.enabledMiddlewares[MiddlewareMirror] && route.MirrorPolicy != "" {
		chain.Use(cb.newMirrorMiddleware(route.MirrorPolicy))
	}

	return chain, nil
}

func (cb *ChainBuilder) getOrCreateCircuitBreaker(policyID string) *circuitbreaker.CircuitBreaker {
	if cb, ok := cb.circuitBreakers[policyID]; ok {
		return cb
	}

	policy, exists := cb.configStore.GetCircuitBreaker(policyID)
	if !exists {
		policy = nil
	}

	breaker := circuitbreaker.NewCircuitBreaker(policy)
	cb.circuitBreakers[policyID] = breaker
	return breaker
}

func (cb *ChainBuilder) getOrCreateHealthChecker(clusterID string) *circuitbreaker.HealthChecker {
	if hc, ok := cb.healthCheckers[clusterID]; ok {
		return hc
	}

	cluster, exists := cb.configStore.GetUpstream(clusterID)
	if !exists {
		return nil
	}

	hc := circuitbreaker.NewHealthChecker(cluster)
	if hc != nil {
		hc.Start()
		cb.healthCheckers[clusterID] = hc
	}
	return hc
}

func (cb *ChainBuilder) newTracingMiddleware() models.Middleware {
	return &tracingMiddleware{
		telemetry: cb.telemetry,
		logger:    cb.logger,
	}
}

func (cb *ChainBuilder) newRateLimitMiddleware(policyID string) models.Middleware {
	return &rateLimitMiddleware{
		policyID:       policyID,
		rateLimitManager: cb.rateLimitManager,
		configStore:    cb.configStore,
		telemetry:      cb.telemetry,
		logger:         cb.logger,
	}
}

func (cb *ChainBuilder) newAuthMiddleware(policyID string) (models.Middleware, error) {
	policy, exists := cb.configStore.GetAuth(policyID)
	if !exists {
		return nil, fmt.Errorf("auth policy %s not found", policyID)
	}

	authChain, err := auth.BuildMiddlewareChain(policy, cb.validAPIKeys)
	if err != nil {
		return nil, fmt.Errorf("failed to build auth chain: %w", err)
	}

	return &authMiddleware{
		policyID:  policyID,
		authChain: authChain,
		telemetry: cb.telemetry,
		logger:    cb.logger,
	}, nil
}

func (cb *ChainBuilder) newGrayMiddleware(policyID string) models.Middleware {
	return &grayMiddleware{
		policyID:    policyID,
		grayManager: cb.grayManager,
		logger:      cb.logger,
	}
}

func (cb *ChainBuilder) newCircuitBreakerMiddleware(policyID string) models.Middleware {
	return &circuitBreakerMiddleware{
		policyID:       policyID,
		chainBuilder:   cb,
		telemetry:      cb.telemetry,
		logger:         cb.logger,
	}
}

func (cb *ChainBuilder) newForwardMiddleware() models.Middleware {
	return &forwardMiddleware{
		forwarder:    cb.forwarder,
		loadBalancer: cb.loadBalancer,
		chainBuilder: cb,
		telemetry:    cb.telemetry,
		logger:       cb.logger,
	}
}

func (cb *ChainBuilder) newMirrorMiddleware(policyID string) models.Middleware {
	return &mirrorMiddleware{
		policyID:      policyID,
		mirrorManager: cb.mirrorManager,
		configStore:   cb.configStore,
		logger:        cb.logger,
	}
}

func (cb *ChainBuilder) Stop() {
	for _, hc := range cb.healthCheckers {
		hc.Stop()
	}
}

type tracingMiddleware struct {
	telemetry *telemetry.Telemetry
	logger    *zap.Logger
}

func (m *tracingMiddleware) Name() string {
	return MiddlewareTracing
}

func (m *tracingMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	if ctx.RequestID == "" {
		ctx.RequestID = uuid.New().String()
	}

	ctx.TraceID = ctx.Request.Header.Get("X-Trace-ID")
	if ctx.TraceID == "" {
		ctx.TraceID = ctx.RequestID
	}

	ctx.ClientIP = getClientIP(ctx.Request)

	ctx.Response.Header().Set("X-Request-ID", ctx.RequestID)
	ctx.Response.Header().Set("X-Trace-ID", ctx.TraceID)

	if m.telemetry != nil {
		span, cleanup := telemetry.CreateRootSpan(ctx)
		defer cleanup()
		defer telemetry.EndSpan(span, nil)

		routeID := ""
		if ctx.Route != nil {
			routeID = ctx.Route.ID
		}
		m.telemetry.IncrementActiveRequests(ctx.Request.Method, routeID)
		defer m.telemetry.DecrementActiveRequests(ctx.Request.Method, routeID)
	}

	m.logger.Debug("request started",
		zap.String("request_id", ctx.RequestID),
		zap.String("trace_id", ctx.TraceID),
		zap.String("method", ctx.Request.Method),
		zap.String("path", ctx.Request.URL.Path),
		zap.String("client_ip", ctx.ClientIP),
	)

	startTime := time.Now()
	err := next(ctx)
	duration := time.Since(startTime)

	m.logger.Debug("request completed",
		zap.String("request_id", ctx.RequestID),
		zap.String("trace_id", ctx.TraceID),
		zap.Duration("duration", duration),
		zap.Error(err),
	)

	return err
}

type rateLimitMiddleware struct {
	policyID        string
	rateLimitManager *ratelimit.Manager
	configStore     *config.ConfigStore
	telemetry       *telemetry.Telemetry
	logger          *zap.Logger
}

func (m *rateLimitMiddleware) Name() string {
	return MiddlewareRateLimit
}

func (m *rateLimitMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	if m.rateLimitManager == nil {
		return next(ctx)
	}

	policy, exists := m.configStore.GetRateLimit(m.policyID)
	if !exists {
		return next(ctx)
	}

	m.rateLimitManager.RegisterPolicy(policy)

	result, err := m.rateLimitManager.Allow(ctx, policy)
	if err != nil {
		m.logger.Error("rate limit check failed",
			zap.String("request_id", ctx.RequestID),
			zap.String("policy_id", m.policyID),
			zap.Error(err),
		)
		return next(ctx)
	}

	ctx.Set(string(models.ContextKeyRateLimit), result)

	ctx.Response.Header().Set("X-RateLimit-Limit", fmt.Sprintf("%d", result.Limit))
	ctx.Response.Header().Set("X-RateLimit-Remaining", fmt.Sprintf("%d", result.Remaining))
	if result.ResetAfter > 0 {
		ctx.Response.Header().Set("X-RateLimit-Reset", fmt.Sprintf("%d", int(result.ResetAfter.Seconds())))
	}

	if !result.Allowed {
		if m.telemetry != nil {
			m.telemetry.RecordRateLimitRejected(result.Reason, m.policyID)
		}

		m.logger.Warn("rate limit exceeded",
			zap.String("request_id", ctx.RequestID),
			zap.String("policy_id", m.policyID),
			zap.String("reason", result.Reason),
		)

		ctx.Response.Header().Set("Content-Type", "application/json")
		ctx.Response.WriteHeader(http.StatusTooManyRequests)
		_, _ = ctx.Response.Write([]byte(`{"error":"rate limit exceeded","code":429,"reason":"` + result.Reason + `"}`))
		return nil
	}

	defer m.rateLimitManager.Release(ctx.RequestID)

	return next(ctx)
}

type authMiddleware struct {
	policyID  string
	authChain *auth.MiddlewareChain
	telemetry *telemetry.Telemetry
	logger    *zap.Logger
}

func (m *authMiddleware) Name() string {
	return MiddlewareAuth
}

func (m *authMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	if m.authChain == nil {
		return next(ctx)
	}

	err := m.authChain.Handle(ctx)
	if err != nil {
		if m.telemetry != nil {
			m.telemetry.RecordAuthFailure("auth_failed", err.Error())
		}
		m.logger.Warn("auth failed",
			zap.String("request_id", ctx.RequestID),
			zap.String("policy_id", m.policyID),
			zap.Error(err),
		)
		return err
	}

	return next(ctx)
}

type grayMiddleware struct {
	policyID    string
	grayManager *mirror.GrayManager
	logger      *zap.Logger
}

func (m *grayMiddleware) Name() string {
	return MiddlewareGray
}

func (m *grayMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	if m.grayManager == nil {
		return next(ctx)
	}

	clusterID, err := m.grayManager.SelectCluster(ctx, m.policyID)
	if err != nil {
		m.logger.Warn("gray routing failed",
			zap.String("request_id", ctx.RequestID),
			zap.String("policy_id", m.policyID),
			zap.Error(err),
		)
		return next(ctx)
	}

	if clusterID != "" {
		ctx.Set("target_cluster", clusterID)
		m.logger.Debug("gray routing selected cluster",
			zap.String("request_id", ctx.RequestID),
			zap.String("policy_id", m.policyID),
			zap.String("cluster_id", clusterID),
		)
	}

	return next(ctx)
}

type circuitBreakerMiddleware struct {
	policyID     string
	chainBuilder *ChainBuilder
	telemetry    *telemetry.Telemetry
	logger       *zap.Logger
}

func (m *circuitBreakerMiddleware) Name() string {
	return MiddlewareCircuitBreaker
}

func (m *circuitBreakerMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	breaker := m.chainBuilder.getOrCreateCircuitBreaker(m.policyID)
	if breaker == nil {
		return next(ctx)
	}

	if !breaker.Allow() {
		if m.telemetry != nil {
			m.telemetry.RecordCircuitBreakerOpen(m.policyID)
		}

		m.logger.Warn("circuit breaker open",
			zap.String("request_id", ctx.RequestID),
			zap.String("policy_id", m.policyID),
			zap.String("state", string(breaker.State())),
		)

		return breaker.HandleFallback(ctx)
	}

	err := next(ctx)

	if err != nil {
		breaker.OnFailure(err)
	} else {
		breaker.OnSuccess()
	}

	return err
}

type forwardMiddleware struct {
	forwarder    *router.Forwarder
	loadBalancer *router.LoadBalancer
	chainBuilder *ChainBuilder
	telemetry    *telemetry.Telemetry
	logger       *zap.Logger
}

func (m *forwardMiddleware) Name() string {
	return MiddlewareForward
}

func (m *forwardMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	if m.forwarder == nil {
		return fmt.Errorf("forwarder is not initialized")
	}

	recorder := newStatusRecorder(ctx.Response)
	ctx.Response = recorder

	startTime := time.Now()
	isError := false
	clusterID := ""
	upstreamIdentifier := ""

	if v, ok := ctx.Get("target_cluster"); ok {
		if cid, ok := v.(string); ok {
			clusterID = cid
		}
	}

	if clusterID == "" && ctx.Route != nil {
		if ctx.Route.UpstreamCluster != "" {
			upstreamIdentifier = ctx.Route.UpstreamCluster
			cluster, exists := m.chainBuilder.configStore.GetUpstream(ctx.Route.UpstreamCluster)
			if exists {
				m.chainBuilder.getOrCreateHealthChecker(ctx.Route.UpstreamCluster)
				if m.loadBalancer != nil {
					err := m.forwarder.ForwardWithLoadBalancer(ctx, cluster, m.loadBalancer)
					duration := time.Since(startTime)
					isError = err != nil || recorder.StatusCode() >= 500

					if m.telemetry != nil {
						statusCode := 200
						if recorder.StatusCode() > 0 {
							statusCode = recorder.StatusCode()
						}
						m.telemetry.RecordUpstreamDuration(ctx.Route.UpstreamCluster, duration, statusCode, !isError)
					}

					if err != nil {
						return fmt.Errorf("forward with load balancer failed: %w", err)
					}

					m.recordUpstreamMetrics(ctx, duration, isError, upstreamIdentifier)
					return next(ctx)
				}
			}
		}
	}

	if ctx.Route != nil && ctx.Route.UpstreamURL != "" {
		upstreamIdentifier = ctx.Route.UpstreamURL
		err := m.forwarder.ForwardWithRetry(ctx, ctx.Route.UpstreamURL)
		duration := time.Since(startTime)
		isError = err != nil || recorder.StatusCode() >= 500

		if m.telemetry != nil {
			statusCode := 200
			if recorder.StatusCode() > 0 {
				statusCode = recorder.StatusCode()
			}
			m.telemetry.RecordUpstreamDuration(ctx.Route.UpstreamURL, duration, statusCode, !isError)
		}

		if err != nil {
			return fmt.Errorf("forward failed: %w", err)
		}

		m.recordUpstreamMetrics(ctx, duration, isError, upstreamIdentifier)
	}

	return next(ctx)
}

func (m *forwardMiddleware) recordUpstreamMetrics(ctx *models.GatewayContext, duration time.Duration, isError bool, upstreamIdentifier string) {
	if ctx.Route == nil || ctx.Route.RateLimitPolicy == "" {
		return
	}

	if m.chainBuilder != nil && m.chainBuilder.rateLimitManager != nil {
		m.chainBuilder.rateLimitManager.RecordUpstreamLatency(ctx, ctx.Route.RateLimitPolicy, duration, isError)
	}
}

type mirrorMiddleware struct {
	policyID      string
	mirrorManager *mirror.MirrorManager
	configStore   *config.ConfigStore
	logger        *zap.Logger
}

func (m *mirrorMiddleware) Name() string {
	return MiddlewareMirror
}

func (m *mirrorMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	if m.mirrorManager == nil {
		return next(ctx)
	}

	cluster, exists := m.configStore.GetUpstream(m.policyID)
	if !exists {
		cluster = nil
	}

	if cluster != nil {
		if err := m.mirrorManager.MirrorRequest(ctx, m.policyID, cluster); err != nil {
			m.logger.Warn("mirror request failed",
				zap.String("request_id", ctx.RequestID),
				zap.String("policy_id", m.policyID),
				zap.Error(err),
			)
		}
	}

	return next(ctx)
}

func getClientIP(r *http.Request) string {
	ip := r.Header.Get("X-Forwarded-For")
	if ip != "" {
		ips := splitAndTrim(ip)
		if len(ips) > 0 {
			return ips[0]
		}
	}

	ip = r.Header.Get("X-Real-IP")
	if ip != "" {
		return ip
	}

	return r.RemoteAddr
}

func splitAndTrim(s string) []string {
	parts := make([]string, 0)
	for _, p := range []string{s} {
		for _, part := range splitByComma(p) {
			parts = append(parts, part)
		}
	}
	return parts
}

func splitByComma(s string) []string {
	var result []string
	var current []rune
	for _, r := range s {
		if r == ',' {
			if len(current) > 0 {
				result = append(result, trimSpace(string(current)))
				current = nil
			}
		} else {
			current = append(current, r)
		}
	}
	if len(current) > 0 {
		result = append(result, trimSpace(string(current)))
	}
	return result
}

func trimSpace(s string) string {
	start := 0
	end := len(s)
	for start < end && s[start] == ' ' {
		start++
	}
	for end > start && s[end-1] == ' ' {
		end--
	}
	return s[start:end]
}
