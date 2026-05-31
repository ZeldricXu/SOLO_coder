package models

import (
	"time"
)

type PromptStatus string

const (
	PromptStatusDraft     PromptStatus = "draft"
	PromptStatusTesting   PromptStatus = "testing"
	PromptStatusActive    PromptStatus = "active"
	PromptStatusArchived  PromptStatus = "archived"
	PromptStatusDeprecated PromptStatus = "deprecated"
)

type ExperimentStatus string

const (
	ExperimentStatusDraft     ExperimentStatus = "draft"
	ExperimentStatusRunning   ExperimentStatus = "running"
	ExperimentStatusPaused    ExperimentStatus = "paused"
	ExperimentStatusCompleted ExperimentStatus = "completed"
	ExperimentStatusCancelled ExperimentStatus = "cancelled"
)

type ExperimentType string

const (
	ExperimentTypeAB     ExperimentType = "ab"
	ExperimentTypeMulti  ExperimentType = "multi_arm"
	ExperimentTypeHoldout ExperimentType = "holdout"
)

type MetricType string

const (
	MetricTypeAccuracy  MetricType = "accuracy"
	MetricTypeLatency   MetricType = "latency"
	MetricTypeCost      MetricType = "cost"
	MetricTypeSatisfaction MetricType = "satisfaction"
	MetricTypeErrorRate MetricType = "error_rate"
)

type PromptVersion struct {
	ID            string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	PromptID      string                 `json:"prompt_id" gorm:"type:varchar(64);index"`
	Version       int                    `json:"version" gorm:"index"`
	Name          string                 `json:"name" gorm:"type:varchar(128)"`
	Description   string                 `json:"description" gorm:"type:text"`
	SystemPrompt  string                 `json:"system_prompt" gorm:"type:text"`
	UserPrompt    string                 `json:"user_prompt" gorm:"type:text"`
	Variables     map[string]string      `json:"variables" gorm:"type:jsonb"`
	ModelConfig   map[string]interface{} `json:"model_config" gorm:"type:jsonb"`
	Status        string                 `json:"status" gorm:"type:varchar(32);index"`
	ParentVersion int                    `json:"parent_version,omitempty"`
	ChangeLog     string                 `json:"change_log" gorm:"type:text"`
	CreatedBy     string                 `json:"created_by" gorm:"type:varchar(64)"`
	CreatedAt     time.Time              `json:"created_at"`
	UpdatedAt     time.Time              `json:"updated_at"`
}

type Prompt struct {
	ID          string            `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name        string            `json:"name" gorm:"type:varchar(128);index"`
	Description string            `json:"description" gorm:"type:text"`
	TaskType    string            `json:"task_type" gorm:"type:varchar(64);index"`
	Tags        []string          `json:"tags" gorm:"type:jsonb"`
	Status      string            `json:"status" gorm:"type:varchar(32);index"`
	ActiveVersionID string        `json:"active_version_id,omitempty" gorm:"type:varchar(64)"`
	CreatedBy   string            `json:"created_by" gorm:"type:varchar(64)"`
	Owner       string            `json:"owner" gorm:"type:varchar(64);index"`
	CreatedAt   time.Time         `json:"created_at"`
	UpdatedAt   time.Time         `json:"updated_at"`
}

type ABExperiment struct {
	ID               string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name             string                 `json:"name" gorm:"type:varchar(128);index"`
	Description      string                 `json:"description" gorm:"type:text"`
	Type             string                 `json:"type" gorm:"type:varchar(32);index"`
	Status           string                 `json:"status" gorm:"type:varchar(32);index"`
	ControlPromptID  string                 `json:"control_prompt_id" gorm:"type:varchar(64)"`
	ControlVersionID string                 `json:"control_version_id" gorm:"type:varchar(64)"`
	TrafficPercentage float64               `json:"traffic_percentage"`
	DurationHours    int                    `json:"duration_hours"`
	Variants         []*ExperimentVariant   `json:"variants" gorm:"type:jsonb"`
	TargetMetrics    []string               `json:"target_metrics" gorm:"type:jsonb"`
	SegmentFilter    map[string]interface{} `json:"segment_filter" gorm:"type:jsonb"`
	StartedAt        *time.Time             `json:"started_at,omitempty"`
	CompletedAt      *time.Time             `json:"completed_at,omitempty"`
	CreatedBy        string                 `json:"created_by" gorm:"type:varchar(64)"`
	CreatedAt        time.Time              `json:"created_at"`
	UpdatedAt        time.Time              `json:"updated_at"`
}

type ExperimentVariant struct {
	ID           string  `json:"id"`
	PromptID     string  `json:"prompt_id"`
	VersionID    string  `json:"version_id"`
	Name         string  `json:"name"`
	TrafficShare float64 `json:"traffic_share"`
	Description  string  `json:"description,omitempty"`
}

type ExperimentMetric struct {
	ID             string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	ExperimentID   string    `json:"experiment_id" gorm:"type:varchar(64);index"`
	VariantID      string    `json:"variant_id" gorm:"type:varchar(64);index"`
	MetricType     string    `json:"metric_type" gorm:"type:varchar(32);index"`
	MetricName     string    `json:"metric_name" gorm:"type:varchar(64)"`
	Value          float64   `json:"value"`
	Confidence     float64   `json:"confidence"`
	SampleSize     int64     `json:"sample_size"`
	IsStatSignificant bool  `json:"is_stat_significant"`
	ImprovementPct float64   `json:"improvement_pct"`
	Timestamp      time.Time `json:"timestamp" gorm:"index"`
	CreatedAt      time.Time `json:"created_at"`
}

type EvaluationResult struct {
	ID               string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	ExperimentID     string                 `json:"experiment_id" gorm:"type:varchar(64);index"`
	RunID            string                 `json:"run_id" gorm:"type:varchar(64);index"`
	VariantResults   map[string]*VariantResult `json:"variant_results" gorm:"type:jsonb"`
	WinningVariantID string                 `json:"winning_variant_id,omitempty"`
	Conclusion       string                 `json:"conclusion" gorm:"type:text"`
	Recommendations  []string               `json:"recommendations" gorm:"type:jsonb"`
	OverallMetrics   map[string]float64     `json:"overall_metrics" gorm:"type:jsonb"`
	CreatedAt        time.Time              `json:"created_at"`
}

type VariantResult struct {
	VariantID    string            `json:"variant_id"`
	PromptID     string            `json:"prompt_id"`
	VersionID    string            `json:"version_id"`
	SampleCount  int64             `json:"sample_count"`
	Metrics      map[string]float64 `json:"metrics"`
	Confidence   map[string]float64 `json:"confidence"`
	Rank         int               `json:"rank"`
	IsWinner     bool              `json:"is_winner"`
}

type ParallelRunConfig struct {
	ConcurrencyLevel int           `json:"concurrency_level"`
	TimeoutPerTask   time.Duration `json:"timeout_per_task"`
	MaxRetries       int           `json:"max_retries"`
	BatchSize        int           `json:"batch_size"`
}

type TestCase struct {
	ID        string                 `json:"id"`
	Input     string                 `json:"input"`
	Expected  string                 `json:"expected,omitempty"`
	Variables map[string]string      `json:"variables,omitempty"`
	Metadata  map[string]interface{} `json:"metadata,omitempty"`
}

type BatchTestRequest struct {
	ExperimentID string      `json:"experiment_id" binding:"required"`
	TestCases    []*TestCase `json:"test_cases" binding:"required"`
	Parallel     bool        `json:"parallel"`
	Concurrency  int         `json:"concurrency"`
}
