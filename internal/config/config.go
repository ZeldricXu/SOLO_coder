package config

import (
	"errors"
	"fmt"
	"gopkg.in/yaml.v3"
	"io/ioutil"
	"reflect"
	"strings"
	"sync"
	"time"
)

type Manager struct {
	mu          sync.RWMutex
	configs     map[string]*Config
	defaults    map[string]interface{}
	validators  map[string]Validator
	namespace   string
}

type Config struct {
	ConfigID  string                 `json:"config_id" yaml:"config_id"`
	Namespace string                 `json:"namespace" yaml:"namespace"`
	Version   int                    `json:"version" yaml:"version"`
	Params    map[string]interface{} `json:"parameters" yaml:"parameters"`
	Enabled   bool                   `json:"enabled" yaml:"enabled"`
	AppliedAt time.Time              `json:"applied_at" yaml:"applied_at"`
}

type Validator func(value interface{}) error

const (
	MaxConfigIDLength = 64
	MaxKeyLength      = 128
	MaxParamsCount    = 1000
	MaxStringLength   = 1024 * 1024
)

var (
	ErrConfigNotFound = errors.New("config not found")
	ErrInvalidType   = errors.New("invalid type")
	ErrValidation    = errors.New("validation failed")
	ErrLimitExceeded = errors.New("limit exceeded")
)

func NewManager(namespace string) *Manager {
	return &Manager{
		configs:    make(map[string]*Config),
		defaults:   make(map[string]interface{}),
		validators: make(map[string]Validator),
		namespace:  namespace,
	}
}

func (m *Manager) LoadFromFile(path string) error {
	data, err := ioutil.ReadFile(path)
	if err != nil {
		return fmt.Errorf("failed to read config file: %w", err)
	}

	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return fmt.Errorf("failed to parse config file: %w", err)
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if cfg.Namespace == "" {
		cfg.Namespace = m.namespace
	}
	if cfg.AppliedAt.IsZero() {
		cfg.AppliedAt = time.Now()
	}

	if err := m.validateConfig(&cfg); err != nil {
		return err
	}

	m.configs[cfg.ConfigID] = &cfg
	return nil
}

func (m *Manager) SetDefault(key string, value interface{}) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.defaults[key] = value
}

func (m *Manager) GetDefault(key string) (interface{}, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	v, ok := m.defaults[key]
	return v, ok
}

func (m *Manager) RegisterValidator(key string, validator Validator) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.validators[key] = validator
}

func (m *Manager) Get(configID string) (*Config, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	cfg, ok := m.configs[configID]
	if !ok {
		return nil, ErrConfigNotFound
	}
	return cfg, nil
}

