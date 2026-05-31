package quality

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
	"streamsql/internal/common/logger"
)

type ExecutorStatus string

const (
	ExecutorStatusIdle     ExecutorStatus = "idle"
	ExecutorStatusRunning  ExecutorStatus = "running"
	ExecutorStatusStopped  ExecutorStatus = "stopped"

	MaxExecutionTimeout = 300
)

type RuleExecutor struct {
	rules        map[string]*QualityRule
	validators   map[RuleType]RuleValidator
	results      map[string][]RuleExecutionResult
	anomalies    map[string][]AnomalyRecord
	cron         *cron.Cron
	cronEntries  map[string]cron.EntryID
	mu           sync.RWMutex
	notification NotificationService
	status       ExecutorStatus
	activeTasks  map[string]context.CancelFunc
}

type NotificationService interface {
	Send(rule *QualityRule, result *RuleExecutionResult, anomalies []AnomalyRecord) error
}

type ConsoleNotification struct{}

func NewConsoleNotification() *ConsoleNotification {
	return &ConsoleNotification{}
}

func (n *ConsoleNotification) Send(rule *QualityRule, result *RuleExecutionResult, anomalies []AnomalyRecord) error {
	if !result.Passed {
		logger.Sugar().Warnf("Quality check failed for rule %s: %d anomalies found", rule.Name, len(anomalies))
	}
	return nil
}

func NewRuleExecutor(notification NotificationService) *RuleExecutor {
	executor := &RuleExecutor{
		rules:        make(map[string]*QualityRule),
		validators:   make(map[RuleType]RuleValidator),
		results:      make(map[string][]RuleExecutionResult),
		anomalies:    make(map[string][]AnomalyRecord),
		cron:         cron.New(),
		cronEntries:  make(map[string]cron.EntryID),
		notification: notification,
		status:       ExecutorStatusIdle,
		activeTasks:  make(map[string]context.CancelFunc),
	}

	executor.validators[RuleTypeRange] = NewRangeValidator()
	executor.validators[RuleTypeNullCheck] = NewNullCheckValidator()

	executor.cron.Start()
	logger.Sugar().Info("Rule executor initialized")
	return executor
}

func (e *RuleExecutor) AddRule(rule *QualityRule) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	if rule.ID == "" {
		rule.ID = uuid.New().String()
	}
	if rule.TimeoutSeconds <= 0 {
		rule.TimeoutSeconds = DefaultTimeoutSeconds
	}
	if rule.MaxRetries <= 0 {
		rule.MaxRetries = DefaultMaxRetries
	}
	rule.CreatedAt = time.Now().UTC()
	rule.UpdatedAt = rule.CreatedAt

	e.rules[rule.ID] = rule

	if rule.Status == RuleStatusActive && rule.CronExpression != "" {
		entryID, err := e.cron.AddFunc(rule.CronExpression, func() {
			_, _ = e.ExecuteRule(rule.ID)
		})
		if err != nil {
			logger.Sugar().Errorf("Failed to schedule rule %s: %v", rule.ID, err)
			return err
		}
		e.cronEntries[rule.ID] = entryID
		logger.Sugar().Infof("Scheduled rule %s with cron: %s", rule.ID, rule.CronExpression)
	}

	logger.Sugar().Infof("Added quality rule: %s (%s)", rule.Name, rule.ID)
	return nil
}

func (e *RuleExecutor) RemoveRule(ruleID string) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	if entryID, ok := e.cronEntries[ruleID]; ok {
		e.cron.Remove(entryID)
		delete(e.cronEntries, ruleID)
	}

	delete(e.rules, ruleID)
	logger.Sugar().Infof("Removed quality rule: %s", ruleID)
	return nil
}

