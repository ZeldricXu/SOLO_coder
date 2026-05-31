package monitoring

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.uber.org/zap"

	"session316/internal/logger"
	"session316/internal/models"
	"session316/pkg/errors"
	"session316/pkg/utils"
)

const (
	DefaultWindowSize     = 60
	DefaultSnapshotInterval = 30 * time.Second
	DefaultMaxSnapshots   = 100
	DefaultNamespace      = "session316"
	DefaultSubsystem      = "monitoring"

	MetricTypeCounter   = "counter"
	MetricTypeGauge     = "gauge"
	MetricTypeHistogram = "histogram"
	MetricTypeSummary   = "summary"
)

type MetricType string

type MetricDefinition struct {
	Name        string            `json:"name"`
	Type        MetricType        `json:"type"`
	Description string            `json:"description"`
	Labels      map[string]string `json:"labels,omitempty"`
	Buckets     []float64         `json:"buckets,omitempty"`
	Objectives  map[float64]float64 `json:"objectives,omitempty"`
}

type MetricRecord struct {
	Name      string            `json:"name"`
	Value     float64           `json:"value"`
	Labels    map[string]string `json:"labels,omitempty"`
	Timestamp time.Time         `json:"timestamp"`
}

type WindowMetric struct {
	Name       string            `json:"name"`
	Labels     map[string]string `json:"labels,omitempty"`
	Count      int64             `json:"count"`
	Sum        float64           `json:"sum"`
	Min        float64           `json:"min"`
	Max        float64           `json:"max"`
	Avg        float64           `json:"avg"`
	P50        float64           `json:"p50"`
	P95        float64           `json:"p95"`
	P99        float64           `json:"p99"`
	WindowStart time.Time        `json:"window_start"`
	WindowEnd   time.Time        `json:"window_end"`
}

type MetricsQuery struct {
	Name       string            `json:"name,omitempty"`
	Labels     map[string]string `json:"labels,omitempty"`
	StartTime  *time.Time        `json:"start_time,omitempty"`
	EndTime    *time.Time        `json:"end_time,omitempty"`
	Aggregate  string            `json:"aggregate,omitempty"`
	Limit      int               `json:"limit,omitempty"`
}

type MetricsResult struct {
	Records []MetricRecord    `json:"records,omitempty"`
	Window  *WindowMetric     `json:"window,omitempty"`
	Snapshots []models.Snapshot `json:"snapshots,omitempty"`
	Total   int64             `json:"total"`
}

type MonitoringConfig struct {
	WindowSize       int           `json:"window_size"`
	SnapshotInterval time.Duration `json:"snapshot_interval"`
	MaxSnapshots     int           `json:"max_snapshots"`
	Namespace        string        `json:"namespace"`
	Subsystem        string        `json:"subsystem"`
	Host             string        `json:"host"`
	Region           string        `json:"region"`
	EnablePrometheus bool          `json:"enable_prometheus"`
	PrometheusPath   string        `json:"prometheus_path"`
	PrometheusPort   int           `json:"prometheus_port"`
}

type MonitoringManager struct {
	mu             sync.RWMutex
	config         *MonitoringConfig
	metrics        map[string]*metricState
	window         *slidingWindow
	snapshots      []models.Snapshot
	promRegistry   *prometheus.Registry
	promCollectors map[string]prometheus.Collector
	httpServer     *http.Server
	ctx            context.Context
	cancel         context.CancelFunc
	wg             sync.WaitGroup
}

type metricState struct {
	def       *MetricDefinition
	counter   *int64
	gauge     float64
	gaugeMu   sync.RWMutex
	values    []float64
}

type slidingWindow struct {
	mu       sync.RWMutex
	size     int
	records  []MetricRecord
	startIdx int
	count    int
}

var (
	manager *MonitoringManager
	once    sync.Once
)

