package config

import (
	"sort"
	"sync"

	"github.com/parking-platform/platform/pkg/models"
	"github.com/parking-platform/platform/pkg/utils"
)

type ConfigManager struct {
	mu        sync.RWMutex
	configs   map[string][]*models.Config
	current   map[string]*models.Config
}

func NewConfigManager() *ConfigManager {
	return &ConfigManager{
		configs: make(map[string][]*models.Config),
		current: make(map[string]*models.Config),
	}
}

func (m *ConfigManager) Create(namespace string, parameters map[string]interface{}, enabled bool) *models.Config {
	m.mu.Lock()
	defer m.mu.Unlock()

	var version int64 = 1
	if versions, ok := m.configs[namespace]; ok && len(versions) > 0 {
		version = versions[len(versions)-1].Version + 1
	}

	cfg := &models.Config{
		ConfigID:   utils.GenerateID("cfg"),
		Namespace:  namespace,
		Version:    version,
		Parameters: parameters,
		Enabled:    enabled,
		AppliedAt:  utils.Now(),
	}

	m.configs[namespace] = append(m.configs[namespace], cfg)
	if enabled {
		m.current[namespace] = cfg
	}
	return cfg
}

func (m *ConfigManager) Get(namespace string) (*models.Config, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	c, ok := m.current[namespace]
	return c, ok
}

func (m *ConfigManager) GetVersion(namespace string, version int64) (*models.Config, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	versions, ok := m.configs[namespace]
	if !ok {
		return nil, false
	}
	for _, c := range versions {
		if c.Version == version {
			return c, true
		}
	}
	return nil, false
}

func (m *ConfigManager) ListVersions(namespace string) []*models.Config {
	m.mu.RLock()
	defer m.mu.RUnlock()
	versions, ok := m.configs[namespace]
	if !ok {
		return nil
	}
	result := make([]*models.Config, len(versions))
	copy(result, versions)
	sort.Slice(result, func(i, j int) bool {
		return result[i].Version > result[j].Version
	})
	return result
}

func (m *ConfigManager) Rollback(namespace string, toVersion int64) (*models.Config, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	versions, ok := m.configs[namespace]
	if !ok {
		return nil, ErrNamespaceNotFound
	}

	var target *models.Config
	for _, c := range versions {
		if c.Version == toVersion {
			target = c
			break
		}
	}
	if target == nil {
		return nil, ErrVersionNotFound
	}

	newVersion := int64(1)
	if len(versions) > 0 {
		newVersion = versions[len(versions)-1].Version + 1
	}

	rolledBack := &models.Config{
		ConfigID:   utils.GenerateID("cfg"),
		Namespace:  namespace,
		Version:    newVersion,
		Parameters: target.Parameters,
		Enabled:    true,
		AppliedAt:  utils.Now(),
	}

	m.configs[namespace] = append(versions, rolledBack)
	m.current[namespace] = rolledBack
	return rolledBack, nil
}

func (m *ConfigManager) Update(namespace string, parameters map[string]interface{}) *models.Config {
	return m.Create(namespace, parameters, true)
}

func (m *ConfigManager) Disable(namespace string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if current, ok := m.current[namespace]; ok {
		current.Enabled = false
	}
	delete(m.current, namespace)
}

func (m *ConfigManager) ListNamespaces() []string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]string, 0, len(m.configs))
	for ns := range m.configs {
		result = append(result, ns)
	}
	return result
}

var (
	ErrNamespaceNotFound = &configError{"namespace not found"}
	ErrVersionNotFound   = &configError{"version not found"}
)

type configError struct {
	msg string
}

func (e *configError) Error() string { return e.msg }
