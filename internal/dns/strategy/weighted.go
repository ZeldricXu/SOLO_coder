package strategy

import (
	"context"
	"math/rand"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
)

type WeightedStrategy struct {
	rng *rand.Rand
	mu  sync.Mutex
}

func NewWeightedStrategy() ports.DNSResolveStrategy {
	return &WeightedStrategy{
		rng: rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

func (s *WeightedStrategy) Select(ctx context.Context, upstreams []*domain.DNSUpstream) *domain.DNSUpstream {
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

func (s *WeightedStrategy) Type() domain.StrategyType {
	return domain.StrategyWeighted
}
