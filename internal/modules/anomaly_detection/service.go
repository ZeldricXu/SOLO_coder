package anomaly_detection

import (
	"context"
	"math"
	"sync"
	"time"

	"loglevelplatform/internal/common/database"
	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/common/models"
	"loglevelplatform/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type DetectionAlgorithm string

const (
	AlgoThreshold     DetectionAlgorithm = "threshold"
	AlgoStdDev        DetectionAlgorithm = "stddev"
	AlgoMovingAverage DetectionAlgorithm = "moving_average"
	AlgoEWMA          DetectionAlgorithm = "ewma"
	AlgoIsolationForest DetectionAlgorithm = "isolation_forest"
	AlgoPercentile    DetectionAlgorithm = "percentile"
)

type Severity string

const (
	SeverityLow    Severity = "low"
	SeverityMedium Severity = "medium"
	SeverityHigh   Severity = "high"
	SeverityCritical Severity = "critical"
)

type Baseline struct {
	MetricName  string
	Algorithm   DetectionAlgorithm
	Mean        float64
	StdDev      float64
	Min         float64
	Max         float64
	Percentiles map[float64]float64
	LastUpdated time.Time
	DataPoints  []float64
}

type DetectionConfig struct {
	MetricName      string
	Algorithm       DetectionAlgorithm
	ThresholdMin    *float64
	ThresholdMax    *float64
	StdDevFactor    *float64
	WindowSize      int
	Alpha           float64
	Sensitivity     float64
	SeverityMapping map[float64]Severity
}

type Service struct {
	db          *gorm.DB
	baselines   map[string]*Baseline
	configs     map[string]*DetectionConfig
	history     map[string][]float64
	mu          sync.RWMutex
	maxHistory  int
}

var (
	instance *Service
	once     sync.Once
)

func NewService() *Service {
	once.Do(func() {
		instance = &Service{
			db:         database.GetDB(),
			baselines:  make(map[string]*Baseline),
			configs:    make(map[string]*DetectionConfig),
			history:    make(map[string][]float64),
			maxHistory: 10000,
		}
	})
	return instance
}

func (s *Service) RegisterConfig(config *DetectionConfig) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.configs[config.MetricName] = config
}

func (s *Service) GetConfig(metricName string) (*DetectionConfig, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	config, exists := s.configs[metricName]
	return config, exists
}

func (s *Service) RecordDataPoint(ctx context.Context, metricName string, value float64, tags map[string]string) {
	s.mu.Lock()
	s.history[metricName] = append(s.history[metricName], value)
	if len(s.history[metricName]) > s.maxHistory {
		s.history[metricName] = s.history[metricName][len(s.history[metricName])-s.maxHistory:]
	}
	s.mu.Unlock()

	s.updateBaseline(metricName)

	config, exists := s.GetConfig(metricName)
	if exists {
		go s.detectAnomaly(ctx, metricName, value, tags, config)
	}
}

func (s *Service) updateBaseline(metricName) {
	s.mu.RLock()
	data := make([]float64, len(s.history[metricName]))
	copy(data, s.history[metricName])
	s.mu.RUnlock()

	if len(data) < 10 {
		return
	}

	mean := utils.Average(data)
	stdDev := utils.StdDev(data)
	min := s.findMin(data)
	max := s.findMax(data)
	percentiles := map[float64]float64{
		50:  s.percentile(data, 50),
		90:  s.percentile(data, 90),
		95:  s.percentile(data, 95),
		99:  s.percentile(data, 99),
		99.9: s.percentile(data, 99.9),
	}

	baseline := &Baseline{
		MetricName:  metricName,
		Mean:        mean,
		StdDev:      stdDev,
		Min:         min,
		Max:         max,
		Percentiles: percentiles,
		LastUpdated: time.Now(),
		DataPoints:  data,
	}

	s.mu.Lock()
	s.baselines[metricName] = baseline
	s.mu.Unlock()
}

