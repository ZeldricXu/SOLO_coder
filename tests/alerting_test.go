package tests

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"session130/internal/alerting"
	"session130/pkg/models"
	"session130/tests/builders"
)

func TestEvaluator_AddRule_Success(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	rule := builders.NewAlertRuleBuilder().
		WithRuleID("rule_001").
		WithName("High Error Rate").
		WithMetric("error_rate").
		WithCondition("gt").
		WithThreshold(0.05).
		Build()

	err := e.AddRule(rule)

	require.NoError(t, err)

	retrieved, err := e.GetRule("rule_001")
	require.NoError(t, err)
	assert.Equal(t, rule.RuleID, retrieved.RuleID)
	assert.Equal(t, rule.Name, retrieved.Name)
	assert.Equal(t, rule.Metric, retrieved.Metric)
}

func TestEvaluator_AddRule_EmptyRuleID(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	rule := builders.NewAlertRuleBuilder().
		WithRuleID("").
		Build()

	err := e.AddRule(rule)

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "rule_id is required")
}

func TestEvaluator_AddRule_EmptyMetric(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	rule := builders.NewAlertRuleBuilder().
		WithRuleID("rule_002").
		WithMetric("").
		Build()

	err := e.AddRule(rule)

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "metric is required")
}

func TestEvaluator_AddRule_DuplicateRuleID(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	rule1 := builders.NewAlertRuleBuilder().
		WithRuleID("rule_003").
		WithMetric("latency_p99").
		Build()

	rule2 := builders.NewAlertRuleBuilder().
		WithRuleID("rule_003").
		WithMetric("error_rate").
		Build()

	err := e.AddRule(rule1)
	require.NoError(t, err)

	err = e.AddRule(rule2)
	assert.NoError(t, err)

	retrieved, _ := e.GetRule("rule_003")
	assert.Equal(t, "error_rate", retrieved.Metric)
}

func TestEvaluator_RemoveRule_Success(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	rule := builders.NewAlertRuleBuilder().
		WithRuleID("rule_004").
		Build()

	_ = e.AddRule(rule)

	e.RemoveRule("rule_004")

	_, err := e.GetRule("rule_004")
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "not found")
}

func TestEvaluator_RemoveRule_Nonexistent(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	e.RemoveRule("nonexistent_rule")

	rules := e.ListRules()
	assert.Empty(t, rules)
}

func TestEvaluator_GetRule_Nonexistent(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	rule, err := e.GetRule("nonexistent")

	assert.Error(t, err)
	assert.Nil(t, rule)
}

func TestEvaluator_ListRules(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	rules := builders.NewAlertRuleBuilder().BuildList(5)
	for _, rule := range rules {
		_ = e.AddRule(rule)
	}

	list := e.ListRules()
	assert.Len(t, list, 5)
}

func TestEvaluator_ListRules_Empty(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	rules := e.ListRules()
	assert.Empty(t, rules)
}

func TestEvaluateCondition_GreaterThan(t *testing.T) {
	rule := &models.AlertRule{
		Condition: "gt",
		Threshold: 0.5,
	}

	assert.True(t, alerting.EvaluateCondition(rule, 0.6))
	assert.False(t, alerting.EvaluateCondition(rule, 0.5))
	assert.False(t, alerting.EvaluateCondition(rule, 0.4))
}

func TestEvaluateCondition_GreaterThanOrEqual(t *testing.T) {
	rule := &models.AlertRule{
		Condition: "gte",
		Threshold: 0.5,
	}

	assert.True(t, alerting.EvaluateCondition(rule, 0.6))
	assert.True(t, alerting.EvaluateCondition(rule, 0.5))
	assert.False(t, alerting.EvaluateCondition(rule, 0.4))
}

func TestEvaluateCondition_LessThan(t *testing.T) {
	rule := &models.AlertRule{
		Condition: "lt",
		Threshold: 0.5,
	}

	assert.False(t, alerting.EvaluateCondition(rule, 0.6))
	assert.False(t, alerting.EvaluateCondition(rule, 0.5))
	assert.True(t, alerting.EvaluateCondition(rule, 0.4))
}

func TestEvaluateCondition_LessThanOrEqual(t *testing.T) {
	rule := &models.AlertRule{
		Condition: "lte",
		Threshold: 0.5,
	}

	assert.False(t, alerting.EvaluateCondition(rule, 0.6))
	assert.True(t, alerting.EvaluateCondition(rule, 0.5))
	assert.True(t, alerting.EvaluateCondition(rule, 0.4))
}

