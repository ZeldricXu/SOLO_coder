package monitoring

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/edgeplatform/session306/internal/data"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/pkg/errors"
	"github.com/edgeplatform/session306/pkg/events"
	"github.com/edgeplatform/session306/pkg/utils"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"go.uber.org/zap"
)

type MetricsCollector interface {
	Collect() map[string]float64
}

type MonitoringManager struct {
	da               *data.DataAccess
	eventBus         events.EventBus
	logger           *zap.Logger
	metricRepo       data.MetricRepository
	registry         *prometheus.Registry
	counters         map[string]*prometheus.CounterVec
	gauges           map[string]*prometheus.GaugeVec
	histograms       map[string]*prometheus.HistogramVec
	metricLabels     map[string][]string
	mu               sync.RWMutex
	snapshotInterval time.Duration
	collectors       map[string]MetricsCollector
}

func NewMonitoringManager(da *data.DataAccess, eb events.EventBus, metricRepo data.MetricRepository, log *zap.Logger) *MonitoringManager {
	registry := prometheus.NewRegistry()

	return &MonitoringManager{
		da:               da,
		eventBus:         eb,
		logger:           log,
		metricRepo:       metricRepo,
		registry:         registry,
		counters:         make(map[string]*prometheus.CounterVec),
		gauges:           make(map[string]*prometheus.GaugeVec),
		histograms:       make(map[string]*prometheus.HistogramVec),
		metricLabels:     make(map[string][]string),
		snapshotInterval: 30 * time.Second,
		collectors:       make(map[string]MetricsCollector),
	}
}

func (m *MonitoringManager) Start(ctx context.Context) error {
	m.registerDefaultMetrics()
	go m.snapshotCollector(ctx)
	m.logger.Info("Monitoring manager started")
	return nil
}

func (m *MonitoringManager) registerDefaultMetrics() {
	m.RegisterCounter("requests_total", "Total number of requests", []string{"method", "endpoint", "status"})
	m.RegisterCounter("errors_total", "Total number of errors", []string{"module", "error_type"})
	m.RegisterCounter("events_total", "Total number of events", []string{"event_type", "source"})

	m.RegisterGauge("active_connections", "Number of active connections", []string{"module"})
	m.RegisterGauge("queue_size", "Current queue size", []string{"queue_name"})
	m.RegisterGauge("cpu_usage_percent", "CPU usage percentage", []string{"host"})
	m.RegisterGauge("memory_usage_bytes", "Memory usage in bytes", []string{"host"})
	m.RegisterGauge("disk_usage_bytes", "Disk usage in bytes", []string{"host"})

	m.RegisterHistogram("request_duration_seconds", "Request duration in seconds",
		[]string{"method", "endpoint"},
		[]float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10},
	)
	m.RegisterHistogram("processing_duration_seconds", "Processing duration in seconds",
		[]string{"module", "operation"},
		[]float64{0.01, 0.05, 0.1, 0.5, 1, 5, 10, 30, 60},
	)

	m.logger.Info("Default metrics registered")
}

func (m *MonitoringManager) RegisterCounter(name, help string, labels []string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.counters[name]; exists {
		return
	}

	counter := promauto.With(m.registry).NewCounterVec(
		prometheus.CounterOpts{
			Name: name,
			Help: help,
		},
		labels,
	)
	m.counters[name] = counter
	m.metricLabels[name] = labels
}

func (m *MonitoringManager) RegisterGauge(name, help string, labels []string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.gauges[name]; exists {
		return
	}

	gauge := promauto.With(m.registry).NewGaugeVec(
		prometheus.GaugeOpts{
			Name: name,
			Help: help,
		},
		labels,
	)
	m.gauges[name] = gauge
	m.metricLabels[name] = labels
}

