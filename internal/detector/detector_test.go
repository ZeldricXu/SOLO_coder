package detector

import (
	"context"
	"fmt"
	"sort"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
	"github.com/datateam/loganalyzer/internal/storage"
	"github.com/datateam/loganalyzer/internal/testdata"
)

func TestCalculateZScore_NormalDistribution(t *testing.T) {
	data := []float64{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}
	mean := 5.5
	stdDev := 2.872

	testCases := []struct {
		name           string
		value          float64
		expectedZScore float64
	}{
		{"at mean", 5.5, 0.0},
		{"one std dev above", mean + stdDev, 1.0},
		{"one std dev below", mean - stdDev, 1.0},
		{"two std dev above", mean + 2*stdDev, 2.0},
		{"three std dev above", mean + 3*stdDev, 3.0},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			score := CalculateZScore(data, tc.value)
			assert.InDelta(t, tc.expectedZScore, score, 0.1)
		})
	}
}

func TestCalculateZScore_AnomalyDetection(t *testing.T) {
	normalData := []float64{1.2, 1.5, 1.3, 1.4, 1.2, 1.6, 1.3, 1.4, 1.5, 1.2, 1.3, 1.4, 1.1, 1.2, 1.3}
	anomalousValue := 10.0

	normalScore := CalculateZScore(normalData, 1.4)
	anomalyScore := CalculateZScore(normalData, anomalousValue)

	assert.Less(t, normalScore, 2.0, "Normal value should have low Z-score")
	assert.Greater(t, anomalyScore, 3.0, "Anomalous value should have high Z-score")
}

func TestCalculateMAD_RobustToOutliers(t *testing.T) {
	dataWithOutliers := []float64{1, 2, 3, 4, 5, 6, 7, 8, 9, 100}
	normalValue := 5.0
	anomalousValue := 100.0

	normalScore := CalculateMAD(dataWithOutliers, normalValue)
	anomalyScore := CalculateMAD(dataWithOutliers, anomalousValue)

	assert.Less(t, normalScore, 2.0, "Normal value should have low MAD score")
	assert.Greater(t, anomalyScore, 3.0, "Anomalous value should have high MAD score")
}

func TestCalculateMAD_CompareToZScore_OutlierSensitivity(t *testing.T) {
	data := []float64{1, 2, 3, 4, 5, 6, 7, 8, 9, 100}

	zScoreNormal := CalculateZScore(data, 5)
	madScoreNormal := CalculateMAD(data, 5)

	zScoreAnomaly := CalculateZScore(data, 100)
	madScoreAnomaly := CalculateMAD(data, 100)

	assert.Greater(t, madScoreAnomaly/madScoreNormal, zScoreAnomaly/zScoreNormal,
		"MAD should be more sensitive to outliers in presence of extreme values")
}

func TestDetectionEngine_ColdStart_InsufficientData_NoAlert(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	rule := config.DetectionRule{
		ID:              "cold-start-test",
		Name:            "Cold Start Test",
		Enabled:         true,
		Type:            string(MetricErrorRate),
		ServiceName:     "test-service",
		Metric:          string(MetricErrorRate),
		Threshold:       3.0,
		WindowSize:      5 * time.Minute,
		Algorithm:       string(AlgorithmZScore),
		Severity:        string(models.SeverityHigh),
		MinObservations: 30,
	}

	cfg := config.DetectionConfig{
		Algorithm:  string(AlgorithmZScore),
		WindowSize: 5 * time.Minute,
		SlideStep:  50 * time.Millisecond,
		Rules:      []config.DetectionRule{rule},
	}

	input := make(chan *models.LogEvent, 100)
	engine, err := NewDetectionEngine(cfg, mockRedis, input)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	err = engine.Start(ctx)
	require.NoError(t, err)

	for i := 0; i < 10; i++ {
		level := models.LevelInfo
		if i%5 == 0 {
			level = models.LevelError
		}
		event := testdata.NewLogEvent(
			testdata.WithServiceName("test-service"),
			testdata.WithLevel(level),
			testdata.WithTimestamp(time.Now().Add(-time.Duration(i) * time.Second)),
		)
		input <- event
	}

	time.Sleep(2 * time.Second)
	cancel()

	alertCount := 0
	timeout := time.After(500 * time.Millisecond)
collectAlerts:
	for {
		select {
		case alert := <-engine.Alerts():
			if alert != nil {
				alertCount++
			}
		case <-timeout:
			break collectAlerts
		}
	}

	assert.Equal(t, 0, alertCount, "No alerts should be triggered during cold start with insufficient data")
}

