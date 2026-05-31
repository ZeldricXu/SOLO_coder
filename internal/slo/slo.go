package slo

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"time"

	"session130/internal/logger"
	"session130/internal/metrics"
	"session130/pkg/models"
)

const (
	defaultMaxMetricsPerSLI = 10000
	idHashLength            = 12
)

var (
	ErrSLINameRequired = errors.New("name is required")
	ErrSLONameRequired = errors.New("name is required")
	ErrSLIIDRequired   = errors.New("sli_id is required")
	ErrSLINotFound     = errors.New("SLI not found")
	ErrSLONotFound     = errors.New("SLO not found")
)

type SLICalculator struct {
	mu               sync.RWMutex
	sliConfigs       map[string]*models.SLIConfig
	sloConfigs       map[string]*models.SLOConfig
	sliMetrics       map[string][]*models.SLIMetric
	errorBudgets     map[string]*models.ErrorBudgetState
	burnRateAlerts   map[string]*models.BurnRateAlert
	maxMetricsPerSLI int
}

func NewSLICalculator() *SLICalculator {
	return &SLICalculator{
		sliConfigs:       make(map[string]*models.SLIConfig),
		sloConfigs:       make(map[string]*models.SLOConfig),
		sliMetrics:       make(map[string][]*models.SLIMetric),
		errorBudgets:     make(map[string]*models.ErrorBudgetState),
		burnRateAlerts:   make(map[string]*models.BurnRateAlert),
		maxMetricsPerSLI: defaultMaxMetricsPerSLI,
	}
}

func (c *SLICalculator) AddSLIConfig(config models.SLIConfig) bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	if config.SLIID == "" {
		config.SLIID = generateID("sli", config.Name)
	}
	now := time.Now()
	config.CreatedAt = now
	config.UpdatedAt = now

	c.sliConfigs[config.SLIID] = &config
	c.sliMetrics[config.SLIID] = make([]*models.SLIMetric, 0, c.maxMetricsPerSLI)

	metrics.Inc("slo_sli_config_added_total", nil)
	logger.Info("", "SLI config added", map[string]interface{}{
		"sli_id": config.SLIID,
		"name":   config.Name,
		"metric": config.MetricName,
		"goal":   config.Goal,
	})
	return true
}

func (c *SLICalculator) AddSLOConfig(config models.SLOConfig) bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	if config.SLOID == "" {
		config.SLOID = generateID("slo", config.Name)
	}
	now := time.Now()
	config.CreatedAt = now
	config.UpdatedAt = now

	c.sloConfigs[config.SLOID] = &config
	c.initializeErrorBudget(&config)

	metrics.Inc("slo_slo_config_added_total", nil)
	logger.Info("", "SLO config added", map[string]interface{}{
		"slo_id":       config.SLOID,
		"name":         config.Name,
		"sli_id":       config.SLIID,
		"target":       config.Target,
		"error_budget": config.ErrorBudget,
	})
	return true
}

func (c *SLICalculator) initializeErrorBudget(slo *models.SLOConfig) {
	windowStart := time.Now()
	windowEnd := windowStart.Add(parseWindowDuration(slo.Window))

	c.errorBudgets[slo.SLOID] = &models.ErrorBudgetState{
		SLOID:           slo.SLOID,
		RemainingBudget: slo.ErrorBudget,
		ConsumedBudget:  0,
		TotalBudget:     slo.ErrorBudget,
		BurnRate:        0,
		WindowStart:     windowStart,
		WindowEnd:       windowEnd,
		LastUpdated:     time.Now(),
	}
}

func (c *SLICalculator) GetSLIConfig(sliID string) *models.SLIConfig {
	c.mu.RLock()
	defer c.mu.RUnlock()

	config, exists := c.sliConfigs[sliID]
	if !exists {
		return nil
	}
	return config
}

func (c *SLICalculator) GetSLOConfig(sloID string) *models.SLOConfig {
	c.mu.RLock()
	defer c.mu.RUnlock()

	config, exists := c.sloConfigs[sloID]
	if !exists {
		return nil
	}
	return config
}

func (c *SLICalculator) GetAllSLOConfigs() []models.SLOConfig {
	c.mu.RLock()
	defer c.mu.RUnlock()

	configs := make([]models.SLOConfig, 0, len(c.sloConfigs))
	for _, config := range c.sloConfigs {
		configs = append(configs, *config)
	}
	return configs
}

