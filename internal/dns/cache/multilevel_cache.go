package cache

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"go.uber.org/zap"
)

type MultiLevelCache struct {
	l1     ports.DNSCache
	l2     ports.DNSCache
	mu     sync.RWMutex
	logger *zap.Logger
}

func NewMultiLevelCache(l1, l2 ports.DNSCache, logger *zap.Logger) ports.MultiLevelDNSCache {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &MultiLevelCache{
		l1:     l1,
		l2:     l2,
		logger: logger,
	}
}

func (c *MultiLevelCache) Get(ctx context.Context, key string) (*domain.DNSResponse, bool) {
	resp, _, hit := c.GetWithTier(ctx, key)
	return resp, hit
}

func (c *MultiLevelCache) GetWithTier(ctx context.Context, key string) (*domain.DNSResponse, domain.CacheTier, bool) {
	if resp, hit := c.l1.Get(ctx, key); hit {
		resp.CacheTier = string(domain.CacheTierL1)
		c.logger.Debug("l1 cache hit",
			zap.String("key", key),
		)
		return resp, domain.CacheTierL1, true
	}

	if resp, hit := c.l2.Get(ctx, key); hit {
		resp.CacheTier = string(domain.CacheTierL2)
		c.logger.Debug("l2 cache hit, warming l1",
			zap.String("key", key),
		)
		ttl := time.Duration(resp.TTL) * time.Second
		c.l1.Set(ctx, key, resp, ttl)
		return resp, domain.CacheTierL2, true
	}

	return nil, "", false
}

func (c *MultiLevelCache) Set(ctx context.Context, key string, resp *domain.DNSResponse, ttl time.Duration) {
	c.SetWithTier(ctx, key, resp, ttl, domain.CacheTierL1)
	c.SetWithTier(ctx, key, resp, ttl, domain.CacheTierL2)
}

func (c *MultiLevelCache) SetWithTier(ctx context.Context, key string, resp *domain.DNSResponse, ttl time.Duration, tier domain.CacheTier) {
	switch tier {
	case domain.CacheTierL1:
		c.l1.Set(ctx, key, resp, ttl)
	case domain.CacheTierL2:
		c.l2.Set(ctx, key, resp, ttl)
	default:
		c.l1.Set(ctx, key, resp, ttl)
		c.l2.Set(ctx, key, resp, ttl)
	}
}

func (c *MultiLevelCache) Delete(ctx context.Context, key string) {
	c.l1.Delete(ctx, key)
	c.l2.Delete(ctx, key)
}

func (c *MultiLevelCache) Clear(ctx context.Context) {
	c.l1.Clear(ctx)
	c.l2.Clear(ctx)
}

func (c *MultiLevelCache) Stats(ctx context.Context) *domain.CacheStats {
	return c.l1.Stats(ctx)
}

func (c *MultiLevelCache) MultiLevelStats(ctx context.Context) *domain.MultiLevelCacheStats {
	return &domain.MultiLevelCacheStats{
		L1: c.l1.Stats(ctx),
		L2: c.l2.Stats(ctx),
	}
}

func (c *MultiLevelCache) Invalidate(ctx context.Context, req *domain.CacheInvalidationRequest) error {
	if req == nil {
		return fmt.Errorf("invalidation request is nil")
	}

	for _, key := range req.Keys {
		c.Delete(ctx, key)
		c.logger.Debug("cache invalidated by key",
			zap.String("key", key),
		)
	}

	for _, domain := range req.Domains {
		recordType := req.RecordType
		if recordType == "" {
			recordType = "A"
		}
		key := GenerateKey(domain, recordType)
		c.Delete(ctx, key)
		c.logger.Debug("cache invalidated by domain",
			zap.String("domain", domain),
			zap.String("record_type", recordType),
		)
	}

	c.logger.Info("cache invalidation completed",
		zap.Int("key_count", len(req.Keys)),
		zap.Int("domain_count", len(req.Domains)),
	)

	return nil
}

func (c *MultiLevelCache) Warmup(ctx context.Context, req *domain.CacheWarmupRequest, resolver ports.DNSResolver) (*domain.MultiLevelCacheStats, error) {
	if req == nil {
		return nil, fmt.Errorf("warmup request is nil")
	}
	if resolver == nil {
		return nil, fmt.Errorf("resolver is nil")
	}

	recordType := req.RecordType
	if recordType == "" {
		recordType = "A"
	}

	ttl := req.TTL
	if ttl <= 0 {
		ttl = 300
	}

	successCount := 0
	failCount := 0

	for _, domain := range req.Domains {
		resp, err := resolver.Resolve(ctx, domain, recordType)
		if err != nil {
			failCount++
			c.logger.Warn("cache warmup failed for domain",
				zap.String("domain", domain),
				zap.Error(err),
			)
			continue
		}

		key := GenerateKey(domain, recordType)
		c.Set(ctx, key, resp, time.Duration(ttl)*time.Second)
		successCount++
	}

	c.logger.Info("cache warmup completed",
		zap.Int("total", len(req.Domains)),
		zap.Int("success", successCount),
		zap.Int("failed", failCount),
	)

	return c.MultiLevelStats(ctx), nil
}