func TestDetectionEngine_SlidingWindow_ZScoreAnomaly(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	rule := config.DetectionRule{
		ID:              "error-rate-test",
		Name:            "Error Rate Spike",
		Enabled:         true,
		Type:            string(MetricErrorRate),
		ServiceName:     "api-gateway",
		Metric:          string(MetricErrorRate),
		Threshold:       3.0,
		WindowSize:      5 * time.Minute,
		Algorithm:       string(AlgorithmZScore),
		Severity:        string(models.SeverityHigh),
		MinObservations: 10,
	}

	cfg := config.DetectionConfig{
		Algorithm:  string(AlgorithmZScore),
		WindowSize: 5 * time.Minute,
		SlideStep:  50 * time.Millisecond,
		Rules:      []config.DetectionRule{rule},
	}

	input := make(chan *models.LogEvent, 1000)
	engine, err := NewDetectionEngine(cfg, mockRedis, input)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = engine.Start(ctx)
	require.NoError(t, err)

	baseTime := time.Now().Add(-3 * time.Minute)
	for i := 0; i < 60; i++ {
		level := models.LevelInfo
		if i%20 == 0 {
			level = models.LevelError
		}
		event := testdata.NewLogEvent(
			testdata.WithServiceName("api-gateway"),
			testdata.WithLevel(level),
			testdata.WithTimestamp(baseTime.Add(time.Duration(i) * time.Second)),
		)
		input <- event
	}

	for i := 0; i < 30; i++ {
		level := models.LevelError
		if i%3 == 0 {
			level = models.LevelInfo
		}
		event := testdata.NewLogEvent(
			testdata.WithServiceName("api-gateway"),
			testdata.WithLevel(level),
			testdata.WithTimestamp(baseTime.Add(60*time.Second + time.Duration(i)*time.Second)),
		)
		input <- event
	}

	time.Sleep(2 * time.Second)
	cancel()

	alerts := make([]*models.Alert, 0)
	timeout := time.After(500 * time.Millisecond)
collectAlerts:
	for {
		select {
		case alert := <-engine.Alerts():
			if alert != nil {
				alerts = append(alerts, alert)
			}
		case <-timeout:
			break collectAlerts
		}
	}

	assert.GreaterOrEqual(t, len(alerts), 1, "Should detect at least one anomaly during error rate spike")
	if len(alerts) > 0 {
		assert.Equal(t, models.AlertTypeErrorRate, alerts[0].AlertType)
		assert.Equal(t, models.SeverityHigh, alerts[0].Severity)
		assert.Greater(t, alerts[0].ZScore, 3.0, "Z-score should exceed threshold")
	}
}

func TestDetectionEngine_P99LatencyAnomaly(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	rule := config.DetectionRule{
		ID:              "p99-latency-test",
		Name:            "P99 Latency Spike",
		Enabled:         true,
		Type:            string(MetricP99Latency),
		ServiceName:     "api-gateway",
		Metric:          string(MetricP99Latency),
		Threshold:       3.0,
		WindowSize:      10 * time.Minute,
		Algorithm:       string(AlgorithmZScore),
		Severity:        string(models.SeverityHigh),
		MinObservations: 20,
	}

	cfg := config.DetectionConfig{
		Algorithm:  string(AlgorithmZScore),
		WindowSize: 5 * time.Minute,
		SlideStep:  50 * time.Millisecond,
		Rules:      []config.DetectionRule{rule},
	}

	input := make(chan *models.LogEvent, 1000)
	engine, err := NewDetectionEngine(cfg, mockRedis, input)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = engine.Start(ctx)
	require.NoError(t, err)

	key := fmt.Sprintf("%s:%s:%s", rule.ServiceName, MetricP99Latency, rule.ID)
	baseTime := time.Now().Add(-3 * time.Minute)

	for i := 0; i < 30; i++ {
		window := baseTime.Add(-time.Duration(i+1) * time.Minute)
		for j := 0; j < 5; j++ {
			mockRedis.Mock().AddWindowValue(ctx, key, window, 100+float64(j)*2)
		}
	}

	for i := 0; i < 100; i++ {
		respTime := int64(100 + (i % 10) * 1)
		event := testdata.NewLogEvent(
			testdata.WithServiceName("api-gateway"),
			testdata.WithResponseTime(respTime),
			testdata.WithTimestamp(baseTime.Add(time.Duration(i) * time.Second)),
		)
		input <- event
	}

	for i := 0; i < 30; i++ {
		respTime := int64(1000)
		event := testdata.NewLogEvent(
			testdata.WithServiceName("api-gateway"),
			testdata.WithResponseTime(respTime),
			testdata.WithTimestamp(baseTime.Add(100*time.Second + time.Duration(i)*time.Second)),
		)
		input <- event
	}

	time.Sleep(2 * time.Second)
	cancel()

	alerts := make([]*models.Alert, 0)
	timeout := time.After(500 * time.Millisecond)
collectAlerts:
	for {
		select {
		case alert := <-engine.Alerts():
			if alert != nil {
				alerts = append(alerts, alert)
			}
		case <-timeout:
			break collectAlerts
		}
	}

	assert.GreaterOrEqual(t, len(alerts), 1, "Should detect P99 latency anomaly")
	if len(alerts) > 0 {
		assert.Greater(t, alerts[0].MetricValue, 300.0, "P99 value should be elevated")
	}
}

