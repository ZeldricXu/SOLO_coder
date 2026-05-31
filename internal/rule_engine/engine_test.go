package rule_engine

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/edgeplatform/session306/internal/mocks"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/internal/testfactory"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

type ruleEngineTestFixture struct {
	re              *RuleEngine
	eventBus        *mocks.MockEventBus
	logger          *zap.Logger
	factory         *testfactory.TestDataFactory
	mockExecutor    *mocks.MockActionExecutor
	ctx             context.Context
	cancel          context.CancelFunc
}

func setupRuleEngineTest(t *testing.T) *ruleEngineTestFixture {
	t.Helper()

	logger, _ := zap.NewDevelopment()
	eventBus := mocks.NewMockEventBus()
	mockDA := mocks.NewMockDataAccess()
	factory := testfactory.NewTestDataFactory()
	mockExecutor := mocks.NewMockActionExecutor()

	ctx, cancel := context.WithCancel(context.Background())

	re := NewRuleEngine(mockDA.DataAccess, eventBus, logger, 2)

	re.executors["notification"] = mockExecutor
	re.executors["webhook"] = mockExecutor
	re.executors["command"] = mockExecutor
	re.executors["http_request"] = mockExecutor
	re.executors["mqtt_publish"] = mockExecutor

	eventBus.On("Publish", mock.Anything, mock.Anything).Return(nil)
	eventBus.On("Subscribe", mock.Anything, mock.Anything).Return("sub_test")
	eventBus.On("Unsubscribe", mock.Anything).Return()
	eventBus.On("Close").Return()

	return &ruleEngineTestFixture{
		re:           re,
		eventBus:     eventBus,
		logger:       logger,
		factory:    factory,
		mockExecutor: mockExecutor,
		ctx:        ctx,
		cancel:     cancel,
	}
}

func (f *ruleEngineTestFixture) teardown() {
	f.cancel()
	f.mockExecutor.Reset()
}

func TestNewRuleEngine(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	assert.NotNil(t, f.re)
	assert.NotNil(t, f.re.executors)
	assert.NotNil(t, f.re.rules)
	assert.NotNil(t, f.re.rulesByDev)
	assert.NotNil(t, f.re.taskQueue)
	assert.Equal(t, 2, f.re.workerCount)

	assert.Contains(t, f.re.executors, "http_request")
	assert.Contains(t, f.re.executors, "mqtt_publish")
	assert.Contains(t, f.re.executors, "command")
	assert.Contains(t, f.re.executors, "notification")
	assert.Contains(t, f.re.executors, "webhook")
}

func TestNewRuleEngine_DefaultWorkers(t *testing.T) {
	t.Parallel()

	logger, _ := zap.NewDevelopment()
	eventBus := mocks.NewMockEventBus()
	mockDA := mocks.NewMockDataAccess()

	re := NewRuleEngine(mockDA.DataAccess, eventBus, logger, 0)

	assert.Equal(t, 5, re.workerCount)
}

