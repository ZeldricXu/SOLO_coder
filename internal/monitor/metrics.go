package monitor

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/pkg/models"
	"github.com/google/uuid"
	"github.com/prometheus/client_golang/prometheus"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type Metrics struct {
	reg            *prometheus.Registry
	requestCount   *prometheus.CounterVec
	requestLatency *prometheus.HistogramVec
	errorCount     *prometheus.CounterVec
	activeRequests *prometheus.GaugeVec
	slaBreaches    *prometheus.CounterVec
	tenantUsage    *prometheus.GaugeVec
	db             *gorm.DB
	mu             sync.RWMutex
	customMetrics  map[string]prometheus.Collector
}

func NewMetrics(db *gorm.DB) *Metrics {
	reg := prometheus.NewRegistry()

	m := &Metrics{
		reg: reg,
		requestCount: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "http_requests_total",
				Help: "Total number of HTTP requests",
			},
			[]string{"method", "path", "status_code", "tenant_id"},
		),
		requestLatency: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "http_request_duration_seconds",
				Help:    "HTTP request latency in seconds",
				Buckets: prometheus.DefBuckets,
			},
			[]string{"method", "path", "tenant_id"},
		),
		errorCount: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "errors_total",
				Help: "Total number of errors",
			},
			[]string{"type", "tenant_id"},
		),
		activeRequests: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "active_requests",
				Help: "Number of active requests",
			},
			[]string{"tenant_id"},
		),
		slaBreaches: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "sla_breaches_total",
				Help: "Total number of SLA breaches",
			},
			[]string{"type", "tenant_id"},
		),
		tenantUsage: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "tenant_resource_usage",
				Help: "Tenant resource usage",
			},
			[]string{"tenant_id", "resource_type"},
		),
		db:            db,
		customMetrics: make(map[string]prometheus.Collector),
	}

	reg.MustRegister(
		m.requestCount,
		m.requestLatency,
		m.errorCount,
		m.activeRequests,
		m.slaBreaches,
		m.tenantUsage,
	)

	return m
}

func (m *Metrics) Registry() *prometheus.Registry {
	return m.reg
}

func (m *Metrics) RecordRequest(method, path, statusCode, tenantID string) {
	m.requestCount.WithLabelValues(method, path, statusCode, tenantID).Inc()
}

func (m *Metrics) RecordLatency(method, path, tenantID string, duration time.Duration) {
	m.requestLatency.WithLabelValues(method, path, tenantID).Observe(duration.Seconds())
}

func (m *Metrics) RecordError(errorType, tenantID string) {
	m.errorCount.WithLabelValues(errorType, tenantID).Inc()
}

func (m *Metrics) IncActiveRequests(tenantID string) {
	m.activeRequests.WithLabelValues(tenantID).Inc()
}

func (m *Metrics) DecActiveRequests(tenantID string) {
	m.activeRequests.WithLabelValues(tenantID).Dec()
}

func (m *Metrics) RecordSLABreach(breachType, tenantID string) {
	m.slaBreaches.WithLabelValues(breachType, tenantID).Inc()
}

func (m *Metrics) SetTenantUsage(tenantID, resourceType string, value float64) {
	m.tenantUsage.WithLabelValues(tenantID, resourceType).Set(value)
}

func (m *Metrics) RegisterCustom(name string, collector prometheus.Collector) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.customMetrics[name]; exists {
		return errors.New("metric already registered")
	}

	if err := m.reg.Register(collector); err != nil {
		return err
	}

	m.customMetrics[name] = collector
	return nil
}

func (m *Metrics) UnregisterCustom(name string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if collector, exists := m.customMetrics[name]; exists {
		m.reg.Unregister(collector)
		delete(m.customMetrics, name)
	}
}

type MetricsSnapshot struct {
	Throughput  float64 `json:"throughput"`
	LatencyP50  float64 `json:"latency_p50"`
	LatencyP95  float64 `json:"latency_p95"`
	LatencyP99  float64 `json:"latency_p99"`
	ErrorRate   float64 `json:"error_rate"`
	ActiveReqs  float64 `json:"active_requests"`
	SLABreaches float64 `json:"sla_breaches"`
}

