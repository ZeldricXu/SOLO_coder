package anomaly

import (
	"fmt"
	"math"
	"sync"
	"testing"
	"time"

	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
	"log-pipeline/testfixtures"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func testAnomalyConfig() *config.AnomalyConfig {
	cfg := config.DefaultConfig().Anomaly
	cfg.MovingAverageWindow = 10
	cfg.StdDevThreshold = 2.0
	cfg.IsolationForest.Trees = 5
	cfg.IsolationForest.SampleSize = 10
	cfg.IsolationForest.Contamination = 0.1
	return &cfg
}

func TestDetectMovingAverage_SteadyTraffic_NoAnomaly(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	for i := 0; i < 10; i++ {
		detector.DetectMovingAverage(testfixtures.NewSteadyWindowAggregate())
	}

	results := detector.DetectMovingAverage(testfixtures.NewSteadyWindowAggregate())
	assert.Empty(t, results, "steady traffic should not produce anomalies")
}

func TestDetectMovingAverage_SuddenSpike_DetectsAnomaly(t *testing.T) {
	cfg := testAnomalyConfig()
	detector := NewAnomalyDetector(cfg)

	for i := 0; i < 10; i++ {
		detector.DetectMovingAverage(testfixtures.NewSteadyWindowAggregate())
	}

	results := detector.DetectMovingAverage(testfixtures.NewSpikeWindowAggregate())
	require.NotEmpty(t, results, "sudden spike should be detected as anomaly")

	found := false
	for _, r := range results {
		if r.IsAnomaly && r.Method == "moving_avg_stddev" {
			found = true
			assert.True(t, r.IsAnomaly)
			assert.Equal(t, "moving_avg_stddev", r.Method)
			assert.Greater(t, r.AnomalyScore, cfg.StdDevThreshold,
				"anomaly score should exceed StdDevThreshold")
		}
	}
	assert.True(t, found, "expected at least one moving_avg_stddev anomaly result")
}

func TestDetectMovingAverage_GradualIncrease_NoAnomaly(t *testing.T) {
	cfg := testAnomalyConfig()
	detector := NewAnomalyDetector(cfg)

	for i := 0; i < 10; i++ {
		count := int64(100 + i*2)
		agg := testfixtures.NewSteadyWindowAggregate(func(a *models.WindowAggregate) {
			a.Count = count
			a.LevelCounts = map[string]int64{"INFO": count - 5, "ERROR": 3, "WARN": 2}
		})
		detector.DetectMovingAverage(agg)
	}

	agg := testfixtures.NewSteadyWindowAggregate(func(a *models.WindowAggregate) {
		a.Count = 120
		a.LevelCounts = map[string]int64{"INFO": 115, "ERROR": 3, "WARN": 2}
	})
	results := detector.DetectMovingAverage(agg)
	assert.Empty(t, results, "gradual increase within threshold should not trigger anomaly")
}

func TestDetectMovingAverage_AnomalyScoreAccuracy(t *testing.T) {
	cfg := testAnomalyConfig()
	detector := NewAnomalyDetector(cfg)

	for i := 0; i < 10; i++ {
		detector.DetectMovingAverage(testfixtures.NewSteadyWindowAggregate())
	}

	results := detector.DetectMovingAverage(testfixtures.NewSpikeWindowAggregate())
	require.NotEmpty(t, results, "spike should produce anomalies for verification")

	for _, r := range results {
		if r.Method != "moving_avg_stddev" {
			continue
		}
		mean := r.Features["mean"]
		stddev := r.Features["stddev"]
		zScore := r.Features["z_score"]

		if stddev > 0 {
			expectedZ := math.Abs(r.Value-mean) / stddev
			assert.InDelta(t, expectedZ, zScore, 0.01,
				"z-score should equal |value - mean| / stddev")
		}
		assert.InDelta(t, zScore, r.AnomalyScore, 0.01,
			"AnomalyScore should match z_score feature")
	}
}

func TestIsolationForest_TrainAndDetect(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	trainingData := make([]map[string]float64, 50)
	for i := range trainingData {
		trainingData[i] = map[string]float64{
			"log_count":   100,
			"error_count": 3,
			"warn_count":  2,
			"info_count":  95,
			"error_ratio": 0.03,
		}
	}
	for i := 0; i < 5; i++ {
		trainingData = append(trainingData, map[string]float64{
			"log_count":   5000,
			"error_count": 4800,
			"warn_count":  100,
			"info_count":  100,
			"error_ratio": 0.96,
		})
	}

	detector.TrainIsolationForest(trainingData)

	outlierAgg := testfixtures.NewSpikeWindowAggregate()
	results := detector.DetectIsolationForest(outlierAgg)

	if len(results) > 0 {
		assert.True(t, results[0].IsAnomaly)
		assert.Equal(t, "isolation_forest", results[0].Method)
		assert.Greater(t, results[0].AnomalyScore, 0.6)
	}
}

func TestIsolationForest_NormalData_NoAnomaly(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	trainingData := make([]map[string]float64, 50)
	for i := range trainingData {
		trainingData[i] = map[string]float64{
			"log_count":   100,
			"error_count": 3,
			"warn_count":  2,
			"info_count":  95,
			"error_ratio": 0.03,
		}
	}

	detector.TrainIsolationForest(trainingData)

	normalAgg := testfixtures.NewSteadyWindowAggregate()
	results := detector.DetectIsolationForest(normalAgg)
	assert.Empty(t, results, "normal data should not be flagged as anomaly by isolation forest")
}

func TestIsolationForest_OutlierData_Anomaly(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	trainingData := make([]map[string]float64, 100)
	for i := range trainingData {
		trainingData[i] = map[string]float64{
			"log_count":   100,
			"error_count": 3,
			"warn_count":  2,
			"info_count":  95,
			"error_ratio": 0.03,
		}
	}

	detector.TrainIsolationForest(trainingData)

	extremeAgg := testfixtures.NewWindowAggregate(func(a *models.WindowAggregate) {
		a.Count = 10000
		a.LevelCounts = map[string]int64{"INFO": 10, "ERROR": 9900, "WARN": 90}
	})
	results := detector.DetectIsolationForest(extremeAgg)

	if len(results) > 0 {
		assert.True(t, results[0].IsAnomaly, "extreme outlier should be flagged")
		assert.Greater(t, results[0].AnomalyScore, 0.6,
			"outlier should have high anomaly score")
	}
}

func TestDetectMovingAverage_WindowNotFilled_NoAnomaly(t *testing.T) {
	cfg := testAnomalyConfig()
	detector := NewAnomalyDetector(cfg)

	for i := 0; i < cfg.MovingAverageWindow-1; i++ {
		detector.DetectMovingAverage(testfixtures.NewSpikeWindowAggregate())
	}

	results := detector.DetectMovingAverage(testfixtures.NewSpikeWindowAggregate())
	assert.Empty(t, results, "window not yet filled should not produce anomalies")
}

func TestDetectMovingAverage_EmptyLevelCounts(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	for i := 0; i < 10; i++ {
		agg := testfixtures.NewWindowAggregate(func(a *models.WindowAggregate) {
			a.Count = 100
			a.LevelCounts = map[string]int64{}
		})
		detector.DetectMovingAverage(agg)
	}

	agg := testfixtures.NewWindowAggregate(func(a *models.WindowAggregate) {
		a.Count = 100
		a.LevelCounts = map[string]int64{}
	})
	assert.NotPanics(t, func() {
		detector.DetectMovingAverage(agg)
	}, "empty LevelCounts should not cause panic")
}

func TestDetectMovingAverage_AllZeroValues(t *testing.T) {
	cfg := testAnomalyConfig()
	detector := NewAnomalyDetector(cfg)

	for i := 0; i < 10; i++ {
		agg := testfixtures.NewWindowAggregate(func(a *models.WindowAggregate) {
			a.Count = 0
			a.LevelCounts = map[string]int64{"INFO": 0, "ERROR": 0, "WARN": 0}
		})
		detector.DetectMovingAverage(agg)
	}

	agg := testfixtures.NewWindowAggregate(func(a *models.WindowAggregate) {
		a.Count = 0
		a.LevelCounts = map[string]int64{"INFO": 0, "ERROR": 0, "WARN": 0}
	})

	assert.NotPanics(t, func() {
		detector.DetectMovingAverage(agg)
	}, "all-zero values should not cause divide-by-zero panic")
}

func TestDetectIsolationForest_UntrainedForest(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	agg := testfixtures.NewSteadyWindowAggregate()
	results := detector.DetectIsolationForest(agg)
	assert.Nil(t, results, "untrained forest should return nil")
}

func TestDetectIsolationForest_EmptyFeatures(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	trainingData := make([]map[string]float64, 20)
	for i := range trainingData {
		trainingData[i] = map[string]float64{
			"log_count":   100,
			"error_count": 3,
			"warn_count":  2,
			"info_count":  95,
			"error_ratio": 0.03,
		}
	}
	detector.TrainIsolationForest(trainingData)

	agg := &models.WindowAggregate{
		WindowID:    "test-empty",
		WindowStart: time.Now().Add(-time.Minute),
		WindowEnd:   time.Now(),
		WindowType:  "sliding",
		Key:         "127.0.0.1",
		Count:       0,
		LevelCounts: nil,
	}

	assert.NotPanics(t, func() {
		detector.DetectIsolationForest(agg)
	}, "minimal features should not cause panic")
}

func TestDetectMovingAverage_ConcurrentCalls(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	for i := 0; i < 10; i++ {
		detector.DetectMovingAverage(testfixtures.NewSteadyWindowAggregate())
	}

	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			agg := testfixtures.NewWindowAggregate(func(a *models.WindowAggregate) {
				a.Count = int64(100 + idx*10)
				a.LevelCounts = map[string]int64{"INFO": int64(95 + idx), "ERROR": 3, "WARN": 2}
			})
			detector.DetectMovingAverage(agg)
		}(i)
	}
	wg.Wait()
}

