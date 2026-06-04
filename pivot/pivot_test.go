package pivot

import (
	"testing"

	"github.com/dataexplorer/store"
)

func TestPivotTable_CountDistinct(t *testing.T) {
	tbl := store.NewTable("test")
	tbl.RowCount = 8

	region := tbl.AddColumn("region", store.TypeString)
	region.StrData[0] = "East"
	region.StrData[1] = "East"
	region.StrData[2] = "East"
	region.StrData[3] = "East"
	region.StrData[4] = "West"
	region.StrData[5] = "West"
	region.StrData[6] = "West"
	region.StrData[7] = "West"

	product := tbl.AddColumn("product", store.TypeString)
	product.StrData[0] = "A"
	product.StrData[1] = "A"
	product.StrData[2] = "B"
	product.StrData[3] = "B"
	product.StrData[4] = "A"
	product.StrData[5] = "A"
	product.StrData[6] = "B"
	product.StrData[7] = "B"

	customer := tbl.AddColumn("customer_id", store.TypeFloat)
	customer.FloatData[0] = 101
	customer.FloatData[1] = 102
	customer.FloatData[2] = 101
	customer.FloatData[3] = 103
	customer.FloatData[4] = 201
	customer.FloatData[5] = 201
	customer.FloatData[6] = 202
	customer.FloatData[7] = 202

	config := PivotConfig{
		RowDims:    []string{"region"},
		ColDims:    []string{"product"},
		ValueField: "customer_id",
		AggMethod:  AggCountDistinct,
	}

	result, err := NewPivotTable(tbl, config)
	if err != nil {
		t.Fatalf("pivot table error: %v", err)
	}

	if len(result.Rows) != 2 {
		t.Errorf("expected 2 rows, got %d", len(result.Rows))
	}
	if len(result.Cols) != 2 {
		t.Errorf("expected 2 cols, got %d", len(result.Cols))
	}

	eastKey := "East"
	westKey := "West"
	prodAKey := "A"
	prodBKey := "B"

	eastA := result.Cells[eastKey][prodAKey]
	if eastA.Value != 2.0 {
		t.Errorf("East/A count distinct: expected 2, got %v", eastA.Value)
	}

	eastB := result.Cells[eastKey][prodBKey]
	if eastB.Value != 2.0 {
		t.Errorf("East/B count distinct: expected 2, got %v", eastB.Value)
	}

	westA := result.Cells[westKey][prodAKey]
	if westA.Value != 1.0 {
		t.Errorf("West/A count distinct: expected 1, got %v", westA.Value)
	}

	westB := result.Cells[westKey][prodBKey]
	if westB.Value != 1.0 {
		t.Errorf("West/B count distinct: expected 1, got %v", westB.Value)
	}

	if result.GrandTotal.Value != 5.0 {
		t.Errorf("Grand total count distinct: expected 5, got %v", result.GrandTotal.Value)
	}
}

func TestPivotTable_PercentileP50(t *testing.T) {
	tbl := store.NewTable("test")
	tbl.RowCount = 6

	region := tbl.AddColumn("region", store.TypeString)
	region.StrData[0] = "East"
	region.StrData[1] = "East"
	region.StrData[2] = "East"
	region.StrData[3] = "West"
	region.StrData[4] = "West"
	region.StrData[5] = "West"

	value := tbl.AddColumn("value", store.TypeFloat)
	value.FloatData[0] = 10
	value.FloatData[1] = 20
	value.FloatData[2] = 30
	value.FloatData[3] = 100
	value.FloatData[4] = 200
	value.FloatData[5] = 300

	config := PivotConfig{
		RowDims:        []string{"region"},
		ColDims:        []string{},
		ValueField:     "value",
		AggMethod:      AggPercentile,
		PercentileValue: 50.0,
	}

	result, err := NewPivotTable(tbl, config)
	if err != nil {
		t.Fatalf("pivot table error: %v", err)
	}

	eastKey := "East"
	westKey := "West"

	eastCell := result.Cells[eastKey][""]
	if eastCell.Value != 20.0 {
		t.Errorf("East P50: expected 20, got %v", eastCell.Value)
	}

	westCell := result.Cells[westKey][""]
	if westCell.Value != 200.0 {
		t.Errorf("West P50: expected 200, got %v", westCell.Value)
	}
}

