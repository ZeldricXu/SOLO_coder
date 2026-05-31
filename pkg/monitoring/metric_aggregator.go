package monitoring

import (
	"fmt"
	"sort"
)

type MetricsAggregator interface {
	Aggregate(points []*MetricPoint, aggType string) (float64, error)
	Sum(points []*MetricPoint) float64
	Avg(points []*MetricPoint) float64
	Min(points []*MetricPoint) float64
	Max(points []*MetricPoint) float64
	Count(points []*MetricPoint) float64
	Percentile(points []*MetricPoint, p float64) float64
}

type DefaultMetricsAggregator struct{}

func NewDefaultMetricsAggregator() *DefaultMetricsAggregator {
	return &DefaultMetricsAggregator{}
}

func (a *DefaultMetricsAggregator) Aggregate(points []*MetricPoint, aggType string) (float64, error) {
	if len(points) == 0 {
		return 0, fmt.Errorf("no data points to aggregate")
	}

	switch aggType {
	case "sum":
		return a.Sum(points), nil
	case "avg":
		return a.Avg(points), nil
	case "min":
		return a.Min(points), nil
	case "max":
		return a.Max(points), nil
	case "count":
		return a.Count(points), nil
	case "p95":
		return a.Percentile(points, 95), nil
	case "p99":
		return a.Percentile(points, 99), nil
	default:
		return 0, fmt.Errorf("unsupported aggregation type: %s", aggType)
	}
}

func (a *DefaultMetricsAggregator) Sum(points []*MetricPoint) float64 {
	var sum float64
	for _, p := range points {
		sum += p.Value
	}
	return sum
}

func (a *DefaultMetricsAggregator) Avg(points []*MetricPoint) float64 {
	if len(points) == 0 {
		return 0
	}
	return a.Sum(points) / float64(len(points))
}

func (a *DefaultMetricsAggregator) Min(points []*MetricPoint) float64 {
	if len(points) == 0 {
		return 0
	}
	min := points[0].Value
	for _, p := range points[1:] {
		if p.Value < min {
			min = p.Value
		}
	}
	return min
}

func (a *DefaultMetricsAggregator) Max(points []*MetricPoint) float64 {
	if len(points) == 0 {
		return 0
	}
	max := points[0].Value
	for _, p := range points[1:] {
		if p.Value > max {
			max = p.Value
		}
	}
	return max
}

func (a *DefaultMetricsAggregator) Count(points []*MetricPoint) float64 {
	return float64(len(points))
}

func (a *DefaultMetricsAggregator) Percentile(points []*MetricPoint, p float64) float64 {
	if len(points) == 0 {
		return 0
	}

	values := make([]float64, len(points))
	for i, point := range points {
		values[i] = point.Value
	}

	sort.Float64s(values)
	index := int(float64(len(values)-1) * p / 100)
	return values[index]
}
