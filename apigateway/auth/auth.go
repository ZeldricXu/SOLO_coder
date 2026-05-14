package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"
)

type APIKey struct {
	Key        string
	Secret     string
	UserID     string
	Roles      []string
	ExpiresAt  time.Time
	Enabled    bool
	RateLimit  int
}

type JWTClaims struct {
	UserID    string              `json:"user_id"`
	Roles     []string            `json:"roles"`
	ExpiresAt int64               `json:"exp"`
	IssuedAt  int64               `json:"iat"`
	Custom    map[string]string   `json:"custom,omitempty"`
}

type AuthManager struct {
	apiKeys       map[string]*APIKey
	jwtSecrets    map[string]string
	bearerTokens  map[string]JWTClaims
	mu            sync.RWMutex
}

func NewAuthManager() *AuthManager {
	return &AuthManager{
		apiKeys:      make(map[string]*APIKey),
		jwtSecrets:   make(map[string]string),
		bearerTokens: make(map[string]JWTClaims),
	}
}

func (am *AuthManager) AddAPIKey(key *APIKey) error {
	if key == nil || key.Key == "" {
		return fmt.Errorf("invalid API key")
	}

	am.mu.Lock()
	defer am.mu.Unlock()

	am.apiKeys[key.Key] = key
	return nil
}

func (am *AuthManager) RemoveAPIKey(key string) {
	am.mu.Lock()
	defer am.mu.Unlock()

	delete(am.apiKeys, key)
}

func (am *AuthManager) GetAPIKey(key string) (*APIKey, bool) {
	am.mu.RLock()
	defer am.mu.RUnlock()

	apiKey, exists := am.apiKeys[key]
	return apiKey, exists
}

func (am *AuthManager) AddJWTSecret(issuer, secret string) {
	am.mu.Lock()
	defer am.mu.Unlock()

	am.jwtSecrets[issuer] = secret
}

func (am *AuthManager) AddBearerToken(token string, claims JWTClaims) {
	am.mu.Lock()
	defer am.mu.Unlock()

	am.bearerTokens[token] = claims
}

func (am *AuthManager) Authenticate(r *http.Request) (bool, *JWTClaims, error) {
	authHeader := r.Header.Get("Authorization")
	if authHeader == "" {
		return false, nil, fmt.Errorf("missing authorization header")
	}

	parts := strings.SplitN(authHeader, " ", 2)
	if len(parts) != 2 {
		return false, nil, fmt.Errorf("invalid authorization header format")
	}

	authType := strings.ToLower(parts[0])
	authValue := parts[1]

	switch authType {
	case "bearer":
		return am.authenticateBearer(authValue)
	case "apikey", "api-key", "x-api-key":
		return am.authenticateAPIKey(authValue)
	case "basic":
		return am.authenticateBasic(authValue)
	case "hmac":
		return am.authenticateHMAC(authValue, r)
	default:
		return false, nil, fmt.Errorf("unsupported authentication type: %s", authType)
	}
}

func (am *AuthManager) authenticateBearer(token string) (bool, *JWTClaims, error) {
	am.mu.RLock()
	claims, exists := am.bearerTokens[token]
	am.mu.RUnlock()

	if !exists {
		return false, nil, fmt.Errorf("invalid bearer token")
	}

	now := time.Now().Unix()
	if claims.ExpiresAt > 0 && now > claims.ExpiresAt {
		return false, nil, fmt.Errorf("token has expired")
	}

	return true, &claims, nil
}

func (am *AuthManager) authenticateAPIKey(key string) (bool, *JWTClaims, error) {
	am.mu.RLock()
	apiKey, exists := am.apiKeys[key]
	am.mu.RUnlock()

	if !exists {
		return false, nil, fmt.Errorf("invalid API key")
	}

	if !apiKey.Enabled {
		return false, nil, fmt.Errorf("API key is disabled")
	}

	if !apiKey.ExpiresAt.IsZero() && time.Now().After(apiKey.ExpiresAt) {
		return false, nil, fmt.Errorf("API key has expired")
	}

	claims := &JWTClaims{
		UserID:   apiKey.UserID,
		Roles:    apiKey.Roles,
		IssuedAt: time.Now().Unix(),
	}

	return true, claims, nil
}

