package anomaly

import (
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/solocoder/backup-engine/internal/logger"
	"github.com/solocoder/backup-engine/pkg/common"
)

type AlgorithmType string

const (
	AlgorithmZScore        AlgorithmType = "zscore"
	AlgorithmPercentile    AlgorithmType = "percentile"
	AlgorithmMovingAverage AlgorithmType = "moving_average"
	AlgorithmExpSmoothing  AlgorithmType = "exp_smoothing"
	AlgorithmMAD           AlgorithmType = "mad"
)

type DetectorConfig struct {
	Algorithm     AlgorithmType `json:"algorithm"`
	Threshold     float64       `json:"threshold"`
	WindowSize    int           `json:"window_size"`
	Sensitivity   float64       `json:"sensitivity"`
	MinDataPoints int           `json:"min_data_points"`
}

type AnomalyResult struct {
	Metric     common.Metric       `json:"metric"`
	IsAnomaly  bool                `json:"is_anomaly"`
	Score      float64             `json:"score"`
	Algorithm  AlgorithmType       `json:"algorithm"`
	Expected   float64             `json:"expected"`
	Deviation  float64             `json:"deviation"`
	Baseline   map[string]float64  `json:"baseline"`
	Timestamp  time.Time           `json:"timestamp"`
}

type Detector interface {
	Detect(metric common.Metric, history []common.Metric) (*AnomalyResult, error)
	Name() AlgorithmType
}

type BaseDetector struct {
	config DetectorConfig
	mu     sync.RWMutex
}

type ZScoreDetector struct {
	BaseDetector
}

type PercentileDetector struct {
	BaseDetector
}

type MovingAverageDetector struct {
	BaseDetector
}

type ExpSmoothingDetector struct {
	BaseDetector
	alpha float64
	prev  float64
}

type MADDetector struct {
	BaseDetector
}

type DetectorEngine struct {
	detectors    map[AlgorithmType]Detector
	history      map[string][]common.Metric
	maxHistory   int
	mu           sync.RWMutex
	defaultAlgo  AlgorithmType
}

func NewZScoreDetector(config DetectorConfig) *ZScoreDetector {
	if config.Threshold == 0 {
		config.Threshold = 3.0
	}
	if config.MinDataPoints == 0 {
		config.MinDataPoints = 10
	}
	return &ZScoreDetector{BaseDetector: BaseDetector{config: config}}
}

func (d *ZScoreDetector) Name() AlgorithmType {
	return AlgorithmZScore
}

func (d *ZScoreDetector) Detect(metric common.Metric, history []common.Metric) (*AnomalyResult, error) {
	d.mu.RLock()
	defer d.mu.RUnlock()

	if len(history) < d.config.MinDataPoints {
		return &AnomalyResult{
			Metric:    metric,
			IsAnomaly: false,
			Score:     0,
			Algorithm: d.Name(),
		}, nil
	}

	values := make([]float64, len(history))
	for i, m := range history {
		values[i] = m.Value
	}

	mean := common.Mean(values)
	stdDev := common.StdDev(values)

	if stdDev == 0 {
		stdDev = 0.0001
	}

	zScore := math.Abs(metric.Value - mean) / stdDev
	isAnomaly := zScore > d.config.Threshold

	return &AnomalyResult{
		Metric:    metric,
		IsAnomaly: isAnomaly,
		Score:     zScore,
		Algorithm: d.Name(),
		Expected:  mean,
		Deviation: metric.Value - mean,
		Baseline: map[string]float64{
			"mean":    mean,
			"stddev":  stdDev,
			"zscore":  zScore,
			"threshold": d.config.Threshold,
		},
		Timestamp: time.Now(),
	}, nil
}

func NewPercentileDetector(config DetectorConfig) *PercentileDetector {
	if config.Threshold == 0 {
		config.Threshold = 99.0
	}
	if config.MinDataPoints == 0 {
		config.MinDataPoints = 20
	}
	return &PercentileDetector{BaseDetector: BaseDetector{config: config}}
}

func (d *PercentileDetector) Name() AlgorithmType {
	return AlgorithmPercentile
}

