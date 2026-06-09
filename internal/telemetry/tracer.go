package telemetry

import (
	"context"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.21.0"
	"go.opentelemetry.io/otel/trace"
)

var (
	tracer           trace.Tracer
	tracerProvider   *sdktrace.TracerProvider
	defaultAttrs     []attribute.KeyValue
)

const (
	InstrumentationName = "DF1-56/api-gateway"
)

func InitTracer(serviceName, otlpEndpoint string) (func(), error) {
	defaultAttrs = []attribute.KeyValue{
		semconv.ServiceName(serviceName),
		semconv.ServiceVersion("1.0.0"),
	}

	res, err := resource.New(context.Background(),
		resource.WithAttributes(defaultAttrs...),
		resource.WithFromEnv(),
		resource.WithTelemetrySDK(),
	)
	if err != nil {
		return nil, err
	}

	var exporter sdktrace.SpanExporter
	if otlpEndpoint != "" {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()

		exporter, err = otlptracegrpc.New(ctx,
			otlptracegrpc.WithEndpoint(otlpEndpoint),
			otlptracegrpc.WithInsecure(),
		)
		if err != nil {
			return nil, err
		}
	} else {
		exporter = &noOpExporter{}
	}

	tracerProvider = sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
		sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.TraceIDRatioBased(1.0))),
	)

	otel.SetTracerProvider(tracerProvider)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))

	tracer = otel.Tracer(InstrumentationName)

	shutdown := func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if tracerProvider != nil {
			_ = tracerProvider.Shutdown(ctx)
		}
	}

	return shutdown, nil
}

func StartSpan(ctx context.Context, name string, opts ...trace.SpanStartOption) (context.Context, trace.Span) {
	return tracer.Start(ctx, name, opts...)
}

func EndSpan(span trace.Span, err error) {
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
	} else {
		span.SetStatus(codes.Ok, "")
	}
	span.End()
}

func GetTracer() trace.Tracer {
	return tracer
}

func GetTracerProvider() *sdktrace.TracerProvider {
	return tracerProvider
}

type noOpExporter struct{}

func (e *noOpExporter) ExportSpans(ctx context.Context, spans []sdktrace.ReadOnlySpan) error {
	return nil
}

func (e *noOpExporter) Shutdown(ctx context.Context) error {
	return nil
}

func SpanAttributesFromGatewayContext(attrs map[string]interface{}) []attribute.KeyValue {
	spanAttrs := make([]attribute.KeyValue, 0, len(attrs))
	for k, v := range attrs {
		switch val := v.(type) {
		case string:
			spanAttrs = append(spanAttrs, attribute.String(k, val))
		case int:
			spanAttrs = append(spanAttrs, attribute.Int(k, val))
		case int64:
			spanAttrs = append(spanAttrs, attribute.Int64(k, val))
		case bool:
			spanAttrs = append(spanAttrs, attribute.Bool(k, val))
		case float64:
			spanAttrs = append(spanAttrs, attribute.Float64(k, val))
		}
	}
	return spanAttrs
}
