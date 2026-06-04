package anomaly

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/dataexplorer/parser"
	"github.com/dataexplorer/store"
)

func testDataPath(filename string) string {
	return filepath.Join("..", "testdata", filename)
}

func TestDetectIQR_Simple(t *testing.T) {
	values := []float64{10, 12, 14, 15, 16, 18, 19, 20, 21, 22, 24, 25, 26, 28, 30}

	table := store.NewTable("test")
	table.RowCount = len(values)
	col := table.AddColumn("value", store.TypeFloat)
	for i, v := range values {
		col.FloatData[i] = v
	}

	config := AnomalyConfig{
		Column:    "value",
		Method:    MethodIQR,
		Threshold: 1.5,
	}

	result, err := DetectIQR(table, config)
	if err != nil {
		t.Fatalf("detect error: %v", err)
	}

	if result.Column != "value" {
		t.Errorf("expected column value, got %s", result.Column)
	}
	if result.TotalChecked != len(values) {
		t.Errorf("expected checked %d, got %d", len(values), result.TotalChecked)
	}

	t.Logf("IQR bounds: [%f, %f]", result.LowerBound, result.UpperBound)
	t.Logf("Anomalies found: %d", result.AnomalyCount)
}

func TestDetectIQR_WithOutlier(t *testing.T) {
	values := []float64{10, 12, 14, 15, 16, 18, 19, 20, 21, 22, 24, 25, 26, 28, 30, 100}

	table := store.NewTable("test")
	table.RowCount = len(values)
	col := table.AddColumn("value", store.TypeFloat)
	for i, v := range values {
		col.FloatData[i] = v
	}

	config := AnomalyConfig{
		Column:    "value",
		Method:    MethodIQR,
		Threshold: 1.5,
	}

	result, err := DetectIQR(table, config)
	if err != nil {
		t.Fatalf("detect error: %v", err)
	}

	if result.AnomalyCount != 1 {
		t.Errorf("expected 1 anomaly (100), got %d", result.AnomalyCount)
	}

	if result.AnomalyCount > 0 {
		if result.AnomalyValues[0] != 100 {
			t.Errorf("expected anomaly value 100, got %v", result.AnomalyValues[0])
		}
		if result.AnomalyIndices[0] != 15 {
			t.Errorf("expected anomaly at index 15, got %d", result.AnomalyIndices[0])
		}
	}
}

func TestDetectIQR_NoOutliers(t *testing.T) {
	values := []float64{10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20}

	table := store.NewTable("test")
	table.RowCount = len(values)
	col := table.AddColumn("value", store.TypeFloat)
	for i, v := range values {
		col.FloatData[i] = v
	}

	config := AnomalyConfig{
		Column:    "value",
		Method:    MethodIQR,
		Threshold: 1.5,
	}

	result, err := DetectIQR(table, config)
	if err != nil {
		t.Fatalf("detect error: %v", err)
	}

	if result.AnomalyCount != 0 {
		t.Errorf("expected 0 anomalies, got %d", result.AnomalyCount)
	}
}

func TestDetectZScore_Simple(t *testing.T) {
	values := []float64{10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20}

	table := store.NewTable("test")
	table.RowCount = len(values)
	col := table.AddColumn("value", store.TypeFloat)
	for i, v := range values {
		col.FloatData[i] = v
	}

	config := AnomalyConfig{
		Column:    "value",
		Method:    MethodZScore,
		Threshold: 3.0,
	}

	result, err := DetectZScore(table, config)
	if err != nil {
		t.Fatalf("detect error: %v", err)
	}

	mean := 15.0
	stddev := 3.3166
	expectedLower := mean - 3.0*stddev
	expectedUpper := mean + 3.0*stddev

	if result.AnomalyCount != 0 {
		t.Errorf("expected 0 anomalies, got %d", result.AnomalyCount)
	}

	t.Logf("Z-Score bounds: [%f, %f]", result.LowerBound, result.UpperBound)
	t.Logf("Expected bounds: [%f, %f]", expectedLower, expectedUpper)
}

func TestDetectZScore_WithOutlier(t *testing.T) {
	values := []float64{10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 50}

	table := store.NewTable("test")
	table.RowCount = len(values)
	col := table.AddColumn("value", store.TypeFloat)
	for i, v := range values {
		col.FloatData[i] = v
	}

	config := AnomalyConfig{
		Column:    "value",
		Method:    MethodZScore,
		Threshold: 2.0,
	}

	result, err := DetectZScore(table, config)
	if err != nil {
		t.Fatalf("detect error: %v", err)
	}

	t.Logf("Z-Score bounds: [%f, %f]", result.LowerBound, result.UpperBound)
	t.Logf("Anomalies found: %d", result.AnomalyCount)

	if result.AnomalyCount < 1 {
		t.Error("expected at least 1 anomaly (50)")
	}
}

