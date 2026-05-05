package comparison

import (
	"database/sql"
	"fmt"
	"sort"
	"strings"

	"dbmigrator/internal/database"
)

type ColumnInfo struct {
	Name         string
	DataType     string
	Nullable     bool
	Default      sql.NullString
	IsPrimaryKey bool
	Extra        string
}

type TableInfo struct {
	Name    string
	Columns []*ColumnInfo
}

type IndexInfo struct {
	Name       string
	TableName  string
	Columns    []string
	IsUnique   bool
	IsPrimary  bool
}

type SchemaDiff struct {
	AddedTables   []string
	RemovedTables []string
	ModifiedTables map[string]*TableDiff
}

type TableDiff struct {
	TableName     string
	AddedColumns  []*ColumnInfo
	RemovedColumns []string
	ModifiedColumns map[string]*ColumnDiff
	AddedIndexes   []*IndexInfo
	RemovedIndexes []string
}

type ColumnDiff struct {
	ColumnName string
	OldType    string
	NewType    string
	OldNullable bool
	NewNullable bool
	OldDefault sql.NullString
	NewDefault sql.NullString
}

type Comparator struct {
	db *database.DBConnection
}

func NewComparator(db *database.DBConnection) *Comparator {
	return &Comparator{
		db: db,
	}
}

func (c *Comparator) GetCurrentSchema() ([]*TableInfo, error) {
	switch c.db.Driver() {
	case "mysql":
		return c.getMySQLSchema()
	case "postgres":
		return c.getPostgreSQLSchema()
	default:
		return nil, fmt.Errorf("unsupported driver for schema comparison: %s", c.db.Driver())
	}
}

func (c *Comparator) getMySQLSchema() ([]*TableInfo, error) {
	tablesQuery := `
		SELECT table_name 
		FROM information_schema.tables 
		WHERE table_schema = DATABASE() 
		AND table_type = 'BASE TABLE'
		ORDER BY table_name
	`

	rows, err := c.db.Query(tablesQuery)
	if err != nil {
		return nil, fmt.Errorf("failed to query tables: %w", err)
	}
	defer rows.Close()

	tables := make([]*TableInfo, 0)
	for rows.Next() {
		var tableName string
		if err := rows.Scan(&tableName); err != nil {
			return nil, fmt.Errorf("failed to scan table name: %w", err)
		}

		columns, err := c.getMySQLTableColumns(tableName)
		if err != nil {
			return nil, err
		}

		tables = append(tables, &TableInfo{
			Name:    tableName,
			Columns: columns,
		})
	}

	return tables, nil
}

func (c *Comparator) getMySQLTableColumns(tableName string) ([]*ColumnInfo, error) {
	query := `
		SELECT 
			c.column_name,
			c.column_type,
			c.is_nullable,
			c.column_default,
			c.extra,
			CASE WHEN k.constraint_name = 'PRIMARY' THEN 'YES' ELSE 'NO' END as is_primary
		FROM information_schema.columns c
		LEFT JOIN information_schema.key_column_usage k 
			ON c.table_schema = k.table_schema 
			AND c.table_name = k.table_name 
			AND c.column_name = k.column_name
			AND k.constraint_name = 'PRIMARY'
		WHERE c.table_schema = DATABASE() 
		AND c.table_name = ?
		ORDER BY c.ordinal_position
	`

	rows, err := c.db.Query(query, tableName)
	if err != nil {
		return nil, fmt.Errorf("failed to query columns for table %s: %w", tableName, err)
	}
	defer rows.Close()

	columns := make([]*ColumnInfo, 0)
	for rows.Next() {
		col := &ColumnInfo{}
		var isNullable, isPrimary string
		var defaultVal sql.NullString

		if err := rows.Scan(
			&col.Name,
			&col.DataType,
			&isNullable,
			&defaultVal,
			&col.Extra,
			&isPrimary,
		); err != nil {
			return nil, fmt.Errorf("failed to scan column: %w", err)
		}

		col.Nullable = strings.ToUpper(isNullable) == "YES"
		col.IsPrimaryKey = strings.ToUpper(isPrimary) == "YES"
		col.Default = defaultVal

		columns = append(columns, col)
	}

	return columns, nil
}

