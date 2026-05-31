package monitoring

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/parking-platform/platform/pkg/models"
)

func TestNewAlertManager(t *testing.T) {
	manager := NewAlertManager()
	if manager == nil {
		t.Fatal("Expected non-nil AlertManager")
	}
	if len(manager.ListRules()) != 0 {
		t.Error("Expected empty rules initially")
	}
	if len(manager.ListNotifications()) != 0 {
		t.Error("Expected empty notifications initially")
	}
}

func TestAddAndListRules(t *testing.T) {
	manager := NewAlertManager()

	annotations := map[string]string{
		"message": "High error rate detected",
		"runbook": "https://example.com/runbooks/error-rate",
	}

	rule := manager.AddRule("high-error-rate", "error_rate > 0.01", "critical", annotations)
	if rule == nil {
		t.Fatal("Expected non-nil rule")
	}
	if rule.Name != "high-error-rate" {
		t.Errorf("Expected name 'high-error-rate', got %q", rule.Name)
	}
	if rule.Expression != "error_rate > 0.01" {
		t.Errorf("Expected expression mismatch")
	}
	if rule.Severity != "critical" {
		t.Errorf("Expected severity 'critical', got %q", rule.Severity)
	}
	if !rule.Enabled {
		t.Error("Expected rule to be enabled by default")
	}
	if rule.Annotations["message"] != "High error rate detected" {
		t.Error("Expected annotation 'message' to match")
	}

	rules := manager.ListRules()
	if len(rules) != 1 {
		t.Errorf("Expected 1 rule, got %d", len(rules))
	}
	if rules[0].ID != rule.ID {
		t.Error("Expected listed rule to match added rule")
	}
}

func TestUpdateRule(t *testing.T) {
	manager := NewAlertManager()
	rule := manager.AddRule("test-rule", "latency_p99 > 500", "warning", nil)

	err := manager.UpdateRule(rule.ID, false)
	if err != nil {
		t.Fatalf("Failed to update rule: %v", err)
	}

	rules := manager.ListRules()
	if rules[0].Enabled {
		t.Error("Expected rule to be disabled")
	}

	err = manager.UpdateRule("non-existent", true)
	if err == nil {
		t.Error("Expected error for non-existent rule")
	}
	if err != ErrRuleNotFound {
		t.Error("Expected ErrRuleNotFound")
	}
}

func TestRecordAndGetMetrics(t *testing.T) {
	manager := NewAlertManager()

	manager.RecordMetric("error_rate", 0.005)
	manager.RecordMetric("latency_p99", 120.5)
	manager.RecordMetric("throughput", 1500)

	value, ok := manager.GetMetric("error_rate")
	if !ok {
		t.Error("Expected to find 'error_rate' metric")
	}
	if value != 0.005 {
		t.Errorf("Expected 0.005, got %f", value)
	}

	_, ok = manager.GetMetric("non-existent")
	if ok {
		t.Error("Expected 'ok' to be false for non-existent metric")
	}
}

func TestEvaluateRules(t *testing.T) {
	manager := NewAlertManager()

	criticalAnnotations := map[string]string{"message": "Critical error rate"}
	warningAnnotations := map[string]string{"message": "High latency"}

	manager.AddRule("error-rule", "error_rate > 0.01", "critical", criticalAnnotations)
	manager.AddRule("latency-rule", "latency_p99 > 500", "warning", warningAnnotations)

	manager.RecordMetric("error_rate", 0.005)
	manager.RecordMetric("latency_p99", 100)

	alerts := manager.EvaluateRules()
	if len(alerts) != 0 {
		t.Errorf("Expected 0 alerts, got %d", len(alerts))
	}

	manager.RecordMetric("error_rate", 0.02)
	alerts = manager.EvaluateRules()
	if len(alerts) != 1 {
		t.Errorf("Expected 1 alert, got %d", len(alerts))
	}
	if alerts[0].Level != "critical" {
		t.Errorf("Expected critical severity, got %q", alerts[0].Level)
	}

	manager.RecordMetric("latency_p99", 600)
	alerts = manager.EvaluateRules()
	if len(alerts) != 2 {
		t.Errorf("Expected 2 alerts, got %d", len(alerts))
	}

	notifications := manager.ListNotifications()
	if len(notifications) < 2 {
		t.Errorf("Expected at least 2 notifications")
	}
}

