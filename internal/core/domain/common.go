package domain

import (
	"fmt"
	"time"
)

type AppError struct {
	Message string
	Code    int
	Cause   error
}

func (e *AppError) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("%s: %v", e.Message, e.Cause)
	}
	return e.Message
}

type StrategyType string

const (
	StrategyRoundRobin StrategyType = "round_robin"
	StrategyFastest    StrategyType = "fastest"
	StrategyFailover   StrategyType = "failover"
	StrategyWeighted   StrategyType = "weighted"
)

type ResourceStatus string

const (
	StatusPending      ResourceStatus = "pending"
	StatusProvisioning ResourceStatus = "provisioning"
	StatusRunning      ResourceStatus = "running"
	StatusCompleted    ResourceStatus = "completed"
	StatusFailed       ResourceStatus = "failed"
	StatusCancelled    ResourceStatus = "cancelled"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey"`
	Type       string                 `json:"type"`
	Status     ResourceStatus         `json:"status"`
	Attributes map[string]interface{} `json:"attributes" gorm:"serializer:json"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type Config struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters" gorm:"serializer:json"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID       string     `json:"run_id" gorm:"primaryKey"`
	EntityID    string     `json:"entity_id"`
	Phase       string     `json:"phase"`
	Progress    float64    `json:"progress"`
	StartedAt   time.Time  `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	ErrorDetail string     `json:"error_detail,omitempty"`
}

type Snapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics" gorm:"serializer:json"`
	Dimensions map[string]string      `json:"dimensions" gorm:"serializer:json"`
	EntityID   string                 `json:"entity_id"`
	Version    int64                  `json:"version"`
	State      map[string]interface{} `json:"state" gorm:"serializer:json"`
}

type CreateResourceRequest struct {
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

type Resource struct {
	ID         string                 `json:"id"`
	Type       string                 `json:"type"`
	Status     ResourceStatus         `json:"status"`
	Config     map[string]interface{} `json:"config"`
	Labels     map[string]string      `json:"labels"`
	CreatedAt  time.Time              `json:"created_at"`
}

type ResourceStatusResponse struct {
	ID       string         `json:"id"`
	Status   ResourceStatus `json:"status"`
	Progress float64        `json:"progress"`
}

type Operation struct {
	Action string            `json:"action"`
	ID     string            `json:"id"`
	Params map[string]string `json:"params"`
}

type BatchResult struct {
	BatchID string             `json:"batch_id"`
	Results []*OperationResult `json:"results"`
}

type OperationResult struct {
	ID      string `json:"id"`
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
}

type CacheEntry struct {
	Key        string
	Value      interface{}
	Expiration time.Time
	HitCount   int64
}

type CacheStats struct {
	Hits      int64   `json:"hits"`
	Misses    int64   `json:"misses"`
	HitRate   float64 `json:"hit_rate"`
	Size      int     `json:"size"`
	MaxSize   int     `json:"max_size"`
	Evictions int64   `json:"evictions"`
}
