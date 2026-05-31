package metadata

import (
	"context"
	"database/sql"
	"fmt"
	"session154/internal/logger"

	"go.uber.org/zap"
)

type SchemaExtractor interface {
	ListTables(ctx context.Context, db *sql.DB, sourceType DataSourceType) ([]string, error)
	GetColumns(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string) ([]ColumnSchema, error)
	GetTableStats(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string) (rowCount int64, sizeBytes int64, err error)
	GetSampleData(ctx context.Context, db *sql.DB, tableName string, limit int) ([]map[string]interface{}, error)
	GetColumnStatistics(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string, columns []ColumnSchema) (map[string]ColumnStats, error)
}

type StandardExtractor struct{}

func NewStandardExtractor() SchemaExtractor {
	return &StandardExtractor{}
}

func (e *StandardExtractor) ListTables(ctx context.Context, db *sql.DB, sourceType DataSourceType) ([]string, error) {
	query, err := buildListTablesQuery(sourceType)
	if err != nil {
		return nil, err
	}

	rows, err := db.QueryContext(ctx, query)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var tables []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			return nil, err
		}
		tables = append(tables, name)
	}
	return tables, rows.Err()
}

func (e *StandardExtractor) GetColumns(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string) ([]ColumnSchema, error) {
	query, err := buildColumnsQuery(sourceType)
	if err != nil {
		return nil, err
	}

	rows, err := db.QueryContext(ctx, query, tableName)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var columns []ColumnSchema
	for rows.Next() {
		var col ColumnSchema
		var defaultValue sql.NullString
		var comment sql.NullString

		if err := rows.Scan(&col.Name, &col.Type, &col.Nullable, &col.PrimaryKey, &defaultValue, &comment); err != nil {
			return nil, err
		}

		if defaultValue.Valid {
			col.DefaultValue = defaultValue.String
		}
		if comment.Valid {
			col.Comment = comment.String
		}
		columns = append(columns, col)
	}
	return columns, rows.Err()
}

func (e *StandardExtractor) GetTableStats(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string) (int64, int64, error) {
	query, err := buildTableStatsQuery(sourceType)
	if err != nil {
		return 0, 0, err
	}

	var rowCount, sizeBytes int64
	err = db.QueryRowContext(ctx, query, tableName).Scan(&rowCount, &sizeBytes)
	return rowCount, sizeBytes, err
}

func (e *StandardExtractor) GetSampleData(ctx context.Context, db *sql.DB, tableName string, limit int) ([]map[string]interface{}, error) {
	query := fmt.Sprintf(`SELECT * FROM %s LIMIT %d`, quoteIdentifier(tableName), limit)
	rows, err := db.QueryContext(ctx, query)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	columns, err := rows.Columns()
	if err != nil {
		return nil, err
	}

	var sampleData []map[string]interface{}
	for rows.Next() {
		values := make([]interface{}, len(columns))
		valuePtrs := make([]interface{}, len(columns))
		for i := range values {
			valuePtrs[i] = &values[i]
		}

		if err := rows.Scan(valuePtrs...); err != nil {
			continue
		}

		row := make(map[string]interface{})
		for i, col := range columns {
			row[col] = normalizeValue(values[i])
		}
		sampleData = append(sampleData, row)
	}
	return sampleData, nil
}

func (e *StandardExtractor) GetColumnStatistics(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string, columns []ColumnSchema) (map[string]ColumnStats, error) {
	stats := make(map[string]ColumnStats)

	for _, col := range columns {
		colStat, err := e.analyzeColumn(ctx, db, tableName, col)
		if err != nil {
			logger.Warn("failed to analyze column", zap.String("column", col.Name), zap.Error(err))
			continue
		}
		stats[col.Name] = colStat
	}
	return stats, nil
}

