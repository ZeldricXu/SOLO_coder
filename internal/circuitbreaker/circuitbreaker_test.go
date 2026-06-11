package circuitbreaker

import (
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"DF1-56/internal/models"
	"DF1-56/internal/testutil"
)

func TestCircuitBreaker_NormalPath(t *testing.T) {
	cbFactory := testutil.NewCircuitBreakerPolicyFactory()

	t.Run("circuit breaker starts in closed state", func(t *testing.T) {
		policy := cbFactory()
		cb := NewCircuitBreaker(policy)

		assert.Equal(t, models.StateClosed, cb.State())
		assert.True(t, cb.Allow())
	})

	t.Run("consecutive failures trigger circuit open", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(0.5),
			testutil.WithRequestVolume(10),
			testutil.WithSleepWindow(5*time.Second),
		)
		cb := NewCircuitBreaker(policy)

		for i := 0; i < 10; i++ {
			if i < 5 {
				cb.OnSuccess()
			} else {
				cb.OnFailure(errors.New("test error"))
			}
		}

		assert.Equal(t, models.StateOpen, cb.State())
		assert.False(t, cb.Allow(), "should reject requests when circuit is open")
		assert.Equal(t, float64(0.5), cb.ErrorRate())
	})

	t.Run("half-open state allows limited requests", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(0.5),
			testutil.WithRequestVolume(5),
			testutil.WithSleepWindow(100*time.Millisecond),
			testutil.WithHalfOpenRequests(3),
			testutil.WithSuccessThreshold(2),
		)
		cb := NewCircuitBreaker(policy)

		for i := 0; i < 10; i++ {
			cb.OnFailure(errors.New("test error"))
		}
		assert.Equal(t, models.StateOpen, cb.State())

		time.Sleep(150 * time.Millisecond)

		assert.True(t, cb.Allow(), "should allow first request in half-open state")
		assert.True(t, cb.Allow(), "should allow second request in half-open state")
		assert.True(t, cb.Allow(), "should allow third request in half-open state")
		assert.False(t, cb.Allow(), "should reject fourth request in half-open state")
	})

	t.Run("successful requests in half-open state close circuit", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(0.5),
			testutil.WithRequestVolume(5),
			testutil.WithSleepWindow(50*time.Millisecond),
			testutil.WithHalfOpenRequests(5),
			testutil.WithSuccessThreshold(2),
		)
		cb := NewCircuitBreaker(policy)

		for i := 0; i < 10; i++ {
			cb.OnFailure(errors.New("test error"))
		}
		assert.Equal(t, models.StateOpen, cb.State())

		time.Sleep(100 * time.Millisecond)

		cb.OnSuccess()
		assert.Equal(t, models.StateHalfOpen, cb.State())

		cb.OnSuccess()
		assert.Equal(t, models.StateClosed, cb.State(), "circuit should close after success threshold")
		assert.True(t, cb.Allow())
	})

	t.Run("failure in half-open state reopens circuit", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(0.5),
			testutil.WithRequestVolume(5),
			testutil.WithSleepWindow(50*time.Millisecond),
			testutil.WithHalfOpenRequests(5),
			testutil.WithSuccessThreshold(3),
		)
		cb := NewCircuitBreaker(policy)

		for i := 0; i < 10; i++ {
			cb.OnFailure(errors.New("test error"))
		}
		assert.Equal(t, models.StateOpen, cb.State())

		time.Sleep(100 * time.Millisecond)

		cb.OnSuccess()
		assert.Equal(t, models.StateHalfOpen, cb.State())

		cb.OnFailure(errors.New("test error"))
		assert.Equal(t, models.StateOpen, cb.State(), "circuit should reopen after failure in half-open")
	})

	t.Run("disabled circuit breaker always allows", func(t *testing.T) {
		policy := cbFactory()
		policy.Enabled = false
		cb := NewCircuitBreaker(policy)

		assert.Equal(t, models.StateDisabled, cb.State())

		for i := 0; i < 100; i++ {
			cb.OnFailure(errors.New("test error"))
		}

		assert.Equal(t, models.StateDisabled, cb.State())
		assert.True(t, cb.Allow(), "disabled breaker should always allow")
	})
}

