package classification

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"gopkg.in/yaml.v3"
)

type ClassificationScenario struct {
	Name        string                 `json:"name" yaml:"name"`
	Description string                 `json:"description" yaml:"description"`
	Patterns    []ScenarioPattern      `json:"patterns" yaml:"patterns"`
	Policies    []ScenarioPolicy       `json:"policies" yaml:"policies"`
	Rules       map[string]interface{} `json:"rules" yaml:"rules"`
	Enabled     bool                   `json:"enabled" yaml:"enabled"`
	CreatedAt   time.Time              `json:"created_at" yaml:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at" yaml:"updated_at"`
}

type ScenarioPattern struct {
	Name        string `json:"name" yaml:"name"`
	Regex       string `json:"regex" yaml:"regex"`
	Sensitivity string `json:"sensitivity" yaml:"sensitivity"`
	Category    string `json:"category" yaml:"category"`
	Level       int    `json:"level" yaml:"level"`
	Enabled     bool   `json:"enabled" yaml:"enabled"`
}

type ScenarioPolicy struct {
	Level       int    `json:"level" yaml:"level"`
	Action      string `json:"action" yaml:"action"`
	Description string `json:"description" yaml:"description"`
	Enabled     bool   `json:"enabled" yaml:"enabled"`
}

type ConfigSource interface {
	Load(ctx context.Context) (*ClassificationScenario, error)
	Watch(ctx context.Context, onChange func(*ClassificationScenario)) error
	Name() string
}

type MemoryConfigSource struct {
	scenario *ClassificationScenario
	mu       sync.RWMutex
}

func NewMemoryConfigSource(scenario *ClassificationScenario) *MemoryConfigSource {
	return &MemoryConfigSource{
		scenario: scenario,
	}
}

func (s *MemoryConfigSource) Load(ctx context.Context) (*ClassificationScenario, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.scenario == nil {
		return nil, fmt.Errorf("no scenario configured")
	}
	return s.scenario, nil
}

func (s *MemoryConfigSource) Watch(ctx context.Context, onChange func(*ClassificationScenario)) error {
	return nil
}

func (s *MemoryConfigSource) Name() string {
	return "memory"
}

func (s *MemoryConfigSource) Update(scenario *ClassificationScenario) {
	s.mu.Lock()
	defer s.mu.Unlock()
	scenario.UpdatedAt = time.Now()
	s.scenario = scenario
}

type FileConfigSource struct {
	path     string
	format   string
	interval time.Duration
}

func NewFileConfigSource(path, format string, interval time.Duration) *FileConfigSource {
	return &FileConfigSource{
		path:     path,
		format:   format,
		interval: interval,
	}
}

func (s *FileConfigSource) Load(ctx context.Context) (*ClassificationScenario, error) {
	data, err := osReadFile(s.path)
	if err != nil {
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	var scenario ClassificationScenario
	switch s.format {
	case "yaml", "yml":
		err = yaml.Unmarshal(data, &scenario)
	case "json":
		err = json.Unmarshal(data, &scenario)
	default:
		return nil, fmt.Errorf("unsupported format: %s", s.format)
	}

	if err != nil {
		return nil, fmt.Errorf("failed to parse config: %w", err)
	}

	return &scenario, nil
}

func (s *FileConfigSource) Watch(ctx context.Context, onChange func(*ClassificationScenario)) error {
	if s.interval <= 0 {
		return nil
	}

	go func() {
		ticker := time.NewTicker(s.interval)
		defer ticker.Stop()

		var lastHash string
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				scenario, err := s.Load(ctx)
				if err != nil {
					continue
				}
				hash := calculateScenarioHash(scenario)
				if hash != lastHash {
					lastHash = hash
					onChange(scenario)
				}
			}
		}
	}()

	return nil
}

func (s *FileConfigSource) Name() string {
	return fmt.Sprintf("file:%s", s.path)
}

type DynamicConfigManager struct {
	sources        map[string]ConfigSource
	activeScenario string
	scenarios      map[string]*ClassificationScenario
	listeners      []func(string, *ClassificationScenario)
	mu             sync.RWMutex
}

