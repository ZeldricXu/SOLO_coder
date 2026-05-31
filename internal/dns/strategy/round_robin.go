package strategy

import (
	"context"
	"sync"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
)

type RoundRobinStrategy struct {
	mu      sync.Mutex
	counter int
}

func NewRoundRobinStrategy() ports.DNSResolveStrategy {
	return &RoundRobinStrategy{}
}

func (s *RoundRobinStrategy) Select(ctx context.Context, upstreams []*domain.DNSUpstream) *domain.DNSUpstream {
	if len(upstreams) == 0 {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	idx := s.counter % len(upstreams)
	s.counter++
	return upstreams[idx]
}

func (s *RoundRobinStrategy) Type() domain.StrategyType {
	return domain.StrategyRoundRobin
}
