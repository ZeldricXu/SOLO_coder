package query

import (
	"testing"

	"github.com/dataexplorer/store"
)

func makeUsersTable() *store.Table {
	t := store.NewTable("users")
	t.RowCount = 4

	idCol := t.AddColumn("id", store.TypeInt)
	idCol.IntData[0] = 1
	idCol.IntData[1] = 2
	idCol.IntData[2] = 3
	idCol.IntData[3] = 4

	nameCol := t.AddColumn("name", store.TypeString)
	nameCol.StrData[0] = "Alice"
	nameCol.StrData[1] = "Bob"
	nameCol.StrData[2] = "Charlie"
	nameCol.StrData[3] = "Dave"

	ageCol := t.AddColumn("age", store.TypeInt)
	ageCol.IntData[0] = 30
	ageCol.IntData[1] = 25
	ageCol.IntData[2] = 35
	ageCol.IntData[3] = 40

	return t
}

func makeOrdersTable() *store.Table {
	t := store.NewTable("orders")
	t.RowCount = 5

	idCol := t.AddColumn("id", store.TypeInt)
	idCol.IntData[0] = 101
	idCol.IntData[1] = 102
	idCol.IntData[2] = 103
	idCol.IntData[3] = 104
	idCol.IntData[4] = 105

	userIdCol := t.AddColumn("user_id", store.TypeInt)
	userIdCol.IntData[0] = 1
	userIdCol.IntData[1] = 1
	userIdCol.IntData[2] = 2
	userIdCol.IntData[3] = 3
	userIdCol.IntData[4] = 99

	amountCol := t.AddColumn("amount", store.TypeFloat)
	amountCol.FloatData[0] = 100.0
	amountCol.FloatData[1] = 200.0
	amountCol.FloatData[2] = 150.0
	amountCol.FloatData[3] = 300.0
	amountCol.FloatData[4] = 500.0

	return t
}

func TestInnerJoin_Simple(t *testing.T) {
	users := makeUsersTable()
	orders := makeOrdersTable()

	tables := map[string]*store.Table{
		"users":  users,
		"orders": orders,
	}

	exec := NewExecutor(nil)
	stmt, err := NewParser().Parse(
		"SELECT * FROM orders JOIN users ON orders.user_id = users.id",
	)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	result, err := exec.ExecuteJoin(tables, stmt)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 4 {
		t.Errorf("expected 4 rows, got %d", result.RowCount)
	}

	colNames := result.ColumnNames()
	expectedCols := []string{"orders.id", "user_id", "amount", "users.id", "name", "age"}
	if len(colNames) != len(expectedCols) {
		t.Errorf("expected %d columns, got %d: %v", len(expectedCols), len(colNames), colNames)
	}

	amountCol := result.GetColumn("amount")
	if amountCol == nil {
		t.Fatal("amount column not found")
	}

	nameCol := result.GetColumn("name")
	if nameCol == nil {
		t.Fatal("name column not found")
	}

	expectedAmounts := []float64{100.0, 200.0, 150.0, 300.0}
	expectedNames := []string{"Alice", "Alice", "Bob", "Charlie"}

	for i := 0; i < result.RowCount; i++ {
		if amountCol.FloatData[i] != expectedAmounts[i] {
			t.Errorf("row %d: expected amount %v, got %v", i, expectedAmounts[i], amountCol.FloatData[i])
		}
		if nameCol.StrData[i] != expectedNames[i] {
			t.Errorf("row %d: expected name %v, got %v", i, expectedNames[i], nameCol.StrData[i])
		}
	}
}

func TestLeftJoin_NonMatching(t *testing.T) {
	users := makeUsersTable()
	orders := makeOrdersTable()

	tables := map[string]*store.Table{
		"users":  users,
		"orders": orders,
	}

	exec := NewExecutor(nil)
	stmt, err := NewParser().Parse(
		"SELECT * FROM users LEFT JOIN orders ON users.id = orders.user_id",
	)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	result, err := exec.ExecuteJoin(tables, stmt)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 5 {
		t.Errorf("expected 5 rows, got %d", result.RowCount)
	}

	nameCol := result.GetColumn("name")
	if nameCol == nil {
		t.Fatal("name column not found")
	}

	amountCol := result.GetColumn("amount")
	if amountCol == nil {
		t.Fatal("amount column not found")
	}

	expectedNames := []string{"Alice", "Alice", "Bob", "Charlie", "Dave"}
	expectedAmounts := []float64{100.0, 200.0, 150.0, 300.0, 0}
	expectedNullAmounts := []bool{false, false, false, false, true}

	for i := 0; i < result.RowCount; i++ {
		if nameCol.StrData[i] != expectedNames[i] {
			t.Errorf("row %d: expected name %v, got %v", i, expectedNames[i], nameCol.StrData[i])
		}
		if !expectedNullAmounts[i] && amountCol.FloatData[i] != expectedAmounts[i] {
			t.Errorf("row %d: expected amount %v, got %v", i, expectedAmounts[i], amountCol.FloatData[i])
		}
		if amountCol.NullMap[i] != expectedNullAmounts[i] {
			t.Errorf("row %d: expected null=%v, got %v", i, expectedNullAmounts[i], amountCol.NullMap[i])
		}
	}
}

