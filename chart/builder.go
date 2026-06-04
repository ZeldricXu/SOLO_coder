package chart

import (
	"encoding/json"
	"fmt"
)

type ColumnMeta struct {
	Name        string
	DataType    string
	Min         float64
	Max         float64
	Mean        float64
	Count       int
	NullCount   int
	Cardinality int
}

type ChartSpecBuilder struct {
	config      ChartConfig
	columnMetas map[string]*ColumnMeta
	data        []map[string]interface{}
}

func NewChartSpecBuilder(config ChartConfig) *ChartSpecBuilder {
	return &ChartSpecBuilder{
		config:      config,
		columnMetas: make(map[string]*ColumnMeta),
	}
}

func (b *ChartSpecBuilder) AddColumnMeta(meta *ColumnMeta) {
	b.columnMetas[meta.Name] = meta
}

func (b *ChartSpecBuilder) SetData(data []map[string]interface{}) {
	b.data = data
}

func (b *ChartSpecBuilder) getVegaTypeFromMeta(colName string) string {
	meta, ok := b.columnMetas[colName]
	if !ok {
		return "nominal"
	}
	switch meta.DataType {
	case "string", "bool":
		return "nominal"
	case "int", "float":
		return "quantitative"
	case "date":
		return "temporal"
	default:
		return "nominal"
	}
}

func (b *ChartSpecBuilder) markType() string {
	switch b.config.Type {
	case BarChart, Histogram:
		return "bar"
	case LineChart:
		return "line"
	case ScatterChart:
		return "point"
	case BoxPlot:
		return "boxplot"
	case Heatmap:
		return "rect"
	default:
		return "bar"
	}
}

func (b *ChartSpecBuilder) Build() (map[string]interface{}, error) {
	if b.data == nil {
		return nil, fmt.Errorf("no data set for chart spec")
	}

	mark := b.markType()
	var encoding map[string]interface{}

	if b.config.Type == Heatmap {
		xType := b.getVegaTypeFromMeta(b.config.XField)
		if xType == "quantitative" {
			xType = "ordinal"
		}
		yType := b.getVegaTypeFromMeta(b.config.YField)
		if yType == "quantitative" {
			yType = "ordinal"
		}

		colorAgg := b.config.ColorAggregate
		if colorAgg == "" {
			colorAgg = "count"
		}

		encoding = map[string]interface{}{
			"x": map[string]interface{}{
				"field": b.config.XField,
				"type":  xType,
			},
			"y": map[string]interface{}{
				"field": b.config.YField,
				"type":  yType,
			},
			"color": map[string]interface{}{
				"aggregate": colorAgg,
				"type":      "quantitative",
			},
		}
	} else if b.config.Type == Histogram {
		binCount := b.config.BinCount
		if binCount == 0 {
			binCount = 10
		}

		xType := b.getVegaTypeFromMeta(b.config.XField)

		encoding = map[string]interface{}{
			"x": map[string]interface{}{
				"bin":   map[string]interface{}{"maxbins": binCount},
				"field": b.config.XField,
				"type":  xType,
			},
			"y": map[string]interface{}{
				"aggregate": "count",
				"type":      "quantitative",
			},
		}
	} else {
		xType := b.getVegaTypeFromMeta(b.config.XField)
		yType := b.getVegaTypeFromMeta(b.config.YField)

		xEncoding := map[string]interface{}{
			"field": b.config.XField,
			"type":  xType,
		}
		yEncoding := map[string]interface{}{
			"field": b.config.YField,
			"type":  yType,
		}

		if b.config.Type == BoxPlot {
			xEncoding["type"] = "nominal"
			yEncoding["type"] = "quantitative"
		}

		if b.config.Type == BarChart && b.config.Aggregate != "" {
			yEncoding["aggregate"] = b.config.Aggregate
		}

		encoding = map[string]interface{}{
			"x": xEncoding,
			"y": yEncoding,
		}

		if b.config.ColorField != "" {
			colorType := b.getVegaTypeFromMeta(b.config.ColorField)
			encoding["color"] = map[string]interface{}{
				"field": b.config.ColorField,
				"type":  colorType,
			}
		}
	}

	width := b.config.Width
	if width == 0 {
		width = 600
	}
	height := b.config.Height
	if height == 0 {
		height = 400
	}

	spec := map[string]interface{}{
		"$schema": "https://vega.github.io/schema/vega-lite/v5.json",
		"title":   b.config.Title,
		"data": map[string]interface{}{
			"values": b.data,
		},
		"mark":     mark,
		"encoding": encoding,
		"width":    width,
		"height":   height,
	}

	return spec, nil
}