func (m *Manager) Set(cfg *Config) error {
	if cfg == nil {
		return fmt.Errorf("%w: config cannot be nil", ErrValidation)
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if cfg.Namespace == "" {
		cfg.Namespace = m.namespace
	}
	if cfg.AppliedAt.IsZero() {
		cfg.AppliedAt = time.Now()
	}

	if err := m.validateConfig(cfg); err != nil {
		return err
	}

	existing, ok := m.configs[cfg.ConfigID]
	if ok {
		cfg.Version = existing.Version + 1
	} else {
		cfg.Version = 1
	}

	if cfg.Params == nil {
		cfg.Params = make(map[string]interface{})
	}

	m.configs[cfg.ConfigID] = cfg
	return nil
}

func (m *Manager) GetParam(configID, key string) (interface{}, error) {
	cfg, err := m.Get(configID)
	if err != nil {
		defaultVal, ok := m.GetDefault(key)
		if ok {
			return defaultVal, nil
		}
		return nil, err
	}

	val, ok := cfg.Params[key]
	if !ok {
		defaultVal, ok := m.GetDefault(key)
		if ok {
			return defaultVal, nil
		}
		return nil, fmt.Errorf("parameter %s not found", key)
	}

	return val, nil
}

func (m *Manager) GetString(configID, key string) (string, error) {
	val, err := m.GetParam(configID, key)
	if err != nil {
		return "", err
	}
	s, ok := val.(string)
	if !ok {
		return "", fmt.Errorf("%w: expected string, got %T", ErrInvalidType, val)
	}
	return s, nil
}

func (m *Manager) GetInt(configID, key string) (int, error) {
	val, err := m.GetParam(configID, key)
	if err != nil {
		return 0, err
	}
	switch v := val.(type) {
	case int:
		return v, nil
	case int32:
		return int(v), nil
	case int64:
		return int(v), nil
	case float64:
		if v != float64(int(v)) {
			return 0, fmt.Errorf("%w: float64 value %v has fractional part, cannot convert to int", ErrInvalidType, v)
		}
		return int(v), nil
	}
	return 0, fmt.Errorf("%w: expected int, got %T", ErrInvalidType, val)
}

func (m *Manager) GetBool(configID, key string) (bool, error) {
	val, err := m.GetParam(configID, key)
	if err != nil {
		return false, err
	}
	b, ok := val.(bool)
	if !ok {
		return false, fmt.Errorf("%w: expected bool, got %T", ErrInvalidType, val)
	}
	return b, nil
}

func (m *Manager) GetFloat64(configID, key string) (float64, error) {
	val, err := m.GetParam(configID, key)
	if err != nil {
		return 0, err
	}
	switch v := val.(type) {
	case float64:
		return v, nil
	case int:
		return float64(v), nil
	}
	return 0, fmt.Errorf("%w: expected float64, got %T", ErrInvalidType, val)
}

func (m *Manager) GetDuration(configID, key string) (time.Duration, error) {
	val, err := m.GetString(configID, key)
	if err != nil {
		return 0, err
	}
	return time.ParseDuration(val)
}

func (m *Manager) validateConfig(cfg *Config) error {
	if cfg.ConfigID == "" {
		return fmt.Errorf("%w: config_id is required", ErrValidation)
	}

	if len(cfg.ConfigID) > MaxConfigIDLength {
		return fmt.Errorf("%w: config_id exceeds maximum length %d (got %d)", ErrLimitExceeded, MaxConfigIDLength, len(cfg.ConfigID))
	}

	if cfg.Params != nil {
		if len(cfg.Params) > MaxParamsCount {
			return fmt.Errorf("%w: too many parameters %d (max %d)", ErrLimitExceeded, len(cfg.Params), MaxParamsCount)
		}

		for key, val := range cfg.Params {
			if len(key) > MaxKeyLength {
				return fmt.Errorf("%w: parameter key '%s' exceeds maximum length %d", ErrLimitExceeded, key, MaxKeyLength)
			}

			if err := m.validateParameterValue(key, val); err != nil {
				return err
			}
		}
	}

	for key, validator := range m.validators {
		if cfg.Params != nil {
			if val, ok := cfg.Params[key]; ok {
				if err := validator(val); err != nil {
					return fmt.Errorf("%w: %s validation failed: %v", ErrValidation, key, err)
				}
			}
		}
	}

	return nil
}

func (m *Manager) validateParameterValue(key string, val interface{}) error {
	switch v := val.(type) {
	case string:
		if len(v) > MaxStringLength {
			return fmt.Errorf("%w: parameter '%s' string value exceeds maximum length %d", ErrLimitExceeded, key, MaxStringLength)
		}
	case map[string]interface{}:
		if len(v) > MaxParamsCount {
			return fmt.Errorf("%w: parameter '%s' nested map exceeds maximum size %d", ErrLimitExceeded, key, MaxParamsCount)
		}
		for k, nestedVal := range v {
			if len(k) > MaxKeyLength {
				return fmt.Errorf("%w: nested key '%s' in parameter '%s' exceeds maximum length %d", ErrLimitExceeded, k, key, MaxKeyLength)
			}
			if err := m.validateParameterValue(key+"."+k, nestedVal); err != nil {
				return err
			}
		}
	case []interface{}:
		if len(v) > MaxParamsCount {
			return fmt.Errorf("%w: parameter '%s' array exceeds maximum size %d", ErrLimitExceeded, key, MaxParamsCount)
		}
		for i, item := range v {
			if err := m.validateParameterValue(fmt.Sprintf("%s[%d]", key, i), item); err != nil {
				return err
			}
		}
	}
	return nil
}

func (m *Manager) List() []*Config {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*Config, 0, len(m.configs))
	for _, cfg := range m.configs {
		result = append(result, cfg)
	}
	return result
}

func (m *Manager) Delete(configID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.configs, configID)
}

func (m *Manager) ApplyDefaults(cfg *Config) *Config {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if cfg.Params == nil {
		cfg.Params = make(map[string]interface{})
	}

	for key, defVal := range m.defaults {
		if _, ok := cfg.Params[key]; !ok {
			cfg.Params[key] = defVal
		}
	}

	return cfg
}

func ValidateRange(min, max int) Validator {
	return func(value interface{}) error {
		v, ok := value.(int)
		if !ok {
			return fmt.Errorf("expected int")
		}
		if v < min || v > max {
			return fmt.Errorf("value %d out of range [%d, %d]", v, min, max)
		}
		return nil
	}
}

func ValidateRequired() Validator {
	return func(value interface{}) error {
		if value == nil {
			return fmt.Errorf("value is required")
		}
		v := reflect.ValueOf(value)
		if v.Kind() == reflect.String && v.String() == "" {
			return fmt.Errorf("value is required")
		}
		return nil
	}
}

func ValidateEnum(values ...string) Validator {
	return func(value interface{}) error {
		s, ok := value.(string)
		if !ok {
			return fmt.Errorf("expected string")
		}
		for _, v := range values {
			if s == v {
				return nil
			}
		}
		return fmt.Errorf("value %s not in allowed set: %s", s, strings.Join(values, ", "))
	}
}

func ValidateMinLength(min int) Validator {
	return func(value interface{}) error {
		s, ok := value.(string)
		if !ok {
			return fmt.Errorf("expected string")
		}
		if len(s) < min {
			return fmt.Errorf("string length %d less than minimum %d", len(s), min)
		}
		return nil
	}
}
