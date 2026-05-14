package session

import (
	"accessguard/config"
	"accessguard/models"
	"accessguard/storage"
	"accessguard/utils"
	"time"
)

type SessionNotifier interface {
	NotifySessionExpired(sessionID, userID, reason string)
	NotifyUserSessionsExpired(userID, reason string)
}

type Service struct {
	store  storage.SessionStore
	config *config.Config
	notifier SessionNotifier
}

func NewService(store storage.SessionStore, cfg *config.Config) *Service {
	return &Service{
		store:  store,
		config: cfg,
	}
}

func (s *Service) SetSessionNotifier(notifier SessionNotifier) {
	s.notifier = notifier
}

func (s *Service) CreateSession(userID, ipAddress string) (*models.Session, error) {
	if userID == "" {
		return nil, models.ErrInvalidRequest
	}

	s.RevokeUserSessionsWithReason(userID, "new_login")

	now := time.Now()
	session := &models.Session{
		SessionID: utils.GenerateSessionID(),
		UserID:    userID,
		CreatedAt: now,
		ExpiresAt: now.Add(s.config.Session.DefaultTTL),
		IPAddress: ipAddress,
		Status:    models.SessionStatusActive,
	}

	err := s.store.Create(session)
	if err != nil {
		return nil, err
	}

	return session, nil
}

func (s *Service) GetSession(sessionID string) (*models.Session, error) {
	return s.store.GetByID(sessionID)
}

func (s *Service) ValidateSession(sessionID string) (*models.Session, error) {
	session, err := s.store.GetByID(sessionID)
	if err != nil {
		return nil, err
	}

	if session.Status != models.SessionStatusActive {
		if session.Status == models.SessionStatusRevoked {
			return nil, models.ErrSessionRevoked
		}
		return nil, models.ErrSessionExpired
	}

	now := time.Now()
	if now.After(session.ExpiresAt) {
		session.Status = models.SessionStatusExpired
		s.store.Update(session)
		return nil, models.ErrSessionExpired
	}

	session.ExpiresAt = now.Add(s.config.Session.ExtendTTL)
	err = s.store.Update(session)
	if err != nil {
		return nil, err
	}

	return session, nil
}

func (s *Service) RevokeSession(sessionID string) error {
	session, err := s.store.GetByID(sessionID)
	if err != nil {
		return err
	}

	if session.Status == models.SessionStatusActive && s.notifier != nil {
		s.notifier.NotifySessionExpired(session.SessionID, session.UserID, "explicit_revoke")
	}

	session.Status = models.SessionStatusRevoked
	return s.store.Update(session)
}

func (s *Service) RevokeUserSessions(userID string) error {
	return s.RevokeUserSessionsWithReason(userID, "admin_revoke")
}

func (s *Service) RevokeUserSessionsWithReason(userID, reason string) error {
	if s.notifier != nil {
		s.notifier.NotifyUserSessionsExpired(userID, reason)
	}

	sessions := s.store.ListByUserID(userID)
	for _, session := range sessions {
		if session.Status == models.SessionStatusActive {
			session.Status = models.SessionStatusRevoked
			s.store.Update(session)
		}
	}
	return nil
}

func (s *Service) ListUserSessions(userID string) []*models.Session {
	return s.store.ListByUserID(userID)
}

func (s *Service) DeleteSession(sessionID string) error {
	return s.store.Delete(sessionID)
}

func (s *Service) ExtendSession(sessionID string) error {
	session, err := s.store.GetByID(sessionID)
	if err != nil {
		return err
	}

	if session.Status != models.SessionStatusActive {
		return models.ErrSessionExpired
	}

	session.ExpiresAt = time.Now().Add(s.config.Session.ExtendTTL)
	return s.store.Update(session)
}
