package data_aggregation

import (
	"time"

	"edgescheduler/internal/common/models"
)

type AggregationType string
type AggregationStatus string

const (
	AggregationTypeSum     AggregationType = "sum"
	AggregationTypeAvg     AggregationType = "avg"
	AggregationTypeCount   AggregationType = "count"
	AggregationTypeMin     AggregationType = "min"
	AggregationTypeMax     AggregationType = "max"
	AggregationTypeFirst   AggregationType = "first"
	AggregationTypeLast    AggregationType = "last"

	AggregationStatusPending   AggregationStatus = "pending"
	AggregationStatusProcessing AggregationStatus = "processing"
	AggregationStatusCompleted AggregationStatus = "completed"
	AggregationStatusFailed    AggregationStatus = "failed"
)

type AggregationRule struct {
	models.BaseModel
	RuleID        string            `gorm:"type:varchar(50);not null;uniqueIndex" json:"rule_id"`
	Name          string            `gorm:"type:varchar(100);not null" json:"name"`
	DeviceID      string            `gorm:"type:varchar(50);not null;index" json:"device_id"`
	DataSource    string            `gorm:"type:varchar(100);not null" json:"data_source"`
	AggregationType AggregationType `gorm:"type:varchar(20);not null" json:"aggregation_type"`
	Field         string            `gorm:"type:varchar(100);not null" json:"field"`
	GroupBy       []string          `gorm:"type:jsonb" json:"group_by"`
	WindowSeconds int               `gorm:"not null;default:60" json:"window_seconds"`
	Filter        map[string]interface{} `gorm:"type:jsonb" json:"filter"`
	Enabled       bool              `gorm:"default:true" json:"enabled"`
}

type AggregationResult struct {
	models.BaseModel
	ResultID    string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"result_id"`
	RuleID      string                 `gorm:"type:varchar(50);not null;index" json:"rule_id"`
	DeviceID    string                 `gorm:"type:varchar(50);not null;index" json:"device_id"`
	WindowStart time.Time              `gorm:"not null;index" json:"window_start"`
	WindowEnd   time.Time              `gorm:"not null;index" json:"window_end"`
	Value       float64                `json:"value"`
	Count       int64                  `json:"count"`
	GroupValues map[string]string      `gorm:"type:jsonb" json:"group_values"`
	Uploaded    bool                   `gorm:"default:false;index" json:"uploaded"`
	UploadedAt  *time.Time             `json:"uploaded_at,omitempty"`
}

type RawDataPoint struct {
	DeviceID    string                 `json:"device_id"`
	DataSource  string                 `json:"data_source"`
	Timestamp   time.Time              `json:"timestamp"`
	Data        map[string]interface{} `json:"data"`
}

type AggregationRequest struct {
	RuleID      string    `json:"rule_id"`
	DeviceID    string    `json:"device_id"`
	WindowStart time.Time `json:"window_start"`
	WindowEnd   time.Time `json:"window_end"`
}