func TestPivotTable_PercentileP90(t *testing.T) {
	tbl := store.NewTable("test")
	tbl.RowCount = 10

	category := tbl.AddColumn("category", store.TypeString)
	for i := 0; i < 10; i++ {
		category.StrData[i] = "All"
	}

	value := tbl.AddColumn("value", store.TypeFloat)
	for i := 0; i < 10; i++ {
		value.FloatData[i] = float64(i + 1)
	}

	config := PivotConfig{
		RowDims:        []string{"category"},
		ColDims:        []string{},
		ValueField:     "value",
		AggMethod:      AggPercentile,
		PercentileValue: 90.0,
	}

	result, err := NewPivotTable(tbl, config)
	if err != nil {
		t.Fatalf("pivot table error: %v", err)
	}

	cell := result.Cells["All"][""]
	expected := 9.0
	if cell.Value != expected {
		t.Errorf("P90: expected %v, got %v", expected, cell.Value)
	}
}

func TestPivotTable_PercentileWithColumns(t *testing.T) {
	tbl := store.NewTable("test")
	tbl.RowCount = 8

	region := tbl.AddColumn("region", store.TypeString)
	region.StrData[0] = "East"
	region.StrData[1] = "East"
	region.StrData[2] = "East"
	region.StrData[3] = "East"
	region.StrData[4] = "West"
	region.StrData[5] = "West"
	region.StrData[6] = "West"
	region.StrData[7] = "West"

	product := tbl.AddColumn("product", store.TypeString)
	product.StrData[0] = "A"
	product.StrData[1] = "A"
	product.StrData[2] = "B"
	product.StrData[3] = "B"
	product.StrData[4] = "A"
	product.StrData[5] = "A"
	product.StrData[6] = "B"
	product.StrData[7] = "B"

	value := tbl.AddColumn("value", store.TypeFloat)
	value.FloatData[0] = 10
	value.FloatData[1] = 30
	value.FloatData[2] = 50
	value.FloatData[3] = 70
	value.FloatData[4] = 100
	value.FloatData[5] = 300
	value.FloatData[6] = 500
	value.FloatData[7] = 700

	config := PivotConfig{
		RowDims:        []string{"region"},
		ColDims:        []string{"product"},
		ValueField:     "value",
		AggMethod:      AggPercentile,
		PercentileValue: 50.0,
	}

	result, err := NewPivotTable(tbl, config)
	if err != nil {
		t.Fatalf("pivot table error: %v", err)
	}

	eastKey := "East"
	westKey := "West"
	prodAKey := "A"
	prodBKey := "B"

	eastA := result.Cells[eastKey][prodAKey]
	if eastA.Value != 10.0 {
		t.Errorf("East/A P50: expected 10, got %v", eastA.Value)
	}

	eastB := result.Cells[eastKey][prodBKey]
	if eastB.Value != 50.0 {
		t.Errorf("East/B P50: expected 50, got %v", eastB.Value)
	}

	westA := result.Cells[westKey][prodAKey]
	if westA.Value != 100.0 {
		t.Errorf("West/A P50: expected 100, got %v", westA.Value)
	}

	westB := result.Cells[westKey][prodBKey]
	if westB.Value != 500.0 {
		t.Errorf("West/B P50: expected 500, got %v", westB.Value)
	}
}

func TestPivotTable_BackwardCompatibility(t *testing.T) {
	tbl := store.NewTable("test")
	tbl.RowCount = 4

	cat := tbl.AddColumn("category", store.TypeString)
	cat.StrData[0] = "A"
	cat.StrData[1] = "A"
	cat.StrData[2] = "B"
	cat.StrData[3] = "B"

	val := tbl.AddColumn("value", store.TypeFloat)
	val.FloatData[0] = 10
	val.FloatData[1] = 20
	val.FloatData[2] = 30
	val.FloatData[3] = 40

	config := PivotConfig{
		RowDims:    []string{"category"},
		ColDims:    []string{},
		ValueField: "value",
		AggMethod:  AggSum,
	}

	result, err := NewPivotTable(tbl, config)
	if err != nil {
		t.Fatalf("pivot table error: %v", err)
	}

	cellA := result.Cells["A"][""]
	if cellA.Value != 30.0 {
		t.Errorf("Group A sum: expected 30, got %v", cellA.Value)
	}

	cellB := result.Cells["B"][""]
	if cellB.Value != 70.0 {
		t.Errorf("Group B sum: expected 70, got %v", cellB.Value)
	}
}
