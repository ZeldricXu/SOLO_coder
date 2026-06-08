package collector

import (
	"context"
	"encoding/json"
	"log"
	"strconv"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
)

type KafkaCollector struct {
	*BaseCollector
	cfg    config.KafkaCollectorConfig
	reader *kafka.Reader
}

func NewKafkaCollector(cfg config.KafkaCollectorConfig) (*KafkaCollector, error) {
	if cfg.GroupID == "" {
		cfg.GroupID = "log-analyzer-collector"
	}

	return &KafkaCollector{
		BaseCollector: NewBaseCollector(cfg.Name, models.SourceKafka, 1000),
		cfg:           cfg,
	}, nil
}

func (c *KafkaCollector) Start(ctx context.Context) error {
	if c.IsRunning() {
		return nil
	}

	c.reader = kafka.NewReader(kafka.ReaderConfig{
		Brokers:   c.cfg.Brokers,
		Topic:     c.cfg.Topic,
		GroupID:   c.cfg.GroupID,
		Partition: c.cfg.Partition,
		MinBytes:  10e3,
		MaxBytes:  10e6,
		MaxWait:   1 * time.Second,
	})

	c.SetRunning(true)
	c.wg.Add(1)
	go c.consumeLoop(ctx)

	log.Printf("Kafka collector started: %s (topic: %s)", c.name, c.cfg.Topic)
	return nil
}

func (c *KafkaCollector) consumeLoop(ctx context.Context) {
	defer c.wg.Done()
	defer c.reader.Close()

	for {
		select {
		case <-ctx.Done():
			return
		case <-c.stopCh:
			return
		default:
		}

		msg, err := c.reader.ReadMessage(ctx)
		if err != nil {
			if err == context.Canceled {
				return
			}
			log.Printf("Kafka read error [%s]: %v", c.name, err)
			time.Sleep(1 * time.Second)
			continue
		}

		event := c.parseMessage(msg)
		if event != nil {
			c.Emit(event)
		}
	}
}

func (c *KafkaCollector) parseMessage(msg kafka.Message) *models.LogEvent {
	event := models.NewLogEvent()
	event.Source = models.SourceKafka
	event.SourceID = c.cfg.Name
	event.RawMessage = string(msg.Value)
	event.Message = event.RawMessage

	if !msg.Time.IsZero() {
		event.Timestamp = msg.Time
	} else {
		event.Timestamp = time.Now()
	}

	for _, h := range msg.Headers {
		switch h.Key {
		case "level", "severity":
			event.Level = models.ParseLogLevel(string(h.Value))
		case "service", "service_name", "app":
			event.ServiceName = string(h.Value)
		case "host", "hostname":
			event.Host = string(h.Value)
		case "trace_id", "traceId":
			event.TraceID = string(h.Value)
		case "span_id", "spanId":
			event.SpanID = string(h.Value)
		case "user_id", "userId":
			event.UserID = string(h.Value)
		case "client_ip", "ip":
			event.ClientIP = string(h.Value)
		case "error_code", "err_code":
			event.ErrorCode = string(h.Value)
		case "status", "status_code":
			if code, err := strconv.Atoi(string(h.Value)); err == nil {
				event.StatusCode = code
			}
		case "response_time", "latency":
			if rt, err := strconv.ParseInt(string(h.Value), 10, 64); err == nil {
				event.ResponseTime = rt
			}
		default:
			if event.Labels == nil {
				event.Labels = make(map[string]string)
			}
			event.Labels[h.Key] = string(h.Value)
		}
	}

	c.tryParseJSON(event)

	if event.Level == models.LevelUnknown {
		event.Level = c.detectLevel(event.Message)
	}

	return event
}

func (c *KafkaCollector) tryParseJSON(event *models.LogEvent) {
	var jsonData map[string]interface{}
	if err := json.Unmarshal([]byte(event.RawMessage), &jsonData); err != nil {
		return
	}

	event.ParsedFields = jsonData

	if ts, ok := c.extractTime(jsonData, "timestamp", "@timestamp", "time"); ok {
		event.Timestamp = ts
	}

	if level, ok := c.extractString(jsonData, "level", "severity", "log_level"); ok {
		event.Level = models.ParseLogLevel(level)
	}

	if msg, ok := c.extractString(jsonData, "message", "msg", "log_message"); ok {
		event.Message = msg
	}

	if service, ok := c.extractString(jsonData, "service", "service_name", "app"); ok {
		event.ServiceName = service
	}

	if host, ok := c.extractString(jsonData, "host", "hostname", "host_name"); ok {
		event.Host = host
	}

	if traceID, ok := c.extractString(jsonData, "trace_id", "traceId", "X-B3-TraceId"); ok {
		event.TraceID = traceID
	}

	if spanID, ok := c.extractString(jsonData, "span_id", "spanId", "X-B3-SpanId"); ok {
		event.SpanID = spanID
	}

	if userID, ok := c.extractString(jsonData, "user_id", "userId", "uid"); ok {
		event.UserID = userID
	}

	if ip, ok := c.extractString(jsonData, "client_ip", "remote_addr", "ip"); ok {
		event.ClientIP = ip
	}

	if errCode, ok := c.extractString(jsonData, "error_code", "err_code", "code"); ok {
		event.ErrorCode = errCode
	}

	if status, ok := c.extractInt(jsonData, "status", "status_code", "http_status"); ok {
		event.StatusCode = status
	}

	if rt, ok := c.extractInt64(jsonData, "response_time", "latency", "duration_ms"); ok {
		event.ResponseTime = rt
	}
}

func (c *KafkaCollector) extractString(data map[string]interface{}, keys ...string) (string, bool) {
	for _, key := range keys {
		if val, ok := data[key]; ok {
			if str, ok := val.(string); ok && str != "" {
				return str, true
			}
		}
	}
	return "", false
}

func (c *KafkaCollector) extractInt(data map[string]interface{}, keys ...string) (int, bool) {
	for _, key := range keys {
		if val, ok := data[key]; ok {
			switch v := val.(type) {
			case int:
				return v, true
			case float64:
				return int(v), true
			}
		}
	}
	return 0, false
}

func (c *KafkaCollector) extractInt64(data map[string]interface{}, keys ...string) (int64, bool) {
	for _, key := range keys {
		if val, ok := data[key]; ok {
			switch v := val.(type) {
			case int64:
				return v, true
			case int:
				return int64(v), true
			case float64:
				return int64(v), true
			}
		}
	}
	return 0, false
}

func (c *KafkaCollector) extractTime(data map[string]interface{}, keys ...string) (time.Time, bool) {
	for _, key := range keys {
		if val, ok := data[key]; ok {
			switch v := val.(type) {
			case string:
				if t, err := time.Parse(time.RFC3339Nano, v); err == nil {
					return t, true
				}
				if t, err := time.Parse("2006-01-02T15:04:05.000Z07:00", v); err == nil {
					return t, true
				}
			case float64:
				return time.UnixMilli(int64(v)), true
			case int64:
				return time.UnixMilli(v), true
			}
		}
	}
	return time.Time{}, false
}

func (c *KafkaCollector) detectLevel(message string) models.LogLevel {
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

func (c *KafkaCollector) Stop() error {
	if err := c.BaseCollector.Stop(); err != nil {
		return err
	}
	if c.reader != nil {
		return c.reader.Close()
	}
	return nil
}
