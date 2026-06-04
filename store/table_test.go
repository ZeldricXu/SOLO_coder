package store

import (
	"sort"
	"testing"
)

func TestNewColumn(t *testing.T) {
	col := NewColumn("test", TypeInt, 5)

	if col.Name != "test" {
		t.Errorf("expected name test, got %s", col.Name)
	}
	if col.DataType != TypeInt {
		t.Errorf("expected TypeInt, got %v", col.DataType)
	}
	if col.Length != 5 {
		t.Errorf("expected length 5, got %d", col.Length)
	}
	if len(col.NullMap) != 5 {
		t.Errorf("expected nullmap length 5, got %d", len(col.NullMap))
	}
}

func TestColumn_SetGetValue(t *testing.T) {
	col := NewColumn("nums", TypeInt, 3)

	col.SetValue(0, int64(10))
	col.SetValue(1, int64(20))
	col.SetValue(2, nil)

	if col.GetInt(0) != 10 {
		t.Errorf("expected 10 at index 0, got %d", col.GetInt(0))
	}
	if col.GetInt(1) != 20 {
		t.Errorf("expected 20 at index 1, got %d", col.GetInt(1))
	}
	if !col.IsNull(2) {
		t.Error("expected null at index 2")
	}
}

func TestColumn_FloatConvert(t *testing.T) {
	col := NewColumn("nums", TypeFloat, 2)

	col.SetValue(0, 3.14)
	col.SetValue(1, int64(42))

	if col.GetFloat(0) != 3.14 {
		t.Errorf("expected 3.14, got %f", col.GetFloat(0))
	}
	if col.GetFloat(1) != 42.0 {
		t.Errorf("expected 42.0 (converted), got %f", col.GetFloat(1))
	}
}

func TestColumn_StringType(t *testing.T) {
	col := NewColumn("names", TypeString, 2)

	col.SetValue(0, "hello")
	col.SetValue(1, "world")

	if col.GetString(0) != "hello" {
		t.Errorf("expected hello, got %s", col.GetString(0))
	}
}

func TestColumn_BoolType(t *testing.T) {
	col := NewColumn("flags", TypeBool, 2)

	col.SetValue(0, true)
	col.SetValue(1, false)

	if !col.GetBool(0) {
		t.Error("expected true at 0")
	}
	if col.GetBool(1) {
		t.Error("expected false at 1")
	}
}

func TestColumn_UniqueValues(t *testing.T) {
	col := NewColumn("cats", TypeString, 5)
	col.SetValue(0, "A")
	col.SetValue(1, "B")
	col.SetValue(2, "A")
	col.SetValue(3, "C")
	col.SetValue(4, "B")

	unique := col.UniqueValues()
	if len(unique) != 3 {
		t.Errorf("expected 3 unique values, got %d", len(unique))
	}
}

func TestTable_AddColumn(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 5

	col := tbl.AddColumn("id", TypeInt)

	if col == nil {
		t.Fatal("column is nil")
	}
	if len(tbl.Columns) != 1 {
		t.Errorf("expected 1 column, got %d", len(tbl.Columns))
	}
	if tbl.GetColumn("id") == nil {
		t.Error("GetColumn returned nil")
	}
}

func TestTable_GetColumnNotFound(t *testing.T) {
	tbl := NewTable("test")
	if tbl.GetColumn("nonexistent") != nil {
		t.Error("expected nil for non-existent column")
	}
}

func TestTable_Select(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 3
	tbl.AddColumn("a", TypeInt)
	tbl.AddColumn("b", TypeString)
	tbl.AddColumn("c", TypeFloat)

	selected := tbl.Select([]string{"a", "c"})

	if len(selected.Columns) != 2 {
		t.Errorf("expected 2 columns, got %d", len(selected.Columns))
	}
	names := selected.ColumnNames()
	if names[0] != "a" || names[1] != "c" {
		t.Errorf("expected [a, c], got %v", names)
	}
}

