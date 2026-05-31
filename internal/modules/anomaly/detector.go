package anomaly

import (
	"context"
	"fmt"
	"math"
	"sort"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
)

type Detector interface {
	Detect(ctx context.Context, metricName string, values []float64) (*domain.AnomalyResult, error)
}

type ThreeSigmaDetector struct{}
type EWMADetector struct {
	alpha float64
}
type IsolationForestDetector struct {
	trees     int
	sampleSize int
}

type AnomalyDetector struct {
	detectors map[domain.AnomalyAlgorithm]Detector
}

func NewAnomalyDetector() *AnomalyDetector {
	return &AnomalyDetector{
		detectors: map[domain.AnomalyAlgorithm]Detector{
			domain.AnomalyAlgorithm3Sigma:      &ThreeSigmaDetector{},
			domain.AnomalyAlgorithmEWMA:        &EWMADetector{alpha: 0.3},
			domain.AnomalyAlgorithmIsolationForest: &IsolationForestDetector{trees: 100, sampleSize: 256},
		},
	}
}

func (d *ThreeSigmaDetector) Detect(ctx context.Context, metricName string, values []float64) (*domain.AnomalyResult, error) {
	if len(values) == 0 {
		return nil, fmt.Errorf("no data points provided")
	}

	mean, stdDev := calculateMeanAndStdDev(values)
	currentValue := values[len(values)-1]
	zScore := 0.0
	if stdDev > 0 {
		zScore = math.Abs(currentValue - mean) / stdDev
	}

	isAnomaly := zScore > 3
	severity := domain.AnomalySeverityInfo
	if zScore > 4 {
		severity = domain.AnomalySeverityCritical
	} else if zScore > 3 {
		severity = domain.AnomalySeverityWarning
	}

	result := &domain.AnomalyResult{
		ResultID:     uuid.New().String(),
		MetricName:   metricName,
		Algorithm:    domain.AnomalyAlgorithm3Sigma,
		Severity:     severity,
		CurrentValue: currentValue,
		ExpectedLow:  mean - 3*stdDev,
		ExpectedHigh: mean + 3*stdDev,
		Score:        zScore,
		IsAnomaly:    isAnomaly,
		Timestamp:    time.Now(),
		DetectedAt:   time.Now(),
	}

	return result, nil
}

func (d *EWMADetector) Detect(ctx context.Context, metricName string, values []float64) (*domain.AnomalyResult, error) {
	if len(values) == 0 {
		return nil, fmt.Errorf("no data points provided")
	}

	var ewma float64
	var ewmaVar float64
	const initialBurnIn = 10

	for i, val := range values {
		if i == 0 {
			ewma = val
			ewmaVar = 0
		} else {
			diff := val - ewma
			ewma = ewma + d.alpha*diff
			if i > initialBurnIn {
				ewmaVar = (1 - d.alpha) * (ewmaVar + d.alpha*diff*diff)
			}
		}
	}

	currentValue := values[len(values)-1]
	stdDev := math.Sqrt(ewmaVar)
	upperBound := ewma + 3*stdDev
	lowerBound := ewma - 3*stdDev

	isAnomaly := currentValue > upperBound || currentValue < lowerBound
	severity := domain.AnomalySeverityInfo
	if stdDev > 0 {
		deviation := math.Abs(currentValue-ewma) / stdDev
		if deviation > 4 {
			severity = domain.AnomalySeverityCritical
		} else if deviation > 3 {
			severity = domain.AnomalySeverityWarning
		}
	}

	result := &domain.AnomalyResult{
		ResultID:     uuid.New().String(),
		MetricName:   metricName,
		Algorithm:    domain.AnomalyAlgorithmEWMA,
		Severity:     severity,
		CurrentValue: currentValue,
		ExpectedLow:  lowerBound,
		ExpectedHigh: upperBound,
		Score:        ewmaVar,
		IsAnomaly:    isAnomaly,
		Timestamp:    time.Now(),
		DetectedAt:   time.Now(),
	}

	return result, nil
}

func (d *IsolationForestDetector) Detect(ctx context.Context, metricName string, values []float64) (*domain.AnomalyResult, error) {
	if len(values) == 0 {
		return nil, fmt.Errorf("no data points provided")
	}

	anomalyScore := d.calculateAnomalyScore(values)
	currentValue := values[len(values)-1]

	mean, stdDev := calculateMeanAndStdDev(values)
	isAnomaly := anomalyScore > 0.6
	severity := domain.AnomalySeverityInfo
	if anomalyScore > 0.8 {
		severity = domain.AnomalySeverityCritical
	} else if anomalyScore > 0.6 {
		severity = domain.AnomalySeverityWarning
	}

	result := &domain.AnomalyResult{
		ResultID:     uuid.New().String(),
		MetricName:   metricName,
		Algorithm:    domain.AnomalyAlgorithmIsolationForest,
		Severity:     severity,
		CurrentValue: currentValue,
		ExpectedLow:  mean - 2*stdDev,
		ExpectedHigh: mean + 2*stdDev,
		Score:        anomalyScore,
		IsAnomaly:    isAnomaly,
		Timestamp:    time.Now(),
		DetectedAt:   time.Now(),
	}

	return result, nil
}

func (d *IsolationForestDetector) calculateAnomalyScore(values []float64) float64 {
	if len(values) < 2 {
		return 0.5
	}

	mean, stdDev := calculateMeanAndStdDev(values)
	if stdDev == 0 {
		return 0
	}

	currentValue := values[len(values)-1]
	zScore := math.Abs(currentValue-mean) / stdDev

	score := 1 - math.Exp(-zScore/3)
	return score
}

