package auth

import (
	"strings"

	"DF1-56/internal/models"
)

type ProviderMiddleware struct {
	provider AuthProvider
	optional bool
	priority int
}

func NewProviderMiddleware(provider AuthProvider, optional bool, priority int) *ProviderMiddleware {
	return &ProviderMiddleware{
		provider: provider,
		optional: optional,
		priority: priority,
	}
}

func (m *ProviderMiddleware) Name() string {
	return "provider_" + m.provider.Name()
}

func (m *ProviderMiddleware) Priority() int {
	return m.priority
}

func (m *ProviderMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	if setter, ok := m.provider.(interface{ SetOptional(bool) }); ok {
		setter.SetOptional(m.optional)
	}

	result, err := m.provider.Validate(ctx.Request.Context(), ctx.Request)
	if err != nil {
		if m.optional {
			return next(ctx)
		}
		return Unauthorized(ctx, err.Error())
	}

	if result == nil || !result.Authenticated {
		if m.optional {
			return next(ctx)
		}
		return Unauthorized(ctx, "authentication failed")
	}

	if result.Claims != nil {
		ctx.Set(string(models.ContextKeyClaims), result.Claims)
	}

	if result.Subject != "" {
		ctx.UserID = result.Subject
		ctx.Set(string(models.ContextKeyUserID), result.Subject)
	}

	if len(result.Permissions) > 0 {
		ctx.Set("permissions", result.Permissions)
	}

	return next(ctx)
}

func BuildProviderMiddlewareChain(policy *models.AuthPolicy, validKeys map[string]string, customProviderConfigs map[string]interface{}) (*MiddlewareChain, error) {
	chain := NewMiddlewareChain()

	RegisterDefaultProviders()

	for _, strategy := range policy.Strategies {
		var mw Middleware
		var err error

		providerName := strings.ToLower(string(strategy.Type))

		if customConfig, ok := customProviderConfigs[providerName]; ok {
			provider, err := CreateProvider(providerName, customConfig)
			if err != nil {
				return nil, err
			}

			if apiKeyProvider, ok := provider.(*APIKeyProvider); ok {
				apiKeyProvider.SetValidKeys(validKeys)
			}

			mw = NewProviderMiddleware(provider, strategy.Optional, strategy.Priority)
		} else {
			switch strategy.Type {
			case models.AuthTypeJWT:
				if strategy.Config.JWTConfig != nil {
					provider := &JWTProvider{}
					if err := provider.Configure(strategy.Config.JWTConfig); err != nil {
						return nil, err
					}
					mw = NewProviderMiddleware(provider, strategy.Optional, strategy.Priority)
				}
			case models.AuthTypeAPIKey:
				if strategy.Config.APIKeyConfig != nil {
					provider := &APIKeyProvider{}
					if err := provider.Configure(strategy.Config.APIKeyConfig); err != nil {
						return nil, err
					}
					provider.SetValidKeys(validKeys)
					mw = NewProviderMiddleware(provider, strategy.Optional, strategy.Priority)
				}
			case models.AuthTypeOAuth2:
				if strategy.Config.OAuth2Config != nil {
					provider := &OAuth2Provider{}
					if err := provider.Configure(strategy.Config.OAuth2Config); err != nil {
						return nil, err
					}
					mw = NewProviderMiddleware(provider, strategy.Optional, strategy.Priority)
				}
			default:
				provider, exists := GetProvider(providerName)
				if exists {
					mw = NewProviderMiddleware(provider, strategy.Optional, strategy.Priority)
				}
			}
		}

		if err != nil {
			return nil, err
		}
		if mw != nil {
			chain.Use(mw)
		}
	}

	return chain, nil
}
