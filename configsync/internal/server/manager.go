package server

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"configsync/internal/models"
)

var (
	ErrGroupNotFound = errors.New("server group not found")
	ErrGroupExists   = errors.New("server group already exists")
)

type Manager struct {
	configDir string
	groups    map[string]*models.ServerGroup
}

func NewManager(configDir string) (*Manager, error) {
	m := &Manager{
		configDir: configDir,
		groups:    make(map[string]*models.ServerGroup),
	}

	if err := os.MkdirAll(configDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create config directory: %w", err)
	}

	if err := m.loadAllGroups(); err != nil {
		return nil, fmt.Errorf("failed to load server groups: %w", err)
	}

	return m, nil
}

func (m *Manager) loadAllGroups() error {
	files, err := filepath.Glob(filepath.Join(m.configDir, "*.json"))
	if err != nil {
		return err
	}

	for _, file := range files {
		data, err := os.ReadFile(file)
		if err != nil {
			return fmt.Errorf("failed to read %s: %w", file, err)
		}

		var group models.ServerGroup
		if err := json.Unmarshal(data, &group); err != nil {
			return fmt.Errorf("failed to parse %s: %w", file, err)
		}

		if group.GroupName != "" {
			m.groups[group.GroupName] = &group
		}
	}

	return nil
}

func (m *Manager) GetGroup(groupName string) (*models.ServerGroup, error) {
	group, exists := m.groups[groupName]
	if !exists {
		return nil, ErrGroupNotFound
	}
	return group, nil
}

func (m *Manager) ListGroups() []*models.ServerGroup {
	groups := make([]*models.ServerGroup, 0, len(m.groups))
	for _, group := range m.groups {
		groups = append(groups, group)
	}
	return groups
}

func (m *Manager) AddGroup(group *models.ServerGroup) error {
	if _, exists := m.groups[group.GroupName]; exists {
		return ErrGroupExists
	}

	if err := m.saveGroup(group); err != nil {
		return err
	}

	m.groups[group.GroupName] = group
	return nil
}

func (m *Manager) UpdateGroup(group *models.ServerGroup) error {
	if _, exists := m.groups[group.GroupName]; !exists {
		return ErrGroupNotFound
	}

	if err := m.saveGroup(group); err != nil {
		return err
	}

	m.groups[group.GroupName] = group
	return nil
}

func (m *Manager) DeleteGroup(groupName string) error {
	if _, exists := m.groups[groupName]; !exists {
		return ErrGroupNotFound
	}

	filePath := filepath.Join(m.configDir, groupName+".json")
	if err := os.Remove(filePath); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to delete group file: %w", err)
	}

	delete(m.groups, groupName)
	return nil
}

func (m *Manager) saveGroup(group *models.ServerGroup) error {
	data, err := json.MarshalIndent(group, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal group: %w", err)
	}

	filePath := filepath.Join(m.configDir, group.GroupName+".json")
	if err := os.WriteFile(filePath, data, 0644); err != nil {
		return fmt.Errorf("failed to write group file: %w", err)
	}

	return nil
}

func (m *Manager) GetServersInGroup(groupName string) ([]models.Server, error) {
	group, err := m.GetGroup(groupName)
	if err != nil {
		return nil, err
	}
	return group.Servers, nil
}
