package alerts

import (
	"fmt"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"observability-platform/pkg/models"
)

type MetricUnit string

const (
	UnitCount   MetricUnit = "count"
	UnitSeconds MetricUnit = "seconds"
	UnitBytes   MetricUnit = "bytes"
	UnitPercent MetricUnit = "percent"
)

type MetricType string

const (
	TypeCounter   MetricType = "counter"
	TypeGauge     MetricType = "gauge"
	TypeHistogram MetricType = "histogram"
	TypeSummary   MetricType = "summary"
)

type ObservableMetric struct {
	Name        string
	Type        MetricType
	Unit        MetricUnit
	Description string
	Labels      map[string]string
}

type CounterMetric struct {
	ObservableMetric
	value int64
}

func (m *CounterMetric) Inc(delta int64) {
	atomic.AddInt64(&m.value, delta)
}

func (m *CounterMetric) Value() int64 {
	return atomic.LoadInt64(&m.value)
}

type GaugeMetric struct {
	ObservableMetric
	value int64
}

func (m *GaugeMetric) Set(value int64) {
	atomic.StoreInt64(&m.value, value)
}

func (m *GaugeMetric) Inc(delta int64) {
	atomic.AddInt64(&m.value, delta)
}

func (m *GaugeMetric) Dec(delta int64) {
	atomic.AddInt64(&m.value, -delta)
}

func (m *GaugeMetric) Value() int64 {
	return atomic.LoadInt64(&m.value)
}

type HistogramMetric struct {
	ObservableMetric
	buckets    []float64
	bucketVals []int64
	sum        float64
	count      int64
	mu         sync.RWMutex
}

func NewHistogramMetric(name, desc string, unit MetricUnit, buckets []float64, labels map[string]string) *HistogramMetric {
	if labels == nil {
		labels = make(map[string]string)
	}
	if buckets == nil {
		buckets = []float64{0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5, 10}
	}
	return &HistogramMetric{
		ObservableMetric: ObservableMetric{
			Name:        name,
			Type:        TypeHistogram,
			Unit:        unit,
			Description: desc,
			Labels:      labels,
		},
		buckets:    buckets,
		bucketVals: make([]int64, len(buckets)+1),
	}
}

func (m *HistogramMetric) Observe(value float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	for i, bound := range m.buckets {
		if value <= bound {
			m.bucketVals[i]++
		}
	}
	m.bucketVals[len(m.buckets)]++
	m.sum += value
	m.count++
}

func (m *HistogramMetric) Snapshot() (sum float64, count int64, buckets []float64, bucketVals []int64) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	bucketValsCopy := make([]int64, len(m.bucketVals))
	copy(bucketValsCopy, m.bucketVals)
	return m.sum, m.count, m.buckets, bucketValsCopy
}

type SummaryMetric struct {
	ObservableMetric
	quantiles []float64
	values    []float64
	mu        sync.RWMutex
}

func NewSummaryMetric(name, desc string, unit MetricUnit, quantiles []float64, labels map[string]string) *SummaryMetric {
	if labels == nil {
		labels = make(map[string]string)
	}
	if quantiles == nil {
		quantiles = []float64{0.5, 0.9, 0.95, 0.99}
	}
	return &SummaryMetric{
		ObservableMetric: ObservableMetric{
			Name:        name,
			Type:        TypeSummary,
			Unit:        unit,
			Description: desc,
			Labels:      labels,
		},
		quantiles: quantiles,
		values:    make([]float64, 0, 1000),
	}
}

func (m *SummaryMetric) Observe(value float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.values = append(m.values, value)
	if len(m.values) > 10000 {
		m.values = m.values[len(m.values)-10000:]
	}
}

func (m *SummaryMetric) Snapshot() map[float64]float64 {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if len(m.values) == 0 {
		return nil
	}

	sorted := make([]float64, len(m.values))
	copy(sorted, m.values)
	sort.Float64s(sorted)

	result := make(map[float64]float64)
	for _, q := range m.quantiles {
		idx := int(float64(len(sorted)-1) * q)
		result[q] = sorted[idx]
	}
	return result
}

type EngineMetrics struct {
	EvaluationCount     *CounterMetric
	EvaluationError     *CounterMetric
	EvaluationDuration  *HistogramMetric
	RuleFiredCount      *CounterMetric
	RuleFiredDuration   *HistogramMetric
	AlertCreated        *CounterMetric
	AlertResolved       *CounterMetric
	NotificationSent    *CounterMetric
	NotificationFailed  *CounterMetric
	NotificationLatency *HistogramMetric
	ActiveAlerts        *GaugeMetric
	RulesTotal          *GaugeMetric
	RulesEnabled        *GaugeMetric
	RulesDisabled       *GaugeMetric
	SilencesActive      *GaugeMetric
	ExpressionParseFail *CounterMetric
	BackpressureEvents  *CounterMetric
}

