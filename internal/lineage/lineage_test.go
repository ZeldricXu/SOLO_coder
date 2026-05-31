package lineage

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/datatrace/datatrace/internal/common/testbuilder"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestParseSQL_NormalPath(t *testing.T) {
	cases := testbuilder.NewLineageTestDataBuilder().
		WithSimpleSelect().
		WithSelectJoin().
		WithInsertSelect().
		WithComplexETL().
		Build()

	for _, tc := range cases {
		t.Run(tc.Name, func(t *testing.T) {
			parser := NewLineageParser()
			tables, lineages, err := parser.ParseSQL(tc.SQL)

			if tc.ShouldError {
				assert.Error(t, err)
				assert.Nil(t, tables)
				assert.Nil(t, lineages)
				return
			}

			require.NoError(t, err)
			assert.Equal(t, len(tc.ExpectTables), len(tables))

			tableNames := make([]string, 0, len(tables))
			for _, tbl := range tables {
				tableNames = append(tableNames, tbl.Name)
			}

			for _, expected := range tc.ExpectTables {
				assert.Contains(t, tableNames, expected, "expected table %s not found", expected)
			}

			if tc.ExpectEdges > 0 {
				assert.Equal(t, tc.ExpectEdges, len(lineages))
			}
		})
	}
}

func TestParseSQL_BoundaryInputs(t *testing.T) {
	parser := NewLineageParser()

	t.Run("Empty SQL", func(t *testing.T) {
		tables, lineages, err := parser.ParseSQL("")
		assert.Error(t, err)
		assert.Nil(t, tables)
		assert.Nil(t, lineages)
	})

	t.Run("Whitespace only", func(t *testing.T) {
		tables, lineages, err := parser.ParseSQL("   \n\t\r  ")
		assert.Error(t, err)
		assert.Nil(t, tables)
		assert.Nil(t, lineages)
	})

	t.Run("Very long SQL", func(t *testing.T) {
		longSQL := "SELECT " + strings.Repeat("col, ", 1000) + "col1001 FROM very_long_table_name_that_is_quite_extensive"
		tables, lineages, err := parser.ParseSQL(longSQL)
		assert.NoError(t, err)
		assert.NotNil(t, tables)
		assert.Equal(t, 1, len(tables))
		assert.Equal(t, "very_long_table_name_that_is_quite_extensive", tables[0].Name)
	})

	t.Run("SQL with special characters", func(t *testing.T) {
		sql := `SELECT * FROM "schema"."table-with-dashes" WHERE name = 'O''Brien'`
		tables, _, err := parser.ParseSQL(sql)
		assert.NoError(t, err)
		assert.NotNil(t, tables)
	})

	t.Run("UPDATE statement", func(t *testing.T) {
		tables, _, err := parser.ParseSQL("UPDATE users SET name = 'test' WHERE id = 1")
		assert.NoError(t, err)
		assert.Equal(t, 1, len(tables))
		assert.Equal(t, "users", tables[0].Name)
	})

	t.Run("CREATE TABLE AS SELECT", func(t *testing.T) {
		tables, lineages, err := parser.ParseSQL("CREATE TABLE new_table AS SELECT * FROM old_table")
		assert.NoError(t, err)
		assert.Equal(t, 2, len(tables))
		assert.Equal(t, 1, len(lineages))
	})
}

func TestParseSQL_FieldExtraction(t *testing.T) {
	parser := NewLineageParser()

	t.Run("Extract specific fields", func(t *testing.T) {
		sql := "SELECT id, name, email, created_at FROM users"
		tables, _, err := parser.ParseSQL(sql)
		require.NoError(t, err)
		require.Equal(t, 1, len(tables))
		assert.Contains(t, tables[0].Fields, "id")
		assert.Contains(t, tables[0].Fields, "name")
		assert.Contains(t, tables[0].Fields, "email")
		assert.Contains(t, tables[0].Fields, "created_at")
	})

	t.Run("Handle wildcard", func(t *testing.T) {
		sql := "SELECT * FROM users"
		tables, _, err := parser.ParseSQL(sql)
		require.NoError(t, err)
		require.Equal(t, 1, len(tables))
		assert.Empty(t, tables[0].Fields)
	})

	t.Run("Handle aliases", func(t *testing.T) {
		sql := "SELECT u.id AS user_id, u.name AS user_name FROM users u"
		tables, _, err := parser.ParseSQL(sql)
		require.NoError(t, err)
		require.Equal(t, 1, len(tables))
		assert.Contains(t, tables[0].Fields, "u.id")
	})
}

