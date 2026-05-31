package gateway

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/cache"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
	apperrors "session189/pkg/errors"
)

const (
	cacheTTL         = time.Hour
	apiKeyPrefix     = "sk-"
	authSchemeBearer = "Bearer"
	authSchemeAPIKey = "ApiKey"
)

type AuthManager struct {
	jwtSecret      string
	jwtExpireHours int
}

type UserClaims struct {
	UserID   string `json:"user_id"`
	Username string `json:"username"`
	Role     string `json:"role"`
	jwt.RegisteredClaims
}

type APIKey struct {
	KeyID     string    `json:"key_id" gorm:"primaryKey;type:varchar(64)"`
	Key       string    `json:"key" gorm:"type:varchar(128);uniqueIndex"`
	Name      string    `json:"name"`
	UserID    string    `json:"user_id" gorm:"index"`
	Role      string    `json:"role"`
	ExpiresAt *time.Time `json:"expires_at,omitempty"`
	CreatedAt time.Time `json:"created_at"`
}

func (APIKey) TableName() string { return "api_keys" }

func NewAuthManager(jwtSecret string, jwtExpireHours int) *AuthManager {
	return &AuthManager{
		jwtSecret:      jwtSecret,
		jwtExpireHours: jwtExpireHours,
	}
}

func (a *AuthManager) GenerateToken(userID, username, role string) (string, time.Time, error) {
	expireAt := time.Now().Add(time.Duration(a.jwtExpireHours) * time.Hour)

	claims := UserClaims{
		UserID:   userID,
		Username: username,
		Role:     role,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(expireAt),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			Issuer:    "session189",
			Subject:   userID,
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenString, err := token.SignedString([]byte(a.jwtSecret))
	if err != nil {
		return "", time.Time{}, apperrors.Internal("sign token failed", err)
	}

	return tokenString, expireAt, nil
}

func (a *AuthManager) ValidateToken(tokenString string) (*UserClaims, error) {
	claims := &UserClaims{}
	token, err := jwt.ParseWithClaims(tokenString, claims, a.jwtKeyFunc)
	if err != nil {
		return nil, apperrors.Unauthorized(fmt.Sprintf("parse token failed: %v", err))
	}
	if !token.Valid {
		return nil, apperrors.Unauthorized("invalid token")
	}
	return claims, nil
}

func (a *AuthManager) jwtKeyFunc(token *jwt.Token) (interface{}, error) {
	if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
		return nil, apperrors.Unauthorized(fmt.Sprintf("unexpected signing method: %v", token.Header["alg"]))
	}
	return []byte(a.jwtSecret), nil
}

func (a *AuthManager) GenerateAPIKey(userID, name, role string, expiresIn *time.Duration) (*APIKey, error) {
	key := fmt.Sprintf("%s%s", apiKeyPrefix, strings.ReplaceAll(uuid.New().String(), "-", ""))

	var expiresAt *time.Time
	if expiresIn != nil {
		t := time.Now().Add(*expiresIn)
		expiresAt = &t
	}

	apiKey := &APIKey{
		KeyID:     uuid.New().String(),
		Key:       key,
		Name:      name,
		UserID:    userID,
		Role:      role,
		ExpiresAt: expiresAt,
		CreatedAt: time.Now(),
	}

	if err := database.DB.Create(apiKey).Error; err != nil {
		return nil, apperrors.Internal("create api key failed", err)
	}

	logger.Info("API key generated", zap.String("key_id", apiKey.KeyID), zap.String("user_id", userID))
	return apiKey, nil
}

func (a *AuthManager) ValidateAPIKey(key string) (*APIKey, error) {
	if cached, err := a.getCachedAPIKey(key); err == nil {
		return cached, nil
	}

	var apiKey APIKey
	if err := database.DB.Where("key = ?", key).First(&apiKey).Error; err != nil {
		return nil, apperrors.Unauthorized("api key not found")
	}

	if isExpired(apiKey.ExpiresAt) {
		return nil, apperrors.Unauthorized("api key expired")
	}

	a.cacheAPIKey(key, &apiKey)
	return &apiKey, nil
}

func (a *AuthManager) RevokeAPIKey(keyID string) error {
	if err := database.DB.Where("key_id = ?", keyID).Delete(&APIKey{}).Error; err != nil {
		return apperrors.Internal("revoke api key failed", err)
	}
	return nil
}

func (a *AuthManager) ListAPIKeys(userID string) ([]APIKey, error) {
	var keys []APIKey
	if err := database.DB.Where("user_id = ?", userID).Order("created_at DESC").Find(&keys).Error; err != nil {
		return nil, apperrors.Internal("list api keys failed", err)
	}
	return keys, nil
}

func (a *AuthManager) getCachedAPIKey(key string) (*APIKey, error) {
	cachedKey, err := cache.Get(context.Background(), cacheKeyForAPIKey(key))
	if err != nil || cachedKey == "" {
		return nil, err
	}

	var apiKey APIKey
	if err := json.Unmarshal([]byte(cachedKey), &apiKey); err != nil {
		return nil, err
	}
	if isExpired(apiKey.ExpiresAt) {
		return nil, apperrors.Unauthorized("api key expired")
	}
	return &apiKey, nil
}

func (a *AuthManager) cacheAPIKey(key string, apiKey *APIKey) {
	cachedData, _ := json.Marshal(apiKey)
	_ = cache.Set(context.Background(), cacheKeyForAPIKey(key), string(cachedData), cacheTTL)
}

func (a *AuthManager) ExtractCredentials(authHeader string) (string, string, error) {
	if authHeader == "" {
		return "", "", apperrors.Unauthorized("authorization header is required")
	}

	parts := strings.SplitN(authHeader, " ", 2)
	if len(parts) != 2 {
		return "", "", apperrors.Unauthorized("invalid authorization header format")
	}

	scheme, credentials := parts[0], parts[1]
	switch scheme {
	case authSchemeBearer, authSchemeAPIKey:
		return scheme, credentials, nil
	default:
		return "", "", apperrors.Unauthorized(fmt.Sprintf("invalid authorization scheme, expected %s or %s", authSchemeBearer, authSchemeAPIKey))
	}
}

func cacheKeyForAPIKey(key string) string {
	return fmt.Sprintf("apikey:%s", key)
}

func isExpired(expiresAt *time.Time) bool {
	return expiresAt != nil && expiresAt.Before(time.Now())
}

func ExtractTokenFromAuthHeader(authHeader string) (string, error) {
	_, credentials, err := (&AuthManager{}).ExtractCredentials(authHeader)
	return credentials, err
}
