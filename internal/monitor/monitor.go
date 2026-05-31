package monitor

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"sort"
	"strings"
	"sync"
	"time"

	"techplatform/internal/dao"
	"techplatform/internal/notification"
	"techplatform/pkg/common"
	"techplatform/pkg/common/logger"
	"techplatform/pkg/common/utils"
	"techplatform/pkg/models"

	"gorm.io/gorm"
)

type AlertLevel string

const (
	AlertLevelInfo     AlertLevel = "info"
	AlertLevelWarning  AlertLevel = "warning"
	AlertLevelError    AlertLevel = "error"
	AlertLevelCritical AlertLevel = "critical"
)

type AlertStatus string

const (
	AlertStatusActive   AlertStatus = "active"
	AlertStatusResolved AlertStatus = "resolved"
	AlertStatusSilenced AlertStatus = "silenced"
)

type MetricType string

const (
	MetricTypeGauge   MetricType = "gauge"
	MetricTypeCounter MetricType = "counter"
	MetricTypeHistogram MetricType = "histogram"
)

type ComparisonOperator string

const (
	OpGreaterThan      ComparisonOperator = ">"
	OpGreaterThanEqual ComparisonOperator = ">="
	OpLessThan         ComparisonOperator = "<"
	OpLessThanEqual    ComparisonOperator = "<="
	OpEqual            ComparisonOperator = "=="
	OpNotEqual         ComparisonOperator = "!="
)

type AlertRule struct {
	models.BaseModel
	Name          string             `json:"name" gorm:"index;size:100"`
	Description   string             `json:"description"`
	MetricName    string             `json:"metric_name" gorm:"index;size:100"`
	Level         AlertLevel         `json:"level" gorm:"index;size:50"`
	Operator      ComparisonOperator `json:"operator" gorm:"size:10"`
	Threshold     float64            `json:"threshold"`
	Duration      int                `json:"duration_seconds"`
	Labels        string             `json:"labels"`
	Annotations   string             `json:"annotations"`
	ForChannels   string             `json:"for_channels"`
	Recipients    string             `json:"recipients"`
	Enabled       bool               `json:"enabled" gorm:"index"`
	RunEvery      int                `json:"run_every_seconds"`
	LastEvaluated *time.Time         `json:"last_evaluated"`
	CreatedBy     string             `json:"created_by"`
	SilenceUntil  *time.Time         `json:"silence_until"`
}

type Alert struct {
	models.BaseModel
	RuleID        string      `json:"rule_id" gorm:"index"`
	RuleName      string      `json:"rule_name"`
	Level         AlertLevel  `json:"level" gorm:"index;size:50"`
	Status        AlertStatus `json:"status" gorm:"index;size:50"`
	MetricName    string      `json:"metric_name"`
	CurrentValue  float64     `json:"current_value"`
	Threshold     float64     `json:"threshold"`
	Operator      string      `json:"operator"`
	Labels        string      `json:"labels"`
	Annotations   string      `json:"annotations"`
	StartedAt     *time.Time  `json:"started_at"`
	ResolvedAt    *time.Time  `json:"resolved_at"`
	Duration      int64       `json:"duration_seconds"`
	Recipients    string      `json:"recipients"`
	Notified      bool        `json:"notified"`
	NotifyCount   int         `json:"notify_count"`
	LastNotified  *time.Time  `json:"last_notified"`
}

type Metric struct {
	models.BaseModel
	Name       string    `json:"name" gorm:"index;size:100"`
	Type       MetricType `json:"type" gorm:"size:50"`
	Value      float64   `json:"value"`
	Labels     string    `json:"labels"`
	Timestamp  time.Time `json:"timestamp" gorm:"index"`
}

type MonitorManager struct {
	mu                sync.RWMutex
	db                *dao.DAO
	notifManager      *notification.NotificationManager
	alertRules        map[string]*AlertRule
	activeAlerts      map[string]*Alert
	metrics           map[string][]Metric
	stopChan          chan struct{}
	evalInterval      time.Duration
	running           bool
	metricsBufferSize int
}

type MonitorConfig struct {
	EvaluationInterval time.Duration
	MetricsBufferSize  int
}