func DefaultConfig() *MonitoringConfig {
	return &MonitoringConfig{
		WindowSize:       DefaultWindowSize,
		SnapshotInterval: DefaultSnapshotInterval,
		MaxSnapshots:     DefaultMaxSnapshots,
		Namespace:        DefaultNamespace,
		Subsystem:        DefaultSubsystem,
		Host:             "localhost",
		Region:           "unknown",
		EnablePrometheus: true,
		PrometheusPath:   "/metrics",
		PrometheusPort:   9090,
	}
}

func NewMonitoringManager(cfg *MonitoringConfig) *MonitoringManager {
	if cfg == nil {
		cfg = DefaultConfig()
	}
	if cfg.WindowSize <= 0 {
		cfg.WindowSize = DefaultWindowSize
	}
	if cfg.SnapshotInterval <= 0 {
		cfg.SnapshotInterval = DefaultSnapshotInterval
	}
	if cfg.MaxSnapshots <= 0 {
		cfg.MaxSnapshots = DefaultMaxSnapshots
	}
	if cfg.Namespace == "" {
		cfg.Namespace = DefaultNamespace
	}
	if cfg.Subsystem == "" {
		cfg.Subsystem = DefaultSubsystem
	}

	ctx, cancel := context.WithCancel(context.Background())

	m := &MonitoringManager{
		config:         cfg,
		metrics:        make(map[string]*metricState),
		window:         newSlidingWindow(cfg.WindowSize),
		snapshots:      make([]models.Snapshot, 0, cfg.MaxSnapshots),
		promRegistry:   prometheus.NewRegistry(),
		promCollectors: make(map[string]prometheus.Collector),
		ctx:            ctx,
		cancel:         cancel,
	}

	m.registerBuiltinMetrics()

	if cfg.EnablePrometheus {
		m.startPrometheusServer()
	}

	m.startBackgroundTasks()

	logger.Info("Monitoring manager initialized",
		zap.String("namespace", cfg.Namespace),
		zap.String("subsystem", cfg.Subsystem),
		zap.Int("window_size", cfg.WindowSize),
		zap.Duration("snapshot_interval", cfg.SnapshotInterval),
	)

	return m
}

func Init(cfg *MonitoringConfig) {
	once.Do(func() {
		manager = NewMonitoringManager(cfg)
	})
}

func GetManager() *MonitoringManager {
	if manager == nil {
		Init(nil)
	}
	return manager
}

