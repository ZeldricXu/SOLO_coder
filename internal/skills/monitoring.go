package skills

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/prometheus/client_golang/prometheus"
	"go.uber.org/zap"
)

type CriticalPath string

const (
	PathSkillTreeBuild      CriticalPath = "skill_tree_build"
	PathEmployeeAssessment  CriticalPath = "employee_assessment"
	PathLearningPathGen     CriticalPath = "learning_path_generation"
	PathSkillGapAnalysis    CriticalPath = "skill_gap_analysis"
	PathSkillRecommend      CriticalPath = "skill_recommendation"
	PathSkillImport         CriticalPath = "skill_import"
	PathGraphTraversal      CriticalPath = "graph_traversal"
)

type MonitoringConfig struct {
	PrometheusEnabled bool
	EnableDetailedLogs bool
	SlowOperationThreshold time.Duration
}

func DefaultMonitoringConfig() *MonitoringConfig {
	return &MonitoringConfig{
		PrometheusEnabled:       true,
		EnableDetailedLogs:      true,
		SlowOperationThreshold:  500 * time.Millisecond,
	}
}

type GraphMetrics struct {
	registry *prometheus.Registry

	operationDuration *prometheus.HistogramVec
	operationCount    *prometheus.CounterVec
	operationErrors   *prometheus.CounterVec

	skillCount        *prometheus.Gauge
	employeeSkillCount *prometheus.Gauge
	learningPathCount  *prometheus.Gauge

	activeOperations   *prometheus.GaugeVec
	queueSize          *prometheus.GaugeVec

	cacheHits          *prometheus.CounterVec
	cacheMisses        *prometheus.CounterVec

	mu                 sync.RWMutex
	customMetrics      map[string]prometheus.Collector
}

type MetricsRegistry struct {
	metrics *GraphMetrics
	config  *MonitoringConfig
	mu      sync.RWMutex
	running bool
}

var globalMetrics *GraphMetrics
var metricsOnce sync.Once
var metricsMu sync.RWMutex

func GetGlobalMetrics() *GraphMetrics {
	metricsMu.RLock()
	m := globalMetrics
	metricsMu.RUnlock()
	if m != nil {
		return m
	}
	metricsMu.Lock()
	defer metricsMu.Unlock()
	if globalMetrics == nil {
		globalMetrics = NewGraphMetrics(nil)
	}
	return globalMetrics
}

func NewGraphMetrics(reg *prometheus.Registry) *GraphMetrics {
	if reg == nil {
		reg = prometheus.DefaultRegisterer.(*prometheus.Registry)
	}

	m := &GraphMetrics{
		registry: reg,
		customMetrics: make(map[string]prometheus.Collector),
	}

	m.operationDuration = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "skills_operation_duration_seconds",
			Help:    "Duration of skill graph operations in seconds",
			Buckets: prometheus.ExponentialBuckets(0.001, 2, 15),
		},
		[]string{"operation", "path", "status"},
	)

	m.operationCount = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "skills_operations_total",
			Help: "Total number of skill graph operations",
		},
		[]string{"operation", "path", "status"},
	)

	m.operationErrors = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "skills_operation_errors_total",
			Help: "Total number of skill graph operation errors",
		},
		[]string{"operation", "path", "error_type"},
	)

	m.skillCount = prometheus.NewGauge(
		prometheus.GaugeOpts{
			Name: "skills_total",
			Help: "Total number of skills in the graph",
		},
	)

	m.employeeSkillCount = prometheus.NewGauge(
		prometheus.GaugeOpts{
			Name: "employee_skills_total",
			Help: "Total number of employee skill assessments",
		},
	)

	m.learningPathCount = prometheus.NewGauge(
		prometheus.GaugeOpts{
			Name: "learning_paths_total",
			Help: "Total number of learning paths",
		},
	)

	m.activeOperations = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "skills_active_operations",
			Help: "Number of active skill graph operations",
		},
		[]string{"operation", "path"},
	)

	m.queueSize = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "skills_queue_size",
			Help: "Size of skill graph processing queues",
		},
		[]string{"queue_type"},
	)

	m.cacheHits = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "skills_cache_hits_total",
			Help: "Total number of cache hits",
		},
		[]string{"cache_level"},
	)

	m.cacheMisses = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "skills_cache_misses_total",
			Help: "Total number of cache misses",
		},
		[]string{"cache_level"},
	)

	reg.MustRegister(
		m.operationDuration,
		m.operationCount,
		m.operationErrors,
		m.skillCount,
		m.employeeSkillCount,
		m.learningPathCount,
		m.activeOperations,
		m.queueSize,
		m.cacheHits,
		m.cacheMisses,
	)

	return m
}

