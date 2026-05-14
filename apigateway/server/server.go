package server

import (
	"apigateway/api"
	"apigateway/auth"
	"apigateway/circuitbreaker"
	"apigateway/forward"
	"apigateway/loadbalancer"
	"apigateway/logger"
	"apigateway/metrics"
	"apigateway/models"
	"apigateway/ratelimit"
	"apigateway/router"
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

type GatewayServer struct {
	routerManager             *router.RouterManager
	rateLimiter               *ratelimit.RateLimiter
	rateLimitStore            ratelimit.RateLimitStore
	loadBalancer              *loadbalancer.LoadBalancer
	circuitBreaker            *circuitbreaker.CircuitBreaker
	metricsCollector          *metrics.MetricsCollector
	logger                    *logger.RequestLogger
	authManager               *auth.AuthManager
	forwarder                 *forward.Forwarder
	asyncForwarder            *forward.AsyncForwarder
	persistedForwarderPool    *forward.PersistedAsyncForwarderPool
	apiServer                 *api.APIServer
	gatewayServer             *http.Server
	adminServer               *http.Server
	gatewayPort               int
	adminPort                 int
	gatewayConfig             *models.GatewayConfig
}

func NewGatewayServer(gatewayPort, adminPort int) *GatewayServer {
	return NewGatewayServerWithConfig(gatewayPort, adminPort, nil)
}

func NewGatewayServerWithConfig(gatewayPort, adminPort int, config *models.GatewayConfig) *GatewayServer {
	if config == nil {
		config = &models.GatewayConfig{}
	}

	routerManager := router.NewRouterManager()

	rateLimitStore := ratelimit.NewRateLimitStore(config.Redis)
	rateLimiter := ratelimit.NewRateLimiterWithStore(rateLimitStore)
	if config.DefaultRateLimit.Algorithm != "" {
		rateLimiter.SetDefaultAlgorithm(config.DefaultRateLimit.Algorithm)
	}

	loadBalancer := loadbalancer.NewLoadBalancer()
	circuitBreaker := circuitbreaker.NewCircuitBreaker()
	metricsCollector := metrics.NewMetricsCollector()
	requestLogger := logger.NewRequestLogger()
	authManager := auth.NewAuthManager()

	forwarder := forward.NewForwarder(
		routerManager,
		rateLimiter,
		loadBalancer,
		circuitBreaker,
		metricsCollector,
		requestLogger,
		authManager,
	)

	workerCount := config.AsyncForward.WorkerCount
	if workerCount <= 0 {
		workerCount = 4
	}
	queueSize := config.AsyncForward.QueueSize
	if queueSize <= 0 {
		queueSize = 1000
	}

	asyncForwarder := forward.NewAsyncForwarder(workerCount, queueSize)
	asyncForwarder.Start()

	persistedForwarderPool := forward.NewPersistedAsyncForwarderPool(config.Redis)

	apiServer := api.NewAPIServer(
		routerManager,
		rateLimiter,
		loadBalancer,
		circuitBreaker,
		metricsCollector,
		requestLogger,
		authManager,
	)

	return &GatewayServer{
		routerManager:          routerManager,
		rateLimiter:            rateLimiter,
		rateLimitStore:         rateLimitStore,
		loadBalancer:           loadBalancer,
		circuitBreaker:         circuitBreaker,
		metricsCollector:       metricsCollector,
		logger:                 requestLogger,
		authManager:            authManager,
		forwarder:              forwarder,
		asyncForwarder:         asyncForwarder,
		persistedForwarderPool: persistedForwarderPool,
		apiServer:              apiServer,
		gatewayPort:            gatewayPort,
		adminPort:              adminPort,
		gatewayConfig:          config,
	}
}

func (s *GatewayServer) InitDemoData() {
	_ = s.loadBalancer.RegisterService(&models.LoadBalancerConfig{
		BalanceID:       "balance_user_service",
		ServiceName:     "user-service",
		BalanceAlgorithm: "round_robin",
		Instances: []models.ServiceInstance{
			{InstanceID: "user-service-01", Address: "localhost:8081", Weight: 1, Healthy: true},
			{InstanceID: "user-service-02", Address: "localhost:8082", Weight: 2, Healthy: true},
		},
	})

	_ = s.loadBalancer.RegisterService(&models.LoadBalancerConfig{
		BalanceID:       "balance_order_service",
		ServiceName:     "order-service",
		BalanceAlgorithm: "weighted_round_robin",
		Instances: []models.ServiceInstance{
			{InstanceID: "order-service-01", Address: "localhost:8083", Weight: 3, Healthy: true},
			{InstanceID: "order-service-02", Address: "localhost:8084", Weight: 1, Healthy: true},
		},
	})

	_ = s.circuitBreaker.Register(&models.CircuitBreakerConfig{
		CircuitID:              "circuit_user_service",
		ServiceName:            "user-service",
		ServiceImportance:      models.ServiceImportanceCritical,
		FailureThreshold:       50,
		FailureRateThreshold:   0.5,
		OpenTimeout:            30,
		HalfOpenRequests:       3,
		CriticalHalfOpenRequests: 10,
		HighHalfOpenRequests:     5,
		MediumHalfOpenRequests:   3,
		LowHalfOpenRequests:      1,
	})

	_ = s.circuitBreaker.Register(&models.CircuitBreakerConfig{
		CircuitID:              "circuit_order_service",
		ServiceName:            "order-service",
		ServiceImportance:      models.ServiceImportanceHigh,
		FailureThreshold:       30,
		FailureRateThreshold:   0.6,
		OpenTimeout:            60,
		HalfOpenRequests:       5,
		CriticalHalfOpenRequests: 10,
		HighHalfOpenRequests:     5,
		MediumHalfOpenRequests:   3,
		LowHalfOpenRequests:      1,
	})

	authRequired := true
	_, _ = s.routerManager.CreateRoute(&models.CreateRouteRequest{
		RoutePattern:    "/api/users/*",
		TargetService:   "user-service",
		TargetInstances: []string{"user-service-01", "user-service-02"},
		ForwardConfig: &models.ForwardConfig{
			Timeout:      5000,
			RetryCount:   2,
			AsyncEnabled: true,
			PersistTasks: true,
		},
		AuthRequired: &authRequired,
		RateLimit: &models.RateLimitConfig{
			QPS:         100,
			Burst:       20,
			Algorithm:   models.AlgorithmTokenBucket,
			WindowSize:  1,
			Distributed: false,
		},
		Group: "user",
	})

	authNotRequired := false
	_, _ = s.routerManager.CreateRoute(&models.CreateRouteRequest{
		RoutePattern:    "/api/orders/*",
		TargetService:   "order-service",
		TargetInstances: []string{"order-service-01", "order-service-02"},
		ForwardConfig: &models.ForwardConfig{
			Timeout:      3000,
			RetryCount:   3,
			AsyncEnabled: true,
			PersistTasks: false,
		},
		AuthRequired: &authNotRequired,
		RateLimit: &models.RateLimitConfig{
			QPS:         50,
			Burst:       10,
			Algorithm:   models.AlgorithmLeakyBucket,
			WindowSize:  1,
			Distributed: false,
		},
		Group: "order",
	})

	_ = s.authManager.AddAPIKey(&auth.APIKey{
		Key:     "ak_demo_key_001",
		Secret:  "sk_demo_secret_001",
		UserID:  "user_001",
		Roles:   []string{"admin", "user"},
		Enabled: true,
	})

	s.authManager.AddBearerToken("demo_token_001", auth.JWTClaims{
		UserID:   "user_002",
		Roles:    []string{"user"},
		ExpiresAt: time.Now().Add(24 * time.Hour).Unix(),
		IssuedAt: time.Now().Unix(),
	})
}

func (s *GatewayServer) Start() error {
	gatewayMux := http.NewServeMux()
	gatewayMux.Handle("/", s.forwarder)

	adminMux := http.NewServeMux()
	adminMux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ok","service":"apigateway"}`))
	})
	s.apiServer.RegisterRoutes(adminMux)

	s.gatewayServer = &http.Server{
		Addr:         fmt.Sprintf(":%d", s.gatewayPort),
		Handler:      gatewayMux,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	s.adminServer = &http.Server{
		Addr:         fmt.Sprintf(":%d", s.adminPort),
		Handler:      adminMux,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	s.logger.Info("Starting API Gateway server...")
	s.logger.Info(fmt.Sprintf("Gateway server listening on port %d", s.gatewayPort))
	s.logger.Info(fmt.Sprintf("Admin API server listening on port %d", s.adminPort))

	go func() {
		if err := s.gatewayServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			s.logger.Error(fmt.Sprintf("Gateway server error: %v", err))
		}
	}()

	go func() {
		if err := s.adminServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			s.logger.Error(fmt.Sprintf("Admin server error: %v", err))
		}
	}()

	return nil
}

