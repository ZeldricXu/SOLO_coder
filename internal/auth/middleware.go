package auth

import (
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
	return nil
}

func BuildMiddlewareChain(policy *models.AuthPolicy, validKeys map[string]string) (*MiddlewareChain, error) {
	chain := NewMiddlewareChain()

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
