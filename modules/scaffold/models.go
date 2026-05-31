package scaffold

import (
	"time"
)

type Template struct {
	ID          string                 `gorm:"primaryKey" json:"id"`
	Name        string                 `gorm:"uniqueIndex" json:"name"`
	Description string                 `json:"description"`
	Language    string                 `gorm:"index" json:"language"`
	Framework   string                 `json:"framework"`
	Tags        []string               `gorm:"serializer:json" json:"tags"`
	Files       []TemplateFile         `gorm:"serializer:json" json:"files"`
	Parameters  []TemplateParameter    `gorm:"serializer:json" json:"parameters"`
	Questions   []InteractiveQuestion  `gorm:"serializer:json" json:"questions"`
	Metadata    map[string]interface{} `gorm:"serializer:json" json:"metadata"`
	Public      bool                   `json:"public"`
	OwnerID     string                 `json:"owner_id"`
	Version     string                 `json:"version"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type TemplateFile struct {
	Path    string `json:"path"`
	Content string `json:"content"`
	Type    string `json:"type"`
}

type TemplateParameter struct {
	Name         string      `json:"name"`
	Label        string      `json:"label"`
	Type         string      `json:"type"`
	Default      interface{} `json:"default"`
	Required     bool        `json:"required"`
	Description  string      `json:"description"`
	Options      []string    `json:"options,omitempty"`
	Validation   string      `json:"validation,omitempty"`
}

type InteractiveQuestion struct {
	ID           string      `json:"id"`
	Text         string      `json:"text"`
	Type         string      `json:"type"`
	Options      []Option    `json:"options,omitempty"`
	Default      interface{} `json:"default"`
	Required     bool        `json:"required"`
	Condition    *QuestionCondition `json:"condition,omitempty"`
	DependsOn    string      `json:"depends_on,omitempty"`
	Parameter    string      `json:"parameter"`
}

type Option struct {
	Value interface{} `json:"value"`
	Label string      `json:"label"`
}

type QuestionCondition struct {
	Parameter string      `json:"parameter"`
	Operator  string      `json:"operator"`
	Value     interface{} `json:"value"`
}

type GenerationRequest struct {
	ID              string                 `gorm:"primaryKey" json:"id"`
	TemplateID      string                 `gorm:"index" json:"template_id"`
	TemplateName    string                 `json:"template_name"`
	Parameters      map[string]interface{} `gorm:"serializer:json" json:"parameters"`
	ProjectName     string                 `json:"project_name"`
	OutputFormat    string                 `json:"output_format"`
	Status          string                 `json:"status"`
	GeneratedFiles  []GeneratedFile        `gorm:"serializer:json" json:"generated_files"`
	DownloadURL     string                 `json:"download_url,omitempty"`
	Error           *string                `json:"error,omitempty"`
	CreatedAt       time.Time              `json:"created_at"`
	CompletedAt     *time.Time             `json:"completed_at"`
}

type GeneratedFile struct {
	Path    string `json:"path"`
	Content string `json:"content"`
}

type TemplateQuestionRequest struct {
	TemplateID string                 `json:"template_id"`
	Answers    map[string]interface{} `json:"answers"`
}

type QuestionFlow struct {
	Questions []InteractiveQuestion `json:"questions"`
	Progress  float64               `json:"progress"`
	Total     int                   `json:"total"`
}
