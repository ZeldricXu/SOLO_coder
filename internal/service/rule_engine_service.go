package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/database"
	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/edgevision/edgevision/pkg/utils"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type RuleEngineService struct {
	db          *gorm.DB
	cron        *cron.Cron
	running     map[string]bool
	eventChan     chan RuleEvent
}

type RuleEvent struct {
	DeviceID string                 `json:"device_id"`
	EventType string               `json:"event_type"`
	Data      map[string]interface{} `json:"data"`
}

func NewRuleEngineService() *RuleEngineService {
	service := &RuleEngineService{
		db:      database.GetDB(),
		cron:    cron.New(),
		running: make(map[string]bool),
		eventChan: make(chan RuleEvent, 1000),
	}

	service.cron.Start()

	go service.processEvents()
	go service.startConditionChecker()

	return service
}

type CreateRuleRequest struct {
	Name            string                 `json:"name"`
	Description     string                 `json:"description"`
	RuleType        string                 `json:"rule_type"`
	TriggerType     string                 `json:"trigger_type"`
	Condition       string                 `json:"condition"`
	ConditionConfig map[string]interface{} `json:"condition_config"`
	Actions         []model.RuleAction     `json:"actions"`
	DeviceIDs       []string               `json:"device_ids"`
	Priority        int                    `json:"priority"`
	ExecutionMode   string                 `json:"execution_mode"`
	Timeout         int                    `json:"timeout"`
	RetryCount      int                    `json:"retry_count"`
	Metadata        map[string]interface{} `json:"metadata"`
}

func (s *RuleEngineService) CreateRule(ctx context.Context, req *CreateRuleRequest) (*model.Rule, error) {
	rule := &model.Rule{
		ID:              utils.GenerateID("rule"),
		Name:            req.Name,
		Description:     req.Description,
		RuleType:        req.RuleType,
		TriggerType:     req.TriggerType,
		Condition:       req.Condition,
		ConditionConfig: req.ConditionConfig,
		Actions:         req.Actions,
		DeviceIDs:       req.DeviceIDs,
		Priority:        req.Priority,
		IsEnabled:     true,
		ExecutionMode: req.ExecutionMode,
		Timeout:       req.Timeout,
		RetryCount:    req.RetryCount,
		Metadata:      req.Metadata,
		CreatedAt:     utils.Now(),
		UpdatedAt:     utils.Now(),
	}

	if err := s.db.Create(rule).Error; err != nil {
		logger.Get().Error("failed to create rule", zap.Error(err))
		return nil, err
	}

	if rule.TriggerType == model.TriggerTypeCron {
		_ = s.scheduleCronRule(rule)
	}

	return rule, nil
}

func (s *RuleEngineService) scheduleCronRule(rule *model.Rule) error {
	cronExpr, ok := rule.ConditionConfig["cron_expression"].(string)
	if !ok {
		return errors.New("cron expression not found")
	}

	_, err := s.cron.AddFunc(cronExpr, func() {
		ctx := context.Background()
		s.TriggerRule(ctx, rule.ID, nil)
	})

	if err == nil {
		s.running[rule.ID] = true
		logger.Get().Info("cron rule scheduled",
			zap.String("rule_id", rule.ID),
			zap.String("cron", cronExpr))
	}

	return err
}

func (s *RuleEngineService) GetRule(ctx context.Context, ruleID string) (*model.Rule, error) {
	var rule model.Rule
	if err := s.db.First(&rule, "id = ?", ruleID).Error; err != nil {
		return nil, err
	}
	return &rule, nil
}

