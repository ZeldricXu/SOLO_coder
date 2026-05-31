package model

import (
	"time"
)

type QualityRule struct {
	ID          string                 `gorm:"primaryKey;column:id" json:"id"`
	Name        string                 `gorm:"column:name;uniqueIndex" json:"name"`
	Language    string                 `gorm:"column:language;index" json:"language"`
	Severity    string                 `gorm:"column:severity;index" json:"severity"`
	Category    string                 `gorm:"column:category" json:"category"`
	Description string                 `gorm:"column:description" json:"description"`
	RuleConfig  map[string]interface{} `gorm:"column:rule_config;type:jsonb" json:"rule_config"`
	Enabled     bool                   `gorm:"column:enabled" json:"enabled"`
	Threshold   float64                `gorm:"column:threshold" json:"threshold"`
	CreatedAt   time.Time              `gorm:"column:created_at" json:"created_at"`
	UpdatedAt   time.Time              `gorm:"column:updated_at" json:"updated_at"`
}

func (QualityRule) TableName() string {
	return "quality_rules"
}

type QualityReport struct {
	ID            string                 `gorm:"primaryKey;column:id" json:"id"`
	ProjectID     string                 `gorm:"column:project_id;index" json:"project_id"`
	Branch        string                 `gorm:"column:branch" json:"branch"`
	CommitHash    string                 `gorm:"column:commit_hash" json:"commit_hash"`
	Language      string                 `gorm:"column:language" json:"language"`
	TotalIssues   int                    `gorm:"column:total_issues" json:"total_issues"`
	CriticalCount int                    `gorm:"column:critical_count" json:"critical_count"`
	HighCount     int                    `gorm:"column:high_count" json:"high_count"`
	MediumCount   int                    `gorm:"column:medium_count" json:"medium_count"`
	LowCount      int                    `gorm:"column:low_count" json:"low_count"`
	Passed        bool                   `gorm:"column:passed" json:"passed"`
	Issues        []QualityIssue         `gorm:"column:issues;type:jsonb;serializer:json" json:"issues"`
	GeneratedBy   string                 `gorm:"column:generated_by" json:"generated_by"`
	GeneratedAt   time.Time              `gorm:"column:generated_at" json:"generated_at"`
	CreatedAt     time.Time              `gorm:"column:created_at" json:"created_at"`
}

func (QualityReport) TableName() string {
	return "quality_reports"
}

type QualityIssue struct {
	RuleID      string `json:"rule_id"`
	RuleName    string `json:"rule_name"`
	Severity    string `json:"severity"`
	Category    string `json:"category"`
	Message     string `json:"message"`
	FilePath    string `json:"file_path"`
	LineNumber  int    `json:"line_number"`
	ColumnStart int    `json:"column_start"`
	ColumnEnd   int    `json:"column_end"`
	CodeSnippet string `json:"code_snippet"`
	Suggestion  string `json:"suggestion"`
}

type QualityCheckRequest struct {
	ProjectID  string            `json:"project_id" binding:"required"`
	Branch     string            `json:"branch"`
	CommitHash string            `json:"commit_hash"`
	Language   string            `json:"language" binding:"required"`
	Rules      []string          `json:"rules"`
	CodePath   string            `json:"code_path"`
}

type QualityCheckResult struct {
	ReportID      string         `json:"report_id"`
	Passed        bool           `json:"passed"`
	TotalIssues   int            `json:"total_issues"`
	CriticalCount int            `json:"critical_count"`
	HighCount     int            `json:"high_count"`
	MediumCount   int            `json:"medium_count"`
	LowCount      int            `json:"low_count"`
	Issues        []QualityIssue `json:"issues"`
	GeneratedAt   time.Time      `json:"generated_at"`
}

type QualityGateConfig struct {
	ID           string  `gorm:"primaryKey;column:id" json:"id"`
	Name         string  `gorm:"column:name;uniqueIndex" json:"name"`
	CriticalLimit int    `gorm:"column:critical_limit" json:"critical_limit"`
	HighLimit     int    `gorm:"column:high_limit" json:"high_limit"`
	MediumLimit   int    `gorm:"column:medium_limit" json:"medium_limit"`
	CoverageThreshold float64 `gorm:"column:coverage_threshold" json:"coverage_threshold"`
	Enabled       bool    `gorm:"column:enabled" json:"enabled"`
	CreatedAt     time.Time `gorm:"column:created_at" json:"created_at"`
	UpdatedAt     time.Time `gorm:"column:updated_at" json:"updated_at"`
}

func (QualityGateConfig) TableName() string {
	return "quality_gate_configs"
}
