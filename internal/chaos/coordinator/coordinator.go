package coordinator

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"github.com/chaoslab/platform/internal/chaos/metrics"
	"go.uber.org/zap"
)

type activeRunContext struct {
	runID        string
	scenario     *domain.ChaosScenario
	scope        *domain.InjectionScope
	injector     ports.ChaosInjector
	cancel       context.CancelFunc
	startedAt    time.Time
	autoRollback bool
}

type Coordinator struct {
	mu                sync.RWMutex
	activeRuns        map[string]*activeRunContext
	scenarioRepo      ports.ScenarioRepository
	runRepo           ports.RunInstanceRepository
	injectorReg       ports.InjectorRegistry
	metricsCollector  ports.MetricsCollector
	prometheusExporter *metrics.PrometheusExporter
	timings           map[string][]*domain.ExecutionTiming
	logger            *zap.Logger
}

func NewExecutionCoordinator(
	scenarioRepo ports.ScenarioRepository,
	runRepo ports.RunInstanceRepository,
	injectorReg ports.InjectorRegistry,
	logger *zap.Logger,
) ports.ExecutionCoordinator {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &Coordinator{
		activeRuns:         make(map[string]*activeRunContext),
		scenarioRepo:       scenarioRepo,
		runRepo:            runRepo,
		injectorReg:        injectorReg,
		metricsCollector:   metrics.NewMetricsCollector(),
		prometheusExporter: metrics.NewPrometheusExporter(),
		timings:            make(map[string][]*domain.ExecutionTiming),
		logger:             logger,
	}
}

func NewExecutionCoordinatorWithMetrics(
	scenarioRepo ports.ScenarioRepository,
	runRepo ports.RunInstanceRepository,
	injectorReg ports.InjectorRegistry,
	metricsCollector ports.MetricsCollector,
	prometheusExporter *metrics.PrometheusExporter,
	logger *zap.Logger,
) ports.ExecutionCoordinator {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &Coordinator{
		activeRuns:         make(map[string]*activeRunContext),
		scenarioRepo:       scenarioRepo,
		runRepo:            runRepo,
		injectorReg:        injectorReg,
		metricsCollector:   metricsCollector,
		prometheusExporter: prometheusExporter,
		timings:            make(map[string][]*domain.ExecutionTiming),
		logger:             logger,
	}
}

func (c *Coordinator) Start(ctx context.Context, scenario *domain.ChaosScenario, scope *domain.InjectionScope) (*domain.RunInstance, error) {
	if !scenario.Enabled {
		return nil, fmt.Errorf("scenario is disabled")
	}
	if scope == nil {
		return nil, fmt.Errorf("injection scope is required")
	}
	if scope.Percentage < 0 || scope.Percentage > 100 {
		return nil, fmt.Errorf("percentage must be between 0 and 100")
	}

	injector, err := c.injectorReg.Get(scenario.InjectorType)
	if err != nil {
		return nil, err
	}

	runID := fmt.Sprintf("run_%d", time.Now().UnixNano())
	runCtx, cancel := context.WithCancel(ctx)

	run := &domain.RunInstance{
		RunID:     runID,
		EntityID:  scenario.ScenarioID,
		Phase:     "initializing",
		Progress:  0.0,
		StartedAt: time.Now(),
	}

	activeCtx := &activeRunContext{
		runID:        runID,
		scenario:     scenario,
		scope:        scope,
		injector:     injector,
		cancel:       cancel,
		startedAt:    time.Now(),
		autoRollback: scenario.AutoRollback,
	}

	if err := c.runRepo.Save(ctx, run); err != nil {
		return nil, err
	}

	c.mu.Lock()
	c.activeRuns[runID] = activeCtx
	c.mu.Unlock()

	go c.executeInjection(runCtx, activeCtx, run, injector, scenario, scope)

	c.logger.Info("chaos scenario execution started",
		zap.String("run_id", runID),
		zap.String("scenario_id", scenario.ScenarioID),
		zap.String("scenario_name", scenario.Name),
		zap.Any("scope", scope),
	)

	return run, nil
}

