package datquality

import (
	"context"
	"fmt"
	"sync"
	"time"

	"gorm.io/gorm"
	applogger "session172/internal/logger"
	"session172/pkg/models"
)

type StrategyType string

const (
	StrategyStrict   StrategyType = "strict"
	StrategyStandard StrategyType = "standard"
	StrategyRelaxed  StrategyType = "relaxed"
	StrategyCustom   StrategyType = "custom"
)

type ExecutionStrategy interface {
	Name() string
	Type() StrategyType
	PreExecute(ctx context.Context, rule *models.DataQualityRule) error
	Execute(ctx context.Context, db *gorm.DB, rule *models.DataQualityRule, validator RuleValidator) (*models.QualityCheckResult, error)
	PostExecute(ctx context.Context, result *models.QualityCheckResult) error
	HandleError(ctx context.Context, err error) (*models.QualityCheckResult, error)
	ShouldExecute(rule *models.DataQualityRule) bool
}

type StrategyManager struct {
	mu              sync.RWMutex
	strategies      map[StrategyType]ExecutionStrategy
	activeStrategy  StrategyType
	ruleStrategies  map[string]StrategyType
	fallbackStrategy StrategyType
}

type BaseStrategy struct {
	name string
}

type StrictStrategy struct{ BaseStrategy }
type StandardStrategy struct{ BaseStrategy }
type RelaxedStrategy struct{ BaseStrategy }
type CustomStrategy struct {
	BaseStrategy
	PreExecuteFunc  func(ctx context.Context, rule *models.DataQualityRule) error
	PostExecuteFunc func(ctx context.Context, result *models.QualityCheckResult) error
	HandleErrorFunc func(ctx context.Context, err error) (*models.QualityCheckResult, error)
}

var (
	strategyManagerInstance *StrategyManager
	strategyManagerOnce     sync.Once
)

func NewStrategyManager() *StrategyManager {
	strategyManagerOnce.Do(func() {
		sm := &StrategyManager{
			strategies:       make(map[StrategyType]ExecutionStrategy),
			activeStrategy:   StrategyStandard,
			ruleStrategies:   make(map[string]StrategyType),
			fallbackStrategy: StrategyStandard,
		}
		sm.registerDefaults()
		strategyManagerInstance = sm
	})
	return strategyManagerInstance
}

func GetStrategyManager() *StrategyManager {
	if strategyManagerInstance == nil {
		return NewStrategyManager()
	}
	return strategyManagerInstance
}

func (sm *StrategyManager) registerDefaults() {
	sm.strategies[StrategyStrict] = &StrictStrategy{
		BaseStrategy: BaseStrategy{name: "strict"},
	}
	sm.strategies[StrategyStandard] = &StandardStrategy{
		BaseStrategy: BaseStrategy{name: "standard"},
	}
	sm.strategies[StrategyRelaxed] = &RelaxedStrategy{
		BaseStrategy: BaseStrategy{name: "relaxed"},
	}
	sm.strategies[StrategyCustom] = &CustomStrategy{
		BaseStrategy: BaseStrategy{name: "custom"},
	}
}

func (sm *StrategyManager) SetStrategy(strategyType StrategyType) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if _, exists := sm.strategies[strategyType]; !exists {
		return fmt.Errorf("strategy not found: %s", strategyType)
	}

	oldStrategy := sm.activeStrategy
	sm.activeStrategy = strategyType
	applogger.Infof("Quality strategy changed: %s -> %s", oldStrategy, strategyType)
	return nil
}

func (sm *StrategyManager) GetStrategy() StrategyType {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.activeStrategy
}

func (sm *StrategyManager) SetRuleStrategy(ruleID string, strategyType StrategyType) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if _, exists := sm.strategies[strategyType]; !exists {
		return fmt.Errorf("strategy not found: %s", strategyType)
	}

	sm.ruleStrategies[ruleID] = strategyType
	applogger.Infof("Rule %s strategy set to: %s", ruleID, strategyType)
	return nil
}

func (sm *StrategyManager) GetRuleStrategy(ruleID string) StrategyType {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	if strategy, exists := sm.ruleStrategies[ruleID]; exists {
		return strategy
	}
	return sm.activeStrategy
}

