package strategy

import (
	"context"
	"sync"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
)

type FailoverStrategy struct {
	mu      sync.Mutex
	lastIdx int
}

func NewFailoverStrategy() ports.DNSResolveStrategy {
	return &FailoverStrategy{}
}

func (s *FailoverStrategy) Select(ctx context.Context, upstreams []*domain.DNSUpstream) *domain.DNSUpstream {
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

func (s *FailoverStrategy) Type() domain.StrategyType {
	return domain.StrategyFailover
}