func TestEvaluateCondition_Equal(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"temperature": 25.0,
		"status":      "normal",
		"count":       10,
	}

	tests := []struct {
		name     string
		cond     model.RuleCondition
		expected bool
	}{
		{
			name: "float equal",
			cond: model.RuleCondition{
				Field:    "temperature",
				Operator: model.OpEqual,
				Value:    25.0,
			},
			expected: true,
		},
		{
			name: "string equal",
			cond: model.RuleCondition{
				Field:    "status",
				Operator: model.OpEqual,
				Value:    "normal",
			},
			expected: true,
		},
		{
			name: "int equal",
			cond: model.RuleCondition{
				Field:    "count",
				Operator: model.OpEqual,
				Value:    10,
			},
			expected: true,
		},
		{
			name: "not equal",
			cond: model.RuleCondition{
				Field:    "temperature",
				Operator: model.OpEqual,
				Value:    30.0,
			},
			expected: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, err := f.re.evaluateCondition(tt.cond, data)
			require.NoError(t, err)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestEvaluateCondition_NotEqual(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"temperature": 25.0,
	}

	cond := model.RuleCondition{
		Field:    "temperature",
		Operator: model.OpNotEqual,
		Value:    30.0,
	}

	result, err := f.re.evaluateCondition(cond, data)
	require.NoError(t, err)
	assert.True(t, result)

	cond.Value = 25.0
	result, err = f.re.evaluateCondition(cond, data)
	require.NoError(t, err)
	assert.False(t, result)
}

func TestEvaluateCondition_ComparisonOperators(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"value": 50.0,
	}

	tests := []struct {
		name     string
		operator model.RuleOperator
		value    interface{}
		expected bool
	}{
		{"gt_true", model.OpGreaterThan, 40.0, true},
		{"gt_false", model.OpGreaterThan, 60.0, false},
		{"lt_true", model.OpLessThan, 60.0, true},
		{"lt_false", model.OpLessThan, 40.0, false},
		{"gte_equal", model.OpGreaterEqual, 50.0, true},
		{"gte_true", model.OpGreaterEqual, 40.0, true},
		{"lte_equal", model.OpLessEqual, 50.0, true},
		{"lte_true", model.OpLessEqual, 60.0, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cond := model.RuleCondition{
				Field:    "value",
				Operator: tt.operator,
				Value:    tt.value,
			}
			result, err := f.re.evaluateCondition(cond, data)
			require.NoError(t, err)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestEvaluateCondition_Contains(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"message": "Hello World",
		"tags":    []interface{}{"production", "api"},
		"strTags": []string{"a", "b", "c"},
	}

	tests := []struct {
		name     string
		field    string
		value    interface{}
		expected bool
	}{
		{"string_contains", "message", "World", true},
		{"string_not_contains", "message", "Foo", false},
		{"slice_contains", "tags", "api", true},
		{"slice_not_contains", "tags", "test", false},
		{"string_slice_contains", "strTags", "b", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cond := model.RuleCondition{
				Field:    tt.field,
				Operator: model.OpContains,
				Value:    tt.value,
			}
			result, err := f.re.evaluateCondition(cond, data)
			require.NoError(t, err)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestEvaluateCondition_InAndNotIn(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"status": "running",
		"level":  2,
	}

	tests := []struct {
		name     string
		operator model.RuleOperator
		field    string
		value    interface{}
		expected bool
	}{
		{"in_string_true", model.OpIn, "status", []interface{}{"running", "stopped"}, true},
		{"in_string_false", model.OpIn, "status", []interface{}{"paused", "error"}, false},
		{"nin_string_true", model.OpNotIn, "status", []interface{}{"error", "failed"}, true},
		{"nin_string_false", model.OpNotIn, "status", []interface{}{"running", "stopped"}, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cond := model.RuleCondition{
				Field:    tt.field,
				Operator: tt.operator,
				Value:    tt.value,
			}
			result, err := f.re.evaluateCondition(cond, data)
			require.NoError(t, err)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestEvaluateCondition_Regex(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"log":    "ERROR: connection timeout",
		"email":  "test@example.com",
		"number": 12345,
	}

	tests := []struct {
		name     string
		field    string
		pattern  string
		expected bool
	}{
		{"regex_match_error", "log", `ERROR.*timeout`, true},
		{"regex_match_email", "email", `^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`, true},
		{"regex_no_match", "log", `DEBUG.*`, false},
		{"regex_non_string_field", "number", `^\d+$`, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cond := model.RuleCondition{
				Field:    tt.field,
				Operator: model.OpRegex,
				Value:    tt.pattern,
			}
			result, err := f.re.evaluateCondition(cond, data)
			require.NoError(t, err)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestEvaluateCondition_InvalidRegex(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"field": "test",
	}

	cond := model.RuleCondition{
		Field:    "field",
		Operator: model.OpRegex,
		Value:    `[invalid`,
	}

	_, err := f.re.evaluateCondition(cond, data)
	assert.Error(t, err)
}

func TestEvaluateCondition_UnsupportedOperator(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"field": "test",
	}

	cond := model.RuleCondition{
		Field:    "field",
		Operator: model.RuleOperator("invalid_op"),
		Value:    "test",
	}

	_, err := f.re.evaluateCondition(cond, data)
	assert.Error(t, err)
}

func TestEvaluateCondition_NestedField(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"system": map[string]interface{}{
			"cpu": map[string]interface{}{
				"usage": 85.5,
			},
		},
	}

	cond := model.RuleCondition{
		Field:    "system.cpu.usage",
		Operator: model.OpGreaterThan,
		Value:    80.0,
	}

	result, err := f.re.evaluateCondition(cond, data)
	require.NoError(t, err)
	assert.True(t, result)
}

func TestEvaluateCondition_FieldNotFound(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"existing": "value",
	}

	cond := model.RuleCondition{
		Field:    "nonexistent",
		Operator: model.OpEqual,
		Value:    "test",
	}

	result, err := f.re.evaluateCondition(cond, data)
	require.NoError(t, err)
	assert.False(t, result)
}

func TestEvaluateConditions_MatchAll(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"temperature": 35.0,
		"humidity":    85.0,
	}

	conditions := []model.RuleCondition{
		{Field: "temperature", Operator: model.OpGreaterThan, Value: 30.0},
		{Field: "humidity", Operator: model.OpGreaterThan, Value: 80.0},
	}

	result, err := f.re.evaluateConditions(conditions, true, data)
	require.NoError(t, err)
	assert.True(t, result)

	conditions[1].Value = 90.0
	result, err = f.re.evaluateConditions(conditions, true, data)
	require.NoError(t, err)
	assert.False(t, result)
}

func TestEvaluateConditions_MatchAny(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{
		"temperature": 35.0,
		"humidity":    50.0,
	}

	conditions := []model.RuleCondition{
		{Field: "temperature", Operator: model.OpGreaterThan, Value: 40.0},
		{Field: "humidity", Operator: model.OpGreaterThan, Value: 80.0},
	}

	result, err := f.re.evaluateConditions(conditions, false, data)
	require.NoError(t, err)
	assert.False(t, result)

	conditions[0].Value = 30.0
	result, err = f.re.evaluateConditions(conditions, false, data)
	require.NoError(t, err)
	assert.True(t, result)
}

func TestEvaluateConditions_EmptyConditions(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := map[string]interface{}{}

	result, err := f.re.evaluateConditions(nil, true, data)
	require.NoError(t, err)
	assert.True(t, result)
}

func TestEvaluate_NoRules(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	data := f.factory.CreateTestRuleTriggerData()
	triggered, err := f.re.Evaluate(f.ctx, "no_such_device", data)

	require.NoError(t, err)
	assert.Nil(t, triggered)
}

func TestEvaluate_RuleTriggered(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_test_001"

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.rulesByDev[rule.DeviceID] = append(f.re.rulesByDev[rule.DeviceID], rule)
	f.re.mu.Unlock()

	data := f.factory.CreateRuleTriggerData(35.0, 70.0)

	triggered, err := f.re.Evaluate(f.ctx, rule.DeviceID, data)

	require.NoError(t, err)
	assert.Len(t, triggered, 1)
	assert.Contains(t, triggered, rule.RuleID)

	time.Sleep(20 * time.Millisecond)

	assert.Equal(t, 1, f.mockExecutor.ExecuteCount)
}

func TestEvaluate_CooldownPeriod(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_test_002"
	rule.CooldownSeconds = 100

	now := time.Now()
	rule.LastTriggeredAt = &now

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.rulesByDev[rule.DeviceID] = append(f.re.rulesByDev[rule.DeviceID], rule)
	f.re.mu.Unlock()

	data := f.factory.CreateRuleTriggerData(35.0, 70.0)

	triggered, err := f.re.Evaluate(f.ctx, rule.DeviceID, data)

	require.NoError(t, err)
	assert.Empty(t, triggered)
	assert.Equal(t, 0, f.mockExecutor.ExecuteCount)
}

func TestEvaluate_CooldownExpired(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_test_003"
	rule.CooldownSeconds = 1

	// 1小时前触发过
	past := time.Now().Add(-1 * time.Hour)
	rule.LastTriggeredAt = &past

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.rulesByDev[rule.DeviceID] = append(f.re.rulesByDev[rule.DeviceID], rule)
	f.re.mu.Unlock()

	data := f.factory.CreateRuleTriggerData(35.0, 70.0)

	triggered, err := f.re.Evaluate(f.ctx, rule.DeviceID, data)

	require.NoError(t, err)
	assert.Len(t, triggered, 1)

	time.Sleep(20 * time.Millisecond)
	assert.Equal(t, 1, f.mockExecutor.ExecuteCount)
}

func TestEvaluate_DisabledRule(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_test_004"
	rule.Enabled = false

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.rulesByDev[rule.DeviceID] = append(f.re.rulesByDev[rule.DeviceID], rule)
	f.re.mu.Unlock()

	data := f.factory.CreateRuleTriggerData(35.0, 70.0)

	triggered, err := f.re.Evaluate(f.ctx, rule.DeviceID, data)

	require.NoError(t, err)
	assert.Empty(t, triggered)
	assert.Equal(t, 0, f.mockExecutor.ExecuteCount)
}

func TestEvaluate_MultipleRules(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule1 := f.factory.CreateTemperatureRule()
	rule1.RuleID = "rule_multi_1"
	rule1.DeviceID = "dev_multi"
	rule1.CooldownSeconds = 0

	rule2 := f.factory.CreateMultiConditionRule()
	rule2.RuleID = "rule_multi_2"
	rule2.DeviceID = "dev_multi"
	rule2.CooldownSeconds = 0

	f.re.mu.Lock()
	f.re.rules[rule1.RuleID] = rule1
	f.re.rules[rule2.RuleID] = rule2
	f.re.rulesByDev["dev_multi"] = append(f.re.rulesByDev["dev_multi"], rule1, rule2)
	f.re.mu.Unlock()

	data := f.factory.CreateRuleTriggerData(35.0, 85.0)

	triggered, err := f.re.Evaluate(f.ctx, "dev_multi", data)

	require.NoError(t, err)
	assert.Len(t, triggered, 2)

	time.Sleep(50 * time.Millisecond)
	assert.Equal(t, 2, f.mockExecutor.ExecuteCount)
}

func TestEvaluate_ActionExecutorError(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_error_001"
	rule.CooldownSeconds = 0

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.rulesByDev[rule.DeviceID] = append(f.re.rulesByDev[rule.DeviceID], rule)
	f.re.mu.Unlock()

	f.mockExecutor.ReturnError = true
	f.mockExecutor.ErrorMessage = "mock executor error"

	data := f.factory.CreateRuleTriggerData(35.0, 70.0)

	triggered, err := f.re.Evaluate(f.ctx, rule.DeviceID, data)

	require.NoError(t, err)
	assert.Len(t, triggered, 1)

	time.Sleep(20 * time.Millisecond)
	assert.Equal(t, 1, f.mockExecutor.ExecuteCount)
}

func TestProcessEvent_Success(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_process_001"

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.mu.Unlock()

	event := &model.RuleTriggerEvent{
		RuleID:   rule.RuleID,
		DeviceID: rule.DeviceID,
		Timestamp: time.Now(),
		Data:     f.factory.CreateRuleTriggerData(35.0, 70.0),
	}

	f.re.processEvent(f.ctx, event)

	assert.Equal(t, 1, f.mockExecutor.ExecuteCount)
	assert.Equal(t, int64(1), rule.TriggerCount)
	assert.NotNil(t, rule.LastTriggeredAt)
}

func TestProcessEvent_RuleNotFound(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	event := &model.RuleTriggerEvent{
		RuleID:    "nonexistent_rule",
		DeviceID:  "dev_001",
		Timestamp: time.Now(),
		Data:      make(map[string]interface{}),
	}

	f.re.processEvent(f.ctx, event)

	assert.Equal(t, 0, f.mockExecutor.ExecuteCount)
}

func TestProcessEvent_ActionError(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_process_error_001"

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.mu.Unlock()

	f.mockExecutor.ReturnError = true

	event := &model.RuleTriggerEvent{
		RuleID:    rule.RuleID,
		DeviceID:  rule.DeviceID,
		Timestamp: time.Now(),
		Data:      f.factory.CreateRuleTriggerData(35.0, 70.0),
	}

	f.re.processEvent(f.ctx, event)

	assert.Equal(t, 1, f.mockExecutor.ExecuteCount)
	assert.Equal(t, int64(1), rule.TriggerCount)
}

func TestConcurrentEvaluate(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_concurrent_001"
	rule.DeviceID = "dev_concurrent"
	rule.CooldownSeconds = 0

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.rulesByDev[rule.DeviceID] = append(f.re.rulesByDev[rule.DeviceID], rule)
	f.re.mu.Unlock()

	var wg sync.WaitGroup
	concurrency := 50

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			data := f.factory.CreateRuleTriggerData(35.0, 70.0)
			_, err := f.re.Evaluate(f.ctx, rule.DeviceID, data)
			assert.NoError(t, err)
		}()
	}

	wg.Wait()

	time.Sleep(100 * time.Millisecond)
	assert.GreaterOrEqual(t, f.mockExecutor.ExecuteCount, 1)
}

func TestConcurrentAddAndEvaluate(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	var wg sync.WaitGroup
	concurrency := 20

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()

			rule := testfactory.NewRuleBuilder().
				WithName("Concurrent Rule").
				WithDeviceID("dev_concurrent_mix").
				WithCondition(model.RuleCondition{
					Field:    "value",
					Operator: model.OpGreaterThan,
					Value:    0,
				}).
				WithAction(model.RuleAction{
					Type: "notification",
					Parameters: map[string]interface{}{
						"message": "test",
					},
				}).
				WithCooldown(0).
				Build()
			rule.RuleID = testfactory.NewTestDataFactory().CreateRandomDeviceID()

			f.re.mu.Lock()
			f.re.rules[rule.RuleID] = rule
			f.re.rulesByDev[rule.DeviceID] = append(f.re.rulesByDev[rule.DeviceID], rule)
			f.re.mu.Unlock()
		}(i)
	}

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			data := map[string]interface{}{"value": 100}
			_, _ = f.re.Evaluate(f.ctx, "dev_concurrent_mix", data)
		}()
	}

	wg.Wait()

	time.Sleep(50 * time.Millisecond)
}

