package domain

import (
	"time"
)

type SpanStatus string

const (
	SpanStatusUnset SpanStatus = "unset"
	SpanStatusOK    SpanStatus = "ok"
	SpanStatusError SpanStatus = "error"
)

type SamplingDecision string

const (
	SamplingDecisionDrop    SamplingDecision = "drop"
	SamplingDecisionRecord  SamplingDecision = "record"
	SamplingDecisionRecordAndSample SamplingDecision = "record_and_sample"
)

type TraceSpan struct {
	SpanID        string                 `json:"span_id" gorm:"primaryKey;type:varchar(32)"`
	TraceID       string                 `json:"trace_id" gorm:"type:varchar(32);index"`
	ParentSpanID  string                 `json:"parent_span_id,omitempty" gorm:"type:varchar(32);index"`
	Name          string                 `json:"name" gorm:"index"`
	ServiceName   string                 `json:"service_name" gorm:"index"`
	Kind          string                 `json:"kind" gorm:"type:varchar(32)"`
	Status        SpanStatus             `json:"status" gorm:"type:varchar(16);index"`
	StatusCode    int32                  `json:"status_code"`
	StatusMessage string                 `json:"status_message"`
	Attributes    map[string]interface{} `json:"attributes" gorm:"type:jsonb"`
	StartTime     time.Time              `json:"start_time" gorm:"index"`
	EndTime       time.Time              `json:"end_time"`
	DurationNano  int64                  `json:"duration_nano"`
	Sampled       bool                   `json:"sampled" gorm:"index"`
	CreatedAt     time.Time              `json:"created_at"`
}

func (TraceSpan) TableName() string {
	return "trace_spans"
}

type SamplingPolicy struct {
	PolicyID    string                 `json:"policy_id" gorm:"primaryKey;type:varchar(64)"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	RuleType    string                 `json:"rule_type" gorm:"type:varchar(32)"`
	Rules       map[string]interface{} `json:"rules" gorm:"type:jsonb"`
	SampleRate  float64                `json:"sample_rate"`
	Priority    int32                  `json:"priority"`
	Enabled     bool                   `json:"enabled" gorm:"index"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

func (SamplingPolicy) TableName() string {
	return "sampling_policies"
}

type TraceSummary struct {
	SummaryID    string    `json:"summary_id" gorm:"primaryKey;type:varchar(64)"`
	TraceID      string    `json:"trace_id" gorm:"uniqueIndex;type:varchar(32)"`
	SpanCount    int32     `json:"span_count"`
	ServiceCount int32     `json:"service_count"`
	HasError     bool      `json:"has_error" gorm:"index"`
	TotalDurationNano int64 `json:"total_duration_nano"`
	RootService  string    `json:"root_service"`
	RootOperation string   `json:"root_operation"`
	StartTime    time.Time `json:"start_time" gorm:"index"`
	EndTime      time.Time `json:"end_time"`
}

func (TraceSummary) TableName() string {
	return "trace_summaries"
}
