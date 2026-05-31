package alerter

import (
	"context"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
)

type AlertEngine struct {
	cron         *cron.Cron
	ruleEntries  map[string]cron.EntryID
	mu           sync.RWMutex
	metricsStore map[string][]float64
	notifier     func(ctx context.Context, event *domain.AlertEvent) error
}

type AlertEvaluation struct {
	RuleID      string
	IsTriggered bool
	Value       float64
	Message     string
}

func NewAlertEngine() *AlertEngine {
	return &AlertEngine{
		cron:         cron.New(cron.WithSeconds()),
		ruleEntries:  make(map[string]cron.EntryID),
		metricsStore: make(map[string][]float64),
	}
}

func (e *AlertEngine) SetNotifier(notifier func(ctx context.Context, event *domain.AlertEvent) error) {
	e.notifier = notifier
}

func (e *AlertEngine) Start() error {
	rules, err := e.listEnabledRules()
	if err != nil {
		return fmt.Errorf("list enabled rules failed: %w", err)
	}

	for _, rule := range rules {
		if err := e.scheduleRule(&rule); err != nil {
			logger.Error("Failed to schedule alert rule",
				zap.String("rule_id", rule.RuleID),
				zap.Error(err))
			continue
		}
	}

	e.cron.Start()
	logger.Info("Alert engine started", zap.Int("rule_count", len(e.ruleEntries)))
	return nil
}

func (e *AlertEngine) Stop() {
	e.cron.Stop()
	logger.Info("Alert engine stopped")
}

func (e *AlertEngine) scheduleRule(rule *domain.AlertRule) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	if entryID, exists := e.ruleEntries[rule.RuleID]; exists {
		e.cron.Remove(entryID)
		delete(e.ruleEntries, rule.RuleID)
	}

	cronExpr := fmt.Sprintf("@every %ds", rule.WindowSeconds)
	if rule.WindowSeconds <= 0 {
		cronExpr = "@every 60s"
	}

	entryID, err := e.cron.AddFunc(cronExpr, func() {
		e.evaluateRule(rule)
	})
	if err != nil {
		return fmt.Errorf("add cron rule failed: %w", err)
	}

	e.ruleEntries[rule.RuleID] = entryID

	logger.Info("Alert rule scheduled",
		zap.String("rule_id", rule.RuleID),
		zap.String("name", rule.Name),
		zap.String("metric", rule.MetricName))

	return nil
}

func (e *AlertEngine) evaluateRule(rule *domain.AlertRule) {
	ctx := context.Background()

	metricValue, err := e.getMetricValue(rule.MetricName, rule.WindowSeconds)
	if err != nil {
		logger.Error("Failed to get metric value",
			zap.String("rule_id", rule.RuleID),
			zap.String("metric", rule.MetricName),
			zap.Error(err))
		return
	}

	evaluation := e.evaluateCondition(rule, metricValue)

	if evaluation.IsTriggered {
		if err := e.createAlertEvent(ctx, rule, metricValue, evaluation.Message); err != nil {
			logger.Error("Failed to create alert event",
				zap.String("rule_id", rule.RuleID),
				zap.Error(err))
		}
	}

	logger.Debug("Alert rule evaluated",
		zap.String("rule_id", rule.RuleID),
		zap.Float64("value", metricValue),
		zap.Bool("triggered", evaluation.IsTriggered))
}

func (e *AlertEngine) getMetricValue(metricName string, windowSeconds int32) (float64, error) {
	e.mu.RLock()
	defer e.mu.RUnlock()

	values, exists := e.metricsStore[metricName]
	if !exists || len(values) == 0 {
		return 0, fmt.Errorf("no data for metric %s", metricName)
	}

	return calculateAverage(values), nil
}

