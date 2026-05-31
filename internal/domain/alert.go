package domain

import (
	"time"
)

type AlertConditionType string

const (
	AlertConditionThreshold    AlertConditionType = "threshold"
	AlertConditionRateOfChange AlertConditionType = "rate_of_change"
	AlertConditionAnomaly      AlertConditionType = "anomaly"
)

type AlertOperator string

const (
	AlertOperatorGT AlertOperator = "gt"
	AlertOperatorLT AlertOperator = "lt"
	AlertOperatorGE AlertOperator = "ge"
	AlertOperatorLE AlertOperator = "le"
	AlertOperatorEQ AlertOperator = "eq"
	AlertOperatorNE AlertOperator = "ne"
)

type AlertSeverity string

const (
	AlertSeverityCritical AlertSeverity = "critical"
	AlertSeverityWarning  AlertSeverity = "warning"
	AlertSeverityInfo     AlertSeverity = "info"
)

type AlertRule struct {
	RuleID          string              `json:"rule_id" gorm:"primaryKey;type:varchar(64)"`
	Name            string              `json:"name"`
	MetricName      string              `json:"metric_name" gorm:"index"`
	ConditionType   AlertConditionType  `json:"condition_type" gorm:"type:varchar(32)"`
	Operator        AlertOperator       `json:"operator" gorm:"type:varchar(8)"`
	Threshold       float64             `json:"threshold"`
	WindowSeconds   int32               `json:"window_seconds"`
	Severity        AlertSeverity       `json:"severity" gorm:"type:varchar(16);index"`
	Enabled         bool                `json:"enabled" gorm:"index"`
	NotificationIDs []string            `json:"notification_ids" gorm:"type:text[]"`
	CreatedAt       time.Time           `json:"created_at"`
	UpdatedAt       time.Time           `json:"updated_at"`
}

func (AlertRule) TableName() string {
	return "alert_rules"
}

type AlertEvent struct {
	EventID     string        `json:"event_id" gorm:"primaryKey;type:varchar(64)"`
	RuleID      string        `json:"rule_id" gorm:"type:varchar(64);index"`
	Severity    AlertSeverity `json:"severity" gorm:"type:varchar(16);index"`
	MetricValue float64       `json:"metric_value"`
	Message     string        `json:"message" gorm:"type:text"`
	Resolved    bool          `json:"resolved" gorm:"index"`
	TriggeredAt time.Time     `json:"triggered_at" gorm:"index"`
	ResolvedAt  *time.Time    `json:"resolved_at,omitempty"`
}

func (AlertEvent) TableName() string {
	return "alert_events"
}
