package telemetry

import (
	"context"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	semconv "go.opentelemetry.io/otel/semconv/v1.21.0"
)

var (
	meter          metric.Meter
	meterProvider  *sdkmetric.MeterProvider
	promRegistry   *prometheus.Registry
)

func InitMeter(serviceName string) (func(), error) {
	res, err := resource.New(context.Background(),
		resource.WithAttributes(
			semconv.ServiceName(serviceName),
			semconv.ServiceVersion("1.0.0"),
		),
		resource.WithFromEnv(),
		resource.WithTelemetrySDK(),
	)
	if err != nil {
		return nil, err
	}

	promRegistry = prometheus.NewRegistry()

	meterProvider = sdkmetric.NewMeterProvider(
		sdkmetric.WithResource(res),
	)

	otel.SetMeterProvider(meterProvider)

	meter = otel.Meter(InstrumentationName,
		metric.WithInstrumentationVersion("1.0.0"),
	)

	shutdown := func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if meterProvider != nil {
			_ = meterProvider.Shutdown(ctx)
		}
	}

	return shutdown, nil
}

func GetMeter() metric.Meter {
	return meter
}

func GetMeterProvider() *sdkmetric.MeterProvider {
	return meterProvider
}

func GetPrometheusRegistry() *prometheus.Registry {
	return promRegistry
}

func StringAttribute(key, value string) attribute.KeyValue {
	return attribute.String(key, value)
}

func IntAttribute(key string, value int) attribute.KeyValue {
	return attribute.Int(key, value)
}

func BoolAttribute(key string, value bool) attribute.KeyValue {
	return attribute.Bool(key, value)
}

func Float64Attribute(key string, value float64) attribute.KeyValue {
	return attribute.Float64(key, value)
}
