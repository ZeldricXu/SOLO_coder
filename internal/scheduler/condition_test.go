package scheduler

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/solocoder/cloudci/internal/common/types"
)

func TestConditionEvaluator_BasicComparisons(t *testing.T) {
	t.Parallel()
	evaluator := NewConditionEvaluator()

	tests := []struct {
		name     string
		expr     string
		expected bool
	}{
		{"true literal", "true", true},
		{"false literal", "false", false},
		{"string equals", "'hello' == 'hello'", true},
		{"string not equals", "'hello' != 'world'", true},
		{"int equals", "42 == 42", true},
		{"int greater", "100 > 50", true},
		{"int less", "50 < 100", true},
		{"int greater or equal", "100 >= 100", true},
		{"int less or equal", "50 <= 50", true},
		{"float compare", "3.14 > 2.718", true},
		{"string regex match", "'main' =~ '^ma'", true},
		{"string regex no match", "'main' !~ '^dev'", true},
		{"contains", "'hello world' contains 'world'", true},
		{"startsWith", "'feature/login' startsWith 'feature/'", true},
		{"endsWith", "'main.go' endsWith '.go'", true},
		{"in operator", "'prod' in 'production,staging,prod'", true},
		{"not in operator", "'dev' not in 'prod,staging'", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ctx := &EvaluationContext{}
			result, err := evaluator.Evaluate(tt.expr, ctx)
			require.NoError(t, err)
			assert.Equal(t, tt.expected, result, "expr: %s", tt.expr)
		})
	}
}

func TestConditionEvaluator_LogicalOperators(t *testing.T) {
	t.Parallel()
	evaluator := NewConditionEvaluator()

	tests := []struct {
		name     string
		expr     string
		expected bool
	}{
		{"and true", "true && true", true},
		{"and false", "true && false", false},
		{"or true", "false || true", true},
		{"or false", "false || false", false},
		{"not true", "!false", true},
		{"not false", "!true", false},
		{"complex 1", "(true || false) && true", true},
		{"complex 2", "!(false && true) || false", true},
		{"complex 3", "'a' == 'a' && 1 < 2", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ctx := &EvaluationContext{}
			result, err := evaluator.Evaluate(tt.expr, ctx)
			require.NoError(t, err)
			assert.Equal(t, tt.expected, result, "expr: %s", tt.expr)
		})
	}
}

func TestConditionEvaluator_EmptyCondition(t *testing.T) {
	t.Parallel()
	evaluator := NewConditionEvaluator()

	result, err := evaluator.Evaluate("", &EvaluationContext{})
	require.NoError(t, err)
	assert.True(t, result)

	result, err = evaluator.Evaluate("   ", &EvaluationContext{})
	require.NoError(t, err)
	assert.True(t, result)
}

func TestConditionEvaluator_EventFields(t *testing.T) {
	t.Parallel()
	evaluator := NewConditionEvaluator()

	ctx := &EvaluationContext{
		Event: &types.InternalEvent{
			ProjectID:   "proj-123",
			Commit:      "abc123def",
			Branch:      "main",
			Tag:         "v1.0.0",
			Message:     "feat: add new feature",
			Author:      "John Doe",
			AuthorEmail: "john@example.com",
			EventSource: types.EventSourceGitHub,
			EventType:   types.EventTypePush,
		},
	}

	tests := []struct {
		name     string
		expr     string
		expected bool
	}{
		{"branch equals", "event.branch == 'main'", true},
		{"tag starts with v", "event.tag startsWith 'v'", true},
		{"message contains feat", "event.message contains 'feat'", true},
		{"source equals", "event.source == 'github'", true},
		{"commit length", "event.commit == 'abc123def'", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, err := evaluator.Evaluate(tt.expr, ctx)
			require.NoError(t, err)
			assert.Equal(t, tt.expected, result, "expr: %s", tt.expr)
		})
	}
}