func (m *Metrics) TakeSnapshot(ctx context.Context, tenantID string, dimensions map[string]string) (*models.StatsSnapshot, error) {
	snapshot := MetricsSnapshot{}

	metrics, err := m.reg.Gather()
	if err != nil {
		return nil, err
	}

	for _, mf := range metrics {
		switch mf.GetName() {
		case "http_requests_total":
			for _, m := range mf.GetMetric() {
				if hasLabel(m, "tenant_id", tenantID) {
					snapshot.Throughput += float64(m.GetCounter().GetValue())
				}
			}
		case "active_requests":
			for _, m := range mf.GetMetric() {
				if hasLabel(m, "tenant_id", tenantID) {
					snapshot.ActiveReqs += m.GetGauge().GetValue()
				}
			}
		case "sla_breaches_total":
			for _, m := range mf.GetMetric() {
				if hasLabel(m, "tenant_id", tenantID) {
					snapshot.SLABreaches += float64(m.GetCounter().GetValue())
				}
			}
		}
	}

	metricsBytes, _ := json.Marshal(snapshot)
	dimBytes, _ := json.Marshal(dimensions)

	snap := &models.StatsSnapshot{
		SnapshotID: fmt.Sprintf("snap_%s", uuid.New().String()[:8]),
		Timestamp:  time.Now(),
		Metrics:    metricsBytes,
		Dimensions: dimBytes,
		TenantID:   tenantID,
	}

	if err := m.db.WithContext(ctx).Create(snap).Error; err != nil {
		logger.Error("failed to save metrics snapshot", zap.Error(err))
		return nil, err
	}

	return snap, nil
}

func hasLabel(m interface{ GetLabel() []*prometheus.Desc }, name, value string) bool {
	return true
}

func (m *Metrics) QuerySnapshots(ctx context.Context, tenantID string, start, end time.Time, limit int) ([]*models.StatsSnapshot, error) {
	var snapshots []*models.StatsSnapshot
	query := m.db.WithContext(ctx).
		Where("tenant_id = ? AND timestamp BETWEEN ? AND ?", tenantID, start, end).
		Order("timestamp DESC")

	if limit > 0 {
		query = query.Limit(limit)
	}

	if err := query.Find(&snapshots).Error; err != nil {
		return nil, err
	}
	return snapshots, nil
}

func (m *Metrics) GetSystemHealth(ctx context.Context) map[string]interface{} {
	health := make(map[string]interface{})
	health["timestamp"] = time.Now().UTC().Format(time.RFC3339)

	sqlDB, err := m.db.DB()
	if err != nil {
		health["database"] = map[string]interface{}{
			"status": "error",
			"error":  err.Error(),
		}
	} else {
		stats := sqlDB.Stats()
		health["database"] = map[string]interface{}{
			"status":         "healthy",
			"open_connections": stats.OpenConnections,
			"in_use":         stats.InUse,
			"idle":           stats.Idle,
		}
	}

	metrics, err := m.reg.Gather()
	if err == nil {
		metricCount := 0
		for range metrics {
			metricCount++
		}
		health["metrics"] = map[string]interface{}{
			"status":        "healthy",
			"metric_families": metricCount,
		}
	}

	return health
}

type StatsQuery struct {
	TenantID   string
	MetricName string
	Start      time.Time
	End        time.Time
	Dimensions map[string]string
	Aggregation string
}

func (m *Metrics) QueryStats(ctx context.Context, query StatsQuery) ([]map[string]interface{}, error) {
	var snapshots []*models.StatsSnapshot
	dbQuery := m.db.WithContext(ctx).
		Where("tenant_id = ?", query.TenantID).
		Where("timestamp BETWEEN ? AND ?", query.Start, query.End).
		Order("timestamp ASC")

	if err := dbQuery.Find(&snapshots).Error; err != nil {
		return nil, err
	}

	results := make([]map[string]interface{}, 0, len(snapshots))
	for _, snap := range snapshots {
		var metrics MetricsSnapshot
		if err := json.Unmarshal(snap.Metrics, &metrics); err != nil {
			continue
		}

		results = append(results, map[string]interface{}{
			"timestamp": snap.Timestamp,
			"metrics":   metrics,
		})
	}

	return results, nil
}
