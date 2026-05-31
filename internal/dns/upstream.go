package dns

import (
	"context"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/common"
	"go.uber.org/zap"
)

type UpstreamManager struct {
	mu        sync.RWMutex
	upstreams map[string]*common.DNSUpstream
	latencies map[string]time.Duration
}

func NewUpstreamManager() *UpstreamManager {
	return &UpstreamManager{
		upstreams: make(map[string]*common.DNSUpstream),
		latencies: make(map[string]time.Duration),
	}
}

func (m *UpstreamManager) Add(ctx context.Context, upstream *common.DNSUpstream) error {
	if upstream == nil {
		return common.NewBadRequestError("upstream cannot be nil")
	}
	if upstream.Name == "" {
		return common.NewValidationError("upstream name is required", "name")
	}
	if upstream.Address == "" {
		return common.NewValidationError("upstream address is required", "address")
	}
	if upstream.Port <= 0 {
		upstream.Port = 53
	}
	if upstream.TimeoutMs <= 0 {
		upstream.TimeoutMs = 5000
	}
	if upstream.Weight <= 0 {
		upstream.Weight = 1
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.upstreams[upstream.Name]; exists {
		return common.NewConflictError(fmt.Sprintf("upstream %s already exists", upstream.Name))
	}

	m.upstreams[upstream.Name] = upstream
	m.latencies[upstream.Name] = 0

	common.Info("dns upstream added",
		zap.String("name", upstream.Name),
		zap.String("address", upstream.Address),
		zap.Int("port", upstream.Port),
	)

	return nil
}

func (m *UpstreamManager) Remove(ctx context.Context, name string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.upstreams[name]; !exists {
		return common.NewNotFoundError(fmt.Sprintf("upstream %s not found", name))
	}

	delete(m.upstreams, name)
	delete(m.latencies, name)

	common.Info("dns upstream removed",
		zap.String("name", name),
	)

	return nil
}

func (m *UpstreamManager) Get(ctx context.Context, name string) (*common.DNSUpstream, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	upstream, exists := m.upstreams[name]
	if !exists {
		return nil, common.NewNotFoundError(fmt.Sprintf("upstream %s not found", name))
	}
	return upstream, nil
}

func (m *UpstreamManager) List(ctx context.Context) ([]*common.DNSUpstream, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	list := make([]*common.DNSUpstream, 0, len(m.upstreams))
	for _, u := range m.upstreams {
		list = append(list, u)
	}
	return list, nil
}

func (m *UpstreamManager) GetEnabled(ctx context.Context) []*common.DNSUpstream {
	m.mu.RLock()
	defer m.mu.RUnlock()

	list := make([]*common.DNSUpstream, 0)
	for _, u := range m.upstreams {
		if u.Enabled {
			list = append(list, u)
		}
	}
	return list
}

func (m *UpstreamManager) Query(ctx context.Context, upstream *common.DNSUpstream, domain, recordType string) (*common.DNSResponse, error) {
	start := time.Now()

	timeout := time.Duration(upstream.TimeoutMs) * time.Millisecond
	queryCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	addr := fmt.Sprintf("%s:%d", upstream.Address, upstream.Port)
	d := net.Dialer{Timeout: timeout}
	conn, err := d.DialContext(queryCtx, "udp", addr)
	if err != nil {
		m.recordLatency(upstream.Name, time.Since(start))
		common.Warn("dns upstream connection failed",
			zap.String("upstream", upstream.Name),
			zap.String("address", addr),
			zap.Error(err),
		)
		return nil, common.NewRetryableError(fmt.Errorf("connect to upstream %s: %w", upstream.Name, err))
	}
	defer conn.Close()

	records, err := m.simulateDNSQuery(domain, recordType)
	if err != nil {
		m.recordLatency(upstream.Name, time.Since(start))
		return nil, err
	}

	latency := time.Since(start)
	m.recordLatency(upstream.Name, latency)

	return &common.DNSResponse{
		Domain:    domain,
		Records:   records,
		TTL:       300,
		Upstream:  upstream.Name,
		CacheHit:  false,
		LatencyMs: latency.Milliseconds(),
	}, nil
}

func (m *UpstreamManager) simulateDNSQuery(domain, recordType string) ([]string, error) {
	if recordType == "A" {
		return []string{"192.168.1.10", "192.168.1.11"}, nil
	}
	if recordType == "AAAA" {
		return []string{"::1"}, nil
	}
	if recordType == "CNAME" {
		return []string{domain + ".cdn.example.com"}, nil
	}
	if recordType == "MX" {
		return []string{"10 mail." + domain}, nil
	}
	return []string{}, nil
}

func (m *UpstreamManager) recordLatency(name string, latency time.Duration) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.latencies[name] = latency
}

func (m *UpstreamManager) GetLatency(name string) time.Duration {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.latencies[name]
}
