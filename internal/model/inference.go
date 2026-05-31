package model

import "time"

type ModelStatus string

const (
	ModelStatusPending   ModelStatus = "pending"
	ModelStatusDeploying ModelStatus = "deploying"
	ModelStatusReady     ModelStatus = "ready"
	ModelStatusRunning   ModelStatus = "running"
	ModelStatusError     ModelStatus = "error"
	ModelStatusDisabled  ModelStatus = "disabled"
)

type InferenceStatus string

const (
	InferenceStatusQueued    InferenceStatus = "queued"
	InferenceStatusRunning   InferenceStatus = "running"
	InferenceStatusCompleted InferenceStatus = "completed"
	InferenceStatusFailed    InferenceStatus = "failed"
	InferenceStatusCancelled InferenceStatus = "cancelled"
)

type AIModel struct {
	ModelID        string            `json:"model_id" gorm:"primaryKey;type:varchar(64)"`
	Name           string            `json:"name" gorm:"type:varchar(128);index"`
	Version        string            `json:"version" gorm:"type:varchar(32);index"`
	Framework      string            `json:"framework" gorm:"type:varchar(32)"`
	Architecture   string            `json:"architecture" gorm:"type:varchar(64)"`
	SizeBytes      int64             `json:"size_bytes"`
	Checksum       string            `json:"checksum" gorm:"type:varchar(64)"`
	DownloadURL    string            `json:"download_url" gorm:"type:varchar(512)"`
	Status         ModelStatus       `json:"status" gorm:"type:varchar(32);index"`
	DeviceIDs      []string          `json:"device_ids" gorm:"type:jsonb"`
	Labels         map[string]string `json:"labels" gorm:"type:jsonb"`
	Metadata       map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	DeployedAt     *time.Time        `json:"deployed_at"`
	CreatedAt      time.Time         `json:"created_at" gorm:"index"`
	UpdatedAt      time.Time         `json:"updated_at" gorm:"index"`
}

func (m *AIModel) TableName() string {
	return "ai_models"
}

type InferenceTask struct {
	TaskID         string            `json:"task_id" gorm:"primaryKey;type:varchar(64)"`
	ModelID        string            `json:"model_id" gorm:"type:varchar(64);index"`
	DeviceID       string            `json:"device_id" gorm:"type:varchar(64);index"`
	TraceID        string            `json:"trace_id" gorm:"type:varchar(64);index"`
	Status         InferenceStatus   `json:"status" gorm:"type:varchar(32);index"`
	InputData      string            `json:"input_data" gorm:"type:text"`
	InputFormat    string            `json:"input_format" gorm:"type:varchar(32)"`
	OutputData     *string           `json:"output_data" gorm:"type:text"`
	OutputFormat   string            `json:"output_format" gorm:"type:varchar(32)"`
	Priority       int               `json:"priority" gorm:"default:0;index"`
	TimeoutSeconds int               `json:"timeout_seconds" gorm:"default:300"`
	StartTime      *time.Time        `json:"start_time"`
	EndTime        *time.Time        `json:"end_time"`
	DurationMs     *int64            `json:"duration_ms"`
	Error          *string           `json:"error" gorm:"type:text"`
	CallbackURL    string            `json:"callback_url" gorm:"type:varchar(512)"`
	ResultSynced   bool              `json:"result_synced" gorm:"default:false;index"`
	CreatedAt      time.Time         `json:"created_at" gorm:"index"`
	UpdatedAt      time.Time         `json:"updated_at" gorm:"index"`
}

func (t *InferenceTask) TableName() string {
	return "inference_tasks"
}

type ModelDeployRequest struct {
	ModelID   string   `json:"model_id" binding:"required"`
	DeviceIDs []string `json:"device_ids" binding:"required,min=1"`
}

type InferenceRequest struct {
	ModelID        string                 `json:"model_id" binding:"required"`
	DeviceID       string                 `json:"device_id" binding:"required"`
	InputData      string                 `json:"input_data" binding:"required"`
	InputFormat    string                 `json:"input_format"`
	OutputFormat   string                 `json:"output_format"`
	Priority       int                    `json:"priority"`
	TimeoutSeconds int                    `json:"timeout_seconds"`
	CallbackURL    string                 `json:"callback_url"`
	Parameters     map[string]interface{} `json:"parameters"`
}
