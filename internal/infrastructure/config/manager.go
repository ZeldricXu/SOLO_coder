package config

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"time"

	"github.com/solocoder/session148/internal/domain"
	apperr "github.com/solocoder/session148/pkg/errors"
	"github.com/solocoder/session148/pkg/utils"
)

type FileConfigManager struct {
	configDir string
	defaults  map[string]map[string]interface{}
	mu        sync.RWMutex
	logger    domain.Logger
}

type ConfigManagerConfig struct {
	ConfigDir string
	Logger    domain.Logger
}

func NewFileConfigManager(cfg ConfigManagerConfig) *FileConfigManager {
	if cfg.ConfigDir == "" {
		cfg.ConfigDir = "./config"
	}
	os.MkdirAll(cfg.ConfigDir, 0755)

	return &FileConfigManager{
		configDir: cfg.ConfigDir,
		defaults:  make(map[string]map[string]interface{}),
		logger:    cfg.Logger,
	}
}

func (m *FileConfigManager) Load(ctx context.Context, namespace string) (*domain.ConfigDefinition, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	path := filepath.Join(m.configDir, namespace+".json")
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			defaults := m.GetDefault(namespace)
			return &domain.ConfigDefinition{
				ConfigID:   utils.NewConfigID(),
				Namespace:  namespace,
				Version:    1,
				Parameters: defaults,
				Enabled:    true,
				AppliedAt:  time.Now().UTC(),
			}, nil
		}
		return nil, apperr.NewInternalError(fmt.Sprintf("failed to load config: %v", err))
	}

	var config domain.ConfigDefinition
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, apperr.NewInternalError(fmt.Sprintf("invalid config format: %v", err))
	}

	defaults := m.GetDefault(namespace)
	for k, v := range defaults {
		if _, exists := config.Parameters[k]; !exists {
			config.Parameters[k] = v
		}
	}

	return &config, nil
}

func (m *FileConfigManager) Save(ctx context.Context, config *domain.ConfigDefinition) error {
	if err := m.Validate(config); err != nil {
		return err
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	config.Version++
	config.AppliedAt = time.Now().UTC()

	path := filepath.Join(m.configDir, config.Namespace+".json")
	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return apperr.NewInternalError(fmt.Sprintf("failed to marshal config: %v", err))
	}

	if err := os.WriteFile(path, data, 0644); err != nil {
		return apperr.NewInternalError(fmt.Sprintf("failed to write config: %v", err))
	}

	m.logger.Info("config saved", "namespace", config.Namespace, "version", config.Version)
	return nil
}

func (m *FileConfigManager) Validate(config *domain.ConfigDefinition) error {
	if config == nil {
		return apperr.NewValidationError("config is required", "config cannot be nil")
	}
	if config.Namespace == "" {
		return apperr.NewValidationError("namespace is required", "namespace cannot be empty")
	}

	var violations []string

	schema, ok := m.getSchema(config.Namespace)
	if ok {
		for field, constraints := range schema {
			value, exists := config.Parameters[field]
			if !exists {
				if _, hasDefault := m.GetDefault(config.Namespace)[field]; !hasDefault {
					violations = append(violations, fmt.Sprintf("missing required field: %s", field))
				}
				continue
			}

			if constraint, ok := constraints.(map[string]interface{}); ok {
				if expectedType, ok := constraint["type"].(string); ok {
					if !m.validateType(value, expectedType) {
						violations = append(violations, fmt.Sprintf("field '%s' expected type %s, got %T", field, expectedType, value))
					}
				}
				if min, ok := constraint["min"].(float64); ok {
					if num, ok := value.(float64); ok && num < min {
						violations = append(violations, fmt.Sprintf("field '%s' must be >= %v", field, min))
					}
				}
				if max, ok := constraint["max"].(float64); ok {
					if num, ok := value.(float64); ok && num > max {
						violations = append(violations, fmt.Sprintf("field '%s' must be <= %v", field, max))
					}
				}
			}
		}
	}

	if len(violations) > 0 {
		return apperr.NewValidationError("config validation failed", strings.Join(violations, "; "))
	}

	return nil
}

func (m *FileConfigManager) GetDefault(namespace string) map[string]interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if defaults, ok := m.defaults[namespace]; ok {
		result := make(map[string]interface{})
		for k, v := range defaults {
			result[k] = v
		}
		return result
	}

	commonDefaults := map[string]interface{}{
		"timeout":        30,
		"retries":        3,
		"max_concurrent": 10,
		"log_level":      "info",
		"enabled":        true,
	}
	return commonDefaults
}

func (m *FileConfigManager) RegisterDefaults(namespace string, defaults map[string]interface{}) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.defaults[namespace] = defaults
}

