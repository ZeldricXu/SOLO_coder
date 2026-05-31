package strategies

import (
	"time"

	"github.com/solocoder/task-scheduler/internal/contracts"
)

type FixedRetryStrategy struct {
	maxRetries int
	delay      time.Duration
}

func NewFixedRetryStrategy(maxRetries int, delay time.Duration) *FixedRetryStrategy {
	return &FixedRetryStrategy{
		maxRetries: maxRetries,
		delay:      delay,
	}
}

func (s *FixedRetryStrategy) GetType() contracts.RetryStrategyType {
	return contracts.RetryStrategyFixed
}

func (s *FixedRetryStrategy) ShouldRetry(attempt int, err error) bool {
	return attempt < s.maxRetries
}

func (s *FixedRetryStrategy) GetDelay(attempt int) time.Duration {
	return s.delay
}

func (s *FixedRetryStrategy) GetMaxRetries() int {
	return s.maxRetries
}

type ExponentialRetryStrategy struct {
	maxRetries int
	baseDelay  time.Duration
	maxDelay   time.Duration
}

func NewExponentialRetryStrategy(maxRetries int, baseDelay, maxDelay time.Duration) *ExponentialRetryStrategy {
	return &ExponentialRetryStrategy{
		maxRetries: maxRetries,
		baseDelay:  baseDelay,
		maxDelay:   maxDelay,
	}
}

func (s *ExponentialRetryStrategy) GetType() contracts.RetryStrategyType {
	return contracts.RetryStrategyExponential
}

func (s *ExponentialRetryStrategy) ShouldRetry(attempt int, err error) bool {
	return attempt < s.maxRetries
}

func (s *ExponentialRetryStrategy) GetDelay(attempt int) time.Duration {
	delay := s.baseDelay * time.Duration(1<<uint(attempt))
	if delay > s.maxDelay {
		return s.maxDelay
	}
	return delay
}

func (s *ExponentialRetryStrategy) GetMaxRetries() int {
	return s.maxRetries
}

type LinearRetryStrategy struct {
	maxRetries int
	baseDelay  time.Duration
	increment  time.Duration
	maxDelay   time.Duration
}

func NewLinearRetryStrategy(maxRetries int, baseDelay, increment, maxDelay time.Duration) *LinearRetryStrategy {
	return &LinearRetryStrategy{
		maxRetries: maxRetries,
		baseDelay:  baseDelay,
		increment:  increment,
		maxDelay:   maxDelay,
	}
}

func (s *LinearRetryStrategy) GetType() contracts.RetryStrategyType {
	return contracts.RetryStrategyLinear
}

func (s *LinearRetryStrategy) ShouldRetry(attempt int, err error) bool {
	return attempt < s.maxRetries
}

func (s *LinearRetryStrategy) GetDelay(attempt int) time.Duration {
	delay := s.baseDelay + s.increment*time.Duration(attempt)
	if delay > s.maxDelay {
		return s.maxDelay
	}
	return delay
}

func (s *LinearRetryStrategy) GetMaxRetries() int {
	return s.maxRetries
}

type NoRetryStrategy struct{}

func NewNoRetryStrategy() *NoRetryStrategy {
	return &NoRetryStrategy{}
}

func (s *NoRetryStrategy) GetType() contracts.RetryStrategyType {
	return contracts.RetryStrategyFixed
}

func (s *NoRetryStrategy) ShouldRetry(attempt int, err error) bool {
	return false
}

func (s *NoRetryStrategy) GetDelay(attempt int) time.Duration {
	return 0
}

func (s *NoRetryStrategy) GetMaxRetries() int {
	return 0
}