func TestDetectZScore_Threshold(t *testing.T) {
	values := []float64{10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 100}

	table := store.NewTable("test")
	table.RowCount = len(values)
	col := table.AddColumn("value", store.TypeFloat)
	for i, v := range values {
		col.FloatData[i] = v
	}

	configStrict := AnomalyConfig{
		Column:    "value",
		Method:    MethodZScore,
		Threshold: 1.0,
	}
	resultStrict, _ := DetectZScore(table, configStrict)

	configLoose := AnomalyConfig{
		Column:    "value",
		Method:    MethodZScore,
		Threshold: 5.0,
	}
	resultLoose, _ := DetectZScore(table, configLoose)

	if resultStrict.AnomalyCount <= resultLoose.AnomalyCount {
		t.Error("strict threshold (1.0) should find more anomalies than loose (5.0)")
	}

	t.Logf("Strict (1.0): %d anomalies", resultStrict.AnomalyCount)
	t.Logf("Loose (5.0): %d anomalies", resultLoose.AnomalyCount)
}

func TestDetect_Dispatch(t *testing.T) {
	table := store.NewTable("test")
	table.RowCount = 5
	col := table.AddColumn("value", store.TypeFloat)
	col.FloatData[0] = 1
	col.FloatData[1] = 2
	col.FloatData[2] = 3
	col.FloatData[3] = 4
	col.FloatData[4] = 100

	configIQR := AnomalyConfig{
		Column:    "value",
		Method:    MethodIQR,
		Threshold: 1.5,
	}
	resultIQR, err := Detect(table, configIQR)
	if err != nil {
		t.Fatalf("IQR dispatch error: %v", err)
	}
	if resultIQR.Method != MethodIQR {
		t.Error("expected MethodIQR")
	}

	configZ := AnomalyConfig{
		Column:    "value",
		Method:    MethodZScore,
		Threshold: 3.0,
	}
	resultZ, err := Detect(table, configZ)
	if err != nil {
		t.Fatalf("Z dispatch error: %v", err)
	}
	if resultZ.Method != MethodZScore {
		t.Error("expected MethodZScore")
	}
}

func TestDetect_InvalidColumn(t *testing.T) {
	table := store.NewTable("test")
	table.RowCount = 3
	table.AddColumn("x", store.TypeInt)

	config := AnomalyConfig{
		Column:    "nonexistent",
		Method:    MethodIQR,
		Threshold: 1.5,
	}

	_, err := Detect(table, config)
	if err == nil {
		t.Error("expected error for non-existent column")
	}
}

func TestDetect_NonNumericColumn(t *testing.T) {
	table := store.NewTable("test")
	table.RowCount = 3
	col := table.AddColumn("category", store.TypeString)
	col.SetValue(0, "A")
	col.SetValue(1, "B")
	col.SetValue(2, "C")

	config := AnomalyConfig{
		Column:    "category",
		Method:    MethodIQR,
		Threshold: 1.5,
	}

	_, err := Detect(table, config)
	if err == nil {
		t.Error("expected error for string column")
	}
}

func TestAnomalyResult_ToJSON(t *testing.T) {
	result := &AnomalyResult{
		Column:         "value",
		Method:         MethodIQR,
		Threshold:      1.5,
		AnomalyIndices: []int{5, 10},
		AnomalyValues:  []float64{100, 200},
		LowerBound:     0,
		UpperBound:     50,
		TotalChecked:   15,
		AnomalyCount:   2,
	}

	jsonStr := result.ToJSON()
	if jsonStr == "" {
		t.Error("json is empty")
	}
	t.Logf("JSON: %s", jsonStr)
}

func TestMarkAnomalies(t *testing.T) {
	values := []float64{10, 11, 12, 13, 14, 100, 15, 16, 17, 18}

	table := store.NewTable("test")
	table.RowCount = len(values)
	col := table.AddColumn("value", store.TypeFloat)
	for i, v := range values {
		col.FloatData[i] = v
	}

	config := AnomalyConfig{
		Column:    "value",
		Method:    MethodIQR,
		Threshold: 1.5,
	}

	result, _ := Detect(table, config)
	marked := MarkAnomalies(table, result)

	if marked.RowCount != result.AnomalyCount {
		t.Errorf("expected %d rows in marked table, got %d", result.AnomalyCount, marked.RowCount)
	}
}

