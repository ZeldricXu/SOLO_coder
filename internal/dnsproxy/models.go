package dnsproxy

import (
	"time"
)

type RecordType int

const (
	TypeA    RecordType = 1
	TypeAAAA RecordType = 28
	TypeCNAME RecordType = 5
	TypeMX   RecordType = 15
	TypeTXT  RecordType = 16
)

type DnsUpstream struct {
	ID         string        `json:"id"`
	Name       string        `json:"name"`
	Host       string        `json:"host"`
	Port       int           `json:"port"`
	Priority   int           `json:"priority"`
	Weight     int           `json:"weight"`
	Protocol   string        `json:"protocol"`
	Enabled    bool          `json:"enabled"`
	TimeoutMs  int           `json:"timeout_ms"`
	MaxRetries int           `json:"max_retries"`
	CreatedAt  time.Time     `json:"created_at"`
	UpdatedAt  time.Time     `json:"updated_at"`
}

type DnsCacheEntry struct {
	ID         string      `json:"id"`
	Domain     string      `json:"domain"`
	RecordType RecordType  `json:"record_type"`
	RecordData []string    `json:"record_data"`
	TTL        int64       `json:"ttl"`
	ExpiresAt  time.Time   `json:"expires_at"`
	CreatedAt  time.Time   `json:"created_at"`
	HitCount   int64       `json:"hit_count"`
}

type DnsResolveRequest struct {
	Domain     string      `json:"domain"`
	RecordType RecordType  `json:"record_type"`
	SkipCache  bool        `json:"skip_cache"`
	ClientIP   string      `json:"client_ip"`
	TraceID    string      `json:"trace_id"`
}

type DnsResolveResponse struct {
	Domain        string    `json:"domain"`
	RecordType    RecordType `json:"record_type"`
	Records       []string  `json:"records"`
	TTL           int64     `json:"ttl"`
	FromCache     bool      `json:"from_cache"`
	UpstreamUsed  string    `json:"upstream_used"`
	ResolveTimeMs int64     `json:"resolve_time_ms"`
	ResolvedAt    time.Time `json:"resolved_at"`
	TraceID       string    `json:"trace_id"`
}

type BatchResolveRequest struct {
	Requests []DnsResolveRequest `json:"requests"`
}

type BatchResolveResponse struct {
	Responses []DnsResolveResponse `json:"responses"`
	TotalTime int64                `json:"total_time_ms"`
}

type StreamBatchConfig struct {
	BatchSize        int           `json:"batch_size"`
	FlushInterval    time.Duration `json:"flush_interval"`
	MaxConcurrency   int           `json:"max_concurrency"`
	TimeoutPerBatch  time.Duration `json:"timeout_per_batch"`
	RetryOnFailure   bool          `json:"retry_on_failure"`
	MaxRetries       int           `json:"max_retries"`
}

type ResolveResult struct {
	Request  DnsResolveRequest
	Response *DnsResolveResponse
	Error    error
	Index    int
}

type ResolveProgress struct {
	Total      int
	Completed  int
	Failed     int
	InProgress int
	StartTime  time.Time
}
