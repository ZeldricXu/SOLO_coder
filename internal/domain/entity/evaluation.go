package entity

import (
	"time"
)

type EvaluationMetric struct {
	ID             string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ModelVersionID string                 `gorm:"type:varchar(64);not null;index" json:"model_version_id"`
	Type           string                 `gorm:"type:varchar(64);not null" json:"type"`
	Name           string                 `gorm:"type:varchar(256);not null" json:"name"`
	Value          float64                `gorm:"not null" json:"value"`
	BaselineValue  float64                `json:"baseline_value"`
	Unit           string                 `gorm:"type:varchar(64)" json:"unit"`
	Metadata       map[string]interface{} `gorm:"type:jsonb" json:"metadata,omitempty"`
	Timestamp      time.Time              `gorm:"not null;index" json:"timestamp"`
	CreatedAt      time.Time              `gorm:"not null" json:"created_at"`
}

type DriftDetection struct {
	ID             string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ModelVersionID string    `gorm:"type:varchar(64);not null;index" json:"model_version_id"`
	FeatureName    string    `gorm:"type:varchar(256);not null" json:"feature_name"`
	DriftType      string    `gorm:"type:varchar(64);not null" json:"drift_type"`
	DriftScore     float64   `gorm:"not null" json:"drift_score"`
	Threshold      float64   `gorm:"not null" json:"threshold"`
	Alert          bool      `gorm:"default:false" json:"alert"`
	Status         string    `gorm:"type:varchar(64);not null" json:"status"`
	Timestamp      time.Time `gorm:"not null;index" json:"timestamp"`
	CreatedAt      time.Time `gorm:"not null" json:"created_at"`
}

type EvaluationDataset struct {
	ID          string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name        string                 `gorm:"type:varchar(256);not null" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	Type        string                 `gorm:"type:varchar(64);not null" json:"type"`
	Source      string                 `gorm:"type:varchar(512)" json:"source"`
	SampleCount int                    `json:"sample_count"`
	Schema      map[string]interface{} `gorm:"type:jsonb" json:"schema,omitempty"`
	Status      string                 `gorm:"type:varchar(64);not null" json:"status"`
	CreatedBy   string                 `gorm:"type:varchar(128)" json:"created_by"`
	CreatedAt   time.Time              `gorm:"not null" json:"created_at"`
	UpdatedAt   time.Time              `gorm:"not null" json:"updated_at"`
}

type EvaluationRun struct {
	ID               string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ModelVersionID   string                 `gorm:"type:varchar(64);not null;index" json:"model_version_id"`
	DatasetID        string                 `gorm:"type:varchar(64);not null" json:"dataset_id"`
	Name             string                 `gorm:"type:varchar(256);not null" json:"name"`
	Status           string                 `gorm:"type:varchar(64);not null;index" json:"status"`
	Metrics          map[string]float64     `gorm:"type:jsonb" json:"metrics,omitempty"`
	Parameters       map[string]interface{} `gorm:"type:jsonb" json:"parameters,omitempty"`
	StartTime        time.Time              `json:"start_time"`
	EndTime          *time.Time             `json:"end_time,omitempty"`
	CreatedBy        string                 `gorm:"type:varchar(128)" json:"created_by"`
	CreatedAt        time.Time              `gorm:"not null" json:"created_at"`
}

type MonitorConfig struct {
	ID             string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	ModelVersionID string                 `gorm:"type:varchar(64);not null;index" json:"model_version_id"`
	Name           string                 `gorm:"type:varchar(256);not null" json:"name"`
	Enabled        bool                   `gorm:"default:true" json:"enabled"`
	AlertRules     []AlertRule            `gorm:"type:jsonb" json:"alert_rules"`
	CheckInterval  string                 `json:"check_interval"`
	Config         map[string]interface{} `gorm:"type:jsonb" json:"config,omitempty"`
	CreatedAt      time.Time              `gorm:"not null" json:"created_at"`
	UpdatedAt      time.Time              `gorm:"not null" json:"updated_at"`
}

type AlertRule struct {
	Metric      string  `json:"metric"`
	Condition   string  `json:"condition"`
	Threshold   float64 `json:"threshold"`
	Severity    string  `json:"severity"`
	Enabled     bool    `json:"enabled"`
}

type DriftType string

const (
	DriftTypeData          DriftType = "data_drift"
	DriftTypeConcept       DriftType = "concept_drift"
	DriftTypePrediction    DriftType = "prediction_drift"
)

type MetricType string

const (
	MetricTypeAccuracy   MetricType = "accuracy"
	MetricTypePrecision  MetricType = "precision"
	MetricTypeRecall     MetricType = "recall"
	MetricTypeF1         MetricType = "f1"
	MetricTypeBLEU       MetricType = "bleu"
	MetricTypeROUGE      MetricType = "rouge"
	MetricTypePerplexity MetricType = "perplexity"
	MetricTypeLatency    MetricType = "latency"
	MetricTypeThroughput MetricType = "throughput"
)
