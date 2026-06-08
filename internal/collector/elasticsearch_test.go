package collector

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
	"github.com/datateam/loganalyzer/internal/testdata"
)

func TestElasticsearchCollector_ParseHit_NormalPath(t *testing.T) {
	cfg := config.ElasticsearchConfig{
		Name:         "test-es",
		Addresses:    []string{"http://localhost:9200"},
		Index:        "logs-*",
		TimeField:    "@timestamp",
		LevelField:   "level",
		MessageField: "message",
		ScrollSize:   100,
		PollInterval: 30 * time.Second,
	}

	collector, err := NewElasticsearchCollector(cfg)
	require.NoError(t, err)

	ts := time.Now().UTC()
	expectedEvent := testdata.NewLogEvent(
		testdata.WithTimestamp(ts),
		testdata.WithLevel(models.LevelError),
		testdata.WithMessage("Database connection failed"),
		testdata.WithServiceName("order-service"),
		testdata.WithTraceID("trace-12345"),
		testdata.WithClientIP("192.168.1.100"),
		testdata.WithStatusCode(500),
		testdata.WithResponseTime(250),
		testdata.WithErrorCode("DB_CONN_FAILED"),
	)

	doc := testdata.NewESDocument(expectedEvent)
	hit := testdata.NewESHit(doc, "doc-1")

	resultEvent := collector.parseHit(hit)

	require.NotNil(t, resultEvent)
	assert.Equal(t, models.SourceElasticsearch, resultEvent.Source)
	assert.Equal(t, "test-es", resultEvent.SourceID)
	assert.Equal(t, expectedEvent.Level, resultEvent.Level)
	assert.Equal(t, expectedEvent.Message, resultEvent.Message)
	assert.Equal(t, expectedEvent.ServiceName, resultEvent.ServiceName)
	assert.Equal(t, expectedEvent.TraceID, resultEvent.TraceID)
	assert.Equal(t, expectedEvent.ClientIP, resultEvent.ClientIP)
	assert.Equal(t, expectedEvent.StatusCode, resultEvent.StatusCode)
	assert.Equal(t, expectedEvent.ResponseTime, resultEvent.ResponseTime)
	assert.Equal(t, expectedEvent.ErrorCode, resultEvent.ErrorCode)
	assert.WithinDuration(t, expectedEvent.Timestamp, resultEvent.Timestamp, time.Millisecond)
}

func TestElasticsearchCollector_ParseHit_TimeRangeExtraction(t *testing.T) {
	cfg := config.ElasticsearchConfig{
		Name:         "test-es",
		Addresses:    []string{"http://localhost:9200"},
		Index:        "logs-*",
		TimeField:    "@timestamp",
		ScrollSize:   100,
		PollInterval: 30 * time.Second,
	}

	collector, err := NewElasticsearchCollector(cfg)
	require.NoError(t, err)

	baseTime := time.Now().UTC().Truncate(time.Second)
	events := make([]*models.LogEvent, 0)
	hitList := make([]map[string]interface{}, 0)

	for i := 0; i < 10; i++ {
		ts := baseTime.Add(time.Duration(i) * time.Second)
		event := testdata.NewLogEvent(
			testdata.WithTimestamp(ts),
			testdata.WithMessage(fmt.Sprintf("log message %d", i)),
		)
		events = append(events, event)
		hitList = append(hitList, testdata.NewESHit(testdata.NewESDocument(event), fmt.Sprintf("doc-%d", i)))
	}

	initialSeq := collector.lastSeq
	for i, hit := range hitList {
		result := collector.parseHit(hit)
		require.NotNil(t, result)
		assert.Equal(t, events[i].Message, result.Message)
	}

	assert.Greater(t, collector.lastSeq, initialSeq)
	assert.Equal(t, events[9].Timestamp.UnixMilli(), collector.lastSeq)
}