func (c *Coordinator) executeInjection(
	ctx context.Context,
	activeCtx *activeRunContext,
	run *domain.RunInstance,
	injector ports.ChaosInjector,
	scenario *domain.ChaosScenario,
	scope *domain.InjectionScope,
) {
	runID := run.RunID
	injectorType := scenario.InjectorType
	scenarioID := scenario.ScenarioID

	runTimings := make([]*domain.ExecutionTiming, 0)

	c.recordTiming(runID, "initialize", activeCtx.startedAt, time.Now(), &runTimings)

	c.runRepo.UpdatePhase(runID, "injecting", 0.1)
	c.recordPhase(scenarioID, "injecting")
	c.recordInjectionStarted(injectorType)

	injectStart := time.Now()
	injectErr := injector.Inject(ctx, scope, scenario.Parameters)
	injectDuration := time.Since(injectStart)

	c.recordTiming(runID, "inject", injectStart, time.Now(), &runTimings)
	c.recordInjectionDuration(injectorType, injectDuration)
	c.recordMetricsTiming("inject", injectDuration, map[string]string{
		"injector_type": injectorType,
		"success":       fmt.Sprintf("%t", injectErr == nil),
		"scenario_id":   scenarioID,
	})

	if injectErr != nil {
		c.runRepo.UpdateError(runID, fmt.Sprintf("injection failed: %v", injectErr))
		c.recordInjectionCompleted(injectorType, false)
		c.recordCounter("injections_failed", 1, map[string]string{"injector_type": injectorType})
		c.logger.Error("chaos injection failed",
			zap.String("run_id", runID),
			zap.Error(injectErr),
		)
		c.saveTimings(runID, runTimings)
		return
	}

	c.recordInjectionCompleted(injectorType, true)
	c.recordCounter("injections_completed", 1, map[string]string{"injector_type": injectorType})

	c.runRepo.UpdatePhase(runID, "running", 0.5)
	c.recordPhase(scenarioID, "running")

	runningStart := time.Now()
	select {
	case <-ctx.Done():
		c.logger.Info("chaos injection cancelled",
			zap.String("run_id", runID),
		)
	case <-time.After(scenario.Duration):
		c.logger.Info("chaos injection duration completed",
			zap.String("run_id", runID),
			zap.Duration("duration", scenario.Duration),
		)
	}
	c.recordTiming(runID, "running", runningStart, time.Now(), &runTimings)

	c.runRepo.UpdatePhase(runID, "rolling_back", 0.8)
	c.recordPhase(scenarioID, "rolling_back")
	c.recordRollbackStarted(injectorType)

	rollbackCtx, rollbackCancel := context.WithTimeout(context.Background(), scenario.RollbackTimeout)
	defer rollbackCancel()

	rollbackStart := time.Now()
	rollbackErr := injector.Rollback(rollbackCtx, runID)
	rollbackDuration := time.Since(rollbackStart)

	c.recordTiming(runID, "rollback", rollbackStart, time.Now(), &runTimings)
	c.recordRollbackDuration(injectorType, rollbackDuration)
	c.recordMetricsTiming("rollback", rollbackDuration, map[string]string{
		"injector_type": injectorType,
		"success":       fmt.Sprintf("%t", rollbackErr == nil),
		"scenario_id":   scenarioID,
	})

	if rollbackErr != nil {
		c.recordRollbackCompleted(injectorType, false)
		c.recordCounter("rollbacks_failed", 1, map[string]string{"injector_type": injectorType})
		c.logger.Error("chaos rollback failed",
			zap.String("run_id", runID),
			zap.Error(rollbackErr),
		)
	} else {
		c.recordRollbackCompleted(injectorType, true)
		c.recordCounter("rollbacks_completed", 1, map[string]string{"injector_type": injectorType})
	}

	c.runRepo.UpdatePhase(runID, "completed", 1.0)
	c.recordPhase(scenarioID, "completed")

	c.recordTiming(runID, "total", activeCtx.startedAt, time.Now(), &runTimings)

	c.mu.Lock()
	delete(c.activeRuns, runID)
	c.mu.Unlock()

	c.updateActiveRuns()

	c.recordCounter("total_runs", 1, map[string]string{"scenario_id": scenarioID})

	c.saveTimings(runID, runTimings)

	c.logger.Info("chaos scenario execution completed",
		zap.String("run_id", runID),
		zap.Duration("duration", time.Since(activeCtx.startedAt)),
	)
}

func (c *Coordinator) recordTiming(runID, phase string, start, end time.Time, timings *[]*domain.ExecutionTiming) {
	*timings = append(*timings, &domain.ExecutionTiming{
		Phase:     phase,
		StartTime: start,
		EndTime:   end,
		Duration:  end.Sub(start),
	})
}

