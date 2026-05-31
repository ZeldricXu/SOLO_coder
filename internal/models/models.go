package models

import "time"

type Resource struct {
	ID        string                 `json:"id" gorm:"primaryKey"`
	Type      string                 `json:"type"`
	Status    string                 `json:"status"`
	Config    map[string]interface{} `json:"config" gorm:"serializer:json"`
	Labels    map[string]string      `json:"labels" gorm:"serializer:json"`
	Progress  float64                `json:"progress"`
	CreatedAt time.Time              `json:"created_at"`
	UpdatedAt time.Time              `json:"updated_at"`
}

type BatchOperation struct {
	BatchID   string    `json:"batch_id" gorm:"primaryKey"`
	CreatedAt time.Time `json:"created_at"`
}

type BatchResult struct {
	ID        string `json:"id" gorm:"primaryKey"`
	BatchID   string `json:"batch_id"`
	Action    string `json:"action"`
	ResourceID string `json:"resource_id"`
	Status    string `json:"status"`
	Message   string `json:"message"`
}
