package monitoring

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	appErr "session133/pkg/errors"
	"session133/pkg/utils"
)

type MonitoringService struct {
	db                *gorm.DB
	logger            *zap.Logger
	notificationChans []NotificationConfig
	metricsStore      map[string][]MetricDataPoint
}

func NewMonitoringService(db *gorm.DB, logger *zap.Logger) *MonitoringService {
	return &MonitoringService{
		db:           db,
		logger:       logger,
		metricsStore: make(map[string][]MetricDataPoint),
	}
}

func (s *MonitoringService) CreateAlertRule(ctx context.Context, req *CreateAlertRuleRequest, userID string) (*AlertRule, error) {
	now := time.Now()
	forDuration, _ := time.ParseDuration(req.Duration)
	if forDuration == 0 {
		forDuration = time.Minute
	}

	rule := &AlertRule{
		ID:          utils.GenerateID("rule"),
		Name:        req.Name,
		Namespace:   req.Namespace,
		Description: req.Description,
		Query:       req.Query,
		Condition:   req.Condition,
		Threshold:   req.Threshold,
		Duration:    req.Duration,
		ForDuration: forDuration,
		Severity:    req.Severity,
		Labels:      req.Labels,
		Annotations: req.Annotations,
		Enabled:     true,
		CreatedBy:   userID,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	if err := s.db.WithContext(ctx).Create(rule).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return rule, nil
}

func (s *MonitoringService) GetAlertRule(ctx context.Context, ruleID string) (*AlertRule, error) {
	rule := &AlertRule{}
	if err := s.db.WithContext(ctx).Where("id = ?", ruleID).First(rule).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, appErr.NotFound("告警规则")
		}
		return nil, appErr.Internal(err.Error())
	}
	return rule, nil
}

func (s *MonitoringService) ListAlertRules(ctx context.Context, namespace string, page, pageSize int) ([]AlertRule, int64, error) {
	var rules []AlertRule
	var total int64

	query := s.db.WithContext(ctx).Model(&AlertRule{})
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&rules).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return rules, total, nil
}

func (s *MonitoringService) UpdateAlertRule(ctx context.Context, ruleID string, req *UpdateAlertRuleRequest) (*AlertRule, error) {
	rule, err := s.GetAlertRule(ctx, ruleID)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	updates := make(map[string]interface{})

	if req.Name != "" {
		updates["name"] = req.Name
	}
	if req.Description != "" {
		updates["description"] = req.Description
	}
	if req.Query != "" {
		updates["query"] = req.Query
	}
	if req.Condition != "" {
		updates["condition"] = req.Condition
	}
	if req.Threshold != nil {
		updates["threshold"] = *req.Threshold
	}
	if req.Duration != "" {
		updates["duration"] = req.Duration
	}
	if req.Severity != "" {
		updates["severity"] = req.Severity
	}
	if req.Labels != nil {
		updates["labels"] = req.Labels
	}
	if req.Annotations != nil {
		updates["annotations"] = req.Annotations
	}
	if req.Enabled != nil {
		updates["enabled"] = *req.Enabled
	}
	updates["updated_at"] = now

	if err := s.db.WithContext(ctx).Model(rule).Updates(updates).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return s.GetAlertRule(ctx, ruleID)
}

func (s *MonitoringService) DeleteAlertRule(ctx context.Context, ruleID string) error {
	_, err := s.GetAlertRule(ctx, ruleID)
	if err != nil {
		return err
	}

	if err := s.db.WithContext(ctx).Where("id = ?", ruleID).Delete(&AlertRule{}).Error; err != nil {
		return appErr.Internal(err.Error())
	}
	return nil
}

func (s *MonitoringService) RecordMetric(ctx context.Context, metricName string, value float64, labels map[string]string) {
	s.metricsStore[metricName] = append(s.metricsStore[metricName], MetricDataPoint{
		Timestamp: time.Now(),
		Value:     value,
		Labels:    labels,
	})

	if len(s.metricsStore[metricName]) > 10000 {
		s.metricsStore[metricName] = s.metricsStore[metricName][len(s.metricsStore[metricName])-10000:]
	}
}