func NewMonitorManager(db *dao.DAO, notifMgr *notification.NotificationManager, config MonitorConfig) *MonitorManager {
	if config.EvaluationInterval <= 0 {
		config.EvaluationInterval = 30 * time.Second
	}
	if config.MetricsBufferSize <= 0 {
		config.MetricsBufferSize = 10000
	}

	mm := &MonitorManager{
		db:                db,
		notifManager:      notifMgr,
		alertRules:        make(map[string]*AlertRule),
		activeAlerts:      make(map[string]*Alert),
		metrics:           make(map[string][]Metric),
		stopChan:          make(chan struct{}),
		evalInterval:      config.EvaluationInterval,
		metricsBufferSize: config.MetricsBufferSize,
	}

	db.AutoMigrate(&AlertRule{}, &Alert{}, &Metric{})
	mm.loadDefaultRules()
	mm.loadActiveAlerts()

	mm.running = true
	go mm.startEvaluator()

	logger.Info("Monitor manager initialized, eval interval: %v", config.EvaluationInterval)
	return mm
}

func (mm *MonitorManager) loadDefaultRules() {
	defaultRules := []*AlertRule{
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "CPU使用率过高",
			Description: "当CPU使用率持续超过阈值时触发告警",
			MetricName:  "cpu_usage",
			Level:       AlertLevelWarning,
			Operator:    OpGreaterThan,
			Threshold:   80.0,
			Duration:    300,
			ForChannels: "inapp,slack",
			Enabled:     true,
			RunEvery:    60,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "内存使用率过高",
			Description: "当内存使用率持续超过阈值时触发告警",
			MetricName:  "memory_usage",
			Level:       AlertLevelWarning,
			Operator:    OpGreaterThan,
			Threshold:   85.0,
			Duration:    300,
			ForChannels: "inapp,slack",
			Enabled:     true,
			RunEvery:    60,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "磁盘空间不足",
			Description: "当磁盘使用率超过阈值时触发告警",
			MetricName:  "disk_usage",
			Level:       AlertLevelCritical,
			Operator:    OpGreaterThan,
			Threshold:   90.0,
			Duration:    60,
			ForChannels: "inapp,slack,email",
			Enabled:     true,
			RunEvery:    300,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "服务不可用",
			Description: "当服务健康检查失败时触发告警",
			MetricName:  "service_health",
			Level:       AlertLevelCritical,
			Operator:    OpEqual,
			Threshold:   0,
			Duration:    60,
			ForChannels: "inapp,slack,email,sms",
			Enabled:     true,
			RunEvery:    30,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "错误率过高",
			Description: "当错误率超过阈值时触发告警",
			MetricName:  "error_rate",
			Level:       AlertLevelError,
			Operator:    OpGreaterThan,
			Threshold:   5.0,
			Duration:    120,
			ForChannels: "inapp,slack",
			Enabled:     true,
			RunEvery:    60,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "环境数量超限",
			Description: "当运行中的环境数量接近最大限制时触发告警",
			MetricName:  "active_environments",
			Level:       AlertLevelWarning,
			Operator:    OpGreaterThan,
			Threshold:   8.0,
			Duration:    60,
			ForChannels: "inapp",
			Enabled:     true,
			RunEvery:    300,
		},
	}

	for _, rule := range defaultRules {
		var existing AlertRule
		result := mm.db.DB().Where("name = ?", rule.Name).First(&existing)
		if result.Error == gorm.ErrRecordNotFound {
			mm.db.DB().Create(rule)
		}
	}

	var rules []AlertRule
	mm.db.DB().Where("enabled = ?", true).Find(&rules)
	for i := range rules {
		mm.alertRules[rules[i].ID] = &rules[i]
	}
}

func (mm *MonitorManager) loadActiveAlerts() {
	var alerts []Alert
	mm.db.DB().Where("status = ?", AlertStatusActive).Find(&alerts)
	for i := range alerts {
		mm.activeAlerts[alerts[i].RuleID] = &alerts[i]
	}
	logger.Info("Loaded %d active alerts", len(alerts))
}

func (mm *MonitorManager) CreateRule(rule *AlertRule) (*AlertRule, error) {
	if rule.Name == "" {
		return nil, fmt.Errorf("%w: rule name required", common.ErrInvalidInput)
	}
	if rule.MetricName == "" {
		return nil, fmt.Errorf("%w: metric name required", common.ErrInvalidInput)
	}

	rule.ID = utils.GenerateUUID()
	rule.Enabled = true
	if rule.RunEvery == 0 {
		rule.RunEvery = 60
	}

	if err := mm.db.DB().Create(rule).Error; err != nil {
		return nil, err
	}

	mm.mu.Lock()
	mm.alertRules[rule.ID] = rule
	mm.mu.Unlock()

	logger.Info("Alert rule created: %s (metric: %s)", rule.Name, rule.MetricName)
	return rule, nil
}

