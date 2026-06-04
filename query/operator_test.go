package query

import (
	"testing"

	"github.com/dataexplorer/store"
)

func createTestTable() *store.Table {
	tbl := store.NewTable("test")
	intCol := tbl.AddColumn("id", store.TypeInt)
	floatCol := tbl.AddColumn("value", store.TypeFloat)
	strCol := tbl.AddColumn("category", store.TypeString)

	rows := []struct {
		id    int64
		value float64
		cat   string
	}{
		{1, 10.5, "A"},
		{2, 20.5, "B"},
		{3, 30.5, "A"},
		{4, 40.5, "B"},
		{5, 50.5, "A"},
	}

	tbl.SetRowCount(len(rows))
	for i, r := range rows {
		intCol.IntData[i] = r.id
		floatCol.FloatData[i] = r.value
		strCol.StrData[i] = r.cat
	}
	return tbl
}

func TestScanOp(t *testing.T) {
	tbl := createTestTable()
	op := NewScanOp(tbl, []string{"id", "value", "category"})

	cols, types := op.Schema()
	if len(cols) != 3 {
		t.Fatalf("expected 3 columns, got %d", len(cols))
	}
	if cols[0] != "id" || cols[1] != "value" || cols[2] != "category" {
		t.Fatalf("unexpected column names: %v", cols)
	}
	if types[0] != store.TypeInt || types[1] != store.TypeFloat || types[2] != store.TypeString {
		t.Fatalf("unexpected column types")
	}

	batch, err := op.Next()
	if err != nil {
		t.Fatal(err)
	}
	if batch == nil {
		t.Fatal("expected batch, got nil")
	}
	if batch.Len() != 5 {
		t.Fatalf("expected 5 rows, got %d", batch.Len())
	}

	batch, err = op.Next()
	if err != nil {
		t.Fatal(err)
	}
	if batch != nil {
		t.Fatal("expected nil batch after all rows consumed")
	}
}

func TestScanOpSelectAll(t *testing.T) {
	tbl := createTestTable()
	op := NewScanOp(tbl, []string{"*"})

	cols, _ := op.Schema()
	if len(cols) != 3 {
		t.Fatalf("expected 3 columns for *, got %d", len(cols))
	}

	batch, err := op.Next()
	if err != nil {
		t.Fatal(err)
	}
	if batch.Len() != 5 {
		t.Fatalf("expected 5 rows, got %d", batch.Len())
	}
}

func TestSelectOp(t *testing.T) {
	tbl := createTestTable()
	scan := NewScanOp(tbl, []string{"*"})
	sel := NewSelectOp(scan, []string{"id", "category"}, tbl)

	cols, types := sel.Schema()
	if len(cols) != 2 {
		t.Fatalf("expected 2 columns, got %d", len(cols))
	}
	if cols[0] != "id" || cols[1] != "category" {
		t.Fatalf("unexpected column names: %v", cols)
	}
	if types[0] != store.TypeInt || types[1] != store.TypeString {
		t.Fatalf("unexpected column types")
	}

	batch, err := sel.Next()
	if err != nil {
		t.Fatal(err)
	}
	if batch.Len() != 5 {
		t.Fatalf("expected 5 rows, got %d", batch.Len())
	}
	if len(batch.Columns) != 2 {
		t.Fatalf("expected 2 columns in batch, got %d", len(batch.Columns))
	}

	idVal, ok := batch.Rows[0][0].(int64)
	if !ok || idVal != 1 {
		t.Fatalf("expected id=1, got %v", batch.Rows[0][0])
	}
	catVal, ok := batch.Rows[0][1].(string)
	if !ok || catVal != "A" {
		t.Fatalf("expected category=A, got %v", batch.Rows[0][1])
	}
}

func TestFilterOp(t *testing.T) {
	tbl := createTestTable()
	scan := NewScanOp(tbl, []string{"*"})

	expr := &CompareExpr{
		Col:  "value",
		Op:   ">",
		Value: float64(25.0),
	}

	eval := NewExecutor(nil)
	filter := NewFilterOp(scan, expr, eval, tbl)

	batch, err := filter.Next()
	if err != nil {
		t.Fatal(err)
	}
	if batch.Len() != 3 {
		t.Fatalf("expected 3 rows (value > 25), got %d", batch.Len())
	}

	expectedValues := []float64{30.5, 40.5, 50.5}
	for i, expected := range expectedValues {
		val, ok := batch.Rows[i][1].(float64)
		if !ok || val != expected {
			t.Fatalf("row %d: expected value=%f, got %v", i, expected, batch.Rows[i][1])
		}
	}
}

func TestLimitOp(t *testing.T) {
	tbl := createTestTable()
	scan := NewScanOp(tbl, []string{"*"})
	limit := NewLimitOp(scan, 3)

	batch, err := limit.Next()
	if err != nil {
		t.Fatal(err)
	}
	if batch.Len() != 3 {
		t.Fatalf("expected 3 rows, got %d", batch.Len())
	}

	batch, err = limit.Next()
	if err != nil {
		t.Fatal(err)
	}
	if batch != nil {
		t.Fatal("expected nil batch after limit reached")
	}
}

