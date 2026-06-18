package service

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/enterprise/knowledgebase/internal/config"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/jwt"
	"github.com/enterprise/knowledgebase/internal/pkg/utils"
	"github.com/enterprise/knowledgebase/internal/repository"
	"github.com/google/uuid"
)

type AuthService struct {
	userRepo    *repository.UserRepository
	tenantRepo  *repository.TenantRepository
	permRepo    *repository.PermissionRepository
	jwtConfig   config.JWTConfig
}

func NewAuthService(
	userRepo *repository.UserRepository,
	tenantRepo *repository.TenantRepository,
	permRepo *repository.PermissionRepository,
	jwtCfg config.JWTConfig,
) *AuthService {
	return &AuthService{
		userRepo:   userRepo,
		tenantRepo: tenantRepo,
		permRepo:   permRepo,
		jwtConfig:  jwtCfg,
	}
}

type LoginRequest struct {
	TenantNamespace string `json:"tenant_namespace" binding:"required"`
	Username        string `json:"username" binding:"required"`
	Password        string `json:"password" binding:"required"`
	ClientIP        string `json:"-"`
}

type LoginResponse struct {
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expires_at"`
	User      *UserInfo `json:"user"`
	Tenant    *TenantInfo `json:"tenant"`
}

type UserInfo struct {
	ID         uuid.UUID `json:"id"`
	Username   string    `json:"username"`
	Email      string    `json:"email"`
	FullName   string    `json:"full_name"`
	AvatarURL  string    `json:"avatar_url"`
	Role       string    `json:"role"`
	Language   string    `json:"language"`
	IsSuperAdmin bool  `json:"is_super_admin"`
}

type TenantInfo struct {
	ID        uuid.UUID `json:"id"`
	Name      string    `json:"name"`
	Domain    string    `json:"domain"`
	Namespace string    `json:"namespace"`
	LogoURL   string    `json:"logo_url"`
}

func (s *AuthService) Login(ctx context.Context, req *LoginRequest) (*LoginResponse, error) {
	tenant, err := s.tenantRepo.GetByNamespace(ctx, req.TenantNamespace)
	if err != nil {
		return nil, errors.New("invalid tenant or credentials")
	}
	if tenant.Status != model.TenantStatusActive {
		return nil, errors.New("tenant is not active")
	}

	user, err := s.userRepo.GetByUsername(ctx, tenant.ID, req.Username)
	if err != nil {
		return nil, errors.New("invalid tenant or credentials")
	}
	if user.Status != model.UserStatusActive {
		return nil, errors.New("user account is not active")
	}

	if !utils.CheckPassword(req.Password, user.PasswordHash) {
		return nil, errors.New("invalid tenant or credentials")
	}

	_ = s.userRepo.UpdateLastLogin(ctx, user.ID, req.ClientIP)

	tenantCtx := context.WithValue(ctx, "tenant_id", tenant.ID)
	role, _ := s.permRepo.GetResourceRole(tenantCtx, user.ID, nil, nil, model.ResourceTypeTenant, tenant.ID)
	if role == "" {
		role = model.RoleViewer
	}

	token, expiresAt, err := jwt.GenerateToken(
		user.ID, tenant.ID, user.Username, user.Email, string(role),
		s.jwtConfig.Secret, s.jwtConfig.Issuer, s.jwtConfig.ExpireHour,
	)
	if err != nil {
		return nil, fmt.Errorf("generate token: %w", err)
	}

	return &LoginResponse{
		Token:     token,
		ExpiresAt: expiresAt,
		User: &UserInfo{
			ID:           user.ID,
			Username:     user.Username,
			Email:        user.Email,
			FullName:     user.FullName,
			AvatarURL:    user.AvatarURL,
			Role:         string(role),
			Language:     user.Language,
			IsSuperAdmin: user.IsSuperAdmin,
		},
		Tenant: &TenantInfo{
			ID:        tenant.ID,
			Name:      tenant.Name,
			Domain:    tenant.Domain,
			Namespace: tenant.Namespace,
			LogoURL:   tenant.LogoURL,
		},
	}, nil
}

type RegisterRequest struct {
	TenantName      string `json:"tenant_name" binding:"required"`
	TenantDomain    string `json:"tenant_domain"`
	TenantNamespace string `json:"tenant_namespace" binding:"required"`
	Username        string `json:"username" binding:"required,min=3"`
	Email           string `json:"email" binding:"required,email"`
	Password        string `json:"password" binding:"required,min=8"`
	FullName        string `json:"full_name" binding:"required"`
}

