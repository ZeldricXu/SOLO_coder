package auth

import (
	"accessguard/audit"
	"accessguard/config"
	"accessguard/models"
	"accessguard/session"
	"accessguard/user"
	"accessguard/utils"
)

type Service struct {
	userService    *user.Service
	sessionService *session.Service
	auditService   audit.Service
	passwordUtils  *utils.PasswordUtils
	config         *config.Config
	mfaCodes       map[string]string
}

func NewService(userService *user.Service, sessionService *session.Service, auditService audit.Service, passwordUtils *utils.PasswordUtils, cfg *config.Config) *Service {
	return &Service{
		userService:    userService,
		sessionService: sessionService,
		auditService:   auditService,
		passwordUtils:  passwordUtils,
		config:         cfg,
		mfaCodes:       make(map[string]string),
	}
}

func (s *Service) Login(req *models.LoginRequest, ipAddress string) (*models.LoginResponse, error) {
	if req.Username == "" || req.Password == "" {
		return nil, models.ErrInvalidRequest
	}

	u, err := s.userService.GetUserByUsername(req.Username)
	if err != nil {
		if ipAddress != "" {
			s.auditService.RecordLogin("unknown", ipAddress, "", false)
		}
		return nil, err
	}

	if u.Status != models.UserStatusActive {
		if ipAddress != "" {
			s.auditService.RecordLogin(u.UserID, ipAddress, "", false)
		}
		return nil, models.ErrUserDisabled
	}

	if !s.passwordUtils.VerifyPassword(req.Password, u.PasswordHash) {
		if ipAddress != "" {
			s.auditService.RecordLogin(u.UserID, ipAddress, "", false)
		}
		return nil, models.ErrInvalidPassword
	}

	if u.MFAEnabled {
		if req.MFACode == "" {
			return nil, models.ErrMFARequired
		}
		storedCode, exists := s.mfaCodes[u.UserID]
		if !exists || storedCode != req.MFACode {
			if ipAddress != "" {
				s.auditService.RecordLogin(u.UserID, ipAddress, "", false)
			}
			return nil, models.ErrMFAFailed
		}
		delete(s.mfaCodes, u.UserID)
	}

	session, err := s.sessionService.CreateSession(u.UserID, ipAddress)
	if err != nil {
		return nil, err
	}

	err = s.userService.UpdateLastLogin(u.UserID)
	if err != nil {
		s.sessionService.RevokeSession(session.SessionID)
		return nil, err
	}

	if ipAddress != "" {
		s.auditService.RecordLogin(u.UserID, ipAddress, session.SessionID, true)
	}

	return &models.LoginResponse{
		SessionID: session.SessionID,
		ExpiresAt: session.ExpiresAt,
		UserID:    u.UserID,
	}, nil
}

func (s *Service) Logout(sessionID, ipAddress string) error {
	session, err := s.sessionService.GetSession(sessionID)
	if err == nil {
		s.auditService.RecordLogout(session.UserID, ipAddress, sessionID)
	}
	return s.sessionService.RevokeSession(sessionID)
}

func (s *Service) GenerateMFACode(userID string) (string, error) {
	code := utils.GenerateMFACode(s.config.MFA.CodeLength)
	s.mfaCodes[userID] = code
	return code, nil
}

func (s *Service) ValidateSession(sessionID string) (*models.Session, error) {
	return s.sessionService.ValidateSession(sessionID)
}

func (s *Service) GetUserBySession(sessionID string) (*models.User, error) {
	session, err := s.sessionService.ValidateSession(sessionID)
	if err != nil {
		return nil, err
	}
	return s.userService.GetUserByID(session.UserID)
}
