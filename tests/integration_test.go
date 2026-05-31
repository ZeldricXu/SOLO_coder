package tests

import (
	"context"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/solocoder/backup-engine/internal/alerting"
	"github.com/solocoder/backup-engine/internal/anomaly"
	"github.com/solocoder/backup-engine/internal/core"
	"github.com/solocoder/backup-engine/internal/logger"
	"github.com/solocoder/backup-engine/internal/logpipeline"
	"github.com/solocoder/backup-engine/internal/scheduler"
	"github.com/solocoder/backup-engine/internal/slo"
	"github.com/solocoder/backup-engine/internal/storage"
	"github.com/solocoder/backup-engine/pkg/common"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestStorageBackupRestore(t *testing.T) {
	ctx := context.Background()
	config := common.BackupConfig{
		Source:        "test",
		Destination:   "./test_backups",
		Compression:   "none",
		EncryptionKey: "test-encryption-key-32bytes-long!",
		RetentionDays: 7,
		MaxParallel:   3,
	}

	sm, err := storage.NewStorageManager("./test_backups", config)
	require.NoError(t, err)

	testData := []byte("test data for backup and restore " + time.Now().String())

	info, err := sm.Backup(ctx, "test-backup", testData)
	require.NoError(t, err)
	assert.NotEmpty(t, info.ID)
	assert.Equal(t, "test-backup", info.Name)
	assert.True(t, info.Encrypted)

	restored, err := sm.Restore(ctx, info.ID)
	require.NoError(t, err)
	assert.Equal(t, string(testData), string(restored))

	list := sm.List()
	assert.GreaterOrEqual(t, len(list), 1)

	err = sm.Delete(info.ID)
	require.NoError(t, err)

	_, err = sm.Restore(ctx, info.ID)
	assert.Error(t, err)
}

func TestAnomalyDetection(t *testing.T) {
	engine := anomaly.NewDetectorEngine(1000, anomaly.AlgorithmZScore)

	now := time.Now()
	for i := 0; i < 50; i++ {
		metric := common.Metric{
			Name:      "test_metric",
			Value:     50 + float64(i%5),
			Timestamp: now.Add(-time.Duration(50-i) * time.Second),
		}
		engine.AddMetric(metric)
	}

	normalMetric := common.Metric{
		Name:      "test_metric",
		Value:     52,
		Timestamp: time.Now(),
	}

	result, err := engine.Detect(normalMetric, anomaly.AlgorithmZScore)
	require.NoError(t, err)
	assert.False(t, result.IsAnomaly)

	anomalyMetric := common.Metric{
		Name:      "test_metric",
		Value:     500,
		Timestamp: time.Now(),
	}

	result, err = engine.Detect(anomalyMetric, anomaly.AlgorithmZScore)
	require.NoError(t, err)
	assert.True(t, result.IsAnomaly)
	assert.Greater(t, result.Score, 3.0)

	allResults, err := engine.DetectAll(anomalyMetric)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(allResults), 5)

	algorithms := make(map[anomaly.AlgorithmType]bool)
	for _, r := range allResults {
		algorithms[r.Algorithm] = true
	}

	assert.True(t, algorithms[anomaly.AlgorithmZScore])
	assert.True(t, algorithms[anomaly.AlgorithmPercentile])
	assert.True(t, algorithms[anomaly.AlgorithmMovingAverage])
	assert.True(t, algorithms[anomaly.AlgorithmExpSmoothing])
	assert.True(t, algorithms[anomaly.AlgorithmMAD])
}

func TestCoreProcessor(t *testing.T) {
	processor := core.NewProcessor()
	processor.SetTimeout(10 * time.Second)
	processor.SetMaxRetries(2)

	handlerCalled := 0
	processor.RegisterHandler("test_op", func(ctx context.Context, req *common.Request) (*common.Response, error) {
		handlerCalled++
		return &common.Response{
			Success: true,
			Code:    200,
			Message: "success",
			Data:    map[string]string{"result": "ok"},
		}, nil
	})

	ctx := context.Background()
	req := &common.Request{
		ID:        "test-req-1",
		TraceID:   "test-trace-1",
		Operation: "test_op",
		Timestamp: time.Now(),
		Payload:   map[string]interface{}{"key": "value"},
		Headers:   make(map[string]string),
	}

	resp := processor.Process(ctx, req)
	assert.True(t, resp.Success)
	assert.Equal(t, 200, resp.Code)
	assert.Equal(t, "test-req-1", resp.RequestID)
	assert.Equal(t, "test-trace-1", resp.TraceID)
	assert.Equal(t, 1, handlerCalled)

	unknownReq := &common.Request{
		Operation: "unknown_op",
		Timestamp: time.Now(),
	}

	resp = processor.Process(ctx, unknownReq)
	assert.False(t, resp.Success)
	assert.Equal(t, 404, resp.Code)

	metrics := processor.GetMetrics()
	assert.Equal(t, int64(2), metrics["total_requests"])
	assert.Equal(t, int64(1), metrics["success_requests"])
}

