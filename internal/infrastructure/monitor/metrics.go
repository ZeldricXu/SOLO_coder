package monitor

import (
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type Metrics struct {
	RequestDuration  *prometheus.HistogramVec
	RequestTotal     *prometheus.CounterVec
	RequestInFlight  prometheus.Gauge
	CacheHitTotal    *prometheus.CounterVec
	CacheMissTotal   *prometheus.CounterVec
	TaskDuration     *prometheus.HistogramVec
	TaskTotal        *prometheus.CounterVec
	ErrorTotal       *prometheus.CounterVec
	EnvironmentUsage *prometheus.GaugeVec
}

func NewMetrics() *Metrics {
	m := &Metrics{
		RequestDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "http_request_duration_seconds",
				Help:    "HTTP request duration in seconds",
				Buckets: prometheus.DefBuckets,
			},
			[]string{"method", "path", "status"},
		),
		RequestTotal: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "http_requests_total",
				Help: "Total number of HTTP requests",
			},
			[]string{"method", "path", "status"},
		),
		RequestInFlight: prometheus.NewGauge(
			prometheus.GaugeOpts{
				Name: "http_requests_in_flight",
				Help: "Number of in-flight HTTP requests",
			},
		),
		CacheHitTotal: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "cache_hits_total",
				Help: "Total number of cache hits",
			},
			[]string{"cache_type", "module"},
		),
		CacheMissTotal: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "cache_misses_total",
				Help: "Total number of cache misses",
			},
			[]string{"cache_type", "module"},
		),
		TaskDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "task_duration_seconds",
				Help:    "Task execution duration in seconds",
				Buckets: []float64{0.1, 0.5, 1, 5, 10, 30, 60},
			},
			[]string{"module", "operation", "status"},
		),
		TaskTotal: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "tasks_total",
				Help: "Total number of tasks",
			},
			[]string{"module", "operation", "status"},
		),
		ErrorTotal: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "errors_total",
				Help: "Total number of errors",
			},
			[]string{"module", "error_type"},
		),
		EnvironmentUsage: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "environment_usage",
				Help: "Environment resource usage",
			},
			[]string{"env_id", "resource_type"},
		),
	}

	prometheus.MustRegister(
		m.RequestDuration,
		m.RequestTotal,
		m.RequestInFlight,
		m.CacheHitTotal,
		m.CacheMissTotal,
		m.TaskDuration,
		m.TaskTotal,
		m.ErrorTotal,
		m.EnvironmentUsage,
	)

	return m
}

func (m *Metrics) ObserveRequestDuration(method, path, status string, duration time.Duration) {
	m.RequestDuration.WithLabelValues(method, path, status).Observe(duration.Seconds())
	m.RequestTotal.WithLabelValues(method, path, status).Inc()
}

func (m *Metrics) IncInFlight() {
	m.RequestInFlight.Inc()
}

func (m *Metrics) DecInFlight() {
	m.RequestInFlight.Dec()
}

func (m *Metrics) ObserveCacheHit(cacheType, module string) {
	m.CacheHitTotal.WithLabelValues(cacheType, module).Inc()
}

func (m *Metrics) ObserveCacheMiss(cacheType, module string) {
	m.CacheMissTotal.WithLabelValues(cacheType, module).Inc()
}

func (m *Metrics) ObserveTaskDuration(module, operation, status string, duration time.Duration) {
	m.TaskDuration.WithLabelValues(module, operation, status).Observe(duration.Seconds())
	m.TaskTotal.WithLabelValues(module, operation, status).Inc()
}

func (m *Metrics) ObserveError(module, errorType string) {
	m.ErrorTotal.WithLabelValues(module, errorType).Inc()
}

func (m *Metrics) SetEnvironmentUsage(envID, resourceType string, value float64) {
	m.EnvironmentUsage.WithLabelValues(envID, resourceType).Set(value)
}

func MetricsHandler() promhttp.Handler {
	return promhttp.Handler()
}
