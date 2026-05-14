package circuitbreaker

import (
	"apigateway/models"
	"fmt"
	"sync"
	"time"
)

const (
	StatusClosed   = "closed"
	StatusOpen     = "open"
	StatusHalfOpen = "half_open"
)

const (
	DefaultCriticalHalfOpenRequests = 10
	DefaultHighHalfOpenRequests     = 5
	DefaultMediumHalfOpenRequests   = 3
	DefaultLowHalfOpenRequests      = 1
)

type CircuitBreaker struct {
	configs map[string]*models.CircuitBreakerConfig
	states  map[string]*models.CircuitBreakerState
	mu      sync.RWMutex
}

func GetHalfOpenRequestsByImportance(serviceImportance string) int {
	switch serviceImportance {
	case models.ServiceImportanceCritical:
		return DefaultCriticalHalfOpenRequests
	case models.ServiceImportanceHigh:
		return DefaultHighHalfOpenRequests
	case models.ServiceImportanceMedium:
		return DefaultMediumHalfOpenRequests
	case models.ServiceImportanceLow:
		return DefaultLowHalfOpenRequests
	default:
		return 3
	}
}

func GetHalfOpenRequestsForConfig(config *models.CircuitBreakerConfig) int {
	if config == nil {
		return 3
	}

	switch config.ServiceImportance {
	case models.ServiceImportanceCritical:
		if config.CriticalHalfOpenRequests > 0 {
			return config.CriticalHalfOpenRequests
		}
		return DefaultCriticalHalfOpenRequests
	case models.ServiceImportanceHigh:
		if config.HighHalfOpenRequests > 0 {
			return config.HighHalfOpenRequests
		}
		return DefaultHighHalfOpenRequests
	case models.ServiceImportanceMedium:
		if config.MediumHalfOpenRequests > 0 {
			return config.MediumHalfOpenRequests
		}
		return DefaultMediumHalfOpenRequests
	case models.ServiceImportanceLow:
		if config.LowHalfOpenRequests > 0 {
			return config.LowHalfOpenRequests
		}
		return DefaultLowHalfOpenRequests
	default:
		if config.HalfOpenRequests > 0 {
			return config.HalfOpenRequests
		}
		return 3
	}
}

func NewCircuitBreaker() *CircuitBreaker {
	return &CircuitBreaker{
		configs: make(map[string]*models.CircuitBreakerConfig),
		states:  make(map[string]*models.CircuitBreakerState),
	}
}

func (cb *CircuitBreaker) Register(config *models.CircuitBreakerConfig) error {
	if config == nil || config.ServiceName == "" {
		return fmt.Errorf("invalid circuit breaker config")
	}

	cb.mu.Lock()
	defer cb.mu.Unlock()

	if config.FailureThreshold <= 0 {
		config.FailureThreshold = 50
	}
	if config.FailureRateThreshold <= 0 {
		config.FailureRateThreshold = 0.5
	}
	if config.OpenTimeout <= 0 {
		config.OpenTimeout = 30
	}
	if config.HalfOpenRequests <= 0 {
		config.HalfOpenRequests = 3
	}

	if config.CircuitID == "" {
		config.CircuitID = "circuit_" + config.ServiceName
	}

	cb.configs[config.ServiceName] = config

	if _, exists := cb.states[config.ServiceName]; !exists {
		cb.states[config.ServiceName] = &models.CircuitBreakerState{
			CircuitID:     config.CircuitID,
			ServiceName:   config.ServiceName,
			Status:        StatusClosed,
			FailureCount:  0,
			SuccessCount:  0,
			TotalRequests: 0,
		}
	}

	return nil
}

func (cb *CircuitBreaker) Unregister(serviceName string) {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	delete(cb.configs, serviceName)
	delete(cb.states, serviceName)
}

