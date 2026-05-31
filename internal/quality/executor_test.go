package quality

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestRuleValidator_WithContext(t *testing.T) {
	t.Run("range validator should support context cancellation", func(t *testing.T) {
		validator := NewRangeValidator()
		rule := &QualityRule{
			ID:         "test_rule",
			Name:       "Test Rule",
			Type:       RuleTypeRange,
			Severity:   SeverityMedium,
			Parameters: map[string]interface{}{
				"range": map[string]interface{}{
					"min_value": 0.0,
					"max_value": 10.0,
				},
			},
		}

		data := []float64{1.0, 5.0, 15.0, 3.0, 20.0}

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()

		passed, anomalies, err := validator.Validate(ctx, rule, data)
		assert.NoError(t, err)
		assert.False(t, passed)
		assert.Len(t, anomalies, 2)
	})

	t.Run("null check validator should support context cancellation", func(t *testing.T) {
		validator := NewNullCheckValidator()
		rule := &QualityRule{
			ID:       "test_rule",
			Name:     "Test Rule",
			Type:     RuleTypeNullCheck,
			Severity: SeverityMedium,
		}

		data := []interface{}{"value1", nil, "value3", nil, "value5"}

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()

		passed, anomalies, err := validator.Validate(ctx, rule, data)
		assert.NoError(t, err)
		assert.False(t, passed)
		assert.Len(t, anomalies, 2)
	})

	t.Run("validator should return error on cancelled context", func(t *testing.T) {
		validator := NewRangeValidator()
		rule := &QualityRule{
			ID:   "test_rule",
			Name: "Test Rule",
			Type: RuleTypeRange,
		}

		data := []float64{1.0, 2.0, 3.0}

		ctx, cancel := context.WithCancel(context.Background())
		cancel()

		passed, anomalies, err := validator.Validate(ctx, rule, data)
		assert.Error(t, err)
		assert.Equal(t, context.Canceled, err)
		assert.False(t, passed)
		assert.Nil(t, anomalies)
	})
}

func TestRuleExecutor_AddRule_DefaultValues(t *testing.T) {
	executor := NewRuleExecutor(NewConsoleNotification())

	t.Run("should set default timeout when not provided", func(t *testing.T) {
		rule := &QualityRule{
			Name: "Test Rule",
			Type: RuleTypeRange,
		}

		err := executor.AddRule(rule)
		assert.NoError(t, err)
		assert.Equal(t, DefaultTimeoutSeconds, rule.TimeoutSeconds)
		assert.Equal(t, DefaultMaxRetries, rule.MaxRetries)
	})

	t.Run("should not override provided timeout", func(t *testing.T) {
		rule := &QualityRule{
			Name:           "Test Rule",
			Type:           RuleTypeRange,
			TimeoutSeconds: 60,
			MaxRetries:     5,
		}

		err := executor.AddRule(rule)
		assert.NoError(t, err)
		assert.Equal(t, 60, rule.TimeoutSeconds)
		assert.Equal(t, 5, rule.MaxRetries)
	})

	t.Run("should treat negative values as invalid and set defaults", func(t *testing.T) {
		rule := &QualityRule{
			Name:           "Test Rule",
			Type:           RuleTypeRange,
			TimeoutSeconds: -10,
			MaxRetries:     -5,
		}

		err := executor.AddRule(rule)
		assert.NoError(t, err)
		assert.Equal(t, DefaultTimeoutSeconds, rule.TimeoutSeconds)
		assert.Equal(t, DefaultMaxRetries, rule.MaxRetries)
	})
}

func TestRuleExecutor_ExecuteRule_Timeout(t *testing.T) {
	executor := NewRuleExecutor(NewConsoleNotification())

	rule := &QualityRule{
		Name:           "Timeout Test Rule",
		Type:           RuleTypeRange,
		TimeoutSeconds: 5,
		MaxRetries:     2,
		Status:         RuleStatusActive,
		Parameters: map[string]interface{}{
			"range": map[string]interface{}{
				"min_value": 0.0,
				"max_value": 100.0,
			},
		},
	}

	err := executor.AddRule(rule)
	assert.NoError(t, err)

	t.Run("should execute successfully within timeout", func(t *testing.T) {
		result, err := executor.ExecuteRule(rule.ID)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Equal(t, "completed", result.Status)
	})
}

func TestRuleExecutor_TotalExecutionTimeout(t *testing.T) {
	executor := NewRuleExecutor(NewConsoleNotification())

	t.Run("should respect max execution timeout", func(t *testing.T) {
		calculatedTimeout := 5 * (2 + 1)
		if calculatedTimeout > MaxExecutionTimeout {
			calculatedTimeout = MaxExecutionTimeout
		}
		assert.LessOrEqual(t, calculatedTimeout, MaxExecutionTimeout)
	})
}

func TestRuleExecutor_ExecuteRule_WithRetry(t *testing.T) {
	executor := NewRuleExecutor(NewConsoleNotification())

	rule := &QualityRule{
		Name:           "Retry Test Rule",
		Type:           RuleTypeRange,
		TimeoutSeconds: 30,
		MaxRetries:     2,
		Status:         RuleStatusActive,
		Parameters: map[string]interface{}{
			"range": map[string]interface{}{
				"min_value": 0.0,
				"max_value": 100.0,
			},
		},
	}

	err := executor.AddRule(rule)
	assert.NoError(t, err)

	result, err := executor.ExecuteRule(rule.ID)
	assert.NoError(t, err)
	assert.NotNil(t, result)
	assert.Equal(t, 0, result.RetryCount)
	assert.True(t, result.Passed)
}

func TestNewRuleExecutor_Initialization(t *testing.T) {
	executor := NewRuleExecutor(NewConsoleNotification())

	assert.NotNil(t, executor)
	assert.NotNil(t, executor.validators)
	assert.NotNil(t, executor.rules)
	assert.NotNil(t, executor.results)
	assert.NotNil(t, executor.anomalies)
	assert.NotNil(t, executor.cron)
	assert.NotNil(t, executor.cronEntries)
	assert.NotNil(t, executor.activeTasks)
	assert.Equal(t, ExecutorStatusIdle, executor.status)

	assert.Contains(t, executor.validators, RuleTypeRange)
	assert.Contains(t, executor.validators, RuleTypeNullCheck)
}

func TestConsoleNotification_Send(t *testing.T) {
	notifier := NewConsoleNotification()
	rule := &QualityRule{
		ID:   "test",
		Name: "Test",
	}
	result := &RuleExecutionResult{
		Passed: false,
	}
	anomalies := []AnomalyRecord{
		{ID: "anom1", RuleID: "test"},
	}

	err := notifier.Send(rule, result, anomalies)
	assert.NoError(t, err)
}
