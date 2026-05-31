package metadata

import (
	"context"
	"database/sql"
	"sync"
	"testing"
	"time"
)

type mockExtractor struct{}

func (m *mockExtractor) ListTables(ctx context.Context, db *sql.DB, sourceType DataSourceType) ([]string, error) {
	return []string{"table1", "table2", "table3"}, nil
}

func (m *mockExtractor) GetColumns(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string) ([]ColumnSchema, error) {
	return []ColumnSchema{
		{Name: "id", Type: "int", PrimaryKey: true},
		{Name: "name", Type: "varchar"},
	}, nil
}

func (m *mockExtractor) GetTableStats(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string) (int64, int64, error) {
	return 1000, 10240, nil
}

func (m *mockExtractor) GetSampleData(ctx context.Context, db *sql.DB, tableName string, limit int) ([]map[string]interface{}, error) {
	return []map[string]interface{}{
		{"id": 1, "name": "test"},
	}, nil
}

func (m *mockExtractor) GetColumnStatistics(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string, columns []ColumnSchema) (map[string]ColumnStats, error) {
	return map[string]ColumnStats{
		"id":   {DistinctCount: 1000},
		"name": {DistinctCount: 500},
	}, nil
}

type mockConnector struct {
	connected bool
}

func (m *mockConnector) Connect(ctx context.Context) error {
	m.connected = true
	return nil
}

func (m *mockConnector) Disconnect() error {
	m.connected = false
	return nil
}

func (m *mockConnector) DB() *sql.DB { return nil }

func (m *mockConnector) Config() DataSourceConfig {
	return DataSourceConfig{Type: SourcePostgreSQL, Database: "test"}
}

func TestCrawler_SetSampleSize_ConcurrentSafe(t *testing.T) {
	crawler := NewCrawlerWith(&mockConnector{}, &mockExtractor{})

	var wg sync.WaitGroup
	iterations := 1000

	for i := 0; i < iterations; i++ {
		wg.Add(1)
		go func(size int) {
			defer wg.Done()
			crawler.SetSampleSize(size)
		}(i)
	}

	wg.Wait()
}

func TestCrawlerScheduler_ConcurrentCrawl(t *testing.T) {
	scheduler := NewCrawlerScheduler()
	scheduler.crawlers["test"] = NewCrawlerWith(&mockConnector{}, &mockExtractor{})

	var wg sync.WaitGroup
	iterations := 100
	errors := make(chan error, iterations)

	for i := 0; i < iterations; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			_, err := scheduler.CrawlOnce(ctx, "test")
			if err != nil && err.Error() != "crawl task already running for id: test" {
				errors <- err
			}
		}()
	}

	wg.Wait()
	close(errors)

	for err := range errors {
		if err != nil {
			t.Errorf("unexpected error: %v", err)
		}
	}
}

func TestCrawlerScheduler_Stop_CancelsActiveTasks(t *testing.T) {
	scheduler := NewCrawlerScheduler()

	slowExtractor := &slowMockExtractor{delay: 100 * time.Millisecond}
	scheduler.crawlers["test"] = NewCrawlerWith(&mockConnector{}, slowExtractor)

	go func() {
		ctx := context.Background()
		scheduler.CrawlOnce(ctx, "test")
	}()

	time.Sleep(20 * time.Millisecond)

	if !scheduler.IsRunning("test") {
		t.Error("expected task to be running")
	}

	scheduler.Stop("test")

	time.Sleep(50 * time.Millisecond)

	if scheduler.IsRunning("test") {
		t.Error("expected task to be stopped after Stop()")
	}
}

func TestCrawlerScheduler_StopAll(t *testing.T) {
	scheduler := NewCrawlerScheduler()

	slowExtractor := &slowMockExtractor{delay: 100 * time.Millisecond}
	scheduler.crawlers["test1"] = NewCrawlerWith(&mockConnector{}, slowExtractor)
	scheduler.crawlers["test2"] = NewCrawlerWith(&mockConnector{}, slowExtractor)

	go scheduler.CrawlOnce(context.Background(), "test1")
	go scheduler.CrawlOnce(context.Background(), "test2")

	time.Sleep(20 * time.Millisecond)

	scheduler.StopAll()

	time.Sleep(50 * time.Millisecond)

	if scheduler.IsRunning("test1") || scheduler.IsRunning("test2") {
		t.Error("expected all tasks to be stopped after StopAll()")
	}
}

type slowMockExtractor struct {
	delay time.Duration
}

func (m *slowMockExtractor) ListTables(ctx context.Context, db *sql.DB, sourceType DataSourceType) ([]string, error) {
	select {
	case <-time.After(m.delay):
		return []string{"table1"}, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (m *slowMockExtractor) GetColumns(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string) ([]ColumnSchema, error) {
	return []ColumnSchema{{Name: "id", Type: "int"}}, nil
}

func (m *slowMockExtractor) GetTableStats(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string) (int64, int64, error) {
	return 0, 0, nil
}

func (m *slowMockExtractor) GetSampleData(ctx context.Context, db *sql.DB, tableName string, limit int) ([]map[string]interface{}, error) {
	return nil, nil
}

func (m *slowMockExtractor) GetColumnStatistics(ctx context.Context, db *sql.DB, sourceType DataSourceType, tableName string, columns []ColumnSchema) (map[string]ColumnStats, error) {
	return nil, nil
}
