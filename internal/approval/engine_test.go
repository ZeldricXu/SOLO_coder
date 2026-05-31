package approval

import (
	"context"
	"encoding/json"
	"sync"
	"testing"

	"github.com/datamigration/platform/pkg/models"
	"github.com/datamigration/platform/pkg/testutil"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

func setupTestDB(t *testing.T) (*gorm.DB, *RuleEngine) {
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	require.NoError(t, err)

	err = db.AutoMigrate(&models.ApprovalRule{}, &models.ApprovalTask{})
	require.NoError(t, err)

	engine := NewRuleEngine(db)
	return db, engine
}

func TestEvaluateCondition_Equal(t *testing.T) {
	data := map[string]interface{}{"status": "active", "count": 100, "enabled": true}

	testCases := []struct {
		name     string
		condition Condition
		expected bool
	}{
		{"String Match", Condition{Field: "status", Operator: "==", Value: "active"}, true},
		{"String No Match", Condition{Field: "status", Operator: "==", Value: "inactive"}, false},
		{"Int Match", Condition{Field: "count", Operator: "==", Value: 100}, true},
		{"Int No Match", Condition{Field: "count", Operator: "==", Value: 99}, false},
		{"Bool Match", Condition{Field: "enabled", Operator: "==", Value: true}, true},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result := evaluateCondition(tc.condition, data)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestEvaluateCondition_NotEqual(t *testing.T) {
	data := map[string]interface{}{"status": "active", "count": 100}

	testCases := []struct {
		name     string
		condition Condition
		expected bool
	}{
		{"String Not Equal", Condition{Field: "status", Operator: "!=", Value: "inactive"}, true},
		{"String Equal", Condition{Field: "status", Operator: "!=", Value: "active"}, false},
		{"Field Not Exists != Empty", Condition{Field: "missing", Operator: "!=", Value: "value"}, true},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result := evaluateCondition(tc.condition, data)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestEvaluateCondition_Comparison(t *testing.T) {
	data := map[string]interface{}{"amount": 1000.0, "count": 50, "score": 75.5}

	testCases := []struct {
		name     string
		condition Condition
		expected bool
	}{
		{"> True", Condition{Field: "amount", Operator: ">", Value: 500.0}, true},
		{"> False", Condition{Field: "amount", Operator: ">", Value: 1000.0}, false},
		{">= True", Condition{Field: "amount", Operator: ">=", Value: 1000.0}, true},
		{">= False", Condition{Field: "amount", Operator: ">=", Value: 2000.0}, false},
		{"< True", Condition{Field: "count", Operator: "<", Value: 100.0}, true},
		{"< False", Condition{Field: "count", Operator: "<", Value: 50.0}, false},
		{"<= True", Condition{Field: "count", Operator: "<=", Value: 50.0}, true},
		{"<= False", Condition{Field: "count", Operator: "<=", Value: 49.0}, false},
		{"Float Comparison", Condition{Field: "score", Operator: ">=", Value: 75.0}, true},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result := evaluateCondition(tc.condition, data)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestEvaluateCondition_StringOperations(t *testing.T) {
	data := map[string]interface{}{"title": "Approval Request", "department": "engineering"}

	testCases := []struct {
		name     string
		condition Condition
		expected bool
	}{
		{"Contains True", Condition{Field: "title", Operator: "contains", Value: "Approval"}, true},
		{"Contains False", Condition{Field: "title", Operator: "contains", Value: "Reject"}, false},
		{"startsWith True", Condition{Field: "title", Operator: "startsWith", Value: "Approval"}, true},
		{"startsWith False", Condition{Field: "title", Operator: "startsWith", Value: "Request"}, false},
		{"endsWith True", Condition{Field: "title", Operator: "endsWith", Value: "Request"}, true},
		{"endsWith False", Condition{Field: "title", Operator: "endsWith", Value: "Approval"}, false},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result := evaluateCondition(tc.condition, data)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestEvaluateCondition_InList(t *testing.T) {
	data := map[string]interface{}{"role": "admin", "priority": "high"}

	testCases := []struct {
		name     string
		condition Condition
		expected bool
	}{
		{"In List True", Condition{Field: "role", Operator: "in", Value: []interface{}{"admin", "manager"}}, true},
		{"In List False", Condition{Field: "role", Operator: "in", Value: []interface{}{"user", "viewer"}}, false},
		{"Not In List", Condition{Field: "priority", Operator: "in", Value: []interface{}{"low", "medium"}}, false},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result := evaluateCondition(tc.condition, data)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestEvaluateCondition_NestedField(t *testing.T) {
	data := map[string]interface{}{
		"order": map[string]interface{}{
			"details": map[string]interface{}{
				"amount": 1500.0,
				"status": "pending",
			},
		},
	}

	testCases := []struct {
		name     string
		condition Condition
		expected bool
	}{
		{"Nested 2 Levels", Condition{Field: "order.details.amount", Operator: ">", Value: 1000.0}, true},
		{"Nested Status", Condition{Field: "order.details.status", Operator: "==", Value: "pending"}, true},
		{"Missing Nested Field", Condition{Field: "order.details.missing", Operator: "==", Value: "x"}, false},
		{"Invalid Nested Path", Condition{Field: "order.invalid.deep", Operator: "==", Value: "x"}, false},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result := evaluateCondition(tc.condition, data)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestEvaluateCondition_UnknownOperator(t *testing.T) {
	data := map[string]interface{}{"value": 100}
	cond := Condition{Field: "value", Operator: "unknown_op", Value: 100}
	result := evaluateCondition(cond, data)
	assert.False(t, result)
}

func TestRuleEngine_Evaluate_All(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond, approvers, strategy := factory.CreateApprovalRule("tnt_1", "wf_1")

	rule := &models.ApprovalRule{
		ID:        "rule_1",
		Condition: mustMarshal(t, cond),
		Approvers: mustMarshal(t, approvers),
		Strategy:  strategy,
	}

	testCases := []struct {
		name     string
		payload  map[string]interface{}
		expected bool
	}{
		{"Amount >= 1000 True", map[string]interface{}{"amount": 2000.0}, true},
		{"Amount < 1000 False", map[string]interface{}{"amount": 500.0}, false},
		{"Amount == 1000 True", map[string]interface{}{"amount": 1000.0}, true},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result, err := engine.Evaluate(ctx, rule, tc.payload)
			require.NoError(t, err)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestRuleEngine_Evaluate_EmptyCondition(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	emptyCond := &ApprovalCondition{}
	rule := &models.ApprovalRule{
		ID:        "rule_1",
		Condition: mustMarshal(t, emptyCond),
		Approvers: mustMarshal(t, &ApproverSpec{Type: "static", UserIDs: []string{"user_1"}}),
	}

	result, err := engine.Evaluate(ctx, rule, map[string]interface{}{"any": "data"})
	require.NoError(t, err)
	assert.True(t, result)
}

func TestRuleEngine_Evaluate_AllAnyNone(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()

	allCond := factory.CreateApprovalConditionAll(
		factory.CreateCondition("amount", ">=", 1000.0),
		factory.CreateCondition("status", "==", "pending"),
	)
	ruleAll := &models.ApprovalRule{
		ID:        "rule_all",
		Condition: mustMarshal(t, allCond),
		Approvers: mustMarshal(t, &ApproverSpec{Type: "static", UserIDs: []string{"user_1"}}),
	}

	testCasesAll := []struct {
		name     string
		payload  map[string]interface{}
		expected bool
	}{
		{"Both Match", map[string]interface{}{"amount": 2000.0, "status": "pending"}, true},
		{"First Match Second No", map[string]interface{}{"amount": 2000.0, "status": "approved"}, false},
		{"First No Second Match", map[string]interface{}{"amount": 500.0, "status": "pending"}, false},
		{"Neither Match", map[string]interface{}{"amount": 500.0, "status": "approved"}, false},
	}

	for _, tc := range testCasesAll {
		t.Run("All_"+tc.name, func(t *testing.T) {
			result, err := engine.Evaluate(ctx, ruleAll, tc.payload)
			require.NoError(t, err)
			assert.Equal(t, tc.expected, result)
		})
	}

	anyCond := factory.CreateApprovalConditionAny(
		factory.CreateCondition("role", "==", "admin"),
		factory.CreateCondition("department", "==", "engineering"),
	)
	ruleAny := &models.ApprovalRule{
		ID:        "rule_any",
		Condition: mustMarshal(t, anyCond),
		Approvers: mustMarshal(t, &ApproverSpec{Type: "static", UserIDs: []string{"user_1"}}),
	}

	testCasesAny := []struct {
		name     string
		payload  map[string]interface{}
		expected bool
	}{
		{"First Match", map[string]interface{}{"role": "admin", "department": "sales"}, true},
		{"Second Match", map[string]interface{}{"role": "user", "department": "engineering"}, true},
		{"Both Match", map[string]interface{}{"role": "admin", "department": "engineering"}, true},
		{"Neither Match", map[string]interface{}{"role": "user", "department": "sales"}, false},
	}

	for _, tc := range testCasesAny {
		t.Run("Any_"+tc.name, func(t *testing.T) {
			result, err := engine.Evaluate(ctx, ruleAny, tc.payload)
			require.NoError(t, err)
			assert.Equal(t, tc.expected, result)
		})
	}

	noneCond := factory.CreateApprovalConditionNone(
		factory.CreateCondition("status", "==", "blocked"),
		factory.CreateCondition("risk", "==", "high"),
	)
	ruleNone := &models.ApprovalRule{
		ID:        "rule_none",
		Condition: mustMarshal(t, noneCond),
		Approvers: mustMarshal(t, &ApproverSpec{Type: "static", UserIDs: []string{"user_1"}}),
	}

	testCasesNone := []struct {
		name     string
		payload  map[string]interface{}
		expected bool
	}{
		{"None Match", map[string]interface{}{"status": "active", "risk": "low"}, true},
		{"First Match", map[string]interface{}{"status": "blocked", "risk": "low"}, false},
		{"Second Match", map[string]interface{}{"status": "active", "risk": "high"}, false},
	}

	for _, tc := range testCasesNone {
		t.Run("None_"+tc.name, func(t *testing.T) {
			result, err := engine.Evaluate(ctx, ruleNone, tc.payload)
			require.NoError(t, err)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestRuleEngine_Evaluate_InvalidConditionJSON(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	rule := &models.ApprovalRule{
		ID:        "rule_1",
		Condition: []byte("invalid json"),
		Approvers: mustMarshal(t, &ApproverSpec{Type: "static", UserIDs: []string{"user_1"}}),
	}

	result, err := engine.Evaluate(ctx, rule, map[string]interface{}{})
	require.Error(t, err)
	assert.False(t, result)
}

func TestRuleEngine_ResolveApprovers_Static(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	approvers := &ApproverSpec{
		Type:    "static",
		UserIDs: []string{"manager_1", "manager_2", "director_1"},
	}
	rule := &models.ApprovalRule{
		ID:        "rule_1",
		Condition: mustMarshal(t, &ApprovalCondition{}),
		Approvers: mustMarshal(t, approvers),
	}

	result, err := engine.ResolveApprovers(ctx, rule, map[string]interface{}{})
	require.NoError(t, err)
	assert.Equal(t, []string{"manager_1", "manager_2", "director_1"}, result)
}

func TestRuleEngine_ResolveApprovers_Role(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	approvers := &ApproverSpec{
		Type:  "role",
		Roles: []string{"admin", "manager"},
	}
	rule := &models.ApprovalRule{
		ID:        "rule_1",
		Condition: mustMarshal(t, &ApprovalCondition{}),
		Approvers: mustMarshal(t, approvers),
	}

	result, err := engine.ResolveApprovers(ctx, rule, map[string]interface{}{})
	require.NoError(t, err)
	assert.Equal(t, []string{"user_role_admin", "user_role_manager"}, result)
}

func TestRuleEngine_ResolveApprovers_Department(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	approvers := &ApproverSpec{
		Type:        "department",
		Departments: []string{"engineering", "finance"},
	}
	rule := &models.ApprovalRule{
		ID:        "rule_1",
		Condition: mustMarshal(t, &ApprovalCondition{}),
		Approvers: mustMarshal(t, approvers),
	}

	result, err := engine.ResolveApprovers(ctx, rule, map[string]interface{}{})
	require.NoError(t, err)
	assert.Equal(t, []string{"user_dept_engineering", "user_dept_finance"}, result)
}

func TestRuleEngine_ResolveApprovers_Dynamic(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	approvers := &ApproverSpec{Type: "dynamic"}
	rule := &models.ApprovalRule{
		ID:        "rule_1",
		Condition: mustMarshal(t, &ApprovalCondition{}),
		Approvers: mustMarshal(t, approvers),
	}

	t.Run("Dynamic Approvers List", func(t *testing.T) {
		result, err := engine.ResolveApprovers(ctx, rule, map[string]interface{}{
			"approvers": []string{"dynamic_1", "dynamic_2"},
		})
		require.NoError(t, err)
		assert.Equal(t, []string{"dynamic_1", "dynamic_2"}, result)
	})

	t.Run("Dynamic Manager ID", func(t *testing.T) {
		result, err := engine.ResolveApprovers(ctx, rule, map[string]interface{}{
			"manager_id": "manager_456",
		})
		require.NoError(t, err)
		assert.Equal(t, []string{"manager_456"}, result)
	})

	t.Run("Dynamic Resolution Failed", func(t *testing.T) {
		result, err := engine.ResolveApprovers(ctx, rule, map[string]interface{}{})
		require.Error(t, err)
		assert.Nil(t, result)
	})
}

func TestRuleEngine_ResolveApprovers_InvalidApproversJSON(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	rule := &models.ApprovalRule{
		ID:        "rule_1",
		Condition: mustMarshal(t, &ApprovalCondition{}),
		Approvers: []byte("invalid json"),
	}

	result, err := engine.ResolveApprovers(ctx, rule, map[string]interface{}{})
	require.Error(t, err)
	assert.Nil(t, result)
}

func TestRuleEngine_CreateRule(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond, approvers, strategy := factory.CreateApprovalRule("tnt_1", "wf_1")

	rule, err := engine.CreateRule(ctx, "tnt_1", "Expense Approval", "wf_1", cond, strategy, approvers, 100)
	require.NoError(t, err)
	require.NotNil(t, rule)

	assert.NotEmpty(t, rule.ID)
	assert.Equal(t, "Expense Approval", rule.Name)
	assert.Equal(t, "wf_1", rule.WorkflowID)
	assert.Equal(t, strategy, rule.Strategy)
	assert.True(t, rule.Enabled)
	assert.Equal(t, 100, rule.Priority)
	assert.NotEmpty(t, rule.Condition)
	assert.NotEmpty(t, rule.Approvers)
}

func TestRuleEngine_GetRules(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond, approvers, strategy := factory.CreateApprovalRule("tnt_1", "wf_1")

	rule1, err := engine.CreateRule(ctx, "tnt_1", "Rule A", "wf_1", cond, strategy, approvers, 50)
	require.NoError(t, err)

	rule2, err := engine.CreateRule(ctx, "tnt_1", "Rule B", "wf_1", cond, strategy, approvers, 100)
	require.NoError(t, err)

	_, err = engine.CreateRule(ctx, "tnt_2", "Rule C", "wf_2", cond, strategy, approvers, 75)
	require.NoError(t, err)

	rules, err := engine.GetRules(ctx, "tnt_1", "wf_1")
	require.NoError(t, err)
	assert.Len(t, rules, 2)
	assert.Equal(t, rule2.ID, rules[0].ID)
	assert.Equal(t, rule1.ID, rules[1].ID)

	rulesAll, err := engine.GetRules(ctx, "tnt_1", "")
	require.NoError(t, err)
	assert.Len(t, rulesAll, 2)
}

func TestRuleEngine_CreateApprovalTasks_StrategyAll(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond, approvers, strategy := factory.CreateApprovalRule(
		"tnt_1", "wf_1",
		testutil.WithApprovalStrategy(StrategyAll),
	)

	_, err := engine.CreateRule(ctx, "tnt_1", "All Strategy Rule", "wf_1", cond, strategy, approvers, 100)
	require.NoError(t, err)

	tasks, err := engine.CreateApprovalTasks(ctx, "tnt_1", "wf_1", "inst_1", map[string]interface{}{"amount": 5000.0})
	require.NoError(t, err)
	assert.Len(t, tasks, 2)

	for _, task := range tasks {
		assert.Equal(t, "wf_1", task.WorkflowID)
		assert.Equal(t, "inst_1", task.InstanceID)
		assert.Equal(t, StatusPending, task.Status)
	}
}

func TestRuleEngine_CreateApprovalTasks_NoMatchingRules(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond := factory.CreateApprovalConditionAll(
		factory.CreateCondition("amount", ">", 1000000.0),
	)
	approvers := &ApproverSpec{Type: "static", UserIDs: []string{"approver_1"}}

	_, err := engine.CreateRule(ctx, "tnt_1", "High Value Rule", "wf_1", cond, StrategyAll, approvers, 100)
	require.NoError(t, err)

	tasks, err := engine.CreateApprovalTasks(ctx, "tnt_1", "wf_1", "inst_1", map[string]interface{}{"amount": 1000.0})
	require.NoError(t, err)
	assert.Len(t, tasks, 0)
}

func TestRuleEngine_Approve(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond, approvers, strategy := factory.CreateApprovalRule("tnt_1", "wf_1")
	_, err := engine.CreateRule(ctx, "tnt_1", "Rule", "wf_1", cond, strategy, approvers, 100)
	require.NoError(t, err)

	tasks, err := engine.CreateApprovalTasks(ctx, "tnt_1", "wf_1", "inst_1", map[string]interface{}{"amount": 5000.0})
	require.NoError(t, err)
	task := tasks[0]

	err = engine.Approve(ctx, task.ID, task.ApproverID, "Approved!")
	require.NoError(t, err)

	var loadedTask models.ApprovalTask
	err = engine.db.First(&loadedTask, "id = ?", task.ID).Error
	require.NoError(t, err)
	assert.Equal(t, StatusApproved, loadedTask.Status)
	assert.Equal(t, "Approved!", loadedTask.Comment)
	assert.NotNil(t, loadedTask.DecisionTime)
}

func TestRuleEngine_Reject(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond, approvers, strategy := factory.CreateApprovalRule("tnt_1", "wf_1")
	_, err := engine.CreateRule(ctx, "tnt_1", "Rule", "wf_1", cond, strategy, approvers, 100)
	require.NoError(t, err)

	tasks, err := engine.CreateApprovalTasks(ctx, "tnt_1", "wf_1", "inst_1", map[string]interface{}{"amount": 5000.0})
	require.NoError(t, err)
	task := tasks[0]

	err = engine.Reject(ctx, task.ID, task.ApproverID, "Rejected!")
	require.NoError(t, err)

	var loadedTask models.ApprovalTask
	err = engine.db.First(&loadedTask, "id = ?", task.ID).Error
	require.NoError(t, err)
	assert.Equal(t, StatusRejected, loadedTask.Status)
	assert.Equal(t, "Rejected!", loadedTask.Comment)
	assert.NotNil(t, loadedTask.DecisionTime)
}

func TestRuleEngine_CheckApprovalStatus_StrategyAll(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond, approvers, strategy := factory.CreateApprovalRule(
		"tnt_1", "wf_1",
		testutil.WithApprovalStrategy(StrategyAll),
	)

	rule, err := engine.CreateRule(ctx, "tnt_1", "All Rule", "wf_1", cond, strategy, approvers, 100)
	require.NoError(t, err)

	tasks, err := engine.CreateApprovalTasks(ctx, "tnt_1", "wf_1", "inst_all", map[string]interface{}{"amount": 5000.0})
	require.NoError(t, err)
	assert.Len(t, tasks, 2)

	status, err := engine.CheckApprovalStatus(ctx, "inst_all")
	require.NoError(t, err)
	assert.Equal(t, StatusPending, status)

	err = engine.Approve(ctx, tasks[0].ID, tasks[0].ApproverID, "OK")
	require.NoError(t, err)

	status, err = engine.CheckApprovalStatus(ctx, "inst_all")
	require.NoError(t, err)
	assert.Equal(t, StatusPending, status)

	err = engine.Approve(ctx, tasks[1].ID, tasks[1].ApproverID, "OK")
	require.NoError(t, err)

	status, err = engine.CheckApprovalStatus(ctx, "inst_all")
	require.NoError(t, err)
	assert.Equal(t, StatusApproved, status)

	_ = rule
}

func TestRuleEngine_CheckApprovalStatus_StrategyAny(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond, approvers, strategy := factory.CreateApprovalRule(
		"tnt_1", "wf_1",
		testutil.WithApprovalStrategy(StrategyAny),
	)

	_, err := engine.CreateRule(ctx, "tnt_1", "Any Rule", "wf_1", cond, strategy, approvers, 100)
	require.NoError(t, err)

	tasks, err := engine.CreateApprovalTasks(ctx, "tnt_1", "wf_1", "inst_any", map[string]interface{}{"amount": 5000.0})
	require.NoError(t, err)
	assert.Len(t, tasks, 2)

	err = engine.Approve(ctx, tasks[0].ID, tasks[0].ApproverID, "OK")
	require.NoError(t, err)

	status, err := engine.CheckApprovalStatus(ctx, "inst_any")
	require.NoError(t, err)
	assert.Equal(t, StatusApproved, status)
}

func TestRuleEngine_CheckApprovalStatus_Rejected(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond, approvers, strategy := factory.CreateApprovalRule(
		"tnt_1", "wf_1",
		testutil.WithApprovalStrategy(StrategyAll),
	)

	_, err := engine.CreateRule(ctx, "tnt_1", "Rule", "wf_1", cond, strategy, approvers, 100)
	require.NoError(t, err)

	tasks, err := engine.CreateApprovalTasks(ctx, "tnt_1", "wf_1", "inst_reject", map[string]interface{}{"amount": 5000.0})
	require.NoError(t, err)

	err = engine.Reject(ctx, tasks[0].ID, tasks[0].ApproverID, "No")
	require.NoError(t, err)

	status, err := engine.CheckApprovalStatus(ctx, "inst_reject")
	require.NoError(t, err)
	assert.Equal(t, StatusRejected, status)
}

func TestRuleEngine_CheckApprovalStatus_AnyRejected(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond, approvers, strategy := factory.CreateApprovalRule(
		"tnt_1", "wf_1",
		testutil.WithApprovalStrategy(StrategyAny),
	)

	_, err := engine.CreateRule(ctx, "tnt_1", "Rule", "wf_1", cond, strategy, approvers, 100)
	require.NoError(t, err)

	tasks, err := engine.CreateApprovalTasks(ctx, "tnt_1", "wf_1", "inst_any_reject", map[string]interface{}{"amount": 5000.0})
	require.NoError(t, err)

	err = engine.Reject(ctx, tasks[0].ID, tasks[0].ApproverID, "No")
	require.NoError(t, err)
	err = engine.Reject(ctx, tasks[1].ID, tasks[1].ApproverID, "No")
	require.NoError(t, err)

	status, err := engine.CheckApprovalStatus(ctx, "inst_any_reject")
	require.NoError(t, err)
	assert.Equal(t, StatusRejected, status)
}

func TestRuleEngine_CheckApprovalStatus_NoTasks(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	status, err := engine.CheckApprovalStatus(ctx, "non_existent_instance")
	require.NoError(t, err)
	assert.Equal(t, StatusApproved, status)
}

func TestRuleEngine_GetPendingTasks(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond, approvers, strategy := factory.CreateApprovalRule("tnt_1", "wf_1")
	_, err := engine.CreateRule(ctx, "tnt_1", "Rule", "wf_1", cond, strategy, approvers, 100)
	require.NoError(t, err)

	tasks, err := engine.CreateApprovalTasks(ctx, "tnt_1", "wf_1", "inst_pending", map[string]interface{}{"amount": 5000.0})
	require.NoError(t, err)
	approverID := tasks[0].ApproverID

	pendingTasks, err := engine.GetPendingTasks(ctx, approverID)
	require.NoError(t, err)
	assert.Len(t, pendingTasks, 1)
	assert.Equal(t, tasks[0].ID, pendingTasks[0].ID)

	err = engine.Approve(ctx, tasks[0].ID, approverID, "OK")
	require.NoError(t, err)

	pendingTasks, err = engine.GetPendingTasks(ctx, approverID)
	require.NoError(t, err)
	assert.Len(t, pendingTasks, 0)
}

func TestConcurrentApprovalProcessing(t *testing.T) {
	_, engine := setupTestDB(t)
	ctx := context.Background()

	factory := testutil.NewFactory()
	cond := factory.CreateApprovalConditionAll(
		factory.CreateCondition("amount", ">=", 0.0),
	)
	approvers := &ApproverSpec{Type: "static", UserIDs: []string{"app_1", "app_2", "app_3"}}

	_, err := engine.CreateRule(ctx, "tnt_1", "Concurrent Rule", "wf_concurrent", cond, StrategyAll, approvers, 100)
	require.NoError(t, err)

	const numInstances = 50
	var wg sync.WaitGroup
	var mu sync.Mutex
	failures := 0
	createdCount := 0

	for i := 0; i < numInstances; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			instanceID := fmt.Sprintf("inst_concurrent_%d", idx)
			tasks, err := engine.CreateApprovalTasks(
				ctx,
				"tnt_1",
				"wf_concurrent",
				instanceID,
				map[string]interface{}{"amount": float64(idx * 100)},
			)
			mu.Lock()
			if err != nil {
				failures++
			} else {
				createdCount += len(tasks)
			}
			mu.Unlock()
		}(i)
	}
	wg.Wait()

	assert.Equal(t, 0, failures)
	assert.Equal(t, numInstances*3, createdCount)

	var totalTasks int64
	engine.db.Model(&models.ApprovalTask{}).Count(&totalTasks)
	assert.Equal(t, int64(numInstances*3), totalTasks)
}

func TestComparison_NumberTypes(t *testing.T) {
	testCases := []struct {
		name     string
		a        interface{}
		b        interface{}
		expected int
	}{
		{"int vs int", 10, 5, 1},
		{"int32 vs int32", int32(10), int32(5), 1},
		{"int64 vs int64", int64(10), int64(5), 1},
		{"float32 vs float32", float32(10.5), float32(5.5), 1},
		{"float64 vs float64", 10.5, 5.5, 1},
		{"Equal", 5, 5, 0},
		{"Less Than", 3, 5, -1},
		{"json.Number", json.Number("100"), 50.0, 1},
		{"String (invalid)", "invalid", 5, -2},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result := compareNumbers(tc.a, tc.b)
			assert.Equal(t, tc.expected, result)
		})
	}
}

func TestGetNestedValue_MissingPath(t *testing.T) {
	data := map[string]interface{}{"a": "value"}

	val, ok := getNestedValue(data, "a")
	assert.True(t, ok)
	assert.Equal(t, "value", val)

	_, ok = getNestedValue(data, "b")
	assert.False(t, ok)

	data2 := map[string]interface{}{"x": map[string]interface{}{"y": 123}}
	val, ok = getNestedValue(data2, "x.y")
	assert.True(t, ok)
	assert.Equal(t, 123, val)

	data3 := map[string]interface{}{"x": "not_a_map"}
	_, ok = getNestedValue(data3, "x.y")
	assert.False(t, ok)
}

func mustMarshal(t *testing.T, v interface{}) []byte {
	b, err := json.Marshal(v)
	require.NoError(t, err)
	return b
}
