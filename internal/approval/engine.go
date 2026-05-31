package approval

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"strings"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/pkg/models"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

const (
	StrategyAll    = "all"
	StrategyAny    = "any"
	StrategyFirst  = "first"
	StatusPending  = "pending"
	StatusApproved = "approved"
	StatusRejected = "rejected"
	StatusEscalated = "escalated"
)

type Condition struct {
	Field    string      `json:"field"`
	Operator string      `json:"operator"`
	Value    interface{} `json:"value"`
}

type ApprovalCondition struct {
	All  []Condition `json:"all,omitempty"`
	Any  []Condition `json:"any,omitempty"`
	None []Condition `json:"none,omitempty"`
}

type ApproverSpec struct {
	Type     string   `json:"type"`
	Roles    []string `json:"roles,omitempty"`
	UserIDs  []string `json:"user_ids,omitempty"`
	Departments []string `json:"departments,omitempty"`
}

type RuleEngine struct {
	db *gorm.DB
}

func NewRuleEngine(db *gorm.DB) *RuleEngine {
	return &RuleEngine{db: db}
}

func (e *RuleEngine) CreateRule(ctx context.Context, tenantID, name, workflowID string, condition *ApprovalCondition, strategy string, approvers *ApproverSpec, priority int) (*models.ApprovalRule, error) {
	condBytes, err := json.Marshal(condition)
	if err != nil {
		return nil, err
	}
	apprBytes, err := json.Marshal(approvers)
	if err != nil {
		return nil, err
	}

	rule := &models.ApprovalRule{
		ID:         fmt.Sprintf("rul_%s", uuid.New().String()[:8]),
		Name:       name,
		WorkflowID: workflowID,
		Condition:  condBytes,
		Strategy:   strategy,
		Approvers:  apprBytes,
		Priority:   priority,
		Enabled:    true,
		CreatedAt:  time.Now(),
		UpdatedAt:  time.Now(),
		TenantID:   tenantID,
	}

	if err := e.db.WithContext(ctx).Create(rule).Error; err != nil {
		logger.Error("failed to create approval rule", zap.Error(err))
		return nil, err
	}

	return rule, nil
}

func (e *RuleEngine) GetRules(ctx context.Context, tenantID, workflowID string) ([]*models.ApprovalRule, error) {
	var rules []*models.ApprovalRule
	query := e.db.WithContext(ctx).Where("tenant_id = ? AND enabled = ?", tenantID, true)
	if workflowID != "" {
		query = query.Where("workflow_id = ?", workflowID)
	}
	if err := query.Order("priority DESC").Find(&rules).Error; err != nil {
		return nil, err
	}
	return rules, nil
}

func (e *RuleEngine) Evaluate(ctx context.Context, rule *models.ApprovalRule, payload map[string]interface{}) (bool, error) {
	var cond ApprovalCondition
	if err := json.Unmarshal(rule.Condition, &cond); err != nil {
		return false, err
	}

	if len(cond.All) == 0 && len(cond.Any) == 0 && len(cond.None) == 0 {
		return true, nil
	}

	if len(cond.All) > 0 {
		for _, c := range cond.All {
			if !evaluateCondition(c, payload) {
				return false, nil
			}
		}
	}

	if len(cond.Any) > 0 {
		matched := false
		for _, c := range cond.Any {
			if evaluateCondition(c, payload) {
				matched = true
				break
			}
		}
		if !matched {
			return false, nil
		}
	}

	if len(cond.None) > 0 {
		for _, c := range cond.None {
			if evaluateCondition(c, payload) {
				return false, nil
			}
		}
	}

	return true, nil
}

func evaluateCondition(cond Condition, data map[string]interface{}) bool {
	actual, ok := getNestedValue(data, cond.Field)
	if !ok {
		return cond.Operator == "!="
	}

	switch cond.Operator {
	case "==":
		return reflect.DeepEqual(actual, cond.Value)
	case "!=":
		return !reflect.DeepEqual(actual, cond.Value)
	case ">":
		return compareNumbers(actual, cond.Value) > 0
	case ">=":
		return compareNumbers(actual, cond.Value) >= 0
	case "<":
		return compareNumbers(actual, cond.Value) < 0
	case "<=":
		return compareNumbers(actual, cond.Value) <= 0
	case "contains":
		return containsString(actual, cond.Value)
	case "in":
		return inList(actual, cond.Value)
	case "startsWith":
		return startsWith(actual, cond.Value)
	case "endsWith":
		return endsWith(actual, cond.Value)
	default:
		return false
	}
}

