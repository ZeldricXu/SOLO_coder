package testdata

import (
	"apigateway/models"
	"time"
)

type RouteConfigBuilder struct {
	route *models.Route
}

func NewRouteConfigBuilder() *RouteConfigBuilder {
	return &RouteConfigBuilder{
		route: &models.Route{
			RouteID:         generateRouteID("/api/test"),
			RoutePattern:    "/api/test/*",
			TargetService:   "test-service",
			TargetInstances: []string{"test-01", "test-02"},
			ForwardConfig: models.ForwardConfig{
				Timeout:    3000,
				RetryCount: 2,
			},
			AuthRequired: false,
			RateLimit: models.RateLimitConfig{
				QPS:   100,
				Burst: 20,
			},
			Enabled:   true,
			Group:     "test",
			CreatedAt: time.Now(),
			UpdatedAt: time.Now(),
		},
	}
}

func (b *RouteConfigBuilder) WithRouteID(id string) *RouteConfigBuilder {
	b.route.RouteID = id
	return b
}

func (b *RouteConfigBuilder) WithPattern(pattern string) *RouteConfigBuilder {
	b.route.RoutePattern = pattern
	b.route.RouteID = generateRouteID(pattern)
	return b
}

func (b *RouteConfigBuilder) WithTargetService(service string) *RouteConfigBuilder {
	b.route.TargetService = service
	return b
}

func (b *RouteConfigBuilder) WithInstances(instances []string) *RouteConfigBuilder {
	b.route.TargetInstances = instances
	return b
}

func (b *RouteConfigBuilder) WithTimeout(timeout int) *RouteConfigBuilder {
	b.route.ForwardConfig.Timeout = timeout
	return b
}

func (b *RouteConfigBuilder) WithRetryCount(count int) *RouteConfigBuilder {
	b.route.ForwardConfig.RetryCount = count
	return b
}

func (b *RouteConfigBuilder) WithAuthRequired(required bool) *RouteConfigBuilder {
	b.route.AuthRequired = required
	return b
}

func (b *RouteConfigBuilder) WithRateLimit(qps, burst int) *RouteConfigBuilder {
	b.route.RateLimit = models.RateLimitConfig{
		QPS:   qps,
		Burst: burst,
	}
	return b
}

func (b *RouteConfigBuilder) WithGroup(group string) *RouteConfigBuilder {
	b.route.Group = group
	return b
}

func (b *RouteConfigBuilder) WithEnabled(enabled bool) *RouteConfigBuilder {
	b.route.Enabled = enabled
	return b
}

func (b *RouteConfigBuilder) Build() *models.Route {
	clone := *b.route
	return &clone
}

func generateRouteID(pattern string) string {
	return "route_" + pattern
}

type RateLimitConfigBuilder struct {
	config *models.LimitConfig
}

func NewRateLimitConfigBuilder() *RateLimitConfigBuilder {
	return &RateLimitConfigBuilder{
		config: &models.LimitConfig{
			LimitID:        "limit_test",
			RouteID:        "route_test",
			LimitType:      "qps",
			LimitValue:     100,
			BurstSize:      20,
			LimitAlgorithm: "token_bucket",
			Enabled:        true,
		},
	}
}

func (b *RateLimitConfigBuilder) WithLimitID(id string) *RateLimitConfigBuilder {
	b.config.LimitID = id
	return b
}

func (b *RateLimitConfigBuilder) WithRouteID(routeID string) *RateLimitConfigBuilder {
	b.config.RouteID = routeID
	return b
}

func (b *RateLimitConfigBuilder) WithLimitType(limitType string) *RateLimitConfigBuilder {
	b.config.LimitType = limitType
	return b
}

func (b *RateLimitConfigBuilder) WithLimitValue(value int) *RateLimitConfigBuilder {
	b.config.LimitValue = value
	return b
}

func (b *RateLimitConfigBuilder) WithBurstSize(size int) *RateLimitConfigBuilder {
	b.config.BurstSize = size
	return b
}

