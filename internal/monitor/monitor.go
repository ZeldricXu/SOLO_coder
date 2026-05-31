package monitor

import (
	"context"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"gorm.io/gorm"
	"session187/internal/common"
	"session187/pkg/errors"
)

type MetricType string

const (
	MetricCounter   MetricType = "counter"
	MetricGauge     MetricType = "gauge"
	MetricHistogram MetricType = "histogram"
	MetricSummary   MetricType = "summary"
)

type MetricDefinition struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID    string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	Name        string                 `json:"name" gorm:"type:varchar(128);index"`
	Type        MetricType             `json:"type" gorm:"type:varchar(32)"`
	Description string                 `json:"description" gorm:"type:text"`
	Labels      []string               `json:"labels" gorm:"type:jsonb;serializer:json"`
	Buckets     []float64              `json:"buckets" gorm:"type:jsonb;serializer:json"`
	Enabled     bool                   `json:"enabled" gorm:"default:true"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type MetricDataPoint struct {
	ID         string            `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID   string            `json:"tenant_id" gorm:"type:varchar(64);index"`
	MetricID   string            `json:"metric_id" gorm:"type:varchar(64);index"`
	Value      float64           `json:"value"`
	Labels     map[string]string `json:"labels" gorm:"type:jsonb"`
	Timestamp  time.Time         `json:"timestamp" gorm:"index"`
}

type Monitor struct {
	db             *gorm.DB
	registry       *prometheus.Registry
	counters       map[string]prometheus.Counter
	gauges         map[string]prometheus.Gauge
	histograms     map[string]prometheus.Histogram
	summaries      map[string]prometheus.Summary
	mu             sync.RWMutex
	collectInterval time.Duration
}

func NewMonitor(db *gorm.DB) *Monitor {
	return &Monitor{
		db:              db,
		registry:        prometheus.NewRegistry(),
		counters:        make(map[string]prometheus.Counter),
		gauges:          make(map[string]prometheus.Gauge),
		histograms:      make(map[string]prometheus.Histogram),
		summaries:       make(map[string]prometheus.Summary),
		collectInterval: 15 * time.Second,
	}
}

func (m *Monitor) RegisterMetric(def *MetricDefinition) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	fullName := def.TenantID + "_" + def.Name
	switch def.Type {
	case MetricCounter:
		if _, exists := m.counters[fullName]; !exists {
			m.counters[fullName] = promauto.With(m.registry).NewCounter(prometheus.CounterOpts{
				Name: fullName,
				Help: def.Description,
			})
		}
	case MetricGauge:
		if _, exists := m.gauges[fullName]; !exists {
			m.gauges[fullName] = promauto.With(m.registry).NewGauge(prometheus.GaugeOpts{
				Name: fullName,
				Help: def.Description,
			})
		}
	case MetricHistogram:
		buckets := def.Buckets
		if len(buckets) == 0 {
			buckets = prometheus.DefBuckets
		}
		if _, exists := m.histograms[fullName]; !exists {
			m.histograms[fullName] = promauto.With(m.registry).NewHistogram(prometheus.HistogramOpts{
				Name:    fullName,
				Help:    def.Description,
				Buckets: buckets,
			})
		}
	case MetricSummary:
		if _, exists := m.summaries[fullName]; !exists {
			m.summaries[fullName] = promauto.With(m.registry).NewSummary(prometheus.SummaryOpts{
				Name:       fullName,
				Help:       def.Description,
				Objectives: map[float64]float64{0.5: 0.05, 0.9: 0.01, 0.99: 0.001},
			})
		}
	}
	if def.ID == "" {
		def.ID = common.GenerateID("mtr")
	}
	def.CreatedAt = common.TimeNowUTC()
	def.UpdatedAt = common.TimeNowUTC()
	return m.db.Create(def).Error
}

func (m *Monitor) IncrementCounter(tenantID, metricName string, value float64, labels map[string]string) error {
	m.mu.RLock()
	defer m.mu.RUnlock()
	fullName := tenantID + "_" + metricName
	if counter, ok := m.counters[fullName]; ok {
		counter.Add(value)
		m.persistMetric(tenantID, metricName, value, labels)
		return nil
	}
	return errors.NewWithDetail(404, "指标不存在", metricName)
}

func (m *Monitor) SetGauge(tenantID, metricName string, value float64, labels map[string]string) error {
	m.mu.RLock()
	defer m.mu.RUnlock()
	fullName := tenantID + "_" + metricName
	if gauge, ok := m.gauges[fullName]; ok {
		gauge.Set(value)
		m.persistMetric(tenantID, metricName, value, labels)
		return nil
	}
	return errors.NewWithDetail(404, "指标不存在", metricName)
}

