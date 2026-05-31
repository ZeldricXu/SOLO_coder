package apigateway

import (
	"context"
	"fmt"
	"sync"

	"github.com/go-redis/redis/v8"
	"go.uber.org/zap"
)

type StrategyManager struct {
	strategies       map[string]RateLimitStrategy
	currentStrategy  RateLimitStrategy
	currentStrategyName string
	config           *RateLimitConfig
	redisClient      *redis.Client
	logger           *zap.Logger
	mu               sync.RWMutex
}

func NewStrategyManager(config *RateLimitConfig, redisClient *redis.Client, logger *zap.Logger) *StrategyManager {
	sm := &StrategyManager{
		strategies:  make(map[string]RateLimitStrategy),
		config:      config,
		redisClient: redisClient,
		logger:      logger,
	}

	sm.initDefaultStrategies()
	sm.SetStrategy(string(config.Strategy))

	return sm
}

func (sm *StrategyManager) initDefaultStrategies() {
	sm.strategies[string(StrategyTokenBucket)] = NewTokenBucketStrategy(sm.config, sm.redisClient, sm.logger)
	sm.strategies[string(StrategySlidingWindow)] = NewSlidingWindowStrategy(sm.config, sm.redisClient, sm.logger)
	sm.strategies[string(StrategyFixedWindow)] = NewFixedWindowStrategy(sm.config, sm.redisClient, sm.logger)
}

func (sm *StrategyManager) RegisterStrategy(name string, strategy RateLimitStrategy) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.strategies[name] = strategy
	sm.logger.Info("New rate limit strategy registered", zap.String("name", name))
}

func (sm *StrategyManager) SetStrategy(name string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	strategy, exists := sm.strategies[name]
	if !exists {
		return fmt.Errorf("strategy %s not found", name)
	}

	oldName := sm.currentStrategyName
	sm.currentStrategy = strategy
	sm.currentStrategyName = name
	sm.config.Strategy = RateLimitStrategy(name)

	sm.logger.Info("Rate limit strategy changed",
		zap.String("old", oldName),
		zap.String("new", name),
	)

	return nil
}

func (sm *StrategyManager) GetCurrentStrategy() RateLimitStrategy {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.currentStrategy
}

func (sm *StrategyManager) GetStrategy(name string) (RateLimitStrategy, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	strategy, exists := sm.strategies[name]
	if !exists {
		return nil, fmt.Errorf("strategy %s not found", name)
	}
	return strategy, nil
}

func (sm *StrategyManager) ListStrategies() []string {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	names := make([]string, 0, len(sm.strategies))
	for name := range sm.strategies {
		names = append(names, name)
	}
	return names
}

func (sm *StrategyManager) Allow(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	sm.mu.RLock()
	strategy := sm.currentStrategy
	sm.mu.RUnlock()

	if strategy == nil {
		return true, nil, fmt.Errorf("no rate limit strategy set")
	}

	return strategy.Allow(ctx, key)
}

func (sm *StrategyManager) UpdateConfig(config *RateLimitConfig) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	sm.config = config

	for _, strategy := range sm.strategies {
		strategy.UpdateConfig(config)
	}

	if string(config.Strategy) != sm.currentStrategyName {
		if strategy, exists := sm.strategies[string(config.Strategy)]; exists {
			sm.currentStrategy = strategy
			sm.currentStrategyName = string(config.Strategy)
			sm.logger.Info("Strategy changed via config update",
				zap.String("strategy", string(config.Strategy)),
			)
		}
	}
}

func (sm *StrategyManager) CleanupAll(ctx context.Context) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	for name, strategy := range sm.strategies {
		if err := strategy.Cleanup(ctx); err != nil {
			sm.logger.Error("Failed to cleanup strategy",
				zap.String("name", name),
				zap.Error(err),
			)
		}
	}
}

func (sm *StrategyManager) GetConfig() *RateLimitConfig {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.config
}

func (sm *StrategyManager) GetCurrentStrategyName() string {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.currentStrategyName
}