func TestTable_Filter(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 5
	col := tbl.AddColumn("value", TypeInt)
	col.IntData[0] = 1
	col.IntData[1] = 2
	col.IntData[2] = 3
	col.IntData[3] = 4
	col.IntData[4] = 5

	mask := []bool{true, false, true, false, true}
	filtered := tbl.Filter(mask)

	if filtered.RowCount != 3 {
		t.Errorf("expected 3 rows, got %d", filtered.RowCount)
	}

	fcol := filtered.GetColumn("value")
	if fcol == nil {
		t.Fatal("value column not found")
	}
	if fcol.GetInt(0) != 1 || fcol.GetInt(1) != 3 || fcol.GetInt(2) != 5 {
		t.Errorf("expected [1, 3, 5], got [%d, %d, %d]", fcol.GetInt(0), fcol.GetInt(1), fcol.GetInt(2))
	}
}

func TestTable_Limit(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 10
	tbl.AddColumn("x", TypeInt)

	limited := tbl.Limit(5)
	if limited.RowCount != 5 {
		t.Errorf("expected 5 rows, got %d", limited.RowCount)
	}

	same := tbl.Limit(100)
	if same.RowCount != 10 {
		t.Errorf("expected 10 rows (no change), got %d", same.RowCount)
	}
}

func TestTable_SortAscending(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 5
	col := tbl.AddColumn("value", TypeInt)
	col.IntData[0] = 50
	col.IntData[1] = 10
	col.IntData[2] = 40
	col.IntData[3] = 20
	col.IntData[4] = 30

	tbl.Sort("value", true)

	if col.GetInt(0) != 10 || col.GetInt(4) != 50 {
		t.Errorf("expected sorted [10,...,50], got [%d, ..., %d]", col.GetInt(0), col.GetInt(4))
	}
}

func TestTable_SortDescending(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 5
	col := tbl.AddColumn("value", TypeInt)
	col.IntData[0] = 10
	col.IntData[1] = 50
	col.IntData[2] = 30
	col.IntData[3] = 40
	col.IntData[4] = 20

	tbl.Sort("value", false)

	if col.GetInt(0) != 50 || col.GetInt(4) != 10 {
		t.Errorf("expected sorted descending [50,...,10], got [%d, ..., %d]", col.GetInt(0), col.GetInt(4))
	}
}

func TestTable_GroupBySum(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 4

	cat := tbl.AddColumn("category", TypeString)
	cat.StrData[0] = "A"
	cat.StrData[1] = "A"
	cat.StrData[2] = "B"
	cat.StrData[3] = "B"

	val := tbl.AddColumn("value", TypeFloat)
	val.FloatData[0] = 10
	val.FloatData[1] = 20
	val.FloatData[2] = 30
	val.FloatData[3] = 40

	results, err := tbl.GroupBy([]string{"category"}, "value", AggSum)
	if err != nil {
		t.Fatalf("group by error: %v", err)
	}

	if len(results) != 2 {
		t.Errorf("expected 2 groups, got %d", len(results))
	}

	for _, r := range results {
		catVal := r.Keys["category"]
		sumVal := r.Values["SUM(value)"]
		if catVal == "A" && sumVal != 30.0 {
			t.Errorf("group A sum: expected 30, got %v", sumVal)
		}
		if catVal == "B" && sumVal != 70.0 {
			t.Errorf("group B sum: expected 70, got %v", sumVal)
		}
	}
}

func TestTable_GroupByCount(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 4

	cat := tbl.AddColumn("category", TypeString)
	cat.StrData[0] = "A"
	cat.StrData[1] = "A"
	cat.StrData[2] = "B"
	cat.StrData[3] = "B"

	val := tbl.AddColumn("value", TypeFloat)
	val.FloatData[0] = 10
	val.FloatData[1] = 20
	val.FloatData[2] = 30
	val.FloatData[3] = 40

	results, err := tbl.GroupBy([]string{"category"}, "value", AggCount)
	if err != nil {
		t.Fatalf("group by error: %v", err)
	}

	for _, r := range results {
		if r.Count != 2 {
			t.Errorf("expected count 2 per group, got %d", r.Count)
		}
	}
}

func TestTable_GroupByAvg(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 2

	cat := tbl.AddColumn("category", TypeString)
	cat.StrData[0] = "A"
	cat.StrData[1] = "A"

	val := tbl.AddColumn("value", TypeFloat)
	val.FloatData[0] = 10
	val.FloatData[1] = 20

	results, err := tbl.GroupBy([]string{"category"}, "value", AggAvg)
	if err != nil {
		t.Fatalf("group by error: %v", err)
	}

	if len(results) != 1 {
		t.Fatalf("expected 1 group, got %d", len(results))
	}

	avg := results[0].Values["AVG(value)"]
	if avg != 15.0 {
		t.Errorf("expected avg 15, got %v", avg)
	}
}

