package models

import (
	"time"

	"github.com/google/uuid"
)

type LogLevel string

const (
	LevelDebug   LogLevel = "DEBUG"
	LevelInfo    LogLevel = "INFO"
	LevelWarn    LogLevel = "WARN"
	LevelError   LogLevel = "ERROR"
	LevelFatal   LogLevel = "FATAL"
	LevelUnknown LogLevel = "UNKNOWN"
)

type LogSource string

const (
	SourceElasticsearch LogSource = "elasticsearch"
	SourceLoki          LogSource = "loki"
	SourceKafka         LogSource = "kafka"
	SourceSyslog        LogSource = "syslog"
)

type LogEvent struct {
	ID            string                 `json:"id"`
	Timestamp     time.Time              `json:"timestamp"`
	ReceivedAt    time.Time              `json:"received_at"`
	Source        LogSource              `json:"source"`
	SourceID      string                 `json:"source_id"`
	ServiceName   string                 `json:"service_name"`
	Host          string                 `json:"host"`
	Level         LogLevel               `json:"level"`
	Message       string                 `json:"message"`
	RawMessage    string                 `json:"raw_message"`
	TraceID       string                 `json:"trace_id"`
	SpanID        string                 `json:"span_id"`
	UserID        string                 `json:"user_id"`
	ClientIP      string                 `json:"client_ip"`
	GeoLocation   *GeoLocation           `json:"geo_location,omitempty"`
	StatusCode    int                    `json:"status_code"`
	ResponseTime  int64                  `json:"response_time_ms"`
	ErrorCode     string                 `json:"error_code"`
	ErrorDesc     string                 `json:"error_description"`
	Tags          []string               `json:"tags,omitempty"`
	ParsedFields  map[string]interface{} `json:"parsed_fields,omitempty"`
	Labels        map[string]string      `json:"labels,omitempty"`
	OriginalIndex string                 `json:"original_index,omitempty"`
}

type GeoLocation struct {
	Country   string  `json:"country"`
	City      string  `json:"city"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
	ISP       string  `json:"isp,omitempty"`
}

func NewLogEvent() *LogEvent {
	return &LogEvent{
		ID:            uuid.New().String(),
		ReceivedAt:    time.Now(),
		ParsedFields:  make(map[string]interface{}),
		Labels:        make(map[string]string),
		Tags:          make([]string, 0),
	}
}

func ParseLogLevel(level string) LogLevel {
	switch level {
	case "DEBUG", "debug":
		return LevelDebug
	case "INFO", "info":
		return LevelInfo
	case "WARN", "warn", "WARNING", "warning":
		return LevelWarn
	case "ERROR", "error":
		return LevelError
	case "FATAL", "fatal", "CRITICAL", "critical":
		return LevelFatal
	default:
		return LevelUnknown
	}
}
