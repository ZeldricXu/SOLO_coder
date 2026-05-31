package slomonitor

import (
	"context"
	"errors"
	"fmt"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"math"
	"sync"
	"taskmanager/internal/logger"
	"taskmanager/pkg/models"
	"time"
)

type BurnRateCalculationStrategy interface {
	GetName() string
	Calculate(sli *models.SLI, window time.Duration, slo *models.SLO) float64
	Threshold() float64
}

type DefaultBurnRateStrategy struct{}

func (s *DefaultBurnRateStrategy) GetName() string { return "default" }
func (s *DefaultBurnRateStrategy) Calculate(sli *models.SLI, window time.Duration, slo *models.SLO) float64 {
	if sli == nil || slo == nil || slo.ErrorBudget == 0 {
		return 0
	}
	if window == 0 {
		return 0
	}
	windowHours := window.Hours()
	if windowHours == 0 {
		return 0
	}
	errorRatio := sli.ErrorRatio
	budgetPerHour := slo.ErrorBudget / float64(slo.WindowDays) / 24
	if budgetPerHour <= 0 {
		return 0
	}
	burnRate := (errorRatio * 100) / budgetPerHour
	return math.Max(0, burnRate)
}
func (s *DefaultBurnRateStrategy) Threshold() float64 { return 1.0 }

type ConservativeBurnRateStrategy struct {
	SafetyFactor float64
}

func (s *ConservativeBurnRateStrategy) GetName() string { return "conservative" }
func (s *ConservativeBurnRateStrategy) Calculate(sli *models.SLI, window time.Duration, slo *models.SLO) float64 {
	if sli == nil || slo == nil || slo.ErrorBudget == 0 {
		return 0
	}
	base := (&DefaultBurnRateStrategy{}).Calculate(sli, window, slo)
	factor := s.SafetyFactor
	if factor <= 0 {
		factor = 1.2
	}
	return base * factor
}
func (s *ConservativeBurnRateStrategy) Threshold() float64 { return 0.8 }

type AggressiveBurnRateStrategy struct {
	AggressionFactor float64
}

func (s *AggressiveBurnRateStrategy) GetName() string { return "aggressive" }
func (s *AggressiveBurnRateStrategy) Calculate(sli *models.SLI, window time.Duration, slo *models.SLO) float64 {
	if sli == nil || slo == nil || slo.ErrorBudget == 0 {
		return 0
	}
	base := (&DefaultBurnRateStrategy{}).Calculate(sli, window, slo)
	factor := s.AggressionFactor
	if factor <= 0 {
		factor = 0.8
	}
	return base * factor
}
func (s *AggressiveBurnRateStrategy) Threshold() float64 { return 1.5 }

type MultiWindowBurnRateStrategy struct {
	ShortWindow time.Duration
	LongWindow  time.Duration
}

func (s *MultiWindowBurnRateStrategy) GetName() string { return "multi_window" }
func (s *MultiWindowBurnRateStrategy) Calculate(sli *models.SLI, window time.Duration, slo *models.SLO) float64 {
	if sli == nil || slo == nil {
		return 0
	}
	defaultStrat := &DefaultBurnRateStrategy{}
	shortWindow := s.ShortWindow
	if shortWindow == 0 {
		shortWindow = time.Hour
	}
	longWindow := s.LongWindow
	if longWindow == 0 {
		longWindow = 6 * time.Hour
	}
	shortRate := defaultStrat.Calculate(sli, shortWindow, slo)
	longRate := defaultStrat.Calculate(sli, longWindow, slo)
	return (shortRate + longRate) / 2
}
func (s *MultiWindowBurnRateStrategy) Threshold() float64 { return 1.0 }

type AlertingStrategy interface {
	GetName() string
	ShouldAlert(burnRate float64, slo *models.SLO) bool
	AlertSeverity(burnRate float64) string
}

type ThresholdAlertingStrategy struct {
	WarningThreshold  float64
	CriticalThreshold float64
}

func (s *ThresholdAlertingStrategy) GetName() string { return "threshold" }
func (s *ThresholdAlertingStrategy) ShouldAlert(burnRate float64, slo *models.SLO) bool {
	if slo.BurnRateThreshold == 0 {
		return burnRate > s.WarningThreshold
	}
	return burnRate > slo.BurnRateThreshold
}
func (s *ThresholdAlertingStrategy) AlertSeverity(burnRate float64) string {
	if burnRate > s.CriticalThreshold {
		return "critical"
	}
	if burnRate > s.WarningThreshold {
		return "warning"
	}
	return "info"
}

