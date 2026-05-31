package slo

import (
	"context"
	"math"
	"sync"
	"time"

	"github.com/google/uuid"
	"observability-platform/pkg/models"
)

type SLICalculator interface {
	Calculate(sli *models.SLI, window time.Duration) (float64, error)
}

type AvailabilitySLICalculator struct {
	metricProvider MetricProvider
}

type LatencySLICalculator struct {
	metricProvider MetricProvider
	threshold      time.Duration
}

type MetricProvider interface {
	GetMetricValue(name string, labels map[string]string) (float64, bool)
	GetMetricSeries(name string, labels map[string]string, window time.Duration) []float64
}

type SLOMonitor struct {
	sloDefinitions   map[string]*models.SLO
	alertPolicies    map[string]*models.SLOAlertPolicy
	sliCalculators   map[string]SLICalculator
	metricProvider   MetricProvider
	records          []models.SLORecord
	evalInterval     time.Duration
	mu               sync.RWMutex
	ctx              context.Context
	cancel           context.CancelFunc
	wg               sync.WaitGroup
	alertCallback    func(slo *models.SLO, alertType string)
}

type MonitorConfig struct {
	EvaluationInterval time.Duration
	MaxRecordSize      int
}

func NewSLOMonitor(config MonitorConfig, provider MetricProvider) *SLOMonitor {
	if config.EvaluationInterval <= 0 {
		config.EvaluationInterval = time.Minute
	}
	if config.MaxRecordSize <= 0 {
		config.MaxRecordSize = 10000
	}

	ctx, cancel := context.WithCancel(context.Background())

	return &SLOMonitor{
		sloDefinitions: make(map[string]*models.SLO),
		alertPolicies:  make(map[string]*models.SLOAlertPolicy),
		sliCalculators: make(map[string]SLICalculator),
		metricProvider: provider,
		records:        make([]models.SLORecord, 0, config.MaxRecordSize),
		evalInterval:   config.EvaluationInterval,
		ctx:            ctx,
		cancel:         cancel,
	}
}

func (m *SLOMonitor) AddSLO(slo *models.SLO) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if slo.ID == "" {
		slo.ID = uuid.New().String()
	}
	slo.CreatedAt = time.Now()
	slo.UpdatedAt = time.Now()

	m.sloDefinitions[slo.ID] = slo
	m.updateErrorBudget(slo)
}

func (m *SLOMonitor) GetSLO(id string) (*models.SLO, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	slo, exists := m.sloDefinitions[id]
	return slo, exists
}

func (m *SLOMonitor) GetAllSLOs() []*models.SLO {
	m.mu.RLock()
	defer m.mu.RUnlock()

	sloList := make([]*models.SLO, 0, len(m.sloDefinitions))
	for _, slo := range m.sloDefinitions {
		sloList = append(sloList, slo)
	}
	return sloList
}

func (m *SLOMonitor) DeleteSLO(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.sloDefinitions[id]; !exists {
		return &SLOError{Message: "SLO not found: " + id}
	}

	delete(m.sloDefinitions, id)
	delete(m.alertPolicies, id)
	return nil
}

func (m *SLOMonitor) AddAlertPolicy(policy *models.SLOAlertPolicy) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if policy.ID == "" {
		policy.ID = uuid.New().String()
	}
	m.alertPolicies[policy.SLOID] = policy
}

func (m *SLOMonitor) SetAlertCallback(callback func(slo *models.SLO, alertType string)) {
	m.alertCallback = callback
}

func (m *SLOMonitor) Start() {
	m.wg.Add(1)
	go m.evaluationLoop()
}

func (m *SLOMonitor) Stop() {
	m.cancel()
	m.wg.Wait()
}

func (m *SLOMonitor) evaluationLoop() {
	defer m.wg.Done()

	ticker := time.NewTicker(m.evalInterval)
	defer ticker.Stop()

	for {
		select {
		case <-m.ctx.Done():
			return
		case <-ticker.C:
			m.evaluateAllSLOs()
		}
	}
}

func (m *SLOMonitor) evaluateAllSLOs() {
	m.mu.RLock()
	sloIDs := make([]string, 0, len(m.sloDefinitions))
	for id := range m.sloDefinitions {
		sloIDs = append(sloIDs, id)
	}
	m.mu.RUnlock()

	for _, id := range sloIDs {
		m.evaluateSLO(id)
	}
}

