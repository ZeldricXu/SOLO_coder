package auth

import (
	"context"
	"net/http"

	"DF1-56/internal/models"
)

type OnionMiddleware func(http.Handler) http.Handler

type onionCtxKeyType struct{}

var onionCtxKey = onionCtxKeyType{}

func WithGatewayContext(r *http.Request, gctx *models.GatewayContext) *http.Request {
	return r.WithContext(context.WithValue(r.Context(), onionCtxKey, gctx))
}

func GatewayContextFromRequest(r *http.Request) (*models.GatewayContext, bool) {
	gctx, ok := r.Context().Value(onionCtxKey).(*models.GatewayContext)
	return gctx, ok
}

func Compose(middlewares ...OnionMiddleware) OnionMiddleware {
	return func(next http.Handler) http.Handler {
		for i := len(middlewares) - 1; i >= 0; i-- {
			next = middlewares[i](next)
		}
		return next
	}
}

func ComposeWithOrder(middlewares []OnionMiddleware, order []int) OnionMiddleware {
	if len(order) != len(middlewares) {
		return Compose(middlewares...)
	}
	reordered := make([]OnionMiddleware, len(middlewares))
	for i, idx := range order {
		if idx < 0 || idx >= len(middlewares) {
			reordered[i] = middlewares[i]
			continue
		}
		reordered[i] = middlewares[idx]
	}
	return Compose(reordered...)
}

func ToOnionMiddleware(mw Middleware) OnionMiddleware {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gctx, ok := GatewayContextFromRequest(r)
			if !ok {
				next.ServeHTTP(w, r)
				return
			}

			nextFunc := func(ctx *models.GatewayContext) error {
				updatedReq := WithGatewayContext(ctx.Request, ctx)
				ctx.Request = updatedReq
				next.ServeHTTP(ctx.Response, updatedReq)
				return nil
			}

			if err := mw.Handle(gctx, nextFunc); err != nil {
				return
			}
		})
	}
}

func FromOnionMiddleware(name string, mw OnionMiddleware) Middleware {
	return &onionToOldAdapter{name: name, mw: mw}
}

type onionToOldAdapter struct {
	name string
	mw   OnionMiddleware
}

func (a *onionToOldAdapter) Name() string {
	return a.name
}

func (a *onionToOldAdapter) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	nextHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gctx, _ := GatewayContextFromRequest(r)
		if gctx == nil {
			gctx = ctx
		}
		_ = next(gctx)
	})

	r := WithGatewayContext(ctx.Request, ctx)
	ctx.Request = r

	handler := a.mw(nextHandler)
	handler.ServeHTTP(ctx.Response, r)
	return nil
}
