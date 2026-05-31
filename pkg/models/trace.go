package models

import (
	"time"
)

type SpanKind string

const (
	SpanKindUnspecified SpanKind = "UNSPECIFIED"
	SpanKindInternal    SpanKind = "INTERNAL"
	SpanKindServer      SpanKind = "SERVER"
	SpanKindClient      SpanKind = "CLIENT"
	SpanKindProducer    SpanKind = "PRODUCER"
	SpanKindConsumer    SpanKind = "CONSUMER"
)

type SpanStatus struct {
	Code    int32  `json:"code"`
	Message string `json:"message,omitempty"`
}

type AttributeValue struct {
	StringValue *string  `json:"string_value,omitempty"`
	IntValue    *int64   `json:"int_value,omitempty"`
	DoubleValue *float64 `json:"double_value,omitempty"`
	BoolValue   *bool    `json:"bool_value,omitempty"`
}

type Event struct {
	Timestamp  time.Time              `json:"timestamp"`
	Name       string                 `json:"name"`
	Attributes map[string]interface{} `json:"attributes,omitempty"`
}

type Link struct {
	TraceID    string                 `json:"trace_id"`
	SpanID     string                 `json:"span_id"`
	TraceState string                 `json:"trace_state,omitempty"`
	Attributes map[string]interface{} `json:"attributes,omitempty"`
}

type Span struct {
	TraceID           string                 `json:"trace_id"`
	SpanID            string                 `json:"span_id"`
	TraceState        string                 `json:"trace_state,omitempty"`
	ParentSpanID      string                 `json:"parent_span_id,omitempty"`
	Name              string                 `json:"name"`
	Kind              SpanKind               `json:"kind"`
	StartTime         time.Time              `json:"start_time"`
	EndTime           time.Time              `json:"end_time"`
	Duration          time.Duration          `json:"duration"`
	Attributes        map[string]interface{} `json:"attributes,omitempty"`
	Events            []Event                `json:"events,omitempty"`
	Links             []Link                 `json:"links,omitempty"`
	Status            SpanStatus             `json:"status"`
	Resource          map[string]interface{} `json:"resource,omitempty"`
	Instrumentation   InstrumentationScope   `json:"instrumentation,omitempty"`
	Sampled           bool                   `json:"sampled"`
	SamplingPriority  float64                `json:"sampling_priority,omitempty"`
	ServiceName       string                 `json:"service_name,omitempty"`
	OperationName     string                 `json:"operation_name,omitempty"`
	HTTPStatusCode    *int                   `json:"http_status_code,omitempty"`
	HTTPMethod        string                 `json:"http_method,omitempty"`
	HTTPURL           string                 `json:"http_url,omitempty"`
}

type InstrumentationScope struct {
	Name    string `json:"name"`
	Version string `json:"version,omitempty"`
}

type Trace struct {
	TraceID    string    `json:"trace_id"`
	Spans      []Span    `json:"spans"`
	StartTime  time.Time `json:"start_time"`
	EndTime    time.Time `json:"end_time"`
	Duration   time.Duration `json:"duration"`
	ServiceCount int     `json:"service_count"`
	SpanCount  int       `json:"span_count"`
	ErrorCount int       `json:"error_count"`
	RootService string   `json:"root_service,omitempty"`
}

type ServiceNode struct {
	ServiceName   string                 `json:"service_name"`
	InstanceCount int                    `json:"instance_count"`
	Attributes    map[string]interface{} `json:"attributes,omitempty"`
}

type ServiceEdge struct {
	FromService string        `json:"from_service"`
	ToService   string        `json:"to_service"`
	CallCount   int64         `json:"call_count"`
	ErrorCount  int64         `json:"error_count"`
	AvgLatency  time.Duration `json:"avg_latency"`
	P50Latency  time.Duration `json:"p50_latency"`
	P95Latency  time.Duration `json:"p95_latency"`
	P99Latency  time.Duration `json:"p99_latency"`
}

type ServiceTopology struct {
	Nodes        []ServiceNode          `json:"nodes"`
	Edges        []ServiceEdge          `json:"edges"`
	GeneratedAt  time.Time              `json:"generated_at"`
	TimeWindow   time.Duration          `json:"time_window"`
	Metadata     map[string]interface{} `json:"metadata,omitempty"`
}
