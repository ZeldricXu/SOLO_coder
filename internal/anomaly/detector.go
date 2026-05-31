package anomaly

import (
	"fmt"
	"math"
	"sort"
	"sync"
	"time"

	"github.com/google/uuid"
)

type AnomalySeverity string

const (
	SeverityLow     AnomalySeverity = "LOW"
	SeverityMedium  AnomalySeverity = "MEDIUM"
	SeverityHigh    AnomalySeverity = "HIGH"
	SeverityCritical AnomalySeverity = "CRITICAL"
)

type AnomalyType string

const (
	TypeSpike       AnomalyType = "SPIKE"
	TypeDrop        AnomalyType = "DROP"
	TypeTrendUp     AnomalyType = "TREND_UP"
	TypeTrendDown   AnomalyType = "TREND_DOWN"
	TypeSeasonal    AnomalyType = "SEASONAL"
	TypeLevelShift  AnomalyType = "LEVEL_SHIFT"
)

type Anomaly struct {
	ID            string                 `json:"id"`
	MetricName    string                 `json:"metric_name"`
	Labels        map[string]string      `json:"labels,omitempty"`
	Type          AnomalyType            `json:"type"`
	Severity      AnomalySeverity       `json:"severity"`
	Timestamp     time.Time              `json:"timestamp"`
	Value         float64                `json:"value"`
	ExpectedValue float64                `json:"expected_value"`
	Deviation     float64                `json:"deviation"`
	DeviationPct  float64                `json:"deviation_percentage"`
	Score         float64                `json:"score"`
	Description   string                 `json:"description"`
	Algorithm     string                 `json:"algorithm"`
	Metadata      map[string]interface{} `json:"metadata,omitempty"`
}

type DetectionAlgorithm interface {
	Detect(data []float64, currentValue float64) (*Anomaly, error)
	Name() string
}

type ZScoreAlgorithm struct {
	Threshold float64
}

func NewZScoreAlgorithm(threshold float64) *ZScoreAlgorithm {
	if threshold <= 0 {
		threshold = 3.0
	}
	return &ZScoreAlgorithm{Threshold: threshold}
}

func (a *ZScoreAlgorithm) Detect(data []float64, currentValue float64) (*Anomaly, error) {
	if len(data) < 2 {
		return nil, nil
	}

	mean := computeMean(data)
	stdDev := computeStdDev(data)

	if stdDev == 0 {
		return nil, nil
	}

	zScore := (currentValue - mean) / stdDev

	if math.Abs(zScore) >= a.Threshold {
		anomalyType := TypeSpike
		if zScore < 0 {
			anomalyType = TypeDrop
		}

		return &Anomaly{
			ID:            uuid.New().String(),
			Type:          anomalyType,
			Severity:      calculateSeverity(math.Abs(zScore), a.Threshold),
			Value:         currentValue,
			ExpectedValue: mean,
			Deviation:     currentValue - mean,
			DeviationPct:  ((currentValue - mean) / math.Abs(mean)) * 100,
			Score:         math.Abs(zScore),
			Algorithm:     a.Name(),
		}, nil
	}

	return nil, nil
}

func (a *ZScoreAlgorithm) Name() string {
	return "zscore"
}

type MADAlgorithm struct {
	Threshold float64
}

func NewMADAlgorithm(threshold float64) *MADAlgorithm {
	if threshold <= 0 {
		threshold = 3.0
	}
	return &MADAlgorithm{Threshold: threshold}
}

func (a *MADAlgorithm) Detect(data []float64, currentValue float64) (*Anomaly, error) {
	if len(data) < 2 {
		return nil, nil
	}

	median := calculateMedian(data)
	absDeviations := make([]float64, len(data))
	for i, v := range data {
		absDeviations[i] = math.Abs(v - median)
	}
	mad := calculateMedian(absDeviations)

	if mad == 0 {
		return nil, nil
	}

	modifiedZScore := 0.6745 * (currentValue - median) / mad

	if math.Abs(modifiedZScore) >= a.Threshold {
		anomalyType := TypeSpike
		if modifiedZScore < 0 {
			anomalyType = TypeDrop
		}

		return &Anomaly{
			ID:            uuid.New().String(),
			Type:          anomalyType,
			Severity:      calculateSeverity(math.Abs(modifiedZScore), a.Threshold),
			Value:         currentValue,
			ExpectedValue: median,
			Deviation:     currentValue - median,
			DeviationPct:  ((currentValue - median) / math.Abs(median)) * 100,
			Score:         math.Abs(modifiedZScore),
			Algorithm:     a.Name(),
		}, nil
	}

	return nil, nil
}