func TestJoin_WithWhere(t *testing.T) {
	users := makeUsersTable()
	orders := makeOrdersTable()

	tables := map[string]*store.Table{
		"users":  users,
		"orders": orders,
	}

	exec := NewExecutor(nil)
	stmt, err := NewParser().Parse(
		"SELECT name, amount FROM orders JOIN users ON orders.user_id = users.id WHERE amount > 150",
	)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	result, err := exec.ExecuteJoin(tables, stmt)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 2 {
		t.Errorf("expected 2 rows, got %d", result.RowCount)
	}

	amountCol := result.GetColumn("amount")
	if amountCol == nil {
		t.Fatal("amount column not found")
	}

	if amountCol.FloatData[0] != 200.0 || amountCol.FloatData[1] != 300.0 {
		t.Errorf("expected amounts 200 and 300, got %v and %v", amountCol.FloatData[0], amountCol.FloatData[1])
	}
}

func TestJoin_WithGroupBy(t *testing.T) {
	users := makeUsersTable()
	orders := makeOrdersTable()

	tables := map[string]*store.Table{
		"users":  users,
		"orders": orders,
	}

	exec := NewExecutor(nil)
	stmt, err := NewParser().Parse(
		"SELECT name FROM orders JOIN users ON orders.user_id = users.id GROUP BY name SUM(amount)",
	)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	result, err := exec.ExecuteJoin(tables, stmt)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 3 {
		t.Errorf("expected 3 groups, got %d", result.RowCount)
	}

	nameCol := result.GetColumn("name")
	if nameCol == nil {
		t.Fatal("name column not found")
	}

	sumCol := result.GetColumn("SUM(amount)")
	if sumCol == nil {
		t.Fatal("SUM(amount) column not found")
	}

	expectedSums := map[string]float64{
		"Alice":   300.0,
		"Bob":     150.0,
		"Charlie": 300.0,
	}

	for i := 0; i < result.RowCount; i++ {
		name := nameCol.StrData[i]
		expected := expectedSums[name]
		if sumCol.FloatData[i] != expected {
			t.Errorf("%s: expected sum %v, got %v", name, expected, sumCol.FloatData[i])
		}
	}
}

func TestJoin_NullJoinKeys(t *testing.T) {
	users := makeUsersTable()
	orders := makeOrdersTable()

	userIdCol := orders.GetColumn("user_id")
	userIdCol.NullMap[4] = true
	userIdCol.IntData[4] = 0

	tables := map[string]*store.Table{
		"users":  users,
		"orders": orders,
	}

	exec := NewExecutor(nil)
	stmt, err := NewParser().Parse(
		"SELECT * FROM orders JOIN users ON orders.user_id = users.id",
	)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	result, err := exec.ExecuteJoin(tables, stmt)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 4 {
		t.Errorf("expected 4 rows (NULL join key should not match), got %d", result.RowCount)
	}
}

func TestParse_InnerJoin(t *testing.T) {
	stmt, err := NewParser().Parse(
		"SELECT * FROM orders JOIN users ON orders.user_id = users.id",
	)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if stmt.From != "orders" {
		t.Errorf("expected FROM orders, got %s", stmt.From)
	}

	if len(stmt.Joins) != 1 {
		t.Fatalf("expected 1 join, got %d", len(stmt.Joins))
	}

	join := stmt.Joins[0]
	if join.JoinType != JoinInner {
		t.Errorf("expected INNER join, got %v", join.JoinType)
	}
	if join.Table != "users" {
		t.Errorf("expected join table users, got %s", join.Table)
	}
	if join.LeftCol != "orders.user_id" {
		t.Errorf("expected left col orders.user_id, got %s", join.LeftCol)
	}
	if join.RightCol != "users.id" {
		t.Errorf("expected right col users.id, got %s", join.RightCol)
	}
}

func TestParse_LeftJoin(t *testing.T) {
	stmt, err := NewParser().Parse(
		"SELECT * FROM users LEFT JOIN orders ON users.id = orders.user_id",
	)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if len(stmt.Joins) != 1 {
		t.Fatalf("expected 1 join, got %d", len(stmt.Joins))
	}

	join := stmt.Joins[0]
	if join.JoinType != JoinLeft {
		t.Errorf("expected LEFT join, got %v", join.JoinType)
	}
	if join.Table != "orders" {
		t.Errorf("expected join table orders, got %s", join.Table)
	}
}

