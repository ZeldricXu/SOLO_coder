package chart

import (
	"encoding/json"
	"testing"

	"github.com/dataexplorer/store"
)

func createTestTable() *store.Table {
	tbl := store.NewTable("test")
	tbl.RowCount = 4

	tbl.AddColumn("date", store.TypeDate)
	tbl.AddColumn("hour", store.TypeInt)
	tbl.AddColumn("amount", store.TypeFloat)
	tbl.AddColumn("category", store.TypeString)
	tbl.AddColumn("value", store.TypeInt)

	dateCol := tbl.GetColumn("date")
	hourCol := tbl.GetColumn("hour")
	amountCol := tbl.GetColumn("amount")
	catCol := tbl.GetColumn("category")
	valueCol := tbl.GetColumn("value")

	for i := 0; i < 4; i++ {
		dateCol.SetValue(i, int64(1609459200000+i*86400000))
		hourCol.SetValue(i, int64(i*6))
		amountCol.SetValue(i, float64(10.5*float64(i+1)))
		catCol.SetValue(i, string(rune('A'+i)))
		valueCol.SetValue(i, int64(100*(i+1)))
	}

	return tbl
}

func TestHeatmapSpec(t *testing.T) {
	tbl := createTestTable()

	config := ChartConfig{
		Type:   Heatmap,
		XField: "date",
		YField: "hour",
		Title:  "Test Heatmap",
	}

	specStr, err := GenerateSpec(tbl, config)
	if err != nil {
		t.Fatalf("GenerateSpec failed: %v", err)
	}

	var spec map[string]interface{}
	err = json.Unmarshal([]byte(specStr), &spec)
	if err != nil {
		t.Fatalf("spec is not valid JSON: %v", err)
	}

	if spec["mark"] != "rect" {
		t.Errorf("expected mark 'rect', got %v", spec["mark"])
	}

	encoding, ok := spec["encoding"].(map[string]interface{})
	if !ok {
		t.Fatal("encoding not found or not a map")
	}

	colorEnc, ok := encoding["color"].(map[string]interface{})
	if !ok {
		t.Fatal("color encoding not found or not a map")
	}

	if colorEnc["aggregate"] != "count" {
		t.Errorf("expected color aggregate 'count', got %v", colorEnc["aggregate"])
	}
	if colorEnc["type"] != "quantitative" {
		t.Errorf("expected color type 'quantitative', got %v", colorEnc["type"])
	}

	xEnc, ok := encoding["x"].(map[string]interface{})
	if !ok {
		t.Fatal("x encoding not found or not a map")
	}
	if xEnc["field"] != "date" {
		t.Errorf("expected x field 'date', got %v", xEnc["field"])
	}

	yEnc, ok := encoding["y"].(map[string]interface{})
	if !ok {
		t.Fatal("y encoding not found or not a map")
	}
	if yEnc["field"] != "hour" {
		t.Errorf("expected y field 'hour', got %v", yEnc["field"])
	}
}

func TestHeatmapWithCustomAggregate(t *testing.T) {
	tbl := createTestTable()

	config := ChartConfig{
		Type:           Heatmap,
		XField:         "category",
		YField:         "hour",
		ColorAggregate: "sum",
	}

	specStr, err := GenerateSpec(tbl, config)
	if err != nil {
		t.Fatalf("GenerateSpec failed: %v", err)
	}

	var spec map[string]interface{}
	err = json.Unmarshal([]byte(specStr), &spec)
	if err != nil {
		t.Fatalf("spec is not valid JSON: %v", err)
	}

	encoding := spec["encoding"].(map[string]interface{})
	colorEnc := encoding["color"].(map[string]interface{})

	if colorEnc["aggregate"] != "sum" {
		t.Errorf("expected color aggregate 'sum', got %v", colorEnc["aggregate"])
	}
}

func TestHistogramSpec(t *testing.T) {
	tbl := createTestTable()

	config := ChartConfig{
		Type:   Histogram,
		XField: "amount",
		Title:  "Test Histogram",
	}

	specStr, err := GenerateSpec(tbl, config)
	if err != nil {
		t.Fatalf("GenerateSpec failed: %v", err)
	}

	var spec map[string]interface{}
	err = json.Unmarshal([]byte(specStr), &spec)
	if err != nil {
		t.Fatalf("spec is not valid JSON: %v", err)
	}

	if spec["mark"] != "bar" {
		t.Errorf("expected mark 'bar', got %v", spec["mark"])
	}

	encoding, ok := spec["encoding"].(map[string]interface{})
	if !ok {
		t.Fatal("encoding not found or not a map")
	}

	xEnc, ok := encoding["x"].(map[string]interface{})
	if !ok {
		t.Fatal("x encoding not found or not a map")
	}

	bin, ok := xEnc["bin"].(map[string]interface{})
	if !ok {
		t.Fatal("bin transform not found or not a map")
	}

	if bin["maxbins"] != float64(10) {
		t.Errorf("expected default maxbins 10, got %v", bin["maxbins"])
	}
	if xEnc["field"] != "amount" {
		t.Errorf("expected x field 'amount', got %v", xEnc["field"])
	}
	if xEnc["type"] != "quantitative" {
		t.Errorf("expected x type 'quantitative', got %v", xEnc["type"])
	}

	yEnc, ok := encoding["y"].(map[string]interface{})
	if !ok {
		t.Fatal("y encoding not found or not a map")
	}

	if yEnc["aggregate"] != "count" {
		t.Errorf("expected y aggregate 'count', got %v", yEnc["aggregate"])
	}
	if yEnc["type"] != "quantitative" {
		t.Errorf("expected y type 'quantitative', got %v", yEnc["type"])
	}
}

