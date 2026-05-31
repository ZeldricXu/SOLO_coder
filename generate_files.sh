#!/bin/bash
BASE_DIR="/Users/huangzitong/SoloCoder/session189"

# Domain models
cat > "$BASE_DIR/internal/domain/notification.go << 'GOEOF'
package domain

import (
	"time"
)

type NotificationChannel string

const (
	NotificationChannelEmail   NotificationChannel = "email"
	NotificationChannelSMS   NotificationChannel = "sms"
	NotificationChannelDingTalk NotificationChannel = "dingtalk"
	NotificationChannelWebhook NotificationChannel = "webhook"
)

type NotificationTemplate struct {
	TemplateID string                 `json:"template_id" gorm:"primaryKey;type:varchar(64)"`
	Name       string                 `json:"name"`
	Channel    NotificationChannel      `json:"channel" gorm:"type:varchar(32);index"`
	Title      string                 `json:"title"`
	Content    string                 `json:"content" gorm:"type:text"`
	Variables  map[string]interface{} `json:"variables" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

func (NotificationTemplate) TableName() string {
	return "notification_templates"
}

type NotificationRecord struct {
	RecordID   string                 `json:"record_id" gorm:"primaryKey;type:varchar(64)"`
	TemplateID string                 `json:"template_id" gorm:"type:varchar(64);index"`
	Channel    NotificationChannel      `json:"channel" gorm:"type:varchar(32);index"`
	Recipient  string                 `json:"recipient"`
	Title      string                 `json:"title"`
	Content    string                 `json:"content" gorm:"type:text"`
	Status     string                 `json:"status" gorm:"type:varchar(32);index"`
	ErrorMsg   *string                `json:"error_msg,omitempty" gorm:"type:text"`
	SentAt     *time.Time             `json:"sent_at,omitempty"`
	CreatedAt  time.Time              `json:"created_at"`
}

func (NotificationRecord) TableName() string {
	return "notification_records"
}
GOEOF

cat > "$BASE_DIR/internal/domain/storage.go << 'GOEOF'
package domain

import (
	"time"
)

type BackupStatus string

const (
	BackupStatusPending  BackupStatus = "pending"
	BackupStatusRunning BackupStatus = "running"
	BackupStatusSuccess BackupStatus = "success"
	BackupStatusFailed  BackupStatus = "failed"
)

type BackupRecord struct {
	BackupID   string       `json:"backup_id" gorm:"primaryKey;type:varchar(64)"`
	BackupType string       `json:"backup_type" gorm:"type:varchar(32);index"`
	Source     string       `json:"source"`
	Target     string       `json:"target"`
	SizeBytes  int64        `json:"size_bytes"`
	Status     BackupStatus `json:"status" gorm:"type:varchar(32);index"`
	ErrorMsg   *string      `json:"error_msg,omitempty" gorm:"type:text"`
	Checksum   string       `json:"checksum"`
	StartedAt  time.Time    `json:"started_at"`
	CompletedAt *time.Time   `json:"completed_at,omitempty"`
	CreatedAt  time.Time    `json:"created_at"`
}

func (BackupRecord) TableName() string {
	return "backup_records"
}

type RestoreRecord struct {
	RestoreID string       `json:"restore_id" gorm:"primaryKey;type:varchar(64)"`
	BackupID  string       `json:"backup_id" gorm:"type:varchar(64);index"`
	Source    string       `json:"source"`
	Target    string       `json:"target"`
	Status    BackupStatus `json:"status" gorm:"type:varchar(32);index"`
	ErrorMsg  *string      `json:"error_msg,omitempty" gorm:"type:text"`
	StartedAt time.Time    `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	CreatedAt time.Time   `json:"created_at"`
}

func (RestoreRecord) TableName() string {
	return "restore_records"
}
GOEOF

cat > "$BASE_DIR/internal/domain/anomaly.go << 'GOEOF'
package domain

import (
	"time"
)

type AnomalyAlgorithm string

const (
	AnomalyAlgorithmZScore       AnomalyAlgorithm = "zscore"
	AnomalyAlgorithmIQR           AnomalyAlgorithm = "iqr"
	AnomalyAlgorithmEWMA         AnomalyAlgorithm = "ewma"
	AnomalyAlgorithmIsolationForest AnomalyAlgorithm = "isolation_forest"
)

