package models

import (
	"time"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey"`
	Type       string                 `json:"type"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `json:"attributes" gorm:"serializer:json"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type ConfigDefinition struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters" gorm:"serializer:json"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID        string     `json:"run_id" gorm:"primaryKey"`
	EntityID     string     `json:"entity_id"`
	Phase        string     `json:"phase"`
	Progress     float64    `json:"progress"`
	StartedAt    time.Time  `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at,omitempty"`
	ErrorDetail  *string    `json:"error_detail,omitempty"`
}

type Snapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]interface{} `json:"metrics" gorm:"serializer:json"`
	Dimensions map[string]string      `json:"dimensions" gorm:"serializer:json"`
}

type Service struct {
	ID          string            `json:"id" gorm:"primaryKey"`
	Name        string            `json:"name"`
	Description string            `json:"description"`
	Type        string            `json:"type"`
	Version     string            `json:"version"`
	Owner       string            `json:"owner"`
	Labels      map[string]string `json:"labels" gorm:"serializer:json"`
	Endpoints   []string          `json:"endpoints" gorm:"serializer:json"`
	Dependencies []string         `json:"dependencies" gorm:"serializer:json"`
	CreatedAt   time.Time         `json:"created_at"`
	UpdatedAt   time.Time         `json:"updated_at"`
}

type LogLevelConfig struct {
	ID        uint      `gorm:"primaryKey"`
	Service   string    `json:"service" gorm:"uniqueIndex"`
	Level     string    `json:"level"`
	UpdatedAt time.Time `json:"updated_at"`
}

type Environment struct {
	ID         string    `json:"id" gorm:"primaryKey"`
	Name       string    `json:"name"`
	Namespace  string    `json:"namespace"`
	Status     string    `json:"status"`
	Owner      string    `json:"owner"`
	TTLMinutes int       `json:"ttl_minutes"`
	CreatedAt  time.Time `json:"created_at"`
	ExpiresAt  time.Time `json:"expires_at"`
}

type FeatureFlag struct {
	ID          string                 `json:"id" gorm:"primaryKey"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Enabled     bool                   `json:"enabled"`
	Rules       map[string]interface{} `json:"rules" gorm:"serializer:json"`
	RolloutPercent int                 `json:"rollout_percent"`
	UserGroups  []string               `json:"user_groups" gorm:"serializer:json"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type Vulnerability struct {
	ID             string    `json:"id" gorm:"primaryKey"`
	CVEID          string    `json:"cve_id"`
	Severity       string    `json:"severity"`
	PackageName    string    `json:"package_name"`
	CurrentVersion string    `json:"current_version"`
	FixedVersion   string    `json:"fixed_version"`
	Description    string    `json:"description"`
	DiscoveredAt   time.Time `json:"discovered_at"`
}