func (e *RuleExecutor) UpdateRule(ruleID string, updates map[string]interface{}) (*QualityRule, error) {
	e.mu.Lock()
	defer e.mu.Unlock()

	rule, ok := e.rules[ruleID]
	if !ok {
		return nil, fmt.Errorf("rule not found: %s", ruleID)
	}

	if name, ok := updates["name"].(string); ok {
		rule.Name = name
	}
	if description, ok := updates["description"].(string); ok {
		rule.Description = description
	}
	if status, ok := updates["status"].(RuleStatus); ok {
		rule.Status = status
	}
	if params, ok := updates["parameters"].(map[string]interface{}); ok {
		rule.Parameters = params
	}
	if cronExpr, ok := updates["cron_expression"].(string); ok {
		if entryID, ok := e.cronEntries[ruleID]; ok {
			e.cron.Remove(entryID)
		}
		if cronExpr != "" && rule.Status == RuleStatusActive {
			entryID, err := e.cron.AddFunc(cronExpr, func() {
				_, _ = e.ExecuteRule(ruleID)
			})
			if err == nil {
				e.cronEntries[ruleID] = entryID
			}
		}
		rule.CronExpression = cronExpr
	}

	rule.UpdatedAt = time.Now().UTC()
	return rule, nil
}

func (e *RuleExecutor) GetRule(ruleID string) (*QualityRule, error) {
	e.mu.RLock()
	defer e.mu.RUnlock()

	rule, ok := e.rules[ruleID]
	if !ok {
		return nil, fmt.Errorf("rule not found: %s", ruleID)
	}
	return rule, nil
}

func (e *RuleExecutor) ListRules() []*QualityRule {
	e.mu.RLock()
	defer e.mu.RUnlock()

	rules := make([]*QualityRule, 0, len(e.rules))
	for _, rule := range e.rules {
		rules = append(rules, rule)
	}
	return rules
}

func (e *RuleExecutor) ExecuteRule(ruleID string) (*RuleExecutionResult, error) {
	e.mu.RLock()
	rule, ok := e.rules[ruleID]
	e.mu.RUnlock()

	if !ok {
		return nil, fmt.Errorf("rule not found: %s", ruleID)
	}

	startTime := time.Now()
	logger.Sugar().Infof("Executing quality rule: %s", rule.Name)

	var result *RuleExecutionResult
	var anomalies []AnomalyRecord
	var err error

	totalTimeout := time.Duration(rule.TimeoutSeconds) * time.Duration(rule.MaxRetries+1) * time.Second
	if totalTimeout > time.Duration(MaxExecutionTimeout)*time.Second {
		totalTimeout = time.Duration(MaxExecutionTimeout) * time.Second
	}

	totalCtx, totalCancel := context.WithTimeout(context.Background(), totalTimeout)
	defer totalCancel()

	for retry := 0; retry <= rule.MaxRetries; retry++ {
		select {
		case <-totalCtx.Done():
			logger.Sugar().Errorf("Rule %s total execution timeout after %v", rule.ID, totalTimeout)
			err = fmt.Errorf("total execution timeout after %v", totalTimeout)
			break
		default:
		}

		executionTimeout := time.Duration(rule.TimeoutSeconds) * time.Second
		ctx, cancel := context.WithTimeout(totalCtx, executionTimeout)

		result, anomalies, err = e.executeWithValidator(ctx, rule)

		cancel()

		if err == nil {
			result.RetryCount = retry
			break
		}

		if retry < rule.MaxRetries {
			logger.Sugar().Warnf("Rule %s execution failed (retry %d/%d): %v",
				rule.ID, retry+1, rule.MaxRetries, err)
			select {
			case <-totalCtx.Done():
				break
			case <-time.After(time.Second * time.Duration(retry+1)):
			}
		}
	}

	if err != nil {
		result = &RuleExecutionResult{
			ID:           uuid.New().String(),
			RuleID:       rule.ID,
			RuleName:     rule.Name,
			Status:       "failed",
			Passed:       false,
			ErrorMessage: err.Error(),
			ExecutedAt:   time.Now().UTC(),
		}
	}

	result.DurationMs = time.Since(startTime).Milliseconds()

	e.mu.Lock()
	e.results[ruleID] = append(e.results[ruleID], *result)
	if len(anomalies) > 0 {
		for i := range anomalies {
			anomalies[i].ID = uuid.New().String()
		}
		e.anomalies[ruleID] = append(e.anomalies[ruleID], anomalies...)
	}
	e.mu.Unlock()

	if e.notification != nil && rule.NotificationCfg.Enabled && !result.Passed {
		_ = e.notification.Send(rule, result, anomalies)
	}

	logger.Sugar().Infof("Rule %s execution completed: passed=%v, anomalies=%d, duration=%dms",
		rule.ID, result.Passed, len(anomalies), result.DurationMs)

	return result, nil
}

