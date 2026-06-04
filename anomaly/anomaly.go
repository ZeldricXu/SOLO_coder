package anomaly

import (
	"encoding/json"
	"fmt"
	"math"
	"sort"

	"github.com/dataexplorer/store"
)

type AnomalyMethod int

const (
	MethodIQR AnomalyMethod = iota
	MethodZScore
)

type AnomalyConfig struct {
	Column    string
	Method    AnomalyMethod
	Threshold float64
}

type AnomalyResult struct {
	Column         string         `json:"column"`
	Method         AnomalyMethod  `json:"method"`
	Threshold      float64        `json:"threshold"`
	AnomalyIndices []int          `json:"anomalyIndices"`
	AnomalyValues  []float64      `json:"anomalyValues"`
	LowerBound     float64        `json:"lowerBound"`
	UpperBound     float64        `json:"upperBound"`
	TotalChecked   int            `json:"totalChecked"`
	AnomalyCount   int            `json:"anomalyCount"`
}

func extractNumericValues(table *store.Table, colName string) ([]float64, []int, error) {
	col := table.GetColumn(colName)
	if col == nil {
		return nil, nil, fmt.Errorf("column %s not found", colName)
	}
	if col.DataType != store.TypeInt && col.DataType != store.TypeFloat {
		return nil, nil, fmt.Errorf("column %s is not numeric", colName)
	}

	var values []float64
	var indices []int
	for i := 0; i < table.RowCount; i++ {
		if col.IsNull(i) {
			continue
		}
		values = append(values, col.GetFloat(i))
		indices = append(indices, i)
	}
	return values, indices, nil
}

func percentile(sorted []float64, p float64) float64 {
	n := len(sorted)
	if n == 0 {
		return 0
	}
	if n == 1 {
		return sorted[0]
	}
	rank := p / 100.0 * float64(n-1)
	lowerIdx := int(math.Floor(rank))
	upperIdx := int(math.Ceil(rank))
	if lowerIdx == upperIdx {
		return sorted[lowerIdx]
	}
	fraction := rank - float64(lowerIdx)
	return sorted[lowerIdx] + fraction*(sorted[upperIdx]-sorted[lowerIdx])
}

func DetectIQR(table *store.Table, config AnomalyConfig) (*AnomalyResult, error) {
	threshold := config.Threshold
	if threshold == 0 {
		threshold = 1.5
	}

	values, indices, err := extractNumericValues(table, config.Column)
	if err != nil {
		return nil, err
	}

	if len(values) == 0 {
		return &AnomalyResult{
			Column:         config.Column,
			Method:         MethodIQR,
			Threshold:      threshold,
			AnomalyIndices: []int{},
			AnomalyValues:  []float64{},
			TotalChecked:   0,
			AnomalyCount:   0,
		}, nil
	}

	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	q1 := percentile(sorted, 25)
	q3 := percentile(sorted, 75)
	iqr := q3 - q1

	lower := q1 - threshold*iqr
	upper := q3 + threshold*iqr

	var anomalyIndices []int
	var anomalyValues []float64
	for i, val := range values {
		if val < lower || val > upper {
			anomalyIndices = append(anomalyIndices, indices[i])
			anomalyValues = append(anomalyValues, val)
		}
	}

	if anomalyIndices == nil {
		anomalyIndices = []int{}
		anomalyValues = []float64{}
	}

	return &AnomalyResult{
		Column:         config.Column,
		Method:         MethodIQR,
		Threshold:      threshold,
		AnomalyIndices: anomalyIndices,
		AnomalyValues:  anomalyValues,
		LowerBound:     lower,
		UpperBound:     upper,
		TotalChecked:   len(values),
		AnomalyCount:   len(anomalyIndices),
	}, nil
}

