package tests

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap/zaptest"

	"session133/internal/apigateway"
)

func TestStrategyManager_SetStrategy(t *testing.T) {
	logger := zaptest.NewLogger(t)
	config := &apigateway.RateLimitConfig{
		Strategy:    apigateway.StrategyTokenBucket,
		Limit:       100,
		Window:      time.Minute,
		Burst:       100,
		RedisPrefix: "test",
	}

	sm := apigateway.NewStrategyManager(config, nil, logger)

	assert.Equal(t, string(apigateway.StrategyTokenBucket), sm.GetCurrentStrategyName())

	err := sm.SetStrategy(string(apigateway.StrategySlidingWindow))
	require.NoError(t, err)
	assert.Equal(t, string(apigateway.StrategySlidingWindow), sm.GetCurrentStrategyName())

	err = sm.SetStrategy(string(apigateway.StrategyFixedWindow))
	require.NoError(t, err)
	assert.Equal(t, string(apigateway.StrategyFixedWindow), sm.GetCurrentStrategyName())

	err = sm.SetStrategy("unknown_strategy")
	assert.Error(t, err)
}

func TestStrategyManager_ListStrategies(t *testing.T) {
	logger := zaptest.NewLogger(t)
	config := &apigateway.RateLimitConfig{
		Strategy:    apigateway.StrategyTokenBucket,
		Limit:       100,
		Window:      time.Minute,
		Burst:       100,
		RedisPrefix: "test",
	}

	sm := apigateway.NewStrategyManager(config, nil, logger)

	strategies := sm.ListStrategies()
	assert.Len(t, strategies, 3)
	assert.Contains(t, strategies, string(apigateway.StrategyTokenBucket))
	assert.Contains(t, strategies, string(apigateway.StrategySlidingWindow))
	assert.Contains(t, strategies, string(apigateway.StrategyFixedWindow))
}

func TestStrategyManager_RegisterCustomStrategy(t *testing.T) {
	logger := zaptest.NewLogger(t)
	config := &apigateway.RateLimitConfig{
		Strategy:    apigateway.StrategyTokenBucket,
		Limit:       100,
		Window:      time.Minute,
		Burst:       100,
		RedisPrefix: "test",
	}

	sm := apigateway.NewStrategyManager(config, nil, logger)

	customStrategy := &MockRateLimitStrategy{name: "custom"}
	sm.RegisterStrategy("custom", customStrategy)

	strategies := sm.ListStrategies()
	assert.Len(t, strategies, 4)
	assert.Contains(t, strategies, "custom")

	err := sm.SetStrategy("custom")
	require.NoError(t, err)
	assert.Equal(t, "custom", sm.GetCurrentStrategyName())
}

func TestStrategyManager_Allow(t *testing.T) {
	logger := zaptest.NewLogger(t)
	config := &apigateway.RateLimitConfig{
		Strategy:    apigateway.StrategyTokenBucket,
		Limit:       5,
		Window:      time.Minute,
		Burst:       5,
		RedisPrefix: "test",
	}

	sm := apigateway.NewStrategyManager(config, nil, logger)

	ctx := context.Background()
	for i := 0; i < 5; i++ {
		allowed, result, err := sm.Allow(ctx, "test_key")
		require.NoError(t, err)
		assert.True(t, allowed)
		assert.NotNil(t, result)
	}

	allowed, result, err := sm.Allow(ctx, "test_key")
	require.NoError(t, err)
	assert.NotNil(t, result)
}

