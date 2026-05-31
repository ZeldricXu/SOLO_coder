package tenant

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"session130/internal/logger"
	"session130/internal/metrics"
)

type OperationType string

const (
	OpCreateTenant      OperationType = "create_tenant"
	OpGetTenant       OperationType = "get_tenant"
	OpUpdateConfig    OperationType = "update_config"
	OpUpdatePlan     OperationType = "update_plan"
	OpSuspendTenant   OperationType = "suspend_tenant"
	OpActivateTenant OperationType = "activate_tenant"
	OpDeleteTenant   OperationType = "delete_tenant"
	OpCheckRateLimit  OperationType = "check_rate_limit"
	OpCheckQuota     OperationType = "check_quota"
	OpRecordUsage    OperationType = "record_usage"
)

type OperationStatus string

const (
	StatusSuccess OperationStatus = "success"
	StatusFailed  OperationStatus = "failed"
)

type OperationMetrics struct {
	TotalCount    int64         `json:"total_count"`
	SuccessCount  int64         `json:"success_count"`
	FailedCount   int64         `json:"failed_count"`
	TotalDuration time.Duration `json:"total_duration"`
	MinDuration   time.Duration `json:"min_duration"`
	MaxDuration   time.Duration `json:"max_duration"`
	AvgDuration   time.Duration `json:"avg_duration"`
	P50Duration   time.Duration `json:"p50_duration"`
	P95Duration   time.Duration `json:"p95_duration"`
	P99Duration   time.Duration `json:"p99_duration"`
}

type OperationStats struct {
	mu           sync.RWMutex
	operation    OperationType
	metrics      OperationMetrics
	durations    []time.Duration
	maxSamples   int
}

func NewOperationStats(op OperationType, maxSamples int) *OperationStats {
	return &OperationStats{
		operation:  op,
		maxSamples: maxSamples,
		durations:  make([]time.Duration, 0, maxSamples),
	}
}

func (s *OperationStats) Record(duration time.Duration, status OperationStatus) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.metrics.TotalCount++
	s.metrics.TotalDuration += duration

	if status == StatusSuccess {
		s.metrics.SuccessCount++
	} else {
		s.metrics.FailedCount++
	}

	if s.metrics.MinDuration == 0 || duration < s.metrics.MinDuration {
		s.metrics.MinDuration = duration
	}
	if duration > s.metrics.MaxDuration {
		s.metrics.MaxDuration = duration
	}

	s.durations = append(s.durations, duration)
	if len(s.durations) > s.maxSamples {
		s.durations = s.durations[len(s.durations)-s.maxSamples:]
	}

	s.calculatePercentiles()

	if s.metrics.TotalCount > 0 {
		s.metrics.AvgDuration = s.metrics.TotalDuration / time.Duration(s.metrics.TotalCount)
	}
}

func (s *OperationStats) calculatePercentiles() {
	if len(s.durations) == 0 {
		return
	}

	sorted := make([]time.Duration, len(s.durations))
	copy(sorted, s.durations)
	for i := 0; i < len(sorted); i++ {
		for j := i + 1; j < len(sorted); j++ {
			if sorted[i] > sorted[j] {
				sorted[i], sorted[j] = sorted[j], sorted[i]
			}
		}
	}

	n := len(sorted)
	s.metrics.P50Duration = sorted[n*50/100]
	s.metrics.P95Duration = sorted[n*95/100]
	s.metrics.P99Duration = sorted[n*99/100]
}

func (s *OperationStats) GetMetrics() OperationMetrics {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.metrics
}

func (s *OperationStats) Reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.metrics = OperationMetrics{}
	s.durations = s.durations[:0]
}

type Monitor struct {
	tenantMgr        *Manager
	operationStats   map[OperationType]*OperationStats
	activeTenants    int64
	suspendedTenants int64
	totalRequests    int64
	rateLimitHits     int64
	rateLimitMisses  int64
	quotaViolations int64
	mu               sync.RWMutex
	enablePrometheus bool
}