type AnomalyResult struct {
	ResultID     string                 `json:"result_id" gorm:"primaryKey;type:varchar(64)"`
	MetricName   string                 `json:"metric_name" gorm:"index"`
	Algorithm    AnomalyAlgorithm       `json:"algorithm" gorm:"type:varchar(32)"`
	IsAnomaly    bool                   `json:"is_anomaly"`
	CurrentValue float64                `json:"current_value"`
	ExpectedValue float64               `json:"expected_value"`
	Score       float64                `json:"score"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	Timestamp   time.Time              `json:"timestamp" gorm:"index"`
	CreatedAt   time.Time              `json:"created_at"`
}

func (AnomalyResult) TableName() string {
	return "anomaly_results"
}

type MetricBaseline struct {
	BaselineID   string                 `json:"baseline_id" gorm:"primaryKey;type:varchar(64)"`
	MetricName   string                 `json:"metric_name" gorm:"index"`
	Mean         float64                `json:"mean"`
	StdDev       float64                `json:"std_dev"`
	Percentiles  map[string]float64      `json:"percentiles" gorm:"type:jsonb"`
	WindowStart  time.Time              `json:"window_start"`
	WindowEnd    time.Time              `json:"window_end"`
	CreatedAt    time.Time              `json:"created_at"`
}

func (MetricBaseline) TableName() string {
	return "metric_baselines"
}
GOEOF

cat > "$BASE_DIR/internal/domain/task.go << 'GOEOF'
package domain

import (
	"time"
)

type TaskStatus string

const (
	TaskStatusPending   TaskStatus = "pending"
	TaskStatusQueued    TaskStatus = "queued"
	TaskStatusRunning   TaskStatus = "running"
	TaskStatusPaused    TaskStatus = "paused"
	TaskStatusCompleted TaskStatus = "completed"
	TaskStatusFailed    TaskStatus = "failed"
	TaskStatusCancelled TaskStatus = "cancelled"
	TaskStatusTimedOut  TaskStatus = "timed_out"
)

type Task struct {
	TaskID       string                 `json:"task_id" gorm:"primaryKey;type:varchar(64)"`
	EntityID     string                 `json:"entity_id" gorm:"type:varchar(64);index"`
	TaskType     string                 `json:"task_type" gorm:"type:varchar(64);index"`
	Status       TaskStatus             `json:"status" gorm:"type:varchar(32);index"`
	Priority     int32                  `json:"priority" gorm:"index"`
	Payload      map[string]interface{} `json:"payload" gorm:"type:jsonb"`
	Result       map[string]interface{} `json:"result,omitempty" gorm:"type:jsonb"`
	ErrorDetail  *string                `json:"error_detail,omitempty" gorm:"type:text"`
	RetryCount   int32                  `json:"retry_count"`
	MaxRetries   int32                  `json:"max_retries"`
	TimeoutSec   int64                  `json:"timeout_sec"`
	StartedAt   *time.Time             `json:"started_at,omitempty"`
	CompletedAt *time.Time             `json:"completed_at,omitempty"`
	CreatedAt    time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

func (Task) TableName() string {
	return "tasks"
}
GOEOF

cat > "$BASE_DIR/internal/domain/slo.go << 'GOEOF'
package domain

import (
	"time"
)

type SLOStatus string

const (
	SLOStatusOK       SLOStatus = "ok"
	SLOStatusWarning  SLOStatus = "warning"
	SLOStatusExhausted SLOStatus = "exhausted"
)

type SLO struct {
	SLOID       string                 `json:"slo_id" gorm:"primaryKey;type:varchar(64)"`
	Name         string                 `json:"name"`
	Description  string                 `json:"description"`
	TargetPercent float64                `json:"target_percent"`
	WindowDays   int32                  `json:"window_days"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

func (SLO) TableName() string {
	return "slos"
}

type SLI struct {
	SLIID        string                 `json:"sli_id" gorm:"primaryKey;type:varchar(64)"`
	SLOID        string                 `json:"slo_id" gorm:"type:varchar(64);index"`
	MetricName   string                 `json:"metric_name" gorm:"index"`
	GoodEvents   int64                  `json:"good_events"`
	TotalEvents  int64                  `json:"total_events"`
	SLIValue     float64                `json:"sli_value"`
	Timestamp    time.Time              `json:"timestamp" gorm:"index"`
	CreatedAt    time.Time              `json:"created_at"`
}

func (SLI) TableName() string {
	return "slis"
}

type ErrorBudget struct {
	BudgetID     string                 `json:"budget_id" gorm:"primaryKey;type:varchar(64)"`
	SLOID        string                 `json:"slo_id" gorm:"type:varchar(64);uniqueIndex"`
	TotalBudget  float64                `json:"total_budget"`
	ConsumedBudget float64              `json:"consumed_budget"`
	RemainingBudget float64             `json:"remaining_budget"`
	BurnRate     float64                `json:"burn_rate"`
	Status        SLOStatus              `json:"status" gorm:"type:varchar(32);index"`
	WindowStart  time.Time              `json:"window_start"`
	WindowEnd    time.Time              `json:"window_end"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

func (ErrorBudget) TableName() string {
	return "error_budgets"
}
GOEOF

cat > "$BASE_DIR/internal/domain/tracing.go << 'GOEOF'
package domain

import (
	"time"
)

type SpanStatus string

const (
	SpanStatusOK    SpanStatus = "ok"
	SpanStatusError SpanStatus = "error"
	SpanStatusUnset SpanStatus = "unset"
)

type TraceSpan struct {
	SpanID       string                 `json:"span_id" gorm:"primaryKey;type:varchar(64)"`
	TraceID      string                 `json:"trace_id" gorm:"type:varchar(64);index"`
	ParentSpanID *string                `json:"parent_span_id,omitempty" gorm:"type:varchar(64);index"`
	ServiceName  string                 `json:"service_name" gorm:"index"`
	OperationName string                 `json:"operation_name" gorm:"index"`
	Status       SpanStatus             `json:"status" gorm:"type:varchar(16);index"`
	Attributes   map[string]interface{} `json:"attributes" gorm:"type:jsonb"`
	DurationNano int64                  `json:"duration_nano"`
	StartTime    time.Time              `json:"start_time" gorm:"index"`
	EndTime      time.Time              `json:"end_time"`
	CreatedAt    time.Time              `json:"created_at"`
}

func (TraceSpan) TableName() string {
	return "trace_spans"
}

type SamplingPolicy struct {
	PolicyID     string                 `json:"policy_id" gorm:"primaryKey;type:varchar(64)"`
	Name          string                 `json:"name"`
	ServiceName  string                 `json:"service_name" gorm:"index"`
	SamplingRate float64                `json:"sampling_rate"`
	MinDuration  *int64                 `json:"min_duration_nano,omitempty"`
	ErrorOnly    bool                   `json:"error_only"`
	Enabled      bool                   `json:"enabled" gorm:"index"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

func (SamplingPolicy) TableName() string {
	return "sampling_policies"
}
GOEOF

echo "Domain files created successfully"