func TestCircuitBreaker_AbnormalPath(t *testing.T) {
	cbFactory := testutil.NewCircuitBreakerPolicyFactory()

	t.Run("nil policy uses defaults", func(t *testing.T) {
		cb := NewCircuitBreaker(nil)

		assert.NotNil(t, cb)
		assert.Equal(t, models.StateClosed, cb.State())
		assert.True(t, cb.Allow())
	})

	t.Run("invalid threshold values use defaults", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(-1.0),
			testutil.WithRequestVolume(-1),
			testutil.WithSleepWindow(-1*time.Second),
		)
		cb := NewCircuitBreaker(policy)

		assert.Equal(t, models.StateClosed, cb.State())
		assert.True(t, cb.Allow())

		for i := 0; i < 20; i++ {
			cb.OnFailure(errors.New("test error"))
		}
		assert.Equal(t, models.StateOpen, cb.State())
	})

	t.Run("circuit remains open during sleep window", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(0.3),
			testutil.WithRequestVolume(10),
			testutil.WithSleepWindow(500*time.Millisecond),
		)
		cb := NewCircuitBreaker(policy)

		for i := 0; i < 10; i++ {
			cb.OnFailure(errors.New("test error"))
		}
		assert.Equal(t, models.StateOpen, cb.State())

		for i := 0; i < 50; i++ {
			assert.False(t, cb.Allow(), "should not allow during sleep window")
			time.Sleep(5 * time.Millisecond)
		}
	})

	t.Run("fallback response returns configured response", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithFallbackResponse(503, `{"error": "service unavailable"}`),
			testutil.WithErrorThreshold(0.5),
			testutil.WithRequestVolume(5),
		)
		cb := NewCircuitBreaker(policy)

		for i := 0; i < 10; i++ {
			cb.OnFailure(errors.New("test error"))
		}
		assert.Equal(t, models.StateOpen, cb.State())

		ctx, recorder := testutil.NewTestGatewayContext("GET", "/api/test")

		err := cb.HandleFallback(ctx)
		require.NoError(t, err)
		assert.Equal(t, 503, recorder.Code)
		assert.Contains(t, recorder.Body.String(), "service unavailable")
	})

	t.Run("error rate calculation is correct", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(0.7),
			testutil.WithRequestVolume(20),
		)
		cb := NewCircuitBreaker(policy)

		for i := 0; i < 20; i++ {
			if i < 15 {
				cb.OnFailure(errors.New("test error"))
			} else {
				cb.OnSuccess()
			}
		}

		assert.Equal(t, int64(20), cb.TotalRequests())
		assert.Equal(t, int64(15), cb.FailureCount())
		assert.Equal(t, int64(5), cb.SuccessCount())
		assert.True(t, testutil.AlmostEqual(0.75, cb.ErrorRate(), 0.01))
		assert.Equal(t, models.StateOpen, cb.State())
	})

	t.Run("reset returns to closed state", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(0.5),
			testutil.WithRequestVolume(5),
		)
		cb := NewCircuitBreaker(policy)

		for i := 0; i < 10; i++ {
			cb.OnFailure(errors.New("test error"))
		}
		assert.Equal(t, models.StateOpen, cb.State())

		cb.Reset()

		assert.Equal(t, models.StateClosed, cb.State())
		assert.Equal(t, int64(0), cb.TotalRequests())
		assert.Equal(t, int64(0), cb.FailureCount())
		assert.Equal(t, float64(0), cb.ErrorRate())
		assert.True(t, cb.Allow())
	})
}