func NewEngineMetrics() *EngineMetrics {
	return &EngineMetrics{
		EvaluationCount: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_evaluations_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total number of alert rule evaluations",
			},
		},
		EvaluationError: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_evaluation_errors_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total number of alert rule evaluation errors",
			},
		},
		EvaluationDuration: NewHistogramMetric(
			"alert_evaluation_duration_seconds",
			"Time spent evaluating alert rules",
			UnitSeconds,
			[]float64{0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5},
			nil,
		),
		RuleFiredCount: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_rule_firings_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total number of times alert rules have fired",
			},
		},
		RuleFiredDuration: NewHistogramMetric(
			"alert_firing_duration_seconds",
			"Duration an alert has been firing",
			UnitSeconds,
			[]float64{1, 10, 60, 300, 600, 3600},
			nil,
		),
		AlertCreated: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alerts_created_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total number of alerts created",
			},
		},
		AlertResolved: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alerts_resolved_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total number of alerts resolved",
			},
		},
		NotificationSent: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_notifications_sent_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total number of alert notifications sent",
			},
		},
		NotificationFailed: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_notifications_failed_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total number of alert notification failures",
			},
		},
		NotificationLatency: NewHistogramMetric(
			"alert_notification_latency_seconds",
			"Time taken to send alert notifications",
			UnitSeconds,
			[]float64{0.01, 0.05, 0.1, 0.5, 1, 5, 10},
			nil,
		),
		ActiveAlerts: &GaugeMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alerts_active",
				Type:        TypeGauge,
				Unit:        UnitCount,
				Description: "Number of currently active alerts",
			},
		},
		RulesTotal: &GaugeMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_rules_total",
				Type:        TypeGauge,
				Unit:        UnitCount,
				Description: "Total number of configured alert rules",
			},
		},
		RulesEnabled: &GaugeMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_rules_enabled",
				Type:        TypeGauge,
				Unit:        UnitCount,
				Description: "Number of enabled alert rules",
			},
		},
		RulesDisabled: &GaugeMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_rules_disabled",
				Type:        TypeGauge,
				Unit:        UnitCount,
				Description: "Number of disabled alert rules",
			},
		},
		SilencesActive: &GaugeMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_silences_active",
				Type:        TypeGauge,
				Unit:        UnitCount,
				Description: "Number of currently active silences",
			},
		},
		ExpressionParseFail: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_expression_parse_failures_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total number of expression parsing failures",
			},
		},
		BackpressureEvents: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_backpressure_events_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total number of backpressure events",
			},
		},
	}
}

type RuleMetrics struct {
	ruleID           string
	ruleName         string
	EvaluationCount  *CounterMetric
	EvaluationError  *CounterMetric
	EvaluationLatency *HistogramMetric
	FiringCount      *CounterMetric
	CurrentStatus    *GaugeMetric
}

func NewRuleMetrics(ruleID, ruleName string) *RuleMetrics {
	labels := map[string]string{
		"rule_id":   ruleID,
		"rule_name": ruleName,
	}

	return &RuleMetrics{
		ruleID:   ruleID,
		ruleName: ruleName,
		EvaluationCount: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_rule_evaluations_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total evaluations for this rule",
				Labels:      labels,
			},
		},
		EvaluationError: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_rule_evaluation_errors_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total evaluation errors for this rule",
				Labels:      labels,
			},
		},
		EvaluationLatency: NewHistogramMetric(
			"alert_rule_evaluation_latency_seconds",
			"Evaluation latency for this rule",
			UnitSeconds,
			[]float64{0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1},
			labels,
		),
		FiringCount: &CounterMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_rule_firings_total",
				Type:        TypeCounter,
				Unit:        UnitCount,
				Description: "Total firing events for this rule",
				Labels:      labels,
			},
		},
		CurrentStatus: &GaugeMetric{
			ObservableMetric: ObservableMetric{
				Name:        "alert_rule_status",
				Type:        TypeGauge,
				Unit:        UnitCount,
				Description: "Current status of the rule (0=inactive, 1=firing)",
				Labels:      labels,
			},
		},
	}
}

type MetricsRegistry struct {
	engineMetrics *EngineMetrics
	ruleMetrics   map[string]*RuleMetrics
	mu            sync.RWMutex
}

