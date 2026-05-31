package apigateway

import (
	"context"
	"sync"
	"time"

	"github.com/go-redis/redis/v8"
	"go.uber.org/zap"
)

type AdaptiveRateLimiter struct {
	strategyManager *StrategyManager
	redisClient     *redis.Client
	logger          *zap.Logger
	mu              sync.RWMutex
}

func NewAdaptiveRateLimiter(config *RateLimitConfig, redisClient *redis.Client, logger *zap.Logger) *AdaptiveRateLimiter {
	return &AdaptiveRateLimiter{
		strategyManager: NewStrategyManager(config, redisClient, logger),
		redisClient:     redisClient,
		logger:          logger,
	}
}

func (arl *AdaptiveRateLimiter) Allow(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	return arl.strategyManager.Allow(ctx, key)
}

func (arl *AdaptiveRateLimiter) SetStrategy(strategyName string) error {
	return arl.strategyManager.SetStrategy(strategyName)
}

func (arl *AdaptiveRateLimiter) GetCurrentStrategy() string {
	return arl.strategyManager.GetCurrentStrategyName()
}

func (arl *AdaptiveRateLimiter) ListAvailableStrategies() []string {
	return arl.strategyManager.ListStrategies()
}

func (arl *AdaptiveRateLimiter) UpdateConfig(config *RateLimitConfig) {
	arl.strategyManager.UpdateConfig(config)
}

func (arl *AdaptiveRateLimiter) GetConfig() *RateLimitConfig {
	return arl.strategyManager.GetConfig()
}

func (arl *AdaptiveRateLimiter) RegisterCustomStrategy(name string, strategy RateLimitStrategy) {
	arl.strategyManager.RegisterStrategy(name, strategy)
}

func (arl *AdaptiveRateLimiter) Cleanup(ctx context.Context) {
	arl.strategyManager.CleanupAll(ctx)
}

type RateLimitStrategyManager interface {
	Allow(ctx context.Context, key string) (bool, *RateLimitResult, error)
	SetStrategy(name string) error
	GetCurrentStrategy() string
	ListStrategies() []string
	UpdateConfig(config *RateLimitConfig)
	RegisterStrategy(name string, strategy RateLimitStrategy)
}

func (arl *AdaptiveRateLimiter) StartAdaptiveTuning(ctx context.Context, metricsFn func() map[string]float64) {
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				arl.logger.Info("Adaptive tuning stopped")
				return
			case <-ticker.C:
				metrics := metricsFn()
				arl.adjustStrategy(metrics)
			}
		}
	}()
}

func (arl *AdaptiveRateLimiter) adjustStrategy(metrics map[string]float64) {
	arl.mu.Lock()
	defer arl.mu.Unlock()

	errorRate := metrics["error_rate"]
	latencyP95 := metrics["latency_p95_ms"]
	throughput := metrics["throughput"]

	currentStrategy := arl.strategyManager.GetCurrentStrategyName()

	if errorRate > 0.05 || latencyP95 > 500 {
		if currentStrategy != string(StrategySlidingWindow) {
			arl.logger.Info("High error rate or latency detected, switching to sliding window",
				zap.Float64("error_rate", errorRate),
				zap.Float64("latency_p95", latencyP95),
			)
			arl.strategyManager.SetStrategy(string(StrategySlidingWindow))
		}
		config := arl.strategyManager.GetConfig()
		if config.Limit > 10 {
			newLimit := int(float64(config.Limit) * 0.8)
			arl.logger.Info("Reducing rate limit",
				zap.Int("old_limit", config.Limit),
				zap.Int("new_limit", newLimit),
			)
			config.Limit = newLimit
			arl.strategyManager.UpdateConfig(config)
		}
	} else if throughput > 1000 && errorRate < 0.01 {
		if currentStrategy != string(StrategyTokenBucket) {
			arl.logger.Info("High throughput with low error rate, switching to token bucket",
				zap.Float64("throughput", throughput),
				zap.Float64("error_rate", errorRate),
			)
			arl.strategyManager.SetStrategy(string(StrategyTokenBucket))
		}
		config := arl.strategyManager.GetConfig()
		newLimit := int(float64(config.Limit) * 1.2)
		arl.logger.Info("Increasing rate limit",
			zap.Int("old_limit", config.Limit),
			zap.Int("new_limit", newLimit),
		)
		config.Limit = newLimit
		arl.strategyManager.UpdateConfig(config)
	}
}
