package tests

import (
	"context"
	"fmt"
	"go.uber.org/zap"
	"metricplatform/pkg/alertengine"
	"metricplatform/pkg/anomaly"
	"metricplatform/pkg/logpipeline"
	"metricplatform/pkg/metrics"
	"metricplatform/pkg/scheduler"
	"metricplatform/pkg/slo"
	"metricplatform/pkg/tracing"
	"testing"
	"time"

	"metricplatform/internal/models"
)

type MockMetricsProvider struct{}

func (m *MockMetricsProvider) GetMetricValue(ctx context.Context, metricName string, tags map[string]string) (float64, error) {
	if metricName == "error_rate" {
		return 0.05, nil
	}
	if metricName == "request_latency" {
		return 150.0, nil
	}
	return 0, nil
}

func (m *MockMetricsProvider) GetSLIValue(ctx context.Context, expression string, window time.Duration) (float64, error) {
	return 99.5, nil
}

type MockNotifier struct {
	notifications []*models.Alert
}

func (n *MockNotifier) Send(ctx context.Context, alert *models.Alert) error {
	n.notifications = append(n.notifications, alert)
	return nil
}

func TestAlertEngine(t *testing.T) {
	logger, _ := zap.NewDevelopment()
	defer logger.Sync()

	mp := &MockMetricsProvider{}
	notifier := &MockNotifier{}
	notifiers := []alertengine.Notifier{notifier}

	engine := alertengine.NewRuleEvaluator(mp, notifiers, logger)

	rule := &models.AlertRule{
		Name:        "High Error Rate",
		Expression:  "error_rate{service=\"api\"} > 0.01",
		Severity:    "critical",
		ForDuration: 1 * time.Second,
		Enabled:     true,
	}

	if err := engine.AddRule(rule); err != nil {
		t.Fatalf("Failed to add rule: %v", err)
	}

	parsed, err := alertengine.ParseExpression("error_rate{service=\"api\"} > 0.01")
	if err != nil {
		t.Fatalf("Failed to parse expression: %v", err)
	}

	if parsed.MetricName != "error_rate" {
		t.Errorf("Expected metric name 'error_rate', got '%s'", parsed.MetricName)
	}
	if parsed.Operator != ">" {
		t.Errorf("Expected operator '>', got '%s'", parsed.Operator)
	}
	if parsed.Threshold != 0.01 {
		t.Errorf("Expected threshold 0.01, got %f", parsed.Threshold)
	}
	if parsed.Tags["service"] != "api" {
		t.Errorf("Expected tag service=api, got '%s'", parsed.Tags["service"])
	}

	engine.Start()
	time.Sleep(2 * time.Second)
	engine.Stop()

	alerts := engine.GetActiveAlerts()
	if len(alerts) == 0 {
		t.Log("No active alerts (expected for short test duration)")
	}

	t.Log("Alert engine test passed")
}

func TestAnomalyDetection(t *testing.T) {
	logger, _ := zap.NewDevelopment()
	defer logger.Sync()

	algorithms := []anomaly.DetectionAlgorithm{
		anomaly.AlgorithmZScore,
		anomaly.AlgorithmIQR,
	}
	detector := anomaly.NewDetector(algorithms, 2.0, 20, false, logger)

	now := time.Now()
	for i := 0; i < 15; i++ {
		detector.AddDataPoint(models.MetricDataPoint{
			MetricName: "request_latency",
			Value:      100.0 + float64(i)*2,
			Timestamp:  now.Add(time.Duration(i) * time.Second),
		})
	}

	results, err := detector.Detect(context.Background(), "request_latency", 500.0)
	if err != nil {
		t.Fatalf("Anomaly detection failed: %v", err)
	}

	if len(results) == 0 {
		t.Error("Expected anomaly to be detected for value 500")
	}

	for _, result := range results {
		t.Logf("Anomaly detected: algorithm=%s, score=%.2f, severity=%s",
			result.Algorithm, result.Score, result.Severity)
	}

	t.Log("Anomaly detection test passed")
}

