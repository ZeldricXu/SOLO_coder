package auth

import (
	"bytes"
	"context"
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

type IntrospectionParser func([]byte) (*IntrospectionResult, error)

type IntrospectionResult struct {
	Active      bool                   `json:"active"`
	Subject     string                 `json:"sub"`
	UserID      string                 `json:"user_id"`
	ClientID    string                 `json:"client_id"`
	Scope       string                 `json:"scope"`
	Permissions []string               `json:"permissions"`
	ExpiresAt   int64                  `json:"exp"`
	Extra       map[string]interface{} `json:"-"`
}

type OAuth2Provider struct {
	config        *models.OAuth2Config
	optional      bool
	cache         map[string]*introspectCacheEntry
	cacheMu       sync.RWMutex
	client        *http.Client
	customParser  IntrospectionParser
	responseField string
	activeField   string
	subjectField  string
	userIDField   string
	scopeField    string
}

type introspectCacheEntry struct {
	response *IntrospectionResult
	expires  time.Time
}

func (p *OAuth2Provider) Name() string {
	return "oauth2"
}

func (p *OAuth2Provider) Validate(ctx context.Context, req *http.Request) (*AuthResult, error) {
	token, err := p.extractToken(req)
	if err != nil {
		if p.optional {
			return &AuthResult{Authenticated: false}, nil
		}
		return nil, err
	}

	introspectResp, err := p.introspectToken(ctx, token)
	if err != nil {
		if p.optional {
			return &AuthResult{Authenticated: false}, nil
		}
		return nil, fmt.Errorf("token introspection failed: %w", err)
	}

	if !introspectResp.Active {
		if p.optional {
			return &AuthResult{Authenticated: false}, nil
		}
		return nil, errors.New("inactive or expired token")
	}

	result := &AuthResult{
		Authenticated: true,
		Claims: map[string]interface{}{
			"client_id": introspectResp.ClientID,
			"scope":     introspectResp.Scope,
		},
	}

	if introspectResp.Extra != nil {
		for k, v := range introspectResp.Extra {
			result.Claims[k] = v
		}
	}

	userID := introspectResp.UserID
	if userID == "" {
		userID = introspectResp.Subject
	}
	if userID != "" {
		result.Subject = userID
		result.Claims["user_id"] = userID
	}

	if introspectResp.Permissions != nil {
		result.Permissions = introspectResp.Permissions
	} else if introspectResp.Scope != "" {
		result.Permissions = strings.Split(introspectResp.Scope, " ")
	}

	return result, nil
}

func (p *OAuth2Provider) Configure(config interface{}) error {
	if config == nil {
		return errors.New("oauth2 config is required")
	}

	cfg, ok := config.(*models.OAuth2Config)
	if !ok {
		cfgMap, ok := config.(map[string]interface{})
		if !ok {
			return errors.New("invalid oauth2 config type")
		}
		cfg = p.parseConfigFromMap(cfgMap)
	}

	if cfg.IntrospectionURL == "" {
		return errors.New("introspection URL is required")
	}
	if cfg.ClientID == "" {
		return errors.New("client ID is required")
	}
	if cfg.ClientSecret == "" {
		return errors.New("client secret is required")
	}

	p.config = cfg
	p.cache = make(map[string]*introspectCacheEntry)
	p.client = &http.Client{
		Timeout: 10 * time.Second,
	}

	if customFormat, ok := cfg.Headers["X-Custom-Response-Format"]; ok {
		if customFormat == "non-standard" {
			p.customParser = p.nonStandardParser
			p.responseField = "data"
			p.activeField = "is_valid"
			p.subjectField = "owner"
			p.userIDField = "user_identifier"
			p.scopeField = "authorities"
		}
	}

	return nil
}

func (p *OAuth2Provider) parseConfigFromMap(cfgMap map[string]interface{}) *models.OAuth2Config {
	cfg := &models.OAuth2Config{
		Headers: make(map[string]string),
	}

	if v, ok := cfgMap["introspection_url"].(string); ok {
		cfg.IntrospectionURL = v
	}
	if v, ok := cfgMap["client_id"].(string); ok {
		cfg.ClientID = v
	}
	if v, ok := cfgMap["client_secret"].(string); ok {
		cfg.ClientSecret = v
	}
	if v, ok := cfgMap["token_type_hint"].(string); ok {
		cfg.TokenTypeHint = v
	}
	if v, ok := cfgMap["headers"].(map[string]interface{}); ok {
		for k, val := range v {
			if s, ok := val.(string); ok {
				cfg.Headers[k] = s
			}
		}
	}

	return cfg
}

func (p *OAuth2Provider) SetCustomParser(parser IntrospectionParser) {
	p.customParser = parser
}

func (p *OAuth2Provider) extractToken(req *http.Request) (string, error) {
	authHeader := req.Header.Get("Authorization")
	if authHeader != "" {
		parts := strings.Split(authHeader, " ")
		if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") {
			return parts[1], nil
		}
	}

	queryToken := req.URL.Query().Get("access_token")
	if queryToken != "" {
		return queryToken, nil
	}

	cookie, err := req.Cookie("access_token")
	if err == nil && cookie.Value != "" {
		return cookie.Value, nil
	}

	return "", errors.New("token not found")
}

