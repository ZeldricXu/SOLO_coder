package metadata

import (
	"database/sql"
	"fmt"
	"sync"
	"time"

	"github.com/datatransform/platform/pkg/logger"
	"go.uber.org/zap"
)

type ColumnInfo struct {
	Name         string
	DataType     string
	Nullable     bool
	DefaultValue string
	IsPrimaryKey bool
	IsForeignKey bool
	Position     int
}

type TableInfo struct {
	Name           string
	Schema         string
	Columns        []ColumnInfo
	RowCount       int64
	SizeBytes      int64
	CreateTime     time.Time
	LastModified   time.Time
	SampleData     []map[string]interface{}
	Statistics     TableStatistics
}

type TableStatistics struct {
	DistinctRows    int64
	NullCount       map[string]int64
	MinValues       map[string]interface{}
	MaxValues       map[string]interface{}
	Cardinality     map[string]int64
}

type DataSourceInfo struct {
	Type         string
	Name         string
	Host         string
	Port         int
	Database     string
	LastScanTime time.Time
	Tables       []TableInfo
}

type ScanConfig struct {
	SampleRowCount   int
	IncludeStatistics bool
	IncludeSampleData bool
	MaxTableSize     int64
}

type MetadataCrawler struct {
	config     ScanConfig
	dataSources map[string]DataSourceInfo
	mu         sync.RWMutex
	running    bool
	stopChan   chan struct{}
}

func NewMetadataCrawler(config ScanConfig) *MetadataCrawler {
	return &MetadataCrawler{
		config:     config,
		dataSources: make(map[string]DataSourceInfo),
		stopChan:   make(chan struct{}),
	}
}

func (c *MetadataCrawler) AddDataSource(name string, host string, port int, database string, dbType string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.dataSources[name] = DataSourceInfo{
		Type:     dbType,
		Name:     name,
		Host:     host,
		Port:     port,
		Database: database,
		Tables:   make([]TableInfo, 0),
	}
}

func (c *MetadataCrawler) Start() {
	c.mu.Lock()
	if c.running {
		c.mu.Unlock()
		return
	}
	c.running = true
	c.mu.Unlock()

	logger.Info("starting metadata crawler")
	go c.scanLoop()
}

func (c *MetadataCrawler) Stop() {
	c.mu.Lock()
	if !c.running {
		c.mu.Unlock()
		return
	}
	c.running = false
	close(c.stopChan)
	c.mu.Unlock()

	logger.Info("metadata crawler stopped")
}

func (c *MetadataCrawler) scanLoop() {
	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()

	c.scanAllDataSources()

	for {
		select {
		case <-c.stopChan:
			return
		case <-ticker.C:
			c.scanAllDataSources()
		}
	}
}

func (c *MetadataCrawler) scanAllDataSources() {
	c.mu.RLock()
	sources := make([]string, 0, len(c.dataSources))
	for name := range c.dataSources {
		sources = append(sources, name)
	}
	c.mu.RUnlock()

	for _, sourceName := range sources {
		c.scanDataSource(sourceName)
	}
}

func (c *MetadataCrawler) scanDataSource(sourceName string) {
	logger.Info("scanning data source", zap.String("source", sourceName))

	c.mu.RLock()
	ds, exists := c.dataSources[sourceName]
	c.mu.RUnlock()

	if !exists {
		return
	}

	tables := c.discoverTables(&ds)

	for i := range tables {
		c.extractTableMetadata(&tables[i])

		if c.config.IncludeStatistics {
			c.collectTableStatistics(&tables[i])
		}

		if c.config.IncludeSampleData {
			c.collectSampleData(&tables[i])
		}
	}

	c.mu.Lock()
	ds.Tables = tables
	ds.LastScanTime = time.Now()
	c.dataSources[sourceName] = ds
	c.mu.Unlock()

	logger.Info("data source scan completed",
		zap.String("source", sourceName),
		zap.Int("table_count", len(tables)),
	)
}

func (c *MetadataCrawler) discoverTables(ds *DataSourceInfo) []TableInfo {
	tables := make([]TableInfo, 0)

	simulatedTables := []string{"users", "orders", "products", "transactions", "logs"}

	for _, tableName := range simulatedTables {
		table := TableInfo{
			Name:         tableName,
			Schema:       "public",
			RowCount:     int64(1000 + time.Now().UnixNano()%10000),
			SizeBytes:    int64(1024*1024 + time.Now().UnixNano()%(100*1024*1024)),
			CreateTime:   time.Now().Add(-30 * 24 * time.Hour),
			LastModified: time.Now().Add(-1 * time.Hour),
		}

		table.Columns = c.discoverColumns(ds, tableName)
		tables = append(tables, table)
	}

	return tables
}

func (c *MetadataCrawler) discoverColumns(ds *DataSourceInfo, tableName string) []ColumnInfo {
	columns := make([]ColumnInfo, 0)

	simulatedColumns := map[string][]struct {
		name         string
		dataType     string
		nullable     bool
		isPrimaryKey bool
	}{
		"users": {
			{"id", "bigint", false, true},
			{"name", "varchar(255)", true, false},
			{"email", "varchar(255)", true, false},
			{"created_at", "timestamp", false, false},
		},
		"orders": {
			{"id", "bigint", false, true},
			{"user_id", "bigint", false, false},
			{"amount", "decimal(10,2)", false, false},
			{"status", "varchar(50)", false, false},
			{"created_at", "timestamp", false, false},
		},
		"products": {
			{"id", "bigint", false, true},
			{"name", "varchar(255)", false, false},
			{"price", "decimal(10,2)", false, false},
			{"stock", "integer", false, false},
		},
		"transactions": {
			{"id", "bigint", false, true},
			{"order_id", "bigint", false, false},
			{"amount", "decimal(10,2)", false, false},
			{"transaction_date", "timestamp", false, false},
		},
		"logs": {
			{"id", "bigint", false, true},
			{"level", "varchar(20)", false, false},
			{"message", "text", true, false},
			{"created_at", "timestamp", false, false},
		},
	}

	if cols, exists := simulatedColumns[tableName]; exists {
		for i, col := range cols {
			columns = append(columns, ColumnInfo{
				Name:         col.name,
				DataType:     col.dataType,
				Nullable:     col.nullable,
				IsPrimaryKey: col.isPrimaryKey,
				Position:     i + 1,
			})
		}
	}

	return columns
}