func TestStartAndStop(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	ctx, cancel := context.WithCancel(f.ctx)
	defer cancel()

	err := f.re.Start(ctx)
	require.NoError(t, err)

	time.Sleep(20 * time.Millisecond)

	f.re.Stop()

	time.Sleep(20 * time.Millisecond)
}

func TestWorkerContextCancellation(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	ctx, cancel := context.WithCancel(f.ctx)

	go f.re.worker(ctx, 0)

	time.Sleep(10 * time.Millisecond)

	cancel()

	time.Sleep(20 * time.Millisecond)
}

func TestEnableRule(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_enable_001"
	rule.Enabled = false

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.rulesByDev[rule.DeviceID] = append(f.re.rulesByDev[rule.DeviceID], rule)
	f.re.mu.Unlock()

	data := f.factory.CreateRuleTriggerData(35.0, 70.0)
	triggered, _ := f.re.Evaluate(f.ctx, rule.DeviceID, data)
	assert.Empty(t, triggered)

	f.re.EnableRule(f.ctx, rule.RuleID, true)

	f.re.mu.RLock()
	assert.True(t, f.re.rules[rule.RuleID].Enabled)
	f.re.mu.RUnlock()

	triggered, _ = f.re.Evaluate(f.ctx, rule.DeviceID, data)
	assert.Len(t, triggered, 1)
}

