package slo

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
)

type ErrorBudgetManager struct{}

func NewErrorBudgetManager() *ErrorBudgetManager {
	return &ErrorBudgetManager{}
}

func (m *ErrorBudgetManager) GetBudget(ctx context.Context, sloID string) (*domain.ErrorBudget, error) {
	var budget domain.ErrorBudget
	if err := database.DB.WithContext(ctx).
		Where("slo_id = ?", sloID).
		Order("calculated_at DESC").
		First(&budget).Error; err != nil {
		return nil, fmt.Errorf("get error budget failed: %w", err)
	}
	return &budget, nil
}

func (m *ErrorBudgetManager) GetBudgetHistory(ctx context.Context, sloID string, start, end time.Time, limit int) ([]domain.ErrorBudget, int64, error) {
	var budgets []domain.ErrorBudget
	var total int64

	query := database.DB.WithContext(ctx).Model(&domain.ErrorBudget{}).
		Where("slo_id = ? AND calculated_at >= ? AND calculated_at <= ?", sloID, start, end)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count budget history failed: %w", err)
	}

	if err := query.Order("calculated_at DESC").Limit(limit).Find(&budgets).Error; err != nil {
		return nil, 0, fmt.Errorf("list budget history failed: %w", err)
	}

	return budgets, total, nil
}

func (m *ErrorBudgetManager) GetSLIMeasurements(ctx context.Context, sliID string, start, end time.Time, limit int) ([]domain.SLIMeasurement, int64, error) {
	var measurements []domain.SLIMeasurement
	var total int64

	query := database.DB.WithContext(ctx).Model(&domain.SLIMeasurement{}).
		Where("sli_id = ? AND timestamp >= ? AND timestamp <= ?", sliID, start, end)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count sli measurements failed: %w", err)
	}

	if err := query.Order("timestamp DESC").Limit(limit).Find(&measurements).Error; err != nil {
		return nil, 0, fmt.Errorf("list sli measurements failed: %w", err)
	}

	return measurements, total, nil
}

func (m *ErrorBudgetManager) GetSLOCompliance(ctx context.Context, sloID string, period time.Duration) (float64, error) {
	var slo domain.SLO
	if err := database.DB.WithContext(ctx).Where("slo_id = ?", sloID).First(&slo).Error; err != nil {
		return 0, fmt.Errorf("get slo failed: %w", err)
	}

	startTime := time.Now().Add(-period)
	var measurements []domain.SLIMeasurement

	if err := database.DB.WithContext(ctx).
		Where("sli_id = ? AND timestamp >= ?", slo.SLIID, startTime).
		Find(&measurements).Error; err != nil {
		return 0, fmt.Errorf("get measurements failed: %w", err)
	}

	if len(measurements) == 0 {
		return 100.0, nil
	}

	validCount := 0
	for _, m := range measurements {
		if m.IsValid {
			validCount++
		}
	}

	compliance := float64(validCount) / float64(len(measurements)) * 100
	return compliance, nil
}

func (m *ErrorBudgetManager) GetBurnRateAlert(ctx context.Context, sloID string) (bool, float64, string) {
	budget, err := m.GetBudget(ctx, sloID)
	if err != nil {
		return false, 0, "no budget data"
	}

	var slo domain.SLO
	if err := database.DB.WithContext(ctx).Where("slo_id = ?", sloID).First(&slo).Error; err != nil {
		return false, budget.BurnRate, "slo not found"
	}

	switch {
	case budget.BurnRate > 2.0:
		return true, budget.BurnRate, "critical: burn rate > 2x"
	case budget.BurnRate > 1.5:
		return true, budget.BurnRate, "warning: burn rate > 1.5x"
	case budget.BurnRate > 1.0:
		return true, budget.BurnRate, "info: burn rate > 1x"
	default:
		return false, budget.BurnRate, "normal"
	}
}

func (m *ErrorBudgetManager) GenerateBudgetReport(ctx context.Context, sloID string) (map[string]interface{}, error) {
	slo, err := (&SLOMonitor{}).GetSLO(ctx, sloID)
	if err != nil {
		return nil, fmt.Errorf("get slo failed: %w", err)
	}

	budget, err := m.GetBudget(ctx, sloID)
	if err != nil {
		return nil, fmt.Errorf("get budget failed: %w", err)
	}

	compliance7d, _ := m.GetSLOCompliance(ctx, sloID, 7*24*time.Hour)
	compliance30d, _ := m.GetSLOCompliance(ctx, sloID, 30*24*time.Hour)

	alert, burnRate, alertMsg := m.GetBurnRateAlert(ctx, sloID)

	report := map[string]interface{}{
		"slo_id":              slo.SLOID,
		"slo_name":            slo.Name,
		"slo_target":          slo.Target,
		"current_status":      slo.Status,
		"budget_total":        budget.TotalSeconds,
		"budget_used":         budget.UsedSeconds,
		"budget_remaining":    budget.RemainingPercentage,
		"burn_rate":           burnRate,
		"compliance_7d":       compliance7d,
		"compliance_30d":      compliance30d,
		"has_alert":           alert,
		"alert_message":       alertMsg,
		"report_generated_at": time.Now(),
	}

	logger.Info("Budget report generated",
		zap.String("slo_id", sloID),
		zap.Float64("burn_rate", burnRate),
		zap.Float64("compliance_7d", compliance7d))

	return report, nil
}

func (m *ErrorBudgetManager) ResetBudget(ctx context.Context, sloID string) error {
	var slo domain.SLO
	if err := database.DB.WithContext(ctx).Where("slo_id = ?", sloID).First(&slo).Error; err != nil {
		return fmt.Errorf("slo not found: %w", err)
	}

	if err := database.DB.WithContext(ctx).
		Where("slo_id = ?", sloID).
		Delete(&domain.ErrorBudget{}).Error; err != nil {
		return fmt.Errorf("delete existing budgets failed: %w", err)
	}

	now := time.Now()
	slo.StartTime = now
	slo.EndTime = now.AddDate(0, 1, 0)
	slo.Status = domain.SLOStatusOK
	slo.BudgetUsed = 0
	slo.BudgetRemaining = slo.BudgetTotal
	slo.UpdatedAt = now

	if err := database.DB.WithContext(ctx).Save(&slo).Error; err != nil {
		return fmt.Errorf("reset slo failed: %w", err)
	}

	logger.Info("Error budget reset", zap.String("slo_id", sloID))
	return nil
}

func (m *ErrorBudgetManager) ListBudgets(ctx context.Context, offset, limit int) ([]domain.ErrorBudget, int64, error) {
	var budgets []domain.ErrorBudget
	var total int64

	if err := database.DB.WithContext(ctx).Model(&domain.ErrorBudget{}).Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count budgets failed: %w", err)
	}

	if err := database.DB.WithContext(ctx).Order("calculated_at DESC").Offset(offset).Limit(limit).Find(&budgets).Error; err != nil {
		return nil, 0, fmt.Errorf("list budgets failed: %w", err)
	}

	return budgets, total, nil
}
