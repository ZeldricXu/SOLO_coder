package metrics

import (
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

type PrometheusExporter struct {
	reg                     *prometheus.Registry
	injectionsStarted       *prometheus.CounterVec
	injectionsCompleted     *prometheus.CounterVec
	injectionsFailed        *prometheus.CounterVec
	injectionDuration       *prometheus.HistogramVec
	rollbacksStarted        *prometheus.CounterVec
	rollbacksCompleted      *prometheus.CounterVec
	rollbacksFailed         *prometheus.CounterVec
	activeRuns              prometheus.Gauge
	scenarioExecutionTotal  *prometheus.CounterVec
	mu                      sync.Mutex
}

func NewPrometheusExporter() *PrometheusExporter {
	reg := prometheus.NewRegistry()

	e := &PrometheusExporter{
		reg: reg,
		injectionsStarted: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "chaos_injections_started_total",
				Help: "Total number of chaos injections started",
			},
			[]string{"injector_type"},
		),
		injectionsCompleted: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "chaos_injections_completed_total",
				Help: "Total number of chaos injections completed successfully",
			},
			[]string{"injector_type", "success"},
		),
		injectionsFailed: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "chaos_injections_failed_total",
				Help: "Total number of chaos injections failed",
			},
			[]string{"injector_type"},
		),
		injectionDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "chaos_injection_duration_seconds",
				Help:    "Duration of chaos injection operations",
				Buckets: prometheus.DefBuckets,
			},
			[]string{"injector_type", "phase"},
		),
		rollbacksStarted: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "chaos_rollbacks_started_total",
				Help: "Total number of chaos rollbacks started",
			},
			[]string{"injector_type"},
		),
		rollbacksCompleted: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "chaos_rollbacks_completed_total",
				Help: "Total number of chaos rollbacks completed",
			},
			[]string{"injector_type", "success"},
		),
		rollbacksFailed: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "chaos_rollbacks_failed_total",
				Help: "Total number of chaos rollbacks failed",
			},
			[]string{"injector_type"},
		),
		activeRuns: prometheus.NewGauge(
			prometheus.GaugeOpts{
				Name: "chaos_active_runs",
				Help: "Current number of active chaos runs",
			},
		),
		scenarioExecutionTotal: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "chaos_scenario_execution_total",
				Help: "Total number of scenario executions",
			},
			[]string{"scenario_id", "phase"},
		),
	}

	reg.MustRegister(
		e.injectionsStarted,
		e.injectionsCompleted,
		e.injectionsFailed,
		e.injectionDuration,
		e.rollbacksStarted,
		e.rollbacksCompleted,
		e.rollbacksFailed,
		e.activeRuns,
		e.scenarioExecutionTotal,
	)

	return e
}

func (e *PrometheusExporter) RecordInjectionStarted(injectorType string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.injectionsStarted.WithLabelValues(injectorType).Inc()
}

func (e *PrometheusExporter) RecordInjectionCompleted(injectorType string, success bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	successStr := "false"
	if success {
		successStr = "true"
	}
	e.injectionsCompleted.WithLabelValues(injectorType, successStr).Inc()
	if !success {
		e.injectionsFailed.WithLabelValues(injectorType).Inc()
	}
}

func (e *PrometheusExporter) RecordInjectionDuration(injectorType string, duration time.Duration) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.injectionDuration.WithLabelValues(injectorType, "inject").Observe(duration.Seconds())
}

func (e *PrometheusExporter) RecordRollbackDuration(injectorType string, duration time.Duration) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.injectionDuration.WithLabelValues(injectorType, "rollback").Observe(duration.Seconds())
}

func (e *PrometheusExporter) SetActiveRuns(count int) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.activeRuns.Set(float64(count))
}

func (e *PrometheusExporter) RecordRollbackStarted(injectorType string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.rollbacksStarted.WithLabelValues(injectorType).Inc()
}

func (e *PrometheusExporter) RecordRollbackCompleted(injectorType string, success bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	successStr := "false"
	if success {
		successStr = "true"
	}
	e.rollbacksCompleted.WithLabelValues(injectorType, successStr).Inc()
	if !success {
		e.rollbacksFailed.WithLabelValues(injectorType).Inc()
	}
}

func (e *PrometheusExporter) RecordPhase(scenarioID, phase string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.scenarioExecutionTotal.WithLabelValues(scenarioID, phase).Inc()
}

func (e *PrometheusExporter) GetRegistry() *prometheus.Registry {
	return e.reg
}
