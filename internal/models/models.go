package models

import (
	"database/sql/driver"
	"encoding/json"
	"errors"
	"time"
)

type JSONObject map[string]interface{}

func (j JSONObject) Value() (driver.Value, error) {
	return json.Marshal(j)
}

func (j *JSONObject) Scan(value interface{}) error {
	bytes, ok := value.([]byte)
	if !ok {
		return errors.New("failed to unmarshal JSONB value")
	}
	return json.Unmarshal(bytes, j)
}

type Entity struct {
	ID         string     `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Type       string     `gorm:"type:varchar(32);index" json:"type"`
	Status     string     `gorm:"type:varchar(32);index" json:"status"`
	Attributes JSONObject `gorm:"type:jsonb" json:"attributes"`
	CreatedAt  time.Time  `json:"created_at"`
	UpdatedAt  time.Time  `json:"updated_at"`
}

type ConfigDefinition struct {
	ConfigID   string     `gorm:"primaryKey;type:varchar(64)" json:"config_id"`
	Namespace  string     `gorm:"type:varchar(64);index" json:"namespace"`
	Version    int        `gorm:"index" json:"version"`
	Parameters JSONObject `gorm:"type:jsonb" json:"parameters"`
	Enabled    bool       `gorm:"default:true;index" json:"enabled"`
	AppliedAt  *time.Time `json:"applied_at"`
	CreatedAt  time.Time  `json:"created_at"`
	UpdatedAt  time.Time  `json:"updated_at"`
}

type RunInstance struct {
	RunID        string     `gorm:"primaryKey;type:varchar(64)" json:"run_id"`
	EntityID     string     `gorm:"type:varchar(64);index" json:"entity_id"`
	Phase        string     `gorm:"type:varchar(32);index" json:"phase"`
	Progress     float64    `gorm:"default:0" json:"progress"`
	StartedAt    time.Time  `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at"`
	ErrorDetail  *string    `json:"error_detail"`
	CreatedAt    time.Time  `json:"created_at"`
	UpdatedAt    time.Time  `json:"updated_at"`
}

type StatsSnapshot struct {
	SnapshotID string     `gorm:"primaryKey;type:varchar(64)" json:"snapshot_id"`
	Timestamp  time.Time  `gorm:"index" json:"timestamp"`
	Metrics    JSONObject `gorm:"type:jsonb" json:"metrics"`
	Dimensions JSONObject `gorm:"type:jsonb" json:"dimensions"`
	CreatedAt  time.Time  `json:"created_at"`
}

type EventLog struct {
	ID            string     `gorm:"primaryKey;type:varchar(64)" json:"id"`
	AggregateID   string     `gorm:"type:varchar(64);index" json:"aggregate_id"`
	AggregateType string     `gorm:"type:varchar(64);index" json:"aggregate_type"`
	EventType     string     `gorm:"type:varchar(64);index" json:"event_type"`
	Version       int        `json:"version"`
	Payload       JSONObject `gorm:"type:jsonb" json:"payload"`
	Metadata      JSONObject `gorm:"type:jsonb" json:"metadata"`
	Timestamp     time.Time  `gorm:"index" json:"timestamp"`
}

type CommandLog struct {
	ID            string     `gorm:"primaryKey;type:varchar(64)" json:"id"`
	CommandType   string     `gorm:"type:varchar(64);index" json:"command_type"`
	AggregateID   string     `gorm:"type:varchar(64);index" json:"aggregate_id"`
	Payload       JSONObject `gorm:"type:jsonb" json:"payload"`
	Principal     string     `gorm:"type:varchar(64)" json:"principal"`
	IPAddress     string     `gorm:"type:varchar(64)" json:"ip_address"`
	Status        string     `gorm:"type:varchar(32);index" json:"status"`
	ExecutedAt    time.Time  `json:"executed_at"`
	CorrelationID string     `gorm:"type:varchar(64);index" json:"correlation_id"`
}

type AuditLog struct {
	ID            string     `gorm:"primaryKey;type:varchar(64)" json:"id"`
	CommandID     string     `gorm:"type:varchar(64);index" json:"command_id"`
	EventID       string     `gorm:"type:varchar(64);index" json:"event_id"`
	Action        string     `gorm:"type:varchar(64);index" json:"action"`
	ResourceType  string     `gorm:"type:varchar(64)" json:"resource_type"`
	ResourceID    string     `gorm:"type:varchar(64)" json:"resource_id"`
	Principal     string     `gorm:"type:varchar(64)" json:"principal"`
	ChangeSummary JSONObject `gorm:"type:jsonb" json:"change_summary"`
	Compliance    bool       `gorm:"default:false" json:"compliance"`
	Timestamp     time.Time  `gorm:"index" json:"timestamp"`
}
