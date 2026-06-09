package config

import (
	"encoding/json"
	"fmt"
	"strings"

	"DF1-56/internal/models"
)

type ConfigLoader struct {
	client *ETCDClient
}

func NewConfigLoader(client *ETCDClient) *ConfigLoader {
	return &ConfigLoader{
		client: client,
	}
}

func (l *ConfigLoader) LoadAll() (*GatewayConfig, error) {
	routes, err := l.LoadRoutes()
	if err != nil {
		return nil, fmt.Errorf("failed to load routes: %w", err)
	}

	rateLimits, err := l.LoadRateLimits()
	if err != nil {
		return nil, fmt.Errorf("failed to load rate limits: %w", err)
	}

	auths, err := l.LoadAuths()
	if err != nil {
		return nil, fmt.Errorf("failed to load auth policies: %w", err)
	}

	circuitBreakers, err := l.LoadCircuitBreakers()
	if err != nil {
		return nil, fmt.Errorf("failed to load circuit breakers: %w", err)
	}

	grays, err := l.LoadGrays()
	if err != nil {
		return nil, fmt.Errorf("failed to load gray policies: %w", err)
	}

	mirrors, err := l.LoadMirrors()
	if err != nil {
		return nil, fmt.Errorf("failed to load mirror policies: %w", err)
	}

	upstreams, err := l.LoadUpstreams()
	if err != nil {
		return nil, fmt.Errorf("failed to load upstreams: %w", err)
	}

	return &GatewayConfig{
		Routes:          routes,
		RateLimits:      rateLimits,
		Auths:           auths,
		CircuitBreakers: circuitBreakers,
		Grays:           grays,
		Mirrors:         mirrors,
		Upstreams:       upstreams,
	}, nil
}

func (l *ConfigLoader) LoadRoutes() (map[string]*models.Route, error) {
	kvs, err := l.client.List(EtcdKeyPrefixRoutes)
	if err != nil {
		return nil, err
	}

	routes := make(map[string]*models.Route, len(kvs))
	for key, value := range kvs {
		id := strings.TrimPrefix(key, EtcdKeyPrefixRoutes)
		if id == "" {
			continue
		}

		var route models.Route
		if err := json.Unmarshal(value, &route); err != nil {
			return nil, fmt.Errorf("failed to unmarshal route %s: %w", id, err)
		}
		if route.ID == "" {
			route.ID = id
		}
		routes[id] = &route
	}

	return routes, nil
}

func (l *ConfigLoader) LoadRateLimits() (map[string]*models.RateLimitPolicy, error) {
	kvs, err := l.client.List(EtcdKeyPrefixRateLimits)
	if err != nil {
		return nil, err
	}

	policies := make(map[string]*models.RateLimitPolicy, len(kvs))
	for key, value := range kvs {
		id := strings.TrimPrefix(key, EtcdKeyPrefixRateLimits)
		if id == "" {
			continue
		}

		var policy models.RateLimitPolicy
		if err := json.Unmarshal(value, &policy); err != nil {
			return nil, fmt.Errorf("failed to unmarshal rate limit policy %s: %w", id, err)
		}
		if policy.ID == "" {
			policy.ID = id
		}
		policies[id] = &policy
	}

	return policies, nil
}

func (l *ConfigLoader) LoadAuths() (map[string]*models.AuthPolicy, error) {
	kvs, err := l.client.List(EtcdKeyPrefixAuths)
	if err != nil {
		return nil, err
	}

	policies := make(map[string]*models.AuthPolicy, len(kvs))
	for key, value := range kvs {
		id := strings.TrimPrefix(key, EtcdKeyPrefixAuths)
		if id == "" {
			continue
		}

		var policy models.AuthPolicy
		if err := json.Unmarshal(value, &policy); err != nil {
			return nil, fmt.Errorf("failed to unmarshal auth policy %s: %w", id, err)
		}
		if policy.ID == "" {
			policy.ID = id
		}
		policies[id] = &policy
	}

	return policies, nil
}

func (l *ConfigLoader) LoadCircuitBreakers() (map[string]*models.CircuitBreakerPolicy, error) {
	kvs, err := l.client.List(EtcdKeyPrefixCircuitBreakers)
	if err != nil {
		return nil, err
	}

	policies := make(map[string]*models.CircuitBreakerPolicy, len(kvs))
	for key, value := range kvs {
		id := strings.TrimPrefix(key, EtcdKeyPrefixCircuitBreakers)
		if id == "" {
			continue
		}

		var policy models.CircuitBreakerPolicy
		if err := json.Unmarshal(value, &policy); err != nil {
			return nil, fmt.Errorf("failed to unmarshal circuit breaker policy %s: %w", id, err)
		}
		if policy.ID == "" {
			policy.ID = id
		}
		policies[id] = &policy
	}

	return policies, nil
}

func (l *ConfigLoader) LoadGrays() (map[string]*models.GrayPolicy, error) {
	kvs, err := l.client.List(EtcdKeyPrefixGrays)
	if err != nil {
		return nil, err
	}

	policies := make(map[string]*models.GrayPolicy, len(kvs))
	for key, value := range kvs {
		id := strings.TrimPrefix(key, EtcdKeyPrefixGrays)
		if id == "" {
			continue
		}

		var policy models.GrayPolicy
		if err := json.Unmarshal(value, &policy); err != nil {
			return nil, fmt.Errorf("failed to unmarshal gray policy %s: %w", id, err)
		}
		if policy.ID == "" {
			policy.ID = id
		}
		policies[id] = &policy
	}

	return policies, nil
}

func (l *ConfigLoader) LoadMirrors() (map[string]*models.MirrorPolicy, error) {
	kvs, err := l.client.List(EtcdKeyPrefixMirrors)
	if err != nil {
		return nil, err
	}

	policies := make(map[string]*models.MirrorPolicy, len(kvs))
	for key, value := range kvs {
		id := strings.TrimPrefix(key, EtcdKeyPrefixMirrors)
		if id == "" {
			continue
		}

		var policy models.MirrorPolicy
		if err := json.Unmarshal(value, &policy); err != nil {
			return nil, fmt.Errorf("failed to unmarshal mirror policy %s: %w", id, err)
		}
		if policy.ID == "" {
			policy.ID = id
		}
		policies[id] = &policy
	}

	return policies, nil
}

func (l *ConfigLoader) LoadUpstreams() (map[string]*models.UpstreamCluster, error) {
	kvs, err := l.client.List(EtcdKeyPrefixUpstreams)
	if err != nil {
		return nil, err
	}

	clusters := make(map[string]*models.UpstreamCluster, len(kvs))
	for key, value := range kvs {
		id := strings.TrimPrefix(key, EtcdKeyPrefixUpstreams)
		if id == "" {
			continue
		}

		var cluster models.UpstreamCluster
		if err := json.Unmarshal(value, &cluster); err != nil {
			return nil, fmt.Errorf("failed to unmarshal upstream cluster %s: %w", id, err)
		}
		if cluster.ID == "" {
			cluster.ID = id
		}
		clusters[id] = &cluster
	}

	return clusters, nil
}