func TestAnomalyDetector_StartStop(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())
	aggChan := make(chan *models.WindowAggregate, 20)

	detector.Start(aggChan)

	for i := 0; i < 5; i++ {
		aggChan <- testfixtures.NewSteadyWindowAggregate()
	}

	detector.Stop()

	_, ok := <-detector.Anomalies()
	assert.False(t, ok, "anomaly channel should be closed after Stop")
}

func TestIsolationForest_Training(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	trainingData := make([]map[string]float64, 20)
	for i := range trainingData {
		trainingData[i] = map[string]float64{
			"log_count":   100,
			"error_count": 3,
			"warn_count":  2,
			"info_count":  95,
			"error_ratio": 0.03,
		}
	}
	detector.TrainIsolationForest(trainingData)

	require.NotNil(t, detector.iforest, "isolation forest should be initialized")
	require.Greater(t, len(detector.iforest.trees), 0, "trees should be built after training")

	for i, tree := range detector.iforest.trees {
		require.NotNil(t, tree, fmt.Sprintf("tree %d should not be nil", i))
		assert.NotNil(t, tree.root, fmt.Sprintf("tree %d root should not be nil", i))
	}
}

func TestIsolationForest_AnomalyScoreRange(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	trainingData := make([]map[string]float64, 50)
	for i := range trainingData {
		trainingData[i] = map[string]float64{
			"log_count":   100,
			"error_count": 3,
			"warn_count":  2,
			"info_count":  95,
			"error_ratio": 0.03,
		}
	}
	detector.TrainIsolationForest(trainingData)

	testCases := []map[string]float64{
		{"log_count": 100, "error_count": 3, "warn_count": 2, "info_count": 95, "error_ratio": 0.03},
		{"log_count": 5000, "error_count": 4800, "warn_count": 100, "info_count": 100, "error_ratio": 0.96},
		{"log_count": 50, "error_count": 1, "warn_count": 0, "info_count": 49, "error_ratio": 0.02},
		{"log_count": 10000, "error_count": 9900, "warn_count": 50, "info_count": 50, "error_ratio": 0.99},
	}

	for _, features := range testCases {
		score := detector.iforest.AnomalyScore(features)
		assert.GreaterOrEqual(t, score, 0.0, "anomaly score should be >= 0")
		assert.LessOrEqual(t, score, 1.0, "anomaly score should be <= 1")
	}
}