var (
	monitorInstance *Monitor
	monitorOnce     sync.Once
)

func NewMonitor(tenantMgr *Manager) *Monitor {
	m := &Monitor{
		tenantMgr:      tenantMgr,
		operationStats: make(map[OperationType]*OperationStats),
		enablePrometheus: true,
	}

	operations := []OperationType{
		OpCreateTenant,
		OpGetTenant,
		OpUpdateConfig,
		OpUpdatePlan,
		OpSuspendTenant,
		OpActivateTenant,
		OpDeleteTenant,
		OpCheckRateLimit,
		OpCheckQuota,
		OpRecordUsage,
	}

	for _, op := range operations {
		m.operationStats[op] = NewOperationStats(op, 10000)
	}

	return m
}

func GetMonitor() *Monitor {
	monitorOnce.Do(func() {
		monitorInstance = NewMonitor(GetManager())
	})
	return monitorInstance
}

func (m *Monitor) RecordOperation(op OperationType, duration time.Duration, status OperationStatus, labels map[string]string) {
	if stats, exists := m.operationStats[op]; exists {
		stats.Record(duration, status)
	}

	if m.enablePrometheus {
		metrics.Inc("tenant_operation_total", map[string]string{
			"operation": string(op),
			"status":    string(status),
		})
		metrics.Observe("tenant_operation_duration_seconds", duration.Seconds(), map[string]string{
			"operation": string(op),
		})
	}

	if status == StatusFailed {
		logger.Warn("", "tenant operation failed", map[string]interface{}{
			"operation": string(op),
			"duration":  duration.String(),
			"labels":    labels,
		})
	}
}

func (m *Monitor) TrackTenantStatus(status TenantStatus) {
	switch status {
	case StatusActive:
		atomic.AddInt64(&m.activeTenants, 1)
	case StatusSuspended:
		atomic.AddInt64(&m.suspendedTenants, 1)
	case StatusDeleted:
		atomic.AddInt64(&m.activeTenants, -1)
	}
}

func (m *Monitor) TrackRequest() {
	atomic.AddInt64(&m.totalRequests, 1)
	if m.enablePrometheus {
		metrics.Inc("tenant_requests_total", nil)
	}
}

func (m *Monitor) TrackRateLimit(allowed bool) {
	if allowed {
		atomic.AddInt64(&m.rateLimitHits, 1)
		if m.enablePrometheus {
			metrics.Inc("tenant_rate_limit_allowed_total", nil)
		}
	} else {
		atomic.AddInt64(&m.rateLimitMisses, 1)
		if m.enablePrometheus {
			metrics.Inc("tenant_rate_limit_blocked_total", nil)
		}
	}
}

func (m *Monitor) TrackQuotaViolation() {
	atomic.AddInt64(&m.quotaViolations, 1)
	if m.enablePrometheus {
		metrics.Inc("tenant_quota_violations_total", nil)
	}
}

func (m *Monitor) GetOperationStats(op OperationType) (OperationMetrics, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	stats, exists := m.operationStats[op]
	if !exists {
		return OperationMetrics{}, fmt.Errorf("operation %s not found", op)
	}
	return stats.GetMetrics(), nil
}

func (m *Monitor) GetAllOperationStats() map[OperationType]OperationMetrics {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make(map[OperationType]OperationMetrics, len(m.operationStats))
	for op, stats := range m.operationStats {
		result[op] = stats.GetMetrics()
	}
	return result
}

