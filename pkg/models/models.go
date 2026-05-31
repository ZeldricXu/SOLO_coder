package models

import (
	"encoding/json"
	"time"
)

type Entity struct {
	ID         string          `json:"id" gorm:"primaryKey;size:64"`
	Type       string          `json:"type" gorm:"size:32;index"`
	Status     string          `json:"status" gorm:"size:32;index"`
	Attributes json.RawMessage `json:"attributes" gorm:"type:jsonb"`
	CreatedAt  time.Time       `json:"created_at" gorm:"index"`
	UpdatedAt  time.Time       `json:"updated_at"`
	TenantID   string          `json:"tenant_id" gorm:"size:64;index"`
}

func (Entity) TableName() string { return "entities" }

type ConfigDefinition struct {
	ConfigID   string          `json:"config_id" gorm:"primaryKey;size:64"`
	Namespace  string          `json:"namespace" gorm:"size:64;index"`
	Version    int             `json:"version" gorm:"index"`
	Parameters json.RawMessage `json:"parameters" gorm:"type:jsonb"`
	Enabled    bool            `json:"enabled" gorm:"index"`
	AppliedAt  time.Time       `json:"applied_at"`
	TenantID   string          `json:"tenant_id" gorm:"size:64;index"`
}

func (ConfigDefinition) TableName() string { return "config_definitions" }

type RunInstance struct {
	RunID        string          `json:"run_id" gorm:"primaryKey;size:64"`
	EntityID     string          `json:"entity_id" gorm:"size:64;index"`
	Phase        string          `json:"phase" gorm:"size:32;index"`
	Progress     float64         `json:"progress" gorm:"type:decimal(5,2)"`
	StartedAt    time.Time       `json:"started_at"`
	CompletedAt  *time.Time      `json:"completed_at"`
	ErrorDetail  json.RawMessage `json:"error_detail" gorm:"type:jsonb"`
	TenantID     string          `json:"tenant_id" gorm:"size:64;index"`
}

func (RunInstance) TableName() string { return "run_instances" }

type StatsSnapshot struct {
	SnapshotID string          `json:"snapshot_id" gorm:"primaryKey;size:64"`
	Timestamp  time.Time       `json:"timestamp" gorm:"index"`
	Metrics    json.RawMessage `json:"metrics" gorm:"type:jsonb"`
	Dimensions json.RawMessage `json:"dimensions" gorm:"type:jsonb"`
	TenantID   string          `json:"tenant_id" gorm:"size:64;index"`
}

func (StatsSnapshot) TableName() string { return "stats_snapshots" }

type Tenant struct {
	ID          string          `json:"id" gorm:"primaryKey;size:64"`
	Name        string          `json:"name" gorm:"size:128;uniqueIndex"`
	Description string          `json:"description" gorm:"size:512"`
	Config      json.RawMessage `json:"config" gorm:"type:jsonb"`
	Quota       json.RawMessage `json:"quota" gorm:"type:jsonb"`
	Status      string          `json:"status" gorm:"size:32;index"`
	CreatedAt   time.Time       `json:"created_at"`
	UpdatedAt   time.Time       `json:"updated_at"`
}

func (Tenant) TableName() string { return "tenants" }

type ApprovalRule struct {
	ID          string          `json:"id" gorm:"primaryKey;size:64"`
	Name        string          `json:"name" gorm:"size:128"`
	WorkflowID  string          `json:"workflow_id" gorm:"size:64;index"`
	Condition   json.RawMessage `json:"condition" gorm:"type:jsonb"`
	Strategy    string          `json:"strategy" gorm:"size:32"`
	Approvers   json.RawMessage `json:"approvers" gorm:"type:jsonb"`
	Priority    int             `json:"priority"`
	Enabled     bool            `json:"enabled" gorm:"index"`
	CreatedAt   time.Time       `json:"created_at"`
	UpdatedAt   time.Time       `json:"updated_at"`
	TenantID    string          `json:"tenant_id" gorm:"size:64;index"`
}

func (ApprovalRule) TableName() string { return "approval_rules" }