func DetectZScore(table *store.Table, config AnomalyConfig) (*AnomalyResult, error) {
	threshold := config.Threshold
	if threshold == 0 {
		threshold = 3.0
	}

	values, indices, err := extractNumericValues(table, config.Column)
	if err != nil {
		return nil, err
	}

	if len(values) == 0 {
		return &AnomalyResult{
			Column:         config.Column,
			Method:         MethodZScore,
			Threshold:      threshold,
			AnomalyIndices: []int{},
			AnomalyValues:  []float64{},
			TotalChecked:   0,
			AnomalyCount:   0,
		}, nil
	}

	mean := 0.0
	for _, v := range values {
		mean += v
	}
	mean /= float64(len(values))

	variance := 0.0
	for _, v := range values {
		diff := v - mean
		variance += diff * diff
	}

	stddev := 0.0
	if len(values) > 1 {
		stddev = math.Sqrt(variance / float64(len(values)-1))
	}

	lower := mean - threshold*stddev
	upper := mean + threshold*stddev

	var anomalyIndices []int
	var anomalyValues []float64
	for i, val := range values {
		if val < lower || val > upper {
			anomalyIndices = append(anomalyIndices, indices[i])
			anomalyValues = append(anomalyValues, val)
		}
	}

	if anomalyIndices == nil {
		anomalyIndices = []int{}
		anomalyValues = []float64{}
	}

	return &AnomalyResult{
		Column:         config.Column,
		Method:         MethodZScore,
		Threshold:      threshold,
		AnomalyIndices: anomalyIndices,
		AnomalyValues:  anomalyValues,
		LowerBound:     lower,
		UpperBound:     upper,
		TotalChecked:   len(values),
		AnomalyCount:   len(anomalyIndices),
	}, nil
}

func Detect(table *store.Table, config AnomalyConfig) (*AnomalyResult, error) {
	switch config.Method {
	case MethodIQR:
		return DetectIQR(table, config)
	case MethodZScore:
		return DetectZScore(table, config)
	default:
		return nil, fmt.Errorf("unknown anomaly method: %d", config.Method)
	}
}

func (r *AnomalyResult) ToJSON() string {
	b, err := json.Marshal(r)
	if err != nil {
		return "{}"
	}
	return string(b)
}

func MarkAnomalies(table *store.Table, result *AnomalyResult) *store.Table {
	anomalySet := make(map[int]bool)
	for _, idx := range result.AnomalyIndices {
		anomalySet[idx] = true
	}

	for _, idx := range result.AnomalyIndices {
		for _, c := range table.Columns {
			c.DirtyMap[idx] = true
		}
	}

	mask := make([]bool, table.RowCount)
	for i := 0; i < table.RowCount; i++ {
		mask[i] = anomalySet[i]
	}

	return table.Filter(mask)
}

func HighlightSpec(table *store.Table, result *AnomalyResult, chartType string) string {
	mark := "point"
	switch chartType {
	case "scatter":
		mark = "point"
	case "line":
		mark = "line"
	case "bar":
		mark = "bar"
	}

	data := table.ToJSON(0, table.RowCount)

	anomalySet := make(map[int]bool)
	for _, idx := range result.AnomalyIndices {
		anomalySet[idx] = true
	}

	var baseData []map[string]interface{}
	var anomalyData []map[string]interface{}
	for i, row := range data {
		baseData = append(baseData, row)
		if anomalySet[i] {
			rowCopy := make(map[string]interface{})
			for k, v := range row {
				rowCopy[k] = v
			}
			anomalyData = append(anomalyData, rowCopy)
		}
	}

	xCol := ""
	for _, name := range table.ColumnNames() {
		if name != result.Column {
			xCol = name
			break
		}
	}

	yType := "quantitative"
	col := table.GetColumn(result.Column)
	if col != nil {
		switch col.DataType {
		case store.TypeString, store.TypeBool:
			yType = "nominal"
		case store.TypeDate:
			yType = "temporal"
		}
	}

	xType := "nominal"
	if xCol != "" {
		xColObj := table.GetColumn(xCol)
		if xColObj != nil {
			switch xColObj.DataType {
			case store.TypeInt, store.TypeFloat:
				xType = "quantitative"
			case store.TypeDate:
				xType = "temporal"
			}
		}
	}

	spec := map[string]interface{}{
		"$schema": "https://vega.github.io/schema/vega-lite/v5.json",
		"layer": []interface{}{
			map[string]interface{}{
				"data": map[string]interface{}{
					"values": baseData,
				},
				"mark": mark,
				"encoding": map[string]interface{}{
					"x": map[string]interface{}{
						"field": xCol,
						"type":  xType,
					},
					"y": map[string]interface{}{
						"field": result.Column,
						"type":  yType,
					},
					"color": map[string]interface{}{
						"value": "steelblue",
					},
				},
			},
			map[string]interface{}{
				"data": map[string]interface{}{
					"values": anomalyData,
				},
				"mark": "point",
				"encoding": map[string]interface{}{
					"x": map[string]interface{}{
						"field": xCol,
						"type":  xType,
					},
					"y": map[string]interface{}{
						"field": result.Column,
						"type":  yType,
					},
					"color": map[string]interface{}{
						"value": "red",
					},
					"size": map[string]interface{}{
						"value": 100,
					},
				},
			},
		},
	}

	b, err := json.Marshal(spec)
	if err != nil {
		return "{}"
	}
	return string(b)
}
