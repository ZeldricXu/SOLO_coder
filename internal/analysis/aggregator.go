package analysis

import (
	"fmt"
	"math"
	"sort"
	"sync"

	"github.com/df1-96/experiment/internal/models"
	"gonum.org/v1/gonum/floats"
	"gonum.org/v1/gonum/stat"
)

type Aggregator struct {
	options *AnalysisOptions
}

func NewAggregator(options *AnalysisOptions) *Aggregator {
	if options == nil {
		options = DefaultAnalysisOptions()
	}
	return &Aggregator{options: options}
}

func (a *Aggregator) PrepareResults(results []*models.Result, tasks []*models.Task, metricNames []string) []*ResultWithParams {
	taskMap := make(map[int64]*models.Task)
	for _, task := range tasks {
		taskMap[task.ID] = task
	}

	prepared := make([]*ResultWithParams, 0, len(results))
	for _, result := range results {
		task, ok := taskMap[result.TaskID]
		if !ok {
			continue
		}

		values := make(map[string]float64)
		for _, name := range metricNames {
			if v, ok := result.Data[name]; ok {
				if f, err := toFloat(v); err == nil {
					values[name] = f
				}
			}
		}

		if len(values) > 0 {
			prepared = append(prepared, &ResultWithParams{
				Result:     result,
				Task:       task,
				Params:     task.Params,
				ParamsHash: task.ParamsHash,
				Values:     values,
			})
		}
	}

	return prepared
}

func (a *Aggregator) AggregateByParams(results []*ResultWithParams, metricName string) (map[string]*AggregatedResult, error) {
	groups := make(map[string][]*ResultWithParams)
	for _, r := range results {
		if _, ok := r.Values[metricName]; !ok {
			continue
		}
		groups[r.ParamsHash] = append(groups[r.ParamsHash], r)
	}

	resultsMap := make(map[string]*AggregatedResult)
	paramsMap := make(map[string]models.Params)

	for _, r := range results {
		paramsMap[r.ParamsHash] = r.Params
	}

	var wg sync.WaitGroup
	var mu sync.Mutex
	sem := make(chan struct{}, a.options.Concurrency)

	for hash, group := range groups {
		hash := hash
		group := group
		sem <- struct{}{}
		wg.Add(1)

		go func() {
			defer wg.Done()
			defer func() { <-sem }()

			values := make([]float64, 0, len(group))
			for _, r := range group {
				values = append(values, r.Values[metricName])
			}

			filteredCount := 0
			if a.options.OutlierEnabled {
				filtered, removed := a.FilterOutliers(values)
				filteredCount = removed
				values = filtered
			}

			if len(values) == 0 {
				return
			}

			agg := a.computeAggregated(values, metricName)
			agg.ParamsHash = hash
			agg.Params = paramsMap[hash]
			agg.FilteredCount = filteredCount

			mu.Lock()
			resultsMap[hash] = agg
			mu.Unlock()
		}()
	}

	wg.Wait()
	return resultsMap, nil
}

func (a *Aggregator) FilterOutliers(values []float64) ([]float64, int) {
	if len(values) < 3 {
		return values, 0
	}

	var filterFunc func([]float64) []bool
	switch a.options.OutlierMethod {
	case OutlierMethodThreeSigma:
		filterFunc = a.threeSigmaFilter
	case OutlierMethodIQR:
		filterFunc = a.iqrFilter
	default:
		filterFunc = a.iqrFilter
	}

	mask := filterFunc(values)
	filtered := make([]float64, 0, len(values))
	removed := 0

	for i, v := range values {
		if mask[i] {
			filtered = append(filtered, v)
		} else {
			removed++
		}
	}

	return filtered, removed
}

func (a *Aggregator) threeSigmaFilter(values []float64) []bool {
	mean := stat.Mean(values, nil)
	stdDev := stat.StdDev(values, nil)

	lower := mean - 3*stdDev
	upper := mean + 3*stdDev

	mask := make([]bool, len(values))
	for i, v := range values {
		mask[i] = v >= lower && v <= upper
	}

	return mask
}

func (a *Aggregator) iqrFilter(values []float64) []bool {
	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	q1 := stat.Quantile(0.25, stat.Empirical, sorted, nil)
	q3 := stat.Quantile(0.75, stat.Empirical, sorted, nil)
	iqr := q3 - q1

	lower := q1 - 1.5*iqr
	upper := q3 + 1.5*iqr

	mask := make([]bool, len(values))
	for i, v := range values {
		mask[i] = v >= lower && v <= upper
	}

	return mask
}