func (a *MADAlgorithm) Name() string {
	return "mad"
}

func computeMean(data []float64) float64 {
	if len(data) == 0 {
		return 0
	}
	sum := 0.0
	for _, v := range data {
		sum += v
	}
	return sum / float64(len(data))
}

func computeStdDev(data []float64) float64 {
	if len(data) < 2 {
		return 0
	}
	mean := computeMean(data)
	sum := 0.0
	for _, v := range data {
		d := v - mean
		sum += d * d
	}
	return math.Sqrt(sum / float64(len(data)-1))
}

func calculateMedian(data []float64) float64 {
	sorted := make([]float64, len(data))
	copy(sorted, data)
	sort.Float64s(sorted)

	n := len(sorted)
	if n%2 == 0 {
		return (sorted[n/2-1] + sorted[n/2]) / 2
	}
	return sorted[n/2]
}

type EWMAAlgorithm struct {
	Alpha     float64
	Threshold float64
}

func NewEWMAAlgorithm(alpha, threshold float64) *EWMAAlgorithm {
	if alpha <= 0 || alpha >= 1 {
		alpha = 0.3
	}
	if threshold <= 0 {
		threshold = 2.0
	}
	return &EWMAAlgorithm{Alpha: alpha, Threshold: threshold}
}

func (a *EWMAAlgorithm) Detect(data []float64, currentValue float64) (*Anomaly, error) {
	if len(data) < 2 {
		return nil, nil
	}

	ewma := data[0]
	for i := 1; i < len(data); i++ {
		ewma = a.Alpha*data[i] + (1-a.Alpha)*ewma
	}

	residuals := make([]float64, len(data))
	currentEWMA := data[0]
	for i, v := range data {
		residuals[i] = math.Abs(v - currentEWMA)
		currentEWMA = a.Alpha*v + (1-a.Alpha)*currentEWMA
	}

	meanResidual := computeMean(residuals)
	stdResidual := computeStdDev(residuals)

	if stdResidual == 0 {
		return nil, nil
	}

	deviation := math.Abs(currentValue - ewma)
	if deviation > meanResidual+a.Threshold*stdResidual {
		anomalyType := TypeSpike
		if currentValue < ewma {
			anomalyType = TypeDrop
		}

		return &Anomaly{
			ID:            uuid.New().String(),
			Type:          anomalyType,
			Severity:      calculateSeverity(deviation/(meanResidual+stdResidual), a.Threshold),
			Value:         currentValue,
			ExpectedValue: ewma,
			Deviation:     currentValue - ewma,
			DeviationPct:  ((currentValue - ewma) / math.Abs(ewma)) * 100,
			Score:         deviation / (meanResidual + stdResidual),
			Algorithm:     a.Name(),
		}, nil
	}

	return nil, nil
}

func (a *EWMAAlgorithm) Name() string {
	return "ewma"
}

type SeasonalAlgorithm struct {
	Period     int
	Threshold  float64
}

func NewSeasonalAlgorithm(period int, threshold float64) *SeasonalAlgorithm {
	if period <= 0 {
		period = 24
	}
	if threshold <= 0 {
		threshold = 2.5
	}
	return &SeasonalAlgorithm{Period: period, Threshold: threshold}
}

func (a *SeasonalAlgorithm) Detect(data []float64, currentValue float64) (*Anomaly, error) {
	if len(data) < a.Period*2 {
		return nil, nil
	}

	seasonalValues := make([]float64, 0)
	for i := len(data) - 1; i >= 0; i -= a.Period {
		seasonalValues = append(seasonalValues, data[i])
		if len(seasonalValues) >= 4 {
			break
		}
	}

	if len(seasonalValues) < 2 {
		return nil, nil
	}

	mean := computeMean(seasonalValues)
	stdDev := computeStdDev(seasonalValues)

	if stdDev == 0 {
		return nil, nil
	}

	zScore := (currentValue - mean) / stdDev

	if math.Abs(zScore) >= a.Threshold {
		anomalyType := TypeSeasonal
		if zScore > 0 {
			anomalyType = TypeSpike
		} else {
			anomalyType = TypeDrop
		}

		return &Anomaly{
			ID:            uuid.New().String(),
			Type:          anomalyType,
			Severity:      calculateSeverity(math.Abs(zScore), a.Threshold),
			Value:         currentValue,
			ExpectedValue: mean,
			Deviation:     currentValue - mean,
			DeviationPct:  ((currentValue - mean) / math.Abs(mean)) * 100,
			Score:         math.Abs(zScore),
			Algorithm:     a.Name(),
			Metadata: map[string]interface{}{
				"historical_values": seasonalValues,
			},
		}, nil
	}

	return nil, nil
}

