package config

import (
	"sync"
	"time"
)

type BillingConfig struct {
	ID               string                 `json:"id"`
	Name             string                 `json:"name"`
	Scenario         string                 `json:"scenario"`
	BasePrice        float64                `json:"base_price"`
	Currency         string                 `json:"currency"`
	PricingRules     []PricingRuleConfig   `json:"pricing_rules"`
	DiscountPolicies []DiscountPolicy      `json:"discount_policies"`
	InvoiceSettings  InvoiceSettings       `json:"invoice_settings"`
	IsDefault        bool                   `json:"is_default"`
	Enabled          bool                   `json:"enabled"`
	EffectiveFrom    *time.Time             `json:"effective_from"`
	EffectiveTo      *time.Time             `json:"effective_to"`
	CreatedAt        time.Time              `json:"created_at"`
	UpdatedAt        time.Time              `json:"updated_at"`
	Version          int                    `json:"version"`
}

type PricingRuleConfig struct {
	ResourceType string  `json:"resource_type"`
	UnitPrice    float64 `json:"unit_price"`
	Unit         string  `json:"unit"`
	FreeTier     float64 `json:"free_tier"`
	DiscountTier float64 `json:"discount_tier"`
	DiscountRate float64 `json:"discount_rate"`
}

type DiscountPolicy struct {
	ID           string  `json:"id"`
	Name         string  `json:"name"`
	Type         string  `json:"type"`
	Condition    string  `json:"condition"`
	DiscountRate float64 `json:"discount_rate"`
	MaxDiscount  float64 `json:"max_discount"`
	Priority     int     `json:"priority"`
}

type InvoiceSettings struct {
	DueDays       int      `json:"due_days"`
	PaymentMethods []string `json:"payment_methods"`
	ReminderDays  []int    `json:"reminder_days"`
	AutoGenerate  bool     `json:"auto_generate"`
	NotifyChannels []string `json:"notify_channels"`
}

type ConfigChangeEvent struct {
	ConfigID  string    `json:"config_id"`
	OldConfig *BillingConfig `json:"old_config"`
	NewConfig *BillingConfig `json:"new_config"`
	ChangedAt time.Time `json:"changed_at"`
	Operator  string    `json:"operator"`
}

type ConfigChangeListener func(event ConfigChangeEvent)

type DynamicConfigManager interface {
	GetConfig(scenario string, tenantID string) (*BillingConfig, error)
	GetDefaultConfig() (*BillingConfig, error)
	UpdateConfig(config *BillingConfig, operator string) error
	AddConfig(config *BillingConfig, operator string) error
	DeleteConfig(configID string, operator string) error
	ListConfigs(scenario string) ([]BillingConfig, error)
	RegisterChangeListener(listener ConfigChangeListener)
	UnregisterChangeListener(listener ConfigChangeListener)
	HotReload() error
	GetChangeHistory(configID string, limit int) ([]ConfigChangeEvent, error)
}

type inMemoryConfigManager struct {
	mu           sync.RWMutex
	configs      map[string]*BillingConfig
	scenarioMap  map[string]map[string]*BillingConfig
	listeners    []ConfigChangeListener
	history      map[string][]ConfigChangeEvent
}

func NewDynamicConfigManager() DynamicConfigManager {
	return &inMemoryConfigManager{
		configs:     make(map[string]*BillingConfig),
		scenarioMap: make(map[string]map[string]*BillingConfig),
		history:     make(map[string][]ConfigChangeEvent),
	}
}

func (m *inMemoryConfigManager) GetConfig(scenario string, tenantID string) (*BillingConfig, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if scenarioConfigs, ok := m.scenarioMap[scenario]; ok {
		for _, cfg := range scenarioConfigs {
			if cfg.Enabled && m.isEffective(cfg) {
				return cfg, nil
			}
		}
	}
	return m.getDefaultConfigLocked(), nil
}

func (m *inMemoryConfigManager) GetDefaultConfig() (*BillingConfig, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.getDefaultConfigLocked(), nil
}

func (m *inMemoryConfigManager) getDefaultConfigLocked() *BillingConfig {
	for _, cfg := range m.configs {
		if cfg.IsDefault && cfg.Enabled {
			return cfg
		}
	}
	return m.getFallbackConfig()
}