func TestStrategyManager_UpdateConfig(t *testing.T) {
	logger := zaptest.NewLogger(t)
	config := &apigateway.RateLimitConfig{
		Strategy:    apigateway.StrategyTokenBucket,
		Limit:       100,
		Window:      time.Minute,
		Burst:       100,
		RedisPrefix: "test",
	}

	sm := apigateway.NewStrategyManager(config, nil, logger)

	newConfig := &apigateway.RateLimitConfig{
		Strategy:    apigateway.StrategySlidingWindow,
		Limit:       200,
		Window:      2 * time.Minute,
		Burst:       200,
		RedisPrefix: "test_v2",
	}

	sm.UpdateConfig(newConfig)

	updatedConfig := sm.GetConfig()
	assert.Equal(t, apigateway.StrategySlidingWindow, updatedConfig.Strategy)
	assert.Equal(t, 200, updatedConfig.Limit)
	assert.Equal(t, string(apigateway.StrategySlidingWindow), sm.GetCurrentStrategyName())
}

func TestAdaptiveRateLimiter_StrategySwitching(t *testing.T) {
	logger := zaptest.NewLogger(t)
	config := &apigateway.RateLimitConfig{
		Strategy:    apigateway.StrategyTokenBucket,
		Limit:       100,
		Window:      time.Minute,
		Burst:       100,
		RedisPrefix: "test",
	}

	arl := apigateway.NewAdaptiveRateLimiter(config, nil, logger)

	assert.Equal(t, string(apigateway.StrategyTokenBucket), arl.GetCurrentStrategy())

	err := arl.SetStrategy(string(apigateway.StrategySlidingWindow))
	require.NoError(t, err)
	assert.Equal(t, string(apigateway.StrategySlidingWindow), arl.GetCurrentStrategy())

	available := arl.ListAvailableStrategies()
	assert.Len(t, available, 3)
}

func TestAdaptiveRateLimiter_RegisterCustomStrategy(t *testing.T) {
	logger := zaptest.NewLogger(t)
	config := &apigateway.RateLimitConfig{
		Strategy:    apigateway.StrategyTokenBucket,
		Limit:       100,
		Window:      time.Minute,
		Burst:       100,
		RedisPrefix: "test",
	}

	arl := apigateway.NewAdaptiveRateLimiter(config, nil, logger)

	customStrategy := &MockRateLimitStrategy{name: "custom_strategy"}
	arl.RegisterCustomStrategy("custom_strategy", customStrategy)

	available := arl.ListAvailableStrategies()
	assert.Contains(t, available, "custom_strategy")

	err := arl.SetStrategy("custom_strategy")
	require.NoError(t, err)
	assert.Equal(t, "custom_strategy", arl.GetCurrentStrategy())
}

func TestStrategyManager_ConcurrentStrategyAccess(t *testing.T) {
	logger := zaptest.NewLogger(t)
	config := &apigateway.RateLimitConfig{
		Strategy:    apigateway.StrategyTokenBucket,
		Limit:       1000,
		Window:      time.Minute,
		Burst:       1000,
		RedisPrefix: "test",
	}

	sm := apigateway.NewStrategyManager(config, nil, logger)

	ctx := context.Background()
	done := make(chan bool)

	go func() {
		for i := 0; i < 100; i++ {
			sm.Allow(ctx, "concurrent_key")
		}
		done <- true
	}()

	go func() {
		for i := 0; i < 10; i++ {
			sm.SetStrategy(string(apigateway.StrategySlidingWindow))
			time.Sleep(time.Millisecond)
			sm.SetStrategy(string(apigateway.StrategyTokenBucket))
			time.Sleep(time.Millisecond)
		}
		done <- true
	}()

	<-done
	<-done
}

type MockRateLimitStrategy struct {
	name string
}

func (m *MockRateLimitStrategy) Name() string {
	return m.name
}

func (m *MockRateLimitStrategy) Allow(ctx context.Context, key string) (bool, *apigateway.RateLimitResult, error) {
	return true, &apigateway.RateLimitResult{
		Limit:     100,
		Remaining: 100,
		Reset:     time.Now().Add(time.Minute),
		Allowed:   true,
	}, nil
}

func (m *MockRateLimitStrategy) UpdateConfig(config *apigateway.RateLimitConfig) {}

func (m *MockRateLimitStrategy) Cleanup(ctx context.Context) error {
	return nil
}