type ApprovalTask struct {
	ID           string          `json:"id" gorm:"primaryKey;size:64"`
	WorkflowID   string          `json:"workflow_id" gorm:"size:64;index"`
	InstanceID   string          `json:"instance_id" gorm:"size:64;index"`
	RuleID       string          `json:"rule_id" gorm:"size:64"`
	ApproverID   string          `json:"approver_id" gorm:"size:64;index"`
	Status       string          `json:"status" gorm:"size:32;index"`
	Comment      string          `json:"comment" gorm:"size:1024"`
	DecisionTime *time.Time      `json:"decision_time"`
	Payload      json.RawMessage `json:"payload" gorm:"type:jsonb"`
	CreatedAt    time.Time       `json:"created_at"`
	UpdatedAt    time.Time       `json:"updated_at"`
	TenantID     string          `json:"tenant_id" gorm:"size:64;index"`
}

func (ApprovalTask) TableName() string { return "approval_tasks" }

type Skill struct {
	ID          string          `json:"id" gorm:"primaryKey;size:64"`
	Name        string          `json:"name" gorm:"size:128"`
	Description string          `json:"description" gorm:"size:512"`
	ParentID    *string         `json:"parent_id" gorm:"size:64;index"`
	Category    string          `json:"category" gorm:"size:64;index"`
	Level       int             `json:"level"`
	Metadata    json.RawMessage `json:"metadata" gorm:"type:jsonb"`
	CreatedAt   time.Time       `json:"created_at"`
	UpdatedAt   time.Time       `json:"updated_at"`
}

func (Skill) TableName() string { return "skills" }

