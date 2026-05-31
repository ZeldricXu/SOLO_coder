package model

import (
	"time"
)

type APIContract struct {
	ID           string                 `gorm:"primaryKey;column:id" json:"id"`
	ServiceID    string                 `gorm:"column:service_id;index" json:"service_id"`
	ContractType string                 `gorm:"column:contract_type;index" json:"contract_type"`
	Schema       map[string]interface{} `gorm:"column:schema;type:jsonb" json:"schema"`
	Version      string                 `gorm:"column:version" json:"version"`
	BasePath     string                 `gorm:"column:base_path" json:"base_path"`
	Servers      []string               `gorm:"column:servers;type:jsonb;serializer:json" json:"servers"`
	SecuritySchemes map[string]interface{} `gorm:"column:security_schemes;type:jsonb" json:"security_schemes"`
	CreatedBy    string                 `gorm:"column:created_by" json:"created_by"`
	Status       string                 `gorm:"column:status;index" json:"status"`
	CreatedAt    time.Time              `gorm:"column:created_at" json:"created_at"`
	UpdatedAt    time.Time              `gorm:"column:updated_at" json:"updated_at"`
}

func (APIContract) TableName() string {
	return "api_contracts"
}

type MockServer struct {
	ID             string    `gorm:"primaryKey;column:id" json:"id"`
	ContractID     string    `gorm:"column:contract_id;index" json:"contract_id"`
	Name           string    `gorm:"column:name" json:"name"`
	BaseURL        string    `gorm:"column:base_url" json:"base_url"`
	Port           int       `gorm:"column:port" json:"port"`
	Status         string    `gorm:"column:status;index" json:"status"`
	DelayMs        int       `gorm:"column:delay_ms" json:"delay_ms"`
	ErrorRate      float64   `gorm:"column:error_rate" json:"error_rate"`
	CreatedBy      string    `gorm:"column:created_by" json:"created_by"`
	StartedAt      *time.Time `gorm:"column:started_at" json:"started_at"`
	StoppedAt      *time.Time `gorm:"column:stopped_at" json:"stopped_at"`
	CreatedAt      time.Time `gorm:"column:created_at" json:"created_at"`
	UpdatedAt      time.Time `gorm:"column:updated_at" json:"updated_at"`
}

func (MockServer) TableName() string {
	return "mock_servers"
}

type RegisterContractRequest struct {
	ServiceID      string                 `json:"service_id" binding:"required"`
	ContractType   string                 `json:"contract_type" binding:"required"`
	Schema         map[string]interface{} `json:"schema" binding:"required"`
	Version        string                 `json:"version"`
	BasePath       string                 `json:"base_path"`
	Servers        []string               `json:"servers"`
}

type ValidateContractRequest struct {
	ContractID string `json:"contract_id" binding:"required"`
	Method     string `json:"method" binding:"required"`
	Path       string `json:"path" binding:"required"`
	RequestBody interface{} `json:"request_body"`
	Headers    map[string]string `json:"headers"`
}

type ValidationResult struct {
	Valid    bool              `json:"valid"`
	Errors   []ValidationError `json:"errors"`
	Warnings []string          `json:"warnings"`
}

type ValidationError struct {
	Field   string `json:"field"`
	Message string `json:"message"`
	Type    string `json:"type"`
}

type CreateMockServerRequest struct {
	ContractID string  `json:"contract_id" binding:"required"`
	Name       string  `json:"name" binding:"required"`
	Port       int     `json:"port"`
	DelayMs    int     `json:"delay_ms"`
	ErrorRate  float64 `json:"error_rate"`
}

type MockServerStatus struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	BaseURL   string    `json:"base_url"`
	Status    string    `json:"status"`
	Port      int       `json:"port"`
	StartedAt *time.Time `json:"started_at"`
}
