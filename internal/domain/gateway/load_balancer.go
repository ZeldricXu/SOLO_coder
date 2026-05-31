package gateway

import (
	"context"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
)

type RoundRobinLoadBalancer struct {
	counter atomic.Uint64
}

func NewRoundRobinLoadBalancer() *RoundRobinLoadBalancer {
	return &RoundRobinLoadBalancer{}
}

func (lb *RoundRobinLoadBalancer) Select(ctx context.Context, providers []domain.ModelProvider) (domain.ModelProvider, error) {
	if len(providers) == 0 {
		return nil, errors.New(errors.ErrCodeUnavailable, "no providers available")
	}

	healthyProviders := make([]domain.ModelProvider, 0)
	for _, p := range providers {
		if p.Healthy(ctx) {
			healthyProviders = append(healthyProviders, p)
		}
	}

	if len(healthyProviders) == 0 {
		return providers[0], nil
	}

	idx := lb.counter.Add(1) % uint64(len(healthyProviders))
	return healthyProviders[idx], nil
}

func (lb *RoundRobinLoadBalancer) RecordSuccess(provider domain.ModelProvider) {}

func (lb *RoundRobinLoadBalancer) RecordFailure(provider domain.ModelProvider) {}

type WeightedLoadBalancer struct {
	weights map[string]int
	mu      sync.RWMutex
}

func NewWeightedLoadBalancer() *WeightedLoadBalancer {
	return &WeightedLoadBalancer{
		weights: make(map[string]int),
	}
}

func (lb *WeightedLoadBalancer) SetWeight(providerName string, weight int) {
	lb.mu.Lock()
	defer lb.mu.Unlock()
	lb.weights[providerName] = weight
}

func (lb *WeightedLoadBalancer) Select(ctx context.Context, providers []domain.ModelProvider) (domain.ModelProvider, error) {
	if len(providers) == 0 {
		return nil, errors.New(errors.ErrCodeUnavailable, "no providers available")
	}

	lb.mu.RLock()
	defer lb.mu.RUnlock()

	type weighted struct {
		provider domain.ModelProvider
		weight   int
	}

	var healthy []weighted
	totalWeight := 0

	for _, p := range providers {
		if p.Healthy(ctx) {
			w := lb.weights[p.Name()]
			if w <= 0 {
				w = 1
			}
			healthy = append(healthy, weighted{provider: p, weight: w})
			totalWeight += w
		}
	}

	if len(healthy) == 0 {
		return nil, errors.New(errors.ErrCodeUnavailable, "no healthy providers")
	}

	r := time.Now().UnixNano() % int64(totalWeight)
	cumulative := 0
	for _, w := range healthy {
		cumulative += w.weight
		if int64(cumulative) > r {
			return w.provider, nil
		}
	}

	return healthy[0].provider, nil
}

func (lb *WeightedLoadBalancer) RecordSuccess(provider domain.ModelProvider) {}

func (lb *WeightedLoadBalancer) RecordFailure(provider domain.ModelProvider) {}

type CircuitBreaker struct {
	state     CircuitBreakerState
	failures  int
	threshold int
	timeout   time.Duration
	lastFail  time.Time
	mu        sync.RWMutex
}

func NewCircuitBreaker(threshold int, timeout time.Duration) *CircuitBreaker {
	return &CircuitBreaker{
		state:     CircuitClosed,
		threshold: threshold,
		timeout:   timeout,
	}
}

func (cb *CircuitBreaker) Allow() bool {
	cb.mu.RLock()
	defer cb.mu.RUnlock()

	switch cb.state {
	case CircuitOpen:
		if time.Since(cb.lastFail) > cb.timeout {
			cb.mu.RUnlock()
			cb.mu.Lock()
			if cb.state == CircuitOpen {
				cb.state = CircuitHalfOpen
			}
			cb.mu.Unlock()
			cb.mu.RLock()
		}
		return cb.state != CircuitOpen
	case CircuitHalfOpen:
		return true
	default:
		return true
	}
}

func (cb *CircuitBreaker) RecordSuccess() {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	cb.failures = 0
	cb.state = CircuitClosed
}

func (cb *CircuitBreaker) RecordFailure() {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	cb.failures++
	cb.lastFail = time.Now()
	if cb.failures >= cb.threshold {
		cb.state = CircuitOpen
	}
}

func (cb *CircuitBreaker) State() CircuitBreakerState {
	cb.mu.RLock()
	defer cb.mu.RUnlock()
	return cb.state
}
