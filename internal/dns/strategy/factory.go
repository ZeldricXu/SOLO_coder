package strategy

import (
	"fmt"
	"sync"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
)

type Factory struct {
	mu            sync.RWMutex
	upstreamMgr   ports.DNSUpstreamManager
	current       ports.DNSResolveStrategy
	strategies    map[domain.StrategyType]func() ports.DNSResolveStrategy
}

func NewFactory(upstreamMgr ports.DNSUpstreamManager) ports.DNSStrategyFactory {
	f := &Factory{
		upstreamMgr: upstreamMgr,
		strategies:  make(map[domain.StrategyType]func() ports.DNSResolveStrategy),
	}

	f.register(domain.StrategyRoundRobin, func() ports.DNSResolveStrategy {
		return NewRoundRobinStrategy()
	})
	f.register(domain.StrategyFastest, func() ports.DNSResolveStrategy {
		return NewFastestStrategy(upstreamMgr)
	})
	f.register(domain.StrategyFailover, func() ports.DNSResolveStrategy {
		return NewFailoverStrategy()
	})
	f.register(domain.StrategyWeighted, func() ports.DNSResolveStrategy {
		return NewWeightedStrategy()
	})

	defaultStrategy, _ := f.Create(domain.StrategyRoundRobin)
	f.current = defaultStrategy

	return f
}

func (f *Factory) register(strategyType domain.StrategyType, factory func() ports.DNSResolveStrategy) {
	f.strategies[strategyType] = factory
}

func (f *Factory) Create(strategyType domain.StrategyType) (ports.DNSResolveStrategy, error) {
	factory, exists := f.strategies[strategyType]
	if !exists {
		return nil, fmt.Errorf("unsupported strategy: %s", strategyType)
	}
	return factory(), nil
}

func (f *Factory) SetStrategy(strategyType domain.StrategyType) error {
	strategy, err := f.Create(strategyType)
	if err != nil {
		return err
	}

	f.mu.Lock()
	defer f.mu.Unlock()
	f.current = strategy
	return nil
}

func (f *Factory) CurrentStrategy() ports.DNSResolveStrategy {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.current
}