func (mm *MonitorManager) GetRule(id string) (*AlertRule, error) {
	mm.mu.RLock()
	if rule, exists := mm.alertRules[id]; exists {
		mm.mu.RUnlock()
		return rule, nil
	}
	mm.mu.RUnlock()

	var rule AlertRule
	if err := mm.db.DB().First(&rule, "id = ?", id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, common.ErrNotFound
		}
		return nil, err
	}
	return &rule, nil
}

func (mm *MonitorManager) ListRules(page, pageSize int, metric, level string, enabledOnly bool) (*models.PageResult, error) {
	page, pageSize = normalizePagination(page, pageSize)

	var rules []AlertRule
	var total int64

	query := mm.db.DB().Model(&AlertRule{})
	if metric != "" {
		query = query.Where("metric_name LIKE ?", "%"+metric+"%")
	}
	if level != "" {
		query = query.Where("level = ?", level)
	}
	if enabledOnly {
		query = query.Where("enabled = ?", true)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&rules).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Items:    rules,
	}, nil
}

func (mm *MonitorManager) UpdateRule(id string, updates map[string]interface{}) (*AlertRule, error) {
	rule, err := mm.GetRule(id)
	if err != nil {
		return nil, err
	}

	if err := mm.db.DB().Model(rule).Updates(updates).Error; err != nil {
		return nil, err
	}

	mm.db.DB().First(rule, "id = ?", id)

	mm.mu.Lock()
	mm.alertRules[id] = rule
	mm.mu.Unlock()

	logger.Info("Alert rule updated: %s", rule.Name)
	return rule, nil
}

func (mm *MonitorManager) DeleteRule(id string) error {
	rule, err := mm.GetRule(id)
	if err != nil {
		return err
	}

	if err := mm.db.DB().Delete(rule).Error; err != nil {
		return err
	}

	mm.mu.Lock()
	delete(mm.alertRules, id)
	delete(mm.activeAlerts, id)
	mm.mu.Unlock()

	logger.Info("Alert rule deleted: %s", rule.Name)
	return nil
}

func (mm *MonitorManager) RecordMetric(name string, value float64, metricType MetricType, labels map[string]string) error {
	labelsJSON, _ := json.Marshal(labels)

	metric := Metric{
		BaseModel: models.BaseModel{ID: utils.GenerateUUID()},
		Name:      name,
		Type:      metricType,
		Value:     value,
		Labels:    string(labelsJSON),
		Timestamp: time.Now(),
	}

	mm.mu.Lock()
	mm.metrics[name] = append(mm.metrics[name], metric)
	if len(mm.metrics[name]) > mm.metricsBufferSize {
		mm.metrics[name] = mm.metrics[name][len(mm.metrics[name])-mm.metricsBufferSize:]
	}
	mm.mu.Unlock()

	if err := mm.db.DB().Create(&metric).Error; err != nil {
		return err
	}

	return nil
}

func (mm *MonitorManager) GetMetricHistory(name string, startTime, endTime time.Time, interval string) ([]Metric, error) {
	var metrics []Metric
	query := mm.db.DB().Where("name = ? AND timestamp >= ? AND timestamp <= ?", name, startTime, endTime)

	if err := query.Order("timestamp ASC").Find(&metrics).Error; err != nil {
		return nil, err
	}

	return metrics, nil
}

func (mm *MonitorManager) GetLatestMetric(name string) (*Metric, error) {
	var metric Metric
	if err := mm.db.DB().Where("name = ?", name).Order("timestamp DESC").First(&metric).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, common.ErrNotFound
		}
		return nil, err
	}
	return &metric, nil
}

func (mm *MonitorManager) startEvaluator() {
	ticker := time.NewTicker(mm.evalInterval)
	defer ticker.Stop()

	for {
		select {
		case <-mm.stopChan:
			return
		case <-ticker.C:
			mm.evaluateRules()
		}
	}
}

