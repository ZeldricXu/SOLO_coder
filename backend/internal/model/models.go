package model

import (
	"database/sql/driver"
	"encoding/json"
	"errors"
	"time"
)

type SwitchType string

const (
	SwitchTypeBoolean    SwitchType = "BOOLEAN"
	SwitchTypePercentage SwitchType = "PERCENTAGE"
	SwitchTypeWhitelist  SwitchType = "WHITELIST"
)

type SwitchScope string

const (
	ScopeGlobal      SwitchScope = "GLOBAL"
	ScopeEnvironment SwitchScope = "ENVIRONMENT"
	ScopeTenant      SwitchScope = "TENANT"
)

type SwitchStatus string

const (
	StatusDraft          SwitchStatus = "DRAFT"
	StatusPendingApproval SwitchStatus = "PENDING_APPROVAL"
	StatusActive       SwitchStatus = "ACTIVE"
	StatusInactive     SwitchStatus = "INACTIVE"
	StatusScheduled    SwitchStatus = "SCHEDULED"
)

type StrategyOperator string

const (
	OperatorAND StrategyOperator = "AND"
	OperatorOR  StrategyOperator = "OR"
)

type WhitelistField string

const (
	FieldUserID     WhitelistField = "USER_ID"
	FieldDepartment WhitelistField = "DEPARTMENT"
	FieldTag        WhitelistField = "TAG"
)

type WhitelistOperator string

const (
	OpIn          WhitelistOperator = "IN"
	OpNotIn       WhitelistOperator = "NOT_IN"
	OpContains    WhitelistOperator = "CONTAINS"
	OpNotContains WhitelistOperator = "NOT_CONTAINS"
)

type ApprovalStatus string

const (
	ApprovalPending   ApprovalStatus = "PENDING"
	ApprovalApproved ApprovalStatus = "APPROVED"
	ApprovalRejected ApprovalStatus = "REJECTED"
	ApprovalCancelled ApprovalStatus = "CANCELLED"
)

type EventType string

const (
	EventSwitchCreated   EventType = "SWITCH_CREATED"
	EventSwitchUpdated   EventType = "SWITCH_UPDATED"
	EventSwitchDeleted   EventType = "SWITCH_DELETED"
	EventSwitchEnabled   EventType = "SWITCH_ENABLED"
	EventSwitchDisabled  EventType = "SWITCH_DISABLED"
	EventStrategyUpdated EventType = "STRATEGY_UPDATED"
	EventApprovalRequested EventType = "APPROVAL_REQUESTED"
	EventApprovalApproved  EventType = "APPROVAL_APPROVED"
	EventApprovalRejected  EventType = "APPROVAL_REJECTED"
	EventAutoRollback    EventType = "AUTO_ROLLBACK"
)

type StringArray []string

func (a StringArray) Value() (driver.Value, error) {
	return json.Marshal(a)
}

func (a *StringArray) Scan(value interface{}) error {
	b, ok := value.([]byte)
	if !ok {
		return errors.New("type assertion to []byte failed")
	}
	return json.Unmarshal(b, a)
}

type JSONB map[string]interface{}

func (j JSONB) Value() (driver.Value, error) {
	return json.Marshal(j)
}

func (j *JSONB) Scan(value interface{}) error {
	b, ok := value.([]byte)
	if !ok {
		return errors.New("type assertion to []byte failed")
	}
	return json.Unmarshal(b, j)
}

type Switch struct {
	ID                  string         `json:"id" db:"id"`
	Key                 string         `json:"key" db:"key"`
	Name                string         `json:"name" db:"name"`
	Description         string         `json:"description" db:"description"`
	Type                SwitchType     `json:"type" db:"type"`
	Scope               SwitchScope    `json:"scope" db:"scope"`
	ServiceID           string         `json:"service_id" db:"service_id"`
	ServiceName         string         `json:"service_name,omitempty" db:"-"`
	Owner               string         `json:"owner" db:"owner"`
	Status              SwitchStatus   `json:"status" db:"status"`
	Enabled             bool           `json:"enabled" db:"enabled"`
	BooleanValue        bool           `json:"boolean_value,omitempty" db:"boolean_value"`
	PercentageValue     int            `json:"percentage_value,omitempty" db:"percentage_value"`
	Environment         string         `json:"environment,omitempty" db:"environment"`
	TenantID            string         `json:"tenant_id,omitempty" db:"tenant_id"`
	RequireApproval     bool           `json:"require_approval" db:"require_approval"`
	AutoRollbackEnabled bool           `json:"auto_rollback_enabled" db:"auto_rollback_enabled"`
	AutoRollbackThreshold float64      `json:"auto_rollback_threshold,omitempty" db:"auto_rollback_threshold"`
	CreatedBy           string         `json:"created_by" db:"created_by"`
	CreatedAt           time.Time      `json:"created_at" db:"created_at"`
	UpdatedAt           time.Time      `json:"updated_at" db:"updated_at"`
	DeletedAt           *time.Time     `json:"deleted_at,omitempty" db:"deleted_at"`
	Strategies          []*Strategy    `json:"strategies,omitempty" db:"-"`
}

