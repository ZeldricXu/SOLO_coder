package entity

import (
	"time"
)

type Document struct {
	ID          string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name        string                 `gorm:"type:varchar(512);not null" json:"name"`
	Type        string                 `gorm:"type:varchar(64);not null" json:"type"`
	Size        int64                  `json:"size"`
	Hash        string                 `gorm:"type:varchar(256)" json:"hash"`
	Source      string                 `gorm:"type:varchar(512)" json:"source"`
	Status      string                 `gorm:"type:varchar(64);not null;index" json:"status"`
	Metadata    map[string]interface{} `gorm:"type:jsonb" json:"metadata,omitempty"`
	CreatedBy   string                 `gorm:"type:varchar(128)" json:"created_by"`
	CreatedAt   time.Time              `gorm:"not null" json:"created_at"`
	UpdatedAt   time.Time              `gorm:"not null" json:"updated_at"`
}

type DocumentChunk struct {
	ID         string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	DocumentID string    `gorm:"type:varchar(64);not null;index" json:"document_id"`
	Index      int       `gorm:"not null" json:"index"`
	Content    string    `gorm:"type:text;not null" json:"content"`
	StartPos   int       `json:"start_pos"`
	EndPos     int       `json:"end_pos"`
	TokenCount int       `json:"token_count"`
	CreatedAt  time.Time `gorm:"not null" json:"created_at"`
}

type ChunkVector struct {
	ID         string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ChunkID    string    `gorm:"type:varchar(64);not null;uniqueIndex" json:"chunk_id"`
	Vector     []float64 `gorm:"type:vector(1536)" json:"vector"`
	ModelID    string    `gorm:"type:varchar(64);not null" json:"model_id"`
	Dimensions int       `gorm:"not null" json:"dimensions"`
	CreatedAt  time.Time `gorm:"not null" json:"created_at"`
}

type ParsePipeline struct {
	ID          string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name        string                 `gorm:"type:varchar(256);not null" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	Steps       []PipelineStep         `gorm:"type:jsonb" json:"steps"`
	Config      map[string]interface{} `gorm:"type:jsonb" json:"config,omitempty"`
	Enabled     bool                   `gorm:"default:true" json:"enabled"`
	CreatedAt   time.Time              `gorm:"not null" json:"created_at"`
	UpdatedAt   time.Time              `gorm:"not null" json:"updated_at"`
}

type PipelineStep struct {
	Name   string                 `json:"name"`
	Type   string                 `json:"type"`
	Order  int                    `json:"order"`
	Config map[string]interface{} `json:"config,omitempty"`
}

type PipelineExecution struct {
	ID           string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	PipelineID   string    `gorm:"type:varchar(64);not null;index" json:"pipeline_id"`
	DocumentID   string    `gorm:"type:varchar(64);not null" json:"document_id"`
	Status       string    `gorm:"type:varchar(64);not null;index" json:"status"`
	CurrentStep  int       `json:"current_step"`
	TotalSteps   int       `json:"total_steps"`
	ChunkCount   int       `json:"chunk_count"`
	ErrorDetail  *string   `gorm:"type:text" json:"error_detail,omitempty"`
	StartedAt    time.Time `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at,omitempty"`
	CreatedAt    time.Time `gorm:"not null" json:"created_at"`
}

type DocumentType string

const (
	DocTypePDF      DocumentType = "pdf"
	DocTypeWord     DocumentType = "word"
	DocTypeExcel    DocumentType = "excel"
	DocTypePPT      DocumentType = "ppt"
	DocTypeText     DocumentType = "text"
	DocTypeMarkdown DocumentType = "markdown"
	DocTypeHTML     DocumentType = "html"
	DocTypeJSON     DocumentType = "json"
	DocTypeCSV      DocumentType = "csv"
)

type PipelineStepType string

const (
	StepTypeExtract  PipelineStepType = "extract"
	StepTypeClean    PipelineStepType = "clean"
	StepTypeSplit    PipelineStepType = "split"
	StepTypeEmbed    PipelineStepType = "embed"
	StepTypeStore    PipelineStepType = "store"
	StepTypeIndex    PipelineStepType = "index"
)

type PipelineStatus string

const (
	PipelineStatusPending   PipelineStatus = "pending"
	PipelineStatusRunning   PipelineStatus = "running"
	PipelineStatusPaused    PipelineStatus = "paused"
	PipelineStatusCompleted PipelineStatus = "completed"
	PipelineStatusFailed    PipelineStatus = "failed"
)
