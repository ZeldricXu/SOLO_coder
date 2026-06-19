package analysis

import (
	"math"
	"sort"
	"strconv"

	"github.com/df1-96/experiment/internal/models"
	"gonum.org/v1/gonum/stat"
)

type Visualization struct {
	options *AnalysisOptions
}

func NewVisualization(options *AnalysisOptions) *Visualization {
	if options == nil {
		options = DefaultAnalysisOptions()
	}
	return &Visualization{options: options}
}

func (v *Visualization) PrepareHeatmap(
	results map[string]*AggregatedResult,
	xParam, yParam, metricName string,
) (*HeatmapData, error) {
	xValues := make(map[string]bool)
	yValues := make(map[string]bool)
	valueMap := make(map[string]map[string]float64)

	for _, agg := range results {
		if agg.MetricName != metricName {
			continue
		}

		xVal := getParamString(agg.Params, xParam)
		yVal := getParamString(agg.Params, yParam)

		if xVal == "" || yVal == "" {
			continue
		}

		xValues[xVal] = true
		yValues[yVal] = true

		if _, ok := valueMap[xVal]; !ok {
			valueMap[xVal] = make(map[string]float64)
		}
		valueMap[xVal][yVal] = agg.Mean
	}

	xLabels := sortedKeys(xValues)
	yLabels := sortedKeys(yValues)

	values := make([][]float64, len(xLabels))
	for i, x := range xLabels {
		values[i] = make([]float64, len(yLabels))
		for j, y := range yLabels {
			values[i][j] = valueMap[x][y]
		}
	}

	return &HeatmapData{
		XLabels: xLabels,
		YLabels: yLabels,
		Values:  values,
		Metric:  metricName,
		XAxis:   xParam,
		YAxis:   yParam,
	}, nil
}

func (v *Visualization) PrepareCorrelationHeatmap(matrix *MatrixData) (*HeatmapData, error) {
	if matrix == nil {
		return nil, nil
	}

	r, c := matrix.Dims()
	values := make([][]float64, r)
	for i := 0; i < r; i++ {
		values[i] = make([]float64, c)
		for j := 0; j < c; j++ {
			values[i][j] = matrix.At(i, j)
		}
	}

	return &HeatmapData{
		XLabels: matrix.ColLabels,
		YLabels: matrix.RowLabels,
		Values:  values,
		Metric:  "correlation",
		XAxis:   "Variables",
		YAxis:   "Variables",
	}, nil
}

func (v *Visualization) PrepareScatterPlot(
	results []*ResultWithParams,
	xMetric, yMetric, colorMetric string,
) (*ScatterData, error) {
	points := make([]*ScatterPoint, 0, len(results))

	for _, r := range results {
		xVal, xOk := r.Values[xMetric]
		yVal, yOk := r.Values[yMetric]

		if !xOk || !yOk {
			continue
		}

		point := &ScatterPoint{
			X:     xVal,
			Y:     yVal,
			Label: r.ParamsHash,
		}

		if colorMetric != "" {
			if cVal, ok := r.Values[colorMetric]; ok {
				point.Color = cVal
			}
		}

		points = append(points, point)
	}

	return &ScatterData{
		Points: points,
		XLabel: xMetric,
		YLabel: yMetric,
		Title:  yMetric + " vs " + xMetric,
	}, nil
}

func (v *Visualization) PrepareParamScatterPlot(
	results []*ResultWithParams,
	paramName, outputMetric string,
) (*ScatterData, error) {
	points := make([]*ScatterPoint, 0, len(results))

	for _, r := range results {
		paramVal, err := getParamFloat(r.Params, paramName)
		if err != nil {
			continue
		}

		outputVal, ok := r.Values[outputMetric]
		if !ok {
			continue
		}

		points = append(points, &ScatterPoint{
			X:     paramVal,
			Y:     outputVal,
			Label: r.ParamsHash,
		})
	}

	return &ScatterData{
		Points: points,
		XLabel: paramName,
		YLabel: outputMetric,
		Title:  outputMetric + " vs " + paramName,
	}, nil
}