func TestSortOp(t *testing.T) {
	tbl := createTestTable()
	scan := NewScanOp(tbl, []string{"*"})
	sort := NewSortOp(scan, "value", false, tbl)

	batch, err := sort.Next()
	if err != nil {
		t.Fatal(err)
	}
	if batch.Len() != 5 {
		t.Fatalf("expected 5 rows, got %d", batch.Len())
	}

	expected := []float64{50.5, 40.5, 30.5, 20.5, 10.5}
	for i, exp := range expected {
		val, ok := batch.Rows[i][1].(float64)
		if !ok || val != exp {
			t.Fatalf("row %d: expected %f, got %v", i, exp, batch.Rows[i][1])
		}
	}
}

func TestPipelineChain(t *testing.T) {
	tbl := createTestTable()

	stmt := &SelectStmt{
		From:    "test",
		Columns: []string{"id", "value", "category"},
		Where: &CompareExpr{
			Col:  "value",
			Op:   ">",
			Value: float64(15.0),
		},
		OrderBy: "value",
		OrderAsc: false,
		Limit:   2,
	}

	exec := NewPipelineExecutor(nil)
	result, err := exec.Execute(tbl, stmt)
	if err != nil {
		t.Fatal(err)
	}

	if result.RowCount != 2 {
		t.Fatalf("expected 2 rows, got %d", result.RowCount)
	}

	valCol := result.GetColumn("value")
	if valCol == nil {
		t.Fatal("value column not found")
	}

	if valCol.FloatData[0] != 50.5 || valCol.FloatData[1] != 40.5 {
		t.Fatalf("unexpected values: %v", valCol.FloatData[:result.RowCount])
	}
}

func TestAggregateOpSum(t *testing.T) {
	tbl := createTestTable()
	scan := NewScanOp(tbl, []string{"*"})
	agg := NewAggregateOp(scan, []string{"category"}, "SUM", "value", 0, tbl)

	cols, types := agg.Schema()
	if len(cols) != 2 {
		t.Fatalf("expected 2 columns, got %d", len(cols))
	}
	if cols[0] != "category" || cols[1] != "SUM(value)" {
		t.Fatalf("unexpected column names: %v", cols)
	}
	if types[1] != store.TypeFloat {
		t.Fatalf("expected float type for aggregate, got %v", types[1])
	}

	batch, err := agg.Next()
	if err != nil {
		t.Fatal(err)
	}
	if batch.Len() != 2 {
		t.Fatalf("expected 2 groups, got %d", batch.Len())
	}

	expected := map[string]float64{
		"A": 10.5 + 30.5 + 50.5,
		"B": 20.5 + 40.5,
	}

	for i := 0; i < batch.Len(); i++ {
		cat := batch.Rows[i][0].(string)
		sum := batch.Rows[i][1].(float64)
		if sum != expected[cat] {
			t.Fatalf("category %s: expected sum=%f, got %f", cat, expected[cat], sum)
		}
	}
}

func TestCollectToTable(t *testing.T) {
	tbl := createTestTable()
	scan := NewScanOp(tbl, []string{"id", "value"})

	result, err := CollectToTable(scan, "result")
	if err != nil {
		t.Fatal(err)
	}

	if result.RowCount != 5 {
		t.Fatalf("expected 5 rows, got %d", result.RowCount)
	}
	if len(result.Columns) != 2 {
		t.Fatalf("expected 2 columns, got %d", len(result.Columns))
	}

	idCol := result.GetColumn("id")
	if idCol == nil || idCol.IntData[0] != 1 {
		t.Fatal("id column not correct")
	}

	valCol := result.GetColumn("value")
	if valCol == nil || valCol.FloatData[0] != 10.5 {
		t.Fatal("value column not correct")
	}
}

func TestBatchSize(t *testing.T) {
	tbl := store.NewTable("large")
	idCol := tbl.AddColumn("id", store.TypeInt)
	tbl.SetRowCount(2500)
	for i := 0; i < 2500; i++ {
		idCol.IntData[i] = int64(i)
	}

	op := NewScanOp(tbl, []string{"id"})

	total := 0
	batchCount := 0
	for {
		batch, err := op.Next()
		if err != nil {
			t.Fatal(err)
		}
		if batch == nil {
			break
		}
		batchCount++
		total += batch.Len()

		if batch.Len() > BatchSize {
			t.Fatalf("batch size %d exceeds limit %d", batch.Len(), BatchSize)
		}
	}

	if total != 2500 {
		t.Fatalf("expected 2500 rows, got %d", total)
	}
	if batchCount != 3 {
		t.Fatalf("expected 3 batches (2500/1024), got %d", batchCount)
	}
}
