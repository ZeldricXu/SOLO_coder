package gpu

import (
	"time"

	"github.com/dataplatform/engine/internal/domain"
)

type GPUStatus string

const (
	GPUStatusAvailable GPUStatus = "available"
	GPUStatusAllocated GPUStatus = "allocated"
	GPUStatusPreempted GPUStatus = "preempted"
	GPUStatusDegraded  GPUStatus = "degraded"
)

type GPUResource struct {
	ID          string                 `json:"id"`
	NodeID      string                 `json:"node_id"`
	DeviceIndex int                    `json:"device_index"`
	TotalVRAM   uint64                 `json:"total_vram_mb"`
	UsedVRAM    uint64                 `json:"used_vram_mb"`
	Status      GPUStatus              `json:"status"`
	Labels      map[string]string      `json:"labels"`
}

type GPUResourceRequest struct {
	TaskID        string            `json:"task_id"`
	MinVRAM       uint64            `json:"min_vram_mb"`
	PreferredVRAM uint64            `json:"preferred_vram_mb"`
	Labels        map[string]string `json:"labels"`
}

type TaskPriority int

const (
	PriorityLow      TaskPriority = 0
	PriorityMedium   TaskPriority = 1
	PriorityHigh     TaskPriority = 2
	PriorityCritical TaskPriority = 3
)

type GPUTask struct {
	ID           string                 `json:"id"`
	Name         string                 `json:"name"`
	Priority     TaskPriority           `json:"priority"`
	VRAMRequired uint64                 `json:"vram_required_mb"`
	Status       domain.ResourceStatus  `json:"status"`
	ResourceID   string                 `json:"resource_id,omitempty"`
	Payload      interface{}            `json:"payload"`
	Result       interface{}            `json:"result,omitempty"`
	Error        string                 `json:"error,omitempty"`
	SubmittedAt  time.Time              `json:"submitted_at"`
	StartedAt    *time.Time             `json:"started_at,omitempty"`
	CompletedAt  *time.Time             `json:"completed_at,omitempty"`
	Preemptible  bool                   `json:"preemptible"`
}