func (c *Coordinator) saveTimings(runID string, timings []*domain.ExecutionTiming) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.timings[runID] = timings
	if len(c.timings) > 1000 {
		var oldestID string
		var oldestTime time.Time
		first := true
		for id, t := range c.timings {
			if len(t) > 0 {
				if first || t[0].StartTime.Before(oldestTime) {
					oldestID = id
					oldestTime = t[0].StartTime
					first = false
				}
			}
		}
		if oldestID != "" {
			delete(c.timings, oldestID)
		}
	}
}

func (c *Coordinator) recordPhase(scenarioID, phase string) {
	if c.prometheusExporter != nil {
		c.prometheusExporter.RecordPhase(scenarioID, phase)
	}
}

func (c *Coordinator) recordInjectionStarted(injectorType string) {
	c.recordCounter("injections_started", 1, map[string]string{"injector_type": injectorType})
	if c.prometheusExporter != nil {
		c.prometheusExporter.RecordInjectionStarted(injectorType)
	}
}

func (c *Coordinator) recordInjectionCompleted(injectorType string, success bool) {
	if c.prometheusExporter != nil {
		c.prometheusExporter.RecordInjectionCompleted(injectorType, success)
	}
}

func (c *Coordinator) recordRollbackStarted(injectorType string) {
	c.recordCounter("rollbacks_started", 1, map[string]string{"injector_type": injectorType})
	if c.prometheusExporter != nil {
		c.prometheusExporter.RecordRollbackStarted(injectorType)
	}
}

func (c *Coordinator) recordRollbackCompleted(injectorType string, success bool) {
	if c.prometheusExporter != nil {
		c.prometheusExporter.RecordRollbackCompleted(injectorType, success)
	}
}

func (c *Coordinator) recordInjectionDuration(injectorType string, duration time.Duration) {
	if c.prometheusExporter != nil {
		c.prometheusExporter.RecordInjectionDuration(injectorType, duration)
	}
}

func (c *Coordinator) recordRollbackDuration(injectorType string, duration time.Duration) {
	if c.prometheusExporter != nil {
		c.prometheusExporter.RecordRollbackDuration(injectorType, duration)
	}
}

func (c *Coordinator) recordMetricsTiming(operation string, duration time.Duration, labels map[string]string) {
	if c.metricsCollector != nil {
		c.metricsCollector.RecordTiming(operation, duration, labels)
	}
}

func (c *Coordinator) recordCounter(name string, value int64, labels map[string]string) {
	if c.metricsCollector != nil {
		c.metricsCollector.RecordCounter(name, value, labels)
	}
}

func (c *Coordinator) updateActiveRuns() {
	count := c.ActiveRunsCount()
	if c.metricsCollector != nil {
		c.metricsCollector.RecordGauge("active_runs", float64(count), nil)
	}
	if c.prometheusExporter != nil {
		c.prometheusExporter.SetActiveRuns(count)
	}
}

func (c *Coordinator) GetExecutionTiming(runID string) ([]*domain.ExecutionTiming, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	timings, exists := c.timings[runID]
	if !exists {
		return nil, fmt.Errorf("timings for run %s not found", runID)
	}
	result := make([]*domain.ExecutionTiming, len(timings))
	copy(result, timings)
	return result, nil
}

func (c *Coordinator) GetMetrics() *domain.ChaosMetrics {
	if c.metricsCollector == nil {
		return &domain.ChaosMetrics{}
	}
	return c.metricsCollector.GetMetrics()
}

func (c *Coordinator) GetPrometheusExporter() *metrics.PrometheusExporter {
	return c.prometheusExporter
}

func (c *Coordinator) GetMetricsCollector() ports.MetricsCollector {
	return c.metricsCollector
}

func (c *Coordinator) Cancel(ctx context.Context, runID string) error {
	c.mu.RLock()
	activeCtx, exists := c.activeRuns[runID]
	c.mu.RUnlock()

	if !exists {
		return fmt.Errorf("active run %s not found", runID)
	}

	activeCtx.cancel()
	c.runRepo.UpdatePhase(runID, "cancelled", 1.0)

	c.logger.Info("chaos execution cancelled",
		zap.String("run_id", runID),
	)

	return nil
}

func (c *Coordinator) GetStatus(ctx context.Context, runID string) (*domain.RunInstance, error) {
	return c.runRepo.FindByID(ctx, runID)
}

func (c *Coordinator) ActiveRunsCount() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.activeRuns)
}