func TestBuildDAG(t *testing.T) {
	parser := NewLineageParser()

	t.Run("Build simple DAG", func(t *testing.T) {
		sql := `INSERT INTO summary
			SELECT u.id, COUNT(o.id)
			FROM users u
			JOIN orders o ON u.id = o.user_id
			GROUP BY u.id`

		tables, lineages, err := parser.ParseSQL(sql)
		require.NoError(t, err)

		graph, err := parser.BuildDAG(tables, lineages)
		require.NoError(t, err)
		require.NotNil(t, graph)

		assert.Equal(t, 3, graph.NodeCount())
		assert.Equal(t, 2, graph.EdgeCount())
	})

	t.Run("Build DAG with duplicate tables", func(t *testing.T) {
		tables := []TableNode{
			{Name: "users", Database: "public"},
			{Name: "users", Database: "public"},
			{Name: "orders", Database: "public"},
		}

		graph, err := parser.BuildDAG(tables, nil)
		require.NoError(t, err)
		assert.Equal(t, 2, graph.NodeCount())
	})
}

func TestDAGTopologicalSort(t *testing.T) {
	parser := NewLineageParser()

	t.Run("Valid DAG sort", func(t *testing.T) {
		sql := `INSERT INTO analytics.report
			SELECT * FROM staging.processed
			JOIN raw.users ON raw.users.id = staging.processed.user_id`

		tables, lineages, err := parser.ParseSQL(sql)
		require.NoError(t, err)

		graph, err := parser.BuildDAG(tables, lineages)
		require.NoError(t, err)

		order, err := graph.TopologicalSort()
		require.NoError(t, err)
		assert.Equal(t, graph.NodeCount(), len(order))
	})

	t.Run("Cycle detection", func(t *testing.T) {
		tables := []TableNode{
			{Name: "a", Database: "db"},
			{Name: "b", Database: "db"},
			{Name: "c", Database: "db"},
		}

		lineages := []FieldLineage{
			{SourceTable: "a", TargetTable: "b"},
			{SourceTable: "b", TargetTable: "c"},
			{SourceTable: "c", TargetTable: "a"},
		}

		graph, err := parser.BuildDAG(tables, lineages)
		require.NoError(t, err)

		_, err = graph.TopologicalSort()
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "cycle detected")
	})
}

func TestGetLineage(t *testing.T) {
	parser := NewLineageParser()

	setupTestDAG(t, parser)

	t.Run("Get existing table lineage", func(t *testing.T) {
		node, upstream, downstream := parser.GetLineage("orders")
		require.NotNil(t, node)
		assert.Equal(t, "orders", node.Table)
		assert.NotNil(t, upstream)
		assert.NotNil(t, downstream)
	})

	t.Run("Get non-existent table lineage", func(t *testing.T) {
		node, upstream, downstream := parser.GetLineage("non_existent_table")
		assert.Nil(t, node)
		assert.Nil(t, upstream)
		assert.Nil(t, downstream)
	})
}

func TestLineageParser_ConcurrentOperations(t *testing.T) {
	parser := NewLineageParser()
	const goroutines = 50
	const operations = 100

	var wg sync.WaitGroup
	wg.Add(goroutines)

	for i := 0; i < goroutines; i++ {
		go func(id int) {
			defer wg.Done()
			for j := 0; j < operations; j++ {
				sql := "SELECT * FROM users"
				tables, lineages, err := parser.ParseSQL(sql)
				assert.NoError(t, err)
				assert.NotNil(t, tables)

				_, _ = parser.BuildDAG(tables, lineages)

				node, up, down := parser.GetLineage("users")
				assert.NotNil(t, node)
				assert.NotNil(t, up)
				assert.NotNil(t, down)
			}
		}(i)
	}

	wg.Wait()
}

