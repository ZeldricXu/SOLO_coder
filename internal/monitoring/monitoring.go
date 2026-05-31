package monitoring

import (
	"time"
)

type AlertStatus string

const (
	AlertStatusFiring    AlertStatus = "firing"
	AlertStatusResolved  AlertStatus = "resolved"
	AlertStatusPending   AlertStatus = "pending"
	AlertStatusSilenced  AlertStatus = "silenced"
)

type AlertSeverity string

const (
	SeverityCritical AlertSeverity = "critical"
	SeverityWarning  AlertSeverity = "warning"
	SeverityInfo     AlertSeverity = "info"
)

type AlertRule struct {
	ID            string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name          string                 `gorm:"type:varchar(128);not null" json:"name"`
	Namespace     string                 `gorm:"type:varchar(64);index;not null" json:"namespace"`
	Description   string                 `gorm:"type:text" json:"description"`
	Query         string                 `gorm:"type:text;not null" json:"query"`
	Condition     string                 `gorm:"type:varchar(32);not null" json:"condition"`
	Threshold     float64                `json:"threshold"`
	Duration      string                 `gorm:"type:varchar(32)" json:"duration"`
	Severity      AlertSeverity          `gorm:"type:varchar(32);index" json:"severity"`
	Labels        map[string]string      `gorm:"type:jsonb;serializer:json" json:"labels"`
	Annotations   map[string]string      `gorm:"type:jsonb;serializer:json" json:"annotations"`
	Enabled       bool                   `json:"enabled"`
	ForDuration   time.Duration          `json:"-"`
	CreatedBy     string                 `gorm:"type:varchar(64)" json:"created_by"`
	CreatedAt     time.Time              `json:"created_at"`
	UpdatedAt     time.Time              `json:"updated_at"`
}

type Alert struct {
	ID             string            `gorm:"primaryKey;type:varchar(64)" json:"id"`
	RuleID         string            `gorm:"type:varchar(64);index;not null" json:"rule_id"`
	Name           string            `gorm:"type:varchar(128);not null" json:"name"`
	Namespace      string            `gorm:"type:varchar(64);index;not null" json:"namespace"`
	Status         AlertStatus       `gorm:"type:varchar(32);index" json:"status"`
	Severity       AlertSeverity     `gorm:"type:varchar(32);index" json:"severity"`
	Labels         map[string]string `gorm:"type:jsonb;serializer:json" json:"labels"`
	Annotations    map[string]string `gorm:"type:jsonb;serializer:json" json:"annotations"`
	Value          float64           `json:"value"`
	Threshold      float64           `json:"threshold"`
	Condition      string            `json:"condition"`
	StartsAt       time.Time         `json:"starts_at"`
	EndsAt         *time.Time        `json:"ends_at,omitempty"`
	Fingerprint    string            `gorm:"type:varchar(128);index" json:"fingerprint"`
	NotificationSent bool            `json:"notification_sent"`
	CreatedAt      time.Time         `json:"created_at"`
	UpdatedAt      time.Time         `json:"updated_at"`
}

type NotificationChannel string

const (
	ChannelWebhook NotificationChannel = "webhook"
	ChannelEmail   NotificationChannel = "email"
	ChannelSMS     NotificationChannel = "sms"
	ChannelDingTalk NotificationChannel = "dingtalk"
	ChannelWeChat  NotificationChannel = "wechat"
)

type NotificationConfig struct {
	ID        string              `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name      string              `gorm:"type:varchar(128);not null" json:"name"`
	Channel   NotificationChannel `gorm:"type:varchar(32);not null" json:"channel"`
	Config    map[string]string   `gorm:"type:jsonb;serializer:json" json:"config"`
	Enabled   bool                `json:"enabled"`
	CreatedBy string              `gorm:"type:varchar(64)" json:"created_by"`
	CreatedAt time.Time           `json:"created_at"`
	UpdatedAt time.Time           `json:"updated_at"`
}

type MetricDataPoint struct {
	Timestamp time.Time              `json:"timestamp"`
	Value     float64                `json:"value"`
	Labels    map[string]string      `json:"labels"`
}

type CreateAlertRuleRequest struct {
	Name        string            `json:"name" binding:"required"`
	Namespace   string            `json:"namespace" binding:"required"`
	Description string            `json:"description"`
	Query       string            `json:"query" binding:"required"`
	Condition   string            `json:"condition" binding:"required"`
	Threshold   float64           `json:"threshold" binding:"required"`
	Duration    string            `json:"duration"`
	Severity    AlertSeverity     `json:"severity" binding:"required"`
	Labels      map[string]string `json:"labels"`
	Annotations map[string]string `json:"annotations"`
}

type UpdateAlertRuleRequest struct {
	Name        string            `json:"name"`
	Description string            `json:"description"`
	Query       string            `json:"query"`
	Condition   string            `json:"condition"`
	Threshold   *float64          `json:"threshold"`
	Duration    string            `json:"duration"`
	Severity    AlertSeverity     `json:"severity"`
	Labels      map[string]string `json:"labels"`
	Annotations map[string]string `json:"annotations"`
	Enabled     *bool             `json:"enabled"`
}