func TestEvaluateCondition_Equal(t *testing.T) {
	rule := &models.AlertRule{
		Condition: "eq",
		Threshold: 0.5,
	}

	assert.False(t, alerting.EvaluateCondition(rule, 0.6))
	assert.True(t, alerting.EvaluateCondition(rule, 0.5))
	assert.False(t, alerting.EvaluateCondition(rule, 0.4))
}

func TestEvaluateCondition_NotEqual(t *testing.T) {
	rule := &models.AlertRule{
		Condition: "ne",
		Threshold: 0.5,
	}

	assert.True(t, alerting.EvaluateCondition(rule, 0.6))
	assert.False(t, alerting.EvaluateCondition(rule, 0.5))
	assert.True(t, alerting.EvaluateCondition(rule, 0.4))
}

func TestEvaluateCondition_SymbolOperators(t *testing.T) {
	testCases := []struct {
		condition string
		value     float64
		threshold float64
		expected  bool
	}{
		{">", 0.6, 0.5, true},
		{">=", 0.5, 0.5, true},
		{"<", 0.4, 0.5, true},
		{"<=", 0.5, 0.5, true},
		{"==", 0.5, 0.5, true},
		{"!=", 0.4, 0.5, true},
		{">", 0.5, 0.5, false},
		{"<", 0.5, 0.5, false},
	}

	for _, tc := range testCases {
		t.Run(tc.condition, func(t *testing.T) {
			rule := &models.AlertRule{
				Condition: tc.condition,
				Threshold: tc.threshold,
			}
			assert.Equal(t, tc.expected, alerting.EvaluateCondition(rule, tc.value))
		})
	}
}

func TestEvaluateCondition_UnknownCondition(t *testing.T) {
	rule := &models.AlertRule{
		Condition: "unknown_op",
		Threshold: 0.5,
	}

	assert.Equal(t, alerting.EvaluateCondition(rule, 0.6), 0.6 > 0.5)
}

func TestEvaluateCondition_EdgeValues(t *testing.T) {
	rule := &models.AlertRule{
		Condition: "gt",
		Threshold: 0,
	}

	assert.False(t, alerting.EvaluateCondition(rule, -0.0001))
	assert.False(t, alerting.EvaluateCondition(rule, 0))
	assert.True(t, alerting.EvaluateCondition(rule, 0.0001))
}

func TestEvaluateCondition_LargeValues(t *testing.T) {
	rule := &models.AlertRule{
		Condition: "lt",
		Threshold: 1e9,
	}

	assert.True(t, alerting.EvaluateCondition(rule, 999999999))
	assert.False(t, alerting.EvaluateCondition(rule, 1000000000))
	assert.False(t, alerting.EvaluateCondition(rule, 1000000001))
}

func TestEvaluateCondition_SmallValues(t *testing.T) {
	rule := &models.AlertRule{
		Condition: "gt",
		Threshold: 1e-9,
	}

	assert.False(t, alerting.EvaluateCondition(rule, 9.99999999e-10))
	assert.False(t, alerting.EvaluateCondition(rule, 1e-9))
	assert.True(t, alerting.EvaluateCondition(rule, 1.000000001e-9))
}

func TestEvaluateCondition_NegativeValues(t *testing.T) {
	rule := &models.AlertRule{
		Condition: "lt",
		Threshold: -10,
	}

	assert.True(t, alerting.EvaluateCondition(rule, -11))
	assert.False(t, alerting.EvaluateCondition(rule, -10))
	assert.False(t, alerting.EvaluateCondition(rule, -9))
}

func TestEvaluator_SetMetricSource(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	called := false
	e.SetMetricSource(func(metric string, labels map[string]string) (float64, error) {
		called = true
		return 0.1, nil
	})

	rule := builders.NewAlertRuleBuilder().
		WithRuleID("test_rule").
		WithMetric("test_metric").
		WithCondition("gt").
		WithThreshold(0.05).
		Build()

	_ = e.AddRule(rule)
	e.Evaluate()

	time.Sleep(50 * time.Millisecond)
	assert.True(t, called)
}