func (d *AnomalyDetector) DetectWithAlgorithm(ctx context.Context, metricName string, values []float64, algorithm domain.AnomalyAlgorithm) (*domain.AnomalyResult, error) {
	detector, exists := d.detectors[algorithm]
	if !exists {
		return nil, fmt.Errorf("unsupported algorithm: %s", algorithm)
	}

	result, err := detector.Detect(ctx, metricName, values)
	if err != nil {
		return nil, err
	}

	if err := database.DB.WithContext(ctx).Create(result).Error; err != nil {
		logger.Error("Failed to save anomaly result", zap.Error(err))
	}

	if result.IsAnomaly {
		logger.Warn("Anomaly detected",
			zap.String("metric", metricName),
			zap.String("algorithm", string(algorithm)),
			zap.Float64("value", result.CurrentValue),
			zap.Float64("score", result.Score))
	}

	return result, nil
}

func (d *AnomalyDetector) DetectAll(ctx context.Context, metricName string, values []float64) ([]*domain.AnomalyResult, error) {
	var results []*domain.AnomalyResult

	for algorithm := range d.detectors {
		result, err := d.DetectWithAlgorithm(ctx, metricName, values, algorithm)
		if err != nil {
			logger.Error("Anomaly detection failed",
				zap.String("algorithm", string(algorithm)),
				zap.Error(err))
			continue
		}
		results = append(results, result)
	}

	return results, nil
}

func (d *AnomalyDetector) UpdateBaseline(ctx context.Context, metricName string, values []float64) (*domain.MetricBaseline, error) {
	if len(values) == 0 {
		return nil, fmt.Errorf("no data points provided")
	}

	sortedValues := make([]float64, len(values))
	copy(sortedValues, values)
	sort.Float64sortedValues)

	mean, stdDev := calculateMeanAndStdDev(values)
	minVal := sortedValues[0]
	maxVal := sortedValues[len(sortedValues)-1]
	p5 := percentile(sortedValues, 5)
	p95 := percentile(sortedValues, 95)

	baseline := &domain.MetricBaseline{
		BaselineID:  uuid.New().String(),
		MetricName:  metricName,
		Mean:        mean,
		StdDev:      stdDev,
		Min:         minVal,
		Max:         maxVal,
		Percentile5: p5,
		Percentile95: p95,
		WindowStart: time.Now().Add(-time.Hour),
		WindowEnd:   time.Now(),
		SampleCount: int64(len(values)),
		UpdatedAt:   time.Now(),
	}

	var existingBaseline domain.MetricBaseline
	if err := database.DB.WithContext(ctx).Where("metric_name = ?", metricName).First(&existingBaseline).Error; err == nil {
		baseline.BaselineID = existingBaseline.BaselineID
		if err := database.DB.WithContext(ctx).Model(&existingBaseline).Updates(baseline).Error; err != nil {
			return nil, fmt.Errorf("update baseline failed: %w", err)
		}
	} else {
		if err := database.DB.WithContext(ctx).Create(baseline).Error; err != nil {
			return nil, fmt.Errorf("create baseline failed: %w", err)
		}
	}

	logger.Info("Metric baseline updated",
		zap.String("metric", metricName),
		zap.Float64("mean", mean),
		zap.Float64("stddev", stdDev))

	return baseline, nil
}

func (d *AnomalyDetector) GetBaseline(ctx context.Context, metricName string) (*domain.MetricBaseline, error) {
	var baseline domain.MetricBaseline
	if err := database.DB.WithContext(ctx).Where("metric_name = ?", metricName).First(&baseline).Error; err != nil {
		return nil, fmt.Errorf("get baseline failed: %w", err)
	}
	return &baseline, nil
}

func (d *AnomalyDetector) ListResults(ctx context.Context, metricName string, isAnomaly *bool, offset, limit int) ([]domain.AnomalyResult, int64, error) {
	var results []domain.AnomalyResult
	var total int64

	query := database.DB.WithContext(ctx).Model(&domain.AnomalyResult{})
	if metricName != "" {
		query = query.Where("metric_name = ?", metricName)
	}
	if isAnomaly != nil {
		query = query.Where("is_anomaly = ?", *isAnomaly)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count anomaly results failed: %w", err)
	}

	if err := query.Order("detected_at DESC").Offset(offset).Limit(limit).Find(&results).Error; err != nil {
		return nil, 0, fmt.Errorf("list anomaly results failed: %w", err)
	}

	return results, total, nil
}

func (d *AnomalyDetector) GetResult(ctx context.Context, resultID string) (*domain.AnomalyResult, error) {
	var result domain.AnomalyResult
	if err := database.DB.WithContext(ctx).Where("result_id = ?", resultID).First(&result).Error; err != nil {
		return nil, fmt.Errorf("get anomaly result failed: %w", err)
	}
	return &result, nil
}

func calculateMeanAndStdDev(values []float64) (float64, float64) {
	if len(values) == 0 {
		return 0, 0
	}

	sum := 0.0
	for _, v := range values {
		sum += v
	}
	mean := sum / float64(len(values))

	variance := 0.0
	for _, v := range values {
		diff := v - mean
		variance += diff * diff
	}
	variance /= float64(len(values))

	return mean, math.Sqrt(variance)
}

func percentile(sortedValues []float64, p float64) float64 {
	if len(sortedValues) == 0 {
		return 0
	}
	if p <= 0 {
		return sortedValues[0]
	}
	if p >= 100 {
		return sortedValues[len(sortedValues)-1]
	}

	index := (p / 100) * float64(len(sortedValues)-1)
	lower := int(math.Floor(index))
	upper := int(math.Ceil(index))

	if lower == upper {
		return sortedValues[lower]
	}

	weight := index - float64(lower)
	return sortedValues[lower]*(1-weight) + sortedValues[upper]*weight
}
