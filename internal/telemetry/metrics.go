package telemetry

import (
	"strconv"
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

type MetricsCollector struct {
	httpRequestsTotal       *prometheus.CounterVec
	httpRequestDuration     *prometheus.HistogramVec
	middlewareDuration      *prometheus.HistogramVec
	upstreamDuration        *prometheus.HistogramVec
	rateLimitRejectedTotal  *prometheus.CounterVec
	circuitBreakerOpenTotal *prometheus.CounterVec
	activeRequests          *prometheus.GaugeVec
}

var defaultBuckets = []float64{
	0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60,
}

var collector *MetricsCollector

func NewMetricsCollector() (*MetricsCollector, error) {
	registry := GetPrometheusRegistry()

	httpRequestsTotal := prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "Total number of HTTP requests",
		},
		[]string{"method", "path", "route_id", "status_code", "status"},
	)

	httpRequestDuration := prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "HTTP request duration in seconds",
			Buckets: defaultBuckets,
		},
		[]string{"method", "path", "route_id", "status_code", "status"},
	)

	middlewareDuration := prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "middleware_duration_seconds",
			Help:    "Middleware execution duration in seconds",
			Buckets: defaultBuckets,
		},
		[]string{"middleware", "success"},
	)

	upstreamDuration := prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "upstream_duration_seconds",
			Help:    "Upstream call duration in seconds",
			Buckets: defaultBuckets,
		},
		[]string{"upstream", "status_code", "success", "status"},
	)

	rateLimitRejectedTotal := prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "rate_limit_rejected_total",
			Help: "Total number of rate limit rejections",
		},
		[]string{"reason", "dimension"},
	)

	circuitBreakerOpenTotal := prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "circuit_breaker_open_total",
			Help: "Total number of circuit breaker open events",
		},
		[]string{"route_id"},
	)

	activeRequests := prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "active_requests",
			Help: "Current number of active requests",
		},
		[]string{"method", "route_id"},
	)

	if registry != nil {
		registry.MustRegister(
			httpRequestsTotal,
			httpRequestDuration,
			middlewareDuration,
			upstreamDuration,
			rateLimitRejectedTotal,
			circuitBreakerOpenTotal,
			activeRequests,
		)
	} else {
		prometheus.MustRegister(
			httpRequestsTotal,
			httpRequestDuration,
			middlewareDuration,
			upstreamDuration,
			rateLimitRejectedTotal,
			circuitBreakerOpenTotal,
			activeRequests,
		)
	}

	collector = &MetricsCollector{
		httpRequestsTotal:       httpRequestsTotal,
		httpRequestDuration:     httpRequestDuration,
		middlewareDuration:      middlewareDuration,
		upstreamDuration:        upstreamDuration,
		rateLimitRejectedTotal:  rateLimitRejectedTotal,
		circuitBreakerOpenTotal: circuitBreakerOpenTotal,
		activeRequests:          activeRequests,
	}

	return collector, nil
}

func GetMetricsCollector() *MetricsCollector {
	return collector
}

func (c *MetricsCollector) RecordRequest(method, path, routeID string, statusCode int, duration time.Duration) {
	c.httpRequestsTotal.WithLabelValues(
		method,
		path,
		routeID,
		strconv.Itoa(statusCode),
		getStatusClass(statusCode),
	).Inc()

	c.httpRequestDuration.WithLabelValues(
		method,
		path,
		routeID,
		strconv.Itoa(statusCode),
		getStatusClass(statusCode),
	).Observe(duration.Seconds())
}

func (c *MetricsCollector) RecordMiddlewareDuration(name string, duration time.Duration, success bool) {
	c.middlewareDuration.WithLabelValues(
		name,
		strconv.FormatBool(success),
	).Observe(duration.Seconds())
}

func (c *MetricsCollector) RecordUpstreamDuration(upstream string, duration time.Duration, statusCode int, success bool) {
	c.upstreamDuration.WithLabelValues(
		upstream,
		strconv.Itoa(statusCode),
		strconv.FormatBool(success),
		getStatusClass(statusCode),
	).Observe(duration.Seconds())
}

func (c *MetricsCollector) RecordRateLimitRejected(reason string, dimension string) {
	c.rateLimitRejectedTotal.WithLabelValues(reason, dimension).Inc()
}

func (c *MetricsCollector) RecordCircuitBreakerOpen(routeID string) {
	c.circuitBreakerOpenTotal.WithLabelValues(routeID).Inc()
}

func (c *MetricsCollector) IncrementActiveRequests(method, routeID string) {
	c.activeRequests.WithLabelValues(method, routeID).Inc()
}

func (c *MetricsCollector) DecrementActiveRequests(method, routeID string) {
	c.activeRequests.WithLabelValues(method, routeID).Dec()
}

func RecordRequest(method, path, routeID string, statusCode int, duration time.Duration) {
	if collector != nil {
		collector.RecordRequest(method, path, routeID, statusCode, duration)
	}
}

func RecordMiddlewareDuration(name string, duration time.Duration, success bool) {
	if collector != nil {
		collector.RecordMiddlewareDuration(name, duration, success)
	}
}

func RecordUpstreamDuration(upstream string, duration time.Duration, statusCode int, success bool) {
	if collector != nil {
		collector.RecordUpstreamDuration(upstream, duration, statusCode, success)
	}
}

func RecordRateLimitRejected(reason string, dimension string) {
	if collector != nil {
		collector.RecordRateLimitRejected(reason, dimension)
	}
}

func RecordCircuitBreakerOpen(routeID string) {
	if collector != nil {
		collector.RecordCircuitBreakerOpen(routeID)
	}
}

func IncrementActiveRequests(method, routeID string) {
	if collector != nil {
		collector.IncrementActiveRequests(method, routeID)
	}
}

func DecrementActiveRequests(method, routeID string) {
	if collector != nil {
		collector.DecrementActiveRequests(method, routeID)
	}
}

func getStatusClass(code int) string {
	switch {
	case code >= 100 && code < 200:
		return "1xx"
	case code >= 200 && code < 300:
		return "2xx"
	case code >= 300 && code < 400:
		return "3xx"
	case code >= 400 && code < 500:
		return "4xx"
	case code >= 500 && code < 600:
		return "5xx"
	default:
		return "unknown"
	}
}
