package auth

import (
	"errors"
	"strings"

	"DF1-56/internal/models"
)

type APIKeyMiddleware struct {
	config    *models.APIKeyConfig
	validKeys map[string]string
	optional  bool
	priority  int
}

func NewAPIKeyMiddleware(config *models.APIKeyConfig, validKeys map[string]string, optional bool, priority int) (*APIKeyMiddleware, error) {
	if config.HeaderName == "" && config.QueryParam == "" {
		return nil, errors.New("either header name or query param must be configured")
	}
	return &APIKeyMiddleware{
		config:    config,
		validKeys: validKeys,
		optional:  optional,
		priority:  priority,
	}, nil
}

func (m *APIKeyMiddleware) Name() string {
	return "api_key"
}

func (m *APIKeyMiddleware) Priority() int {
	return m.priority
}

func (m *APIKeyMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	apiKey, err := m.extractAPIKey(ctx)
	if err != nil {
		if m.optional {
			return next(ctx)
		}
		return Unauthorized(ctx, "missing API key")
	}

	if !m.validateAPIKey(apiKey) {
		if m.optional {
			return next(ctx)
		}
		return Unauthorized(ctx, "invalid API key")
	}

	ctx.Set(string(models.ContextKeyAPIKey), apiKey)

	if userID, ok := m.validKeys[apiKey]; ok {
		ctx.UserID = userID
		ctx.Set(string(models.ContextKeyUserID), userID)
	}

	return next(ctx)
}

func (m *APIKeyMiddleware) extractAPIKey(ctx *models.GatewayContext) (string, error) {
	if m.config.HeaderName != "" {
		apiKey := ctx.Request.Header.Get(m.config.HeaderName)
		if apiKey != "" {
			apiKey = strings.TrimSpace(apiKey)
			if strings.HasPrefix(apiKey, "Bearer ") {
				apiKey = strings.TrimPrefix(apiKey, "Bearer ")
			}
			return apiKey, nil
		}
	}

	if m.config.QueryParam != "" {
		apiKey := ctx.Request.URL.Query().Get(m.config.QueryParam)
		if apiKey != "" {
			return strings.TrimSpace(apiKey), nil
		}
	}

	return "", errors.New("API key not found")
}

func (m *APIKeyMiddleware) validateAPIKey(apiKey string) bool {
	if m.validKeys == nil {
		return false
	}
	_, ok := m.validKeys[apiKey]
	return ok
}
