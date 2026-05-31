package edge_rules

import (
	"time"

	"edgescheduler/internal/common/models"
)

type RuleStatus string
type RuleType string
type ActionType string
type ConditionOperator string

const (
	RuleStatusEnabled  RuleStatus = "enabled"
	RuleStatusDisabled RuleStatus = "disabled"
	RuleStatusError    RuleStatus = "error"

	RuleTypeDataflow   RuleType = "dataflow"
	RuleTypeDevice     RuleType = "device"
	RuleTypeScheduled  RuleType = "scheduled"
	RuleTypeCustom     RuleType = "custom"

	ActionTypeLocalCommand ActionType = "local_command"
	ActionTypeAPICall      ActionType = "api_call"
	ActionTypeMQTTPublish   ActionType = "mqtt_publish"
	ActionTypeTriggerRule   ActionType = "trigger_rule"
	ActionTypeSetDesired    ActionType = "set_desired"
	ActionTypeAlert         ActionType = "alert"

	ConditionOpEqual          ConditionOperator = "eq"
	ConditionOpNotEqual       ConditionOperator = "neq"
	ConditionOpGreaterThan    ConditionOperator = "gt"
	ConditionOpGreaterOrEqual ConditionOperator = "gte"
	ConditionOpLessThan       ConditionOperator = "lt"
	ConditionOpLessOrEqual    ConditionOperator = "lte"
	ConditionOpContains       ConditionOperator = "contains"
	ConditionOpNotContains    ConditionOperator = "not_contains"
	ConditionOpIn             ConditionOperator = "in"
	ConditionOpNotIn          ConditionOperator = "not_in"
	ConditionOpRegex          ConditionOperator = "regex"
)

type Rule struct {
	models.BaseModel
	RuleID      string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"rule_id"`
	Name        string                 `gorm:"type:varchar(200);not null" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	RuleType    RuleType               `gorm:"type:varchar(30);not null;index" json:"rule_type"`
	Status      RuleStatus             `gorm:"type:varchar(20);not null;index" json:"status"`
	Priority    int                    `gorm:"default:0;index" json:"priority"`

	DataSources  []string              `gorm:"type:varchar(50)[]" json:"data_sources,omitempty"`
	Conditions   []RuleCondition       `gorm:"type:jsonb" json:"conditions"`
	ConditionLogic string              `gorm:"type:varchar(10);default:'AND'" json:"condition_logic"`

	Actions      []RuleAction          `gorm:"type:jsonb" json:"actions"`

	Trigger      map[string]interface{} `gorm:"type:jsonb" json:"trigger,omitempty"`

	ExecutionCount int                  `gorm:"default:0" json:"execution_count"`
	SuccessCount   int                  `gorm:"default:0" json:"success_count"`
	FailedCount    int                  `gorm:"default:0" json:"failed_count"`
	LastExecutedAt *time.Time           `json:"last_executed_at,omitempty"`
	LastError      string               `gorm:"type:text" json:"last_error,omitempty"`

	TimeoutMs    int                  `gorm:"default:5000" json:"timeout_ms"`
	Retries      int                  `gorm:"default:0" json:"retries"`
}

type RuleCondition struct {
	ID         string              `json:"id"`
	Field      string              `json:"field" binding:"required"`
	Operator   ConditionOperator   `json:"operator" binding:"required"`
	Value      interface{}         `json:"value" binding:"required"`
	DataType   string              `json:"data_type"`
}

type RuleAction struct {
	ID         string              `json:"id"`
	Type       ActionType          `json:"type" binding:"required"`
	Parameters map[string]interface{} `json:"parameters"`
	DelayMs    int                 `json:"delay_ms,omitempty"`
	Enabled    bool                `json:"enabled"`
}

type RuleExecutionLog struct {
	models.BaseModel
	LogID       string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"log_id"`
	RuleID      string                 `gorm:"type:varchar(50);not null;index" json:"rule_id"`
	RuleName    string                 `gorm:"type:varchar(200)" json:"rule_name"`
	TriggerEvent string               `gorm:"type:varchar(200)" json:"trigger_event"`
	TriggerData map[string]interface{} `gorm:"type:jsonb" json:"trigger_data"`
	Status      string                 `gorm:"type:varchar(20);index" json:"status"`
	Result      map[string]interface{} `gorm:"type:jsonb" json:"result"`
	Error       string                 `gorm:"type:text" json:"error,omitempty"`
	ExecutionTimeMs int64              `gorm:"default:0" json:"execution_time_ms"`
}

type RuleCreateRequest struct {
	Name          string                 `json:"name" binding:"required"`
	Description   string                 `json:"description"`
	RuleType      RuleType               `json:"rule_type" binding:"required"`
	Priority      int                    `json:"priority"`
	DataSources   []string               `json:"data_sources"`
	Conditions    []RuleCondition        `json:"conditions" binding:"required,min=1"`
	ConditionLogic string                `json:"condition_logic"`
	Actions       []RuleAction           `json:"actions" binding:"required,min=1"`
	Trigger       map[string]interface{} `json:"trigger"`
	TimeoutMs     int                    `json:"timeout_ms"`
	Retries       int                    `json:"retries"`
}

type RuleUpdateRequest struct {
	Name          *string                `json:"name"`
	Description   *string                `json:"description"`
	Status        *RuleStatus            `json:"status"`
	Priority      *int                   `json:"priority"`
	Conditions    *[]RuleCondition       `json:"conditions"`
	ConditionLogic *string               `json:"condition_logic"`
	Actions       *[]RuleAction          `json:"actions"`
}

type RuleExecutionRequest struct {
	TriggerData map[string]interface{} `json:"trigger_data"`
}
