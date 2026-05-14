package storage

import (
	"accessguard/models"
	"sync"
)

type RoleStore interface {
	Create(role *models.Role) error
	GetByID(roleID string) (*models.Role, error)
	Update(role *models.Role) error
	Delete(roleID string) error
	List() []*models.Role
	GetByIDs(roleIDs []string) []*models.Role
}

type InMemoryRoleStore struct {
	roles map[string]*models.Role
	mu    sync.RWMutex
}

func NewInMemoryRoleStore() *InMemoryRoleStore {
	return &InMemoryRoleStore{
		roles: make(map[string]*models.Role),
	}
}

func (s *InMemoryRoleStore) Create(role *models.Role) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.roles[role.RoleID]; exists {
		return models.ErrRoleAlreadyExists
	}

	s.roles[role.RoleID] = role
	return nil
}

func (s *InMemoryRoleStore) GetByID(roleID string) (*models.Role, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	role, exists := s.roles[roleID]
	if !exists {
		return nil, models.ErrRoleNotFound
	}
	return role, nil
}

func (s *InMemoryRoleStore) Update(role *models.Role) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.roles[role.RoleID]; !exists {
		return models.ErrRoleNotFound
	}

	s.roles[role.RoleID] = role
	return nil
}

func (s *InMemoryRoleStore) Delete(roleID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.roles[roleID]; !exists {
		return models.ErrRoleNotFound
	}

	delete(s.roles, roleID)
	return nil
}

func (s *InMemoryRoleStore) List() []*models.Role {
	s.mu.RLock()
	defer s.mu.RUnlock()

	roles := make([]*models.Role, 0, len(s.roles))
	for _, role := range s.roles {
		roles = append(roles, role)
	}
	return roles
}

func (s *InMemoryRoleStore) GetByIDs(roleIDs []string) []*models.Role {
	s.mu.RLock()
	defer s.mu.RUnlock()

	roles := make([]*models.Role, 0, len(roleIDs))
	for _, roleID := range roleIDs {
		if role, exists := s.roles[roleID]; exists {
			roles = append(roles, role)
		}
	}
	return roles
}