func (am *AuthManager) authenticateBasic(credentials string) (bool, *JWTClaims, error) {
	decoded, err := base64.StdEncoding.DecodeString(credentials)
	if err != nil {
		return false, nil, fmt.Errorf("invalid basic auth credentials")
	}

	parts := strings.SplitN(string(decoded), ":", 2)
	if len(parts) != 2 {
		return false, nil, fmt.Errorf("invalid basic auth format")
	}

	username := parts[0]
	password := parts[1]

	if username == "" || password == "" {
		return false, nil, fmt.Errorf("username or password is empty")
	}

	claims := &JWTClaims{
		UserID:   username,
		Roles:    []string{"basic"},
		IssuedAt: time.Now().Unix(),
	}

	return true, claims, nil
}

func (am *AuthManager) authenticateHMAC(authValue string, r *http.Request) (bool, *JWTClaims, error) {
	parts := strings.Split(authValue, ":")
	if len(parts) != 3 {
		return false, nil, fmt.Errorf("invalid HMAC format")
	}

	apiKey := parts[0]
	timestamp := parts[1]
	signature := parts[2]

	am.mu.RLock()
	key, exists := am.apiKeys[apiKey]
	am.mu.RUnlock()

	if !exists || !key.Enabled {
		return false, nil, fmt.Errorf("invalid or disabled API key")
	}

	parsedTime, err := time.Parse(time.RFC3339, timestamp)
	if err != nil {
		return false, nil, fmt.Errorf("invalid timestamp format")
	}

	if time.Since(parsedTime) > 5*time.Minute {
		return false, nil, fmt.Errorf("request has expired")
	}

	message := fmt.Sprintf("%s%s%s%s", r.Method, r.URL.Path, timestamp, apiKey)
	expectedSignature := computeHMAC(message, key.Secret)

	if signature != expectedSignature {
		return false, nil, fmt.Errorf("invalid HMAC signature")
	}

	claims := &JWTClaims{
		UserID:   key.UserID,
		Roles:    key.Roles,
		IssuedAt: time.Now().Unix(),
	}

	return true, claims, nil
}

func (am *AuthManager) CheckPermission(claims *JWTClaims, requiredRoles []string) bool {
	if claims == nil {
		return false
	}

	if len(requiredRoles) == 0 {
		return true
	}

	for _, requiredRole := range requiredRoles {
		for _, userRole := range claims.Roles {
			if userRole == requiredRole || userRole == "admin" {
				return true
			}
		}
	}

	return false
}

func (am *AuthManager) ValidateToken(token string) (*JWTClaims, bool) {
	am.mu.RLock()
	defer am.mu.RUnlock()

	claims, exists := am.bearerTokens[token]
	if !exists {
		return nil, false
	}

	now := time.Now().Unix()
	if claims.ExpiresAt > 0 && now > claims.ExpiresAt {
		return nil, false
	}

	return &claims, true
}

func (am *AuthManager) ListAPIKeys() []*APIKey {
	am.mu.RLock()
	defer am.mu.RUnlock()

	keys := make([]*APIKey, 0, len(am.apiKeys))
	for _, key := range am.apiKeys {
		keyCopy := *key
		keyCopy.Secret = "***"
		keys = append(keys, &keyCopy)
	}
	return keys
}

func (am *AuthManager) UpdateAPIKey(key string, updates *APIKey) error {
	am.mu.Lock()
	defer am.mu.Unlock()

	existing, exists := am.apiKeys[key]
	if !exists {
		return fmt.Errorf("API key not found")
	}

	if updates.UserID != "" {
		existing.UserID = updates.UserID
	}
	if len(updates.Roles) > 0 {
		existing.Roles = updates.Roles
	}
	if !updates.ExpiresAt.IsZero() {
		existing.ExpiresAt = updates.ExpiresAt
	}
	if updates.RateLimit > 0 {
		existing.RateLimit = updates.RateLimit
	}
	existing.Enabled = updates.Enabled

	return nil
}

func computeHMAC(message, secret string) string {
	h := hmac.New(sha256.New, []byte(secret))
	h.Write([]byte(message))
	return base64.StdEncoding.EncodeToString(h.Sum(nil))
}

func GenerateAPIKey() string {
	return "ak_" + fmt.Sprintf("%d", time.Now().UnixNano())
}

func GenerateAPISecret() string {
	return base64.StdEncoding.EncodeToString([]byte(GenerateAPIKey() + time.Now().String()))
}
