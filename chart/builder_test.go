package chart

import (
	"encoding/json"
	"testing"
)

func TestChartSpecBuilder_BasicSpec(t *testing.T) {
	config := ChartConfig{
		Type:   BarChart,
		XField: "category",
		YField: "value",
		Title:  "Test Chart",
	}

	builder := NewChartSpecBuilder(config)

	builder.AddColumnMeta(&ColumnMeta{
		Name:     "category",
		DataType: "string",
		Count:    100,
	})
	builder.AddColumnMeta(&ColumnMeta{
		Name:     "value",
		DataType: "float",
		Min:      0,
		Max:      100,
		Mean:     50,
		Count:    100,
	})

	data := []map[string]interface{}{
		{"category": "A", "value": 10.5},
		{"category": "B", "value": 20.5},
		{"category": "C", "value": 30.5},
	}
	builder.SetData(data)

	spec, err := builder.Build()
	if err != nil {
		t.Fatal(err)
	}

	if spec["$schema"] != "https://vega.github.io/schema/vega-lite/v5.json" {
		t.Fatal("invalid schema URL")
	}
	if spec["title"] != "Test Chart" {
		t.Fatalf("expected title 'Test Chart', got %v", spec["title"])
	}
	if spec["mark"] != "bar" {
		t.Fatalf("expected mark 'bar', got %v", spec["mark"])
	}

	dataField, ok := spec["data"].(map[string]interface{})
	if !ok {
		t.Fatal("data field is not a map")
	}
	values, ok := dataField["values"].([]map[string]interface{})
	if !ok || len(values) != 3 {
		t.Fatalf("expected 3 data rows, got %v", values)
	}
}

func TestChartSpecBuilder_Heatmap(t *testing.T) {
	config := ChartConfig{
		Type:           Heatmap,
		XField:         "day",
		YField:         "hour",
		ColorAggregate: "sum",
		Title:          "Heatmap Test",
	}

	builder := NewChartSpecBuilder(config)
	builder.AddColumnMeta(&ColumnMeta{
		Name:     "day",
		DataType: "string",
		Count:    7,
	})
	builder.AddColumnMeta(&ColumnMeta{
		Name:     "hour",
		DataType: "int",
		Count:    24,
	})
	builder.AddColumnMeta(&ColumnMeta{
		Name:     "value",
		DataType: "int",
		Count:    168,
	})

	data := make([]map[string]interface{}, 10)
	for i := 0; i < 10; i++ {
		data[i] = map[string]interface{}{
			"day":   "Mon",
			"hour":  i,
			"value": i * 10,
		}
	}
	builder.SetData(data)

	spec, err := builder.Build()
	if err != nil {
		t.Fatal(err)
	}

	if spec["mark"] != "rect" {
		t.Fatalf("expected mark 'rect' for heatmap, got %v", spec["mark"])
	}

	encoding := spec["encoding"].(map[string]interface{})
	colorEnc := encoding["color"].(map[string]interface{})
	if colorEnc["aggregate"] != "sum" {
		t.Fatalf("expected color aggregate 'sum', got %v", colorEnc["aggregate"])
	}
	if colorEnc["type"] != "quantitative" {
		t.Fatalf("expected color type 'quantitative', got %v", colorEnc["type"])
	}
}

func TestChartSpecBuilder_Histogram(t *testing.T) {
	config := ChartConfig{
		Type:     Histogram,
		XField:   "amount",
		BinCount: 20,
		Title:    "Histogram Test",
	}

	builder := NewChartSpecBuilder(config)
	builder.AddColumnMeta(&ColumnMeta{
		Name:     "amount",
		DataType: "float",
		Min:      0,
		Max:      1000,
		Count:    1000,
	})

	data := make([]map[string]interface{}, 100)
	for i := 0; i < 100; i++ {
		data[i] = map[string]interface{}{
			"amount": float64(i * 10),
		}
	}
	builder.SetData(data)

	spec, err := builder.Build()
	if err != nil {
		t.Fatal(err)
	}

	if spec["mark"] != "bar" {
		t.Fatalf("expected mark 'bar' for histogram, got %v", spec["mark"])
	}

	encoding := spec["encoding"].(map[string]interface{})
	xEnc := encoding["x"].(map[string]interface{})
	bin, ok := xEnc["bin"].(map[string]interface{})
	if !ok {
		t.Fatal("expected bin config for histogram")
	}
	if bin["maxbins"] != 20 {
		t.Fatalf("expected maxbins=20, got %v", bin["maxbins"])
	}

	yEnc := encoding["y"].(map[string]interface{})
	if yEnc["aggregate"] != "count" {
		t.Fatalf("expected y aggregate 'count', got %v", yEnc["aggregate"])
	}
}