func TestElasticsearchCollector_ScrollAndProcess_MultipleBatches(t *testing.T) {
	var requestCount int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Elastic-Product", "Elasticsearch")
		count := atomic.AddInt32(&requestCount, 1)

		if strings.Contains(r.URL.Path, "_search") && !strings.Contains(r.URL.Path, "_search/scroll") {
			initialResponse := map[string]interface{}{
				"_scroll_id": "scroll-id-123",
				"hits": map[string]interface{}{
					"total": map[string]interface{}{"value": 15},
					"hits":  createTestHits(0, 10),
				},
			}
			json.NewEncoder(w).Encode(initialResponse)
			return
		}

		if strings.Contains(r.URL.Path, "_search/scroll") && r.Method != http.MethodDelete {
			var hits []interface{}
			if count == 2 {
				hits = createTestHits(10, 15)
			} else {
				hits = []interface{}{}
			}
			scrollResponse := map[string]interface{}{
				"_scroll_id": "scroll-id-123",
				"hits": map[string]interface{}{
					"total": map[string]interface{}{"value": 15},
					"hits":  hits,
				},
			}
			json.NewEncoder(w).Encode(scrollResponse)
			return
		}

		if strings.Contains(r.URL.Path, "_search/scroll") && r.Method == http.MethodDelete {
			w.WriteHeader(http.StatusOK)
			return
		}

		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	cfg := config.ElasticsearchConfig{
		Name:         "test-es",
		Addresses:    []string{server.URL},
		Index:        "logs-*",
		ScrollSize:   10,
		PollInterval: 50 * time.Millisecond,
		Query:        `{"match_all": {}}`,
	}

	collector, err := NewElasticsearchCollector(cfg)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = collector.Start(ctx)
	require.NoError(t, err)
	defer collector.Stop()

	collected := make([]*models.LogEvent, 0)
	timeout := time.After(3 * time.Second)
	collecting := true

	for collecting {
		select {
		case event := <-collector.Output():
			if event != nil {
				collected = append(collected, event)
			}
			if len(collected) >= 15 {
				collecting = false
			}
		case <-timeout:
			collecting = false
		}
	}

	assert.Equal(t, 15, len(collected), "Should collect all 15 documents across 2 scroll batches")
	for i, event := range collected {
		expectedMsg := fmt.Sprintf("test message %d", i)
		assert.Equal(t, expectedMsg, event.Message)
	}
}

func TestElasticsearchCollector_ConnectionFailure_ReconnectAndCursorResume(t *testing.T) {
	var failCount int32
	var requestCount int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Elastic-Product", "Elasticsearch")
		count := atomic.AddInt32(&requestCount, 1)

		if atomic.LoadInt32(&failCount) < 2 && count <= 2 {
			atomic.AddInt32(&failCount, 1)
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}

		hits := createTestHits(0, 5)
		response := map[string]interface{}{
			"_scroll_id": "scroll-id-after-reconnect",
			"hits": map[string]interface{}{
				"total": map[string]interface{}{"value": 5},
				"hits":  hits,
			},
		}
		json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	cfg := config.ElasticsearchConfig{
		Name:         "test-es",
		Addresses:    []string{server.URL},
		Index:        "logs-*",
		ScrollSize:   100,
		PollInterval: 100 * time.Millisecond,
		Query:        `{"match_all": {}}`,
	}

	collector, err := NewElasticsearchCollector(cfg)
	require.NoError(t, err)

	initialSeq := collector.lastSeq

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = collector.Start(ctx)
	require.NoError(t, err)
	defer collector.Stop()

	collected := make([]*models.LogEvent, 0)
	timeout := time.After(3 * time.Second)
	collecting := true

	for collecting {
		select {
		case event := <-collector.Output():
			if event != nil {
				collected = append(collected, event)
			}
			if len(collected) >= 5 {
				collecting = false
			}
		case <-timeout:
			collecting = false
		}
	}

	assert.GreaterOrEqual(t, atomic.LoadInt32(&failCount), int32(2), "Should have failed at least twice before succeeding")
	assert.Greater(t, collector.lastSeq, initialSeq, "Cursor should have advanced after reconnect")
	assert.Equal(t, 5, len(collected), "Should collect all documents after reconnect")
}