func (m *SLOMonitor) evaluateSLO(id string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	slo, exists := m.sloDefinitions[id]
	if !exists {
		return
	}

	sliValue, err := m.calculateSLI(&slo.SLI, slo.Window)
	if err != nil {
		return
	}

	slo.SLI.CurrentValue = sliValue
	m.updateErrorBudget(slo)
	m.evaluateBurnRate(slo)
	slo.UpdatedAt = time.Now()

	record := models.SLORecord{
		Timestamp:      time.Now(),
		SLOID:          slo.ID,
		SLIValue:       sliValue,
		BudgetConsumed: slo.ErrorBudget.ConsumedPercentage,
		BurnRate:       slo.ErrorBudget.BurnRate,
	}
	m.records = append(m.records, record)

	maxRecords := cap(m.records)
	if len(m.records) > maxRecords {
		m.records = m.records[len(m.records)-maxRecords:]
	}
}

func (m *SLOMonitor) calculateSLI(sli *models.SLI, window time.Duration) (float64, error) {
	switch sli.Type {
	case "availability":
		return m.calculateAvailabilitySLI(sli, window)
	case "latency":
		return m.calculateLatencySLI(sli, window)
	case "throughput":
		return m.calculateThroughputSLI(sli, window)
	default:
		return 1.0, nil
	}
}

func (m *SLOMonitor) calculateAvailabilitySLI(sli *models.SLI, window time.Duration) (float64, error) {
	totalRequests, _ := m.metricProvider.GetMetricValue("requests_total", sli.Labels)
	errorRequests, _ := m.metricProvider.GetMetricValue("requests_errors_total", sli.Labels)

	if totalRequests == 0 {
		return 1.0, nil
	}

	successRate := 1.0 - (errorRequests / totalRequests)
	if successRate < 0 {
		successRate = 0
	}
	return successRate, nil
}

func (m *SLOMonitor) calculateLatencySLI(sli *models.SLI, window time.Duration) (float64, error) {
	_ = 500.0
	if val, ok := sli.Config["threshold_ms"].(float64); ok {
		_ = val
	}

	totalRequests, _ := m.metricProvider.GetMetricValue("requests_total", sli.Labels)
	fastRequests, _ := m.metricProvider.GetMetricValue("requests_fast_total", sli.Labels)

	if totalRequests == 0 {
		return 1.0, nil
	}

	return fastRequests / totalRequests, nil
}

func (m *SLOMonitor) calculateThroughputSLI(sli *models.SLI, window time.Duration) (float64, error) {
	throughput, _ := m.metricProvider.GetMetricValue("throughput", sli.Labels)
	target, _ := sli.Config["target"].(float64)

	if target == 0 {
		return 1.0, nil
	}

	return math.Min(throughput/target, 1.0), nil
}

func (m *SLOMonitor) updateErrorBudget(slo *models.SLO) {
	totalBudgetSeconds := slo.Window.Seconds() * (1.0 - slo.Target)
	slo.ErrorBudget.TotalBudgetSeconds = totalBudgetSeconds

	sliPerformance := slo.SLI.CurrentValue
	if sliPerformance >= slo.Target {
		slo.ErrorBudget.ConsumedSeconds = 0
		slo.ErrorBudget.ConsumedPercentage = 0
		slo.ErrorBudget.RemainingSeconds = totalBudgetSeconds
		slo.ErrorBudget.RemainingPercentage = 100
		slo.ErrorBudget.Status = models.ErrorBudgetStatusHealthy
		slo.ErrorBudget.BurnRate = 0
	} else {
		deficit := slo.Target - sliPerformance
		consumedSeconds := totalBudgetSeconds * deficit / (1.0 - slo.Target)
		consumedPercent := (consumedSeconds / totalBudgetSeconds) * 100

		slo.ErrorBudget.ConsumedSeconds = consumedSeconds
		slo.ErrorBudget.ConsumedPercentage = consumedPercent
		slo.ErrorBudget.RemainingSeconds = totalBudgetSeconds - consumedSeconds
		slo.ErrorBudget.RemainingPercentage = 100 - consumedPercent

		if slo.ErrorBudget.RemainingPercentage <= 0 {
			slo.ErrorBudget.Status = models.ErrorBudgetStatusExhausted
		} else if slo.ErrorBudget.RemainingPercentage <= 20 {
			slo.ErrorBudget.Status = models.ErrorBudgetStatusWarning
		} else {
			slo.ErrorBudget.Status = models.ErrorBudgetStatusHealthy
		}

		slo.ErrorBudget.BurnRate = m.calculateBurnRate(slo)
	}

	if slo.ErrorBudget.BurnRate > 0 && slo.ErrorBudget.RemainingSeconds > 0 {
		timeToExhaustion := time.Duration(slo.ErrorBudget.RemainingSeconds/slo.ErrorBudget.BurnRate) * time.Second
		exhaustionTime := time.Now().Add(timeToExhaustion)
		slo.ErrorBudget.ProjectedExhaustion = &exhaustionTime
	} else {
		slo.ErrorBudget.ProjectedExhaustion = nil
	}
}

