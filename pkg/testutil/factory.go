package testutil

import (
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/datamigration/platform/internal/approval"
	"github.com/datamigration/platform/pkg/models"
	"github.com/google/uuid"
)

type Factory struct {
	counter int
	mu      sync.Mutex
}

func NewFactory() *Factory {
	return &Factory{}
}

func (f *Factory) nextID(prefix string) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.counter++
	return fmt.Sprintf("%s_%s_%d", prefix, uuid.New().String()[:6], f.counter)
}

func (f *Factory) CreateTenant(opts ...func(*models.Tenant)) *models.Tenant {
	defaultCfg := &models.TenantConfig{
		Theme:       "default",
		Language:    "zh-CN",
		Timezone:    "Asia/Shanghai",
		Features:    map[string]bool{"basic": true},
		CustomParams: map[string]interface{}{"env": "test"},
	}
	defaultQuota := &models.Quota{
		MaxStorageGB:   100,
		MaxUsers:       50,
		MaxWorkflows:   20,
		MaxAPICallsDay: 10000,
	}

	cfgBytes, _ := json.Marshal(defaultCfg)
	quotaBytes, _ := json.Marshal(defaultQuota)

	tenant := &models.Tenant{
		ID:          f.nextID("tnt"),
		Name:        fmt.Sprintf("TestTenant_%d", f.counter),
		Description: "Test tenant created by factory",
		Config:      cfgBytes,
		Quota:       quotaBytes,
		Status:      "active",
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
	}

	for _, opt := range opts {
		opt(tenant)
	}
	return tenant
}

func (f *Factory) CreateEntity(tenantID string, opts ...func(*models.Entity)) *models.Entity {
	attrs := map[string]interface{}{
		"source":  "test",
		"version": 1,
	}
	attrsBytes, _ := json.Marshal(attrs)

	entity := &models.Entity{
		ID:         f.nextID("ent"),
		Type:       "event",
		Status:     "active",
		Attributes: attrsBytes,
		CreatedAt:  time.Now(),
		UpdatedAt:  time.Now(),
		TenantID:   tenantID,
	}

	for _, opt := range opts {
		opt(entity)
	}
	return entity
}

func (f *Factory) CreateApprovalRule(tenantID, workflowID string, opts ...func(*approval.ApprovalCondition, *approval.ApproverSpec, string)) (*approval.ApprovalCondition, *approval.ApproverSpec, string) {
	condition := &approval.ApprovalCondition{
		All: []approval.Condition{
			{Field: "amount", Operator: ">=", Value: float64(1000)},
		},
	}
	approvers := &approval.ApproverSpec{
		Type:    "static",
		UserIDs: []string{"approver_1", "approver_2"},
	}
	strategy := approval.StrategyAll

	for _, opt := range opts {
		opt(condition, approvers, &strategy)
	}
	return condition, approvers, strategy
}

func (f *Factory) CreateApprovalConditionAll(conditions ...approval.Condition) *approval.ApprovalCondition {
	return &approval.ApprovalCondition{All: conditions}
}

func (f *Factory) CreateApprovalConditionAny(conditions ...approval.Condition) *approval.ApprovalCondition {
	return &approval.ApprovalCondition{Any: conditions}
}

func (f *Factory) CreateApprovalConditionNone(conditions ...approval.Condition) *approval.ApprovalCondition {
	return &approval.ApprovalCondition{None: conditions}
}

func (f *Factory) CreateCondition(field, operator string, value interface{}) approval.Condition {
	return approval.Condition{
		Field:    field,
		Operator: operator,
		Value:    value,
	}
}

func (f *Factory) CreateSkill(opts ...func(*models.Skill)) *models.Skill {
	meta := map[string]interface{}{"level": "intermediate"}
	metaBytes, _ := json.Marshal(meta)

	skill := &models.Skill{
		ID:          f.nextID("skl"),
		Name:        fmt.Sprintf("Skill_%d", f.counter),
		Description: "Test skill",
		Category:    "engineering",
		Level:       1,
		Metadata:    metaBytes,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
	}

	for _, opt := range opts {
		opt(skill)
	}
	return skill
}

func (f *Factory) CreateEmployeeSkill(employeeID, skillID string, proficiency int, opts ...func(*models.EmployeeSkill)) *models.EmployeeSkill {
	es := &models.EmployeeSkill{
		ID:           f.nextID("esk"),
		EmployeeID:   employeeID,
		SkillID:      skillID,
		Proficiency:  proficiency,
		AssessmentAt: time.Now(),
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
	}

	for _, opt := range opts {
		opt(es)
	}
	return es
}