func (v *Visualization) PrepareConvergenceData(
	results []*ResultWithParams,
	metricName string,
) (*ConvergenceData, error) {
	type point struct {
		iteration int64
		value     float64
	}

	points := make([]*point, 0, len(results))
	for _, r := range results {
		if val, ok := r.Values[metricName]; ok {
			points = append(points, &point{
				iteration: r.Result.Iteration,
				value:     val,
			})
		}
	}

	sort.Slice(points, func(i, j int) bool {
		return points[i].iteration < points[j].iteration
	})

	convPoints := make([]*ConvergencePoint, len(points))
	runningMean := make([]float64, len(points))
	runningVariance := make([]float64, len(points))

	var sum, sumSq float64
	for i, p := range points {
		convPoints[i] = &ConvergencePoint{
			Iteration: p.iteration,
			Value:     p.value,
			Metric:    metricName,
		}

		sum += p.value
		sumSq += p.value * p.value
		n := float64(i + 1)
		runningMean[i] = sum / n
		if i > 0 {
			runningVariance[i] = (sumSq - sum*sum/n) / (n - 1)
		}
	}

	return &ConvergenceData{
		Points:  convPoints,
		Metric:  metricName,
		Running: runningMean,
	}, nil
}

func (v *Visualization) PrepareSensitivityBar(
	result *SensitivityResult,
) (*BarData, error) {
	if result == nil || len(result.Indices) == 0 {
		return nil, nil
	}

	rankMap := make(map[string]int)
	for i, name := range result.Ranking {
		rankMap[name] = i
	}

	sortedIndices := make([]*SensitivityIndex, len(result.Indices))
	copy(sortedIndices, result.Indices)
	sort.Slice(sortedIndices, func(i, j int) bool {
		return rankMap[sortedIndices[i].Parameter] < rankMap[sortedIndices[j].Parameter]
	})

	labels := make([]string, len(sortedIndices))
	values := make([]float64, len(sortedIndices))
	errors := make([]float64, len(sortedIndices))

	for i, idx := range sortedIndices {
		labels[i] = idx.Parameter
		values[i] = idx.FirstOrder
		errors[i] = math.Max(0, idx.TotalOrder-idx.FirstOrder)
	}

	return &BarData{
		Labels: labels,
		Values: values,
		Errors: errors,
		Title:  "Sensitivity Analysis - " + result.OutputMetric,
		YLabel: "First Order Index",
	}, nil
}

func (v *Visualization) PrepareRegressionSensitivityBar(
	results []*RegressionSensitivity,
) (*BarData, error) {
	if len(results) == 0 {
		return nil, nil
	}

	sorted := make([]*RegressionSensitivity, len(results))
	copy(sorted, results)
	sort.Slice(sorted, func(i, j int) bool {
		return math.Abs(sorted[i].Standardized) > math.Abs(sorted[j].Standardized)
	})

	labels := make([]string, len(sorted))
	values := make([]float64, len(sorted))
	errors := make([]float64, len(sorted))

	for i, r := range sorted {
		labels[i] = r.Parameter
		values[i] = r.Standardized
		errors[i] = r.StdErr
	}

	return &BarData{
		Labels: labels,
		Values: values,
		Errors: errors,
		Title:  "Regression Sensitivity Analysis",
		YLabel: "Standardized Coefficient",
	}, nil
}

func (v *Visualization) PreparePercentileBars(
	values []float64,
	percentiles []float64,
) (*BarData, error) {
	if len(values) == 0 {
		return nil, nil
	}

	if percentiles == nil {
		percentiles = []float64{10, 25, 50, 75, 90, 95, 99}
	}

	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	labels := make([]string, len(percentiles))
	vals := make([]float64, len(percentiles))
	errors := make([]float64, len(percentiles))

	for i, p := range percentiles {
		labels[i] = strconv.FormatFloat(p, 'f', 0, 64) + "%"
		vals[i] = stat.Quantile(p/100.0, stat.Empirical, sorted, nil)
	}

	return &BarData{
		Labels: labels,
		Values: vals,
		Errors: errors,
		Title:  "Percentile Distribution",
		YLabel: "Value",
	}, nil
}

func (v *Visualization) PrepareBoxPlotData(
	results map[string]*AggregatedResult,
	metricName string,
) (map[string][]float64, []string) {
	data := make(map[string][]float64)
	labels := make([]string, 0, len(results))

	for _, agg := range results {
		if agg.MetricName != metricName {
			continue
		}

		label := getParamsLabel(agg.Params)
		data[label] = agg.RawValues
		labels = append(labels, label)
	}

	sort.Strings(labels)
	return data, labels
}

