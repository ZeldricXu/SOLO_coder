package builders

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math/rand"
	"time"

	"session130/pkg/models"
)

type ConfigBuilder struct {
	namespace  string
	version    int
	parameters map[string]interface{}
	enabled    bool
}

func NewConfigBuilder() *ConfigBuilder {
	return &ConfigBuilder{
		namespace:  "test_namespace",
		version:    1,
		parameters: make(map[string]interface{}),
		enabled:    true,
	}
}

func (b *ConfigBuilder) WithNamespace(namespace string) *ConfigBuilder {
	b.namespace = namespace
	return b
}

func (b *ConfigBuilder) WithVersion(version int) *ConfigBuilder {
	b.version = version
	return b
}

func (b *ConfigBuilder) WithParameters(params map[string]interface{}) *ConfigBuilder {
	b.parameters = params
	return b
}

func (b *ConfigBuilder) WithParameter(key string, value interface{}) *ConfigBuilder {
	b.parameters[key] = value
	return b
}

func (b *ConfigBuilder) WithEnabled(enabled bool) *ConfigBuilder {
	b.enabled = enabled
	return b
}

func (b *ConfigBuilder) Build() *models.Config {
	return &models.Config{
		ConfigID:   generateConfigID(b.namespace, b.version),
		Namespace:  b.namespace,
		Version:    b.version,
		Parameters: b.parameters,
		Enabled:    b.enabled,
		AppliedAt:  time.Now(),
	}
}

func (b *ConfigBuilder) BuildList(count int) []*models.Config {
	configs := make([]*models.Config, count)
	for i := 0; i < count; i++ {
		builder := *b
		builder.version = i + 1
		configs[i] = builder.Build()
	}
	return configs
}

type AlertRuleBuilder struct {
	ruleID     string
	name       string
	metric     string
	condition  string
	threshold  float64
	duration   time.Duration
	enabled    bool
	labels     map[string]string
	notifyChan []string
}

func NewAlertRuleBuilder() *AlertRuleBuilder {
	return &AlertRuleBuilder{
		ruleID:     fmt.Sprintf("rule_%d", rand.Int63()),
		name:       "Test Alert Rule",
		metric:     "test_metric",
		condition:  "gt",
		threshold:  0.5,
		duration:   5 * time.Minute,
		enabled:    true,
		labels:     make(map[string]string),
		notifyChan: []string{"log"},
	}
}

func (b *AlertRuleBuilder) WithRuleID(ruleID string) *AlertRuleBuilder {
	b.ruleID = ruleID
	return b
}

func (b *AlertRuleBuilder) WithName(name string) *AlertRuleBuilder {
	b.name = name
	return b
}

func (b *AlertRuleBuilder) WithMetric(metric string) *AlertRuleBuilder {
	b.metric = metric
	return b
}

func (b *AlertRuleBuilder) WithCondition(condition string) *AlertRuleBuilder {
	b.condition = condition
	return b
}

func (b *AlertRuleBuilder) WithThreshold(threshold float64) *AlertRuleBuilder {
	b.threshold = threshold
	return b
}

func (b *AlertRuleBuilder) WithDuration(duration time.Duration) *AlertRuleBuilder {
	b.duration = duration
	return b
}

func (b *AlertRuleBuilder) WithEnabled(enabled bool) *AlertRuleBuilder {
	b.enabled = enabled
	return b
}

func (b *AlertRuleBuilder) WithLabels(labels map[string]string) *AlertRuleBuilder {
	b.labels = labels
	return b
}

func (b *AlertRuleBuilder) WithLabel(key, value string) *AlertRuleBuilder {
	b.labels[key] = value
	return b
}

func (b *AlertRuleBuilder) WithNotifyChannel(ch string) *AlertRuleBuilder {
	b.notifyChan = append(b.notifyChan, ch)
	return b
}

func (b *AlertRuleBuilder) Build() *models.AlertRule {
	return &models.AlertRule{
		RuleID:     b.ruleID,
		Name:       b.name,
		Metric:     b.metric,
		Condition:  b.condition,
		Threshold:  b.threshold,
		Duration:   b.duration,
		Enabled:    b.enabled,
		Labels:     b.labels,
		NotifyChan: b.notifyChan,
	}
}

func (b *AlertRuleBuilder) BuildList(count int) []*models.AlertRule {
	rules := make([]*models.AlertRule, count)
	for i := 0; i < count; i++ {
		builder := *b
		builder.ruleID = fmt.Sprintf("rule_%d_%d", i, rand.Int63())
		builder.name = fmt.Sprintf("Rule %d", i+1)
		rules[i] = builder.Build()
	}
	return rules
}

type MetricsSnapshotBuilder struct {
	snapshotID string
	metrics    map[string]float64
	dimensions map[string]string
}

func NewMetricsSnapshotBuilder() *MetricsSnapshotBuilder {
	return &MetricsSnapshotBuilder{
		snapshotID: fmt.Sprintf("snap_%d", rand.Int63()),
		metrics: map[string]float64{
			"throughput": 1000.0,
			"latency_p50": 50.0,
			"latency_p99": 250.0,
			"error_rate": 0.001,
		},
		dimensions: map[string]string{
			"host":   "test-host-1",
			"region": "cn-east",
		},
	}
}

