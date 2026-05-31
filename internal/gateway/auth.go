package gateway

import (
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

type User struct {
	UserID string   `json:"user_id"`
	Roles  []string `json:"roles"`
}

type AuthClaims struct {
	UserID string   `json:"user_id"`
	Roles  []string `json:"roles"`
	jwt.RegisteredClaims
}

type UserStore interface {
	Validate(userID, password string) (*User, bool)
	AddUser(userID, password string, roles []string)
	GetUser(userID string) (*User, bool)
}

type InMemoryUserStore struct {
	users map[string]string
	roles map[string][]string
	mu    sync.RWMutex
}

func NewInMemoryUserStore() *InMemoryUserStore {
	return &InMemoryUserStore{
		users: make(map[string]string),
		roles: make(map[string][]string),
	}
}

func (s *InMemoryUserStore) AddUser(userID, password string, roles []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.users[userID] = password
	s.roles[userID] = roles
}

func (s *InMemoryUserStore) Validate(userID, password string) (*User, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	pwd, ok := s.users[userID]
	if !ok || pwd != password {
		return nil, false
	}

	return &User{UserID: userID, Roles: s.roles[userID]}, true
}

func (s *InMemoryUserStore) GetUser(userID string) (*User, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	roles, ok := s.roles[userID]
	if !ok {
		return nil, false
	}
	return &User{UserID: userID, Roles: roles}, true
}

type TokenManager interface {
	GenerateToken(user *User) (string, error)
	ValidateToken(tokenString string) (*AuthClaims, error)
}

type JWTTokenManager struct {
	secret        []byte
	tokenDuration time.Duration
	issuer        string
}

func NewJWTTokenManager(secret string, tokenDuration time.Duration) *JWTTokenManager {
	return &JWTTokenManager{
		secret:        []byte(secret),
		tokenDuration: tokenDuration,
		issuer:        "session154-gateway",
	}
}

func (m *JWTTokenManager) GenerateToken(user *User) (string, error) {
	claims := AuthClaims{
		UserID: user.UserID,
		Roles:  user.Roles,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(m.tokenDuration)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			Issuer:    m.issuer,
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(m.secret)
}

func (m *JWTTokenManager) ValidateToken(tokenString string) (*AuthClaims, error) {
	claims := &AuthClaims{}
	token, err := jwt.ParseWithClaims(tokenString, claims, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("unexpected signing method")
		}
		return m.secret, nil
	})

	if err != nil {
		return nil, err
	}

	if !token.Valid {
		return nil, errors.New("invalid token")
	}

	return claims, nil
}

type Authenticator struct {
	userStore    UserStore
	tokenManager TokenManager
}

func NewAuthenticator(jwtSecret string, tokenDuration time.Duration) *Authenticator {
	return &Authenticator{
		userStore:    NewInMemoryUserStore(),
		tokenManager: NewJWTTokenManager(jwtSecret, tokenDuration),
	}
}

func (a *Authenticator) WithUserStore(store UserStore) *Authenticator {
	a.userStore = store
	return a
}

func (a *Authenticator) WithTokenManager(tm TokenManager) *Authenticator {
	a.tokenManager = tm
	return a
}

func (a *Authenticator) AddUser(userID, password string, roles []string) {
	a.userStore.AddUser(userID, password, roles)
}

func (a *Authenticator) Login(userID, password string) (string, error) {
	user, ok := a.userStore.Validate(userID, password)
	if !ok {
		return "", errors.New("invalid credentials")
	}
	return a.tokenManager.GenerateToken(user)
}

func (a *Authenticator) Authenticate(ctx *gin.Context) {
	authHeader := ctx.GetHeader("Authorization")
	if authHeader == "" {
		respondUnauthorized(ctx, "missing authorization header")
		return
	}

	parts := strings.Split(authHeader, " ")
	if len(parts) != 2 || parts[0] != "Bearer" {
		respondUnauthorized(ctx, "invalid authorization format")
		return
	}

	claims, err := a.tokenManager.ValidateToken(parts[1])
	if err != nil {
		respondUnauthorized(ctx, "invalid token")
		return
	}

	ctx.Set("userID", claims.UserID)
	ctx.Set("roles", claims.Roles)
	ctx.Next()
}

type Authorizer struct{}

func NewAuthorizer() *Authorizer {
	return &Authorizer{}
}

func (az *Authorizer) RequireRole(requiredRoles ...string) gin.HandlerFunc {
	return func(ctx *gin.Context) {
		rolesVal, exists := ctx.Get("roles")
		if !exists {
			ctx.JSON(http.StatusForbidden, gin.H{"code": 403, "msg": "no user context"})
			ctx.Abort()
			return
		}

		userRoles, ok := rolesVal.([]string)
		if !ok {
			ctx.JSON(http.StatusForbidden, gin.H{"code": 403, "msg": "invalid roles format"})
			ctx.Abort()
			return
		}

		for _, rr := range requiredRoles {
			for _, ur := range userRoles {
				if ur == rr {
					ctx.Next()
					return
				}
			}
		}

		ctx.JSON(http.StatusForbidden, gin.H{"code": 403, "msg": "insufficient permissions"})
		ctx.Abort()
	}
}

func (az *Authorizer) RequireAnyRole(requiredRoles ...string) gin.HandlerFunc {
	return az.RequireRole(requiredRoles...)
}

func respondUnauthorized(ctx *gin.Context, msg string) {
	ctx.JSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": msg})
	ctx.Abort()
}
