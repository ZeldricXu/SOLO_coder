package slo

import (
	"context"
	"fmt"
	"go.uber.org/zap"
	"metricplatform/internal/models"
	"metricplatform/pkg/alertengine"
	"metricplatform/pkg/cache"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
)

type SLOMetricsProvider interface {
	GetSLIValue(ctx context.Context, expression string, window time.Duration) (float64, error)
}

type Monitor struct {
	sloDefinitions    map[string]*models.SLO
	sloStatuses       map[string]*models.SLOStatus
	metricsProvider   SLOMetricsProvider
	alertEvaluator    *alertengine.RuleEvaluator
	cron              *cron.Cron
	logger            *zap.Logger
	mu                sync.RWMutex

	sliCache          *cache.MultiLevelCache
	statusCache       *cache.MultiLevelCache
	cacheEnabled      bool
	repo              interface{}
}

type BurnRateLevel struct {
	Level    int
	Window   time.Duration
	Factor   float64
}

var burnRateLevels = []BurnRateLevel{
	{Level: 1, Window: time.Hour, Factor: 1.0},
	{Level: 2, Window: 6 * time.Hour, Factor: 2.0},
	{Level: 3, Window: 24 * time.Hour, Factor: 4.0},
	{Level: 4, Window: 72 * time.Hour, Factor: 8.0},
}

type MonitorOption func(*Monitor)

func WithSliCache(c *cache.MultiLevelCache) MonitorOption {
	return func(m *Monitor) {
		m.sliCache = c
	}
}

func WithStatusCache(c *cache.MultiLevelCache) MonitorOption {
	return func(m *Monitor) {
		m.statusCache = c
	}
}

func WithCacheEnabled(enabled bool) MonitorOption {
	return func(m *Monitor) {
		m.cacheEnabled = enabled
	}
}

func NewMonitor(mp SLOMetricsProvider, ae *alertengine.RuleEvaluator, logger *zap.Logger, opts ...MonitorOption) *Monitor {
	m := &Monitor{
		sloDefinitions:  make(map[string]*models.SLO),
		sloStatuses:     make(map[string]*models.SLOStatus),
		metricsProvider: mp,
		alertEvaluator:  ae,
		cron:            cron.New(),
		logger:          logger,
		cacheEnabled:    true,
	}

	for _, opt := range opts {
		opt(m)
	}

	if m.cacheEnabled {
		if m.sliCache == nil {
			m.sliCache = cache.NewMultiLevelCache(logger,
				cache.WithMemoryTTL(1*time.Minute),
				cache.WithRedisTTL(5*time.Minute),
				cache.WithPenetrationProtection(true, 30*time.Second),
				cache.WithLoader(func(ctx context.Context, key string) (interface{}, error) {
					return m.loadSLIFromSource(ctx, key)
				}),
			)
		}

		if m.statusCache == nil {
			m.statusCache = cache.NewMultiLevelCache(logger,
				cache.WithMemoryTTL(30*time.Second),
				cache.WithRedisTTL(2*time.Minute),
				cache.WithPenetrationProtection(true, 10*time.Second),
			)
		}
	}

	return m
}

func (m *Monitor) AddSLO(slo *models.SLO) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if slo.ID == "" {
		slo.ID = uuid.New().String()
	}
	slo.CreatedAt = time.Now()
	slo.UpdatedAt = time.Now()

	m.sloDefinitions[slo.ID] = slo
	m.sloStatuses[slo.ID] = &models.SLOStatus{
		ID:                  uuid.New().String(),
		SLOID:               slo.ID,
		CurrentSLI:          100.0,
		ErrorBudgetRemaining: 1.0,
		ErrorBudgetBurnRate: 0.0,
		BurnRateAlertLevel:  0,
		WindowStart:         time.Now().AddDate(0, 0, -slo.WindowDays),
		WindowEnd:           time.Now(),
		UpdatedAt:           time.Now(),
	}

	_, err := m.cron.AddFunc("@every 1m", func() {
		m.evaluateSLO(slo.ID)
	})
	if err != nil {
		return fmt.Errorf("failed to schedule SLO evaluation: %w", err)
	}

	if m.cacheEnabled {
		go m.warmupCache(context.Background(), slo.ID)
	}

	m.logger.Info("SLO added", zap.String("slo_id", slo.ID), zap.String("name", slo.Name))
	return nil
}

