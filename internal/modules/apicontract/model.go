package apicontract

import (
	"depguard/internal/common/model"
	"time"
)

type APISchema struct {
	model.BaseModel
	Name        string                 `gorm:"type:varchar(128);not null" json:"name"`
	Version     string                 `gorm:"type:varchar(32);not null" json:"version"`
	SchemaType  string                 `gorm:"type:varchar(16);index;not null" json:"schema_type"`
	Content     string                 `gorm:"type:text;not null" json:"content"`
	Format      string                 `gorm:"type:varchar(16);not null" json:"format"`
	ServiceName string                 `gorm:"type:varchar(64);index" json:"service_name"`
	Metadata    map[string]interface{} `gorm:"type:jsonb" json:"metadata"`
	IsActive    bool                   `gorm:"default:true" json:"is_active"`
}

type ValidationResult struct {
	model.BaseModel
	SchemaID    string                 `gorm:"type:varchar(64);index" json:"schema_id"`
	Status      string                 `gorm:"type:varchar(32);index" json:"status"`
	Errors      []string               `gorm:"type:text[]" json:"errors"`
	Warnings    []string               `gorm:"type:text[]" json:"warnings"`
	Details     map[string]interface{} `gorm:"type:jsonb" json:"details"`
	ValidatedAt time.Time              `json:"validated_at"`
	DurationMs  int64                  `json:"duration_ms"`
}

type MockServer struct {
	model.BaseModel
	ServerID    string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"server_id"`
	SchemaID    string                 `gorm:"type:varchar(64);index" json:"schema_id"`
	Name        string                 `gorm:"type:varchar(128);not null" json:"name"`
	Port        int                    `json:"port"`
	Status      string                 `gorm:"type:varchar(32);index" json:"status"`
	BaseURL     string                 `gorm:"type:varchar(256)" json:"base_url"`
	Config      map[string]interface{} `gorm:"type:jsonb" json:"config"`
	Endpoints   []string               `gorm:"type:varchar(256)[]" json:"endpoints"`
	StartedAt   *time.Time             `json:"started_at"`
	StoppedAt   *time.Time             `json:"stopped_at"`
	CreatedBy   string                 `gorm:"type:varchar(64)" json:"created_by"`
}

type ContractTest struct {
	model.BaseModel
	TestID      string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"test_id"`
	SchemaID    string                 `gorm:"type:varchar(64);index" json:"schema_id"`
	Name        string                 `gorm:"type:varchar(128);not null" json:"name"`
	TestType    string                 `gorm:"type:varchar(32);index" json:"test_type"`
	Request     map[string]interface{} `gorm:"type:jsonb" json:"request"`
	Expected    map[string]interface{} `gorm:"type:jsonb" json:"expected"`
	LastResult  string                 `gorm:"type:varchar(16)" json:"last_result"`
	LastRunAt   *time.Time             `json:"last_run_at"`
	PassCount   int                    `gorm:"default:0" json:"pass_count"`
	FailCount   int                    `gorm:"default:0" json:"fail_count"`
}
