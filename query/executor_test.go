package query

import (
	"testing"

	"github.com/dataexplorer/store"
)

func makeTestTable() *store.Table {
	t := store.NewTable("test")
	t.RowCount = 5

	idCol := t.AddColumn("id", store.TypeInt)
	idCol.IntData[0] = 1
	idCol.IntData[1] = 2
	idCol.IntData[2] = 3
	idCol.IntData[3] = 4
	idCol.IntData[4] = 5

	catCol := t.AddColumn("category", store.TypeString)
	catCol.StrData[0] = "A"
	catCol.StrData[1] = "A"
	catCol.StrData[2] = "B"
	catCol.StrData[3] = "B"
	catCol.StrData[4] = "C"

	valCol := t.AddColumn("value", store.TypeFloat)
	valCol.FloatData[0] = 10.0
	valCol.FloatData[1] = 20.0
	valCol.FloatData[2] = 30.0
	valCol.FloatData[3] = 40.0
	valCol.FloatData[4] = 50.0

	return t
}

func TestExecute_SelectColumns(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()
	im.BuildIndex(table.GetColumn("category"))

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT id, value FROM test")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 5 {
		t.Errorf("expected 5 rows, got %d", result.RowCount)
	}

	if len(result.Columns) != 2 {
		t.Errorf("expected 2 columns, got %d", len(result.Columns))
	}

	names := result.ColumnNames()
	if names[0] != "id" || names[1] != "value" {
		t.Errorf("expected [id, value], got %v", names)
	}
}

func TestExecute_FilterWhere(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()
	im.BuildIndex(table.GetColumn("category"))

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT * FROM test WHERE value > 25")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 3 {
		t.Errorf("expected 3 rows (values > 25), got %d", result.RowCount)
	}
}

func TestExecute_FilterEquals(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()
	im.BuildIndex(table.GetColumn("category"))

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse(`SELECT * FROM test WHERE category = 'A'`)
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 2 {
		t.Errorf("expected 2 rows (category A), got %d", result.RowCount)
	}
}

func TestExecute_FilterAnd(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()
	im.BuildIndex(table.GetColumn("category"))

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT * FROM test WHERE value >= 20 AND value <= 40")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 3 {
		t.Errorf("expected 3 rows (20-40), got %d", result.RowCount)
	}
}

func TestExecute_FilterBetween(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT * FROM test WHERE value BETWEEN 15 AND 45")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 3 {
		t.Errorf("expected 3 rows (15-45), got %d", result.RowCount)
	}
}

func TestExecute_FilterIn(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse(`SELECT * FROM test WHERE category IN ('A', 'C')`)
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 3 {
		t.Errorf("expected 3 rows (A and C), got %d", result.RowCount)
	}
}

func TestExecute_OrderBy(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT value FROM test ORDER BY value DESC")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	col := result.GetColumn("value")
	if col == nil {
		t.Fatal("value column not found")
	}

	if col.FloatData[0] != 50.0 || col.FloatData[1] != 40.0 {
		t.Errorf("expected descending order, first two: got %v, %v", col.FloatData[0], col.FloatData[1])
	}
}

func TestExecute_Limit(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT * FROM test LIMIT 3")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 3 {
		t.Errorf("expected 3 rows, got %d", result.RowCount)
	}
}

func TestExecute_GroupBySum(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT category FROM test GROUP BY category SUM(value)")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 3 {
		t.Errorf("expected 3 groups, got %d", result.RowCount)
	}

	sumCol := result.GetColumn("SUM(value)")
	if sumCol == nil {
		t.Errorf("SUM(value) column not found, columns: %v", result.ColumnNames())
	} else {
		if sumCol.FloatData[0] != 30.0 {
			t.Errorf("group A sum: expected 30, got %v", sumCol.FloatData[0])
		}
	}
}

func TestExecute_GroupByAvg(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT category FROM test GROUP BY category AVG(value)")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	avgCol := result.GetColumn("AVG(value)")
	if avgCol == nil {
		t.Errorf("AVG(value) column not found, columns: %v", result.ColumnNames())
	} else {
		if avgCol.FloatData[0] != 15.0 {
			t.Errorf("group A avg: expected 15, got %v", avgCol.FloatData[0])
		}
	}
}

func TestExecute_GroupByCount(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT category FROM test GROUP BY category COUNT(value)")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	countCol := result.GetColumn("COUNT(value)")
	if countCol == nil {
		t.Errorf("COUNT(value) column not found, columns: %v", result.ColumnNames())
	}
}

func TestExecute_ComplexPipeline(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()
	im.BuildIndex(table.GetColumn("category"))

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse(`
		SELECT category, value FROM test 
		WHERE value > 15 
		GROUP BY category SUM(value) 
		LIMIT 10
	`)
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 3 {
		t.Errorf("expected 3 groups after filter, got %d", result.RowCount)
	}
}

func TestExecute_FilterIsNull(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()

	valCol := table.GetColumn("value")
	valCol.NullMap[2] = true

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT * FROM test WHERE value IS NULL")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 1 {
		t.Errorf("expected 1 null row, got %d", result.RowCount)
	}
}

func TestExecute_FilterIsNotNull(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()

	valCol := table.GetColumn("value")
	valCol.NullMap[2] = true

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT * FROM test WHERE value IS NOT NULL")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 4 {
		t.Errorf("expected 4 non-null rows, got %d", result.RowCount)
	}
}

func TestExecute_FilterOr(t *testing.T) {
	table := makeTestTable()
	im := store.NewIndexManager()

	exec := NewExecutor(im)
	stmt, _ := NewParser().Parse("SELECT * FROM test WHERE value < 15 OR value > 45")
	result, err := exec.Execute(table, stmt)

	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 2 {
		t.Errorf("expected 2 rows (10 and 50), got %d", result.RowCount)
	}
}
