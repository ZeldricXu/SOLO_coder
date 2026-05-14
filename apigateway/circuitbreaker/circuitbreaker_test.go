package circuitbreaker

import (
	"apigateway/models"
	"apigateway/testdata"
	"sync"
	"testing"
	"time"
)

func TestCircuitBreaker_BasicFlow(t *testing.T) {
	cb := NewCircuitBreaker()

	config := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("test-service").
		WithFailureThreshold(5).
		WithFailureRateThreshold(0.5).
		WithOpenTimeout(1).
		WithHalfOpenRequests(2).
		Build()

	err := cb.Register(config)
	if err != nil {
		t.Fatalf("Failed to register circuit breaker: %v", err)
	}

	state, _ := cb.GetState("test-service")
	if state.Status != StatusClosed {
		t.Errorf("Expected initial state closed, got %s", state.Status)
	}

	for i := 0; i < 5; i++ {
		allowed, status := cb.AllowRequest("test-service")
		if !allowed {
			t.Errorf("Expected request %d to be allowed in closed state", i)
		}
		if status != StatusClosed {
			t.Errorf("Expected status closed, got %s", status)
		}
		cb.OnFailure("test-service")
	}

	state, _ = cb.GetState("test-service")
	if state.Status != StatusOpen {
		t.Errorf("Expected state open after threshold exceeded, got %s", state.Status)
	}

	allowed, _ := cb.AllowRequest("test-service")
	if allowed {
		t.Error("Expected request to be rejected when open")
	}
}

func TestCircuitBreaker_FailureRateThreshold(t *testing.T) {
	cb := NewCircuitBreaker()

	config := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("rate-service").
		WithFailureThreshold(10).
		WithFailureRateThreshold(0.5).
		WithOpenTimeout(30).
		WithHalfOpenRequests(3).
		Build()

	cb.Register(config)

	for i := 0; i < 10; i++ {
		cb.AllowRequest("rate-service")
		if i < 4 {
			cb.OnFailure("rate-service")
		} else {
			cb.OnSuccess("rate-service")
		}
	}

	state, _ := cb.GetState("rate-service")
	if state.Status != StatusClosed {
		t.Errorf("Expected closed state (failure rate 40%% < 50%%), got %s", state.Status)
	}

	for i := 0; i < 10; i++ {
		cb.AllowRequest("rate-service")
		cb.OnFailure("rate-service")
	}

	state, _ = cb.GetState("rate-service")
	if state.Status != StatusOpen {
		t.Errorf("Expected open state (failure rate high), got %s", state.Status)
	}
}

func TestCircuitBreaker_HalfOpenProbe(t *testing.T) {
	cb := NewCircuitBreaker()

	config := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("probe-service").
		WithFailureThreshold(5).
		WithFailureRateThreshold(0.5).
		WithOpenTimeout(1).
		WithHalfOpenRequests(3).
		Build()

	cb.Register(config)

	for i := 0; i < 10; i++ {
		cb.AllowRequest("probe-service")
		cb.OnFailure("probe-service")
	}

	state, _ := cb.GetState("probe-service")
	if state.Status != StatusOpen {
		t.Fatalf("Expected open state, got %s", state.Status)
	}

	time.Sleep(1100 * time.Millisecond)

	allowed, status := cb.AllowRequest("probe-service")
	if !allowed {
		t.Error("Expected first probe request to be allowed after timeout")
	}
	if status != StatusHalfOpen {
		t.Errorf("Expected half-open status, got %s", status)
	}

	cb.OnSuccess("probe-service")
	cb.AllowRequest("probe-service")
	cb.OnSuccess("probe-service")
	cb.AllowRequest("probe-service")
	cb.OnSuccess("probe-service")

	state, _ = cb.GetState("probe-service")
	if state.Status != StatusClosed {
		t.Errorf("Expected closed state after successful probes, got %s", state.Status)
	}
}

