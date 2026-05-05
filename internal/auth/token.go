package auth

import (
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"pixelrealm/pkg/config"
	"pixelrealm/pkg/models"
)

var (
	ErrInvalidToken = errors.New("invalid token")
	ErrTokenExpired = errors.New("token expired")
)

type Claims struct {
	PlayerID models.PlayerID `json:"player_id"`
	Username string          `json:"username"`
	jwt.RegisteredClaims
}

type TokenManager struct {
	config *config.JWTConfig
}

func NewTokenManager(cfg *config.JWTConfig) *TokenManager {
	return &TokenManager{
		config: cfg,
	}
}

func (m *TokenManager) GenerateToken(playerID models.PlayerID, username string) (string, error) {
	now := time.Now()
	expiresAt := now.Add(m.config.ExpireTime)
	
	claims := Claims{
		PlayerID: playerID,
		Username: username,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(expiresAt),
			IssuedAt:  jwt.NewNumericDate(now),
			NotBefore: jwt.NewNumericDate(now),
			Issuer:    "pixelrealm",
			Subject:   string(playerID),
		},
	}
	
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signedToken, err := token.SignedString([]byte(m.config.Secret))
	if err != nil {
		return "", err
	}
	
	return signedToken, nil
}

func (m *TokenManager) ValidateToken(tokenString string) (*Claims, error) {
	token, err := jwt.ParseWithClaims(tokenString, &Claims{}, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, ErrInvalidToken
		}
		return []byte(m.config.Secret), nil
	})
	
	if err != nil {
		if errors.Is(err, jwt.ErrTokenExpired) {
			return nil, ErrTokenExpired
		}
		return nil, ErrInvalidToken
	}
	
	if claims, ok := token.Claims.(*Claims); ok && token.Valid {
		return claims, nil
	}
	
	return nil, ErrInvalidToken
}

func (m *TokenManager) RefreshToken(tokenString string) (string, error) {
	claims, err := m.ValidateToken(tokenString)
	if err != nil && err != ErrTokenExpired {
		return "", err
	}
	
	if err == ErrTokenExpired {
		now := time.Now()
		if now.Sub(claims.ExpiresAt.Time) > 24*time.Hour {
			return "", ErrInvalidToken
		}
	}
	
	return m.GenerateToken(claims.PlayerID, claims.Username)
}