func TestHighlightSpec(t *testing.T) {
	table := store.NewTable("test")
	table.RowCount = 5
	table.AddColumn("x", store.TypeInt)
	table.AddColumn("y", store.TypeFloat)

	result := &AnomalyResult{
		Column:         "y",
		Method:         MethodIQR,
		Threshold:      1.5,
		AnomalyIndices: []int{2},
		AnomalyValues:  []float64{100},
		AnomalyCount:   1,
		TotalChecked:   5,
	}

	spec := HighlightSpec(table, result, "scatter")
	if spec == "" {
		t.Error("highlight spec is empty")
	}
	t.Logf("Highlight spec length: %d chars", len(spec))
}

func TestDetectIQR_FromCSV(t *testing.T) {
	f, err := os.Open(testDataPath("anomaly_iqr.csv"))
	if err != nil {
		t.Fatalf("failed to open test file: %v", err)
	}
	defer f.Close()

	data, _ := os.ReadFile(testDataPath("anomaly_iqr.csv"))

	p := parser.NewParser()
	parseResult := p.Parse(data, "csv", "anomaly_test")

	if parseResult.Table == nil {
		t.Fatal("failed to parse anomaly CSV")
	}

	config := AnomalyConfig{
		Column:    "value",
		Method:    MethodIQR,
		Threshold: 1.5,
	}

	result, err := Detect(parseResult.Table, config)
	if err != nil {
		t.Fatalf("detect error: %v", err)
	}

	if result.AnomalyCount < 1 {
		t.Error("expected at least 1 anomaly (100)")
	}

	t.Logf("IQR bounds: [%f, %f]", result.LowerBound, result.UpperBound)
	t.Logf("Anomalies: %d/%d", result.AnomalyCount, result.TotalChecked)
}

func TestDetectIQR_DefaultThreshold(t *testing.T) {
	values := []float64{10, 12, 14, 15, 16, 100, 18, 19, 20}

	table := store.NewTable("test")
	table.RowCount = len(values)
	col := table.AddColumn("value", store.TypeFloat)
	for i, v := range values {
		col.FloatData[i] = v
	}

	config := AnomalyConfig{
		Column:    "value",
		Method:    MethodIQR,
		Threshold: 0,
	}

	result, err := DetectIQR(table, config)
	if err != nil {
		t.Fatalf("detect error: %v", err)
	}

	if result.AnomalyCount < 1 {
		t.Error("expected at least 1 anomaly with default threshold (1.5)")
	}
}

func TestDetectZScore_DefaultThreshold(t *testing.T) {
	values := []float64{
		10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
		10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
		10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
		100,
	}

	table := store.NewTable("test")
	table.RowCount = len(values)
	col := table.AddColumn("value", store.TypeFloat)
	for i, v := range values {
		col.FloatData[i] = v
	}

	config := AnomalyConfig{
		Column:    "value",
		Method:    MethodZScore,
		Threshold: 0,
	}

	result, err := DetectZScore(table, config)
	if err != nil {
		t.Fatalf("detect error: %v", err)
	}

	if result.AnomalyCount < 1 {
		t.Error("expected at least 1 anomaly with default threshold (3.0)")
	}
}

func TestDetectIQR_AllSameValues(t *testing.T) {
	values := []float64{5, 5, 5, 5, 5}

	table := store.NewTable("test")
	table.RowCount = len(values)
	col := table.AddColumn("value", store.TypeFloat)
	for i, v := range values {
		col.FloatData[i] = v
	}

	config := AnomalyConfig{
		Column:    "value",
		Method:    MethodIQR,
		Threshold: 1.5,
	}

	result, err := DetectIQR(table, config)
	if err != nil {
		t.Fatalf("detect error: %v", err)
	}

	if result.AnomalyCount != 0 {
		t.Errorf("expected 0 anomalies for all same values, got %d", result.AnomalyCount)
	}
}

func TestDetect_IntColumn(t *testing.T) {
	table := store.NewTable("test")
	table.RowCount = 5
	col := table.AddColumn("count", store.TypeInt)
	col.IntData[0] = 10
	col.IntData[1] = 12
	col.IntData[2] = 14
	col.IntData[3] = 16
	col.IntData[4] = 100

	config := AnomalyConfig{
		Column:    "count",
		Method:    MethodIQR,
		Threshold: 1.5,
	}

	result, err := Detect(table, config)
	if err != nil {
		t.Fatalf("detect error: %v", err)
	}

	if result.AnomalyCount < 1 {
		t.Error("expected at least 1 anomaly (100) in int column")
	}
}