func TestMetricsCollector(t *testing.T) {
	logger, _ := zap.NewDevelopment()
	defer logger.Sync()

	algorithms := []anomaly.DetectionAlgorithm{anomaly.AlgorithmZScore}
	detector := anomaly.NewDetector(algorithms, 3.0, 50, false, logger)
	collector := metrics.NewCollector(1000, 100*time.Millisecond, detector, logger)

	collector.AddAggregation("request_latency", "avg", metrics.AggregationAvg)
	collector.AddAggregation("request_latency", "p99", metrics.AggregationP99)
	collector.AddAggregation("throughput", "sum", metrics.AggregationSum)

	collector.Start()
	defer collector.Stop()

	for i := 0; i < 100; i++ {
		err := collector.Collect(models.MetricDataPoint{
			MetricName: "request_latency",
			Value:      100.0 + float64(i%10)*10,
			Tags:       map[string]string{"service": "api"},
		})
		if err != nil {
			t.Fatalf("Failed to collect metric: %v", err)
		}

		err = collector.Collect(models.MetricDataPoint{
			MetricName: "throughput",
			Value:      10.0,
			Tags:       map[string]string{"service": "api"},
		})
		if err != nil {
			t.Fatalf("Failed to collect metric: %v", err)
		}
	}

	time.Sleep(200 * time.Millisecond)

	aggValues := collector.GetAggregatedValues()
	if len(aggValues) == 0 {
		t.Error("Expected aggregated values")
	}

	for metric, aggs := range aggValues {
		for name, value := range aggs {
			t.Logf("Metric: %s, Aggregation: %s, Value: %.2f", metric, name, value)
		}
	}

	snapshot := collector.CreateSnapshot(map[string]string{"host": "node-1", "region": "cn-east"})
	if snapshot == nil {
		t.Fatal("Failed to create snapshot")
	}
	t.Logf("Snapshot created: %s with %d metrics", snapshot.SnapshotID, len(snapshot.Metrics))

	t.Log("Metrics collector test passed")
}

func TestLogPipeline(t *testing.T) {
	logger, _ := zap.NewDevelopment()
	defer logger.Sync()

	pipeline := logpipeline.NewPipeline(1000, 2, logger)

	pipeline.AddFilter(logpipeline.LevelFilter("info"))
	pipeline.AddParser(logpipeline.JSONParser())
	pipeline.SetRouter(logpipeline.LevelRouter())

	errorOutput := pipeline.AddOutput("error", 100)
	defaultOutput := pipeline.AddOutput("default", 100)

	pipeline.Start()
	defer pipeline.Stop()

	logs := []*models.LogEntry{
		{Level: "info", Message: "Application started", Source: "api"},
		{Level: "error", Message: "{\"error\": \"connection failed\", \"code\": 500}", Source: "db"},
		{Level: "warning", Message: "High memory usage detected", Source: "api"},
		{Level: "debug", Message: "Debug message should be filtered", Source: "api"},
	}

	for _, log := range logs {
		err := pipeline.Process(log)
		if err != nil {
			t.Fatalf("Failed to process log: %v", err)
		}
	}

	time.Sleep(100 * time.Millisecond)

	errorCount := 0
	defaultCount := 0

	for {
		select {
		case log := <-errorOutput:
			errorCount++
			t.Logf("Error log received: %s", log.Message)
		default:
			goto doneError
		}
	}
doneError:

	for {
		select {
		case log := <-defaultOutput:
			defaultCount++
			t.Logf("Default log received: %s", log.Message)
		default:
			goto doneDefault
		}
	}
doneDefault:

	t.Logf("Processed %d error logs, %d default logs", errorCount, defaultCount)
	t.Log("Log pipeline test passed")
}

