package ratelimit

import (
	"context"
	"fmt"
	"sync"
	"time"

	"DF1-56/internal/models"
)

type Manager struct {
	store             interface{}
	keyBuilder        *KeyBuilder
	tokenBucket       *TokenBucket
	slidingWindow     *SlidingWindow
	concurrency       *Concurrency
	mu                sync.Mutex
	releaseFuncs      map[string][]func()
	adaptiveManagers  map[string]*AdaptiveManager
	adaptiveMu        sync.RWMutex
	policies          map[string]*models.RateLimitPolicy
	policiesMu        sync.RWMutex
}

type Store interface {
	TokenBucketStore
	SlidingWindowStore
	ConcurrencyStore
}

func NewManager(redisClient RedisClient) *Manager {
	store := NewRedisStore(redisClient)
	return &Manager{
		store:            store,
		keyBuilder:       NewKeyBuilder(),
		tokenBucket:      NewTokenBucket(store),
		slidingWindow:    NewSlidingWindow(store),
		concurrency:      NewConcurrency(store),
		releaseFuncs:     make(map[string][]func()),
		adaptiveManagers: make(map[string]*AdaptiveManager),
		policies:         make(map[string]*models.RateLimitPolicy),
	}
}

func NewManagerWithStore(store Store) *Manager {
	return &Manager{
		store:            store,
		keyBuilder:       NewKeyBuilder(),
		tokenBucket:      NewTokenBucket(store),
		slidingWindow:    NewSlidingWindow(store),
		concurrency:      NewConcurrency(store),
		releaseFuncs:     make(map[string][]func()),
		adaptiveManagers: make(map[string]*AdaptiveManager),
		policies:         make(map[string]*models.RateLimitPolicy),
	}
}

func (m *Manager) Allow(ctx *models.GatewayContext, policy *models.RateLimitPolicy) (*models.RateLimitResult, error) {
	if policy == nil || !policy.Enabled {
		return &models.RateLimitResult{
			Allowed:   true,
			Limit:     0,
			Remaining: 0,
		}, nil
	}

	if len(policy.Rules) == 0 {
		return &models.RateLimitResult{
			Allowed:   true,
			Limit:     0,
			Remaining: 0,
		}, nil
	}

	m.mu.Lock()
	if m.releaseFuncs == nil {
		m.releaseFuncs = make(map[string][]func())
	}
	m.mu.Unlock()

	var finalResult *models.RateLimitResult
	var firstError error

	for _, rule := range policy.Rules {
		result, err := m.applyRule(ctx, policy, rule)
		if err != nil && firstError == nil {
			firstError = err
		}

		if result != nil {
			if finalResult == nil {
				finalResult = result
			} else {
				if !result.Allowed {
					if finalResult.Allowed {
						finalResult = result
					} else {
						if result.ResetAfter > finalResult.ResetAfter {
							finalResult.ResetAfter = result.ResetAfter
						}
						if result.Limit > 0 && (finalResult.Limit == 0 || result.Limit < finalResult.Limit) {
							finalResult.Limit = result.Limit
							finalResult.Remaining = result.Remaining
						}
						if result.Reason != "" {
							finalResult.Reason = result.Reason
						}
					}
				} else {
					if finalResult.Allowed {
						if result.Remaining < finalResult.Remaining {
							finalResult.Remaining = result.Remaining
						}
						if result.ResetAfter > finalResult.ResetAfter {
							finalResult.ResetAfter = result.ResetAfter
						}
						if result.Limit > 0 && (finalResult.Limit == 0 || result.Limit < finalResult.Limit) {
							finalResult.Limit = result.Limit
						}
					}
				}
			}
		}
	}

	if finalResult == nil {
		finalResult = &models.RateLimitResult{
			Allowed:   true,
			Limit:     0,
			Remaining: 0,
		}
	}

	if !finalResult.Allowed {
		m.releaseRequestResources(ctx.RequestID)
	}

	return finalResult, firstError
}

