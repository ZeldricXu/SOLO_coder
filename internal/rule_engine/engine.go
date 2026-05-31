package rule_engine

import (
	"context"
	"fmt"
	"reflect"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/edgeplatform/session306/internal/data"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/pkg/errors"
	"github.com/edgeplatform/session306/pkg/events"
	"github.com/edgeplatform/session306/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type ActionExecutor interface {
	Execute(ctx context.Context, action model.RuleAction, data map[string]interface{}) error
}

type baseExecutor struct {
	logger *zap.Logger
}

func (e *baseExecutor) logAction(actionType string, params map[string]interface{}, paramName string) error {
	param, ok := params[paramName].(string)
	if !ok {
		return errors.NewValidationError(fmt.Sprintf("%s action missing '%s' parameter", actionType, paramName))
	}
	e.logger.Debug("Executing action",
		zap.String("type", actionType),
		zap.String(paramName, param),
	)
	return nil
}

type HTTPActionExecutor struct {
	baseExecutor
}

func (e *HTTPActionExecutor) Execute(ctx context.Context, action model.RuleAction, data map[string]interface{}) error {
	return e.logAction("HTTP", action.Parameters, "url")
}

type CommandActionExecutor struct {
	baseExecutor
}

func (e *CommandActionExecutor) Execute(ctx context.Context, action model.RuleAction, data map[string]interface{}) error {
	return e.logAction("Command", action.Parameters, "command")
}

type NotificationActionExecutor struct {
	baseExecutor
}

func (e *NotificationActionExecutor) Execute(ctx context.Context, action model.RuleAction, data map[string]interface{}) error {
	return e.logAction("Notification", action.Parameters, "message")
}

type WebhookActionExecutor struct {
	baseExecutor
}

func (e *WebhookActionExecutor) Execute(ctx context.Context, action model.RuleAction, data map[string]interface{}) error {
	return e.logAction("Webhook", action.Parameters, "url")
}

type RuleEngine struct {
	da          *data.DataAccess
	eventBus    events.EventBus
	logger      *zap.Logger
	executors   map[string]ActionExecutor
	rules       map[string]*model.Rule
	rulesByDev  map[string]map[string]*model.Rule
	rulesMu     sync.RWMutex
	workerCount int
	taskQueue   chan *model.RuleTriggerEvent
}

func NewRuleEngine(da *data.DataAccess, eb events.EventBus, log *zap.Logger, workerCount int) *RuleEngine {
	if workerCount <= 0 {
		workerCount = 5
	}
	re := &RuleEngine{
		da:          da,
		eventBus:    eb,
		logger:      log,
		executors:   make(map[string]ActionExecutor),
		rules:       make(map[string]*model.Rule),
		rulesByDev:  make(map[string]map[string]*model.Rule),
		workerCount: workerCount,
		taskQueue:   make(chan *model.RuleTriggerEvent, 1000),
	}

	re.executors["http_request"] = &HTTPActionExecutor{baseExecutor{logger: log}}
	re.executors["mqtt_publish"] = &HTTPActionExecutor{baseExecutor{logger: log}}
	re.executors["command"] = &CommandActionExecutor{baseExecutor{logger: log}}
	re.executors["notification"] = &NotificationActionExecutor{baseExecutor{logger: log}}
	re.executors["webhook"] = &WebhookActionExecutor{baseExecutor{logger: log}}

	return re
}

func (re *RuleEngine) Start(ctx context.Context) error {
	if err := re.loadRules(ctx); err != nil {
		return err
	}

	for i := 0; i < re.workerCount; i++ {
		go re.worker(ctx, i)
	}

	go re.watchDeviceEvents(ctx)

	re.logger.Info("Rule engine started", zap.Int("workers", re.workerCount))
	return nil
}

func (re *RuleEngine) loadRules(ctx context.Context) error {
	var rules []model.Rule
	if err := re.da.DB().WithContext(ctx).Where("enabled = ?", true).Find(&rules).Error; err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal, "failed to load rules")
	}

	re.rulesMu.Lock()
	defer re.rulesMu.Unlock()

	re.rules = make(map[string]*model.Rule, len(rules))
	re.rulesByDev = make(map[string]map[string]*model.Rule)

	for i := range rules {
		rule := &rules[i]
		re.rules[rule.RuleID] = rule
		if re.rulesByDev[rule.DeviceID] == nil {
			re.rulesByDev[rule.DeviceID] = make(map[string]*model.Rule)
		}
		re.rulesByDev[rule.DeviceID][rule.RuleID] = rule
	}

	re.logger.Info("Rules loaded", zap.Int("count", len(rules)))
	return nil
}

