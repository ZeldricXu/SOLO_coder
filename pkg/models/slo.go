package models

import (
	"time"
)

type SLI struct {
	Name           string                 `json:"name"`
	Description    string                 `json:"description,omitempty"`
	Type           string                 `json:"type"`
	Config         map[string]interface{} `json:"config"`
	CurrentValue   float64                `json:"current_value"`
	TargetValue    float64                `json:"target_value"`
	Labels         map[string]string      `json:"labels,omitempty"`
}

type ErrorBudgetStatus string

const (
	ErrorBudgetStatusHealthy   ErrorBudgetStatus = "HEALTHY"
	ErrorBudgetStatusWarning   ErrorBudgetStatus = "WARNING"
	ErrorBudgetStatusExhausted ErrorBudgetStatus = "EXHAUSTED"
)

type ErrorBudget struct {
	TotalBudgetSeconds   float64            `json:"total_budget_seconds"`
	ConsumedSeconds      float64            `json:"consumed_seconds"`
	RemainingSeconds     float64            `json:"remaining_seconds"`
	ConsumedPercentage   float64            `json:"consumed_percentage"`
	RemainingPercentage  float64            `json:"remaining_percentage"`
	Status               ErrorBudgetStatus  `json:"status"`
	BurnRate             float64            `json:"burn_rate"`
	ProjectedExhaustion  *time.Time         `json:"projected_exhaustion,omitempty"`
}

type SLO struct {
	ID              string                 `json:"id"`
	Name            string                 `json:"name"`
	Description     string                 `json:"description,omitempty"`
	ServiceName     string                 `json:"service_name"`
	SLI             SLI                    `json:"sli"`
	Target          float64                `json:"target"`
	Window          time.Duration          `json:"window"`
	ErrorBudget     ErrorBudget            `json:"error_budget"`
	Labels          map[string]string      `json:"labels,omitempty"`
	CreatedAt       time.Time              `json:"created_at"`
	UpdatedAt       time.Time              `json:"updated_at"`
}

type BurnRateAlert struct {
	WindowSize       time.Duration `json:"window_size"`
	BurnRateThreshold float64       `json:"burn_rate_threshold"`
	Notify           bool          `json:"notify"`
	Page             bool          `json:"page"`
}

type SLOAlertPolicy struct {
	ID               string            `json:"id"`
	SLOID            string            `json:"slo_id"`
	Name             string            `json:"name"`
	FastBurnRate     BurnRateAlert     `json:"fast_burn_rate"`
	SlowBurnRate     BurnRateAlert     `json:"slow_burn_rate"`
	BurnoutThreshold float64           `json:"burnout_threshold"`
	Enabled          bool              `json:"enabled"`
}

type SLORecord struct {
	Timestamp      time.Time `json:"timestamp"`
	SLOID          string    `json:"slo_id"`
	SLIValue       float64   `json:"sli_value"`
	BudgetConsumed float64   `json:"budget_consumed"`
	BurnRate       float64   `json:"burn_rate"`
}
