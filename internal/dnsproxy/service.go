package dnsproxy

import (
	"context"
	"sync"
	"time"
)

type ResolverService struct {
	upstreamManager *UpstreamManager
	cacheManager    *CacheManager
	streamResolver  Resolver
}

var (
	serviceInstance *ResolverService
	serviceOnce     sync.Once
)

func NewResolverService() *ResolverService {
	config := StreamBatchConfig{
		BatchSize:       100,
		FlushInterval:   100 * time.Millisecond,
		MaxConcurrency:  10,
		TimeoutPerBatch: 30 * time.Second,
		RetryOnFailure:  true,
		MaxRetries:      3,
	}

	return &ResolverService{
		upstreamManager: GetUpstreamManager(),
		cacheManager:    GetCacheManager(),
		streamResolver:  NewStreamResolver(config),
	}
}

func GetResolverService() *ResolverService {
	serviceOnce.Do(func() {
		serviceInstance = NewResolverService()
	})
	return serviceInstance
}

func (s *ResolverService) Start() {
	s.streamResolver.Start()
}

func (s *ResolverService) Stop() {
	s.streamResolver.Stop()
}

func (s *ResolverService) Resolve(ctx context.Context, req DnsResolveRequest) (*DnsResolveResponse, error) {
	return s.streamResolver.Resolve(ctx, req)
}

func (s *ResolverService) ResolveBatch(ctx context.Context, req BatchResolveRequest) *BatchResolveResponse {
	s.streamResolver.AddToTotal(len(req.Requests))
	return s.streamResolver.ResolveBatch(ctx, req.Requests)
}

func (s *ResolverService) GetUpstreamManager() *UpstreamManager {
	return s.upstreamManager
}

func (s *ResolverService) GetCacheManager() *CacheManager {
	return s.cacheManager
}

func (s *ResolverService) GetProgress() *ResolveProgress {
	return s.streamResolver.GetProgress()
}

func (m *UpstreamManager) SelectUpstream() *DnsUpstream {
	return m.Select()
}
