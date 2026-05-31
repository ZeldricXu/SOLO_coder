package edge_inference

import (
	"time"

	"edgescheduler/internal/common/models"
)

type ModelStatus string
type TaskStatus string

const (
	ModelStatusPending   ModelStatus = "pending"
	ModelStatusDeploying ModelStatus = "deploying"
	ModelStatusDeployed  ModelStatus = "deployed"
	ModelStatusFailed    ModelStatus = "failed"

	TaskStatusPending   TaskStatus = "pending"
	TaskStatusRunning   TaskStatus = "running"
	TaskStatusCompleted TaskStatus = "completed"
	TaskStatusFailed    TaskStatus = "failed"
)

type AIModel struct {
	models.BaseModel
	ModelID      string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"model_id"`
	Name         string                 `gorm:"type:varchar(100);not null" json:"name"`
	Version      string                 `gorm:"type:varchar(50);not null" json:"version"`
	Type         string                 `gorm:"type:varchar(50);not null" json:"type"`
	Format       string                 `gorm:"type:varchar(30);not null" json:"format"`
	SizeBytes    int64                  `json:"size_bytes"`
	Checksum     string                 `gorm:"type:varchar(64)" json:"checksum"`
	DownloadURL  string                 `gorm:"type:varchar(500)" json:"download_url"`
	TargetDevice string                 `gorm:"type:varchar(50)" json:"target_device"`
	Status       ModelStatus            `gorm:"type:varchar(20);index" json:"status"`
	Metadata     map[string]interface{} `gorm:"type:jsonb" json:"metadata"`
}

type ModelDeployment struct {
	models.BaseModel
	DeploymentID string    `gorm:"type:varchar(50);not null;uniqueIndex" json:"deployment_id"`
	ModelID      string    `gorm:"type:varchar(50);not null;index" json:"model_id"`
	DeviceID     string    `gorm:"type:varchar(50);not null;index" json:"device_id"`
	Status       ModelStatus `gorm:"type:varchar(20);index" json:"status"`
	DeployedAt   *time.Time `json:"deployed_at,omitempty"`
	ErrorDetail  string    `gorm:"type:text" json:"error_detail,omitempty"`
}

type InferenceTask struct {
	models.BaseModel
	TaskID      string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"task_id"`
	ModelID     string                 `gorm:"type:varchar(50);not null;index" json:"model_id"`
	DeviceID    string                 `gorm:"type:varchar(50);not null;index" json:"device_id"`
	InputData   map[string]interface{} `gorm:"type:jsonb" json:"input_data"`
	OutputData  map[string]interface{} `gorm:"type:jsonb" json:"output_data,omitempty"`
	Status      TaskStatus             `gorm:"type:varchar(20);index" json:"status"`
	Priority    int                    `gorm:"default:0" json:"priority"`
	LatencyMs   int64                  `json:"latency_ms,omitempty"`
	ErrorDetail string                 `gorm:"type:text" json:"error_detail,omitempty"`
	StartedAt   *time.Time             `json:"started_at,omitempty"`
	CompletedAt *time.Time             `json:"completed_at,omitempty"`
	CallbackURL string                 `gorm:"type:varchar(500)" json:"callback_url,omitempty"`
}

type InferenceRequest struct {
	ModelID     string                 `json:"model_id" binding:"required"`
	DeviceID    string                 `json:"device_id" binding:"required"`
	InputData   map[string]interface{} `json:"input_data" binding:"required"`
	Priority    int                    `json:"priority"`
	CallbackURL string                 `json:"callback_url"`
}

type ModelDeployRequest struct {
	ModelID  string `json:"model_id" binding:"required"`
	DeviceID string `json:"device_id" binding:"required"`
}
