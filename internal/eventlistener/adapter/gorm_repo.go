package adapter

import (
	"context"
	"github.com/solocoder/session147/internal/eventlistener/domain"
	"github.com/solocoder/session147/internal/eventlistener/ports"
	"gorm.io/gorm"
)

type gormEventRepo struct {
	db *gorm.DB
}

func NewGormEventRepository(db *gorm.DB) ports.EventRepository {
	return &gormEventRepo{db: db}
}

func (r *gormEventRepo) CreateSubscription(ctx context.Context, sub *domain.EventSubscription) error {
	return r.db.WithContext(ctx).Create(sub).Error
}

func (r *gormEventRepo) GetSubscription(ctx context.Context, id string) (*domain.EventSubscription, error) {
	var sub domain.EventSubscription
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&sub).Error
	if err != nil {
		return nil, err
	}
	return &sub, nil
}

func (r *gormEventRepo) ListSubscriptions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.EventSubscription, int64, error) {
	var subs []domain.EventSubscription
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.EventSubscription{})
	for k, v := range filter {
		query = query.Where(k+" = ?", v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&subs).Error
	return subs, total, err
}

func (r *gormEventRepo) UpdateSubscription(ctx context.Context, sub *domain.EventSubscription) error {
	return r.db.WithContext(ctx).Save(sub).Error
}

func (r *gormEventRepo) DeleteSubscription(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Delete(&domain.EventSubscription{}, "id = ?", id).Error
}

func (r *gormEventRepo) GetActiveSubscriptions(ctx context.Context, chainID int64) ([]domain.EventSubscription, error) {
	var subs []domain.EventSubscription
	err := r.db.WithContext(ctx).Where("chain_id = ? AND status = ?", chainID, domain.SubscriptionStatusActive).
		Find(&subs).Error
	return subs, err
}

func (r *gormEventRepo) StoreEventLog(ctx context.Context, log *domain.EventLogEntry) error {
	return r.db.WithContext(ctx).Create(log).Error
}

func (r *gormEventRepo) GetEventLog(ctx context.Context, id string) (*domain.EventLogEntry, error) {
	var log domain.EventLogEntry
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&log).Error
	if err != nil {
		return nil, err
	}
	return &log, nil
}

func (r *gormEventRepo) ListEventLogs(ctx context.Context, subscriptionID string, page, pageSize int) ([]domain.EventLogEntry, int64, error) {
	var logs []domain.EventLogEntry
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.EventLogEntry{})
	if subscriptionID != "" {
		query = query.Where("subscription_id = ?", subscriptionID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("timestamp DESC").Find(&logs).Error
	return logs, total, err
}

func (r *gormEventRepo) UpdateEventLogCallback(ctx context.Context, id string, status string, err string) error {
	updates := map[string]interface{}{
		"callback_status": status,
	}
	if err != "" {
		updates["callback_error"] = err
	}
	if status == domain.CallbackStatusSuccess {
		updates["callback_time"] = gorm.Expr("NOW()")
	}
	return r.db.WithContext(ctx).Model(&domain.EventLogEntry{}).Where("id = ?", id).Updates(updates).Error
}