func (c *Comparator) getPostgreSQLSchema() ([]*TableInfo, error) {
	tablesQuery := `
		SELECT table_name 
		FROM information_schema.tables 
		WHERE table_schema = 'public' 
		AND table_type = 'BASE TABLE'
		ORDER BY table_name
	`

	rows, err := c.db.Query(tablesQuery)
	if err != nil {
		return nil, fmt.Errorf("failed to query tables: %w", err)
	}
	defer rows.Close()

	tables := make([]*TableInfo, 0)
	for rows.Next() {
		var tableName string
		if err := rows.Scan(&tableName); err != nil {
			return nil, fmt.Errorf("failed to scan table name: %w", err)
		}

		columns, err := c.getPostgreSQLTableColumns(tableName)
		if err != nil {
			return nil, err
		}

		tables = append(tables, &TableInfo{
			Name:    tableName,
			Columns: columns,
		})
	}

	return tables, nil
}

func (c *Comparator) getPostgreSQLTableColumns(tableName string) ([]*ColumnInfo, error) {
	query := `
		SELECT 
			c.column_name,
			CASE 
				WHEN c.character_maximum_length IS NOT NULL 
				THEN c.data_type || '(' || c.character_maximum_length || ')'
				WHEN c.numeric_precision IS NOT NULL AND c.numeric_scale IS NOT NULL
				THEN c.data_type || '(' || c.numeric_precision || ',' || c.numeric_scale || ')'
				ELSE c.data_type
			END as column_type,
			c.is_nullable,
			c.column_default,
			CASE WHEN pk.constraint_name IS NOT NULL THEN 'YES' ELSE 'NO' END as is_primary
		FROM information_schema.columns c
		LEFT JOIN (
			SELECT ku.table_name, ku.column_name, tc.constraint_name
			FROM information_schema.table_constraints tc
			JOIN information_schema.key_column_usage ku 
				ON tc.constraint_name = ku.constraint_name
			WHERE tc.constraint_type = 'PRIMARY KEY'
				AND tc.table_schema = 'public'
		) pk ON c.table_name = pk.table_name AND c.column_name = pk.column_name
		WHERE c.table_schema = 'public' 
		AND c.table_name = $1
		ORDER BY c.ordinal_position
	`

	rows, err := c.db.Query(query, tableName)
	if err != nil {
		return nil, fmt.Errorf("failed to query columns for table %s: %w", tableName, err)
	}
	defer rows.Close()

	columns := make([]*ColumnInfo, 0)
	for rows.Next() {
		col := &ColumnInfo{}
		var isNullable, isPrimary string
		var defaultVal sql.NullString

		if err := rows.Scan(
			&col.Name,
			&col.DataType,
			&isNullable,
			&defaultVal,
			&isPrimary,
		); err != nil {
			return nil, fmt.Errorf("failed to scan column: %w", err)
		}

		col.Nullable = strings.ToUpper(isNullable) == "YES"
		col.IsPrimaryKey = strings.ToUpper(isPrimary) == "YES"
		col.Default = defaultVal

		columns = append(columns, col)
	}

	return columns, nil
}

func (c *Comparator) CompareSchemas(currentTables []*TableInfo, expectedTables []*TableInfo) *SchemaDiff {
	currentMap := make(map[string]*TableInfo)
	expectedMap := make(map[string]*TableInfo)

	for _, t := range currentTables {
		currentMap[t.Name] = t
	}
	for _, t := range expectedTables {
		expectedMap[t.Name] = t
	}

	diff := &SchemaDiff{
		AddedTables:    make([]string, 0),
		RemovedTables:  make([]string, 0),
		ModifiedTables: make(map[string]*TableDiff),
	}

	for name := range expectedMap {
		if _, exists := currentMap[name]; !exists {
			diff.AddedTables = append(diff.AddedTables, name)
		}
	}

	for name := range currentMap {
		if _, exists := expectedMap[name]; !exists {
			diff.RemovedTables = append(diff.RemovedTables, name)
		}
	}

	for name, expectedTable := range expectedMap {
		if currentTable, exists := currentMap[name]; exists {
			tableDiff := c.compareTables(currentTable, expectedTable)
			if tableDiff != nil {
				diff.ModifiedTables[name] = tableDiff
			}
		}
	}

	sort.Strings(diff.AddedTables)
	sort.Strings(diff.RemovedTables)

	return diff
}