func (e *StandardExtractor) analyzeColumn(ctx context.Context, db *sql.DB, tableName string, col ColumnSchema) (ColumnStats, error) {
	var stat ColumnStats
	if !isAnalyzableType(col.Type) {
		return stat, nil
	}

	quotedCol := quoteIdentifier(col.Name)
	quotedTable := quoteIdentifier(tableName)

	countQuery := fmt.Sprintf(`SELECT COUNT(DISTINCT %s), COUNT(*) - COUNT(%s) FROM %s`,
		quotedCol, quotedCol, quotedTable)
	if err := db.QueryRowContext(ctx, countQuery).Scan(&stat.DistinctCount, &stat.NullCount); err != nil {
		return stat, err
	}

	if isNumericType(col.Type) {
		minMaxQuery := fmt.Sprintf(`SELECT MIN(%s), MAX(%s) FROM %s`, quotedCol, quotedCol, quotedTable)
		var minVal, maxVal sql.NullFloat64
		if err := db.QueryRowContext(ctx, minMaxQuery).Scan(&minVal, &maxVal); err == nil {
			if minVal.Valid {
				stat.MinValue = minVal.Float64
			}
			if maxVal.Valid {
				stat.MaxValue = maxVal.Float64
			}
		}
	}

	topValues, _ := e.getTopValues(ctx, db, tableName, col)
	stat.TopValues = topValues
	return stat, nil
}

func (e *StandardExtractor) getTopValues(ctx context.Context, db *sql.DB, tableName string, col ColumnSchema) ([]TopValue, error) {
	quotedCol := quoteIdentifier(col.Name)
	quotedTable := quoteIdentifier(tableName)
	query := fmt.Sprintf(`SELECT %s, COUNT(*) as cnt FROM %s WHERE %s IS NOT NULL GROUP BY %s ORDER BY cnt DESC LIMIT 10`,
		quotedCol, quotedTable, quotedCol, quotedCol)

	rows, err := db.QueryContext(ctx, query)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var topValues []TopValue
	for rows.Next() {
		var val interface{}
		var count int64
		if err := rows.Scan(&val, &count); err != nil {
			continue
		}
		topValues = append(topValues, TopValue{Value: normalizeValue(val), Count: count})
	}
	return topValues, nil
}

func buildListTablesQuery(sourceType DataSourceType) (string, error) {
	switch sourceType {
	case SourcePostgreSQL:
		return "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'", nil
	case SourceMySQL:
		return "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", nil
	default:
		return "", fmt.Errorf("unsupported data source type")
	}
}

func buildColumnsQuery(sourceType DataSourceType) (string, error) {
	switch sourceType {
	case SourcePostgreSQL:
		return `
			SELECT 
				c.column_name, 
				c.data_type, 
				c.is_nullable = 'YES',
				COALESCE(pk.is_primary, false),
				c.column_default,
				COALESCE(pgd.description, '')
			FROM information_schema.columns c
			LEFT JOIN (
				SELECT kcu.column_name, true as is_primary
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu 
					ON tc.constraint_name = kcu.constraint_name
				WHERE tc.table_name = $1 AND tc.constraint_type = 'PRIMARY KEY'
			) pk ON c.column_name = pk.column_name
			LEFT JOIN pg_catalog.pg_description pgd
				ON pgd.objoid = (SELECT oid FROM pg_class WHERE relname = $1)
				AND pgd.objsubid = c.ordinal_position
			WHERE c.table_name = $1
			ORDER BY c.ordinal_position`, nil
	case SourceMySQL:
		return `
			SELECT 
				column_name, 
				data_type, 
				is_nullable = 'YES',
				column_key = 'PRI',
				column_default,
				column_comment
			FROM information_schema.columns 
			WHERE table_name = ? 
			ORDER BY ordinal_position`, nil
	default:
		return "", fmt.Errorf("unsupported data source type")
	}
}

func buildTableStatsQuery(sourceType DataSourceType) (string, error) {
	switch sourceType {
	case SourcePostgreSQL:
		return `
			SELECT 
				c.reltuples::bigint,
				pg_total_relation_size(c.oid)
			FROM pg_class c
			WHERE c.relname = $1`, nil
	case SourceMySQL:
		return `
			SELECT 
				table_rows,
				data_length + index_length
			FROM information_schema.tables 
			WHERE table_name = ? AND table_schema = DATABASE()`, nil
	default:
		return "", fmt.Errorf("unsupported data source type")
	}
}

func isAnalyzableType(dataType string) bool {
	switch dataType {
	case "text", "varchar", "char", "int", "integer", "bigint", "smallint", "numeric", "float", "boolean", "date", "timestamp", "timestamptz":
		return true
	default:
		return false
	}
}

func isNumericType(dataType string) bool {
	switch dataType {
	case "int", "integer", "bigint", "smallint", "numeric", "float":
		return true
	default:
		return false
	}
}

func quoteIdentifier(name string) string {
	return fmt.Sprintf(`"%s"`, name)
}

func normalizeValue(val interface{}) interface{} {
	if b, ok := val.([]byte); ok {
		return string(b)
	}
	return val
}
