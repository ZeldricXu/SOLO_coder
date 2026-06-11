package auth

import (
	"errors"
	"net/http"
	"sort"

	"DF1-56/internal/models"
)

type Middleware interface {
	Name() string
	Handle(ctx *models.GatewayContext, next models.HandlerFunc) error
}

type MiddlewareChain struct {
	middlewares []Middleware
	sorted      bool
}

func NewMiddlewareChain(middlewares ...Middleware) *MiddlewareChain {
	return &MiddlewareChain{
		middlewares: middlewares,
		sorted:      false,
	}
}

func (c *MiddlewareChain) Use(middleware Middleware) {
	c.middlewares = append(c.middlewares, middleware)
	c.sorted = false
}

func (c *MiddlewareChain) sortByPriority() {
	if c.sorted {
		return
	}

	type priorityMiddleware struct {
		mw       Middleware
		priority int
	}

	var pms []priorityMiddleware
	for _, mw := range c.middlewares {
		priority := 0
		if pm, ok := mw.(interface{ Priority() int }); ok {
			priority = pm.Priority()
		}
		pms = append(pms, priorityMiddleware{mw: mw, priority: priority})
	}

	sort.Slice(pms, func(i, j int) bool {
		return pms[i].priority < pms[j].priority
	})

	c.middlewares = make([]Middleware, len(pms))
	for i, pm := range pms {
		c.middlewares[i] = pm.mw
	}
	c.sorted = true
}

func (c *MiddlewareChain) Handle(ctx *models.GatewayContext) error {
	c.sortByPriority()

	handler := func(ctx *models.GatewayContext) error {
		return nil
	}

	for i := len(c.middlewares) - 1; i >= 0; i-- {
		m := c.middlewares[i]
		next := handler
		handler = func(ctx *models.GatewayContext, m Middleware, next models.HandlerFunc) models.HandlerFunc {
			return func(ctx *models.GatewayContext) error {
				return m.Handle(ctx, next)
			}
		}(ctx, m, next)
	}

	return handler(ctx)
}

func Unauthorized(ctx *models.GatewayContext, message string) error {
	ctx.Response.Header().Set("Content-Type", "application/json")
	ctx.Response.WriteHeader(http.StatusUnauthorized)
	_, _ = ctx.Response.Write([]byte(`{"error":"` + message + `","code":401}`))
	return errors.New(message)
}

func BuildMiddlewareChain(policy *models.AuthPolicy, validKeys map[string]string) (*MiddlewareChain, error) {
	return BuildMiddlewareChainWithCustomProviders(policy, validKeys, nil)
}

func BuildMiddlewareChainWithCustomProviders(policy *models.AuthPolicy, validKeys map[string]string, customProviderConfigs map[string]interface{}) (*MiddlewareChain, error) {
	chain := NewMiddlewareChain()

	RegisterDefaultProviders()

	for _, strategy := range policy.Strategies {
		var mw Middleware
		var err error

		switch strategy.Type {
		case models.AuthTypeJWT:
			if strategy.Config.JWTConfig != nil {
				mw, err = NewJWTMiddleware(strategy.Config.JWTConfig, strategy.Optional, strategy.Priority)
			}
		case models.AuthTypeAPIKey:
			if strategy.Config.APIKeyConfig != nil {
				mw, err = NewAPIKeyMiddleware(strategy.Config.APIKeyConfig, validKeys, strategy.Optional, strategy.Priority)
			}
		case models.AuthTypeOAuth2:
			if strategy.Config.OAuth2Config != nil {
				mw, err = NewOAuth2Middleware(strategy.Config.OAuth2Config, strategy.Optional, strategy.Priority)
			}
		case models.AuthTypeCustom:
			if strategy.Config.CustomProvider != nil {
				mw, err = buildCustomProviderMiddleware(strategy.Config.CustomProvider, validKeys, strategy.Optional, strategy.Priority, customProviderConfigs)
			}
		default:
			providerType := string(strategy.Type)
			if customConfig, ok := customProviderConfigs[providerType]; ok {
				provider, err := CreateProvider(providerType, customConfig)
				if err == nil {
					if apiKeyProvider, ok := provider.(*APIKeyProvider); ok {
						apiKeyProvider.SetValidKeys(validKeys)
					}
					mw = NewProviderMiddleware(provider, strategy.Optional, strategy.Priority)
				}
			} else {
				provider, exists := GetProvider(providerType)
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

func buildCustomProviderMiddleware(customConfig *models.CustomProviderConfig, validKeys map[string]string, optional bool, priority int, customProviderConfigs map[string]interface{}) (Middleware, error) {
	if customConfig == nil {
		return nil, nil
	}

	providerType := customConfig.Type
	if providerType == "" {
		providerType = customConfig.Name
	}

	var config interface{}
	if customConfig.Config != nil {
		config = customConfig.Config
	} else if customProviderConfigs != nil {
		if cfg, ok := customProviderConfigs[providerType]; ok {
			config = cfg
		}
	}

	provider, err := CreateProvider(providerType, config)
	if err != nil {
		return nil, err
	}

	if apiKeyProvider, ok := provider.(*APIKeyProvider); ok {
		apiKeyProvider.SetValidKeys(validKeys)
	}

	return NewProviderMiddleware(provider, optional, priority), nil
}