type Strategy struct {
	ID          string              `json:"id" db:"id"`
	SwitchID    string              `json:"switch_id" db:"switch_id"`
	Name        string              `json:"name" db:"name"`
	Description string              `json:"description" db:"description"`
	Operator    StrategyOperator    `json:"operator" db:"operator"`
	Priority    int                 `json:"priority" db:"priority"`
	Enabled     bool                `json:"enabled" db:"enabled"`
	Conditions  []*WhitelistCondition `json:"conditions,omitempty" db:"-"`
	CreatedAt   time.Time           `json:"created_at" db:"created_at"`
	UpdatedAt   time.Time           `json:"updated_at" db:"updated_at"`
}

type WhitelistCondition struct {
	ID        string            `json:"id" db:"id"`
	StrategyID string           `json:"strategy_id" db:"strategy_id"`
	Field     WhitelistField    `json:"field" db:"field"`
	Operator  WhitelistOperator `json:"operator" db:"operator"`
	Values    StringArray       `json:"values" db:"values"`
	CreatedAt time.Time         `json:"created_at" db:"created_at"`
}

type SwitchHistory struct {
	ID            string     `json:"id" db:"id"`
	SwitchID      string     `json:"switch_id" db:"switch_id"`
	EventType     EventType  `json:"event_type" db:"event_type"`
	OldValue      *JSONB     `json:"old_value,omitempty" db:"old_value"`
	NewValue      *JSONB     `json:"new_value,omitempty" db:"new_value"`
	OperatorUser  string     `json:"operator_user" db:"operator_user"`
	Remark        string     `json:"remark,omitempty" db:"remark"`
	CreatedAt     time.Time  `json:"created_at" db:"created_at"`
}

type Approval struct {
	ID             string         `json:"id" db:"id"`
	SwitchID       string         `json:"switch_id" db:"switch_id"`
	SwitchKey      string         `json:"switch_key,omitempty" db:"-"`
	SwitchName     string         `json:"switch_name,omitempty" db:"-"`
	Title          string         `json:"title" db:"title"`
	Description    string         `json:"description,omitempty" db:"description"`
	Requester      string         `json:"requester" db:"requester"`
	Approver       string         `json:"approver" db:"approver"`
	Status         ApprovalStatus `json:"status" db:"status"`
	ChangeContent  JSONB          `json:"change_content" db:"change_content"`
	ApprovedAt     *time.Time     `json:"approved_at,omitempty" db:"approved_at"`
	RejectedAt     *time.Time     `json:"rejected_at,omitempty" db:"rejected_at"`
	RejectReason   string         `json:"reject_reason,omitempty" db:"reject_reason"`
	CreatedAt      time.Time      `json:"created_at" db:"created_at"`
	UpdatedAt      time.Time      `json:"updated_at" db:"updated_at"`
}

type ScheduledTask struct {
	ID            string     `json:"id" db:"id"`
	SwitchID      string     `json:"switch_id" db:"switch_id"`
	TaskType      string     `json:"task_type" db:"task_type"`
	TargetEnabled bool       `json:"target_enabled" db:"target_enabled"`
	ExecuteAt     time.Time  `json:"execute_at" db:"execute_at"`
	ExecutedAt    *time.Time `json:"executed_at,omitempty" db:"executed_at"`
	Status        string     `json:"status" db:"status"`
	ErrorMessage  string     `json:"error_message,omitempty" db:"error_message"`
	CreatedBy     string     `json:"created_by" db:"created_by"`
	CreatedAt     time.Time  `json:"created_at" db:"created_at"`
}

type SwitchStats struct {
	ID              string    `json:"id" db:"id"`
	SwitchID        string    `json:"switch_id" db:"switch_id"`
	Date            string    `json:"date" db:"date"`
	TotalEvaluations int64    `json:"total_evaluations" db:"total_evaluations"`
	TrueCount       int64    `json:"true_count" db:"true_count"`
	FalseCount      int64    `json:"false_count" db:"false_count"`
	ErrorCount      int64    `json:"error_count" db:"error_count"`
	AvgLatencyMs    float64  `json:"avg_latency_ms" db:"avg_latency_ms"`
	P99LatencyMs    float64  `json:"p99_latency_ms" db:"p99_latency_ms"`
	CreatedAt       time.Time `json:"created_at" db:"created_at"`
	UpdatedAt       time.Time `json:"updated_at" db:"updated_at"`
}

