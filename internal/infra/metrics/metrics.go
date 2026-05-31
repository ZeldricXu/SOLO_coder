package metrics

import (
	"context"
	"net/http"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type MetricsConfig struct {
	Namespace   string
	Subsystem   string
	Labels      map[string]string
}

type Metrics struct {
	config          *MetricsConfig
	registry        *prometheus.Registry
	mutex           sync.RWMutex

	RequestCount    *prometheus.CounterVec
	RequestDuration *prometheus.HistogramVec
	RequestErrors   *prometheus.CounterVec
	RequestInFlight *prometheus.GaugeVec

	BlockchainLatency *prometheus.HistogramVec
	BlockchainErrors  *prometheus.CounterVec
	BlockchainHealth  *prometheus.GaugeVec

	ActiveConnections   *prometheus.Gauge
	ConnectionPoolSize  *prometheus.Gauge

	GasEstimateHits     *prometheus.Counter
	GasEstimateMisses   *prometheus.Counter
	GasEstimateLatency  *prometheus.Histogram

	TransactionSent     *prometheus.Counter
	TransactionFailed   *prometheus.Counter
	TransactionLatency  *prometheus.Histogram
}

type MetricsService interface {
	RecordRequest(method string, path string, duration time.Duration, success bool)
	RecordBlockchainCall(operation string, chainID string, duration time.Duration, success bool)
	RecordGasEstimate(hit bool, duration time.Duration)
	RecordTransaction(success bool, duration time.Duration)
	UpdateActiveConnections(count int)
	UpdateConnectionPoolSize(size int)
	UpdateBlockchainHealth(chainID string, healthy bool)
	GetHandler() http.Handler
	GetRegistry() *prometheus.Registry
}

func NewMetrics(config *MetricsConfig) *Metrics {
	if config == nil {
		config = &MetricsConfig{
			Namespace: "gas_estimator",
			Subsystem: "blockchain",
		}
	}

	registry := prometheus.NewRegistry()

	m := &Metrics{
		config:   config,
		registry: registry,

		RequestCount: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "http_requests_total",
				Help:      "Total number of HTTP requests",
			},
			[]string{"method", "path", "status"},
		),

		RequestDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "http_request_duration_seconds",
				Help:      "Duration of HTTP requests in seconds",
				Buckets:   prometheus.DefBuckets,
			},
			[]string{"method", "path"},
		),

		RequestErrors: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "http_request_errors_total",
				Help:      "Total number of HTTP request errors",
			},
			[]string{"method", "path", "error_type"},
		),

		RequestInFlight: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "http_requests_in_flight",
				Help:      "Current number of HTTP requests being processed",
			},
			[]string{"method", "path"},
		),

		BlockchainLatency: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "blockchain_call_duration_seconds",
				Help:      "Duration of blockchain API calls in seconds",
				Buckets:   []float64{0.01, 0.05, 0.1, 0.5, 1, 5, 10, 30, 60},
			},
			[]string{"operation", "chain_id"},
		),

		BlockchainErrors: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "blockchain_call_errors_total",
				Help:      "Total number of blockchain API call errors",
			},
			[]string{"operation", "chain_id", "error_type"},
		),

		BlockchainHealth: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "blockchain_health",
				Help:      "Health status of blockchain connections",
			},
			[]string{"chain_id"},
		),

		ActiveConnections: prometheus.NewGauge(
			prometheus.GaugeOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "active_connections",
				Help:      "Current number of active blockchain connections",
			},
		),

		ConnectionPoolSize: prometheus.NewGauge(
			prometheus.GaugeOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "connection_pool_size",
				Help:      "Size of blockchain connection pool",
			},
		),

		GasEstimateHits: prometheus.NewCounter(
			prometheus.CounterOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "gas_estimate_hits_total",
				Help:      "Total number of gas estimate cache hits",
			},
		),

		GasEstimateMisses: prometheus.NewCounter(
			prometheus.CounterOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "gas_estimate_misses_total",
				Help:      "Total number of gas estimate cache misses",
			},
		),

		GasEstimateLatency: prometheus.NewHistogram(
			prometheus.HistogramOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "gas_estimate_duration_seconds",
				Help:      "Duration of gas estimate operations in seconds",
				Buckets:   prometheus.DefBuckets,
			},
		),

		TransactionSent: prometheus.NewCounter(
			prometheus.CounterOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "transactions_sent_total",
				Help:      "Total number of transactions sent",
			},
		),

		TransactionFailed: prometheus.NewCounter(
			prometheus.CounterOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "transactions_failed_total",
				Help:      "Total number of failed transactions",
			},
		),

		TransactionLatency: prometheus.NewHistogram(
			prometheus.HistogramOpts{
				Namespace: config.Namespace,
				Subsystem: config.Subsystem,
				Name:      "transaction_duration_seconds",
				Help:      "Duration of transaction operations in seconds",
				Buckets:   []float64{0.1, 0.5, 1, 5, 10, 30, 60, 120},
			},
		),
	}

	registry.MustRegister(
		m.RequestCount,
		m.RequestDuration,
		m.RequestErrors,
		m.RequestInFlight,
		m.BlockchainLatency,
		m.BlockchainErrors,
		m.BlockchainHealth,
		m.ActiveConnections,
		m.ConnectionPoolSize,
		m.GasEstimateHits,
		m.GasEstimateMisses,
		m.GasEstimateLatency,
		m.TransactionSent,
		m.TransactionFailed,
		m.TransactionLatency,
	)

	return m
}