func (m *Manager) applyRule(ctx *models.GatewayContext, policy *models.RateLimitPolicy, rule models.RateLimitRule) (*models.RateLimitResult, error) {
	key := m.keyBuilder.BuildRuleKey(ctx, rule, policy.KeyBuilder)
	algorithm := policy.Algorithm

	if algorithm == models.AlgorithmMixed {
		algorithm = m.getDefaultAlgorithmForDimension(rule.Dimension)
	}

	switch algorithm {
	case models.AlgorithmTokenBucket:
		return m.applyTokenBucket(ctx, policy, key, rule)
	case models.AlgorithmSlidingWindow:
		return m.applySlidingWindow(ctx, policy, key, rule)
	case models.AlgorithmConcurrency:
		return m.applyConcurrency(ctx, policy, key, rule)
	default:
		return m.applyTokenBucket(ctx, policy, key, rule)
	}
}

func (m *Manager) getDefaultAlgorithmForDimension(dimension models.RateLimitDimension) models.RateLimitAlgorithm {
	switch dimension {
	case models.DimensionAPI, models.DimensionUser:
		return models.AlgorithmTokenBucket
	case models.DimensionIP:
		return models.AlgorithmSlidingWindow
	case models.DimensionCustom:
		return models.AlgorithmTokenBucket
	default:
		return models.AlgorithmTokenBucket
	}
}

func (m *Manager) applyTokenBucket(ctx *models.GatewayContext, policy *models.RateLimitPolicy, key string, rule models.RateLimitRule) (*models.RateLimitResult, error) {
	effectiveLimit := m.getEffectiveLimit(policy, rule)

	capacity := rule.Capacity
	if capacity <= 0 {
		capacity = effectiveLimit
	}
	if capacity <= 0 {
		capacity = 100
	}
	if policy.Adaptive != nil && policy.Adaptive.Enabled {
		capacity = effectiveLimit
	}

	refillRate := rule.RefillRate
	if refillRate <= 0 {
		refillRate = effectiveLimit
	}
	if refillRate <= 0 {
		refillRate = 10
	}

	window := rule.Window
	if window <= 0 {
		window = time.Second
	}

	allowed, remaining, limit, resetAfter := m.tokenBucket.Take(ctx, key, capacity, refillRate, window)

	result := &models.RateLimitResult{
		Allowed:    allowed,
		Limit:      limit,
		Remaining:  remaining,
		ResetAfter: resetAfter,
	}

	if !allowed {
		result.Reason = fmt.Sprintf("token bucket rate limit exceeded for key: %s, limit: %d", key, limit)
	}

	return result, nil
}

func (m *Manager) applySlidingWindow(ctx *models.GatewayContext, policy *models.RateLimitPolicy, key string, rule models.RateLimitRule) (*models.RateLimitResult, error) {
	effectiveLimit := m.getEffectiveLimit(policy, rule)

	limit := effectiveLimit
	if limit <= 0 {
		limit = 100
	}

	window := rule.Window
	if window <= 0 {
		window = time.Minute
	}

	allowed, remaining, limitVal, resetAfter := m.slidingWindow.Allow(ctx, key, limit, window)

	result := &models.RateLimitResult{
		Allowed:    allowed,
		Limit:      limitVal,
		Remaining:  remaining,
		ResetAfter: resetAfter,
	}

	if !allowed {
		result.Reason = fmt.Sprintf("sliding window rate limit exceeded for key: %s, limit: %d/%s", key, limit, window)
	}

	return result, nil
}

