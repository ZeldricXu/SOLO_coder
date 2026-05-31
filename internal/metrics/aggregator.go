package metrics

import (
	"fmt"
	"sort"
	"sync"
	"time"

	"observability-platform/pkg/models"
)

type AggregationType string

const (
	AggregationSum     AggregationType = "sum"
	AggregationAvg     AggregationType = "avg"
	AggregationCount   AggregationType = "count"
	AggregationMin     AggregationType = "min"
	AggregationMax     AggregationType = "max"
	AggregationRate    AggregationType = "rate"
	AggregationP50     AggregationType = "p50"
	AggregationP95     AggregationType = "p95"
	AggregationP99     AggregationType = "p99"
	AggregationLast    AggregationType = "last"
)

type MetricSeries struct {
	Metric     models.Metric
	DataPoints []models.TimeSeriesDataPoint
}

type TimeWindow struct {
	Start time.Time
	End   time.Time
}

type AggregationConfig struct {
	Interval        time.Duration
	RetentionPeriod time.Duration
	Aggregations    []AggregationType
}

type MetricAggregator struct {
	config          AggregationConfig
	rawData         map[string]MetricSeries
	aggregatedData  map[string]map[AggregationType]MetricSeries
	timeBuckets     map[string]map[int64][]float64
	mu              sync.RWMutex
	lastFlush       time.Time
}

func NewMetricAggregator(config AggregationConfig) *MetricAggregator {
	if config.Interval <= 0 {
		config.Interval = time.Minute
	}
	if config.RetentionPeriod <= 0 {
		config.RetentionPeriod = time.Hour * 24
	}
	if len(config.Aggregations) == 0 {
		config.Aggregations = []AggregationType{
			AggregationSum, AggregationAvg, AggregationCount,
			AggregationMin, AggregationMax,
		}
	}

	return &MetricAggregator{
		config:         config,
		rawData:        make(map[string]MetricSeries),
		aggregatedData: make(map[string]map[AggregationType]MetricSeries),
		timeBuckets:    make(map[string]map[int64][]float64),
		lastFlush:      time.Now(),
	}
}

func (a *MetricAggregator) AddDataPoint(metric models.Metric, value float64, timestamp time.Time) {
	a.mu.Lock()
	defer a.mu.Unlock()

	seriesKey := a.getSeriesKey(metric)

	dp := models.TimeSeriesDataPoint{
		Timestamp: timestamp,
		Value:     value,
		Labels:    metric.Labels,
	}

	if series, exists := a.rawData[seriesKey]; exists {
		series.DataPoints = append(series.DataPoints, dp)
		a.rawData[seriesKey] = series
	} else {
		a.rawData[seriesKey] = MetricSeries{
			Metric:     metric,
			DataPoints: []models.TimeSeriesDataPoint{dp},
		}
	}

	bucketTime := timestamp.Truncate(a.config.Interval).Unix()
	if _, exists := a.timeBuckets[seriesKey]; !exists {
		a.timeBuckets[seriesKey] = make(map[int64][]float64)
	}
	a.timeBuckets[seriesKey][bucketTime] = append(a.timeBuckets[seriesKey][bucketTime], value)
}

func (a *MetricAggregator) AddDataPointsBatch(points []struct {
	Metric    models.Metric
	Value     float64
	Timestamp time.Time
}) {
	for _, p := range points {
		a.AddDataPoint(p.Metric, p.Value, p.Timestamp)
	}
}

func (a *MetricAggregator) getSeriesKey(metric models.Metric) string {
	labels := make([]string, 0, len(metric.Labels))
	for k, v := range metric.Labels {
		labels = append(labels, fmt.Sprintf("%s=%s", k, v))
	}
	sort.Strings(labels)

	key := metric.Name
	for _, label := range labels {
		key += "|" + label
	}
	return key
}

func (a *MetricAggregator) Aggregate() {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.aggregateInternal()
}

func (a *MetricAggregator) AggregateAtomic() {
	a.mu.Lock()
	defer a.mu.Unlock()

	a.aggregateInternal()
}

func (a *MetricAggregator) aggregateInternal() {
	newBuckets := make(map[string]map[int64][]float64, len(a.timeBuckets))

	for seriesKey, buckets := range a.timeBuckets {
		if _, exists := a.aggregatedData[seriesKey]; !exists {
			a.aggregatedData[seriesKey] = make(map[AggregationType]MetricSeries)
		}

		rawSeries := a.rawData[seriesKey]

		for bucketTime, values := range buckets {
			timestamp := time.Unix(bucketTime, 0)
			aggResults := a.computeAggregations(values)

			for _, aggType := range a.config.Aggregations {
				if value, exists := aggResults[aggType]; exists {
					aggSeries := a.aggregatedData[seriesKey][aggType]
					aggSeries.Metric = rawSeries.Metric
					aggSeries.DataPoints = append(aggSeries.DataPoints, models.TimeSeriesDataPoint{
						Timestamp: timestamp,
						Value:     value,
						Labels:    rawSeries.Metric.Labels,
					})
					a.aggregatedData[seriesKey][aggType] = aggSeries
				}
			}

			newBuckets[seriesKey] = make(map[int64][]float64)
		}
	}

	a.timeBuckets = newBuckets
	a.lastFlush = time.Now()
}

