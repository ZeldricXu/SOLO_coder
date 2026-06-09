package config

import (
	"DF1-56/internal/models"
	"sync"
)

const (
	EtcdKeyPrefixRoutes         = "/apigateway/routes/"
	EtcdKeyPrefixRateLimits     = "/apigateway/ratelimits/"
	EtcdKeyPrefixAuths          = "/apigateway/auths/"
	EtcdKeyPrefixCircuitBreakers = "/apigateway/circuitbreakers/"
	EtcdKeyPrefixGrays          = "/apigateway/grays/"
	EtcdKeyPrefixMirrors        = "/apigateway/mirrors/"
	EtcdKeyPrefixUpstreams      = "/apigateway/upstreams/"
)

type ETCDConfig struct {
	Endpoints []string
	Username  string
	Password  string
}

type ChangeType string

const (
	ChangeTypePut    ChangeType = "PUT"
	ChangeTypeDelete ChangeType = "DELETE"
)

type GatewayConfig struct {
	Routes          map[string]*models.Route              `json:"routes"`
	RateLimits      map[string]*models.RateLimitPolicy    `json:"rate_limits"`
	Auths           map[string]*models.AuthPolicy         `json:"auths"`
	CircuitBreakers map[string]*models.CircuitBreakerPolicy `json:"circuit_breakers"`
	Grays           map[string]*models.GrayPolicy         `json:"grays"`
	Mirrors         map[string]*models.MirrorPolicy       `json:"mirrors"`
	Upstreams       map[string]*models.UpstreamCluster    `json:"upstreams"`
}

type ConfigStore struct {
	mu              sync.RWMutex
	routes          map[string]*models.Route
	rateLimits      map[string]*models.RateLimitPolicy
	auths           map[string]*models.AuthPolicy
	circuitBreakers map[string]*models.CircuitBreakerPolicy
	grays           map[string]*models.GrayPolicy
	mirrors         map[string]*models.MirrorPolicy
	upstreams       map[string]*models.UpstreamCluster
}

func NewConfigStore() *ConfigStore {
	return &ConfigStore{
		routes:          make(map[string]*models.Route),
		rateLimits:      make(map[string]*models.RateLimitPolicy),
		auths:           make(map[string]*models.AuthPolicy),
		circuitBreakers: make(map[string]*models.CircuitBreakerPolicy),
		grays:           make(map[string]*models.GrayPolicy),
		mirrors:         make(map[string]*models.MirrorPolicy),
		upstreams:       make(map[string]*models.UpstreamCluster),
	}
}

func (s *ConfigStore) GetRoute(id string) (*models.Route, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	r, ok := s.routes[id]
	return r, ok
}

func (s *ConfigStore) GetRoutes() map[string]*models.Route {
	s.mu.RLock()
	defer s.mu.RUnlock()
	routes := make(map[string]*models.Route, len(s.routes))
	for k, v := range s.routes {
		routes[k] = v
	}
	return routes
}

func (s *ConfigStore) SetRoute(id string, route *models.Route) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.routes[id] = route
}

func (s *ConfigStore) DeleteRoute(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.routes, id)
}

func (s *ConfigStore) GetRateLimit(id string) (*models.RateLimitPolicy, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	p, ok := s.rateLimits[id]
	return p, ok
}

func (s *ConfigStore) GetRateLimits() map[string]*models.RateLimitPolicy {
	s.mu.RLock()
	defer s.mu.RUnlock()
	policies := make(map[string]*models.RateLimitPolicy, len(s.rateLimits))
	for k, v := range s.rateLimits {
		policies[k] = v
	}
	return policies
}

func (s *ConfigStore) SetRateLimit(id string, policy *models.RateLimitPolicy) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.rateLimits[id] = policy
}

func (s *ConfigStore) DeleteRateLimit(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.rateLimits, id)
}

func (s *ConfigStore) GetAuth(id string) (*models.AuthPolicy, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	p, ok := s.auths[id]
	return p, ok
}

