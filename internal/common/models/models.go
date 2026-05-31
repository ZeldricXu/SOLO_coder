package models

import (
	"time"
)

type CoreEntity struct {
	ID         string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Type       string                 `json:"type" gorm:"type:varchar(32);index"`
	Status     string                 `json:"status" gorm:"type:varchar(32);index"`
	Attributes map[string]interface{} `json:"attributes" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type ConfigDefinition struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey;type:varchar(64)"`
	Namespace  string                 `json:"namespace" gorm:"type:varchar(64);index"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID       string     `json:"run_id" gorm:"primaryKey;type:varchar(64)"`
	EntityID    string     `json:"entity_id" gorm:"type:varchar(64);index"`
	Phase       string     `json:"phase" gorm:"type:varchar(32);index"`
	Progress    float64    `json:"progress"`
	StartedAt   time.Time  `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	ErrorDetail *string    `json:"error_detail,omitempty"`
}

type StatsSnapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey;type:varchar(64)"`
	Timestamp  time.Time              `json:"timestamp" gorm:"index"`
	Metrics    map[string]float64     `json:"metrics" gorm:"type:jsonb"`
	Dimensions map[string]string      `json:"dimensions" gorm:"type:jsonb"`
}

type LogLevelConfig struct {
	ID        string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Namespace string    `json:"namespace" gorm:"type:varchar(64);uniqueIndex:idx_ns_component"`
	Component string    `json:"component" gorm:"type:varchar(128);uniqueIndex:idx_ns_component"`
	Level     string    `json:"level" gorm:"type:varchar(16)"`
	UpdatedAt time.Time `json:"updated_at"`
	UpdatedBy string    `json:"updated_by" gorm:"type:varchar(64)"`
}

type NotificationRecord struct {
	ID             string            `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Type           string            `json:"type" gorm:"type:varchar(32)"`
	Channel        string            `json:"channel" gorm:"type:varchar(32)"`
	Recipient      string            `json:"recipient" gorm:"type:varchar(256)"`
	Content        string            `json:"content" gorm:"type:text"`
	Status         string            `json:"status" gorm:"type:varchar(32);index"`
	RetryCount     int               `json:"retry_count"`
	MaxRetries     int               `json:"max_retries"`
	SentAt         *time.Time        `json:"sent_at,omitempty"`
	DeliveredAt    *time.Time        `json:"delivered_at,omitempty"`
	FailedAt       *time.Time        `json:"failed_at,omitempty"`
	ErrorMsg       *string           `json:"error_msg,omitempty"`
	TraceID        string            `json:"trace_id" gorm:"type:varchar(64);index"`
	Metadata       map[string]string `json:"metadata" gorm:"type:jsonb"`
	CreatedAt      time.Time         `json:"created_at"`
}

type MetricPoint struct {
	Name      string            `json:"name"`
	Value     float64           `json:"value"`
	Timestamp int64             `json:"timestamp"`
	Tags      map[string]string `json:"tags"`
}

type ScheduledTask struct {
	ID             string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name           string                 `json:"name" gorm:"type:varchar(128);index"`
	Description    string                 `json:"description" gorm:"type:varchar(512)"`
	Type           string                 `json:"type" gorm:"type:varchar(32)"`
	CronExpr       string                 `json:"cron_expr" gorm:"type:varchar(64)"`
	Payload        map[string]interface{} `json:"payload" gorm:"type:jsonb"`
	DependsOn      []string               `json:"depends_on" gorm:"type:jsonb"`
	TimeoutSeconds int                    `json:"timeout_seconds"`
	Retries        int                    `json:"retries"`
	Enabled        bool                   `json:"enabled"`
	Status         string                 `json:"status" gorm:"type:varchar(32);index"`
	LastRunAt      *time.Time             `json:"last_run_at,omitempty"`
	NextRunAt      *time.Time             `json:"next_run_at,omitempty"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
}

type TaskExecution struct {
	ID        string     `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TaskID    string     `json:"task_id" gorm:"type:varchar(64);index"`
	Status    string     `json:"status" gorm:"type:varchar(32);index"`
	StartTime time.Time  `json:"start_time"`
	EndTime   *time.Time `json:"end_time,omitempty"`
	ErrorMsg  *string    `json:"error_msg,omitempty"`
	Result    string     `json:"result" gorm:"type:text"`
}

type AnomalyDetectionResult struct {
	ID          string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	MetricName  string    `json:"metric_name" gorm:"type:varchar(128);index"`
	Algorithm   string    `json:"algorithm" gorm:"type:varchar(64)"`
	Severity    string    `json:"severity" gorm:"type:varchar(16)"`
	IsAnomaly   bool      `json:"is_anomaly"`
	Value       float64   `json:"value"`
	ExpectedMin float64   `json:"expected_min"`
	ExpectedMax float64   `json:"expected_max"`
	Timestamp   time.Time `json:"timestamp" gorm:"index"`
	Tags        map[string]string `json:"tags" gorm:"type:jsonb"`
}

type CacheEntry struct {
	Key        string    `json:"key" gorm:"primaryKey;type:varchar(256)"`
	Value      string    `json:"value" gorm:"type:text"`
	ExpiresAt  time.Time `json:"expires_at" gorm:"index"`
	HitCount   int64     `json:"hit_count"`
	MissCount  int64     `json:"miss_count"`
	CreatedAt  time.Time `json:"created_at"`
	AccessedAt time.Time `json:"accessed_at"`
}
