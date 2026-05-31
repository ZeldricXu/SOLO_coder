package monitoring

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"session172/internal/logger"
	"session172/pkg/models"
	"session172/pkg/utils"
)

type Monitor struct {
	mu              sync.RWMutex
	registry        *prometheus.Registry
	counters        map[string]prometheus.Counter
	gauges          map[string]prometheus.Gauge
	histograms      map[string]prometheus.Histogram
	summaries       map[string]prometheus.Summary
	metrics         map[string]float64
	snapshots       []*models.MetricsSnapshot
	exposeEndpoint  string
}

type Metrics struct {
	Throughput    float64 `json:"throughput"`
	LatencyP99    float64 `json:"latency_p99"`
	LatencyP95    float64 `json:"latency_p95"`
	LatencyP50    float64 `json:"latency_p50"`
	ErrorRate     float64 `json:"error_rate"`
	ActiveRequests int    `json:"active_requests"`
	TotalRequests  int64  `json:"total_requests"`
}

var (
	monitorInstance *Monitor
	monitorOnce     sync.Once
)

func NewMonitor() *Monitor {
	monitorOnce.Do(func() {
		monitorInstance = &Monitor{
			registry:       prometheus.NewRegistry(),
			counters:       make(map[string]prometheus.Counter),
			gauges:         make(map[string]prometheus.Gauge),
			histograms:     make(map[string]prometheus.Histogram),
			summaries:      make(map[string]prometheus.Summary),
			metrics:        make(map[string]float64),
			snapshots:      make([]*models.MetricsSnapshot, 0),
			exposeEndpoint: "/metrics",
		}
		monitorInstance.registerDefaults()
	})
	return monitorInstance
}

func GetMonitor() *Monitor {
	if monitorInstance == nil {
		return NewMonitor()
	}
	return monitorInstance
}

func (m *Monitor) registerDefaults() {
	m.RegisterCounter("requests_total", "Total number of requests", []string{"method", "path", "status"})
	m.RegisterCounter("errors_total", "Total number of errors", []string{"type"})
	m.RegisterGauge("active_requests", "Number of active requests", nil)
	m.RegisterHistogram("request_duration_seconds", "Request duration in seconds", []string{"method", "path"},
		[]float64{0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5, 10})
	m.RegisterSummary("latency_summary", "Request latency summary", []string{"method", "path"},
		map[float64]float64{0.5: 0.05, 0.9: 0.01, 0.95: 0.005, 0.99: 0.001})
}

func (m *Monitor) RegisterCounter(name string, help string, labels []string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	counter := prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: name,
			Help: help,
		},
		labels,
	)
	m.registry.MustRegister(counter)
	m.counters[name] = counter
}

func (m *Monitor) RegisterGauge(name string, help string, labels []string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	gauge := prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: name,
			Help: help,
		},
		labels,
	)
	m.registry.MustRegister(gauge)
	m.gauges[name] = gauge
}

func (m *Monitor) RegisterHistogram(name string, help string, labels []string, buckets []float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	histogram := prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    name,
			Help:    help,
			Buckets: buckets,
		},
		labels,
	)
	m.registry.MustRegister(histogram)
	m.histograms[name] = histogram
}

func (m *Monitor) RegisterSummary(name string, help string, labels []string, objectives map[float64]float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	summary := prometheus.NewSummaryVec(
		prometheus.SummaryOpts{
			Name:       name,
			Help:       help,
			Objectives: objectives,
		},
		labels,
	)
	m.registry.MustRegister(summary)
	m.summaries[name] = summary
}

func (m *Monitor) IncrementCounter(name string, labels ...string) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if counter, ok := m.counters[name]; ok {
		if counterVec, ok := counter.(*prometheus.CounterVec); ok {
			counterVec.WithLabelValues(labels...).Inc()
		}
	}

	m.metrics[name]++
}

func (m *Monitor) SetGauge(name string, value float64, labels ...string) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if gauge, ok := m.gauges[name]; ok {
		if gaugeVec, ok := gauge.(*prometheus.GaugeVec); ok {
			gaugeVec.WithLabelValues(labels...).Set(value)
		}
	}

	m.metrics[name] = value
}

func (m *Monitor) ObserveHistogram(name string, value float64, labels ...string) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if histogram, ok := m.histograms[name]; ok {
		if histVec, ok := histogram.(*prometheus.HistogramVec); ok {
			histVec.WithLabelValues(labels...).Observe(value)
		}
	}
}