func (s *Service) detectAnomaly(ctx context.Context, metricName string, value float64, tags map[string]string, config *DetectionConfig) {
	log := logger.FromContext(ctx)

	var isAnomaly bool
	var expectedMin, expectedMax float64
	var severity Severity

	switch config.Algorithm {
	case AlgoThreshold:
		isAnomaly, expectedMin, expectedMax = s.detectThreshold(value, config)
	case AlgoStdDev:
		isAnomaly, expectedMin, expectedMax = s.detectStdDev(value, metricName, config)
	case AlgoMovingAverage:
		isAnomaly, expectedMin, expectedMax = s.detectMovingAverage(value, metricName, config)
	case AlgoEWMA:
		isAnomaly, expectedMin, expectedMax = s.detectEWMA(value, metricName, config)
	case AlgoPercentile:
		isAnomaly, expectedMin, expectedMax = s.detectPercentile(value, metricName, config)
	default:
		isAnomaly, expectedMin, expectedMax = s.detectStdDev(value, metricName, config)
	}

	if isAnomaly {
		severity = s.calculateSeverity(value, expectedMin, expectedMax, config)

		result := &models.AnomalyDetectionResult{
			ID:          utils.NewID("anom"),
			MetricName:  metricName,
			Algorithm:   string(config.Algorithm),
			Severity:    string(severity),
			IsAnomaly:   true,
			Value:       value,
			ExpectedMin: expectedMin,
			ExpectedMax: expectedMax,
			Timestamp:   time.Now(),
			Tags:        tags,
		}

		if err := s.db.Create(result).Error; err != nil {
			log.Error("failed to save anomaly detection result", zap.Error(err))
		}

		log.Warn("anomaly detected",
			zap.String("metric", metricName),
			zap.String("algorithm", string(config.Algorithm)),
			zap.Float64("value", value),
			zap.String("severity", string(severity)),
			zap.Float64("expected_min", expectedMin),
			zap.Float64("expected_max", expectedMax),
		)
	}
}

func (s *Service) detectThreshold(value float64, config *DetectionConfig) (bool, float64, float64) {
	min := -math.MaxFloat64
	max := math.MaxFloat64

	if config.ThresholdMin != nil {
		min = *config.ThresholdMin
	}
	if config.ThresholdMax != nil {
		max = *config.ThresholdMax
	}

	return value < min || value > max, min, max
}

func (s *Service) detectStdDev(value float64, metricName string, config *DetectionConfig) (bool, float64, float64) {
	s.mu.RLock()
	baseline, exists := s.baselines[metricName]
	s.mu.RUnlock()

	if !exists {
		return false, value, value
	}

	factor := 3.0
	if config.StdDevFactor != nil {
		factor = *config.StdDevFactor
	}

	expectedMin := baseline.Mean - factor*baseline.StdDev
	expectedMax := baseline.Mean + factor*baseline.StdDev

	return value < expectedMin || value > expectedMax, expectedMin, expectedMax
}

func (s *Service) detectMovingAverage(value float64, metricName string, config *DetectionConfig) (bool, float64, float64) {
	s.mu.RLock()
	data := s.history[metricName]
	s.mu.RUnlock()

	windowSize := config.WindowSize
	if windowSize <= 0 {
		windowSize = 50
	}

	if len(data) < windowSize {
		return false, value, value
	}

	recent := data[len(data)-windowSize:]
	ma := utils.Average(recent)
	stdDev := utils.StdDev(recent)

	sensitivity := config.Sensitivity
	if sensitivity <= 0 {
		sensitivity = 2.0
	}

	expectedMin := ma - sensitivity*stdDev
	expectedMax := ma + sensitivity*stdDev

	return value < expectedMin || value > expectedMax, expectedMin, expectedMax
}

func (s *Service) detectEWMA(value float64, metricName string, config *DetectionConfig) (bool, float64, float64) {
	s.mu.RLock()
	data := s.history[metricName]
	s.mu.RUnlock()

	if len(data) < 2 {
		return false, value, value
	}

	alpha := config.Alpha
	if alpha <= 0 || alpha >= 1 {
		alpha = 0.3
	}

	ewma := data[0]
	for i := 1; i < len(data); i++ {
		ewma = alpha*data[i] + (1-alpha)*ewma
	}

	sensitivity := config.Sensitivity
	if sensitivity <= 0 {
		sensitivity = 3.0
	}

	s.mu.RLock()
	baseline := s.baselines[metricName]
	s.mu.RUnlock()

	stdDev := 1.0
	if baseline != nil {
		stdDev = baseline.StdDev
	}

	expectedMin := ewma - sensitivity*stdDev
	expectedMax := ewma + sensitivity*stdDev

	return value < expectedMin || value > expectedMax, expectedMin, expectedMax
}