func TestDetectionEngine_ConcurrentWindowUpdates_DataConsistency(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	rule := config.DetectionRule{
		ID:              "concurrent-test",
		Name:            "Concurrent Window Test",
		Enabled:         true,
		Type:            string(MetricErrorRate),
		ServiceName:     "concurrent-service",
		Metric:          string(MetricErrorRate),
		Threshold:       3.0,
		WindowSize:      5 * time.Minute,
		Algorithm:       string(AlgorithmZScore),
		Severity:        string(models.SeverityHigh),
		MinObservations: 50,
	}

	cfg := config.DetectionConfig{
		Algorithm:  string(AlgorithmZScore),
		WindowSize: 5 * time.Minute,
		SlideStep:  50 * time.Millisecond,
		Rules:      []config.DetectionRule{rule},
	}

	input := make(chan *models.LogEvent, 10000)
	engine, err := NewDetectionEngine(cfg, mockRedis, input)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	err = engine.Start(ctx)
	require.NoError(t, err)

	const numWriters = 10
	const eventsPerWriter = 500
	var writeComplete int32
	var wg sync.WaitGroup

	baseTime := time.Now().Add(-1 * time.Hour)

	for w := 0; w < numWriters; w++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			for i := 0; i < eventsPerWriter; i++ {
				level := models.LevelInfo
				if i%10 == 0 {
					level = models.LevelError
				}
				event := testdata.NewLogEvent(
					testdata.WithServiceName("concurrent-service"),
					testdata.WithLevel(level),
					testdata.WithTimestamp(baseTime.Add(time.Duration(workerID*eventsPerWriter+i) * time.Millisecond)),
				)
				input <- event
			}
			atomic.AddInt32(&writeComplete, 1)
		}(w)
	}

	wg.Wait()
	assert.Equal(t, int32(numWriters), atomic.LoadInt32(&writeComplete))

	engine.windowMu.RLock()
	windowDataCount := len(engine.windowData)
	engine.windowMu.RUnlock()

	assert.Greater(t, windowDataCount, 0, "Window data should be populated")

	for key, wd := range engine.windowData {
		engine.windowMu.RLock()
		count := wd.Count
		engine.windowMu.RUnlock()

		assert.Greater(t, count, int64(0), fmt.Sprintf("Window %s should have data", key))
	}
}

func TestDetectionEngine_AlgorithmSwitch_ZScoreToMAD(t *testing.T) {
	data := []float64{100, 102, 98, 101, 99, 103, 97, 100, 101, 99, 102, 98, 100, 101, 99}
	anomaly := 150.0

	zScore := CalculateZScore(data, anomaly)
	madScore := CalculateMAD(data, anomaly)

	assert.Greater(t, zScore, 3.0, "Z-score should detect anomaly")
	assert.Greater(t, madScore, 3.0, "MAD should detect anomaly")
	assert.NotEqual(t, zScore, madScore, "Z-score and MAD should produce different scores")
}

