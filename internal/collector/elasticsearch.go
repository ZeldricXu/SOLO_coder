package collector

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	es "github.com/elastic/go-elasticsearch/v8"
	"github.com/elastic/go-elasticsearch/v8/esapi"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
)

type ElasticsearchCollector struct {
	*BaseCollector
	cfg     config.ElasticsearchConfig
	client  *es.Client
	lastSeq int64
}

func NewElasticsearchCollector(cfg config.ElasticsearchConfig) (*ElasticsearchCollector, error) {
	esCfg := es.Config{
		Addresses: cfg.Addresses,
		Username:  cfg.Username,
		Password:  cfg.Password,
	}

	client, err := es.NewClient(esCfg)
	if err != nil {
		return nil, fmt.Errorf("failed to create elasticsearch client: %w", err)
	}

	return &ElasticsearchCollector{
		BaseCollector: NewBaseCollector(cfg.Name, models.SourceElasticsearch, 1000),
		cfg:           cfg,
		client:        client,
		lastSeq:       time.Now().UnixMilli(),
	}, nil
}

func (c *ElasticsearchCollector) Start(ctx context.Context) error {
	if c.IsRunning() {
		return nil
	}
	c.SetRunning(true)

	c.wg.Add(1)
	go c.pollLoop(ctx)

	log.Printf("Elasticsearch collector started: %s", c.name)
	return nil
}

func (c *ElasticsearchCollector) pollLoop(ctx context.Context) {
	defer c.wg.Done()

	ticker := time.NewTicker(c.cfg.PollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-c.stopCh:
			return
		case <-ticker.C:
			if err := c.scrollAndProcess(ctx); err != nil {
				log.Printf("Elasticsearch scroll error [%s]: %v", c.name, err)
			}
		}
	}
}

func (c *ElasticsearchCollector) scrollAndProcess(ctx context.Context) error {
	query := c.buildQuery()

	scrollID := ""
	for {
		var req esapi.SearchRequest
		if scrollID == "" {
			req = esapi.SearchRequest{
				Index:        []string{c.cfg.Index},
				Body:         strings.NewReader(query),
				Scroll:       1 * time.Minute,
				Size:         c.cfg.ScrollSize,
				DocumentType: "_doc",
			}
		} else {
			req = esapi.ScrollRequest{
				ScrollID: scrollID,
				Scroll:   1 * time.Minute,
			}
		}

		res, err := req.Do(ctx, c.client)
		if err != nil {
			return fmt.Errorf("search request failed: %w", err)
		}
		defer res.Body.Close()

		if res.IsError() {
			var e map[string]interface{}
			if err := json.NewDecoder(res.Body).Decode(&e); err != nil {
				return fmt.Errorf("error parsing the response body: %w", err)
			}
			return fmt.Errorf("elasticsearch error: %v", e["error"])
		}

		var result map[string]interface{}
		if err := json.NewDecoder(res.Body).Decode(&result); err != nil {
			return fmt.Errorf("failed to decode response: %w", err)
		}

		hits, ok := result["hits"].(map[string]interface{})
		if !ok {
			break
		}

		hitList, ok := hits["hits"].([]interface{})
		if !ok || len(hitList) == 0 {
			break
		}

		for _, hit := range hitList {
			event := c.parseHit(hit.(map[string]interface{}))
			if event != nil {
				c.Emit(event)
			}
		}

		newScrollID, _ := result["_scroll_id"].(string)
		if newScrollID == scrollID || len(hitList) < c.cfg.ScrollSize {
			if newScrollID != "" {
				clearReq := esapi.ClearScrollRequest{ScrollID: []string{newScrollID}}
				_, _ = clearReq.Do(ctx, c.client)
			}
			break
		}
		scrollID = newScrollID
	}

	return nil
}

func (c *ElasticsearchCollector) buildQuery() string {
	timeField := c.cfg.TimeField
	if timeField == "" {
		timeField = "@timestamp"
	}

	query := fmt.Sprintf(`{
		"query": {
			"bool": {
				"must": [
					{
						"range": {
							"%s": {
								"gt": %d
							}
						}
					}
				],
				"filter": []
			}
		},
		"sort": [
			{"%s": {"order": "asc"}}
		]
	}`, timeField, c.lastSeq, timeField)

	return query
}

func (c *ElasticsearchCollector) parseHit(hit map[string]interface{}) *models.LogEvent {
	source, ok := hit["_source"].(map[string]interface{})
	if !ok {
		return nil
	}

	event := models.NewLogEvent()
	event.Source = models.SourceElasticsearch
	event.SourceID = c.cfg.Name
	event.RawMessage = c.extractString(source, c.cfg.MessageField, "message")
	event.Message = event.RawMessage
	event.Level = models.ParseLogLevel(c.extractString(source, c.cfg.LevelField, "level"))
	event.Host = c.extractString(source, "host", "hostname")
	event.ServiceName = c.extractString(source, "service.name", "service", "app")
	event.TraceID = c.extractString(source, "trace_id", "traceId", "X-B3-TraceId")
	event.SpanID = c.extractString(source, "span_id", "spanId", "X-B3-SpanId")
	event.ClientIP = c.extractString(source, "client_ip", "remote_addr", "ip")
	event.UserID = c.extractString(source, "user_id", "userId", "uid")
	event.StatusCode = c.extractInt(source, "status_code", "status")
	event.ResponseTime = c.extractInt64(source, "response_time", "latency", "duration_ms")
	event.ErrorCode = c.extractString(source, "error_code", "err_code", "code")
	event.OriginalIndex = c.extractString(hit, "_index")

	if ts := c.extractTime(source, c.cfg.TimeField, "@timestamp", "timestamp"); !ts.IsZero() {
		event.Timestamp = ts
		if ts.UnixMilli() > c.lastSeq {
			c.lastSeq = ts.UnixMilli()
		}
	}

	for k, v := range source {
		if _, exists := event.ParsedFields[k]; !exists {
			event.ParsedFields[k] = v
		}
	}

	return event
}

func (c *ElasticsearchCollector) extractString(data map[string]interface{}, keys ...string) string {
	for _, key := range keys {
		if val, ok := data[key]; ok {
			if str, ok := val.(string); ok && str != "" {
				return str
			}
		}
	}
	return ""
}

func (c *ElasticsearchCollector) extractInt(data map[string]interface{}, keys ...string) int {
	for _, key := range keys {
		if val, ok := data[key]; ok {
			switch v := val.(type) {
			case int:
				return v
			case float64:
				return int(v)
			}
		}
	}
	return 0
}

func (c *ElasticsearchCollector) extractInt64(data map[string]interface{}, keys ...string) int64 {
	for _, key := range keys {
		if val, ok := data[key]; ok {
			switch v := val.(type) {
			case int64:
				return v
			case int:
				return int64(v)
			case float64:
				return int64(v)
			}
		}
	}
	return 0
}

func (c *ElasticsearchCollector) extractTime(data map[string]interface{}, keys ...string) time.Time {
	for _, key := range keys {
		if val, ok := data[key]; ok {
			switch v := val.(type) {
			case string:
				if t, err := time.Parse(time.RFC3339Nano, v); err == nil {
					return t
				}
				if t, err := time.Parse("2006-01-02T15:04:05.000Z07:00", v); err == nil {
					return t
				}
			case float64:
				return time.UnixMilli(int64(v))
			case int64:
				return time.UnixMilli(v)
			}
		}
	}
	return time.Time{}
}