func TestParse_MultipleJoins(t *testing.T) {
	stmt, err := NewParser().Parse(
		"SELECT * FROM a JOIN b ON a.id = b.a_id JOIN c ON b.id = c.b_id",
	)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if len(stmt.Joins) != 2 {
		t.Fatalf("expected 2 joins, got %d", len(stmt.Joins))
	}

	if stmt.Joins[0].Table != "b" {
		t.Errorf("expected first join table b, got %s", stmt.Joins[0].Table)
	}
	if stmt.Joins[1].Table != "c" {
		t.Errorf("expected second join table c, got %s", stmt.Joins[1].Table)
	}
}

func TestTable_Clone(t *testing.T) {
	users := makeUsersTable()
	clone := users.Clone()

	if clone.Name != users.Name {
		t.Errorf("expected name %s, got %s", users.Name, clone.Name)
	}
	if clone.RowCount != users.RowCount {
		t.Errorf("expected row count %d, got %d", users.RowCount, clone.RowCount)
	}
	if len(clone.Columns) != len(users.Columns) {
		t.Errorf("expected %d columns, got %d", len(users.Columns), len(clone.Columns))
	}

	origName := users.GetColumn("name")
	cloneName := clone.GetColumn("name")
	if cloneName.StrData[0] != origName.StrData[0] {
		t.Errorf("expected name %s, got %s", origName.StrData[0], cloneName.StrData[0])
	}

	origName.StrData[0] = "Modified"
	if cloneName.StrData[0] == "Modified" {
		t.Error("clone should be independent of original")
	}
}

func TestColumn_Clone(t *testing.T) {
	users := makeUsersTable()
	orig := users.GetColumn("name")
	clone := orig.Clone()

	if clone.Name != orig.Name {
		t.Errorf("expected name %s, got %s", orig.Name, clone.Name)
	}
	if clone.Length != orig.Length {
		t.Errorf("expected length %d, got %d", orig.Length, clone.Length)
	}

	if clone.StrData[0] != orig.StrData[0] {
		t.Errorf("expected value %s, got %s", orig.StrData[0], clone.StrData[0])
	}

	orig.StrData[0] = "Modified"
	if clone.StrData[0] == "Modified" {
		t.Error("clone should be independent of original")
	}
}

func TestTable_AppendColumnsFrom(t *testing.T) {
	users := makeUsersTable()
	orders := makeOrdersTable()

	usersClone := users.Clone()
	usersClone.AppendColumnsFrom(orders, "orders")

	colNames := usersClone.ColumnNames()
	expectedNames := []string{"id", "name", "age", "orders.id", "orders.user_id", "orders.amount"}

	if len(colNames) != len(expectedNames) {
		t.Errorf("expected %d columns, got %d: %v", len(expectedNames), len(colNames), colNames)
	}

	for i, name := range expectedNames {
		if colNames[i] != name {
			t.Errorf("column %d: expected %s, got %s", i, name, colNames[i])
		}
	}

	amountCol := usersClone.GetColumn("orders.amount")
	if amountCol == nil {
		t.Fatal("orders.amount column not found")
	}
	if amountCol.FloatData[0] != 100.0 {
		t.Errorf("expected amount 100, got %v", amountCol.FloatData[0])
	}
}

func TestJoin_QualifiedColumnNames(t *testing.T) {
	users := makeUsersTable()
	orders := makeOrdersTable()

	tables := map[string]*store.Table{
		"users":  users,
		"orders": orders,
	}

	exec := NewExecutor(nil)
	stmt, err := NewParser().Parse(
		"SELECT users.name, orders.amount FROM orders JOIN users ON orders.user_id = users.id WHERE users.age > 28",
	)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	result, err := exec.ExecuteJoin(tables, stmt)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 3 {
		t.Errorf("expected 3 rows (age > 28), got %d", result.RowCount)
	}

	nameCol := result.GetColumn("users.name")
	if nameCol == nil {
		nameCol = result.GetColumn("name")
	}
	if nameCol == nil {
		t.Fatal("name column not found")
	}

	expectedNames := []string{"Alice", "Alice", "Charlie"}
	for i := 0; i < result.RowCount; i++ {
		if nameCol.StrData[i] != expectedNames[i] {
			t.Errorf("row %d: expected name %v, got %v", i, expectedNames[i], nameCol.StrData[i])
		}
	}
}

func TestJoin_InnerKeyword(t *testing.T) {
	users := makeUsersTable()
	orders := makeOrdersTable()

	tables := map[string]*store.Table{
		"users":  users,
		"orders": orders,
	}

	exec := NewExecutor(nil)
	stmt, err := NewParser().Parse(
		"SELECT * FROM orders INNER JOIN users ON orders.user_id = users.id",
	)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if len(stmt.Joins) != 1 {
		t.Fatalf("expected 1 join, got %d", len(stmt.Joins))
	}

	if stmt.Joins[0].JoinType != JoinInner {
		t.Errorf("expected INNER join type, got %v", stmt.Joins[0].JoinType)
	}

	result, err := exec.ExecuteJoin(tables, stmt)
	if err != nil {
		t.Fatalf("execute error: %v", err)
	}

	if result.RowCount != 4 {
		t.Errorf("expected 4 rows, got %d", result.RowCount)
	}
}
