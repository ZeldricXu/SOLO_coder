package differentialprivacy

import (
	"context"
	"fmt"
	"sync"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"go.uber.org/zap"
)

type StrategyChangeListener func(oldStrategy, newStrategy string)

type StrategyManager struct {
	strategies  map[string]PrivacyStrategy
	active      string
	listeners   []StrategyChangeListener
	logger      *zap.Logger
	mu          sync.RWMutex
}

func NewStrategyManager(logger *zap.Logger) *StrategyManager {
	m := &StrategyManager{
		strategies: make(map[string]PrivacyStrategy),
		logger:     logger,
		listeners:  make([]StrategyChangeListener, 0),
	}

	m.registerDefaultStrategies()
	m.active = "balanced"

	return m
}

func (m *StrategyManager) registerDefaultStrategies() {
	m.strategies["strict"] = NewStrictPrivacyStrategy()
	m.strategies["balanced"] = NewBalancedPrivacyStrategy()
	m.strategies["relaxed"] = NewRelaxedPrivacyStrategy()
	m.strategies["adaptive"] = NewAdaptivePrivacyStrategy()
}

func (m *StrategyManager) Register(strategy PrivacyStrategy) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	name := strategy.Name()
	if _, exists := m.strategies[name]; exists {
		return fmt.Errorf("strategy %s already exists", name)
	}

	if err := strategy.ValidateConfig(); err != nil {
		return fmt.Errorf("invalid strategy config: %w", err)
	}

	m.strategies[name] = strategy
	m.logger.Info("Privacy strategy registered", zap.String("strategy", name))
	return nil
}

func (m *StrategyManager) Unregister(name string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.strategies[name]; !exists {
		return
	}

	if m.active == name {
		m.active = "balanced"
		m.logger.Info("Active strategy unregistered, switching to balanced", zap.String("strategy", name))
	}

	delete(m.strategies, name)
	m.logger.Info("Privacy strategy unregistered", zap.String("strategy", name))
}

func (m *StrategyManager) SetActive(name string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.strategies[name]; !exists {
		return fmt.Errorf("strategy %s not found", name)
	}

	oldStrategy := m.active
	m.active = name

	m.logger.Info("Privacy strategy changed",
		zap.String("old", oldStrategy),
		zap.String("new", name),
	)

	m.notifyListeners(oldStrategy, name)

	return nil
}

func (m *StrategyManager) GetActive() PrivacyStrategy {
	m.mu.RLock()
	defer m.mu.RUnlock()

	strategy, exists := m.strategies[m.active]
	if !exists {
		return m.strategies["balanced"]
	}
	return strategy
}

func (m *StrategyManager) Get(name string) (PrivacyStrategy, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	strategy, exists := m.strategies[name]
	return strategy, exists
}

func (m *StrategyManager) List() []string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	names := make([]string, 0, len(m.strategies))
	for name := range m.strategies {
		names = append(names, name)
	}
	return names
}

func (m *StrategyManager) Process(ctx context.Context, result *interfaces.QueryResult, noiseGen NoiseGenerator) (*interfaces.QueryResult, error) {
	strategy := m.GetActive()
	return strategy.Process(ctx, result, noiseGen)
}

func (m *StrategyManager) AddListener(listener StrategyChangeListener) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.listeners = append(m.listeners, listener)
}

func (m *StrategyManager) notifyListeners(oldStrategy, newStrategy string) {
	for _, listener := range m.listeners {
		go listener(oldStrategy, newStrategy)
	}
}

func (m *StrategyManager) GetActiveName() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.active
}
