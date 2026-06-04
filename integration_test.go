package main

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/dataexplorer/anomaly"
	"github.com/dataexplorer/parser"
	"github.com/dataexplorer/query"
	"github.com/dataexplorer/store"
)

func testDataPath(filename string) string {
	return filepath.Join("testdata", filename)
}

func TestIntegration_IrisFullPipeline(t *testing.T) {
	t.Log("=== Integration Test: Iris Dataset Full Pipeline ===")

	// Step 1: Load and parse CSV file
	t.Log("Step 1: Loading iris.csv...")
	data, err := os.ReadFile(testDataPath("iris.csv"))
	if err != nil {
		t.Fatalf("Failed to read iris.csv: %v", err)
	}

	p := parser.NewParser()
	result := p.Parse(data, "csv", "iris")
	if result.Table == nil {
		t.Fatal("Failed to parse iris.csv")
	}

	table := result.Table
	t.Logf("  Loaded %d rows, %d columns", table.RowCount, len(table.Columns))

	if table.RowCount != 30 {
		t.Errorf("Expected 30 rows, got %d", table.RowCount)
	}
	if len(table.Columns) != 5 {
		t.Errorf("Expected 5 columns, got %d", len(table.Columns))
	}

	// Step 2: Verify type inference
	t.Log("Step 2: Verifying type inference...")
	expectedTypes := map[string]store.DataType{
		"sepal_length": store.TypeFloat,
		"sepal_width":  store.TypeFloat,
		"petal_length": store.TypeFloat,
		"petal_width":  store.TypeFloat,
		"species":      store.TypeString,
	}

	for colName, expectedType := range expectedTypes {
		col := table.GetColumn(colName)
		if col == nil {
			t.Errorf("Column %s not found", colName)
			continue
		}
		if col.DataType != expectedType {
			t.Errorf("Column %s: expected %v, got %v", colName, expectedType, col.DataType)
		} else {
			t.Logf("  %s: %v ✓", colName, col.DataType)
		}
	}

	// Step 3: Build bitmap indexes
	t.Log("Step 3: Building bitmap indexes...")
	im := store.NewIndexManager()
	for _, col := range table.Columns {
		if col.DataType == store.TypeString || col.DataType == store.TypeBool {
			im.BuildIndex(col)
			t.Logf("  Index built for: %s", col.Name)
		}
	}

	if !im.HasIndex("species") {
		t.Error("species column should have index")
	}

	// Step 4: Execute query with filter
	t.Log("Step 4: Executing filter query...")
	executor := query.NewExecutor(im)

	stmt, err := query.NewParser().Parse(`
		SELECT * FROM iris 
		WHERE sepal_length > 5.0
	`)
	if err != nil {
		t.Fatalf("Parse error: %v", err)
	}

	queryResult, err := executor.Execute(table, stmt)
	if err != nil {
		t.Fatalf("Execute error: %v", err)
	}

	t.Logf("  Filter result: %d rows (sepal_length > 5.0)", queryResult.RowCount)

	if queryResult.RowCount == 0 {
		t.Error("Query returned no results")
	}

	// Step 5: Test GROUP BY aggregation
	t.Log("Step 5: Testing GROUP BY aggregation...")
	stmt2, err := query.NewParser().Parse(`
		SELECT species FROM iris 
		GROUP BY species AVG(sepal_width)
	`)
	if err != nil {
		t.Fatalf("Parse error: %v", err)
	}

	aggResult, err := executor.Execute(table, stmt2)
	if err != nil {
		t.Fatalf("Execute error: %v", err)
	}

	t.Logf("  Aggregation result: %d groups", aggResult.RowCount)
	t.Logf("  Columns: %v", aggResult.ColumnNames())

	avgCol := aggResult.GetColumn("AVG(sepal_width)")
	if avgCol == nil {
		t.Errorf("AVG(sepal_width) column not found")
	} else {
		for i := 0; i < avgCol.Length; i++ {
			t.Logf("    Group %d avg: %.2f", i, avgCol.FloatData[i])
		}
	}

	// Step 6: Detect anomalies in sepal_length
	t.Log("Step 6: Running anomaly detection (IQR method)...")
	anomalyConfig := anomaly.AnomalyConfig{
		Column:    "sepal_length",
		Method:    anomaly.MethodIQR,
		Threshold: 1.5,
	}

	anomalyResult, err := anomaly.Detect(table, anomalyConfig)
	if err != nil {
		t.Fatalf("Anomaly detection error: %v", err)
	}

	t.Logf("  Bounds: [%.2f, %.2f]", anomalyResult.LowerBound, anomalyResult.UpperBound)
	t.Logf("  Anomalies: %d/%d", anomalyResult.AnomalyCount, anomalyResult.TotalChecked)

	// Step 7: Test table operations (sort, limit, filter)
	t.Log("Step 7: Testing table operations...")

	// Sort
	sortedTable := store.NewTable("sorted")
	sortedTable.RowCount = table.RowCount
	for _, col := range table.Columns {
		newCol := sortedTable.AddColumn(col.Name, col.DataType)
		switch col.DataType {
		case store.TypeFloat:
			copy(newCol.FloatData, col.FloatData)
		case store.TypeString:
			copy(newCol.StrData, col.StrData)
		}
		copy(newCol.NullMap, col.NullMap)
	}
	sortedTable.Sort("sepal_length", false)
	sortedCol := sortedTable.GetColumn("sepal_length")
	if sortedCol == nil {
		t.Error("sorted column not found")
	} else {
		if sortedCol.FloatData[0] < sortedCol.FloatData[sortedCol.Length-1] {
			t.Error("DESC sort should have largest value first")
		}
		t.Logf("  Sort DESC: first=%.2f, last=%.2f", sortedCol.FloatData[0], sortedCol.FloatData[sortedCol.Length-1])
	}

	// Limit
	limited := table.Limit(10)
	if limited.RowCount != 10 {
		t.Errorf("Limit(10): expected 10 rows, got %d", limited.RowCount)
	} else {
		t.Log("  Limit(10): 10 rows ✓")
	}

	// Filter
	mask := make([]bool, table.RowCount)
	speciesCol := table.GetColumn("species")
	for i := 0; i < table.RowCount; i++ {
		mask[i] = speciesCol.GetString(i) == "setosa"
	}
	setosaTable := table.Filter(mask)
	if setosaTable.RowCount != 10 {
		t.Errorf("Filter setosa: expected 10 rows, got %d", setosaTable.RowCount)
	} else {
		t.Log("  Filter(setosa): 10 rows ✓")
	}

	// Step 8: Export tests
	t.Log("Step 8: Testing data serialization...")
	jsonData := table.ToJSONString(0, 5)
	if jsonData == "" {
		t.Error("JSON export is empty")
	} else {
		t.Log("  JSON serialization: OK")
	}

	schemaJson := table.SchemaJSON()
	if schemaJson == "" {
		t.Error("Schema JSON is empty")
	} else {
		t.Log("  Schema JSON: OK")
	}

	t.Log("=== Integration Test Complete ===")
	t.Log("All pipeline stages executed successfully!")
}

