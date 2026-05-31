package models

import "time"

type SLIConfig struct {
	SLIID         string            `json:"sli_id"`
	Name          string            `json:"name"`
	Description   string            `json:"description,omitempty"`
	MetricName    string            `json:"metric_name"`
	Labels        map[string]string `json:"labels,omitempty"`
	Goal          float64           `json:"goal"`
	TargetPercent float64           `json:"target_percent"`
	CreatedAt     time.Time         `json:"created_at"`
	UpdatedAt     time.Time         `json:"updated_at"`
}

type SLOConfig struct {
	SLOID       string            `json:"slo_id"`
	Name        string            `json:"name"`
	Description string            `json:"description,omitempty"`
	SLIID       string            `json:"sli_id"`
	Target      float64           `json:"target"`
	Window      string            `json:"window"`
	ErrorBudget float64           `json:"error_budget"`
	Labels      map[string]string `json:"labels,omitempty"`
	CreatedAt   time.Time         `json:"created_at"`
	UpdatedAt   time.Time         `json:"updated_at"`
}

type SLIMetric struct {
	SLIID        string            `json:"sli_id"`
	Timestamp    time.Time         `json:"timestamp"`
	Value        float64           `json:"value"`
	GoodEvents   int64             `json:"good_events"`
	TotalEvents  int64             `json:"total_events"`
	Labels       map[string]string `json:"labels,omitempty"`
}

type ErrorBudgetState struct {
	SLOID            string    `json:"slo_id"`
	RemainingBudget  float64   `json:"remaining_budget"`
	ConsumedBudget   float64   `json:"consumed_budget"`
	TotalBudget      float64   `json:"total_budget"`
	BurnRate         float64   `json:"burn_rate"`
	WindowStart      time.Time `json:"window_start"`
	WindowEnd        time.Time `json:"window_end"`
	LastUpdated      time.Time `json:"last_updated"`
}

type BurnRateAlert struct {
	AlertID       string    `json:"alert_id"`
	SLOID         string    `json:"slo_id"`
	BurnRate      float64   `json:"burn_rate"`
	Threshold     float64   `json:"threshold"`
	Severity      string    `json:"severity"`
	Status        string    `json:"status"`
	TriggeredAt   time.Time `json:"triggered_at"`
	ResolvedAt    *time.Time `json:"resolved_at,omitempty"`
}

type Entity struct {
	ID         string            `json:"id"`
	Type       string            `json:"type"`
	Status     string            `json:"status"`
	Attributes map[string]string `json:"attributes"`
	CreatedAt  time.Time         `json:"created_at"`
	UpdatedAt  time.Time         `json:"updated_at"`
}

type Config struct {
	ConfigID   string                 `json:"config_id"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID        string     `json:"run_id"`
	EntityID     string     `json:"entity_id"`
	Phase        string     `json:"phase"`
	Progress     float64    `json:"progress"`
	StartedAt    time.Time  `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at"`
	ErrorDetail  *string    `json:"error_detail"`
}

type MetricsSnapshot struct {
	SnapshotID string                 `json:"snapshot_id"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics"`
	Dimensions map[string]string      `json:"dimensions"`
}

type Span struct {
	TraceID    string                 `json:"trace_id"`
	SpanID     string                 `json:"span_id"`
	ParentID   string                 `json:"parent_id"`
	Service    string                 `json:"service"`
	Operation  string                 `json:"operation"`
	StartTime  time.Time              `json:"start_time"`
	EndTime    time.Time              `json:"end_time"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `json:"attributes"`
}

type AlertRule struct {
	RuleID      string                 `json:"rule_id"`
	Name        string                 `json:"name"`
	Metric      string                 `json:"metric"`
	Condition   string                 `json:"condition"`
	Threshold   float64                `json:"threshold"`
	Duration    time.Duration          `json:"duration"`
	Enabled     bool                   `json:"enabled"`
	Labels      map[string]string      `json:"labels"`
	NotifyChan  []string               `json:"notify_channels"`
}

type Alert struct {
	AlertID    string                 `json:"alert_id"`
	RuleID     string                 `json:"rule_id"`
	RuleName   string                 `json:"rule_name"`
	Metric     string                 `json:"metric"`
	Value      float64                `json:"value"`
	Threshold  float64                `json:"threshold"`
	Status     string                 `json:"status"`
	Labels     map[string]string      `json:"labels"`
	StartedAt  time.Time              `json:"started_at"`
	ResolvedAt *time.Time             `json:"resolved_at"`
}

type TopologyNode struct {
	Service    string            `json:"service"`
	Instance   string            `json:"instance"`
	Metadata   map[string]string `json:"metadata"`
}

type TopologyEdge struct {
	From       string            `json:"from"`
	To         string            `json:"to"`
	Count      int64             `json:"count"`
	LatencyP50 float64           `json:"latency_p50"`
	LatencyP99 float64           `json:"latency_p99"`
	ErrorRate  float64           `json:"error_rate"`
}

type ServiceTopology struct {
	Nodes []TopologyNode `json:"nodes"`
	Edges []TopologyEdge `json:"edges"`
}

type ProfileData struct {
	ProfileID string                 `json:"profile_id"`
	Type      string                 `json:"type"`
	StartTime time.Time              `json:"start_time"`
	EndTime   time.Time              `json:"end_time"`
	Samples   int                    `json:"samples"`
	Data      map[string]interface{} `json:"data"`
}

type APIRequest struct {
	TraceID    string                 `json:"trace_id"`
	Namespace  string                 `json:"namespace"`
	EntityType string                 `json:"entity_type"`
	Payload    map[string]interface{} `json:"payload"`
}

type APIResponse struct {
	Code    int                    `json:"code"`
	Message string                 `json:"message"`
	Data    map[string]interface{} `json:"data,omitempty"`
}

type LogEntry struct {
	Timestamp time.Time              `json:"timestamp"`
	Level     string                 `json:"level"`
	Service   string                 `json:"service"`
	TraceID   string                 `json:"trace_id,omitempty"`
	Message   string                 `json:"message"`
	Fields    map[string]interface{} `json:"fields,omitempty"`
}