func (b *ChartSpecBuilder) BuildBrush() (map[string]interface{}, error) {
	spec, err := b.Build()
	if err != nil {
		return nil, err
	}

	if b.config.Type == Histogram || b.config.Type == Heatmap {
		return spec, nil
	}

	spec["selection"] = map[string]interface{}{
		"brush": map[string]interface{}{
			"type": "interval",
		},
	}

	encoding := spec["encoding"].(map[string]interface{})
	encoding["color"] = map[string]interface{}{
		"condition": map[string]interface{}{
			"selection": "brush",
			"value":     "steelblue",
		},
		"value": "grey",
	}

	return spec, nil
}

func (b *ChartSpecBuilder) BuildMultiSeries(seriesField string) (map[string]interface{}, error) {
	spec, err := b.Build()
	if err != nil {
		return nil, err
	}

	if b.config.Type == Heatmap {
		return spec, nil
	}

	encoding := spec["encoding"].(map[string]interface{})
	seriesType := b.getVegaTypeFromMeta(seriesField)

	colorEnc := map[string]interface{}{
		"field": seriesField,
		"type":  seriesType,
	}
	encoding["color"] = colorEnc

	if b.config.Type == LineChart {
		encoding["detail"] = map[string]interface{}{
			"field": seriesField,
			"type":  seriesType,
		}
	}

	return spec, nil
}

func (b *ChartSpecBuilder) BuildJSON() (string, error) {
	spec, err := b.Build()
	if err != nil {
		return "", err
	}
	js, err := json.Marshal(spec)
	if err != nil {
		return "", err
	}
	return string(js), nil
}

func (b *ChartSpecBuilder) BuildBrushJSON() (string, error) {
	spec, err := b.BuildBrush()
	if err != nil {
		return "", err
	}
	js, err := json.Marshal(spec)
	if err != nil {
		return "", err
	}
	return string(js), nil
}

func (b *ChartSpecBuilder) BuildMultiSeriesJSON(seriesField string) (string, error) {
	spec, err := b.BuildMultiSeries(seriesField)
	if err != nil {
		return "", err
	}
	js, err := json.Marshal(spec)
	if err != nil {
		return "", err
	}
	return string(js), nil
}

func ExtractColumnMeta(name string, dataType string, values []interface{}) *ColumnMeta {
	meta := &ColumnMeta{
		Name:     name,
		DataType: dataType,
		Count:    len(values),
	}

	seen := make(map[interface{}]bool)
	var minVal, maxVal, sum float64
	hasMin := false

	for _, v := range values {
		if v == nil {
			meta.NullCount++
			continue
		}
		seen[v] = true

		switch dataType {
		case "int", "float":
			var f float64
			switch val := v.(type) {
			case int64:
				f = float64(val)
			case float64:
				f = val
			case int:
				f = float64(val)
			default:
				continue
			}
			if !hasMin {
				minVal, maxVal = f, f
				hasMin = true
			}
			if f < minVal {
				minVal = f
			}
			if f > maxVal {
				maxVal = f
			}
			sum += f
		}
	}

	meta.Cardinality = len(seen)
	if meta.Count > meta.NullCount && (dataType == "int" || dataType == "float") {
		meta.Min = minVal
		meta.Max = maxVal
		meta.Mean = sum / float64(meta.Count-meta.NullCount)
	}

	return meta
}
