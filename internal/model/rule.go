package model

import "time"

type RuleOperator string

const (
	OpEqual        RuleOperator = "eq"
	OpNotEqual     RuleOperator = "neq"
	OpGreaterThan  RuleOperator = "gt"
	OpLessThan     RuleOperator = "lt"
	OpGreaterEqual RuleOperator = "gte"
	OpLessEqual    RuleOperator = "lte"
	OpContains     RuleOperator = "contains"
	OpIn           RuleOperator = "in"
	OpNotIn        RuleOperator = "nin"
	OpRegex        RuleOperator = "regex"
)

type RuleCondition struct {
	Field    string      `json:"field" binding:"required"`
	Operator RuleOperator `json:"operator" binding:"required"`
	Value    interface{} `json:"value" binding:"required"`
}

type RuleAction struct {
	Type       string                 `json:"type" binding:"required,oneof=http_request mqtt_publish command notification webhook"`
	Parameters map[string]interface{} `json:"parameters" binding:"required"`
}

type Rule struct {
	RuleID          string            `json:"rule_id" gorm:"primaryKey;type:varchar(64)"`
	Name            string            `json:"name" gorm:"type:varchar(128)"`
	Description     string            `json:"description" gorm:"type:varchar(512)"`
	DeviceID        string            `json:"device_id" gorm:"type:varchar(64);index"`
	Enabled         bool              `json:"enabled" gorm:"default:true;index"`
	Conditions      []RuleCondition   `json:"conditions" gorm:"type:jsonb"`
	Actions         []RuleAction      `json:"actions" gorm:"type:jsonb"`
	MatchAll        bool              `json:"match_all" gorm:"default:true"`
	CooldownSeconds int               `json:"cooldown_seconds" gorm:"default:60"`
	LastTriggeredAt *time.Time        `json:"last_triggered_at"`
	TriggerCount    int64             `json:"trigger_count" gorm:"default:0"`
	Tags            map[string]string `json:"tags" gorm:"type:jsonb"`
	CreatedAt       time.Time         `json:"created_at" gorm:"index"`
	UpdatedAt       time.Time         `json:"updated_at" gorm:"index"`
}

func (r *Rule) TableName() string {
	return "rules"
}

type RuleCreateRequest struct {
	Name            string            `json:"name" binding:"required"`
	Description     string            `json:"description"`
	DeviceID        string            `json:"device_id" binding:"required"`
	Enabled         bool              `json:"enabled"`
	Conditions      []RuleCondition   `json:"conditions" binding:"required,min=1"`
	Actions         []RuleAction      `json:"actions" binding:"required,min=1"`
	MatchAll        bool              `json:"match_all"`
	CooldownSeconds int               `json:"cooldown_seconds"`
	Tags            map[string]string `json:"tags"`
}

type RuleTriggerEvent struct {
	RuleID    string                 `json:"rule_id"`
	DeviceID  string                 `json:"device_id"`
	Timestamp time.Time              `json:"timestamp"`
	Data      map[string]interface{} `json:"data"`
}
