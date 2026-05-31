package classification

import (
	"context"
	"time"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type DefaultClassifier struct {
	patternStore  PatternStore
	policyStore   PolicyStore
	classifier    DataClassifier
	applier       PolicyApplier
	scanner       DataScanner
	configManager *DynamicConfigManager
	logger        *zap.Logger
}

func NewDefaultClassifier() *DefaultClassifier {
	patternStore := NewInMemoryPatternStore()
	policyStore := NewInMemoryPolicyStore()
	fieldClassifier := NewRegexFieldClassifier(patternStore)
	classifier := NewDefaultDataClassifier(fieldClassifier, patternStore)
	logger := utils.GetLogger()
	applier := NewDefaultPolicyApplier(policyStore, logger)
	scanner := NewDefaultDataScanner(classifier, applier)
	configManager := NewDynamicConfigManager()

	return &DefaultClassifier{
		patternStore:  patternStore,
		policyStore:   policyStore,
		classifier:    classifier,
		applier:       applier,
		scanner:       scanner,
		configManager: configManager,
		logger:        logger,
	}
}

func (c *DefaultClassifier) Classify(ctx context.Context, data map[string]interface{}) (*interfaces.ClassificationResult, error) {
	result, err := c.classifier.Classify(ctx, data)
	if err != nil {
		return nil, err
	}

	c.logger.Info("Data classification completed",
		zap.String("data_id", result.DataID),
		zap.String("sensitivity", result.Sensitivity),
		zap.Int("level", result.Level),
	)

	return result, nil
}

func (c *DefaultClassifier) ApplyPolicy(ctx context.Context, result *interfaces.ClassificationResult) error {
	return c.applier.ApplyPolicy(ctx, result)
}

func (c *DefaultClassifier) Scan(ctx context.Context, data []map[string]interface{}) ([]*interfaces.ClassificationResult, error) {
	return c.scanner.Scan(ctx, data)
}

func (c *DefaultClassifier) AddPattern(name, regexStr, sensitivity, category string, level int) error {
	return c.patternStore.Add(name, regexStr, sensitivity, category, level)
}

func (c *DefaultClassifier) RemovePattern(name string) {
	c.patternStore.Remove(name)
}

func (c *DefaultClassifier) SetPolicy(level int, action, description string) {
	c.policyStore.Set(level, action, description)
}

func (c *DefaultClassifier) GetPatternStore() PatternStore {
	return c.patternStore
}

func (c *DefaultClassifier) GetPolicyStore() PolicyStore {
	return c.policyStore
}

func (c *DefaultClassifier) GetConfigManager() *DynamicConfigManager {
	return c.configManager
}

func (c *DefaultClassifier) LoadScenario(scenario *ClassificationScenario) error {
	source := NewMemoryConfigSource(scenario)
	if err := c.configManager.AddSource(source); err != nil {
		return err
	}
	return c.configManager.ApplyToClassifier(c)
}

func (c *DefaultClassifier) SwitchScenario(name string) error {
	if err := c.configManager.SetActiveScenario(name); err != nil {
		return err
	}
	return c.configManager.ApplyToClassifier(c)
}

func (c *DefaultClassifier) GetActiveScenario() *ClassificationScenario {
	return c.configManager.GetActiveScenario()
}

func (c *DefaultClassifier) ListScenarios() []string {
	return c.configManager.ListScenarios()
}

func (c *DefaultClassifier) OnScenarioChange(fn func(string, *ClassificationScenario)) {
	c.configManager.AddListener(func(name string, scenario *ClassificationScenario) {
		c.logger.Info("Scenario configuration updated",
			zap.String("scenario", name),
		)
		_ = c.configManager.ApplyToClassifier(c)
		fn(name, scenario)
	})
}

func (c *DefaultClassifier) LoadScenarioFromFile(path, format string, watchInterval time.Duration) error {
	source := NewFileConfigSource(path, format, watchInterval)
	if err := c.configManager.AddSource(source); err != nil {
		return err
	}
	return c.configManager.ApplyToClassifier(c)
}