func TestCircuitBreaker_Concurrency(t *testing.T) {
	cbFactory := testutil.NewCircuitBreakerPolicyFactory()

	t.Run("concurrent state transitions do not produce intermediate states", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(0.5),
			testutil.WithRequestVolume(100),
			testutil.WithSleepWindow(50*time.Millisecond),
			testutil.WithHalfOpenRequests(10),
			testutil.WithSuccessThreshold(5),
		)
		cb := NewCircuitBreaker(policy)

		numGoroutines := 20
		requestsPerGoroutine := 100

		var validStates int64
		var invalidStates int64
		var wg sync.WaitGroup

		for i := 0; i < numGoroutines; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				for j := 0; j < requestsPerGoroutine; j++ {
					state := cb.State()

					if state != models.StateClosed &&
						state != models.StateOpen &&
						state != models.StateHalfOpen &&
						state != models.StateDisabled {
						atomic.AddInt64(&invalidStates, 1)
					} else {
						atomic.AddInt64(&validStates, 1)
					}

					if idx%2 == 0 {
						if j%3 == 0 {
							cb.OnFailure(errors.New("test error"))
						} else {
							cb.OnSuccess()
						}
					} else {
						cb.Allow()
					}
				}
			}(i)
		}

		wg.Wait()

		assert.Equal(t, int64(0), invalidStates, "should not have invalid intermediate states")
		assert.Equal(t, int64(numGoroutines*requestsPerGoroutine), validStates, "all state reads should be valid")

		t.Logf("Valid states: %d, Invalid states: %d", validStates, invalidStates)
	})

	t.Run("concurrent success and failure counts are accurate", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(0.9),
			testutil.WithRequestVolume(10000),
		)
		cb := NewCircuitBreaker(policy)

		numSuccess := int64(5000)
		numFailure := int64(5000)
		numGoroutines := 100
		requestsPerGoroutine := int((numSuccess + numFailure) / int64(numGoroutines))

		var successCount int64
		var failureCount int64
		var wg sync.WaitGroup

		for i := 0; i < numGoroutines; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				for j := 0; j < requestsPerGoroutine; j++ {
					if j%2 == 0 {
						cb.OnSuccess()
						atomic.AddInt64(&successCount, 1)
					} else {
						cb.OnFailure(errors.New("test error"))
						atomic.AddInt64(&failureCount, 1)
					}
				}
			}(i)
		}

		wg.Wait()

		assert.Equal(t, successCount+failureCount, cb.TotalRequests(), "total should match")
		assert.Equal(t, successCount, cb.SuccessCount(), "success count should match")
		assert.Equal(t, failureCount, cb.FailureCount(), "failure count should match")
		assert.True(t, testutil.AlmostEqual(float64(failureCount)/float64(successCount+failureCount), cb.ErrorRate(), 0.01))

		t.Logf("Success: %d, Failure: %d, Error Rate: %.4f", successCount, failureCount, cb.ErrorRate())
	})

	t.Run("concurrent Allow calls do not race", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(0.5),
			testutil.WithRequestVolume(100),
			testutil.WithSleepWindow(100*time.Millisecond),
		)
		cb := NewCircuitBreaker(policy)

		numGoroutines := 50
		requestsPerGoroutine := 200

		var allowedCount int64
		var rejectedCount int64
		var wg sync.WaitGroup

		for i := 0; i < numGoroutines; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				for j := 0; j < requestsPerGoroutine; j++ {
					if cb.Allow() {
						atomic.AddInt64(&allowedCount, 1)
						if j%5 == 0 {
							cb.OnFailure(errors.New("test error"))
						} else {
							cb.OnSuccess()
						}
					} else {
						atomic.AddInt64(&rejectedCount, 1)
					}
				}
			}(i)
		}

		wg.Wait()

		total := numGoroutines * requestsPerGoroutine
		assert.Equal(t, int64(total), allowedCount+rejectedCount, "total requests should match")
		t.Logf("Allowed: %d, Rejected: %d, Total: %d", allowedCount, rejectedCount, total)
	})

	t.Run("concurrent half-open recovery works correctly", func(t *testing.T) {
		policy := cbFactory(
			testutil.WithErrorThreshold(0.5),
			testutil.WithRequestVolume(10),
			testutil.WithSleepWindow(100*time.Millisecond),
			testutil.WithHalfOpenRequests(5),
			testutil.WithSuccessThreshold(3),
		)
		cb := NewCircuitBreaker(policy)

		for i := 0; i < 20; i++ {
			cb.OnFailure(errors.New("test error"))
		}
		assert.Equal(t, models.StateOpen, cb.State())

		time.Sleep(150 * time.Millisecond)

		var wg sync.WaitGroup
		numGoroutines := 10

		for i := 0; i < numGoroutines; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				if cb.Allow() {
					cb.OnSuccess()
				}
			}(i)
		}

		wg.Wait()

		state := cb.State()
		assert.True(t, state == models.StateClosed || state == models.StateHalfOpen,
			"should be in valid state after concurrent recovery, got: %s", state)
	})
}

