package repository

import (
	"time"

	"gorm.io/gorm"
	"session187/internal/billing"
	"session187/internal/common"
	"session187/pkg/errors"
)

type UsageRepository interface {
	Create(record *billing.UsageRecord) (*billing.UsageRecord, error)
	BatchCreate(records []*billing.UsageRecord) error
	Query(tenantID string, start, end time.Time) ([]billing.UsageRecord, error)
	GetSummary(tenantID string, start, end time.Time) (map[string]float64, error)
}

type GormUsageRepository struct {
	db *gorm.DB
}

func NewUsageRepository(db *gorm.DB) UsageRepository {
	return &GormUsageRepository{db: db}
}

func (r *GormUsageRepository) Create(record *billing.UsageRecord) (*billing.UsageRecord, error) {
	if record.ID == "" {
		record.ID = common.GenerateID("usr")
	}
	if record.Attributes == nil {
		record.Attributes = make(map[string]interface{})
	}
	now := common.TimeNowUTC()
	record.Timestamp = now
	record.CreatedAt = now
	if err := r.db.Create(record).Error; err != nil {
		return nil, errors.NewWithDetail(500, "记录用量失败", err.Error())
	}
	return record, nil
}

func (r *GormUsageRepository) BatchCreate(records []*billing.UsageRecord) error {
	if len(records) == 0 {
		return nil
	}
	now := common.TimeNowUTC()
	for _, record := range records {
		if record.ID == "" {
			record.ID = common.GenerateID("usr")
		}
		if record.Timestamp.IsZero() {
			record.Timestamp = now
		}
		if record.CreatedAt.IsZero() {
			record.CreatedAt = now
		}
	}
	if err := r.db.Create(records).Error; err != nil {
		return errors.NewWithDetail(500, "批量记录用量失败", err.Error())
	}
	return nil
}

func (r *GormUsageRepository) Query(tenantID string, start, end time.Time) ([]billing.UsageRecord, error) {
	var records []billing.UsageRecord
	err := r.db.Where("tenant_id = ? AND timestamp >= ? AND timestamp < ?",
		tenantID, start, end).Order("timestamp desc").Find(&records).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "查询用量失败", err.Error())
	}
	return records, nil
}

func (r *GormUsageRepository) GetSummary(tenantID string, start, end time.Time) (map[string]float64, error) {
	records, err := r.Query(tenantID, start, end)
	if err != nil {
		return nil, err
	}
	summary := make(map[string]float64)
	for _, record := range records {
		summary[record.ResourceType] += record.Quantity
	}
	return summary, nil
}