type SwitchIntegration struct {
	ID         string    `json:"id" db:"id"`
	SwitchID   string    `json:"switch_id" db:"switch_id"`
	ServiceName string   `json:"service_name" db:"service_name"`
	SDKVersion string    `json:"sdk_version,omitempty" db:"sdk_version"`
	LastPollAt time.Time `json:"last_poll_at,omitempty" db:"last_poll_at"`
	CreatedAt  time.Time `json:"created_at" db:"created_at"`
	UpdatedAt  time.Time `json:"updated_at" db:"updated_at"`
}

type Service struct {
	ID          string     `json:"id" db:"id"`
	Name        string     `json:"name" db:"name"`
	Description string     `json:"description" db:"description"`
	Owner       string     `json:"owner" db:"owner"`
	CreatedAt   time.Time  `json:"created_at" db:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at" db:"updated_at"`
	DeletedAt   *time.Time `json:"deleted_at,omitempty" db:"deleted_at"`
}

type User struct {
	ID         string    `json:"id" db:"id"`
	Username   string    `json:"username" db:"username"`
	Email      string    `json:"email" db:"email"`
	Name       string    `json:"name" db:"name"`
	Department string    `json:"department" db:"department"`
	Role       string    `json:"role" db:"role"`
	CreatedAt  time.Time `json:"created_at" db:"created_at"`
	UpdatedAt  time.Time `json:"updated_at" db:"updated_at"`
}

type AuditLog struct {
	ID           string    `json:"id" db:"id"`
	UserID       string    `json:"user_id" db:"user_id"`
	Action       string    `json:"action" db:"action"`
	ResourceType string    `json:"resource_type" db:"resource_type"`
	ResourceID   string    `json:"resource_id,omitempty" db:"resource_id"`
	Details      *JSONB    `json:"details,omitempty" db:"details"`
	IPAddress    string    `json:"ip_address,omitempty" db:"ip_address"`
	UserAgent    string    `json:"user_agent,omitempty" db:"user_agent"`
	CreatedAt    time.Time `json:"created_at" db:"created_at"`
}

type EvaluationContext struct {
	UserID     string            `json:"user_id"`
	Department string            `json:"department"`
	Tags       []string          `json:"tags"`
	Environment string            `json:"environment"`
	TenantID   string            `json:"tenant_id"`
	Attributes map[string]string `json:"attributes"`
}

type EvaluationResult struct {
	Enabled    bool                   `json:"enabled"`
	Matched   bool                   `json:"matched"`
	Reason   string                 `json:"reason"`
	SwitchKey string                `json:"switch_key"`
	Value     interface{}            `json:"value,omitempty"`
	MatchedStrategy *Strategy        `json:"matched_strategy,omitempty"`
}

type SDKConfigResponse struct {
	Version     int64            `json:"version"`
	Switches    []*SwitchSnapshot `json:"switches"`
	UpdatedAt   time.Time        `json:"updated_at"`
}

type SwitchSnapshot struct {
	Key             string              `json:"key"`
	Type            SwitchType        `json:"type"`
	Enabled         bool              `json:"enabled"`
	BooleanValue    bool              `json:"boolean_value,omitempty"`
	PercentageValue int               `json:"percentage_value,omitempty"`
	Strategies      []*StrategySnapshot `json:"strategies,omitempty"`
	UpdatedAt       time.Time         `json:"updated_at"`
}

type StrategySnapshot struct {
	ID         string                  `json:"id"`
	Operator   StrategyOperator        `json:"operator"`
	Priority   int                     `json:"priority"`
	Conditions []*ConditionSnapshot `json:"conditions"`
}

type ConditionSnapshot struct {
	Field    WhitelistField    `json:"field"`
	Operator WhitelistOperator `json:"operator"`
	Values   []string        `json:"values"`
}

type Pagination struct {
	Page     int `json:"page"`
	PageSize int `json:"page_size"`
	Total    int64 `json:"total"`
}

type ListRequest struct {
	Page        int    `form:"page,default=1"`
	PageSize    int    `form:"page_size,default=20"`
	Keyword     string `form:"keyword"`
	ServiceID   string `form:"service_id"`
	Environment string `form:"environment"`
	Status      string `form:"status"`
	Owner       string `form:"owner"`
	Type        string `form:"type"`
	Scope       string `form:"scope"`
}

type ListResponse struct {
	Data       interface{} `json:"data"`
	Pagination Pagination  `json:"pagination"`
}

type Response struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

func (r *Response) Success(data interface{}) *Response {
	r.Code = 0
	r.Message = "success"
	r.Data = data
	return r
}

func (r *Response) Error(code int, message string) *Response {
	r.Code = code
	r.Message = message
	return r
}

func NewSuccessResponse(data interface{}) *Response {
	return &Response{
		Code:    0,
		Message: "success",
		Data:    data,
	}
}