func (c *MetadataCrawler) extractTableMetadata(table *TableInfo) {
	logger.Debug("extracting table metadata",
		zap.String("table", table.Name),
		zap.Int("column_count", len(table.Columns)),
	)
}

func (c *MetadataCrawler) collectTableStatistics(table *TableInfo) {
	stats := TableStatistics{
		DistinctRows: table.RowCount,
		NullCount:    make(map[string]int64),
		MinValues:    make(map[string]interface{}),
		MaxValues:    make(map[string]interface{}),
		Cardinality:  make(map[string]int64),
	}

	for _, col := range table.Columns {
		stats.NullCount[col.Name] = table.RowCount / 10
		stats.Cardinality[col.Name] = table.RowCount / 2

		switch col.DataType {
		case "bigint", "integer":
			stats.MinValues[col.Name] = 0
			stats.MaxValues[col.Name] = table.RowCount
		case "varchar(255)", "text", "varchar(50)":
			stats.MinValues[col.Name] = "a"
			stats.MaxValues[col.Name] = "z"
		case "timestamp":
			stats.MinValues[col.Name] = time.Now().Add(-30 * 24 * time.Hour)
			stats.MaxValues[col.Name] = time.Now()
		case "decimal(10,2)":
			stats.MinValues[col.Name] = 0.0
			stats.MaxValues[col.Name] = 99999.99
		}
	}

	table.Statistics = stats

	logger.Info("collected table statistics",
		zap.String("table", table.Name),
		zap.Int64("distinct_rows", stats.DistinctRows),
	)
}

func (c *MetadataCrawler) collectSampleData(table *TableInfo) {
	sampleData := make([]map[string]interface{}, 0, c.config.SampleRowCount)

	for i := 0; i < c.config.SampleRowCount; i++ {
		row := make(map[string]interface{})
		for j, col := range table.Columns {
			switch col.DataType {
			case "bigint", "integer":
				row[col.Name] = i + 1
			case "varchar(255)", "text", "varchar(50)":
				row[col.Name] = fmt.Sprintf("%s_sample_%d", table.Name, i)
			case "timestamp":
				row[col.Name] = time.Now().Add(-time.Duration(i) * time.Hour).Format(time.RFC3339)
			case "decimal(10,2)":
				row[col.Name] = float64(i*100) + 0.99
			default:
				row[col.Name] = fmt.Sprintf("value_%d", j)
			}
		}
		sampleData = append(sampleData, row)
	}

	table.SampleData = sampleData

	logger.Info("collected sample data",
		zap.String("table", table.Name),
		zap.Int("sample_count", len(sampleData)),
	)
}

func (c *MetadataCrawler) GetDataSource(name string) (*DataSourceInfo, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	ds, exists := c.dataSources[name]
	if !exists {
		return nil, false
	}
	return &ds, true
}

func (c *MetadataCrawler) GetAllDataSources() []DataSourceInfo {
	c.mu.RLock()
	defer c.mu.RUnlock()

	result := make([]DataSourceInfo, 0, len(c.dataSources))
	for _, ds := range c.dataSources {
		result = append(result, ds)
	}
	return result
}

func (c *MetadataCrawler) GetTable(sourceName string, tableName string) (*TableInfo, bool) {
	ds, exists := c.GetDataSource(sourceName)
	if !exists {
		return nil, false
	}

	for i := range ds.Tables {
		if ds.Tables[i].Name == tableName {
			return &ds.Tables[i], true
		}
	}
	return nil, false
}

func (c *MetadataCrawler) IsRunning() bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.running
}

func (c *MetadataCrawler) TriggerScan(sourceName string) error {
	go c.scanDataSource(sourceName)
	return nil
}

type DatabaseConnector interface {
	Connect() (*sql.DB, error)
	Disconnect() error
	ListTables() ([]string, error)
	GetTableInfo(tableName string) (TableInfo, error)
}

type PostgresConnector struct {
	Host     string
	Port     int
	User     string
	Password string
	Database string
}

func NewPostgresConnector(host string, port int, user, password, database string) *PostgresConnector {
	return &PostgresConnector{
		Host:     host,
		Port:     port,
		User:     user,
		Password: password,
		Database: database,
	}
}

func (pc *PostgresConnector) Connect() (*sql.DB, error) {
	connStr := fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=disable",
		pc.Host, pc.Port, pc.User, pc.Password, pc.Database)
	return sql.Open("postgres", connStr)
}

func (pc *PostgresConnector) Disconnect() error {
	return nil
}

func (pc *PostgresConnector) ListTables() ([]string, error) {
	return []string{"users", "orders", "products"}, nil
}

func (pc *PostgresConnector) GetTableInfo(tableName string) (TableInfo, error) {
	return TableInfo{
		Name:   tableName,
		Schema: "public",
	}, nil
}
