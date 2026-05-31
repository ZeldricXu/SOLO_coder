package models

import (
	"time"

	"github.com/google/uuid"
)

type EntityStatus string

const (
	StatusActive   EntityStatus = "active"
	StatusInactive EntityStatus = "inactive"
	StatusPending  EntityStatus = "pending"
	StatusFailed   EntityStatus = "failed"
)

type Entity struct {
	ID        uuid.UUID    `json:"id"`
	Name      string       `json:"name"`
	Type      string       `json:"type"`
	Status    EntityStatus `json:"status"`
	Metadata  map[string]string `json:"metadata,omitempty"`
	CreatedAt time.Time    `json:"created_at"`
	UpdatedAt time.Time    `json:"updated_at"`
}

type Config struct {
	ID            uuid.UUID         `json:"id"`
	EntityID      uuid.UUID         `json:"entity_id"`
	Name          string            `json:"name"`
	Description   string            `json:"description,omitempty"`
	Configuration map[string]any    `json:"configuration"`
	Version       string            `json:"version"`
	IsActive      bool              `json:"is_active"`
	CreatedAt     time.Time         `json:"created_at"`
	UpdatedAt     time.Time         `json:"updated_at"`
}

type RunInstance struct {
	ID         uuid.UUID    `json:"id"`
	ConfigID   uuid.UUID    `json:"config_id"`
	EntityID   uuid.UUID    `json:"entity_id"`
	Status     EntityStatus `json:"status"`
	StartTime  time.Time    `json:"start_time"`
	EndTime    *time.Time   `json:"end_time,omitempty"`
	Logs       []string     `json:"logs,omitempty"`
	Result     map[string]any `json:"result,omitempty"`
	Error      string       `json:"error,omitempty"`
	CreatedAt  time.Time    `json:"created_at"`
	UpdatedAt  time.Time    `json:"updated_at"`
}

type Snapshot struct {
	ID           uuid.UUID    `json:"id"`
	EntityID     uuid.UUID    `json:"entity_id"`
	InstanceID   uuid.UUID    `json:"instance_id"`
	State        map[string]any `json:"state"`
	Checksum     string       `json:"checksum"`
	Description  string       `json:"description,omitempty"`
	CreatedAt    time.Time    `json:"created_at"`
}
