package model

import (
	"context"
	"encoding/json"
	"fmt"
	"math/rand"
	"sync"
	"time"

	"go.uber.org/zap"
)

type ScenarioType string

const (
	ScenarioDevelopment ScenarioType = "development"
	ScenarioStaging     ScenarioType = "staging"
	ScenarioProduction  ScenarioType = "production"
)

type StageRule struct {
	MinVersions  int      `json:"min_versions"`
	RequiredChecks []string `json:"required_checks"`
	AutoPromote  bool     `json:"auto_promote"`
	MaxAgeHours  int      `json:"max_age_hours"`
}

type ModelConfig struct {
	MaxVersionsPerModel int                 `json:"max_versions_per_model"`
	MaxNameLength       int                 `json:"max_name_length"`
	MaxDescriptionLen   int                 `json:"max_description_len"`
	DefaultTags         []string            `json:"default_tags"`
	StageRules          map[Stage]StageRule `json:"stage_rules"`
	AutoArchiveEnabled  bool                `json:"auto_archive_enabled"`
	AutoArchiveDays     int                 `json:"auto_archive_days"`
	DescriptionRequired bool                `json:"description_required"`
}

type ScenarioConfig struct {
	Scenario ScenarioType `json:"scenario"`
	Config   ModelConfig  `json:"config"`
}

type ConfigChangeListener interface {
	OnConfigChanged(scenario ScenarioType, oldConfig, newConfig ModelConfig)
}

type DynamicConfigManager struct {
	configs       map[ScenarioType]ModelConfig
	currentScenario ScenarioType
	listeners     map[string]ConfigChangeListener
	mu            sync.RWMutex
	logger        *zap.Logger
}

func NewDynamicConfigManager(logger *zap.Logger) *DynamicConfigManager {
	mgr := &DynamicConfigManager{
		configs:       make(map[ScenarioType]ModelConfig),
		currentScenario: ScenarioDevelopment,
		listeners:     make(map[string]ConfigChangeListener),
		logger:        logger,
	}

	mgr.initDefaultConfigs()
	return mgr
}

func (m *DynamicConfigManager) initDefaultConfigs() {
	m.configs[ScenarioDevelopment] = ModelConfig{
		MaxVersionsPerModel: 100,
		MaxNameLength:       128,
		MaxDescriptionLen:   2000,
		DefaultTags:         []string{"dev"},
		StageRules: map[Stage]StageRule{
			StageDevelopment: {
				MinVersions:  0,
				RequiredChecks: []string{},
				AutoPromote:  true,
				MaxAgeHours:  24,
			},
			StageStaging: {
				MinVersions:  1,
				RequiredChecks: []string{"lint", "unit_test"},
				AutoPromote:  false,
				MaxAgeHours:  72,
			},
			StageProduction: {
				MinVersions:  3,
				RequiredChecks: []string{"lint", "unit_test", "integration_test", "stress_test"},
				AutoPromote:  false,
				MaxAgeHours:  168,
			},
			StageArchived: {
				MinVersions:  0,
				RequiredChecks: []string{},
				AutoPromote:  false,
				MaxAgeHours:  0,
			},
		},
		AutoArchiveEnabled:  true,
		AutoArchiveDays:     30,
		DescriptionRequired: false,
	}

	m.configs[ScenarioStaging] = ModelConfig{
		MaxVersionsPerModel: 50,
		MaxNameLength:       128,
		MaxDescriptionLen:   1000,
		DefaultTags:         []string{"staging"},
		StageRules: map[Stage]StageRule{
			StageDevelopment: {
				MinVersions:  0,
				RequiredChecks: []string{},
				AutoPromote:  false,
				MaxAgeHours:  48,
			},
			StageStaging: {
				MinVersions:  2,
				RequiredChecks: []string{"lint", "unit_test"},
				AutoPromote:  true,
				MaxAgeHours:  72,
			},
			StageProduction: {
				MinVersions:  5,
				RequiredChecks: []string{"lint", "unit_test", "integration_test"},
				AutoPromote:  false,
				MaxAgeHours:  168,
			},
			StageArchived: {
				MinVersions:  0,
				RequiredChecks: []string{},
				AutoPromote:  false,
				MaxAgeHours:  0,
			},
		},
		AutoArchiveEnabled:  true,
		AutoArchiveDays:     14,
		DescriptionRequired: true,
	}

	m.configs[ScenarioProduction] = ModelConfig{
		MaxVersionsPerModel: 20,
		MaxNameLength:       64,
		MaxDescriptionLen:   500,
		DefaultTags:         []string{"production"},
		StageRules: map[Stage]StageRule{
			StageDevelopment: {
				MinVersions:  1,
				RequiredChecks: []string{"lint", "unit_test"},
				AutoPromote:  false,
				MaxAgeHours:  24,
			},
			StageStaging: {
				MinVersions:  3,
				RequiredChecks: []string{"lint", "unit_test", "integration_test"},
				AutoPromote:  false,
				MaxAgeHours:  48,
			},
			StageProduction: {
				MinVersions:  5,
				RequiredChecks: []string{"lint", "unit_test", "integration_test", "stress_test", "security_scan"},
				AutoPromote:  false,
				MaxAgeHours:  720,
			},
			StageArchived: {
				MinVersions:  0,
				RequiredChecks: []string{},
				AutoPromote:  false,
				MaxAgeHours:  0,
			},
		},
		AutoArchiveEnabled:  true,
		AutoArchiveDays:     7,
		DescriptionRequired: true,
	}
}