func TestHealthCheck_NormalPath(t *testing.T) {
	t.Run("HTTP health check with success", func(t *testing.T) {
		mockServer := testutil.NewMockUpstreamServer(200, map[string]string{"status": "ok"})
		defer mockServer.Close()

		config := &models.HealthCheckConfig{
			Type:            models.HealthCheckHTTP,
			Interval:        100 * time.Millisecond,
			Timeout:         1 * time.Second,
			Path:            "/health",
			Method:          "GET",
			ExpectedStatus:  []int{200},
			FailureThreshold: 3,
			SuccessThreshold: 2,
		}

		node := &models.UpstreamNode{
			ID:      "test-node-1",
			Address: mockServer.URL[7:],
			Healthy: false,
		}

		cluster := &models.UpstreamCluster{
			HealthCheck: config,
			Nodes:       []*models.UpstreamNode{node},
		}

		checker := NewHealthChecker(cluster)
		err := checker.CheckNode(node)
		require.NoError(t, err)
		assert.True(t, node.Healthy)
	})

	t.Run("HTTP health check with failure", func(t *testing.T) {
		mockServer := testutil.NewMockUpstreamServer(500, map[string]string{"error": "internal error"})
		defer mockServer.Close()

		config := &models.HealthCheckConfig{
			Type:            models.HealthCheckHTTP,
			Interval:        100 * time.Millisecond,
			Timeout:         1 * time.Second,
			Path:            "/health",
			Method:          "GET",
			ExpectedStatus:  []int{200},
			FailureThreshold: 3,
			SuccessThreshold: 2,
		}

		node := &models.UpstreamNode{
			ID:      "test-node-2",
			Address: mockServer.URL[7:],
			Healthy: true,
		}

		cluster := &models.UpstreamCluster{
			HealthCheck: config,
			Nodes:       []*models.UpstreamNode{node},
		}

		checker := NewHealthChecker(cluster)
		err := checker.CheckNode(node)
		require.NoError(t, err)
		assert.False(t, node.Healthy)
		assert.Equal(t, 1, node.FailCount)
	})

	t.Run("TCP health check", func(t *testing.T) {
		mockServer := testutil.NewMockUpstreamServer(200, map[string]string{"status": "ok"})
		defer mockServer.Close()

		config := &models.HealthCheckConfig{
			Type:            models.HealthCheckTCP,
			Interval:        100 * time.Millisecond,
			Timeout:         1 * time.Second,
			FailureThreshold: 3,
			SuccessThreshold: 2,
		}

		node := &models.UpstreamNode{
			ID:      "test-node-3",
			Address: mockServer.URL[7:],
			Healthy: false,
		}

		cluster := &models.UpstreamCluster{
			HealthCheck: config,
			Nodes:       []*models.UpstreamNode{node},
		}

		checker := NewHealthChecker(cluster)
		err := checker.CheckNode(node)
		require.NoError(t, err)
		assert.True(t, node.Healthy)
	})

	t.Run("TCP health check with unreachable host", func(t *testing.T) {
		config := &models.HealthCheckConfig{
			Type:            models.HealthCheckTCP,
			Interval:        100 * time.Millisecond,
			Timeout:         500 * time.Millisecond,
			FailureThreshold: 3,
			SuccessThreshold: 2,
		}

		node := &models.UpstreamNode{
			ID:      "test-node-4",
			Address: "127.0.0.1:19999",
			Healthy: true,
		}

		cluster := &models.UpstreamCluster{
			HealthCheck: config,
			Nodes:       []*models.UpstreamNode{node},
		}

		checker := NewHealthChecker(cluster)
		err := checker.CheckNode(node)
		assert.Error(t, err)
		assert.False(t, node.Healthy)
	})
}

func TestMetrics_NormalPath(t *testing.T) {
	t.Run("metrics correctly track success and failure", func(t *testing.T) {
		metrics := NewMetrics(time.Minute, 10)

		for i := 0; i < 100; i++ {
			if i%3 == 0 {
				metrics.RecordFailure()
			} else {
				metrics.RecordSuccess()
			}
		}

		assert.Equal(t, int64(100), metrics.TotalRequests())
		assert.Equal(t, int64(66), metrics.SuccessCount())
		assert.Equal(t, int64(34), metrics.FailureCount())
		assert.True(t, testutil.AlmostEqual(0.34, metrics.ErrorRate(), 0.01))
	})

	t.Run("metrics reset clears all counts", func(t *testing.T) {
		metrics := NewMetrics(time.Minute, 10)

		for i := 0; i < 50; i++ {
			metrics.RecordSuccess()
		}

		assert.Equal(t, int64(50), metrics.TotalRequests())

		metrics.Reset()

		assert.Equal(t, int64(0), metrics.TotalRequests())
		assert.Equal(t, int64(0), metrics.SuccessCount())
		assert.Equal(t, int64(0), metrics.FailureCount())
		assert.Equal(t, float64(0), metrics.ErrorRate())
	})
}
