package user

import (
	"accessguard/config"
	"accessguard/models"
	"accessguard/storage"
	"accessguard/utils"
	"time"
)

type Service struct {
	store         storage.UserStore
	passwordUtils *utils.PasswordUtils
	config        *config.Config
}

func NewService(store storage.UserStore, passwordUtils *utils.PasswordUtils, cfg *config.Config) *Service {
	return &Service{
		store:         store,
		passwordUtils: passwordUtils,
		config:        cfg,
	}
}

func (s *Service) CreateUser(req *models.CreateUserRequest) (*models.User, error) {
	if req.Username == "" || req.Password == "" {
		return nil, models.ErrInvalidRequest
	}

	if !s.passwordUtils.ValidatePasswordStrength(req.Password, s.config.Password.MinLength) {
		return nil, models.ErrInvalidRequest
	}

	hashedPassword, err := s.passwordUtils.HashPassword(req.Password)
	if err != nil {
		return nil, err
	}

	user := &models.User{
		UserID:       utils.GenerateUserID(),
		Username:     req.Username,
		PasswordHash: hashedPassword,
		Email:        req.Email,
		Status:       models.UserStatusActive,
		MFAEnabled:   false,
		RoleIDs:      []string{},
		CreatedAt:    time.Now(),
		LastLogin:    nil,
	}

	err = s.store.Create(user)
	if err != nil {
		return nil, err
	}

	return user, nil
}

func (s *Service) GetUserByID(userID string) (*models.User, error) {
	return s.store.GetByID(userID)
}

func (s *Service) GetUserByUsername(username string) (*models.User, error) {
	return s.store.GetByUsername(username)
}

func (s *Service) UpdateUser(userID string, req *models.UpdateUserRequest) (*models.User, error) {
	user, err := s.store.GetByID(userID)
	if err != nil {
		return nil, err
	}

	if req.Email != nil {
		user.Email = *req.Email
	}

	if req.Status != nil {
		user.Status = *req.Status
	}

	if req.Password != nil {
		if !s.passwordUtils.ValidatePasswordStrength(*req.Password, s.config.Password.MinLength) {
			return nil, models.ErrInvalidRequest
		}
		hashedPassword, err := s.passwordUtils.HashPassword(*req.Password)
		if err != nil {
			return nil, err
		}
		user.PasswordHash = hashedPassword
	}

	err = s.store.Update(user)
	if err != nil {
		return nil, err
	}

	return user, nil
}

func (s *Service) DeleteUser(userID string) error {
	return s.store.Delete(userID)
}

func (s *Service) ListUsers() []*models.User {
	return s.store.List()
}

func (s *Service) UpdateLastLogin(userID string) error {
	user, err := s.store.GetByID(userID)
	if err != nil {
		return err
	}

	now := time.Now()
	user.LastLogin = &now
	return s.store.Update(user)
}

func (s *Service) EnableMFA(userID string) error {
	user, err := s.store.GetByID(userID)
	if err != nil {
		return err
	}

	user.MFAEnabled = true
	return s.store.Update(user)
}

func (s *Service) DisableMFA(userID string) error {
	user, err := s.store.GetByID(userID)
	if err != nil {
		return err
	}

	user.MFAEnabled = false
	return s.store.Update(user)
}