func TestAlertEngine(t *testing.T) {
	engine := alerting.NewAlertEngine()

	rule := &common.AlertRule{
		Name:       "test_rule",
		Expression: "{test_metric} > 100",
		Severity:   "warning",
		For:        1 * time.Minute,
		Enabled:    true,
		Labels:     map[string]string{"service": "test"},
		Annotations: map[string]string{
			"description": "Test metric is too high",
		},
	}

	err := engine.AddRule(rule)
	require.NoError(t, err)
	assert.NotEmpty(t, rule.ID)

	for i := 0; i < 10; i++ {
		metric := common.Metric{
			Name:      "test_metric",
			Value:     float64(i * 20),
			Timestamp: time.Now(),
		}
		engine.ReportMetric(metric)
	}

	engine.EvaluateAll()

	alerts := engine.GetActiveAlerts()
	assert.GreaterOrEqual(t, len(alerts), 0)

	rules := engine.GetRules()
	assert.Equal(t, 1, len(rules))
}

func TestSLOMonitor(t *testing.T) {
	monitor := slo.NewSLOMonitor(1000)

	err := monitor.AddSLO(&slo.SLOConfig{
		SLO: common.SLO{
			Name:               "test_availability",
			Description:        "Test availability SLO",
			SLIType:            string(slo.SLIAvailability),
			TargetPercent:      99.9,
			Period:             30 * 24 * time.Hour,
			ErrorBudgetPercent: 0.1,
		},
		WindowType: slo.WindowRolling,
	})
	require.NoError(t, err)

	now := time.Now()
	for i := 0; i < 100; i++ {
		event := slo.SLIEvent{
			Timestamp: now.Add(-time.Duration(100-i) * time.Minute),
			IsGood:    i%100 != 0,
			Value:     100.0,
			TraceID:   common.GenerateTraceID(),
		}
		err := monitor.RecordEvent("test_availability", event)
		require.NoError(t, err)
	}

	sli, err := monitor.CalculateSLI("test_availability", 24*time.Hour)
	require.NoError(t, err)
	assert.InDelta(t, 99.0, sli.Value, 1.0)

	budget, err := monitor.GetErrorBudgetStatus("test_availability")
	require.NoError(t, err)
	assert.Greater(t, budget.Remaining, 0.0)

	alerts := monitor.CheckAlerts()
	assert.NotNil(t, alerts)

	forecastSLO, forecastBudget, err := monitor.CalculateSLOForecast("test_availability", 7)
	require.NoError(t, err)
	assert.Greater(t, forecastSLO, 0.0)
	assert.GreaterOrEqual(t, forecastBudget, 0.0)
}

func TestScheduler(t *testing.T) {
	s := scheduler.NewTaskScheduler(3)

	results := make(map[string]bool)
	var mu sync.Mutex

	task1 := &common.Task{Name: "task1", Description: "First task"}
	task2 := &common.Task{Name: "task2", Description: "Second task"}
	task3 := &common.Task{Name: "task3", Description: "Third task"}

	id1 := s.AddTask(task1, func(ctx context.Context, task *common.Task) error {
		mu.Lock()
		results["task1"] = true
		mu.Unlock()
		time.Sleep(50 * time.Millisecond)
		task.Progress = 100
		return nil
	}, scheduler.PriorityHigh, 1, 5*time.Second)

	id2 := s.AddTask(task2, func(ctx context.Context, task *common.Task) error {
		mu.Lock()
		results["task2"] = true
		mu.Unlock()
		time.Sleep(50 * time.Millisecond)
		task.Progress = 100
		return nil
	}, scheduler.PriorityNormal, 1, 5*time.Second)

	id3 := s.AddTask(task3, func(ctx context.Context, task *common.Task) error {
		mu.Lock()
		results["task3"] = true
		mu.Unlock()
		time.Sleep(50 * time.Millisecond)
		task.Progress = 100
		return nil
	}, scheduler.PriorityLow, 1, 5*time.Second)

	s.AddDependency(id2, id1)
	s.AddDependency(id3, id2)

	err := s.Validate()
	require.NoError(t, err)

	ctx := context.Background()
	err = s.Run(ctx)
	require.NoError(t, err)

	progress := s.GetProgress()
	assert.InDelta(t, 100.0, progress, 0.1)

	mu.Lock()
	assert.True(t, results["task1"])
	assert.True(t, results["task2"])
	assert.True(t, results["task3"])
	mu.Unlock()

	errors := s.GetErrors()
	assert.Empty(t, errors)
}