func TestPercentile_Calculation(t *testing.T) {
	data := make([]float64, 100)
	for i := 0; i < 100; i++ {
		data[i] = float64(i + 1)
	}
	sort.Float64s(data)

	p50 := Percentile(data, 50)
	p90 := Percentile(data, 90)
	p95 := Percentile(data, 95)
	p99 := Percentile(data, 99)

	assert.InDelta(t, 50.5, p50, 1.0, "P50 should be around 50")
	assert.InDelta(t, 90.5, p90, 1.0, "P90 should be around 90")
	assert.InDelta(t, 95.5, p95, 1.0, "P95 should be around 95")
	assert.InDelta(t, 99.5, p99, 1.0, "P99 should be around 99")
}

func TestDetectionEngine_MultipleRules_IndependentDetection(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	rules := []config.DetectionRule{
		{
			ID:              "rule-1",
			Name:            "Service A Error Rate",
			Enabled:         true,
			Type:            string(MetricErrorRate),
			ServiceName:     "service-a",
			Metric:          string(MetricErrorRate),
			Threshold:       3.0,
			WindowSize:      5 * time.Minute,
			Algorithm:       string(AlgorithmZScore),
			Severity:        string(models.SeverityHigh),
			MinObservations: 10,
		},
		{
			ID:              "rule-2",
			Name:            "Service B Error Rate",
			Enabled:         true,
			Type:            string(MetricErrorRate),
			ServiceName:     "service-b",
			Metric:          string(MetricErrorRate),
			Threshold:       3.0,
			WindowSize:      5 * time.Minute,
			Algorithm:       string(AlgorithmZScore),
			Severity:        string(models.SeverityCritical),
			MinObservations: 10,
		},
	}

	cfg := config.DetectionConfig{
		Algorithm:  string(AlgorithmZScore),
		WindowSize: 5 * time.Minute,
		SlideStep:  50 * time.Millisecond,
		Rules:      rules,
	}

	input := make(chan *models.LogEvent, 1000)
	engine, err := NewDetectionEngine(cfg, mockRedis, input)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = engine.Start(ctx)
	require.NoError(t, err)

	baseTime := time.Now().Add(-3 * time.Minute)
	for i := 0; i < 40; i++ {
		levelA := models.LevelInfo
		if i%30 == 0 {
			levelA = models.LevelError
		}
		eventA := testdata.NewLogEvent(
			testdata.WithServiceName("service-a"),
			testdata.WithLevel(levelA),
			testdata.WithTimestamp(baseTime.Add(time.Duration(i) * time.Second)),
		)
		input <- eventA

		eventB := testdata.NewLogEvent(
			testdata.WithServiceName("service-b"),
			testdata.WithLevel(models.LevelInfo),
			testdata.WithTimestamp(baseTime.Add(time.Duration(i) * time.Second)),
		)
		input <- eventB
	}

	for i := 0; i < 20; i++ {
		eventA := testdata.NewLogEvent(
			testdata.WithServiceName("service-a"),
			testdata.WithLevel(models.LevelError),
			testdata.WithTimestamp(baseTime.Add(40*time.Second + time.Duration(i)*time.Second)),
		)
		input <- eventA
	}

	time.Sleep(2 * time.Second)
	cancel()

	alerts := make([]*models.Alert, 0)
	timeout := time.After(500 * time.Millisecond)
collectAlerts:
	for {
		select {
		case alert := <-engine.Alerts():
			if alert != nil {
				alerts = append(alerts, alert)
			}
		case <-timeout:
			break collectAlerts
		}
	}

	serviceAAlerts := 0
	serviceBAlerts := 0
	for _, alert := range alerts {
		switch alert.ServiceName {
		case "service-a":
			serviceAAlerts++
		case "service-b":
			serviceBAlerts++
		}
	}

	assert.GreaterOrEqual(t, serviceAAlerts, 1, "Service A should have alerts")
	assert.Equal(t, 0, serviceBAlerts, "Service B should have no alerts")
}

func TestCalculateZScore_InsufficientData(t *testing.T) {
	smallDataset := []float64{1.0}
	score := CalculateZScore(smallDataset, 100.0)
	assert.Equal(t, 0.0, score, "Should return 0 for insufficient data")

	emptyDataset := []float64{}
	score2 := CalculateZScore(emptyDataset, 100.0)
	assert.Equal(t, 0.0, score2, "Should return 0 for empty dataset")
}

func TestCalculateMAD_ZeroDeviation(t *testing.T) {
	constantData := []float64{5, 5, 5, 5, 5, 5, 5, 5, 5, 5}
	score := CalculateMAD(constantData, 5.0)
	assert.Equal(t, 0.0, score, "Should return 0 when all values are the same")
}