func (s *ConfigStore) GetAuths() map[string]*models.AuthPolicy {
	s.mu.RLock()
	defer s.mu.RUnlock()
	policies := make(map[string]*models.AuthPolicy, len(s.auths))
	for k, v := range s.auths {
		policies[k] = v
	}
	return policies
}

func (s *ConfigStore) SetAuth(id string, policy *models.AuthPolicy) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.auths[id] = policy
}

func (s *ConfigStore) DeleteAuth(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.auths, id)
}

func (s *ConfigStore) GetCircuitBreaker(id string) (*models.CircuitBreakerPolicy, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	p, ok := s.circuitBreakers[id]
	return p, ok
}

func (s *ConfigStore) GetCircuitBreakers() map[string]*models.CircuitBreakerPolicy {
	s.mu.RLock()
	defer s.mu.RUnlock()
	policies := make(map[string]*models.CircuitBreakerPolicy, len(s.circuitBreakers))
	for k, v := range s.circuitBreakers {
		policies[k] = v
	}
	return policies
}

func (s *ConfigStore) SetCircuitBreaker(id string, policy *models.CircuitBreakerPolicy) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.circuitBreakers[id] = policy
}

func (s *ConfigStore) DeleteCircuitBreaker(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.circuitBreakers, id)
}

func (s *ConfigStore) GetGray(id string) (*models.GrayPolicy, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	p, ok := s.grays[id]
	return p, ok
}

func (s *ConfigStore) GetGrays() map[string]*models.GrayPolicy {
	s.mu.RLock()
	defer s.mu.RUnlock()
	policies := make(map[string]*models.GrayPolicy, len(s.grays))
	for k, v := range s.grays {
		policies[k] = v
	}
	return policies
}

func (s *ConfigStore) SetGray(id string, policy *models.GrayPolicy) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.grays[id] = policy
}

func (s *ConfigStore) DeleteGray(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.grays, id)
}

func (s *ConfigStore) GetMirror(id string) (*models.MirrorPolicy, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	p, ok := s.mirrors[id]
	return p, ok
}

func (s *ConfigStore) GetMirrors() map[string]*models.MirrorPolicy {
	s.mu.RLock()
	defer s.mu.RUnlock()
	policies := make(map[string]*models.MirrorPolicy, len(s.mirrors))
	for k, v := range s.mirrors {
		policies[k] = v
	}
	return policies
}

func (s *ConfigStore) SetMirror(id string, policy *models.MirrorPolicy) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.mirrors[id] = policy
}

func (s *ConfigStore) DeleteMirror(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.mirrors, id)
}

func (s *ConfigStore) GetUpstream(id string) (*models.UpstreamCluster, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	c, ok := s.upstreams[id]
	return c, ok
}

func (s *ConfigStore) GetUpstreams() map[string]*models.UpstreamCluster {
	s.mu.RLock()
	defer s.mu.RUnlock()
	clusters := make(map[string]*models.UpstreamCluster, len(s.upstreams))
	for k, v := range s.upstreams {
		clusters[k] = v
	}
	return clusters
}

func (s *ConfigStore) SetUpstream(id string, cluster *models.UpstreamCluster) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.upstreams[id] = cluster
}

func (s *ConfigStore) DeleteUpstream(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.upstreams, id)
}

func (s *ConfigStore) LoadAll(cfg *GatewayConfig) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.routes = cfg.Routes
	s.rateLimits = cfg.RateLimits
	s.auths = cfg.Auths
	s.circuitBreakers = cfg.CircuitBreakers
	s.grays = cfg.Grays
	s.mirrors = cfg.Mirrors
	s.upstreams = cfg.Upstreams
}

func (s *ConfigStore) ToGatewayConfig() *GatewayConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return &GatewayConfig{
		Routes:          s.GetRoutes(),
		RateLimits:      s.GetRateLimits(),
		Auths:           s.GetAuths(),
		CircuitBreakers: s.GetCircuitBreakers(),
		Grays:           s.GetGrays(),
		Mirrors:         s.GetMirrors(),
		Upstreams:       s.GetUpstreams(),
	}
}