func (a *Aggregator) computeAggregated(values []float64, metricName string) *AggregatedResult {
	agg := &AggregatedResult{
		MetricName:  metricName,
		Count:       len(values),
		RawValues:   values,
		Percentiles: make(map[float64]float64),
	}

	if len(values) == 0 {
		return agg
	}

	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	agg.Mean = stat.Mean(values, nil)
	agg.Median = stat.Quantile(0.5, stat.Empirical, sorted, nil)
	agg.Variance = stat.Variance(values, nil)
	agg.StdDev = math.Sqrt(agg.Variance)
	agg.StdErr = agg.StdDev / math.Sqrt(float64(len(values)))
	agg.Min = floats.Min(values)
	agg.Max = floats.Max(values)

	for _, p := range a.options.Percentiles {
		agg.Percentiles[p] = stat.Quantile(p/100.0, stat.Empirical, sorted, nil)
	}

	return agg
}

func (a *Aggregator) MergeResults(agg1, agg2 *AggregatedResult) *AggregatedResult {
	if agg1.MetricName != agg2.MetricName {
		return nil
	}

	merged := &AggregatedResult{
		MetricName:  agg1.MetricName,
		ParamsHash:  agg1.ParamsHash,
		Params:      agg1.Params,
		RawValues:   make([]float64, 0, len(agg1.RawValues)+len(agg2.RawValues)),
		Percentiles: make(map[float64]float64),
	}

	merged.RawValues = append(merged.RawValues, agg1.RawValues...)
	merged.RawValues = append(merged.RawValues, agg2.RawValues...)

	sorted := make([]float64, len(merged.RawValues))
	copy(sorted, merged.RawValues)
	sort.Float64s(sorted)

	merged.Count = len(merged.RawValues)
	merged.Mean = stat.Mean(merged.RawValues, nil)
	merged.Median = stat.Quantile(0.5, stat.Empirical, sorted, nil)
	merged.Variance = stat.Variance(merged.RawValues, nil)
	merged.StdDev = math.Sqrt(merged.Variance)
	merged.StdErr = merged.StdDev / math.Sqrt(float64(merged.Count))
	merged.Min = floats.Min(merged.RawValues)
	merged.Max = floats.Max(merged.RawValues)
	merged.FilteredCount = agg1.FilteredCount + agg2.FilteredCount

	for _, p := range a.options.Percentiles {
		merged.Percentiles[p] = stat.Quantile(p/100.0, stat.Empirical, sorted, nil)
	}

	return merged
}

func (a *Aggregator) GetSummary(aggregated map[string]*AggregatedResult) *BasicStats {
	allValues := make([]float64, 0)
	for _, agg := range aggregated {
		allValues = append(allValues, agg.RawValues...)
	}

	if len(allValues) == 0 {
		return &BasicStats{}
	}

	sorted := make([]float64, len(allValues))
	copy(sorted, allValues)
	sort.Float64s(sorted)

	mode, _ := stat.Mode(allValues, nil)
	return &BasicStats{
		Mean:     stat.Mean(allValues, nil),
		Median:   stat.Quantile(0.5, stat.Empirical, sorted, nil),
		Mode:     mode,
		Variance: stat.Variance(allValues, nil),
		StdDev:   stat.StdDev(allValues, nil),
		StdErr:   stat.StdDev(allValues, nil) / math.Sqrt(float64(len(allValues))),
		Min:      floats.Min(allValues),
		Max:      floats.Max(allValues),
		Count:    len(allValues),
	}
}

func (a *Aggregator) DetectOutliers(values []float64) ([]int, error) {
	if len(values) < 3 {
		return nil, nil
	}

	var mask []bool
	switch a.options.OutlierMethod {
	case OutlierMethodThreeSigma:
		mask = a.threeSigmaFilter(values)
	case OutlierMethodIQR:
		mask = a.iqrFilter(values)
	default:
		return nil, fmt.Errorf("unknown outlier method: %s", a.options.OutlierMethod)
	}

	outliers := make([]int, 0)
	for i, keep := range mask {
		if !keep {
			outliers = append(outliers, i)
		}
	}

	return outliers, nil
}

func toFloat(v interface{}) (float64, error) {
	switch val := v.(type) {
	case float64:
		return val, nil
	case float32:
		return float64(val), nil
	case int:
		return float64(val), nil
	case int32:
		return float64(val), nil
	case int64:
		return float64(val), nil
	case uint:
		return float64(val), nil
	case uint32:
		return float64(val), nil
	case uint64:
		return float64(val), nil
	default:
		return 0, fmt.Errorf("cannot convert %T to float64", v)
	}
}