func (re *RuleEngine) addRuleToMemory(rule *model.Rule) {
	re.rulesMu.Lock()
	defer re.rulesMu.Unlock()

	re.rules[rule.RuleID] = rule
	if re.rulesByDev[rule.DeviceID] == nil {
		re.rulesByDev[rule.DeviceID] = make(map[string]*model.Rule)
	}
	re.rulesByDev[rule.DeviceID][rule.RuleID] = rule
}

func (re *RuleEngine) removeRuleFromMemory(ruleID, deviceID string) {
	re.rulesMu.Lock()
	defer re.rulesMu.Unlock()

	delete(re.rules, ruleID)
	if devRules, ok := re.rulesByDev[deviceID]; ok {
		delete(devRules, ruleID)
		if len(devRules) == 0 {
			delete(re.rulesByDev, deviceID)
		}
	}
}

func (re *RuleEngine) getDeviceRules(deviceID string) []*model.Rule {
	re.rulesMu.RLock()
	defer re.rulesMu.RUnlock()

	devRules, ok := re.rulesByDev[deviceID]
	if !ok {
		return nil
	}

	rules := make([]*model.Rule, 0, len(devRules))
	for _, rule := range devRules {
		rules = append(rules, rule)
	}
	return rules
}

func (re *RuleEngine) CreateRule(ctx context.Context, req *model.RuleCreateRequest) (*model.Rule, error) {
	rule := &model.Rule{
		Name:            req.Name,
		Description:     req.Description,
		DeviceID:        req.DeviceID,
		Enabled:         req.Enabled,
		Conditions:      req.Conditions,
		Actions:         req.Actions,
		MatchAll:        req.MatchAll,
		CooldownSeconds: req.CooldownSeconds,
		Tags:            req.Tags,
		CreatedAt:       utils.NowUTC(),
		UpdatedAt:       utils.NowUTC(),
	}
	rule.RuleID = utils.GenerateID("rule")

	if rule.CooldownSeconds <= 0 {
		rule.CooldownSeconds = 60
	}

	if err := re.da.DB().WithContext(ctx).Create(rule).Error; err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to create rule")
	}

	if rule.Enabled {
		re.addRuleToMemory(rule)
	}

	re.logger.Info("Rule created",
		zap.String("rule_id", rule.RuleID),
		zap.String("device_id", rule.DeviceID),
	)
	return rule, nil
}

func (re *RuleEngine) GetRule(ctx context.Context, ruleID string) (*model.Rule, error) {
	var rule model.Rule
	err := re.da.DB().WithContext(ctx).Where("rule_id = ?", ruleID).First(&rule).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("rule not found")
	}
	return &rule, err
}

func (re *RuleEngine) ListRules(ctx context.Context, deviceID string, offset, limit int) ([]model.Rule, int64, error) {
	var rules []model.Rule
	var total int64

	query := re.da.DB().WithContext(ctx).Model(&model.Rule{})
	if deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("created_at DESC").Find(&rules).Error
	return rules, total, err
}

func (re *RuleEngine) EnableRule(ctx context.Context, ruleID string, enabled bool) error {
	now := utils.NowUTC()
	err := re.da.DB().WithContext(ctx).Model(&model.Rule{}).
		Where("rule_id = ?", ruleID).
		Updates(map[string]interface{}{
			"enabled":    enabled,
			"updated_at": now,
		}).Error
	if err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal, "failed to update rule status")
	}

	rule, err := re.GetRule(ctx, ruleID)
	if err != nil {
		return err
	}

	if enabled {
		re.addRuleToMemory(rule)
	} else {
		re.removeRuleFromMemory(ruleID, rule.DeviceID)
	}

	re.logger.Info("Rule status updated",
		zap.String("rule_id", ruleID),
		zap.Bool("enabled", enabled),
	)
	return nil
}

func (re *RuleEngine) DeleteRule(ctx context.Context, ruleID string) error {
	rule, err := re.GetRule(ctx, ruleID)
	if err != nil {
		return err
	}

	if err := re.da.DB().WithContext(ctx).Delete(&model.Rule{}, "rule_id = ?", ruleID).Error; err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal, "failed to delete rule")
	}

	re.removeRuleFromMemory(ruleID, rule.DeviceID)

	re.logger.Info("Rule deleted", zap.String("rule_id", ruleID))
	return nil
}