func (e *AlertEngine) evaluateCondition(rule *domain.AlertRule, value float64) AlertEvaluation {
	var triggered bool
	var message string

	switch rule.Operator {
	case domain.AlertOperatorGT:
		triggered = value > rule.Threshold
		message = fmt.Sprintf("Metric %s value %.2f is greater than threshold %.2f",
			rule.MetricName, value, rule.Threshold)
	case domain.AlertOperatorLT:
		triggered = value < rule.Threshold
		message = fmt.Sprintf("Metric %s value %.2f is less than threshold %.2f",
			rule.MetricName, value, rule.Threshold)
	case domain.AlertOperatorGE:
		triggered = value >= rule.Threshold
		message = fmt.Sprintf("Metric %s value %.2f is greater than or equal to threshold %.2f",
			rule.MetricName, value, rule.Threshold)
	case domain.AlertOperatorLE:
		triggered = value <= rule.Threshold
		message = fmt.Sprintf("Metric %s value %.2f is less than or equal to threshold %.2f",
			rule.MetricName, value, rule.Threshold)
	case domain.AlertOperatorEQ:
		triggered = math.Abs(value-rule.Threshold) < 0.0001
		message = fmt.Sprintf("Metric %s value %.2f equals threshold %.2f",
			rule.MetricName, value, rule.Threshold)
	case domain.AlertOperatorNE:
		triggered = math.Abs(value-rule.Threshold) >= 0.0001
		message = fmt.Sprintf("Metric %s value %.2f not equals threshold %.2f",
			rule.MetricName, value, rule.Threshold)
	}

	return AlertEvaluation{
		RuleID:      rule.RuleID,
		IsTriggered: triggered,
		Value:       value,
		Message:     message,
	}
}

func (e *AlertEngine) createAlertEvent(ctx context.Context, rule *domain.AlertRule, value float64, message string) error {
	event := &domain.AlertEvent{
		EventID:     uuid.New().String(),
		RuleID:      rule.RuleID,
		Severity:    rule.Severity,
		MetricValue: value,
		Message:     message,
		Resolved:    false,
		TriggeredAt: time.Now(),
	}

	if err := database.DB.WithContext(ctx).Create(event).Error; err != nil {
		return fmt.Errorf("create alert event failed: %w", err)
	}

	if e.notifier != nil {
		go func() {
			if err := e.notifier(context.Background(), event); err != nil {
				logger.Error("Failed to send notification",
					zap.String("event_id", event.EventID),
					zap.Error(err))
			}
		}()
	}

	logger.Warn("Alert triggered",
		zap.String("event_id", event.EventID),
		zap.String("rule_id", rule.RuleID),
		zap.String("severity", string(rule.Severity)),
		zap.Float64("value", value))

	return nil
}

func (e *AlertEngine) ReportMetric(metricName string, value float64) {
	e.mu.Lock()
	defer e.mu.Unlock()

	e.metricsStore[metricName] = append(e.metricsStore[metricName], value)

	if len(e.metricsStore[metricName]) > 1000 {
		e.metricsStore[metricName] = e.metricsStore[metricName][len(e.metricsStore[metricName])-1000:]
	}
}

func (e *AlertEngine) CreateRule(ctx context.Context, rule *domain.AlertRule) (*domain.AlertRule, error) {
	rule.RuleID = uuid.New().String()
	rule.CreatedAt = time.Now()
	rule.UpdatedAt = time.Now()

	if err := database.DB.WithContext(ctx).Create(rule).Error; err != nil {
		return nil, fmt.Errorf("create alert rule failed: %w", err)
	}

	if rule.Enabled {
		if err := e.scheduleRule(rule); err != nil {
			return rule, fmt.Errorf("schedule rule failed: %w", err)
		}
	}

	logger.Info("Alert rule created",
		zap.String("rule_id", rule.RuleID),
		zap.String("name", rule.Name))

	return rule, nil
}

func (e *AlertEngine) UpdateRule(ctx context.Context, ruleID string, updates map[string]interface{}) (*domain.AlertRule, error) {
	var rule domain.AlertRule
	if err := database.DB.WithContext(ctx).Where("rule_id = ?", ruleID).First(&rule).Error; err != nil {
		return nil, fmt.Errorf("rule not found: %w", err)
	}

	updates["updated_at"] = time.Now()
	if err := database.DB.WithContext(ctx).Model(&rule).Updates(updates).Error; err != nil {
		return nil, fmt.Errorf("update rule failed: %w", err)
	}

	if err := database.DB.WithContext(ctx).Where("rule_id = ?", ruleID).First(&rule).Error; err != nil {
		return nil, fmt.Errorf("reload rule failed: %w", err)
	}

	if rule.Enabled {
		if err := e.scheduleRule(&rule); err != nil {
			return &rule, err
		}
	} else {
		e.mu.Lock()
		if entryID, exists := e.ruleEntries[ruleID]; exists {
			e.cron.Remove(entryID)
			delete(e.ruleEntries, ruleID)
		}
		e.mu.Unlock()
	}

	return &rule, nil
}