func (s *AuthService) Register(ctx context.Context, req *RegisterRequest) (*LoginResponse, error) {
	if _, err := s.tenantRepo.GetByNamespace(ctx, req.TenantNamespace); err == nil {
		return nil, errors.New("tenant namespace already exists")
	}
	if req.TenantDomain != "" {
		if _, err := s.tenantRepo.GetByDomain(ctx, req.TenantDomain); err == nil {
			return nil, errors.New("tenant domain already exists")
		}
	}

	hashedPassword, err := utils.HashPassword(req.Password)
	if err != nil {
		return nil, fmt.Errorf("hash password: %w", err)
	}

	tenantID := uuid.New()
	userID := uuid.New()

	err = func() error {
		tenant := &model.Tenant{
			BaseModel: model.BaseModel{
				ID:        tenantID,
				CreatedAt: time.Now().UTC(),
				UpdatedAt: time.Now().UTC(),
			},
			Name:        req.TenantName,
			Domain:      req.TenantDomain,
			Namespace:   req.TenantNamespace,
			Description: "Created via self-service registration",
			Status:      model.TenantStatusActive,
		}
		if err := s.tenantRepo.Create(ctx, tenant); err != nil {
			return err
		}

		user := &model.User{
			BaseModel: model.BaseModel{
				ID:        userID,
				CreatedAt: time.Now().UTC(),
				UpdatedAt: time.Now().UTC(),
			},
			TenantScoped: model.TenantScoped{TenantID: tenantID},
			Username:     req.Username,
			Email:        req.Email,
			PasswordHash: hashedPassword,
			FullName:     req.FullName,
			Status:       model.UserStatusActive,
			IsSuperAdmin: true,
			Language:     "zh-CN",
			Timezone:     "Asia/Shanghai",
		}
		if err := s.userRepo.Create(ctx, user); err != nil {
			return err
		}

		tenantCtx := context.WithValue(ctx, "tenant_id", tenantID)
		return s.permRepo.GrantRole(tenantCtx,
			model.ResourceTypeTenant, tenantID,
			model.SubjectTypeUser, userID,
			model.RoleOwner, userID,
		)
	}()
	if err != nil {
		return nil, fmt.Errorf("register failed: %w", err)
	}

	token, expiresAt, err := jwt.GenerateToken(
		userID, tenantID, req.Username, req.Email, string(model.RoleOwner),
		s.jwtConfig.Secret, s.jwtConfig.Issuer, s.jwtConfig.ExpireHour,
	)
	if err != nil {
		return nil, fmt.Errorf("generate token: %w", err)
	}

	tenant, _ := s.tenantRepo.GetByID(ctx, tenantID)
	return &LoginResponse{
		Token:     token,
		ExpiresAt: expiresAt,
		User: &UserInfo{
			ID:           userID,
			Username:     req.Username,
			Email:        req.Email,
			FullName:     req.FullName,
			Role:         string(model.RoleOwner),
			Language:     "zh-CN",
			IsSuperAdmin: true,
		},
		Tenant: &TenantInfo{
			ID:        tenantID,
			Name:      req.TenantName,
			Domain:    req.TenantDomain,
			Namespace: req.TenantNamespace,
			LogoURL:   tenant.LogoURL,
		},
	}, nil
}

func (s *AuthService) RefreshToken(ctx context.Context, oldToken string) (string, time.Time, error) {
	return jwt.RefreshToken(oldToken, s.jwtConfig.Secret, s.jwtConfig.ExpireHour)
}

type CreateAPITokenRequest struct {
	Name        string       `json:"name" binding:"required"`
	UserID      uuid.UUID    `json:"-"`
	TenantID    uuid.UUID    `json:"-"`
	Scopes      []string     `json:"scopes"`
	IPWhitelist []string     `json:"ip_whitelist"`
	ExpiresAt   *time.Time   `json:"expires_at"`
	RateLimit   int          `json:"rate_limit"`
}

type CreateAPITokenResponse struct {
	TokenID uuid.UUID `json:"token_id"`
	Token   string    `json:"token"`
	Prefix  string    `json:"prefix"`
}

func (s *AuthService) CreateAPIToken(ctx context.Context, req *CreateAPITokenRequest) (*CreateAPITokenResponse, error) {
	rawToken := utils.GenerateAPIKey()
	sum := sha256.Sum256([]byte(rawToken))
	tokenHash := hex.EncodeToString(sum[:])
	prefix := rawToken[:12]

	rateLimit := req.RateLimit
	if rateLimit <= 0 {
		rateLimit = 1000
	}

	scopes := req.Scopes
	if len(scopes) == 0 {
		scopes = []string{model.ScopeAll}
	}

	token := &model.ApiToken{
		BaseModel: model.BaseModel{
			ID:        uuid.New(),
			CreatedAt: time.Now().UTC(),
			UpdatedAt: time.Now().UTC(),
		},
		TenantScoped: model.TenantScoped{TenantID: req.TenantID},
		UserID:       req.UserID,
		Name:         req.Name,
		TokenHash:    tokenHash,
		TokenPrefix:  prefix,
		Scopes:       model.StringArray(scopes),
		IPWhitelist:  model.StringArray(req.IPWhitelist),
		ExpiresAt:    req.ExpiresAt,
		RateLimit:    rateLimit,
		Status:       model.ApiTokenStatusActive,
		CreatedBy:    req.UserID,
	}

	if err := s.userRepo.CreateAPIToken(ctx, token); err != nil {
		return nil, fmt.Errorf("create api token: %w", err)
	}

	return &CreateAPITokenResponse{
		TokenID: token.ID,
		Token:   rawToken,
		Prefix:  prefix,
	}, nil
}

func (s *AuthService) ValidateAPIToken(ctx context.Context, rawToken string, clientIP string) (*model.ApiToken, *model.User, error) {
	if len(rawToken) < 12 {
		return nil, nil, errors.New("invalid token format")
	}

	sum := sha256.Sum256([]byte(rawToken))
	tokenHash := hex.EncodeToString(sum[:])

	token, err := s.userRepo.GetAPITokenByHash(ctx, tokenHash)
	if err != nil {
		return nil, nil, errors.New("invalid token")
	}

	if !token.IsActive() {
		return nil, nil, errors.New("token is not active")
	}

	if len(token.IPWhitelist) > 0 {
		allowed := false
		for _, ip := range token.IPWhitelist {
			if ip == clientIP {
				allowed = true
				break
			}
		}
		if !allowed {
			return nil, nil, errors.New("IP not in whitelist")
		}
	}

	user, err := s.userRepo.GetByID(ctx, token.UserID)
	if err != nil {
		return nil, nil, errors.New("user not found")
	}
	if user.Status != model.UserStatusActive {
		return nil, nil, errors.New("user is not active")
	}

	_ = s.userRepo.UpdateAPITokenUsage(ctx, token.ID, clientIP)

	return token, user, nil
}
