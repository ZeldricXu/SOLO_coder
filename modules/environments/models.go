package environments

import (
	"time"
)

type Environment struct {
	ID             string                 `gorm:"primaryKey" json:"id"`
	Name           string                 `json:"name"`
	Type           string                 `gorm:"index" json:"type"`
	Status         string                 `gorm:"index" json:"status"`
	OwnerID        string                 `gorm:"index" json:"owner_id"`
	ProjectID      string                 `gorm:"index" json:"project_id"`
	Config         map[string]interface{} `gorm:"serializer:json" json:"config"`
	Endpoints      map[string]string      `gorm:"serializer:json" json:"endpoints"`
	Resources      ResourceSpec           `gorm:"serializer:json" json:"resources"`
	TTLSeconds     int                    `json:"ttl_seconds"`
	ExpiresAt      *time.Time             `json:"expires_at"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
	StartedAt      *time.Time             `json:"started_at"`
	StoppedAt      *time.Time             `json:"stopped_at"`
	LastActiveAt   *time.Time             `json:"last_active_at"`
}

type ResourceSpec struct {
	CPU     string `json:"cpu"`
	Memory  string `json:"memory"`
	Storage string `json:"storage"`
	Replicas int    `json:"replicas"`
}

type EnvironmentRequest struct {
	ID          string                 `gorm:"primaryKey" json:"id"`
	Name        string                 `json:"name"`
	Type        string                 `json:"type"`
	OwnerID     string                 `gorm:"index" json:"owner_id"`
	ProjectID   string                 `gorm:"index" json:"project_id"`
	Status      string                 `gorm:"index" json:"status"`
	Config      map[string]interface{} `gorm:"serializer:json" json:"config"`
	Resources   ResourceSpec           `gorm:"serializer:json" json:"resources"`
	TTLSeconds  int                    `json:"ttl_seconds"`
	Reason      string                 `json:"reason"`
	ApproverID  *string                `json:"approver_id,omitempty"`
	ApprovedAt  *time.Time             `json:"approved_at,omitempty"`
	RejectedAt  *time.Time             `json:"rejected_at,omitempty"`
	RejectReason *string               `json:"reject_reason,omitempty"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
	EnvironmentID *string              `json:"environment_id,omitempty"`
}

type UsageRecord struct {
	ID            string    `gorm:"primaryKey" json:"id"`
	EnvironmentID string    `gorm:"index" json:"environment_id"`
	OwnerID       string    `gorm:"index" json:"owner_id"`
	ProjectID     string    `gorm:"index" json:"project_id"`
	CPUSeconds    float64   `json:"cpu_seconds"`
	MemoryMBHours float64   `json:"memory_mb_hours"`
	StartTime     time.Time `json:"start_time"`
	EndTime       time.Time `json:"end_time"`
}

type DailyStats struct {
	ID               string    `gorm:"primaryKey" json:"id"`
	Date             time.Time `gorm:"index:idx_date" json:"date"`
	TotalRequests    int       `json:"total_requests"`
	ApprovedRequests int       `json:"approved_requests"`
	ActiveEnvs       int       `json:"active_envs"`
	TotalCPUHours    float64   `json:"total_cpu_hours"`
	TotalMemoryGBHours float64 `json:"total_memory_gb_hours"`
}

type CreateEnvRequest struct {
	Name       string                 `json:"name" binding:"required"`
	Type       string                 `json:"type" binding:"required"`
	ProjectID  string                 `json:"project_id" binding:"required"`
	Config     map[string]interface{} `json:"config"`
	Resources  *ResourceSpec          `json:"resources"`
	TTLSeconds int                    `json:"ttl_seconds"`
	Reason     string                 `json:"reason"`
}