func (s *MonitoringService) QueryMetric(ctx context.Context, metricName string, startTime, endTime time.Time) ([]MetricDataPoint, error) {
	points, exists := s.metricsStore[metricName]
	if !exists {
		return []MetricDataPoint{}, nil
	}

	var result []MetricDataPoint
	for _, p := range points {
		if p.Timestamp.After(startTime) && p.Timestamp.Before(endTime) {
			result = append(result, p)
		}
	}

	return result, nil
}

func (s *MonitoringService) EvaluateRules(ctx context.Context) {
	var rules []AlertRule
	if err := s.db.Where("enabled = ?", true).Find(&rules).Error; err != nil {
		s.logger.Error("加载告警规则失败", zap.Error(err))
		return
	}

	for _, rule := range rules {
		s.evaluateRule(ctx, &rule)
	}
}

func (s *MonitoringService) evaluateRule(ctx context.Context, rule *AlertRule) {
	metricValue := s.getMetricValue(rule.Query)
	if metricValue == nil {
		return
	}

	triggered := s.evaluateCondition(*metricValue, rule.Condition, rule.Threshold)

	fingerprint := s.generateFingerprint(rule)

	var existingAlert Alert
	err := s.db.Where("rule_id = ? AND fingerprint = ? AND status IN (?)",
		rule.ID, fingerprint, []AlertStatus{AlertStatusFiring, AlertStatusPending}).
		Order("starts_at DESC").
		First(&existingAlert).Error

	now := time.Now()

	if triggered {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			alert := &Alert{
				ID:               utils.GenerateID("alert"),
				RuleID:           rule.ID,
				Name:             rule.Name,
				Namespace:        rule.Namespace,
				Status:           AlertStatusPending,
				Severity:         rule.Severity,
				Labels:           rule.Labels,
				Annotations:      rule.Annotations,
				Value:            *metricValue,
				Threshold:        rule.Threshold,
				Condition:        rule.Condition,
				StartsAt:         now,
				Fingerprint:      fingerprint,
				NotificationSent: false,
				CreatedAt:        now,
				UpdatedAt:        now,
			}

			if rule.ForDuration <= 0 {
				alert.Status = AlertStatusFiring
			}

			s.db.Create(alert)

			if alert.Status == AlertStatusFiring {
				s.sendNotification(ctx, alert)
			}
		} else if err == nil {
			if existingAlert.Status == AlertStatusPending &&
				now.Sub(existingAlert.StartsAt) >= rule.ForDuration {
				existingAlert.Status = AlertStatusFiring
				existingAlert.Value = *metricValue
				existingAlert.UpdatedAt = now
				s.db.Save(&existingAlert)
				s.sendNotification(ctx, &existingAlert)
			} else if existingAlert.Status == AlertStatusFiring {
				existingAlert.Value = *metricValue
				existingAlert.UpdatedAt = now
				s.db.Save(&existingAlert)
			}
		}
	} else {
		if err == nil && existingAlert.Status == AlertStatusFiring {
			existingAlert.Status = AlertStatusResolved
			existingAlert.EndsAt = &now
			existingAlert.UpdatedAt = now
			s.db.Save(&existingAlert)
			s.sendNotification(ctx, &existingAlert)
		}
	}
}

func (s *MonitoringService) getMetricValue(query string) *float64 {
	parts := strings.Split(query, ".")
	if len(parts) > 0 {
		metricName := parts[0]
		points, exists := s.metricsStore[metricName]
		if exists && len(points) > 0 {
			latest := points[len(points)-1]
			return &latest.Value
		}
	}
	return nil
}

func (s *MonitoringService) evaluateCondition(value float64, condition string, threshold float64) bool {
	switch condition {
	case ">":
		return value > threshold
	case ">=":
		return value >= threshold
	case "<":
		return value < threshold
	case "<=":
		return value <= threshold
	case "==":
		return value == threshold
	case "!=":
		return value != threshold
	default:
		return false
	}
}

func (s *MonitoringService) generateFingerprint(rule *AlertRule) string {
	labelsJSON, _ := json.Marshal(rule.Labels)
	hash := sha256.Sum256([]byte(rule.ID + string(labelsJSON)))
	return hex.EncodeToString(hash[:])[:32]
}

