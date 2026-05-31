package dnsproxy

import (
	"context"
	"crypto/rand"
	"math/big"
	"sort"
	"sync"
	"time"

	"session130/internal/logger"
	"session130/internal/metrics"
)

type UpstreamManager struct {
	mu               sync.RWMutex
	upstreams        map[string]*DnsUpstream
	enabledCache      []*DnsUpstream
	prefixWeights    []int
	totalWeight      int
	needsRebuild     bool
}

var (
	upstreamInstance *UpstreamManager
	upstreamOnce     sync.Once
)

func NewUpstreamManager() *UpstreamManager {
	return &UpstreamManager{
		upstreams:    make(map[string]*DnsUpstream),
		enabledCache: make([]*DnsUpstream, 0),
		needsRebuild: true,
	}
}

func GetUpstreamManager() *UpstreamManager {
	upstreamOnce.Do(func() {
		upstreamInstance = NewUpstreamManager()
	})
	return upstreamInstance
}

func (m *UpstreamManager) AddUpstream(upstream *DnsUpstream) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.upstreams[upstream.ID] = upstream
	m.needsRebuild = true
	metrics.Inc("dns_upstreams_total", map[string]string{
		"name":    upstream.Name,
		"enabled": boolToString(upstream.Enabled),
	})
	logger.Info("", "DNS upstream added", map[string]interface{}{
		"id":   upstream.ID,
		"name": upstream.Name,
		"host": upstream.Host,
	})
}

func (m *UpstreamManager) RemoveUpstream(id string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if upstream, exists := m.upstreams[id]; exists {
		delete(m.upstreams, id)
		m.needsRebuild = true
		metrics.Inc("dns_upstreams_removed", map[string]string{
			"name": upstream.Name,
		})
		logger.Info("", "DNS upstream removed", map[string]interface{}{
			"id":   id,
			"name": upstream.Name,
		})
	}
}

func (m *UpstreamManager) GetUpstream(id string) (*DnsUpstream, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	upstream, exists := m.upstreams[id]
	return upstream, exists
}

func (m *UpstreamManager) GetEnabledUpstreams() []*DnsUpstream {
	m.mu.RLock()
	if !m.needsRebuild {
		result := make([]*DnsUpstream, len(m.enabledCache))
		copy(result, m.enabledCache)
		m.mu.RUnlock()
		return result
	}
	m.mu.RUnlock()

	m.mu.Lock()
	defer m.mu.Unlock()

	if !m.needsRebuild {
		result := make([]*DnsUpstream, len(m.enabledCache))
		copy(result, m.enabledCache)
		return result
	}

	m.rebuildEnabledCacheLocked()

	result := make([]*DnsUpstream, len(m.enabledCache))
	copy(result, m.enabledCache)
	return result
}

func (m *UpstreamManager) rebuildEnabledCacheLocked() {
	enabled := make([]*DnsUpstream, 0, len(m.upstreams))
	prefixWeights := make([]int, 0, len(m.upstreams))
	totalWeight := 0

	for _, u := range m.upstreams {
		if u.Enabled {
			enabled = append(enabled, u)
			totalWeight += u.Weight
			prefixWeights = append(prefixWeights, totalWeight)
		}
	}

	m.enabledCache = enabled
	m.prefixWeights = prefixWeights
	m.totalWeight = totalWeight
	m.needsRebuild = false
}

func (m *UpstreamManager) Select() *DnsUpstream {
	m.mu.RLock()
	if m.needsRebuild {
		m.mu.RUnlock()
		m.mu.Lock()
		m.rebuildEnabledCacheLocked()
		m.mu.Unlock()
		m.mu.RLock()
	}

	if len(m.enabledCache) == 0 {
		m.mu.RUnlock()
		return nil
	}

	if m.totalWeight <= 0 {
		result := m.enabledCache[0]
		m.mu.RUnlock()
		return result
	}

	n, _ := rand.Int(rand.Reader, big.NewInt(int64(m.totalWeight)))
	random := int(n.Int64())

	idx := sort.SearchInts(m.prefixWeights, random+1)
	if idx >= len(m.enabledCache) {
		idx = len(m.enabledCache) - 1
	}

	result := m.enabledCache[idx]
	m.mu.RUnlock()
	return result
}

func (m *UpstreamManager) Resolve(ctx context.Context, req DnsResolveRequest) (*DnsResolveResponse, error) {
	start := time.Now()
	upstream := m.Select()
	if upstream == nil {
		return nil, &ResolveError{Message: "no enabled upstreams available"}
	}

	timeout := resolveTimeout(upstream.TimeoutMs)

	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	response := &DnsResolveResponse{
		Domain:        req.Domain,
		RecordType:    req.RecordType,
		Records:       []string{generateMockIP(req.RecordType)},
		TTL:           300,
		FromCache:     false,
		UpstreamUsed:  upstream.Name,
		ResolveTimeMs: time.Since(start).Milliseconds(),
		ResolvedAt:    time.Now(),
		TraceID:       req.TraceID,
	}

	recordDNSResolutionMetrics(upstream.Name, req.RecordType, start)

	return response, nil
}

func resolveTimeout(timeoutMs int) time.Duration {
	if timeoutMs > 0 {
		return time.Duration(timeoutMs) * time.Millisecond
	}
	return 5 * time.Second
}

func recordDNSResolutionMetrics(upstreamName string, recordType RecordType, start time.Time) {
	metrics.Inc("dns_resolutions_total", map[string]string{
		"upstream": upstreamName,
		"type":     recordTypeToString(recordType),
		"status":   "success",
	})
	metrics.Observe("dns_resolution_duration_seconds", time.Since(start).Seconds(), map[string]string{
		"upstream": upstreamName,
	})
}

type ResolveError struct {
	Message string
}

func (e *ResolveError) Error() string {
	return e.Message
}

func generateMockIP(recordType RecordType) string {
	if recordType == TypeAAAA {
		return "2001:db8::1"
	}
	n1, _ := rand.Int(rand.Reader, big.NewInt(256))
	n2, _ := rand.Int(rand.Reader, big.NewInt(256))
	n3, _ := rand.Int(rand.Reader, big.NewInt(256))
	n4, _ := rand.Int(rand.Reader, big.NewInt(256))
	return n1.String() + "." + n2.String() + "." + n3.String() + "." + n4.String()
}

func boolToString(b bool) string {
	if b {
		return "true"
	}
	return "false"
}

func recordTypeToString(t RecordType) string {
	switch t {
	case TypeA:
		return "A"
	case TypeAAAA:
		return "AAAA"
	case TypeCNAME:
		return "CNAME"
	case TypeMX:
		return "MX"
	case TypeTXT:
		return "TXT"
	default:
		return "UNKNOWN"
	}
}
