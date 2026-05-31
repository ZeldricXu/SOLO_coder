package entity

import (
	"time"
)

type Prompt struct {
	BaseEntity
	Name        string                 `gorm:"type:varchar(256);not null" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	Content     string                 `gorm:"type:text;not null" json:"content"`
	ModelID     string                 `gorm:"type:varchar(64)" json:"model_id"`
	Parameters  map[string]interface{} `gorm:"type:jsonb" json:"parameters,omitempty"`
	Tags        []string               `gorm:"type:jsonb" json:"tags,omitempty"`
	CreatedBy   string                 `gorm:"type:varchar(128)" json:"created_by"`
}

type PromptVersion struct {
	ID         string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	PromptID   string                 `gorm:"type:varchar(64);not null;index" json:"prompt_id"`
	Version    string                 `gorm:"type:varchar(64);not null" json:"version"`
	Content    string                 `gorm:"type:text;not null" json:"content"`
	Parameters map[string]interface{} `gorm:"type:jsonb" json:"parameters,omitempty"`
	Status     string                 `gorm:"type:varchar(64);not null" json:"status"`
	CreatedBy  string                 `gorm:"type:varchar(128)" json:"created_by"`
	CommitMsg  string                 `gorm:"type:text" json:"commit_msg"`
	CreatedAt  time.Time              `gorm:"not null" json:"created_at"`
}

type ABExperiment struct {
	ID           string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name         string                 `gorm:"type:varchar(256);not null" json:"name"`
	Description  string                 `gorm:"type:text" json:"description"`
	Status       string                 `gorm:"type:varchar(64);not null;index" json:"status"`
	PromptID     string                 `gorm:"type:varchar(64);not null" json:"prompt_id"`
	ControlGroup ExperimentGroup        `gorm:"embedded;embeddedPrefix:control_" json:"control_group"`
	TestGroups   []ExperimentGroup      `gorm:"type:jsonb" json:"test_groups"`
	TrafficSplit map[string]int         `gorm:"type:jsonb" json:"traffic_split"`
	Metrics      []string               `gorm:"type:jsonb" json:"metrics"`
	StartTime    time.Time              `json:"start_time"`
	EndTime      *time.Time             `json:"end_time,omitempty"`
	CreatedBy    string                 `gorm:"type:varchar(128)" json:"created_by"`
	CreatedAt    time.Time              `gorm:"not null" json:"created_at"`
	UpdatedAt    time.Time              `gorm:"not null" json:"updated_at"`
}

type ExperimentGroup struct {
	VersionID   string  `json:"version_id"`
	Name        string  `json:"name"`
	TrafficWeight int   `json:"traffic_weight"`
}

type ExperimentResult struct {
	ID             string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ExperimentID   string                 `gorm:"type:varchar(64);not null;index" json:"experiment_id"`
	GroupID        string                 `gorm:"type:varchar(64);not null" json:"group_id"`
	Metrics        map[string]float64     `gorm:"type:jsonb" json:"metrics"`
	SampleSize     int                    `json:"sample_size"`
	StatSignificant bool                  `json:"stat_significant"`
	Confidence     float64                `json:"confidence"`
	Timestamp      time.Time              `gorm:"not null" json:"timestamp"`
	CreatedAt      time.Time              `gorm:"not null" json:"created_at"`
}

type PromptStatus string

const (
	PromptStatusDraft     PromptStatus = "draft"
	PromptStatusTesting   PromptStatus = "testing"
	PromptStatusProduction PromptStatus = "production"
	PromptStatusArchived  PromptStatus = "archived"
)

type ExperimentStatus string

const (
	ExpStatusCreated    ExperimentStatus = "created"
	ExpStatusRunning    ExperimentStatus = "running"
	ExpStatusPaused     ExperimentStatus = "paused"
	ExpStatusCompleted  ExperimentStatus = "completed"
	ExpStatusTerminated ExperimentStatus = "terminated"
)