func TestChartSpecBuilder_Brush(t *testing.T) {
	config := ChartConfig{
		Type:   ScatterChart,
		XField: "x",
		YField: "y",
	}

	builder := NewChartSpecBuilder(config)
	builder.AddColumnMeta(&ColumnMeta{Name: "x", DataType: "float", Count: 100})
	builder.AddColumnMeta(&ColumnMeta{Name: "y", DataType: "float", Count: 100})

	data := make([]map[string]interface{}, 50)
	for i := 0; i < 50; i++ {
		data[i] = map[string]interface{}{
			"x": float64(i),
			"y": float64(i * 2),
		}
	}
	builder.SetData(data)

	spec, err := builder.BuildBrush()
	if err != nil {
		t.Fatal(err)
	}

	selection, ok := spec["selection"].(map[string]interface{})
	if !ok {
		t.Fatal("expected selection config for brush")
	}
	brush, ok := selection["brush"].(map[string]interface{})
	if !ok || brush["type"] != "interval" {
		t.Fatalf("expected brush type 'interval', got %v", brush)
	}

	encoding := spec["encoding"].(map[string]interface{})
	colorEnc := encoding["color"].(map[string]interface{})
	condition, ok := colorEnc["condition"].(map[string]interface{})
	if !ok || condition["selection"] != "brush" {
		t.Fatal("expected brush condition in color encoding")
	}
}

func TestChartSpecBuilder_MultiSeries(t *testing.T) {
	config := ChartConfig{
		Type:   LineChart,
		XField: "date",
		YField: "value",
	}

	builder := NewChartSpecBuilder(config)
	builder.AddColumnMeta(&ColumnMeta{Name: "date", DataType: "date", Count: 100})
	builder.AddColumnMeta(&ColumnMeta{Name: "value", DataType: "float", Count: 100})
	builder.AddColumnMeta(&ColumnMeta{Name: "category", DataType: "string", Count: 100})

	data := make([]map[string]interface{}, 30)
	for i := 0; i < 30; i++ {
		cat := "A"
		if i%2 == 0 {
			cat = "B"
		}
		data[i] = map[string]interface{}{
			"date":     i,
			"value":    float64(i) * 1.5,
			"category": cat,
		}
	}
	builder.SetData(data)

	spec, err := builder.BuildMultiSeries("category")
	if err != nil {
		t.Fatal(err)
	}

	encoding := spec["encoding"].(map[string]interface{})
	colorEnc := encoding["color"].(map[string]interface{})
	if colorEnc["field"] != "category" {
		t.Fatalf("expected color field 'category', got %v", colorEnc["field"])
	}

	detail, ok := encoding["detail"].(map[string]interface{})
	if !ok || detail["field"] != "category" {
		t.Fatalf("expected detail field 'category' for line chart, got %v", detail)
	}
}

func TestChartSpecBuilder_HeatmapSkipsMultiSeries(t *testing.T) {
	config := ChartConfig{
		Type:   Heatmap,
		XField: "x",
		YField: "y",
	}

	builder := NewChartSpecBuilder(config)
	builder.AddColumnMeta(&ColumnMeta{Name: "x", DataType: "string", Count: 10})
	builder.AddColumnMeta(&ColumnMeta{Name: "y", DataType: "string", Count: 10})
	builder.SetData([]map[string]interface{}{{"x": "a", "y": "b"}})

	spec, err := builder.BuildMultiSeries("series")
	if err != nil {
		t.Fatal(err)
	}

	encoding := spec["encoding"].(map[string]interface{})
	if _, hasColor := encoding["color"]; hasColor {
		colorEnc := encoding["color"].(map[string]interface{})
		if colorEnc["field"] == "series" {
			t.Fatal("heatmap should not have multi-series color encoding")
		}
	}
}

func TestChartSpecBuilder_HistogramSkipsBrush(t *testing.T) {
	config := ChartConfig{
		Type:   Histogram,
		XField: "value",
	}

	builder := NewChartSpecBuilder(config)
	builder.AddColumnMeta(&ColumnMeta{Name: "value", DataType: "float", Count: 100})
	builder.SetData([]map[string]interface{}{{"value": 1.0}})

	spec, err := builder.BuildBrush()
	if err != nil {
		t.Fatal(err)
	}

	if _, hasSelection := spec["selection"]; hasSelection {
		t.Fatal("histogram should not have brush selection")
	}
}