func (m *MonitoringManager) RegisterMetric(def *MetricDefinition) error {
	if def == nil {
		return errors.ValidationError("definition", "cannot be nil")
	}
	if def.Name == "" {
		return errors.ValidationError("name", "cannot be empty")
	}
	if def.Type == "" {
		return errors.ValidationError("type", "cannot be empty")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.metrics[def.Name]; exists {
		return errors.NewWithDetails(errors.ErrCodeConflict,
			"metric already registered",
			fmt.Sprintf("metric '%s' is already registered", def.Name),
		)
	}

	state := &metricState{
		def: def,
	}

	switch def.Type {
	case MetricTypeCounter:
		counter := int64(0)
		state.counter = &counter
	case MetricTypeGauge:
		state.gauge = 0
	case MetricTypeHistogram, MetricTypeSummary:
		state.values = make([]float64, 0, 1024)
	default:
		return errors.ValidationError("type",
			fmt.Sprintf("unsupported metric type: %s", def.Type),
		)
	}

	m.metrics[def.Name] = state

	if m.config.EnablePrometheus {
		if err := m.registerPrometheusCollector(def); err != nil {
			logger.Warn("Failed to register prometheus collector",
				zap.String("metric", def.Name),
				zap.Error(err),
			)
		}
	}

	logger.Debug("Metric registered",
		zap.String("name", def.Name),
		zap.String("type", string(def.Type)),
	)

	return nil
}

func (m *MonitoringManager) RecordMetric(name string, value float64, labels map[string]string) error {
	if name == "" {
		return errors.ValidationError("name", "cannot be empty")
	}

	m.mu.RLock()
	state, exists := m.metrics[name]
	m.mu.RUnlock()

	if !exists {
		def := &MetricDefinition{
			Name:        name,
			Type:        MetricTypeGauge,
			Description: fmt.Sprintf("Auto-registered metric: %s", name),
			Labels:      labels,
		}
		if err := m.RegisterMetric(def); err != nil {
			return err
		}
		m.mu.RLock()
		state = m.metrics[name]
		m.mu.RUnlock()
	}

	now := time.Now()

	switch state.def.Type {
	case MetricTypeCounter:
		atomic.AddInt64(state.counter, int64(value))
	case MetricTypeGauge:
		state.gaugeMu.Lock()
		state.gauge = value
		state.gaugeMu.Unlock()
	case MetricTypeHistogram, MetricTypeSummary:
		m.mu.Lock()
		state.values = append(state.values, value)
		if len(state.values) > 10000 {
			state.values = state.values[len(state.values)-10000:]
		}
		m.mu.Unlock()
	}

	record := MetricRecord{
		Name:      name,
		Value:     value,
		Labels:    labels,
		Timestamp: now,
	}
	m.window.add(record)

	if m.config.EnablePrometheus {
		m.updatePrometheusCollector(name, value, labels)
	}

	logger.Debug("Metric recorded",
		zap.String("name", name),
		zap.Float64("value", value),
		zap.Any("labels", labels),
	)

	return nil
}

func RecordMetric(name string, value float64, labels map[string]string) error {
	return GetManager().RecordMetric(name, value, labels)
}

func (m *MonitoringManager) GetMetrics(name string, labels map[string]string) (*MetricsResult, error) {
	result := &MetricsResult{}

	m.mu.RLock()
	defer m.mu.RUnlock()

	if name != "" {
		state, exists := m.metrics[name]
		if !exists {
			return nil, errors.NotFoundError("metric", name)
		}

		record := m.buildMetricRecord(state, labels)
		result.Records = []MetricRecord{record}
		result.Total = 1
	} else {
		for _, state := range m.metrics {
			if matchLabels(state.def.Labels, labels) {
				record := m.buildMetricRecord(state, labels)
				result.Records = append(result.Records, record)
			}
		}
		result.Total = int64(len(result.Records))
	}

	return result, nil
}

func GetMetrics(name string, labels map[string]string) (*MetricsResult, error) {
	return GetManager().GetMetrics(name, labels)
}

func (m *MonitoringManager) QueryMetrics(query *MetricsQuery) (*MetricsResult, error) {
	if query == nil {
		return nil, errors.ValidationError("query", "cannot be nil")
	}

	result := &MetricsResult{}
	records := m.window.getRecords()

	filtered := make([]MetricRecord, 0, len(records))
	for _, r := range records {
		if query.Name != "" && r.Name != query.Name {
			continue
		}
		if !matchLabels(r.Labels, query.Labels) {
			continue
		}
		if query.StartTime != nil && r.Timestamp.Before(*query.StartTime) {
			continue
		}
		if query.EndTime != nil && r.Timestamp.After(*query.EndTime) {
			continue
		}
		filtered = append(filtered, r)
	}

	if query.Aggregate == "window" && len(filtered) > 0 {
		window := m.calculateWindowMetrics(filtered, query)
		result.Window = window
	}

	if query.Limit > 0 && query.Limit < len(filtered) {
		filtered = filtered[len(filtered)-query.Limit:]
	}

	result.Records = filtered
	result.Total = int64(len(filtered))

	return result, nil
}

func QueryMetrics(query *MetricsQuery) (*MetricsResult, error) {
	return GetManager().QueryMetrics(query)
}

func (m *MonitoringManager) CreateSnapshot() (*models.Snapshot, error) {
	windowMetrics, err := m.GetCurrentWindowMetrics()
	if err != nil {
		return nil, err
	}

	m.mu.RLock()
	throughput := int(windowMetrics.Avg)
	if windowMetrics.Name == "request_count" || windowMetrics.Name == "throughput" {
		throughput = int(windowMetrics.Sum)
	}

	snapshot := &models.Snapshot{
		SnapshotID: utils.GenerateSnapshotID(),
		Timestamp:  time.Now(),
		Metrics: models.Metrics{
			Throughput: throughput,
			LatencyP99: int(windowMetrics.P99),
			ErrorRate:  m.calculateErrorRate(),
		},
		Dimensions: models.Dimensions{
			Host:   m.config.Host,
			Region: m.config.Region,
		},
	}
	m.mu.RUnlock()

	m.mu.Lock()
	m.snapshots = append(m.snapshots, *snapshot)
	if len(m.snapshots) > m.config.MaxSnapshots {
		m.snapshots = m.snapshots[len(m.snapshots)-m.config.MaxSnapshots:]
	}
	m.mu.Unlock()

	logger.Info("Snapshot created",
		zap.String("snapshot_id", snapshot.SnapshotID),
		zap.Int("throughput", snapshot.Metrics.Throughput),
		zap.Int("latency_p99", snapshot.Metrics.LatencyP99),
		zap.Float64("error_rate", snapshot.Metrics.ErrorRate),
	)

	return snapshot, nil
}

func CreateSnapshot() (*models.Snapshot, error) {
	return GetManager().CreateSnapshot()
}

func (m *MonitoringManager) GetSnapshots(startTime, endTime *time.Time, limit int) (*MetricsResult, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := &MetricsResult{}
	filtered := make([]models.Snapshot, 0, len(m.snapshots))

	for _, s := range m.snapshots {
		if startTime != nil && s.Timestamp.Before(*startTime) {
			continue
		}
		if endTime != nil && s.Timestamp.After(*endTime) {
			continue
		}
		filtered = append(filtered, s)
	}

	if limit > 0 && limit < len(filtered) {
		filtered = filtered[len(filtered)-limit:]
	}

	result.Snapshots = filtered
	result.Total = int64(len(filtered))

	return result, nil
}

func GetSnapshots(startTime, endTime *time.Time, limit int) (*MetricsResult, error) {
	return GetManager().GetSnapshots(startTime, endTime, limit)
}

func (m *MonitoringManager) GetCurrentWindowMetrics() (*WindowMetric, error) {
	records := m.window.getRecords()
	if len(records) == 0 {
		return &WindowMetric{
			WindowStart: time.Now(),
			WindowEnd:   time.Now(),
		}, nil
	}

	return m.calculateWindowMetrics(records, nil), nil
}

func GetCurrentWindowMetrics() (*WindowMetric, error) {
	return GetManager().GetCurrentWindowMetrics()
}

func (m *MonitoringManager) PrometheusHandler() http.Handler {
	return promhttp.HandlerFor(m.promRegistry, promhttp.HandlerOpts{})
}

func (m *MonitoringManager) Shutdown(ctx context.Context) error {
	m.cancel()
	m.wg.Wait()

	if m.httpServer != nil {
		if err := m.httpServer.Shutdown(ctx); err != nil {
			logger.Error("Failed to shutdown prometheus server", zap.Error(err))
		}
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	m.promRegistry = nil
	m.promCollectors = nil

	logger.Info("Monitoring manager shutdown complete")
	return nil
}

func Shutdown(ctx context.Context) error {
	if manager != nil {
		return manager.Shutdown(ctx)
	}
	return nil
}

func (m *MonitoringManager) registerBuiltinMetrics() {
	builtinMetrics := []*MetricDefinition{
		{
			Name:        "request_count",
			Type:        MetricTypeCounter,
			Description: "Total number of requests",
		},
		{
			Name:        "request_latency_ms",
			Type:        MetricTypeHistogram,
			Description: "Request latency in milliseconds",
			Buckets:     []float64{1, 5, 10, 50, 100, 500, 1000, 5000},
		},
		{
			Name:        "error_count",
			Type:        MetricTypeCounter,
			Description: "Total number of errors",
		},
		{
			Name:        "active_connections",
			Type:        MetricTypeGauge,
			Description: "Number of active connections",
		},
		{
			Name:        "memory_usage_bytes",
			Type:        MetricTypeGauge,
			Description: "Memory usage in bytes",
		},
		{
			Name:        "cpu_usage_percent",
			Type:        MetricTypeGauge,
			Description: "CPU usage percentage",
		},
		{
			Name:        "throughput_rps",
			Type:        MetricTypeGauge,
			Description: "Throughput in requests per second",
		},
	}

	for _, def := range builtinMetrics {
		if err := m.RegisterMetric(def); err != nil {
			logger.Warn("Failed to register builtin metric",
				zap.String("name", def.Name),
				zap.Error(err),
			)
		}
	}
}

func (m *MonitoringManager) registerPrometheusCollector(def *MetricDefinition) error {
	var collector prometheus.Collector
	fqName := prometheus.BuildFQName(m.config.Namespace, m.config.Subsystem, def.Name)

	switch def.Type {
	case MetricTypeCounter:
		collector = prometheus.NewCounter(prometheus.CounterOpts{
			Name:        fqName,
			Help:        def.Description,
			ConstLabels: def.Labels,
		})
	case MetricTypeGauge:
		collector = prometheus.NewGauge(prometheus.GaugeOpts{
			Name:        fqName,
			Help:        def.Description,
			ConstLabels: def.Labels,
		})
	case MetricTypeHistogram:
		buckets := def.Buckets
		if len(buckets) == 0 {
			buckets = prometheus.DefBuckets
		}
		collector = prometheus.NewHistogram(prometheus.HistogramOpts{
			Name:        fqName,
			Help:        def.Description,
			Buckets:     buckets,
			ConstLabels: def.Labels,
		})
	case MetricTypeSummary:
		objectives := def.Objectives
		if len(objectives) == 0 {
			objectives = map[float64]float64{0.5: 0.05, 0.9: 0.01, 0.99: 0.001}
		}
		collector = prometheus.NewSummary(prometheus.SummaryOpts{
			Name:        fqName,
			Help:        def.Description,
			Objectives:  objectives,
			ConstLabels: def.Labels,
		})
	default:
		return fmt.Errorf("unsupported metric type: %s", def.Type)
	}

	if err := m.promRegistry.Register(collector); err != nil {
		return err
	}

	m.promCollectors[def.Name] = collector
	return nil
}

func (m *MonitoringManager) updatePrometheusCollector(name string, value float64, labels map[string]string) {
	collector, exists := m.promCollectors[name]
	if !exists {
		return
	}

	switch c := collector.(type) {
	case prometheus.Counter:
		c.Add(value)
	case prometheus.Gauge:
		c.Set(value)
	case prometheus.Histogram:
		c.Observe(value)
	case prometheus.Summary:
		c.Observe(value)
	}
}

func (m *MonitoringManager) startPrometheusServer() {
	mux := http.NewServeMux()
	mux.Handle(m.config.PrometheusPath, m.PrometheusHandler())

	m.httpServer = &http.Server{
		Addr:    fmt.Sprintf(":%d", m.config.PrometheusPort),
		Handler: mux,
	}

	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		logger.Info("Prometheus server starting",
			zap.Int("port", m.config.PrometheusPort),
			zap.String("path", m.config.PrometheusPath),
		)
		if err := m.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("Prometheus server error", zap.Error(err))
		}
	}()
}