func (m *DynamicConfigManager) SetScenario(scenario ScenarioType) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, ok := m.configs[scenario]; !ok {
		m.logger.Warn("Unknown scenario, using development", zap.String("scenario", string(scenario)))
		scenario = ScenarioDevelopment
	}

	oldScenario := m.currentScenario
	m.currentScenario = scenario

	if oldScenario != scenario {
		oldConfig := m.configs[oldScenario]
		newConfig := m.configs[scenario]
		m.notifyListeners(oldScenario, oldConfig, newConfig)
		m.logger.Info("Scenario changed",
			zap.String("old", string(oldScenario)),
			zap.String("new", string(scenario)),
		)
	}
}

func (m *DynamicConfigManager) GetCurrentScenario() ScenarioType {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.currentScenario
}

func (m *DynamicConfigManager) GetConfig() ModelConfig {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.configs[m.currentScenario]
}

func (m *DynamicConfigManager) GetConfigForScenario(scenario ScenarioType) (ModelConfig, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	config, ok := m.configs[scenario]
	if !ok {
		return ModelConfig{}, fmt.Errorf("scenario %s not found", scenario)
	}
	return config, nil
}

func (m *DynamicConfigManager) UpdateConfig(scenario ScenarioType, config ModelConfig) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	oldConfig, ok := m.configs[scenario]
	if !ok {
		return fmt.Errorf("scenario %s not found", scenario)
	}

	m.configs[scenario] = config
	m.logger.Info("Config updated", zap.String("scenario", string(scenario)))

	if scenario == m.currentScenario {
		m.notifyListeners(scenario, oldConfig, config)
	}

	return nil
}

func (m *DynamicConfigManager) PatchConfig(scenario ScenarioType, patches map[string]interface{}) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	config, ok := m.configs[scenario]
	if !ok {
		return fmt.Errorf("scenario %s not found", scenario)
	}

	oldConfig := config

	configJSON, _ := json.Marshal(config)
	var configMap map[string]interface{}
	json.Unmarshal(configJSON, &configMap)

	for k, v := range patches {
		configMap[k] = v
	}

	updatedJSON, _ := json.Marshal(configMap)
	json.Unmarshal(updatedJSON, &config)

	m.configs[scenario] = config
	m.logger.Info("Config patched", zap.String("scenario", string(scenario)), zap.Any("patches", patches))

	if scenario == m.currentScenario {
		m.notifyListeners(scenario, oldConfig, config)
	}

	return nil
}

func (m *DynamicConfigManager) AddListener(id string, listener ConfigChangeListener) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.listeners[id] = listener
}

func (m *DynamicConfigManager) RemoveListener(id string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.listeners, id)
}

func (m *DynamicConfigManager) notifyListeners(scenario ScenarioType, oldConfig, newConfig ModelConfig) {
	for id, listener := range m.listeners {
		go func(l ConfigChangeListener, lid string) {
			defer func() {
				if r := recover(); r != nil {
					m.logger.Error("Config listener panicked",
						zap.String("listener_id", lid),
						zap.Any("recover", r),
					)
				}
			}()
			l.OnConfigChanged(scenario, oldConfig, newConfig)
		}(listener, id)
	}
}

func (m *DynamicConfigManager) ExportAllConfigs() map[ScenarioType]ModelConfig {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make(map[ScenarioType]ModelConfig)
	for k, v := range m.configs {
		result[k] = v
	}
	return result
}

func (m *DynamicConfigManager) ValidateModelCreation(name, description string) error {
	config := m.GetConfig()

	if len(name) == 0 {
		return fmt.Errorf("模型名称不能为空")
	}
	if len(name) > config.MaxNameLength {
		return fmt.Errorf("模型名称不能超过%d个字符", config.MaxNameLength)
	}
	if config.DescriptionRequired && len(description) == 0 {
		return fmt.Errorf("模型描述不能为空（当前场景要求）")
	}
	if len(description) > config.MaxDescriptionLen {
		return fmt.Errorf("模型描述不能超过%d个字符", config.MaxDescriptionLen)
	}

	return nil
}

func (m *DynamicConfigManager) CanTransitionStage(from, to Stage, versionCount int, checks []string) (bool, string) {
	config := m.GetConfig()
	rule, ok := config.StageRules[to]
	if !ok {
		return false, fmt.Sprintf("目标Stage %s 没有配置规则", to)
	}

	if versionCount < rule.MinVersions {
		return false, fmt.Sprintf("至少需要%d个版本才能流转到%s，当前有%d个",
			rule.MinVersions, to, versionCount)
	}

	checkMap := make(map[string]bool)
	for _, c := range checks {
		checkMap[c] = true
	}

	for _, required := range rule.RequiredChecks {
		if !checkMap[required] {
			return false, fmt.Sprintf("缺少必要的检查项: %s", required)
		}
	}

	return true, ""
}

func (m *DynamicConfigManager) StartAutoArchive(ctx context.Context, archiveFn func(ageDays int) error) {
	if !m.GetConfig().AutoArchiveEnabled {
		m.logger.Info("Auto archive disabled")
		return
	}

	go func() {
		ticker := time.NewTicker(1 * time.Hour)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				m.logger.Info("Auto archive stopped")
				return
			case <-ticker.C:
				config := m.GetConfig()
				if !config.AutoArchiveEnabled {
					continue
				}
				if err := archiveFn(config.AutoArchiveDays); err != nil {
					m.logger.Error("Auto archive failed", zap.Error(err))
				}
			}
		}
	}()
}

func (m *DynamicConfigManager) SimulateRandomScenario() ScenarioType {
	scenarios := []ScenarioType{ScenarioDevelopment, ScenarioStaging, ScenarioProduction}
	selected := scenarios[rand.Intn(len(scenarios))]
	m.SetScenario(selected)
	return selected
}
