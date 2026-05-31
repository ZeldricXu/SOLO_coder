package faultinjection

import (
	"errors"
	"math/rand"
	"sync"
	"time"

	"github.com/enterprise/config-platform/pkg/utils"
)

type FaultType string

const (
	FaultTypeDelay     FaultType = "delay"
	FaultTypeError     FaultType = "error"
	FaultTypeAbort     FaultType = "abort"
	FaultTypeCorrupt   FaultType = "corrupt"
)

type ScopeType string

const (
	ScopeGlobal   ScopeType = "global"
	ScopeService  ScopeType = "service"
	ScopeEndpoint ScopeType = "endpoint"
	ScopePod      ScopeType = "pod"
)

type Scope struct {
	Type      ScopeType            `json:"type"`
	Services  []string             `json:"services,omitempty"`
	Endpoints []string             `json:"endpoints,omitempty"`
	Pods      []string             `json:"pods,omitempty"`
	Selectors map[string]string    `json:"selectors,omitempty"`
}

type FaultConfig struct {
	DelayMs     int    `json:"delay_ms,omitempty"`
	ErrorCode   int    `json:"error_code,omitempty"`
	ErrorMessage string `json:"error_message,omitempty"`
	CorruptRate float64 `json:"corrupt_rate,omitempty"`
}

type FaultScenario struct {
	ID          string      `json:"id"`
	Name        string      `json:"name"`
	Description string      `json:"description"`
	FaultType   FaultType   `json:"fault_type"`
	Config      FaultConfig `json:"config"`
	Scope       Scope       `json:"scope"`
	Probability float64     `json:"probability"`
	Duration    int         `json:"duration_seconds"`
	AutoRollback bool       `json:"auto_rollback"`
	Status      string      `json:"status"`
	CreatedAt   time.Time   `json:"created_at"`
	StartedAt   *time.Time  `json:"started_at"`
	EndedAt     *time.Time  `json:"ended_at"`
}

type ActiveFault struct {
	ScenarioID string
	EndTime    time.Time
	Rollback   func() error
}

type Manager struct {
	scenarios map[string]*FaultScenario
	active    map[string]*ActiveFault
	rollbacks map[string]func() error
	mu        sync.RWMutex
}

var (
	instance *Manager
	once     sync.Once
)

func GetManager() *Manager {
	once.Do(func() {
		instance = &Manager{
			scenarios: make(map[string]*FaultScenario),
			active:    make(map[string]*ActiveFault),
			rollbacks: make(map[string]func() error),
		}
		go instance.startExpirationChecker()
	})
	return instance
}

func (m *Manager) startExpirationChecker() {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()

	for range ticker.C {
		m.mu.Lock()
		now := time.Now()
		for id, active := range m.active {
			if now.After(active.EndTime) {
				scenario := m.scenarios[id]
				if scenario != nil {
					scenario.Status = "completed"
					scenario.EndedAt = utils.NowPtr()
					if scenario.AutoRollback && active.Rollback != nil {
						active.Rollback()
					}
				}
				delete(m.active, id)
			}
		}
		m.mu.Unlock()
	}
}

func (m *Manager) CreateScenario(scenario *FaultScenario) (*FaultScenario, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	scenario.ID = utils.GenerateID("fault")
	scenario.Status = "created"
	scenario.CreatedAt = time.Now().UTC()

	if scenario.Probability == 0 {
		scenario.Probability = 1.0
	}
	if scenario.Duration == 0 {
		scenario.Duration = 60
	}

	m.scenarios[scenario.ID] = scenario
	return scenario, nil
}

func (m *Manager) GetScenario(scenarioID string) (*FaultScenario, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	scenario, exists := m.scenarios[scenarioID]
	if !exists {
		return nil, errors.New("scenario not found")
	}
	return scenario, nil
}

func (m *Manager) UpdateScenario(scenarioID string, updates map[string]interface{}) (*FaultScenario, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	scenario, exists := m.scenarios[scenarioID]
	if !exists {
		return nil, errors.New("scenario not found")
	}

	if name, ok := updates["name"].(string); ok {
		scenario.Name = name
	}
	if desc, ok := updates["description"].(string); ok {
		scenario.Description = desc
	}
	if prob, ok := updates["probability"].(float64); ok {
		scenario.Probability = prob
	}
	if duration, ok := updates["duration_seconds"].(int); ok {
		scenario.Duration = duration
	}
	if autoRollback, ok := updates["auto_rollback"].(bool); ok {
		scenario.AutoRollback = autoRollback
	}
	if config, ok := updates["config"].(FaultConfig); ok {
		scenario.Config = config
	}
	if scope, ok := updates["scope"].(Scope); ok {
		scenario.Scope = scope
	}

	return scenario, nil
}