func (m *MonitoringManager) startBackgroundTasks() {
	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		ticker := time.NewTicker(m.config.SnapshotInterval)
		defer ticker.Stop()

		for {
			select {
			case <-m.ctx.Done():
				return
			case <-ticker.C:
				if _, err := m.CreateSnapshot(); err != nil {
					logger.Error("Failed to create snapshot", zap.Error(err))
				}
			}
		}
	}()
}

func (m *MonitoringManager) buildMetricRecord(state *metricState, labels map[string]string) MetricRecord {
	record := MetricRecord{
		Name:      state.def.Name,
		Labels:    labels,
		Timestamp: time.Now(),
	}

	switch state.def.Type {
	case MetricTypeCounter:
		record.Value = float64(atomic.LoadInt64(state.counter))
	case MetricTypeGauge:
		state.gaugeMu.RLock()
		record.Value = state.gauge
		state.gaugeMu.RUnlock()
	case MetricTypeHistogram, MetricTypeSummary:
		m.mu.RLock()
		if len(state.values) > 0 {
			sorted := make([]float64, len(state.values))
			copy(sorted, state.values)
			sort.Float64s(sorted)
			record.Value = sorted[len(sorted)/2]
		}
		m.mu.RUnlock()
	}

	return record
}

func (m *MonitoringManager) calculateWindowMetrics(records []MetricRecord, query *MetricsQuery) *WindowMetric {
	if len(records) == 0 {
		return &WindowMetric{
			WindowStart: time.Now(),
			WindowEnd:   time.Now(),
		}
	}

	values := make([]float64, 0, len(records))
	for _, r := range records {
		values = append(values, r.Value)
	}
	sort.Float64s(values)

	wm := &WindowMetric{
		Count:       int64(len(values)),
		Min:         values[0],
		Max:         values[len(values)-1],
		WindowStart: records[0].Timestamp,
		WindowEnd:   records[len(records)-1].Timestamp,
	}

	if query != nil {
		wm.Name = query.Name
		wm.Labels = query.Labels
	} else if len(records) > 0 {
		wm.Name = records[0].Name
		wm.Labels = records[0].Labels
	}

	var sum float64
	for _, v := range values {
		sum += v
	}
	wm.Sum = sum
	wm.Avg = sum / float64(len(values))
	wm.P50 = percentile(values, 0.50)
	wm.P95 = percentile(values, 0.95)
	wm.P99 = percentile(values, 0.99)

	return wm
}