func (s *RuleEngineService) ListRules(ctx context.Context, page, pageSize int, ruleType, triggerType string) ([]model.Rule, int64, error) {
	var rules []model.Rule
	var total int64

	query := s.db.Model(&model.Rule{})
	if ruleType != "" {
		query = query.Where("rule_type = ?", ruleType)
	}
	if triggerType != "" {
		query = query.Where("trigger_type = ?", triggerType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&rules).Error; err != nil {
		return nil, 0, err
	}

	return rules, total, nil
}

func (s *RuleEngineService) UpdateRule(ctx context.Context, ruleID string, updates map[string]interface{}) (*model.Rule, error) {
	var rule model.Rule
	if err := s.db.First(&rule, "id = ?", ruleID).Error; err != nil {
		return nil, err
	}

	if err := s.db.Model(&rule).Updates(updates).Error; err != nil {
		return nil, err
	}

	if err := s.db.First(&rule, "id = ?", ruleID).Error; err != nil {
		return nil, err
	}

	rule.UpdatedAt = utils.Now()
	_ = s.db.Save(&rule)

	return &rule, nil
}

func (s *RuleEngineService) DeleteRule(ctx context.Context, ruleID string) error {
	if s.running[ruleID] {
		delete(s.running, ruleID)
	}

	return s.db.Delete(&model.Rule{}, ruleID).Error
}

func (s *RuleEngineService) EnableRule(ctx context.Context, ruleID string) (*model.Rule, error) {
	var rule model.Rule
	if err := s.db.First(&rule, "id = ?", ruleID).Error; err != nil {
		return nil, err
	}

	rule.IsEnabled = true
	rule.UpdatedAt = utils.Now()

	if err := s.db.Save(&rule).Error; err != nil {
		return nil, err
	}

	if rule.TriggerType == model.TriggerTypeCron && !s.running[ruleID] {
		_ = s.scheduleCronRule(&rule)
	}

	return &rule, nil
}

func (s *RuleEngineService) DisableRule(ctx context.Context, ruleID string) (*model.Rule, error) {
	var rule model.Rule
	if err := s.db.First(&rule, "id = ?", ruleID).Error; err != nil {
		return nil, err
	}

	rule.IsEnabled = false
	rule.UpdatedAt = utils.Now()

	if err := s.db.Save(&rule).Error; err != nil {
		return nil, err
	}

	if s.running[ruleID] {
		delete(s.running, ruleID)
	}

	return &rule, nil
}

func (s *RuleEngineService) TriggerRule(ctx context.Context, ruleID string, eventData map[string]interface{}) (*model.RuleExecution, error) {
	var rule model.Rule
	if err := s.db.First(&rule, "id = ?", ruleID).Error; err != nil {
		return nil, err
	}

	if !rule.IsEnabled {
		return nil, errors.New("rule is disabled")
	}

	execution := &model.RuleExecution{
		ID:          utils.GenerateID("re"),
		RuleID:      ruleID,
		TriggerType: rule.TriggerType,
		TriggerData: eventData,
		Status:      model.ExecutionStatusRunning,
		Timestamp:   utils.Now(),
		CreatedAt:   utils.Now(),
	}

	if err := s.db.Create(execution).Error; err != nil {
		return nil, err
	}

	go s.executeRule(rule, execution, eventData)

	return execution, nil
}

func (s *RuleEngineService) executeRule(rule model.Rule, execution *model.RuleExecution, eventData map[string]interface{}) {
	startTime := time.Now()

	ctx := context.Background()

	rule.TriggerCount++
	now := utils.Now()
	rule.LastTriggered = &now
	_ = s.db.Save(&rule)

	actions := make([]model.ActionExecution, 0, len(rule.Actions))
	allSuccess := true

	for _, action := range rule.Actions {
		actionExec := s.executeAction(ctx, action, eventData)
		actions = append(actions, actionExec)

		if actionExec.Status != model.ExecutionStatusSuccess {
			allSuccess = false
		}
	}

	execution.Actions = actions

	if allSuccess {
		execution.Status = model.ExecutionStatusSuccess
	} else {
		execution.Status = model.ExecutionStatusFailed
	}

	execution.DurationMs = time.Since(startTime).Milliseconds()
	_ = s.db.Save(execution)

	logger.Get().Info("rule execution completed",
		zap.String("rule_id", rule.ID),
		zap.String("execution_id", execution.ID),
		zap.String("status", execution.Status))
}

func (s *RuleEngineService) executeAction(ctx context.Context, action model.RuleAction, eventData map[string]interface{}) model.ActionExecution {
	actionExec := model.ActionExecution{
		ActionType: action.ActionType,
		Target:     action.Target,
		Status:   model.ExecutionStatusRunning,
		StartedAt: utils.Now(),
	}

	startTime := time.Now()

	var err error
	switch action.ActionType {
	case model.ActionTypeDeviceControl:
		err = s.actionDeviceControl(ctx, action, eventData)
	case model.ActionTypeSendAlert:
		err = s.actionSendAlert(ctx, action, eventData)
	case model.ActionTypeHTTPRequest:
		err = s.actionHTTPRequest(ctx, action, eventData)
	case model.ActionTypeMQTTPublish:
		err = s.actionMQTTPublish(ctx, action, eventData)
	case model.ActionTypeSetShadow:
		err = s.actionSetShadow(ctx, action, eventData)
	case model.ActionTypeTriggerRule:
		err = s.actionTriggerRule(ctx, action, eventData)
	default:
		err = errors.New("unknown action type")
	}

	completedAt := utils.Now()
	actionExec.CompletedAt = &completedAt
	actionExec.DurationMs = time.Since(startTime).Milliseconds()

	if err != nil {
		actionExec.Status = model.ExecutionStatusFailed
		errMsg := err.Error()
		actionExec.ErrorMessage = &errMsg
	} else {
		actionExec.Status = model.ExecutionStatusSuccess
	}

	return actionExec
}

func (s *RuleEngineService) actionDeviceControl(ctx context.Context, action model.RuleAction, eventData map[string]interface{}) error {
	logger.Get().Info("executing device control action",
		zap.String("target", action.Target))
	return nil
}

func (s *RuleEngineService) actionSendAlert(ctx context.Context, action model.RuleAction, eventData map[string]interface{}) error {
	logger.Get().Info("executing send alert action",
		zap.String("target", action.Target))
	return nil
}

func (s *RuleEngineService) actionHTTPRequest(ctx context.Context, action model.RuleAction, eventData map[string]interface{}) error {
	url, ok := action.Parameters["url"].(string)
	if !ok {
		return errors.New("url not found in action parameters")
	}

	method := "POST"
	if m, ok := action.Parameters["method"].(string); ok {
		method = m
	}

	body, _ := json.Marshal(map[string]interface{}{
		"action":     action,
		"event_data": eventData,
	})

	req, _ := http.NewRequest(method, url, strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: time.Duration(action.Timeout) * time.Second}
	_, err := client.Do(req)
	return err
}

func (s *RuleEngineService) actionMQTTPublish(ctx context.Context, action model.RuleAction, eventData map[string]interface{}) error {
	logger.Get().Info("executing MQTT publish action",
		zap.String("target", action.Target))
	return nil
}

func (s *RuleEngineService) actionSetShadow(ctx context.Context, action model.RuleAction, eventData map[string]interface{}) error {
	logger.Get().Info("executing set shadow action",
		zap.String("target", action.Target))
	return nil
}

func (s *RuleEngineService) actionTriggerRule(ctx context.Context, action model.RuleAction, eventData map[string]interface{}) error {
	targetRuleID, ok := action.Parameters["rule_id"].(string)
	if !ok {
		return errors.New("rule_id not found in action parameters")
	}
	_, err := s.TriggerRule(ctx, targetRuleID, eventData)
	return err
}

func (s *RuleEngineService) processEvents() {
	ctx := context.Background()

	for event := range s.eventChan {
		s.processEvent(ctx, event)
	}
}

func (s *RuleEngineService) processEvent(ctx context.Context, event RuleEvent) {
	var rules []model.Rule
	if err := s.db.Where("trigger_type = ? AND is_enabled = ?",
		model.TriggerTypeEvent, true).Find(&rules).Error; err != nil {
		return
	}

	for _, rule := range rules {
		if len(rule.DeviceIDs) > 0 && !utils.ContainsString(rule.DeviceIDs, event.DeviceID) {
			continue
		}

		matched := s.evaluateCondition(rule.Condition, event.Data)
		if matched {
			_, _ = s.TriggerRule(ctx, rule.ID, event.Data)
		}
	}
}

func (s *RuleEngineService) startConditionChecker() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		s.checkAllConditions()
	}
}

