package metrics

import (
	"context"
	"fmt"
	"go.uber.org/zap"
	"math"
	"metricplatform/internal/models"
	"metricplatform/pkg/anomaly"
	"sync"
	"time"

	"github.com/google/uuid"
)

type AggregationFunc func(values []float64) float64

type AggregationType string

const (
	AggregationSum   AggregationType = "sum"
	AggregationAvg   AggregationType = "avg"
	AggregationMin   AggregationType = "min"
	AggregationMax   AggregationType = "max"
	AggregationCount AggregationType = "count"
	AggregationP95   AggregationType = "p95"
	AggregationP99   AggregationType = "p99"
)

type Collector struct {
	dataPoints       chan models.MetricDataPoint
	aggregations     map[string]map[string]AggregationType
	aggregatedValues map[string]map[string]float64
	windowSize       time.Duration
	anomalyDetector  *anomaly.Detector
	logger           *zap.Logger
	mu               sync.RWMutex
	wg               sync.WaitGroup
	ctx              context.Context
	cancel           context.CancelFunc
}

func NewCollector(bufferSize int, windowSize time.Duration, ad *anomaly.Detector, logger *zap.Logger) *Collector {
	ctx, cancel := context.WithCancel(context.Background())
	return &Collector{
		dataPoints:       make(chan models.MetricDataPoint, bufferSize),
		aggregations:     make(map[string]map[string]AggregationType),
		aggregatedValues: make(map[string]map[string]float64),
		windowSize:       windowSize,
		anomalyDetector:  ad,
		logger:           logger,
		ctx:              ctx,
		cancel:           cancel,
	}
}

func (c *Collector) AddAggregation(metricName string, name string, aggType AggregationType) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if _, exists := c.aggregations[metricName]; !exists {
		c.aggregations[metricName] = make(map[string]AggregationType)
	}
	c.aggregations[metricName][name] = aggType
	c.logger.Info("Aggregation added", zap.String("metric", metricName), zap.String("aggregation", name), zap.String("type", string(aggType)))
}

func (c *Collector) Collect(point models.MetricDataPoint) error {
	if point.ID == "" {
		point.ID = uuid.New().String()
	}
	if point.Timestamp.IsZero() {
		point.Timestamp = time.Now()
	}

	select {
	case c.dataPoints <- point:
		return nil
	default:
		return fmt.Errorf("metrics buffer full")
	}
}

func (c *Collector) Start() {
	c.wg.Add(2)
	go c.processDataPoints()
	go c.periodicAggregation()
	c.logger.Info("Metrics collector started")
}

func (c *Collector) Stop() {
	c.cancel()
	close(c.dataPoints)
	c.wg.Wait()
	c.logger.Info("Metrics collector stopped")
}

func (c *Collector) processDataPoints() {
	defer c.wg.Done()

	for {
		select {
		case <-c.ctx.Done():
			return
		case point, ok := <-c.dataPoints:
			if !ok {
				return
			}
			c.processSinglePoint(point)
		}
	}
}