func NewMetricsRegistry() *MetricsRegistry {
	return &MetricsRegistry{
		engineMetrics: NewEngineMetrics(),
		ruleMetrics:   make(map[string]*RuleMetrics),
	}
}

func (r *MetricsRegistry) Engine() *EngineMetrics {
	return r.engineMetrics
}

func (r *MetricsRegistry) ForRule(ruleID, ruleName string) *RuleMetrics {
	r.mu.RLock()
	if rm, exists := r.ruleMetrics[ruleID]; exists {
		r.mu.RUnlock()
		return rm
	}
	r.mu.RUnlock()

	r.mu.Lock()
	defer r.mu.Unlock()

	if rm, exists := r.ruleMetrics[ruleID]; exists {
		return rm
	}

	rm := NewRuleMetrics(ruleID, ruleName)
	r.ruleMetrics[ruleID] = rm
	return rm
}

func (r *MetricsRegistry) RemoveRule(ruleID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.ruleMetrics, ruleID)
}

func (r *MetricsRegistry) GetAllRuleMetrics() map[string]*RuleMetrics {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make(map[string]*RuleMetrics, len(r.ruleMetrics))
	for k, v := range r.ruleMetrics {
		result[k] = v
	}
	return result
}

type PrometheusExporter struct {
	registry *MetricsRegistry
}

func NewPrometheusExporter(registry *MetricsRegistry) *PrometheusExporter {
	return &PrometheusExporter{registry: registry}
}

