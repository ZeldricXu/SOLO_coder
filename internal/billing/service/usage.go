package service

import (
	"time"

	"session187/internal/billing"
	"session187/internal/billing/repository"
)

type UsageService interface {
	RecordUsage(tenantID, resourceType string, quantity float64, unit string, attributes map[string]interface{}) (*billing.UsageRecord, error)
	BatchRecordUsage(records []*billing.UsageRecord) error
	GetUsage(tenantID string, start, end time.Time) ([]billing.UsageRecord, error)
	GetUsageSummary(tenantID string, start, end time.Time) (map[string]float64, error)
}

type usageServiceImpl struct {
	usageRepo repository.UsageRepository
}

func NewUsageService(usageRepo repository.UsageRepository) UsageService {
	return &usageServiceImpl{usageRepo: usageRepo}
}

func (s *usageServiceImpl) RecordUsage(tenantID, resourceType string, quantity float64, unit string, attributes map[string]interface{}) (*billing.UsageRecord, error) {
	record := &billing.UsageRecord{
		TenantID:     tenantID,
		ResourceType: resourceType,
		Quantity:     quantity,
		Unit:         unit,
		Attributes:   attributes,
	}
	return s.usageRepo.Create(record)
}

func (s *usageServiceImpl) BatchRecordUsage(records []*billing.UsageRecord) error {
	return s.usageRepo.BatchCreate(records)
}

func (s *usageServiceImpl) GetUsage(tenantID string, start, end time.Time) ([]billing.UsageRecord, error) {
	return s.usageRepo.Query(tenantID, start, end)
}

func (s *usageServiceImpl) GetUsageSummary(tenantID string, start, end time.Time) (map[string]float64, error) {
	return s.usageRepo.GetSummary(tenantID, start, end)
}