func getNestedValue(data map[string]interface{}, path string) (interface{}, bool) {
	parts := strings.Split(path, ".")
	current := data
	for i, part := range parts {
		if i == len(parts)-1 {
			val, ok := current[part]
			return val, ok
		}
		next, ok := current[part]
		if !ok {
			return nil, false
		}
		current, ok = next.(map[string]interface{})
		if !ok {
			return nil, false
		}
	}
	return nil, false
}

func compareNumbers(a, b interface{}) int {
	af, ok := toFloat64(a)
	if !ok {
		return -2
	}
	bf, ok := toFloat64(b)
	if !ok {
		return -2
	}
	if af > bf {
		return 1
	} else if af < bf {
		return -1
	}
	return 0
}

func toFloat64(v interface{}) (float64, bool) {
	switch val := v.(type) {
	case int:
		return float64(val), true
	case int32:
		return float64(val), true
	case int64:
		return float64(val), true
	case float32:
		return float64(val), true
	case float64:
		return val, true
	case json.Number:
		f, err := val.Float64()
		return f, err == nil
	default:
		return 0, false
	}
}

func containsString(a, b interface{}) bool {
	as, ok := a.(string)
	if !ok {
		return false
	}
	bs, ok := b.(string)
	if !ok {
		return false
	}
	return strings.Contains(as, bs)
}

func inList(a, b interface{}) bool {
	list, ok := b.([]interface{})
	if !ok {
		return false
	}
	for _, item := range list {
		if reflect.DeepEqual(a, item) {
			return true
		}
	}
	return false
}

func startsWith(a, b interface{}) bool {
	as, ok := a.(string)
	if !ok {
		return false
	}
	bs, ok := b.(string)
	if !ok {
		return false
	}
	return strings.HasPrefix(as, bs)
}

func endsWith(a, b interface{}) bool {
	as, ok := a.(string)
	if !ok {
		return false
	}
	bs, ok := b.(string)
	if !ok {
		return false
	}
	return strings.HasSuffix(as, bs)
}

func (e *RuleEngine) ResolveApprovers(ctx context.Context, rule *models.ApprovalRule, payload map[string]interface{}) ([]string, error) {
	var spec ApproverSpec
	if err := json.Unmarshal(rule.Approvers, &spec); err != nil {
		return nil, err
	}

	switch spec.Type {
	case "static":
		return spec.UserIDs, nil
	case "dynamic":
		return e.resolveDynamicApprovers(ctx, spec, payload)
	case "role":
		return e.resolveByRoles(ctx, spec.Roles)
	case "department":
		return e.resolveByDepartments(ctx, spec.Departments)
	default:
		return spec.UserIDs, nil
	}
}

func (e *RuleEngine) resolveDynamicApprovers(ctx context.Context, spec ApproverSpec, payload map[string]interface{}) ([]string, error) {
	if val, ok := payload["approvers"]; ok {
		if list, ok := val.([]string); ok {
			return list, nil
		}
	}
	if val, ok := payload["manager_id"]; ok {
		if id, ok := val.(string); ok {
			return []string{id}, nil
		}
	}
	return nil, errors.New("failed to resolve dynamic approvers")
}

func (e *RuleEngine) resolveByRoles(ctx context.Context, roles []string) ([]string, error) {
	var approvers []string
	for _, role := range roles {
		approvers = append(approvers, fmt.Sprintf("user_role_%s", role))
	}
	return approvers, nil
}

func (e *RuleEngine) resolveByDepartments(ctx context.Context, depts []string) ([]string, error) {
	var approvers []string
	for _, dept := range depts {
		approvers = append(approvers, fmt.Sprintf("user_dept_%s", dept))
	}
	return approvers, nil
}