func (m *MonitoringManager) RegisterHistogram(name, help string, labels []string, buckets []float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.histograms[name]; exists {
		return
	}

	histogram := promauto.With(m.registry).NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    name,
			Help:    help,
			Buckets: buckets,
		},
		labels,
	)
	m.histograms[name] = histogram
	m.metricLabels[name] = labels
}

func (m *MonitoringManager) IncrementCounter(name string, labels map[string]string, value float64) {
	m.mu.RLock()
	counter, exists := m.counters[name]
	m.mu.RUnlock()

	if !exists {
		m.logger.Warn("Counter not found", zap.String("name", name))
		return
	}

	labelValues := m.getLabelValues(counter, labels)
	counter.WithLabelValues(labelValues...).Add(value)
}

func (m *MonitoringManager) SetGauge(name string, labels map[string]string, value float64) {
	m.mu.RLock()
	gauge, exists := m.gauges[name]
	m.mu.RUnlock()

	if !exists {
		m.logger.Warn("Gauge not found", zap.String("name", name))
		return
	}

	labelValues := m.getLabelValues(gauge, labels)
	gauge.WithLabelValues(labelValues...).Set(value)
}

func (m *MonitoringManager) ObserveHistogram(name string, labels map[string]string, value float64) {
	m.mu.RLock()
	histogram, exists := m.histograms[name]
	m.mu.RUnlock()

	if !exists {
		m.logger.Warn("Histogram not found", zap.String("name", name))
		return
	}

	labelValues := m.getLabelValues(histogram, labels)
	histogram.WithLabelValues(labelValues...).Observe(value)
}

func (m *MonitoringManager) getLabelValues(metric interface{}, labels map[string]string) []string {
	var name string
	switch metric.(type) {
	case *prometheus.CounterVec:
		for n, c := range m.counters {
			if c == metric {
				name = n
				break
			}
		}
	case *prometheus.GaugeVec:
		for n, g := range m.gauges {
			if g == metric {
				name = n
				break
			}
		}
	case *prometheus.HistogramVec:
		for n, h := range m.histograms {
			if h == metric {
				name = n
				break
			}
		}
	}

	labelNames, exists := m.metricLabels[name]
	if !exists {
		return nil
	}

	values := make([]string, len(labelNames))
	for i, labelName := range labelNames {
		values[i] = labels[labelName]
	}
	return values
}

func (m *MonitoringManager) RecordRequest(method, endpoint string, status int, duration time.Duration) {
	m.IncrementCounter("requests_total", map[string]string{
		"method":   method,
		"endpoint": endpoint,
		"status":   string(rune(status)),
	}, 1)
	m.ObserveHistogram("request_duration_seconds", map[string]string{
		"method":   method,
		"endpoint": endpoint,
	}, duration.Seconds())

	if status >= 400 {
		m.IncrementCounter("errors_total", map[string]string{
			"module":     "http",
			"error_type": string(rune(status)),
		}, 1)
	}
}

func (m *MonitoringManager) RecordEvent(eventType events.EventType, source string) {
	m.IncrementCounter("events_total", map[string]string{
		"event_type": string(eventType),
		"source":     source,
	}, 1)
}

func (m *MonitoringManager) RecordProcessing(module, operation string, duration time.Duration, err error) {
	m.ObserveHistogram("processing_duration_seconds", map[string]string{
		"module":    module,
		"operation": operation,
	}, duration.Seconds())

	if err != nil {
		m.IncrementCounter("errors_total", map[string]string{
			"module":     module,
			"error_type": "processing_error",
		}, 1)
	}
}

func (m *MonitoringManager) RegisterCollector(name string, collector MetricsCollector) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.collectors[name] = collector
	m.logger.Info("Metrics collector registered", zap.String("name", name))
}

func (m *MonitoringManager) snapshotCollector(ctx context.Context) {
	ticker := time.NewTicker(m.snapshotInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			m.takeSnapshot(ctx)
		}
	}
}

