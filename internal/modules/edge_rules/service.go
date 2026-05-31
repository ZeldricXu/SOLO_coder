package edge_rules

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"edgescheduler/internal/common/database"
	"edgescheduler/internal/common/eventbus"
	"edgescheduler/internal/common/logger"
	"edgescheduler/pkg/utils"
)

type EdgeRulesEngine interface {
	CreateRule(ctx context.Context, req *RuleCreateRequest) (*Rule, error)
	GetRule(ctx context.Context, ruleID string) (*Rule, error)
	ListRules(ctx context.Context, ruleType RuleType, status RuleStatus, offset, limit int) ([]Rule, int64, error)
	UpdateRule(ctx context.Context, ruleID string, req *RuleUpdateRequest) (*Rule, error)
	DeleteRule(ctx context.Context, ruleID string) error
	EnableRule(ctx context.Context, ruleID string) (*Rule, error)
	DisableRule(ctx context.Context, ruleID string) (*Rule, error)

	EvaluateRules(ctx context.Context, eventType string, data map[string]interface{}) []string
	ExecuteRule(ctx context.Context, ruleID string, req *RuleExecutionRequest) (*RuleExecutionLog, error)

	GetExecutionLogs(ctx context.Context, ruleID string, offset, limit int) ([]RuleExecutionLog, int64, error)

	StartRuleEngine(ctx context.Context, workerCount int)
}

type edgeRulesEngineImpl struct {
	db          *gorm.DB
	eventBus    eventbus.EventBus
	rules       map[string]*Rule
	rulesMu     sync.RWMutex
	executionCh chan *executionJob
}

type executionJob struct {
	ctx         context.Context
	ruleID      string
	triggerEvent string
	data        map[string]interface{}
}

func NewEdgeRulesEngine() EdgeRulesEngine {
	return &edgeRulesEngineImpl{
		db:          database.GetDB(),
		eventBus:    eventbus.GetEventBus(),
		rules:       make(map[string]*Rule),
		executionCh: make(chan *executionJob, 10000),
	}
}

func (e *edgeRulesEngineImpl) CreateRule(ctx context.Context, req *RuleCreateRequest) (*Rule, error) {
	for i, cond := range req.Conditions {
		if cond.ID == "" {
			req.Conditions[i].ID = utils.GenerateID("cond")
		}
	}
	for i, act := range req.Actions {
		if act.ID == "" {
			req.Actions[i].ID = utils.GenerateID("act")
		}
		req.Actions[i].Enabled = true
	}

	if req.ConditionLogic == "" {
		req.ConditionLogic = "AND"
	}
	if req.TimeoutMs == 0 {
		req.TimeoutMs = 5000
	}

	rule := &Rule{
		RuleID:         utils.GenerateID("rule"),
		Name:           req.Name,
		Description:    req.Description,
		RuleType:       req.RuleType,
		Status:         RuleStatusEnabled,
		Priority:       req.Priority,
		DataSources:    req.DataSources,
		Conditions:     req.Conditions,
		ConditionLogic: req.ConditionLogic,
		Actions:        req.Actions,
		Trigger:        req.Trigger,
		TimeoutMs:      req.TimeoutMs,
		Retries:        req.Retries,
	}

	if err := e.db.Create(rule).Error; err != nil {
		return nil, fmt.Errorf("failed to create rule: %w", err)
	}

	e.rulesMu.Lock()
	e.rules[rule.RuleID] = rule
	e.rulesMu.Unlock()

	logger.Info("Rule created",
		zap.String("rule_id", rule.RuleID),
		zap.String("name", rule.Name),
		zap.String("rule_type", string(rule.RuleType)),
	)

	e.eventBus.Publish(ctx, eventbus.EventRuleCreated, map[string]interface{}{
		"rule_id":   rule.RuleID,
		"rule_name": rule.Name,
	}, "edge_rules")

	return rule, nil
}