func TestDisabledRuleNotEvaluated(t *testing.T) {
	manager := NewAlertManager()
	rule := manager.AddRule("disabled-rule", "error_rate > 0.01", "critical", map[string]string{"message": "test"})
	manager.RecordMetric("error_rate", 0.05)
	manager.UpdateRule(rule.ID, false)

	alerts := manager.EvaluateRules()
	if len(alerts) != 0 {
		t.Errorf("Expected 0 alerts for disabled rule, got %d", len(alerts))
	}
}

func TestTakeSnapshot(t *testing.T) {
	manager := NewAlertManager()
	manager.RecordMetric("error_rate", 0.01)
	manager.RecordMetric("latency_p99", 250)

	dimensions := map[string]string{"host": "node-1", "region": "cn-east"}
	snapshot := manager.TakeSnapshot(dimensions)

	if snapshot == nil {
		t.Fatal("Expected non-nil snapshot")
	}
	if snapshot.SnapshotID == "" {
		t.Error("Expected non-empty snapshot ID")
	}
	if snapshot.Timestamp.IsZero() {
		t.Error("Expected non-zero timestamp")
	}
	if len(snapshot.Metrics) != 2 {
		t.Errorf("Expected 2 metrics in snapshot, got %d", len(snapshot.Metrics))
	}
	if snapshot.Metrics["error_rate"] != 0.01 {
		t.Error("Expected error_rate to match")
	}
	if snapshot.Dimensions["host"] != "node-1" {
		t.Error("Expected host dimension")
	}
}

func TestEmptyMetrics(t *testing.T) {
	manager := NewAlertManager()
	manager.AddRule("test-rule", "error_rate > 0.01", "warning", map[string]string{"message": "test"})

	alerts := manager.EvaluateRules()
	if len(alerts) != 0 {
		t.Errorf("Expected 0 alerts with no metrics, got %d", len(alerts))
	}
}

func TestZeroMetricValues(t *testing.T) {
	manager := NewAlertManager()
	manager.RecordMetric("error_rate", 0)
	manager.RecordMetric("latency_p99", 0)

	value, ok := manager.GetMetric("error_rate")
	if !ok {
		t.Error("Expected to find zero-value metric")
	}
	if value != 0 {
		t.Error("Expected zero value")
	}
}

func TestLargeMetricValues(t *testing.T) {
	manager := NewAlertManager()
	manager.RecordMetric("large_positive", 1e18)
	manager.RecordMetric("large_negative", -1e18)

	pos, _ := manager.GetMetric("large_positive")
	neg, _ := manager.GetMetric("large_negative")

	if pos != 1e18 {
		t.Error("Large positive value mismatch")
	}
	if neg != -1e18 {
		t.Error("Large negative value mismatch")
	}
}

func TestConcurrentRecordMetrics(t *testing.T) {
	manager := NewAlertManager()
	const goroutines = 100
	const metricsPerGoroutine = 50

	var wg sync.WaitGroup
	wg.Add(goroutines)

	for i := 0; i < goroutines; i++ {
		go func(index int) {
			defer wg.Done()
			for j := 0; j < metricsPerGoroutine; j++ {
				metricName := fmt.Sprintf("metric_%d_%d", index, j)
				manager.RecordMetric(metricName, float64(index+j))
			}
		}(i)
	}
	wg.Wait()

	for i := 0; i < goroutines; i++ {
		for j := 0; j < metricsPerGoroutine; j++ {
			metricName := fmt.Sprintf("metric_%d_%d", i, j)
			_, ok := manager.GetMetric(metricName)
			if !ok {
				t.Errorf("Expected to find metric %s", metricName)
			}
		}
	}
}

func TestConcurrentEvaluateRules(t *testing.T) {
	manager := NewAlertManager()
	manager.AddRule("rule1", "error_rate > 0.01", "critical", map[string]string{"message": "test1"})
	manager.AddRule("rule2", "latency_p99 > 500", "warning", map[string]string{"message": "test2"})
	manager.RecordMetric("error_rate", 0.05)
	manager.RecordMetric("latency_p99", 600)

	const goroutines = 30
	var wg sync.WaitGroup
	wg.Add(goroutines)

	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			_ = manager.EvaluateRules()
		}()
	}
	wg.Wait()

	notifications := manager.ListNotifications()
	if len(notifications) == 0 {
		t.Error("Expected some notifications")
	}
}