func (v *Visualization) PrepareHistogramData(
	values []float64,
	bins int,
) ([]float64, []float64, float64, float64) {
	if len(values) == 0 || bins <= 0 {
		return nil, nil, 0, 0
	}

	min := math.Inf(1)
	max := math.Inf(-1)
	for _, v := range values {
		if v < min {
			min = v
		}
		if v > max {
			max = v
		}
	}

	if min == max {
		return nil, nil, min, max
	}

	binWidth := (max - min) / float64(bins)
	histogram := make([]float64, bins)
	binEdges := make([]float64, bins+1)

	for i := 0; i <= bins; i++ {
		binEdges[i] = min + float64(i)*binWidth
	}

	for _, v := range values {
		binIdx := int((v - min) / binWidth)
		if binIdx >= bins {
			binIdx = bins - 1
		}
		if binIdx < 0 {
			binIdx = 0
		}
		histogram[binIdx]++
	}

	return histogram, binEdges, min, max
}

func (v *Visualization) PrepareResidualPlot(
	predicted, actual []float64,
) (*ScatterData, error) {
	if len(predicted) != len(actual) {
		return nil, nil
	}

	points := make([]*ScatterPoint, len(predicted))
	for i := range predicted {
		residual := actual[i] - predicted[i]
		points[i] = &ScatterPoint{
			X:     predicted[i],
			Y:     residual,
			Label: strconv.Itoa(i),
		}
	}

	return &ScatterData{
		Points: points,
		XLabel: "Predicted Values",
		YLabel: "Residuals",
		Title:  "Residual Plot",
	}, nil
}

func (v *Visualization) PrepareQQPlot(values []float64) (*ScatterData, error) {
	if len(values) < 2 {
		return nil, nil
	}

	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	n := len(values)
	points := make([]*ScatterPoint, n)

	mean := stat.Mean(values, nil)
	stdDev := stat.StdDev(values, nil)

	for i := 0; i < n; i++ {
		p := (float64(i) + 0.5) / float64(n)
		normalQuantile := normalPPF(p)
		scaledQuantile := mean + normalQuantile*stdDev

		points[i] = &ScatterPoint{
			X:     scaledQuantile,
			Y:     sorted[i],
			Label: strconv.Itoa(i),
		}
	}

	return &ScatterData{
		Points: points,
		XLabel: "Theoretical Quantiles",
		YLabel: "Sample Quantiles",
		Title:  "Q-Q Plot",
	}, nil
}

func normalPPF(p float64) float64 {
	if p <= 0 {
		return math.Inf(-1)
	}
	if p >= 1 {
		return math.Inf(1)
	}

	a := []float64{2.50662823884, -18.61500062529, 41.39119773534, -25.44106049637}
	b := []float64{-8.47351093090, 23.08336743743, -21.06224101826, 3.13082909833}

	q := p - 0.5
	r := q * q

	num := (((a[3]*r+a[2])*r+a[1])*r + a[0]) * q
	den := ((((b[3]*r+b[2])*r+b[1])*r+b[0])*r + 1)

	x := num / den

	x = x - (math.Erfc(x/math.Sqrt(2))/2-p)*math.Sqrt(2*math.Pi)*math.Exp(x*x/2)

	return x
}

func getParamString(params models.Params, key string) string {
	if val, ok := params[key]; ok {
		return toString(val)
	}
	return ""
}

func getParamFloat(params models.Params, key string) (float64, error) {
	if val, ok := params[key]; ok {
		return toFloat(val)
	}
	return 0, nil
}

func toString(v interface{}) string {
	switch val := v.(type) {
	case string:
		return val
	case float64:
		return strconv.FormatFloat(val, 'f', 6, 64)
	case float32:
		return strconv.FormatFloat(float64(val), 'f', 6, 64)
	case int:
		return strconv.Itoa(val)
	case int32:
		return strconv.FormatInt(int64(val), 10)
	case int64:
		return strconv.FormatInt(val, 10)
	case bool:
		return strconv.FormatBool(val)
	default:
		return ""
	}
}

func sortedKeys(m map[string]bool) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}

	sort.Slice(keys, func(i, j int) bool {
		f1, err1 := strconv.ParseFloat(keys[i], 64)
		f2, err2 := strconv.ParseFloat(keys[j], 64)
		if err1 == nil && err2 == nil {
			return f1 < f2
		}
		return keys[i] < keys[j]
	})

	return keys
}

func getParamsLabel(params models.Params) string {
	keys := make([]string, 0, len(params))
	for k := range params {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	parts := make([]string, 0, len(keys))
	for _, k := range keys {
		parts = append(parts, k+"="+toString(params[k]))
	}

	if len(parts) > 3 {
		return parts[0] + "," + parts[1] + ",..."
	}

	return join(parts, ",")
}

func join(parts []string, sep string) string {
	if len(parts) == 0 {
		return ""
	}
	result := parts[0]
	for i := 1; i < len(parts); i++ {
		result += sep + parts[i]
	}
	return result
}