func TestConditionEvaluator_StageResults(t *testing.T) {
	t.Parallel()
	evaluator := NewConditionEvaluator()

	ctx := &EvaluationContext{
		StageResults: map[string]StageResult{
			"build": {
				Status:   types.StageStatusSuccess,
				ExitCode: 0,
				Output: map[string]string{
					"artifacts": "5",
					"coverage":  "85.5",
				},
			},
			"test": {
				Status:   types.StageStatusSuccess,
				ExitCode: 0,
			},
			"lint": {
				Status:   types.StageStatusFailed,
				ExitCode: 1,
			},
		},
	}

	tests := []struct {
		name     string
		expr     string
		expected bool
	}{
		{"build success", "stages.build.success", true},
		{"build not failed", "!stages.build.failed", true},
		{"lint failed", "stages.lint.failed", true},
		{"lint exit code", "stages.lint.exit_code == 1", true},
		{"build output artifacts", "stages.build.artifacts == '5'", true},
		{"build coverage compare", "stages.build.coverage > '80'", true},
		{"complex: build success and test success", "stages.build.success && stages.test.success", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, err := evaluator.Evaluate(tt.expr, ctx)
			require.NoError(t, err)
			assert.Equal(t, tt.expected, result, "expr: %s", tt.expr)
		})
	}
}

func TestConditionEvaluator_Variables(t *testing.T) {
	t.Parallel()
	evaluator := NewConditionEvaluator()

	ctx := &EvaluationContext{
		Variables: map[string]string{
			"ENV":         "production",
			"DEPLOY_ENABLED": "true",
			"REGION":      "us-east-1",
		},
	}

	tests := []struct {
		name     string
		expr     string
		expected bool
	}{
		{"env equals", "env.ENV == 'production'", true},
		{"deploy enabled", "env.DEPLOY_ENABLED == 'true'", true},
		{"complex condition", "env.ENV == 'production' && env.DEPLOY_ENABLED == 'true'", true},
		{"region contains", "env.REGION contains 'us-'", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, err := evaluator.Evaluate(tt.expr, ctx)
			require.NoError(t, err)
			assert.Equal(t, tt.expected, result, "expr: %s", tt.expr)
		})
	}
}

func TestConditionEvaluator_RealWorldScenarios(t *testing.T) {
	t.Parallel()
	evaluator := NewConditionEvaluator()

	ctx := &EvaluationContext{
		Event: &types.InternalEvent{
			Branch:      "feature/new-ui",
			EventType:   types.EventTypePullRequest,
			EventSource: types.EventSourceGitHub,
		},
		StageResults: map[string]StageResult{
			"build": {
				Status: types.StageStatusSuccess,
				Output: map[string]string{
					"deploy_needed": "true",
				},
			},
			"test": {
				Status: types.StageStatusSuccess,
			},
		},
		Variables: map[string]string{
			"SKIP_DEPLOY": "false",
		},
	}

	tests := []struct {
		name     string
		expr     string
		expected bool
	}{
		{"only on main", "event.branch == 'main'", false},
		{"on feature branch", "event.branch startsWith 'feature/'", true},
		{"deploy only on push to main", "event.type == 'push' && event.branch == 'main'", false},
		{"deploy if build says so", "stages.build.deploy_needed == 'true'", true},
		{"full deploy condition", "stages.build.success && stages.test.success && stages.build.deploy_needed == 'true' && env.SKIP_DEPLOY == 'false'", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, err := evaluator.Evaluate(tt.expr, ctx)
			require.NoError(t, err)
			assert.Equal(t, tt.expected, result, "expr: %s", tt.expr)
		})
	}
}

func TestConditionEvaluator_InvalidExpression(t *testing.T) {
	t.Parallel()
	evaluator := NewConditionEvaluator()

	_, err := evaluator.Evaluate("'test' =~ [invalid", &EvaluationContext{})
	assert.Error(t, err)
}

func TestConditionEvaluator_OperatorPrecedence(t *testing.T) {
	t.Parallel()
	evaluator := NewConditionEvaluator()

	ctx := &EvaluationContext{}

	result, err := evaluator.Evaluate("true || false && false", ctx)
	require.NoError(t, err)
	assert.True(t, result)

	result, err = evaluator.Evaluate("(true || false) && false", ctx)
	require.NoError(t, err)
	assert.False(t, result)
}