func (s *RuleEngineService) checkAllConditions() {
	ctx := context.Background()

	var rules []model.Rule
	if err := s.db.Where("trigger_type = ? AND is_enabled = ?",
		model.TriggerTypeCondition, true).Find(&rules).Error; err != nil {
		return
	}

	for _, rule := range rules {
		go s.checkRuleCondition(ctx, rule)
	}
}

func (s *RuleEngineService) checkRuleCondition(ctx context.Context, rule model.Rule) {
	matched := s.evaluateCondition(rule.Condition, nil)
	if matched {
		_, _ = s.TriggerRule(ctx, rule.ID, nil)
	}
}

func (s *RuleEngineService) evaluateCondition(condition string, data map[string]interface{}) bool {
	if condition == "" || condition == "true" {
		return true
	}

	if strings.Contains(condition, ">") {
		parts := strings.Split(condition, ">")
		if len(parts) == 2 {
			field := strings.TrimSpace(parts[0])
			threshold := strings.TrimSpace(parts[1])
			if val, ok := data[field]; ok {
				return fmt.Sprintf("%v", val) > threshold
			}
		}
	}

	return true
}

func (s *RuleEngineService) PublishEvent(event RuleEvent) {
	s.eventChan <- event
}

func (s *RuleEngineService) GetExecution(ctx context.Context, executionID string) (*model.RuleExecution, error) {
	var execution model.RuleExecution
	if err := s.db.First(&execution, "id = ?", executionID).Error; err != nil {
		return nil, err
	}
	return &execution, nil
}

func (s *RuleEngineService) ListExecutions(ctx context.Context, ruleID string, page, pageSize int) ([]model.RuleExecution, int64, error) {
	var executions []model.RuleExecution
	var total int64

	query := s.db.Model(&model.RuleExecution{})
	if ruleID != "" {
		query = query.Where("rule_id = ?", ruleID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("timestamp DESC").Find(&executions).Error; err != nil {
		return nil, 0, err
	}

	return executions, total, nil
}

func (s *RuleEngineService) GetStats(ctx context.Context, ruleID string) (map[string]interface{}, error) {
	var totalExecutions, successExecutions, failedExecutions int64

	query := s.db.Model(&model.RuleExecution{}).Where("rule_id = ?", ruleID)
	query.Count(&totalExecutions)
	query.Where("status = ?", model.ExecutionStatusSuccess).Count(&successExecutions)
	query.Where("status = ?", model.ExecutionStatusFailed).Count(&failedExecutions)

	return map[string]interface{}{
		"total_executions":   totalExecutions,
		"success_executions": successExecutions,
		"failed_executions":  failedExecutions,
	}, nil
}