func (m *Monitor) RecordHistogram(tenantID, metricName string, value float64, labels map[string]string) error {
	m.mu.RLock()
	defer m.mu.RUnlock()
	fullName := tenantID + "_" + metricName
	if histogram, ok := m.histograms[fullName]; ok {
		histogram.Observe(value)
		m.persistMetric(tenantID, metricName, value, labels)
		return nil
	}
	return errors.NewWithDetail(404, "指标不存在", metricName)
}

func (m *Monitor) RecordSummary(tenantID, metricName string, value float64, labels map[string]string) error {
	m.mu.RLock()
	defer m.mu.RUnlock()
	fullName := tenantID + "_" + metricName
	if summary, ok := m.summaries[fullName]; ok {
		summary.Observe(value)
		m.persistMetric(tenantID, metricName, value, labels)
		return nil
	}
	return errors.NewWithDetail(404, "指标不存在", metricName)
}

func (m *Monitor) persistMetric(tenantID, metricName string, value float64, labels map[string]string) {
	dp := &MetricDataPoint{
		ID:        common.GenerateID("mdp"),
		TenantID:  tenantID,
		MetricID:  metricName,
		Value:     value,
		Labels:    labels,
		Timestamp: common.TimeNowUTC(),
	}
	m.db.Create(dp)
}

func (m *Monitor) QueryMetrics(tenantID, metricName string, startTime, endTime time.Time, labels map[string]string, aggregation string) ([]MetricDataPoint, float64, error) {
	var dataPoints []MetricDataPoint
	query := m.db.Where("tenant_id = ? AND metric_id = ?", tenantID, metricName)
	if !startTime.IsZero() {
		query = query.Where("timestamp >= ?", startTime)
	}
	if !endTime.IsZero() {
		query = query.Where("timestamp < ?", endTime)
	}
	err := query.Order("timestamp desc").Limit(1000).Find(&dataPoints).Error
	if err != nil {
		return nil, 0, errors.NewWithDetail(500, "查询指标失败", err.Error())
	}
	var avg float64
	if len(dataPoints) > 0 {
		for _, dp := range dataPoints {
			avg += dp.Value
		}
		avg /= float64(len(dataPoints))
	}
	return dataPoints, avg, nil
}

func (m *Monitor) GetMetrics(tenantID string) ([]MetricDefinition, error) {
	var metrics []MetricDefinition
	err := m.db.Where("tenant_id = ?", tenantID).Find(&metrics).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "查询指标定义失败", err.Error())
	}
	return metrics, nil
}

func (m *Monitor) GetRegistry() *prometheus.Registry {
	return m.registry
}

func (m *Monitor) RecordRequest(tenantID, method, path, status string, duration time.Duration) {
	labels := map[string]string{
		"method": method,
		"path":   path,
		"status": status,
	}
	m.IncrementCounter(tenantID, "http_requests_total", 1, labels)
	m.RecordHistogram(tenantID, "http_request_duration_seconds", duration.Seconds(), labels)
}

func (m *Monitor) RecordError(tenantID, module string) {
	labels := map[string]string{"module": module}
	m.IncrementCounter(tenantID, "errors_total", 1, labels)
}

func (m *Monitor) RecordStorageUsed(tenantID string, bytes float64) {
	m.SetGauge(tenantID, "storage_used_bytes", bytes, nil)
}

func (m *Monitor) Start(ctx context.Context) {
	ticker := time.NewTicker(m.collectInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			m.collectSystemMetrics()
		}
	}
}

func (m *Monitor) collectSystemMetrics() {
	var tenants []string
	m.db.Table("tenants").Where("status = ?", "active").Pluck("id", &tenants)
	for _, t := range tenants {
		var count int64
		m.db.Table("object_metadata").Where("tenant_id = ? AND status = ?", t, "active").Count(&count)
		m.SetGauge(t, "total_objects", float64(count), nil)
		var totalSize int64
		m.db.Table("object_metadata").Where("tenant_id = ? AND status = ?", t, "active").Select("COALESCE(SUM(size), 0)").Scan(&totalSize)
		m.SetGauge(t, "total_storage_bytes", float64(totalSize), nil)
	}
}

func (d *MetricDefinition) TableName() string {
	return "metric_definitions"
}

func (p *MetricDataPoint) TableName() string {
	return "metric_data_points"
}
