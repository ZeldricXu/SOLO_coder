package alerter

import (
	"context"
	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"strconv"
	"strings"
	"sync"
	"taskmanager/internal/logger"
	"taskmanager/pkg/models"
	"time"
)

type Notifier interface {
	SendNotification(ctx context.Context, channel, recipient, subject, content string, labels map[string]string) error
}

type AlertEngine struct {
	db        *gorm.DB
	cron      *cron.Cron
	notifier  Notifier
	ruleMap   map[string]cron.EntryID
	mu        sync.RWMutex
	running   bool
}

func NewAlertEngine(db *gorm.DB, notifier Notifier) *AlertEngine {
	return &AlertEngine{
		db:       db,
		cron:     cron.New(),
		notifier: notifier,
		ruleMap:  make(map[string]cron.EntryID),
	}
}

func (ae *AlertEngine) Start() {
	if ae.running {
		return
	}
	ae.running = true
	ae.cron.Start()
	if err := ae.loadRules(); err != nil {
		logger.Error("load alert rules failed", zap.Error(err))
	}
	logger.Info("alert engine started")
}

func (ae *AlertEngine) Stop() {
	if !ae.running {
		return
	}
	ae.running = false
	ctx := ae.cron.Stop()
	<-ctx.Done()
	logger.Info("alert engine stopped")
}

func (ae *AlertEngine) loadRules() error {
	var rules []models.AlertRule
	if err := ae.db.Where("enabled = ?", true).Find(&rules).Error; err != nil {
		return err
	}
	for _, rule := range rules {
		if err := ae.scheduleRule(&rule); err != nil {
			logger.Error("schedule alert rule failed", zap.String("rule_id", rule.ID), zap.Error(err))
		}
	}
	return nil
}

func (ae *AlertEngine) scheduleRule(rule *models.AlertRule) error {
	ae.mu.Lock()
	defer ae.mu.Unlock()
	if entryID, ok := ae.ruleMap[rule.ID]; ok {
		ae.cron.Remove(entryID)
		delete(ae.ruleMap, rule.ID)
	}
	entryID, err := ae.cron.AddFunc("*/10 * * * *", func() {
		ae.evaluateRule(rule)
	})
	if err != nil {
		return err
	}
	ae.ruleMap[rule.ID] = entryID
	return nil
}

func (ae *AlertEngine) evaluateRule(rule *models.AlertRule) {
	logger.Debug("evaluating alert rule", zap.String("rule_id", rule.ID), zap.String("rule_name", rule.Name))
	value, err := ae.evaluateExpr(rule.Expr)
	if err != nil {
		logger.Error("evaluate expr failed", zap.String("rule_id", rule.ID), zap.Error(err))
		return
	}
	threshold := ae.extractThreshold(rule.Expr)
	firing := value > threshold
	var existingAlert models.Alert
	err = ae.db.Where("rule_id = ? AND status = ?", rule.ID, "firing").First(&existingAlert).Error
	if firing {
		if err != nil {
			alert := &models.Alert{
				ID:         uuid.New().String(),
				RuleID:     rule.ID,
				Name:       rule.Name,
				Severity:   rule.Severity,
				Status:     "firing",
				Labels:     rule.Labels,
				StartsAt:   time.Now(),
				Value:      value,
			}
			if err := ae.db.Create(alert).Error; err != nil {
				logger.Error("create alert failed", zap.Error(err))
				return
			}
			ae.sendAlertNotification(rule, alert)
		}
	} else {
		if err == nil {
			now := time.Now()
			existingAlert.Status = "resolved"
			existingAlert.EndsAt = &now
			if err := ae.db.Save(&existingAlert).Error; err != nil {
				logger.Error("resolve alert failed", zap.Error(err))
			}
		}
	}
}

func (ae *AlertEngine) evaluateExpr(expr string) (float64, error) {
	parts := strings.Split(expr, ">")
	if len(parts) != 2 {
		return 0, nil
	}
	metricName := strings.TrimSpace(parts[0])
	return ae.getMetricValue(metricName), nil
}

func (ae *AlertEngine) getMetricValue(metricName string) float64 {
	var snapshot models.Snapshot
	if err := ae.db.Order("timestamp desc").First(&snapshot).Error; err != nil {
		return 0
	}
	if val, ok := snapshot.Metrics[metricName]; ok {
		return val
	}
	return 0
}

