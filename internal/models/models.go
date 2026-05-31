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
	RunID       string                 `json:"run_id" gorm:"primaryKey"`
	EntityID    string                 `json:"entity_id"`
	Phase       string                 `json:"phase"`
	Progress    float64                `json:"progress"`
	StartedAt   time.Time              `json:"started_at"`
	CompletedAt *time.Time             `json:"completed_at"`
	ErrorDetail *string                `json:"error_detail"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"serializer:json"`
}

type MetricsSnapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics" gorm:"serializer:json"`
	Dimensions map[string]string      `json:"dimensions" gorm:"serializer:json"`
}

type AlertRule struct {
	ID          string                 `json:"id" gorm:"primaryKey"`
	Name        string                 `json:"name"`
	Expression  string                 `json:"expression"`
	Severity    string                 `json:"severity"`
	ForDuration time.Duration          `json:"for_duration"`
	Labels      map[string]string      `json:"labels" gorm:"serializer:json"`
	Annotations map[string]string      `json:"annotations" gorm:"serializer:json"`
	Enabled     bool                   `json:"enabled"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type Alert struct {
	ID         string            `json:"id" gorm:"primaryKey"`
	RuleID     string            `json:"rule_id"`
	Labels     map[string]string `json:"labels" gorm:"serializer:json"`
	State      string            `json:"state"`
	Severity   string            `json:"severity"`
	ActiveAt   time.Time         `json:"active_at"`
	ResolvedAt *time.Time        `json:"resolved_at"`
	Value      float64           `json:"value"`
	Message    string            `json:"message"`
}

type SLO struct {
	ID             string                 `json:"id" gorm:"primaryKey"`
	Name           string                 `json:"name"`
	Description    string                 `json:"description"`
	SLIExpression  string                 `json:"sli_expression"`
	TargetPercent  float64                `json:"target_percent"`
	WindowDays     int                    `json:"window_days"`
	Labels         map[string]string      `json:"labels" gorm:"serializer:json"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
}

type SLOStatus struct {
	ID                  string    `json:"id" gorm:"primaryKey"`
	SLOID               string    `json:"slo_id"`
	CurrentSLI          float64   `json:"current_sli"`
	ErrorBudgetRemaining float64  `json:"error_budget_remaining"`
	ErrorBudgetBurnRate float64   `json:"error_budget_burn_rate"`
	BurnRateAlertLevel  int       `json:"burn_rate_alert_level"`
	WindowStart         time.Time `json:"window_start"`
	WindowEnd           time.Time `json:"window_end"`
	UpdatedAt           time.Time `json:"updated_at"`
}

type MetricDataPoint struct {
	ID         string            `json:"id" gorm:"primaryKey"`
	MetricName string            `json:"metric_name"`
	Value      float64           `json:"value"`
	Timestamp  time.Time         `json:"timestamp"`
	Tags       map[string]string `json:"tags" gorm:"serializer:json"`
}

type LogEntry struct {
	ID         string                 `json:"id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp"`
	Level      string                 `json:"level"`
	Message    string                 `json:"message"`
	Source     string                 `json:"source"`
	Labels     map[string]string      `json:"labels" gorm:"serializer:json"`
	Parsed     map[string]interface{} `json:"parsed" gorm:"serializer:json"`
}

type SchemaMigration struct {
	Version   int       `json:"version" gorm:"primaryKey"`
	Name      string    `json:"name"`
	AppliedAt time.Time `json:"applied_at"`
	Checksum  string    `json:"checksum"`
}

type BackupRecord struct {
	ID         string    `json:"id" gorm:"primaryKey"`
	BackupType string    `json:"backup_type"`
	FilePath   string    `json:"file_path"`
	SizeBytes  int64     `json:"size_bytes"`
	Status     string    `json:"status"`
	StartedAt  time.Time `json:"started_at"`
	CompletedAt time.Time `json:"completed_at"`
	Checksum   string    `json:"checksum"`
}

type Span struct {
	TraceID    string                 `json:"trace_id" gorm:"index"`
	SpanID     string                 `json:"span_id" gorm:"primaryKey"`
	ParentID   string                 `json:"parent_id"`
	Name       string                 `json:"name"`
	Service    string                 `json:"service"`
	Kind       string                 `json:"kind"`
	StartTime  time.Time              `json:"start_time"`
	EndTime    time.Time              `json:"end_time"`
	Duration   time.Duration          `json:"duration"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `json:"attributes" gorm:"serializer:json"`
	Events     []SpanEvent            `json:"events" gorm:"serializer:json"`
	Sampled    bool                   `json:"sampled"`
}

type SpanEvent struct {
	Timestamp  time.Time              `json:"timestamp"`
	Name       string                 `json:"name"`
	Attributes map[string]interface{} `json:"attributes"`
}

type SamplingConfig struct {
	ID               string                 `json:"id" gorm:"primaryKey"`
	Service          string                 `json:"service"`
	DefaultSampleRate float64               `json:"default_sample_rate"`
	Rules            []SamplingRule         `json:"rules" gorm:"serializer:json"`
	TailSampling     bool                   `json:"tail_sampling"`
	TailWaitDuration time.Duration          `json:"tail_wait_duration"`
	CreatedAt        time.Time              `json:"created_at"`
	UpdatedAt        time.Time              `json:"updated_at"`
}

type SamplingRule struct {
	AttributeKey   string  `json:"attribute_key"`
	AttributeValue string  `json:"attribute_value"`
	Operator       string  `json:"operator"`
	SampleRate     float64 `json:"sample_rate"`
}

type Task struct {
	ID            string                 `json:"id" gorm:"primaryKey"`
	Name          string                 `json:"name"`
	Type          string                 `json:"type"`
	CronExpr      string                 `json:"cron_expr"`
	Payload       map[string]interface{} `json:"payload" gorm:"serializer:json"`
	Status        string                 `json:"status"`
	LastRunAt     *time.Time             `json:"last_run_at"`
	NextRunAt     *time.Time             `json:"next_run_at"`
	CreatedAt     time.Time              `json:"created_at"`
	UpdatedAt     time.Time              `json:"updated_at"`
}

type TaskRun struct {
	ID           string    `json:"id" gorm:"primaryKey"`
	TaskID       string    `json:"task_id"`
	Status       string    `json:"status"`
	Progress     float64   `json:"progress"`
	StartedAt    time.Time `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at"`
	Error        *string   `json:"error"`
	Output       map[string]interface{} `json:"output" gorm:"serializer:json"`
}

type APIResponse struct {
	Code int         `json:"code"`
	Data interface{} `json:"data,omitempty"`
	Msg  string      `json:"msg,omitempty"`
}