func (m *GraphMetrics) Registry() *prometheus.Registry {
	return m.registry
}

func (m *GraphMetrics) RecordOperation(operation string, path CriticalPath, status string, duration time.Duration) {
	m.operationDuration.WithLabelValues(operation, string(path), status).Observe(duration.Seconds())
	m.operationCount.WithLabelValues(operation, string(path), status).Inc()
}

func (m *GraphMetrics) RecordError(operation string, path CriticalPath, errorType string) {
	m.operationErrors.WithLabelValues(operation, string(path), errorType).Inc()
}

func (m *GraphMetrics) IncActive(operation string, path CriticalPath) {
	m.activeOperations.WithLabelValues(operation, string(path)).Inc()
}

func (m *GraphMetrics) DecActive(operation string, path CriticalPath) {
	m.activeOperations.WithLabelValues(operation, string(path)).Dec()
}

func (m *GraphMetrics) SetSkillCount(count float64) {
	m.skillCount.Set(count)
}

func (m *GraphMetrics) SetEmployeeSkillCount(count float64) {
	m.employeeSkillCount.Set(count)
}

func (m *GraphMetrics) SetLearningPathCount(count float64) {
	m.learningPathCount.Set(count)
}

func (m *GraphMetrics) SetQueueSize(queueType string, size float64) {
	m.queueSize.WithLabelValues(queueType).Set(size)
}

func (m *GraphMetrics) RecordCacheHit(level string) {
	m.cacheHits.WithLabelValues(level).Inc()
}

func (m *GraphMetrics) RecordCacheMiss(level string) {
	m.cacheMisses.WithLabelValues(level).Inc()
}

func (m *GraphMetrics) RegisterCustom(name string, collector prometheus.Collector) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.customMetrics[name]; exists {
		return errors.New("metric already registered")
	}

	if err := m.registry.Register(collector); err != nil {
		return err
	}

	m.customMetrics[name] = collector
	return nil
}

func (m *GraphMetrics) UnregisterCustom(name string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if collector, exists := m.customMetrics[name]; exists {
		m.registry.Unregister(collector)
		delete(m.customMetrics, name)
	}
}

type OperationSpan struct {
	Operation  string
	Path       CriticalPath
	StartTime  time.Time
	Attributes map[string]interface{}
}

type OperationTracker struct {
	metrics    *GraphMetrics
	config     *MonitoringConfig
	spans      map[string]*OperationSpan
	spansMu    sync.RWMutex
}

func NewOperationTracker(metrics *GraphMetrics, config *MonitoringConfig) *OperationTracker {
	if metrics == nil {
		metrics = GetGlobalMetrics()
	}
	if config == nil {
		config = DefaultMonitoringConfig()
	}

	return &OperationTracker{
		metrics: metrics,
		config:  config,
		spans:   make(map[string]*OperationSpan),
	}
}

func (t *OperationTracker) StartSpan(operation string, path CriticalPath, attrs map[string]interface{}) string {
	spanID := generateSpanID()
	span := &OperationSpan{
		Operation:  operation,
		Path:       path,
		StartTime:  time.Now(),
		Attributes: attrs,
	}

	t.spansMu.Lock()
	t.spans[spanID] = span
	t.spansMu.Unlock()

	t.metrics.IncActive(operation, path)

	if t.config.EnableDetailedLogs {
		logger.Debug("operation started",
			zap.String("span_id", spanID),
			zap.String("operation", operation),
			zap.String("path", string(path)),
		)
	}

	return spanID
}

func (t *OperationTracker) EndSpan(spanID string, err error) time.Duration {
	t.spansMu.RLock()
	span, exists := t.spans[spanID]
	t.spansMu.RUnlock()

	if !exists {
		return 0
	}

	duration := time.Since(span.StartTime)

	status := "success"
	errorType := ""
	if err != nil {
		status = "error"
		errorType = getErrorType(err)
		t.metrics.RecordError(span.Operation, span.Path, errorType)
	}

	t.metrics.RecordOperation(span.Operation, span.Path, status, duration)
	t.metrics.DecActive(span.Operation, span.Path)

	t.spansMu.Lock()
	delete(t.spans, spanID)
	t.spansMu.Unlock()

	if t.config.EnableDetailedLogs || duration > t.config.SlowOperationThreshold {
		logger.Info("operation completed",
			zap.String("span_id", spanID),
			zap.String("operation", span.Operation),
			zap.String("path", string(span.Path)),
			zap.String("status", status),
			zap.Duration("duration", duration),
			zap.String("error_type", errorType),
		)
	}

	return duration
}

