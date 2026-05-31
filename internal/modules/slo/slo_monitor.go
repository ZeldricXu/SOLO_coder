package slo

import (
	"context"
	"fmt"
	"math"
	"time"

	"github.com/google/uuid"
	"github.com/robfig/cron/v3"
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
)

type SLOMonitor struct {
	cron        *cron.Cron
	sloEntries  map[string]cron.EntryID
	metricsStore map[string][]float64
}

func NewSLOMonitor() *SLOMonitor {
	return &SLOMonitor{
		cron:        cron.New(cron.WithSeconds()),
		sloEntries:  make(map[string]cron.EntryID),
		metricsStore: make(map[string][]float64),
	}
}

func (m *SLOMonitor) Start() error {
	slos, err := m.listEnabledSLOs()
	if err != nil {
		return fmt.Errorf("list enabled slos failed: %w", err)
	}

	for _, slo := range slos {
		if err := m.scheduleSLO(&slo); err != nil {
			logger.Error("Failed to schedule SLO",
				zap.String("slo_id", slo.SLOID),
				zap.Error(err))
			continue
		}
	}

	m.cron.Start()
	logger.Info("SLO monitor started", zap.Int("slo_count", len(slos)))
	return nil
}

func (m *SLOMonitor) Stop() {
	m.cron.Stop()
	logger.Info("SLO monitor stopped")
}

func (m *SLOMonitor) scheduleSLO(slo *domain.SLO) error {
	if entryID, exists := m.sloEntries[slo.SLOID]; exists {
		m.cron.Remove(entryID)
		delete(m.sloEntries, slo.SLOID)
	}

	entryID, err := m.cron.AddFunc("@every 60s", func() {
		m.evaluateSLO(slo)
	})
	if err != nil {
		return fmt.Errorf("add slo cron failed: %w", err)
	}

	m.sloEntries[slo.SLOID] = entryID

	logger.Info("SLO scheduled",
		zap.String("slo_id", slo.SLOID),
		zap.String("name", slo.Name))

	return nil
}

func (m *SLOMonitor) evaluateSLO(slo *domain.SLO) {
	ctx := context.Background()

	measurement, err := m.collectSLIMeasurement(ctx, slo)
	if err != nil {
		logger.Error("Failed to collect SLI measurement",
			zap.String("slo_id", slo.SLOID),
			zap.Error(err))
		return
	}

	if err := database.DB.WithContext(ctx).Create(measurement).Error; err != nil {
		logger.Error("Failed to save SLI measurement", zap.Error(err))
	}

	m.calculateErrorBudget(ctx, slo, measurement)
	m.updateSLOStatus(ctx, slo)

	logger.Debug("SLO evaluated",
		zap.String("slo_id", slo.SLOID),
		zap.Float64("sli_value", measurement.Value))
}

func (m *SLOMonitor) collectSLIMeasurement(ctx context.Context, slo *domain.SLO) (*domain.SLIMeasurement, error) {
	var sli domain.SLI
	if err := database.DB.WithContext(ctx).Where("sli_id = ?", slo.SLIID).First(&sli).Error; err != nil {
		return nil, fmt.Errorf("get sli failed: %w", err)
	}

	value, err := m.getMetricValue(sli.MetricName)
	if err != nil {
		return nil, fmt.Errorf("get metric value failed: %w", err)
	}

	isValid := value >= sli.TargetValue

	measurement := &domain.SLIMeasurement{
		MeasurementID: uuid.New().String(),
		SLIID:         sli.SLIID,
		Value:         value,
		IsValid:       isValid,
		Timestamp:     time.Now(),
	}

	return measurement, nil
}

func (m *SLOMonitor) getMetricValue(metricName string) (float64, error) {
	values, exists := m.metricsStore[metricName]
	if !exists || len(values) == 0 {
		return 0, fmt.Errorf("no data for metric %s", metricName)
	}

	avg := 0.0
	for _, v := range values {
		avg += v
	}
	avg /= float64(len(values))

	return avg, nil
}