func TestDisableRule(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_disable_001"
	rule.Enabled = true
	rule.CooldownSeconds = 0

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.rulesByDev[rule.DeviceID] = append(f.re.rulesByDev[rule.DeviceID], rule)
	f.re.mu.Unlock()

	data := f.factory.CreateRuleTriggerData(35.0, 70.0)
	triggered, _ := f.re.Evaluate(f.ctx, rule.DeviceID, data)
	assert.Len(t, triggered, 1)

	f.re.EnableRule(f.ctx, rule.RuleID, false)

	f.re.mu.RLock()
	_, exists := f.re.rules[rule.RuleID]
	assert.False(t, exists)
	f.re.mu.RUnlock()

	triggered, _ = f.re.Evaluate(f.ctx, rule.DeviceID, data)
	assert.Empty(t, triggered)
}

func TestDeleteRule(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_delete_001"

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.rulesByDev[rule.DeviceID] = append(f.re.rulesByDev[rule.DeviceID], rule)
	f.re.mu.Unlock()

	f.re.mu.RLock()
	assert.Contains(t, f.re.rules, rule.RuleID)
	f.re.mu.RUnlock()

	f.re.DeleteRule(f.ctx, rule.RuleID)

	f.re.mu.RLock()
	assert.NotContains(t, f.re.rules, rule.RuleID)
	f.re.mu.RUnlock()
}

