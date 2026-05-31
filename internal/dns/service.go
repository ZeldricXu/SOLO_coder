package dns

import (
	"context"
	"fmt"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"github.com/chaoslab/platform/internal/dns/cache"
	"go.uber.org/zap"
)

type ProxyService struct {
	upstreamMgr     ports.DNSUpstreamManager
	cache           ports.DNSCache
	multiLevelCache ports.MultiLevelDNSCache
	strategyFactory ports.DNSStrategyFactory
	maxRetries      int
	logger          *zap.Logger
}

func NewProxyService(
	upstreamMgr ports.DNSUpstreamManager,
	cache ports.DNSCache,
	strategyFactory ports.DNSStrategyFactory,
	maxRetries int,
	logger *zap.Logger,
) ports.DNSProxyService {
	if logger == nil {
		logger = zap.NewNop()
	}
	svc := &ProxyService{
		upstreamMgr:     upstreamMgr,
		cache:           cache,
		strategyFactory: strategyFactory,
		maxRetries:      maxRetries,
		logger:          logger,
	}
	if mlc, ok := cache.(ports.MultiLevelDNSCache); ok {
		svc.multiLevelCache = mlc
	}
	return svc
}

func NewProxyServiceWithMultiLevelCache(
	upstreamMgr ports.DNSUpstreamManager,
	multiLevelCache ports.MultiLevelDNSCache,
	strategyFactory ports.DNSStrategyFactory,
	maxRetries int,
	logger *zap.Logger,
) ports.DNSProxyService {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &ProxyService{
		upstreamMgr:     upstreamMgr,
		cache:           multiLevelCache,
		multiLevelCache: multiLevelCache,
		strategyFactory: strategyFactory,
		maxRetries:      maxRetries,
		logger:          logger,
	}
}

func (s *ProxyService) Resolve(ctx context.Context, domain string, recordType string) (*domain.DNSResponse, error) {
	if domain == "" {
		return nil, fmt.Errorf("domain is required")
	}
	if recordType == "" {
		recordType = "A"
	}

	cacheKey := cache.GenerateKey(domain, recordType)

	if s.multiLevelCache != nil {
		if resp, tier, hit := s.multiLevelCache.GetWithTier(ctx, cacheKey); hit {
			resp.CacheHit = true
			resp.CacheTier = string(tier)
			s.logger.Debug("dns multilevel cache hit",
				zap.String("domain", domain),
				zap.String("record_type", recordType),
				zap.String("tier", string(tier)),
			)
			return resp, nil
		}
	} else {
		if resp, hit := s.cache.Get(ctx, cacheKey); hit {
			resp.CacheHit = true
			s.logger.Debug("dns cache hit",
				zap.String("domain", domain),
				zap.String("record_type", recordType),
			)
			return resp, nil
		}
	}

	start := time.Now()
	resp, err := s.resolveWithRetry(ctx, domain, recordType)
	if err != nil {
		return nil, err
	}
	resp.LatencyMs = time.Since(start).Milliseconds()

	ttl := time.Duration(resp.TTL) * time.Second
	if s.multiLevelCache != nil {
		s.multiLevelCache.Set(ctx, cacheKey, resp, ttl)
	} else {
		s.cache.Set(ctx, cacheKey, resp, ttl)
	}

	return resp, nil
}

func (s *ProxyService) resolveWithRetry(ctx context.Context, domain string, recordType string) (*domain.DNSResponse, error) {
	var lastErr error

	for attempt := 0; attempt < s.maxRetries; attempt++ {
		upstreams := s.upstreamMgr.GetEnabled(ctx)
		if len(upstreams) == 0 {
			return nil, fmt.Errorf("no enabled DNS upstreams available")
		}

		strategy := s.strategyFactory.CurrentStrategy()
		upstream := strategy.Select(ctx, upstreams)
		if upstream == nil {
			return nil, fmt.Errorf("failed to select upstream")
		}

		resp, err := s.upstreamMgr.Query(ctx, upstream, domain, recordType)
		if err == nil {
			return resp, nil
		}

		lastErr = err
		s.logger.Warn("dns resolve attempt failed",
			zap.String("domain", domain),
			zap.String("upstream", upstream.Name),
			zap.Int("attempt", attempt+1),
			zap.Error(err),
		)

		select {
		case <-ctx.Done():
			return nil, fmt.Errorf("dns resolution cancelled")
		case <-time.After(time.Duration(100*(attempt+1)) * time.Millisecond):
		}
	}

	return nil, fmt.Errorf("dns resolution failed after %d attempts: %w", s.maxRetries, lastErr)
}

func (s *ProxyService) AddUpstream(ctx context.Context, upstream *domain.DNSUpstream) error {
	return s.upstreamMgr.Add(ctx, upstream)
}

func (s *ProxyService) RemoveUpstream(ctx context.Context, name string) error {
	return s.upstreamMgr.Remove(ctx, name)
}

func (s *ProxyService) GetUpstreams(ctx context.Context) ([]*domain.DNSUpstream, error) {
	return s.upstreamMgr.List(ctx)
}

func (s *ProxyService) ClearCache(ctx context.Context) error {
	s.cache.Clear(ctx)
	return nil
}

func (s *ProxyService) GetCacheStats(ctx context.Context) (*domain.CacheStats, error) {
	return s.cache.Stats(ctx), nil
}

func (s *ProxyService) SetStrategy(ctx context.Context, strategyType domain.StrategyType) error {
	if err := s.strategyFactory.SetStrategy(strategyType); err != nil {
		return err
	}
	s.logger.Info("dns strategy updated",
		zap.String("strategy", string(strategyType)),
	)
	return nil
}

func (s *ProxyService) WarmupCache(ctx context.Context, req *domain.CacheWarmupRequest) (*domain.MultiLevelCacheStats, error) {
	if s.multiLevelCache == nil {
		return nil, fmt.Errorf("multilevel cache not enabled")
	}
	return s.multiLevelCache.Warmup(ctx, req, s)
}

func (s *ProxyService) InvalidateCache(ctx context.Context, req *domain.CacheInvalidationRequest) error {
	if s.multiLevelCache == nil {
		for _, key := range req.Keys {
			s.cache.Delete(ctx, key)
		}
		for _, d := range req.Domains {
			recordType := req.RecordType
			if recordType == "" {
				recordType = "A"
			}
			key := cache.GenerateKey(d, recordType)
			s.cache.Delete(ctx, key)
		}
		return nil
	}
	return s.multiLevelCache.Invalidate(ctx, req)
}

func (s *ProxyService) GetMultiLevelCacheStats(ctx context.Context) (*domain.MultiLevelCacheStats, error) {
	if s.multiLevelCache == nil {
		stats := s.cache.Stats(ctx)
		return &domain.MultiLevelCacheStats{
			L1: stats,
			L2: &domain.CacheStats{},
		}, nil
	}
	return s.multiLevelCache.MultiLevelStats(ctx), nil
}

func (s *ProxyService) GetMultiLevelCache() ports.MultiLevelDNSCache {
	return s.multiLevelCache
}
