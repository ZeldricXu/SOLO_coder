package entity

import (
	"time"
)

type Model struct {
	BaseEntity
	Name         string                 `gorm:"type:varchar(256);not null;uniqueIndex" json:"name"`
	Description  string                 `gorm:"type:text" json:"description"`
	Provider     string                 `gorm:"type:varchar(128);not null" json:"provider"`
	ModelType    string                 `gorm:"type:varchar(64)" json:"model_type"`
	MaxTokens    int                    `json:"max_tokens"`
	Capabilities []string               `gorm:"type:jsonb" json:"capabilities"`
	Metadata     map[string]interface{} `gorm:"type:jsonb" json:"metadata,omitempty"`
}

type ModelVersion struct {
	ID             string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ModelID        string    `gorm:"type:varchar(64);not null;index" json:"model_id"`
	Version        string    `gorm:"type:varchar(64);not null" json:"version"`
	Stage          string    `gorm:"type:varchar(64);not null;index" json:"stage"`
	Status         string    `gorm:"type:varchar(64);not null" json:"status"`
	Checksum       string    `gorm:"type:varchar(256)" json:"checksum"`
	Size           int64     `json:"size"`
	TrainingParams map[string]interface{} `gorm:"type:jsonb" json:"training_params,omitempty"`
	Artifacts      map[string]string      `gorm:"type:jsonb" json:"artifacts,omitempty"`
	CreatedBy      string    `gorm:"type:varchar(128)" json:"created_by"`
	CreatedAt      time.Time `gorm:"not null" json:"created_at"`
	UpdatedAt      time.Time `gorm:"not null" json:"updated_at"`
	PromotedAt     *time.Time `json:"promoted_at,omitempty"`
	RetiredAt      *time.Time `json:"retired_at,omitempty"`
}

type ModelStage string

const (
	StageStaging    ModelStage = "staging"
	StageProduction ModelStage = "production"
	StageArchived   ModelStage = "archived"
)

type ModelStatus string

const (
	StatusPending   ModelStatus = "pending"
	StatusReady     ModelStatus = "ready"
	StatusDeploying ModelStatus = "deploying"
	StatusRunning   ModelStatus = "running"
	StatusRetired   ModelStatus = "retired"
)
