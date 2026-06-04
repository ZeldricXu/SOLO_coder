package tracing

import (
	"context"
	"fmt"

	"github.com/distributed-task-scheduler/internal/config"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.12.0"
	"go.opentelemetry.io/otel/trace"
)

var (
	Tracer trace.Tracer
)

type TracerManager struct {
	provider *sdktrace.TracerProvider
	enabled  bool
}

func NewTracerManager(cfg config.TracingConfig) (*TracerManager, error) {
	if !cfg.Enabled {
		return &TracerManager{enabled: false}, nil
	}

	ctx := context.Background()

	exporter, err := otlptracehttp.New(ctx,
		otlptracehttp.WithEndpoint(cfg.Endpoint),
		otlptracehttp.WithInsecure(),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create OTLP exporter: %w", err)
	}

	resources, err := resource.New(ctx,
		resource.WithAttributes(
			semconv.ServiceNameKey.String(cfg.ServiceName),
		),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create resource: %w", err)
	}

	provider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(resources),
		sdktrace.WithSampler(sdktrace.AlwaysSample()),
	)

	otel.SetTracerProvider(provider)
	Tracer = provider.Tracer(cfg.ServiceName)

	return &TracerManager{
		provider: provider,
		enabled:  true,
	}, nil
}

func (tm *TracerManager) Shutdown(ctx context.Context) error {
	if !tm.enabled || tm.provider == nil {
		return nil
	}
	return tm.provider.Shutdown(ctx)
}

func StartTaskSpan(ctx context.Context, taskID, executionID, taskName string) (context.Context, trace.Span) {
	if Tracer == nil {
		return ctx, nilSpan{}
	}

	ctx, span := Tracer.Start(ctx, "task.execute",
		trace.WithAttributes(
			attribute.String("task.id", taskID),
			attribute.String("execution.id", executionID),
			attribute.String("task.name", taskName),
		),
	)

	return ctx, span
}

func StartDAGSpan(ctx context.Context, dagID, dagName string) (context.Context, trace.Span) {
	if Tracer == nil {
		return ctx, nilSpan{}
	}

	ctx, span := Tracer.Start(ctx, "dag.execute",
		trace.WithAttributes(
			attribute.String("dag.id", dagID),
			attribute.String("dag.name", dagName),
		),
	)

	return ctx, span
}

func AddEvent(span trace.Span, name string, attributes map[string]string) {
	if span == nil {
		return
	}

	attrs := make([]attribute.KeyValue, 0, len(attributes))
	for k, v := range attributes {
		attrs = append(attrs, attribute.String(k, v))
	}

	span.AddEvent(name, trace.WithAttributes(attrs...))
}

func SetError(span trace.Span, err error) {
	if span == nil || err == nil {
		return
	}

	span.RecordError(err)
}

type nilSpan struct{}

func (nilSpan) End(options ...trace.SpanEndOption)                   {}
func (nilSpan) AddEvent(name string, options ...trace.EventOption)   {}
func (nilSpan) IsRecording() bool                                    { return false }
func (nilSpan) RecordError(err error, options ...trace.EventOption)  {}
func (nilSpan) SpanContext() trace.SpanContext                       { return trace.SpanContext{} }
func (nilSpan) SetStatus(code codes.Code, description string)        {}
func (nilSpan) SetName(name string)                                  {}
func (nilSpan) SetAttributes(kv ...attribute.KeyValue)               {}
func (nilSpan) TracerProvider() trace.TracerProvider                 { return nil }
