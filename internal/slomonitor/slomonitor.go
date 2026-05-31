package slomonitor

import (
	"context"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"math"
	"sync"
	"taskmanager/internal/logger"
	"taskmanager/pkg/models"
	"time"
)

type SLOMonitor struct {
	db           *gorm.DB
	metricsChan  chan MetricEvent
	alerter      Alerter
	stopped      chan struct{}
	wg           sync.WaitGroup
}

type MetricEvent struct {
	ServiceName string
	SLI         string
	Success     bool
	Timestamp   time.Time
}

type Alerter interface {
	FireAlert(ctx context.Context, ruleName string, labels map[string]string, value float64) error
}

func NewSLOMonitor(db *gorm.DB, alerter Alerter) *SLOMonitor {
	return &SLOMonitor{
		db:          db,
		metricsChan: make(chan MetricEvent, 10000),
		alerter:     alerter,
		stopped:     make(chan struct{}),
	}
}

func (m *SLOMonitor) Start() {
	m.wg.Add(1)
	go m.processMetrics()
	logger.Info("slo monitor started")
}

func (m *SLOMonitor) Stop() {
	close(m.stopped)
	m.wg.Wait()
	close(m.metricsChan)
	logger.Info("slo monitor stopped")
}

func (m *SLOMonitor) RecordMetric(event MetricEvent) {
	select {
	case m.metricsChan <- event:
	default:
		logger.Warn("metrics channel full, dropping event", zap.String("service", event.ServiceName))
	}
}

func (m *SLOMonitor) processMetrics() {
	defer m.wg.Done()
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case event := <-m.metricsChan:
			m.handleMetricEvent(event)
		case <-ticker.C:
			m.checkBurnRates()
		case <-m.stopped:
			return
		}
	}
}

func (m *SLOMonitor) handleMetricEvent(event MetricEvent) {
	var slos []models.SLO
	if err := m.db.Where("service_name = ? AND sli = ?", event.ServiceName, event.SLI).Find(&slos).Error; err != nil {
		logger.Error("find slos failed", zap.Error(err))
		return
	}
	for i := range slos {
		slo := &slos[i]
		slo.TotalRequests++
		if !event.Success {
			slo.FailedRequests++
		}
		if slo.TotalRequests > 0 {
			errorRate := float64(slo.FailedRequests) / float64(slo.TotalRequests)
			slo.RemainingBudget = math.Max(0, slo.ErrorBudget - errorRate)
			expectedBudget := slo.ErrorBudget * float64(slo.WindowDays)
			if expectedBudget > 0 {
				slo.BurnRate = (slo.ErrorBudget - slo.RemainingBudget) / expectedBudget
			}
		}
		slo.UpdatedAt = time.Now()
		if err := m.db.Save(slo).Error; err != nil {
			logger.Error("save slo failed", zap.Error(err))
		}
		if slo.RemainingBudget <= 0 {
			m.triggerBurnAlert(slo)
		}
	}
}

func (m *SLOMonitor) checkBurnRates() {
	var slos []models.SLO
	if err := m.db.Where("remaining_budget < ? AND burn_rate > ?", 0.2, 1.0).Find(&slos).Error; err != nil {
		logger.Error("check burn rates failed", zap.Error(err))
		return
	}
	for i := range slos {
		slo := &slos[i]
		m.triggerBurnAlert(slo)
	}
}

func (m *SLOMonitor) triggerBurnAlert(slo *models.SLO) {
	labels := map[string]string{
		"service":     slo.ServiceName,
		"slo":         slo.Name,
		"sli":         slo.SLI,
		"severity":    "critical",
	}
	if m.alerter != nil {
		if err := m.alerter.FireAlert(context.Background(), "slo_burn_rate_exceeded", labels, slo.BurnRate); err != nil {
			logger.Error("fire slo alert failed", zap.Error(err))
		}
	}
	logger.Warn("slo budget burning",
		zap.String("slo", slo.Name),
		zap.String("service", slo.ServiceName),
		zap.Float64("burn_rate", slo.BurnRate),
		zap.Float64("remaining_budget", slo.RemainingBudget),
	)
}

func (m *SLOMonitor) CreateSLO(ctx context.Context, slo *models.SLO) error {
	if slo.ID == "" {
		slo.ID = uuid.New().String()
	}
	slo.CreatedAt = time.Now()
	slo.UpdatedAt = time.Now()
	slo.RemainingBudget = slo.ErrorBudget
	return m.db.Create(slo).Error
}

func (m *SLOMonitor) GetSLO(ctx context.Context, id string) (*models.SLO, error) {
	var slo models.SLO
	if err := m.db.First(&slo, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &slo, nil
}

func (m *SLOMonitor) ListSLOs(ctx context.Context) ([]models.SLO, error) {
	var slos []models.SLO
	if err := m.db.Find(&slos).Error; err != nil {
		return nil, err
	}
	return slos, nil
}

func (m *SLOMonitor) UpdateSLO(ctx context.Context, slo *models.SLO) error {
	slo.UpdatedAt = time.Now()
	return m.db.Save(slo).Error
}

func (m *SLOMonitor) DeleteSLO(ctx context.Context, id string) error {
	return m.db.Delete(&models.SLO{}, "id = ?", id).Error
}

func (m *SLOMonitor) GetSLOStatus(ctx context.Context, id string) (map[string]interface{}, error) {
	slo, err := m.GetSLO(ctx, id)
	if err != nil {
		return nil, err
	}
	var sliValue float64
	if slo.TotalRequests > 0 {
		sliValue = 100.0 * (1.0 - float64(slo.FailedRequests)/float64(slo.TotalRequests))
	}
	return map[string]interface{}{
		"slo_id":            slo.ID,
		"name":              slo.Name,
		"service":           slo.ServiceName,
		"sli":               slo.SLI,
		"sli_value":         sliValue,
		"target_percent":    slo.TargetPercent,
		"error_budget":      slo.ErrorBudget,
		"remaining_budget":  slo.RemainingBudget,
		"burn_rate":         slo.BurnRate,
		"total_requests":    slo.TotalRequests,
		"failed_requests":   slo.FailedRequests,
		"budget_exhausted":  slo.RemainingBudget <= 0,
	}, nil
}

func (m *SLOMonitor) ResetBudget(ctx context.Context, id string) error {
	slo, err := m.GetSLO(ctx, id)
	if err != nil {
		return err
	}
	slo.RemainingBudget = slo.ErrorBudget
	slo.TotalRequests = 0
	slo.FailedRequests = 0
	slo.BurnRate = 0
	slo.UpdatedAt = time.Now()
	return m.db.Save(slo).Error
}
