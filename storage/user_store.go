package storage

import (
	"accessguard/models"
	"sync"
)

type UserStore interface {
	Create(user *models.User) error
	GetByID(userID string) (*models.User, error)
	GetByUsername(username string) (*models.User, error)
	Update(user *models.User) error
	Delete(userID string) error
	List() []*models.User
	AssignRole(userID, roleID string) error
	RemoveRole(userID, roleID string) error
}

type InMemoryUserStore struct {
	users      map[string]*models.User
	usernameIdx map[string]string
	mu         sync.RWMutex
}

func NewInMemoryUserStore() *InMemoryUserStore {
	return &InMemoryUserStore{
		users:      make(map[string]*models.User),
		usernameIdx: make(map[string]string),
	}
}

func (s *InMemoryUserStore) Create(user *models.User) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.usernameIdx[user.Username]; exists {
		return models.ErrUserAlreadyExists
	}

	s.users[user.UserID] = user
	s.usernameIdx[user.Username] = user.UserID
	return nil
}

func (s *InMemoryUserStore) GetByID(userID string) (*models.User, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	user, exists := s.users[userID]
	if !exists {
		return nil, models.ErrUserNotFound
	}
	return user, nil
}

func (s *InMemoryUserStore) GetByUsername(username string) (*models.User, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	userID, exists := s.usernameIdx[username]
	if !exists {
		return nil, models.ErrUserNotFound
	}

	user, exists := s.users[userID]
	if !exists {
		return nil, models.ErrUserNotFound
	}
	return user, nil
}

func (s *InMemoryUserStore) Update(user *models.User) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.users[user.UserID]; !exists {
		return models.ErrUserNotFound
	}

	s.users[user.UserID] = user
	return nil
}

func (s *InMemoryUserStore) Delete(userID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	user, exists := s.users[userID]
	if !exists {
		return models.ErrUserNotFound
	}

	delete(s.usernameIdx, user.Username)
	delete(s.users, userID)
	return nil
}

func (s *InMemoryUserStore) List() []*models.User {
	s.mu.RLock()
	defer s.mu.RUnlock()

	users := make([]*models.User, 0, len(s.users))
	for _, user := range s.users {
		users = append(users, user)
	}
	return users
}

func (s *InMemoryUserStore) AssignRole(userID, roleID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	user, exists := s.users[userID]
	if !exists {
		return models.ErrUserNotFound
	}

	for _, r := range user.RoleIDs {
		if r == roleID {
			return nil
		}
	}

	user.RoleIDs = append(user.RoleIDs, roleID)
	return nil
}

func (s *InMemoryUserStore) RemoveRole(userID, roleID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	user, exists := s.users[userID]
	if !exists {
		return models.ErrUserNotFound
	}

	newRoles := make([]string, 0, len(user.RoleIDs))
	for _, r := range user.RoleIDs {
		if r != roleID {
			newRoles = append(newRoles, r)
		}
	}
	user.RoleIDs = newRoles
	return nil
}
