package auth

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"DF1-56/internal/models"
)

type OAuth2Middleware struct {
	config   *models.OAuth2Config
	optional bool
	priority int
	cache    map[string]*cacheEntry
	cacheMu  sync.RWMutex
	client   *http.Client
}

type cacheEntry struct {
	response *IntrospectionResponse
	expires  time.Time
}

type IntrospectionResponse struct {
	Active   bool   `json:"active"`
	Scope    string `json:"scope,omitempty"`
	ClientID string `json:"client_id,omitempty"`
	UserID   string `json:"user_id,omitempty"`
	Username string `json:"username,omitempty"`
	TokenType string `json:"token_type,omitempty"`
	Exp      int64  `json:"exp,omitempty"`
	Iat      int64  `json:"iat,omitempty"`
	Nbf      int64  `json:"nbf,omitempty"`
	Sub      string `json:"sub,omitempty"`
	Aud      string `json:"aud,omitempty"`
	Iss      string `json:"iss,omitempty"`
	Jti      string `json:"jti,omitempty"`
}

func NewOAuth2Middleware(config *models.OAuth2Config, optional bool, priority int) (*OAuth2Middleware, error) {
	if config.IntrospectionURL == "" {
		return nil, errors.New("introspection URL is required")
	}
	if config.ClientID == "" {
		return nil, errors.New("client ID is required")
	}
	if config.ClientSecret == "" {
		return nil, errors.New("client secret is required")
	}

	return &OAuth2Middleware{
		config:   config,
		optional: optional,
		priority: priority,
		cache:    make(map[string]*cacheEntry),
		client: &http.Client{
			Timeout: 10 * time.Second,
		},
	}, nil
}

func (m *OAuth2Middleware) Name() string {
	return "oauth2"
}

func (m *OAuth2Middleware) Priority() int {
	return m.priority
}

func (m *OAuth2Middleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	token, err := m.extractToken(ctx)
	if err != nil {
		if m.optional {
			return next(ctx)
		}
		return Unauthorized(ctx, "missing or invalid token")
	}

	introspectResp, err := m.introspectToken(token)
	if err != nil {
		if m.optional {
			return next(ctx)
		}
		return Unauthorized(ctx, "token introspection failed: "+err.Error())
	}

	if !introspectResp.Active {
		if m.optional {
			return next(ctx)
		}
		return Unauthorized(ctx, "inactive or expired token")
	}

	ctx.Set(string(models.ContextKeyClaims), introspectResp)

	userID := introspectResp.UserID
	if userID == "" {
		userID = introspectResp.Sub
	}
	if userID != "" {
		ctx.UserID = userID
		ctx.Set(string(models.ContextKeyUserID), userID)
	}

	return next(ctx)
}

func (m *OAuth2Middleware) extractToken(ctx *models.GatewayContext) (string, error) {
	authHeader := ctx.Request.Header.Get("Authorization")
	if authHeader != "" {
		parts := strings.Split(authHeader, " ")
		if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") {
			return parts[1], nil
		}
	}

	queryToken := ctx.Request.URL.Query().Get("access_token")
	if queryToken != "" {
		return queryToken, nil
	}

	cookie, err := ctx.Request.Cookie("access_token")
	if err == nil && cookie.Value != "" {
		return cookie.Value, nil
	}

	return "", errors.New("token not found")
}

func (m *OAuth2Middleware) introspectToken(token string) (*IntrospectionResponse, error) {
	if cached := m.getFromCache(token); cached != nil {
		return cached, nil
	}

	data := url.Values{}
	data.Set("token", token)
	if m.config.TokenTypeHint != "" {
		data.Set("token_type_hint", m.config.TokenTypeHint)
	}

	req, err := http.NewRequest("POST", m.config.IntrospectionURL, strings.NewReader(data.Encode()))
	if err != nil {
		return nil, fmt.Errorf("failed to create introspection request: %w", err)
	}

	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	auth := base64.StdEncoding.EncodeToString([]byte(m.config.ClientID + ":" + m.config.ClientSecret))
	req.Header.Set("Authorization", "Basic "+auth)

	for k, v := range m.config.Headers {
		req.Header.Set(k, v)
	}

	resp, err := m.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("introspection request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("introspection endpoint returned status %d: %s", resp.StatusCode, string(body))
	}

	var body bytes.Buffer
	_, err = io.Copy(&body, resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read introspection response: %w", err)
	}

	var introspectResp IntrospectionResponse
	if err := json.Unmarshal(body.Bytes(), &introspectResp); err != nil {
		return nil, fmt.Errorf("failed to parse introspection response: %w", err)
	}

	m.addToCache(token, &introspectResp)

	return &introspectResp, nil
}

func (m *OAuth2Middleware) getFromCache(token string) *IntrospectionResponse {
	m.cacheMu.RLock()
	defer m.cacheMu.RUnlock()

	entry, ok := m.cache[token]
	if !ok {
		return nil
	}

	if time.Now().After(entry.expires) {
		return nil
	}

	return entry.response
}

func (m *OAuth2Middleware) addToCache(token string, resp *IntrospectionResponse) {
	ttl := 60 * time.Second
	if resp.Exp > 0 {
		expTime := time.Unix(resp.Exp, 0)
		ttl = time.Until(expTime)
		if ttl < 0 {
			return
		}
		if ttl > 5*time.Minute {
			ttl = 5 * time.Minute
		}
	}

	m.cacheMu.Lock()
	defer m.cacheMu.Unlock()

	m.cache[token] = &cacheEntry{
		response: resp,
		expires:  time.Now().Add(ttl),
	}

	if len(m.cache) > 1000 {
		m.cleanupCache()
	}
}

func (m *OAuth2Middleware) cleanupCache() {
	now := time.Now()
	for token, entry := range m.cache {
		if now.After(entry.expires) {
			delete(m.cache, token)
		}
	}

	if len(m.cache) > 1000 {
		m.cache = make(map[string]*cacheEntry)
	}
}