func TestCircuitBreaker_HalfOpenFailure(t *testing.T) {
	cb := NewCircuitBreaker()

	config := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("fail-probe-service").
		WithFailureThreshold(5).
		WithFailureRateThreshold(0.5).
		WithOpenTimeout(1).
		WithHalfOpenRequests(3).
		Build()

	cb.Register(config)

	for i := 0; i < 10; i++ {
		cb.AllowRequest("fail-probe-service")
		cb.OnFailure("fail-probe-service")
	}

	time.Sleep(1100 * time.Millisecond)

	allowed, _ := cb.AllowRequest("fail-probe-service")
	if !allowed {
		t.Error("Expected probe request to be allowed")
	}

	cb.OnFailure("fail-probe-service")

	state, _ := cb.GetState("fail-probe-service")
	if state.Status != StatusOpen {
		t.Errorf("Expected open state after failed probe, got %s", state.Status)
	}
}

func TestCircuitBreaker_ForceOpenClose(t *testing.T) {
	cb := NewCircuitBreaker()

	config := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("force-service").
		WithFailureThreshold(100).
		WithFailureRateThreshold(0.99).
		Build()

	cb.Register(config)

	cb.ForceOpen("force-service")

	state, _ := cb.GetState("force-service")
	if state.Status != StatusOpen {
		t.Errorf("Expected forced open state, got %s", state.Status)
	}

	allowed, _ := cb.AllowRequest("force-service")
	if allowed {
		t.Error("Expected request rejected when force open")
	}

	cb.ForceClose("force-service")

	state, _ = cb.GetState("force-service")
	if state.Status != StatusClosed {
		t.Errorf("Expected forced closed state, got %s", state.Status)
	}
}

func TestCircuitBreaker_Reset(t *testing.T) {
	cb := NewCircuitBreaker()

	config := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("reset-service").
		WithFailureThreshold(3).
		WithFailureRateThreshold(0.5).
		Build()

	cb.Register(config)

	for i := 0; i < 10; i++ {
		cb.AllowRequest("reset-service")
		cb.OnFailure("reset-service")
	}

	state, _ := cb.GetState("reset-service")
	if state.Status != StatusOpen {
		t.Fatalf("Expected open state, got %s", state.Status)
	}

	cb.Reset("reset-service")

	state, _ = cb.GetState("reset-service")
	if state.Status != StatusClosed {
		t.Errorf("Expected closed after reset, got %s", state.Status)
	}

	if state.FailureCount != 0 {
		t.Errorf("Expected 0 failures after reset, got %d", state.FailureCount)
	}
}

func TestCircuitBreaker_StateTransitionSequence(t *testing.T) {
	cb := NewCircuitBreaker()

	config := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("sequence-service").
		WithFailureThreshold(2).
		WithFailureRateThreshold(0.5).
		WithOpenTimeout(1).
		WithHalfOpenRequests(2).
		Build()

	cb.Register(config)

	state, _ := cb.GetState("sequence-service")
	if state.Status != StatusClosed {
		t.Fatalf("Step 1: Expected closed, got %s", state.Status)
	}

	cb.AllowRequest("sequence-service")
	cb.OnFailure("sequence-service")
	cb.AllowRequest("sequence-service")
	cb.OnFailure("sequence-service")
	cb.AllowRequest("sequence-service")
	cb.OnFailure("sequence-service")

	state, _ = cb.GetState("sequence-service")
	if state.Status != StatusOpen {
		t.Fatalf("Step 2: Expected open, got %s", state.Status)
	}

	time.Sleep(1100 * time.Millisecond)

	cb.AllowRequest("sequence-service")
	state, _ = cb.GetState("sequence-service")
	if state.Status != StatusHalfOpen {
		t.Fatalf("Step 3: Expected half-open, got %s", state.Status)
	}

	cb.OnSuccess("sequence-service")
	cb.AllowRequest("sequence-service")
	cb.OnSuccess("sequence-service")

	state, _ = cb.GetState("sequence-service")
	if state.Status != StatusClosed {
		t.Fatalf("Step 4: Expected closed, got %s", state.Status)
	}
}