func (e *edgeRulesEngineImpl) GetRule(ctx context.Context, ruleID string) (*Rule, error) {
	e.rulesMu.RLock()
	if rule, exists := e.rules[ruleID]; exists {
		e.rulesMu.RUnlock()
		return rule, nil
	}
	e.rulesMu.RUnlock()

	var rule Rule
	if err := e.db.Where("rule_id = ?", ruleID).First(&rule).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("rule not found")
		}
		return nil, err
	}

	e.rulesMu.Lock()
	e.rules[ruleID] = &rule
	e.rulesMu.Unlock()

	return &rule, nil
}

func (e *edgeRulesEngineImpl) ListRules(ctx context.Context, ruleType RuleType, status RuleStatus, offset, limit int) ([]Rule, int64, error) {
	var rules []Rule
	var total int64

	query := e.db.Model(&Rule{})
	if ruleType != "" {
		query = query.Where("rule_type = ?", ruleType)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("priority DESC, created_at DESC").Offset(offset).Limit(limit).Find(&rules).Error; err != nil {
		return nil, 0, err
	}

	return rules, total, nil
}

func (e *edgeRulesEngineImpl) UpdateRule(ctx context.Context, ruleID string, req *RuleUpdateRequest) (*Rule, error) {
	rule, err := e.GetRule(ctx, ruleID)
	if err != nil {
		return nil, err
	}

	updates := make(map[string]interface{})
	if req.Name != nil {
		updates["name"] = *req.Name
		rule.Name = *req.Name
	}
	if req.Description != nil {
		updates["description"] = *req.Description
		rule.Description = *req.Description
	}
	if req.Status != nil {
		updates["status"] = *req.Status
		rule.Status = *req.Status
	}
	if req.Priority != nil {
		updates["priority"] = *req.Priority
		rule.Priority = *req.Priority
	}
	if req.Conditions != nil {
		updates["conditions"] = *req.Conditions
		rule.Conditions = *req.Conditions
	}
	if req.ConditionLogic != nil {
		updates["condition_logic"] = *req.ConditionLogic
		rule.ConditionLogic = *req.ConditionLogic
	}
	if req.Actions != nil {
		updates["actions"] = *req.Actions
		rule.Actions = *req.Actions
	}

	if len(updates) > 0 {
		if err := e.db.Model(rule).Updates(updates).Error; err != nil {
			return nil, err
		}
	}

	e.rulesMu.Lock()
	e.rules[ruleID] = rule
	e.rulesMu.Unlock()

	logger.Info("Rule updated",
		zap.String("rule_id", ruleID),
	)

	return rule, nil
}

func (e *edgeRulesEngineImpl) DeleteRule(ctx context.Context, ruleID string) error {
	result := e.db.Where("rule_id = ?", ruleID).Delete(&Rule{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("rule not found")
	}

	e.rulesMu.Lock()
	delete(e.rules, ruleID)
	e.rulesMu.Unlock()

	e.db.Where("rule_id = ?", ruleID).Delete(&RuleExecutionLog{})

	logger.Info("Rule deleted",
		zap.String("rule_id", ruleID),
	)

	return nil
}

func (e *edgeRulesEngineImpl) EnableRule(ctx context.Context, ruleID string) (*Rule, error) {
	rule, err := e.GetRule(ctx, ruleID)
	if err != nil {
		return nil, err
	}

	rule.Status = RuleStatusEnabled
	if err := e.db.Save(rule).Error; err != nil {
		return nil, err
	}

	e.rulesMu.Lock()
	e.rules[ruleID] = rule
	e.rulesMu.Unlock()

	logger.Info("Rule enabled",
		zap.String("rule_id", ruleID),
	)

	return rule, nil
}

func (e *edgeRulesEngineImpl) DisableRule(ctx context.Context, ruleID string) (*Rule, error) {
	rule, err := e.GetRule(ctx, ruleID)
	if err != nil {
		return nil, err
	}

	rule.Status = RuleStatusDisabled
	if err := e.db.Save(rule).Error; err != nil {
		return nil, err
	}

	e.rulesMu.Lock()
	e.rules[ruleID] = rule
	e.rulesMu.Unlock()

	logger.Info("Rule disabled",
		zap.String("rule_id", ruleID),
	)

	return rule, nil
}

func (e *edgeRulesEngineImpl) EvaluateRules(ctx context.Context, eventType string, data map[string]interface{}) []string {
	e.rulesMu.RLock()
	rules := make([]*Rule, 0, len(e.rules))
	for _, rule := range e.rules {
		if rule.Status == RuleStatusEnabled {
			rules = append(rules, rule)
		}
	}
	e.rulesMu.RUnlock()

	matchedRules := make([]string, 0)

	for _, rule := range rules {
		if e.evaluateConditions(rule, data) {
			matchedRules = append(matchedRules, rule.RuleID)

			select {
			case e.executionCh <- &executionJob{
				ctx:          ctx,
				ruleID:       rule.RuleID,
				triggerEvent: eventType,
				data:         data,
			}:
			default:
				logger.Warn("Execution channel full, dropping rule execution",
					zap.String("rule_id", rule.RuleID),
				)
			}
		}
	}

	return matchedRules
}

func (e *edgeRulesEngineImpl) evaluateConditions(rule *Rule, data map[string]interface{}) bool {
	if len(rule.Conditions) == 0 {
		return true
	}

	logic := strings.ToUpper(rule.ConditionLogic)
	results := make([]bool, len(rule.Conditions))

	for i, cond := range rule.Conditions {
		results[i] = e.evaluateSingleCondition(cond, data)
	}

	if logic == "OR" {
		for _, r := range results {
			if r {
				return true
			}
		}
		return false
	}

	for _, r := range results {
		if !r {
			return false
		}
	}
	return true
}

func (e *edgeRulesEngineImpl) evaluateSingleCondition(cond RuleCondition, data map[string]interface{}) bool {
	fieldValue, exists := e.getNestedField(data, cond.Field)
	if !exists {
		return false
	}

	switch cond.Operator {
	case ConditionOpEqual:
		return fmt.Sprintf("%v", fieldValue) == fmt.Sprintf("%v", cond.Value)
	case ConditionOpNotEqual:
		return fmt.Sprintf("%v", fieldValue) != fmt.Sprintf("%v", cond.Value)
	case ConditionOpGreaterThan:
		return e.compareNumeric(fieldValue, cond.Value) > 0
	case ConditionOpGreaterOrEqual:
		return e.compareNumeric(fieldValue, cond.Value) >= 0
	case ConditionOpLessThan:
		return e.compareNumeric(fieldValue, cond.Value) < 0
	case ConditionOpLessOrEqual:
		return e.compareNumeric(fieldValue, cond.Value) <= 0
	case ConditionOpContains:
		strVal, ok1 := fieldValue.(string)
		searchVal, ok2 := cond.Value.(string)
		return ok1 && ok2 && strings.Contains(strVal, searchVal)
	case ConditionOpNotContains:
		strVal, ok1 := fieldValue.(string)
		searchVal, ok2 := cond.Value.(string)
		return ok1 && ok2 && !strings.Contains(strVal, searchVal)
	case ConditionOpIn:
		return e.checkInArray(fieldValue, cond.Value)
	case ConditionOpNotIn:
		return !e.checkInArray(fieldValue, cond.Value)
	case ConditionOpRegex:
		strVal, ok1 := fieldValue.(string)
		pattern, ok2 := cond.Value.(string)
		if !ok1 || !ok2 {
			return false
		}
		matched, _ := regexp.MatchString(pattern, strVal)
		return matched
	default:
		return false
	}
}

func (e *edgeRulesEngineImpl) getNestedField(data map[string]interface{}, path string) (interface{}, bool) {
	parts := strings.Split(path, ".")
	current := data

	for i, part := range parts {
		if i == len(parts)-1 {
			val, exists := current[part]
			return val, exists
		}

		next, exists := current[part]
		if !exists {
			return nil, false
		}

		nextMap, ok := next.(map[string]interface{})
		if !ok {
			return nil, false
		}
		current = nextMap
	}

	return nil, false
}

func (e *edgeRulesEngineImpl) compareNumeric(a, b interface{}) int {
	aFloat, ok1 := e.toFloat64(a)
	bFloat, ok2 := e.toFloat64(b)

	if !ok1 || !ok2 {
		return 0
	}

	if aFloat > bFloat {
		return 1
	} else if aFloat < bFloat {
		return -1
	}
	return 0
}

func (e *edgeRulesEngineImpl) toFloat64(v interface{}) (float64, bool) {
	switch val := v.(type) {
	case float64:
		return val, true
	case float32:
		return float64(val), true
	case int:
		return float64(val), true
	case int64:
		return float64(val), true
	case int32:
		return float64(val), true
	case json.Number:
		f, err := val.Float64()
		return f, err == nil
	default:
		return 0, false
	}
}

func (e *edgeRulesEngineImpl) checkInArray(value interface{}, array interface{}) bool {
	arr, ok := array.([]interface{})
	if !ok {
		return false
	}

	for _, item := range arr {
		if fmt.Sprintf("%v", value) == fmt.Sprintf("%v", item) {
			return true
		}
	}
	return false
}

func (e *edgeRulesEngineImpl) ExecuteRule(ctx context.Context, ruleID string, req *RuleExecutionRequest) (*RuleExecutionLog, error) {
	rule, err := e.GetRule(ctx, ruleID)
	if err != nil {
		return nil, err
	}

	return e.executeRuleInternal(ctx, rule, "manual", req.TriggerData)
}

func (e *edgeRulesEngineImpl) executeRuleInternal(ctx context.Context, rule *Rule, triggerEvent string, data map[string]interface{}) (*RuleExecutionLog, error) {
	startTime := time.Now()
	logEntry := &RuleExecutionLog{
		LogID:        utils.GenerateID("rex"),
		RuleID:       rule.RuleID,
		RuleName:     rule.Name,
		TriggerEvent: triggerEvent,
		TriggerData:  data,
		Status:       "executing",
	}

	defer func() {
		logEntry.ExecutionTimeMs = time.Since(startTime).Milliseconds()
		e.db.Create(logEntry)

		e.db.Model(rule).Updates(map[string]interface{}{
			"execution_count": gorm.Expr("execution_count + 1"),
			"last_executed_at": time.Now().UTC(),
		})

		if logEntry.Status == "success" {
			e.db.Model(rule).UpdateColumn("success_count", gorm.Expr("success_count + 1"))
		} else {
			e.db.Model(rule).Updates(map[string]interface{}{
				"failed_count": gorm.Expr("failed_count + 1"),
				"last_error":   logEntry.Error,
			})
		}

		e.eventBus.Publish(ctx, eventbus.EventRuleExecuted, map[string]interface{}{
			"rule_id": rule.RuleID,
			"status":  logEntry.Status,
		}, "edge_rules")
	}()

	var actionResults []map[string]interface{}

	for _, action := range rule.Actions {
		if !action.Enabled {
			continue
		}

		if action.DelayMs > 0 {
			time.Sleep(time.Duration(action.DelayMs) * time.Millisecond)
		}

		result, err := e.executeAction(ctx, action, data)
		if err != nil {
			logEntry.Status = "failed"
			logEntry.Error = fmt.Sprintf("action %s failed: %v", action.ID, err)

			for i := 0; i < rule.Retries; i++ {
				time.Sleep(time.Duration(100*(i+1)) * time.Millisecond)
				result, err = e.executeAction(ctx, action, data)
				if err == nil {
					logEntry.Status = "success"
					logEntry.Error = ""
					break
				}
			}

			if logEntry.Status == "failed" {
				return logEntry, err
			}
		}

		actionResults = append(actionResults, map[string]interface{}{
			"action_id": action.ID,
			"type":      action.Type,
			"result":    result,
		})
	}

	logEntry.Status = "success"
	logEntry.Result = map[string]interface{}{
		"actions": actionResults,
	}

	logger.Info("Rule executed successfully",
		zap.String("rule_id", rule.RuleID),
		zap.String("rule_name", rule.Name),
		zap.Int64("execution_time_ms", logEntry.ExecutionTimeMs),
	)

	return logEntry, nil
}

func (e *edgeRulesEngineImpl) executeAction(ctx context.Context, action RuleAction, data map[string]interface{}) (interface{}, error) {
	switch action.Type {
	case ActionTypeLocalCommand:
		return e.executeLocalCommand(action.Parameters)
	case ActionTypeAPICall:
		return e.executeAPICall(ctx, action.Parameters)
	case ActionTypeMQTTPublish:
		return e.executeMQTTPublish(action.Parameters, data)
	case ActionTypeSetDesired:
		return e.executeSetDesired(ctx, action.Parameters, data)
	case ActionTypeTriggerRule:
		return e.executeTriggerRule(ctx, action.Parameters, data)
	case ActionTypeAlert:
		return e.executeAlert(action.Parameters, data)
	default:
		return nil, fmt.Errorf("unknown action type: %s", action.Type)
	}
}

func (e *edgeRulesEngineImpl) executeLocalCommand(params map[string]interface{}) (interface{}, error) {
	command, ok := params["command"].(string)
	if !ok {
		return nil, errors.New("command parameter required")
	}

	logger.Info("Executing local command",
		zap.String("command", command),
	)

	return map[string]interface{}{
		"command":     command,
		"executed":    true,
		"simulated":   true,
		"exit_code":   0,
	}, nil
}

func (e *edgeRulesEngineImpl) executeAPICall(ctx context.Context, params map[string]interface{}) (interface{}, error) {
	url, ok := params["url"].(string)
	if !ok {
		return nil, errors.New("url parameter required")
	}

	method, _ := params["method"].(string)
	if method == "" {
		method = "POST"
	}

	logger.Info("Executing API call",
		zap.String("method", method),
		zap.String("url", url),
	)

	return map[string]interface{}{
		"url":       url,
		"method":    method,
		"status":    "simulated_success",
		"http_code": 200,
	}, nil
}

func (e *edgeRulesEngineImpl) executeMQTTPublish(params map[string]interface{}, data map[string]interface{}) (interface{}, error) {
	topic, ok := params["topic"].(string)
	if !ok {
		return nil, errors.New("topic parameter required")
	}

	payload := params["payload"]
	if payload == nil {
		payload = data
	}

	logger.Info("Publishing MQTT message",
		zap.String("topic", topic),
	)

	e.eventBus.Publish(context.Background(), eventbus.EventMQTTMessagePublished, map[string]interface{}{
		"topic":   topic,
		"payload": payload,
	}, "edge_rules")

	return map[string]interface{}{
		"topic":   topic,
		"published": true,
	}, nil
}

func (e *edgeRulesEngineImpl) executeSetDesired(ctx context.Context, params map[string]interface{}, data map[string]interface{}) (interface{}, error) {
	deviceID, ok := params["device_id"].(string)
	if !ok {
		return nil, errors.New("device_id parameter required")
	}

	desired, ok := params["desired"].(map[string]interface{})
	if !ok {
		desired = data
	}

	e.eventBus.Publish(ctx, eventbus.EventShadowDesiredUpdated, map[string]interface{}{
		"device_id": deviceID,
		"desired":   desired,
	}, "edge_rules")

	return map[string]interface{}{
		"device_id": deviceID,
		"desired":   desired,
	}, nil
}

func (e *edgeRulesEngineImpl) executeTriggerRule(ctx context.Context, params map[string]interface{}, data map[string]interface{}) (interface{}, error) {
	targetRuleID, ok := params["rule_id"].(string)
	if !ok {
		return nil, errors.New("rule_id parameter required")
	}

	triggerData := data
	if td, ok := params["data"].(map[string]interface{}); ok {
		triggerData = td
	}

	select {
	case e.executionCh <- &executionJob{
		ctx:          ctx,
		ruleID:       targetRuleID,
		triggerEvent: "rule_chain",
		data:         triggerData,
	}:
	default:
		return nil, errors.New("execution queue full")
	}

	return map[string]interface{}{
		"target_rule_id": targetRuleID,
		"triggered":      true,
	}, nil
}

func (e *edgeRulesEngineImpl) executeAlert(params map[string]interface{}, data map[string]interface{}) (interface{}, error) {
	level, _ := params["level"].(string)
	message, ok := params["message"].(string)
	if !ok {
		message = "Rule triggered alert"
	}

	channels, _ := params["channels"].([]interface{})

	logger.Warn("Alert triggered by rule",
		zap.String("level", level),
		zap.String("message", message),
	)

	e.eventBus.Publish(context.Background(), eventbus.EventAlertTriggered, map[string]interface{}{
		"level":    level,
		"message":  message,
		"channels": channels,
		"data":     data,
	}, "edge_rules")

	return map[string]interface{}{
		"level":    level,
		"message":  message,
		"alerted":  true,
	}, nil
}

func (e *edgeRulesEngineImpl) GetExecutionLogs(ctx context.Context, ruleID string, offset, limit int) ([]RuleExecutionLog, int64, error) {
	var logs []RuleExecutionLog
	var total int64

	query := e.db.Model(&RuleExecutionLog{})
	if ruleID != "" {
		query = query.Where("rule_id = ?", ruleID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("created_at DESC").Offset(offset).Limit(limit).Find(&logs).Error; err != nil {
		return nil, 0, err
	}

	return logs, total, nil
}

func (e *edgeRulesEngineImpl) StartRuleEngine(ctx context.Context, workerCount int) {
	logger.Info("Starting edge rule engine",
		zap.Int("workers", workerCount),
	)

	e.loadRules()

	e.subscribeToEvents(ctx)

	for i := 0; i < workerCount; i++ {
		go e.executionWorker(ctx, i)
	}
}

func (e *edgeRulesEngineImpl) loadRules() {
	var rules []Rule
	e.db.Where("status = ?", RuleStatusEnabled).Find(&rules)

	e.rulesMu.Lock()
	defer e.rulesMu.Unlock()

	for i := range rules {
		e.rules[rules[i].RuleID] = &rules[i]
	}

	logger.Info("Loaded rules into engine",
		zap.Int("count", len(rules)),
	)
}

func (e *edgeRulesEngineImpl) subscribeToEvents(ctx context.Context) {
	events := []string{
		eventbus.EventProtocolDataReceived,
		eventbus.EventDeviceStatusChanged,
		eventbus.EventDataCached,
		eventbus.EventAggregationResult,
		eventbus.EventShadowReportedUpdated,
	}

	for _, eventName := range events {
		evt := eventName
		e.eventBus.Subscribe(ctx, evt, func(ctx context.Context, event eventbus.Event) {
			data, ok := event.Payload.(map[string]interface{})
			if !ok {
				data = make(map[string]interface{})
			}
			data["event_name"] = evt
			data["source"] = event.Source

			e.EvaluateRules(ctx, evt, data)
		})
	}
}

func (e *edgeRulesEngineImpl) executionWorker(ctx context.Context, workerID int) {
	logger.Debug("Rule execution worker started",
		zap.Int("worker_id", workerID),
	)

	for {
		select {
		case <-ctx.Done():
			logger.Debug("Rule execution worker stopped",
				zap.Int("worker_id", workerID),
			)
			return
		case job := <-e.executionCh:
			rule, err := e.GetRule(job.ctx, job.ruleID)
			if err != nil {
				logger.Warn("Rule not found for execution",
					zap.String("rule_id", job.ruleID),
					zap.Error(err),
				)
				continue
			}

			_, _ = e.executeRuleInternal(job.ctx, rule, job.triggerEvent, job.data)
		}
	}
}