func (m *Manager) DeleteScenario(scenarioID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.scenarios[scenarioID]; !exists {
		return errors.New("scenario not found")
	}
	delete(m.scenarios, scenarioID)
	delete(m.active, scenarioID)
	return nil
}

func (m *Manager) ListScenarios() []*FaultScenario {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*FaultScenario, 0, len(m.scenarios))
	for _, s := range m.scenarios {
		result = append(result, s)
	}
	return result
}

func (m *Manager) ActivateScenario(scenarioID string, rollback func() error) (*FaultScenario, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	scenario, exists := m.scenarios[scenarioID]
	if !exists {
		return nil, errors.New("scenario not found")
	}

	scenario.Status = "active"
	scenario.StartedAt = utils.NowPtr()

	m.active[scenarioID] = &ActiveFault{
		ScenarioID: scenarioID,
		EndTime:    time.Now().Add(time.Duration(scenario.Duration) * time.Second),
		Rollback:   rollback,
	}

	return scenario, nil
}

func (m *Manager) DeactivateScenario(scenarioID string) (*FaultScenario, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	scenario, exists := m.scenarios[scenarioID]
	if !exists {
		return nil, errors.New("scenario not found")
	}

	active, isActive := m.active[scenarioID]
	if isActive && scenario.AutoRollback && active.Rollback != nil {
		active.Rollback()
	}

	scenario.Status = "stopped"
	scenario.EndedAt = utils.NowPtr()
	delete(m.active, scenarioID)

	return scenario, nil
}

func (m *Manager) IsFaultActive(service, endpoint, pod string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, active := range m.active {
		scenario := m.scenarios[active.ScenarioID]
		if scenario == nil {
			continue
		}

		if m.matchesScope(scenario.Scope, service, endpoint, pod) {
			if rand.Float64() < scenario.Probability {
				return true
			}
		}
	}
	return false
}

func (m *Manager) GetActiveFault(service, endpoint, pod string) (*FaultScenario, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, active := range m.active {
		scenario := m.scenarios[active.ScenarioID]
		if scenario == nil {
			continue
		}

		if m.matchesScope(scenario.Scope, service, endpoint, pod) {
			if rand.Float64() < scenario.Probability {
				return scenario, true
			}
		}
	}
	return nil, false
}

func (m *Manager) matchesScope(scope Scope, service, endpoint, pod string) bool {
	switch scope.Type {
	case ScopeGlobal:
		return true
	case ScopeService:
		return utils.ContainsString(scope.Services, service)
	case ScopeEndpoint:
		return utils.ContainsString(scope.Endpoints, endpoint)
	case ScopePod:
		return utils.ContainsString(scope.Pods, pod)
	default:
		return false
	}
}

func (m *Manager) ApplyFault(scenario *FaultScenario) {
	switch scenario.FaultType {
	case FaultTypeDelay:
		time.Sleep(time.Duration(scenario.Config.DelayMs) * time.Millisecond)
	}
}

func (m *Manager) CreateDelayScenario(name, description string, delayMs int, scope Scope, probability float64, duration int) (*FaultScenario, error) {
	return m.CreateScenario(&FaultScenario{
		Name:         name,
		Description:  description,
		FaultType:    FaultTypeDelay,
		Config:       FaultConfig{DelayMs: delayMs},
		Scope:        scope,
		Probability:  probability,
		Duration:     duration,
		AutoRollback: true,
	})
}

func (m *Manager) CreateErrorScenario(name, description string, errorCode int, errorMessage string, scope Scope, probability float64, duration int) (*FaultScenario, error) {
	return m.CreateScenario(&FaultScenario{
		Name:         name,
		Description:  description,
		FaultType:    FaultTypeError,
		Config:       FaultConfig{ErrorCode: errorCode, ErrorMessage: errorMessage},
		Scope:        scope,
		Probability:  probability,
		Duration:     duration,
		AutoRollback: true,
	})
}