func (m *Monitor) GetSummary() map[string]interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()

	active := atomic.LoadInt64(&m.activeTenants)
	suspended := atomic.LoadInt64(&m.suspendedTenants)
	totalRequests := atomic.LoadInt64(&m.totalRequests)
	rateLimitHits := atomic.LoadInt64(&m.rateLimitHits)
	rateLimitMisses := atomic.LoadInt64(&m.rateLimitMisses)
	quotaViolations := atomic.LoadInt64(&m.quotaViolations)

	rateLimitTotal := rateLimitHits + rateLimitMisses
	rateLimitAllowRate := float64(0)
	if rateLimitTotal > 0 {
		rateLimitAllowRate = float64(rateLimitHits) / float64(rateLimitTotal)
	}

	return map[string]interface{}{
		"active_tenants":     active,
		"suspended_tenants": suspended,
		"total_tenants":       active + suspended,
		"total_requests":     totalRequests,
		"rate_limit": map[string]interface{}{
			"hits":        rateLimitHits,
			"misses":       rateLimitMisses,
			"allow_rate":   rateLimitAllowRate,
		},
		"quota_violations":  quotaViolations,
	}
}

func (m *Monitor) GetPrometheusMetrics() string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var sb strings.Builder

	sb.WriteString("# HELP tenant_active_tenants Number of active tenants\n")
	sb.WriteString("# TYPE tenant_active_tenants gauge\n")
	sb.WriteString(fmt.Sprintf("tenant_active_tenants %d\n", atomic.LoadInt64(&m.activeTenants)))

	sb.WriteString("# HELP tenant_suspended_tenants Number of suspended tenants\n")
	sb.WriteString("# TYPE tenant_suspended_tenants gauge\n")
	sb.WriteString(fmt.Sprintf("tenant_suspended_tenants %d\n", atomic.LoadInt64(&m.suspendedTenants)))

	sb.WriteString("# HELP tenant_total_requests Total number of tenant requests\n")
	sb.WriteString("# TYPE tenant_total_requests counter\n")
	sb.WriteString(fmt.Sprintf("tenant_total_requests %d\n", atomic.LoadInt64(&m.totalRequests)))

	sb.WriteString("# HELP tenant_rate_limit_allowed_total Total rate limit allowed\n")
	sb.WriteString("# TYPE tenant_rate_limit_allowed_total counter\n")
	sb.WriteString(fmt.Sprintf("tenant_rate_limit_allowed_total %d\n", atomic.LoadInt64(&m.rateLimitHits)))

	sb.WriteString("# HELP tenant_rate_limit_blocked_total Total rate limit blocked\n")
	sb.WriteString("# TYPE tenant_rate_limit_blocked_total counter\n")
	sb.WriteString(fmt.Sprintf("tenant_rate_limit_blocked_total %d\n", atomic.LoadInt64(&m.rateLimitMisses)))

	sb.WriteString("# HELP tenant_quota_violations_total Total quota violations\n")
	sb.WriteString("# TYPE tenant_quota_violations_total counter\n")
	sb.WriteString(fmt.Sprintf("tenant_quota_violations_total %d\n", atomic.LoadInt64(&m.quotaViolations)))

	for op, stats := range m.operationStats {
		opMetrics := stats.GetMetrics()
		sb.WriteString(fmt.Sprintf("# HELP tenant_operation_%s_total Total %s operations\n", string(op), string(op)))
		sb.WriteString(fmt.Sprintf("# TYPE tenant_operation_%s_total counter\n", string(op)))
		sb.WriteString(fmt.Sprintf("tenant_operation_%s_total{status=\"success\"} %d\n", string(op), opMetrics.SuccessCount))
		sb.WriteString(fmt.Sprintf("tenant_operation_%s_total{status=\"failed\"} %d\n", string(op), opMetrics.FailedCount))

		if opMetrics.TotalCount > 0 {
			sb.WriteString(fmt.Sprintf("# HELP tenant_operation_%s_duration_seconds %s operation duration\n", string(op), string(op)))
			sb.WriteString(fmt.Sprintf("# TYPE tenant_operation_%s_duration_seconds summary\n", string(op)))
			sb.WriteString(fmt.Sprintf("tenant_operation_%s_duration_seconds_sum %f\n", string(op), opMetrics.TotalDuration.Seconds()))
			sb.WriteString(fmt.Sprintf("tenant_operation_%s_duration_seconds_count %d\n", string(op), opMetrics.TotalCount))
			sb.WriteString(fmt.Sprintf("tenant_operation_%s_duration_seconds{quantile=\"0.5\"} %f\n", string(op), opMetrics.P50Duration.Seconds()))
			sb.WriteString(fmt.Sprintf("tenant_operation_%s_duration_seconds{quantile=\"0.95\"} %f\n", string(op), opMetrics.P95Duration.Seconds()))
			sb.WriteString(fmt.Sprintf("tenant_operation_%s_duration_seconds{quantile=\"0.99\"} %f\n", string(op), opMetrics.P99Duration.Seconds()))
		}
	}

	return sb.String()
}

