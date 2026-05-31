package upstream

import (
	"context"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"go.uber.org/zap"
)

type Manager struct {
	mu        sync.RWMutex
	upstreams map[string]*domain.DNSUpstream
	latencies map[string]time.Duration
	logger    *zap.Logger
}

func NewManager(logger *zap.Logger) ports.DNSUpstreamManager {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &Manager{
		upstreams: make(map[string]*domain.DNSUpstream),
		latencies: make(map[string]time.Duration),
		logger:    logger,
	}
}

func (m *Manager) Add(ctx context.Context, upstream *domain.DNSUpstream) error {
	if upstream == nil {
		return fmt.Errorf("upstream cannot be nil")
	}
	if upstream.Name == "" {
		return fmt.Errorf("upstream name is required")
	}
	if upstream.Address == "" {
		return fmt.Errorf("upstream address is required")
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
		return fmt.Errorf("upstream %s already exists", upstream.Name)
	}

	m.upstreams[upstream.Name] = upstream
	m.latencies[upstream.Name] = 0

	m.logger.Info("dns upstream added",
		zap.String("name", upstream.Name),
		zap.String("address", upstream.Address),
		zap.Int("port", upstream.Port),
	)

	return nil
}

func (m *Manager) Remove(ctx context.Context, name string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.upstreams[name]; !exists {
		return fmt.Errorf("upstream %s not found", name)
	}

	delete(m.upstreams, name)
	delete(m.latencies, name)

	m.logger.Info("dns upstream removed",
		zap.String("name", name),
	)

	return nil
}

func (m *Manager) Get(ctx context.Context, name string) (*domain.DNSUpstream, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	upstream, exists := m.upstreams[name]
	if !exists {
		return nil, fmt.Errorf("upstream %s not found", name)
	}
	return upstream, nil
}

func (m *Manager) List(ctx context.Context) ([]*domain.DNSUpstream, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	list := make([]*domain.DNSUpstream, 0, len(m.upstreams))
	for _, u := range m.upstreams {
		list = append(list, u)
	}
	return list, nil
}

func (m *Manager) GetEnabled(ctx context.Context) []*domain.DNSUpstream {
	m.mu.RLock()
	defer m.mu.RUnlock()

	list := make([]*domain.DNSUpstream, 0)
	for _, u := range m.upstreams {
		if u.Enabled {
			list = append(list, u)
		}
	}
	return list
}

func (m *Manager) Query(ctx context.Context, upstream *domain.DNSUpstream, domain, recordType string) (*domain.DNSResponse, error) {
	start := time.Now()

	timeout := time.Duration(upstream.TimeoutMs) * time.Millisecond
	queryCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	addr := fmt.Sprintf("%s:%d", upstream.Address, upstream.Port)
	d := net.Dialer{Timeout: timeout}
	conn, err := d.DialContext(queryCtx, "udp", addr)
	if err != nil {
		m.recordLatency(upstream.Name, time.Since(start))
		m.logger.Warn("dns upstream connection failed",
			zap.String("upstream", upstream.Name),
			zap.String("address", addr),
			zap.Error(err),
		)
		return nil, fmt.Errorf("connect to upstream %s: %w", upstream.Name, err)
	}
	defer conn.Close()

	records, err := m.simulateDNSQuery(domain, recordType)
	if err != nil {
		m.recordLatency(upstream.Name, time.Since(start))
		return nil, err
	}

	latency := time.Since(start)
	m.recordLatency(upstream.Name, latency)

	return &domain.DNSResponse{
		Domain:    domain,
		Records:   records,
		TTL:       300,
		Upstream:  upstream.Name,
		CacheHit:  false,
		LatencyMs: latency.Milliseconds(),
	}, nil
}

func (m *Manager) simulateDNSQuery(domain, recordType string) ([]string, error) {
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

func (m *Manager) recordLatency(name string, latency time.Duration) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.latencies[name] = latency
}

func (m *Manager) GetLatency(name string) time.Duration {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.latencies[name]
}