func (m *inMemoryConfigManager) getFallbackConfig() *BillingConfig {
	return &BillingConfig{
		ID:        "default-fallback",
		Name:      "Default Billing Config",
		Scenario:  "default",
		BasePrice: 99.0,
		Currency:  "CNY",
		PricingRules: []PricingRuleConfig{
			{ResourceType: "storage", UnitPrice: 0.01, Unit: "GB", FreeTier: 10},
			{ResourceType: "requests", UnitPrice: 0.0001, Unit: "1000_requests", FreeTier: 10000},
			{ResourceType: "bandwidth", UnitPrice: 0.5, Unit: "GB", FreeTier: 100},
		},
		InvoiceSettings: InvoiceSettings{
			DueDays:       15,
			PaymentMethods: []string{"alipay", "wechat", "bank_transfer"},
			ReminderDays:  []int{3, 7, 15},
			AutoGenerate:  true,
			NotifyChannels: []string{"email", "sms"},
		},
		IsDefault: true,
		Enabled:   true,
		Version:   1,
	}
}

func (m *inMemoryConfigManager) isEffective(cfg *BillingConfig) bool {
	now := time.Now()
	if cfg.EffectiveFrom != nil && now.Before(*cfg.EffectiveFrom) {
		return false
	}
	if cfg.EffectiveTo != nil && now.After(*cfg.EffectiveTo) {
		return false
	}
	return true
}

func (m *inMemoryConfigManager) UpdateConfig(config *BillingConfig, operator string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	oldConfig := m.configs[config.ID]
	config.Version++
	config.UpdatedAt = time.Now()
	if config.IsDefault {
		for _, cfg := range m.configs {
			if cfg.ID != config.ID {
				cfg.IsDefault = false
			}
		}
	}
	m.configs[config.ID] = config
	if _, ok := m.scenarioMap[config.Scenario]; !ok {
		m.scenarioMap[config.Scenario] = make(map[string]*BillingConfig)
	}
	m.scenarioMap[config.Scenario][config.ID] = config
	event := ConfigChangeEvent{
		ConfigID:  config.ID,
		OldConfig: oldConfig,
		NewConfig: config,
		ChangedAt: time.Now(),
		Operator:  operator,
	}
	m.history[config.ID] = append(m.history[config.ID], event)
	m.notifyListeners(event)
	return nil
}

func (m *inMemoryConfigManager) AddConfig(config *BillingConfig, operator string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	config.CreatedAt = time.Now()
	config.UpdatedAt = time.Now()
	config.Version = 1
	if config.IsDefault {
		for _, cfg := range m.configs {
			cfg.IsDefault = false
		}
	}
	m.configs[config.ID] = config
	if _, ok := m.scenarioMap[config.Scenario]; !ok {
		m.scenarioMap[config.Scenario] = make(map[string]*BillingConfig)
	}
	m.scenarioMap[config.Scenario][config.ID] = config
	event := ConfigChangeEvent{
		ConfigID:  config.ID,
		OldConfig: nil,
		NewConfig: config,
		ChangedAt: time.Now(),
		Operator:  operator,
	}
	m.history[config.ID] = append(m.history[config.ID], event)
	m.notifyListeners(event)
	return nil
}

func (m *inMemoryConfigManager) DeleteConfig(configID string, operator string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	oldConfig := m.configs[configID]
	delete(m.configs, configID)
	for scenario, configs := range m.scenarioMap {
		delete(configs, configID)
		if len(configs) == 0 {
			delete(m.scenarioMap, scenario)
		}
	}
	event := ConfigChangeEvent{
		ConfigID:  configID,
		OldConfig: oldConfig,
		NewConfig: nil,
		ChangedAt: time.Now(),
		Operator:  operator,
	}
	m.history[configID] = append(m.history[configID], event)
	m.notifyListeners(event)
	return nil
}

func (m *inMemoryConfigManager) ListConfigs(scenario string) ([]BillingConfig, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var configs []BillingConfig
	if scenario != "" {
		if scenarioConfigs, ok := m.scenarioMap[scenario]; ok {
			for _, cfg := range scenarioConfigs {
				configs = append(configs, *cfg)
			}
		}
	} else {
		for _, cfg := range m.configs {
			configs = append(configs, *cfg)
		}
	}
	return configs, nil
}

func (m *inMemoryConfigManager) RegisterChangeListener(listener ConfigChangeListener) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.listeners = append(m.listeners, listener)
}

func (m *inMemoryConfigManager) UnregisterChangeListener(listener ConfigChangeListener) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i, l := range m.listeners {
		if &l == &listener {
			m.listeners = append(m.listeners[:i], m.listeners[i+1:]...)
			break
		}
	}
}

func (m *inMemoryConfigManager) notifyListeners(event ConfigChangeEvent) {
	for _, listener := range m.listeners {
		go listener(event)
	}
}

func (m *inMemoryConfigManager) HotReload() error {
	return nil
}

func (m *inMemoryConfigManager) GetChangeHistory(configID string, limit int) ([]ConfigChangeEvent, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	history := m.history[configID]
	if limit > 0 && len(history) > limit {
		history = history[len(history)-limit:]
	}
	return history, nil
}