func TestIntegration_TitanicPipeline(t *testing.T) {
	t.Log("=== Integration Test: Titanic Dataset ===")

	data, err := os.ReadFile(testDataPath("titanic.csv"))
	if err != nil {
		t.Fatalf("Failed to read titanic.csv: %v", err)
	}

	p := parser.NewParser()
	result := p.Parse(data, "csv", "titanic")
	if result.Table == nil {
		t.Fatal("Failed to parse titanic.csv")
	}

	table := result.Table
	t.Logf("Loaded %d rows, %d columns", table.RowCount, len(table.Columns))

	// Test null handling
	ageCol := table.GetColumn("Age")
	nullCount := 0
	for i := 0; i < ageCol.Length; i++ {
		if ageCol.IsNull(i) {
			nullCount++
		}
	}
	t.Logf("Null values in Age: %d", nullCount)

	// Test query with IS NULL filter
	im := store.NewIndexManager()
	executor := query.NewExecutor(im)

	stmt, err := query.NewParser().Parse("SELECT * FROM titanic WHERE Age IS NULL")
	if err != nil {
		t.Fatalf("Parse error: %v", err)
	}

	queryResult, err := executor.Execute(table, stmt)
	if err != nil {
		t.Fatalf("Execute error: %v", err)
	}

	t.Logf("NULL Age query result: %d rows", queryResult.RowCount)

	// Test Survived aggregation
	stmt2, err := query.NewParser().Parse("SELECT Pclass FROM titanic GROUP BY Pclass COUNT(Survived)")
	if err != nil {
		t.Fatalf("Parse error: %v", err)
	}

	aggResult, err := executor.Execute(table, stmt2)
	if err != nil {
		t.Fatalf("Execute error: %v", err)
	}

	t.Logf("Pclass aggregation result: %d groups", aggResult.RowCount)

	t.Log("=== Titanic Test Complete ===")
}