func (m *FileConfigManager) validateType(value interface{}, expectedType string) bool {
	switch expectedType {
	case "string":
		_, ok := value.(string)
		return ok
	case "int", "number":
		switch value.(type) {
		case int, float64:
			return true
		}
		return false
	case "bool":
		_, ok := value.(bool)
		return ok
	case "array":
		_, ok := value.([]interface{})
		return ok
	case "object":
		_, ok := value.(map[string]interface{})
		return ok
	}
	return true
}

func (m *FileConfigManager) getSchema(namespace string) (map[string]interface{}, bool) {
	schemas := map[string]map[string]interface{}{
		"production": {
			"timeout":        map[string]interface{}{"type": "int", "min": 1, "max": 300},
			"retries":        map[string]interface{}{"type": "int", "min": 0, "max": 10},
			"max_concurrent": map[string]interface{}{"type": "int", "min": 1, "max": 1000},
		},
	}
	schema, ok := schemas[namespace]
	return schema, ok
}

func (m *FileConfigManager) ListNamespaces() ([]string, error) {
	files, err := os.ReadDir(m.configDir)
	if err != nil {
		return nil, err
	}

	var namespaces []string
	for _, f := range files {
		if !f.IsDir() && strings.HasSuffix(f.Name(), ".json") {
			namespaces = append(namespaces, strings.TrimSuffix(f.Name(), ".json"))
		}
	}
	return namespaces, nil
}

func (m *FileConfigManager) Watch(namespace string, onChange func(*domain.ConfigDefinition)) {
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()

		var lastHash string
		for range ticker.C {
			config, err := m.Load(context.Background(), namespace)
			if err != nil {
				continue
			}
			data, _ := json.Marshal(config)
			hash := utils.HashBytes(data)
			if hash != lastHash {
				lastHash = hash
				onChange(config)
			}
		}
	}()
}

func (m *FileConfigManager) DeepCopy(src map[string]interface{}) map[string]interface{} {
	dst := make(map[string]interface{})
	for k, v := range src {
		dst[k] = deepCopyValue(v)
	}
	return dst
}

func deepCopyValue(v interface{}) interface{} {
	switch val := v.(type) {
	case map[string]interface{}:
		dst := make(map[string]interface{})
		for mk, mv := range val {
			dst[mk] = deepCopyValue(mv)
		}
		return dst
	case []interface{}:
		dst := make([]interface{}, len(val))
		for i, item := range val {
			dst[i] = deepCopyValue(item)
		}
		return dst
	default:
		return v
	}
}

func (m *FileConfigManager) Merge(base, overlay map[string]interface{}) map[string]interface{} {
	result := m.DeepCopy(base)
	for k, v := range overlay {
		if existing, ok := result[k]; ok {
			if existingMap, ok := existing.(map[string]interface{}); ok {
				if overlayMap, ok := v.(map[string]interface{}); ok {
					result[k] = m.Merge(existingMap, overlayMap)
					continue
				}
			}
		}
		result[k] = v
	}
	return result
}

type ValidationRule func(map[string]interface{}) error

func (m *FileConfigManager) RegisterValidationRule(namespace string, rule ValidationRule) {
}

func (m *FileConfigManager) GetParameter(config *domain.ConfigDefinition, path string) (interface{}, bool) {
	parts := strings.Split(path, ".")
	var current interface{} = config.Parameters

	for _, part := range parts {
		if m, ok := current.(map[string]interface{}); ok {
			current, ok = m[part]
			if !ok {
				return nil, false
			}
		} else {
			return nil, false
		}
	}
	return current, true
}

func (m *FileConfigManager) SetParameter(config *domain.ConfigDefinition, path string, value interface{}) {
	parts := strings.Split(path, ".")
	current := config.Parameters

	for i, part := range parts {
		if i == len(parts)-1 {
			current[part] = value
			return
		}

		if _, ok := current[part]; !ok {
			current[part] = make(map[string]interface{})
		}

		if next, ok := current[part].(map[string]interface{}); ok {
			current = next
		} else {
			newMap := make(map[string]interface{})
			current[part] = newMap
			current = newMap
		}
	}
}

func (m *FileConfigManager) Diff(oldCfg, newCfg *domain.ConfigDefinition) []string {
	var changes []string
	oldParams := oldCfg.Parameters
	newParams := newCfg.Parameters

	for k, oldVal := range oldParams {
		if newVal, exists := newParams[k]; exists {
			if !reflect.DeepEqual(oldVal, newVal) {
				changes = append(changes, fmt.Sprintf("%s changed", k))
			}
		} else {
			changes = append(changes, fmt.Sprintf("%s removed", k))
		}
	}

	for k := range newParams {
		if _, exists := oldParams[k]; !exists {
			changes = append(changes, fmt.Sprintf("%s added", k))
		}
	}
	return changes
}