func (m *Monitor) RemoveSLO(sloID string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.cacheEnabled {
		ctx := context.Background()
		m.sliCache.Delete(ctx, m.sliCacheKey(sloID))
		m.statusCache.Delete(ctx, m.statusCacheKey(sloID))
	}

	delete(m.sloDefinitions, sloID)
	delete(m.sloStatuses, sloID)
	m.logger.Info("SLO removed", zap.String("slo_id", sloID))
}

func (m *Monitor) Start() {
	m.cron.Start()
	m.logger.Info("SLO monitor started", zap.Bool("cache_enabled", m.cacheEnabled))
}

func (m *Monitor) Stop() {
	m.cron.Stop()
	m.logger.Info("SLO monitor stopped")
}

func (m *Monitor) evaluateSLO(sloID string) {
	m.mu.RLock()
	slo, exists := m.sloDefinitions[sloID]
	status := m.sloStatuses[sloID]
	m.mu.RUnlock()

	if !exists {
		return
	}

	ctx := context.Background()
	window := time.Duration(slo.WindowDays) * 24 * time.Hour

	sliValue, err := m.getSLIValue(ctx, slo.ID, slo.SLIExpression, window)
	if err != nil {
		m.logger.Error("Failed to calculate SLI", zap.Error(err), zap.String("slo_id", sloID))
		return
	}

	errorBudget := 100.0 - slo.TargetPercent
	errorBudgetRemaining := (100.0 - sliValue) / errorBudget
	if errorBudgetRemaining > 1.0 {
		errorBudgetRemaining = 0.0
	} else {
		errorBudgetRemaining = 1.0 - errorBudgetRemaining
	}

	burnRate := calculateBurnRate(sliValue, slo.TargetPercent, window)
	alertLevel := calculateBurnRateAlertLevel(burnRate, sloID, m)

	m.mu.Lock()
	status.CurrentSLI = sliValue
	status.ErrorBudgetRemaining = errorBudgetRemaining
	status.ErrorBudgetBurnRate = burnRate
	status.BurnRateAlertLevel = alertLevel
	status.WindowStart = time.Now().Add(-window)
	status.WindowEnd = time.Now()
	status.UpdatedAt = time.Now()
	m.mu.Unlock()

	if m.cacheEnabled {
		m.statusCache.Set(ctx, m.statusCacheKey(sloID), status)
	}

	if alertLevel > 0 {
		m.triggerBurnAlert(ctx, slo, status, alertLevel)
	}

	m.logger.Info("SLO evaluated",
		zap.String("slo_id", sloID),
		zap.Float64("sli", sliValue),
		zap.Float64("budget_remaining", errorBudgetRemaining),
		zap.Float64("burn_rate", burnRate),
		zap.Int("alert_level", alertLevel))
}

func (m *Monitor) getSLIValue(ctx context.Context, sloID, expression string, window time.Duration) (float64, error) {
	if !m.cacheEnabled {
		return m.metricsProvider.GetSLIValue(ctx, expression, window)
	}

	cacheKey := m.sliCacheKey(sloID)
	cachedValue, level, err := m.sliCache.GetWithLoader(ctx, cacheKey, func(ctx context.Context, key string) (interface{}, error) {
		return m.loadSLIFromSource(ctx, sloID+":"+expression+":"+window.String())
	})

	if err != nil {
		return 0, err
	}

	if cachedValue == nil {
		return m.metricsProvider.GetSLIValue(ctx, expression, window)
	}

	m.logger.Debug("SLI value retrieved", zap.String("slo_id", sloID), zap.String("cache_level", string(level)))

	if f, ok := cachedValue.(float64); ok {
		return f, nil
	}
	return 0, fmt.Errorf("invalid cached value type")
}