func (s *Service) detectPercentile(value float64, metricName string, config *DetectionConfig) (bool, float64, float64) {
	s.mu.RLock()
	baseline, exists := s.baselines[metricName]
	s.mu.RUnlock()

	if !exists {
		return false, value, value
	}

	sensitivity := config.Sensitivity
	if sensitivity <= 0 {
		sensitivity = 99.0
	}

	expectedMin := baseline.Percentiles[(100-sensitivity)/2]
	expectedMax := baseline.Percentiles[100-(100-sensitivity)/2]

	return value < expectedMin || value > expectedMax, expectedMin, expectedMax
}

func (s *Service) calculateSeverity(value, min, max float64, config *DetectionConfig) Severity {
	range_ := max - min
	if range_ <= 0 {
		range_ = 1
	}

	var deviation float64
	if value > max {
		deviation = (value - max) / range_
	} else if value < min {
		deviation = (min - value) / range_
	}

	if deviation >= 2.0 {
		return SeverityCritical
	} else if deviation >= 1.0 {
		return SeverityHigh
	} else if deviation >= 0.5 {
		return SeverityMedium
	}
	return SeverityLow
}

func (s *Service) GetBaseline(ctx context.Context, metricName string) (*Baseline, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	baseline, exists := s.baselines[metricName]
	if !exists {
		return nil, nil
	}

	result := *baseline
	result.DataPoints = nil
	return &result, nil
}

func (s *Service) ListBaselines(ctx context.Context) []Baseline {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]Baseline, 0, len(s.baselines))
	for _, baseline := range s.baselines {
		b := *baseline
		b.DataPoints = nil
		result = append(result, b)
	}
	return result
}

func (s *Service) GetDetectionResults(ctx context.Context, metricName, severity string, startTime, endTime time.Time, limit int) ([]models.AnomalyDetectionResult, error) {
	var results []models.AnomalyDetectionResult
	query := s.db.Model(&models.AnomalyDetectionResult{})

	if metricName != "" {
		query = query.Where("metric_name = ?", metricName)
	}
	if severity != "" {
		query = query.Where("severity = ?", severity)
	}
	if !startTime.IsZero() {
		query = query.Where("timestamp >= ?", startTime)
	}
	if !endTime.IsZero() {
		query = query.Where("timestamp <= ?", endTime)
	}

	if err := query.Order("timestamp DESC").Limit(limit).Find(&results).Error; err != nil {
		return nil, err
	}

	return results, nil
}

func (s *Service) TestDetection(ctx context.Context, metricName string, value float64) (map[string]interface{}, error) {
	config, exists := s.GetConfig(metricName)
	if !exists {
		return nil, nil
	}

	var isAnomaly bool
	var expectedMin, expectedMax float64

	switch config.Algorithm {
	case AlgoThreshold:
		isAnomaly, expectedMin, expectedMax = s.detectThreshold(value, config)
	case AlgoStdDev:
		isAnomaly, expectedMin, expectedMax = s.detectStdDev(value, metricName, config)
	case AlgoMovingAverage:
		isAnomaly, expectedMin, expectedMax = s.detectMovingAverage(value, metricName, config)
	case AlgoEWMA:
		isAnomaly, expectedMin, expectedMax = s.detectEWMA(value, metricName, config)
	case AlgoPercentile:
		isAnomaly, expectedMin, expectedMax = s.detectPercentile(value, metricName, config)
	default:
		isAnomaly, expectedMin, expectedMax = s.detectStdDev(value, metricName, config)
	}

	severity := s.calculateSeverity(value, expectedMin, expectedMax, config)

	return map[string]interface{}{
		"is_anomaly":    isAnomaly,
		"value":         value,
		"expected_min":  expectedMin,
		"expected_max":  expectedMax,
		"severity":      severity,
		"algorithm":     config.Algorithm,
	}, nil
}

func (s *Service) findMin(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	min := values[0]
	for _, v := range values[1:] {
		if v < min {
			min = v
		}
	}
	return min
}

func (s *Service) findMax(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	max := values[0]
	for _, v := range values[1:] {
		if v > max {
			max = v
		}
	}
	return max
}

func (s *Service) percentile(values []float64, p float64) float64 {
	if len(values) == 0 {
		return 0
	}

	sorted := make([]float64, len(values))
	copy(sorted, values)
	s.sortFloat64(sorted)

	index := int(p / 100.0 * float64(len(sorted)-1))
	if index >= len(sorted) {
		index = len(sorted) - 1
	}
	return sorted[index]
}

func (s *Service) sortFloat64(arr []float64) {
	for i := 0; i < len(arr); i++ {
		for j := i + 1; j < len(arr); j++ {
			if arr[i] > arr[j] {
				arr[i], arr[j] = arr[j], arr[i]
			}
		}
	}
}