func (ae *AlertEngine) extractThreshold(expr string) float64 {
	parts := strings.Split(expr, ">")
	if len(parts) != 2 {
		return 0
	}
	thresholdStr := strings.TrimSpace(parts[1])
	threshold, _ := strconv.ParseFloat(thresholdStr, 64)
	return threshold
}

func (ae *AlertEngine) sendAlertNotification(rule *models.AlertRule, alert *models.Alert) {
	if ae.notifier == nil {
		return
	}
	subject := "[ALERT] " + alert.Severity + ": " + rule.Name
	content := "Alert " + rule.Name + " is firing!\n" +
		"Value: " + strconv.FormatFloat(alert.Value, 'f', 2, 64) + "\n" +
		"Severity: " + alert.Severity + "\n" +
		"Started at: " + alert.StartsAt.Format(time.RFC3339) + "\n"
	labels := make(map[string]string)
	for k, v := range rule.Labels {
		labels[k] = v
	}
	labels["alert_id"] = alert.ID
	labels["severity"] = alert.Severity
	if err := ae.notifier.SendNotification(context.Background(), "email", "admin@example.com", subject, content, labels); err != nil {
		logger.Error("send alert notification failed", zap.Error(err))
	}
}

func (ae *AlertEngine) FireAlert(ctx context.Context, ruleName string, labels map[string]string, value float64) error {
	alert := &models.Alert{
		ID:       uuid.New().String(),
		RuleID:   "internal",
		Name:     ruleName,
		Severity: labels["severity"],
		Status:   "firing",
		Labels:   labels,
		StartsAt: time.Now(),
		Value:    value,
	}
	return ae.db.Create(alert).Error
}

func (ae *AlertEngine) CreateRule(ctx context.Context, rule *models.AlertRule) error {
	if rule.ID == "" {
		rule.ID = uuid.New().String()
	}
	rule.CreatedAt = time.Now()
	rule.UpdatedAt = time.Now()
	if err := ae.db.Create(rule).Error; err != nil {
		return err
	}
	if rule.Enabled {
		return ae.scheduleRule(rule)
	}
	return nil
}

func (ae *AlertEngine) GetRule(ctx context.Context, id string) (*models.AlertRule, error) {
	var rule models.AlertRule
	if err := ae.db.First(&rule, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &rule, nil
}

func (ae *AlertEngine) ListRules(ctx context.Context) ([]models.AlertRule, error) {
	var rules []models.AlertRule
	if err := ae.db.Find(&rules).Error; err != nil {
		return nil, err
	}
	return rules, nil
}

func (ae *AlertEngine) UpdateRule(ctx context.Context, rule *models.AlertRule) error {
	existing, err := ae.GetRule(ctx, rule.ID)
	if err != nil {
		return err
	}
	rule.UpdatedAt = time.Now()
	if err := ae.db.Save(rule).Error; err != nil {
		return err
	}
	if rule.Enabled != existing.Enabled {
		if rule.Enabled {
			return ae.scheduleRule(rule)
		} else {
			ae.mu.Lock()
			defer ae.mu.Unlock()
			if entryID, ok := ae.ruleMap[rule.ID]; ok {
				ae.cron.Remove(entryID)
				delete(ae.ruleMap, rule.ID)
			}
		}
	}
	return nil
}

func (ae *AlertEngine) DeleteRule(ctx context.Context, id string) error {
	ae.mu.Lock()
	defer ae.mu.Unlock()
	if entryID, ok := ae.ruleMap[id]; ok {
		ae.cron.Remove(entryID)
		delete(ae.ruleMap, id)
	}
	return ae.db.Delete(&models.AlertRule{}, "id = ?", id).Error
}

func (ae *AlertEngine) ListAlerts(ctx context.Context, status string, limit int) ([]models.Alert, error) {
	var alerts []models.Alert
	query := ae.db.Order("starts_at desc")
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if limit > 0 {
		query = query.Limit(limit)
	}
	if err := query.Find(&alerts).Error; err != nil {
		return nil, err
	}
	return alerts, nil
}

func (ae *AlertEngine) ResolveAlert(ctx context.Context, id string) error {
	var alert models.Alert
	if err := ae.db.First(&alert, "id = ?", id).Error; err != nil {
		return err
	}
	now := time.Now()
	alert.Status = "resolved"
	alert.EndsAt = &now
	return ae.db.Save(&alert).Error
}
