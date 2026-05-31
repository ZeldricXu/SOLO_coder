package models

import (
	"time"
)

type AttackStrategy string

const (
	AttackStrategyPromptInjection AttackStrategy = "prompt_injection"
	AttackStrategyJailbreak       AttackStrategy = "jailbreak"
	AttackStrategyRoleplay        AttackStrategy = "roleplay"
	AttackStrategyObfuscation     AttackStrategy = "obfuscation"
	AttackStrategyFewShot         AttackStrategy = "few_shot"
	AttackStrategyTranslation     AttackStrategy = "translation"
	AttackStrategyTypo            AttackStrategy = "typo"
	AttackStrategyContextOverflow AttackStrategy = "context_overflow"
)

type AttackSeverity string

const (
	AttackSeverityLow      AttackSeverity = "low"
	AttackSeverityMedium   AttackSeverity = "medium"
	AttackSeverityHigh     AttackSeverity = "high"
	AttackSeverityCritical AttackSeverity = "critical"
)

type AttackStatus string

const (
	AttackStatusPending   AttackStatus = "pending"
	AttackStatusRunning   AttackStatus = "running"
	AttackStatusCompleted AttackStatus = "completed"
	AttackStatusFailed    AttackStatus = "failed"
)

type MergeStrategy string

const (
	MergeStrategyUnion        MergeStrategy = "union"
	MergeStrategyIntersection MergeStrategy = "intersection"
	MergeStrategyBestOf       MergeStrategy = "best_of"
	MergeStrategyWeighted     MergeStrategy = "weighted"
)

type AdversarialPrompt struct {
	ID                string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Strategy          string                 `json:"strategy" gorm:"type:varchar(64);index"`
	Severity          string                 `json:"severity" gorm:"type:varchar(32);index"`
	OriginalPrompt    string                 `json:"original_prompt" gorm:"type:text"`
	AdversarialText   string                 `json:"adversarial_text" gorm:"type:text"`
	TargetBehavior    string                 `json:"target_behavior" gorm:"type:varchar(256)"`
	ExpectedOutput    string                 `json:"expected_output,omitempty" gorm:"type:text"`
	AttackConfig      map[string]interface{} `json:"attack_config" gorm:"type:jsonb"`
	SuccessThreshold  float64                `json:"success_threshold"`
	CreatedBy         string                 `json:"created_by" gorm:"type:varchar(64)"`
	CreatedAt         time.Time              `json:"created_at"`
	UpdatedAt         time.Time              `json:"updated_at"`
}

type AttackJob struct {
	ID               string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name             string                 `json:"name" gorm:"type:varchar(128);index"`
	Description      string                 `json:"description" gorm:"type:text"`
	TargetModel      string                 `json:"target_model" gorm:"type:varchar(128);index"`
	Strategies       []string               `json:"strategies" gorm:"type:jsonb"`
	SeverityFilter   []string               `json:"severity_filter" gorm:"type:jsonb"`
	BasePrompts      []string               `json:"base_prompts" gorm:"type:jsonb"`
	TargetBehaviors  []string               `json:"target_behaviors" gorm:"type:jsonb"`
	Status           string                 `json:"status" gorm:"type:varchar(32);index"`
	Concurrency      int                    `json:"concurrency"`
	BatchSize        int                    `json:"batch_size"`
	MergeStrategy    string                 `json:"merge_strategy" gorm:"type:varchar(32)"`
	TotalAttacks     int                    `json:"total_attacks"`
	CompletedAttacks int                    `json:"completed_attacks"`
	SuccessCount     int                    `json:"success_count"`
	FailureCount     int                    `json:"failure_count"`
	OverallSuccessRate float64              `json:"overall_success_rate"`
	StartedAt        *time.Time             `json:"started_at,omitempty"`
	CompletedAt      *time.Time             `json:"completed_at,omitempty"`
	CreatedBy        string                 `json:"created_by" gorm:"type:varchar(64)"`
	CreatedAt        time.Time              `json:"created_at"`
	UpdatedAt        time.Time              `json:"updated_at"`
}

