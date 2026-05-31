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

type NotificationPriority int

const (
	PriorityLow     NotificationPriority = 1
	PriorityNormal  NotificationPriority = 2
	PriorityMedium  NotificationPriority = 3
	PriorityHigh    NotificationPriority = 4
	PriorityCritical NotificationPriority = 5
)

type SuppressionType string

const (
	SuppressionDedup    SuppressionType = "dedup"
	SuppressionWindow   SuppressionType = "window"
	SuppressionRateLimit SuppressionType = "rate_limit"
)

type NotificationChannel string

const (
	ChannelEmail    NotificationChannel = "email"
	ChannelSMS      NotificationChannel = "sms"
	ChannelWebhook  NotificationChannel = "webhook"
	ChannelDingtalk NotificationChannel = "dingtalk"
	ChannelWechat   NotificationChannel = "wechat"
)

type NotificationStatus string

const (
	StatusPending   NotificationStatus = "pending"
	StatusQueued    NotificationStatus = "queued"
	StatusSent      NotificationStatus = "sent"
	StatusDelivered NotificationStatus = "delivered"
	StatusFailed    NotificationStatus = "failed"
	StatusRetrying  NotificationStatus = "retrying"
	StatusSuppressed NotificationStatus = "suppressed"
)

type NotificationRecord struct {
	ID                string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Type              string                 `json:"type" gorm:"type:varchar(32);index"`
	Title             string                 `json:"title" gorm:"type:varchar(256)"`
	Content           string                 `json:"content" gorm:"type:text"`
	Channel           string                 `json:"channel" gorm:"type:varchar(32);index"`
	Recipient         string                 `json:"recipient" gorm:"type:varchar(256)"`
	Priority          int                    `json:"priority" gorm:"index"`
	Status            string                 `json:"status" gorm:"type:varchar(32);index"`
	DedupKey          string                 `json:"dedup_key" gorm:"type:varchar(128);index"`
	SuppressionType   string                 `json:"suppression_type,omitempty" gorm:"type:varchar(32)"`
	SuppressionReason string                 `json:"suppression_reason,omitempty" gorm:"type:varchar(256)"`
	RetryCount        int                    `json:"retry_count"`
	MaxRetries        int                    `json:"max_retries"`
	SentAt            *time.Time             `json:"sent_at,omitempty"`
	DeliveredAt       *time.Time             `json:"delivered_at,omitempty"`
	FailedAt          *time.Time             `json:"failed_at,omitempty"`
	ErrorMsg          *string                `json:"error_msg,omitempty"`
	TraceID           string                 `json:"trace_id" gorm:"type:varchar(64);index"`
	Metadata          map[string]string      `json:"metadata" gorm:"type:jsonb"`
	CreatedAt         time.Time              `json:"created_at"`
	UpdatedAt         time.Time              `json:"updated_at"`
}

type SuppressionRule struct {
	ID              string          `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name            string          `json:"name" gorm:"type:varchar(128)"`
	Type            string          `json:"type" gorm:"type:varchar(32);index"`
	NotificationType string        `json:"notification_type" gorm:"type:varchar(32);index"`
	Channel         string          `json:"channel" gorm:"type:varchar(32);index"`
	DedupKeyPattern string          `json:"dedup_key_pattern" gorm:"type:varchar(256)"`
	WindowSeconds   int             `json:"window_seconds"`
	MaxCount        int             `json:"max_count"`
	Enabled         bool            `json:"enabled"`
	CreatedAt       time.Time       `json:"created_at"`
	UpdatedAt       time.Time       `json:"updated_at"`
}

type NotificationTemplate struct {
	ID        string            `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name      string            `json:"name" gorm:"type:varchar(128);index"`
	Type      string            `json:"type" gorm:"type:varchar(32);index"`
	Channel   string            `json:"channel" gorm:"type:varchar(32)"`
	TitleTmpl  string            `json:"title_tmpl" gorm:"type:varchar(512)"`
	ContentTmpl string          `json:"content_tmpl" gorm:"type:text"`
	DefaultPriority int          `json:"default_priority"`
	Variables map[string]string `json:"variables" gorm:"type:jsonb"`
	Enabled   bool              `json:"enabled"`
	CreatedAt time.Time         `json:"created_at"`
	UpdatedAt time.Time         `json:"updated_at"`
}

type NotificationStats struct {
	ID              string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Timestamp       time.Time `json:"timestamp" gorm:"index"`
	NotificationType string   `json:"notification_type" gorm:"type:varchar(32);index"`
	Channel         string    `json:"channel" gorm:"type:varchar(32);index"`
	Priority        int       `json:"priority" gorm:"index"`
	TotalCount      int64     `json:"total_count"`
	SuccessCount    int64     `json:"success_count"`
	FailedCount     int64     `json:"failed_count"`
	SuppressedCount int64     `json:"suppressed_count"`
	AvgLatencyMs    float64   `json:"avg_latency_ms"`
}

type APIResponse struct {
	Code    int         `json:"code"`
	Data    interface{} `json:"data,omitempty"`
	Message string      `json:"message,omitempty"`
}

type BatchOperation struct {
	Action string `json:"action"`
	ID     string `json:"id"`
}

type BatchRequest struct {
	Operations []BatchOperation `json:"operations"`
}

type BatchResult struct {
	ID     string `json:"id"`
	Status string `json:"status"`
	Error  string `json:"error,omitempty"`
}

type BatchResponse struct {
	BatchID string        `json:"batch_id"`
	Results []BatchResult `json:"results"`
}

type RoutingCondition struct {
	Field    string      `json:"field" gorm:"type:varchar(64)"`
	Operator string      `json:"operator" gorm:"type:varchar(32)"`
	Value    interface{} `json:"value" gorm:"type:jsonb"`
}

type DistributionStrategy string

const (
	StrategySingle     DistributionStrategy = "single"
	StrategyMultiAll   DistributionStrategy = "multi_all"
	StrategyMultiAny   DistributionStrategy = "multi_any"
	StrategyFailover   DistributionStrategy = "failover"
	StrategyLoadBalance DistributionStrategy = "load_balance"
	StrategyWeighted   DistributionStrategy = "weighted"
)

type RouteTarget struct {
	Channel  string  `json:"channel" gorm:"type:varchar(32)"`
	Priority int     `json:"priority"`
	Weight   int     `json:"weight"`
	Enabled  bool    `json:"enabled"`
}

type NotificationRoute struct {
	ID              string              `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name            string              `json:"name" gorm:"type:varchar(128);index"`
	Description     string              `json:"description" gorm:"type:varchar(512)"`
	NotificationType string             `json:"notification_type" gorm:"type:varchar(32);index"`
	Conditions      []RoutingCondition  `json:"conditions" gorm:"type:jsonb"`
	ConditionLogic  string              `json:"condition_logic" gorm:"type:varchar(16);default:'AND'"`
	Strategy        string              `json:"strategy" gorm:"type:varchar(32)"`
	Targets         []RouteTarget       `json:"targets" gorm:"type:jsonb"`
	DefaultChannel  string              `json:"default_channel" gorm:"type:varchar(32)"`
	Enabled         bool                `json:"enabled" gorm:"index"`
	Priority        int                 `json:"priority"`
	CreatedAt       time.Time           `json:"created_at"`
	UpdatedAt       time.Time           `json:"updated_at"`
}

type RoutingResult struct {
	Matched       bool
	RouteID       string
	RouteName     string
	Strategy      string
	Targets       []RouteTarget
	DefaultChannel string
}
