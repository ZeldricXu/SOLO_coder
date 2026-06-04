package chart

import (
	"github.com/dataexplorer/store"
)

type ChartType int

const (
	BarChart ChartType = iota
	LineChart
	ScatterChart
	BoxPlot
	Heatmap
	Histogram
)

type ChartConfig struct {
	Type           ChartType
	XField         string
	YField         string
	ColorField     string
	Title          string
	Width          int
	Height         int
	Aggregate      string
	BinCount       int
	ColorAggregate string
}

func getVegaType(col *store.Column) string {
	switch col.DataType {
	case store.TypeString, store.TypeBool:
		return "nominal"
	case store.TypeInt, store.TypeFloat:
		return "quantitative"
	case store.TypeDate:
		return "temporal"
	default:
		return "nominal"
	}
}

func markType(ct ChartType) string {
	switch ct {
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

func buildBaseSpecWithBuilder(table *store.Table, config ChartConfig) (map[string]interface{}, error) {
	builder := NewChartSpecBuilder(config)

	if xCol := table.GetColumn(config.XField); xCol != nil {
		xMeta := columnToMeta(config.XField, xCol)
		builder.AddColumnMeta(xMeta)
	}

	if config.YField != "" {
		if yCol := table.GetColumn(config.YField); yCol != nil {
			yMeta := columnToMeta(config.YField, yCol)
			builder.AddColumnMeta(yMeta)
		}
	}

	if config.ColorField != "" {
		if cCol := table.GetColumn(config.ColorField); cCol != nil {
			cMeta := columnToMeta(config.ColorField, cCol)
			builder.AddColumnMeta(cMeta)
		}
	}

	data := table.ToJSON(0, 10000)
	builder.SetData(data)

	return builder.Build()
}

func columnToMeta(name string, col *store.Column) *ColumnMeta {
	min, max, mean, count, nullCount, _ := col.Stats()
	unique := col.UniqueValues()
	return &ColumnMeta{
		Name:        name,
		DataType:    col.DataType.String(),
		Min:         min,
		Max:         max,
		Mean:        mean,
		Count:       count,
		NullCount:   nullCount,
		Cardinality: len(unique),
	}
}

func GenerateSpec(table *store.Table, config ChartConfig) (string, error) {
	builder := NewChartSpecBuilder(config)

	if xCol := table.GetColumn(config.XField); xCol != nil {
		builder.AddColumnMeta(columnToMeta(config.XField, xCol))
	}
	if config.YField != "" {
		if yCol := table.GetColumn(config.YField); yCol != nil {
			builder.AddColumnMeta(columnToMeta(config.YField, yCol))
		}
	}
	if config.ColorField != "" {
		if cCol := table.GetColumn(config.ColorField); cCol != nil {
			builder.AddColumnMeta(columnToMeta(config.ColorField, cCol))
		}
	}

	builder.SetData(table.ToJSON(0, 10000))
	return builder.BuildJSON()
}

func GenerateMultiSeriesSpec(table *store.Table, config ChartConfig, seriesField string) (string, error) {
	builder := NewChartSpecBuilder(config)

	if xCol := table.GetColumn(config.XField); xCol != nil {
		builder.AddColumnMeta(columnToMeta(config.XField, xCol))
	}
	if config.YField != "" {
		if yCol := table.GetColumn(config.YField); yCol != nil {
			builder.AddColumnMeta(columnToMeta(config.YField, yCol))
		}
	}
	if seriesCol := table.GetColumn(seriesField); seriesCol != nil {
		builder.AddColumnMeta(columnToMeta(seriesField, seriesCol))
	}

	builder.SetData(table.ToJSON(0, 10000))
	return builder.BuildMultiSeriesJSON(seriesField)
}

func GenerateBrushSpec(table *store.Table, config ChartConfig) (string, error) {
	builder := NewChartSpecBuilder(config)

	if xCol := table.GetColumn(config.XField); xCol != nil {
		builder.AddColumnMeta(columnToMeta(config.XField, xCol))
	}
	if config.YField != "" {
		if yCol := table.GetColumn(config.YField); yCol != nil {
			builder.AddColumnMeta(columnToMeta(config.YField, yCol))
		}
	}
	if config.ColorField != "" {
		if cCol := table.GetColumn(config.ColorField); cCol != nil {
			builder.AddColumnMeta(columnToMeta(config.ColorField, cCol))
		}
	}

	builder.SetData(table.ToJSON(0, 10000))
	return builder.BuildBrushJSON()
}
