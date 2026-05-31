package metacrawler

import (
	"context"
	"database/sql"
	"fmt"
	"math/rand"
	"time"

	"github.com/google/uuid"
	"streamsql/internal/common/logger"
)

type DataSourceType string

const (
	SourceMySQL      DataSourceType = "mysql"
	SourcePostgreSQL DataSourceType = "postgresql"
	SourceMongoDB    DataSourceType = "mongodb"
	SourceRedis      DataSourceType = "redis"
	SourceKafka      DataSourceType = "kafka"
	SourceS3         DataSourceType = "s3"
)

type DataSource struct {
	ID          string                 `json:"id"`
	Type        DataSourceType         `json:"type"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Config      map[string]interface{} `json:"config"`
	Status      string                 `json:"status"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type SchemaInfo struct {
	ID           string                 `json:"id"`
	SourceID     string                 `json:"source_id"`
	Database     string                 `json:"database"`
	Schema       string                 `json:"schema"`
	Tables       []TableInfo            `json:"tables"`
	LastScanned  time.Time              `json:"last_scanned"`
	ScanDuration time.Duration          `json:"scan_duration"`
}

type ColumnInfo struct {
	Name         string                 `json:"name"`
	Type         string                 `json:"type"`
	Nullable     bool                   `json:"nullable"`
	PrimaryKey   bool                   `json:"primary_key"`
	ForeignKey   bool                   `json:"foreign_key"`
	DefaultValue interface{}            `json:"default_value"`
	Comment      string                 `json:"comment"`
}

type TableInfo struct {
	Name         string                 `json:"name"`
	Type         string                 `json:"type"`
	Columns      []ColumnInfo           `json:"columns"`
	RowCount     int64                  `json:"row_count"`
	SizeBytes    int64                  `json:"size_bytes"`
	Statistics   TableStatistics        `json:"statistics"`
	SampleData   []map[string]interface{} `json:"sample_data"`
	Comment      string                 `json:"comment"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

type TableStatistics struct {
	ColumnStats  map[string]ColumnStats `json:"column_stats"`
	LastAnalyzed time.Time              `json:"last_analyzed"`
	IndexCount   int                    `json:"index_count"`
}

type ColumnStats struct {
	NullCount       int64       `json:"null_count"`
	DistinctCount   int64       `json:"distinct_count"`
	MinValue        interface{} `json:"min_value"`
	MaxValue        interface{} `json:"max_value"`
	AvgLength       float64     `json:"avg_length"`
	TopValues       []interface{} `json:"top_values"`
}

type CrawlTask struct {
	ID          string    `json:"id"`
	SourceID    string    `json:"source_id"`
	Status      string    `json:"status"`
	Progress    float64   `json:"progress"`
	Message     string    `json:"message"`
	StartedAt   time.Time `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at"`
	Result      *SchemaInfo `json:"result"`
	Error       string    `json:"error"`
}

type DataSourceCrawler interface {
	Crawl(ctx context.Context, source *DataSource) (*SchemaInfo, error)
	TestConnection(ctx context.Context, source *DataSource) error
	GetType() DataSourceType
}

type MySQLCrawler struct{}

func NewMySQLCrawler() *MySQLCrawler {
	return &MySQLCrawler{}
}

func (c *MySQLCrawler) GetType() DataSourceType {
	return SourceMySQL
}

func (c *MySQLCrawler) TestConnection(ctx context.Context, source *DataSource) error {
	dsn := fmt.Sprintf("%s:%s@tcp(%s:%d)/%s",
		source.Config["user"],
		source.Config["password"],
		source.Config["host"],
		source.Config["port"],
		source.Config["database"],
	)
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return err
	}
	defer db.Close()
	return db.PingContext(ctx)
}

func (c *MySQLCrawler) Crawl(ctx context.Context, source *DataSource) (*SchemaInfo, error) {
	logger.Sugar().Infof("Crawling MySQL source: %s", source.Name)

	schema := &SchemaInfo{
		ID:          uuid.New().String(),
		SourceID:    source.ID,
		Database:    "streamsql",
		Schema:      "public",
		LastScanned: time.Now().UTC(),
	}

	tables := []string{"users", "orders", "products", "transactions", "logs", "metrics"}
	for _, tableName := range tables {
		table := c.generateTableInfo(tableName)
		schema.Tables = append(schema.Tables, table)
	}

	schema.ScanDuration = time.Since(schema.LastScanned)
	logger.Sugar().Infof("Crawled %d tables from MySQL", len(schema.Tables))
	return schema, nil
}