func TestConcurrentReadWrite(t *testing.T) {
	manager := NewAlertManager()
	manager.RecordMetric("shared_metric", 0)

	const goroutines = 25
	var wg sync.WaitGroup
	wg.Add(goroutines * 2)

	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				manager.RecordMetric("shared_metric", float64(j))
			}
		}()

		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				_, _ = manager.GetMetric("shared_metric")
			}
		}()
	}
	wg.Wait()
}

func TestNewMetricsCache(t *testing.T) {
	cache := NewMetricsCache(1*time.Minute, 100)
	if cache == nil {
		t.Fatal("Expected non-nil MetricsCache")
	}
}

func TestCacheSetAndGet(t *testing.T) {
	cache := NewMetricsCache(1*time.Minute, 100)

	cache.Set("error_rate", 0.005)
	cache.Set("latency_p99", 250)

	value, ok := cache.Get("error_rate")
	if !ok {
		t.Error("Expected to find error_rate in cache")
	}
	if value != 0.005 {
		t.Errorf("Expected 0.005, got %f", value)
	}

	_, ok = cache.Get("non-existent")
	if ok {
		t.Error("Expected ok=false for non-existent key")
	}
}

func TestCacheTTLExpiration(t *testing.T) {
	cache := NewMetricsCache(100*time.Millisecond, 100)

	cache.Set("expiring_metric", 100)
	_, ok := cache.Get("expiring_metric")
	if !ok {
		t.Error("Expected to find metric before TTL")
	}

	time.Sleep(200 * time.Millisecond)
	_, ok = cache.Get("expiring_metric")
	if ok {
		t.Error("Expected metric to expire after TTL")
	}
}

func TestCacheLRUEviction(t *testing.T) {
	cache := NewMetricsCache(1*time.Minute, 3)

	cache.Set("a", 1)
	cache.Set("b", 2)
	cache.Set("c", 3)

	_, _ = cache.Get("a")

	cache.Set("d", 4)

	_, ok := cache.Get("b")
	if ok {
		t.Error("Expected 'b' to be evicted")
	}

	_, ok = cache.Get("a")
	if !ok {
		t.Error("Expected 'a' to still exist (was accessed)")
	}
}

func TestCacheInvalidateAndClear(t *testing.T) {
	cache := NewMetricsCache(1*time.Minute, 100)

	cache.Set("a", 1)
	cache.Set("b", 2)
	cache.Set("c", 3)

	cache.Invalidate("b")
	_, ok := cache.Get("b")
	if ok {
		t.Error("Expected 'b' to be invalidated")
	}

	cache.Clear()
	_, ok = cache.Get("a")
	if ok {
		t.Error("Expected cache to be cleared")
	}
}

func TestCacheHotMetrics(t *testing.T) {
	cache := NewMetricsCache(1*time.Minute, 100)

	cache.Set("cold", 1)
	cache.Set("hot", 2)

	for i := 0; i < 15; i++ {
		_, _ = cache.Get("hot")
	}
	_, _ = cache.Get("cold")

	hotMetrics := cache.HotMetrics()
	found := false
	for _, m := range hotMetrics {
		if m == "hot" {
			found = true
			break
		}
	}
	if !found {
		t.Error("Expected 'hot' to be in hot metrics list")
	}
}

func TestSimpleWarmer(t *testing.T) {
	sourceCalls := 0
	source := func(name string) (float64, error) {
		sourceCalls++
		if name == "error" {
			return 0, errors.New("test error")
		}
		return float64(len(name)), nil
	}

	warmer := NewSimpleWarmer(source)
	err := warmer.WarmUp(context.Background(), []string{"a", "b", "c"})
	if err != nil {
		t.Errorf("WarmUp failed: %v", err)
	}
	if sourceCalls != 3 {
		t.Errorf("Expected 3 source calls, got %d", sourceCalls)
	}
}