func (m *Monitor) ResetAll() {
	m.mu.Lock()
	defer m.mu.Unlock()

	for _, stats := range m.operationStats {
		stats.Reset()
	}

	atomic.StoreInt64(&m.activeTenants, 0)
	atomic.StoreInt64(&m.suspendedTenants, 0)
	atomic.StoreInt64(&m.totalRequests, 0)
	atomic.StoreInt64(&m.rateLimitHits, 0)
	atomic.StoreInt64(&m.rateLimitMisses, 0)
	atomic.StoreInt64(&m.quotaViolations, 0)
}

func (m *Monitor) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]interface{}{
		"summary": m.GetSummary(),
		"operations": m.GetAllOperationStats(),
	})
}

type MonitoredManager struct {
	*Manager
	monitor *Monitor
}

func NewMonitoredManager() *MonitoredManager {
	mgr := NewManager()
	return &MonitoredManager{
		Manager: mgr,
		monitor: GetMonitor(),
	}
}

func (mm *MonitoredManager) CreateTenant(name, adminEmail string, plan BillingPlan) (*Tenant, error) {
	start := time.Now()
	tenant, err := mm.Manager.CreateTenant(name, adminEmail, plan)
	duration := time.Since(start)

	status := StatusSuccess
	labels := map[string]string{"plan": string(plan)}
	if err != nil {
		status = StatusFailed
		labels["error"] = err.Error()
	} else {
		mm.monitor.TrackTenantStatus(StatusActive)
	}

	mm.monitor.RecordOperation(OpCreateTenant, duration, status, labels)
	return tenant, err
}

func (mm *MonitoredManager) GetTenant(tenantID string) (*Tenant, error) {
	start := time.Now()
	tenant, err := mm.Manager.GetTenant(tenantID)
	duration := time.Since(start)

	status := StatusSuccess
	labels := map[string]string{"tenant_id": tenantID}
	if err != nil {
		status = StatusFailed
		labels["error"] = err.Error()
	}

	mm.monitor.RecordOperation(OpGetTenant, duration, status, labels)
	mm.monitor.TrackRequest()
	return tenant, err
}

func (mm *MonitoredManager) UpdateTenantConfig(tenantID string, config map[string]interface{}) (*Tenant, error) {
	start := time.Now()
	tenant, err := mm.Manager.UpdateTenantConfig(tenantID, config)
	duration := time.Since(start)

	status := StatusSuccess
	labels := map[string]string{"tenant_id": tenantID}
	if err != nil {
		status = StatusFailed
		labels["error"] = err.Error()
	}

	mm.monitor.RecordOperation(OpUpdateConfig, duration, status, labels)
	return tenant, err
}

func (mm *MonitoredManager) UpdateBillingPlan(tenantID string, plan BillingPlan) (*Tenant, error) {
	start := time.Now()
	tenant, err := mm.Manager.UpdateBillingPlan(tenantID, plan)
	duration := time.Since(start)

	status := StatusSuccess
	labels := map[string]string{"tenant_id": tenantID, "plan": string(plan)}
	if err != nil {
		status = StatusFailed
		labels["error"] = err.Error()
	}

	mm.monitor.RecordOperation(OpUpdatePlan, duration, status, labels)
	return tenant, err
}