func (b *RateLimitConfigBuilder) WithAlgorithm(algo string) *RateLimitConfigBuilder {
	b.config.LimitAlgorithm = algo
	return b
}

func (b *RateLimitConfigBuilder) WithEnabled(enabled bool) *RateLimitConfigBuilder {
	b.config.Enabled = enabled
	return b
}

func (b *RateLimitConfigBuilder) Build() *models.LimitConfig {
	clone := *b.config
	return &clone
}

type CircuitBreakerConfigBuilder struct {
	config *models.CircuitBreakerConfig
}

func NewCircuitBreakerConfigBuilder() *CircuitBreakerConfigBuilder {
	return &CircuitBreakerConfigBuilder{
		config: &models.CircuitBreakerConfig{
			CircuitID:           "circuit_test",
			ServiceName:         "test-service",
			FailureThreshold:    50,
			FailureRateThreshold: 0.5,
			OpenTimeout:         30,
			HalfOpenRequests:    3,
		},
	}
}

func (b *CircuitBreakerConfigBuilder) WithCircuitID(id string) *CircuitBreakerConfigBuilder {
	b.config.CircuitID = id
	return b
}

func (b *CircuitBreakerConfigBuilder) WithServiceName(name string) *CircuitBreakerConfigBuilder {
	b.config.ServiceName = name
	return b
}

func (b *CircuitBreakerConfigBuilder) WithFailureThreshold(threshold int) *CircuitBreakerConfigBuilder {
	b.config.FailureThreshold = threshold
	return b
}

func (b *CircuitBreakerConfigBuilder) WithFailureRateThreshold(rate float64) *CircuitBreakerConfigBuilder {
	b.config.FailureRateThreshold = rate
	return b
}

func (b *CircuitBreakerConfigBuilder) WithOpenTimeout(timeout int) *CircuitBreakerConfigBuilder {
	b.config.OpenTimeout = timeout
	return b
}

func (b *CircuitBreakerConfigBuilder) WithHalfOpenRequests(count int) *CircuitBreakerConfigBuilder {
	b.config.HalfOpenRequests = count
	return b
}

func (b *CircuitBreakerConfigBuilder) Build() *models.CircuitBreakerConfig {
	clone := *b.config
	return &clone
}

type LoadBalancerConfigBuilder struct {
	config *models.LoadBalancerConfig
}

func NewLoadBalancerConfigBuilder() *LoadBalancerConfigBuilder {
	return &LoadBalancerConfigBuilder{
		config: &models.LoadBalancerConfig{
			BalanceID:        "balance_test",
			ServiceName:      "test-service",
			BalanceAlgorithm: "round_robin",
			Instances: []models.ServiceInstance{
				{InstanceID: "test-01", Address: "127.0.0.1:8081", Weight: 1, Healthy: true},
				{InstanceID: "test-02", Address: "127.0.0.1:8082", Weight: 1, Healthy: true},
			},
		},
	}
}

func (b *LoadBalancerConfigBuilder) WithBalanceID(id string) *LoadBalancerConfigBuilder {
	b.config.BalanceID = id
	return b
}

func (b *LoadBalancerConfigBuilder) WithServiceName(name string) *LoadBalancerConfigBuilder {
	b.config.ServiceName = name
	return b
}

func (b *LoadBalancerConfigBuilder) WithAlgorithm(algo string) *LoadBalancerConfigBuilder {
	b.config.BalanceAlgorithm = algo
	return b
}

func (b *LoadBalancerConfigBuilder) WithInstances(instances []models.ServiceInstance) *LoadBalancerConfigBuilder {
	b.config.Instances = instances
	return b
}

func (b *LoadBalancerConfigBuilder) AddInstance(id, address string, weight int, healthy bool) *LoadBalancerConfigBuilder {
	b.config.Instances = append(b.config.Instances, models.ServiceInstance{
		InstanceID: id,
		Address:    address,
		Weight:     weight,
		Healthy:    healthy,
	})
	return b
}

func (b *LoadBalancerConfigBuilder) Build() *models.LoadBalancerConfig {
	clone := *b.config
	clone.Instances = make([]models.ServiceInstance, len(b.config.Instances))
	copy(clone.Instances, b.config.Instances)
	return &clone
}

