package gateway

import (
	"context"
	"net/http"
	"sync"

	"session189/internal/domain"
	"session189/pkg/config"
	"session189/pkg/eventbus"
)

type AuthStrategy interface {
	Name() string
	Authenticate(ctx context.Context, req *http.Request) (*domain.User, error)
	Enabled() bool
}

type RateLimitStrategy interface {
	Name() string
	Allow(ctx context.Context, key string) (bool, *RateLimitResult, error)
	Enabled() bool
}

type StrategyRegistry struct {
	mu           sync.RWMutex
	authStrategies    map[string]AuthStrategy
	rateLimitStrategies map[string]RateLimitStrategy
	currentAuth    string
	currentRateLimit string
	cfgManager     config.ConfigManager
	bus            eventbus.EventBus
}

func NewStrategyRegistry(cfgManager config.ConfigManager, bus eventbus.EventBus) *StrategyRegistry {
	return &StrategyRegistry{
		authStrategies:       make(map[string]AuthStrategy),
		rateLimitStrategies:  make(map[string]RateLimitStrategy),
		currentAuth:          "jwt",
		currentRateLimit:     "token_bucket",
		cfgManager:           cfgManager,
		bus:                  bus,
	}
}

func (r *StrategyRegistry) RegisterAuthStrategy(strategy AuthStrategy) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.authStrategies[strategy.Name()] = strategy
}

func (r *StrategyRegistry) RegisterRateLimitStrategy(strategy RateLimitStrategy) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.rateLimitStrategies[strategy.Name()] = strategy
}

func (r *StrategyRegistry) SetAuthStrategy(name string) error {
	r.mu.RLock()
	_, exists := r.authStrategies[name]
	r.mu.RUnlock()

	if !exists {
		return ErrStrategyNotFound
	}

	r.mu.Lock()
	r.currentAuth = name
	r.mu.Unlock()

	r.bus.Publish(context.Background(), eventbus.Event{
		Type:   eventbus.EventTypeConfigUpdated,
		Source: "gateway.strategy",
		Data:   map[string]string{"type": "auth", "strategy": name},
	})

	return nil
}

func (r *StrategyRegistry) SetRateLimitStrategy(name string) error {
	r.mu.RLock()
	_, exists := r.rateLimitStrategies[name]
	r.mu.RUnlock()

	if !exists {
		return ErrStrategyNotFound
	}

	r.mu.Lock()
	r.currentRateLimit = name
	r.mu.Unlock()

	r.bus.Publish(context.Background(), eventbus.Event{
		Type:   eventbus.EventTypeConfigUpdated,
		Source: "gateway.strategy",
		Data:   map[string]string{"type": "ratelimit", "strategy": name},
	})

	return nil
}

func (r *StrategyRegistry) GetAuthStrategy() AuthStrategy {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.authStrategies[r.currentAuth]
}

func (r *StrategyRegistry) GetRateLimitStrategy() RateLimitStrategy {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.rateLimitStrategies[r.currentRateLimit]
}

func (r *StrategyRegistry) ListAuthStrategies() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	names := make([]string, 0, len(r.authStrategies))
	for name, s := range r.authStrategies {
		if s.Enabled() {
			names = append(names, name)
		}
	}
	return names
}

func (r *StrategyRegistry) ListRateLimitStrategies() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	names := make([]string, 0, len(r.rateLimitStrategies))
	for name, s := range r.rateLimitStrategies {
		if s.Enabled() {
			names = append(names, name)
		}
	}
	return names
}

type JWTAuthStrategy struct {
	authManager *AuthManager
	enabled     bool
}

func NewJWTAuthStrategy(authManager *AuthManager) *JWTAuthStrategy {
	return &JWTAuthStrategy{
		authManager: authManager,
		enabled:     true,
	}
}

func (s *JWTAuthStrategy) Name() string { return "jwt" }
func (s *JWTAuthStrategy) Enabled() bool { return s.enabled }

func (s *JWTAuthStrategy) Authenticate(ctx context.Context, req *http.Request) (*domain.User, error) {
	authHeader := req.Header.Get("Authorization")
	_, token, err := s.authManager.ExtractCredentials(authHeader)
	if err != nil {
		return nil, err
	}
	return s.authManager.ValidateToken(token)
}

type APIKeyAuthStrategy struct {
	authManager *AuthManager
	enabled     bool
}

func NewAPIKeyAuthStrategy(authManager *AuthManager) *APIKeyAuthStrategy {
	return &APIKeyAuthStrategy{
		authManager: authManager,
		enabled:     true,
	}
}

func (s *APIKeyAuthStrategy) Name() string { return "api_key" }
func (s *APIKeyAuthStrategy) Enabled() bool { return s.enabled }

func (s *APIKeyAuthStrategy) Authenticate(ctx context.Context, req *http.Request) (*domain.User, error) {
	apiKey := req.Header.Get("X-API-Key")
	if apiKey == "" {
		authHeader := req.Header.Get("Authorization")
		_, key, err := s.authManager.ExtractCredentials(authHeader)
		if err != nil {
			return nil, err
		}
		apiKey = key
	}
	return s.authManager.ValidateAPIKey(apiKey)
}

type TokenBucketStrategy struct {
	limiter *TokenBucketLimiter
	enabled bool
}

func NewTokenBucketStrategy(limiter *TokenBucketLimiter) *TokenBucketStrategy {
	return &TokenBucketStrategy{
		limiter: limiter,
		enabled: true,
	}
}

func (s *TokenBucketStrategy) Name() string { return "token_bucket" }
func (s *TokenBucketStrategy) Enabled() bool { return s.enabled }

func (s *TokenBucketStrategy) Allow(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	return s.limiter.Allow(ctx, key)
}

type FixedWindowStrategy struct {
	limiter *FixedWindowLimiter
	enabled bool
}

func NewFixedWindowStrategy(limiter *FixedWindowLimiter) *FixedWindowStrategy {
	return &FixedWindowStrategy{
		limiter: limiter,
		enabled: true,
	}
}

func (s *FixedWindowStrategy) Name() string { return "fixed_window" }
func (s *FixedWindowStrategy) Enabled() bool { return s.enabled }

func (s *FixedWindowStrategy) Allow(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	return s.limiter.Allow(ctx, key)
}

type SlidingWindowStrategy struct {
	limiter *SlidingWindowLimiter
	enabled bool
}

func NewSlidingWindowStrategy(limiter *SlidingWindowLimiter) *SlidingWindowStrategy {
	return &SlidingWindowStrategy{
		limiter: limiter,
		enabled: true,
	}
}

func (s *SlidingWindowStrategy) Name() string { return "sliding_window" }
func (s *SlidingWindowStrategy) Enabled() bool { return s.enabled }

func (s *SlidingWindowStrategy) Allow(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	return s.limiter.Allow(ctx, key)
}