type SLOMonitorConfig struct {
	DefaultBurnRateStrategy  string `json:"default_burn_rate_strategy"`
	DefaultAlertingStrategy  string `json:"default_alerting_strategy"`
	DefaultWindowDays        int    `json:"default_window_days"`
	DefaultBurnRateThreshold float64 `json:"default_burn_rate_threshold"`
	AutoResetBudget          bool   `json:"auto_reset_budget"`
}

type StrategyChangeEvent struct {
	OldStrategy string
	NewStrategy string
	Timestamp   time.Time
}

type SLOMonitor struct {
	db                    *gorm.DB
	burnRateStrategies    map[string]BurnRateCalculationStrategy
	alertingStrategies    map[string]AlertingStrategy
	config                *SLOMonitorConfig
	configMu              sync.RWMutex
	mu                    sync.RWMutex
	strategyChangeCh      chan StrategyChangeEvent
	strategyListeners     []func(event StrategyChangeEvent)
	listenerMu            sync.RWMutex
}

func NewSLOMonitor(db *gorm.DB) *SLOMonitor {
	m := &SLOMonitor{
		db:                 db,
		burnRateStrategies: make(map[string]BurnRateCalculationStrategy),
		alertingStrategies: make(map[string]AlertingStrategy),
		config: &SLOMonitorConfig{
			DefaultBurnRateStrategy:  "default",
			DefaultAlertingStrategy:  "threshold",
			DefaultWindowDays:        30,
			DefaultBurnRateThreshold: 1.0,
			AutoResetBudget:          false,
		},
		strategyChangeCh: make(chan StrategyChangeEvent, 100),
	}
	m.registerDefaultStrategies()
	return m
}

func (m *SLOMonitor) registerDefaultStrategies() {
	m.burnRateStrategies["default"] = &DefaultBurnRateStrategy{}
	m.burnRateStrategies["conservative"] = &ConservativeBurnRateStrategy{SafetyFactor: 1.2}
	m.burnRateStrategies["aggressive"] = &AggressiveBurnRateStrategy{AggressionFactor: 0.8}
	m.burnRateStrategies["multi_window"] = &MultiWindowBurnRateStrategy{
		ShortWindow: time.Hour,
		LongWindow:  6 * time.Hour,
	}

	m.alertingStrategies["threshold"] = &ThresholdAlertingStrategy{
		WarningThreshold:  1.0,
		CriticalThreshold: 2.0,
	}
}

func (m *SLOMonitor) RegisterBurnRateStrategy(name string, strategy BurnRateCalculationStrategy) {
	m.configMu.Lock()
	defer m.configMu.Unlock()
	m.burnRateStrategies[name] = strategy
	logger.Info("burn rate strategy registered", zap.String("name", name))
}

func (m *SLOMonitor) RegisterAlertingStrategy(name string, strategy AlertingStrategy) {
	m.configMu.Lock()
	defer m.configMu.Unlock()
	m.alertingStrategies[name] = strategy
	logger.Info("alerting strategy registered", zap.String("name", name))
}

func (m *SLOMonitor) UnregisterBurnRateStrategy(name string) {
	m.configMu.Lock()
	defer m.configMu.Unlock()
	if name == "default" {
		return
	}
	delete(m.burnRateStrategies, name)
	logger.Info("burn rate strategy unregistered", zap.String("name", name))
}

func (m *SLOMonitor) UnregisterAlertingStrategy(name string) {
	m.configMu.Lock()
	defer m.configMu.Unlock()
	if name == "threshold" {
		return
	}
	delete(m.alertingStrategies, name)
	logger.Info("alerting strategy unregistered", zap.String("name", name))
}

func (m *SLOMonitor) SetBurnRateStrategy(name string) error {
	m.configMu.Lock()
	defer m.configMu.Unlock()
	if _, ok := m.burnRateStrategies[name]; !ok {
		return fmt.Errorf("unknown burn rate strategy: %s", name)
	}
	oldStrategy := m.config.DefaultBurnRateStrategy
	m.config.DefaultBurnRateStrategy = name
	m.notifyStrategyChange(oldStrategy, name)
	logger.Info("burn rate strategy changed",
		zap.String("old", oldStrategy),
		zap.String("new", name),
	)
	return nil
}