func (m *Monitor) ObserveSummary(name string, value float64, labels ...string) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if summary, ok := m.summaries[name]; ok {
		if summaryVec, ok := summary.(*prometheus.SummaryVec); ok {
			summaryVec.WithLabelValues(labels...).Observe(value)
		}
	}
}

func (m *Monitor) RecordRequest(method, path string, status int, duration time.Duration) {
	m.IncrementCounter("requests_total", method, path, fmt.Sprintf("%d", status))
	m.ObserveHistogram("request_duration_seconds", duration.Seconds(), method, path)
	m.ObserveSummary("latency_summary", duration.Seconds(), method, path)

	if status >= 500 {
		m.IncrementCounter("errors_total", "server_error")
	} else if status >= 400 {
		m.IncrementCounter("errors_total", "client_error")
	}
}

func (m *Monitor) SetActiveRequests(count int) {
	m.SetGauge("active_requests", float64(count))
}

func (m *Monitor) GetMetrics() *Metrics {
	m.mu.RLock()
	defer m.mu.RUnlock()

	metrics := &Metrics{
		Throughput:     m.metrics["requests_total"],
		ErrorRate:      m.metrics["errors_total"] / m.metrics["requests_total"],
		ActiveRequests: int(m.metrics["active_requests"]),
		TotalRequests:  int64(m.metrics["requests_total"]),
	}

	return metrics
}

func (m *Monitor) TakeSnapshot(dimensions map[string]string) *models.MetricsSnapshot {
	m.mu.Lock()
	defer m.mu.Unlock()

	metrics := m.GetMetrics()

	snapshot := &models.MetricsSnapshot{
		SnapshotID: utils.GenerateID("snap"),
		Timestamp:  time.Now(),
		Metrics: map[string]float64{
			"throughput":   metrics.Throughput,
			"latency_p99":  metrics.LatencyP99,
			"latency_p95":  metrics.LatencyP95,
			"latency_p50":  metrics.LatencyP50,
			"error_rate":   metrics.ErrorRate,
		},
		Dimensions: dimensions,
		CreatedAt:  time.Now(),
	}

	m.snapshots = append(m.snapshots, snapshot)
	if len(m.snapshots) > 1000 {
		m.snapshots = m.snapshots[1:]
	}

	return snapshot
}

func (m *Monitor) GetSnapshots(limit int) []*models.MetricsSnapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if limit <= 0 || limit > len(m.snapshots) {
		limit = len(m.snapshots)
	}

	snapshots := make([]*models.MetricsSnapshot, limit)
	for i := 0; i < limit; i++ {
		snapshots[i] = m.snapshots[len(m.snapshots)-limit+i]
	}
	return snapshots
}

func (m *Monitor) HTTPHandler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}

func (m *Monitor) ExposeMetrics(mux *http.ServeMux) {
	mux.Handle(m.exposeEndpoint, m.HTTPHandler())
	logger.Infof("Metrics exposed at %s", m.exposeEndpoint)
}

func (m *Monitor) StartCollection(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	logger.Infof("Metrics collection started with interval %v", interval)

	for {
		select {
		case <-ctx.Done():
			logger.Info("Metrics collection stopped")
			return
		case <-ticker.C:
			m.TakeSnapshot(map[string]string{
				"host":   "localhost",
				"region": "cn-east",
			})
		}
	}
}

func (m *Monitor) GetRegistry() *prometheus.Registry {
	return m.registry
}

func (m *Monitor) Reset() {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.metrics = make(map[string]float64)
	m.snapshots = make([]*models.MetricsSnapshot, 0)
}

func (m *Monitor) GetRawMetrics() map[string]float64 {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make(map[string]float64)
	for k, v := range m.metrics {
		result[k] = v
	}
	return result
}

func (m *Monitor) HealthCheck() map[string]interface{} {
	return map[string]interface{}{
		"status":    "healthy",
		"timestamp": time.Now(),
		"metrics_collected": len(m.snapshots),
		"uptime": time.Since(time.Now()).String(),
	}
}

func (m *Monitor) QueryMetrics(ctx context.Context, metricName string, startTime, endTime time.Time) ([]*models.MetricsSnapshot, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var results []*models.MetricsSnapshot
	for _, snap := range m.snapshots {
		if snap.Timestamp.After(startTime) && snap.Timestamp.Before(endTime) {
			if _, ok := snap.Metrics[metricName]; ok || metricName == "" {
				results = append(results, snap)
			}
		}
	}
	return results, nil
}

func (m *Monitor) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]interface{}{
		"metrics":   m.GetMetrics(),
		"snapshots": len(m.snapshots),
		"counters":  len(m.counters),
		"gauges":    len(m.gauges),
	})
}
