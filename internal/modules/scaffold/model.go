package scaffold

import (
	"depguard/internal/common/model"
	"time"
)

type Template struct {
	model.BaseModel
	Name        string                 `gorm:"type:varchar(128);uniqueIndex;not null" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	Language    string                 `gorm:"type:varchar(32);index" json:"language"`
	Framework   string                 `gorm:"type:varchar(64)" json:"framework"`
	Version     string                 `gorm:"type:varchar(32)" json:"version"`
	Tags        []string               `gorm:"type:varchar(64)[]" json:"tags"`
	Parameters  map[string]interface{} `gorm:"type:jsonb" json:"parameters"`
	FileTree    map[string]interface{} `gorm:"type:jsonb" json:"file_tree"`
	IsPublic    bool                   `gorm:"default:true" json:"is_public"`
	Author      string                 `gorm:"type:varchar(64)" json:"author"`
}

type Project struct {
	model.BaseModel
	Name        string                 `gorm:"type:varchar(128);not null" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	TemplateID  string                 `gorm:"type:varchar(64);index" json:"template_id"`
	Namespace   string                 `gorm:"type:varchar(64);index" json:"namespace"`
	Status      string                 `gorm:"type:varchar(32);index;default:pending" json:"status"`
	Config      map[string]interface{} `gorm:"type:jsonb" json:"config"`
	OutputPath  string                 `gorm:"type:varchar(256)" json:"output_path"`
	OwnerID     string                 `gorm:"type:varchar(64);index" json:"owner_id"`
	GeneratedAt *time.Time             `json:"generated_at"`
}

type GenerationTask struct {
	model.BaseModel
	TaskID      string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"task_id"`
	ProjectID   string                 `gorm:"type:varchar(64);index" json:"project_id"`
	TemplateID  string                 `gorm:"type:varchar(64);index" json:"template_id"`
	Status      string                 `gorm:"type:varchar(32);index;default:pending" json:"status"`
	Progress    float64                `gorm:"default:0" json:"progress"`
	Parameters  map[string]interface{} `gorm:"type:jsonb" json:"parameters"`
	Logs        []string               `gorm:"type:text[]" json:"logs"`
	StartedAt   *time.Time             `json:"started_at"`
	CompletedAt *time.Time             `json:"completed_at"`
	ErrorMsg    string                 `gorm:"type:text" json:"error_msg"`
}

type InteractiveSession struct {
	model.BaseModel
	SessionID   string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"session_id"`
	TemplateID  string                 `gorm:"type:varchar(64);index" json:"template_id"`
	UserID      string                 `gorm:"type:varchar(64);index" json:"user_id"`
	CurrentStep int                    `gorm:"default:0" json:"current_step"`
	TotalSteps  int                    `gorm:"default:0" json:"total_steps"`
	Answers     map[string]interface{} `gorm:"type:jsonb" json:"answers"`
	Status      string                 `gorm:"type:varchar(32);index;default:active" json:"status"`
	ExpiresAt   time.Time              `json:"expires_at"`
}

type TaskCheckpoint struct {
	model.BaseModel
	TaskID        string                 `gorm:"type:varchar(64);index;not null" json:"task_id"`
	StepName      string                 `gorm:"type:varchar(128);not null" json:"step_name"`
	StepIndex     int                    `gorm:"not null" json:"step_index"`
	Progress      float64                `gorm:"not null" json:"progress"`
	StateData     map[string]interface{} `gorm:"type:jsonb" json:"state_data"`
	IsCompleted   bool                   `gorm:"default:false" json:"is_completed"`
	Checksum      string                 `gorm:"type:varchar(64)" json:"checksum"`
	RetryCount    int                    `gorm:"default:0" json:"retry_count"`
	LastAttemptAt *time.Time             `json:"last_attempt_at"`
	ErrorMessage  string                 `gorm:"type:text" json:"error_message"`
}

type DataBackup struct {
	model.BaseModel
	BackupID     string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"backup_id"`
	ResourceType string                 `gorm:"type:varchar(32);index;not null" json:"resource_type"`
	ResourceID   string                 `gorm:"type:varchar(64);index" json:"resource_id"`
	DataSnapshot map[string]interface{} `gorm:"type:jsonb" json:"data_snapshot"`
	BackupType   string                 `gorm:"type:varchar(32);index" json:"backup_type"`
	Checksum     string                 `gorm:"type:varchar(64)" json:"checksum"`
	SizeBytes    int64                  `json:"size_bytes"`
	ExpiresAt    *time.Time             `json:"expires_at"`
	CreatedBy    string                 `gorm:"type:varchar(64)" json:"created_by"`
}

type RecoveryRecord struct {
	model.BaseModel
	RecoveryID    string    `gorm:"type:varchar(64);uniqueIndex;not null" json:"recovery_id"`
	BackupID      string    `gorm:"type:varchar(64);index" json:"backup_id"`
	ResourceID    string    `gorm:"type:varchar(64);index" json:"resource_id"`
	Status        string    `gorm:"type:varchar(32);index" json:"status"`
	RecoveredAt   time.Time `json:"recovered_at"`
	RecoveredBy   string    `gorm:"type:varchar(64)" json:"recovered_by"`
	RecoveryLog   []string  `gorm:"type:text[]" json:"recovery_log"`
	DurationMs    int64     `json:"duration_ms"`
}