func (e *PrometheusExporter) formatLabels(labels map[string]string) string {
	if len(labels) == 0 {
		return ""
	}

	keys := make([]string, 0, len(labels))
	for k := range labels {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	parts := make([]string, 0, len(keys))
	for _, k := range keys {
		parts = append(parts, fmt.Sprintf("%s=\"%s\"", k, escapeValue(labels[k])))
	}

	return "{" + strings.Join(parts, ",") + "}"
}

func escapeValue(v string) string {
	v = strings.ReplaceAll(v, "\\", "\\\\")
	v = strings.ReplaceAll(v, "\"", "\\\"")
	v = strings.ReplaceAll(v, "\n", "\\n")
	return v
}

func (e *PrometheusExporter) formatCounter(m *CounterMetric) string {
	var sb strings.Builder

	sb.WriteString(fmt.Sprintf("# HELP %s %s\n", m.Name, m.Description))
	sb.WriteString(fmt.Sprintf("# TYPE %s %s\n", m.Name, m.Type))
	sb.WriteString(fmt.Sprintf("%s%s %d\n", m.Name, e.formatLabels(m.Labels), m.Value()))

	return sb.String()
}

func (e *PrometheusExporter) formatGauge(m *GaugeMetric) string {
	var sb strings.Builder

	sb.WriteString(fmt.Sprintf("# HELP %s %s\n", m.Name, m.Description))
	sb.WriteString(fmt.Sprintf("# TYPE %s %s\n", m.Name, m.Type))
	sb.WriteString(fmt.Sprintf("%s%s %d\n", m.Name, e.formatLabels(m.Labels), m.Value()))

	return sb.String()
}

func (e *PrometheusExporter) formatHistogram(m *HistogramMetric) string {
	var sb strings.Builder

	sum, count, buckets, bucketVals := m.Snapshot()

	sb.WriteString(fmt.Sprintf("# HELP %s %s\n", m.Name, m.Description))
	sb.WriteString(fmt.Sprintf("# TYPE %s %s\n", m.Name, m.Type))

	labels := e.formatLabels(m.Labels)

	for i, bound := range buckets {
		bucketLabels := e.formatLabels(mergeLabels(m.Labels, map[string]string{"le": fmt.Sprintf("%g", bound)}))
		sb.WriteString(fmt.Sprintf("%s_bucket%s %d\n", m.Name, bucketLabels, bucketVals[i]))
	}

	sb.WriteString(fmt.Sprintf("%s_bucket%s %d\n", m.Name, e.formatLabels(mergeLabels(m.Labels, map[string]string{"le": "+Inf"})), count))
	sb.WriteString(fmt.Sprintf("%s_sum%s %g\n", m.Name, labels, sum))
	sb.WriteString(fmt.Sprintf("%s_count%s %d\n", m.Name, labels, count))

	return sb.String()
}

func mergeLabels(a, b map[string]string) map[string]string {
	result := make(map[string]string, len(a)+len(b))
	for k, v := range a {
		result[k] = v
	}
	for k, v := range b {
		result[k] = v
	}
	return result
}

func (e *PrometheusExporter) Export() string {
	var sb strings.Builder

	em := e.registry.Engine()

	sb.WriteString(e.formatCounter(em.EvaluationCount))
	sb.WriteString(e.formatCounter(em.EvaluationError))
	sb.WriteString(e.formatHistogram(em.EvaluationDuration))
	sb.WriteString(e.formatCounter(em.RuleFiredCount))
	sb.WriteString(e.formatHistogram(em.RuleFiredDuration))
	sb.WriteString(e.formatCounter(em.AlertCreated))
	sb.WriteString(e.formatCounter(em.AlertResolved))
	sb.WriteString(e.formatCounter(em.NotificationSent))
	sb.WriteString(e.formatCounter(em.NotificationFailed))
	sb.WriteString(e.formatHistogram(em.NotificationLatency))
	sb.WriteString(e.formatGauge(em.ActiveAlerts))
	sb.WriteString(e.formatGauge(em.RulesTotal))
	sb.WriteString(e.formatGauge(em.RulesEnabled))
	sb.WriteString(e.formatGauge(em.RulesDisabled))
	sb.WriteString(e.formatGauge(em.SilencesActive))
	sb.WriteString(e.formatCounter(em.ExpressionParseFail))
	sb.WriteString(e.formatCounter(em.BackpressureEvents))

	for _, rm := range e.registry.GetAllRuleMetrics() {
		sb.WriteString(e.formatCounter(rm.EvaluationCount))
		sb.WriteString(e.formatCounter(rm.EvaluationError))
		sb.WriteString(e.formatHistogram(rm.EvaluationLatency))
		sb.WriteString(e.formatCounter(rm.FiringCount))
		sb.WriteString(e.formatGauge(rm.CurrentStatus))
	}

	return sb.String()
}

type ObservableAlertEngine struct {
	*AlertEngine
	metrics    *MetricsRegistry
	exporter   *PrometheusExporter
	evalTimers map[string]time.Time
	timersMu   sync.Mutex
}

func NewObservableAlertEngine(config EngineConfig, provider MetricProvider) *ObservableAlertEngine {
	base := NewAlertEngine(config, provider)
	registry := NewMetricsRegistry()
	return &ObservableAlertEngine{
		AlertEngine: base,
		metrics:     registry,
		exporter:    NewPrometheusExporter(registry),
		evalTimers:  make(map[string]time.Time),
	}
}

func (e *ObservableAlertEngine) evaluateRuleWithMetrics(rule *models.AlertRule) (*models.AlertEvaluationResult, error) {
	start := time.Now()
	rm := e.metrics.ForRule(rule.ID, rule.Name)

	rm.EvaluationCount.Inc(1)
	e.metrics.Engine().EvaluationCount.Inc(1)

	result, err := e.AlertEngine.evaluateRule(rule)

	duration := time.Since(start).Seconds()
	rm.EvaluationLatency.Observe(duration)
	e.metrics.Engine().EvaluationDuration.Observe(duration)

	if err != nil {
		rm.EvaluationError.Inc(1)
		e.metrics.Engine().EvaluationError.Inc(1)
		return nil, err
	}

	if len(result.Matches) > 0 {
		rm.FiringCount.Inc(int64(len(result.Matches)))
		rm.CurrentStatus.Set(1)
		e.metrics.Engine().RuleFiredCount.Inc(int64(len(result.Matches)))
	} else {
		rm.CurrentStatus.Set(0)
	}

	return result, nil
}

func (e *ObservableAlertEngine) processEvaluationResultWithMetrics(rule *models.AlertRule, result *models.AlertEvaluationResult) {
	preActiveCount := int64(len(e.GetActiveAlerts()))

	e.AlertEngine.processEvaluationResult(rule, result)

	postActiveCount := int64(len(e.GetActiveAlerts()))
	e.metrics.Engine().ActiveAlerts.Set(postActiveCount)

	if postActiveCount > preActiveCount {
		e.metrics.Engine().AlertCreated.Inc(postActiveCount - preActiveCount)
	} else if postActiveCount < preActiveCount {
		e.metrics.Engine().AlertResolved.Inc(preActiveCount - postActiveCount)
	}

	e.updateRuleCounts()
	e.updateSilenceCounts()
}

func (e *ObservableAlertEngine) updateRuleCounts() {
	e.mu.RLock()
	defer e.mu.RUnlock()

	total := int64(len(e.rules))
	enabled := int64(0)
	for _, r := range e.rules {
		if r.Enabled {
			enabled++
		}
	}

	e.metrics.Engine().RulesTotal.Set(total)
	e.metrics.Engine().RulesEnabled.Set(enabled)
	e.metrics.Engine().RulesDisabled.Set(total - enabled)
}

func (e *ObservableAlertEngine) updateSilenceCounts() {
	e.mu.RLock()
	defer e.mu.RUnlock()

	now := time.Now()
	active := int64(0)
	for _, s := range e.silences {
		if !now.Before(s.StartsAt) && !now.After(s.EndsAt) {
			active++
		}
	}
	e.metrics.Engine().SilencesActive.Set(active)
}

func (e *ObservableAlertEngine) sendNotificationWithMetrics(alert *models.Alert, channelIDs []string) {
	start := time.Now()

	e.AlertEngine.sendNotification(alert, channelIDs)

	latency := time.Since(start).Seconds()
	e.metrics.Engine().NotificationLatency.Observe(latency)
	e.metrics.Engine().NotificationSent.Inc(int64(len(channelIDs)))
}

func (e *ObservableAlertEngine) evaluateAllRulesWithMetrics() {
	e.mu.RLock()
	rules := make([]*models.AlertRule, 0, len(e.rules))
	for _, rule := range e.rules {
		if rule.Enabled {
			rules = append(rules, rule)
		}
	}
	e.mu.RUnlock()

	for _, rule := range rules {
		result, err := e.evaluateRuleWithMetrics(rule)
		if err != nil {
			continue
		}
		e.processEvaluationResultWithMetrics(rule, result)
	}
}

func (e *ObservableAlertEngine) Start() {
	e.wg.Add(1)
	go func() {
		defer e.wg.Done()

		ticker := time.NewTicker(e.evalInterval)
		defer ticker.Stop()

		for {
			select {
			case <-e.ctx.Done():
				return
			case <-ticker.C:
				e.evaluateAllRulesWithMetrics()
			}
		}
	}()
}

func (e *ObservableAlertEngine) AddRule(rule *models.AlertRule) {
	e.AlertEngine.AddRule(rule)
	e.updateRuleCounts()
}

func (e *ObservableAlertEngine) UpdateRule(rule *models.AlertRule) error {
	err := e.AlertEngine.UpdateRule(rule)
	if err == nil {
		e.updateRuleCounts()
	}
	return err
}

func (e *ObservableAlertEngine) DeleteRule(ruleID string) error {
	err := e.AlertEngine.DeleteRule(ruleID)
	if err == nil {
		e.metrics.RemoveRule(ruleID)
		e.updateRuleCounts()
	}
	return err
}

func (e *ObservableAlertEngine) AddSilence(silence *models.AlertSilence) {
	e.AlertEngine.AddSilence(silence)
	e.updateSilenceCounts()
}

func (e *ObservableAlertEngine) GetMetrics() *MetricsRegistry {
	return e.metrics
}

func (e *ObservableAlertEngine) ExportMetricsPrometheus() string {
	return e.exporter.Export()
}

func (e *ObservableAlertEngine) GetMetricsJSON() map[string]interface{} {
	result := make(map[string]interface{})

	em := e.metrics.Engine()
	result["engine"] = map[string]interface{}{
		"evaluations_total":        em.EvaluationCount.Value(),
		"evaluation_errors_total":  em.EvaluationError.Value(),
		"rule_firings_total":       em.RuleFiredCount.Value(),
		"alerts_created_total":     em.AlertCreated.Value(),
		"alerts_resolved_total":    em.AlertResolved.Value(),
		"notifications_sent_total": em.NotificationSent.Value(),
		"notifications_failed_total": em.NotificationFailed.Value(),
		"active_alerts":            em.ActiveAlerts.Value(),
		"rules_total":              em.RulesTotal.Value(),
		"rules_enabled":            em.RulesEnabled.Value(),
		"rules_disabled":           em.RulesDisabled.Value(),
		"active_silences":          em.SilencesActive.Value(),
	}

	rules := make(map[string]interface{})
	for ruleID, rm := range e.metrics.GetAllRuleMetrics() {
		rules[ruleID] = map[string]interface{}{
			"rule_name":            rm.ruleName,
			"evaluations_total":    rm.EvaluationCount.Value(),
			"evaluation_errors":    rm.EvaluationError.Value(),
			"firings_total":        rm.FiringCount.Value(),
			"current_status":       rm.CurrentStatus.Value(),
		}
	}
	result["rules"] = rules

	return result
}
