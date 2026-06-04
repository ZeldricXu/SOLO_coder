package models

import (
	"time"
)

type LogEntry struct {
	ID        string            `json:"id"`
	Timestamp time.Time         `json:"timestamp"`
	Source    string            `json:"source"`
	Host      string            `json:"host"`
	Level     string            `json:"level"`
	Message   string            `json:"message"`
	Fields    map[string]string `json:"fields"`
	Raw       string            `json:"raw"`
}

type WindowAggregate struct {
	WindowID     string                 `json:"window_id"`
	WindowStart  time.Time              `json:"window_start"`
	WindowEnd    time.Time              `json:"window_end"`
	WindowType   string                 `json:"window_type"`
	Key          string                 `json:"key"`
	Count        int64                  `json:"count"`
	LevelCounts  map[string]int64       `json:"level_counts"`
	Fields       map[string]interface{} `json:"fields"`
	LogSamples   []LogEntry             `json:"log_samples"`
}

type AlertEvent struct {
	ID          string                 `json:"id"`
	Timestamp   time.Time              `json:"timestamp"`
	AlertType   string                 `json:"alert_type"`
	Severity    string                 `json:"severity"`
	Title       string                 `json:"title"`
	Description string                 `json:"description"`
	SourceIP    string                 `json:"source_ip"`
	Source      string                 `json:"source"`
	Count       int64                  `json:"count"`
	Details     map[string]interface{} `json:"details"`
}

type AnomalyResult struct {
	ID               string                 `json:"id"`
	Timestamp        time.Time              `json:"timestamp"`
	MetricName       string                 `json:"metric_name"`
	AnomalyScore     float64                `json:"anomaly_score"`
	IsAnomaly        bool                   `json:"is_anomaly"`
	Method           string                 `json:"method"`
	Threshold        float64                `json:"threshold"`
	Value            float64                `json:"value"`
	Features         map[string]float64     `json:"features"`
	Score            float64                `json:"score"`
	TopContributors  []ContributingFeature  `json:"top_contributors"`
	DeviationPercent float64                `json:"deviation_percent"`
}

type ContributingFeature struct {
	Name       string  `json:"name"`
	Value      float64 `json:"value"`
	Baseline   float64 `json:"baseline"`
	Deviation  float64 `json:"deviation"`
	Contribution float64 `json:"contribution"`
}

type MetricPoint struct {
	Name      string            `json:"name"`
	Timestamp time.Time         `json:"timestamp"`
	Value     float64           `json:"value"`
	Labels    map[string]string `json:"labels"`
}