type AttackResult struct {
	ID                 string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	JobID              string                 `json:"job_id" gorm:"type:varchar(64);index"`
	PromptID           string                 `json:"prompt_id" gorm:"type:varchar(64);index"`
	Strategy           string                 `json:"strategy" gorm:"type:varchar(64);index"`
	Severity           string                 `json:"severity" gorm:"type:varchar(32);index"`
	OriginalPrompt     string                 `json:"original_prompt" gorm:"type:text"`
	AdversarialPrompt  string                 `json:"adversarial_prompt" gorm:"type:text"`
	ModelResponse      string                 `json:"model_response" gorm:"type:text"`
	TargetBehavior     string                 `json:"target_behavior" gorm:"type:varchar(256)"`
	Success            bool                   `json:"success"`
	ConfidenceScore    float64                `json:"confidence_score"`
	AttackLatencyMs    int64                  `json:"attack_latency_ms"`
	ErrorMsg           string                 `json:"error_msg,omitempty" gorm:"type:text"`
	Metadata           map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt          time.Time              `json:"created_at"`
}

type SecurityAssessment struct {
	ID                    string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	JobID                 string                 `json:"job_id" gorm:"type:varchar(64);index"`
	ModelName             string                 `json:"model_name" gorm:"type:varchar(128);index"`
	OverallVulnerability  float64                `json:"overall_vulnerability"`
	StrategyBreakdown     map[string]*StrategyStats `json:"strategy_breakdown" gorm:"type:jsonb"`
	SeverityBreakdown     map[string]*SeverityStats `json:"severity_breakdown" gorm:"type:jsonb"`
	TopSuccessfulAttacks  []*AttackResult        `json:"top_successful_attacks" gorm:"type:jsonb"`
	Recommendations       []string               `json:"recommendations" gorm:"type:jsonb"`
	RiskLevel             string                 `json:"risk_level" gorm:"type:varchar(32)"`
	GeneratedAt           time.Time              `json:"generated_at"`
}

type StrategyStats struct {
	Strategy     string  `json:"strategy"`
	TotalAttacks int     `json:"total_attacks"`
	SuccessCount int     `json:"success_count"`
	SuccessRate  float64 `json:"success_rate"`
	AvgLatencyMs int64   `json:"avg_latency_ms"`
}

type SeverityStats struct {
	Severity     string  `json:"severity"`
	TotalAttacks int     `json:"total_attacks"`
	SuccessCount int     `json:"success_count"`
	SuccessRate  float64 `json:"success_rate"`
}

type BatchAttackRequest struct {
	JobID          string        `json:"job_id" binding:"required"`
	BasePrompts    []string      `json:"base_prompts" binding:"required"`
	Strategies     []string      `json:"strategies"`
	Severities     []string      `json:"severities"`
	TargetBehaviors []string     `json:"target_behaviors"`
	Concurrency    int           `json:"concurrency"`
	MergeStrategy  string        `json:"merge_strategy"`
}

type MergeRequest struct {
	SourceJobIDs   []string `json:"source_job_ids" binding:"required"`
	TargetJobName  string   `json:"target_job_name" binding:"required"`
	MergeStrategy  string   `json:"merge_strategy" binding:"required"`
	Description    string   `json:"description"`
}

type AttackTemplate struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name        string                 `json:"name" gorm:"type:varchar(128);index"`
	Strategy    string                 `json:"strategy" gorm:"type:varchar(64);index"`
	Severity    string                 `json:"severity" gorm:"type:varchar(32)"`
	Template    string                 `json:"template" gorm:"type:text"`
	Description string                 `json:"description" gorm:"type:text"`
	Variables   map[string]string      `json:"variables" gorm:"type:jsonb"`
	Enabled     bool                   `json:"enabled"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}