func TestElasticsearchCollector_ConcurrentMultipleCollectors_Backpressure(t *testing.T) {
	const numCollectors = 5
	const eventsPerCollector = 100
	const smallBufferSize = 50

	manager := NewManager(smallBufferSize)
	servers := make([]*httptest.Server, numCollectors)

	for i := 0; i < numCollectors; i++ {
		collectorIdx := i
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("X-Elastic-Product", "Elasticsearch")
			hits := createTestHits(collectorIdx*eventsPerCollector, (collectorIdx+1)*eventsPerCollector)
			response := map[string]interface{}{
				"_scroll_id": fmt.Sprintf("scroll-id-%d", collectorIdx),
				"hits": map[string]interface{}{
					"total": map[string]interface{}{"value": eventsPerCollector},
					"hits":  hits,
				},
			}
			json.NewEncoder(w).Encode(response)
		}))
		servers[i] = server

		cfg := config.ElasticsearchConfig{
			Name:         fmt.Sprintf("collector-%d", i),
			Addresses:    []string{server.URL},
			Index:        "logs-*",
			ScrollSize:   eventsPerCollector,
			PollInterval: 50 * time.Millisecond,
			Query:        `{"match_all": {}}`,
		}

		collector, err := NewElasticsearchCollector(cfg)
		require.NoError(t, err)
		manager.AddCollector(collector)
	}

	defer func() {
		for _, s := range servers {
			s.Close()
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	err := manager.Start(ctx)
	require.NoError(t, err)

	var collectedCount int64
	var wg sync.WaitGroup

	wg.Add(1)
	go func() {
		defer wg.Done()
		for event := range manager.Output() {
			if event != nil {
				atomic.AddInt64(&collectedCount, 1)
			}
		}
	}()

	time.Sleep(3 * time.Second)
	cancel()
	manager.Stop()
	wg.Wait()

	totalExpected := numCollectors * eventsPerCollector
	assert.GreaterOrEqual(t, atomic.LoadInt64(&collectedCount), int64(totalExpected/2),
		"Should handle backpressure and collect at least half the events")
}

func TestElasticsearchCollector_InvalidDocument_SkipGracefully(t *testing.T) {
	cfg := config.ElasticsearchConfig{
		Name:         "test-es",
		Addresses:    []string{"http://localhost:9200"},
		Index:        "logs-*",
		ScrollSize:   100,
		PollInterval: 30 * time.Second,
	}

	collector, err := NewElasticsearchCollector(cfg)
	require.NoError(t, err)

	invalidHit := map[string]interface{}{
		"_index": "logs-test",
		"_id":    "doc-1",
	}

	result := collector.parseHit(invalidHit)
	assert.Nil(t, result, "Should return nil for document without _source")

	validButMalformed := map[string]interface{}{
		"_index": "logs-test",
		"_id":    "doc-2",
		"_source": map[string]interface{}{
			"message": 12345,
			"level":   999,
		},
	}

	result2 := collector.parseHit(validButMalformed)
	assert.NotNil(t, result2, "Should handle malformed fields gracefully")
	assert.Equal(t, "", result2.Message, "Non-string message should be empty")
	assert.Equal(t, models.LevelUnknown, result2.Level, "Invalid level should default to unknown")
}

func createTestHits(start, end int) []interface{} {
	hits := make([]interface{}, 0, end-start)
	for i := start; i < end; i++ {
		event := testdata.NewLogEvent(
			testdata.WithTimestamp(time.Now().Add(time.Duration(i)*time.Second)),
			testdata.WithMessage(fmt.Sprintf("test message %d", i)),
			testdata.WithLevel(models.LogLevel([]string{"INFO", "WARN", "ERROR"}[i%3])),
		)
		hits = append(hits, testdata.NewESHit(testdata.NewESDocument(event), fmt.Sprintf("doc-%d", i)))
	}
	return hits
}