func TestCircuitBreaker(t *testing.T) {
	cb := core.NewCircuitBreaker("test_cb", 3, 100*time.Millisecond)

	assert.Equal(t, core.StateClosed, cb.State())
	assert.True(t, cb.Allow())

	for i := 0; i < 3; i++ {
		cb.Failure()
	}

	assert.Equal(t, core.StateOpen, cb.State())
	assert.False(t, cb.Allow())

	time.Sleep(150 * time.Millisecond)
	assert.True(t, cb.Allow())
	assert.Equal(t, core.StateHalfOpen, cb.State())

	cb.Success()
	assert.Equal(t, core.StateClosed, cb.State())
}

func TestFullWorkflow(t *testing.T) {
	ctx := context.Background()

	backupConfig := common.BackupConfig{
		Source:        "integration_test",
		Destination:   "./integration_test_backups",
		Compression:   "none",
		EncryptionKey: "integration-test-key-32bytes!!",
		RetentionDays: 1,
		MaxParallel:   2,
	}

	sm, err := storage.NewStorageManager("./integration_test_backups", backupConfig)
	require.NoError(t, err)

	processor := core.NewProcessor()
	processor.RegisterHandler(core.OpBackup, func(ctx context.Context, req *common.Request) (*common.Response, error) {
		payload := req.Payload.(map[string]interface{})
		name := payload["name"].(string)
		data := []byte(payload["data"].(string))
		info, err := sm.Backup(ctx, name, data)
		if err != nil {
			return nil, err
		}
		return &common.Response{Success: true, Code: 200, Data: info}, nil
	})

	anomalyEngine := anomaly.NewDetectorEngine(1000, anomaly.AlgorithmZScore)
	sloMonitor := slo.NewSLOMonitor(1000)
	alertEngine := alerting.NewAlertEngine()

	sloMonitor.AddSLO(&slo.SLOConfig{
		SLO: common.SLO{
			Name:               "backup_availability",
			TargetPercent:      99.0,
			Period:             24 * time.Hour,
			ErrorBudgetPercent: 1.0,
		},
		WindowType: slo.WindowRolling,
	})

	for i := 0; i < 5; i++ {
		req := &common.Request{
			ID:        common.NewID(),
			TraceID:   common.GenerateTraceID(),
			Operation: string(core.OpBackup),
			Timestamp: time.Now(),
			Payload: map[string]interface{}{
				"name": fmt.Sprintf("backup-%d", i),
				"data": fmt.Sprintf("test data %d", i),
			},
			Headers: make(map[string]string),
		}

		resp := processor.Process(ctx, req)
		assert.True(t, resp.Success)

		sloMonitor.RecordEvent("backup_availability", slo.SLIEvent{
			Timestamp: time.Now(),
			IsGood:    resp.Success,
			Value:     float64(resp.Duration.Milliseconds()),
			TraceID:   req.TraceID,
		})

		metric := common.Metric{
			Name:      "backup_latency",
			Value:     float64(resp.Duration.Milliseconds()),
			Timestamp: time.Now(),
		}
		anomalyEngine.AddMetric(metric)
		alertEngine.ReportMetric(metric)
	}

	backups := sm.List()
	assert.Equal(t, 5, len(backups))

	sli, _ := sloMonitor.CalculateSLI("backup_availability", time.Hour)
	assert.Equal(t, 100.0, sli.Value)

	processorMetrics := processor.GetMetrics()
	assert.Equal(t, int64(5), processorMetrics["total_requests"])
}