func TestAlertManagerWithCache(t *testing.T) {
	cache := NewMetricsCache(1*time.Minute, 100)
	manager := NewAlertManagerWithCache(cache, nil)

	manager.RecordMetric("test_metric", 42)

	value, ok := manager.GetMetric("test_metric")
	if !ok {
		t.Error("Expected to find metric via cache")
	}
	if value != 42 {
		t.Errorf("Expected 42, got %f", value)
	}
}

func TestWarmUp(t *testing.T) {
	warmed := int32(0)
	warmer := NewSimpleWarmer(func(name string) (float64, error) {
		atomic.AddInt32(&warmed, 1)
		return 0, nil
	})

	manager := NewAlertManagerWithCache(nil, warmer)

	if manager.IsWarmedUp() {
		t.Error("Expected not warmed up initially")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err := manager.WarmUp(ctx, []string{"a", "b", "c"})
	if err != nil {
		t.Errorf("WarmUp failed: %v", err)
	}

	if !manager.IsWarmedUp() {
		t.Error("Expected warmed up after WarmUp")
	}
	if atomic.LoadInt32(&warmed) != 3 {
		t.Errorf("Expected 3 warmed metrics, got %d", warmed)
	}

	err = manager.WarmUp(ctx, []string{"x", "y", "z"})
	if err != nil {
		t.Errorf("Second WarmUp should be idempotent, got error: %v", err)
	}
	if atomic.LoadInt32(&warmed) != 3 {
		t.Error("Expected second WarmUp to not call warmer again")
	}
}

func TestCacheInvalidateMethods(t *testing.T) {
	cache := NewMetricsCache(1*time.Minute, 100)
	manager := NewAlertManagerWithCache(cache, nil)

	manager.RecordMetric("a", 1)
	manager.RecordMetric("b", 2)
	manager.RecordMetric("c", 3)

	manager.InvalidateCache("a", "c")
	_, ok := manager.cache.Get("a")
	if ok {
		t.Error("Expected 'a' to be invalidated")
	}
	_, ok = manager.cache.Get("b")
	if !ok {
		t.Error("Expected 'b' to still exist")
	}

	manager.InvalidateCache()
	_, ok = manager.cache.Get("b")
	if ok {
		t.Error("Expected all to be cleared")
	}
}

func TestCacheStats(t *testing.T) {
	cache := NewMetricsCache(1*time.Minute, 100)
	manager := NewAlertManagerWithCache(cache, nil)

	manager.RecordMetric("hot1", 1)
	manager.RecordMetric("cold1", 2)

	for i := 0; i < 15; i++ {
		_, _ = manager.GetMetric("hot1")
	}

	stats := manager.CacheStats()
	if stats["warmed_up"] != false {
		t.Error("Expected warmed_up to be false")
	}
	if stats["hot_metrics_count"] != 1 {
		t.Errorf("Expected 1 hot metric, got %v", stats["hot_metrics_count"])
	}
}

func TestContextCancellationDuringWarmUp(t *testing.T) {
	warmer := NewSimpleWarmer(func(name string) (float64, error) {
		time.Sleep(50 * time.Millisecond)
		return 0, nil
	})

	manager := NewAlertManagerWithCache(nil, warmer)

	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	err := manager.WarmUp(ctx, []string{"a", "b", "c", "d"})
	if err == nil {
		t.Error("Expected error from cancelled context")
	}
}

func TestCustomNotifier(t *testing.T) {
	notifiedCount := 0
	customNotifier := &testNotifier{
		notifyFn: func(n models.AlertNotification) error {
			notifiedCount++
			return nil
		},
	}

	manager := NewAlertManager(customNotifier)
	manager.AddRule("test-rule", "error_rate > 0.01", "warning", map[string]string{"message": "test"})
	manager.RecordMetric("error_rate", 0.05)

	_ = manager.EvaluateRules()
	if notifiedCount != 1 {
		t.Errorf("Expected 1 notification, got %d", notifiedCount)
	}
}

type testNotifier struct {
	notifyFn func(models.AlertNotification) error
}

func (n *testNotifier) Notify(notification models.AlertNotification) error {
	return n.notifyFn(notification)
}