func (d *PercentileDetector) Detect(metric common.Metric, history []common.Metric) (*AnomalyResult, error) {
	d.mu.RLock()
	defer d.mu.RUnlock()

	if len(history) < d.config.MinDataPoints {
		return &AnomalyResult{
			Metric:    metric,
			IsAnomaly: false,
			Score:     0,
			Algorithm: d.Name(),
		}, nil
	}

	values := make([]float64, len(history))
	for i, m := range history {
		values[i] = m.Value
	}

	pHigh := common.Percentile(values, d.config.Threshold)
	pLow := common.Percentile(values, 100-d.config.Threshold)

	isAnomaly := metric.Value > pHigh || metric.Value < pLow
	score := 0.0
	if metric.Value > pHigh {
		score = (metric.Value - pHigh) / (pHigh + 0.0001)
	} else if metric.Value < pLow {
		score = (pLow - metric.Value) / (pLow + 0.0001)
	}

	return &AnomalyResult{
		Metric:    metric,
		IsAnomaly: isAnomaly,
		Score:     score,
		Algorithm: d.Name(),
		Expected:  common.Mean(values),
		Deviation: metric.Value - common.Mean(values),
		Baseline: map[string]float64{
			"percentile_high": pHigh,
			"percentile_low":  pLow,
			"threshold":       d.config.Threshold,
		},
		Timestamp: time.Now(),
	}, nil
}

func NewMovingAverageDetector(config DetectorConfig) *MovingAverageDetector {
	if config.WindowSize == 0 {
		config.WindowSize = 10
	}
	if config.Sensitivity == 0 {
		config.Sensitivity = 2.0
	}
	if config.MinDataPoints == 0 {
		config.MinDataPoints = config.WindowSize
	}
	return &MovingAverageDetector{BaseDetector: BaseDetector{config: config}}
}

func (d *MovingAverageDetector) Name() AlgorithmType {
	return AlgorithmMovingAverage
}

func (d *MovingAverageDetector) Detect(metric common.Metric, history []common.Metric) (*AnomalyResult, error) {
	d.mu.RLock()
	defer d.mu.RUnlock()

	if len(history) < d.config.MinDataPoints {
		return &AnomalyResult{
			Metric:    metric,
			IsAnomaly: false,
			Score:     0,
			Algorithm: d.Name(),
		}, nil
	}

	window := d.config.WindowSize
	if window > len(history) {
		window = len(history)
	}

	recent := history[len(history)-window:]
	values := make([]float64, len(recent))
	for i, m := range recent {
		values[i] = m.Value
	}

	ma := common.Mean(values)
	stdDev := common.StdDev(values)

	if stdDev == 0 {
		stdDev = 0.0001
	}

	deviation := math.Abs(metric.Value - ma)
	isAnomaly := deviation > d.config.Sensitivity*stdDev

	return &AnomalyResult{
		Metric:    metric,
		IsAnomaly: isAnomaly,
		Score:     deviation / stdDev,
		Algorithm: d.Name(),
		Expected:  ma,
		Deviation: metric.Value - ma,
		Baseline: map[string]float64{
			"moving_average": ma,
			"stddev":         stdDev,
			"sensitivity":    d.config.Sensitivity,
			"window_size":    float64(window),
		},
		Timestamp: time.Now(),
	}, nil
}

func NewExpSmoothingDetector(config DetectorConfig) *ExpSmoothingDetector {
	if config.Sensitivity == 0 {
		config.Sensitivity = 0.3
	}
	if config.MinDataPoints == 0 {
		config.MinDataPoints = 5
	}
	return &ExpSmoothingDetector{
		BaseDetector: BaseDetector{config: config},
		alpha:        0.3,
	}
}

func (d *ExpSmoothingDetector) Name() AlgorithmType {
	return AlgorithmExpSmoothing
}

func (d *ExpSmoothingDetector) Detect(metric common.Metric, history []common.Metric) (*AnomalyResult, error) {
	d.mu.Lock()
	defer d.mu.Unlock()

	if len(history) < d.config.MinDataPoints {
		if len(history) > 0 {
			d.prev = history[len(history)-1].Value
		}
		return &AnomalyResult{
			Metric:    metric,
			IsAnomaly: false,
			Score:     0,
			Algorithm: d.Name(),
		}, nil
	}

	if d.prev == 0 && len(history) > 0 {
		d.prev = history[0].Value
	}

	values := make([]float64, len(history))
	for i, m := range history {
		values[i] = m.Value
		d.prev = d.alpha*m.Value + (1-d.alpha)*d.prev
	}

	expected := d.alpha*metric.Value + (1-d.alpha)*d.prev
	deviation := math.Abs(metric.Value - expected)

	historyValues := make([]float64, len(history))
	for i, m := range history {
		historyValues[i] = m.Value
	}
	stdDev := common.StdDev(historyValues)
	if stdDev == 0 {
		stdDev = 0.0001
	}

	isAnomaly := deviation > d.config.Sensitivity*stdDev

	return &AnomalyResult{
		Metric:    metric,
		IsAnomaly: isAnomaly,
		Score:     deviation / stdDev,
		Algorithm: d.Name(),
		Expected:  expected,
		Deviation: metric.Value - expected,
		Baseline: map[string]float64{
			"exp_smoothed": d.prev,
			"alpha":        d.alpha,
			"stddev":       stdDev,
		},
		Timestamp: time.Now(),
	}, nil
}