func (m *MonitoringManager) calculateErrorRate() float64 {
	errorState, errExists := m.metrics["error_count"]
	requestState, reqExists := m.metrics["request_count"]

	if !errExists || !reqExists {
		return 0
	}

	errCount := float64(atomic.LoadInt64(errorState.counter))
	reqCount := float64(atomic.LoadInt64(requestState.counter))

	if reqCount == 0 {
		return 0
	}

	return errCount / reqCount
}

func newSlidingWindow(size int) *slidingWindow {
	return &slidingWindow{
		size:    size,
		records: make([]MetricRecord, size),
	}
}

func (w *slidingWindow) add(record MetricRecord) {
	w.mu.Lock()
	defer w.mu.Unlock()

	w.records[w.startIdx] = record
	w.startIdx = (w.startIdx + 1) % w.size
	if w.count < w.size {
		w.count++
	}
}

func (w *slidingWindow) getRecords() []MetricRecord {
	w.mu.RLock()
	defer w.mu.RUnlock()

	if w.count == 0 {
		return []MetricRecord{}
	}

	result := make([]MetricRecord, w.count)
	if w.count < w.size {
		copy(result, w.records[:w.count])
	} else {
		copy(result, w.records[w.startIdx:])
		copy(result[w.size-w.startIdx:], w.records[:w.startIdx])
	}

	return result
}

func matchLabels(src, dst map[string]string) bool {
	if len(dst) == 0 {
		return true
	}
	for k, v := range dst {
		if srcVal, ok := src[k]; !ok || srcVal != v {
			return false
		}
	}
	return true
}

func percentile(sortedValues []float64, p float64) float64 {
	if len(sortedValues) == 0 {
		return 0
	}
	if len(sortedValues) == 1 {
		return sortedValues[0]
	}

	idx := int(float64(len(sortedValues)-1) * p)
	if idx >= len(sortedValues) {
		idx = len(sortedValues) - 1
	}
	return sortedValues[idx]
}

func (r *MetricsResult) ToJSON() ([]byte, error) {
	return json.Marshal(r)
}

func (wm *WindowMetric) ToJSON() ([]byte, error) {
	return json.Marshal(wm)
}
