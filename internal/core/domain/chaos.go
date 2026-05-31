package domain

import (
	"time"
)

type ChaosScenario struct {
	ScenarioID      string                 `json:"scenario_id" gorm:"primaryKey"`
	Name            string                 `json:"name"`
	Namespace       string                 `json:"namespace"`
	Description     string                 `json:"description"`
	InjectorType    string                 `json:"injector_type"`
	Parameters      map[string]interface{} `json:"parameters" gorm:"serializer:json"`
	Duration        time.Duration          `json:"duration"`
	AutoRollback    bool                   `json:"auto_rollback"`
	RollbackTimeout time.Duration          `json:"rollback_timeout"`
	CreatedAt       time.Time              `json:"created_at"`
	Enabled         bool                   `json:"enabled"`
}

type InjectionScope struct {
	Namespace    string            `json:"namespace"`
	Selector     map[string]string `json:"selector"`
	Targets      []string          `json:"targets"`
	Percentage   int               `json:"percentage"`
	ExcludeHosts []string          `json:"exclude_hosts"`
}

type ChaosMetrics struct {
	InjectionsStarted   int64            `json:"injections_started"`
	InjectionsCompleted int64            `json:"injections_completed"`
	InjectionsFailed    int64            `json:"injections_failed"`
	RollbacksStarted    int64            `json:"rollbacks_started"`
	RollbacksCompleted  int64            `json:"rollbacks_completed"`
	RollbacksFailed     int64            `json:"rollbacks_failed"`
	ActiveRuns          int              `json:"active_runs"`
	TotalRuns           int64            `json:"total_runs"`
	Timings             []*TimingMetric  `json:"timings,omitempty"`
	InjectorStats       map[string]*InjectorStat `json:"injector_stats,omitempty"`
}

type InjectorStat struct {
	Type          string           `json:"type"`
	TotalCalls    int64            `json:"total_calls"`
	SuccessCount  int64            `json:"success_count"`
	FailureCount  int64            `json:"failure_count"`
	AvgDurationMs float64          `json:"avg_duration_ms"`
	MinDurationMs int64            `json:"min_duration_ms"`
	MaxDurationMs int64            `json:"max_duration_ms"`
}

type ExecutionTiming struct {
	Phase     string        `json:"phase"`
	StartTime time.Time     `json:"start_time"`
	EndTime   time.Time     `json:"end_time"`
	Duration  time.Duration `json:"duration"`
}

type ExecutionMetrics struct {
	RunID         string                   `json:"run_id"`
	PhaseTimings  map[string]time.Duration `json:"phase_timings"`
	TotalDuration time.Duration            `json:"total_duration"`
	Timings       []*ExecutionTiming       `json:"timings,omitempty"`
}
