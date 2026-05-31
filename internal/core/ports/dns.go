package ports

import (
	"context"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
)

type DNSUpstreamManager interface {
	Add(ctx context.Context, upstream *domain.DNSUpstream) error
	Remove(ctx context.Context, name string) error
	Get(ctx context.Context, name string) (*domain.DNSUpstream, error)
	List(ctx context.Context) ([]*domain.DNSUpstream, error)
	GetEnabled(ctx context.Context) []*domain.DNSUpstream
	Query(ctx context.Context, upstream *domain.DNSUpstream, domain, recordType string) (*domain.DNSResponse, error)
	GetLatency(name string) time.Duration
}

type DNSCache interface {
	Get(ctx context.Context, key string) (*domain.DNSResponse, bool)
	Set(ctx context.Context, key string, resp *domain.DNSResponse, ttl time.Duration)
	Delete(ctx context.Context, key string)
	Clear(ctx context.Context)
	Stats(ctx context.Context) *domain.CacheStats
}

type MultiLevelDNSCache interface {
	DNSCache
	GetWithTier(ctx context.Context, key string) (*domain.DNSResponse, domain.CacheTier, bool)
	SetWithTier(ctx context.Context, key string, resp *domain.DNSResponse, ttl time.Duration, tier domain.CacheTier)
	Invalidate(ctx context.Context, req *domain.CacheInvalidationRequest) error
	Warmup(ctx context.Context, req *domain.CacheWarmupRequest, resolver DNSResolver) (*domain.MultiLevelCacheStats, error)
	MultiLevelStats(ctx context.Context) *domain.MultiLevelCacheStats
}

type DNSCacheWarmer interface {
	Warmup(ctx context.Context, domains []string, recordType string) error
}

type DNSResolveStrategy interface {
	Select(ctx context.Context, upstreams []*domain.DNSUpstream) *domain.DNSUpstream
	Type() domain.StrategyType
}

type DNSResolver interface {
	Resolve(ctx context.Context, domain string, recordType string) (*domain.DNSResponse, error)
}

type DNSStrategyFactory interface {
	Create(strategyType domain.StrategyType) (DNSResolveStrategy, error)
	SetStrategy(strategyType domain.StrategyType) error
	CurrentStrategy() DNSResolveStrategy
}