func (a *SeasonalAlgorithm) Name() string {
	return "seasonal"
}

type TrendAlgorithm struct {
	WindowSize  int
	Threshold   float64
}

func NewTrendAlgorithm(windowSize int, threshold float64) *TrendAlgorithm {
	if windowSize <= 0 {
		windowSize = 10
	}
	if threshold <= 0 {
		threshold = 0.1
	}
	return &TrendAlgorithm{WindowSize: windowSize, Threshold: threshold}
}

func (a *TrendAlgorithm) Detect(data []float64, currentValue float64) (*Anomaly, error) {
	if len(data) < a.WindowSize {
		return nil, nil
	}

	recentData := data[len(data)-a.WindowSize:]
	slope := calculateSlope(recentData)

	if math.Abs(slope) >= a.Threshold {
		anomalyType := TypeTrendUp
		if slope < 0 {
			anomalyType = TypeTrendDown
		}

		mean := computeMean(recentData)

		return &Anomaly{
			ID:            uuid.New().String(),
			Type:          anomalyType,
			Severity:      calculateSeverity(math.Abs(slope)/a.Threshold, 1.0),
			Value:         currentValue,
			ExpectedValue: mean,
			Deviation:     slope * float64(a.WindowSize),
			DeviationPct:  (slope / mean) * 100 * float64(a.WindowSize),
			Score:         math.Abs(slope),
			Algorithm:     a.Name(),
			Metadata: map[string]interface{}{
				"slope": slope,
			},
		}, nil
	}

	return nil, nil
}

func (a *TrendAlgorithm) Name() string {
	return "trend"
}

func calculateSlope(data []float64) float64 {
	n := float64(len(data))
	xSum := 0.0
	ySum := 0.0
	xySum := 0.0
	x2Sum := 0.0

	for i, y := range data {
		x := float64(i)
		xSum += x
		ySum += y
		xySum += x * y
		x2Sum += x * x
	}

	denominator := n*x2Sum - xSum*xSum
	if denominator == 0 {
		return 0
	}

	return (n*xySum - xSum*ySum) / denominator
}

type QuantileAlgorithm struct {
	LowerQuantile float64
	UpperQuantile float64
}

func NewQuantileAlgorithm(lower, upper float64) *QuantileAlgorithm {
	if lower <= 0 || lower >= 1 {
		lower = 0.05
	}
	if upper <= 0 || upper >= 1 {
		upper = 0.95
	}
	if lower >= upper {
		lower = 0.05
		upper = 0.95
	}
	return &QuantileAlgorithm{LowerQuantile: lower, UpperQuantile: upper}
}

func (a *QuantileAlgorithm) Detect(data []float64, currentValue float64) (*Anomaly, error) {
	if len(data) < 4 {
		return nil, nil
	}

	sorted := make([]float64, len(data))
	copy(sorted, data)
	sort.Float64s(sorted)

	lowerIdx := int(float64(len(sorted)-1) * a.LowerQuantile)
	upperIdx := int(float64(len(sorted)-1) * a.UpperQuantile)
	lowerBound := sorted[lowerIdx]
	upperBound := sorted[upperIdx]

	if currentValue < lowerBound || currentValue > upperBound {
		anomalyType := TypeSpike
		if currentValue < lowerBound {
			anomalyType = TypeDrop
		}

		median := calculateMedian(data)

		return &Anomaly{
			ID:            uuid.New().String(),
			Type:          anomalyType,
			Severity:      calculateSeverity(2.0, 1.5),
			Value:         currentValue,
			ExpectedValue: median,
			Deviation:     currentValue - median,
			DeviationPct:  ((currentValue - median) / math.Abs(median)) * 100,
			Score:         1.0,
			Algorithm:     a.Name(),
			Metadata: map[string]interface{}{
				"lower_bound": lowerBound,
				"upper_bound": upperBound,
			},
		}, nil
	}

	return nil, nil
}

func (a *QuantileAlgorithm) Name() string {
	return "quantile"
}

func calculateSeverity(score, threshold float64) AnomalySeverity {
	ratio := score / threshold
	switch {
	case ratio >= 4.0:
		return SeverityCritical
	case ratio >= 2.0:
		return SeverityHigh
	case ratio >= 1.5:
		return SeverityMedium
	default:
		return SeverityLow
	}
}