func (m *Monitor) loadSLIFromSource(ctx context.Context, key string) (interface{}, error) {
	parts := splitKey(key)
	if len(parts) < 3 {
		return nil, fmt.Errorf("invalid cache key: %s", key)
	}

	sloID := parts[0]
	expression := parts[1]
	windowStr := parts[2]

	window, err := time.ParseDuration(windowStr)
	if err != nil {
		window = 30 * 24 * time.Hour
	}

	m.mu.RLock()
	slo, exists := m.sloDefinitions[sloID]
	m.mu.RUnlock()

	if !exists {
		return nil, nil
	}

	value, err := m.metricsProvider.GetSLIValue(ctx, expression, window)
	if err != nil {
		return nil, err
	}

	m.logger.Debug("SLI loaded from source",
		zap.String("slo_id", sloID),
		zap.Float64("value", value),
		zap.String("expression", slo.SLIExpression))

	return value, nil
}

func (m *Monitor) warmupCache(ctx context.Context, sloID string) {
	m.mu.RLock()
	slo, exists := m.sloDefinitions[sloID]
	m.mu.RUnlock()

	if !exists {
		return
	}

	window := time.Duration(slo.WindowDays) * 24 * time.Hour
	keys := []string{m.sliCacheKey(sloID), m.statusCacheKey(sloID)}

	m.sliCache.Warmup(ctx, keys, func(ctx context.Context, key string) (interface{}, error) {
		if key == m.sliCacheKey(sloID) {
			return m.loadSLIFromSource(ctx, sloID+":"+slo.SLIExpression+":"+window.String())
		}
		return nil, nil
	})

	m.logger.Info("SLO cache warmed up", zap.String("slo_id", sloID))
}

func (m *Monitor) InvalidateCache(sloID string) error {
	if !m.cacheEnabled {
		return nil
	}

	ctx := context.Background()
	if err := m.sliCache.Delete(ctx, m.sliCacheKey(sloID)); err != nil {
		return err
	}
	if err := m.statusCache.Delete(ctx, m.statusCacheKey(sloID)); err != nil {
		return err
	}

	m.logger.Info("SLO cache invalidated", zap.String("slo_id", sloID))
	return nil
}

func calculateBurnRate(currentSLI, targetPercent float64, window time.Duration) float64 {
	if targetPercent >= 100 {
		return 0
	}
	errorBudget := 100.0 - targetPercent
	currentErrorRate := 100.0 - currentSLI
	if errorBudget <= 0 {
		return 0
	}
	return currentErrorRate / errorBudget
}

func calculateBurnRateAlertLevel(burnRate float64, sloID string, m *Monitor) int {
	for i := len(burnRateLevels) - 1; i >= 0; i-- {
		level := burnRateLevels[i]
		if burnRate >= level.Factor {
			return level.Level
		}
	}
	return 0
}

func (m *Monitor) triggerBurnAlert(ctx context.Context, slo *models.SLO, status *models.SLOStatus, level int) {
	window := time.Duration(slo.WindowDays) * 24 * time.Hour
	daysRemaining := estimateDaysRemaining(status.ErrorBudgetRemaining, status.ErrorBudgetBurnRate, window)

	rule := &models.AlertRule{
		ID:          fmt.Sprintf("slo-burn-%s-%d", slo.ID, level),
		Name:        fmt.Sprintf("SLO Burn Rate Alert - %s (Level %d)", slo.Name, level),
		Expression:  fmt.Sprintf("slo_burn_rate{slo=\"%s\"} >= %.1f", slo.Name, burnRateLevels[level-1].Factor),
		Severity:    getSeverityForLevel(level),
		ForDuration: 5 * time.Minute,
		Labels: map[string]string{
			"slo_id":  slo.ID,
			"slo_name": slo.Name,
			"level":   fmt.Sprintf("%d", level),
		},
		Annotations: map[string]string{
			"summary": fmt.Sprintf("Error budget burning at %.1fx rate for %s", status.ErrorBudgetBurnRate, slo.Name),
			"description": fmt.Sprintf("Current SLI: %.2f%%, Target: %.2f%%, Budget Remaining: %.1f%%, Est. Days Left: %.1f",
				status.CurrentSLI, slo.TargetPercent, status.ErrorBudgetRemaining*100, daysRemaining),
			"runbook": "https://runbooks.example.com/slo-burn",
		},
		Enabled: true,
	}

	if err := m.alertEvaluator.AddRule(rule); err != nil {
		m.logger.Error("Failed to add burn rate alert rule", zap.Error(err))
	}
}

