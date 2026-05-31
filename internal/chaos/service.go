package chaos

import (
	"context"
	"fmt"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"github.com/chaoslab/platform/internal/chaos/metrics"
	"go.uber.org/zap"
)

type OrchestratorService struct {
	scenarioRepo   ports.ScenarioRepository
	runRepo        ports.RunInstanceRepository
	injectorReg    ports.InjectorRegistry
	coordinator    ports.ExecutionCoordinator
	logger         *zap.Logger
}

func NewOrchestratorService(
	scenarioRepo ports.ScenarioRepository,
	runRepo ports.RunInstanceRepository,
	injectorReg ports.InjectorRegistry,
	coordinator ports.ExecutionCoordinator,
	logger *zap.Logger,
) ports.ChaosOrchestratorService {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &OrchestratorService{
		scenarioRepo: scenarioRepo,
		runRepo:      runRepo,
		injectorReg:  injectorReg,
		coordinator:  coordinator,
		logger:       logger,
	}
}

func (s *OrchestratorService) DefineScenario(ctx context.Context, scenario *domain.ChaosScenario) (*domain.ChaosScenario, error) {
	if scenario == nil {
		return nil, &domain.AppError{Message: "scenario cannot be nil", Code: 400}
	}
	if scenario.Name == "" {
		return nil, &domain.AppError{Message: "scenario name is required", Code: 400}
	}
	if scenario.Namespace == "" {
		return nil, &domain.AppError{Message: "namespace is required", Code: 400}
	}
	if scenario.InjectorType == "" {
		return nil, &domain.AppError{Message: "injector type is required", Code: 400}
	}
	if _, err := s.injectorReg.Get(scenario.InjectorType); err != nil {
		return nil, &domain.AppError{Message: fmt.Sprintf("invalid injector type: %s", scenario.InjectorType), Code: 400}
	}
	if scenario.Duration <= 0 {
		scenario.Duration = 5 * time.Minute
	}
	if scenario.RollbackTimeout <= 0 {
		scenario.RollbackTimeout = 30 * time.Second
	}

	scenario.ScenarioID = fmt.Sprintf("scn_%d", time.Now().UnixNano())
	scenario.CreatedAt = time.Now()
	scenario.Enabled = true

	if err := s.scenarioRepo.Save(ctx, scenario); err != nil {
		return nil, &domain.AppError{Message: "failed to save scenario", Code: 500, Cause: err}
	}

	s.logger.Info("chaos scenario defined",
		zap.String("scenario_id", scenario.ScenarioID),
		zap.String("name", scenario.Name),
		zap.String("namespace", scenario.Namespace),
		zap.String("injector_type", scenario.InjectorType),
		zap.Duration("duration", scenario.Duration),
	)

	return scenario, nil
}

func (s *OrchestratorService) ExecuteScenario(ctx context.Context, scenarioID string, scope *domain.InjectionScope) (*domain.RunInstance, error) {
	scenario, err := s.scenarioRepo.FindByID(ctx, scenarioID)
	if err != nil {
		return nil, &domain.AppError{Message: fmt.Sprintf("scenario %s not found", scenarioID), Code: 404}
	}

	run, err := s.coordinator.Start(ctx, scenario, scope)
	if err != nil {
		return nil, &domain.AppError{Message: err.Error(), Code: 400}
	}

	return run, nil
}

func (s *OrchestratorService) CancelExecution(ctx context.Context, runID string) error {
	if err := s.coordinator.Cancel(ctx, runID); err != nil {
		return &domain.AppError{Message: err.Error(), Code: 404}
	}
	return nil
}

func (s *OrchestratorService) GetExecutionStatus(ctx context.Context, runID string) (*domain.RunInstance, error) {
	run, err := s.coordinator.GetStatus(ctx, runID)
	if err != nil {
		return nil, &domain.AppError{Message: fmt.Sprintf("run %s not found", runID), Code: 404}
	}
	return run, nil
}

func (s *OrchestratorService) ListScenarios(ctx context.Context, namespace string) ([]*domain.ChaosScenario, error) {
	scenarios, err := s.scenarioRepo.ListByNamespace(ctx, namespace)
	if err != nil {
		return nil, &domain.AppError{Message: "failed to list scenarios", Code: 500, Cause: err}
	}
	return scenarios, nil
}

func (s *OrchestratorService) DeleteScenario(ctx context.Context, scenarioID string) error {
	if s.coordinator.ActiveRunsCount() > 0 {
		return &domain.AppError{Message: "cannot delete scenario with active runs", Code: 409}
	}

	if err := s.scenarioRepo.Delete(ctx, scenarioID); err != nil {
		return &domain.AppError{Message: err.Error(), Code: 404}
	}

	s.logger.Info("chaos scenario deleted",
		zap.String("scenario_id", scenarioID),
	)

	return nil
}

func (s *OrchestratorService) GetActiveRunsCount() int {
	return s.coordinator.ActiveRunsCount()
}

func (s *OrchestratorService) GetSupportedInjectors() []string {
	return s.injectorReg.ListTypes()
}

func (s *OrchestratorService) GetMetrics() *domain.ChaosMetrics {
	type metricsGetter interface {
		GetMetrics() *domain.ChaosMetrics
	}
	if getter, ok := s.coordinator.(metricsGetter); ok {
		return getter.GetMetrics()
	}
	return &domain.ChaosMetrics{}
}

func (s *OrchestratorService) GetExecutionTiming(ctx context.Context, runID string) (*domain.ExecutionMetrics, error) {
	type timingGetter interface {
		GetExecutionTiming(runID string) ([]*domain.ExecutionTiming, error)
	}
	getter, ok := s.coordinator.(timingGetter)
	if !ok {
		return nil, &domain.AppError{Message: "timing not supported", Code: 501}
	}

	timings, err := getter.GetExecutionTiming(runID)
	if err != nil {
		return nil, &domain.AppError{Message: err.Error(), Code: 404}
	}

	phaseTimings := make(map[string]time.Duration)
	var totalDuration time.Duration
	for _, t := range timings {
		phaseTimings[t.Phase] = t.Duration
		if t.Phase == "total" {
			totalDuration = t.Duration
		}
	}

	return &domain.ExecutionMetrics{
		RunID:         runID,
		PhaseTimings:  phaseTimings,
		TotalDuration: totalDuration,
		Timings:       timings,
	}, nil
}

func (s *OrchestratorService) GetPrometheusExporter() *metrics.PrometheusExporter {
	type exporterGetter interface {
		GetPrometheusExporter() *metrics.PrometheusExporter
	}
	if getter, ok := s.coordinator.(exporterGetter); ok {
		return getter.GetPrometheusExporter()
	}
	return nil
}

func (s *OrchestratorService) GetMetricsCollector() ports.MetricsCollector {
	type collectorGetter interface {
		GetMetricsCollector() ports.MetricsCollector
	}
	if getter, ok := s.coordinator.(collectorGetter); ok {
		return getter.GetMetricsCollector()
	}
	return nil
}
