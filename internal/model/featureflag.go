package model

import (
	"time"
)

type FeatureFlag struct {
	ID          string                 `gorm:"primaryKey;column:id" json:"id"`
	Name        string                 `gorm:"column:name;uniqueIndex" json:"name"`
	Key         string                 `gorm:"column:key;uniqueIndex" json:"key"`
	Description string                 `gorm:"column:description" json:"description"`
	Enabled     bool                   `gorm:"column:enabled" json:"enabled"`
	RolloutPercent int                `gorm:"column:rollout_percent" json:"rollout_percent"`
	TargetUsers []string               `gorm:"column:target_users;type:jsonb;serializer:json" json:"target_users"`
	TargetGroups []string              `gorm:"column:target_groups;type:jsonb;serializer:json" json:"target_groups"`
	Segments    []string               `gorm:"column:segments;type:jsonb;serializer:json" json:"segments"`
	Conditions  map[string]interface{} `gorm:"column:conditions;type:jsonb" json:"conditions"`
	Variations  []FlagVariation        `gorm:"column:variations;type:jsonb;serializer:json" json:"variations"`
	DefaultValue string                `gorm:"column:default_value" json:"default_value"`
	CreatedBy   string                 `gorm:"column:created_by" json:"created_by"`
	StartAt     *time.Time             `gorm:"column:start_at" json:"start_at"`
	EndAt       *time.Time             `gorm:"column:end_at" json:"end_at"`
	CreatedAt   time.Time              `gorm:"column:created_at" json:"created_at"`
	UpdatedAt   time.Time              `gorm:"column:updated_at" json:"updated_at"`
}

func (FeatureFlag) TableName() string {
	return "feature_flags"
}

type FlagVariation struct {
	Value       string  `json:"value"`
	Weight      int     `json:"weight"`
	Description string  `json:"description"`
}

type UserSegment struct {
	ID          string                 `gorm:"primaryKey;column:id" json:"id"`
	Name        string                 `gorm:"column:name;uniqueIndex" json:"name"`
	Description string                 `gorm:"column:description" json:"description"`
	Conditions  map[string]interface{} `gorm:"column:conditions;type:jsonb" json:"conditions"`
	Users       []string               `gorm:"column:users;type:jsonb;serializer:json" json:"users"`
	CreatedBy   string                 `gorm:"column:created_by" json:"created_by"`
	CreatedAt   time.Time              `gorm:"column:created_at" json:"created_at"`
	UpdatedAt   time.Time              `gorm:"column:updated_at" json:"updated_at"`
}

func (UserSegment) TableName() string {
	return "user_segments"
}

type CreateFeatureFlagRequest struct {
	Name           string                 `json:"name" binding:"required"`
	Key            string                 `json:"key" binding:"required"`
	Description    string                 `json:"description"`
	Enabled        bool                   `json:"enabled"`
	RolloutPercent int                    `json:"rollout_percent"`
	TargetUsers    []string               `json:"target_users"`
	TargetGroups   []string               `json:"target_groups"`
	Segments       []string               `json:"segments"`
	Conditions     map[string]interface{} `json:"conditions"`
	Variations     []FlagVariation        `json:"variations"`
	DefaultValue   string                 `json:"default_value"`
	StartAt        *time.Time             `json:"start_at"`
	EndAt          *time.Time             `json:"end_at"`
}

type UpdateRolloutRequest struct {
	RolloutPercent int      `json:"rollout_percent" binding:"required"`
	Segments       []string `json:"segments"`
}

type FlagEvaluationRequest struct {
	FlagKey   string            `json:"flag_key" binding:"required"`
	UserID    string            `json:"user_id" binding:"required"`
	Context   map[string]string `json:"context"`
}

type FlagEvaluationResult struct {
	FlagKey    string `json:"flag_key"`
	Value      string `json:"value"`
	Enabled    bool   `json:"enabled"`
	Reason     string `json:"reason"`
}