func (s *GatewayServer) Shutdown() error {
	s.logger.Info("Shutting down API Gateway server...")

	if s.asyncForwarder != nil {
		s.logger.Info("Stopping async forwarder...")
		s.asyncForwarder.Stop()
	}

	if s.persistedForwarderPool != nil {
		s.logger.Info("Stopping persisted forwarder pool...")
		s.persistedForwarderPool.StopAll()
	}

	if s.rateLimitStore != nil {
		s.logger.Info("Closing rate limit store...")
		s.rateLimitStore.Close()
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if s.gatewayServer != nil {
		if err := s.gatewayServer.Shutdown(ctx); err != nil {
			s.logger.Error(fmt.Sprintf("Gateway server shutdown error: %v", err))
		}
	}

	if s.adminServer != nil {
		if err := s.adminServer.Shutdown(ctx); err != nil {
			s.logger.Error(fmt.Sprintf("Admin server shutdown error: %v", err))
		}
	}

	s.logger.Info("API Gateway server shutdown complete")
	return nil
}

func (s *GatewayServer) WaitForShutdown() {
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)

	sig := <-stop
	s.logger.Info(fmt.Sprintf("Received signal: %s", sig))

	s.Shutdown()
}

func (s *GatewayServer) GetRouterManager() *router.RouterManager {
	return s.routerManager
}

func (s *GatewayServer) GetRateLimiter() *ratelimit.RateLimiter {
	return s.rateLimiter
}

func (s *GatewayServer) GetLoadBalancer() *loadbalancer.LoadBalancer {
	return s.loadBalancer
}

func (s *GatewayServer) GetCircuitBreaker() *circuitbreaker.CircuitBreaker {
	return s.circuitBreaker
}

func (s *GatewayServer) GetMetricsCollector() *metrics.MetricsCollector {
	return s.metricsCollector
}

func (s *GatewayServer) GetLogger() *logger.RequestLogger {
	return s.logger
}

func (s *GatewayServer) GetAuthManager() *auth.AuthManager {
	return s.authManager
}

func (s *GatewayServer) GetRateLimitStore() ratelimit.RateLimitStore {
	return s.rateLimitStore
}

func (s *GatewayServer) GetAsyncForwarder() *forward.AsyncForwarder {
	return s.asyncForwarder
}

func (s *GatewayServer) GetPersistedForwarderPool() *forward.PersistedAsyncForwarderPool {
	return s.persistedForwarderPool
}

func (s *GatewayServer) GetGatewayConfig() *models.GatewayConfig {
	return s.gatewayConfig
}