func TestCircuitBreaker_MultipleServices(t *testing.T) {
	cb := NewCircuitBreaker()

	config1 := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("service-a").
		WithFailureThreshold(5).
		Build()

	config2 := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("service-b").
		WithFailureThreshold(10).
		Build()

	cb.Register(config1)
	cb.Register(config2)

	for i := 0; i < 10; i++ {
		cb.AllowRequest("service-a")
		cb.OnFailure("service-a")
	}

	stateA, _ := cb.GetState("service-a")
	stateB, _ := cb.GetState("service-b")

	if stateA.Status != StatusOpen {
		t.Errorf("Service A should be open, got %s", stateA.Status)
	}

	if stateB.Status != StatusClosed {
		t.Errorf("Service B should be closed, got %s", stateB.Status)
	}

	services := cb.ListServices()
	if len(services) != 2 {
		t.Errorf("Expected 2 services, got %d", len(services))
	}
}

func TestCircuitBreaker_ConcurrentAccess(t *testing.T) {
	cb := NewCircuitBreaker()

	config := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("concurrent-service").
		WithFailureThreshold(100).
		WithFailureRateThreshold(0.5).
		Build()

	cb.Register(config)

	numGoroutines := 50
	requestsPerGoroutine := 20

	var wg sync.WaitGroup
	wg.Add(numGoroutines)

	for i := 0; i < numGoroutines; i++ {
		go func(goroutineID int) {
			defer wg.Done()
			for j := 0; j < requestsPerGoroutine; j++ {
				allowed, _ := cb.AllowRequest("concurrent-service")
				if allowed {
					if j%2 == 0 {
						cb.OnSuccess("concurrent-service")
					} else {
						cb.OnFailure("concurrent-service")
					}
				}
			}
		}(i)
	}

	wg.Wait()

	state, _ := cb.GetState("concurrent-service")
	t.Logf("Final state: %s, failures: %d, success: %d, total: %d",
		state.Status, state.FailureCount, state.SuccessCount, state.TotalRequests)

	if state.Status == StatusOpen {
		t.Log("Note: Circuit breaker opened during concurrent test")
	}
}

func TestCircuitBreaker_SuccessRateRecovery(t *testing.T) {
	cb := NewCircuitBreaker()

	config := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("recovery-service").
		WithFailureThreshold(10).
		WithFailureRateThreshold(0.5).
		WithOpenTimeout(1).
		WithHalfOpenRequests(5).
		Build()

	cb.Register(config)

	for i := 0; i < 20; i++ {
		cb.AllowRequest("recovery-service")
		cb.OnFailure("recovery-service")
	}

	state, _ := cb.GetState("recovery-service")
	if state.Status != StatusOpen {
		t.Fatalf("Expected open state, got %s", state.Status)
	}

	time.Sleep(1100 * time.Millisecond)

	for i := 0; i < 5; i++ {
		allowed, _ := cb.AllowRequest("recovery-service")
		if !allowed {
			t.Errorf("Expected probe %d to be allowed", i)
		}
		cb.OnSuccess("recovery-service")
	}

	state, _ = cb.GetState("recovery-service")
	if state.Status != StatusClosed {
		t.Errorf("Expected closed after successful recovery probes, got %s", state.Status)
	}
}

func TestCircuitBreaker_Counters(t *testing.T) {
	cb := NewCircuitBreaker()

	config := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("counter-service").
		WithFailureThreshold(100).
		WithFailureRateThreshold(0.99).
		Build()

	cb.Register(config)

	totalRequests := 100
	successRequests := 70
	failureRequests := 30

	for i := 0; i < successRequests; i++ {
		cb.AllowRequest("counter-service")
		cb.OnSuccess("counter-service")
	}

	for i := 0; i < failureRequests; i++ {
		cb.AllowRequest("counter-service")
		cb.OnFailure("counter-service")
	}

	state, _ := cb.GetState("counter-service")

	if state.TotalRequests != totalRequests {
		t.Errorf("Expected total requests %d, got %d", totalRequests, state.TotalRequests)
	}

	if state.SuccessCount != successRequests {
		t.Errorf("Expected success count %d, got %d", successRequests, state.SuccessCount)
	}

	if state.FailureCount != failureRequests {
		t.Errorf("Expected failure count %d, got %d", failureRequests, state.FailureCount)
	}
}

func TestCircuitBreaker_Unregister(t *testing.T) {
	cb := NewCircuitBreaker()

	config := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("unregister-service").
		Build()

	cb.Register(config)

	_, exists := cb.GetState("unregister-service")
	if !exists {
		t.Error("Expected service to exist")
	}

	cb.Unregister("unregister-service")

	_, exists = cb.GetState("unregister-service")
	if exists {
		t.Error("Expected service to be unregistered")
	}
}

