package retry_test

import (
	"testing"
	"time"

	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/internal/retry"
	"github.com/distributed-task-scheduler/test/testkit"
	"github.com/stretchr/testify/assert"
)

func TestCalculateBackoff_Exponential(t *testing.T) {
	rm := retry.NewRetryManager(nil, nil)

	baseDelay := 5 * time.Second

	b0 := rm.CalculateBackoff(retry.BackoffExponential, 0, baseDelay)
	assert.Equal(t, 1*5*time.Second, b0)

	b1 := rm.CalculateBackoff(retry.BackoffExponential, 1, baseDelay)
	assert.Equal(t, 2*5*time.Second, b1)

	b2 := rm.CalculateBackoff(retry.BackoffExponential, 2, baseDelay)
	assert.Equal(t, 4*5*time.Second, b2)

	b3 := rm.CalculateBackoff(retry.BackoffExponential, 3, baseDelay)
	assert.Equal(t, 8*5*time.Second, b3)
}

func TestCalculateBackoff_Fixed(t *testing.T) {
	rm := retry.NewRetryManager(nil, nil)

	baseDelay := 5 * time.Second

	for i := 0; i < 5; i++ {
		b := rm.CalculateBackoff(retry.BackoffFixed, i, baseDelay)
		assert.Equal(t, 5*time.Second, b, "fixed backoff should always return base delay")
	}
}

func TestCalculateBackoff_Linear(t *testing.T) {
	rm := retry.NewRetryManager(nil, nil)

	baseDelay := 5 * time.Second

	b0 := rm.CalculateBackoff(retry.BackoffLinear, 0, baseDelay)
	assert.Equal(t, 1*5*time.Second, b0)

	b1 := rm.CalculateBackoff(retry.BackoffLinear, 1, baseDelay)
	assert.Equal(t, 2*5*time.Second, b1)

	b2 := rm.CalculateBackoff(retry.BackoffLinear, 2, baseDelay)
	assert.Equal(t, 3*5*time.Second, b2)
}

func TestShouldRetry_UnderLimit(t *testing.T) {
	rm := retry.NewRetryManager(nil, nil)

	exec := testkit.NewExecutionBuilder().
		WithRetryCount(0).
		Build()

	assert.True(t, rm.ShouldRetry(exec, 3))
}

func TestShouldRetry_AtLimit(t *testing.T) {
	rm := retry.NewRetryManager(nil, nil)

	exec := testkit.NewExecutionBuilder().
		WithRetryCount(3).
		Build()

	assert.False(t, rm.ShouldRetry(exec, 3))
}

func TestShouldRetry_OverLimit(t *testing.T) {
	rm := retry.NewRetryManager(nil, nil)

	exec := testkit.NewExecutionBuilder().
		WithRetryCount(5).
		Build()

	assert.False(t, rm.ShouldRetry(exec, 3))
}

func TestShouldRetry_ZeroMaxRetries(t *testing.T) {
	rm := retry.NewRetryManager(nil, nil)

	exec := testkit.NewExecutionBuilder().
		WithRetryCount(0).
		Build()

	assert.False(t, rm.ShouldRetry(exec, 0))
}

func TestShouldRetry_IncrementalRetries(t *testing.T) {
	rm := retry.NewRetryManager(nil, nil)

	for i := 0; i < 3; i++ {
		exec := testkit.NewExecutionBuilder().
			WithRetryCount(i).
			Build()
		assert.True(t, rm.ShouldRetry(exec, 3), "should retry at count %d", i)
	}

	exec := testkit.NewExecutionBuilder().
		WithRetryCount(3).
		Build()
	assert.False(t, rm.ShouldRetry(exec, 3))
}

func TestBackoffExponential_Capped(t *testing.T) {
	rm := retry.NewRetryManager(nil, nil)
	baseDelay := 5 * time.Second

	b10 := rm.CalculateBackoff(retry.BackoffExponential, 10, baseDelay)
	expected := time.Duration(1024) * 5 * time.Second
	assert.Equal(t, expected, b10)
}

func TestBackoffDefault_IsExponential(t *testing.T) {
	rm := retry.NewRetryManager(nil, nil)
	baseDelay := 5 * time.Second

	b := rm.CalculateBackoff("unknown_strategy", 2, baseDelay)
	expected := time.Duration(4) * 5 * time.Second
	assert.Equal(t, expected, b, "unknown strategy should fallback to exponential")
}
