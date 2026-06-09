package telemetry

import (
	"context"
	"net/http"
	"strings"
	"time"

	"DF1-56/internal/models"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

const (
	HeaderTraceID = "X-Trace-ID"
)

type tracedMiddleware struct {
	inner models.Middleware
}

func TraceMiddleware(middleware models.Middleware) models.Middleware {
	return &tracedMiddleware{
		inner: middleware,
	}
}

func (t *tracedMiddleware) Name() string {
	return t.inner.Name()
}

func (t *tracedMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	start := time.Now()
	spanCtx, span := StartSpan(ctx.Context, "middleware."+t.inner.Name(),
		trace.WithSpanKind(trace.SpanKindInternal),
		trace.WithAttributes(
			attribute.String("middleware.name", t.inner.Name()),
		),
	)

	ctx.Context = spanCtx

	defer func() {
		duration := time.Since(start)
		RecordMiddlewareDuration(t.inner.Name(), duration, true)
	}()

	err := t.inner.Handle(ctx, func(gctx *models.GatewayContext) error {
		nextStart := time.Now()
		nextErr := next(gctx)
		RecordMiddlewareDuration("next_handler", time.Since(nextStart), nextErr == nil)
		return nextErr
	})

	EndSpan(span, err)
	return err
}

func CreateRootSpan(ctx *models.GatewayContext) (trace.Span, func()) {
	propagator := propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	)

	carrier := propagation.HeaderCarrier(ctx.Request.Header)
	spanCtx := propagator.Extract(ctx.Context, carrier)

	routeID := ""
	if ctx.Route != nil {
		routeID = ctx.Route.ID
	}

	attrs := []attribute.KeyValue{
		attribute.String("http.method", ctx.Request.Method),
		attribute.String("http.path", ctx.Request.URL.Path),
		attribute.String("http.user_agent", ctx.Request.UserAgent()),
		attribute.String("http.scheme", getScheme(ctx.Request)),
		attribute.String("client.ip", ctx.ClientIP),
		attribute.String("request_id", ctx.RequestID),
		attribute.String("route_id", routeID),
	}

	if ctx.UserID != "" {
		attrs = append(attrs, attribute.String("user.id", ctx.UserID))
	}

	spanCtx, span := StartSpan(spanCtx, "http.request",
		trace.WithSpanKind(trace.SpanKindServer),
		trace.WithAttributes(attrs...),
	)

	ctx.Context = spanCtx
	ctx.TraceID = span.SpanContext().TraceID().String()
	ctx.Response.Header().Set(HeaderTraceID, ctx.TraceID)

	IncrementActiveRequests(ctx.Request.Method, routeID)

	cleanup := func() {
		DecrementActiveRequests(ctx.Request.Method, routeID)
	}

	return span, cleanup
}

func StartUpstreamSpan(ctx context.Context, upstream, method, path string) (context.Context, trace.Span, time.Time) {
	start := time.Now()
	spanCtx, span := StartSpan(ctx, "upstream.call",
		trace.WithSpanKind(trace.SpanKindClient),
		trace.WithAttributes(
			attribute.String("upstream.name", upstream),
			attribute.String("http.method", method),
			attribute.String("http.path", path),
		),
	)
	return spanCtx, span, start
}

func EndUpstreamSpan(span trace.Span, start time.Time, upstream string, statusCode int, err error) {
	duration := time.Since(start)
	success := err == nil && statusCode < 500

	span.SetAttributes(
		attribute.Int("http.status_code", statusCode),
		attribute.Bool("success", success),
	)

	RecordUpstreamDuration(upstream, duration, statusCode, success)
	EndSpan(span, err)
}

func InjectTraceContext(ctx context.Context, req *http.Request) {
	propagator := propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	)

	carrier := propagation.HeaderCarrier(req.Header)
	propagator.Inject(ctx, carrier)
}

func ExtractTraceContext(ctx context.Context, headers http.Header) context.Context {
	propagator := propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	)

	carrier := propagation.HeaderCarrier(headers)
	return propagator.Extract(ctx, carrier)
}

func SetSpanStatus(ctx context.Context, statusCode int, err error) {
	span := trace.SpanFromContext(ctx)
	if !span.IsRecording() {
		return
	}

	span.SetAttributes(
		attribute.Int("http.status_code", statusCode),
	)

	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
	} else if statusCode >= 400 {
		span.SetStatus(codes.Error, http.StatusText(statusCode))
	} else {
		span.SetStatus(codes.Ok, "")
	}
}

func GetTraceID(ctx context.Context) string {
	spanCtx := trace.SpanContextFromContext(ctx)
	if spanCtx.IsValid() {
		return spanCtx.TraceID().String()
	}
	return ""
}

func getScheme(r *http.Request) string {
	if r.TLS != nil {
		return "https"
	}
	if scheme := r.Header.Get("X-Forwarded-Proto"); scheme != "" {
		return strings.ToLower(scheme)
	}
	return "http"
}
