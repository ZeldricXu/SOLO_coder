package dns

import (
	"errors"
	"net"
	"sync"
	"time"

	"github.com/enterprise/config-platform/pkg/utils"
	"github.com/miekg/dns"
)

type UpstreamDNS struct {
	ID        string    `json:"id"`
	Address   string    `json:"address"`
	Port      int       `json:"port"`
	Priority  int       `json:"priority"`
	Weight    int       `json:"weight"`
	Enabled   bool      `json:"enabled"`
	Healthy   bool      `json:"healthy"`
	Latency   time.Duration `json:"latency"`
	CreatedAt time.Time `json:"created_at"`
}

type CacheEntry struct {
	Response   *dns.Msg
	ExpiresAt  time.Time
	AccessedAt time.Time
}

type ResolverStrategy string

const (
	StrategyRoundRobin ResolverStrategy = "round_robin"
	StrategyWeighted   ResolverStrategy = "weighted"
	StrategyLatency    ResolverStrategy = "latency"
	StrategyFailover   ResolverStrategy = "failover"
)

type Manager struct {
	upstreams map[string]*UpstreamDNS
	cache     map[string]*CacheEntry
	strategy  ResolverStrategy
	cacheTTL  time.Duration
	maxCache  int
	mu        sync.RWMutex
	rrCounter int
}

var (
	instance *Manager
	once     sync.Once
)

func GetManager() *Manager {
	once.Do(func() {
		instance = &Manager{
			upstreams: make(map[string]*UpstreamDNS),
			cache:     make(map[string]*CacheEntry),
			strategy:  StrategyRoundRobin,
			cacheTTL:  5 * time.Minute,
			maxCache:  10000,
		}
		go instance.startHealthChecks()
		go instance.startCacheCleanup()
	})
	return instance
}

func (m *Manager) startHealthChecks() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		m.checkAllUpstreams()
	}
}

func (m *Manager) startCacheCleanup() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		m.mu.Lock()
		now := time.Now()
		for key, entry := range m.cache {
			if now.After(entry.ExpiresAt) {
				delete(m.cache, key)
			}
		}
		if len(m.cache) > m.maxCache {
			m.evictOldest()
		}
		m.mu.Unlock()
	}
}

func (m *Manager) evictOldest() {
	var oldestKey string
	var oldestTime time.Time

	for key, entry := range m.cache {
		if oldestTime.IsZero() || entry.AccessedAt.Before(oldestTime) {
			oldestKey = key
			oldestTime = entry.AccessedAt
		}
	}
	if oldestKey != "" {
		delete(m.cache, oldestKey)
	}
}

func (m *Manager) checkAllUpstreams() {
	m.mu.RLock()
	upstreams := make([]*UpstreamDNS, 0, len(m.upstreams))
	for _, u := range m.upstreams {
		upstreams = append(upstreams, u)
	}
	m.mu.RUnlock()

	for _, u := range upstreams {
		start := time.Now()
		c := &dns.Client{Timeout: 2 * time.Second}
		m := new(dns.Msg)
		m.SetQuestion("google.com.", dns.TypeA)

		_, _, err := c.Exchange(m, net.JoinHostPort(u.Address, "53"))
		latency := time.Since(start)

		m.mu.Lock()
		u.Latency = latency
		u.Healthy = err == nil
		m.mu.Unlock()
	}
}

func (m *Manager) AddUpstream(address string, port, priority, weight int) (*UpstreamDNS, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	upstream := &UpstreamDNS{
		ID:        utils.GenerateID("dns"),
		Address:   address,
		Port:      port,
		Priority:  priority,
		Weight:    weight,
		Enabled:   true,
		Healthy:   true,
		CreatedAt: time.Now().UTC(),
	}

	m.upstreams[upstream.ID] = upstream
	return upstream, nil
}

func (m *Manager) RemoveUpstream(upstreamID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.upstreams[upstreamID]; !exists {
		return errors.New("upstream not found")
	}
	delete(m.upstreams, upstreamID)
	return nil
}

func (m *Manager) ListUpstreams() []*UpstreamDNS {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*UpstreamDNS, 0, len(m.upstreams))
	for _, u := range m.upstreams {
		result = append(result, u)
	}
	return result
}

func (m *Manager) SetStrategy(strategy ResolverStrategy) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.strategy = strategy
}

func (m *Manager) GetStrategy() ResolverStrategy {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.strategy
}

func (m *Manager) selectUpstream() *UpstreamDNS {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var healthy []*UpstreamDNS
	for _, u := range m.upstreams {
		if u.Enabled && u.Healthy {
			healthy = append(healthy, u)
		}
	}

	if len(healthy) == 0 {
		for _, u := range m.upstreams {
			if u.Enabled {
				return u
			}
		}
		return nil
	}

	switch m.strategy {
	case StrategyRoundRobin:
		m.rrCounter = (m.rrCounter + 1) % len(healthy)
		return healthy[m.rrCounter]
	case StrategyLatency:
		var best *UpstreamDNS
		for _, u := range healthy {
			if best == nil || u.Latency < best.Latency {
				best = u
			}
		}
		return best
	case StrategyWeighted:
		totalWeight := 0
		for _, u := range healthy {
			totalWeight += u.Weight
		}
		if totalWeight == 0 {
			return healthy[0]
		}
		return healthy[0]
	default:
		return healthy[0]
	}
}

func (m *Manager) Resolve(domain string, qType uint16) (*dns.Msg, error) {
	cacheKey := domain + ":" + string(rune(qType))

	m.mu.RLock()
	if entry, exists := m.cache[cacheKey]; exists {
		if time.Now().Before(entry.ExpiresAt) {
			entry.AccessedAt = time.Now()
			m.mu.RUnlock()
			return entry.Response, nil
		}
	}
	m.mu.RUnlock()

	upstream := m.selectUpstream()
	if upstream == nil {
		return nil, errors.New("no healthy upstream DNS servers")
	}

	c := &dns.Client{Timeout: 5 * time.Second}
	msg := new(dns.Msg)
	msg.SetQuestion(dns.Fqdn(domain), qType)

	resp, _, err := c.Exchange(msg, net.JoinHostPort(upstream.Address, "53"))
	if err != nil {
		return nil, err
	}

	m.mu.Lock()
	m.cache[cacheKey] = &CacheEntry{
		Response:   resp,
		ExpiresAt:  time.Now().Add(m.cacheTTL),
		AccessedAt: time.Now(),
	}
	m.mu.Unlock()

	return resp, nil
}

func (m *Manager) ClearCache() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.cache = make(map[string]*CacheEntry)
}

func (m *Manager) GetCacheStats() (int, int) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.cache), m.maxCache
}
