package metacrawler

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"streamsql/internal/common/logger"
)

type MetaCrawlerService struct {
	crawlers      map[DataSourceType]DataSourceCrawler
	sources       map[string]*DataSource
	tasks         map[string]*CrawlTask
	schemas       map[string]*SchemaInfo
	mu            sync.RWMutex
}

func NewMetaCrawlerService() *MetaCrawlerService {
	service := &MetaCrawlerService{
		crawlers: make(map[DataSourceType]DataSourceCrawler),
		sources:  make(map[string]*DataSource),
		tasks:    make(map[string]*CrawlTask),
		schemas:  make(map[string]*SchemaInfo),
	}

	service.RegisterCrawler(NewMySQLCrawler())
	service.RegisterCrawler(NewPostgreSQLCrawler())
	service.RegisterCrawler(NewMongoDBCrawler())
	service.RegisterCrawler(NewKafkaCrawler())

	service.initDefaultSources()
	return service
}

func (s *MetaCrawlerService) RegisterCrawler(crawler DataSourceCrawler) {
	s.crawlers[crawler.GetType()] = crawler
	logger.Sugar().Infof("Registered crawler for type: %s", crawler.GetType())
}

func (s *MetaCrawlerService) initDefaultSources() {
	defaultSources := []*DataSource{
		{
			ID:          uuid.New().String(),
			Type:        SourceMySQL,
			Name:        "Production MySQL",
			Description: "主业务MySQL数据库",
			Config: map[string]interface{}{
				"host":     "localhost",
				"port":     3306,
				"user":     "root",
				"password": "password",
				"database": "streamsql",
			},
			Status:    "active",
			CreatedAt: time.Now().UTC(),
			UpdatedAt: time.Now().UTC(),
		},
		{
			ID:          uuid.New().String(),
			Type:        SourcePostgreSQL,
			Name:        "Analytics PostgreSQL",
			Description: "分析用PostgreSQL数据库",
			Config: map[string]interface{}{
				"host":     "localhost",
				"port":     5432,
				"user":     "postgres",
				"password": "password",
				"database": "analytics",
			},
			Status:    "active",
			CreatedAt: time.Now().UTC(),
			UpdatedAt: time.Now().UTC(),
		},
		{
			ID:          uuid.New().String(),
			Type:        SourceMongoDB,
			Name:        "MongoDB Cluster",
			Description: "文档存储MongoDB集群",
			Config: map[string]interface{}{
				"host":     "localhost",
				"port":     27017,
				"database": "app",
			},
			Status:    "active",
			CreatedAt: time.Now().UTC(),
			UpdatedAt: time.Now().UTC(),
		},
		{
			ID:          uuid.New().String(),
			Type:        SourceKafka,
			Name:        "Kafka Events",
			Description: "事件流Kafka集群",
			Config: map[string]interface{}{
				"brokers": "localhost:9092",
				"group_id": "streamsql_crawler",
			},
			Status:    "active",
			CreatedAt: time.Now().UTC(),
			UpdatedAt: time.Now().UTC(),
		},
	}

	for _, src := range defaultSources {
		s.sources[src.ID] = src
	}

	logger.Sugar().Infof("Initialized %d default data sources", len(defaultSources))
}

func (s *MetaCrawlerService) CreateSource(source *DataSource) (*DataSource, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	source.ID = uuid.New().String()
	source.Status = "pending"
	source.CreatedAt = time.Now().UTC()
	source.UpdatedAt = time.Now().UTC()

	s.sources[source.ID] = source
	logger.Sugar().Infof("Created data source: %s (type: %s)", source.Name, source.Type)
	return source, nil
}

func (s *MetaCrawlerService) GetSource(id string) (*DataSource, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	source, exists := s.sources[id]
	if !exists {
		return nil, fmt.Errorf("data source not found: %s", id)
	}
	return source, nil
}

func (s *MetaCrawlerService) ListSources() []*DataSource {
	s.mu.RLock()
	defer s.mu.RUnlock()

	sources := make([]*DataSource, 0, len(s.sources))
	for _, src := range s.sources {
		sources = append(sources, src)
	}
	return sources
}

func (s *MetaCrawlerService) UpdateSource(id string, source *DataSource) (*DataSource, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	existing, exists := s.sources[id]
	if !exists {
		return nil, fmt.Errorf("data source not found: %s", id)
	}

	existing.Name = source.Name
	existing.Description = source.Description
	existing.Config = source.Config
	existing.UpdatedAt = time.Now().UTC()

	logger.Sugar().Infof("Updated data source: %s", existing.Name)
	return existing, nil
}

func (s *MetaCrawlerService) DeleteSource(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.sources[id]; !exists {
		return fmt.Errorf("data source not found: %s", id)
	}

	delete(s.sources, id)
	logger.Sugar().Infof("Deleted data source: %s", id)
	return nil
}

