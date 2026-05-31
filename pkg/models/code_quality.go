package models

import "time"

type Language string

const (
	LanguagePython     Language = "python"
	LanguageJavaScript Language = "javascript"
	LanguageTypeScript Language = "typescript"
	LanguageGo         Language = "go"
	LanguageJava       Language = "java"
)

type Severity string

const (
	SeverityInfo     Severity = "info"
	SeverityWarning  Severity = "warning"
	SeverityError    Severity = "error"
	SeverityCritical Severity = "critical"
)

type AnalysisRule struct {
	RuleID      string                 `json:"rule_id"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Language    Language               `json:"language"`
	Severity    Severity               `json:"severity"`
	Pattern     string                 `json:"pattern,omitempty"`
	Enabled     bool                   `json:"enabled"`
	Parameters  map[string]interface{} `json:"parameters,omitempty"`
}

type AnalysisIssue struct {
	RuleID         string   `json:"rule_id"`
	Line           int      `json:"line"`
	Column         int      `json:"column"`
	Message        string   `json:"message"`
	Severity       Severity `json:"severity"`
	Snippet        string   `json:"snippet,omitempty"`
	FixSuggestion  string   `json:"fix_suggestion,omitempty"`
}

type AnalysisReport struct {
	ReportID          string            `json:"report_id"`
	Language          Language          `json:"language"`
	TotalFiles        int               `json:"total_files"`
	TotalIssues       int               `json:"total_issues"`
	IssuesBySeverity  map[Severity]int `json:"issues_by_severity"`
	Issues            []AnalysisIssue   `json:"issues"`
	QualityScore      float64           `json:"quality_score"`
	ThresholdPass     bool              `json:"threshold_pass"`
	CreatedAt         time.Time         `json:"created_at"`
}

type QualityThreshold struct {
	Critical     int     `json:"critical"`
	Error        int     `json:"error"`
	Warning      int     `json:"warning"`
	QualityScore float64 `json:"quality_score"`
}
