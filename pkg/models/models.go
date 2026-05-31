package models

import (
	"time"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey"`
	Type       string                 `json:"type"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `json:"attributes" gorm:"serializer:json"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type Config struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters" gorm:"serializer:json"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID       string     `json:"run_id" gorm:"primaryKey"`
	EntityID    string     `json:"entity_id"`
	Phase       string     `json:"phase"`
	Progress    float64    `json:"progress"`
	StartedAt   time.Time  `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at"`
	ErrorDetail *string    `json:"error_detail"`
}

type Snapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics" gorm:"serializer:json"`
	Dimensions map[string]string      `json:"dimensions" gorm:"serializer:json"`
}

type Task struct {
	ID          string                 `json:"id" gorm:"primaryKey"`
	Name        string                 `json:"name"`
	CronExpr    string                 `json:"cron_expr"`
	Command     string                 `json:"command"`
	Parameters  map[string]interface{} `json:"parameters" gorm:"serializer:json"`
	Status      string                 `json:"status"`
	NextRun     *time.Time             `json:"next_run"`
	LastRun     *time.Time             `json:"last_run"`
	Enabled     bool                   `json:"enabled"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type SLO struct {
	ID               string    `json:"id" gorm:"primaryKey"`
	Name             string    `json:"name"`
	ServiceName      string    `json:"service_name"`
	SLI              string    `json:"sli"`
	TargetPercent    float64   `json:"target_percent"`
	ErrorBudget      float64   `json:"error_budget"`
	BurnRate         float64   `json:"burn_rate"`
	WindowDays       int       `json:"window_days"`
	RemainingBudget  float64   `json:"remaining_budget"`
	TotalRequests    int64     `json:"total_requests"`
	FailedRequests   int64     `json:"failed_requests"`
	CreatedAt        time.Time `json:"created_at"`
	UpdatedAt        time.Time `json:"updated_at"`
}

type AlertRule struct {
	ID          string                 `json:"id" gorm:"primaryKey"`
	Name        string                 `json:"name"`
	Expr        string                 `json:"expr"`
	Severity    string                 `json:"severity"`
	For         string                 `json:"for"`
	Labels      map[string]string      `json:"labels" gorm:"serializer:json"`
	Annotations map[string]string      `json:"annotations" gorm:"serializer:json"`
	Enabled     bool                   `json:"enabled"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type Alert struct {
	ID         string            `json:"id" gorm:"primaryKey"`
	RuleID     string            `json:"rule_id"`
	Name       string            `json:"name"`
	Severity   string            `json:"severity"`
	Status     string            `json:"status"`
	Labels     map[string]string `json:"labels" gorm:"serializer:json"`
	StartsAt   time.Time         `json:"starts_at"`
	EndsAt     *time.Time        `json:"ends_at"`
	Value      float64           `json:"value"`
}

type LogEntry struct {
	ID        string                 `json:"id"`
	Timestamp time.Time              `json:"timestamp"`
	Level     string                 `json:"level"`
	Message   string                 `json:"message"`
	Service   string                 `json:"service"`
	TraceID   string                 `json:"trace_id"`
	Fields    map[string]interface{} `json:"fields"`
}

type Span struct {
	TraceID    string                 `json:"trace_id"`
	SpanID     string                 `json:"span_id"`
	ParentID   string                 `json:"parent_id"`
	Service    string                 `json:"service"`
	Operation  string                 `json:"operation"`
	StartTime  time.Time              `json:"start_time"`
	EndTime    time.Time              `json:"end_time"`
	Duration   int64                  `json:"duration"`
	StatusCode int                    `json:"status_code"`
	Tags       map[string]string      `json:"tags"`
}

type ServiceNode struct {
	ServiceName string `json:"service_name"`
	CallCount   int64  `json:"call_count"`
	AvgLatency  int64  `json:"avg_latency"`
	ErrorRate   float64 `json:"error_rate"`
}

type ServiceEdge struct {
	From       string `json:"from"`
	To         string `json:"to"`
	CallCount  int64  `json:"call_count"`
	AvgLatency int64  `json:"avg_latency"`
}

type Topology struct {
	Nodes []ServiceNode `json:"nodes"`
	Edges []ServiceEdge `json:"edges"`
}

type Notification struct {
	ID           string            `json:"id" gorm:"primaryKey"`
	Channel      string            `json:"channel"`
	Recipient    string            `json:"recipient"`
	Subject      string            `json:"subject"`
	Content      string            `json:"content"`
	Status       string            `json:"status"`
	RetryCount   int               `json:"retry_count"`
	MaxRetries   int               `json:"max_retries"`
	ErrorDetail  *string           `json:"error_detail"`
	Labels       map[string]string `json:"labels" gorm:"serializer:json"`
	SentAt       *time.Time        `json:"sent_at"`
	CreatedAt    time.Time         `json:"created_at"`
	UpdatedAt    time.Time         `json:"updated_at"`
}

type StoredFile struct {
	ID            string    `json:"id" gorm:"primaryKey"`
	Name          string    `json:"name"`
	Path          string    `json:"path"`
	Size          int64     `json:"size"`
	ContentType   string    `json:"content_type"`
	StorageClass  string    `json:"storage_class"`
	ExpireAt      *time.Time `json:"expire_at"`
	LastAccessed  time.Time `json:"last_accessed"`
	CreatedAt     time.Time `json:"created_at"`
}
