package metadata

import "time"

type DataSourceType string

const (
	SourcePostgreSQL DataSourceType = "postgresql"
	SourceMySQL      DataSourceType = "mysql"
	SourceMongoDB    DataSourceType = "mongodb"
	SourceCSV        DataSourceType = "csv"
)

type DataSourceConfig struct {
	Type     DataSourceType `json:"type"`
	Host     string         `json:"host"`
	Port     int            `json:"port"`
	Database string         `json:"database"`
	User     string         `json:"user"`
	Password string         `json:"password"`
	DSN      string         `json:"dsn,omitempty"`
}

type ColumnSchema struct {
	Name         string      `json:"name"`
	Type         string      `json:"type"`
	Nullable     bool        `json:"nullable"`
	PrimaryKey   bool        `json:"primary_key"`
	DefaultValue interface{} `json:"default_value,omitempty"`
	Comment      string      `json:"comment,omitempty"`
}

type TableSchema struct {
	Name       string                 `json:"name"`
	Columns    []ColumnSchema         `json:"columns"`
	RowCount   int64                  `json:"row_count"`
	SizeBytes  int64                  `json:"size_bytes"`
	Comment    string                 `json:"comment,omitempty"`
	SampleData []map[string]interface{} `json:"sample_data,omitempty"`
	Statistics *TableStatistics       `json:"statistics,omitempty"`
}

type TableStatistics struct {
	ColumnStats  map[string]ColumnStats `json:"column_stats"`
	LastAnalyzed time.Time              `json:"last_analyzed"`
}

type ColumnStats struct {
	DistinctCount int64       `json:"distinct_count"`
	NullCount     int64       `json:"null_count"`
	MinValue      interface{} `json:"min_value,omitempty"`
	MaxValue      interface{} `json:"max_value,omitempty"`
	AvgLength     float64     `json:"avg_length,omitempty"`
	TopValues     []TopValue  `json:"top_values,omitempty"`
}

type TopValue struct {
	Value interface{} `json:"value"`
	Count int64       `json:"count"`
}

type SchemaMetadata struct {
	DataSourceID string        `json:"data_source_id"`
	Tables       []TableSchema `json:"tables"`
	CollectedAt  time.Time     `json:"collected_at"`
	DurationMs   int64         `json:"duration_ms"`
}
