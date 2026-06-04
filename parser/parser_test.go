package parser

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/dataexplorer/store"
)

func testDataPath(filename string) string {
	return filepath.Join("..", "testdata", filename)
}

func TestParseCSV_Iris(t *testing.T) {
	f, err := os.Open(testDataPath("iris.csv"))
	if err != nil {
		t.Fatalf("failed to open test file: %v", err)
	}
	defer f.Close()

	p := NewParser()
	result := p.ParseCSV(f, "iris")

	if result.Table == nil {
		t.Fatal("expected table, got nil")
	}

	if result.Table.RowCount != 30 {
		t.Errorf("expected 30 rows, got %d", result.Table.RowCount)
	}

	if len(result.Table.Columns) != 5 {
		t.Errorf("expected 5 columns, got %d", len(result.Table.Columns))
	}

	colNames := result.Table.ColumnNames()
	expected := []string{"sepal_length", "sepal_width", "petal_length", "petal_width", "species"}
	for i, name := range expected {
		if colNames[i] != name {
			t.Errorf("column %d: expected %s, got %s", i, name, colNames[i])
		}
	}

	col := result.Table.GetColumn("species")
	if col == nil {
		t.Fatal("species column not found")
	}
	if col.DataType != store.TypeString {
		t.Errorf("species column: expected string type, got %v", col.DataType)
	}

	numCol := result.Table.GetColumn("sepal_length")
	if numCol == nil {
		t.Fatal("sepal_length column not found")
	}
	if numCol.DataType != store.TypeFloat {
		t.Errorf("sepal_length: expected float type, got %v", numCol.DataType)
	}
}

func TestParseCSV_Titanic(t *testing.T) {
	f, err := os.Open(testDataPath("titanic.csv"))
	if err != nil {
		t.Fatalf("failed to open test file: %v", err)
	}
	defer f.Close()

	p := NewParser()
	result := p.ParseCSV(f, "titanic")

	if result.Table == nil {
		t.Fatal("expected table, got nil")
	}

	if result.Table.RowCount != 10 {
		t.Errorf("expected 10 rows, got %d", result.Table.RowCount)
	}

	ageCol := result.Table.GetColumn("Age")
	if ageCol == nil {
		t.Fatal("Age column not found")
	}

	nullCount := 0
	for i := 0; i < ageCol.Length; i++ {
		if ageCol.IsNull(i) {
			nullCount++
		}
	}
	if nullCount != 1 {
		t.Errorf("expected 1 null in Age, got %d", nullCount)
	}

	if !ageCol.IsNull(5) {
		t.Error("row 5 (index 5) should have null Age")
	}
}

func TestParseCSV_QuotesAndNewlines(t *testing.T) {
	f, err := os.Open(testDataPath("csv_quotes.csv"))
	if err != nil {
		t.Fatalf("failed to open test file: %v", err)
	}
	defer f.Close()

	p := NewParser()
	result := p.ParseCSV(f, "quotes")

	if result.Table == nil {
		t.Fatal("expected table, got nil")
	}

	if result.Table.RowCount != 5 {
		t.Errorf("expected 5 rows, got %d", result.Table.RowCount)
	}

	descCol := result.Table.GetColumn("description")
	if descCol == nil {
		t.Fatal("description column not found")
	}

	if descCol.DataType != store.TypeString {
		t.Errorf("expected string type, got %v", descCol.DataType)
	}

	row1Val := descCol.GetString(0)
	if !strings.Contains(row1Val, "comma") {
		t.Errorf("expected comma in description, got: %s", row1Val)
	}

	row2Val := descCol.GetString(1)
	if !strings.Contains(row2Val, "quotes") {
		t.Errorf("expected quotes in row 2, got: %s", row2Val)
	}

	row3Val := descCol.GetString(2)
	if !strings.Contains(row3Val, "Line 1") {
		t.Errorf("expected newline in row 3, got: %q", row3Val)
	}
}

func TestParseCSV_MismatchedColumns(t *testing.T) {
	f, err := os.Open(testDataPath("mismatched_cols.csv"))
	if err != nil {
		t.Fatalf("failed to open test file: %v", err)
	}
	defer f.Close()

	p := NewParser()
	result := p.ParseCSV(f, "mismatched")

	if result.Table == nil {
		t.Fatal("expected table, got nil")
	}

	if result.Table.RowCount != 5 {
		t.Errorf("expected 5 rows, got %d", result.Table.RowCount)
	}

	colC := result.Table.GetColumn("c")
	if colC == nil {
		t.Fatal("column c not found")
	}

	if !colC.IsNull(1) {
		t.Error("row 1, column c should be null (only 2 cols)")
	}

	if !colC.IsNull(3) {
		t.Error("row 3, column c should be null (only 1 col)")
	}
}

func TestParseCSV_EmptyData(t *testing.T) {
	f, err := os.Open(testDataPath("empty_data.csv"))
	if err != nil {
		t.Fatalf("failed to open test file: %v", err)
	}
	defer f.Close()

	p := NewParser()
	result := p.ParseCSV(f, "empty")

	if result.Table == nil {
		t.Fatal("expected table, got nil")
	}

	if result.Table.RowCount != 0 {
		t.Errorf("expected 0 rows, got %d", result.Table.RowCount)
	}

	if len(result.Table.Columns) != 2 {
		t.Errorf("expected 2 columns, got %d", len(result.Table.Columns))
	}
}

func TestParseCSV_MixedTypes_Dirty(t *testing.T) {
	f, err := os.Open(testDataPath("mixed_types.csv"))
	if err != nil {
		t.Fatalf("failed to open test file: %v", err)
	}
	defer f.Close()

	p := NewParser()
	result := p.ParseCSV(f, "mixed")

	if result.Table == nil {
		t.Fatal("expected table, got nil")
	}

	ageCol := result.Table.GetColumn("age")
	if ageCol == nil {
		t.Fatal("age column not found")
	}

	dirtyCount := 0
	for i := 0; i < ageCol.Length; i++ {
		if ageCol.IsDirty(i) {
			dirtyCount++
		}
	}

	if dirtyCount < 2 {
		t.Errorf("expected at least 2 dirty rows, got %d", dirtyCount)
	}

	if !ageCol.IsNull(4) {
		t.Error("row 4 (Eve, not_a_number) should be null")
	}
	if !ageCol.IsDirty(4) {
		t.Error("row 4 should be marked dirty")
	}
	if !ageCol.IsNull(6) {
		t.Error("row 6 (Grace, thirty) should be null")
	}
	if !ageCol.IsDirty(6) {
		t.Error("row 6 should be marked dirty")
	}
}