func (mm *MonitorManager) evaluateRules() {
	mm.mu.RLock()
	rules := make([]*AlertRule, 0, len(mm.alertRules))
	for _, rule := range mm.alertRules {
		if rule.Enabled && (rule.SilenceUntil == nil || time.Now().After(*rule.SilenceUntil)) {
			now := time.Now()
			if rule.LastEvaluated == nil || now.Sub(*rule.LastEvaluated) >= time.Duration(rule.RunEvery)*time.Second {
				rules = append(rules, rule)
			}
		}
	}
	mm.mu.RUnlock()

	for _, rule := range rules {
		mm.evaluateRule(rule)
	}
}

func (mm *MonitorManager) evaluateRule(rule *AlertRule) {
	now := time.Now()

	mm.mu.Lock()
	rule.LastEvaluated = &now
	mm.db.DB().Save(rule)
	mm.mu.Unlock()

	values, err := mm.getMetricValues(rule.MetricName, time.Duration(rule.Duration)*time.Second)
	if err != nil {
		logger.Warn("Failed to get metric values for %s: %v", rule.MetricName, err)
		return
	}

	if len(values) == 0 {
		return
	}

	currentValue := mm.calculateAggregate(values, "avg")
	triggered := mm.evaluateCondition(currentValue, rule.Operator, rule.Threshold)

	mm.mu.Lock()
	activeAlert, exists := mm.activeAlerts[rule.ID]
	mm.mu.Unlock()

	if triggered {
		if !exists {
			mm.triggerAlert(rule, currentValue, values)
		} else {
			activeAlert.CurrentValue = currentValue
			mm.db.DB().Save(activeAlert)

			if shouldNotifyAgain(activeAlert) {
				mm.sendAlertNotification(activeAlert, false)
			}
		}
	} else {
		if exists {
			mm.resolveAlert(activeAlert)
		}
	}
}

func (mm *MonitorManager) getMetricValues(name string, duration time.Duration) ([]float64, error) {
	mm.mu.RLock()
	metrics, exists := mm.metrics[name]
	mm.mu.RUnlock()

	if !exists {
		var dbMetrics []Metric
		cutoff := time.Now().Add(-duration)
		mm.db.DB().Where("name = ? AND timestamp >= ?", name, cutoff).Order("timestamp ASC").Find(&dbMetrics)

		values := make([]float64, len(dbMetrics))
		for i, m := range dbMetrics {
			values[i] = m.Value
		}
		return values, nil
	}

	cutoff := time.Now().Add(-duration)
	values := make([]float64, 0)
	for i := len(metrics) - 1; i >= 0; i-- {
		if metrics[i].Timestamp.After(cutoff) {
			values = append([]float64{metrics[i].Value}, values...)
		} else {
			break
		}
	}

	return values, nil
}

func (mm *MonitorManager) calculateAggregate(values []float64, aggType string) float64 {
	if len(values) == 0 {
		return 0
	}

	switch aggType {
	case "avg":
		sum := 0.0
		for _, v := range values {
			sum += v
		}
		return sum / float64(len(values))
	case "max":
		max := values[0]
		for _, v := range values[1:] {
			if v > max {
				max = v
			}
		}
		return max
	case "min":
		min := values[0]
		for _, v := range values[1:] {
			if v < min {
				min = v
			}
		}
		return min
	case "sum":
		sum := 0.0
		for _, v := range values {
			sum += v
		}
		return sum
	case "p95":
		sorted := make([]float64, len(values))
		copy(sorted, values)
		sort.Float64s(sorted)
		idx := int(math.Ceil(0.95 * float64(len(sorted))))
		return sorted[idx-1]
	default:
		return values[len(values)-1]
	}
}

func (mm *MonitorManager) evaluateCondition(value float64, op ComparisonOperator, threshold float64) bool {
	switch op {
	case OpGreaterThan:
		return value > threshold
	case OpGreaterThanEqual:
		return value >= threshold
	case OpLessThan:
		return value < threshold
	case OpLessThanEqual:
		return value <= threshold
	case OpEqual:
		return value == threshold
	case OpNotEqual:
		return value != threshold
	default:
		return false
	}
}

