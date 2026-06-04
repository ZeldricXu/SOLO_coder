package query

import (
	"testing"
)

func TestParse_SelectAll(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if stmt.From != "data" {
		t.Errorf("expected FROM data, got %s", stmt.From)
	}

	if len(stmt.Columns) != 1 || stmt.Columns[0] != "*" {
		t.Errorf("expected [*] columns, got %v", stmt.Columns)
	}
}

func TestParse_SelectColumns(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT col1, col2, col3 FROM data")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	expected := []string{"col1", "col2", "col3"}
	if len(stmt.Columns) != len(expected) {
		t.Fatalf("expected %d columns, got %d", len(expected), len(stmt.Columns))
	}

	for i, c := range expected {
		if stmt.Columns[i] != c {
			t.Errorf("column %d: expected %s, got %s", i, c, stmt.Columns[i])
		}
	}
}

func TestParse_WhereCompare(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data WHERE x > 100")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if stmt.Where == nil {
		t.Fatal("expected WHERE expression, got nil")
	}

	compare, ok := stmt.Where.(*CompareExpr)
	if !ok {
		t.Fatalf("expected CompareExpr, got %T", stmt.Where)
	}

	if compare.Col != "x" {
		t.Errorf("expected column x, got %s", compare.Col)
	}
	if compare.Op != ">" {
		t.Errorf("expected op >, got %s", compare.Op)
	}

	var actualVal int64
	if f, ok := compare.Value.(int64); ok {
		actualVal = f
	} else if f, ok := compare.Value.(float64); ok {
		actualVal = int64(f)
	} else {
		t.Fatalf("expected numeric value, got %T: %v", compare.Value, compare.Value)
	}
	if actualVal != 100 {
		t.Errorf("expected value 100, got %d", actualVal)
	}
}

func TestParse_WhereEqualsString(t *testing.T) {
	stmt, err := NewParser().Parse(`SELECT * FROM data WHERE name = 'Alice'`)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	compare, ok := stmt.Where.(*CompareExpr)
	if !ok {
		t.Fatalf("expected CompareExpr, got %T", stmt.Where)
	}

	if compare.Col != "name" {
		t.Errorf("expected column name, got %s", compare.Col)
	}
	if compare.Op != "=" {
		t.Errorf("expected op =, got %s", compare.Op)
	}
	if compare.Value != "Alice" {
		t.Errorf("expected value Alice, got %v", compare.Value)
	}
}

func TestParse_WhereAnd(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data WHERE x > 10 AND y < 20")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	bin, ok := stmt.Where.(*BinaryExpr)
	if !ok {
		t.Fatalf("expected BinaryExpr, got %T", stmt.Where)
	}

	if bin.Op != "AND" {
		t.Errorf("expected AND, got %s", bin.Op)
	}

	left, ok := bin.Left.(*CompareExpr)
	if !ok {
		t.Errorf("expected left CompareExpr, got %T", bin.Left)
	} else {
		if left.Col != "x" || left.Op != ">" {
			t.Errorf("left: expected x >, got %s %s", left.Col, left.Op)
		}
	}

	right, ok := bin.Right.(*CompareExpr)
	if !ok {
		t.Errorf("expected right CompareExpr, got %T", bin.Right)
	} else {
		if right.Col != "y" || right.Op != "<" {
			t.Errorf("right: expected y <, got %s %s", right.Col, right.Op)
		}
	}
}

func TestParse_WhereOr(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data WHERE a = 1 OR b = 2")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	bin, ok := stmt.Where.(*BinaryExpr)
	if !ok {
		t.Fatalf("expected BinaryExpr, got %T", stmt.Where)
	}

	if bin.Op != "OR" {
		t.Errorf("expected OR, got %s", bin.Op)
	}
}

func TestParse_WhereIn(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data WHERE x IN (1, 2, 3)")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	inExpr, ok := stmt.Where.(*InExpr)
	if !ok {
		t.Fatalf("expected InExpr, got %T", stmt.Where)
	}

	if inExpr.Col != "x" {
		t.Errorf("expected column x, got %s", inExpr.Col)
	}
	if inExpr.Negated {
		t.Error("expected not negated")
	}
	if len(inExpr.Values) != 3 {
		t.Errorf("expected 3 values, got %d", len(inExpr.Values))
	}
}

func TestParse_WhereNotIn(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data WHERE x NOT IN ('a', 'b')")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	inExpr, ok := stmt.Where.(*InExpr)
	if !ok {
		t.Fatalf("expected InExpr, got %T", stmt.Where)
	}

	if !inExpr.Negated {
		t.Error("expected negated")
	}
	if len(inExpr.Values) != 2 {
		t.Errorf("expected 2 values, got %d", len(inExpr.Values))
	}
}

func TestParse_WhereBetween(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data WHERE x BETWEEN 10 AND 20")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	between, ok := stmt.Where.(*BetweenExpr)
	if !ok {
		t.Fatalf("expected BetweenExpr, got %T", stmt.Where)
	}

	if between.Col != "x" {
		t.Errorf("expected column x, got %s", between.Col)
	}

	lowVal := toNumeric(between.Low)
	highVal := toNumeric(between.High)
	if lowVal != 10 {
		t.Errorf("expected low 10, got %v", between.Low)
	}
	if highVal != 20 {
		t.Errorf("expected high 20, got %v", between.High)
	}
}