func (a *MetricAggregator) computeAggregations(values []float64) map[AggregationType]float64 {
	result := make(map[AggregationType]float64)
	if len(values) == 0 {
		return result
	}

	sum := 0.0
	min := values[0]
	max := values[0]
	count := float64(len(values))

	for _, v := range values {
		sum += v
		if v < min {
			min = v
		}
		if v > max {
			max = v
		}
	}

	avg := sum / count

	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	for _, aggType := range a.config.Aggregations {
		switch aggType {
		case AggregationSum:
			result[aggType] = sum
		case AggregationAvg:
			result[aggType] = avg
		case AggregationCount:
			result[aggType] = count
		case AggregationMin:
			result[aggType] = min
		case AggregationMax:
			result[aggType] = max
		case AggregationP50:
			result[aggType] = a.percentile(sorted, 0.50)
		case AggregationP95:
			result[aggType] = a.percentile(sorted, 0.95)
		case AggregationP99:
			result[aggType] = a.percentile(sorted, 0.99)
		case AggregationLast:
			result[aggType] = values[len(values)-1]
		}
	}

	return result
}

func (a *MetricAggregator) percentile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	index := int(float64(len(sorted)-1) * p)
	return sorted[index]
}

func (a *MetricAggregator) GetSeries(metricName string, labels map[string]string, aggType AggregationType, window TimeWindow) ([]models.TimeSeriesDataPoint, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	metric := models.Metric{Name: metricName, Labels: labels}
	seriesKey := a.getSeriesKey(metric)

	if aggType == "" {
		if series, exists := a.rawData[seriesKey]; exists {
			return a.filterByTimeWindow(series.DataPoints, window), nil
		}
		return nil, fmt.Errorf("series not found")
	}

	if seriesMap, exists := a.aggregatedData[seriesKey]; exists {
		if series, exists := seriesMap[aggType]; exists {
			return a.filterByTimeWindow(series.DataPoints, window), nil
		}
	}
	return nil, fmt.Errorf("aggregated series not found")
}

func (a *MetricAggregator) filterByTimeWindow(points []models.TimeSeriesDataPoint, window TimeWindow) []models.TimeSeriesDataPoint {
	if window.Start.IsZero() && window.End.IsZero() {
		return points
	}

	filtered := make([]models.TimeSeriesDataPoint, 0, len(points))
	for _, p := range points {
		if (window.Start.IsZero() || !p.Timestamp.Before(window.Start)) &&
			(window.End.IsZero() || !p.Timestamp.After(window.End)) {
			filtered = append(filtered, p)
		}
	}
	return filtered
}

func (a *MetricAggregator) GetAllSeries() []models.Metric {
	a.mu.RLock()
	defer a.mu.RUnlock()

	metrics := make([]models.Metric, 0, len(a.rawData))
	seen := make(map[string]bool)

	for _, series := range a.rawData {
		key := a.getSeriesKey(series.Metric)
		if !seen[key] {
			metrics = append(metrics, series.Metric)
			seen[key] = true
		}
	}
	return metrics
}

func (a *MetricAggregator) CleanOldData() {
	a.mu.Lock()
	defer a.mu.Unlock()

	cutoff := time.Now().Add(-a.config.RetentionPeriod)

	for key, series := range a.rawData {
		filtered := make([]models.TimeSeriesDataPoint, 0, len(series.DataPoints))
		for _, dp := range series.DataPoints {
			if dp.Timestamp.After(cutoff) {
				filtered = append(filtered, dp)
			}
		}
		series.DataPoints = filtered
		a.rawData[key] = series
	}

	for seriesKey, aggMap := range a.aggregatedData {
		for aggType, series := range aggMap {
			filtered := make([]models.TimeSeriesDataPoint, 0, len(series.DataPoints))
			for _, dp := range series.DataPoints {
				if dp.Timestamp.After(cutoff) {
					filtered = append(filtered, dp)
				}
			}
			series.DataPoints = filtered
			a.aggregatedData[seriesKey][aggType] = series
		}
	}
}

func (a *MetricAggregator) GetStats() map[string]interface{} {
	a.mu.RLock()
	defer a.mu.RUnlock()

	totalRawPoints := 0
	totalAggPoints := 0

	for _, series := range a.rawData {
		totalRawPoints += len(series.DataPoints)
	}

	for _, aggMap := range a.aggregatedData {
		for _, series := range aggMap {
			totalAggPoints += len(series.DataPoints)
		}
	}

	return map[string]interface{}{
		"series_count":       len(a.rawData),
		"raw_points_count":   totalRawPoints,
		"agg_points_count":   totalAggPoints,
		"last_aggregation":   a.lastFlush,
		"aggregation_interval": a.config.Interval.String(),
	}
}