func (re *RuleEngine) Evaluate(ctx context.Context, deviceID string, data map[string]interface{}) ([]string, error) {
	rules := re.getDeviceRules(deviceID)
	if len(rules) == 0 {
		return nil, nil
	}

	triggered := make([]string, 0)

	for _, rule := range rules {
		if !rule.Enabled {
			continue
		}

		if isInCooldown(rule) {
			continue
		}

		matched, err := re.evaluateConditions(rule.Conditions, rule.MatchAll, data)
		if err != nil {
			re.logger.Warn("Rule evaluation failed",
				zap.String("rule_id", rule.RuleID),
				zap.Error(err),
			)
			continue
		}

		if matched {
			event := &model.RuleTriggerEvent{
				RuleID:    rule.RuleID,
				DeviceID:  deviceID,
				Timestamp: utils.NowUTC(),
				Data:      data,
			}

			select {
			case re.taskQueue <- event:
				triggered = append(triggered, rule.RuleID)
			default:
				re.logger.Warn("Rule engine task queue full, dropping event",
					zap.String("rule_id", rule.RuleID),
				)
			}
		}
	}

	return triggered, nil
}

func isInCooldown(rule *model.Rule) bool {
	if rule.LastTriggeredAt == nil || rule.CooldownSeconds <= 0 {
		return false
	}
	return time.Since(*rule.LastTriggeredAt).Seconds() < float64(rule.CooldownSeconds)
}

func (re *RuleEngine) evaluateConditions(conditions []model.RuleCondition, matchAll bool, data map[string]interface{}) (bool, error) {
	if len(conditions) == 0 {
		return true, nil
	}

	for _, cond := range conditions {
		matched, err := re.evaluateCondition(cond, data)
		if err != nil {
			return false, err
		}
		if matched && !matchAll {
			return true, nil
		}
		if !matched && matchAll {
			return false, nil
		}
	}

	return true, nil
}

func (re *RuleEngine) evaluateCondition(cond model.RuleCondition, data map[string]interface{}) (bool, error) {
	fieldValue, err := getNestedValue(data, cond.Field)
	if err != nil {
		return false, nil
	}

	switch cond.Operator {
	case model.OpEqual:
		return reflect.DeepEqual(fieldValue, cond.Value), nil
	case model.OpNotEqual:
		return !reflect.DeepEqual(fieldValue, cond.Value), nil
	case model.OpGreaterThan:
		return compareNumbers(fieldValue, cond.Value) > 0, nil
	case model.OpLessThan:
		return compareNumbers(fieldValue, cond.Value) < 0, nil
	case model.OpGreaterEqual:
		return compareNumbers(fieldValue, cond.Value) >= 0, nil
	case model.OpLessEqual:
		return compareNumbers(fieldValue, cond.Value) <= 0, nil
	case model.OpContains:
		return containsValue(fieldValue, cond.Value), nil
	case model.OpIn:
		return isInSlice(cond.Value, fieldValue), nil
	case model.OpNotIn:
		return !isInSlice(cond.Value, fieldValue), nil
	case model.OpRegex:
		return matchRegex(fieldValue, cond.Value)
	}

	return false, fmt.Errorf("unsupported operator: %s", cond.Operator)
}

func getNestedValue(data map[string]interface{}, field string) (interface{}, error) {
	parts := strings.Split(field, ".")
	current := data

	for i, part := range parts {
		if i == len(parts)-1 {
			val, ok := current[part]
			if !ok {
				return nil, fmt.Errorf("field not found: %s", field)
			}
			return val, nil
		}

		next, ok := current[part]
		if !ok {
			return nil, fmt.Errorf("field not found: %s", field)
		}

		nextMap, ok := next.(map[string]interface{})
		if !ok {
			return nil, fmt.Errorf("field is not an object: %s", part)
		}
		current = nextMap
	}

	return nil, fmt.Errorf("field not found: %s", field)
}

func compareNumbers(a, b interface{}) int {
	af := toFloat64(a)
	bf := toFloat64(b)
	if af > bf {
		return 1
	} else if af < bf {
		return -1
	}
	return 0
}

func toFloat64(v interface{}) float64 {
	switch val := v.(type) {
	case int:
		return float64(val)
	case int32:
		return float64(val)
	case int64:
		return float64(val)
	case float32:
		return float64(val)
	case float64:
		return val
	default:
		return 0
	}
}

func containsValue(field, value interface{}) bool {
	switch f := field.(type) {
	case string:
		if v, ok := value.(string); ok {
			return strings.Contains(f, v)
		}
	case []interface{}:
		for _, item := range f {
			if reflect.DeepEqual(item, value) {
				return true
			}
		}
	case []string:
		if v, ok := value.(string); ok {
			for _, item := range f {
				if item == v {
					return true
				}
			}
		}
	}
	return false
}