func (c *SLICalculator) RecordSLI(sliID string, goodEvents, totalEvents int64, labels map[string]string) *models.SLIMetric {
	c.mu.RLock()
	sliConfig, exists := c.sliConfigs[sliID]
	if !exists {
		c.mu.RUnlock()
		return nil
	}
	c.mu.RUnlock()

	value := calculateSLIValue(goodEvents, totalEvents)
	metric := &models.SLIMetric{
		SLIID:       sliID,
		Timestamp:   time.Now(),
		Value:       value,
		GoodEvents:  goodEvents,
		TotalEvents: totalEvents,
		Labels:      labels,
	}

	c.appendMetric(sliID, metric)
	c.updateErrorBudgetsAsync(sliID, metric)
	c.emitSLIMetrics(sliID, value)

	return metric
}

func calculateSLIValue(goodEvents, totalEvents int64) float64 {
	if totalEvents <= 0 {
		return 0
	}
	return float64(goodEvents) / float64(totalEvents)
}

func (c *SLICalculator) appendMetric(sliID string, metric *models.SLIMetric) {
	c.mu.Lock()
	defer c.mu.Unlock()

	metricsArr := c.sliMetrics[sliID]
	metricsArr = append(metricsArr, metric)
	if len(metricsArr) > c.maxMetricsPerSLI {
		metricsArr = metricsArr[1:]
	}
	c.sliMetrics[sliID] = metricsArr
}

func (c *SLICalculator) updateErrorBudgetsAsync(sliID string, metric *models.SLIMetric) {
	go func() {
		c.mu.Lock()
		defer c.mu.Unlock()

		for _, sloConfig := range c.sloConfigs {
			if sloConfig.SLIID == sliID {
				c.updateErrorBudgetLocked(sloConfig, metric)
			}
		}
	}()
}

func (c *SLICalculator) emitSLIMetrics(sliID string, value float64) {
	metrics.Inc("slo_sli_metric_recorded_total", map[string]string{
		"sli_id": sliID,
	})
	metrics.Observe("slo_sli_value", value, map[string]string{
		"sli_id": sliID,
	})
}

func (c *SLICalculator) RecordAvailabilitySLI(sliID string, success bool, labels map[string]string) {
	good := int64(0)
	if success {
		good = 1
	}
	c.RecordSLI(sliID, good, 1, labels)
}

func (c *SLICalculator) updateErrorBudgetLocked(slo *models.SLOConfig, metric *models.SLIMetric) {
	budgetState, exists := c.errorBudgets[slo.SLOID]
	if !exists {
		return
	}

	now := time.Now()
	c.resetWindowIfExpired(budgetState, slo, now)
	c.consumeBudget(budgetState, slo, metric)
	c.updateBurnRate(budgetState, now)

	budgetState.LastUpdated = now

	go c.checkBurnRateAlertAsync(slo, budgetState)

	metrics.Gauge("slo_error_budget_remaining", int64(budgetState.RemainingBudget*100), map[string]string{
		"slo_id": slo.SLOID,
	})
	metrics.Gauge("slo_burn_rate", int64(budgetState.BurnRate*100), map[string]string{
		"slo_id": slo.SLOID,
	})
}

func (c *SLICalculator) resetWindowIfExpired(budgetState *models.ErrorBudgetState, slo *models.SLOConfig, now time.Time) {
	if now.After(budgetState.WindowEnd) {
		budgetState.WindowStart = now
		budgetState.WindowEnd = now.Add(parseWindowDuration(slo.Window))
		budgetState.ConsumedBudget = 0
		budgetState.RemainingBudget = slo.ErrorBudget
	}
}

func (c *SLICalculator) consumeBudget(budgetState *models.ErrorBudgetState, slo *models.SLOConfig, metric *models.SLIMetric) {
	sliDeficit := slo.Target - metric.Value
	if sliDeficit > 0 {
		budgetConsumed := sliDeficit * float64(metric.TotalEvents) / 100.0
		budgetState.ConsumedBudget += budgetConsumed
		budgetState.RemainingBudget = slo.ErrorBudget - budgetState.ConsumedBudget
	}
}

func (c *SLICalculator) updateBurnRate(budgetState *models.ErrorBudgetState, now time.Time) {
	elapsed := now.Sub(budgetState.WindowStart).Seconds()
	windowDuration := budgetState.WindowEnd.Sub(budgetState.WindowStart).Seconds()
	if elapsed > 0 && windowDuration > 0 {
		budgetState.BurnRate = (budgetState.ConsumedBudget / budgetState.TotalBudget) / (elapsed / windowDuration)
	}
}

func (c *SLICalculator) checkBurnRateAlertAsync(slo *models.SLOConfig, budgetState *models.ErrorBudgetState) {
	c.mu.Lock()
	defer c.mu.Unlock()

	alertKey := fmt.Sprintf("%s_burnrate", slo.SLOID)
	existingAlert, exists := c.burnRateAlerts[alertKey]

	severity, threshold := getAlertSeverityAndThreshold(budgetState.BurnRate)

	if severity == "" {
		c.resolveAlertIfFiring(existingAlert, slo)
		return
	}

	c.createOrUpdateAlert(existingAlert, slo, budgetState, alertKey, severity, threshold)
}

