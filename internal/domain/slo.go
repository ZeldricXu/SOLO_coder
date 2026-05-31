package domain

import (
	"time"
)

type SLOStatus string

const (
	SLOStatusOK       SLOStatus = "ok"
	SLOStatusWarning  SLOStatus = "warning"
	SLOStatusBurning  SLOStatus = "burning"
	SLOStatusExhausted SLOStatus = "exhausted"
)

type SLI struct {
	SLIID       string                 `json:"sli_id" gorm:"primaryKey;type:varchar(64)"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	MetricName  string                 `json:"metric_name" gorm:"index"`
	TargetValue float64                `json:"target_value"`
	Unit        string                 `json:"unit"`
	Labels      map[string]string      `json:"labels" gorm:"type:jsonb"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

func (SLI) TableName() string {
	return "slis"
}

type SLO struct {
	SLOID       string                 `json:"slo_id" gorm:"primaryKey;type:varchar(64)"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	SLIID       string                 `json:"sli_id" gorm:"type:varchar(64);index"`
	Target      float64                `json:"target"`
	TimeWindow  string                 `json:"time_window" gorm:"type:varchar(32)"`
	Status      SLOStatus              `json:"status" gorm:"type:varchar(32);index"`
	BudgetTotal float64                `json:"budget_total"`
	BudgetUsed  float64                `json:"budget_used"`
	BudgetRemaining float64            `json:"budget_remaining"`
	Labels      map[string]string      `json:"labels" gorm:"type:jsonb"`
	StartTime   time.Time              `json:"start_time"`
	EndTime     time.Time              `json:"end_time"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

func (SLO) TableName() string {
	return "slos"
}

type ErrorBudget struct {
	BudgetID    string    `json:"budget_id" gorm:"primaryKey;type:varchar(64)"`
	SLOID       string    `json:"slo_id" gorm:"type:varchar(64);index"`
	PeriodStart time.Time `json:"period_start" gorm:"index"`
	PeriodEnd   time.Time `json:"period_end"`
	TotalSeconds float64  `json:"total_seconds"`
	UsedSeconds  float64  `json:"used_seconds"`
	BurnRate     float64  `json:"burn_rate"`
	RemainingPercentage float64 `json:"remaining_percentage"`
	CalculatedAt time.Time `json:"calculated_at"`
}

func (ErrorBudget) TableName() string {
	return "error_budgets"
}

type SLIMeasurement struct {
	MeasurementID string    `json:"measurement_id" gorm:"primaryKey;type:varchar(64)"`
	SLIID         string    `json:"sli_id" gorm:"type:varchar(64);index"`
	Value         float64   `json:"value"`
	IsValid       bool      `json:"is_valid"`
	Timestamp     time.Time `json:"timestamp" gorm:"index"`
}

func (SLIMeasurement) TableName() string {
	return "sli_measurements"
}