func (m *SLOMonitor) calculateErrorBudget(ctx context.Context, slo *domain.SLO, measurement *domain.SLIMeasurement) {
	periodStart := slo.StartTime
	periodEnd := slo.EndTime

	totalSeconds := periodEnd.Sub(periodStart).Seconds()

	usedSeconds := 0.0
	if !measurement.IsValid {
		usedSeconds = 60.0
	}

	existingBudget := &domain.ErrorBudget{}
	err := database.DB.WithContext(ctx).
		Where("slo_id = ? AND period_start = ? AND period_end = ?", slo.SLOID, periodStart, periodEnd).
		First(existingBudget).Error

	if err == nil {
		usedSeconds = existingBudget.UsedSeconds + usedSeconds
		existingBudget.UsedSeconds = usedSeconds
		existingBudget.BurnRate = m.calculateBurnRate(slo, usedSeconds, totalSeconds)
		existingBudget.RemainingPercentage = 100 - (usedSeconds / totalSeconds * 100)
		existingBudget.CalculatedAt = time.Now()
		_ = database.DB.WithContext(ctx).Save(existingBudget).Error
	} else {
		budget := &domain.ErrorBudget{
			BudgetID:          uuid.New().String(),
			SLOID:             slo.SLOID,
			PeriodStart:       periodStart,
			PeriodEnd:         periodEnd,
			TotalSeconds:      totalSeconds,
			UsedSeconds:       usedSeconds,
			BurnRate:          m.calculateBurnRate(slo, usedSeconds, totalSeconds),
			RemainingPercentage: 100 - (usedSeconds / totalSeconds * 100),
			CalculatedAt:      time.Now(),
		}
		_ = database.DB.WithContext(ctx).Create(budget).Error
	}

	elapsed := time.Since(periodStart).Seconds()
	expectedUsed := (1 - slo.Target/100) * elapsed

	slo.BudgetUsed = usedSeconds
	slo.BudgetRemaining = math.Max(0, totalSeconds - usedSeconds)
	slo.BudgetTotal = totalSeconds

	if usedSeconds > expectedUsed*2 {
		slo.Status = domain.SLOStatusBurning
	} else if slo.BudgetRemaining <= 0 {
		slo.Status = domain.SLOStatusExhausted
	} else if usedSeconds > expectedUsed {
		slo.Status = domain.SLOStatusWarning
	} else {
		slo.Status = domain.SLOStatusOK
	}
}

func (m *SLOMonitor) calculateBurnRate(slo *domain.SLO, usedSeconds, totalSeconds float64) float64 {
	elapsed := time.Since(slo.StartTime).Seconds()
	if elapsed <= 0 {
		return 0
	}

	budgetErrorRate := usedSeconds / elapsed
	allowedErrorRate := (100 - slo.Target) / 100

	if allowedErrorRate <= 0 {
		return 0
	}

	return budgetErrorRate / allowedErrorRate
}

func (m *SLOMonitor) updateSLOStatus(ctx context.Context, slo *domain.SLO) {
	_ = database.DB.WithContext(ctx).Model(slo).Updates(map[string]interface{}{
		"status":            slo.Status,
		"budget_used":       slo.BudgetUsed,
		"budget_remaining":  slo.BudgetRemaining,
		"updated_at":        time.Now(),
	}).Error
}

func (m *SLOMonitor) ReportMetric(metricName string, value float64) {
	m.metricsStore[metricName] = append(m.metricsStore[metricName], value)
	if len(m.metricsStore[metricName]) > 100 {
		m.metricsStore[metricName] = m.metricsStore[metricName][len(m.metricsStore[metricName])-100:]
	}
}

func (m *SLOMonitor) CreateSLO(ctx context.Context, slo *domain.SLO) (*domain.SLO, error) {
	slo.SLOID = uuid.New().String()
	slo.CreatedAt = time.Now()
	slo.UpdatedAt = time.Now()
	slo.Status = domain.SLOStatusOK

	if err := database.DB.WithContext(ctx).Create(slo).Error; err != nil {
		return nil, fmt.Errorf("create slo failed: %w", err)
	}

	if err := m.scheduleSLO(slo); err != nil {
		return slo, fmt.Errorf("schedule slo failed: %w", err)
	}

	logger.Info("SLO created", zap.String("slo_id", slo.SLOID), zap.String("name", slo.Name))
	return slo, nil
}

func (m *SLOMonitor) UpdateSLO(ctx context.Context, sloID string, updates map[string]interface{}) (*domain.SLO, error) {
	var slo domain.SLO
	if err := database.DB.WithContext(ctx).Where("slo_id = ?", sloID).First(&slo).Error; err != nil {
		return nil, fmt.Errorf("slo not found: %w", err)
	}

	updates["updated_at"] = time.Now()
	if err := database.DB.WithContext(ctx).Model(&slo).Updates(updates).Error; err != nil {
		return nil, fmt.Errorf("update slo failed: %w", err)
	}

	if err := database.DB.WithContext(ctx).Where("slo_id = ?", sloID).First(&slo).Error; err != nil {
		return nil, fmt.Errorf("reload slo failed: %w", err)
	}

	if err := m.scheduleSLO(&slo); err != nil {
		return &slo, err
	}

	return &slo, nil
}

