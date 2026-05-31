package domain

import (
	"time"
)

type Entity struct {
	ID         string                 `json:"id"`
	Type       string                 `json:"type"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `json:"attributes"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type ConfigDefinition struct {
	ConfigID   string                 `json:"config_id"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID        string     `json:"run_id"`
	EntityID     string     `json:"entity_id"`
	Phase        string     `json:"phase"`
	Progress     float64    `json:"progress"`
	StartedAt    time.Time  `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at"`
	ErrorDetail  *string    `json:"error_detail"`
}

type MetricsSnapshot struct {
	SnapshotID string                 `json:"snapshot_id"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics"`
	Dimensions map[string]string      `json:"dimensions"`
}

type LogEntry struct {
	Level     string                 `json:"level"`
	Message   string                 `json:"message"`
	Fields    map[string]interface{} `json:"fields,omitempty"`
	Timestamp time.Time              `json:"timestamp"`
	TraceID   string                 `json:"trace_id,omitempty"`
}

type AuditRecord struct {
	ID          string                 `json:"id"`
	Operation   string                 `json:"operation"`
	UserID      string                 `json:"user_id"`
	Resource    string                 `json:"resource"`
	Data        map[string]interface{} `json:"data"`
	Timestamp   time.Time              `json:"timestamp"`
	Hash        string                 `json:"hash"`
	PreviousHash string                `json:"previous_hash"`
}

type DataRecord struct {
	ID         string                 `json:"id"`
	SchemaVersion int                 `json:"schema_version"`
	Payload    map[string]interface{} `json:"payload"`
	Masked     bool                   `json:"masked,omitempty"`
	CreatedAt  time.Time              `json:"created_at"`
}

type AlertRule struct {
	ID          string  `json:"id"`
	Name        string  `json:"name"`
	Metric      string  `json:"metric"`
	Threshold   float64 `json:"threshold"`
	Operator    string  `json:"operator"`
	Enabled     bool    `json:"enabled"`
	Severity    string  `json:"severity"`
	NotificationChannel string `json:"notification_channel"`
}

type Alert struct {
	ID        string    `json:"id"`
	RuleID    string    `json:"rule_id"`
	Message   string    `json:"message"`
	Severity  string    `json:"severity"`
	TriggeredAt time.Time `json:"triggered_at"`
	Resolved  bool      `json:"resolved"`
}

type BackupInfo struct {
	ID         string    `json:"id"`
	Source     string    `json:"source"`
	Path       string    `json:"path"`
	Size       int64     `json:"size"`
	CreatedAt  time.Time `json:"created_at"`
	Encrypted  bool      `json:"encrypted"`
	Checksum   string    `json:"checksum"`
}

type FLModel struct {
	ID           string    `json:"id"`
	Name         string    `json:"name"`
	Version      int       `json:"version"`
	Parameters   []float64 `json:"parameters"`
	Round        int       `json:"round"`
	LastUpdated  time.Time `json:"last_updated"`
}

type FLTask struct {
	ID        string    `json:"id"`
	ModelID   string    `json:"model_id"`
	ClientID  string    `json:"client_id"`
	Status    string    `json:"status"`
	Gradient  []float64 `json:"gradient,omitempty"`
	StartTime time.Time `json:"start_time"`
	EndTime   *time.Time `json:"end_time,omitempty"`
}

type UserRole string

const (
	RoleAdmin    UserRole = "admin"
	RoleAnalyst  UserRole = "analyst"
	RoleViewer   UserRole = "viewer"
	RoleOperator UserRole = "operator"
)

type User struct {
	ID    string   `json:"id"`
	Roles []UserRole `json:"roles"`
}
