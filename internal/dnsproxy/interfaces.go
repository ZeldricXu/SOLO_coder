package dnsproxy

import "context"

type Resolver interface {
	Resolve(ctx context.Context, req DnsResolveRequest) (*DnsResolveResponse, error)
	ResolveBatch(ctx context.Context, requests []DnsResolveRequest) *BatchResolveResponse
	Start()
	Stop()
	GetProgress() *ResolveProgress
}

type UpstreamSelector interface {
	Select() *DnsUpstream
	AddUpstream(upstream *DnsUpstream)
	RemoveUpstream(id string)
	GetUpstream(id string) (*DnsUpstream, bool)
	GetEnabledUpstreams() []*DnsUpstream
}

type Cache interface {
	Get(domain string, recordType RecordType) (*DnsCacheEntry, bool)
	Put(domain string, recordType RecordType, records []string, ttl int64)
	Invalidate(domain string, recordType RecordType)
	CleanExpired() int
	GetStats() map[string]interface{}
}