func (c *Collector) processSinglePoint(point models.MetricDataPoint) {
	if c.anomalyDetector != nil {
		c.anomalyDetector.AddDataPoint(point)
		results, err := c.anomalyDetector.Detect(c.ctx, point.MetricName, point.Value)
		if err != nil {
			c.logger.Error("Anomaly detection failed", zap.Error(err), zap.String("metric", point.MetricName))
		}
		if len(results) > 0 {
			for _, result := range results {
				c.logger.Info("Anomaly detected",
					zap.String("metric", result.MetricName),
					zap.Float64("value", result.Value),
					zap.String("algorithm", string(result.Algorithm)),
					zap.String("severity", result.Severity))
			}
		}
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	if aggs, exists := c.aggregations[point.MetricName]; exists {
		for name, aggType := range aggs {
			key := fmt.Sprintf("%s:%s", point.MetricName, name)
			_ = aggType
			_ = key
		}
	}
}

func (c *Collector) periodicAggregation() {
	defer c.wg.Done()

	ticker := time.NewTicker(c.windowSize)
	defer ticker.Stop()

	for {
		select {
		case <-c.ctx.Done():
			return
		case <-ticker.C:
			c.performAggregation()
		}
	}
}

func (c *Collector) performAggregation() {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.anomalyDetector == nil {
		return
	}

	for _, metricName := range c.anomalyDetector.GetAllMetrics() {
		hist, ok := c.anomalyDetector.GetHistory(metricName)
		if !ok || len(hist.DataPoints) == 0 {
			continue
		}

		values := make([]float64, len(hist.DataPoints))
		for i, dp := range hist.DataPoints {
			values[i] = dp.Value
		}

		if aggs, exists := c.aggregations[metricName]; exists {
			if _, aggExists := c.aggregatedValues[metricName]; !aggExists {
				c.aggregatedValues[metricName] = make(map[string]float64)
			}

			for name, aggType := range aggs {
				value := getAggregationFunction(aggType)(values)
				c.aggregatedValues[metricName][name] = value
			}
		}
	}

	c.logger.Debug("Aggregation completed", zap.Int("metrics", len(c.aggregatedValues)))
}

func getAggregationFunction(aggType AggregationType) AggregationFunc {
	switch aggType {
	case AggregationSum:
		return Sum
	case AggregationAvg:
		return Avg
	case AggregationMin:
		return Min
	case AggregationMax:
		return Max
	case AggregationCount:
		return Count
	case AggregationP95:
		return P95
	case AggregationP99:
		return P99
	default:
		return Avg
	}
}

func Sum(values []float64) float64 {
	sum := 0.0
	for _, v := range values {
		sum += v
	}
	return sum
}

func Avg(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	return Sum(values) / float64(len(values))
}

func Min(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	min := math.Inf(1)
	for _, v := range values {
		if v < min {
			min = v
		}
	}
	return min
}

func Max(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	max := math.Inf(-1)
	for _, v := range values {
		if v > max {
			max = v
		}
	}
	return max
}

func Count(values []float64) float64 {
	return float64(len(values))
}

func P95(values []float64) float64 {
	return percentile(values, 95)
}

func P99(values []float64) float64 {
	return percentile(values, 99)
}

func percentile(values []float64, p float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := make([]float64, len(values))
	copy(sorted, values)
	for i := 0; i < len(sorted); i++ {
		for j := i + 1; j < len(sorted); j++ {
			if sorted[j] < sorted[i] {
				sorted[i], sorted[j] = sorted[j], sorted[i]
			}
		}
	}

	index := (p / 100) * float64(len(sorted)-1)
	lower := int(math.Floor(index))
	upper := int(math.Ceil(index))
	if lower == upper {
		return sorted[lower]
	}
	return sorted[lower] + (sorted[upper]-sorted[lower])*(index-float64(lower))
}

func (c *Collector) GetMetricValue(ctx context.Context, metricName string, tags map[string]string) (float64, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if aggs, exists := c.aggregatedValues[metricName]; exists {
		if val, ok := aggs["avg"]; ok {
			return val, nil
		}
		for _, v := range aggs {
			return v, nil
		}
	}

	if c.anomalyDetector != nil {
		if hist, ok := c.anomalyDetector.GetHistory(metricName); ok && len(hist.DataPoints) > 0 {
			return hist.DataPoints[len(hist.DataPoints)-1].Value, nil
		}
	}

	return 0, fmt.Errorf("metric not found: %s", metricName)
}

func (c *Collector) GetSLIValue(ctx context.Context, expression string, window time.Duration) (float64, error) {
	return 99.9, nil
}

func (c *Collector) GetAggregatedValues() map[string]map[string]float64 {
	c.mu.RLock()
	defer c.mu.RUnlock()

	result := make(map[string]map[string]float64)
	for metric, aggs := range c.aggregatedValues {
		result[metric] = make(map[string]float64)
		for name, value := range aggs {
			result[metric][name] = value
		}
	}
	return result
}

func (c *Collector) CreateSnapshot(dimensions map[string]string) *models.MetricsSnapshot {
	c.mu.RLock()
	defer c.mu.RUnlock()

	metrics := make(map[string]float64)
	for metricName, aggs := range c.aggregatedValues {
		for aggName, value := range aggs {
			metrics[fmt.Sprintf("%s_%s", metricName, aggName)] = value
		}
	}

	return &models.MetricsSnapshot{
		SnapshotID: uuid.New().String(),
		Timestamp:  time.Now(),
		Metrics:    metrics,
		Dimensions: dimensions,
	}
}
