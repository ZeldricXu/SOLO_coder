package breaker

import (
	"sync"
	"time"

	"github.com/featureflag/sdk"
)

type CircuitBreaker struct {
	mu              sync.Mutex
	state           featureflag.CircuitBreakerState
	failureCount    int
	successCount    int
	threshold       int
	timeout         time.Duration
	lastStateChange time.Time
	halfOpenMax     int
}

func NewCircuitBreaker(threshold int, timeout time.Duration) *CircuitBreaker {
	if threshold <= 0 {
		threshold = 5
	}
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	return &CircuitBreaker{
		state:     featureflag.StateClosed,
		threshold: threshold,
		timeout:   timeout,
		halfOpenMax: 1,
	}
}

func (cb *CircuitBreaker) Allow() bool {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	switch cb.state {
	case featureflag.StateClosed:
		return true
	case featureflag.StateOpen:
		if time.Since(cb.lastStateChange) > cb.timeout {
			cb.state = featureflag.StateHalfOpen
			cb.lastStateChange = time.Now()
			cb.successCount = 0
			cb.failureCount = 0
			return true
		}
		return false
	case featureflag.StateHalfOpen:
		if cb.successCount < cb.halfOpenMax {
			return true
		}
		return false
	default:
		return true
	}
}

func (cb *CircuitBreaker) Success() {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	switch cb.state {
	case featureflag.StateHalfOpen:
		cb.successCount++
		if cb.successCount >= cb.halfOpenMax {
			cb.state = featureflag.StateClosed
			cb.lastStateChange = time.Now()
			cb.failureCount = 0
			cb.successCount = 0
		}
	case featureflag.StateClosed:
		cb.failureCount = 0
	}
}

func (cb *CircuitBreaker) Failure() {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	cb.failureCount++

	switch cb.state {
	case featureflag.StateClosed:
		if cb.failureCount >= cb.threshold {
			cb.state = featureflag.StateOpen
			cb.lastStateChange = time.Now()
			cb.successCount = 0
		}
	case featureflag.StateHalfOpen:
		cb.state = featureflag.StateOpen
		cb.lastStateChange = time.Now()
		cb.successCount = 0
	}
}

func (cb *CircuitBreaker) State() featureflag.CircuitBreakerState {
	cb.mu.Lock()
	defer cb.mu.Unlock()
	return cb.state
}

func (cb *CircuitBreaker) Reset() {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	cb.state = featureflag.StateClosed
	cb.failureCount = 0
	cb.successCount = 0
	cb.lastStateChange = time.Now()
}

func (cb *CircuitBreaker) IsOpen() bool {
	return cb.State() == featureflag.StateOpen
}

func (cb *CircuitBreaker) IsClosed() bool {
	return cb.State() == featureflag.StateClosed
}

func (cb *CircuitBreaker) IsHalfOpen() bool {
	return cb.State() == featureflag.StateHalfOpen
}

type NoopCircuitBreaker struct{}

func NewNoopCircuitBreaker() *NoopCircuitBreaker {
	return &NoopCircuitBreaker{}
}

func (cb *NoopCircuitBreaker) Allow() bool                     { return true }
func (cb *NoopCircuitBreaker) Success()                       {}
func (cb *NoopCircuitBreaker) Failure()                       {}
func (cb *NoopCircuitBreaker) State() featureflag.CircuitBreakerState { return featureflag.StateClosed }
func (cb *NoopCircuitBreaker) Reset()                         {}
