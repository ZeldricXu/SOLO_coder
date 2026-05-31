package monitoring

import (
	"sync"
	"time"

	"github.com/enterprise/config-platform/pkg/types"
	"github.com/enterprise/config-platform/pkg/utils"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

type MetricType string

const (
	MetricTypeCounter   MetricType = "counter"
	MetricTypeGauge     MetricType = "gauge"
	MetricTypeHistogram MetricType = "histogram"
)

type Metric struct {
	Name        string            `json:"name"`
	Type        MetricType        `json:"type"`
	Description string            `json:"description"`
	Labels      map[string]string `json:"labels"`
	Value       float64           `json:"value"`
	Timestamp   time.Time         `json:"timestamp"`
}

type SnapshotQuery struct {
	StartTime  time.Time
	EndTime    time.Time
	MetricName string
	Dimensions map[string]string
}

type Manager struct {
	counters   map[string]*prometheus.CounterVec
	gauges     map[string]*prometheus.GaugeVec
	histograms map[string]*prometheus.HistogramVec
	snapshots  []*types.Snapshot
	mu         sync.RWMutex
}

var (
	instance *Manager
	once     sync.Once
)

func GetManager() *Manager {
	once.Do(func() {
		instance = &Manager{
			counters:   make(map[string]*prometheus.CounterVec),
			gauges:     make(map[string]*prometheus.GaugeVec),
			histograms: make(map[string]*prometheus.HistogramVec),
			snapshots:  make([]*types.Snapshot, 0),
		}
		instance.registerDefaultMetrics()
		go instance.startSnapshotCollector()
	})
	return instance
}

func (m *Manager) registerDefaultMetrics() {
	m.RegisterCounter("http_requests_total", "Total HTTP requests", []string{"method", "path", "status"})
	m.RegisterGauge("system_memory_usage_bytes", "Memory usage in bytes", []string{"host"})
	m.RegisterGauge("system_cpu_usage_percent", "CPU usage percentage", []string{"host"})
	m.RegisterHistogram("http_request_duration_seconds", "HTTP request duration", []string{"method", "path"},
		[]float64{0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5, 10})
	m.RegisterCounter("gateway_rate_limited_total", "Total rate limited requests", []string{"client_ip"})
	m.RegisterCounter("fault_injections_total", "Total fault injections", []string{"fault_type", "scope"})
	m.RegisterCounter("cert_rotations_total", "Total certificate rotations", []string{"common_name"})
}

func (m *Manager) RegisterCounter(name, description string, labels []string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.counters[name]; !exists {
		m.counters[name] = promauto.NewCounterVec(prometheus.CounterOpts{
			Name: name,
			Help: description,
		}, labels)
	}
}

func (m *Manager) RegisterGauge(name, description string, labels []string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.gauges[name]; !exists {
		m.gauges[name] = promauto.NewGaugeVec(prometheus.GaugeOpts{
			Name: name,
			Help: description,
		}, labels)
	}
}

func (m *Manager) RegisterHistogram(name, description string, labels []string, buckets []float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.histograms[name]; !exists {
		m.histograms[name] = promauto.NewHistogramVec(prometheus.HistogramOpts{
			Name:    name,
			Help:    description,
			Buckets: buckets,
		}, labels)
	}
}

func (m *Manager) IncrementCounter(name string, labels map[string]string) {
	m.mu.RLock()
	counter, exists := m.counters[name]
	m.mu.RUnlock()

	if exists {
		labelValues := makeLabels(labels)
		counter.WithLabelValues(labelValues...).Inc()
	}
}

func (m *Manager) AddCounter(name string, value float64, labels map[string]string) {
	m.mu.RLock()
	counter, exists := m.counters[name]
	m.mu.RUnlock()

	if exists {
		labelValues := makeLabels(labels)
		counter.WithLabelValues(labelValues...).Add(value)
	}
}

func (m *Manager) SetGauge(name string, value float64, labels map[string]string) {
	m.mu.RLock()
	gauge, exists := m.gauges[name]
	m.mu.RUnlock()

	if exists {
		labelValues := makeLabels(labels)
		gauge.WithLabelValues(labelValues...).Set(value)
	}
}

func (m *Manager) ObserveHistogram(name string, value float64, labels map[string]string) {
	m.mu.RLock()
	histogram, exists := m.histograms[name]
	m.mu.RUnlock()

	if exists {
		labelValues := makeLabels(labels)
		histogram.WithLabelValues(labelValues...).Observe(value)
	}
}

func makeLabels(labels map[string]string) []string {
	result := make([]string, 0, len(labels))
	for _, v := range labels {
		result = append(result, v)
	}
	return result
}

func (m *Manager) startSnapshotCollector() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		m.collectSnapshot()
	}
}

func (m *Manager) collectSnapshot() {
	m.mu.Lock()
	defer m.mu.Unlock()

	metrics := make(map[string]float64)
	metrics["snapshot_collected"] = 1

	dimensions := make(map[string]string)
	dimensions["host"] = "local"
	dimensions["region"] = "cn-east"

	snapshot := &types.Snapshot{
		SnapshotID: utils.GenerateID("snap"),
		Timestamp:  time.Now().UTC(),
		Metrics:    metrics,
		Dimensions: dimensions,
	}

	m.snapshots = append(m.snapshots, snapshot)

	if len(m.snapshots) > 10080 {
		m.snapshots = m.snapshots[1:]
	}
}

func (m *Manager) GetSnapshots(query SnapshotQuery) []*types.Snapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var result []*types.Snapshot
	for _, snap := range m.snapshots {
		if !snap.Timestamp.Before(query.StartTime) && !snap.Timestamp.After(query.EndTime) {
			if query.MetricName != "" {
				if _, exists := snap.Metrics[query.MetricName]; !exists {
					continue
				}
			}
			match := true
			for k, v := range query.Dimensions {
				if snap.Dimensions[k] != v {
					match = false
					break
				}
			}
			if match {
				result = append(result, snap)
			}
		}
	}
	return result
}

func (m *Manager) GetLatestSnapshot() *types.Snapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if len(m.snapshots) == 0 {
		return nil
	}
	return m.snapshots[len(m.snapshots)-1]
}

func (m *Manager) RecordHTTPRequest(method, path string, statusCode int, duration time.Duration) {
	statusLabel := "2xx"
	if statusCode >= 400 && statusCode < 500 {
		statusLabel = "4xx"
	} else if statusCode >= 500 {
		statusLabel = "5xx"
	}

	m.IncrementCounter("http_requests_total", map[string]string{
		"method": method,
		"path":   path,
		"status": statusLabel,
	})

	m.ObserveHistogram("http_request_duration_seconds", duration.Seconds(), map[string]string{
		"method": method,
		"path":   path,
	})
}

func (m *Manager) RecordRateLimit(clientIP string) {
	m.IncrementCounter("gateway_rate_limited_total", map[string]string{
		"client_ip": clientIP,
	})
}

func (m *Manager) RecordFaultInjection(faultType, scope string) {
	m.IncrementCounter("fault_injections_total", map[string]string{
		"fault_type": faultType,
		"scope":      scope,
	})
}

func (m *Manager) RecordCertRotation(commonName string) {
	m.IncrementCounter("cert_rotations_total", map[string]string{
		"common_name": commonName,
	})
}

func (m *Manager) GetMetrics() map[string]interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()

	return map[string]interface{}{
		"counters_registered":   len(m.counters),
		"gauges_registered":     len(m.gauges),
		"histograms_registered": len(m.histograms),
		"snapshots_stored":      len(m.snapshots),
	}
}