func (s *MetaCrawlerService) TestConnection(id string) error {
	source, err := s.GetSource(id)
	if err != nil {
		return err
	}

	crawler, exists := s.crawlers[source.Type]
	if !exists {
		return fmt.Errorf("no crawler registered for type: %s", source.Type)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	return crawler.TestConnection(ctx, source)
}

func (s *MetaCrawlerService) StartCrawl(sourceID string) (*CrawlTask, error) {
	source, err := s.GetSource(sourceID)
	if err != nil {
		return nil, err
	}

	crawler, exists := s.crawlers[source.Type]
	if !exists {
		return nil, fmt.Errorf("no crawler registered for type: %s", source.Type)
	}

	task := &CrawlTask{
		ID:        uuid.New().String(),
		SourceID:  sourceID,
		Status:    "running",
		Progress:  0,
		Message:   "开始采集元数据...",
		StartedAt: time.Now().UTC(),
	}

	s.mu.Lock()
	s.tasks[task.ID] = task
	s.mu.Unlock()

	go s.executeCrawl(task, source, crawler)

	logger.Sugar().Infof("Started crawl task %s for source %s", task.ID, source.Name)
	return task, nil
}

func (s *MetaCrawlerService) executeCrawl(task *CrawlTask, source *DataSource, crawler DataSourceCrawler) {
	ctx := context.Background()

	s.updateTaskProgress(task, 0.1, "连接数据源...")

	s.updateTaskProgress(task, 0.3, "扫描表结构...")

	schema, err := crawler.Crawl(ctx, source)
	if err != nil {
		s.completeTask(task, nil, err.Error())
		return
	}

	s.updateTaskProgress(task, 0.7, "提取统计信息...")

	s.updateTaskProgress(task, 0.9, "生成样例数据...")

	s.mu.Lock()
	s.schemas[schema.ID] = schema
	s.mu.Unlock()

	s.completeTask(task, schema, "")

	logger.Sugar().Infof("Crawl task %s completed successfully, found %d tables",
		task.ID, len(schema.Tables))
}

func (s *MetaCrawlerService) updateTaskProgress(task *CrawlTask, progress float64, message string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	task.Progress = progress
	task.Message = message
}

func (s *MetaCrawlerService) completeTask(task *CrawlTask, result *SchemaInfo, errMsg string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now().UTC()
	task.CompletedAt = &now
	task.Result = result

	if errMsg != "" {
		task.Status = "failed"
		task.Error = errMsg
		logger.Sugar().Errorf("Crawl task %s failed: %s", task.ID, errMsg)
	} else {
		task.Status = "completed"
		task.Progress = 1.0
		task.Message = "采集完成"
	}
}

func (s *MetaCrawlerService) GetTask(id string) (*CrawlTask, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	task, exists := s.tasks[id]
	if !exists {
		return nil, fmt.Errorf("crawl task not found: %s", id)
	}
	return task, nil
}

func (s *MetaCrawlerService) ListTasks() []*CrawlTask {
	s.mu.RLock()
	defer s.mu.RUnlock()

	tasks := make([]*CrawlTask, 0, len(s.tasks))
	for _, t := range s.tasks {
		tasks = append(tasks, t)
	}
	return tasks
}

func (s *MetaCrawlerService) GetSchema(id string) (*SchemaInfo, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	schema, exists := s.schemas[id]
	if !exists {
		return nil, fmt.Errorf("schema not found: %s", id)
	}
	return schema, nil
}

func (s *MetaCrawlerService) ListSchemas() []*SchemaInfo {
	s.mu.RLock()
	defer s.mu.RUnlock()

	schemas := make([]*SchemaInfo, 0, len(s.schemas))
	for _, sc := range s.schemas {
		schemas = append(schemas, sc)
	}
	return schemas
}

func (s *MetaCrawlerService) GetTable(sourceID, tableName string) (*TableInfo, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for _, schema := range s.schemas {
		if schema.SourceID == sourceID {
			for _, table := range schema.Tables {
				if table.Name == tableName {
					return &table, nil
				}
			}
		}
	}

	return nil, fmt.Errorf("table not found: %s", tableName)
}

func (s *MetaCrawlerService) SearchTables(keyword string) []TableInfo {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var results []TableInfo
	for _, schema := range s.schemas {
		for _, table := range schema.Tables {
			if containsKeyword(table.Name, keyword) ||
				containsKeyword(table.Comment, keyword) {
				results = append(results, table)
			}
		}
	}

	return results
}

func containsKeyword(s, keyword string) bool {
	if keyword == "" {
		return true
	}
	return len(s) >= len(keyword) && (s == keyword ||
		(len(s) > len(keyword) && (s[:len(keyword)] == keyword ||
			s[len(s)-len(keyword):] == keyword ||
			indexOf(s, keyword) >= 0)))
}

func indexOf(s, sub string) int {
	for i := 0; i <= len(s)-len(sub); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

func (s *MetaCrawlerService) GetStats() map[string]interface{} {
	s.mu.RLock()
	defer s.mu.RUnlock()

	totalTables := 0
	totalColumns := 0
	totalRows := int64(0)

	for _, schema := range s.schemas {
		for _, table := range schema.Tables {
			totalTables++
			totalColumns += len(table.Columns)
			totalRows += table.RowCount
		}
	}

	return map[string]interface{}{
		"data_sources":     len(s.sources),
		"crawl_tasks":      len(s.tasks),
		"schemas":          len(s.schemas),
		"total_tables":     totalTables,
		"total_columns":    totalColumns,
		"total_rows":       totalRows,
	}
}
