package differentialprivacy

import (
	"context"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type DefaultPrivacyInjector struct {
	budgetManager   BudgetManager
	noiseGenerator NoiseGenerator
	resultInjector ResultInjector
	calculator     BudgetCalculator
	strategyMgr   *StrategyManager
	logger         *zap.Logger
}

func NewDefaultPrivacyInjector(config *BudgetConfig) *DefaultPrivacyInjector {
	logger := utils.GetLogger()
	return &DefaultPrivacyInjector{
		budgetManager:  NewDefaultBudgetManager(config),
		noiseGenerator: NewDefaultNoiseGenerator(),
		resultInjector: NewDefaultResultInjector(),
		calculator:    NewDefaultBudgetCalculator(),
		strategyMgr:   NewStrategyManager(logger),
		logger:        logger,
	}
}

func (d *DefaultPrivacyInjector) InjectNoise(ctx context.Context, queryResult *interfaces.QueryResult) (*interfaces.QueryResult, error) {
	activeStrategy := d.strategyMgr.GetActive()

	estimatedEpsilon, estimatedDelta := activeStrategy.GetBudgetEstimate(queryResult)
	if estimatedEpsilon <= 0 {
		estimatedEpsilon = 1.0
	}
	if estimatedDelta <= 0 {
		estimatedDelta = 1e-5
	}

	if err := d.budgetManager.Consume(ctx, estimatedEpsilon, estimatedDelta); err != nil {
		return nil, err
	}

	result, err := d.strategyMgr.Process(ctx, queryResult, d.noiseGenerator)
	if err != nil {
		return nil, err
	}

	d.logger.Info("Noise injected successfully",
		zap.String("strategy", activeStrategy.Name()),
		zap.Float64("epsilon_used", estimatedEpsilon),
		zap.Float64("delta_used", estimatedDelta),
		zap.String("noise_type", result.NoiseType),
		zap.Int("rows_processed", len(result.Data)),
	)

	return result, nil
}

func (d *DefaultPrivacyInjector) ConsumeBudget(ctx context.Context, budget float64) error {
	return d.budgetManager.Consume(ctx, budget, 0)
}

func (d *DefaultPrivacyInjector) GetRemainingBudget(ctx context.Context) float64 {
	remaining, _ := d.budgetManager.Remaining()
	return remaining
}

func (d *DefaultPrivacyInjector) ResetBudget(ctx context.Context) error {
	d.budgetManager.Reset()
	return nil
}

func (d *DefaultPrivacyInjector) GenerateAdaptiveNoise(value float64, sensitivity, epsilon, delta float64, confidence float64) float64 {
	return d.noiseGenerator.GenerateAdaptive(value, sensitivity, epsilon, delta, confidence)
}

func (d *DefaultPrivacyInjector) CalculateBudgetUsage(operations int, perOpEpsilon float64) (float64, error) {
	return d.calculator.CalculateUsage(operations, perOpEpsilon)
}

func (d *DefaultPrivacyInjector) OptimizeBudgetAllocation(totalBudget float64, priorities map[string]float64) (map[string]float64, error) {
	return d.calculator.OptimizeAllocation(totalBudget, priorities)
}

func (d *DefaultPrivacyInjector) GetBudgetManager() BudgetManager {
	return d.budgetManager
}

func (d *DefaultPrivacyInjector) GetNoiseGenerator() NoiseGenerator {
	return d.noiseGenerator
}

func (d *DefaultPrivacyInjector) SetPrivacyStrategy(name string) error {
	return d.strategyMgr.SetActive(name)
}

func (d *DefaultPrivacyInjector) GetActiveStrategy() string {
	return d.strategyMgr.GetActiveName()
}

func (d *DefaultPrivacyInjector) ListStrategies() []string {
	return d.strategyMgr.List()
}

func (d *DefaultPrivacyInjector) RegisterStrategy(strategy PrivacyStrategy) error {
	return d.strategyMgr.Register(strategy)
}

func (d *DefaultPrivacyInjector) OnStrategyChange(listener StrategyChangeListener) {
	d.strategyMgr.AddListener(listener)
}

func (d *DefaultPrivacyInjector) GetStrategyManager() *StrategyManager {
	return d.strategyMgr
}