func (e *AlertEngine) DeleteRule(ctx context.Context, ruleID string) error {
	e.mu.Lock()
	if entryID, exists := e.ruleEntries[ruleID]; exists {
		e.cron.Remove(entryID)
		delete(e.ruleEntries, ruleID)
	}
	e.mu.Unlock()

	if err := database.DB.WithContext(ctx).Where("rule_id = ?", ruleID).Delete(&domain.AlertRule{}).Error; err != nil {
		return fmt.Errorf("delete rule failed: %w", err)
	}

	logger.Info("Alert rule deleted", zap.String("rule_id", ruleID))
	return nil
}

func (e *AlertEngine) GetRule(ctx context.Context, ruleID string) (*domain.AlertRule, error) {
	var rule domain.AlertRule
	if err := database.DB.WithContext(ctx).Where("rule_id = ?", ruleID).First(&rule).Error; err != nil {
		return nil, fmt.Errorf("get rule failed: %w", err)
	}
	return &rule, nil
}

func (e *AlertEngine) ListRules(ctx context.Context, offset, limit int) ([]domain.AlertRule, int64, error) {
	var rules []domain.AlertRule
	var total int64

	if err := database.DB.WithContext(ctx).Model(&domain.AlertRule{}).Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count rules failed: %w", err)
	}

	if err := database.DB.WithContext(ctx).Order("created_at DESC").Offset(offset).Limit(limit).Find(&rules).Error; err != nil {
		return nil, 0, fmt.Errorf("list rules failed: %w", err)
	}

	return rules, total, nil
}

func (e *AlertEngine) GetAlertEvent(ctx context.Context, eventID string) (*domain.AlertEvent, error) {
	var event domain.AlertEvent
	if err := database.DB.WithContext(ctx).Where("event_id = ?", eventID).First(&event).Error; err != nil {
		return nil, fmt.Errorf("get alert event failed: %w", err)
	}
	return &event, nil
}

func (e *AlertEngine) ListAlertEvents(ctx context.Context, ruleID string, resolved *bool, offset, limit int) ([]domain.AlertEvent, int64, error) {
	var events []domain.AlertEvent
	var total int64

	query := database.DB.WithContext(ctx).Model(&domain.AlertEvent{})
	if ruleID != "" {
		query = query.Where("rule_id = ?", ruleID)
	}
	if resolved != nil {
		query = query.Where("resolved = ?", *resolved)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count alert events failed: %w", err)
	}

	if err := query.Order("triggered_at DESC").Offset(offset).Limit(limit).Find(&events).Error; err != nil {
		return nil, 0, fmt.Errorf("list alert events failed: %w", err)
	}

	return events, total, nil
}

func (e *AlertEngine) ResolveAlert(ctx context.Context, eventID string) error {
	now := time.Now()
	if err := database.DB.WithContext(ctx).Model(&domain.AlertEvent{}).
		Where("event_id = ?", eventID).
		Updates(map[string]interface{}{
			"resolved":   true,
			"resolved_at": &now,
		}).Error; err != nil {
		return fmt.Errorf("resolve alert failed: %w", err)
	}

	logger.Info("Alert resolved", zap.String("event_id", eventID))
	return nil
}

func (e *AlertEngine) listEnabledRules() ([]domain.AlertRule, error) {
	var rules []domain.AlertRule
	if err := database.DB.Where("enabled = ?", true).Find(&rules).Error; err != nil {
		return nil, fmt.Errorf("list enabled rules failed: %w", err)
	}
	return rules, nil
}

func calculateAverage(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sum := 0.0
	for _, v := range values {
		sum += v
	}
	return sum / float64(len(values))
}
