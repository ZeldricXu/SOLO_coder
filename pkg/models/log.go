package models

import (
	"time"
)

type LogEntry struct {
	ID          string                 `json:"id"`
	Timestamp   time.Time              `json:"timestamp"`
	Severity    SeverityLevel          `json:"severity"`
	Message     string                 `json:"message"`
	ServiceName string                 `json:"service_name,omitempty"`
	Hostname    string                 `json:"hostname,omitempty"`
	TraceID     string                 `json:"trace_id,omitempty"`
	SpanID      string                 `json:"span_id,omitempty"`
	Attributes  map[string]interface{} `json:"attributes,omitempty"`
	Resource    map[string]interface{} `json:"resource,omitempty"`
	Parsed      bool                   `json:"parsed"`
}

type LogPipelineConfig struct {
	Parsers    []LogParserConfig   `json:"parsers,omitempty"`
	Filters    []LogFilterConfig   `json:"filters,omitempty"`
	Processors []LogProcessorConfig `json:"processors,omitempty"`
	Outputs    []LogOutputConfig   `json:"outputs,omitempty"`
}

type LogParserConfig struct {
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
}

type LogFilterConfig struct {
	Expression string                 `json:"expression"`
	Config     map[string]interface{} `json:"config,omitempty"`
}

type LogProcessorConfig struct {
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
}

type LogOutputConfig struct {
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
}