func TestTable_ToJSON(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 2

	id := tbl.AddColumn("id", TypeInt)
	id.IntData[0] = 1
	id.IntData[1] = 2

	name := tbl.AddColumn("name", TypeString)
	name.StrData[0] = "Alice"
	name.StrData[1] = "Bob"

	data := tbl.ToJSON(0, 10)
	if len(data) != 2 {
		t.Errorf("expected 2 rows, got %d", len(data))
	}

	if data[0]["id"] != int64(1) {
		t.Errorf("expected id=1, got %v", data[0]["id"])
	}
	if data[0]["name"] != "Alice" {
		t.Errorf("expected name=Alice, got %v", data[0]["name"])
	}
}

func TestTable_ToJSONWithLimit(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 100
	tbl.AddColumn("x", TypeInt)

	data := tbl.ToJSON(10, 5)
	if len(data) != 5 {
		t.Errorf("expected 5 rows, got %d", len(data))
	}
}

func TestTable_SchemaJSON(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 1
	tbl.AddColumn("id", TypeInt)
	tbl.AddColumn("name", TypeString)
	tbl.AddColumn("active", TypeBool)

	schema := tbl.SchemaJSON()
	if schema == "" {
		t.Error("schema is empty")
	}
}

func TestTable_ColumnNames(t *testing.T) {
	tbl := NewTable("test")
	tbl.AddColumn("a", TypeInt)
	tbl.AddColumn("b", TypeString)
	tbl.AddColumn("c", TypeFloat)

	names := tbl.ColumnNames()
	expected := []string{"a", "b", "c"}
	for i, n := range expected {
		if names[i] != n {
			t.Errorf("index %d: expected %s, got %s", i, n, names[i])
		}
	}
}

func TestTable_GroupByCountDistinct(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 6

	cat := tbl.AddColumn("category", TypeString)
	cat.StrData[0] = "A"
	cat.StrData[1] = "A"
	cat.StrData[2] = "A"
	cat.StrData[3] = "B"
	cat.StrData[4] = "B"
	cat.StrData[5] = "B"

	val := tbl.AddColumn("value", TypeInt)
	val.IntData[0] = 10
	val.IntData[1] = 10
	val.IntData[2] = 20
	val.IntData[3] = 30
	val.IntData[4] = 30
	val.IntData[5] = 30

	results, err := tbl.GroupBy([]string{"category"}, "value", AggCountDistinct)
	if err != nil {
		t.Fatalf("group by error: %v", err)
	}

	if len(results) != 2 {
		t.Errorf("expected 2 groups, got %d", len(results))
	}

	for _, r := range results {
		catVal := r.Keys["category"]
		distinctCount := r.Values["COUNT_DISTINCT(value)"]
		if catVal == "A" && distinctCount != 2.0 {
			t.Errorf("group A count distinct: expected 2, got %v", distinctCount)
		}
		if catVal == "B" && distinctCount != 1.0 {
			t.Errorf("group B count distinct: expected 1, got %v", distinctCount)
		}
	}
}

func TestTable_GroupByPercentileP50(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 5

	cat := tbl.AddColumn("category", TypeString)
	for i := 0; i < 5; i++ {
		cat.StrData[i] = "A"
	}

	val := tbl.AddColumn("value", TypeFloat)
	val.FloatData[0] = 10
	val.FloatData[1] = 20
	val.FloatData[2] = 30
	val.FloatData[3] = 40
	val.FloatData[4] = 50

	results, err := tbl.GroupBy([]string{"category"}, "value", AggPercentile, 50.0)
	if err != nil {
		t.Fatalf("group by error: %v", err)
	}

	if len(results) != 1 {
		t.Fatalf("expected 1 group, got %d", len(results))
	}

	p50 := results[0].Values["PERCENTILE(value)"]
	if p50 != 30.0 {
		t.Errorf("P50: expected 30, got %v", p50)
	}
}

