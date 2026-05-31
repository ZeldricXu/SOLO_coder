package dns

import (
	"math/rand"
	"net"
	"sort"
	"sync"
	"time"

	"github.com/parking-platform/platform/pkg/models"
	"github.com/parking-platform/platform/pkg/utils"
)

type Resolver interface {
	LookupHost(domain string) ([]string, error)
}

type SystemResolver struct{}

func (r *SystemResolver) LookupHost(domain string) ([]string, error) {
	return net.LookupHost(domain)
}

type DNSProxy struct {
	mu        sync.RWMutex
	upstreams map[string]*models.DNSUpstream
	cache     map[string]*models.DNSCacheEntry
	resolver  Resolver
}

func NewDNSProxy(resolver Resolver) *DNSProxy {
	if resolver == nil {
		resolver = &SystemResolver{}
	}
	return &DNSProxy{
		upstreams: make(map[string]*models.DNSUpstream),
		cache:     make(map[string]*models.DNSCacheEntry),
		resolver:  resolver,
	}
}

func (p *DNSProxy) AddUpstream(name, address string, priority int) *models.DNSUpstream {
	p.mu.Lock()
	defer p.mu.Unlock()
	u := &models.DNSUpstream{
		ID:       utils.GenerateID("upstream"),
		Name:     name,
		Address:  address,
		Priority: priority,
		Enabled:  true,
	}
	p.upstreams[u.ID] = u
	return u
}

func (p *DNSProxy) ListUpstreams() []*models.DNSUpstream {
	p.mu.RLock()
	defer p.mu.RUnlock()
	result := make([]*models.DNSUpstream, 0, len(p.upstreams))
	for _, u := range p.upstreams {
		result = append(result, u)
	}
	sort.Slice(result, func(i, j int) bool {
		return result[i].Priority < result[j].Priority
	})
	return result
}

func (p *DNSProxy) RemoveUpstream(id string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.upstreams, id)
}

func (p *DNSProxy) Resolve(domain string) ([]string, error) {
	p.mu.RLock()
	if entry, ok := p.cache[domain]; ok {
		if time.Now().Before(entry.ExpiresAt) {
			defer p.mu.RUnlock()
			return append([]string(nil), entry.Records...), nil
		}
	}
	p.mu.RUnlock()

	records, err := p.resolver.LookupHost(domain)
	if err != nil {
		return nil, err
	}

	entry := &models.DNSCacheEntry{
		Domain:    domain,
		Records:   append([]string(nil), records...),
		TTL:       300,
		ExpiresAt: time.Now().Add(300 * time.Second),
	}

	p.mu.Lock()
	p.cache[domain] = entry
	p.mu.Unlock()

	return records, nil
}

func (p *DNSProxy) SmartResolve(domain string) ([]string, error) {
	upstreams := p.ListUpstreams()
	for _, u := range upstreams {
		if !u.Enabled {
			continue
		}
		records, err := p.ResolveWithUpstream(domain, u)
		if err == nil {
			return records, nil
		}
	}
	return p.Resolve(domain)
}

func (p *DNSProxy) ResolveWithUpstream(domain string, upstream *models.DNSUpstream) ([]string, error) {
	return p.resolver.LookupHost(domain)
}

func (p *DNSProxy) InvalidateCache(domain string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.cache, domain)
}

func (p *DNSProxy) ClearCache() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.cache = make(map[string]*models.DNSCacheEntry)
}

func (p *DNSProxy) LoadBalancedResolve(domain string) ([]string, error) {
	records, err := p.Resolve(domain)
	if err != nil {
		return nil, err
	}
	if len(records) > 1 {
		rand.Shuffle(len(records), func(i, j int) {
			records[i], records[j] = records[j], records[i]
		})
	}
	return records, nil
}