func TestCompareNumbers(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name     string
		a        interface{}
		b        interface{}
		expected int
	}{
		{"int_gt", 10, 5, 1},
		{"int_lt", 5, 10, -1},
		{"int_eq", 5, 5, 0},
		{"float_gt", 10.5, 5.5, 1},
		{"float_lt", 5.5, 10.5, -1},
		{"mixed_gt_int_float", 10, 5.5, 1},
		{"mixed_lt_float_int", 5.5, 10, -1},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := compareNumbers(tt.a, tt.b)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestToFloat64(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name     string
		input    interface{}
		expected float64
	}{
		{"int", 42, 42.0},
		{"int32", int32(42), 42.0},
		{"int64", int64(42), 42.0},
		{"float32", float32(42.5), 42.5},
		{"float64", 42.5, 42.5},
		{"string_default", "not_a_number", 0.0},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := toFloat64(tt.input)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestGetNestedValue(t *testing.T) {
	t.Parallel()

	data := map[string]interface{}{
		"level1": map[string]interface{}{
			"level2": map[string]interface{}{
				"level3": "deep_value",
			},
			"value": "level1_value",
		},
		"top": "top_value",
	}

	tests := []struct {
		name     string
		field    string
		expected interface{}
		hasError bool
	}{
		{"top_level", "top", "top_value", false},
		{"one_level", "level1.value", "level1_value", false},
		{"three_levels", "level1.level2.level3", "deep_value", false},
		{"not_found", "nonexistent", nil, true},
		{"not_object", "level1.value.invalid", nil, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result, err := getNestedValue(data, tt.field)
			if tt.hasError {
				assert.Error(t, err)
			} else {
				require.NoError(t, err)
				assert.Equal(t, tt.expected, result)
			}
		})
	}
}

func TestTaskQueueFull(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	rule := f.factory.CreateTemperatureRule()
	rule.RuleID = "rule_queue_full_001"
	rule.CooldownSeconds = 0

	f.re.mu.Lock()
	f.re.rules[rule.RuleID] = rule
	f.re.rulesByDev[rule.DeviceID] = append(f.re.rulesByDev[rule.DeviceID], rule)
	f.re.mu.Unlock()

	for i := 0; i < 1000; i++ {
		event := &model.RuleTriggerEvent{
			RuleID:    rule.RuleID,
			DeviceID:  rule.DeviceID,
			Timestamp: time.Now(),
			Data:      f.factory.CreateRuleTriggerData(35.0, 70.0),
		}
		select {
		case f.re.taskQueue <- event:
		default:
			break
		}
	}

	data := f.factory.CreateRuleTriggerData(35.0, 70.0)
	_, err := f.re.Evaluate(f.ctx, rule.DeviceID, data)

	assert.NoError(t, err)
}

func TestResourceCleanup(t *testing.T) {
	t.Parallel()

	f := setupRuleEngineTest(t)
	defer f.teardown()

	ctx, cancel := context.WithCancel(f.ctx)

	err := f.re.Start(ctx)
	require.NoError(t, err)

	rule1 := f.factory.CreateTemperatureRule()
	rule1.RuleID = "rule_cleanup_1"
	rule2 := f.factory.CreateTemperatureRule()
	rule2.RuleID = "rule_cleanup_2"

	f.re.mu.Lock()
	f.re.rules[rule1.RuleID] = rule1
	f.re.rules[rule2.RuleID] = rule2
	f.re.rulesByDev["dev_cleanup"] = []*model.Rule{rule1, rule2}
	f.re.mu.Unlock()

	f.re.DeleteRule(f.ctx, rule1.RuleID)
	f.re.EnableRule(f.ctx, rule2.RuleID, false)

	f.re.mu.RLock()
	assert.NotContains(t, f.re.rules, rule1.RuleID)
	assert.NotContains(t, f.re.rules, rule2.RuleID)
	f.re.mu.RUnlock()

	cancel()
	f.re.Stop()

	time.Sleep(50 * time.Millisecond)

	select {
	case _, ok := <-f.re.taskQueue:
		assert.False(t, ok, "task queue should be closed or empty")
	default:
	}
}