func TestLineageParser_ConcurrentParseAndBuild(t *testing.T) {
	parser := NewLineageParser()

	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		for i := 0; i < 1000; i++ {
			_, _, _ = parser.ParseSQL("SELECT * FROM table_a JOIN table_b ON table_a.id = table_b.a_id")
		}
	}()

	go func() {
		defer wg.Done()
		for i := 0; i < 1000; i++ {
			tables := []TableNode{
				{Name: "t1", Database: "db"},
				{Name: "t2", Database: "db"},
			}
			lineages := []FieldLineage{
				{SourceTable: "t1", TargetTable: "t2"},
			}
			_, _ = parser.BuildDAG(tables, lineages)
		}
	}()

	wg.Wait()
}

func TestSQLParserInterface(t *testing.T) {
	customParser := &mockSQLParser{
		returnError: false,
		tables: []TableNode{
			{Name: "custom_table", Database: "custom_db"},
		},
	}

	parser := NewLineageParserWithParser(customParser)

	tables, lineages, err := parser.ParseSQL("custom query")
	assert.NoError(t, err)
	assert.Equal(t, 1, len(tables))
	assert.Equal(t, "custom_table", tables[0].Name)
	assert.Empty(t, lineages)
}

func TestToEntity(t *testing.T) {
	parser := NewLineageParser()
	entity := parser.ToEntity()

	assert.NotNil(t, entity)
	assert.Equal(t, "lineage_dag", entity.Type)
	assert.Equal(t, "active", entity.Status)
	assert.NotEmpty(t, entity.ID)
	assert.False(t, entity.CreatedAt.IsZero())
	assert.False(t, entity.UpdatedAt.IsZero())
}

func TestDAGGraph_GetNodes(t *testing.T) {
	parser := NewLineageParser()
	tables := []TableNode{
		{Name: "a", Database: "db"},
		{Name: "b", Database: "db"},
		{Name: "c", Database: "db"},
	}

	graph, err := parser.BuildDAG(tables, nil)
	require.NoError(t, err)

	nodes := graph.GetNodes()
	assert.Equal(t, 3, len(nodes))
}

type mockSQLParser struct {
	returnError bool
	tables      []TableNode
	lineages    []FieldLineage
}

func (m *mockSQLParser) Parse(sql string) ([]TableNode, []FieldLineage, error) {
	if m.returnError {
		return nil, nil, assert.AnError
	}
	return m.tables, m.lineages, nil
}

func setupTestDAG(t *testing.T, parser *LineageParser) {
	sql := `INSERT INTO summary
		SELECT u.id, o.amount
		FROM users u
		JOIN orders o ON u.id = o.user_id`

	tables, lineages, err := parser.ParseSQL(sql)
	require.NoError(t, err)

	_, err = parser.BuildDAG(tables, lineages)
	require.NoError(t, err)
}

func TestRegexSQLParser_Performance(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping performance test in short mode")
	}

	parser := NewRegexSQLParser()
	sql := "SELECT id, name, email FROM users JOIN orders ON users.id = orders.user_id WHERE active = true"

	start := time.Now()
	for i := 0; i < 10000; i++ {
		_, _, _ = parser.Parse(sql)
	}
	elapsed := time.Since(start)

	t.Logf("Parsed 10000 SQL statements in %v", elapsed)
	assert.Less(t, elapsed, 5*time.Second)
}

func TestRegexSQLParser_ConcurrentParse(t *testing.T) {
	parser := NewRegexSQLParser()
	var wg sync.WaitGroup
	const goroutines = 100

	wg.Add(goroutines)
	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				_, _, _ = parser.Parse("SELECT * FROM test_table")
			}
		}()
	}

	wg.Wait()
}
