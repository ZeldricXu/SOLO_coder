package apigateway

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type Role string

const (
	RoleAdmin  Role = "admin"
	RoleUser   Role = "user"
	RoleViewer Role = "viewer"
)

type User struct {
	ID           string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Username     string    `gorm:"type:varchar(64);uniqueIndex;not null" json:"username"`
	Email        string    `gorm:"type:varchar(128);uniqueIndex;not null" json:"email"`
	PasswordHash string    `gorm:"type:varchar(256);not null" json:"-"`
	Role         Role      `gorm:"type:varchar(32);not null" json:"role"`
	APIKey       string    `gorm:"type:varchar(64);uniqueIndex" json:"api_key,omitempty"`
	Status       string    `gorm:"type:varchar(32);default:active" json:"status"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
}

type AuthConfig struct {
	JWTSecret     string
	TokenTTL      time.Duration
	APIKeyHeader  string
}

type AuthService struct {
	db     *gorm.DB
	logger *zap.Logger
	config *AuthConfig
}

func NewAuthService(db *gorm.DB, logger *zap.Logger, config *AuthConfig) *AuthService {
	return &AuthService{
		db:     db,
		logger: logger,
		config: config,
	}
}

type LoginRequest struct {
	Username string `json:"username" binding:"required"`
	Password string `json:"password" binding:"required"`
}

type LoginResponse struct {
	AccessToken string `json:"access_token"`
	TokenType   string `json:"token_type"`
	ExpiresIn   int64  `json:"expires_in"`
	User        *User  `json:"user"`
}

func (s *AuthService) Login(ctx context.Context, req *LoginRequest) (*LoginResponse, error) {
	user := &User{}
	err := s.db.WithContext(ctx).Where("username = ? OR email = ?", req.Username, req.Username).First(user).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, fmt.Errorf("用户名或密码错误")
		}
		return nil, err
	}

	if user.Status != "active" {
		return nil, fmt.Errorf("账户已被禁用")
	}

	if !s.verifyPassword(req.Password, user.PasswordHash) {
		return nil, fmt.Errorf("用户名或密码错误")
	}

	token, expiresIn, err := s.generateToken(user)
	if err != nil {
		return nil, err
	}

	return &LoginResponse{
		AccessToken: token,
		TokenType:   "Bearer",
		ExpiresIn:   expiresIn,
		User:        user,
	}, nil
}

func (s *AuthService) generateToken(user *User) (string, int64, error) {
	now := time.Now()
	expiresAt := now.Add(s.config.TokenTTL)

	claims := jwt.MapClaims{
		"user_id": user.ID,
		"username": user.Username,
		"role":    string(user.Role),
		"exp":     expiresAt.Unix(),
		"iat":     now.Unix(),
		"jti":     uuid.New().String(),
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signedToken, err := token.SignedString([]byte(s.config.JWTSecret))
	if err != nil {
		return "", 0, err
	}

	return signedToken, int64(s.config.TokenTTL.Seconds()), nil
}

func (s *AuthService) ValidateToken(tokenString string) (jwt.MapClaims, error) {
	token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
		}
		return []byte(s.config.JWTSecret), nil
	})

	if err != nil {
		return nil, err
	}

	if claims, ok := token.Claims.(jwt.MapClaims); ok && token.Valid {
		return claims, nil
	}

	return nil, fmt.Errorf("invalid token")
}

func (s *AuthService) ValidateAPIKey(apiKey string) (*User, error) {
	user := &User{}
	err := s.db.Where("api_key = ? AND status = ?", apiKey, "active").First(user).Error
	if err != nil {
		return nil, err
	}
	return user, nil
}

func (s *AuthService) verifyPassword(password, hash string) bool {
	return hash == s.hashPassword(password)
}

func (s *AuthService) hashPassword(password string) string {
	return fmt.Sprintf("hash_%s", password)
}

func (s *AuthService) CreateUser(ctx context.Context, username, email, password string, role Role) (*User, error) {
	now := time.Now()
	user := &User{
		ID:           "user_" + uuid.New().String()[:16],
		Username:     username,
		Email:        email,
		PasswordHash: s.hashPassword(password),
		Role:         role,
		APIKey:       "ak_" + uuid.New().String(),
		Status:       "active",
		CreatedAt:    now,
		UpdatedAt:    now,
	}

	if err := s.db.WithContext(ctx).Create(user).Error; err != nil {
		return nil, err
	}

	return user, nil
}

func (s *AuthService) GetUserByID(ctx context.Context, userID string) (*User, error) {
	user := &User{}
	err := s.db.WithContext(ctx).Where("id = ?", userID).First(user).Error
	return user, err
}

type Permission string

const (
	PermModelCreate   Permission = "model:create"
	PermModelRead     Permission = "model:read"
	PermModelUpdate   Permission = "model:update"
	PermModelDelete   Permission = "model:delete"
	PermConfigRead    Permission = "config:read"
	PermConfigWrite   Permission = "config:write"
	PermGPUAllocate   Permission = "gpu:allocate"
	PermFeatureRead   Permission = "feature:read"
	PermFeatureWrite  Permission = "feature:write"
	PermAdminAll      Permission = "admin:*"
)

var rolePermissions = map[Role][]Permission{
	RoleAdmin: {
		PermModelCreate, PermModelRead, PermModelUpdate, PermModelDelete,
		PermConfigRead, PermConfigWrite,
		PermGPUAllocate,
		PermFeatureRead, PermFeatureWrite,
		PermAdminAll,
	},
	RoleUser: {
		PermModelCreate, PermModelRead, PermModelUpdate,
		PermConfigRead,
		PermGPUAllocate,
		PermFeatureRead, PermFeatureWrite,
	},
	RoleViewer: {
		PermModelRead,
		PermConfigRead,
		PermFeatureRead,
	},
}

func (s *AuthService) CheckPermission(role Role, permission Permission) bool {
	perms, ok := rolePermissions[role]
	if !ok {
		return false
	}

	for _, p := range perms {
		if p == permission || p == PermAdminAll {
			return true
		}
		if strings.HasSuffix(string(p), ":*") {
			prefix := strings.TrimSuffix(string(p), "*")
			if strings.HasPrefix(string(permission), prefix) {
				return true
			}
		}
	}
	return false
}

func HasRole(ctx context.Context, role string) bool {
	roles, ok := ctx.Value("user_roles").([]string)
	if !ok {
		return false
	}
	for _, r := range roles {
		if r == role {
			return true
		}
	}
	return false
}