func NewDynamicConfigManager() *DynamicConfigManager {
	return &DynamicConfigManager{
		sources:        make(map[string]ConfigSource),
		scenarios:      make(map[string]*ClassificationScenario),
		listeners:      make([]func(string, *ClassificationScenario), 0),
	}
}

func (m *DynamicConfigManager) AddSource(source ConfigSource) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	ctx := context.Background()
	scenario, err := source.Load(ctx)
	if err != nil {
		return err
	}

	m.sources[source.Name()] = source
	m.scenarios[scenario.Name] = scenario

	if m.activeScenario == "" {
		m.activeScenario = scenario.Name
	}

	go source.Watch(ctx, func(updated *ClassificationScenario) {
		m.mu.Lock()
		m.scenarios[updated.Name] = updated
		m.mu.Unlock()
		m.notifyListeners(updated.Name, updated)
	})

	return nil
}

func (m *DynamicConfigManager) SetActiveScenario(name string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.scenarios[name]; !exists {
		return fmt.Errorf("scenario %s not found", name)
	}

	oldScenario := m.activeScenario
	m.activeScenario = name

	if oldScenario != name {
		m.notifyListeners(name, m.scenarios[name])
	}

	return nil
}

func (m *DynamicConfigManager) GetActiveScenario() *ClassificationScenario {
	m.mu.RLock()
	defer m.mu.RUnlock()

	scenario, exists := m.scenarios[m.activeScenario]
	if !exists {
		return nil
	}
	return scenario
}

func (m *DynamicConfigManager) GetScenario(name string) (*ClassificationScenario, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	scenario, exists := m.scenarios[name]
	return scenario, exists
}

func (m *DynamicConfigManager) ListScenarios() []string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	names := make([]string, 0, len(m.scenarios))
	for name := range m.scenarios {
		names = append(names, name)
	}
	return names
}

func (m *DynamicConfigManager) AddListener(listener func(string, *ClassificationScenario)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.listeners = append(m.listeners, listener)
}

func (m *DynamicConfigManager) UpdateScenario(name string, scenario *ClassificationScenario) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.scenarios[name]; !exists {
		return fmt.Errorf("scenario %s not found", name)
	}

	scenario.UpdatedAt = time.Now()
	m.scenarios[name] = scenario

	if m.activeScenario == name {
		m.notifyListeners(name, scenario)
	}

	return nil
}

func (m *DynamicConfigManager) notifyListeners(name string, scenario *ClassificationScenario) {
	for _, listener := range m.listeners {
		go listener(name, scenario)
	}
}

func (m *DynamicConfigManager) ApplyToClassifier(classifier *DefaultClassifier) error {
	scenario := m.GetActiveScenario()
	if scenario == nil {
		return fmt.Errorf("no active scenario")
	}

	m.applyPatterns(scenario, classifier)
	m.applyPolicies(scenario, classifier)

	return nil
}

func (m *DynamicConfigManager) applyPatterns(scenario *ClassificationScenario, classifier *DefaultClassifier) {
	patternStore := classifier.GetPatternStore()
	for _, p := range scenario.Patterns {
		if !p.Enabled {
			patternStore.Remove(p.Name)
			continue
		}
		_ = patternStore.Add(p.Name, p.Regex, p.Sensitivity, p.Category, p.Level)
	}
}

func (m *DynamicConfigManager) applyPolicies(scenario *ClassificationScenario, classifier *DefaultClassifier) {
	policyStore := classifier.GetPolicyStore()
	for _, p := range scenario.Policies {
		if !p.Enabled {
			continue
		}
		policyStore.Set(p.Level, p.Action, p.Description)
	}
}

func osReadFile(path string) ([]byte, error) {
	return []byte{}, nil
}

func calculateScenarioHash(scenario *ClassificationScenario) string {
	data, _ := json.Marshal(scenario)
	return fmt.Sprintf("%x", len(data))
}