func TestHistogramCustomBinCount(t *testing.T) {
	tbl := createTestTable()

	config := ChartConfig{
		Type:     Histogram,
		XField:   "amount",
		BinCount: 20,
	}

	specStr, err := GenerateSpec(tbl, config)
	if err != nil {
		t.Fatalf("GenerateSpec failed: %v", err)
	}

	var spec map[string]interface{}
	err = json.Unmarshal([]byte(specStr), &spec)
	if err != nil {
		t.Fatalf("spec is not valid JSON: %v", err)
	}

	encoding := spec["encoding"].(map[string]interface{})
	xEnc := encoding["x"].(map[string]interface{})
	bin := xEnc["bin"].(map[string]interface{})

	if bin["maxbins"] != float64(20) {
		t.Errorf("expected maxbins 20, got %v", bin["maxbins"])
	}
}

func TestAllChartTypesGenerateValidJSON(t *testing.T) {
	tbl := createTestTable()

	chartTypes := []struct {
		name     string
		chartType ChartType
		xField   string
		yField   string
	}{
		{"Bar", BarChart, "category", "value"},
		{"Line", LineChart, "date", "value"},
		{"Scatter", ScatterChart, "amount", "value"},
		{"BoxPlot", BoxPlot, "category", "value"},
		{"Heatmap", Heatmap, "date", "hour"},
		{"Histogram", Histogram, "amount", ""},
	}

	for _, ct := range chartTypes {
		t.Run(ct.name, func(t *testing.T) {
			config := ChartConfig{
				Type:   ct.chartType,
				XField: ct.xField,
				YField: ct.yField,
			}

			specStr, err := GenerateSpec(tbl, config)
			if err != nil {
				t.Fatalf("GenerateSpec failed for %s: %v", ct.name, err)
			}

			var spec map[string]interface{}
			err = json.Unmarshal([]byte(specStr), &spec)
			if err != nil {
				t.Fatalf("%s spec is not valid JSON: %v", ct.name, err)
			}

			if spec["mark"] == nil {
				t.Errorf("%s spec missing 'mark' field", ct.name)
			}
			if spec["encoding"] == nil {
				t.Errorf("%s spec missing 'encoding' field", ct.name)
			}
			if spec["$schema"] == nil {
				t.Errorf("%s spec missing '$schema' field", ct.name)
			}
		})
	}
}

func TestGenerateBrushSpecSkipsNewTypes(t *testing.T) {
	tbl := createTestTable()

	heatmapConfig := ChartConfig{
		Type:   Heatmap,
		XField: "date",
		YField: "hour",
	}

	specStr, err := GenerateBrushSpec(tbl, heatmapConfig)
	if err != nil {
		t.Fatalf("GenerateBrushSpec failed: %v", err)
	}

	var spec map[string]interface{}
	err = json.Unmarshal([]byte(specStr), &spec)
	if err != nil {
		t.Fatalf("spec is not valid JSON: %v", err)
	}

	if _, ok := spec["selection"]; ok {
		t.Error("expected no selection field for Heatmap")
	}

	histConfig := ChartConfig{
		Type:   Histogram,
		XField: "amount",
	}

	specStr, err = GenerateBrushSpec(tbl, histConfig)
	if err != nil {
		t.Fatalf("GenerateBrushSpec failed: %v", err)
	}

	err = json.Unmarshal([]byte(specStr), &spec)
	if err != nil {
		t.Fatalf("spec is not valid JSON: %v", err)
	}

	if _, ok := spec["selection"]; ok {
		t.Error("expected no selection field for Histogram")
	}
}

func TestGenerateMultiSeriesSpecHeatmapSkip(t *testing.T) {
	tbl := createTestTable()

	config := ChartConfig{
		Type:   Heatmap,
		XField: "date",
		YField: "hour",
	}

	specStr, err := GenerateMultiSeriesSpec(tbl, config, "category")
	if err != nil {
		t.Fatalf("GenerateMultiSeriesSpec failed: %v", err)
	}

	var spec map[string]interface{}
	err = json.Unmarshal([]byte(specStr), &spec)
	if err != nil {
		t.Fatalf("spec is not valid JSON: %v", err)
	}

	encoding := spec["encoding"].(map[string]interface{})
	colorEnc := encoding["color"].(map[string]interface{})

	if colorEnc["aggregate"] != "count" {
		t.Errorf("expected heatmap color aggregate to remain 'count', got %v", colorEnc["aggregate"])
	}
	if colorEnc["field"] != nil {
		t.Errorf("expected heatmap color field to be nil, got %v", colorEnc["field"])
	}
}

func TestMarkType(t *testing.T) {
	tests := []struct {
		chartType ChartType
		expected  string
	}{
		{BarChart, "bar"},
		{LineChart, "line"},
		{ScatterChart, "point"},
		{BoxPlot, "boxplot"},
		{Heatmap, "rect"},
		{Histogram, "bar"},
	}

	for _, tt := range tests {
		result := markType(tt.chartType)
		if result != tt.expected {
			t.Errorf("markType(%v) = %s, expected %s", tt.chartType, result, tt.expected)
		}
	}
}