type Baseline struct {
	Mean      float64
	StdDev    float64
	Median    float64
	Min       float64
	Max       float64
	P50       float64
	P95       float64
	P99       float64
	DataPoints int
	UpdatedAt time.Time
}

type MetricHistory struct {
	MetricName string
	Labels     map[string]string
	DataPoints []float64
	Timestamps []time.Time
	Baseline   Baseline
	MaxPoints  int
}

type AnomalyDetector struct {
	algorithms map[string]DetectionAlgorithm
	history    map[string]*MetricHistory
	anomalies  []Anomaly
	mu         sync.RWMutex
	maxAnomalies int
	autoUpdateBaseline bool
}

type DetectorConfig struct {
	MaxHistoryPoints    int
	MaxAnomalies        int
	AutoUpdateBaseline  bool
}

func NewAnomalyDetector(config DetectorConfig) *AnomalyDetector {
	if config.MaxHistoryPoints <= 0 {
		config.MaxHistoryPoints = 1000
	}
	if config.MaxAnomalies <= 0 {
		config.MaxAnomalies = 1000
	}

	detector := &AnomalyDetector{
		algorithms:        make(map[string]DetectionAlgorithm),
		history:           make(map[string]*MetricHistory),
		anomalies:         make([]Anomaly, 0, config.MaxAnomalies),
		maxAnomalies:      config.MaxAnomalies,
		autoUpdateBaseline: config.AutoUpdateBaseline,
	}

	detector.algorithms["zscore"] = NewZScoreAlgorithm(3.0)
	detector.algorithms["mad"] = NewMADAlgorithm(3.0)
	detector.algorithms["ewma"] = NewEWMAAlgorithm(0.3, 2.0)
	detector.algorithms["seasonal"] = NewSeasonalAlgorithm(24, 2.5)
	detector.algorithms["trend"] = NewTrendAlgorithm(10, 0.1)
	detector.algorithms["quantile"] = NewQuantileAlgorithm(0.05, 0.95)

	return detector
}

func (d *AnomalyDetector) AddAlgorithm(algorithm DetectionAlgorithm) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.algorithms[algorithm.Name()] = algorithm
}

func (d *AnomalyDetector) RemoveAlgorithm(name string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	delete(d.algorithms, name)
}

func (d *AnomalyDetector) AddDataPoint(metricName string, labels map[string]string, value float64, timestamp time.Time) []Anomaly {
	d.mu.Lock()
	defer d.mu.Unlock()

	key := d.getMetricKey(metricName, labels)

	history, exists := d.history[key]
	if !exists {
		history = &MetricHistory{
			MetricName: metricName,
			Labels:     labels,
			DataPoints: make([]float64, 0),
			Timestamps: make([]time.Time, 0),
			MaxPoints:  1000,
		}
		d.history[key] = history
	}

	history.DataPoints = append(history.DataPoints, value)
	history.Timestamps = append(history.Timestamps, timestamp)

	if len(history.DataPoints) > history.MaxPoints {
		history.DataPoints = history.DataPoints[len(history.DataPoints)-history.MaxPoints:]
		history.Timestamps = history.Timestamps[len(history.Timestamps)-history.MaxPoints:]
	}

	if d.autoUpdateBaseline {
		d.updateBaseline(history)
	}

	detectedAnomalies := make([]Anomaly, 0)
	for _, algorithm := range d.algorithms {
		if anomaly, err := algorithm.Detect(history.DataPoints[:len(history.DataPoints)-1], value); err == nil && anomaly != nil {
			anomaly.MetricName = metricName
			anomaly.Labels = labels
			anomaly.Timestamp = timestamp
			anomaly.Description = d.generateDescription(anomaly)
			detectedAnomalies = append(detectedAnomalies, *anomaly)
		}
	}

	for _, anomaly := range detectedAnomalies {
		d.anomalies = append(d.anomalies, anomaly)
	}

	if len(d.anomalies) > d.maxAnomalies {
		d.anomalies = d.anomalies[len(d.anomalies)-d.maxAnomalies:]
	}

	return detectedAnomalies
}

