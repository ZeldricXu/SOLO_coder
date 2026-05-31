package dynamicconfig

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"
)

type ConfigType string

const (
	ConfigTypeDocIndex    ConfigType = "doc_index"
	ConfigTypeQualityGate ConfigType = "quality_gate"
	ConfigTypeFeatureFlag ConfigType = "feature_flag"
)

type ConfigScenario string

const (
	ScenarioDefault      ConfigScenario = "default"
	ScenarioProduction   ConfigScenario = "production"
	ScenarioStaging      ConfigScenario = "staging"
	ScenarioDevelopment  ConfigScenario = "development"
	ScenarioPerformance  ConfigScenario = "performance"
	ScenarioSecurity     ConfigScenario = "security"
)

type DynamicConfig struct {
	ID           string                 `json:"id" gorm:"primaryKey"`
	ConfigType   ConfigType             `json:"config_type" gorm:"index"`
	Scenario     ConfigScenario         `json:"scenario" gorm:"index"`
	Key          string                 `json:"key" gorm:"index:idx_type_key"`
	Value        string                 `json:"value" gorm:"type:text"`
	ValueType    string                 `json:"value_type"`
	Description  string                 `json:"description"`
	DefaultValue string                 `json:"default_value"`
	IsActive     bool                   `json:"is_active" gorm:"default:true"`
	Version      int                    `json:"version" gorm:"default:1"`
	Tags         []string               `json:"tags" gorm:"serializer:json"`
	Metadata     map[string]interface{} `json:"metadata" gorm:"serializer:json"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

type ConfigChangeEvent struct {
	ID           string                 `json:"id"`
	ConfigType   ConfigType             `json:"config_type"`
	Scenario     ConfigScenario         `json:"scenario"`
	Key          string                 `json:"key"`
	OldValue     string                 `json:"old_value"`
	NewValue     string                 `json:"new_value"`
	ChangeType   string                 `json:"change_type"`
	ChangedAt    time.Time              `json:"changed_at"`
}

type ConfigChangeListener interface {
	OnConfigChange(event ConfigChangeEvent)
}

type Manager struct {
	scenario      ConfigScenario
	configs       map[string]map[string]*DynamicConfig
	listeners     map[ConfigType][]ConfigChangeListener
	eventCh       chan ConfigChangeEvent
	mu            sync.RWMutex
	autoPersist   bool
	initialized   bool
}

var (
	globalManager *Manager
	initOnce      sync.Once
)

func GetManager() *Manager {
	initOnce.Do(func() {
		globalManager = &Manager{
			scenario:    ScenarioDefault,
			configs:     make(map[string]map[string]*DynamicConfig),
			listeners:   make(map[ConfigType][]ConfigChangeListener),
			eventCh:     make(chan ConfigChangeEvent, 100),
			autoPersist: true,
		}
		go globalManager.processEvents()
	})
	return globalManager
}

func (m *Manager) SetScenario(scenario ConfigScenario) {
	m.mu.Lock()
	defer m.mu.Unlock()
	oldScenario := m.scenario
	m.scenario = scenario
	m.mu.Unlock()

	if oldScenario != scenario {
		m.mu.RLock()
		for configType, listeners := range m.listeners {
			for _, listener := range listeners {
				listener.OnConfigChange(ConfigChangeEvent{
					ConfigType: configType,
					Scenario:   scenario,
					ChangeType: "scenario_switch",
					ChangedAt:  time.Now(),
				})
			}
		}
		m.mu.RUnlock()
	}
}

func (m *Manager) GetScenario() ConfigScenario {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.scenario
}

func (m *Manager) RegisterListener(configType ConfigType, listener ConfigChangeListener) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.listeners[configType] = append(m.listeners[configType], listener)
}

func (m *Manager) Get(configType ConfigType, key string, scenario ...ConfigScenario) *DynamicConfig {
	sc := m.scenario
	if len(scenario) > 0 {
		sc = scenario[0]
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	typeKey := fmt.Sprintf("%s:%s", sc, configType)
	if typeConfigs, ok := m.configs[typeKey]; ok {
		if cfg, exists := typeConfigs[key]; exists && cfg.IsActive {
			return cfg
		}
	}

	defaultKey := fmt.Sprintf("%s:%s", ScenarioDefault, configType)
	if defaultConfigs, ok := m.configs[defaultKey]; ok {
		if cfg, exists := defaultConfigs[key]; exists && cfg.IsActive {
			return cfg
		}
	}

	return nil
}

func (m *Manager) GetString(configType ConfigType, key string, defaultValue string) string {
	cfg := m.Get(configType, key)
	if cfg == nil {
		return defaultValue
	}
	return cfg.Value
}

func (m *Manager) GetInt(configType ConfigType, key string, defaultValue int) int {
	cfg := m.Get(configType, key)
	if cfg == nil {
		return defaultValue
	}
	var result int
	if err := json.Unmarshal([]byte(cfg.Value), &result); err != nil {
		return defaultValue
	}
	return result
}

func (m *Manager) GetFloat64(configType ConfigType, key string, defaultValue float64) float64 {
	cfg := m.Get(configType, key)
	if cfg == nil {
		return defaultValue
	}
	var result float64
	if err := json.Unmarshal([]byte(cfg.Value), &result); err != nil {
		return defaultValue
	}
	return result
}

func (m *Manager) GetBool(configType ConfigType, key string, defaultValue bool) bool {
	cfg := m.Get(configType, key)
	if cfg == nil {
		return defaultValue
	}
	var result bool
	if err := json.Unmarshal([]byte(cfg.Value), &result); err != nil {
		return defaultValue
	}
	return result
}

func (m *Manager) GetMap(configType ConfigType, key string, defaultValue map[string]interface{}) map[string]interface{} {
	cfg := m.Get(configType, key)
	if cfg == nil {
		return defaultValue
	}
	var result map[string]interface{}
	if err := json.Unmarshal([]byte(cfg.Value), &result); err != nil {
		return defaultValue
	}
	return result
}

func (m *Manager) Set(configType ConfigType, key string, value interface{}, scenario ...ConfigScenario) error {
	sc := m.scenario
	if len(scenario) > 0 {
		sc = scenario[0]
	}

	valueBytes, err := json.Marshal(value)
	if err != nil {
		return err
	}

	var valueType string
	switch value.(type) {
	case string:
		valueType = "string"
	case int, int32, int64:
		valueType = "int"
	case float32, float64:
		valueType = "float64"
	case bool:
		valueType = "bool"
	default:
		valueType = "json"
	}

	typeKey := fmt.Sprintf("%s:%s", sc, configType)

	m.mu.Lock()
	if _, exists := m.configs[typeKey]; !exists {
		m.configs[typeKey] = make(map[string]*DynamicConfig)
	}

	oldValue := ""
	if existing, ok := m.configs[typeKey][key]; ok {
		oldValue = existing.Value
	}

	cfg := &DynamicConfig{
		ID:         fmt.Sprintf("cfg_%d", time.Now().UnixNano()),
		ConfigType: configType,
		Scenario:   sc,
		Key:        key,
		Value:      string(valueBytes),
		ValueType:  valueType,
		IsActive:   true,
		Version:    1,
		CreatedAt:  time.Now(),
		UpdatedAt:  time.Now(),
	}

	if existing, ok := m.configs[typeKey][key]; ok {
		cfg.ID = existing.ID
		cfg.Version = existing.Version + 1
		cfg.CreatedAt = existing.CreatedAt
	}

	m.configs[typeKey][key] = cfg
	m.mu.Unlock()

	if oldValue != string(valueBytes) {
		m.publishEvent(ConfigChangeEvent{
			ID:         cfg.ID,
			ConfigType: configType,
			Scenario:   sc,
			Key:        key,
			OldValue:   oldValue,
			NewValue:   string(valueBytes),
			ChangeType: "update",
			ChangedAt:  time.Now(),
		})
	}

	return nil
}

func (m *Manager) SetWithDescription(configType ConfigType, key string, value interface{}, description string, scenario ...ConfigScenario) error {
	err := m.Set(configType, key, value, scenario...)
	if err != nil {
		return err
	}

	sc := m.scenario
	if len(scenario) > 0 {
		sc = scenario[0]
	}

	typeKey := fmt.Sprintf("%s:%s", sc, configType)
	m.mu.Lock()
	if cfg, ok := m.configs[typeKey][key]; ok {
		cfg.Description = description
	}
	m.mu.Unlock()

	return nil
}

func (m *Manager) Delete(configType ConfigType, key string, scenario ...ConfigScenario) error {
	sc := m.scenario
	if len(scenario) > 0 {
		sc = scenario[0]
	}

	typeKey := fmt.Sprintf("%s:%s", sc, configType)

	m.mu.Lock()
	oldValue := ""
	if existing, ok := m.configs[typeKey][key]; ok {
		oldValue = existing.Value
		delete(m.configs[typeKey], key)
	}
	m.mu.Unlock()

	if oldValue != "" {
		m.publishEvent(ConfigChangeEvent{
			ConfigType: configType,
			Scenario:   sc,
			Key:        key,
			OldValue:   oldValue,
			ChangeType: "delete",
			ChangedAt:  time.Now(),
		})
	}

	return nil
}

func (m *Manager) GetAll(configType ConfigType, scenario ...ConfigScenario) map[string]*DynamicConfig {
	sc := m.scenario
	if len(scenario) > 0 {
		sc = scenario[0]
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	typeKey := fmt.Sprintf("%s:%s", sc, configType)
	result := make(map[string]*DynamicConfig)
	if configs, ok := m.configs[typeKey]; ok {
		for k, v := range configs {
			if v.IsActive {
				result[k] = v
			}
		}
	}

	defaultKey := fmt.Sprintf("%s:%s", ScenarioDefault, configType)
	if sc != ScenarioDefault {
		if defaultConfigs, ok := m.configs[defaultKey]; ok {
			for k, v := range defaultConfigs {
				if v.IsActive {
					if _, exists := result[k]; !exists {
						result[k] = v
					}
				}
			}
		}
	}

	return result
}

func (m *Manager) publishEvent(event ConfigChangeEvent) {
	select {
	case m.eventCh <- event:
	default:
	}
}

func (m *Manager) processEvents() {
	for event := range m.eventCh {
		m.mu.RLock()
		listeners, ok := m.listeners[event.ConfigType]
		m.mu.RUnlock()

		if ok {
			for _, listener := range listeners {
				go listener.OnConfigChange(event)
			}
		}
	}
}

func (m *Manager) Import(configs []*DynamicConfig) error {
	if configs == nil {
		return errors.New("configs cannot be nil")
	}

	for _, cfg := range configs {
		typeKey := fmt.Sprintf("%s:%s", cfg.Scenario, cfg.ConfigType)
		m.mu.Lock()
		if _, exists := m.configs[typeKey]; !exists {
			m.configs[typeKey] = make(map[string]*DynamicConfig)
		}
		m.configs[typeKey][cfg.Key] = cfg
		m.mu.Unlock()
	}

	return nil
}

func (m *Manager) Export(configType ConfigType, scenario ...ConfigScenario) []*DynamicConfig {
	configsMap := m.GetAll(configType, scenario...)
	result := make([]*DynamicConfig, 0, len(configsMap))
	for _, cfg := range configsMap {
		result = append(result, cfg)
	}
	return result
}

func (m *Manager) HotUpdate(ctx context.Context, updates []*DynamicConfig) error {
	if updates == nil {
		return errors.New("updates cannot be nil")
	}

	for _, cfg := range updates {
		err := m.Set(cfg.ConfigType, cfg.Key, cfg.Value, cfg.Scenario)
		if err != nil {
			return err
		}
	}

	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}

	return nil
}

func (m *Manager) Watch(ctx context.Context, configType ConfigType, handler func(event ConfigChangeEvent)) {
	listener := &simpleListener{handler: handler}
	m.RegisterListener(configType, listener)

	go func() {
		<-ctx.Done()
	}()
}

type simpleListener struct {
	handler func(event ConfigChangeEvent)
}

func (l *simpleListener) OnConfigChange(event ConfigChangeEvent) {
	if l.handler != nil {
		l.handler(event)
	}
}