func (s *MonitoringService) sendNotification(ctx context.Context, alert *Alert) {
	s.logger.Info("发送告警通知",
		zap.String("alert_id", alert.ID),
		zap.String("name", alert.Name),
		zap.String("status", string(alert.Status)),
		zap.String("severity", string(alert.Severity)),
	)

	for _, channel := range s.notificationChans {
		if !channel.Enabled {
			continue
		}

		switch channel.Channel {
		case ChannelWebhook:
			s.sendWebhook(ctx, channel, alert)
		}
	}

	alert.NotificationSent = true
	s.db.Save(alert)
}

func (s *MonitoringService) sendWebhook(ctx context.Context, config NotificationConfig, alert *Alert) {
	url, exists := config.Config["url"]
	if !exists {
		return
	}

	payload := map[string]interface{}{
		"alert_id":   alert.ID,
		"name":       alert.Name,
		"status":     alert.Status,
		"severity":   alert.Severity,
		"value":      alert.Value,
		"threshold":  alert.Threshold,
		"condition":  alert.Condition,
		"starts_at":  alert.StartsAt,
		"labels":     alert.Labels,
		"annotations": alert.Annotations,
	}

	jsonPayload, _ := json.Marshal(payload)
	http.Post(url, "application/json", strings.NewReader(string(jsonPayload)))
}

func (s *MonitoringService) ListAlerts(ctx context.Context, namespace string, status AlertStatus, page, pageSize int) ([]Alert, int64, error) {
	var alerts []Alert
	var total int64

	query := s.db.WithContext(ctx).Model(&Alert{})
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&alerts).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return alerts, total, nil
}

func (s *MonitoringService) GetAlert(ctx context.Context, alertID string) (*Alert, error) {
	alert := &Alert{}
	if err := s.db.WithContext(ctx).Where("id = ?", alertID).First(alert).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, appErr.NotFound("告警")
		}
		return nil, appErr.Internal(err.Error())
	}
	return alert, nil
}

func (s *MonitoringService) AddNotificationChannel(ctx context.Context, config *NotificationConfig, userID string) (*NotificationConfig, error) {
	now := time.Now()
	config.ID = utils.GenerateID("notif")
	config.CreatedBy = userID
	config.CreatedAt = now
	config.UpdatedAt = now

	if err := s.db.WithContext(ctx).Create(config).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	s.notificationChans = append(s.notificationChans, *config)
	return config, nil
}

func (s *MonitoringService) ListNotificationChannels(ctx context.Context, page, pageSize int) ([]NotificationConfig, int64, error) {
	var channels []NotificationConfig
	var total int64

	query := s.db.WithContext(ctx).Model(&NotificationConfig{})

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&channels).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return channels, total, nil
}

func (s *MonitoringService) DeleteNotificationChannel(ctx context.Context, channelID string) error {
	if err := s.db.WithContext(ctx).Where("id = ?", channelID).Delete(&NotificationConfig{}).Error; err != nil {
		return appErr.Internal(err.Error())
	}

	for i, ch := range s.notificationChans {
		if ch.ID == channelID {
			s.notificationChans = append(s.notificationChans[:i], s.notificationChans[i+1:]...)
			break
		}
	}

	return nil
}

func (s *MonitoringService) GetMetricsSummary(ctx context.Context, startTime, endTime time.Time) map[string]interface{} {
	summary := make(map[string]interface{})

	for metricName, points := range s.metricsStore {
		var values []float64
		for _, p := range points {
			if p.Timestamp.After(startTime) && p.Timestamp.Before(endTime) {
				values = append(values, p.Value)
			}
		}

		if len(values) > 0 {
			sort.Float64s(values)
			sum := 0.0
			for _, v := range values {
				sum += v
			}
			summary[metricName] = map[string]interface{}{
				"count": len(values),
				"avg":   sum / float64(len(values)),
				"min":   values[0],
				"max":   values[len(values)-1],
				"p50":   values[len(values)/2],
				"p95":   values[int(float64(len(values))*0.95)],
				"p99":   values[int(float64(len(values))*0.99)],
			}
		}
	}

	return summary
}

func (s *MonitoringService) StartRuleEvaluator(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.EvaluateRules(ctx)
		}
	}
}
