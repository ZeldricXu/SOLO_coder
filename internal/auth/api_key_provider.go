package auth

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"DF1-56/internal/models"
)

type APIKeyProvider struct {
	config    *models.APIKeyConfig
	validKeys map[string]string
	optional  bool
}

func (p *APIKeyProvider) Name() string {
	return "api_key"
}

func (p *APIKeyProvider) Validate(ctx context.Context, req *http.Request) (*AuthResult, error) {
	apiKey, err := p.extractAPIKey(req)
	if err != nil {
		if p.optional {
			return &AuthResult{Authenticated: false}, nil
		}
		return nil, err
	}

	if !p.validateAPIKey(apiKey) {
		if p.optional {
			return &AuthResult{Authenticated: false}, nil
		}
		return nil, errors.New("invalid API key")
	}

	result := &AuthResult{
		Authenticated: true,
		Claims: map[string]interface{}{
			"api_key": apiKey,
		},
	}

	if userID, ok := p.validKeys[apiKey]; ok {
		result.Subject = userID
		result.Claims["user_id"] = userID
	}

	return result, nil
}

func (p *APIKeyProvider) Configure(config interface{}) error {
	if config == nil {
		return errors.New("api key config is required")
	}

	cfg, ok := config.(*models.APIKeyConfig)
	if !ok {
		cfgMap, ok := config.(map[string]interface{})
		if !ok {
			return errors.New("invalid api key config type")
		}
		cfg = p.parseConfigFromMap(cfgMap)
	}

	if cfg.HeaderName == "" && cfg.QueryParam == "" {
		return errors.New("either header name or query param must be configured")
	}

	p.config = cfg
	return nil
}

func (p *APIKeyProvider) parseConfigFromMap(cfgMap map[string]interface{}) *models.APIKeyConfig {
	cfg := &models.APIKeyConfig{}

	if v, ok := cfgMap["header_name"].(string); ok {
		cfg.HeaderName = v
	}
	if v, ok := cfgMap["query_param"].(string); ok {
		cfg.QueryParam = v
	}

	return cfg
}

func (p *APIKeyProvider) SetValidKeys(validKeys map[string]string) {
	p.validKeys = validKeys
}

func (p *APIKeyProvider) extractAPIKey(req *http.Request) (string, error) {
	if p.config.HeaderName != "" {
		apiKey := req.Header.Get(p.config.HeaderName)
		if apiKey != "" {
			apiKey = strings.TrimSpace(apiKey)
			if strings.HasPrefix(apiKey, "Bearer ") {
				apiKey = strings.TrimPrefix(apiKey, "Bearer ")
			}
			return apiKey, nil
		}
	}

	if p.config.QueryParam != "" {
		apiKey := req.URL.Query().Get(p.config.QueryParam)
		if apiKey != "" {
			return strings.TrimSpace(apiKey), nil
		}
	}

	return "", errors.New("API key not found")
}

func (p *APIKeyProvider) validateAPIKey(apiKey string) bool {
	if p.validKeys == nil {
		return false
	}
	_, ok := p.validKeys[apiKey]
	return ok
}

func (p *APIKeyProvider) SetOptional(optional bool) {
	p.optional = optional
}