func (m *MonitoringManager) takeSnapshot(ctx context.Context) {
	metrics := make(map[string]interface{})

	m.mu.RLock()
	for name, collector := range m.collectors {
		for k, v := range collector.Collect() {
			metrics[name+"_"+k] = v
		}
	}
	m.mu.RUnlock()

	m.mu.RLock()
	for name, gauge := range m.gauges {
		metrics[name] = getGaugeValue(gauge)
	}
	m.mu.RUnlock()

	dimensions := map[string]string{
		"host":   "localhost",
		"region": "cn-east",
	}

	snapshot := &model.MetricSnapshot{
		Timestamp:  utils.NowUTC(),
		Metrics:    metrics,
		Dimensions: dimensions,
	}

	if err := m.metricRepo.Create(ctx, snapshot); err != nil {
		m.logger.Warn("Failed to create metric snapshot", zap.Error(err))
	} else {
		m.logger.Debug("Metric snapshot created",
			zap.String("snapshot_id", snapshot.SnapshotID),
			zap.Int("metrics_count", len(metrics)),
		)
	}
}

func getGaugeValue(gauge *prometheus.GaugeVec) float64 {
	return 0
}

func (m *MonitoringManager) GetMetrics(ctx context.Context) (map[string]interface{}, error) {
	gatherer := prometheus.Gatherer(m.registry)
	metricFamilies, err := gatherer.Gather()
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to gather metrics")
	}

	result := make(map[string]interface{})
	for _, mf := range metricFamilies {
		metrics := make([]map[string]interface{}, 0)
		for _, m := range mf.GetMetric() {
			metric := make(map[string]interface{})
			labels := make(map[string]string)
			for _, l := range m.GetLabel() {
				labels[l.GetName()] = l.GetValue()
			}
			metric["labels"] = labels

			if m.GetCounter() != nil {
				metric["value"] = m.GetCounter().GetValue()
			} else if m.GetGauge() != nil {
				metric["value"] = m.GetGauge().GetValue()
			} else if m.GetHistogram() != nil {
				hist := m.GetHistogram()
				metric["sample_count"] = hist.GetSampleCount()
				metric["sample_sum"] = hist.GetSampleSum()
				buckets := make(map[string]uint64)
				for _, b := range hist.GetBucket() {
					buckets[fmt.Sprintf("%f", b.GetUpperBound())] = b.GetCumulativeCount()
				}
				metric["buckets"] = buckets
			}
			metrics = append(metrics, metric)
		}
		result[mf.GetName()] = metrics
	}

	return result, nil
}

func (m *MonitoringManager) QuerySnapshots(ctx context.Context, startTime, endTime time.Time, dimensions map[string]string, offset, limit int) ([]model.MetricSnapshot, int64, error) {
	return m.metricRepo.Query(ctx, startTime, endTime, dimensions, offset, limit)
}

func (m *MonitoringManager) GetRegistry() *prometheus.Registry {
	return m.registry
}

func (m *MonitoringManager) GetHealth(ctx context.Context) (map[string]interface{}, error) {
	dbErr := m.da.HealthCheck(ctx)

	status := "healthy"
	components := make(map[string]interface{})

	components["database"] = map[string]interface{}{
		"status": func() string {
			if dbErr == nil {
				return "healthy"
			}
			return "unhealthy"
		}(),
		"error": func() string {
			if dbErr != nil {
				return dbErr.Error()
			}
			return ""
		}(),
	}

	if dbErr != nil {
		status = "unhealthy"
	}

	return map[string]interface{}{
		"status":     status,
		"timestamp":  utils.NowUTC(),
		"components": components,
	}, nil
}

func (m *MonitoringManager) ExportJSON(ctx context.Context) (string, error) {
	metrics, err := m.GetMetrics(ctx)
	if err != nil {
		return "", err
	}

	b, err := json.MarshalIndent(metrics, "", "  ")
	if err != nil {
		return "", err
	}
	return string(b), nil
}