func (m *Manager) applyConcurrency(ctx *models.GatewayContext, policy *models.RateLimitPolicy, key string, rule models.RateLimitRule) (*models.RateLimitResult, error) {
	effectiveLimit := m.getEffectiveLimit(policy, rule)

	maxConcurrent := effectiveLimit
	if maxConcurrent <= 0 {
		maxConcurrent = 100
	}

	allowed, releaseFunc, err := m.concurrency.Acquire(ctx, key, maxConcurrent)
	if err != nil {
		return nil, err
	}

	if allowed {
		m.mu.Lock()
		m.releaseFuncs[ctx.RequestID] = append(m.releaseFuncs[ctx.RequestID], releaseFunc)
		m.mu.Unlock()
	}

	result := &models.RateLimitResult{
		Allowed:    allowed,
		Limit:      maxConcurrent,
		Remaining:  maxConcurrent - 1,
		ResetAfter: 0,
	}

	if !allowed {
		result.Reason = fmt.Sprintf("concurrency limit exceeded for key: %s, max: %d", key, maxConcurrent)
	}

	return result, nil
}

func (m *Manager) Release(requestID string) {
	m.releaseRequestResources(requestID)
}

func (m *Manager) releaseRequestResources(requestID string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if funcs, ok := m.releaseFuncs[requestID]; ok {
		for _, f := range funcs {
			f()
		}
		delete(m.releaseFuncs, requestID)
	}
}

func (m *Manager) RegisterPolicy(policy *models.RateLimitPolicy) {
	if policy == nil {
		return
	}

	m.policiesMu.Lock()
	m.policies[policy.ID] = policy
	m.policiesMu.Unlock()

	if policy.Adaptive != nil && policy.Adaptive.Enabled {
		originalLimit := m.getOriginalLimit(policy)
		am := NewAdaptiveManager(policy.ID, policy.Adaptive, originalLimit)

		m.adaptiveMu.Lock()
		existing, exists := m.adaptiveManagers[policy.ID]
		if exists {
			existing.Stop()
		}
		m.adaptiveManagers[policy.ID] = am
		m.adaptiveMu.Unlock()

		am.Start()
	}
}

func (m *Manager) getOriginalLimit(policy *models.RateLimitPolicy) int64 {
	if len(policy.Rules) == 0 {
		return 100
	}

	maxLimit := int64(0)
	for _, rule := range policy.Rules {
		if rule.Limit > maxLimit {
			maxLimit = rule.Limit
		}
		if rule.Capacity > maxLimit {
			maxLimit = rule.Capacity
		}
	}
	if maxLimit == 0 {
		maxLimit = 100
	}
	return maxLimit
}

func (m *Manager) RecordUpstreamLatency(ctx context.Context, policyID string, duration time.Duration, isError bool) {
	m.adaptiveMu.RLock()
	am, exists := m.adaptiveManagers[policyID]
	m.adaptiveMu.RUnlock()

	if !exists {
		return
	}

	am.RecordLatency(ctx, policyID, duration, isError)
}

func (m *Manager) GetAdaptiveManager(policyID string) *AdaptiveManager {
	m.adaptiveMu.RLock()
	defer m.adaptiveMu.RUnlock()
	return m.adaptiveManagers[policyID]
}

func (m *Manager) GetCurrentLimit(policyID string) int64 {
	m.adaptiveMu.RLock()
	am, exists := m.adaptiveManagers[policyID]
	m.adaptiveMu.RUnlock()

	if !exists {
		m.policiesMu.RLock()
		policy, ok := m.policies[policyID]
		m.policiesMu.RUnlock()
		if ok {
			return m.getOriginalLimit(policy)
		}
		return 0
	}
	return am.GetCurrentLimit()
}

func (m *Manager) Stop() {
	m.adaptiveMu.Lock()
	defer m.adaptiveMu.Unlock()

	for _, am := range m.adaptiveManagers {
		am.Stop()
	}
}

func (m *Manager) getEffectiveLimit(policy *models.RateLimitPolicy, rule models.RateLimitRule) int64 {
	if policy.Adaptive == nil || !policy.Adaptive.Enabled {
		return rule.Limit
	}

	m.adaptiveMu.RLock()
	am, exists := m.adaptiveManagers[policy.ID]
	m.adaptiveMu.RUnlock()

	if !exists {
		return rule.Limit
	}

	adaptiveLimit := am.GetCurrentLimit()
	if rule.Limit > 0 && adaptiveLimit > rule.Limit {
		return rule.Limit
	}
	return adaptiveLimit
}