func TestChartSpecBuilder_WithColorField(t *testing.T) {
	config := ChartConfig{
		Type:       ScatterChart,
		XField:     "x",
		YField:     "y",
		ColorField: "group",
	}

	builder := NewChartSpecBuilder(config)
	builder.AddColumnMeta(&ColumnMeta{Name: "x", DataType: "float", Count: 100})
	builder.AddColumnMeta(&ColumnMeta{Name: "y", DataType: "float", Count: 100})
	builder.AddColumnMeta(&ColumnMeta{Name: "group", DataType: "string", Count: 100})

	data := []map[string]interface{}{
		{"x": 1.0, "y": 2.0, "group": "A"},
		{"x": 2.0, "y": 4.0, "group": "B"},
	}
	builder.SetData(data)

	spec, err := builder.Build()
	if err != nil {
		t.Fatal(err)
	}

	encoding := spec["encoding"].(map[string]interface{})
	colorEnc := encoding["color"].(map[string]interface{})
	if colorEnc["field"] != "group" {
		t.Fatalf("expected color field 'group', got %v", colorEnc["field"])
	}
	if colorEnc["type"] != "nominal" {
		t.Fatalf("expected color type 'nominal', got %v", colorEnc["type"])
	}
}

func TestExtractColumnMeta(t *testing.T) {
	values := []interface{}{int64(1), int64(2), int64(3), nil, int64(5)}
	meta := ExtractColumnMeta("test", "int", values)

	if meta.Name != "test" {
		t.Fatalf("expected name 'test', got %s", meta.Name)
	}
	if meta.DataType != "int" {
		t.Fatalf("expected type 'int', got %s", meta.DataType)
	}
	if meta.Count != 5 {
		t.Fatalf("expected count 5, got %d", meta.Count)
	}
	if meta.NullCount != 1 {
		t.Fatalf("expected nullCount 1, got %d", meta.NullCount)
	}
	if meta.Min != 1.0 {
		t.Fatalf("expected min 1, got %f", meta.Min)
	}
	if meta.Max != 5.0 {
		t.Fatalf("expected max 5, got %f", meta.Max)
	}
	if meta.Mean != 2.75 {
		t.Fatalf("expected mean 2.75, got %f", meta.Mean)
	}
	if meta.Cardinality != 4 {
		t.Fatalf("expected cardinality 4, got %d", meta.Cardinality)
	}
}

func TestChartSpecBuilder_JSONOutput(t *testing.T) {
	config := ChartConfig{
		Type:   BarChart,
		XField: "cat",
		YField: "val",
		Title:  "JSON Test",
	}

	builder := NewChartSpecBuilder(config)
	builder.AddColumnMeta(&ColumnMeta{Name: "cat", DataType: "string", Count: 3})
	builder.AddColumnMeta(&ColumnMeta{Name: "val", DataType: "float", Count: 3})
	builder.SetData([]map[string]interface{}{
		{"cat": "A", "val": 1.0},
		{"cat": "B", "val": 2.0},
		{"cat": "C", "val": 3.0},
	})

	jsonStr, err := builder.BuildJSON()
	if err != nil {
		t.Fatal(err)
	}

	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(jsonStr), &parsed); err != nil {
		t.Fatalf("invalid JSON output: %v", err)
	}

	if parsed["title"] != "JSON Test" {
		t.Fatalf("JSON parsing failed, expected title 'JSON Test', got %v", parsed["title"])
	}
}

func TestChartSpecBuilder_BoxPlot(t *testing.T) {
	config := ChartConfig{
		Type:   BoxPlot,
		XField: "category",
		YField: "value",
	}

	builder := NewChartSpecBuilder(config)
	builder.AddColumnMeta(&ColumnMeta{Name: "category", DataType: "string", Count: 100})
	builder.AddColumnMeta(&ColumnMeta{Name: "value", DataType: "float", Count: 100})

	data := make([]map[string]interface{}, 100)
	for i := 0; i < 100; i++ {
		cat := "A"
		if i >= 50 {
			cat = "B"
		}
		data[i] = map[string]interface{}{
			"category": cat,
			"value":    float64(i % 10),
		}
	}
	builder.SetData(data)

	spec, err := builder.Build()
	if err != nil {
		t.Fatal(err)
	}

	if spec["mark"] != "boxplot" {
		t.Fatalf("expected mark 'boxplot', got %v", spec["mark"])
	}

	encoding := spec["encoding"].(map[string]interface{})
	xEnc := encoding["x"].(map[string]interface{})
	if xEnc["type"] != "nominal" {
		t.Fatalf("expected x type 'nominal' for boxplot, got %v", xEnc["type"])
	}
	yEnc := encoding["y"].(map[string]interface{})
	if yEnc["type"] != "quantitative" {
		t.Fatalf("expected y type 'quantitative' for boxplot, got %v", yEnc["type"])
	}
}