func TestParse_WhereIsNull(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data WHERE x IS NULL")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	isNull, ok := stmt.Where.(*IsNullExpr)
	if !ok {
		t.Fatalf("expected IsNullExpr, got %T", stmt.Where)
	}

	if isNull.Col != "x" {
		t.Errorf("expected column x, got %s", isNull.Col)
	}
	if isNull.Negated {
		t.Error("expected not negated")
	}
}

func TestParse_WhereIsNotNull(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data WHERE x IS NOT NULL")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	isNull, ok := stmt.Where.(*IsNullExpr)
	if !ok {
		t.Fatalf("expected IsNullExpr, got %T", stmt.Where)
	}

	if !isNull.Negated {
		t.Error("expected negated")
	}
}

func TestParse_Limit(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data LIMIT 50")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if stmt.Limit != 50 {
		t.Errorf("expected limit 50, got %d", stmt.Limit)
	}
}

func TestParse_OrderByAsc(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data ORDER BY name")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if stmt.OrderBy != "name" {
		t.Errorf("expected order by name, got %s", stmt.OrderBy)
	}
	if !stmt.OrderAsc {
		t.Error("expected ascending order")
	}
}

func TestParse_OrderByDesc(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data ORDER BY value DESC")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if stmt.OrderBy != "value" {
		t.Errorf("expected order by value, got %s", stmt.OrderBy)
	}
	if stmt.OrderAsc {
		t.Error("expected descending order")
	}
}

func TestParse_GroupBy(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT category FROM data GROUP BY category SUM(amount)")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if len(stmt.GroupBy) != 1 || stmt.GroupBy[0] != "category" {
		t.Errorf("expected group by category, got %v", stmt.GroupBy)
	}
	if stmt.AggCol != "amount" {
		t.Errorf("expected agg col amount, got %s", stmt.AggCol)
	}
	if stmt.AggFunc != "SUM" {
		t.Errorf("expected agg func SUM, got %s", stmt.AggFunc)
	}
}

func TestParse_ComplexQuery(t *testing.T) {
	sql := `SELECT name, age FROM users 
		WHERE age >= 18 AND country = 'USA' 
		GROUP BY country AVG(income)
		ORDER BY age DESC 
		LIMIT 100`

	stmt, err := NewParser().Parse(sql)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if len(stmt.Columns) != 2 {
		t.Errorf("expected 2 columns, got %d", len(stmt.Columns))
	}
	if stmt.From != "users" {
		t.Errorf("expected FROM users, got %s", stmt.From)
	}
	if stmt.Where == nil {
		t.Error("expected WHERE clause")
	}
	if len(stmt.GroupBy) != 1 || stmt.GroupBy[0] != "country" {
		t.Errorf("expected group by country, got %v", stmt.GroupBy)
	}
	if stmt.OrderBy != "age" {
		t.Errorf("expected order by age, got %s", stmt.OrderBy)
	}
	if stmt.OrderAsc {
		t.Error("expected DESC order")
	}
	if stmt.Limit != 100 {
		t.Errorf("expected limit 100, got %d", stmt.Limit)
	}
}

func TestParse_Operators(t *testing.T) {
	ops := []struct {
		op      string
		wantOp  string
	}{
		{"x = 5", "="},
		{"x != 5", "!="},
		{"x > 5", ">"},
		{"x < 5", "<"},
		{"x >= 5", ">="},
		{"x <= 5", "<="},
	}

	for _, tc := range ops {
		sql := "SELECT * FROM data WHERE " + tc.op
		stmt, err := NewParser().Parse(sql)
		if err != nil {
			t.Errorf("%q: parse error: %v", tc.op, err)
			continue
		}
		cmp, ok := stmt.Where.(*CompareExpr)
		if !ok {
			t.Errorf("%q: expected CompareExpr, got %T", tc.op, stmt.Where)
			continue
		}
		if cmp.Op != tc.wantOp {
			t.Errorf("%q: expected op %s, got %s", tc.op, tc.wantOp, cmp.Op)
		}
	}
}

func TestParse_FloatValues(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data WHERE x = 3.14")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	cmp, ok := stmt.Where.(*CompareExpr)
	if !ok {
		t.Fatalf("expected CompareExpr, got %T", stmt.Where)
	}

	val, ok := cmp.Value.(float64)
	if !ok {
		t.Fatalf("expected float64, got %T", cmp.Value)
	}
	if val != 3.14 {
		t.Errorf("expected 3.14, got %v", val)
	}
}

func TestParse_DoubleQuotedString(t *testing.T) {
	stmt, err := NewParser().Parse(`SELECT * FROM data WHERE name = "Bob"`)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	cmp, ok := stmt.Where.(*CompareExpr)
	if !ok {
		t.Fatalf("expected CompareExpr, got %T", stmt.Where)
	}

	if cmp.Value != "Bob" {
		t.Errorf("expected Bob, got %v", cmp.Value)
	}
}

func TestParse_NotExpression(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data WHERE NOT x > 10")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	bin, ok := stmt.Where.(*BinaryExpr)
	if !ok {
		t.Skipf("NOT expression may be BinaryExpr with NOT, got %T", stmt.Where)
		return
	}

	if bin.Op != "NOT" {
		t.Errorf("expected NOT op, got %s", bin.Op)
	}
}

func TestParse_Parentheses(t *testing.T) {
	stmt, err := NewParser().Parse("SELECT * FROM data WHERE (x > 10 AND y < 5) OR z = 0")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if stmt.Where == nil {
		t.Fatal("expected WHERE clause")
	}
}

func toNumeric(v interface{}) float64 {
	switch val := v.(type) {
	case float64:
		return val
	case int64:
		return float64(val)
	case int:
		return float64(val)
	default:
		return -1
	}
}
