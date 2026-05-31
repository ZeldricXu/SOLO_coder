package qualitygate

import (
	"time"
)

type AnalysisRule struct {
	ID          string                 `gorm:"primaryKey" json:"id"`
	Language    string                 `gorm:"index" json:"language"`
	Key         string                 `gorm:"uniqueIndex:idx_rule_key" json:"key"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Severity    string                 `json:"severity"`
	Category    string                 `json:"category"`
	Default     bool                   `json:"default"`
	Enabled     bool                   `json:"enabled"`
	Config      map[string]interface{} `gorm:"serializer:json" json:"config"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type QualityProfile struct {
	ID         string    `gorm:"primaryKey" json:"id"`
	Name       string    `json:"name"`
	Language   string    `gorm:"index" json:"language"`
	Rules      []string  `gorm:"serializer:json" json:"rules"`
	IsDefault  bool      `json:"is_default"`
	ProjectIDs []string  `gorm:"serializer:json" json:"project_ids"`
	CreatedAt  time.Time `json:"created_at"`
	UpdatedAt  time.Time `json:"updated_at"`
}

type AnalysisReport struct {
	ID             string                   `gorm:"primaryKey" json:"id"`
	ProjectID      string                   `gorm:"index" json:"project_id"`
	Commit         string                   `json:"commit"`
	Branch         string                   `json:"branch"`
	Status         string                   `json:"status"`
	TotalIssues    int                      `json:"total_issues"`
	CriticalIssues int                      `json:"critical_issues"`
	MajorIssues    int                      `json:"major_issues"`
	MinorIssues    int                      `json:"minor_issues"`
	QualityScore   float64                  `json:"quality_score"`
	Issues         []AnalysisIssue          `gorm:"serializer:json" json:"issues"`
	GateResult     *GateCheckResult         `gorm:"serializer:json" json:"gate_result"`
	StartedAt      time.Time                `json:"started_at"`
	CompletedAt    *time.Time               `json:"completed_at"`
	CreatedAt      time.Time                `json:"created_at"`
	UpdatedAt      time.Time                `json:"updated_at"`
}

type AnalysisIssue struct {
	RuleKey    string            `json:"rule_key"`
	Message    string            `json:"message"`
	Severity   string            `json:"severity"`
	Line       int               `json:"line"`
	File       string            `json:"file"`
	StartLine  int               `json:"start_line"`
	EndLine    int               `json:"end_line"`
	StartColumn int              `json:"start_column"`
	EndColumn  int               `json:"end_column"`
	Extras     map[string]string `json:"extras,omitempty"`
}

type GateCheckResult struct {
	Passed   bool              `json:"passed"`
	Checks   []GateCheckDetail `json:"checks"`
	Failures []string          `json:"failures,omitempty"`
}

type GateCheckDetail struct {
	Metric    string  `json:"metric"`
	Threshold float64 `json:"threshold"`
	Actual    float64 `json:"actual"`
	Passed    bool    `json:"passed"`
}

type QualityGate struct {
	ID         string                 `gorm:"primaryKey" json:"id"`
	Name       string                 `json:"name"`
	IsDefault  bool                   `json:"is_default"`
	Conditions []GateCondition        `gorm:"serializer:json" json:"conditions"`
	ProjectIDs []string               `gorm:"serializer:json" json:"project_ids"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type GateCondition struct {
	Metric    string  `json:"metric"`
	Threshold float64 `json:"threshold"`
	Operator  string  `json:"operator"`
}

type AnalyzeRequest struct {
	ProjectID string            `json:"project_id" binding:"required"`
	Language  string            `json:"language"`
	Commit    string            `json:"commit"`
	Branch    string            `json:"branch"`
	Code      map[string]string `json:"code,omitempty"`
	Rules     []string          `json:"rules,omitempty"`
}
