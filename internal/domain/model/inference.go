package model

import (
	"time"
)

type AIModel struct {
	ID              string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name              string                 `json:"name" gorm:"type:varchar(128)"`
	Version           string                 `json:"version" gorm:"type:varchar(64)"`
	Framework         string                 `json:"framework" gorm:"type:varchar(64)"`
	ModelType         string                 `json:"model_type" gorm:"type:varchar(64)"`
	FileSize          int64                  `json:"file_size"`
	FileURL           string                 `json:"file_url" gorm:"type:varchar(512)"`
	Checksum          string                 `json:"checksum" gorm:"type:varchar(128)"`
	InputFormat       string                 `json:"input_format" gorm:"type:varchar(64)"`
	OutputFormat      string                 `json:"output_format" gorm:"type:varchar(64)"`
	InputShape        []int                  `json:"input_shape" gorm:"type:jsonb"`
	Labels            []string               `json:"labels" gorm:"type:text[]"`
	Threshold         float64                `json:"threshold" gorm:"default:0.5"`
	Accuracy          float64                `json:"accuracy"`
	HardwareRequirements map[string]interface{} `json:"hardware_requirements" gorm:"type:jsonb"`
	Metadata          map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	IsActive          bool                   `json:"is_active" gorm:"default:true"`
	DeployedAt        *time.Time             `json:"deployed_at"`
	CreatedAt         time.Time              `json:"created_at"`
	UpdatedAt         time.Time              `json:"updated_at"`
}

type ModelDeployment struct {
	ID              string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	ModelID         string                 `json:"model_id" gorm:"type:varchar(64);index"`
	DeviceID        string                 `json:"device_id" gorm:"type:varchar(64);index"`
	Status          string                 `json:"status" gorm:"type:varchar(32);index"`
	DeploymentType    string                 `json:"deployment_type" gorm:"type:varchar(32)"`
	TargetFramework string                 `json:"target_framework" gorm:"type:varchar(64)"`
	Accelerator   string                 `json:"accelerator" gorm:"type:varchar(32)"`
	InstanceCount   int                    `json:"instance_count" gorm:"default:1"`
	BatchSize       int                    `json:"batch_size" gorm:"default:1"`
	Parameters      map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	DeployedAt      *time.Time             `json:"deployed_at"`
	LastHeartbeat   *time.Time             `json:"last_heartbeat"`
	CreatedAt       time.Time              `json:"created_at"`
	UpdatedAt       time.Time              `json:"updated_at"`
}

type InferenceTask struct {
	ID              string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	DeploymentID    string                 `json:"deployment_id" gorm:"type:varchar(64);index"`
	ModelID         string                 `json:"model_id" gorm:"type:varchar(64);index"`
	DeviceID        string                 `json:"device_id" gorm:"type:varchar(64);index"`
	TaskType        string                 `json:"task_type" gorm:"type:varchar(64)"`
	InputData       string                 `json:"input_data" gorm:"type:text"`
	InputSource     string                 `json:"input_source" gorm:"type:varchar(512)"`
	Status          string                 `json:"status" gorm:"type:varchar(32);index"`
	Priority        int                    `json:"priority" gorm:"default:0"`
	Result            map[string]interface{} `json:"result" gorm:"type:jsonb"`
	Confidence      float64                `json:"confidence"`
	LatencyMs       int64                  `json:"latency_ms"`
	ErrorMessage    *string                `json:"error_message"`
	ScheduledAt     *time.Time             `json:"scheduled_at"`
	StartedAt       *time.Time             `json:"started_at"`
	CompletedAt     *time.Time             `json:"completed_at"`
	CallbackURL     string                 `json:"callback_url" gorm:"type:varchar(512)"`
	CreatedAt       time.Time              `json:"created_at"`
	UpdatedAt       time.Time              `json:"updated_at"`
}

const (
	ModelStatusDeployed  = "deployed"
	ModelStatusDeploying = "deploying"
	ModelStatusFailed    = "failed"
	ModelStatusUndeployed = "undeployed"
)

const (
	TaskStatusPending    = "pending"
	TaskStatusRunning  = "running"
	TaskStatusCompleted = "completed"
	TaskStatusFailed   = "failed"
	TaskStatusCancelled = "cancelled"
)

const (
	InferenceTypeImage = "image"
	InferenceTypeVideo = "video"
	InferenceTypeAudio = "audio"
	InferenceTypeText = "text"
)