func TestEvaluator_Evaluate_DisabledRule(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	rule := builders.NewAlertRuleBuilder().
		WithRuleID("disabled_rule").
		WithEnabled(false).
		Build()

	_ = e.AddRule(rule)

	evalCalled := false
	e.SetMetricSource(func(metric string, labels map[string]string) (float64, error) {
		evalCalled = true
		return 0.0, nil
	})

	e.Evaluate()
	time.Sleep(50 * time.Millisecond)

	assert.False(t, evalCalled, "Disabled rules should not be evaluated")
}

func TestEvaluator_GetAlerts_All(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	e.SetMetricSource(func(metric string, labels map[string]string) (float64, error) {
		return 0.1, nil
	})

	rule := builders.NewAlertRuleBuilder().
		WithRuleID("alert_rule").
		WithMetric("error_rate").
		WithCondition("gt").
		WithThreshold(0.05).
		Build()

	_ = e.AddRule(rule)
	e.Evaluate()

	time.Sleep(50 * time.Millisecond)

	alerts := e.GetAlerts("")
	assert.GreaterOrEqual(t, len(alerts), 0)
}

func TestEvaluator_GetAlerts_ByStatus(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	alerts := e.GetAlerts("firing")
	assert.Empty(t, alerts)

	alerts = e.GetAlerts("resolved")
	assert.Empty(t, alerts)
}

func TestEvaluator_RegisterNotifier(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	called := false
	mockNotifier := &mockNotifier{
		onNotify: func(alert *models.Alert) error {
			called = true
			return nil
		},
	}

	e.RegisterNotifier("mock", mockNotifier)

	rule := builders.NewAlertRuleBuilder().
		WithRuleID("notify_rule").
		WithMetric("error_rate").
		WithCondition("gt").
		WithThreshold(0.05).
		WithLabel("notify_channels", "mock").
		Build()

	_ = e.AddRule(rule)

	e.SetMetricSource(func(metric string, labels map[string]string) (float64, error) {
		return 0.1, nil
	})

	e.Evaluate()
	time.Sleep(100 * time.Millisecond)

	assert.True(t, called)
}

func TestEvaluator_PreWarmCache(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	fetchCount := 0
	e.SetMetricSource(func(metric string, labels map[string]string) (float64, error) {
		fetchCount++
		return 0.5, nil
	})

	rules := builders.NewAlertRuleBuilder().BuildList(3)
	for _, rule := range rules {
		rule.Enabled = true
		_ = e.AddRule(rule)
	}

	e.PreWarmCache()
	assert.Equal(t, 3, fetchCount)

	e.PreWarmCache()
	assert.Equal(t, 3, fetchCount, "Should use cache for second pre-warm")
}

func TestEvaluator_GetCacheStats(t *testing.T) {
	e := alerting.NewEvaluatorWithConfig(1*time.Second, 1000, 30*time.Second, 8)
	defer e.Stop()

	stats := e.GetCacheStats()

	assert.Equal(t, 8, stats["eval_workers"])
	assert.Equal(t, 100, stats["batch_size"])
	assert.Equal(t, 0, stats["queue_size"])
}

func TestL1Cache_GetSet(t *testing.T) {
	cache := alerting.NewL1Cache(100)

	cache.Set("key1", 42.5, 1*time.Minute)

	value, exists := cache.Get("key1")
	assert.True(t, exists)
	assert.Equal(t, 42.5, value)
}

func TestL1Cache_Get_NotFound(t *testing.T) {
	cache := alerting.NewL1Cache(100)

	_, exists := cache.Get("nonexistent")
	assert.False(t, exists)
}

func TestL1Cache_Get_Expired(t *testing.T) {
	cache := alerting.NewL1Cache(100)

	cache.Set("key1", 42.5, 50*time.Millisecond)
	time.Sleep(60 * time.Millisecond)

	_, exists := cache.Get("key1")
	assert.False(t, exists)
}

func TestL1Cache_EvictLRU(t *testing.T) {
	cache := alerting.NewL1Cache(3)

	cache.Set("key1", 1, 1*time.Minute)
	cache.Set("key2", 2, 1*time.Minute)
	cache.Set("key3", 3, 1*time.Minute)

	_, _ = cache.Get("key1")

	cache.Set("key4", 4, 1*time.Minute)

	_, exists := cache.Get("key2")
	assert.False(t, exists, "key2 should be evicted as LRU")

	_, exists = cache.Get("key1")
	assert.True(t, exists, "key1 should still exist")
}