type EmployeeSkill struct {
	ID           string    `json:"id" gorm:"primaryKey;size:64"`
	EmployeeID   string    `json:"employee_id" gorm:"size:64;index"`
	SkillID      string    `json:"skill_id" gorm:"size:64;index"`
	Proficiency  int       `json:"proficiency"`
	AssessmentAt time.Time `json:"assessment_at"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
}

func (EmployeeSkill) TableName() string { return "employee_skills" }

type LearningPath struct {
	ID          string          `json:"id" gorm:"primaryKey;size:64"`
	EmployeeID  string          `json:"employee_id" gorm:"size:64;index"`
	Name        string          `json:"name" gorm:"size:128"`
	Description string          `json:"description" gorm:"size:512"`
	Steps       json.RawMessage `json:"steps" gorm:"type:jsonb"`
	Status      string          `json:"status" gorm:"size:32;index"`
	Progress    float64         `json:"progress" gorm:"type:decimal(5,2)"`
	CreatedAt   time.Time       `json:"created_at"`
	UpdatedAt   time.Time       `json:"updated_at"`
}

func (LearningPath) TableName() string { return "learning_paths" }

type SLAConfiguration struct {
	ID             string    `json:"id" gorm:"primaryKey;size:64"`
	Name           string    `json:"name" gorm:"size:128"`
	WorkflowType   string    `json:"workflow_type" gorm:"size:64;index"`
	ResponseTime   int       `json:"response_time"`
	ResolutionTime int       `json:"resolution_time"`
	EscalationTime int       `json:"escalation_time"`
	Enabled        bool      `json:"enabled" gorm:"index"`
	CreatedAt      time.Time `json:"created_at"`
	UpdatedAt      time.Time `json:"updated_at"`
	TenantID       string    `json:"tenant_id" gorm:"size:64;index"`
}

func (SLAConfiguration) TableName() string { return "sla_configurations" }

type SLATracking struct {
	ID             string     `json:"id" gorm:"primaryKey;size:64"`
	InstanceID     string     `json:"instance_id" gorm:"size:64;index"`
	SLAConfigID    string     `json:"sla_config_id" gorm:"size:64"`
	ResponseDue    time.Time  `json:"response_due"`
	ResolutionDue  time.Time  `json:"resolution_due"`
	EscalationDue  time.Time  `json:"escalation_due"`
	ResponseAt     *time.Time `json:"response_at"`
	ResolutionAt   *time.Time `json:"resolution_at"`
	EscalatedAt    *time.Time `json:"escalated_at"`
	Status         string     `json:"status" gorm:"size:32;index"`
	BreachCount    int        `json:"breach_count"`
	CreatedAt      time.Time  `json:"created_at"`
	UpdatedAt      time.Time  `json:"updated_at"`
	TenantID       string     `json:"tenant_id" gorm:"size:64;index"`
}

func (SLATracking) TableName() string { return "sla_tracking" }

type WorkflowDefinition struct {
	ID          string          `json:"id" gorm:"primaryKey;size:64"`
	Name        string          `json:"name" gorm:"size:128"`
	Description string          `json:"description" gorm:"size:512"`
	Version     int             `json:"version"`
	Nodes       json.RawMessage `json:"nodes" gorm:"type:jsonb"`
	Edges       json.RawMessage `json:"edges" gorm:"type:jsonb"`
	Config      json.RawMessage `json:"config" gorm:"type:jsonb"`
	Enabled     bool            `json:"enabled" gorm:"index"`
	CreatedAt   time.Time       `json:"created_at"`
	UpdatedAt   time.Time       `json:"updated_at"`
	TenantID    string          `json:"tenant_id" gorm:"size:64;index"`
}

func (WorkflowDefinition) TableName() string { return "workflow_definitions" }

type WorkflowInstance struct {
	ID              string          `json:"id" gorm:"primaryKey;size:64"`
	DefinitionID    string          `json:"definition_id" gorm:"size:64;index"`
	CurrentNodeID   string          `json:"current_node_id" gorm:"size:64"`
	Status          string          `json:"status" gorm:"size:32;index"`
	Payload         json.RawMessage `json:"payload" gorm:"type:jsonb"`
	Context         json.RawMessage `json:"context" gorm:"type:jsonb"`
	StartedAt       time.Time       `json:"started_at"`
	CompletedAt     *time.Time      `json:"completed_at"`
	LastActivityAt  time.Time       `json:"last_activity_at"`
	TenantID        string          `json:"tenant_id" gorm:"size:64;index"`
}

func (WorkflowInstance) TableName() string { return "workflow_instances" }

type ScheduledTask struct {
	ID         string          `json:"id" gorm:"primaryKey;size:64"`
	Name       string          `json:"name" gorm:"size:128"`
	CronExpr   string          `json:"cron_expr" gorm:"size:128"`
	TaskType   string          `json:"task_type" gorm:"size:64;index"`
	Payload    json.RawMessage `json:"payload" gorm:"type:jsonb"`
	Enabled    bool            `json:"enabled" gorm:"index"`
	LastRunAt  *time.Time      `json:"last_run_at"`
	NextRunAt  time.Time       `json:"next_run_at"`
	CreatedAt  time.Time       `json:"created_at"`
	UpdatedAt  time.Time       `json:"updated_at"`
	TenantID   string          `json:"tenant_id" gorm:"size:64;index"`
}

func (ScheduledTask) TableName() string { return "scheduled_tasks" }

type SchemaMigration struct {
	ID        string    `json:"id" gorm:"primaryKey;size:64"`
	Version   int64     `json:"version" gorm:"uniqueIndex"`
	Name      string    `json:"name" gorm:"size:256"`
	AppliedAt time.Time `json:"applied_at"`
	TenantID  string    `json:"tenant_id" gorm:"size:64;default:''"`
}

func (SchemaMigration) TableName() string { return "schema_migrations" }

type APIResponse struct {
	Code    int             `json:"code"`
	Message string          `json:"message,omitempty"`
	Data    json.RawMessage `json:"data,omitempty"`
	Total   *int64          `json:"total,omitempty"`
	Page    *int            `json:"page,omitempty"`
	PageSize *int           `json:"page_size,omitempty"`
}

type ResourceRequest struct {
	Type   string                 `json:"type" binding:"required"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

type BatchOperation struct {
	Action string            `json:"action" binding:"required"`
	ID     string            `json:"id"`
	Params map[string]string `json:"params"`
}

type BatchRequest struct {
	Operations []BatchOperation `json:"operations" binding:"required,min=1"`
}

type Quota struct {
	MaxStorageGB   int `json:"max_storage_gb"`
	MaxUsers       int `json:"max_users"`
	MaxWorkflows   int `json:"max_workflows"`
	MaxAPICallsDay int `json:"max_api_calls_day"`
}

type TenantConfig struct {
	Theme       string                 `json:"theme"`
	Language    string                 `json:"language"`
	Timezone    string                 `json:"timezone"`
	Features    map[string]bool        `json:"features"`
	CustomParams map[string]interface{} `json:"custom_params"`
}