func NewMADDetector(config DetectorConfig) *MADDetector {
	if config.Threshold == 0 {
		config.Threshold = 3.0
	}
	if config.MinDataPoints == 0 {
		config.MinDataPoints = 10
	}
	return &MADDetector{BaseDetector: BaseDetector{config: config}}
}

func (d *MADDetector) Name() AlgorithmType {
	return AlgorithmMAD
}

func (d *MADDetector) Detect(metric common.Metric, history []common.Metric) (*AnomalyResult, error) {
	d.mu.RLock()
	defer d.mu.RUnlock()

	if len(history) < d.config.MinDataPoints {
		return &AnomalyResult{
			Metric:    metric,
			IsAnomaly: false,
			Score:     0,
			Algorithm: d.Name(),
		}, nil
	}

	values := make([]float64, len(history))
	for i, m := range history {
		values[i] = m.Value
	}

	median := common.Percentile(values, 50)

	absDeviations := make([]float64, len(values))
	for i, v := range values {
		absDeviations[i] = math.Abs(v - median)
	}

	mad := common.Percentile(absDeviations, 50)
	if mad == 0 {
		mad = 0.0001
	}

	modifiedZScore := 0.6745 * (metric.Value - median) / mad
	isAnomaly := math.Abs(modifiedZScore) > d.config.Threshold

	return &AnomalyResult{
		Metric:    metric,
		IsAnomaly: isAnomaly,
		Score:     math.Abs(modifiedZScore),
		Algorithm: d.Name(),
		Expected:  median,
		Deviation: metric.Value - median,
		Baseline: map[string]float64{
			"median":         median,
			"mad":            mad,
			"modified_zscore": modifiedZScore,
			"threshold":      d.config.Threshold,
		},
		Timestamp: time.Now(),
	}, nil
}

func NewDetectorEngine(maxHistory int, defaultAlgo AlgorithmType) *DetectorEngine {
	engine := &DetectorEngine{
		detectors:   make(map[AlgorithmType]Detector),
		history:     make(map[string][]common.Metric),
		maxHistory:  maxHistory,
		defaultAlgo: defaultAlgo,
	}

	defaultConfig := DetectorConfig{}
	engine.RegisterDetector(NewZScoreDetector(defaultConfig))
	engine.RegisterDetector(NewPercentileDetector(defaultConfig))
	engine.RegisterDetector(NewMovingAverageDetector(defaultConfig))
	engine.RegisterDetector(NewExpSmoothingDetector(defaultConfig))
	engine.RegisterDetector(NewMADDetector(defaultConfig))

	return engine
}

func (e *DetectorEngine) RegisterDetector(detector Detector) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.detectors[detector.Name()] = detector
	logger.Info("Registered anomaly detector", map[string]interface{}{"algorithm": detector.Name()})
}

func (e *DetectorEngine) AddMetric(metric common.Metric) {
	e.mu.Lock()
	defer e.mu.Unlock()

	key := metric.Name
	e.history[key] = append(e.history[key], metric)

	if len(e.history[key]) > e.maxHistory {
		e.history[key] = e.history[key][len(e.history[key])-e.maxHistory:]
	}
}

func (e *DetectorEngine) Detect(metric common.Metric, algo AlgorithmType) (*AnomalyResult, error) {
	e.AddMetric(metric)

	if algo == "" {
		algo = e.defaultAlgo
	}

	e.mu.RLock()
	detector, exists := e.detectors[algo]
	history := e.history[metric.Name]
	e.mu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("%w: %s", common.ErrInvalidAlgorithm, algo)
	}

	result, err := detector.Detect(metric, history[:len(history)-1])
	if err != nil {
		return nil, err
	}

	if result.IsAnomaly {
		logger.Warn("Anomaly detected", map[string]interface{}{
			"metric":    metric.Name,
			"value":     metric.Value,
			"algorithm": algo,
			"score":     result.Score,
		})
	}

	return result, nil
}

func (e *DetectorEngine) DetectAll(metric common.Metric) ([]*AnomalyResult, error) {
	e.AddMetric(metric)

	e.mu.RLock()
	detectors := make([]Detector, 0, len(e.detectors))
	for _, d := range e.detectors {
		detectors = append(detectors, d)
	}
	history := e.history[metric.Name]
	e.mu.RUnlock()

	results := make([]*AnomalyResult, 0, len(detectors))
	for _, detector := range detectors {
		result, err := detector.Detect(metric, history[:len(history)-1])
		if err != nil {
			logger.Warn("Detector failed", map[string]interface{}{
				"algorithm": detector.Name(),
				"error":     err.Error(),
			})
			continue
		}
		results = append(results, result)
	}

	return results, nil
}

func (e *DetectorEngine) GetHistory(metricName string) []common.Metric {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.history[metricName]
}