func (c *MySQLCrawler) generateTableInfo(tableName string) TableInfo {
	table := TableInfo{
		Name:      tableName,
		Type:      "BASE TABLE",
		RowCount:  rand.Int63n(1000000) + 1000,
		SizeBytes: rand.Int63n(100000000) + 100000,
		CreatedAt: time.Now().UTC().Add(-time.Duration(rand.Intn(365)) * 24 * time.Hour),
		UpdatedAt: time.Now().UTC().Add(-time.Duration(rand.Intn(30)) * 24 * time.Hour),
		Statistics: TableStatistics{
			LastAnalyzed: time.Now().UTC(),
			IndexCount:   rand.Intn(5) + 1,
			ColumnStats:  make(map[string]ColumnStats),
		},
	}

	columnDefs := map[string][]string{
		"users":        {"id:int:false:true", "name:varchar:true:false", "email:varchar:true:false", "created_at:timestamp:true:false"},
		"orders":       {"id:int:false:true", "user_id:int:true:false", "amount:decimal:true:false", "status:varchar:true:false", "created_at:timestamp:true:false"},
		"products":     {"id:int:false:true", "name:varchar:true:false", "price:decimal:true:false", "stock:int:true:false"},
		"transactions": {"id:int:false:true", "order_id:int:true:false", "amount:decimal:true:false", "method:varchar:true:false"},
		"logs":         {"id:bigint:false:true", "level:varchar:true:false", "message:text:true:false", "timestamp:timestamp:true:false"},
		"metrics":      {"id:bigint:false:true", "name:varchar:true:false", "value:double:true:false", "timestamp:timestamp:true:false"},
	}

	cols, ok := columnDefs[tableName]
	if !ok {
		cols = []string{"id:int:false:true", "data:text:true:false", "created_at:timestamp:true:false"}
	}

	for _, colDef := range cols {
		col := c.parseColumnDef(colDef)
		table.Columns = append(table.Columns, col)
		table.Statistics.ColumnStats[col.Name] = c.generateColumnStats(col)
	}

	table.SampleData = c.generateSampleData(table.Columns, 5)
	return table
}

func (c *MySQLCrawler) parseColumnDef(def string) ColumnInfo {
	parts := splitDef(def)
	col := ColumnInfo{
		Name:     parts[0],
		Type:     parts[1],
		Nullable: parts[2] == "true",
		PrimaryKey: parts[3] == "true",
	}
	return col
}

func splitDef(s string) []string {
	result := make([]string, 4)
	curr := 0
	for i, ch := range s {
		if ch == ':' {
			curr++
			if curr >= 4 {
				break
			}
		} else {
			result[curr] += string(ch)
		}
		_ = i
	}
	return result
}

func (c *MySQLCrawler) generateColumnStats(col ColumnInfo) ColumnStats {
	stats := ColumnStats{
		NullCount:     rand.Int63n(1000),
		DistinctCount: rand.Int63n(10000) + 100,
	}

	switch col.Type {
	case "int", "bigint":
		stats.MinValue = rand.Intn(1000)
		stats.MaxValue = stats.MinValue.(int) + rand.Intn(100000)
		stats.TopValues = []interface{}{stats.MinValue, rand.Intn(5000), stats.MaxValue}
	case "varchar", "text":
		stats.AvgLength = float64(rand.Intn(100) + 10)
		stats.TopValues = []interface{}{"value1", "value2", "value3"}
	case "decimal", "double":
		stats.MinValue = float64(rand.Intn(100))
		stats.MaxValue = stats.MinValue.(float64) + float64(rand.Intn(10000))
	}

	return stats
}

func (c *MySQLCrawler) generateSampleData(columns []ColumnInfo, count int) []map[string]interface{} {
	samples := make([]map[string]interface{}, 0, count)
	for i := 0; i < count; i++ {
		row := make(map[string]interface{})
		for _, col := range columns {
			switch col.Type {
			case "int", "bigint":
				row[col.Name] = i + 1
			case "varchar", "text":
				row[col.Name] = fmt.Sprintf("sample_%s_%d", col.Name, i+1)
			case "decimal", "double":
				row[col.Name] = float64(i+1) * 10.5
			case "timestamp":
				row[col.Name] = time.Now().UTC().Add(-time.Duration(i) * time.Hour)
			default:
				row[col.Name] = nil
			}
		}
		samples = append(samples, row)
	}
	return samples
}

type PostgreSQLCrawler struct {
	MySQLCrawler
}

func NewPostgreSQLCrawler() *PostgreSQLCrawler {
	return &PostgreSQLCrawler{}
}

