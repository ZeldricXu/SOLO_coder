package apicontract

import (
	"time"
)

type APISchema struct {
	ID          string                 `gorm:"primaryKey" json:"id"`
	Name        string                 `json:"name"`
	Type        string                 `gorm:"index" json:"type"`
	Version     string                 `json:"version"`
	Content     string                 `gorm:"type:text" json:"content"`
	Format      string                 `json:"format"`
	ServiceID   string                 `gorm:"index" json:"service_id"`
	Metadata    map[string]interface{} `gorm:"serializer:json" json:"metadata"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type ValidationResult struct {
	ID           string    `gorm:"primaryKey" json:"id"`
	SchemaID     string    `gorm:"index" json:"schema_id"`
	Status       string    `json:"status"`
	TotalErrors  int       `json:"total_errors"`
	TotalWarnings int      `json:"total_warnings"`
	Errors       []ValidationIssue `gorm:"serializer:json" json:"errors"`
	Warnings     []ValidationIssue `gorm:"serializer:json" json:"warnings"`
	ValidatedAt  time.Time `json:"validated_at"`
	CreatedAt    time.Time `json:"created_at"`
}

type ValidationIssue struct {
	Location string `json:"location"`
	Message  string `json:"message"`
	Path     string `json:"path"`
	Rule     string `json:"rule"`
}

type MockServer struct {
	ID         string    `gorm:"primaryKey" json:"id"`
	SchemaID   string    `gorm:"index" json:"schema_id"`
	Name       string    `json:"name"`
	Port       int       `json:"port"`
	Status     string    `json:"status"`
	Endpoints  []MockEndpoint `gorm:"serializer:json" json:"endpoints"`
	Config     map[string]interface{} `gorm:"serializer:json" json:"config"`
	CreatedAt  time.Time `json:"created_at"`
	StartedAt  *time.Time `json:"started_at"`
	StoppedAt  *time.Time `json:"stopped_at"`
}

type MockEndpoint struct {
	Method      string            `json:"method"`
	Path        string            `json:"path"`
	StatusCode  int               `json:"status_code"`
	Response    interface{}       `json:"response"`
	Headers     map[string]string `json:"headers"`
	DelayMs     int               `json:"delay_ms"`
	Examples    []MockExample     `json:"examples"`
}

type MockExample struct {
	Name        string            `json:"name"`
	StatusCode  int               `json:"status_code"`
	Response    interface{}       `json:"response"`
	Headers     map[string]string `json:"headers"`
	Conditions  map[string]string `json:"conditions"`
}

type ContractTest struct {
	ID           string    `gorm:"primaryKey" json:"id"`
	ConsumerID   string    `json:"consumer_id"`
	ProviderID   string    `json:"provider_id"`
	Status       string    `json:"status"`
	Specification string   `gorm:"type:text" json:"specification"`
	LastRunAt    *time.Time `json:"last_run_at"`
	Passed       bool      `json:"passed"`
	CreatedAt    time.Time `json:"created_at"`
	UpdatedAt    time.Time `json:"updated_at"`
}

type TestRun struct {
	ID           string    `gorm:"primaryKey" json:"id"`
	ContractID   string    `gorm:"index" json:"contract_id"`
	Status       string    `json:"status"`
	Passed       bool      `json:"passed"`
	TotalTests   int       `json:"total_tests"`
	PassedTests  int       `json:"passed_tests"`
	FailedTests  int       `json:"failed_tests"`
	Results      []TestResult `gorm:"serializer:json" json:"results"`
	StartedAt    time.Time `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at"`
}

type TestResult struct {
	Name        string `json:"name"`
	Passed      bool   `json:"passed"`
	Message     string `json:"message,omitempty"`
	Request     interface{} `json:"request,omitempty"`
	Expected    interface{} `json:"expected,omitempty"`
	Actual      interface{} `json:"actual,omitempty"`
}

type ValidateRequest struct {
	SchemaID   string `json:"schema_id"`
	SchemaType string `json:"schema_type"`
	Content    string `json:"content"`
}

type CreateMockRequest struct {
	SchemaID string                 `json:"schema_id"`
	Name     string                 `json:"name"`
	Port     int                    `json:"port"`
	Config   map[string]interface{} `json:"config"`
}

type VerifyRequest struct {
	ServiceURL   string `json:"service_url"`
	SchemaID     string `json:"schema_id"`
}
