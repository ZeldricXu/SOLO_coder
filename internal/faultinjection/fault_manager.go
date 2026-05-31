package faultinjection

import (
	"sync"
	"time"

	"github.com/parking-platform/platform/pkg/models"
	"github.com/parking-platform/platform/pkg/utils"
)

type FaultInjector interface {
	Inject(scenario *models.FaultScenario) error
	Rollback(scenario *models.FaultScenario) error
}

type NoopInjector struct{}

func (i *NoopInjector) Inject(scenario *models.FaultScenario) error { return nil }
func (i *NoopInjector) Rollback(scenario *models.FaultScenario) error { return nil }

type ActiveFault struct {
	Scenario   *models.FaultScenario
	InjectedAt time.Time
	Timer      *time.Timer
}

type FaultManager struct {
	mu       sync.RWMutex
	scenarios map[string]*models.FaultScenario
	active   map[string]*ActiveFault
	injector FaultInjector
}

func NewFaultManager(injector FaultInjector) *FaultManager {
	if injector == nil {
		injector = &NoopInjector{}
	}
	return &FaultManager{
		scenarios: make(map[string]*models.FaultScenario),
		active:    make(map[string]*ActiveFault),
		injector:  injector,
	}
}

func (m *FaultManager) CreateScenario(name, faultType string, target models.FaultTarget, duration int64, autoRollback bool, parameters map[string]interface{}) *models.FaultScenario {
	m.mu.Lock()
	defer m.mu.Unlock()
	scenario := &models.FaultScenario{
		ID:           utils.GenerateID("fault"),
		Name:         name,
		Type:         faultType,
		Target:       target,
		Duration:     duration,
		AutoRollback: autoRollback,
		Parameters:   parameters,
	}
	m.scenarios[scenario.ID] = scenario
	return scenario
}

func (m *FaultManager) ListScenarios() []*models.FaultScenario {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]*models.FaultScenario, 0, len(m.scenarios))
	for _, s := range m.scenarios {
		result = append(result, s)
	}
	return result
}

func (m *FaultManager) GetScenario(id string) (*models.FaultScenario, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s, ok := m.scenarios[id]
	return s, ok
}

func (m *FaultManager) InjectScenario(id string) error {
	m.mu.Lock()
	scenario, ok := m.scenarios[id]
	if !ok {
		m.mu.Unlock()
		return ErrScenarioNotFound
	}
	if _, active := m.active[id]; active {
		m.mu.Unlock()
		return ErrAlreadyInjected
	}
	m.mu.Unlock()

	err := m.injector.Inject(scenario)
	if err != nil {
		return err
	}

	active := &ActiveFault{
		Scenario:   scenario,
		InjectedAt: utils.Now(),
	}

	if scenario.AutoRollback && scenario.Duration > 0 {
		active.Timer = time.AfterFunc(time.Duration(scenario.Duration)*time.Second, func() {
			_ = m.RollbackScenario(id)
		})
	}

	m.mu.Lock()
	m.active[id] = active
	m.mu.Unlock()

	return nil
}

func (m *FaultManager) RollbackScenario(id string) error {
	m.mu.Lock()
	active, ok := m.active[id]
	if !ok {
		m.mu.Unlock()
		return ErrNotInjected
	}
	if active.Timer != nil {
		active.Timer.Stop()
	}
	delete(m.active, id)
	scenario := active.Scenario
	m.mu.Unlock()

	return m.injector.Rollback(scenario)
}

func (m *FaultManager) ListActive() []*models.FaultScenario {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]*models.FaultScenario, 0, len(m.active))
	for _, a := range m.active {
		result = append(result, a.Scenario)
	}
	return result
}

func (m *FaultManager) RollbackAll() error {
	m.mu.RLock()
	ids := make([]string, 0, len(m.active))
	for id := range m.active {
		ids = append(ids, id)
	}
	m.mu.RUnlock()

	for _, id := range ids {
		if err := m.RollbackScenario(id); err != nil {
			return err
		}
	}
	return nil
}

var (
	ErrScenarioNotFound = &faultError{"fault scenario not found"}
	ErrAlreadyInjected  = &faultError{"fault scenario already injected"}
	ErrNotInjected      = &faultError{"fault scenario not injected"}
)

type faultError struct {
	msg string
}

func (e *faultError) Error() string { return e.msg }
