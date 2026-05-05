package auth

import (
	"context"
	"errors"
	"sync"
	"time"

	"pixelrealm/pkg/models"
)

var (
	ErrSessionNotFound = errors.New("session not found")
	ErrSessionExpired  = errors.New("session expired")
)

type Session struct {
	PlayerID    models.PlayerID
	Token       string
	CreatedAt   time.Time
	LastActive  time.Time
	ExpiresAt   time.Time
	IPAddress   string
	UserAgent   string
}

type SessionManager struct {
	sessions     map[models.PlayerID]*Session
	sessionsByToken map[string]*Session
	mu           sync.RWMutex
	maxSessionTime time.Duration
	idleTimeout  time.Duration
}

func NewSessionManager(maxSessionTime, idleTimeout time.Duration) *SessionManager {
	manager := &SessionManager{
		sessions:        make(map[models.PlayerID]*Session),
		sessionsByToken: make(map[string]*Session),
		maxSessionTime:  maxSessionTime,
		idleTimeout:     idleTimeout,
	}
	
	go manager.cleanupLoop()
	
	return manager
}

func (m *SessionManager) CreateSession(playerID models.PlayerID, token string, ipAddress, userAgent string) *Session {
	now := time.Now()
	session := &Session{
		PlayerID:   playerID,
		Token:      token,
		CreatedAt:  now,
		LastActive: now,
		ExpiresAt:  now.Add(m.maxSessionTime),
		IPAddress:  ipAddress,
		UserAgent:  userAgent,
	}
	
	m.mu.Lock()
	defer m.mu.Unlock()
	
	if oldSession, exists := m.sessions[playerID]; exists {
		delete(m.sessionsByToken, oldSession.Token)
	}
	
	m.sessions[playerID] = session
	m.sessionsByToken[token] = session
	
	return session
}

func (m *SessionManager) GetSessionByPlayerID(playerID models.PlayerID) (*Session, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	
	session, exists := m.sessions[playerID]
	if !exists {
		return nil, ErrSessionNotFound
	}
	
	if m.isSessionExpired(session) {
		delete(m.sessions, playerID)
		delete(m.sessionsByToken, session.Token)
		return nil, ErrSessionExpired
	}
	
	return session, nil
}

func (m *SessionManager) GetSessionByToken(token string) (*Session, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	
	session, exists := m.sessionsByToken[token]
	if !exists {
		return nil, ErrSessionNotFound
	}
	
	if m.isSessionExpired(session) {
		delete(m.sessions, session.PlayerID)
		delete(m.sessionsByToken, token)
		return nil, ErrSessionExpired
	}
	
	return session, nil
}

func (m *SessionManager) RefreshSession(playerID models.PlayerID) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	
	session, exists := m.sessions[playerID]
	if !exists {
		return ErrSessionNotFound
	}
	
	session.LastActive = time.Now()
	return nil
}

func (m *SessionManager) DestroySession(playerID models.PlayerID) {
	m.mu.Lock()
	defer m.mu.Unlock()
	
	session, exists := m.sessions[playerID]
	if exists {
		delete(m.sessionsByToken, session.Token)
		delete(m.sessions, playerID)
	}
}

func (m *SessionManager) DestroySessionByToken(token string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	
	session, exists := m.sessionsByToken[token]
	if exists {
		delete(m.sessions, session.PlayerID)
		delete(m.sessionsByToken, token)
	}
}

func (m *SessionManager) IsPlayerOnline(playerID models.PlayerID) bool {
	_, err := m.GetSessionByPlayerID(playerID)
	return err == nil
}

func (m *SessionManager) GetOnlinePlayers() []models.PlayerID {
	m.mu.RLock()
	defer m.mu.RUnlock()
	
	var players []models.PlayerID
	for playerID, session := range m.sessions {
		if !m.isSessionExpired(session) {
			players = append(players, playerID)
		}
	}
	return players
}

func (m *SessionManager) GetOnlineCount() int {
	return len(m.GetOnlinePlayers())
}

func (m *SessionManager) isSessionExpired(session *Session) bool {
	now := time.Now()
	
	if now.After(session.ExpiresAt) {
		return true
	}
	
	if now.Sub(session.LastActive) > m.idleTimeout {
		return true
	}
	
	return false
}

func (m *SessionManager) cleanupLoop() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()
	
	for range ticker.C {
		m.cleanupExpiredSessions()
	}
}

func (m *SessionManager) cleanupExpiredSessions() {
	m.mu.Lock()
	defer m.mu.Unlock()
	
	for playerID, session := range m.sessions {
		if m.isSessionExpired(session) {
			delete(m.sessionsByToken, session.Token)
			delete(m.sessions, playerID)
		}
	}
}

type AuthService struct {
	tokenManager    *TokenManager
	passwordHasher  *PasswordHasher
	sessionManager  *SessionManager
	playerCache     interface{
		Load(ctx context.Context, playerID models.PlayerID) (*models.Player, error)
		Put(player *models.Player)
		Save(player *models.Player, async bool)
	}
}

func NewAuthService(
	tokenManager *TokenManager,
	passwordHasher *PasswordHasher,
	sessionManager *SessionManager,
	playerCache interface{
		Load(ctx context.Context, playerID models.PlayerID) (*models.Player, error)
		Put(player *models.Player)
		Save(player *models.Player, async bool)
	},
) *AuthService {
	return &AuthService{
		tokenManager:   tokenManager,
		passwordHasher: passwordHasher,
		sessionManager: sessionManager,
		playerCache:    playerCache,
	}
}

func (s *AuthService) Login(ctx context.Context, username, password string) (*models.Player, string, error) {
	return nil, "", nil
}

func (s *AuthService) Register(ctx context.Context, username, password string) (*models.Player, string, error) {
	return nil, "", nil
}

func (s *AuthService) ValidateToken(token string) (*models.PlayerID, error) {
	claims, err := s.tokenManager.ValidateToken(token)
	if err != nil {
		return nil, err
	}
	
	session, err := s.sessionManager.GetSessionByToken(token)
	if err != nil {
		return nil, err
	}
	
	if session.PlayerID != claims.PlayerID {
		return nil, ErrInvalidToken
	}
	
	s.sessionManager.RefreshSession(claims.PlayerID)
	
	return &claims.PlayerID, nil
}

func (s *AuthService) Logout(playerID models.PlayerID) {
	s.sessionManager.DestroySession(playerID)
}

func (s *AuthService) IsOnline(playerID models.PlayerID) bool {
	return s.sessionManager.IsPlayerOnline(playerID)
}
