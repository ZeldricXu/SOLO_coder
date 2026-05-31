package slo

import (
	"context"
	"fmt"
	"math"
	"sync"
	"time"

	"github.com/solocoder/backup-engine/internal/logger"
	"github.com/solocoder/backup-engine/pkg/common"
)

type SLIType string

const (
	SLIAvailability SLIType = "availability"
	SLILatency     SLIType = "latency"
	SLIQuality     SLIType = "quality"
	SLIThroughput  SLIType = "throughput"
)

type WindowType string

const (
	WindowRolling WindowType = "rolling"
	WindowCalendar WindowType = "calendar"
)

type SLOConfig struct {
	common.SLO
	WindowType      WindowType    `json:"window_type"`
	AlertThresholds []float64     `json:"alert_thresholds"`
}

type SLIEvent struct {
	Timestamp time.Time `json:"timestamp"`
	IsGood    bool      `json:"is_good"`
	Value     float64   `json:"value"`
	TraceID   string    `json:"trace_id"`
}

type SLOMonitor struct {
	sloConfigs map[string]*SLOConfig
	events     map[string][]SLIEvent
	budgets    map[string]*errorBudget
	mu         sync.RWMutex
	maxEvents  int
	alerts     chan *common.Alert
}

type errorBudget struct {
	totalSeconds     float64
	consumedSeconds  float64
	remainingSeconds float64
	lastUpdated      time.Time
	windowStart      time.Time
}

func NewSLOMonitor(maxEvents int) *SLOMonitor {
	return &SLOMonitor{
		sloConfigs: make(map[string]*SLOConfig),
		events:     make(map[string][]SLIEvent),
		budgets:    make(map[string]*errorBudget),
		maxEvents:  maxEvents,
		alerts:     make(chan *common.Alert, 100),
	}
}