func isInSlice(sliceValue, item interface{}) bool {
	slice, ok := sliceValue.([]interface{})
	if !ok {
		return false
	}
	for _, s := range slice {
		if reflect.DeepEqual(s, item) {
			return true
		}
	}
	return false
}

func matchRegex(field, pattern interface{}) (bool, error) {
	fieldStr, ok := field.(string)
	if !ok {
		return false, nil
	}
	patternStr, ok := pattern.(string)
	if !ok {
		return false, nil
	}
	matched, err := regexp.MatchString(patternStr, fieldStr)
	if err != nil {
		return false, errors.NewValidationError(fmt.Sprintf("invalid regex pattern: %v", err))
	}
	return matched, nil
}

func (re *RuleEngine) worker(ctx context.Context, workerID int) {
	re.logger.Debug("Rule engine worker started", zap.Int("worker_id", workerID))

	for {
		select {
		case <-ctx.Done():
			re.logger.Debug("Rule engine worker stopped", zap.Int("worker_id", workerID))
			return
		case event := <-re.taskQueue:
			re.processEvent(ctx, event)
		}
	}
}

func (re *RuleEngine) processEvent(ctx context.Context, event *model.RuleTriggerEvent) {
	re.rulesMu.RLock()
	rule, ok := re.rules[event.RuleID]
	re.rulesMu.RUnlock()

	if !ok || !rule.Enabled {
		return
	}

	re.updateRuleTriggerStats(ctx, event.RuleID)
	re.executeActions(ctx, rule, event)
	re.publishTriggerEvent(ctx, event)

	re.logger.Info("Rule triggered and actions executed",
		zap.String("rule_id", event.RuleID),
		zap.String("device_id", event.DeviceID),
	)
}

func (re *RuleEngine) updateRuleTriggerStats(ctx context.Context, ruleID string) {
	now := utils.NowUTC()
	if err := re.da.DB().WithContext(ctx).Model(&model.Rule{}).
		Where("rule_id = ?", ruleID).
		Updates(map[string]interface{}{
			"last_triggered_at": now,
			"trigger_count":     gorm.Expr("trigger_count + 1"),
			"updated_at":        now,
		}).Error; err != nil {
		re.logger.Warn("Failed to update rule trigger stats",
			zap.String("rule_id", ruleID),
			zap.Error(err),
		)
	}

	re.rulesMu.Lock()
	if r, ok := re.rules[ruleID]; ok {
		r.LastTriggeredAt = &now
		r.TriggerCount++
	}
	re.rulesMu.Unlock()
}

func (re *RuleEngine) executeActions(ctx context.Context, rule *model.Rule, event *model.RuleTriggerEvent) {
	for _, action := range rule.Actions {
		executor, ok := re.executors[action.Type]
		if !ok {
			re.logger.Warn("No executor for action type",
				zap.String("action_type", action.Type),
				zap.String("rule_id", event.RuleID),
			)
			continue
		}

		if err := executor.Execute(ctx, action, event.Data); err != nil {
			re.logger.Error("Action execution failed",
				zap.String("action_type", action.Type),
				zap.String("rule_id", event.RuleID),
				zap.Error(err),
			)
		}
	}
}

func (re *RuleEngine) publishTriggerEvent(ctx context.Context, event *model.RuleTriggerEvent) {
	evt := events.Event{
		ID:        utils.GenerateID("evt"),
		Type:      events.EventRuleTriggered,
		Source:    "rule_engine",
		Timestamp: utils.NowUTC(),
		Payload: map[string]interface{}{
			"rule_id":   event.RuleID,
			"device_id": event.DeviceID,
			"data":      event.Data,
		},
	}

	traceID, ok := ctx.Value("trace_id").(string)
	if ok {
		evt.TraceID = traceID
	}

	_ = re.eventBus.Publish(ctx, evt)
}

func (re *RuleEngine) watchDeviceEvents(ctx context.Context) {
	re.eventBus.Subscribe(events.EventDeviceOnline, func(ctx context.Context, event events.Event) error {
		deviceID, _ := event.Payload["device_id"].(string)
		data, _ := event.Payload["data"].(map[string]interface{})
		if deviceID != "" {
			re.Evaluate(ctx, deviceID, data)
		}
		return nil
	})
}

func (re *RuleEngine) Stop() {
	close(re.taskQueue)
	re.logger.Info("Rule engine stopped")
}
