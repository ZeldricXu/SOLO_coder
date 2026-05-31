package prompt

import (
	"time"
)

type PromptStatus string

const (
	PromptStatusDraft     PromptStatus = "draft"
	PromptStatusTesting   PromptStatus = "testing"
	PromptStatusActive    PromptStatus = "active"
	PromptStatusArchived  PromptStatus = "archived"
)

type ExperimentStatus string

const (
	ExperimentStatusDraft     ExperimentStatus = "draft"
	ExperimentStatusRunning   ExperimentStatus = "running"
	ExperimentStatusPaused    ExperimentStatus = "paused"
	ExperimentStatusCompleted ExperimentStatus = "completed"
	ExperimentStatusStopped   ExperimentStatus = "stopped"
)

type Prompt struct {
	ID            string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name          string                 `gorm:"type:varchar(128);not null" json:"name"`
	Namespace     string                 `gorm:"type:varchar(64);index;not null" json:"namespace"`
	Description   string                 `gorm:"type:text" json:"description"`
	Content       string                 `gorm:"type:text;not null" json:"content"`
	Version       string                 `gorm:"type:varchar(32);not null" json:"version"`
	Status        PromptStatus           `gorm:"type:varchar(32);index" json:"status"`
	ModelID       string                 `gorm:"type:varchar(64)" json:"model_id"`
	Tags          []string               `gorm:"type:jsonb;serializer:json" json:"tags"`
	Variables     []string               `gorm:"type:jsonb;serializer:json" json:"variables"`
	Metadata      map[string]interface{} `gorm:"type:jsonb;serializer:json" json:"metadata"`
	CreatedBy     string                 `gorm:"type:varchar(64)" json:"created_by"`
	ParentID      string                 `gorm:"type:varchar(64)" json:"parent_id,omitempty"`
	CreatedAt     time.Time              `json:"created_at"`
	UpdatedAt     time.Time              `json:"updated_at"`
}

type ABExperiment struct {
	ID              string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name            string                 `gorm:"type:varchar(128);not null" json:"name"`
	Namespace       string                 `gorm:"type:varchar(64);index;not null" json:"namespace"`
	Description     string                 `gorm:"type:text" json:"description"`
	Status          ExperimentStatus       `gorm:"type:varchar(32);index" json:"status"`
	ControlPromptID string                `gorm:"type:varchar(64);not null" json:"control_prompt_id"`
	VariantPromptIDs []string             `gorm:"type:jsonb;serializer:json" json:"variant_prompt_ids"`
	TrafficSplit    map[string]int         `gorm:"type:jsonb;serializer:json" json:"traffic_split"`
	TargetMetric    string                 `gorm:"type:varchar(64)" json:"target_metric"`
	SignificanceLevel float64              `json:"significance_level"`
	MinSampleSize   int                    `json:"min_sample_size"`
	StartTime       *time.Time             `json:"start_time,omitempty"`
	EndTime         *time.Time             `json:"end_time,omitempty"`
	CreatedBy       string                 `gorm:"type:varchar(64)" json:"created_by"`
	CreatedAt       time.Time              `json:"created_at"`
	UpdatedAt       time.Time              `json:"updated_at"`
}

type ExperimentResult struct {
	ID              string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ExperimentID    string                 `gorm:"type:varchar(64);index;not null" json:"experiment_id"`
	PromptID        string                 `gorm:"type:varchar(64);index;not null" json:"prompt_id"`
	TotalRequests   int64                  `json:"total_requests"`
	SuccessCount    int64                  `json:"success_count"`
	AvgLatency      float64                `json:"avg_latency"`
	Metrics         map[string]float64     `gorm:"type:jsonb;serializer:json" json:"metrics"`
	ConfidenceScore float64                `json:"confidence_score"`
	IsWinner        bool                   `json:"is_winner"`
	StatSignificant bool                   `json:"stat_significant"`
	CreatedAt       time.Time              `json:"created_at"`
}

type CreatePromptRequest struct {
	Name        string                 `json:"name" binding:"required"`
	Namespace   string                 `json:"namespace" binding:"required"`
	Description string                 `json:"description"`
	Content     string                 `json:"content" binding:"required"`
	Version     string                 `json:"version" binding:"required"`
	ModelID     string                 `json:"model_id"`
	Tags        []string               `json:"tags"`
	Variables   []string               `json:"variables"`
	Metadata    map[string]interface{} `json:"metadata"`
}

type CreateExperimentRequest struct {
	Name            string            `json:"name" binding:"required"`
	Namespace       string            `json:"namespace" binding:"required"`
	Description     string            `json:"description"`
	ControlPromptID string            `json:"control_prompt_id" binding:"required"`
	VariantPromptIDs []string         `json:"variant_prompt_ids" binding:"required,min=1"`
	TrafficSplit    map[string]int    `json:"traffic_split"`
	TargetMetric    string            `json:"target_metric"`
	MinSampleSize   int               `json:"min_sample_size"`
}

type UpdateExperimentStatusRequest struct {
	Status ExperimentStatus `json:"status" binding:"required"`
}
