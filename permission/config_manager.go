package permission

import (
	"accessguard/config"
	"encoding/json"
	"log"
	"os"
	"sync"
)

type PermissionDef struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Category    string `json:"category"`
}

type PermissionConfigManager struct {
	permissions map[string]PermissionDef
	mu          sync.RWMutex
	configFile  string
}

func NewPermissionConfigManager(cfg *config.PermissionsConfig) *PermissionConfigManager {
	manager := &PermissionConfigManager{
		permissions: make(map[string]PermissionDef),
		configFile:  cfg.ConfigFile,
	}

	for name, def := range cfg.Permissions {
		manager.permissions[name] = PermissionDef{
			Name:        def.Name,
			Description: def.Description,
			Category:    def.Category,
		}
	}

	if manager.configFile != "" {
		if err := manager.LoadFromFile(manager.configFile); err != nil {
			log.Printf("Warning: Failed to load permissions from file %s: %v", manager.configFile, err)
		}
	}

	return manager
}

func (m *PermissionConfigManager) LoadFromFile(filename string) error {
	data, err := os.ReadFile(filename)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	var filePerms map[string]PermissionDef
	if err := json.Unmarshal(data, &filePerms); err != nil {
		return err
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	for name, def := range filePerms {
		m.permissions[name] = def
	}

	return nil
}

func (m *PermissionConfigManager) GetPermission(name string) (PermissionDef, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	perm, exists := m.permissions[name]
	return perm, exists
}

func (m *PermissionConfigManager) ListPermissions() []PermissionDef {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]PermissionDef, 0, len(m.permissions))
	for _, perm := range m.permissions {
		result = append(result, perm)
	}
	return result
}

func (m *PermissionConfigManager) ListPermissionsByCategory(category string) []PermissionDef {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]PermissionDef, 0)
	for _, perm := range m.permissions {
		if perm.Category == category {
			result = append(result, perm)
		}
	}
	return result
}

func (m *PermissionConfigManager) AddPermission(def PermissionDef) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.permissions[def.Name] = def
}

func (m *PermissionConfigManager) RemovePermission(name string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	delete(m.permissions, name)
}

func (m *PermissionConfigManager) IsValidPermission(name string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()

	_, exists := m.permissions[name]
	return exists
}

func (m *PermissionConfigManager) ValidatePermissions(names []string) ([]string, []string) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	valid := make([]string, 0, len(names))
	invalid := make([]string, 0)

	seen := make(map[string]bool)
	for _, name := range names {
		if seen[name] {
			continue
		}
		seen[name] = true

		if _, exists := m.permissions[name]; exists {
			valid = append(valid, name)
		} else {
			invalid = append(invalid, name)
		}
	}

	return valid, invalid
}
