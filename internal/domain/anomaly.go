package domain

import (
	"time"
)

type AnomalyAlgorithm string

const (
	AnomalyAlgorithm3Sigma      AnomalyAlgorithm = "3sigma"
	AnomalyAlgorithmIsolationForest AnomalyAlgorithm = "isolation_forest"
	AnomalyAlgorithmEWMA        AnomalyAlgorithm = "ewma"
	AnomalyAlgorithmDBSCAN      AnomalyAlgorithm = "dbscan"
)

type AnomalySeverity string

const (
	AnomalySeverityCritical AnomalySeverity = "critical"
	AnomalySeverityWarning  AnomalySeverity = "warning"
	AnomalySeverityInfo     AnomalySeverity = "info"
)

type AnomalyResult struct {
	ResultID     string           `json:"result_id" gorm:"primaryKey;type:varchar(64)"`
	MetricName   string           `json:"metric_name" gorm:"index"`
	Algorithm    AnomalyAlgorithm `json:"algorithm" gorm:"type:varchar(32)"`
	Severity     AnomalySeverity  `json:"severity" gorm:"type:varchar(16);index"`
	CurrentValue float64          `json:"current_value"`
	ExpectedLow  float64          `json:"expected_low"`
	ExpectedHigh float64          `json:"expected_high"`
	Score        float64          `json:"score"`
	IsAnomaly    bool             `json:"is_anomaly" gorm:"index"`
	Timestamp    time.Time        `json:"timestamp" gorm:"index"`
	DetectedAt   time.Time        `json:"detected_at"`
}

func (AnomalyResult) TableName() string {
	return "anomaly_results"
}

type MetricBaseline struct {
	BaselineID  string    `json:"baseline_id" gorm:"primaryKey;type:varchar(64)"`
	MetricName  string    `json:"metric_name" gorm:"uniqueIndex;type:varchar(128)"`
	Mean        float64   `json:"mean"`
	StdDev      float64   `json:"std_dev"`
	Min         float64   `json:"min"`
	Max         float64   `json:"max"`
	Percentile5 float64   `json:"percentile_5"`
	Percentile95 float64  `json:"percentile_95"`
	WindowStart time.Time `json:"window_start"`
	WindowEnd   time.Time `json:"window_end"`
	SampleCount int64     `json:"sample_count"`
	UpdatedAt   time.Time `json:"updated_at"`
}

func (MetricBaseline) TableName() string {
	return "metric_baselines"
}
