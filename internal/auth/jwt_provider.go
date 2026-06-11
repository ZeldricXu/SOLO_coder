package auth

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"DF1-56/internal/models"
)

type JWTProvider struct {
	config    *models.JWTConfig
	optional  bool
	publicKey *rsa.PublicKey
}

func (p *JWTProvider) Name() string {
	return "jwt"
}

func (p *JWTProvider) Validate(ctx context.Context, req *http.Request) (*AuthResult, error) {
	token, err := p.extractToken(req)
	if err != nil {
		if p.optional {
			return &AuthResult{Authenticated: false}, nil
		}
		return nil, err
	}

	claims, err := p.validateToken(token)
	if err != nil {
		if p.optional {
			return &AuthResult{Authenticated: false}, nil
		}
		return nil, fmt.Errorf("invalid token: %w", err)
	}

	result := &AuthResult{
		Authenticated: true,
		Claims:        claims,
	}

	if sub, ok := claims["sub"].(string); ok {
		result.Subject = sub
	}

	if perms, ok := claims["permissions"].([]interface{}); ok {
		permissions := make([]string, 0, len(perms))
		for _, perm := range perms {
			if p, ok := perm.(string); ok {
				permissions = append(permissions, p)
			}
		}
		result.Permissions = permissions
	}

	if perms, ok := claims["permissions"].([]string); ok {
		result.Permissions = perms
	}

	return result, nil
}

func (p *JWTProvider) Configure(config interface{}) error {
	if config == nil {
		return errors.New("jwt config is required")
	}

	cfg, ok := config.(*models.JWTConfig)
	if !ok {
		cfgMap, ok := config.(map[string]interface{})
		if !ok {
			return errors.New("invalid jwt config type")
		}
		cfg = p.parseConfigFromMap(cfgMap)
	}

	if cfg.Algorithm == "" {
		cfg.Algorithm = "HS256"
	}

	if strings.ToUpper(cfg.Algorithm) == "RS256" && cfg.PublicKey != "" {
		pubKey, err := p.parseRSAPublicKey(cfg.PublicKey)
		if err != nil {
			return fmt.Errorf("invalid RSA public key: %w", err)
		}
		p.publicKey = pubKey
	}

	p.config = cfg
	return nil
}

func (p *JWTProvider) parseConfigFromMap(cfgMap map[string]interface{}) *models.JWTConfig {
	cfg := &models.JWTConfig{}

	if v, ok := cfgMap["secret"].(string); ok {
		cfg.Secret = v
	}
	if v, ok := cfgMap["public_key"].(string); ok {
		cfg.PublicKey = v
	}
	if v, ok := cfgMap["algorithm"].(string); ok {
		cfg.Algorithm = v
	}
	if v, ok := cfgMap["issuer"].(string); ok {
		cfg.Issuer = v
	}
	if v, ok := cfgMap["audience"].([]interface{}); ok {
		audience := make([]string, 0, len(v))
		for _, a := range v {
			if s, ok := a.(string); ok {
				audience = append(audience, s)
			}
		}
		cfg.Audience = audience
	}
	if v, ok := cfgMap["claims_required"].([]interface{}); ok {
		claims := make([]string, 0, len(v))
		for _, c := range v {
			if s, ok := c.(string); ok {
				claims = append(claims, s)
			}
		}
		cfg.ClaimsRequired = claims
	}

	return cfg
}

func (p *JWTProvider) extractToken(req *http.Request) (string, error) {
	authHeader := req.Header.Get("Authorization")
	if authHeader != "" {
		parts := strings.Split(authHeader, " ")
		if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") {
			return parts[1], nil
		}
	}

	queryToken := req.URL.Query().Get("token")
	if queryToken != "" {
		return queryToken, nil
	}

	cookie, err := req.Cookie("jwt_token")
	if err == nil && cookie.Value != "" {
		return cookie.Value, nil
	}

	return "", errors.New("token not found")
}

func (p *JWTProvider) validateToken(tokenString string) (jwt.MapClaims, error) {
	claims := jwt.MapClaims{}

	token, err := jwt.ParseWithClaims(tokenString, claims, func(token *jwt.Token) (interface{}, error) {
		if alg := strings.ToUpper(p.config.Algorithm); alg != "" {
			if token.Method.Alg() != alg {
				return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
			}
		}

		switch strings.ToUpper(p.config.Algorithm) {
		case "HS256":
			if p.config.Secret == "" {
				return nil, errors.New("secret not configured for HS256")
			}
			return []byte(p.config.Secret), nil
		case "RS256":
			if p.publicKey == nil {
				return nil, errors.New("public key not configured for RS256")
			}
			return p.publicKey, nil
		default:
			return nil, fmt.Errorf("unsupported algorithm: %s", p.config.Algorithm)
		}
	})

	if err != nil {
		return nil, err
	}

	if !token.Valid {
		return nil, errors.New("invalid token")
	}

	if p.config.Issuer != "" {
		issuer, err := claims.GetIssuer()
		if err != nil || issuer != p.config.Issuer {
			return nil, errors.New("invalid issuer")
		}
	}

	if len(p.config.Audience) > 0 {
		audience, err := claims.GetAudience()
		if err != nil {
			return nil, errors.New("invalid audience")
		}
		found := false
		for _, aud := range p.config.Audience {
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

	if len(p.config.ClaimsRequired) > 0 {
		for _, claim := range p.config.ClaimsRequired {
			if _, ok := claims[claim]; !ok {
				return nil, fmt.Errorf("required claim missing: %s", claim)
			}
		}
	}

	return claims, nil
}

func (p *JWTProvider) parseRSAPublicKey(publicKeyStr string) (*rsa.PublicKey, error) {
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

func (p *JWTProvider) SetOptional(optional bool) {
	p.optional = optional
}