func TestCircuitBreaker_GetConfig(t *testing.T) {
	cb := NewCircuitBreaker()

	expectedConfig := testdata.NewCircuitBreakerConfigBuilder().
		WithServiceName("config-service").
		WithFailureThreshold(42).
		WithFailureRateThreshold(0.75).
		WithOpenTimeout(60).
		WithHalfOpenRequests(5).
		Build()

	cb.Register(expectedConfig)

	config, exists := cb.GetConfig("config-service")
	if !exists {
		t.Fatal("Expected config to exist")
	}

	if config.FailureThreshold != 42 {
		t.Errorf("Expected threshold 42, got %d", config.FailureThreshold)
	}

	if config.FailureRateThreshold != 0.75 {
		t.Errorf("Expected rate 0.75, got %f", config.FailureRateThreshold)
	}

	if config.OpenTimeout != 60 {
		t.Errorf("Expected timeout 60, got %d", config.OpenTimeout)
	}

	if config.HalfOpenRequests != 5 {
		t.Errorf("Expected half-open 5, got %d", config.HalfOpenRequests)
	}
}

func TestCircuitBreaker_GetAllStates(t *testing.T) {
	cb := NewCircuitBreaker()

	config1 := &models.CircuitBreakerConfig{
		CircuitID:           "c1",
		ServiceName:         "svc1",
		FailureThreshold:    10,
		FailureRateThreshold: 0.5,
		OpenTimeout:         30,
		HalfOpenRequests:    3,
	}

	config2 := &models.CircuitBreakerConfig{
		CircuitID:           "c2",
		ServiceName:         "svc2",
		FailureThreshold:    5,
		FailureRateThreshold: 0.3,
		OpenTimeout:         60,
		HalfOpenRequests:    5,
	}

	cb.Register(config1)
	cb.Register(config2)

	for i := 0; i < 10; i++ {
		cb.AllowRequest("svc2")
		cb.OnFailure("svc2")
	}

	states := cb.GetAllStates()

	if len(states) != 2 {
		t.Errorf("Expected 2 states, got %d", len(states))
	}

	var svc1, svc2 *models.CircuitBreakerState
	for _, s := range states {
		if s.ServiceName == "svc1" {
			svc1 = s
		} else if s.ServiceName == "svc2" {
			svc2 = s
		}
	}

	if svc1 == nil || svc2 == nil {
		t.Fatal("Expected both services to be in states")
	}

	if svc1.Status != StatusClosed {
		t.Errorf("Expected svc1 closed, got %s", svc1.Status)
	}

	if svc2.Status != StatusOpen {
		t.Errorf("Expected svc2 open, got %s", svc2.Status)
	}
}

func TestCircuitBreaker_EdgeCases(t *testing.T) {
	t.Run("Non-existent service", func(t *testing.T) {
		cb := NewCircuitBreaker()

		allowed, status := cb.AllowRequest("non-existent")
		if !allowed {
			t.Error("Should allow requests for unregistered service")
		}
		if status != "" {
			t.Errorf("Expected empty status for unregistered service, got %s", status)
		}

		_, exists := cb.GetState("non-existent")
		if exists {
			t.Error("Should not have state for unregistered service")
		}

		cb.OnSuccess("non-existent")
		cb.OnFailure("non-existent")
	})

	t.Run("Zero threshold", func(t *testing.T) {
		cb := NewCircuitBreaker()

		config := testdata.NewCircuitBreakerConfigBuilder().
			WithServiceName("zero-service").
			WithFailureThreshold(0).
			WithFailureRateThreshold(0).
			WithOpenTimeout(0).
			WithHalfOpenRequests(0).
			Build()

		err := cb.Register(config)
		if err != nil {
			t.Errorf("Should register with default values: %v", err)
		}
	})

	t.Run("Reset non-existent", func(t *testing.T) {
		cb := NewCircuitBreaker()
		cb.Reset("non-existent")
		cb.ForceOpen("non-existent")
		cb.ForceClose("non-existent")
		cb.Unregister("non-existent")
	})
}