func (b *MetricsSnapshotBuilder) WithSnapshotID(id string) *MetricsSnapshotBuilder {
	b.snapshotID = id
	return b
}

func (b *MetricsSnapshotBuilder) WithMetric(name string, value float64) *MetricsSnapshotBuilder {
	b.metrics[name] = value
	return b
}

func (b *MetricsSnapshotBuilder) WithMetrics(metrics map[string]float64) *MetricsSnapshotBuilder {
	b.metrics = metrics
	return b
}

func (b *MetricsSnapshotBuilder) WithDimension(key, value string) *MetricsSnapshotBuilder {
	b.dimensions[key] = value
	return b
}

func (b *MetricsSnapshotBuilder) WithDimensions(dimensions map[string]string) *MetricsSnapshotBuilder {
	b.dimensions = dimensions
	return b
}

func (b *MetricsSnapshotBuilder) Build() *models.MetricsSnapshot {
	return &models.MetricsSnapshot{
		SnapshotID: b.snapshotID,
		Timestamp:  time.Now(),
		Metrics:    b.metrics,
		Dimensions: b.dimensions,
	}
}

func (b *MetricsSnapshotBuilder) BuildList(count int, baseMetrics map[string]float64) []*models.MetricsSnapshot {
	snapshots := make([]*models.MetricsSnapshot, count)
	for i := 0; i < count; i++ {
		builder := *b
		builder.snapshotID = fmt.Sprintf("snap_%d", i)
		metrics := make(map[string]float64)
		for k, v := range baseMetrics {
			metrics[k] = v + float64(i)*0.1
		}
		builder.metrics = metrics
		snapshots[i] = builder.Build()
	}
	return snapshots
}

type APIRequestBuilder struct {
	traceID    string
	namespace  string
	entityType string
	payload    map[string]interface{}
}

func NewAPIRequestBuilder() *APIRequestBuilder {
	return &APIRequestBuilder{
		traceID:    fmt.Sprintf("trace_%d", rand.Int63()),
		namespace:  "default",
		entityType: "task",
		payload:    make(map[string]interface{}),
	}
}

func (b *APIRequestBuilder) WithTraceID(traceID string) *APIRequestBuilder {
	b.traceID = traceID
	return b
}

func (b *APIRequestBuilder) WithNamespace(namespace string) *APIRequestBuilder {
	b.namespace = namespace
	return b
}

func (b *APIRequestBuilder) WithEntityType(entityType string) *APIRequestBuilder {
	b.entityType = entityType
	return b
}

func (b *APIRequestBuilder) WithPayload(payload map[string]interface{}) *APIRequestBuilder {
	b.payload = payload
	return b
}

func (b *APIRequestBuilder) WithPayloadField(key string, value interface{}) *APIRequestBuilder {
	b.payload[key] = value
	return b
}

func (b *APIRequestBuilder) Build() *models.APIRequest {
	return &models.APIRequest{
		TraceID:    b.traceID,
		Namespace:  b.namespace,
		EntityType: b.entityType,
		Payload:    b.payload,
	}
}

type SpanBuilder struct {
	traceID   string
	spanID    string
	parentID  string
	service   string
	operation string
	status    string
	attributes map[string]interface{}
}

func NewSpanBuilder() *SpanBuilder {
	return &SpanBuilder{
		traceID:    fmt.Sprintf("trace_%d", rand.Int63()),
		spanID:     fmt.Sprintf("span_%d", rand.Int63()),
		parentID:   "",
		service:    "test-service",
		operation:  "test-operation",
		status:     "ok",
		attributes: make(map[string]interface{}),
	}
}

func (b *SpanBuilder) WithTraceID(traceID string) *SpanBuilder {
	b.traceID = traceID
	return b
}

func (b *SpanBuilder) WithSpanID(spanID string) *SpanBuilder {
	b.spanID = spanID
	return b
}

func (b *SpanBuilder) WithParentID(parentID string) *SpanBuilder {
	b.parentID = parentID
	return b
}

func (b *SpanBuilder) WithService(service string) *SpanBuilder {
	b.service = service
	return b
}

func (b *SpanBuilder) WithOperation(operation string) *SpanBuilder {
	b.operation = operation
	return b
}

func (b *SpanBuilder) WithStatus(status string) *SpanBuilder {
	b.status = status
	return b
}

func (b *SpanBuilder) WithAttribute(key string, value interface{}) *SpanBuilder {
	b.attributes[key] = value
	return b
}

func (b *SpanBuilder) Build() *models.Span {
	return &models.Span{
		TraceID:    b.traceID,
		SpanID:     b.spanID,
		ParentID:   b.parentID,
		Service:    b.service,
		Operation:  b.operation,
		StartTime:  time.Now(),
		EndTime:    time.Now().Add(100 * time.Millisecond),
		Status:     b.status,
		Attributes: b.attributes,
	}
}

func generateConfigID(namespace string, version int) string {
	h := sha256.New()
	h.Write([]byte(fmt.Sprintf("%s:%d:%d", namespace, version, time.Now().UnixNano())))
	return "cfg_" + hex.EncodeToString(h.Sum(nil))[:8]
}
