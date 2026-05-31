package quality

import (
	"context"
	"time"
)

type RuleType string

const (
	RuleTypeRange       RuleType = "range"
	RuleTypeNullCheck   RuleType = "null_check"
	RuleTypeUnique      RuleType = "unique"
	RuleTypeRegex       RuleType = "regex"
	RuleTypeCustom      RuleType = "custom"
	RuleTypeReferential RuleType = "referential"
	RuleTypeFormat      RuleType = "format"

	DefaultTimeoutSeconds = 30
	DefaultMaxRetries     = 3
)

type RuleStatus string

const (
	RuleStatusActive   RuleStatus = "active"
	RuleStatusInactive RuleStatus = "inactive"
	RuleStatusPaused   RuleStatus = "paused"
)

type SeverityLevel string

const (
	SeverityLow      SeverityLevel = "low"
	SeverityMedium   SeverityLevel = "medium"
	SeverityHigh     SeverityLevel = "high"
	SeverityCritical SeverityLevel = "critical"
)

type QualityRule struct {
	ID              string                 `json:"id"`
	Name            string                 `json:"name"`
	Description     string                 `json:"description"`
	Type            RuleType               `json:"type"`
	TableName       string                 `json:"table_name"`
	ColumnName      string                 `json:"column_name"`
	Parameters      map[string]interface{} `json:"parameters"`
	Severity        SeverityLevel          `json:"severity"`
	Status          RuleStatus             `json:"status"`
	CronExpression  string                 `json:"cron_expression"`
	TimeoutSeconds  int                    `json:"timeout_seconds"`
	MaxRetries      int                    `json:"max_retries"`
	NotificationCfg NotificationConfig     `json:"notification_config"`
	CreatedAt       time.Time              `json:"created_at"`
	UpdatedAt       time.Time              `json:"updated_at"`
	CreatedBy       string                 `json:"created_by"`
}

type NotificationConfig struct {
	Enabled   bool     `json:"enabled"`
	Channels  []string `json:"channels"`
	Recipients []string `json:"recipients"`
}

type RangeParams struct {
	MinValue float64 `json:"min_value"`
	MaxValue float64 `json:"max_value"`
}

type RegexParams struct {
	Pattern string `json:"pattern"`
}

type ReferentialParams struct {
	RefTable  string `json:"ref_table"`
	RefColumn string `json:"ref_column"`
}

type RuleExecutionResult struct {
	ID           string      `json:"id"`
	RuleID       string      `json:"rule_id"`
	RuleName     string      `json:"rule_name"`
	Status       string      `json:"status"`
	Passed       bool        `json:"passed"`
	TotalRows    int64       `json:"total_rows"`
	InvalidRows  int64       `json:"invalid_rows"`
	ErrorRate    float64     `json:"error_rate"`
	DurationMs   int64       `json:"duration_ms"`
	ErrorMessage string      `json:"error_message,omitempty"`
	Details      interface{} `json:"details,omitempty"`
	ExecutedAt   time.Time   `json:"executed_at"`
	RetryCount   int         `json:"retry_count"`
}

type AnomalyRecord struct {
	ID          string                 `json:"id"`
	RuleID      string                 `json:"rule_id"`
	RuleName    string                 `json:"rule_name"`
	TableName   string                 `json:"table_name"`
	ColumnName  string                 `json:"column_name"`
	RowID       string                 `json:"row_id"`
	ColumnValue interface{}            `json:"column_value"`
	Expected    interface{}            `json:"expected,omitempty"`
	Actual      interface{}            `json:"actual,omitempty"`
	Severity    SeverityLevel          `json:"severity"`
	Metadata    map[string]interface{} `json:"metadata"`
	DetectedAt  time.Time              `json:"detected_at"`
	Resolved    bool                   `json:"resolved"`
	ResolvedAt  *time.Time             `json:"resolved_at,omitempty"`
}

type RuleValidator interface {
	Validate(ctx context.Context, rule *QualityRule, data interface{}) (bool, []AnomalyRecord, error)
	Supports(ruleType RuleType) bool
}

type RangeValidator struct{}

func NewRangeValidator() *RangeValidator {
	return &RangeValidator{}
}

func (v *RangeValidator) Supports(ruleType RuleType) bool {
	return ruleType == RuleTypeRange
}

func (v *RangeValidator) Validate(ctx context.Context, rule *QualityRule, data interface{}) (bool, []AnomalyRecord, error) {
	select {
	case <-ctx.Done():
		return false, nil, ctx.Err()
	default:
	}

	params, ok := rule.Parameters["range"].(map[string]interface{})
	if !ok {
		return false, nil, nil
	}

	minVal, _ := params["min_value"].(float64)
	maxVal, _ := params["max_value"].(float64)

	anomalies := make([]AnomalyRecord, 0)

	switch values := data.(type) {
	case []float64:
		for i, val := range values {
			select {
			case <-ctx.Done():
				return false, nil, ctx.Err()
			default:
			}

			if val < minVal || val > maxVal {
				anomalies = append(anomalies, AnomalyRecord{
					RuleID:      rule.ID,
					RuleName:    rule.Name,
					TableName:   rule.TableName,
					ColumnName:  rule.ColumnName,
					ColumnValue: val,
					Expected:    map[string]float64{"min": minVal, "max": maxVal},
					Actual:      val,
					Severity:    rule.Severity,
					DetectedAt:  time.Now().UTC(),
				})
			}
			_ = i
		}
	}

	return len(anomalies) == 0, anomalies, nil
}

type NullCheckValidator struct{}

func NewNullCheckValidator() *NullCheckValidator {
	return &NullCheckValidator{}
}

func (v *NullCheckValidator) Supports(ruleType RuleType) bool {
	return ruleType == RuleTypeNullCheck
}

func (v *NullCheckValidator) Validate(ctx context.Context, rule *QualityRule, data interface{}) (bool, []AnomalyRecord, error) {
	select {
	case <-ctx.Done():
		return false, nil, ctx.Err()
	default:
	}

	anomalies := make([]AnomalyRecord, 0)

	switch values := data.(type) {
	case []interface{}:
		for i, val := range values {
			select {
			case <-ctx.Done():
				return false, nil, ctx.Err()
			default:
			}

			if val == nil {
				anomalies = append(anomalies, AnomalyRecord{
					RuleID:     rule.ID,
					RuleName:   rule.Name,
					TableName:  rule.TableName,
					ColumnName: rule.ColumnName,
					ColumnValue: val,
					Expected:   "not null",
					Actual:     nil,
					Severity:   rule.Severity,
					DetectedAt: time.Now().UTC(),
				})
			}
			_ = i
		}
	}

	return len(anomalies) == 0, anomalies, nil
}
