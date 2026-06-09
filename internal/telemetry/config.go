package telemetry

import (
	"net/http"
	"time"
)

type Config struct {
	ServiceName     string
	ServiceVersion  string
	OTLPEndpoint    string
	MetricsAddr     string
	PrometheusPort  int
	TraceRatio      float64
	TraceSampleRate float64
	Enabled         bool
	MetricsEnabled  bool
	TracingEnabled  bool
	OTLPInsecure    bool
}

type Telemetry struct {
	config        Config
	shutdownTracer func()
	shutdownMeter  func()
	collector     *MetricsCollector
	exporter      *MetricsExporter
}

func NewTelemetry(cfg Config) (*Telemetry, error) {
	if !cfg.Enabled {
		return &Telemetry{config: cfg}, nil
	}

	if cfg.TraceRatio <= 0 {
		cfg.TraceRatio = 1.0
	}

	t := &Telemetry{
		config: cfg,
	}

	var err error
	t.shutdownTracer, err = InitTracer(cfg.ServiceName, cfg.OTLPEndpoint)
	if err != nil {
		return nil, err
	}

	t.shutdownMeter, err = InitMeter(cfg.ServiceName)
	if err != nil {
		return nil, err
	}

	t.collector, err = NewMetricsCollector()
	if err != nil {
		return nil, err
	}

	t.exporter = NewMetricsExporter()

	return t, nil
}

func (t *Telemetry) Shutdown() {
	if t.shutdownTracer != nil {
		t.shutdownTracer()
	}
	if t.shutdownMeter != nil {
		t.shutdownMeter()
	}
}

func (t *Telemetry) Collector() *MetricsCollector {
	return t.collector
}

func (t *Telemetry) Exporter() *MetricsExporter {
	return t.exporter
}

func (t *Telemetry) IsEnabled() bool {
	return t.config.Enabled
}

func (t *Telemetry) RecordRequest(method, path, routeID string, statusCode int, duration time.Duration) {
	if t.collector != nil {
		t.collector.RecordRequest(method, path, routeID, statusCode, duration)
	}
}

func (t *Telemetry) RecordMiddlewareDuration(name string, duration time.Duration, success bool) {
	if t.collector != nil {
		t.collector.RecordMiddlewareDuration(name, duration, success)
	}
}

func (t *Telemetry) RecordUpstreamDuration(upstream string, duration time.Duration, statusCode int, success bool) {
	if t.collector != nil {
		t.collector.RecordUpstreamDuration(upstream, duration, statusCode, success)
	}
}

func (t *Telemetry) RecordRateLimitRejected(reason string, dimension string) {
	if t.collector != nil {
		t.collector.RecordRateLimitRejected(reason, dimension)
	}
}

func (t *Telemetry) RecordCircuitBreakerOpen(routeID string) {
	if t.collector != nil {
		t.collector.RecordCircuitBreakerOpen(routeID)
	}
}

func (t *Telemetry) IncrementActiveRequests(method, routeID string) {
	if t.collector != nil {
		t.collector.IncrementActiveRequests(method, routeID)
	}
}

func (t *Telemetry) DecrementActiveRequests(method, routeID string) {
	if t.collector != nil {
		t.collector.DecrementActiveRequests(method, routeID)
	}
}

func (t *Telemetry) RecordAuthFailure(reason, detail string) {
	if t.collector != nil {
		t.collector.httpRequestsTotal.WithLabelValues(
			"", "", "", "401", "4xx",
		).Inc()
	}
}

func (t *Telemetry) MetricsHandler() http.Handler {
	if t.exporter != nil {
		return t.exporter
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "metrics not available", http.StatusServiceUnavailable)
	})
}
