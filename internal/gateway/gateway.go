package gateway

import (
	"errors"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/enterprise/config-platform/pkg/utils"
	"golang.org/x/time/rate"
)

type AuthMethod string

const (
	AuthMethodAPIKey AuthMethod = "api_key"
	AuthMethodJWT    AuthMethod = "jwt"
	AuthMethodmTLS   AuthMethod = "mtls"
)

type RateLimitConfig struct {
	RequestsPerSecond int `json:"requests_per_second"`
	BurstSize         int `json:"burst_size"`
}

type Route struct {
	ID          string            `json:"id"`
	Path        string            `json:"path"`
	Method      string            `json:"method"`
	UpstreamURL string            `json:"upstream_url"`
	AuthRequired bool             `json:"auth_required"`
	AuthMethods []AuthMethod      `json:"auth_methods"`
	RateLimit   RateLimitConfig   `json:"rate_limit"`
	Timeout     int               `json:"timeout"`
	Enabled     bool              `json:"enabled"`
	CreatedAt   time.Time         `json:"created_at"`
}

type APIKey struct {
	Key         string    `json:"key"`
	UserID      string    `json:"user_id"`
	Roles       []string  `json:"roles"`
	ExpiresAt   time.Time `json:"expires_at"`
	CreatedAt   time.Time `json:"created_at"`
}

type UserRole string

const (
	RoleAdmin  UserRole = "admin"
	RoleUser   UserRole = "user"
	RoleViewer UserRole = "viewer"
)

type limiter struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

type Manager struct {
	routes      map[string]*Route
	apiKeys     map[string]*APIKey
	rateLimiters map[string]*limiter
	roles       map[string][]UserRole
	mu          sync.RWMutex
	cleanupDone chan struct{}
}

var (
	instance *Manager
	once     sync.Once
)

func GetManager() *Manager {
	once.Do(func() {
		instance = &Manager{
			routes:       make(map[string]*Route),
			apiKeys:      make(map[string]*APIKey),
			rateLimiters: make(map[string]*limiter),
			roles:        make(map[string][]UserRole),
			cleanupDone:  make(chan struct{}),
		}
		go instance.startCleanupLoop()
	})
	return instance
}

func (m *Manager) startCleanupLoop() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			m.mu.Lock()
			for key, lim := range m.rateLimiters {
				if time.Since(lim.lastSeen) > 3*time.Minute {
					delete(m.rateLimiters, key)
				}
			}
			m.mu.Unlock()
		case <-m.cleanupDone:
			return
		}
	}
}

func (m *Manager) Close() {
	close(m.cleanupDone)
}

func (m *Manager) CreateRoute(route *Route) (*Route, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	route.ID = utils.GenerateID("route")
	route.CreatedAt = time.Now().UTC()
	route.Enabled = true

	if route.Timeout == 0 {
		route.Timeout = 30
	}
	if route.RateLimit.RequestsPerSecond == 0 {
		route.RateLimit.RequestsPerSecond = 100
	}
	if route.RateLimit.BurstSize == 0 {
		route.RateLimit.BurstSize = 200
	}

	m.routes[route.ID] = route
	return route, nil
}

func (m *Manager) GetRoute(routeID string) (*Route, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	route, exists := m.routes[routeID]
	if !exists {
		return nil, errors.New("route not found")
	}
	return route, nil
}

func (m *Manager) UpdateRoute(routeID string, updates map[string]interface{}) (*Route, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	route, exists := m.routes[routeID]
	if !exists {
		return nil, errors.New("route not found")
	}

	if path, ok := updates["path"].(string); ok {
		route.Path = path
	}
	if upstream, ok := updates["upstream_url"].(string); ok {
		route.UpstreamURL = upstream
	}
	if authReq, ok := updates["auth_required"].(bool); ok {
		route.AuthRequired = authReq
	}
	if enabled, ok := updates["enabled"].(bool); ok {
		route.Enabled = enabled
	}
	if rl, ok := updates["rate_limit"].(RateLimitConfig); ok {
		route.RateLimit = rl
	}

	return route, nil
}

func (m *Manager) DeleteRoute(routeID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.routes[routeID]; !exists {
		return errors.New("route not found")
	}
	delete(m.routes, routeID)
	return nil
}

func (m *Manager) ListRoutes() []*Route {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*Route, 0, len(m.routes))
	for _, route := range m.routes {
		result = append(result, route)
	}
	return result
}

func (m *Manager) CreateAPIKey(userID string, roles []string, ttl time.Duration) *APIKey {
	m.mu.Lock()
	defer m.mu.Unlock()

	apiKey := &APIKey{
		Key:       utils.GenerateRandomString(32),
		UserID:    userID,
		Roles:     roles,
		ExpiresAt: time.Now().Add(ttl),
		CreatedAt: time.Now().UTC(),
	}

	m.apiKeys[apiKey.Key] = apiKey
	m.roles[userID] = append(m.roles[userID], UserRole(roles[0]))
	return apiKey
}

func (m *Manager) ValidateAPIKey(key string) (*APIKey, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	apiKey, exists := m.apiKeys[key]
	if !exists {
		return nil, false
	}

	if time.Now().After(apiKey.ExpiresAt) {
		delete(m.apiKeys, key)
		return nil, false
	}

	return apiKey, true
}

func (m *Manager) RevokeAPIKey(key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.apiKeys[key]; !exists {
		return errors.New("api key not found")
	}
	delete(m.apiKeys, key)
	return nil
}

func (m *Manager) CheckRateLimit(clientIP string, config RateLimitConfig) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	lim, exists := m.rateLimiters[clientIP]
	if !exists {
		lim = &limiter{
			limiter: rate.NewLimiter(rate.Limit(config.RequestsPerSecond), config.BurstSize),
		}
		m.rateLimiters[clientIP] = lim
	}

	lim.lastSeen = time.Now()
	return lim.limiter.Allow()
}

func (m *Manager) AuthenticateRequest(r *http.Request) (bool, *APIKey, error) {
	apiKeyHeader := r.Header.Get("X-API-Key")
	if apiKeyHeader != "" {
		apiKey, valid := m.ValidateAPIKey(apiKeyHeader)
		if !valid {
			return false, nil, errors.New("invalid api key")
		}
		return true, apiKey, nil
	}

	authHeader := r.Header.Get("Authorization")
	if authHeader != "" {
		return true, nil, nil
	}

	return false, nil, errors.New("no authentication provided")
}

func (m *Manager) CheckRole(apiKey *APIKey, requiredRole UserRole) bool {
	if apiKey == nil {
		return false
	}

	for _, role := range apiKey.Roles {
		if UserRole(role) == requiredRole {
			return true
		}
	}
	return false
}

func (m *Manager) MatchRoute(path, method string) (*Route, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, route := range m.routes {
		if route.Enabled && route.Path == path && route.Method == method {
			return route, true
		}
	}
	return nil, false
}

func GetClientIP(r *http.Request) string {
	ip := r.Header.Get("X-Forwarded-For")
	if ip == "" {
		ip, _, _ = net.SplitHostPort(r.RemoteAddr)
	}
	return ip
}