func (p *OAuth2Provider) introspectToken(ctx context.Context, token string) (*IntrospectionResult, error) {
	if cached := p.getFromCache(token); cached != nil {
		return cached, nil
	}

	data := url.Values{}
	data.Set("token", token)
	if p.config.TokenTypeHint != "" {
		data.Set("token_type_hint", p.config.TokenTypeHint)
	}

	req, err := http.NewRequestWithContext(ctx, "POST", p.config.IntrospectionURL, strings.NewReader(data.Encode()))
	if err != nil {
		return nil, fmt.Errorf("failed to create introspection request: %w", err)
	}

	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	auth := base64.StdEncoding.EncodeToString([]byte(p.config.ClientID + ":" + p.config.ClientSecret))
	req.Header.Set("Authorization", "Basic "+auth)

	for k, v := range p.config.Headers {
		req.Header.Set(k, v)
	}

	resp, err := p.client.Do(req)
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

	var introspectResp *IntrospectionResult
	if p.customParser != nil {
		introspectResp, err = p.customParser(body.Bytes())
		if err != nil {
			return nil, fmt.Errorf("failed to parse custom introspection response: %w", err)
		}
	} else {
		introspectResp, err = p.standardParser(body.Bytes())
		if err != nil {
			return nil, fmt.Errorf("failed to parse introspection response: %w", err)
		}
	}

	p.addToCache(token, introspectResp)

	return introspectResp, nil
}

func (p *OAuth2Provider) standardParser(body []byte) (*IntrospectionResult, error) {
	var result IntrospectionResult
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

func (p *OAuth2Provider) nonStandardParser(body []byte) (*IntrospectionResult, error) {
	var rawResponse map[string]interface{}
	if err := json.Unmarshal(body, &rawResponse); err != nil {
		return nil, err
	}

	result := &IntrospectionResult{
		Extra: make(map[string]interface{}),
	}

	dataObj, ok := rawResponse[p.responseField].(map[string]interface{})
	if ok {
		if active, ok := dataObj[p.activeField].(bool); ok {
			result.Active = active
		}
		if sub, ok := dataObj[p.subjectField].(string); ok {
			result.Subject = sub
		}
		if userID, ok := dataObj[p.userIDField].(string); ok {
			result.UserID = userID
		}
		if scope, ok := dataObj[p.scopeField].(string); ok {
			result.Scope = scope
		}

		for k, v := range dataObj {
			result.Extra[k] = v
		}
	} else {
		for k, v := range rawResponse {
			result.Extra[k] = v
		}
	}

	return result, nil
}

func (p *OAuth2Provider) getFromCache(token string) *IntrospectionResult {
	p.cacheMu.RLock()
	defer p.cacheMu.RUnlock()

	entry, ok := p.cache[token]
	if !ok {
		return nil
	}

	if time.Now().After(entry.expires) {
		return nil
	}

	return entry.response
}

func (p *OAuth2Provider) addToCache(token string, resp *IntrospectionResult) {
	ttl := 60 * time.Second
	if resp.ExpiresAt > 0 {
		expTime := time.Unix(resp.ExpiresAt, 0)
		ttl = time.Until(expTime)
		if ttl < 0 {
			return
		}
		if ttl > 5*time.Minute {
			ttl = 5 * time.Minute
		}
	}

	p.cacheMu.Lock()
	defer p.cacheMu.Unlock()

	p.cache[token] = &introspectCacheEntry{
		response: resp,
		expires:  time.Now().Add(ttl),
	}

	if len(p.cache) > 1000 {
		p.cleanupCache()
	}
}

func (p *OAuth2Provider) cleanupCache() {
	now := time.Now()
	for token, entry := range p.cache {
		if now.After(entry.expires) {
			delete(p.cache, token)
		}
	}

	if len(p.cache) > 1000 {
		p.cache = make(map[string]*introspectCacheEntry)
	}
}

func (p *OAuth2Provider) SetOptional(optional bool) {
	p.optional = optional
}
