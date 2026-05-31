package featureflags

import (
	"time"
)

type FeatureFlag struct {
	ID          string                 `gorm:"primaryKey" json:"id"`
	Key         string                 `gorm:"uniqueIndex" json:"key"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Enabled     bool                   `json:"enabled"`
	Rules       []RolloutRule          `gorm:"serializer:json" json:"rules"`
	Tags        []string               `gorm:"serializer:json" json:"tags"`
	Metadata    map[string]interface{} `gorm:"serializer:json" json:"metadata"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type RolloutRule struct {
	ID         string              `json:"id"`
	Type       string              `json:"type"`
	Conditions []RuleCondition     `json:"conditions"`
	Percentage float64             `json:"percentage"`
	Segments   []string            `json:"segments"`
	Users      []string            `json:"users"`
	Value      interface{}         `json:"value"`
	Priority   int                 `json:"priority"`
	Enabled    bool                `json:"enabled"`
	StartAt    *time.Time          `json:"start_at"`
	EndAt      *time.Time          `json:"end_at"`
}

type RuleCondition struct {
	Attribute string        `json:"attribute"`
	Operator  string        `json:"operator"`
	Values    []interface{} `json:"values"`
}

type UserSegment struct {
	ID          string                 `gorm:"primaryKey" json:"id"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Rules       []SegmentRule          `gorm:"serializer:json" json:"rules"`
	UserIDs     []string               `gorm:"serializer:json" json:"user_ids"`
	Metadata    map[string]interface{} `gorm:"serializer:json" json:"metadata"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type SegmentRule struct {
	Attribute string        `json:"attribute"`
	Operator  string        `json:"operator"`
	Values    []interface{} `json:"values"`
}

type EvaluationContext struct {
	UserID     string                 `json:"user_id"`
	Segments   []string               `json:"segments"`
	Attributes map[string]interface{} `json:"attributes"`
}

type EvaluationResult struct {
	Key        string      `json:"key"`
	Enabled    bool        `json:"enabled"`
	Value      interface{} `json:"value"`
	RuleID     string      `json:"rule_id,omitempty"`
	Reason     string      `json:"reason"`
}

type RolloutEvent struct {
	ID          string                 `gorm:"primaryKey" json:"id"`
	FlagKey     string                 `gorm:"index" json:"flag_key"`
	UserID      string                 `gorm:"index" json:"user_id"`
	Variation   string                 `json:"variation"`
	Value       interface{}            `gorm:"serializer:json" json:"value"`
	Timestamp   time.Time              `json:"timestamp"`
	Metadata    map[string]interface{} `gorm:"serializer:json" json:"metadata"`
}

type ExperimentStats struct {
	FlagKey       string  `json:"flag_key"`
	TotalUsers    int64   `json:"total_users"`
	ExposedUsers  int64   `json:"exposed_users"`
	ControlUsers  int64   `json:"control_users"`
	TreatmentUsers int64  `json:"treatment_users"`
	ConversionRate float64 `json:"conversion_rate"`
}