func (m *Metrics) RecordRequest(method string, path string, duration time.Duration, success bool) {
	status := "success"
	if !success {
		status = "error"
	}

	m.RequestCount.WithLabelValues(method, path, status).Inc()
	m.RequestDuration.WithLabelValues(method, path).Observe(duration.Seconds())
}

func (m *Metrics) RecordBlockchainCall(operation string, chainID string, duration time.Duration, success bool) {
	m.BlockchainLatency.WithLabelValues(operation, chainID).Observe(duration.Seconds())

	if !success {
		m.BlockchainErrors.WithLabelValues(operation, chainID, "unknown").Inc()
	}
}

func (m *Metrics) RecordGasEstimate(hit bool, duration time.Duration) {
	if hit {
		m.GasEstimateHits.Inc()
	} else {
		m.GasEstimateMisses.Inc()
	}
	m.GasEstimateLatency.Observe(duration.Seconds())
}

func (m *Metrics) RecordTransaction(success bool, duration time.Duration) {
	if success {
		m.TransactionSent.Inc()
	} else {
		m.TransactionFailed.Inc()
	}
	m.TransactionLatency.Observe(duration.Seconds())
}

func (m *Metrics) UpdateActiveConnections(count int) {
	m.ActiveConnections.Set(float64(count))
}

func (m *Metrics) UpdateConnectionPoolSize(size int) {
	m.ConnectionPoolSize.Set(float64(size))
}

func (m *Metrics) UpdateBlockchainHealth(chainID string, healthy bool) {
	value := 0.0
	if healthy {
		value = 1.0
	}
	m.BlockchainHealth.WithLabelValues(chainID).Set(value)
}

func (m *Metrics) GetHandler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}

func (m *Metrics) GetRegistry() *prometheus.Registry {
	return m.registry
}

type TimedOperation struct {
	start    time.Time
	metrics  *Metrics
	opName   string
	chainID  string
}

func (m *Metrics) StartTimedOperation(opName string, chainID string) *TimedOperation {
	return &TimedOperation{
		start:   time.Now(),
		metrics: m,
		opName:  opName,
		chainID: chainID,
	}
}

func (t *TimedOperation) End(success bool) {
	duration := time.Since(t.start)
	t.metrics.RecordBlockchainCall(t.opName, t.chainID, duration, success)
}

func (t *TimedOperation) EndWithError(err error) bool {
	success := err == nil
	t.End(success)
	return success
}

func (m *Metrics) MeasureRequest(method string, path string, handler func() error) error {
	start := time.Now()
	err := handler()
	duration := time.Since(start)
	
	success := err == nil
	m.RecordRequest(method, path, duration, success)
	
	if !success {
		m.RequestErrors.WithLabelValues(method, path, "error").Inc()
	}
	
	return err
}

func (m *Metrics) MeasureBlockchainOperation(operation string, chainID string, handler func() error) error {
	op := m.StartTimedOperation(operation, chainID)
	err := handler()
	op.EndWithError(err)
	return err
}

func (m *Metrics) MeasureGasEstimate(handler func() (bool, error)) error {
	start := time.Now()
	hit, err := handler()
	duration := time.Since(start)
	
	if err == nil {
		m.RecordGasEstimate(hit, duration)
	}
	
	return err
}

func (m *Metrics) MeasureTransaction(handler func() error) error {
	start := time.Now()
	err := handler()
	duration := time.Since(start)
	
	success := err == nil
	m.RecordTransaction(success, duration)
	
	return err
}

type contextKey struct{}

var metricsContextKey = contextKey{}

func WithMetrics(ctx context.Context, metrics MetricsService) context.Context {
	return context.WithValue(ctx, metricsContextKey, metrics)
}

func FromContext(ctx context.Context) (MetricsService, bool) {
	metrics, ok := ctx.Value(metricsContextKey).(MetricsService)
	return metrics, ok
}