func (m *SLOMonitor) SetAlertingStrategy(name string) error {
	m.configMu.Lock()
	defer m.configMu.Unlock()
	if _, ok := m.alertingStrategies[name]; !ok {
		return fmt.Errorf("unknown alerting strategy: %s", name)
	}
	oldStrategy := m.config.DefaultAlertingStrategy
	m.config.DefaultAlertingStrategy = name
	logger.Info("alerting strategy changed",
		zap.String("old", oldStrategy),
		zap.String("new", name),
	)
	return nil
}

func (m *SLOMonitor) GetBurnRateStrategy(name string) (BurnRateCalculationStrategy, error) {
	m.configMu.RLock()
	defer m.configMu.RUnlock()
	strategy, ok := m.burnRateStrategies[name]
	if !ok {
		return nil, fmt.Errorf("burn rate strategy not found: %s", name)
	}
	return strategy, nil
}

func (m *SLOMonitor) GetAlertingStrategy(name string) (AlertingStrategy, error) {
	m.configMu.RLock()
	defer m.configMu.RUnlock()
	strategy, ok := m.alertingStrategies[name]
	if !ok {
		return nil, fmt.Errorf("alerting strategy not found: %s", name)
	}
	return strategy, nil
}

func (m *SLOMonitor) ListBurnRateStrategies() []string {
	m.configMu.RLock()
	defer m.configMu.RUnlock()
	names := make([]string, 0, len(m.burnRateStrategies))
	for name := range m.burnRateStrategies {
		names = append(names, name)
	}
	return names
}

func (m *SLOMonitor) ListAlertingStrategies() []string {
	m.configMu.RLock()
	defer m.configMu.RUnlock()
	names := make([]string, 0, len(m.alertingStrategies))
	for name := range m.alertingStrategies {
		names = append(names, name)
	}
	return names
}

func (m *SLOMonitor) AddStrategyListener(listener func(event StrategyChangeEvent)) {
	m.listenerMu.Lock()
	defer m.listenerMu.Unlock()
	m.strategyListeners = append(m.strategyListeners, listener)
}

func (m *SLOMonitor) RemoveStrategyListener(listener func(event StrategyChangeEvent)) {
	m.listenerMu.Lock()
	defer m.listenerMu.Unlock()
	for i, l := range m.strategyListeners {
		if fmt.Sprintf("%p", l) == fmt.Sprintf("%p", listener) {
			m.strategyListeners = append(m.strategyListeners[:i], m.strategyListeners[i+1:]...)
			break
		}
	}
}

func (m *SLOMonitor) notifyStrategyChange(oldStrategy, newStrategy string) {
	event := StrategyChangeEvent{
		OldStrategy: oldStrategy,
		NewStrategy: newStrategy,
		Timestamp:   time.Now(),
	}
	select {
	case m.strategyChangeCh <- event:
	default:
	}
	m.listenerMu.RLock()
	defer m.listenerMu.RUnlock()
	for _, listener := range m.strategyListeners {
		go listener(event)
	}
}

func (m *SLOMonitor) GetStrategyChangeChannel() <-chan StrategyChangeEvent {
	return m.strategyChangeCh
}

func (m *SLOMonitor) GetConfig() *SLOMonitorConfig {
	m.configMu.RLock()
	defer m.configMu.RUnlock()
	configCopy := *m.config
	return &configCopy
}

func (m *SLOMonitor) UpdateConfig(newConfig *SLOMonitorConfig) error {
	m.configMu.Lock()
	defer m.configMu.Unlock()
	if newConfig == nil {
		return errors.New("config cannot be nil")
	}
	if _, ok := m.burnRateStrategies[newConfig.DefaultBurnRateStrategy]; !ok {
		return fmt.Errorf("unknown burn rate strategy: %s", newConfig.DefaultBurnRateStrategy)
	}
	if _, ok := m.alertingStrategies[newConfig.DefaultAlertingStrategy]; !ok {
		return fmt.Errorf("unknown alerting strategy: %s", newConfig.DefaultAlertingStrategy)
	}
	if newConfig.DefaultWindowDays <= 0 {
		return errors.New("window days must be positive")
	}
	m.config = newConfig
	logger.Info("SLO monitor config updated")
	return nil
}