func (sm *StrategyManager) RegisterStrategy(strategyType StrategyType, strategy ExecutionStrategy) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if _, exists := sm.strategies[strategyType]; exists {
		return fmt.Errorf("strategy already exists: %s", strategyType)
	}

	sm.strategies[strategyType] = strategy
	applogger.Infof("New strategy registered: %s", strategyType)
	return nil
}

func (sm *StrategyManager) UnregisterStrategy(strategyType StrategyType) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if strategyType == sm.activeStrategy {
		return fmt.Errorf("cannot unregister active strategy: %s", strategyType)
	}

	delete(sm.strategies, strategyType)

	for ruleID, s := range sm.ruleStrategies {
		if s == strategyType {
			sm.ruleStrategies[ruleID] = sm.fallbackStrategy
		}
	}

	applogger.Infof("Strategy unregistered: %s", strategyType)
	return nil
}

func (sm *StrategyManager) GetExecutionStrategy(ruleID string) (ExecutionStrategy, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	strategyType := sm.activeStrategy
	if s, exists := sm.ruleStrategies[ruleID]; exists {
		strategyType = s
	}

	strategy, exists := sm.strategies[strategyType]
	if !exists {
		strategy = sm.strategies[sm.fallbackStrategy]
	}

	return strategy, nil
}

func (sm *StrategyManager) GetAvailableStrategies() []StrategyType {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	strategies := make([]StrategyType, 0, len(sm.strategies))
	for s := range sm.strategies {
		strategies = append(strategies, s)
	}
	return strategies
}

func (sm *StrategyManager) SetFallbackStrategy(strategyType StrategyType) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if _, exists := sm.strategies[strategyType]; !exists {
		return fmt.Errorf("strategy not found: %s", strategyType)
	}

	sm.fallbackStrategy = strategyType
	applogger.Infof("Fallback strategy set to: %s", strategyType)
	return nil
}

func (b BaseStrategy) Name() string {
	return b.name
}

func (b BaseStrategy) PreExecute(ctx context.Context, rule *models.DataQualityRule) error {
	return nil
}

func (b BaseStrategy) PostExecute(ctx context.Context, result *models.QualityCheckResult) error {
	return nil
}

func (b BaseStrategy) ShouldExecute(rule *models.DataQualityRule) bool {
	return rule != nil && rule.Enabled
}

func (s *StrictStrategy) Type() StrategyType {
	return StrategyStrict
}

func (s *StrictStrategy) Execute(ctx context.Context, db *gorm.DB, rule *models.DataQualityRule, validator RuleValidator) (*models.QualityCheckResult, error) {
	result := &models.QualityCheckResult{}
	result, err := validator.Validate(ctx, db, rule, result)
	if err != nil {
		return nil, err
	}

	if result.ErrorRate > 0 {
		result.Status = "failed"
		result.Message = fmt.Sprintf("Strict mode: %.2f%% error rate exceeds zero tolerance", result.ErrorRate*100)
	} else {
		result.Status = "passed"
		result.Message = "All checks passed (strict mode)"
	}

	return result, nil
}

func (s *StrictStrategy) HandleError(ctx context.Context, err error) (*models.QualityCheckResult, error) {
	return nil, err
}

func (s *StandardStrategy) Type() StrategyType {
	return StrategyStandard
}

func (s *StandardStrategy) Execute(ctx context.Context, db *gorm.DB, rule *models.DataQualityRule, validator RuleValidator) (*models.QualityCheckResult, error) {
	result := &models.QualityCheckResult{}
	result, err := validator.Validate(ctx, db, rule, result)
	if err != nil {
		return nil, err
	}

	warnThreshold := 0.01
	errorThreshold := 0.05

	switch {
	case result.ErrorRate >= errorThreshold:
		result.Status = "failed"
		result.Message = fmt.Sprintf("Error rate %.2f%% exceeds threshold %.2f%%",
			result.ErrorRate*100, errorThreshold*100)
	case result.ErrorRate >= warnThreshold:
		result.Status = "warning"
		result.Message = fmt.Sprintf("Error rate %.2f%% exceeds warning threshold %.2f%%",
			result.ErrorRate*100, warnThreshold*100)
	default:
		result.Status = "passed"
		result.Message = "All checks passed"
	}

	return result, nil
}

