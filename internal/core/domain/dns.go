package domain

import "time"

type DNSUpstream struct {
	Name      string `json:"name"`
	Address   string `json:"address"`
	Port      int    `json:"port"`
	Weight    int    `json:"weight"`
	Enabled   bool   `json:"enabled"`
	TimeoutMs int    `json:"timeout_ms"`
}

type DNSResponse struct {
	Domain    string   `json:"domain"`
	Records   []string `json:"records"`
	TTL       int      `json:"ttl"`
	Upstream  string   `json:"upstream"`
	CacheHit  bool     `json:"cache_hit"`
	CacheTier string   `json:"cache_tier,omitempty"`
	LatencyMs int64    `json:"latency_ms"`
}

type MultiLevelCacheStats struct {
	L1 *CacheStats `json:"l1"`
	L2 *CacheStats `json:"l2"`
}

type CacheInvalidationStrategy string

const (
	CacheInvalidationTTL       CacheInvalidationStrategy = "ttl"
	CacheInvalidationWriteBack CacheInvalidationStrategy = "write_back"
	CacheInvalidationEvent     CacheInvalidationStrategy = "event"
)

type CacheWarmupRequest struct {
	Domains    []string `json:"domains"`
	RecordType string   `json:"record_type"`
	TTL        int      `json:"ttl"`
}

type CacheInvalidationRequest struct {
	Keys       []string `json:"keys"`
	Domains    []string `json:"domains"`
	RecordType string   `json:"record_type"`
	Strategy   CacheInvalidationStrategy `json:"strategy"`
}

type CacheTier string

const (
	CacheTierL1 CacheTier = "l1"
	CacheTierL2 CacheTier = "l2"
)

type TimingMetric struct {
	Name      string        `json:"name"`
	Duration  time.Duration `json:"duration"`
	Timestamp time.Time     `json:"timestamp"`
	Labels    map[string]string `json:"labels,omitempty"`
}