func (m *SLOMonitor) DeleteSLO(ctx context.Context, sloID string) error {
	if entryID, exists := m.sloEntries[sloID]; exists {
		m.cron.Remove(entryID)
		delete(m.sloEntries, sloID)
	}

	if err := database.DB.WithContext(ctx).Where("slo_id = ?", sloID).Delete(&domain.SLO{}).Error; err != nil {
		return fmt.Errorf("delete slo failed: %w", err)
	}

	logger.Info("SLO deleted", zap.String("slo_id", sloID))
	return nil
}

func (m *SLOMonitor) GetSLO(ctx context.Context, sloID string) (*domain.SLO, error) {
	var slo domain.SLO
	if err := database.DB.WithContext(ctx).Where("slo_id = ?", sloID).First(&slo).Error; err != nil {
		return nil, fmt.Errorf("get slo failed: %w", err)
	}
	return &slo, nil
}

func (m *SLOMonitor) ListSLOs(ctx context.Context, status domain.SLOStatus, offset, limit int) ([]domain.SLO, int64, error) {
	var slos []domain.SLO
	var total int64

	query := database.DB.WithContext(ctx).Model(&domain.SLO{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count slos failed: %w", err)
	}

	if err := query.Order("created_at DESC").Offset(offset).Limit(limit).Find(&slos).Error; err != nil {
		return nil, 0, fmt.Errorf("list slos failed: %w", err)
	}

	return slos, total, nil
}

func (m *SLOMonitor) CreateSLI(ctx context.Context, sli *domain.SLI) (*domain.SLI, error) {
	sli.SLIID = uuid.New().String()
	sli.CreatedAt = time.Now()
	sli.UpdatedAt = time.Now()

	if err := database.DB.WithContext(ctx).Create(sli).Error; err != nil {
		return nil, fmt.Errorf("create sli failed: %w", err)
	}

	logger.Info("SLI created", zap.String("sli_id", sli.SLIID), zap.String("name", sli.Name))
	return sli, nil
}

func (m *SLOMonitor) UpdateSLI(ctx context.Context, sliID string, updates map[string]interface{}) (*domain.SLI, error) {
	var sli domain.SLI
	if err := database.DB.WithContext(ctx).Where("sli_id = ?", sliID).First(&sli).Error; err != nil {
		return nil, fmt.Errorf("sli not found: %w", err)
	}

	updates["updated_at"] = time.Now()
	if err := database.DB.WithContext(ctx).Model(&sli).Updates(updates).Error; err != nil {
		return nil, fmt.Errorf("update sli failed: %w", err)
	}

	if err := database.DB.WithContext(ctx).Where("sli_id = ?", sliID).First(&sli).Error; err != nil {
		return nil, fmt.Errorf("reload sli failed: %w", err)
	}

	return &sli, nil
}

func (m *SLOMonitor) DeleteSLI(ctx context.Context, sliID string) error {
	if err := database.DB.WithContext(ctx).Where("sli_id = ?", sliID).Delete(&domain.SLI{}).Error; err != nil {
		return fmt.Errorf("delete sli failed: %w", err)
	}
	logger.Info("SLI deleted", zap.String("sli_id", sliID))
	return nil
}

func (m *SLOMonitor) GetSLI(ctx context.Context, sliID string) (*domain.SLI, error) {
	var sli domain.SLI
	if err := database.DB.WithContext(ctx).Where("sli_id = ?", sliID).First(&sli).Error; err != nil {
		return nil, fmt.Errorf("get sli failed: %w", err)
	}
	return &sli, nil
}

func (m *SLOMonitor) ListSLIs(ctx context.Context, offset, limit int) ([]domain.SLI, int64, error) {
	var slis []domain.SLI
	var total int64

	if err := database.DB.WithContext(ctx).Model(&domain.SLI{}).Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count slis failed: %w", err)
	}

	if err := database.DB.WithContext(ctx).Order("created_at DESC").Offset(offset).Limit(limit).Find(&slis).Error; err != nil {
		return nil, 0, fmt.Errorf("list slis failed: %w", err)
	}

	return slis, total, nil
}

func (m *SLOMonitor) listEnabledSLOs() ([]domain.SLO, error) {
	var slos []domain.SLO
	if err := database.DB.Find(&slos).Error; err != nil {
		return nil, fmt.Errorf("list slos failed: %w", err)
	}
	return slos, nil
}