func (m *SLOMonitor) AddSLO(config *SLOConfig) error {
	if config.Name == "" {
		return common.NewValidationError("name", "cannot be empty")
	}
	if config.TargetPercent <= 0 || config.TargetPercent > 100 {
		return common.NewValidationError("target_percent", "must be between 0 and 100")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.sloConfigs[config.Name]; exists {
		return common.ErrAlreadyExists
	}

	m.sloConfigs[config.Name] = config
	m.events[config.Name] = make([]SLIEvent, 0, m.maxEvents)

	totalSeconds := config.Period.Seconds()
	errorBudgetPercent := config.ErrorBudgetPercent
	if errorBudgetPercent == 0 {
		errorBudgetPercent = 100 - config.TargetPercent
	}

	m.budgets[config.Name] = &errorBudget{
		totalSeconds:     totalSeconds * (errorBudgetPercent / 100),
		consumedSeconds:  0,
		remainingSeconds: totalSeconds * (errorBudgetPercent / 100),
		lastUpdated:      time.Now(),
		windowStart:      time.Now(),
	}

	logger.Info("Added SLO", map[string]interface{}{
		"name":            config.Name,
		"target":          config.TargetPercent,
		"period":          config.Period.String(),
		"error_budget_pct": config.ErrorBudgetPercent,
	})

	return nil
}

func (m *SLOMonitor) RecordEvent(sloName string, event SLIEvent) error {
	m.mu.RLock()
	_, exists := m.sloConfigs[sloName]
	m.mu.RUnlock()

	if !exists {
		return common.ErrNotFound
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	m.events[sloName] = append(m.events[sloName], event)
	if len(m.events[sloName]) > m.maxEvents {
		m.events[sloName] = m.events[sloName][len(m.events[sloName])-m.maxEvents:]
	}

	if !event.IsGood {
		budget := m.budgets[sloName]
		budget.consumedSeconds += 60
		budget.remainingSeconds = budget.totalSeconds - budget.consumedSeconds
		budget.lastUpdated = time.Now()
	}

	return nil
}

func (m *SLOMonitor) CalculateSLI(sloName string, window time.Duration) (*common.SLIResult, error) {
	m.mu.RLock()
	sloConfig, exists := m.sloConfigs[sloName]
	events := m.events[sloName]
	m.mu.RUnlock()

	if !exists {
		return nil, common.ErrNotFound
	}

	cutoff := time.Now().Add(-window)
	var goodCount, totalCount int
	var values []float64

	for i := len(events) - 1; i >= 0; i-- {
		if events[i].Timestamp.Before(cutoff) {
			break
		}
		totalCount++
		if events[i].IsGood {
			goodCount++
		}
		values = append(values, events[i].Value)
	}

	if totalCount == 0 {
		return &common.SLIResult{
			Name:        sloName,
			Value:       100.0,
			WindowStart: cutoff,
			WindowEnd:   time.Now(),
			IsGood:      true,
		}, nil
	}

	sliValue := float64(goodCount) / float64(totalCount) * 100
	isGood := sliValue >= sloConfig.TargetPercent

	return &common.SLIResult{
		Name:        sloName,
		Value:       sliValue,
		WindowStart: cutoff,
		WindowEnd:   time.Now(),
		IsGood:      isGood,
	}, nil
}

func (m *SLOMonitor) GetErrorBudgetStatus(sloName string) (*common.ErrorBudgetStatus, error) {
	m.mu.RLock()
	sloConfig, configExists := m.sloConfigs[sloName]
	budget, budgetExists := m.budgets[sloName]
	events := m.events[sloName]
	m.mu.RUnlock()

	if !configExists || !budgetExists {
		return nil, common.ErrNotFound
	}

	consumptionRate := 0.0
	burnRate := 0.0
	estimatedBurnout := time.Time{}

	if len(events) >= 2 {
		recentEvents := events[common.Max(0, len(events)-100):]
		badEvents := 0
		for _, e := range recentEvents {
			if !e.IsGood {
				badEvents++
			}
		}

		if len(recentEvents) > 0 {
			consumptionRate = float64(badEvents) / float64(len(recentEvents))
			burnRate = consumptionRate / (1 - sloConfig.TargetPercent/100)
		}

		if budget.remainingSeconds > 0 && consumptionRate > 0 {
			hoursRemaining := budget.remainingSeconds / 3600 / consumptionRate
			estimatedBurnout = time.Now().Add(time.Duration(hoursRemaining * float64(time.Hour)))
		}
	}

	return &common.ErrorBudgetStatus{
		SLOName:          sloName,
		Remaining:        common.Clamp(budget.remainingSeconds/budget.totalSeconds*100, 0, 100),
		Consumed:         common.Clamp(budget.consumedSeconds/budget.totalSeconds*100, 0, 100),
		ConsumptionRate:  consumptionRate * 100,
		BurnRate:         burnRate,
		EstimatedBurnout: estimatedBurnout,
	}, nil
}

func (m *SLOMonitor) CheckBurnRate(sloName string, window time.Duration, threshold float64) (bool, float64, error) {
	m.mu.RLock()
	sloConfig, exists := m.sloConfigs[sloName]
	events := m.events[sloName]
	m.mu.RUnlock()

	if !exists {
		return false, 0, common.ErrNotFound
	}

	cutoff := time.Now().Add(-window)
	var badCount, totalCount int

	for i := len(events) - 1; i >= 0; i-- {
		if events[i].Timestamp.Before(cutoff) {
			break
		}
		totalCount++
		if !events[i].IsGood {
			badCount++
		}
	}

	if totalCount == 0 {
		return false, 0, nil
	}

	actualErrorRate := float64(badCount) / float64(totalCount)
	allowedErrorRate := 1 - sloConfig.TargetPercent/100
	burnRate := actualErrorRate / allowedErrorRate

	return burnRate > threshold, burnRate, nil
}

func (m *SLOMonitor) CheckAlerts() []*common.Alert {
	var alerts []*common.Alert

	m.mu.RLock()
	sloNames := make([]string, 0, len(m.sloConfigs))
	for name := range m.sloConfigs {
		sloNames = append(sloNames, name)
	}
	m.mu.RUnlock()

	for _, name := range sloNames {
		status, err := m.GetErrorBudgetStatus(name)
		if err != nil {
			continue
		}

		if status.BurnRate > 1.0 {
			severity := "warning"
			if status.BurnRate > 5.0 {
				severity = "critical"
			} else if status.BurnRate > 2.0 {
				severity = "error"
			}

			alert := &common.Alert{
				ID:        common.NewID(),
				RuleID:    "slo_burn_rate_" + name,
				RuleName:  "SLO Burn Rate Alert",
				Severity:  severity,
				Message:   "Error budget burning too fast",
				Labels: map[string]string{
					"slo": name,
				},
				Annotations: map[string]string{
					"description":      "SLO error budget is burning faster than expected",
					"burn_rate":        fmt.Sprintf("%.2f", status.BurnRate),
					"remaining_budget": fmt.Sprintf("%.2f%%", status.Remaining),
				},
				StartsAt: time.Now(),
				Status:   "firing",
			}

			alerts = append(alerts, alert)

			select {
			case m.alerts <- alert:
			default:
			}
		}

		if status.Remaining < 5.0 {
			alert := &common.Alert{
				ID:        common.NewID(),
				RuleID:    "slo_budget_depleted_" + name,
				RuleName:  "SLO Budget Depleted",
				Severity:  "critical",
				Message:   "Error budget almost depleted",
				Labels: map[string]string{
					"slo": name,
				},
				Annotations: map[string]string{
					"description":      "Error budget is almost depleted, take action immediately",
					"remaining_budget": fmt.Sprintf("%.2f%%", status.Remaining),
				},
				StartsAt: time.Now(),
				Status:   "firing",
			}

			alerts = append(alerts, alert)
		}
	}

	return alerts
}

func (m *SLOMonitor) ResetBudget(sloName string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	budget, exists := m.budgets[sloName]
	if !exists {
		return common.ErrNotFound
	}

	config := m.sloConfigs[sloName]
	errorBudgetPercent := config.ErrorBudgetPercent
	if errorBudgetPercent == 0 {
		errorBudgetPercent = 100 - config.TargetPercent
	}

	totalSeconds := config.Period.Seconds() * (errorBudgetPercent / 100)
	budget.totalSeconds = totalSeconds
	budget.consumedSeconds = 0
	budget.remainingSeconds = totalSeconds
	budget.lastUpdated = time.Now()
	budget.windowStart = time.Now()

	logger.Info("Reset error budget", map[string]interface{}{
		"slo_name": sloName,
		"total_seconds": totalSeconds,
	})

	return nil
}

func (m *SLOMonitor) GetSLOs() []*SLOConfig {
	m.mu.RLock()
	defer m.mu.RUnlock()

	sloList := make([]*SLOConfig, 0, len(m.sloConfigs))
	for _, slo := range m.sloConfigs {
		sloList = append(sloList, slo)
	}
	return sloList
}

func (m *SLOMonitor) AlertsChannel() <-chan *common.Alert {
	return m.alerts
}

func (m *SLOMonitor) Start(ctx context.Context, checkInterval time.Duration) {
	ticker := time.NewTicker(checkInterval)
	defer ticker.Stop()

	logger.Info("SLO monitor started", map[string]interface{}{
		"check_interval": checkInterval.String(),
	})

	for {
		select {
		case <-ctx.Done():
			logger.Info("SLO monitor stopped")
			return
		case <-ticker.C:
			alerts := m.CheckAlerts()
			if len(alerts) > 0 {
				logger.Warn("SLO alerts generated", map[string]interface{}{
					"count": len(alerts),
				})
			}
		}
	}
}

func (m *SLOMonitor) CalculateSLOForecast(sloName string, days int) (float64, float64, error) {
	m.mu.RLock()
	sloConfig, exists := m.sloConfigs[sloName]
	events := m.events[sloName]
	budget := m.budgets[sloName]
	m.mu.RUnlock()

	if !exists {
		return 0, 0, common.ErrNotFound
	}

	if len(events) < 10 {
		return 0, 0, nil
	}

	recentEvents := events[common.Max(0, len(events)-1000):]
	badEvents := 0
	for _, e := range recentEvents {
		if !e.IsGood {
			badEvents++
		}
	}

	dailyErrorRate := float64(badEvents) / float64(len(recentEvents))
	dailyBudgetConsumption := dailyErrorRate * 24 * 3600

	forecastConsumed := budget.consumedSeconds + dailyBudgetConsumption*float64(days)
	forecastRemaining := math.Max(0, budget.totalSeconds - forecastConsumed)
	forecastRemainingPct := forecastRemaining / budget.totalSeconds * 100

	window := time.Duration(days) * 24 * time.Hour
	sli, _ := m.CalculateSLI(sloName, window)
	forecastSLO := sli.Value

	return forecastSLO, forecastRemainingPct, nil
}
