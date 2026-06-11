package circuitbreaker

import (
	"sync"
	"time"

	"DF1-56/internal/models"
)

type CircuitBreaker struct {
	mu             sync.RWMutex
	policy         *models.CircuitBreakerPolicy
	state          models.CircuitBreakerState
	metrics        *Metrics
	fallback       *FallbackHandler
	openedAt       time.Time
	halfOpenCount  int64
	halfOpenSuccess int64
}

func NewCircuitBreaker(policy *models.CircuitBreakerPolicy) *CircuitBreaker {
	if policy == nil {
		policy = &models.CircuitBreakerPolicy{
			ErrorThreshold:   0.5,
			RequestVolume:    10,
			SleepWindow:      30 * time.Second,
			HalfOpenRequests: 3,
			SuccessThreshold: 2,
			Timeout:          5 * time.Second,
			Enabled:          true,
		}
	}
	if policy.ErrorThreshold <= 0 || policy.ErrorThreshold > 1 {
		policy.ErrorThreshold = 0.5
	}
	if policy.RequestVolume <= 0 {
		policy.RequestVolume = 10
	}
	if policy.SleepWindow <= 0 {
		policy.SleepWindow = 30 * time.Second
	}
	if policy.HalfOpenRequests <= 0 {
		policy.HalfOpenRequests = 3
	}
	if policy.SuccessThreshold <= 0 {
		policy.SuccessThreshold = 2
	}

	state := models.StateClosed
	if !policy.Enabled {
		state = models.StateDisabled
	}

	return &CircuitBreaker{
		policy:   policy,
		state:    state,
		metrics:  NewMetrics(policy.Timeout*2, 10),
		fallback: NewFallbackHandler(policy.FallbackResponse),
	}
}

func (cb *CircuitBreaker) Allow() bool {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	if cb.state == models.StateDisabled {
		return true
	}

	switch cb.state {
	case models.StateClosed:
		return true
	case models.StateOpen:
		if time.Since(cb.openedAt) >= cb.policy.SleepWindow {
			cb.transitionToHalfOpen()
			return cb.allowHalfOpen()
		}
		return false
	case models.StateHalfOpen:
		return cb.allowHalfOpen()
	default:
		return true
	}
}

func (cb *CircuitBreaker) allowHalfOpen() bool {
	if cb.halfOpenCount < cb.policy.HalfOpenRequests {
		cb.halfOpenCount++
		return true
	}
	return false
}

func (cb *CircuitBreaker) State() models.CircuitBreakerState {
	cb.mu.RLock()
	defer cb.mu.RUnlock()
	return cb.state
}

func (cb *CircuitBreaker) OnSuccess() {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	if cb.state == models.StateDisabled {
		return
	}

	cb.metrics.RecordSuccess()

	switch cb.state {
	case models.StateClosed:
		if cb.shouldOpen() {
			cb.transitionToOpen()
		}
	case models.StateHalfOpen:
		cb.halfOpenSuccess++
		if cb.halfOpenSuccess >= cb.policy.SuccessThreshold {
			cb.transitionToClosed()
		}
	case models.StateOpen:
		if time.Since(cb.openedAt) >= cb.policy.SleepWindow {
			cb.transitionToHalfOpen()
			cb.halfOpenSuccess++
			if cb.halfOpenSuccess >= cb.policy.SuccessThreshold {
				cb.transitionToClosed()
			}
		}
	}
}

func (cb *CircuitBreaker) OnFailure(err error) {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	if cb.state == models.StateDisabled {
		return
	}

	cb.metrics.RecordFailure()

	switch cb.state {
	case models.StateClosed:
		if cb.shouldOpen() {
			cb.transitionToOpen()
		}
	case models.StateHalfOpen:
		cb.transitionToOpen()
	}
}

func (cb *CircuitBreaker) shouldOpen() bool {
	total := cb.metrics.TotalRequests()
	if total < cb.policy.RequestVolume {
		return false
	}
	errorRate := cb.metrics.ErrorRate()
	return errorRate >= cb.policy.ErrorThreshold
}

func (cb *CircuitBreaker) transitionToClosed() {
	cb.state = models.StateClosed
	cb.metrics.Reset()
	cb.halfOpenCount = 0
	cb.halfOpenSuccess = 0
}

func (cb *CircuitBreaker) transitionToOpen() {
	cb.state = models.StateOpen
	cb.openedAt = time.Now()
	cb.halfOpenCount = 0
	cb.halfOpenSuccess = 0
}

func (cb *CircuitBreaker) transitionToHalfOpen() {
	cb.state = models.StateHalfOpen
	cb.halfOpenCount = 0
	cb.halfOpenSuccess = 0
}

func (cb *CircuitBreaker) HandleFallback(ctx *models.GatewayContext) error {
	return cb.fallback.Handle(ctx, cb.policy.FallbackResponse)
}

func (cb *CircuitBreaker) Reset() {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	cb.metrics.Reset()
	cb.halfOpenCount = 0
	cb.halfOpenSuccess = 0
	if cb.policy.Enabled {
		cb.state = models.StateClosed
	} else {
		cb.state = models.StateDisabled
	}
}

func (cb *CircuitBreaker) ErrorRate() float64 {
	cb.mu.RLock()
	defer cb.mu.RUnlock()
	return cb.metrics.ErrorRate()
}

func (cb *CircuitBreaker) TotalRequests() int64 {
	cb.mu.RLock()
	defer cb.mu.RUnlock()
	return cb.metrics.TotalRequests()
}

func (cb *CircuitBreaker) FailureCount() int64 {
	cb.mu.RLock()
	defer cb.mu.RUnlock()
	return cb.metrics.FailureCount()
}

func (cb *CircuitBreaker) SuccessCount() int64 {
	cb.mu.RLock()
	defer cb.mu.RUnlock()
	return cb.metrics.SuccessCount()
}