type TestScenario struct {
	Name        string
	Description string
	Setup       func()
	Expected    interface{}
}

type RateLimitTestScenarios []struct {
	Name           string
	QPS            int
	Burst          int
	RequestCount   int
	ExpectedAllowed int
	ExpectedRejected int
}

func GetDefaultRateLimitScenarios() RateLimitTestScenarios {
	return RateLimitTestScenarios{
		{
			Name:             "Normal traffic within QPS limit",
			QPS:              100,
			Burst:            20,
			RequestCount:     50,
			ExpectedAllowed:  50,
			ExpectedRejected: 0,
		},
		{
			Name:             "Traffic at exactly QPS limit",
			QPS:              100,
			Burst:            20,
			RequestCount:     120,
			ExpectedAllowed:  120,
			ExpectedRejected: 0,
		},
		{
			Name:             "Traffic exceeding QPS limit",
			QPS:              10,
			Burst:            5,
			RequestCount:     100,
			ExpectedAllowed:  15,
			ExpectedRejected: 85,
		},
		{
			Name:             "Zero QPS means no limit",
			QPS:              0,
			Burst:            0,
			RequestCount:     1000,
			ExpectedAllowed:  1000,
			ExpectedRejected: 0,
		},
	}
}

type CircuitBreakerTestScenarios []struct {
	Name              string
	FailureThreshold  int
	FailureRate       float64
	TotalRequests     int
	FailureRequests   int
	ExpectedState     string
}

func GetDefaultCircuitBreakerScenarios() CircuitBreakerTestScenarios {
	return CircuitBreakerTestScenarios{
		{
			Name:             "All success - remains closed",
			FailureThreshold: 10,
			FailureRate:      0.5,
			TotalRequests:    100,
			FailureRequests:  0,
			ExpectedState:    "closed",
		},
		{
			Name:             "Below threshold - remains closed",
			FailureThreshold: 50,
			FailureRate:      0.5,
			TotalRequests:    100,
			FailureRequests:  10,
			ExpectedState:    "closed",
		},
		{
			Name:             "Above threshold but rate below - remains closed",
			FailureThreshold: 10,
			FailureRate:      0.6,
			TotalRequests:    100,
			FailureRequests:  50,
			ExpectedState:    "closed",
		},
	}
}

func GetDemoRouteConfigs() []*models.Route {
	return []*models.Route{
		NewRouteConfigBuilder().
			WithPattern("/api/users/*").
			WithTargetService("user-service").
			WithRateLimit(100, 20).
			WithAuthRequired(true).
			WithGroup("user").
			Build(),
		NewRouteConfigBuilder().
			WithPattern("/api/orders/*").
			WithTargetService("order-service").
			WithRateLimit(50, 10).
			WithAuthRequired(false).
			WithGroup("order").
			Build(),
		NewRouteConfigBuilder().
			WithPattern("/api/products/*").
			WithTargetService("product-service").
			WithRateLimit(200, 50).
			WithAuthRequired(false).
			WithGroup("product").
			Build(),
	}
}

func GetDemoServiceInstances(serviceName string) []models.ServiceInstance {
	switch serviceName {
	case "user-service":
		return []models.ServiceInstance{
			{InstanceID: "user-01", Address: "127.0.0.1:8081", Weight: 1, Healthy: true},
			{InstanceID: "user-02", Address: "127.0.0.1:8082", Weight: 2, Healthy: true},
			{InstanceID: "user-03", Address: "127.0.0.1:8083", Weight: 1, Healthy: false},
		}
	case "order-service":
		return []models.ServiceInstance{
			{InstanceID: "order-01", Address: "127.0.0.1:8091", Weight: 1, Healthy: true},
			{InstanceID: "order-02", Address: "127.0.0.1:8092", Weight: 1, Healthy: true},
		}
	default:
		return []models.ServiceInstance{
			{InstanceID: "default-01", Address: "127.0.0.1:8000", Weight: 1, Healthy: true},
		}
	}
}