func (t *OperationTracker) GetActiveSpans() []*OperationSpan {
	t.spansMu.RLock()
	defer t.spansMu.RUnlock()

	spans := make([]*OperationSpan, 0, len(t.spans))
	for _, span := range t.spans {
		spans = append(spans, span)
	}
	return spans
}

type PathStats struct {
	Path           CriticalPath  `json:"path"`
	TotalOps       int64         `json:"total_operations"`
	SuccessOps     int64         `json:"success_operations"`
	FailedOps      int64         `json:"failed_operations"`
	AvgDuration    time.Duration `json:"avg_duration_ms"`
	P50Duration    time.Duration `json:"p50_duration_ms"`
	P95Duration    time.Duration `json:"p95_duration_ms"`
	P99Duration    time.Duration `json:"p99_duration_ms"`
	ErrorRate      float64       `json:"error_rate"`
	CurrentActive  int           `json:"current_active"`
	LastUpdated    time.Time     `json:"last_updated"`
}

type HealthStatus struct {
	OverallHealth   string            `json:"overall_health"`
	DatabaseStatus  string            `json:"database_status"`
	CacheStatus     string            `json:"cache_status"`
	PathHealth      map[string]string `json:"path_health"`
	ActiveOps       int               `json:"active_operations"`
	SlowOps         int               `json:"slow_operations"`
	LastHealthCheck time.Time         `json:"last_health_check"`
}

type GraphMonitor struct {
	service   *GraphService
	metrics   *GraphMetrics
	tracker   *OperationTracker
	config    *MonitoringConfig

	stopChan  chan struct{}
	wg        sync.WaitGroup
	mu        sync.RWMutex
	running   bool
}

func NewGraphMonitor(service *GraphService, metrics *GraphMetrics, config *MonitoringConfig) *GraphMonitor {
	if metrics == nil {
		metrics = GetGlobalMetrics()
	}
	if config == nil {
		config = DefaultMonitoringConfig()
	}

	return &GraphMonitor{
		service:  service,
		metrics:  metrics,
		tracker:  NewOperationTracker(metrics, config),
		config:   config,
		stopChan: make(chan struct{}),
	}
}

func (m *GraphMonitor) Start(ctx context.Context) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.running {
		return errors.New("monitor already running")
	}

	m.running = true
	m.wg.Add(1)
	go m.collectLoop(ctx)

	logger.Info("graph monitor started")
	return nil
}

func (m *GraphMonitor) Stop() {
	m.mu.Lock()
	if !m.running {
		m.mu.Unlock()
		return
	}
	m.running = false
	m.mu.Unlock()

	close(m.stopChan)
	m.wg.Wait()
	logger.Info("graph monitor stopped")
}

func (m *GraphMonitor) collectLoop(ctx context.Context) {
	defer m.wg.Done()

	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	m.refreshCounts(ctx)

	for {
		select {
		case <-ticker.C:
			m.refreshCounts(ctx)
		case <-m.stopChan:
			return
		case <-ctx.Done():
			return
		}
	}
}

func (m *GraphMonitor) refreshCounts(ctx context.Context) {
	if m.service == nil {
		return
	}

	skills, err := m.service.GetSkillTree(ctx, "")
	if err == nil {
		m.metrics.SetSkillCount(float64(len(skills)))
	}

	employeeSkills, err := m.service.GetEmployeeSkills(ctx, "stats")
	if err == nil {
		m.metrics.SetEmployeeSkillCount(float64(len(employeeSkills)))
	}

	paths, err := m.service.GetLearningPaths(ctx, "stats")
	if err == nil {
		m.metrics.SetLearningPathCount(float64(len(paths)))
	}
}

func (m *GraphMonitor) TrackOperation(operation string, path CriticalPath, attrs map[string]interface{}) func(error) {
	spanID := m.tracker.StartSpan(operation, path, attrs)
	return func(err error) {
		m.tracker.EndSpan(spanID, err)
	}
}

