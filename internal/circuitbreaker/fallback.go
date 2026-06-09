package circuitbreaker

import (
	"errors"
	"net/http"

	"DF1-56/internal/models"
)

type FallbackHandler struct {
	DefaultFallback *models.FallbackResp
}

func NewFallbackHandler(fallback *models.FallbackResp) *FallbackHandler {
	if fallback == nil {
		fallback = &models.FallbackResp{
			StatusCode: http.StatusServiceUnavailable,
			Headers: map[string]string{
				"Content-Type": "application/json",
			},
			Body: `{"error":"service unavailable","code":503,"message":"circuit breaker is open"}`,
		}
	}
	if fallback.StatusCode == 0 {
		fallback.StatusCode = http.StatusServiceUnavailable
	}
	if fallback.Body == "" {
		fallback.Body = `{"error":"service unavailable","code":503,"message":"circuit breaker is open"}`
	}
	return &FallbackHandler{
		DefaultFallback: fallback,
	}
}

func (f *FallbackHandler) Handle(ctx *models.GatewayContext, fallback *models.FallbackResp) error {
	if ctx == nil {
		return errors.New("gateway context is nil")
	}
	if ctx.Response == nil {
		return errors.New("response writer is nil")
	}

	resp := fallback
	if resp == nil {
		resp = f.DefaultFallback
	}

	for key, value := range resp.Headers {
		ctx.Response.Header().Set(key, value)
	}

	if ctx.Response.Header().Get("Content-Type") == "" {
		ctx.Response.Header().Set("Content-Type", "application/json")
	}

	ctx.Response.Header().Set("X-Circuit-Breaker", "open")
	ctx.Response.WriteHeader(resp.StatusCode)

	_, err := ctx.Response.Write([]byte(resp.Body))
	return err
}

func (f *FallbackHandler) HandleWithError(ctx *models.GatewayContext, fallback *models.FallbackResp, err error) error {
	if err != nil && fallback == nil {
		customFallback := *f.DefaultFallback
		customFallback.Body = `{"error":"` + err.Error() + `","code":503,"message":"circuit breaker is open"}`
		return f.Handle(ctx, &customFallback)
	}
	return f.Handle(ctx, fallback)
}
