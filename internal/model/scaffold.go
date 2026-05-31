package model

import (
	"time"
)

type ProjectTemplate struct {
	ID          string                 `gorm:"primaryKey;column:id" json:"id"`
	Name        string                 `gorm:"column:name;uniqueIndex" json:"name"`
	Description string                 `gorm:"column:description" json:"description"`
	Language    string                 `gorm:"column:language;index" json:"language"`
	Framework   string                 `gorm:"column:framework" json:"framework"`
	Version     string                 `gorm:"column:version" json:"version"`
	Parameters  []TemplateParameter    `gorm:"column:parameters;type:jsonb;serializer:json" json:"parameters"`
	Structure   map[string]interface{} `gorm:"column:structure;type:jsonb" json:"structure"`
	Tags        []string               `gorm:"column:tags;type:jsonb;serializer:json" json:"tags"`
	Owner       string                 `gorm:"column:owner" json:"owner"`
	IsPublic    bool                   `gorm:"column:is_public" json:"is_public"`
	Status      string                 `gorm:"column:status;index" json:"status"`
	CreatedAt   time.Time              `gorm:"column:created_at" json:"created_at"`
	UpdatedAt   time.Time              `gorm:"column:updated_at" json:"updated_at"`
}

func (ProjectTemplate) TableName() string {
	return "project_templates"
}

type TemplateParameter struct {
	Name        string      `json:"name"`
	Description string      `json:"description"`
	Type        string      `json:"type"`
	Default     interface{} `json:"default"`
	Required    bool        `json:"required"`
	Options     []string    `json:"options,omitempty"`
	Validation  string      `json:"validation,omitempty"`
	Category    string      `json:"category"`
}

type GeneratedProject struct {
	ID           string                 `gorm:"primaryKey;column:id" json:"id"`
	TemplateID   string                 `gorm:"column:template_id;index" json:"template_id"`
	ProjectName  string                 `gorm:"column:project_name" json:"project_name"`
	Description  string                 `gorm:"column:description" json:"description"`
	Parameters   map[string]interface{} `gorm:"column:parameters;type:jsonb" json:"parameters"`
	OutputPath   string                 `gorm:"column:output_path" json:"output_path"`
	GeneratedBy  string                 `gorm:"column:generated_by" json:"generated_by"`
	Status       string                 `gorm:"column:status;index" json:"status"`
	ErrorMessage *string                `gorm:"column:error_message" json:"error_message"`
	GeneratedAt  time.Time              `gorm:"column:generated_at" json:"generated_at"`
	CreatedAt    time.Time              `gorm:"column:created_at" json:"created_at"`
	UpdatedAt    time.Time              `gorm:"column:updated_at" json:"updated_at"`
}

func (GeneratedProject) TableName() string {
	return "generated_projects"
}

type GenerateProjectRequest struct {
	TemplateID  string                 `json:"template_id" binding:"required"`
	ProjectName string                 `json:"project_name" binding:"required"`
	Description string                 `json:"description"`
	Parameters  map[string]interface{} `json:"parameters" binding:"required"`
	OutputPath  string                 `json:"output_path"`
}

type InteractiveQuestion struct {
	Question  string   `json:"question"`
	Parameter string   `json:"parameter"`
	Options   []string `json:"options,omitempty"`
	Default   string   `json:"default"`
	Category  string   `json:"category"`
}

type BatchGenerateRequest struct {
	Projects []GenerateProjectRequest `json:"projects" binding:"required"`
}

type BatchGenerateResult struct {
	BatchID    string               `json:"batch_id"`
	Total      int                  `json:"total"`
	Successful int                  `json:"successful"`
	Failed     int                  `json:"failed"`
	Results    []GenerateResultItem `json:"results"`
}

type GenerateResultItem struct {
	ProjectName string `json:"project_name"`
	Status      string `json:"status"`
	Message     string `json:"message,omitempty"`
	ProjectID   string `json:"project_id,omitempty"`
}

// ===== 批量操作增强模型

type BatchProgress struct {
	BatchID    string  `json:"batch_id"`
	Total      int     `json:"total"`
	Completed  int     `json:"completed"`
	Successful int     `json:"successful"`
	Failed     int     `json:"failed"`
	InProgress int     `json:"in_progress"`
	Progress   float64 `json:"progress"`
	ElapsedMs  int64   `json:"elapsed_ms"`
	Status     string  `json:"status"`
}

type BatchGenerateTimeoutRequest struct {
	Projects      []GenerateProjectRequest `json:"projects" binding:"required"`
	TimeoutSec    int                      `json:"timeout_sec"`
	MaxConcurrent int                      `json:"max_concurrent"`
}

type CoalescedGenerateRequest struct {
	Requests         []GenerateProjectRequest `json:"requests" binding:"required"`
	CoalesceWindowMs int                      `json:"coalesce_window_ms"`
}

type BatchGenerateTimeoutResult struct {
	BatchID       string               `json:"batch_id"`
	Total         int                  `json:"total"`
	Completed     int                  `json:"completed"`
	TimedOut      int                  `json:"timed_out"`
	TimedOutItems []string             `json:"timed_out_items,omitempty"`
	Results       []GenerateResultItem `json:"results"`
}

type BatchStatus struct {
	BatchID     string     `json:"batch_id"`
	Status      string     `json:"status"`
	CreatedAt   time.Time  `json:"created_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	DurationMs  int64      `json:"duration_ms,omitempty"`
}