func (f *Factory) CreateConfigDefinition(tenantID, namespace string, params map[string]interface{}, opts ...func(*models.ConfigDefinition)) *models.ConfigDefinition {
	paramsBytes, _ := json.Marshal(params)
	if params == nil {
		paramsBytes, _ = json.Marshal(map[string]interface{}{"timeout": 30})
	}

	cfg := &models.ConfigDefinition{
		ConfigID:   f.nextID("cfg"),
		Namespace:  namespace,
		Version:    1,
		Parameters: paramsBytes,
		Enabled:    true,
		AppliedAt:  time.Now(),
		TenantID:   tenantID,
	}

	for _, opt := range opts {
		opt(cfg)
	}
	return cfg
}

func (f *Factory) CreateSLAConfig(tenantID string, opts ...func(*models.SLAConfiguration)) *models.SLAConfiguration {
	sla := &models.SLAConfiguration{
		ID:             f.nextID("sla"),
		Name:           fmt.Sprintf("SLA_Policy_%d", f.counter),
		WorkflowType:   "default",
		ResponseTime:   60,
		ResolutionTime: 1440,
		EscalationTime: 720,
		Enabled:        true,
		CreatedAt:      time.Now(),
		UpdatedAt:      time.Now(),
		TenantID:       tenantID,
	}

	for _, opt := range opts {
		opt(sla)
	}
	return sla
}

func (f *Factory) CreateWorkflowDefinition(tenantID string, opts ...func(*models.WorkflowDefinition)) *models.WorkflowDefinition {
	nodes := []map[string]interface{}{
		{"type": "start", "name": "Start", "config": map[string]interface{}{}},
		{"type": "task", "name": "Process", "config": map[string]interface{}{"handler": "default"}},
		{"type": "end", "name": "End", "config": map[string]interface{}{}},
	}
	edges := []map[string]interface{}{
		{"source": "Start", "target": "Process", "rule": "default"},
		{"source": "Process", "target": "End", "rule": "default"},
	}

	nodesBytes, _ := json.Marshal(nodes)
	edgesBytes, _ := json.Marshal(edges)
	configBytes, _ := json.Marshal(map[string]interface{}{"version": "1.0"})

	wf := &models.WorkflowDefinition{
		ID:          f.nextID("wf"),
		Name:        fmt.Sprintf("Workflow_%d", f.counter),
		Description: "Test workflow",
		Version:     1,
		Nodes:       nodesBytes,
		Edges:       edgesBytes,
		Config:      configBytes,
		Enabled:     true,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
		TenantID:    tenantID,
	}

	for _, opt := range opts {
		opt(wf)
	}
	return wf
}

func (f *Factory) CreateScheduledTask(tenantID, taskType string, opts ...func(*models.ScheduledTask)) *models.ScheduledTask {
	payloadBytes, _ := json.Marshal(map[string]interface{}{"test": true})

	task := &models.ScheduledTask{
		ID:        f.nextID("sch"),
		Name:      fmt.Sprintf("Task_%d", f.counter),
		CronExpr:  "0 * * * *",
		TaskType:  taskType,
		Payload:   payloadBytes,
		Enabled:   true,
		NextRunAt: time.Now().Add(1 * time.Hour),
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
		TenantID:  tenantID,
	}

	for _, opt := range opts {
		opt(task)
	}
	return task
}

func WithTenantName(name string) func(*models.Tenant) {
	return func(t *models.Tenant) {
		t.Name = name
	}
}

func WithTenantStatus(status string) func(*models.Tenant) {
	return func(t *models.Tenant) {
		t.Status = status
	}
}

func WithEntityType(typ string) func(*models.Entity) {
	return func(e *models.Entity) {
		e.Type = typ
	}
}

func WithEntityStatus(status string) func(*models.Entity) {
	return func(e *models.Entity) {
		e.Status = status
	}
}

func WithSkillCategory(cat string) func(*models.Skill) {
	return func(s *models.Skill) {
		s.Category = cat
	}
}

func WithSkillParent(parentID string) func(*models.Skill) {
	return func(s *models.Skill) {
		s.ParentID = &parentID
	}
}

func WithApproverType(typ string) func(*approval.ApprovalCondition, *approval.ApproverSpec, *string) {
	return func(cond *approval.ApprovalCondition, spec *approval.ApproverSpec, strategy *string) {
		spec.Type = typ
	}
}

func WithApprovalStrategy(strategy string) func(*approval.ApprovalCondition, *approval.ApproverSpec, *string) {
	return func(cond *approval.ApprovalCondition, spec *approval.ApproverSpec, s *string) {
		*s = strategy
	}
}
