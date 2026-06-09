package auth

import (
	"crypto/rsa"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"DF1-56/internal/models"
)

type JWTMiddleware struct {
	config    *models.JWTConfig
	optional  bool
	priority  int
	publicKey *rsa.PublicKey
}

func NewJWTMiddleware(config *models.JWTConfig, optional bool, priority int) (*JWTMiddleware, error) {
	mw := &JWTMiddleware{
		config:   config,
		optional: optional,
		priority: priority,
	}

	if strings.ToUpper(config.Algorithm) == "RS256" && config.PublicKey != "" {
		pubKey, err := parseRSAPublicKey(config.PublicKey)
		if err != nil {
			return nil, fmt.Errorf("invalid RSA public key: %w", err)
		}
		mw.publicKey = pubKey
	}

	return mw, nil
}

func (m *JWTMiddleware) Name() string {
	return "jwt"
}

func (m *JWTMiddleware) Priority() int {
	return m.priority
}

func (m *JWTMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	token, err := m.extractToken(ctx)
	if err != nil {
		if m.optional {
			return next(ctx)
		}
		return Unauthorized(ctx, "missing or invalid token")
	}

	claims, err := m.validateToken(token)
	if err != nil {
		if m.optional {
			return next(ctx)
		}
		return Unauthorized(ctx, "invalid token: "+err.Error())
	}

	ctx.Set(string(models.ContextKeyClaims), claims)

	if sub, ok := claims["sub"].(string); ok {
		ctx.UserID = sub
		ctx.Set(string(models.ContextKeyUserID), sub)
	}

	return next(ctx)
}

func (m *JWTMiddleware) extractToken(ctx *models.GatewayContext) (string, error) {
	authHeader := ctx.Request.Header.Get("Authorization")
	if authHeader != "" {
		parts := strings.Split(authHeader, " ")
		if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") {
			return parts[1], nil
		}
	}

	queryToken := ctx.Request.URL.Query().Get("token")
	if queryToken != "" {
		return queryToken, nil
	}

	cookie, err := ctx.Request.Cookie("jwt_token")
	if err == nil && cookie.Value != "" {
		return cookie.Value, nil
	}

	return "", errors.New("token not found")
}

func (m *JWTMiddleware) validateToken(tokenString string) (jwt.MapClaims, error) {
	claims := jwt.MapClaims{}

	token, err := jwt.ParseWithClaims(tokenString, claims, func(token *jwt.Token) (interface{}, error) {
		if alg := strings.ToUpper(m.config.Algorithm); alg != "" {
			if token.Method.Alg() != alg {
				return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
			}
		}

		switch strings.ToUpper(m.config.Algorithm) {
		case "HS256":
			if m.config.Secret == "" {
				return nil, errors.New("secret not configured for HS256")
			}
			return []byte(m.config.Secret), nil
		case "RS256":
			if m.publicKey == nil {
				return nil, errors.New("public key not configured for RS256")
			}
			return m.publicKey, nil
		default:
			return nil, fmt.Errorf("unsupported algorithm: %s", m.config.Algorithm)
		}
	})

	if err != nil {
		return nil, err
	}

	if !token.Valid {
		return nil, errors.New("invalid token")
	}

	if m.config.Issuer != "" {
		issuer, err := claims.GetIssuer()
		if err != nil || issuer != m.config.Issuer {
			return nil, errors.New("invalid issuer")
		}
	}

	if len(m.config.Audience) > 0 {
		audience, err := claims.GetAudience()
		if err != nil {
			return nil, errors.New("invalid audience")
		}
		found := false
		for _, aud := range m.config.Audience {
			for _, a := range audience {
				if a == aud {
					found = true
					break
				}
			}
			if found {
				break
			}
		}
		if !found {
			return nil, errors.New("audience not allowed")
		}
	}

	exp, err := claims.GetExpirationTime()
	if err == nil && exp != nil {
		if exp.Time.Before(time.Now()) {
			return nil, errors.New("token expired")
		}
	}

	if len(m.config.ClaimsRequired) > 0 {
		for _, claim := range m.config.ClaimsRequired {
			if _, ok := claims[claim]; !ok {
				return nil, fmt.Errorf("required claim missing: %s", claim)
			}
		}
	}

	return claims, nil
}

func parseRSAPublicKey(publicKeyStr string) (*rsa.PublicKey, error) {
	publicKeyStr = strings.TrimSpace(publicKeyStr)
	if !strings.HasPrefix(publicKeyStr, "-----BEGIN") {
		decoded, err := base64.StdEncoding.DecodeString(publicKeyStr)
		if err == nil {
			publicKeyStr = string(decoded)
		}
	}

	pubKey, err := jwt.ParseRSAPublicKeyFromPEM([]byte(publicKeyStr))
	if err != nil {
		return nil, err
	}
	return pubKey, nil
}