func (mm *MonitorManager) triggerAlert(rule *AlertRule, currentValue float64, values []float64) {
	now := time.Now()

	labelsMap := make(map[string]interface{})
	if rule.Labels != "" {
		json.Unmarshal([]byte(rule.Labels), &labelsMap)
	}
	labelsMap["metric"] = rule.MetricName
	labelsMap["value"] = currentValue
	labelsMap["threshold"] = rule.Threshold
	labelsJSON, _ := json.Marshal(labelsMap)

	annotationsMap := make(map[string]interface{})
	if rule.Annotations != "" {
		json.Unmarshal([]byte(rule.Annotations), &annotationsMap)
	}
	annotationsMap["description"] = rule.Description
	annotationsMap["values_count"] = len(values)
	annotationsJSON, _ := json.Marshal(annotationsMap)

	alert := &Alert{
		BaseModel:    models.BaseModel{ID: utils.GenerateUUID()},
		RuleID:       rule.ID,
		RuleName:     rule.Name,
		Level:        rule.Level,
		Status:       AlertStatusActive,
		MetricName:   rule.MetricName,
		CurrentValue: currentValue,
		Threshold:    rule.Threshold,
		Operator:     string(rule.Operator),
		Labels:       string(labelsJSON),
		Annotations:  string(annotationsJSON),
		StartedAt:    &now,
		Recipients:   rule.Recipients,
		Notified:     false,
		NotifyCount:  0,
	}

	if err := mm.db.DB().Create(alert).Error; err != nil {
		logger.Error("Failed to create alert: %v", err)
		return
	}

	mm.mu.Lock()
	mm.activeAlerts[rule.ID] = alert
	mm.mu.Unlock()

	mm.sendAlertNotification(alert, true)

	logger.Warn("Alert triggered: %s (value: %.2f, threshold: %.2f)", rule.Name, currentValue, rule.Threshold)
}

func (mm *MonitorManager) resolveAlert(alert *Alert) {
	now := time.Now()
	alert.Status = AlertStatusResolved
	alert.ResolvedAt = &now
	if alert.StartedAt != nil {
		alert.Duration = int64(now.Sub(*alert.StartedAt).Seconds())
	}

	if err := mm.db.DB().Save(alert).Error; err != nil {
		logger.Error("Failed to resolve alert: %v", err)
		return
	}

	mm.mu.Lock()
	delete(mm.activeAlerts, alert.RuleID)
	mm.mu.Unlock()

	mm.sendAlertNotification(alert, false)

	logger.Info("Alert resolved: %s (duration: %ds)", alert.RuleName, alert.Duration)
}

func (mm *MonitorManager) sendAlertNotification(alert *Alert, isNew bool) {
	if mm.notifManager == nil {
		return
	}

	now := time.Now()
	alert.Notified = true
	alert.NotifyCount++
	alert.LastNotified = &now
	mm.db.DB().Save(alert)

	title := fmt.Sprintf("[%s] %s", strings.ToUpper(string(alert.Level)), alert.RuleName)
	if alert.Status == AlertStatusResolved {
		title = "[RESOLVED] " + alert.RuleName
	}

	statusText := "触发"
	if alert.Status == AlertStatusResolved {
		statusText = "恢复"
	}

	content := fmt.Sprintf(
		"告警%s: %s\n\n指标: %s\n当前值: %.2f\n阈值: %.2f %s\n持续时间: %ds\n\n详情: %s",
		statusText,
		alert.RuleName,
		alert.MetricName,
		alert.CurrentValue,
		alert.Threshold,
		alert.Operator,
		alert.Duration,
		alert.Annotations,
	)

	level := notification.LevelInfo
	switch alert.Level {
	case AlertLevelWarning:
		level = notification.LevelWarning
	case AlertLevelError:
		level = notification.LevelError
	case AlertLevelCritical:
		level = notification.LevelCritical
	}

	recipients := strings.Split(alert.Recipients, ",")
	for _, r := range recipients {
		r = strings.TrimSpace(r)
	}
	if len(recipients) == 0 || (len(recipients) == 1 && recipients[0] == "") {
		recipients = []string{"admin@example.com"}
	}

	notif := &notification.Notification{
		Title:      title,
		Content:    content,
		Level:      level,
		Channel:    notification.ChannelInApp,
		Recipients: strings.Join(recipients, ","),
		Source:     "monitor",
		SourceID:   alert.ID,
	}

	go mm.notifManager.Send(context.Background(), notif)
}