func getAlertSeverityAndThreshold(burnRate float64) (string, float64) {
	switch {
	case burnRate >= 2.0:
		return "critical", 2.0
	case burnRate >= 1.5:
		return "warning", 1.5
	case burnRate >= 1.0:
		return "info", 1.0
	default:
		return "", 0
	}
}

func (c *SLICalculator) resolveAlertIfFiring(existingAlert *models.BurnRateAlert, slo *models.SLOConfig) {
	if existingAlert != nil && existingAlert.Status == "firing" {
		now := time.Now()
		existingAlert.Status = "resolved"
		existingAlert.ResolvedAt = &now
		metrics.Inc("slo_burnrate_alert_resolved_total", map[string]string{
			"slo_id":   slo.SLOID,
			"severity": existingAlert.Severity,
		})
	}
}

func (c *SLICalculator) createOrUpdateAlert(
	existingAlert *models.BurnRateAlert,
	slo *models.SLOConfig,
	budgetState *models.ErrorBudgetState,
	alertKey string,
	severity string,
	threshold float64,
) {
	if existingAlert == nil || existingAlert.Status != "firing" {
		alert := &models.BurnRateAlert{
			AlertID:     generateID("alert", slo.SLOID),
			SLOID:       slo.SLOID,
			BurnRate:    budgetState.BurnRate,
			Threshold:   threshold,
			Severity:    severity,
			Status:      "firing",
			TriggeredAt: time.Now(),
		}
		c.burnRateAlerts[alertKey] = alert

		metrics.Inc("slo_burnrate_alert_fired_total", map[string]string{
			"slo_id":   slo.SLOID,
			"severity": severity,
		})

		logger.Warn("", "Burn rate alert fired", map[string]interface{}{
			"alert_id":          alert.AlertID,
			"slo_id":            slo.SLOID,
			"burn_rate":         budgetState.BurnRate,
			"threshold":         threshold,
			"severity":          severity,
			"remaining_budget":  budgetState.RemainingBudget,
		})
	} else {
		existingAlert.BurnRate = budgetState.BurnRate
	}
}

func (c *SLICalculator) GetErrorBudgetState(sloID string) *models.ErrorBudgetState {
	c.mu.RLock()
	defer c.mu.RUnlock()

	state, exists := c.errorBudgets[sloID]
	if !exists {
		return nil
	}
	return state
}

func (c *SLICalculator) GetAllErrorBudgetStates() []models.ErrorBudgetState {
	c.mu.RLock()
	defer c.mu.RUnlock()

	states := make([]models.ErrorBudgetState, 0, len(c.errorBudgets))
	for _, state := range c.errorBudgets {
		states = append(states, *state)
	}
	return states
}

func (c *SLICalculator) GetActiveAlerts() []models.BurnRateAlert {
	c.mu.RLock()
	defer c.mu.RUnlock()

	alerts := make([]models.BurnRateAlert, 0, len(c.burnRateAlerts))
	for _, alert := range c.burnRateAlerts {
		if alert.Status == "firing" {
			alerts = append(alerts, *alert)
		}
	}
	return alerts
}

func (c *SLICalculator) GetSnapshot() map[string]interface{} {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return map[string]interface{}{
		"sli_configs_count":   len(c.sliConfigs),
		"slo_configs_count":   len(c.sloConfigs),
		"error_budgets_count": len(c.errorBudgets),
		"active_alerts":       len(c.getActiveAlertsLocked()),
	}
}

func (c *SLICalculator) getActiveAlertsLocked() int {
	count := 0
	for _, alert := range c.burnRateAlerts {
		if alert.Status == "firing" {
			count++
		}
	}
	return count
}

type SLOManager struct {
	mu         sync.RWMutex
	calculator *SLICalculator
	router     *ReadWriteRouter
	sliCache   map[string]*models.SLIConfig
	sloCache   map[string]*models.SLOConfig
}

var (
	managerInstance *SLOManager
	managerOnce     sync.Once
)

func NewSLOManager() *SLOManager {
	return &SLOManager{
		calculator: NewSLICalculator(),
		router:     GetReadWriteRouter(),
		sliCache:   make(map[string]*models.SLIConfig),
		sloCache:   make(map[string]*models.SLOConfig),
	}
}

func GetManager() *SLOManager {
	managerOnce.Do(func() {
		managerInstance = NewSLOManager()
	})
	return managerInstance
}

func (m *SLOManager) GetCalculator() *SLICalculator {
	return m.calculator
}