func (mm *MonitoredManager) SuspendTenant(tenantID string) (*Tenant, error) {
	start := time.Now()
	tenant, err := mm.Manager.SuspendTenant(tenantID)
	duration := time.Since(start)

	status := StatusSuccess
	labels := map[string]string{"tenant_id": tenantID}
	if err != nil {
		status = StatusFailed
		labels["error"] = err.Error()
	} else {
		mm.monitor.TrackTenantStatus(StatusSuspended)
	}

	mm.monitor.RecordOperation(OpSuspendTenant, duration, status, labels)
	return tenant, err
}

func (mm *MonitoredManager) ActivateTenant(tenantID string) (*Tenant, error) {
	start := time.Now()
	tenant, err := mm.Manager.ActivateTenant(tenantID)
	duration := time.Since(start)

	status := StatusSuccess
	labels := map[string]string{"tenant_id": tenantID}
	if err != nil {
		status = StatusFailed
		labels["error"] = err.Error()
	} else {
		mm.monitor.TrackTenantStatus(StatusActive)
	}

	mm.monitor.RecordOperation(OpActivateTenant, duration, status, labels)
	return tenant, err
}

func (mm *MonitoredManager) DeleteTenant(tenantID string) error {
	start := time.Now()
	err := mm.Manager.DeleteTenant(tenantID)
	duration := time.Since(start)

	status := StatusSuccess
	labels := map[string]string{"tenant_id": tenantID}
	if err != nil {
		status = StatusFailed
		labels["error"] = err.Error()
	} else {
		mm.monitor.TrackTenantStatus(StatusDeleted)
	}

	mm.monitor.RecordOperation(OpDeleteTenant, duration, status, labels)
	return err
}

func (mm *MonitoredManager) CheckRateLimit(tenantID string) (bool, error) {
	start := time.Now()
	allowed, err := mm.Manager.CheckRateLimit(tenantID)
	duration := time.Since(start)

	status := StatusSuccess
	labels := map[string]string{"tenant_id": tenantID}
	if err != nil {
		status = StatusFailed
		labels["error"] = err.Error()
	}

	mm.monitor.RecordOperation(OpCheckRateLimit, duration, status, labels)
	mm.monitor.TrackRateLimit(allowed)
	return allowed, err
}

func (mm *MonitoredManager) CheckResourceQuota(tenantID string) (bool, error) {
	start := time.Now()
	ok, err := mm.Manager.CheckResourceQuota(tenantID)
	duration := time.Since(start)

	status := StatusSuccess
	labels := map[string]string{"tenant_id": tenantID}
	if err != nil {
		status = StatusFailed
		labels["error"] = err.Error()
	} else if !ok {
		mm.monitor.TrackQuotaViolation()
	}

	mm.monitor.RecordOperation(OpCheckQuota, duration, status, labels)
	return ok, err
}

func (mm *MonitoredManager) RecordUsage(tenantID string, usage ResourceUsage) (*Tenant, error) {
	start := time.Now()
	tenant, err := mm.Manager.RecordUsage(tenantID, usage)
	duration := time.Since(start)

	status := StatusSuccess
	labels := map[string]string{"tenant_id": tenantID}
	if err != nil {
		status = StatusFailed
		labels["error"] = err.Error()
	}

	mm.monitor.RecordOperation(OpRecordUsage, duration, status, labels)
	return tenant, err
}

func (mm *MonitoredManager) Monitor() *Monitor {
	return mm.monitor
}

func (mm *MonitoredManager) GetMonitorSummary() map[string]interface{} {
	return mm.monitor.GetSummary()
}

func (mm *MonitoredManager) GetPrometheusMetrics() string {
	return mm.monitor.GetPrometheusMetrics()
}

func (mm *MonitoredManager) GetMonitor() *Monitor {
	return mm.monitor
}