func (s *StandardStrategy) HandleError(ctx context.Context, err error) (*models.QualityCheckResult, error) {
	return &models.QualityCheckResult{
		Status:  "failed",
		Message: fmt.Sprintf("Validation error: %v", err),
	}, nil
}

func (s *RelaxedStrategy) Type() StrategyType {
	return StrategyRelaxed
}

func (s *RelaxedStrategy) Execute(ctx context.Context, db *gorm.DB, rule *models.DataQualityRule, validator RuleValidator) (*models.QualityCheckResult, error) {
	result := &models.QualityCheckResult{}
	result, err := validator.Validate(ctx, db, rule, result)
	if err != nil {
		result.Status = "warning"
		result.Message = fmt.Sprintf("Validation skipped due to error: %v", err)
		return result, nil
	}

	errorThreshold := 0.10

	if result.ErrorRate >= errorThreshold {
		result.Status = "warning"
		result.Message = fmt.Sprintf("High error rate detected: %.2f%%", result.ErrorRate*100)
	} else {
		result.Status = "passed"
		result.Message = "Check passed (relaxed mode)"
	}

	return result, nil
}

func (s *RelaxedStrategy) HandleError(ctx context.Context, err error) (*models.QualityCheckResult, error) {
	return &models.QualityCheckResult{
		Status:  "warning",
		Message: fmt.Sprintf("Validation skipped: %v", err),
	}, nil
}

func (c *CustomStrategy) Type() StrategyType {
	return StrategyCustom
}

func (c *CustomStrategy) Execute(ctx context.Context, db *gorm.DB, rule *models.DataQualityRule, validator RuleValidator) (*models.QualityCheckResult, error) {
	result := &models.QualityCheckResult{}
	return validator.Validate(ctx, db, rule, result)
}

func (c *CustomStrategy) PreExecute(ctx context.Context, rule *models.DataQualityRule) error {
	if c.PreExecuteFunc != nil {
		return c.PreExecuteFunc(ctx, rule)
	}
	return nil
}

func (c *CustomStrategy) PostExecute(ctx context.Context, result *models.QualityCheckResult) error {
	if c.PostExecuteFunc != nil {
		return c.PostExecuteFunc(ctx, result)
	}
	return nil
}

func (c *CustomStrategy) HandleError(ctx context.Context, err error) (*models.QualityCheckResult, error) {
	if c.HandleErrorFunc != nil {
		return c.HandleErrorFunc(ctx, err)
	}
	return &models.QualityCheckResult{
		Status:  "failed",
		Message: fmt.Sprintf("Custom strategy error: %v", err),
	}, nil
}

func (sm *StrategyManager) ExecuteWithStrategy(ctx context.Context, engine *RuleEngine, ruleID string, db *gorm.DB) (*models.QualityCheckResult, error) {
	rule, exists := engine.GetRule(ruleID)
	if !exists {
		return nil, ErrRuleNotFound
	}

	strategy, err := sm.GetExecutionStrategy(ruleID)
	if err != nil {
		return nil, err
	}

	if !strategy.ShouldExecute(rule) {
		return &models.QualityCheckResult{
			ID:        ruleID,
			RuleID:    ruleID,
			Status:    "skipped",
			Message:   "Rule disabled or skipped by strategy",
			CheckedAt: time.Now(),
		}, nil
	}

	if err := strategy.PreExecute(ctx, rule); err != nil {
		return strategy.HandleError(ctx, err)
	}

	validator, exists := engine.validators[rule.RuleType]
	if !exists {
		return strategy.HandleError(ctx, fmt.Errorf("%w: %s", ErrUnsupportedType, rule.RuleType))
	}

	result, err := strategy.Execute(ctx, db, rule, validator)
	if err != nil {
		return strategy.HandleError(ctx, err)
	}

	if err := strategy.PostExecute(ctx, result); err != nil {
		applogger.Warnf("Post-execute hook failed: %v", err)
	}

	return result, nil
}