func (cb *CircuitBreaker) AllowRequest(serviceName string) (bool, string) {
	cb.mu.RLock()
	state, stateExists := cb.states[serviceName]
	config, configExists := cb.configs[serviceName]
	cb.mu.RUnlock()

	if !stateExists || !configExists {
		return true, ""
	}

	cb.mu.Lock()
	defer cb.mu.Unlock()

	halfOpenRequests := GetHalfOpenRequestsForConfig(config)

	switch state.Status {
	case StatusClosed:
		return true, state.Status
	case StatusOpen:
		elapsed := time.Since(state.OpenedAt).Seconds()
		if elapsed >= float64(config.OpenTimeout) {
			state.Status = StatusHalfOpen
			state.HalfOpenCount = 0
			state.SuccessCount = 0
			state.FailureCount = 0
			state.TotalRequests = 0
			return true, state.Status
		}
		return false, state.Status
	case StatusHalfOpen:
		return state.HalfOpenCount < halfOpenRequests, state.Status
	default:
		return true, state.Status
	}
}

func (cb *CircuitBreaker) OnSuccess(serviceName string) {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	state, exists := cb.states[serviceName]
	if !exists {
		return
	}

	config, configExists := cb.configs[serviceName]
	if !configExists {
		return
	}

	state.TotalRequests++
	state.SuccessCount++

	if state.Status == StatusHalfOpen {
		state.HalfOpenCount++
		halfOpenRequests := GetHalfOpenRequestsForConfig(config)
		if state.HalfOpenCount >= halfOpenRequests {
			state.Status = StatusClosed
			state.FailureCount = 0
			state.SuccessCount = 0
			state.TotalRequests = 0
			state.HalfOpenCount = 0
		}
	}
}

func (cb *CircuitBreaker) OnFailure(serviceName string) {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	state, exists := cb.states[serviceName]
	if !exists {
		return
	}

	config, configExists := cb.configs[serviceName]
	if !configExists {
		return
	}

	state.TotalRequests++
	state.FailureCount++
	state.LastFailureAt = time.Now()

	if state.Status == StatusHalfOpen {
		state.Status = StatusOpen
		state.OpenedAt = time.Now()
		state.HalfOpenCount = 0
		return
	}

	if state.Status == StatusClosed {
		if state.FailureCount >= config.FailureThreshold {
			failureRate := float64(state.FailureCount) / float64(state.TotalRequests)
			if failureRate >= config.FailureRateThreshold {
				state.Status = StatusOpen
				state.OpenedAt = time.Now()
			}
		}
	}
}

func (cb *CircuitBreaker) GetState(serviceName string) (*models.CircuitBreakerState, bool) {
	cb.mu.RLock()
	defer cb.mu.RUnlock()

	state, exists := cb.states[serviceName]
	if !exists {
		return nil, false
	}

	stateCopy := *state
	return &stateCopy, true
}

func (cb *CircuitBreaker) GetConfig(serviceName string) (*models.CircuitBreakerConfig, bool) {
	cb.mu.RLock()
	defer cb.mu.RUnlock()

	config, exists := cb.configs[serviceName]
	if !exists {
		return nil, false
	}

	configCopy := *config
	return &configCopy, true
}

func (cb *CircuitBreaker) Reset(serviceName string) {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	config, configExists := cb.configs[serviceName]
	if !configExists {
		return
	}

	cb.states[serviceName] = &models.CircuitBreakerState{
		CircuitID:     config.CircuitID,
		ServiceName:   config.ServiceName,
		Status:        StatusClosed,
		FailureCount:  0,
		SuccessCount:  0,
		TotalRequests: 0,
	}
}

func (cb *CircuitBreaker) ForceOpen(serviceName string) {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	state, exists := cb.states[serviceName]
	if !exists {
		return
	}

	state.Status = StatusOpen
	state.OpenedAt = time.Now()
}

func (cb *CircuitBreaker) ForceClose(serviceName string) {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	state, exists := cb.states[serviceName]
	if !exists {
		return
	}

	state.Status = StatusClosed
	state.FailureCount = 0
	state.SuccessCount = 0
	state.TotalRequests = 0
}

func (cb *CircuitBreaker) ListServices() []string {
	cb.mu.RLock()
	defer cb.mu.RUnlock()

	services := make([]string, 0, len(cb.configs))
	for name := range cb.configs {
		services = append(services, name)
	}
	return services
}

func (cb *CircuitBreaker) GetAllStates() []*models.CircuitBreakerState {
	cb.mu.RLock()
	defer cb.mu.RUnlock()

	states := make([]*models.CircuitBreakerState, 0, len(cb.states))
	for _, state := range cb.states {
		stateCopy := *state
		states = append(states, &stateCopy)
	}
	return states
}