func (d *AnomalyDetector) getMetricKey(name string, labels map[string]string) string {
	keys := make([]string, 0, len(labels))
	for k := range labels {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	key := name
	for _, k := range keys {
		key += "|" + k + "=" + labels[k]
	}
	return key
}

func (d *AnomalyDetector) updateBaseline(history *MetricHistory) {
	if len(history.DataPoints) < 2 {
		return
	}

	data := history.DataPoints
	history.Baseline.Mean = computeMean(data)
	history.Baseline.StdDev = computeStdDev(data)
	history.Baseline.Median = calculateMedian(data)
	history.Baseline.Min = data[0]
	history.Baseline.Max = data[0]

	for _, v := range data {
		if v < history.Baseline.Min {
			history.Baseline.Min = v
		}
		if v > history.Baseline.Max {
			history.Baseline.Max = v
		}
	}

	sorted := make([]float64, len(data))
	copy(sorted, data)
	sort.Float64s(sorted)

	history.Baseline.P50 = d.percentile(sorted, 0.50)
	history.Baseline.P95 = d.percentile(sorted, 0.95)
	history.Baseline.P99 = d.percentile(sorted, 0.99)
	history.Baseline.DataPoints = len(data)
	history.Baseline.UpdatedAt = time.Now()
}

func (d *AnomalyDetector) percentile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	idx := int(float64(len(sorted)-1) * p)
	return sorted[idx]
}

func (d *AnomalyDetector) generateDescription(anomaly *Anomaly) string {
	switch anomaly.Type {
	case TypeSpike:
		return sprintf("Detected %.1f%% spike in %s (value: %.2f, expected: %.2f)",
			anomaly.DeviationPct, anomaly.MetricName, anomaly.Value, anomaly.ExpectedValue)
	case TypeDrop:
		return sprintf("Detected %.1f%% drop in %s (value: %.2f, expected: %.2f)",
			math.Abs(anomaly.DeviationPct), anomaly.MetricName, anomaly.Value, anomaly.ExpectedValue)
	case TypeTrendUp:
		return sprintf("Detected upward trend in %s (score: %.2f)",
			anomaly.MetricName, anomaly.Score)
	case TypeTrendDown:
		return sprintf("Detected downward trend in %s (score: %.2f)",
			anomaly.MetricName, anomaly.Score)
	default:
		return sprintf("Anomaly detected in %s (score: %.2f)",
			anomaly.MetricName, anomaly.Score)
	}
}

func sprintf(format string, args ...interface{}) string {
	return fmt.Sprintf(format, args...)
}

func (d *AnomalyDetector) GetBaseline(metricName string, labels map[string]string) (*Baseline, bool) {
	d.mu.RLock()
	defer d.mu.RUnlock()

	key := d.getMetricKey(metricName, labels)
	history, exists := d.history[key]
	if !exists {
		return nil, false
	}

	baseline := history.Baseline
	return &baseline, true
}

func (d *AnomalyDetector) GetAnomalies(metricName string, severity AnomalySeverity, limit int) []Anomaly {
	d.mu.RLock()
	defer d.mu.RUnlock()

	result := make([]Anomaly, 0, limit)
	count := 0

	for i := len(d.anomalies) - 1; i >= 0 && count < limit; i-- {
		anomaly := d.anomalies[i]

		if metricName != "" && anomaly.MetricName != metricName {
			continue
		}
		if severity != "" && anomaly.Severity != severity {
			continue
		}

		result = append(result, anomaly)
		count++
	}

	return result
}

func (d *AnomalyDetector) GetHistory(metricName string, labels map[string]string, limit int) []float64 {
	d.mu.RLock()
	defer d.mu.RUnlock()

	key := d.getMetricKey(metricName, labels)
	history, exists := d.history[key]
	if !exists {
		return nil
	}

	if limit <= 0 || limit > len(history.DataPoints) {
		limit = len(history.DataPoints)
	}

	result := make([]float64, limit)
	copy(result, history.DataPoints[len(history.DataPoints)-limit:])
	return result
}

func (d *AnomalyDetector) GetStats() map[string]interface{} {
	d.mu.RLock()
	defer d.mu.RUnlock()

	algorithmNames := make([]string, 0, len(d.algorithms))
	for name := range d.algorithms {
		algorithmNames = append(algorithmNames, name)
	}

	severityCounts := make(map[AnomalySeverity]int)
	for _, anomaly := range d.anomalies {
		severityCounts[anomaly.Severity]++
	}

	return map[string]interface{}{
		"metrics_tracked":  len(d.history),
		"algorithms":       algorithmNames,
		"total_anomalies":  len(d.anomalies),
		"severity_counts":  severityCounts,
		"max_anomalies":    d.maxAnomalies,
	}
}