func (m *GraphMonitor) GetHealthStatus(ctx context.Context) *HealthStatus {
	activeSpans := m.tracker.GetActiveSpans()

	health := &HealthStatus{
		OverallHealth:   "healthy",
		DatabaseStatus:  "healthy",
		CacheStatus:     "healthy",
		PathHealth:      make(map[string]string),
		ActiveOps:       len(activeSpans),
		SlowOps:         0,
		LastHealthCheck: time.Now(),
	}

	now := time.Now()
	for _, span := range activeSpans {
		duration := now.Sub(span.StartTime)
		if duration > m.config.SlowOperationThreshold {
			health.SlowOps++
		}
	}

	paths := []CriticalPath{
		PathSkillTreeBuild,
		PathEmployeeAssessment,
		PathLearningPathGen,
		PathSkillGapAnalysis,
		PathSkillRecommend,
	}

	for _, path := range paths {
		health.PathHealth[string(path)] = "healthy"
	}

	if health.SlowOps > 5 {
		health.OverallHealth = "degraded"
	}

	return health
}

func (m *GraphMonitor) GetPathStats(ctx context.Context, path CriticalPath) (*PathStats, error) {
	return &PathStats{
		Path:          path,
		TotalOps:      0,
		SuccessOps:    0,
		FailedOps:     0,
		AvgDuration:   0,
		P50Duration:   0,
		P95Duration:   0,
		P99Duration:   0,
		ErrorRate:     0,
		CurrentActive: 0,
		LastUpdated:   time.Now(),
	}, nil
}

func (m *GraphMonitor) Metrics() *GraphMetrics {
	return m.metrics
}

func (m *GraphMonitor) Tracker() *OperationTracker {
	return m.tracker
}

type MonitoredGraphService struct {
	*GraphService
	monitor *GraphMonitor
}

func NewMonitoredGraphService(service *GraphService, monitor *GraphMonitor) *MonitoredGraphService {
	if monitor == nil {
		monitor = NewGraphMonitor(service, nil, nil)
	}
	return &MonitoredGraphService{
		GraphService: service,
		monitor:      monitor,
	}
}

func (s *MonitoredGraphService) Start(ctx context.Context) error {
	return s.monitor.Start(ctx)
}

func (s *MonitoredGraphService) Stop() {
	s.monitor.Stop()
}

func (s *MonitoredGraphService) GetSkillTree(ctx context.Context, category string) ([]interface{}, error) {
	end := s.monitor.TrackOperation("get_skill_tree", PathSkillTreeBuild, map[string]interface{}{
		"category": category,
	})

	skills, err := s.GraphService.GetSkillTree(ctx, category)
	end(err)

	if err != nil {
		return nil, err
	}

	result := make([]interface{}, len(skills))
	for i, s := range skills {
		result[i] = s
	}
	return result, nil
}

func (s *MonitoredGraphService) AssessEmployeeSkill(ctx context.Context, employeeID, skillID string, proficiency int) (interface{}, error) {
	end := s.monitor.TrackOperation("assess_skill", PathEmployeeAssessment, map[string]interface{}{
		"employee_id": employeeID,
		"skill_id":    skillID,
		"proficiency": proficiency,
	})

	result, err := s.GraphService.AssessEmployeeSkill(ctx, employeeID, skillID, proficiency)
	end(err)
	return result, err
}

func (s *MonitoredGraphService) RecommendLearningPath(ctx context.Context, employeeID string, targetRole string) (interface{}, error) {
	end := s.monitor.TrackOperation("recommend_path", PathLearningPathGen, map[string]interface{}{
		"employee_id": employeeID,
		"target_role": targetRole,
	})

	result, err := s.GraphService.RecommendLearningPath(ctx, employeeID, targetRole)
	end(err)
	return result, err
}

func (s *MonitoredGraphService) GetEmployeeSkillGap(ctx context.Context, employeeID string, targetSkills []string) (map[string]int, error) {
	end := s.monitor.TrackOperation("skill_gap_analysis", PathSkillGapAnalysis, map[string]interface{}{
		"employee_id":   employeeID,
		"target_skills": targetSkills,
	})

	gap, err := s.GraphService.GetEmployeeSkillGap(ctx, employeeID, targetSkills)
	end(err)
	return gap, err
}

func (s *MonitoredGraphService) GetHealthStatus(ctx context.Context) *HealthStatus {
	return s.monitor.GetHealthStatus(ctx)
}

func (s *MonitoredGraphService) GetMetrics() *GraphMetrics {
	return s.monitor.Metrics()
}

func generateSpanID() string {
	return "span_" + time.Now().Format("20060102150405")
}

func getErrorType(err error) string {
	if err == nil {
		return ""
	}

	type errWithType interface {
		Type() string
	}

	if typed, ok := err.(errWithType); ok {
		return typed.Type()
	}

	switch err {
	case context.Canceled:
		return "cancelled"
	case context.DeadlineExceeded:
		return "timeout"
	}

	return "unknown"
}

func (m *GraphMetrics) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]interface{}{
		"status": "available",
	})
}
