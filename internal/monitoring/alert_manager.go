package monitoring

import (
	"sync"

	"github.com/parking-platform/platform/pkg/models"
	"github.com/parking-platform/platform/pkg/utils"
)

type Notifier interface {
	Notify(notification models.AlertNotification) error
}

type ConsoleNotifier struct{}

func (n *ConsoleNotifier) Notify(notification models.AlertNotification) error {
	return nil
}

type AlertManager struct {
	mu          sync.RWMutex
	rules       map[string]*models.AlertRule
	metrics     map[string]float64
	notifiers   []Notifier
	notifications []*models.AlertNotification
}

func NewAlertManager(notifiers ...Notifier) *AlertManager {
	return &AlertManager{
		rules:         make(map[string]*models.AlertRule),
		metrics:       make(map[string]float64),
		notifiers:     append(notifiers, &ConsoleNotifier{}),
		notifications: make([]*models.AlertNotification, 0),
	}
}

func (a *AlertManager) AddRule(name, expression, severity string, annotations map[string]string) *models.AlertRule {
	a.mu.Lock()
	defer a.mu.Unlock()
	rule := &models.AlertRule{
		ID:          utils.GenerateID("rule"),
		Name:        name,
		Expression:  expression,
		Severity:    severity,
		Enabled:     true,
		Annotations: annotations,
		CreatedAt:   utils.Now(),
	}
	a.rules[rule.ID] = rule
	return rule
}

func (a *AlertManager) ListRules() []*models.AlertRule {
	a.mu.RLock()
	defer a.mu.RUnlock()
	result := make([]*models.AlertRule, 0, len(a.rules))
	for _, r := range a.rules {
		result = append(result, r)
	}
	return result
}

func (a *AlertManager) UpdateRule(id string, enabled bool) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	rule, ok := a.rules[id]
	if !ok {
		return ErrRuleNotFound
	}
	rule.Enabled = enabled
	return nil
}

func (a *AlertManager) RecordMetric(name string, value float64) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.metrics[name] = value
}

func (a *AlertManager) GetMetric(name string) (float64, bool) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	v, ok := a.metrics[name]
	return v, ok
}

func (a *AlertManager) EvaluateRules() []*models.AlertNotification {
	a.mu.Lock()
	defer a.mu.Unlock()
	fired := make([]*models.AlertNotification, 0)
	for _, rule := range a.rules {
		if !rule.Enabled {
			continue
		}
		triggered := a.evaluateExpression(rule.Expression)
		if triggered {
			notification := &models.AlertNotification{
				ID:        utils.GenerateID("alert"),
				RuleID:    rule.ID,
				Message:   rule.Annotations["message"],
				Level:     rule.Severity,
				Timestamp: utils.Now(),
			}
			a.notifications = append(a.notifications, notification)
			for _, n := range a.notifiers {
				_ = n.Notify(*notification)
			}
			fired = append(fired, notification)
		}
	}
	return fired
}

func (a *AlertManager) evaluateExpression(expr string) bool {
	if len(a.metrics) == 0 {
		return false
	}
	if expr == "error_rate > 0.01" {
		if v, ok := a.metrics["error_rate"]; ok && v > 0.01 {
			return true
		}
	}
	if expr == "latency_p99 > 500" {
		if v, ok := a.metrics["latency_p99"]; ok && v > 500 {
			return true
		}
	}
	return false
}

func (a *AlertManager) ListNotifications() []*models.AlertNotification {
	a.mu.RLock()
	defer a.mu.RUnlock()
	result := make([]*models.AlertNotification, len(a.notifications))
	copy(result, a.notifications)
	return result
}

func (a *AlertManager) TakeSnapshot(dimensions map[string]string) *models.MetricsSnapshot {
	a.mu.RLock()
	defer a.mu.RUnlock()
	metrics := make(map[string]float64)
	for k, v := range a.metrics {
		metrics[k] = v
	}
	return &models.MetricsSnapshot{
		SnapshotID: utils.GenerateID("snap"),
		Timestamp:  utils.Now(),
		Metrics:    metrics,
		Dimensions: dimensions,
	}
}

var ErrRuleNotFound = &alertError{"alert rule not found"}

type alertError struct {
	msg string
}

func (e *alertError) Error() string { return e.msg }
