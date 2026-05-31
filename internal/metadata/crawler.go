package metadata

import (
	"context"
	"fmt"
	"session154/internal/logger"
	"sync"
	"time"

	"go.uber.org/zap"
)

type Crawler struct {
	connector  DBConnector
	extractor  SchemaExtractor
	sampleSize int
	mu         sync.Mutex
}

func NewCrawler(config DataSourceConfig) *Crawler {
	return &Crawler{
		connector:  NewSQLConnector(config),
		extractor:  NewStandardExtractor(),
		sampleSize: 100,
	}
}

func NewCrawlerWith(connector DBConnector, extractor SchemaExtractor) *Crawler {
	return &Crawler{
		connector:  connector,
		extractor:  extractor,
		sampleSize: 100,
	}
}

func (c *Crawler) SetSampleSize(size int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.sampleSize = size
}

func (c *Crawler) getSampleSize() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.sampleSize
}

func (c *Crawler) Crawl(ctx context.Context) (*SchemaMetadata, error) {
	if err := c.connector.Connect(ctx); err != nil {
		return nil, fmt.Errorf("connect failed: %w", err)
	}
	defer c.connector.Disconnect()

	startTime := time.Now()
	metadata := &SchemaMetadata{
		DataSourceID: fmt.Sprintf("ds_%s_%d", c.connector.Config().Type, time.Now().Unix()),
		CollectedAt:  startTime,
	}

	tables, err := c.extractor.ListTables(ctx, c.connector.DB(), c.connector.Config().Type)
	if err != nil {
		return nil, fmt.Errorf("list tables failed: %w", err)
	}

	logger.Info("crawling tables", zap.Int("table_count", len(tables)))

	var wg sync.WaitGroup
	tableChan := make(chan TableSchema, len(tables))
	sem := make(chan struct{}, 5)

	for _, tableName := range tables {
		wg.Add(1)
		go func(name string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			table, err := c.crawlTable(ctx, name)
			if err != nil {
				logger.Warn("failed to crawl table", zap.String("table", name), zap.Error(err))
				return
			}
			tableChan <- table
		}(tableName)
	}

	wg.Wait()
	close(tableChan)

	for table := range tableChan {
		metadata.Tables = append(metadata.Tables, table)
	}

	metadata.DurationMs = time.Since(startTime).Milliseconds()
	logger.Info("crawling completed",
		zap.Int("tables_crawled", len(metadata.Tables)),
		zap.Int64("duration_ms", metadata.DurationMs))

	return metadata, nil
}

func (c *Crawler) crawlTable(ctx context.Context, tableName string) (TableSchema, error) {
	table := TableSchema{Name: tableName}

	columns, err := c.extractor.GetColumns(ctx, c.connector.DB(), c.connector.Config().Type, tableName)
	if err != nil {
		return table, fmt.Errorf("get columns: %w", err)
	}
	table.Columns = columns

	rowCount, sizeBytes, err := c.extractor.GetTableStats(ctx, c.connector.DB(), c.connector.Config().Type, tableName)
	if err == nil {
		table.RowCount = rowCount
		table.SizeBytes = sizeBytes
	} else {
		logger.Warn("failed to get table stats", zap.String("table", tableName), zap.Error(err))
	}

	sampleData, err := c.extractor.GetSampleData(ctx, c.connector.DB(), tableName, c.getSampleSize())
	if err == nil {
		table.SampleData = sampleData
	} else {
		logger.Warn("failed to get sample data", zap.String("table", tableName), zap.Error(err))
	}

	stats, err := c.extractor.GetColumnStatistics(ctx, c.connector.DB(), c.connector.Config().Type, tableName, columns)
	if err == nil {
		table.Statistics = &TableStatistics{
			ColumnStats:  stats,
			LastAnalyzed: time.Now(),
		}
	} else {
		logger.Warn("failed to get column statistics", zap.String("table", tableName), zap.Error(err))
	}

	return table, nil
}

type CrawlerScheduler struct {
	crawlers    map[string]*Crawler
	schedules   map[string]*time.Ticker
	activeTasks map[string]context.CancelFunc
	mu          sync.RWMutex
	results     map[string]*SchemaMetadata
}

func NewCrawlerScheduler() *CrawlerScheduler {
	return &CrawlerScheduler{
		crawlers:    make(map[string]*Crawler),
		schedules:   make(map[string]*time.Ticker),
		activeTasks: make(map[string]context.CancelFunc),
		results:     make(map[string]*SchemaMetadata),
	}
}

func (cs *CrawlerScheduler) AddCrawler(id string, config DataSourceConfig) {
	cs.mu.Lock()
	defer cs.mu.Unlock()
	cs.crawlers[id] = NewCrawler(config)
}

func (cs *CrawlerScheduler) CrawlOnce(ctx context.Context, id string) (*SchemaMetadata, error) {
	cs.mu.RLock()
	crawler, ok := cs.crawlers[id]
	cs.mu.RUnlock()

	if !ok {
		return nil, fmt.Errorf("crawler not found: %s", id)
	}

	ctx, cancel := context.WithCancel(ctx)
	cs.mu.Lock()
	if _, active := cs.activeTasks[id]; active {
		cs.mu.Unlock()
		cancel()
		return nil, fmt.Errorf("crawl task already running for id: %s", id)
	}
	cs.activeTasks[id] = cancel
	cs.mu.Unlock()

	defer func() {
		cs.mu.Lock()
		delete(cs.activeTasks, id)
		cs.mu.Unlock()
		cancel()
	}()

	metadata, err := crawler.Crawl(ctx)
	if err != nil {
		return nil, err
	}

	cs.mu.Lock()
	cs.results[id] = metadata
	cs.mu.Unlock()

	return metadata, nil
}

func (cs *CrawlerScheduler) Schedule(id string, interval time.Duration) {
	cs.mu.Lock()
	defer cs.mu.Unlock()

	if existing, ok := cs.schedules[id]; ok {
		existing.Stop()
	}

	ticker := time.NewTicker(interval)
	cs.schedules[id] = ticker

	go func() {
		for range ticker.C {
			crawlCtx, crawlCancel := context.WithTimeout(context.Background(), 5*time.Minute)
			if _, err := cs.CrawlOnce(crawlCtx, id); err != nil {
				logger.Error("scheduled crawl failed", zap.String("id", id), zap.Error(err))
			}
			crawlCancel()
		}
	}()
}

func (cs *CrawlerScheduler) Stop(id string) {
	cs.mu.Lock()
	defer cs.mu.Unlock()

	if ticker, ok := cs.schedules[id]; ok {
		ticker.Stop()
		delete(cs.schedules, id)
	}

	if cancel, ok := cs.activeTasks[id]; ok {
		cancel()
		delete(cs.activeTasks, id)
	}
}

func (cs *CrawlerScheduler) StopAll() {
	cs.mu.Lock()
	defer cs.mu.Unlock()

	for _, ticker := range cs.schedules {
		ticker.Stop()
	}
	cs.schedules = make(map[string]*time.Ticker)

	for _, cancel := range cs.activeTasks {
		cancel()
	}
	cs.activeTasks = make(map[string]context.CancelFunc)
}

func (cs *CrawlerScheduler) GetResult(id string) (*SchemaMetadata, bool) {
	cs.mu.RLock()
	defer cs.mu.RUnlock()
	result, ok := cs.results[id]
	return result, ok
}

func (cs *CrawlerScheduler) IsRunning(id string) bool {
	cs.mu.RLock()
	defer cs.mu.RUnlock()
	_, ok := cs.activeTasks[id]
	return ok
}