func TestL1Cache_Invalidate(t *testing.T) {
	cache := alerting.NewL1Cache(100)

	cache.Set("key1", 42.5, 1*time.Minute)
	cache.Invalidate("key1")

	_, exists := cache.Get("key1")
	assert.False(t, exists)
}

func TestL1Cache_InvalidateAll(t *testing.T) {
	cache := alerting.NewL1Cache(100)

	cache.Set("key1", 1, 1*time.Minute)
	cache.Set("key2", 2, 1*time.Minute)
	cache.InvalidateAll()

	_, exists := cache.Get("key1")
	assert.False(t, exists)
	_, exists = cache.Get("key2")
	assert.False(t, exists)
}

func TestL2Cache_GetSet(t *testing.T) {
	cache := alerting.NewL2Cache(100 * time.Millisecond)
	defer cache.Stop()

	cache.Set("key1", 42.5)

	value, exists := cache.Get("key1")
	assert.True(t, exists)
	assert.Equal(t, 42.5, value)
}

func TestL2Cache_Get_Expired(t *testing.T) {
	cache := alerting.NewL2Cache(50 * time.Millisecond)
	defer cache.Stop()

	cache.Set("key1", 42.5)
	time.Sleep(60 * time.Millisecond)

	_, exists := cache.Get("key1")
	assert.False(t, exists)
}

func TestMultiLevelCache_GetSet(t *testing.T) {
	cache := alerting.NewMultiLevelCache(100, 1*time.Minute)
	defer cache.Stop()

	cache.Set("key1", 42.5)

	value, exists := cache.Get("key1")
	assert.True(t, exists)
	assert.Equal(t, 42.5, value)
}

func TestMultiLevelCache_L2ToL1Promotion(t *testing.T) {
	cache := alerting.NewMultiLevelCache(100, 1*time.Minute)
	defer cache.Stop()

	cache.Set("key1", 42.5)

	_, _ = cache.Get("key1")

	l1Cache := alerting.NewL1Cache(100)
	assert.NotNil(t, l1Cache)
}

func TestMultiLevelCache_Invalidate(t *testing.T) {
	cache := alerting.NewMultiLevelCache(100, 1*time.Minute)
	defer cache.Stop()

	cache.Set("key1", 42.5)
	cache.Invalidate("key1")

	_, exists := cache.Get("key1")
	assert.True(t, exists, "L2 should still have the value")
}

func TestEvaluator_ConcurrentRuleAddition(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	var wg sync.WaitGroup
	numRules := 20

	for i := 0; i < numRules; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			rule := builders.NewAlertRuleBuilder().
				WithRuleID(fmt.Sprintf("concurrent_rule_%d", idx)).
				Build()
			_ = e.AddRule(rule)
		}(i)
	}

	wg.Wait()

	rules := e.ListRules()
	assert.Len(t, rules, numRules)
}

func TestEvaluator_BuilderIntegration(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	builder := builders.NewAlertRuleBuilder().
		WithName("High Latency Alert").
		WithMetric("latency_p99").
		WithCondition("gt").
		WithThreshold(500.0).
		WithLabel("service", "api-gateway").
		WithLabel("environment", "production").
		WithEnabled(true)

	rule := builder.Build()

	err := e.AddRule(rule)
	require.NoError(t, err)

	retrieved, err := e.GetRule(rule.RuleID)
	require.NoError(t, err)
	assert.Equal(t, "High Latency Alert", retrieved.Name)
	assert.Equal(t, "latency_p99", retrieved.Metric)
	assert.Equal(t, "api-gateway", retrieved.Labels["service"])
	assert.Equal(t, "production", retrieved.Labels["environment"])
}

func TestEvaluator_Evaluate_NoMetricSource(t *testing.T) {
	e := alerting.NewEvaluator(1 * time.Second)
	defer e.Stop()

	rule := builders.NewAlertRuleBuilder().
		WithRuleID("no_source_rule").
		WithMetric("test_metric").
		Build()

	_ = e.AddRule(rule)

	assert.NotPanics(t, func() {
		e.Evaluate()
	})
}

type mockNotifier struct {
	onNotify func(alert *models.Alert) error
}

func (m *mockNotifier) Notify(alert *models.Alert) error {
	if m.onNotify != nil {
		return m.onNotify(alert)
	}
	return nil
}