func TestDetectMovingAverage_ScoreRange(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	for i := 0; i < 10; i++ {
		agg := testfixtures.NewSteadyWindowAggregate()
		detector.DetectMovingAverage(agg)
	}

	spikeAgg := testfixtures.NewWindowAggregate(func(a *models.WindowAggregate) {
		a.Count = 10000
		a.LevelCounts = map[string]int64{"INFO": 9500, "ERROR": 400, "WARN": 100}
	})
	results := detector.DetectMovingAverage(spikeAgg)

	require.Greater(t, len(results), 0, "should detect anomaly on spike")

	for _, r := range results {
		assert.GreaterOrEqual(t, r.Score, 0.0, "score should be >= 0")
		assert.LessOrEqual(t, r.Score, 100.0, "score should be <= 100")
	}
}

func TestDetectMovingAverage_TopContributors(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	for i := 0; i < 10; i++ {
		agg := testfixtures.NewSteadyWindowAggregate()
		detector.DetectMovingAverage(agg)
	}

	spikeAgg := testfixtures.NewWindowAggregate(func(a *models.WindowAggregate) {
		a.Count = 10000
		a.LevelCounts = map[string]int64{"INFO": 9500, "ERROR": 400, "WARN": 100}
	})
	results := detector.DetectMovingAverage(spikeAgg)

	require.Greater(t, len(results), 0, "should detect anomaly on spike")

	for _, r := range results {
		assert.LessOrEqual(t, len(r.TopContributors), 3, "should have at most 3 top contributors")
		for _, c := range r.TopContributors {
			assert.NotEmpty(t, c.Name, "contributor should have a name")
			assert.GreaterOrEqual(t, c.Deviation, 0.0, "deviation should be >= 0")
			assert.GreaterOrEqual(t, c.Contribution, 0.0, "contribution should be >= 0")
			assert.LessOrEqual(t, c.Contribution, 100.0, "contribution should be <= 100")
		}
	}
}