func (e *RuleExecutor) executeWithValidator(ctx context.Context, rule *QualityRule) (*RuleExecutionResult, []AnomalyRecord, error) {
	select {
	case <-ctx.Done():
		return nil, nil, ctx.Err()
	default:
	}

	validator, ok := e.validators[rule.Type]
	if !ok {
		return nil, nil, fmt.Errorf("no validator for rule type: %s", rule.Type)
	}

	mockData := e.getMockData(rule)

	done := make(chan struct{})
	var (
		passed    bool
		anomalies []AnomalyRecord
		validateErr error
	)

	go func() {
		passed, anomalies, validateErr = validator.Validate(ctx, rule, mockData)
		close(done)
	}()

	select {
	case <-ctx.Done():
		return nil, nil, ctx.Err()
	case <-done:
	}

	if validateErr != nil {
		return nil, nil, validateErr
	}

	totalRows := int64(0)
	invalidRows := int64(len(anomalies))

	switch d := mockData.(type) {
	case []float64:
		totalRows = int64(len(d))
	case []interface{}:
		totalRows = int64(len(d))
	}

	errorRate := 0.0
	if totalRows > 0 {
		errorRate = float64(invalidRows) / float64(totalRows)
	}

	result := &RuleExecutionResult{
		ID:          uuid.New().String(),
		RuleID:      rule.ID,
		RuleName:    rule.Name,
		Status:      "completed",
		Passed:      passed,
		TotalRows:   totalRows,
		InvalidRows: invalidRows,
		ErrorRate:   errorRate,
		ExecutedAt:  time.Now().UTC(),
	}

	return result, anomalies, nil
}

func (e *RuleExecutor) getMockData(rule *QualityRule) interface{} {
	switch rule.Type {
	case RuleTypeRange:
		return []float64{1.0, 5.0, 10.0, 15.0, 20.0, 25.0, 100.0}
	case RuleTypeNullCheck:
		return []interface{}{"value1", nil, "value3", nil, "value5"}
	default:
		return []float64{}
	}
}

func (e *RuleExecutor) ExecuteAll() []*RuleExecutionResult {
	e.mu.RLock()
	ruleIDs := make([]string, 0, len(e.rules))
	for id := range e.rules {
		ruleIDs = append(ruleIDs, id)
	}
	e.mu.RUnlock()

	results := make([]*RuleExecutionResult, 0, len(ruleIDs))
	for _, id := range ruleIDs {
		result, err := e.ExecuteRule(id)
		if err != nil {
			logger.Sugar().Errorf("Failed to execute rule %s: %v", id, err)
			continue
		}
		results = append(results, result)
	}
	return results
}

func (e *RuleExecutor) GetResults(ruleID string) []RuleExecutionResult {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.results[ruleID]
}

func (e *RuleExecutor) GetAnomalies(ruleID string) []AnomalyRecord {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.anomalies[ruleID]
}

func (e *RuleExecutor) GetAllAnomalies() []AnomalyRecord {
	e.mu.RLock()
	defer e.mu.RUnlock()

	var allAnomalies []AnomalyRecord
	for _, anomalies := range e.anomalies {
		allAnomalies = append(allAnomalies, anomalies...)
	}
	return allAnomalies
}

func (e *RuleExecutor) ResolveAnomaly(anomalyID string) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	now := time.Now().UTC()
	for _, anomalies := range e.anomalies {
		for i := range anomalies {
			if anomalies[i].ID == anomalyID {
				anomalies[i].Resolved = true
				anomalies[i].ResolvedAt = &now
				logger.Sugar().Infof("Resolved anomaly: %s", anomalyID)
				return nil
			}
		}
	}
	return fmt.Errorf("anomaly not found: %s", anomalyID)
}

func (e *RuleExecutor) Stop() {
	e.cron.Stop()
	logger.Sugar().Info("Rule executor stopped")
}