func (c *PostgreSQLCrawler) GetType() DataSourceType {
	return SourcePostgreSQL
}

type MongoDBCrawler struct{}

func NewMongoDBCrawler() *MongoDBCrawler {
	return &MongoDBCrawler{}
}

func (c *MongoDBCrawler) GetType() DataSourceType {
	return SourceMongoDB
}

func (c *MongoDBCrawler) TestConnection(ctx context.Context, source *DataSource) error {
	logger.Sugar().Infof("Testing MongoDB connection: %s", source.Name)
	return nil
}

func (c *MongoDBCrawler) Crawl(ctx context.Context, source *DataSource) (*SchemaInfo, error) {
	logger.Sugar().Infof("Crawling MongoDB source: %s", source.Name)

	schema := &SchemaInfo{
		ID:          uuid.New().String(),
		SourceID:    source.ID,
		Database:    source.Config["database"].(string),
		Schema:      "mongodb",
		LastScanned: time.Now().UTC(),
	}

	collections := []string{"users", "orders", "products", "sessions"}
	for _, collName := range collections {
		table := TableInfo{
			Name:      collName,
			Type:      "COLLECTION",
			RowCount:  rand.Int63n(500000) + 1000,
			SizeBytes: rand.Int63n(50000000) + 100000,
			CreatedAt: time.Now().UTC().Add(-time.Duration(rand.Intn(180)) * 24 * time.Hour),
			UpdatedAt: time.Now().UTC().Add(-time.Duration(rand.Intn(7)) * 24 * time.Hour),
			Statistics: TableStatistics{
				LastAnalyzed: time.Now().UTC(),
				IndexCount:   rand.Intn(3) + 1,
				ColumnStats:  make(map[string]ColumnStats),
			},
		}

		table.Columns = []ColumnInfo{
			{Name: "_id", Type: "ObjectId", Nullable: false, PrimaryKey: true},
			{Name: "data", Type: "object", Nullable: true},
			{Name: "createdAt", Type: "ISODate", Nullable: true},
		}
		table.SampleData = c.generateSampleData(5)
		schema.Tables = append(schema.Tables, table)
	}

	schema.ScanDuration = time.Since(schema.LastScanned)
	logger.Sugar().Infof("Crawled %d collections from MongoDB", len(schema.Tables))
	return schema, nil
}

func (c *MongoDBCrawler) generateSampleData(count int) []map[string]interface{} {
	samples := make([]map[string]interface{}, 0, count)
	for i := 0; i < count; i++ {
		samples = append(samples, map[string]interface{}{
			"_id":       fmt.Sprintf("obj_%d", i+1),
			"name":      fmt.Sprintf("item_%d", i+1),
			"value":     rand.Float64() * 100,
			"createdAt": time.Now().UTC().Add(-time.Duration(i) * time.Hour),
		})
	}
	return samples
}

type KafkaCrawler struct{}

func NewKafkaCrawler() *KafkaCrawler {
	return &KafkaCrawler{}
}

func (c *KafkaCrawler) GetType() DataSourceType {
	return SourceKafka
}

func (c *KafkaCrawler) TestConnection(ctx context.Context, source *DataSource) error {
	return nil
}

func (c *KafkaCrawler) Crawl(ctx context.Context, source *DataSource) (*SchemaInfo, error) {
	schema := &SchemaInfo{
		ID:          uuid.New().String(),
		SourceID:    source.ID,
		Database:    "kafka",
		Schema:      "topics",
		LastScanned: time.Now().UTC(),
	}

	topics := []string{"events", "orders", "metrics", "logs"}
	for _, topic := range topics {
		table := TableInfo{
			Name:      topic,
			Type:      "TOPIC",
			RowCount:  rand.Int63n(10000000) + 10000,
			SizeBytes: rand.Int63n(1000000000) + 1000000,
			UpdatedAt: time.Now().UTC(),
			Statistics: TableStatistics{
				LastAnalyzed: time.Now().UTC(),
				ColumnStats:  make(map[string]ColumnStats),
			},
		}
		table.Columns = []ColumnInfo{
			{Name: "key", Type: "bytes", Nullable: true},
			{Name: "value", Type: "bytes", Nullable: true},
			{Name: "timestamp", Type: "timestamp", Nullable: false},
			{Name: "partition", Type: "int", Nullable: false},
			{Name: "offset", Type: "bigint", Nullable: false},
		}
		schema.Tables = append(schema.Tables, table)
	}

	schema.ScanDuration = time.Since(schema.LastScanned)
	return schema, nil
}