func NewErrorResponse(code int, message string) *Response {
	return &Response{
		Code:    code,
		Message: message,
	}
}

type CreateSwitchRequest struct {
	Key                 string         `json:"key" binding:"required"`
	Name                string         `json:"name" binding:"required"`
	Description         string         `json:"description"`
	Type                SwitchType     `json:"type" binding:"required"`
	Scope               SwitchScope    `json:"scope" binding:"required"`
	ServiceID           string         `json:"service_id" binding:"required"`
	Owner               string         `json:"owner" binding:"required"`
	BooleanValue        bool           `json:"boolean_value"`
	PercentageValue     int            `json:"percentage_value"`
	Environment         string         `json:"environment"`
	TenantID            string         `json:"tenant_id"`
	RequireApproval     bool           `json:"require_approval"`
	AutoRollbackEnabled bool           `json:"auto_rollback_enabled"`
	AutoRollbackThreshold float64      `json:"auto_rollback_threshold"`
	Strategies          []*Strategy    `json:"strategies"`
}

type UpdateSwitchRequest struct {
	Name                string         `json:"name"`
	Description         string         `json:"description"`
	Type                SwitchType     `json:"type"`
	Scope               SwitchScope    `json:"scope"`
	ServiceID           string         `json:"service_id"`
	Owner               string         `json:"owner"`
	BooleanValue        bool           `json:"boolean_value"`
	PercentageValue     int            `json:"percentage_value"`
	Environment         string         `json:"environment"`
	TenantID            string         `json:"tenant_id"`
	RequireApproval     bool           `json:"require_approval"`
	AutoRollbackEnabled bool           `json:"auto_rollback_enabled"`
	AutoRollbackThreshold float64      `json:"auto_rollback_threshold"`
}

type EvaluateRequest struct {
	Key               string            `json:"key" binding:"required"`
	UserID            string            `json:"user_id"`
	Department        string            `json:"department"`
	Tags              []string          `json:"tags"`
	Environment       string            `json:"environment" binding:"required"`
	TenantID          string            `json:"tenant_id"`
	Attributes        map[string]string `json:"attributes"`
}

type BatchEvaluateRequest struct {
	Keys              []string          `json:"keys"`
	UserID            string            `json:"user_id"`
	Department        string            `json:"department"`
	Tags              []string          `json:"tags"`
	Environment       string            `json:"environment" binding:"required"`
	TenantID          string            `json:"tenant_id"`
	Attributes        map[string]string `json:"attributes"`
}

type ApprovalRequest struct {
	SwitchID       string `json:"switch_id" binding:"required"`
	Title          string `json:"title" binding:"required"`
	Description    string `json:"description"`
	Approver       string `json:"approver" binding:"required"`
	TargetEnabled  bool   `json:"target_enabled"`
}

type ApprovalProcessRequest struct {
	ApprovalID   string `json:"approval_id" binding:"required"`
	RejectReason string `json:"reject_reason"`
}

type ScheduleRequest struct {
	SwitchID      string    `json:"switch_id" binding:"required"`
	TaskType      string    `json:"task_type" binding:"required"`
	TargetEnabled bool      `json:"target_enabled" binding:"required"`
	ExecuteAt     time.Time `json:"execute_at" binding:"required"`
}

type BatchOperationRequest struct {
	IDs       []string `json:"ids" binding:"required"`
	Operation string   `json:"operation" binding:"required"`
}

type BatchServiceOperationRequest struct {
	ServiceID string `json:"service_id" binding:"required"`
	Operation string `json:"operation" binding:"required"`
}

type StatsSummary struct {
	TotalEvaluations int64   `json:"total_evaluations"`
	TrueCount        int64   `json:"true_count"`
	FalseCount       int64   `json:"false_count"`
	ErrorCount       int64   `json:"error_count"`
	AvgLatencyMs     float64 `json:"avg_latency_ms"`
	P99LatencyMs     float64 `json:"p99_latency_ms"`
}

type ChangeEvent struct {
	EventType EventType   `json:"event_type"`
	SwitchID  string      `json:"switch_id"`
	SwitchKey string      `json:"switch_key"`
	Operator  string      `json:"operator"`
	Timestamp time.Time   `json:"timestamp"`
	Data      interface{} `json:"data"`
}

type StatsReportRequest struct {
	SwitchKey      string `json:"switch_key" binding:"required"`
	TotalCount     int64  `json:"total_count"`
	TrueCount      int64  `json:"true_count"`
	FalseCount     int64  `json:"false_count"`
	ErrorCount     int64  `json:"error_count"`
	AvgLatencyMs   float64 `json:"avg_latency_ms"`
	P99LatencyMs   float64 `json:"p99_latency_ms"`
	ServiceName    string `json:"service_name"`
	SDKVersion     string `json:"sdk_version"`
}
