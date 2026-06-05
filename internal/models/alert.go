package models

import (
	"time"

	"github.com/google/uuid"
)

type Severity string

const (
	SeverityCritical Severity = "CRITICAL"
	SeverityHigh     Severity = "HIGH"
	SeverityMedium   Severity = "MEDIUM"
	SeverityLow      Severity = "LOW"
)

type AlertType string

const (
	AlertTypeErrorRate      AlertType = "ERROR_RATE_SPIKE"
	AlertTypeP99Latency     AlertType = "P99_LATENCY_SPIKE"
	AlertTypeErrorPattern   AlertType = "ERROR_PATTERN"
	AlertTypeCustomRule     AlertType = "CUSTOM_RULE"
)

type Alert struct {
	ID             string                 `json:"id"`
	AlertType      AlertType              `json:"alert_type"`
	Severity       Severity               `json:"severity"`
	Title          string                 `json:"title"`
	Description    string                 `json:"description"`
	ServiceName    string                 `json:"service_name"`
	Timestamp      time.Time              `json:"timestamp"`
	MetricValue    float64                `json:"metric_value"`
	Threshold      float64                `json:"threshold"`
	WindowSize     time.Duration          `json:"window_size"`
	Algorithm      string                 `json:"algorithm"`
	ZScore         float64                `json:"z_score,omitempty"`
	MADScore       float64                `json:"mad_score,omitempty"`
	Tags           []string               `json:"tags,omitempty"`
	Labels         map[string]string      `json:"labels,omitempty"`
	RelatedLogs    []string               `json:"related_log_ids,omitempty"`
	TraceIDs       []string               `json:"trace_ids,omitempty"`
	ErrorCode      string                 `json:"error_code,omitempty"`
	DeduplicationKey string               `json:"deduplication_key"`
}

type Incident struct {
	ID                string            `json:"id"`
	Title             string            `json:"title"`
	Description       string            `json:"description"`
	Severity          Severity          `json:"severity"`
	Status            string            `json:"status"`
	StartTime         time.Time         `json:"start_time"`
	EndTime           *time.Time        `json:"end_time,omitempty"`
	Alerts            []*Alert          `json:"alerts"`
	ServiceNames      []string          `json:"service_names"`
	RootCause         string            `json:"root_cause,omitempty"`
	BusinessImpact    string            `json:"business_impact,omitempty"`
	RelatedTraceIDs   []string          `json:"related_trace_ids"`
	RelatedErrorCodes []string          `json:"related_error_codes"`
	DeduplicationKey  string            `json:"deduplication_key"`
	Acknowledged      bool              `json:"acknowledged"`
	AcknowledgedBy    string            `json:"acknowledged_by,omitempty"`
	AcknowledgedAt    *time.Time        `json:"acknowledged_at,omitempty"`
}

type AlertNotification struct {
	Incident      *Incident `json:"incident"`
	Summary       string    `json:"summary"`
	QueryURL      string    `json:"query_url"`
	LogViewerURL  string    `json:"log_viewer_url"`
	GeneratedAt   time.Time `json:"generated_at"`
}

func NewAlert(alertType AlertType, severity Severity, serviceName string) *Alert {
	return &Alert{
		ID:          uuid.New().String(),
		AlertType:   alertType,
		Severity:    severity,
		ServiceName: serviceName,
		Timestamp:   time.Now(),
		Tags:        make([]string, 0),
		Labels:      make(map[string]string),
		RelatedLogs: make([]string, 0),
		TraceIDs:    make([]string, 0),
	}
}

func NewIncident(alert *Alert) *Incident {
	return &Incident{
		ID:                uuid.New().String(),
		Title:             alert.Title,
		Description:       alert.Description,
		Severity:          alert.Severity,
		Status:            "ACTIVE",
		StartTime:         alert.Timestamp,
		Alerts:            []*Alert{alert},
		ServiceNames:      []string{alert.ServiceName},
		RelatedTraceIDs:   make([]string, 0),
		RelatedErrorCodes: make([]string, 0),
		DeduplicationKey:  alert.DeduplicationKey,
	}
}