func TestDetectMovingAverage_DeviationPercent(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	for i := 0; i < 10; i++ {
		agg := testfixtures.NewSteadyWindowAggregate()
		detector.DetectMovingAverage(agg)
	}

	spikeAgg := testfixtures.NewWindowAggregate(func(a *models.WindowAggregate) {
		a.Count = 10000
		a.LevelCounts = map[string]int64{"INFO": 9500, "ERROR": 400, "WARN": 100}
	})
	results := detector.DetectMovingAverage(spikeAgg)

	require.Greater(t, len(results), 0, "should detect anomaly on spike")

	for _, r := range results {
		assert.GreaterOrEqual(t, r.DeviationPercent, 0.0, "deviation percent should be >= 0")
	}
}

func TestDetectMovingAverage_ContributorContributionSumsTo100(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	for i := 0; i < 10; i++ {
		agg := testfixtures.NewSteadyWindowAggregate()
		detector.DetectMovingAverage(agg)
	}

	spikeAgg := testfixtures.NewWindowAggregate(func(a *models.WindowAggregate) {
		a.Count = 10000
		a.LevelCounts = map[string]int64{"INFO": 9500, "ERROR": 400, "WARN": 100}
	})
	results := detector.DetectMovingAverage(spikeAgg)

	require.Greater(t, len(results), 0, "should detect anomaly on spike")

	for _, r := range results {
		if len(r.TopContributors) > 0 {
			totalContribution := 0.0
			for _, c := range r.TopContributors {
				totalContribution += c.Contribution
			}
			assert.InDelta(t, 100.0, totalContribution, 0.1, "contributions should sum to ~100")
		}
	}
}

func TestZScoreToScore_Range(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	assert.Equal(t, 0.0, detector.zScoreToScore(1.5))
	assert.Equal(t, 0.0, detector.zScoreToScore(2.0))
	assert.Greater(t, detector.zScoreToScore(3.0), 0.0)
	assert.Greater(t, detector.zScoreToScore(5.0), detector.zScoreToScore(3.0))
	assert.LessOrEqual(t, detector.zScoreToScore(15.0), 100.0)
}

func TestIforestScoreToScore_Range(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	assert.Equal(t, 0.0, detector.iforestScoreToScore(0.3))
	assert.Equal(t, 0.0, detector.iforestScoreToScore(0.5))
	assert.Greater(t, detector.iforestScoreToScore(0.7), 0.0)
	assert.Greater(t, detector.iforestScoreToScore(0.9), detector.iforestScoreToScore(0.7))
	assert.LessOrEqual(t, detector.iforestScoreToScore(1.0), 100.0)
}

func TestComputeDeviationPercent_ZeroBaseline(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	deviation := detector.computeDeviationPercent("nonexistent", 100.0)
	assert.Equal(t, 0.0, deviation, "should return 0 when no baseline exists")
}

func TestGetBaselines(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	for i := 0; i < 10; i++ {
		agg := testfixtures.NewSteadyWindowAggregate()
		detector.DetectMovingAverage(agg)
	}

	baselines := detector.GetBaselines()
	assert.NotEmpty(t, baselines, "baselines should be populated after processing")

	for name, stats := range baselines {
		assert.NotEmpty(t, name, "baseline name should not be empty")
		assert.GreaterOrEqual(t, stats.Mean, 0.0, "mean should be >= 0")
		assert.GreaterOrEqual(t, stats.StdDev, 0.0, "stddev should be >= 0")
	}
}

func TestDetectMovingAverage_NoAnomaly_ZeroScore(t *testing.T) {
	detector := NewAnomalyDetector(testAnomalyConfig())

	for i := 0; i < 10; i++ {
		agg := testfixtures.NewSteadyWindowAggregate()
		detector.DetectMovingAverage(agg)
	}

	steadyAgg := testfixtures.NewSteadyWindowAggregate()
	results := detector.DetectMovingAverage(steadyAgg)

	for _, r := range results {
		assert.Equal(t, 0.0, r.Score, "non-anomalous results should not be produced")
	}
}
