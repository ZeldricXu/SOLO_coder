package model

import (
	"time"
)

type Rule struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name        string                 `json:"name" gorm:"type:varchar(128)"`
	Description string                 `json:"description" gorm:"type:text"`
	RuleType    string                 `json:"rule_type" gorm:"type:varchar(32);index"`
	TriggerType string                 `json:"trigger_type" gorm:"type:varchar(32)"`
	Condition   string                 `json:"condition" gorm:"type:text"`
	ConditionConfig map[string]interface{} `json:"condition_config" gorm:"type:jsonb"`
	Actions     []RuleAction           `json:"actions" gorm:"type:jsonb"`
	DeviceIDs   []string               `json:"device_ids" gorm:"type:text[]"`
	Priority    int                    `json:"priority" gorm:"default:0"`
	IsEnabled   bool                   `json:"is_enabled" gorm:"default:true"`
	ExecutionMode string               `json:"execution_mode" gorm:"type:varchar(32)"`
	Timeout     int                    `json:"timeout" gorm:"default:30"`
	RetryCount  int                    `json:"retry_count" gorm:"default:0"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	LastTriggered *time.Time           `json:"last_triggered"`
	TriggerCount  int64                `json:"trigger_count" gorm:"default:0"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type RuleAction struct {
	ActionType string                 `json:"action_type"`
	Target     string                 `json:"target"`
	Parameters map[string]interface{} `json:"parameters"`
	Timeout    int                    `json:"timeout"`
}

type RuleExecution struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	RuleID      string                 `json:"rule_id" gorm:"type:varchar(64);index"`
	DeviceID    string                 `json:"device_id" gorm:"type:varchar(64);index"`
	TriggerType string                 `json:"trigger_type" gorm:"type:varchar(32)"`
	TriggerData map[string]interface{} `json:"trigger_data" gorm:"type:jsonb"`
	Status      string                 `json:"status" gorm:"type:varchar(32);index"`
	Actions     []ActionExecution      `json:"actions" gorm:"type:jsonb"`
	ErrorMessage *string               `json:"error_message"`
	DurationMs  int64                  `json:"duration_ms"`
	Timestamp   time.Time              `json:"timestamp" gorm:"index"`
	CreatedAt   time.Time              `json:"created_at"`
}

type ActionExecution struct {
	ActionType  string                 `json:"action_type"`
	Target      string                 `json:"target"`
	Status      string                 `json:"status"`
	Result      map[string]interface{} `json:"result"`
	ErrorMessage *string               `json:"error_message"`
	DurationMs  int64                  `json:"duration_ms"`
	StartedAt   time.Time              `json:"started_at"`
	CompletedAt *time.Time             `json:"completed_at"`
}

const (
	RuleTypeDataMonitor = "data_monitor"
	RuleTypeSchedule    = "schedule"
	RuleTypeEvent       = "event"
	RuleTypeComposite   = "composite"
)

const (
	TriggerTypeTimer     = "timer"
	TriggerTypeCondition = "condition"
	TriggerTypeEvent     = "event"
	TriggerTypeManual    = "manual"
	TriggerTypeCron      = "cron"
)

const (
	ExecutionStatusSuccess   = "success"
	ExecutionStatusFailed    = "failed"
	ExecutionStatusRunning   = "running"
	ExecutionStatusSkipped   = "skipped"
	ExecutionStatusTimeout   = "timeout"
)

const (
	ActionTypeDeviceControl = "device_control"
	ActionTypeSendAlert     = "send_alert"
	ActionTypeHTTPRequest   = "http_request"
	ActionTypeMQTTPublish   = "mqtt_publish"
	ActionTypeSetShadow     = "set_shadow"
	ActionTypeTriggerRule   = "trigger_rule"
)
