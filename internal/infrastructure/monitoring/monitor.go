package monitoring

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/solocoder/session148/internal/domain"
	apperr "github.com/solocoder/session148/pkg/errors"
	"github.com/solocoder/session148/pkg/utils"
)

type InMemoryMonitor struct {
	metrics     map[string]map[string]float64
	rules       []domain.AlertRule
	alerts      []domain.Alert
	notifiers   map[string]Notifier
	mu          sync.RWMutex
	logger      domain.Logger
	clock       domain.Clock
}

type Notifier interface {
	Send(ctx context.Context, alert domain.Alert) error
}

type MonitorConfig struct {
	Logger domain.Logger
}

func NewInMemoryMonitor(cfg MonitorConfig) *InMemoryMonitor {
	return &InMemoryMonitor{
		metrics:   make(map[string]map[string]float64),
		rules:     []domain.AlertRule{},
		alerts:    []domain.Alert{},
		notifiers: make(map[string]Notifier),
		logger:    cfg.Logger,
		clock:     utils.NewRealClock(),
	}
}

func (m *InMemoryMonitor) RecordMetric(name string, value float64, dimensions map[string]string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	dimKey := "default"
	if dimensions != nil {
		if host, ok := dimensions["host"]; ok {
			dimKey = host
		}
	}

	if _, exists := m.metrics[name]; !exists {
		m.metrics[name] = make(map[string]float64)
	}
	m.metrics[name][dimKey] = value
}

func (m *InMemoryMonitor) EvaluateRules(ctx context.Context) ([]domain.Alert, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var triggeredAlerts []domain.Alert
	now := m.clock.Now()

	for _, rule := range m.rules {
		if !rule.Enabled {
			continue
		}

		values, exists := m.metrics[rule.Metric]
		if !exists {
			continue
		}

		for dim, value := range values {
			triggered := false
			switch rule.Operator {
			case ">":
				triggered = value > rule.Threshold
			case ">=":
				triggered = value >= rule.Threshold
			case "<":
				triggered = value < rule.Threshold
			case "<=":
				triggered = value <= rule.Threshold
			case "==":
				triggered = value == rule.Threshold
			case "!=":
				triggered = value != rule.Threshold
			}

			if triggered {
				alert := domain.Alert{
					ID:          utils.NewAlertID(),
					RuleID:      rule.ID,
					Message:     fmt.Sprintf("%s %s %.2f (current: %.2f) on %s", rule.Name, rule.Operator, rule.Threshold, value, dim),
					Severity:    rule.Severity,
					TriggeredAt: now,
					Resolved:  false,
				}
				triggeredAlerts = append(triggeredAlerts, alert)

				if notifier, ok := m.notifiers[rule.NotificationChannel]; ok {
					go func(a domain.Alert) {
						if err := notifier.Send(ctx, a); err != nil {
							m.logger.Error("failed to send notification", "error", err)
						}
					}(alert)
				}
			}
		}
	}

	m.alerts = append(m.alerts, triggeredAlerts...)
	return triggeredAlerts, nil
}

func (m *InMemoryMonitor) Notify(ctx context.Context, alert domain.Alert) error {
	m.logger.Warn("alert triggered", "alert_id", alert.ID, "message", alert.Message, "severity", alert.Severity)
	return nil
}

func (m *InMemoryMonitor) GetSnapshot(ctx context.Context) (*domain.MetricsSnapshot, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	metrics := make(map[string]float64)
	for name, dims := range m.metrics {
		var sum float64
		var count int
		for _, v := range dims {
			sum += v
			count++
		}
		if count > 0 {
			metrics[name] = sum / float64(count)
		}
	}

	return &domain.MetricsSnapshot{
		SnapshotID: utils.NewSnapshotID(),
		Timestamp:  m.clock.Now(),
		Metrics:    metrics,
		Dimensions: map[string]string{"host": "local"},
	}, nil
}

func (m *InMemoryMonitor) AddRule(rule domain.AlertRule) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.rules = append(m.rules, rule)
}

func (m *InMemoryMonitor) RegisterNotifier(channel string, notifier Notifier) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.notifiers[channel] = notifier
}

func (m *InMemoryMonitor) GetAlerts(resolved bool, limit int) []domain.Alert {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var result []domain.Alert
	for i := len(m.alerts) - 1; i >= 0 && len(result) < limit; i-- {
		if m.alerts[i].Resolved == resolved {
			result = append(result, m.alerts[i])
		}
	}
	return result
}

func (m *InMemoryMonitor) ResolveAlert(alertID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	for i := range m.alerts {
		if m.alerts[i].ID == alertID {
			m.alerts[i].Resolved = true
			return nil
		}
	}
	return apperr.NewNotFoundError(fmt.Sprintf("alert not found: %s", alertID))
}

type ConsoleNotifier struct {
	logger domain.Logger
}

func NewConsoleNotifier(logger domain.Logger) *ConsoleNotifier {
	return &ConsoleNotifier{logger: logger}
}

func (n *ConsoleNotifier) Send(ctx context.Context, alert domain.Alert) error {
	n.logger.Warn("ALERT", "id", alert.ID, "severity", alert.Severity, "message", alert.Message)
	return nil
}

type WebhookNotifier struct {
	URL    string
	logger domain.Logger
}

func NewWebhookNotifier(url string, logger domain.Logger) *WebhookNotifier {
	return &WebhookNotifier{URL: url, logger: logger}
}

func (n *WebhookNotifier) Send(ctx context.Context, alert domain.Alert) error {
	n.logger.Info("sending webhook alert", "url", n.URL, "alert_id", alert.ID)
	return nil
}

type EmailNotifier struct {
	SMTPHost string
	SMTPPort int
	From      string
	To        []string
	logger    domain.Logger
}

func NewEmailNotifier(host string, port int, from string, to []string, logger domain.Logger) *EmailNotifier {
	return &EmailNotifier{
		SMTPHost: host,
		SMTPPort: port,
		From:     from,
		To:       to,
		logger:   logger,
	}
}

func (n *EmailNotifier) Send(ctx context.Context, alert domain.Alert) error {
	n.logger.Info("sending email alert", "to", n.To, "alert_id", alert.ID)
	return nil
}

type MetricsExporter interface {
	Export() map[string]interface{}
}

func (m *InMemoryMonitor) ExportMetrics() map[string]interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()

	export := make(map[string]interface{})
	for name, dims := range m.metrics {
		dimMap := make(map[string]float64)
		for k, v := range dims {
			dimMap[k] = v
		}
		export[name] = dimMap
	}
	return export
}

func (m *InMemoryMonitor) StartRuleEvaluator(ctx context.Context, interval time.Duration) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				alerts, err := m.EvaluateRules(ctx)
				if err != nil {
					m.logger.Error("rule evaluation failed", "error", err)
					continue
				}
				if len(alerts) > 0 {
					m.logger.Info("alerts triggered", "count", len(alerts))
				}
			}
		}
	}()
}

type HealthChecker func() bool

func (m *InMemoryMonitor) RegisterHealthCheck(name string, checker HealthChecker) {
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()

		for range ticker.C {
			healthy := checker()
			if healthy {
				m.RecordMetric("health."+name, 1, nil)
			} else {
				m.RecordMetric("health."+name, 0, nil)
			}
		}
	}()
}
