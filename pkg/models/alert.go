package models

import (
	"time"
)

type AlertRule struct {
	ID             string                 `json:"id"`
	Name           string                 `json:"name"`
	Description    string                 `json:"description,omitempty"`
	Expression     string                 `json:"expression"`
	Duration       time.Duration          `json:"duration"`
	For            time.Duration          `json:"for"`
	Severity       SeverityLevel          `json:"severity"`
	Labels         map[string]string      `json:"labels,omitempty"`
	Annotations    map[string]string      `json:"annotations,omitempty"`
	Enabled        bool                   `json:"enabled"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
	NotificationIDs []string              `json:"notification_ids,omitempty"`
	Config         map[string]interface{} `json:"config,omitempty"`
}

type AlertEvaluationResult struct {
	RuleID     string        `json:"rule_id"`
	RuleName   string        `json:"rule_name"`
	Matches    []AlertMatch  `json:"matches"`
	EvaluatedAt time.Time    `json:"evaluated_at"`
	Duration   time.Duration `json:"duration"`
}

type AlertMatch struct {
	Labels     map[string]string `json:"labels"`
	Value      float64           `json:"value"`
	Condition  string            `json:"condition"`
	ActiveAt   time.Time         `json:"active_at"`
}

type AlertHistory struct {
	ID          string      `json:"id"`
	AlertID     string      `json:"alert_id"`
	RuleID      string      `json:"rule_id"`
	Status      AlertStatus `json:"status"`
	ChangedAt   time.Time   `json:"changed_at"`
	ChangedBy   string      `json:"changed_by,omitempty"`
	Message     string      `json:"message,omitempty"`
}

type AlertSuppression struct {
	ID        string                 `json:"id"`
	Name      string                 `json:"name"`
	Matchers  map[string]string      `json:"matchers"`
	StartsAt  time.Time              `json:"starts_at"`
	EndsAt    time.Time              `json:"ends_at"`
	CreatedBy string                 `json:"created_by"`
	Comment   string                 `json:"comment,omitempty"`
}

type AlertSilence struct {
	ID        string                 `json:"id"`
	Matchers  []Matcher              `json:"matchers"`
	StartsAt  time.Time              `json:"starts_at"`
	EndsAt    time.Time              `json:"ends_at"`
	CreatedBy string                 `json:"created_by"`
	Comment   string                 `json:"comment"`
}

type Matcher struct {
	Name    string `json:"name"`
	Value   string `json:"value"`
	IsRegex bool   `json:"is_regex"`
	IsEqual bool   `json:"is_equal"`
}