func (c *Comparator) compareTables(current *TableInfo, expected *TableInfo) *TableDiff {
	currentCols := make(map[string]*ColumnInfo)
	expectedCols := make(map[string]*ColumnInfo)

	for _, col := range current.Columns {
		currentCols[col.Name] = col
	}
	for _, col := range expected.Columns {
		expectedCols[col.Name] = col
	}

	diff := &TableDiff{
		TableName:        current.Name,
		AddedColumns:     make([]*ColumnInfo, 0),
		RemovedColumns:   make([]string, 0),
		ModifiedColumns:  make(map[string]*ColumnDiff),
		AddedIndexes:     make([]*IndexInfo, 0),
		RemovedIndexes:   make([]string, 0),
	}

	for name, expectedCol := range expectedCols {
		if _, exists := currentCols[name]; !exists {
			diff.AddedColumns = append(diff.AddedColumns, expectedCol)
		}
	}

	for name := range currentCols {
		if _, exists := expectedCols[name]; !exists {
			diff.RemovedColumns = append(diff.RemovedColumns, name)
		}
	}

	for name, expectedCol := range expectedCols {
		if currentCol, exists := currentCols[name]; exists {
			colDiff := c.compareColumns(currentCol, expectedCol)
			if colDiff != nil {
				diff.ModifiedColumns[name] = colDiff
			}
		}
	}

	if len(diff.AddedColumns) > 0 || len(diff.RemovedColumns) > 0 || len(diff.ModifiedColumns) > 0 {
		return diff
	}

	return nil
}

func (c *Comparator) compareColumns(current *ColumnInfo, expected *ColumnInfo) *ColumnDiff {
	hasDiff := false
	diff := &ColumnDiff{
		ColumnName:  current.Name,
		OldType:     current.DataType,
		NewType:     expected.DataType,
		OldNullable: current.Nullable,
		NewNullable: expected.Nullable,
		OldDefault:  current.Default,
		NewDefault:  expected.Default,
	}

	if current.DataType != expected.DataType {
		hasDiff = true
	}

	if current.Nullable != expected.Nullable {
		hasDiff = true
	}

	if current.Default.String != expected.Default.String {
		hasDiff = true
	}

	if hasDiff {
		return diff
	}

	return nil
}

func (c *Comparator) PrintSchemaDiff(diff *SchemaDiff) {
	if len(diff.AddedTables) == 0 && len(diff.RemovedTables) == 0 && len(diff.ModifiedTables) == 0 {
		fmt.Println("No schema differences found.")
		return
	}

	fmt.Println("\nSchema Differences:")
	fmt.Println("====================")

	if len(diff.AddedTables) > 0 {
		fmt.Println("\n+ Added Tables:")
		for _, table := range diff.AddedTables {
			fmt.Printf("  - %s\n", table)
		}
	}

	if len(diff.RemovedTables) > 0 {
		fmt.Println("\n- Removed Tables:")
		for _, table := range diff.RemovedTables {
			fmt.Printf("  - %s\n", table)
		}
	}

	if len(diff.ModifiedTables) > 0 {
		fmt.Println("\n~ Modified Tables:")
		for tableName, tableDiff := range diff.ModifiedTables {
			fmt.Printf("  - %s:\n", tableName)

			if len(tableDiff.AddedColumns) > 0 {
				fmt.Println("    + Added Columns:")
				for _, col := range tableDiff.AddedColumns {
					fmt.Printf("      - %s (%s)\n", col.Name, col.DataType)
				}
			}

			if len(tableDiff.RemovedColumns) > 0 {
				fmt.Println("    - Removed Columns:")
				for _, col := range tableDiff.RemovedColumns {
					fmt.Printf("      - %s\n", col)
				}
			}

			if len(tableDiff.ModifiedColumns) > 0 {
				fmt.Println("    ~ Modified Columns:")
				for colName, colDiff := range tableDiff.ModifiedColumns {
					fmt.Printf("      - %s:\n", colName)
					if colDiff.OldType != colDiff.NewType {
						fmt.Printf("        Type: %s -> %s\n", colDiff.OldType, colDiff.NewType)
					}
					if colDiff.OldNullable != colDiff.NewNullable {
						fmt.Printf("        Nullable: %v -> %v\n", colDiff.OldNullable, colDiff.NewNullable)
					}
				}
			}
		}
	}
}