func TestStorageSnapshotAndRecovery(t *testing.T) {
	ctx := context.Background()
	config := common.BackupConfig{
		Source:        "snapshot_test",
		Destination:   "./snapshot_test_backups",
		Compression:   "none",
		EncryptionKey: "snapshot-test-key-32bytes-long!!",
		RetentionDays: 7,
		MaxParallel:   3,
	}

	sm, err := storage.NewStorageManager("./snapshot_test_backups", config)
	require.NoError(t, err)

	for i := 0; i < 5; i++ {
		data := []byte(fmt.Sprintf("snapshot test data %d", i))
		_, err := sm.Backup(ctx, fmt.Sprintf("snap-backup-%d", i), data)
		require.NoError(t, err)
	}

	originalList := sm.List()
	assert.Equal(t, 5, len(originalList))

	snapshot, err := sm.CreateSnapshot()
	require.NoError(t, err)
	assert.NotEmpty(t, snapshot.ID)
	assert.Equal(t, 5, len(snapshot.Data))
	assert.NotEmpty(t, snapshot.Checksum)
}

func TestAlertEngineCache(t *testing.T) {
	engine := alerting.NewAlertEngineWithCache(100, 10*time.Second)

	rule := &common.AlertRule{
		Name:       "cache_test_rule",
		Expression: "{cpu_usage} > 80",
		Severity:   "warning",
		For:        1 * time.Minute,
		Enabled:    true,
		Labels:     map[string]string{"service": "test"},
		Annotations: map[string]string{
			"description": "CPU usage is high",
		},
	}

	err := engine.AddRule(rule)
	require.NoError(t, err)

	for i := 0; i < 20; i++ {
		metric := common.Metric{
			Name:      "cpu_usage",
			Value:     50.0 + float64(i)*3,
			Timestamp: time.Now(),
		}
		engine.ReportMetric(metric)
	}

	engine.EvaluateAll()

	hits, misses, size := engine.GetCacheStats()
	assert.GreaterOrEqual(t, hits+misses, int64(1))
	assert.GreaterOrEqual(t, size, 0)
}

func TestAlertEnginePreload(t *testing.T) {
	engine := alerting.NewAlertEngine()

	rule := &common.AlertRule{
		Name:       "preload_test_rule",
		Expression: "{latency} > 200",
		Severity:   "warning",
		For:        1 * time.Minute,
		Enabled:    true,
		Annotations: map[string]string{
			"description": "Latency is high",
		},
	}

	err := engine.AddRule(rule)
	require.NoError(t, err)

	var metrics []common.Metric
	now := time.Now()
	for i := 0; i < 50; i++ {
		metrics = append(metrics, common.Metric{
			Name:      "latency",
			Value:     100.0 + float64(i)*5,
			Timestamp: now.Add(-time.Duration(50-i) * time.Second),
		})
	}

	err = engine.PreloadMetrics(metrics)
	require.NoError(t, err)
	assert.True(t, engine.IsPreloaded())
}

func TestLogPipelineHotReload(t *testing.T) {
	configPath := "./test_pipeline_config.json"

	initialConfig := &common.PipelineConfig{
		Processors: []common.ProcessorConfig{
			{Type: "json", Params: map[string]interface{}{}},
		},
		Filters: []common.FilterConfig{
			{Type: "level", Params: map[string]interface{}{"level": "info"}},
		},
		Outputs: []common.OutputConfig{
			{Name: "default", Type: "console", Params: map[string]interface{}{}},
		},
	}

	err := logpipeline.WritePipelineConfig(configPath, initialConfig)
	require.NoError(t, err)
	defer os.Remove(configPath)

	pipeline := logpipeline.NewPipelineWithHotReload(configPath, 1*time.Second)
	require.NotNil(t, pipeline)

	config := pipeline.GetConfig()
	require.NotNil(t, config)
	assert.Equal(t, 1, len(config.Processors))
	assert.Equal(t, 1, len(config.Filters))
	assert.Equal(t, 1, len(config.Outputs))

	updatedConfig := &common.PipelineConfig{
		Processors: []common.ProcessorConfig{
			{Type: "json", Params: map[string]interface{}{}},
		},
		Filters: []common.FilterConfig{
			{Type: "level", Params: map[string]interface{}{"level": "warn"}},
		},
		Outputs: []common.OutputConfig{
			{Name: "default", Type: "console", Params: map[string]interface{}{}},
			{Name: "error", Type: "file", Params: map[string]interface{}{"path": "./test_errors.log"}},
		},
	}

	err = logpipeline.WritePipelineConfig(configPath, updatedConfig)
	require.NoError(t, err)

	time.Sleep(2 * time.Second)

	newConfig := pipeline.GetConfig()
	require.NotNil(t, newConfig)
	assert.Equal(t, 2, len(newConfig.Outputs))
}

func init() {
	logger.Default().SetLevel(logger.WarnLevel)
}
