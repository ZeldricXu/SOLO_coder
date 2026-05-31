package domain

import (
	"time"
)

type RunPhase string

const (
	RunPhasePending    RunPhase = "pending"
	RunPhaseValidating RunPhase = "validating"
	RunPhaseExecuting  RunPhase = "executing"
	RunPhaseFinalizing RunPhase = "finalizing"
	RunPhaseCompleted  RunPhase = "completed"
	RunPhaseFailed     RunPhase = "failed"
)

type RunInstance struct {
	RunID       string     `json:"run_id" gorm:"primaryKey;type:varchar(64)"`
	EntityID    string     `json:"entity_id" gorm:"type:varchar(64);index"`
	Phase       RunPhase   `json:"phase" gorm:"type:varchar(32);index"`
	Progress    float64    `json:"progress" gorm:"type:decimal(5,4)"`
	StartedAt   time.Time  `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	ErrorDetail *string    `json:"error_detail,omitempty" gorm:"type:text"`
	CreatedAt   time.Time  `json:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at"`
}

func (RunInstance) TableName() {

}

func (RunInstance) TableName() string {
	return "run_instances"
}
