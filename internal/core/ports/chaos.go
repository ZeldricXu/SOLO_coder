package ports

import (
	"context"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
)

type ChaosInjector interface {
	Inject(ctx context.Context, scope *domain.InjectionScope, params map[string]interface{}) error
	Rollback(ctx context.Context, runID string) error
	Type() string
}

type InjectorRegistry interface {
	Get(injectorType string) (ChaosInjector, error)
	ListTypes() []string
}

type ScenarioRepository interface {
	Save(ctx context.Context, scenario *domain.ChaosScenario) error
	FindByID(ctx context.Context, scenarioID string) (*domain.ChaosScenario, error)
	ListByNamespace(ctx context.Context, namespace string) ([]*domain.ChaosScenario, error)
	Delete(ctx context.Context, scenarioID string) error
}

type RunInstanceRepository interface {
	Save(ctx context.Context, run *domain.RunInstance) error
	FindByID(ctx context.Context, runID string) (*domain.RunInstance, error)
	Update(ctx context.Context, run *domain.RunInstance) error
	UpdatePhase(runID string, phase string, progress float64)
	UpdateError(runID string, errMsg string)
}



type MetricsCollector interface {
	RecordTiming(operation string, duration time.Duration, labels map[string]string)
	RecordCounter(name string, value int64, labels map[string]string)
	RecordGauge(name string, value float64, labels map[string]string)
	GetMetrics() *domain.ChaosMetrics
}

type PrometheusExporter interface {
	RecordInjectionStarted(injectorType string)
	RecordInjectionCompleted(injectorType string, success bool)
	RecordInjectionDuration(injectorType string, duration time.Duration)
	SetActiveRuns(count int)
	RecordRollbackStarted(injectorType string)
	RecordRollbackCompleted(injectorType string, success bool)
	GetRegistry() interface{}
}

type ExecutionCoordinator interface {
	Start(ctx context.Context, scenario *domain.ChaosScenario, scope *domain.InjectionScope) (*domain.RunInstance, error)
	Cancel(ctx context.Context, runID string) error
	GetStatus(ctx context.Context, runID string) (*domain.RunInstance, error)
	ActiveRunsCount() int
	GetExecutionTiming(runID string) ([]*domain.ExecutionTiming, error)
	GetMetrics() *domain.ChaosMetrics
}
