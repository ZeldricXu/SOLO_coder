package models

import "time"

type LogQueryRequest struct {
	StartTime   time.Time `json:"start_time"`
	EndTime     time.Time `json:"end_time"`
	ServiceName string    `json:"service_name"`
	Keywords    string    `json:"keywords"`
	Level       LogLevel  `json:"level,omitempty"`
	TraceID     string    `json:"trace_id,omitempty"`
	ErrorCode   string    `json:"error_code,omitempty"`
	Page        int       `json:"page"`
	PageSize    int       `json:"page_size"`
}

type LogQueryResponse struct {
	Total        int64                  `json:"total"`
	Logs         []*LogEvent            `json:"logs"`
	TimeSeries   []*TimeSeriesPoint     `json:"time_series"`
	Distribution []*DistributionBucket  `json:"distribution"`
}

type TimeSeriesPoint struct {
	Timestamp time.Time `json:"timestamp"`
	Count     int64     `json:"count"`
	ErrorCount int64    `json:"error_count"`
	P50Latency float64  `json:"p50_latency"`
	P99Latency float64  `json:"p99_latency"`
}

type DistributionBucket struct {
	Key    string `json:"key"`
	Count  int64  `json:"count"`
	Percentage float64 `json:"percentage"`
}

type EventChain struct {
	TraceID     string      `json:"trace_id"`
	StartTime   time.Time   `json:"start_time"`
	EndTime     time.Time   `json:"end_time"`
	Duration    int64       `json:"duration_ms"`
	Services    []string    `json:"services"`
	Events      []*LogEvent `json:"events"`
	HasError    bool        `json:"has_error"`
	ErrorCode   string      `json:"error_code,omitempty"`
	RootService string      `json:"root_service,omitempty"`
}