func TestTable_GroupByPercentileP90(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 10

	cat := tbl.AddColumn("category", TypeString)
	for i := 0; i < 10; i++ {
		cat.StrData[i] = "A"
	}

	val := tbl.AddColumn("value", TypeFloat)
	for i := 0; i < 10; i++ {
		val.FloatData[i] = float64(i + 1)
	}

	results, err := tbl.GroupBy([]string{"category"}, "value", AggPercentile, 90.0)
	if err != nil {
		t.Fatalf("group by error: %v", err)
	}

	p90 := results[0].Values["PERCENTILE(value)"]
	expectedP90 := 9.0
	if p90 != expectedP90 {
		t.Errorf("P90: expected %v, got %v", expectedP90, p90)
	}
}

func TestTable_GroupByPercentileP95(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 20

	cat := tbl.AddColumn("category", TypeString)
	for i := 0; i < 20; i++ {
		cat.StrData[i] = "A"
	}

	val := tbl.AddColumn("value", TypeFloat)
	for i := 0; i < 20; i++ {
		val.FloatData[i] = float64(i + 1)
	}

	results, err := tbl.GroupBy([]string{"category"}, "value", AggPercentile, 95.0)
	if err != nil {
		t.Fatalf("group by error: %v", err)
	}

	p95 := results[0].Values["PERCENTILE(value)"]
	expectedP95 := 19.0
	if p95 != expectedP95 {
		t.Errorf("P95: expected %v, got %v", expectedP95, p95)
	}
}

func TestTable_GroupByPercentileP99(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 100

	cat := tbl.AddColumn("category", TypeString)
	for i := 0; i < 100; i++ {
		cat.StrData[i] = "A"
	}

	val := tbl.AddColumn("value", TypeFloat)
	for i := 0; i < 100; i++ {
		val.FloatData[i] = float64(i + 1)
	}

	results, err := tbl.GroupBy([]string{"category"}, "value", AggPercentile, 99.0)
	if err != nil {
		t.Fatalf("group by error: %v", err)
	}

	p99 := results[0].Values["PERCENTILE(value)"]
	expectedP99 := 99.0
	if p99 != expectedP99 {
		t.Errorf("P99: expected %v, got %v", expectedP99, p99)
	}
}

func TestQuickselectVsSort(t *testing.T) {
	testData := []float64{9, 5, 7, 1, 3, 8, 2, 6, 4}
	indices := []int{0, 2, 4, 6, 8}

	for _, k := range indices {
		arrCopy1 := make([]float64, len(testData))
		copy(arrCopy1, testData)
		quickselectResult := quickselect(arrCopy1, k)

		arrCopy2 := make([]float64, len(testData))
		copy(arrCopy2, testData)
		sort.Float64s(arrCopy2)
		sortedResult := arrCopy2[k]

		if quickselectResult != sortedResult {
			t.Errorf("k=%d: quickselect gave %v, sorted gave %v", k, quickselectResult, sortedResult)
		}
	}
}

func TestTable_GroupByPercentileWithMultipleGroups(t *testing.T) {
	tbl := NewTable("test")
	tbl.RowCount = 8

	cat := tbl.AddColumn("category", TypeString)
	values := []float64{10, 20, 30, 40, 100, 200, 300, 400}
	for i := 0; i < 8; i++ {
		if i < 4 {
			cat.StrData[i] = "A"
		} else {
			cat.StrData[i] = "B"
		}
	}

	val := tbl.AddColumn("value", TypeFloat)
	for i, v := range values {
		val.FloatData[i] = v
	}

	results, err := tbl.GroupBy([]string{"category"}, "value", AggPercentile, 50.0)
	if err != nil {
		t.Fatalf("group by error: %v", err)
	}

	if len(results) != 2 {
		t.Fatalf("expected 2 groups, got %d", len(results))
	}

	for _, r := range results {
		catVal := r.Keys["category"]
		p50 := r.Values["PERCENTILE(value)"]
		if catVal == "A" && p50 != 20.0 {
			t.Errorf("group A P50: expected 20, got %v", p50)
		}
		if catVal == "B" && p50 != 200.0 {
			t.Errorf("group B P50: expected 200, got %v", p50)
		}
	}
}
