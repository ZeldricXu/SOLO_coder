package model

import (
	"time"

	"session133/internal/common"
)

type ModelStage string

const (
	StageDevelopment ModelStage = "development"
	StageStaging     ModelStage = "staging"
	StageProduction  ModelStage = "production"
	StageArchived    ModelStage = "archived"
)

type BaseEntity struct {
	ID         string            `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Type       string            `gorm:"type:varchar(32)" json:"type"`
	Status     string            `gorm:"type:varchar(32)" json:"status"`
	Attributes map[string]string `gorm:"type:jsonb;serializer:json" json:"attributes"`
	CreatedAt  time.Time         `json:"created_at"`
	UpdatedAt  time.Time         `json:"updated_at"`
}

type Model struct {
	BaseEntity
	Name        string       `gorm:"type:varchar(128);uniqueIndex:idx_namespace_name;not null" json:"name"`
	Namespace   string       `gorm:"type:varchar(64);uniqueIndex:idx_namespace_name;not null" json:"namespace"`
	Description string       `gorm:"type:text" json:"description"`
	Framework   string       `gorm:"type:varchar(64)" json:"framework"`
	Tags        []string     `gorm:"type:jsonb;serializer:json" json:"tags"`
	LatestVersionID string   `gorm:"type:varchar(64)" json:"latest_version_id"`
	Owner       string       `gorm:"type:varchar(64)" json:"owner"`
}

type ModelVersion struct {
	ID            string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ModelID       string                 `gorm:"type:varchar(64);index;not null" json:"model_id"`
	Version       string                 `gorm:"type:varchar(32);not null" json:"version"`
	Stage         ModelStage             `gorm:"type:varchar(32);index" json:"stage"`
	Checksum      string                 `gorm:"type:varchar(128)" json:"checksum"`
	SizeBytes     int64                  `json:"size_bytes"`
	Metadata      map[string]interface{} `gorm:"type:jsonb;serializer:json" json:"metadata"`
	TrainingData  string                 `gorm:"type:varchar(256)" json:"training_data"`
	Metrics       map[string]float64     `gorm:"type:jsonb;serializer:json" json:"metrics"`
	ArtifactsURI  string                 `gorm:"type:varchar(512)" json:"artifacts_uri"`
	Description   string                 `gorm:"type:text" json:"description"`
	CreatedBy     string                 `gorm:"type:varchar(64)" json:"created_by"`
	PreviousVersionID string            `gorm:"type:varchar(64)" json:"previous_version_id"`
	NextVersionID     string            `gorm:"type:varchar(64)" json:"next_version_id"`
	CreatedAt     time.Time              `json:"created_at"`
	UpdatedAt     time.Time              `json:"updated_at"`
}

type StageTransition struct {
	ID               string     `gorm:"primaryKey;type:varchar(64)" json:"id"`
	VersionID        string     `gorm:"type:varchar(64);index;not null" json:"version_id"`
	FromStage        ModelStage `gorm:"type:varchar(32)" json:"from_stage"`
	ToStage          ModelStage `gorm:"type:varchar(32)" json:"to_stage"`
	Reason           string     `gorm:"type:text" json:"reason"`
	ApprovedBy       string     `gorm:"type:varchar(64)" json:"approved_by"`
	RollbackAllowed  bool       `json:"rollback_allowed"`
	CreatedAt        time.Time  `json:"created_at"`
}

type CreateModelRequest struct {
	Name        string                 `json:"name" binding:"required"`
	Namespace   string                 `json:"namespace" binding:"required"`
	Description string                 `json:"description"`
	Framework   string                 `json:"framework"`
	Tags        []string               `json:"tags"`
	Metadata    map[string]interface{} `json:"metadata"`
}

type CreateVersionRequest struct {
	Version      string                 `json:"version" binding:"required"`
	Checksum     string                 `json:"checksum"`
	SizeBytes    int64                  `json:"size_bytes"`
	Metadata     map[string]interface{} `json:"metadata"`
	TrainingData string                 `json:"training_data"`
	Metrics      map[string]float64     `json:"metrics"`
	ArtifactsURI string                 `json:"artifacts_uri" binding:"required"`
	Description  string                 `json:"description"`
}

type StageTransitionRequest struct {
	TargetStage   ModelStage `json:"target_stage" binding:"required"`
	Reason        string     `json:"reason"`
	ApprovedBy    string     `json:"approved_by"`
}
