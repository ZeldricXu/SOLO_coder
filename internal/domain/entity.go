package domain

import (
	"time"
)

type EntityType string

const (
	EntityTypeEvent    EntityType = "event"
	EntityTypeTask     EntityType = "task"
	EntityTypeWorkflow EntityType = "workflow"
)

type EntityStatus string

const (
	EntityStatusPending   EntityStatus = "pending"
	EntityStatusRunning   EntityStatus = "running"
	EntityStatusCompleted EntityStatus = "completed"
	EntityStatusFailed    EntityStatus = "failed"
	EntityStatusCancelled EntityStatus = "cancelled"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Type       EntityType             `json:"type" gorm:"type:varchar(32);index"`
	Status     EntityStatus           `json:"status" gorm:"type:varchar(32);index"`
	Attributes map[string]interface{} `json:"attributes" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

func (Entity) TableName() string {
	return "entities"
}
