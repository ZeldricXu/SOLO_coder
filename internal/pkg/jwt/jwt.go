package jwt

import (
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v4"
	"github.com/google/uuid"
)

type Claims struct {
	UserID   uuid.UUID `json:"user_id"`
	TenantID uuid.UUID `json:"tenant_id"`
	jwt.RegisteredClaims
}

func GenerateToken(userID, tenantID uuid.UUID, secret string, expireHours int, issuer string) (string, error) {
	if secret == "" {
		return "", errors.New("secret cannot be empty")
	}
	if expireHours <= 0 {
		expireHours = 24
	}
	if issuer == "" {
		issuer = "knowledgebase"
	}

	claims := Claims{
		UserID:   userID,
		TenantID: tenantID,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Duration(expireHours) * time.Hour)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			NotBefore: jwt.NewNumericDate(time.Now()),
			Issuer:    issuer,
			Subject:   userID.String(),
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(secret))
}

func ParseToken(tokenString, secret string) (*Claims, error) {
	if tokenString == "" {
		return nil, errors.New("token cannot be empty")
	}
	if secret == "" {
		return nil, errors.New("secret cannot be empty")
	}

	token, err := jwt.ParseWithClaims(tokenString, &Claims{}, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("unexpected signing method")
		}
		return []byte(secret), nil
	})

	if err != nil {
		return nil, err
	}

	if claims, ok := token.Claims.(*Claims); ok && token.Valid {
		return claims, nil
	}

	return nil, errors.New("invalid token")
}

func ValidateToken(tokenString, secret string) bool {
	_, err := ParseToken(tokenString, secret)
	return err == nil
}