func (m *SLOMonitor) calculateBurnRate(slo *models.SLO) float64 {
	windowHours := slo.Window.Hours()
	if windowHours == 0 {
		return 0
	}

	return slo.ErrorBudget.ConsumedPercentage / windowHours
}

func (m *SLOMonitor) evaluateBurnRate(slo *models.SLO) {
	policy, exists := m.alertPolicies[slo.ID]
	if !exists || !policy.Enabled {
		return
	}

	if m.alertCallback == nil {
		return
	}

	if slo.ErrorBudget.BurnRate >= policy.FastBurnRate.BurnRateThreshold {
		m.alertCallback(slo, "fast_burn")
	}

	if slo.ErrorBudget.BurnRate >= policy.SlowBurnRate.BurnRateThreshold {
		m.alertCallback(slo, "slow_burn")
	}

	if slo.ErrorBudget.ConsumedPercentage >= policy.BurnoutThreshold {
		m.alertCallback(slo, "burnout")
	}
}

func (m *SLOMonitor) GetRecords(sloID string, limit int) []models.SLORecord {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]models.SLORecord, 0, limit)
	count := 0

	for i := len(m.records) - 1; i >= 0 && count < limit; i-- {
		if m.records[i].SLOID == sloID {
			result = append(result, m.records[i])
			count++
		}
	}

	return result
}

func (m *SLOMonitor) GetSLOStatus(sloID string) (map[string]interface{}, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	slo, exists := m.sloDefinitions[sloID]
	if !exists {
		return nil, &SLOError{Message: "SLO not found: " + sloID}
	}

	return map[string]interface{}{
		"slo_name":              slo.Name,
		"service_name":          slo.ServiceName,
		"sli_current":           slo.SLI.CurrentValue,
		"sli_target":            slo.Target,
		"error_budget_total":    slo.ErrorBudget.TotalBudgetSeconds,
		"error_budget_consumed": slo.ErrorBudget.ConsumedPercentage,
		"error_budget_remaining": slo.ErrorBudget.RemainingPercentage,
		"burn_rate":             slo.ErrorBudget.BurnRate,
		"status":                slo.ErrorBudget.Status,
		"projected_exhaustion":  slo.ErrorBudget.ProjectedExhaustion,
	}, nil
}

func (m *SLOMonitor) ForceEvaluation() {
	m.evaluateAllSLOs()
}

type SLOError struct {
	Message string
}

func (e *SLOError) Error() string {
	return e.Message
}

func NewAvailabilitySLO(name, serviceName string, target float64, window time.Duration) *models.SLO {
	return &models.SLO{
		ID:          uuid.New().String(),
		Name:        name,
		ServiceName: serviceName,
		SLI: models.SLI{
			Name:        "availability",
			Type:        "availability",
			TargetValue: target,
			Labels:      map[string]string{"service": serviceName},
		},
		Target:  target,
		Window:  window,
		Labels:  map[string]string{"service": serviceName},
	}
}

func NewLatencySLO(name, serviceName string, target float64, window time.Duration, thresholdMs float64) *models.SLO {
	return &models.SLO{
		ID:          uuid.New().String(),
		Name:        name,
		ServiceName: serviceName,
		SLI: models.SLI{
			Name:        "latency",
			Type:        "latency",
			TargetValue: target,
			Labels:      map[string]string{"service": serviceName},
			Config:      map[string]interface{}{"threshold_ms": thresholdMs},
		},
		Target:  target,
		Window:  window,
		Labels:  map[string]string{"service": serviceName},
	}
}

func NewDefaultAlertPolicy(sloID string) *models.SLOAlertPolicy {
	return &models.SLOAlertPolicy{
		ID:    uuid.New().String(),
		SLOID: sloID,
		Name:  "Default Alert Policy",
		FastBurnRate: models.BurnRateAlert{
			WindowSize:        time.Hour,
			BurnRateThreshold: 14.4,
			Notify:            true,
			Page:              true,
		},
		SlowBurnRate: models.BurnRateAlert{
			WindowSize:        time.Hour * 6,
			BurnRateThreshold: 1.0,
			Notify:            true,
			Page:              false,
		},
		BurnoutThreshold: 100.0,
		Enabled:          true,
	}
}
