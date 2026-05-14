package storage

import (
	"accessguard/models"
	"sync"
)

type SessionStore interface {
	Create(session *models.Session) error
	GetByID(sessionID string) (*models.Session, error)
	Update(session *models.Session) error
	Delete(sessionID string) error
	ListByUserID(userID string) []*models.Session
}

type InMemorySessionStore struct {
	sessions  map[string]*models.Session
	userIndex map[string][]string
	mu        sync.RWMutex
}

func NewInMemorySessionStore() *InMemorySessionStore {
	return &InMemorySessionStore{
		sessions:  make(map[string]*models.Session),
		userIndex: make(map[string][]string),
	}
}

func (s *InMemorySessionStore) Create(session *models.Session) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.sessions[session.SessionID] = session
	s.userIndex[session.UserID] = append(s.userIndex[session.UserID], session.SessionID)
	return nil
}

func (s *InMemorySessionStore) GetByID(sessionID string) (*models.Session, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	session, exists := s.sessions[sessionID]
	if !exists {
		return nil, models.ErrSessionNotFound
	}
	return session, nil
}

func (s *InMemorySessionStore) Update(session *models.Session) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.sessions[session.SessionID]; !exists {
		return models.ErrSessionNotFound
	}

	s.sessions[session.SessionID] = session
	return nil
}

func (s *InMemorySessionStore) Delete(sessionID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	session, exists := s.sessions[sessionID]
	if !exists {
		return models.ErrSessionNotFound
	}

	if sessionIDs, exists := s.userIndex[session.UserID]; exists {
		newIDs := make([]string, 0, len(sessionIDs))
		for _, id := range sessionIDs {
			if id != sessionID {
				newIDs = append(newIDs, id)
			}
		}
		s.userIndex[session.UserID] = newIDs
	}

	delete(s.sessions, sessionID)
	return nil
}

func (s *InMemorySessionStore) ListByUserID(userID string) []*models.Session {
	s.mu.RLock()
	defer s.mu.RUnlock()

	sessionIDs, exists := s.userIndex[userID]
	if !exists {
		return []*models.Session{}
	}

	sessions := make([]*models.Session, 0, len(sessionIDs))
	for _, id := range sessionIDs {
		if session, exists := s.sessions[id]; exists {
			sessions = append(sessions, session)
		}
	}
	return sessions
}
