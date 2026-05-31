package notification

import (
	"fmt"
	"sync"
	"time"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/notification/strategies"
)

type StrategyManager struct {
	routingStrategies   map[contracts.RoutingStrategyType]contracts.RoutingStrategy
	retryStrategies     map[contracts.RetryStrategyType]contracts.RetryStrategy
	fallbackStrategies  map[contracts.FallbackStrategyType]contracts.FallbackStrategy
	defaultRouting     contracts.RoutingStrategyType
	defaultRetry       contracts.RetryStrategyType
	defaultFallback    contracts.FallbackStrategyType
	mu                 sync.RWMutex
}

func NewStrategyManager() *StrategyManager {
	sm := &StrategyManager{
		routingStrategies:  make(map[contracts.RoutingStrategyType]contracts.RoutingStrategy),
		retryStrategies:    make(map[contracts.RetryStrategyType]contracts.RetryStrategy),
		fallbackStrategies: make(map[contracts.FallbackStrategyType]contracts.FallbackStrategy),
		defaultRouting:     contracts.RoutingStrategySingle,
		defaultRetry:       contracts.RetryStrategyFixed,
		defaultFallback:    contracts.FallbackStrategyNone,
	}

	sm.registerDefaultStrategies()

	return sm
}

func (sm *StrategyManager) registerDefaultStrategies() {
	sm.RegisterRoutingStrategy(strategies.NewSingleChannelStrategy(contracts.ChannelEmail))
	sm.RegisterRoutingStrategy(strategies.NewBroadcastStrategy())
	sm.RegisterRoutingStrategy(strategies.NewFailoverStrategy([]contracts.ChannelType{
		contracts.ChannelEmail,
		contracts.ChannelSlack,
		contracts.ChannelSMS,
	}))
	sm.RegisterRoutingStrategy(strategies.NewPriorityStrategy())
	sm.RegisterRoutingStrategy(strategies.NewRoundRobinStrategy())

	sm.RegisterRetryStrategy(strategies.NewFixedRetryStrategy(3, 1*time.Second))
	sm.RegisterRetryStrategy(strategies.NewExponentialRetryStrategy(5, 100*time.Millisecond, 30*time.Second))
	sm.RegisterRetryStrategy(strategies.NewLinearRetryStrategy(5, 500*time.Millisecond, 500*time.Millisecond, 10*time.Second))
	sm.RegisterRetryStrategy(strategies.NewNoRetryStrategy())

	sm.RegisterFallbackStrategy(strategies.NewNoFallbackStrategy())
	sm.RegisterFallbackStrategy(strategies.NewDowngradeFallbackStrategy(contracts.ChannelEmail))
	sm.RegisterFallbackStrategy(strategies.NewQueueFallbackStrategy(1000))
}

func (sm *StrategyManager) RegisterRoutingStrategy(strategy contracts.RoutingStrategy) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.routingStrategies[strategy.GetType()] = strategy
}

func (sm *StrategyManager) GetRoutingStrategy(strategyType contracts.RoutingStrategyType) (contracts.RoutingStrategy, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	strategy, exists := sm.routingStrategies[strategyType]
	if !exists {
		return nil, fmt.Errorf("routing strategy not found: %s", strategyType)
	}
	return strategy, nil
}

func (sm *StrategyManager) ListRoutingStrategies() []contracts.RoutingStrategyType {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	types := make([]contracts.RoutingStrategyType, 0, len(sm.routingStrategies))
	for t := range sm.routingStrategies {
		types = append(types, t)
	}
	return types
}

func (sm *StrategyManager) SetDefaultRoutingStrategy(strategyType contracts.RoutingStrategyType) error {
	sm.mu.RLock()
	_, exists := sm.routingStrategies[strategyType]
	sm.mu.RUnlock()

	if !exists {
		return fmt.Errorf("routing strategy not found: %s", strategyType)
	}

	sm.mu.Lock()
	sm.defaultRouting = strategyType
	sm.mu.Unlock()
	return nil
}

func (sm *StrategyManager) GetDefaultRoutingStrategy() contracts.RoutingStrategy {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.routingStrategies[sm.defaultRouting]
}

func (sm *StrategyManager) RegisterRetryStrategy(strategy contracts.RetryStrategy) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.retryStrategies[strategy.GetType()] = strategy
}

func (sm *StrategyManager) GetRetryStrategy(strategyType contracts.RetryStrategyType) (contracts.RetryStrategy, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	strategy, exists := sm.retryStrategies[strategyType]
	if !exists {
		return nil, fmt.Errorf("retry strategy not found: %s", strategyType)
	}
	return strategy, nil
}

func (sm *StrategyManager) ListRetryStrategies() []contracts.RetryStrategyType {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	types := make([]contracts.RetryStrategyType, 0, len(sm.retryStrategies))
	for t := range sm.retryStrategies {
		types = append(types, t)
	}
	return types
}

func (sm *StrategyManager) SetDefaultRetryStrategy(strategyType contracts.RetryStrategyType) error {
	sm.mu.RLock()
	_, exists := sm.retryStrategies[strategyType]
	sm.mu.RUnlock()

	if !exists {
		return fmt.Errorf("retry strategy not found: %s", strategyType)
	}

	sm.mu.Lock()
	sm.defaultRetry = strategyType
	sm.mu.Unlock()
	return nil
}

func (sm *StrategyManager) GetDefaultRetryStrategy() contracts.RetryStrategy {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.retryStrategies[sm.defaultRetry]
}

func (sm *StrategyManager) RegisterFallbackStrategy(strategy contracts.FallbackStrategy) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.fallbackStrategies[strategy.GetType()] = strategy
}

func (sm *StrategyManager) GetFallbackStrategy(strategyType contracts.FallbackStrategyType) (contracts.FallbackStrategy, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	strategy, exists := sm.fallbackStrategies[strategyType]
	if !exists {
		return nil, fmt.Errorf("fallback strategy not found: %s", strategyType)
	}
	return strategy, nil
}

func (sm *StrategyManager) ListFallbackStrategies() []contracts.FallbackStrategyType {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	types := make([]contracts.FallbackStrategyType, 0, len(sm.fallbackStrategies))
	for t := range sm.fallbackStrategies {
		types = append(types, t)
	}
	return types
}

func (sm *StrategyManager) SetDefaultFallbackStrategy(strategyType contracts.FallbackStrategyType) error {
	sm.mu.RLock()
	_, exists := sm.fallbackStrategies[strategyType]
	sm.mu.RUnlock()

	if !exists {
		return fmt.Errorf("fallback strategy not found: %s", strategyType)
	}

	sm.mu.Lock()
	sm.defaultFallback = strategyType
	sm.mu.Unlock()
	return nil
}

func (sm *StrategyManager) GetDefaultFallbackStrategy() contracts.FallbackStrategy {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.fallbackStrategies[sm.defaultFallback]
}
