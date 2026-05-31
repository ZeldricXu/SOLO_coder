package dns

import (
	"context"
	"math/rand"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/common"
)

type ResolveStrategy interface {
	Select(ctx context.Context, upstreams []*common.DNSUpstream) *common.DNSUpstream
	Type() common.StrategyType
}

type RoundRobinStrategy struct {
	mu      sync.Mutex
	counter int
}

func NewRoundRobinStrategy() *RoundRobinStrategy {
	return &RoundRobinStrategy{}
}

func (s *RoundRobinStrategy) Select(ctx context.Context, upstreams []*common.DNSUpstream) *common.DNSUpstream {
	if len(upstreams) == 0 {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	idx := s.counter % len(upstreams)
	s.counter++
	return upstreams[idx]
}

func (s *RoundRobinStrategy) Type() common.StrategyType {
	return common.StrategyRoundRobin
}

type FastestStrategy struct {
	upstreamMgr *UpstreamManager
}

func NewFastestStrategy(upstreamMgr *UpstreamManager) *FastestStrategy {
	return &FastestStrategy{
		upstreamMgr: upstreamMgr,
	}
}

func (s *FastestStrategy) Select(ctx context.Context, upstreams []*common.DNSUpstream) *common.DNSUpstream {
	if len(upstreams) == 0 {
		return nil
	}

	var fastest *common.DNSUpstream
	var minLatency time.Duration

	for _, u := range upstreams {
		latency := s.upstreamMgr.GetLatency(u.Name)
		if fastest == nil || latency < minLatency {
			fastest = u
			minLatency = latency
		}
	}

	return fastest
}

func (s *FastestStrategy) Type() common.StrategyType {
	return common.StrategyFastest
}

type FailoverStrategy struct {
	mu      sync.Mutex
	lastIdx int
}

func NewFailoverStrategy() *FailoverStrategy {
	return &FailoverStrategy{}
}

func (s *FailoverStrategy) Select(ctx context.Context, upstreams []*common.DNSUpstream) *common.DNSUpstream {
	if len(upstreams) == 0 {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.lastIdx < len(upstreams) {
		return upstreams[s.lastIdx]
	}
	s.lastIdx = 0
	return upstreams[0]
}

func (s *FailoverStrategy) MarkFailed(idx int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.lastIdx = (idx + 1) % 1000
}

func (s *FailoverStrategy) Type() common.StrategyType {
	return common.StrategyFailover
}

type WeightedStrategy struct {
	rng *rand.Rand
	mu  sync.Mutex
}

func NewWeightedStrategy() *WeightedStrategy {
	return &WeightedStrategy{
		rng: rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

func (s *WeightedStrategy) Select(ctx context.Context, upstreams []*common.DNSUpstream) *common.DNSUpstream {
	if len(upstreams) == 0 {
		return nil
	}

	totalWeight := 0
	for _, u := range upstreams {
		totalWeight += u.Weight
	}

	if totalWeight <= 0 {
		return upstreams[0]
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	r := s.rng.Intn(totalWeight)

	weightSum := 0
	for _, u := range upstreams {
		weightSum += u.Weight
		if r < weightSum {
			return u
		}
	}

	return upstreams[len(upstreams)-1]
}

func (s *WeightedStrategy) Type() common.StrategyType {
	return common.StrategyWeighted
}

type StrategyFactory struct {
	upstreamMgr *UpstreamManager
}

func NewStrategyFactory(upstreamMgr *UpstreamManager) *StrategyFactory {
	return &StrategyFactory{
		upstreamMgr: upstreamMgr,
	}
}

func (f *StrategyFactory) Create(strategyType common.StrategyType) (ResolveStrategy, error) {
	switch strategyType {
	case common.StrategyRoundRobin:
		return NewRoundRobinStrategy(), nil
	case common.StrategyFastest:
		return NewFastestStrategy(f.upstreamMgr), nil
	case common.StrategyFailover:
		return NewFailoverStrategy(), nil
	case common.StrategyWeighted:
		return NewWeightedStrategy(), nil
	default:
		return nil, common.NewValidationError("unsupported strategy", string(strategyType))
	}
}