func (m *SLOMonitor) getSLOStrategy(slo *models.SLO) (BurnRateCalculationStrategy, AlertingStrategy) {
	m.configMu.RLock()
	defer m.configMu.RUnlock()

	burnStrategyName := m.config.DefaultBurnRateStrategy
	alertStrategyName := m.config.DefaultAlertingStrategy

	if slo.Parameters != nil {
		if s, ok := slo.Parameters["burn_rate_strategy"].(string); ok {
			if _, exists := m.burnRateStrategies[s]; exists {
				burnStrategyName = s
			}
		}
		if s, ok := slo.Parameters["alerting_strategy"].(string); ok {
			if _, exists := m.alertingStrategies[s]; exists {
				alertStrategyName = s
			}
		}
	}

	burnStrategy, _ := m.burnRateStrategies[burnStrategyName]
	alertStrategy, _ := m.alertingStrategies[alertStrategyName]
	return burnStrategy, alertStrategy
}

func (m *SLOMonitor) CreateSLO(ctx context.Context, slo *models.SLO) error {
	if slo.ID == "" {
		slo.ID = uuid.New().String()
	}
	slo.CreatedAt = time.Now()
	slo.UpdatedAt = time.Now()

	if slo.WindowDays == 0 {
		slo.WindowDays = m.config.DefaultWindowDays
	}
	if slo.BurnRateThreshold == 0 {
		slo.BurnRateThreshold = m.config.DefaultBurnRateThreshold
	}
	if slo.Parameters == nil {
		slo.Parameters = make(map[string]interface{})
	}

	slo.RemainingBudget = slo.ErrorBudget
	slo.BudgetExhausted = false

	return m.db.Create(slo).Error
}