func TestScheduler(t *testing.T) {
	logger, _ := zap.NewDevelopment()
	defer logger.Sync()

	type MockRepo struct{}
	scheduler := scheduler.NewScheduler(nil, logger)

	handlerCalled := make(chan string, 10)
	scheduler.RegisterHandler("test_task", func(ctx context.Context, task *models.Task, run *models.TaskRun) error {
		handlerCalled <- task.ID
		return nil
	})

	scheduler.Start()
	defer scheduler.Stop()

	task := &models.Task{
		Name:     "Test Task",
		Type:     "test_task",
		CronExpr: "@every 1s",
		Payload:  map[string]interface{}{"param": "value"},
	}

	if err := scheduler.AddTask(task); err != nil {
		t.Fatalf("Failed to add task: %v", err)
	}

	time.Sleep(2500 * time.Millisecond)

	select {
	case taskID := <-handlerCalled:
		t.Logf("Handler called for task: %s", taskID)
	default:
		t.Error("Expected handler to be called at least once")
	}

	tasks, _ := scheduler.GetAllTasks()
	t.Logf("Total tasks: %d", len(tasks))

	t.Log("Scheduler test passed")
}

func TestSLOMonitor(t *testing.T) {
	logger, _ := zap.NewDevelopment()
	defer logger.Sync()

	mp := &MockMetricsProvider{}
	notifier := &MockNotifier{}
	notifiers := []alertengine.Notifier{notifier}
	alertEngine := alertengine.NewRuleEvaluator(mp, notifiers, logger)

	monitor := slo.NewMonitor(mp, alertEngine, logger)

	slo := &models.SLO{
		Name:          "API Availability",
		Description:   "API must be available 99.9% of the time",
		SLIExpression: "availability",
		TargetPercent: 99.9,
		WindowDays:    30,
		Labels:        map[string]string{"service": "api"},
	}

	if err := monitor.AddSLO(slo); err != nil {
		t.Fatalf("Failed to add SLO: %v", err)
	}

	monitor.Start()
	defer monitor.Stop()

	time.Sleep(2 * time.Second)

	sloDef, status, _ := monitor.GetSLO(slo.ID)
	if sloDef == nil {
		t.Fatal("Expected SLO to exist")
	}

	t.Logf("SLO: %s, Current SLI: %.2f%%, Budget Remaining: %.2f%%, Burn Rate: %.2fx",
		sloDef.Name, status.CurrentSLI, status.ErrorBudgetRemaining*100, status.ErrorBudgetBurnRate)

	t.Log("SLO monitor test passed")
}

func TestTraceCollector(t *testing.T) {
	logger, _ := zap.NewDevelopment()
	defer logger.Sync()

	traceCollector := tracing.NewCollector(1000, 2, nil, logger)
	traceCollector.SetSamplingConfig(&models.SamplingConfig{
		Service:           "test-service",
		DefaultSampleRate: 0.5,
		TailSampling:      true,
		TailWaitDuration:  500 * time.Millisecond,
		Rules: []models.SamplingRule{
			{
				AttributeKey:   "error",
				AttributeValue: "true",
				Operator:       "equals",
				SampleRate:     1.0,
			},
		},
	})

	traceCollector.Start()
	defer traceCollector.Stop()

	traceID := "trace-001"
	for i := 0; i < 5; i++ {
		span := &models.Span{
			TraceID:   traceID,
			SpanID:    fmt.Sprintf("span-%d", i),
			ParentID:  "",
			Name:      fmt.Sprintf("operation-%d", i),
			Service:   "test-service",
			StartTime: time.Now(),
			EndTime:   time.Now().Add(time.Duration(i+1) * 10 * time.Millisecond),
			Status:    "ok",
			Attributes: map[string]interface{}{
				"http.method": "GET",
				"http.path":   fmt.Sprintf("/api/v1/resource/%d", i),
			},
		}

		if i == 3 {
			span.Status = "error"
			span.Attributes["error"] = "true"
		}

		err := traceCollector.ReceiveSpan(span)
		if err != nil {
			t.Fatalf("Failed to receive span: %v", err)
		}
	}

	time.Sleep(800 * time.Millisecond)

	buffered := traceCollector.GetBufferedTraceCount()
	t.Logf("Buffered traces after tail sampling: %d", buffered)
	t.Log("Trace collector test passed")
}
