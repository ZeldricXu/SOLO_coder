package collector

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
)

type LokiCollector struct {
	*BaseCollector
	cfg       config.LokiConfig
	client    *http.Client
	lastTime  time.Time
}

type LokiQueryResponse struct {
	Status string `json:"status"`
	Data   struct {
		ResultType string `json:"resultType"`
		Result     []struct {
			Stream map[string]string `json:"stream"`
			Values [][]interface{}   `json:"values"`
		} `json:"result"`
	} `json:"data"`
}

func NewLokiCollector(cfg config.LokiConfig) (*LokiCollector, error) {
	if cfg.Range == 0 {
		cfg.Range = 15 * time.Minute
	}
	if cfg.Step == 0 {
		cfg.Step = 1 * time.Minute
	}
	if cfg.PollInterval == 0 {
		cfg.PollInterval = 30 * time.Second
	}

	return &LokiCollector{
		BaseCollector: NewBaseCollector(cfg.Name, models.SourceLoki, 1000),
		cfg:           cfg,
		client:        &http.Client{Timeout: 30 * time.Second},
		lastTime:      time.Now().Add(-cfg.Range),
	}, nil
}

func (c *LokiCollector) Start(ctx context.Context) error {
	if c.IsRunning() {
		return nil
	}
	c.SetRunning(true)

	c.wg.Add(1)
	go c.pollLoop(ctx)

	log.Printf("Loki collector started: %s", c.name)
	return nil
}

func (c *LokiCollector) pollLoop(ctx context.Context) {
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
			if err := c.queryAndProcess(ctx); err != nil {
				log.Printf("Loki query error [%s]: %v", c.name, err)
			}
		}
	}
}

func (c *LokiCollector) queryAndProcess(ctx context.Context) error {
	start := c.lastTime
	end := time.Now()

	resp, err := c.rangeQuery(ctx, start, end)
	if err != nil {
		return fmt.Errorf("loki range query failed: %w", err)
	}

	if resp.Status != "success" {
		return fmt.Errorf("loki returned non-success status: %s", resp.Status)
	}

	for _, result := range resp.Data.Result {
		for _, value := range result.Values {
			if len(value) < 2 {
				continue
			}

			tsNanoStr, ok := value[0].(string)
			if !ok {
				continue
			}
			tsNano, err := strconv.ParseInt(tsNanoStr, 10, 64)
			if err != nil {
				continue
			}
			ts := time.Unix(0, tsNano)

			msg, ok := value[1].(string)
			if !ok {
				continue
			}

			event := c.parseEntry(ts, msg, result.Stream)
			if event != nil {
				c.Emit(event)
				if ts.After(c.lastTime) {
					c.lastTime = ts
				}
			}
		}
	}

	return nil
}

func (c *LokiCollector) rangeQuery(ctx context.Context, start, end time.Time) (*LokiQueryResponse, error) {
	query := c.cfg.Query
	if query == "" {
		query = `{job=~".+"}`
	}

	params := url.Values{}
	params.Set("query", query)
	params.Set("start", strconv.FormatInt(start.UnixNano(), 10))
	params.Set("end", strconv.FormatInt(end.UnixNano(), 10))
	params.Set("step", fmt.Sprintf("%ds", int(c.cfg.Step.Seconds())))
	params.Set("limit", "1000")

	reqURL := fmt.Sprintf("%s/loki/api/v1/query_range?%s", strings.TrimRight(c.cfg.Address, "/"), params.Encode())

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	if c.cfg.Username != "" {
		req.SetBasicAuth(c.cfg.Username, c.cfg.Password)
	}

	resp, err := c.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("unexpected status %d: %s", resp.StatusCode, string(body))
	}

	var result LokiQueryResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("failed to decode response: %w", err)
	}

	return &result, nil
}

func (c *LokiCollector) parseEntry(timestamp time.Time, message string, labels map[string]string) *models.LogEvent {
	event := models.NewLogEvent()
	event.Source = models.SourceLoki
	event.SourceID = c.cfg.Name
	event.Timestamp = timestamp
	event.RawMessage = message
	event.Message = message
	event.Labels = make(map[string]string)

	for k, v := range labels {
		event.Labels[k] = v

		switch k {
		case "level", "severity":
			event.Level = models.ParseLogLevel(v)
		case "host", "hostname", "node":
			event.Host = v
		case "service", "service_name", "app":
			event.ServiceName = v
		case "trace_id", "traceId":
			event.TraceID = v
		case "span_id", "spanId":
			event.SpanID = v
		case "user_id", "userId":
			event.UserID = v
		case "client_ip", "ip", "remote_addr":
			event.ClientIP = v
		case "error_code", "err_code":
			event.ErrorCode = v
		case "status", "status_code":
			if code, err := strconv.Atoi(v); err == nil {
				event.StatusCode = code
			}
		}
	}

	if event.Level == models.LevelUnknown {
		event.Level = c.detectLevel(message)
	}

	if event.ServiceName == "" {
		if job, ok := labels["job"]; ok {
			event.ServiceName = job
		}
	}

	return event
}

func (c *LokiCollector) detectLevel(message string) models.LogLevel {
	upper := strings.ToUpper(message)
	if strings.Contains(upper, "ERROR") || strings.Contains(upper, "ERR") {
		return models.LevelError
	}
	if strings.Contains(upper, "WARN") || strings.Contains(upper, "WARNING") {
		return models.LevelWarn
	}
	if strings.Contains(upper, "FATAL") || strings.Contains(upper, "CRITICAL") {
		return models.LevelFatal
	}
	if strings.Contains(upper, "DEBUG") {
		return models.LevelDebug
	}
	if strings.Contains(upper, "INFO") {
		return models.LevelInfo
	}
	return models.LevelUnknown
}
