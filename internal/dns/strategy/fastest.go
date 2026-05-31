package strategy

import (
	"context"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
)

type FastestStrategy struct {
	upstreamMgr ports.DNSUpstreamManager
}

func NewFastestStrategy(upstreamMgr ports.DNSUpstreamManager) ports.DNSResolveStrategy {
	return &FastestStrategy{
		upstreamMgr: upstreamMgr,
	}
}

func (s *FastestStrategy) Select(ctx context.Context, upstreams []*domain.DNSUpstream) *domain.DNSUpstream {
	if len(upstreams) == 0 {
		return nil
	}

	var fastest *domain.DNSUpstream
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

func (s *FastestStrategy) Type() domain.StrategyType {
	return domain.StrategyFastest
}
