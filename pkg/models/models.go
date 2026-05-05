package models

import "time"

type MigrationStatus string

const (
	MigrationStatusPending    MigrationStatus = "pending"
	MigrationStatusExecuted   MigrationStatus = "executed"
	MigrationStatusRolledBack MigrationStatus = "rolled_back"
	MigrationStatusFailed     MigrationStatus = "failed"
)

type Migration struct {
	MigrationID   string    `json:"migration_id"`
	Version       string    `json:"version"`
	Name          string    `json:"name"`
	UpScript      string    `json:"-"`
	DownScript    string    `json:"-"`
	UpFilePath    string    `json:"up_file_path"`
	DownFilePath  string    `json:"down_file_path"`
	Dependencies  []string  `json:"dependencies"`
	CreatedAt     time.Time `json:"created_at"`
	Author        string    `json:"author"`
}

type MigrationState struct {
	StatusID         string        `json:"status_id"`
	MigrationID      string        `json:"migration_id"`
	Status           MigrationStatus `json:"status"`
	ExecutedAt       time.Time     `json:"executed_at"`
	ExecutionTimeMS  int64         `json:"execution_time_ms"`
	AffectedObjects  []string      `json:"affected_objects"`
	RollbackAvailable bool         `json:"rollback_available"`
}

type MigrationLog struct {
	LogID         string    `json:"log_id"`
	MigrationID   string    `json:"migration_id"`
	Action        string    `json:"action"`
	SQLStatements []string  `json:"sql_statements"`
	Result        string    `json:"result"`
	ErrorMessage  string    `json:"error_message,omitempty"`
	LoggedAt      time.Time `json:"logged_at"`
}

type DatabaseConfig struct {
	Driver   string `yaml:"driver"`
	Host     string `yaml:"host"`
	Port     int    `yaml:"port"`
	User     string `yaml:"user"`
	Password string `yaml:"password"`
	DBName   string `yaml:"dbname"`
	SSLMode  string `yaml:"sslmode,omitempty"`
}

type Config struct {
	Environments map[string]EnvironmentConfig `yaml:"environments"`
	Migrations   MigrationsConfig             `yaml:"migrations"`
}

type EnvironmentConfig struct {
	Database DatabaseConfig `yaml:"database"`
}

type MigrationsConfig struct {
	Directory string `yaml:"directory"`
	Table     string `yaml:"table"`
	LogTable  string `yaml:"log_table"`
}

type ExecutionResult struct {
	Success       bool
	MigrationID   string
	Version       string
	Error         error
	ExecutionTime time.Duration
	SQLCount      int
}

type RollbackResult struct {
	Success       bool
	MigrationID   string
	Version       string
	Error         error
	ExecutionTime time.Duration
}

type StatusSummary struct {
	Pending    []*Migration
	Executed   []*MigrationState
	Total      int
	PendingCount int
	ExecutedCount int
}
