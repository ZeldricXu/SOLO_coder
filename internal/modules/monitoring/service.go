package monitoring

import (
	"context"
	"runtime"
	"strconv"
	"sync"
	"time"

	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/common/models"
	"loglevelplatform/pkg/utils"

	"github.com/prometheus/client_golang/prometheus"
	"go.uber.org/zap"
)

type MetricType string

const (
	MetricTypeCounter   MetricType = "counter"
	MetricTypeGauge     MetricType = "gauge"
	MetricTypeHistogram MetricType = "histogram"
	MetricTypeSummary   MetricType = "summary"
)

type MetricDefinition struct {
	Name        string
	Type        MetricType
	Help        string
	Labels      []string
	Buckets     []float64
	Objectives  map[float64]float64
}

type Service struct {
	counters   map[string]*prometheus.CounterVec
	gauges     map[string]*prometheus.GaugeVec
	histograms map[string]*prometheus.HistogramVec
	summaries  map[string]*prometheus.SummaryVec
	mu         sync.RWMutex
	registry   *prometheus.Registry
	snapshots  []models.StatsSnapshot
}

var (
	instance *Service
	once     sync.Once
)

func NewService() *Service {
	once.Do(func() {
		instance = &Service{
			counters:   make(map[string]*prometheus.CounterVec),
			gauges:     make(map[string]*prometheus.GaugeVec),
			histograms: make(map[string]*prometheus.HistogramVec),
			summaries:  make(map[string]*prometheus.SummaryVec),
			registry:   prometheus.NewRegistry(),
		}
		instance.registerDefaultMetrics()
	})
	return instance
}

func (s *Service) registerDefaultMetrics() {
	s.RegisterMetric(MetricDefinition{
		Name:   "http_requests_total",
		Type:   MetricTypeCounter,
		Help:   "Total number of HTTP requests",
		Labels: []string{"method", "path", "status"},
	})

	s.RegisterMetric(MetricDefinition{
		Name:    "http_request_duration_seconds",
		Type:    MetricTypeHistogram,
		Help:    "HTTP request duration in seconds",
		Labels:  []string{"method", "path"},
		Buckets: prometheus.DefBuckets,
	})

	s.RegisterMetric(MetricDefinition{
		Name:   "active_goroutines",
		Type:   MetricTypeGauge,
		Help:   "Number of active goroutines",
		Labels: []string{"component"},
	})

	s.RegisterMetric(MetricDefinition{
		Name:   "task_executions_total",
		Type:   MetricTypeCounter,
		Help:   "Total number of task executions",
		Labels: []string{"task_id", "status"},
	})
}

func (s *Service) RegisterMetric(def MetricDefinition) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	switch def.Type {
	case MetricTypeCounter:
		vec := prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: def.Name,
			Help: def.Help,
		}, def.Labels)
		s.counters[def.Name] = vec
		s.registry.MustRegister(vec)

	case MetricTypeGauge:
		vec := prometheus.NewGaugeVec(prometheus.GaugeOpts{
			Name: def.Name,
			Help: def.Help,
		}, def.Labels)
		s.gauges[def.Name] = vec
		s.registry.MustRegister(vec)

	case MetricTypeHistogram:
		buckets := def.Buckets
		if buckets == nil {
			buckets = prometheus.DefBuckets
		}
		vec := prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    def.Name,
			Help:    def.Help,
			Buckets: buckets,
		}, def.Labels)
		s.histograms[def.Name] = vec
		s.registry.MustRegister(vec)

	case MetricTypeSummary:
		objectives := def.Objectives
		if objectives == nil {
			objectives = map[float64]float64{0.5: 0.05, 0.9: 0.01, 0.99: 0.001}
		}
		vec := prometheus.NewSummaryVec(prometheus.SummaryOpts{
			Name:       def.Name,
			Help:       def.Help,
			Objectives: objectives,
		}, def.Labels)
		s.summaries[def.Name] = vec
		s.registry.MustRegister(vec)
	}

	return nil
}

func (s *Service) IncrementCounter(ctx context.Context, name string, labels map[string]string) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if counter, exists := s.counters[name]; exists {
		counter.With(labels).Inc()
	}
}

func (s *Service) SetGauge(ctx context.Context, name string, value float64, labels map[string]string) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if gauge, exists := s.gauges[name]; exists {
		gauge.With(labels).Set(value)
	}
}

func (s *Service) ObserveHistogram(ctx context.Context, name string, value float64, labels map[string]string) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if histogram, exists := s.histograms[name]; exists {
		histogram.With(labels).Observe(value)
	}
}

func (s *Service) ObserveSummary(ctx context.Context, name string, value float64, labels map[string]string) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if summary, exists := s.summaries[name]; exists {
		summary.With(labels).Observe(value)
	}
}

func (s *Service) RecordHTTPRequest(method, path string, status int, duration time.Duration) {
	s.IncrementCounter(context.Background(), "http_requests_total", map[string]string{
		"method": method,
		"path":   path,
		"status": strconv.Itoa(status),
	})
	s.ObserveHistogram(context.Background(), "http_request_duration_seconds", duration.Seconds(), map[string]string{
		"method": method,
		"path":   path,
	})
}

func (s *Service) TakeSnapshot(ctx context.Context, dimensions map[string]string) *models.StatsSnapshot {
	log := logger.FromContext(ctx)

	metrics := make(map[string]float64)

	metrics["timestamp"] = float64(time.Now().Unix())
	metrics["active_goroutines"] = float64(runtimeNumGoroutine())

	snapshot := &models.StatsSnapshot{
		SnapshotID: utils.NewID("snap"),
		Timestamp:  time.Now(),
		Metrics:    metrics,
		Dimensions: dimensions,
	}

	s.mu.Lock()
	s.snapshots = append(s.snapshots, *snapshot)
	if len(s.snapshots) > 1000 {
		s.snapshots = s.snapshots[1:]
	}
	s.mu.Unlock()

	log.Info("stats snapshot taken", zap.String("snapshot_id", snapshot.SnapshotID))
	return snapshot
}

func (s *Service) GetSnapshots(ctx context.Context, startTime, endTime time.Time, limit int) []models.StatsSnapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var result []models.StatsSnapshot
	for i := len(s.snapshots) - 1; i >= 0; i-- {
		snap := s.snapshots[i]
		if (startTime.IsZero() || snap.Timestamp.After(startTime)) &&
			(endTime.IsZero() || snap.Timestamp.Before(endTime)) {
			result = append(result, snap)
			if len(result) >= limit {
				break
			}
		}
	}
	return result
}

func (s *Service) GetRegistry() *prometheus.Registry {
	return s.registry
}

func (s *Service) GetMetrics(ctx context.Context) map[string]interface{} {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make(map[string]interface{})
	counters := make(map[string]interface{})
	gauges := make(map[string]interface{})

	for name := range s.counters {
		counters[name] = "counter"
	}
	for name := range s.gauges {
		gauges[name] = "gauge"
	}

	result["counters"] = counters
	result["gauges"] = gauges
	result["snapshot_count"] = len(s.snapshots)

	return result
}

func runtimeNumGoroutine() int {
	return runtime.NumGoroutine()
}
