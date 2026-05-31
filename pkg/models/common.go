package models

import (
	"time"
)

type SeverityLevel string

const (
	SeverityDebug   SeverityLevel = "DEBUG"
	SeverityInfo    SeverityLevel = "INFO"
	SeverityWarning SeverityLevel = "WARNING"
	SeverityError   SeverityLevel = "ERROR"
	SeverityFatal   SeverityLevel = "FATAL"
)

type TimeSeriesDataPoint struct {
	Timestamp time.Time         `json:"timestamp"`
	Value     float64           `json:"value"`
	Labels    map[string]string `json:"labels,omitempty"`
}

type Metric struct {
	Name        string            `json:"name"`
	Description string            `json:"description,omitempty"`
	Unit        string            `json:"unit,omitempty"`
	Labels      map[string]string `json:"labels,omitempty"`
}

type AlertStatus string

const (
	AlertStatusPending   AlertStatus = "PENDING"
	AlertStatusFiring    AlertStatus = "FIRING"
	AlertStatusResolved  AlertStatus = "RESOLVED"
	AlertStatusSuppressed AlertStatus = "SUPPRESSED"
)

type Alert struct {
	ID             string                 `json:"id"`
	RuleID         string                 `json:"rule_id"`
	RuleName       string                 `json:"rule_name"`
	Status         AlertStatus            `json:"status"`
	Severity       SeverityLevel          `json:"severity"`
	Message        string                 `json:"message"`
	Labels         map[string]string      `json:"labels,omitempty"`
	Annotations    map[string]string      `json:"annotations,omitempty"`
	StartsAt       time.Time              `json:"starts_at"`
	EndsAt         *time.Time             `json:"ends_at,omitempty"`
	GeneratorURL   string                 `json:"generator_url,omitempty"`
	Fingerprint    string                 `json:"fingerprint"`
}

type NotificationChannel struct {
	ID     string                 `json:"id"`
	Name   string                 `json:"name"`
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
}