func (e *RuleEngine) CreateApprovalTasks(ctx context.Context, tenantID, workflowID, instanceID string, payload map[string]interface{}) ([]*models.ApprovalTask, error) {
	rules, err := e.GetRules(ctx, tenantID, workflowID)
	if err != nil {
		return nil, err
	}

	var allTasks []*models.ApprovalTask
	payloadBytes, _ := json.Marshal(payload)

	for _, rule := range rules {
		matched, err := e.Evaluate(ctx, rule, payload)
		if err != nil {
			logger.Warn("failed to evaluate rule", zap.Error(err), zap.String("rule_id", rule.ID))
			continue
		}
		if !matched {
			continue
		}

		approvers, err := e.ResolveApprovers(ctx, rule, payload)
		if err != nil {
			logger.Warn("failed to resolve approvers", zap.Error(err), zap.String("rule_id", rule.ID))
			continue
		}

		for _, approverID := range approvers {
			task := &models.ApprovalTask{
				ID:         fmt.Sprintf("tsk_%s", uuid.New().String()[:8]),
				WorkflowID: workflowID,
				InstanceID: instanceID,
				RuleID:     rule.ID,
				ApproverID: approverID,
				Status:     StatusPending,
				Payload:    payloadBytes,
				CreatedAt:  time.Now(),
				UpdatedAt:  time.Now(),
				TenantID:   tenantID,
			}
			if err := e.db.WithContext(ctx).Create(task).Error; err != nil {
				logger.Error("failed to create approval task", zap.Error(err))
				return nil, err
			}
			allTasks = append(allTasks, task)
		}
	}

	return allTasks, nil
}

func (e *RuleEngine) Approve(ctx context.Context, taskID, approverID, comment string) error {
	now := time.Now()
	return e.db.WithContext(ctx).Model(&models.ApprovalTask{}).
		Where("id = ? AND approver_id = ?", taskID, approverID).
		Updates(map[string]interface{}{
			"status":        StatusApproved,
			"comment":       comment,
			"decision_time": &now,
			"updated_at":    now,
		}).Error
}

func (e *RuleEngine) Reject(ctx context.Context, taskID, approverID, comment string) error {
	now := time.Now()
	return e.db.WithContext(ctx).Model(&models.ApprovalTask{}).
		Where("id = ? AND approver_id = ?", taskID, approverID).
		Updates(map[string]interface{}{
			"status":        StatusRejected,
			"comment":       comment,
			"decision_time": &now,
			"updated_at":    now,
		}).Error
}

func (e *RuleEngine) CheckApprovalStatus(ctx context.Context, instanceID string) (string, error) {
	var tasks []*models.ApprovalTask
	if err := e.db.WithContext(ctx).Where("instance_id = ?", instanceID).Find(&tasks).Error; err != nil {
		return "", err
	}

	if len(tasks) == 0 {
		return StatusApproved, nil
	}

	grouped := make(map[string][]*models.ApprovalTask)
	for _, task := range tasks {
		grouped[task.RuleID] = append(grouped[task.RuleID], task)
	}

	allApproved := true
	for ruleID, ruleTasks := range grouped {
		var rule *models.ApprovalRule
		if err := e.db.WithContext(ctx).Where("id = ?", ruleID).First(&rule).Error; err != nil {
			continue
		}

		switch rule.Strategy {
		case StrategyAll:
			for _, t := range ruleTasks {
				if t.Status != StatusApproved {
					if t.Status == StatusRejected {
						return StatusRejected, nil
					}
					allApproved = false
				}
			}
		case StrategyAny, StrategyFirst:
			hasApproved := false
			hasRejected := 0
			for _, t := range ruleTasks {
				if t.Status == StatusApproved {
					hasApproved = true
					break
				}
				if t.Status == StatusRejected {
					hasRejected++
				}
			}
			if !hasApproved {
				if hasRejected == len(ruleTasks) {
					return StatusRejected, nil
				}
				allApproved = false
			}
		}
	}

	if allApproved {
		return StatusApproved, nil
	}
	return StatusPending, nil
}

func (e *RuleEngine) GetPendingTasks(ctx context.Context, approverID string) ([]*models.ApprovalTask, error) {
	var tasks []*models.ApprovalTask
	if err := e.db.WithContext(ctx).
		Where("approver_id = ? AND status = ?", approverID, StatusPending).
		Order("created_at DESC").
		Find(&tasks).Error; err != nil {
		return nil, err
	}
	return tasks, nil
}