func (m *SLOManager) GetRouter() *ReadWriteRouter {
	return m.router
}

func (m *SLOManager) CreateSLI(ctx context.Context, config models.SLIConfig) (*models.SLIConfig, error) {
	if config.Name == "" {
		return nil, ErrSLINameRequired
	}

	result := m.router.RouteWrite(ctx, "create_sli", []interface{}{config}, func(ctx context.Context) (interface{}, error) {
		m.calculator.AddSLIConfig(config)
		newConfig := m.calculator.GetSLIConfig(config.SLIID)
		if newConfig != nil {
			m.cacheSLI(newConfig)
		}
		return newConfig, nil
	}, nil)

	if result.Error != nil {
		return nil, result.Error
	}

	return result.Data.(*models.SLIConfig), nil
}

func (m *SLOManager) cacheSLI(config *models.SLIConfig) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sliCache[config.SLIID] = config
}

func (m *SLOManager) CreateSLO(ctx context.Context, config models.SLOConfig) (*models.SLOConfig, error) {
	if config.Name == "" {
		return nil, ErrSLONameRequired
	}
	if config.SLIID == "" {
		return nil, ErrSLIIDRequired
	}

	result := m.router.RouteWrite(ctx, "create_slo", []interface{}{config}, func(ctx context.Context) (interface{}, error) {
		m.calculator.AddSLOConfig(config)
		newConfig := m.calculator.GetSLOConfig(config.SLOID)
		if newConfig != nil {
			m.cacheSLO(newConfig)
		}
		return newConfig, nil
	}, nil)

	if result.Error != nil {
		return nil, result.Error
	}

	return result.Data.(*models.SLOConfig), nil
}

func (m *SLOManager) cacheSLO(config *models.SLOConfig) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sloCache[config.SLOID] = config
}

func (m *SLOManager) GetSLO(ctx context.Context, sloID string) (*models.SLOConfig, error) {
	if cached := m.getCachedSLO(sloID); cached != nil {
		return cached, nil
	}

	result := m.router.RouteRead(ctx, "get_slo", []interface{}{sloID}, func(ctx context.Context) (interface{}, error) {
		config := m.calculator.GetSLOConfig(sloID)
		if config == nil {
			return nil, fmt.Errorf("%w: %s", ErrSLONotFound, sloID)
		}
		return config, nil
	}, func(ctx context.Context, replica ReplicaInfo) (interface{}, error) {
		return m.calculator.GetSLOConfig(sloID), nil
	}, nil)

	if result.Error != nil {
		return nil, result.Error
	}

	config := result.Data.(*models.SLOConfig)
	m.cacheSLO(config)

	return config, nil
}

func (m *SLOManager) getCachedSLO(sloID string) *models.SLOConfig {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.sloCache[sloID]
}

func (m *SLOManager) GetAllErrorBudgets(ctx context.Context) ([]models.ErrorBudgetState, error) {
	result := m.router.RouteRead(ctx, "get_all_error_budgets", nil, func(ctx context.Context) (interface{}, error) {
		return m.calculator.GetAllErrorBudgetStates(), nil
	}, nil, nil)

	if result.Error != nil {
		return nil, result.Error
	}

	return result.Data.([]models.ErrorBudgetState), nil
}

func (m *SLOManager) RecordSuccess(ctx context.Context, sliID string, labels map[string]string) {
	m.calculator.RecordAvailabilitySLI(sliID, true, labels)
}

func (m *SLOManager) RecordFailure(ctx context.Context, sliID string, labels map[string]string) {
	m.calculator.RecordAvailabilitySLI(sliID, false, labels)
}

func (m *SLOManager) GetRouterStats() RoutingStats {
	return m.router.GetStats()
}

func (m *SLOManager) AddReplica(info ReplicaInfo) {
	m.router.AddReplica(info)
}

func (m *SLOManager) RemoveReplica(id string) bool {
	return m.router.RemoveReplica(id)
}

func (m *SLOManager) GetAllReplicas() []ReplicaInfo {
	return m.router.GetAllReplicas()
}

func (m *SLOManager) TriggerFailover() (string, error) {
	return m.router.TriggerFailover()
}

func generateID(prefix, name string) string {
	h := sha256.New()
	h.Write([]byte(fmt.Sprintf("%s:%s:%d", prefix, name, time.Now().UnixNano())))
	return prefix + "_" + hex.EncodeToString(h.Sum(nil))[:idHashLength]
}

func parseWindowDuration(window string) time.Duration {
	switch window {
	case "1h":
		return time.Hour
	case "1d":
		return 24 * time.Hour
	case "7d":
		return 7 * 24 * time.Hour
	case "30d":
		return 30 * 24 * time.Hour
	default:
		return 24 * time.Hour
	}
}