func shouldNotifyAgain(alert *Alert) bool {
	if alert.LastNotified == nil {
		return true
	}

	notifyInterval := time.Hour
	switch alert.Level {
	case AlertLevelCritical:
		notifyInterval = 15 * time.Minute
	case AlertLevelError:
		notifyInterval = 30 * time.Minute
	case AlertLevelWarning:
		notifyInterval = 1 * time.Hour
	default:
		notifyInterval = 4 * time.Hour
	}

	return time.Since(*alert.LastNotified) >= notifyInterval
}

func (mm *MonitorManager) ListAlerts(page, pageSize int, level, status, ruleID string) (*models.PageResult, error) {
	page, pageSize = normalizePagination(page, pageSize)

	var alerts []Alert
	var total int64

	query := mm.db.DB().Model(&Alert{})
	if level != "" {
		query = query.Where("level = ?", level)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if ruleID != "" {
		query = query.Where("rule_id = ?", ruleID)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&alerts).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Items:    alerts,
	}, nil
}

func (mm *MonitorManager) SilenceRule(id string, duration time.Duration) error {
	rule, err := mm.GetRule(id)
	if err != nil {
		return err
	}

	until := time.Now().Add(duration)
	rule.SilenceUntil = &until

	if err := mm.db.DB().Save(rule).Error; err != nil {
		return err
	}

	logger.Info("Alert rule silenced: %s until %v", rule.Name, until)
	return nil
}

func (mm *MonitorManager) GetActiveAlerts() []Alert {
	mm.mu.RLock()
	defer mm.mu.RUnlock()

	alerts := make([]Alert, 0, len(mm.activeAlerts))
	for _, a := range mm.activeAlerts {
		alerts = append(alerts, *a)
	}
	return alerts
}

func (mm *MonitorManager) GetStats() map[string]interface{} {
	var totalRules, enabledRules int64
	var totalAlerts, activeAlerts, resolvedAlerts int64

	mm.db.DB().Model(&AlertRule{}).Count(&totalRules)
	mm.db.DB().Model(&AlertRule{}).Where("enabled = ?", true).Count(&enabledRules)
	mm.db.DB().Model(&Alert{}).Count(&totalAlerts)
	mm.db.DB().Model(&Alert{}).Where("status = ?", AlertStatusActive).Count(&activeAlerts)
	mm.db.DB().Model(&Alert{}).Where("status = ?", AlertStatusResolved).Count(&resolvedAlerts)

	byLevel := make(map[string]int64)
	levels := []AlertLevel{AlertLevelInfo, AlertLevelWarning, AlertLevelError, AlertLevelCritical}
	for _, level := range levels {
		var count int64
		mm.db.DB().Model(&Alert{}).Where("level = ? AND status = ?", level, AlertStatusActive).Count(&count)
		byLevel[string(level)] = count
	}

	metricNames := make([]string, 0, len(mm.metrics))
	for name := range mm.metrics {
		metricNames = append(metricNames, name)
	}

	return map[string]interface{}{
		"total_rules":     totalRules,
		"enabled_rules":   enabledRules,
		"total_alerts":    totalAlerts,
		"active_alerts":   activeAlerts,
		"resolved_alerts": resolvedAlerts,
		"active_by_level": byLevel,
		"metrics_count":   len(metricNames),
		"metric_names":    metricNames,
		"eval_interval":   mm.evalInterval.String(),
	}
}

func (mm *MonitorManager) Stop() {
	close(mm.stopChan)
	mm.running = false
	logger.Info("Monitor manager stopped")
}

func (mm *MonitorManager) RecordSystemMetrics() {
	mm.RecordMetric("cpu_usage", 45.5, MetricTypeGauge, map[string]string{"host": "localhost"})
	mm.RecordMetric("memory_usage", 62.3, MetricTypeGauge, map[string]string{"host": "localhost"})
	mm.RecordMetric("disk_usage", 78.5, MetricTypeGauge, map[string]string{"host": "localhost", "mount": "/"})
	mm.RecordMetric("active_environments", 5, MetricTypeGauge, map[string]string{})
	mm.RecordMetric("request_count", 1250, MetricTypeCounter, map[string]string{"service": "api"})
	mm.RecordMetric("error_rate", 0.5, MetricTypeGauge, map[string]string{"service": "api"})
	mm.RecordMetric("service_health", 1, MetricTypeGauge, map[string]string{"service": "api"})
}

func normalizePagination(page, pageSize int) (int, int) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}
	if pageSize > 100 {
		pageSize = 100
	}
	return page, pageSize
}