func getSeverityForLevel(level int) string {
	switch level {
	case 1:
		return "info"
	case 2:
		return "warning"
	case 3:
		return "critical"
	case 4:
		return "critical"
	default:
		return "info"
	}
}

func estimateDaysRemaining(budgetRemaining, burnRate float64, totalWindow time.Duration) float64 {
	if burnRate <= 0 {
		return totalWindow.Hours() / 24
	}
	if budgetRemaining <= 0 {
		return 0
	}
	totalBudget := totalWindow.Hours() / 24
	remainingHours := (budgetRemaining * totalBudget * 24) / burnRate
	return remainingHours / 24
}

func (m *Monitor) GetSLO(sloID string) (*models.SLO, *models.SLOStatus, bool) {
	if m.cacheEnabled {
		ctx := context.Background()
		cachedStatus, _, err := m.statusCache.GetWithLoader(ctx, m.statusCacheKey(sloID), func(ctx context.Context, key string) (interface{}, error) {
			m.mu.RLock()
			status, ok := m.sloStatuses[sloID]
			m.mu.RUnlock()
			if !ok {
				return nil, nil
			}
			return status, nil
		})

		if err == nil && cachedStatus != nil {
			m.mu.RLock()
			slo, ok1 := m.sloDefinitions[sloID]
			m.mu.RUnlock()
			if status, ok := cachedStatus.(*models.SLOStatus); ok && ok1 {
				return slo, status, true
			}
		}
	}

	m.mu.RLock()
	defer m.mu.RUnlock()
	slo, ok1 := m.sloDefinitions[sloID]
	status, ok2 := m.sloStatuses[sloID]
	return slo, status, ok1 && ok2
}

func (m *Monitor) GetAllSLOs() ([]*models.SLO, []*models.SLOStatus) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	slos := make([]*models.SLO, 0, len(m.sloDefinitions))
	statuses := make([]*models.SLOStatus, 0, len(m.sloStatuses))

	for _, slo := range m.sloDefinitions {
		slos = append(slos, slo)
		if status, ok := m.sloStatuses[slo.ID]; ok {
			statuses = append(statuses, status)
		}
	}
	return slos, statuses
}

func (m *Monitor) GetCacheStats() map[string]interface{} {
	if !m.cacheEnabled {
		return map[string]interface{}{"enabled": false}
	}

	sliStats := m.sliCache.GetStats()
	statusStats := m.statusCache.GetStats()

	return map[string]interface{}{
		"enabled": true,
		"sli_cache":    sliStats,
		"status_cache": statusStats,
	}
}

func (m *Monitor) sliCacheKey(sloID string) string {
	return fmt.Sprintf("slo:sli:%s", sloID)
}

func (m *Monitor) statusCacheKey(sloID string) string {
	return fmt.Sprintf("slo:status:%s", sloID)
}

func splitKey(key string) []string {
	var parts []string
	current := ""
	for _, c := range key {
		if c == ':' {
			parts = append(parts, current)
			current = ""
		} else {
			current += string(c)
		}
	}
	if current != "" {
		parts = append(parts, current)
	}
	return parts
}