func (m *SLOMonitor) GetSLO(ctx context.Context, id string) (*models.SLO, error) {
	var slo models.SLO
	if err := m.db.First(&slo, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &slo, nil
}

func (m *SLOMonitor) ListSLOs(ctx context.Context, service string) ([]models.SLO, error) {
	var slos []models.SLO
	query := m.db
	if service != "" {
		query = query.Where("service = ?", service)
	}
	if err := query.Find(&slos).Error; err != nil {
		return nil, err
	}
	return slos, nil
}

func (m *SLOMonitor) UpdateSLO(ctx context.Context, slo *models.SLO) error {
	existing, err := m.GetSLO(ctx, slo.ID)
	if err != nil {
		return err
	}
	slo.UpdatedAt = time.Now()
	slo.CreatedAt = existing.CreatedAt
	return m.db.Save(slo).Error
}

func (m *SLOMonitor) DeleteSLO(ctx context.Context, id string) error {
	return m.db.Delete(&models.SLO{}, "id = ?", id).Error
}

func (m *SLOMonitor) RecordSLI(ctx context.Context, sli *models.SLI) error {
	if sli.ID == "" {
		sli.ID = uuid.New().String()
	}
	sli.Timestamp = time.Now()
	now := sli.Timestamp

	slo, err := m.GetSLO(ctx, sli.SLOID)
	if err != nil {
		return err
	}

	windowStart := now.AddDate(0, 0, -slo.WindowDays)
	var existingSLIs []models.SLI
	if err := m.db.Where("slo_id = ? AND timestamp >= ?", sli.SLOID, windowStart).
		Order("timestamp desc").Find(&existingSLIs).Error; err != nil {
		return err
	}

	totalRequests := sli.TotalRequests
	errorRequests := sli.ErrorRequests
	for _, existing := range existingSLIs {
		totalRequests += existing.TotalRequests
		errorRequests += existing.ErrorRequests
	}

	var errorRatio float64
	if totalRequests > 0 {
		errorRatio = float64(errorRequests) / float64(totalRequests)
	}
	sli.ErrorRatio = errorRatio

	if err := m.db.Create(sli).Error; err != nil {
		return err
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	periodErrorBudget := slo.ErrorBudget / float64(slo.WindowDays)
	slo.RemainingBudget -= periodErrorBudget * errorRatio
	if slo.RemainingBudget <= 0 {
		slo.RemainingBudget = 0
		if !slo.BudgetExhausted {
			slo.BudgetExhausted = true
			slo.BudgetExhaustedAt = &now
			logger.Warn("error budget exhausted",
				zap.String("slo_id", slo.ID),
				zap.String("service", slo.Service),
			)
		}
	} else {
		slo.BudgetExhausted = false
		slo.BudgetExhaustedAt = nil
	}
	slo.UpdatedAt = now

	return m.db.Save(slo).Error
}

func (m *SLOMonitor) GetStatus(ctx context.Context, sloID string) (*models.SLOStatus, error) {
	slo, err := m.GetSLO(ctx, sloID)
	if err != nil {
		return nil, err
	}

	burnStrategy, alertStrategy := m.getSLOStrategy(slo)

	now := time.Now()
	window := time.Duration(slo.WindowDays) * 24 * time.Hour
	windowStart := now.Add(-window)

	var sli []models.SLI
	if err := m.db.Where("slo_id = ? AND timestamp >= ?", sloID, windowStart).
		Order("timestamp desc").Limit(100).Find(&sli).Error; err != nil {
		return nil, err
	}

	var totalRequests, errorRequests uint64
	var latestSLI *models.SLI
	if len(sli) > 0 {
		latestSLI = &sli[0]
		for _, s := range sli {
			totalRequests += s.TotalRequests
			errorRequests += s.ErrorRequests
		}
	}

	var errorRatio float64
	if totalRequests > 0 {
		errorRatio = float64(errorRequests) / float64(totalRequests)
	}

	var burnRate float64
	if latestSLI != nil {
		burnRate = burnStrategy.Calculate(latestSLI, time.Hour, slo)
	}

	budgetPct := 0.0
	if slo.ErrorBudget > 0 {
		budgetPct = (slo.RemainingBudget / slo.ErrorBudget) * 100
	}

	shouldAlert := false
	severity := "info"
	if alertStrategy != nil {
		shouldAlert = alertStrategy.ShouldAlert(burnRate, slo)
		severity = alertStrategy.AlertSeverity(burnRate)
	}

	status := &models.SLOStatus{
		Service:           slo.Service,
		SLOID:             slo.ID,
		SLIName:           slo.SLIName,
		CurrentSLI:        errorRatio,
		Target:            slo.Target,
		ErrorBudget:       slo.ErrorBudget,
		RemainingBudget:   slo.RemainingBudget,
		BudgetPercentage:  budgetPct,
		BurnRate:          burnRate,
		WindowDays:        slo.WindowDays,
		BudgetExhausted:   slo.BudgetExhausted,
		TotalRequests:     totalRequests,
		ErrorRequests:     errorRequests,
		ShouldAlert:       shouldAlert,
		AlertSeverity:     severity,
		BurnRateStrategy:  burnStrategy.GetName(),
		AlertingStrategy:  alertStrategy.GetName(),
	}

	if slo.BudgetExhaustedAt != nil {
		status.BudgetExhaustedAt = *slo.BudgetExhaustedAt
	}

	return status, nil
}

func (m *SLOMonitor) CheckHighBurnRates(ctx context.Context) ([]models.SLOStatus, error) {
	var slos []models.SLO
	if err := m.db.Where("budget_exhausted = ?", false).Find(&slos).Error; err != nil {
		return nil, err
	}

	var highBurnSLOs []models.SLOStatus
	for _, slo := range slos {
		status, err := m.GetStatus(ctx, slo.ID)
		if err != nil {
			logger.Error("get SLO status failed", zap.String("slo_id", slo.ID), zap.Error(err))
			continue
		}
		_, alertStrategy := m.getSLOStrategy(&slo)
		threshold := slo.BurnRateThreshold
		if threshold == 0 {
			threshold = alertStrategy.Threshold()
		}
		if status.BurnRate > threshold {
			highBurnSLOs = append(highBurnSLOs, *status)
			logger.Warn("high burn rate detected",
				zap.String("service", slo.Service),
				zap.String("slo_id", slo.ID),
				zap.Float64("burn_rate", status.BurnRate),
				zap.String("strategy", status.BurnRateStrategy),
			)
		}
	}

	return highBurnSLOs, nil
}

func (m *SLOMonitor) ResetErrorBudget(ctx context.Context, sloID string) error {
	slo, err := m.GetSLO(ctx, sloID)
	if err != nil {
		return err
	}

	slo.RemainingBudget = slo.ErrorBudget
	slo.BudgetExhausted = false
	slo.BudgetExhaustedAt = nil
	slo.UpdatedAt = time.Now()

	logger.Info("error budget reset",
		zap.String("slo_id", sloID),
		zap.Float64("budget", slo.ErrorBudget),
	)

	return m.db.Save(slo).Error
}

func (m *SLOMonitor) GetSLOByServiceAndSLI(ctx context.Context, service, sliName string) (*models.SLO, error) {
	var slo models.SLO
	if err := m.db.Where("service = ? AND sli_name = ?", service, sliName).First(&slo).Error; err != nil {
		return nil, err
	}
	return &slo, nil
}
