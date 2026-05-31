package offline

import (
	"context"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
	"gorm.io/gorm"
)

type dataRecordRepository struct {
	db *gorm.DB
}

func NewDataRecordRepository(db *gorm.DB) *dataRecordRepository {
	return &dataRecordRepository{db: db}
}

func (r *dataRecordRepository) Create(ctx context.Context, record *model.OfflineDataRecord) error {
	return r.db.WithContext(ctx).Create(record).Error
}

func (r *dataRecordRepository) GetByID(ctx context.Context, id string) (*model.OfflineDataRecord, error) {
	var record model.OfflineDataRecord
	if err := r.db.WithContext(ctx).First(&record, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &record, nil
}

func (r *dataRecordRepository) GetPending(ctx context.Context, deviceID string, limit int) ([]model.OfflineDataRecord, error) {
	var records []model.OfflineDataRecord
	query := r.db.WithContext(ctx).Where("status = ?", model.OfflineStatusPending)

	if deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}

	err := query.Order("priority DESC, timestamp ASC").Limit(limit).Find(&records).Error
	return records, err
}

func (r *dataRecordRepository) List(ctx context.Context, deviceID, status string, page, pageSize int) ([]model.OfflineDataRecord, int64, error) {
	var records []model.OfflineDataRecord
	var total int64

	query := r.db.WithContext(ctx).Model(&model.OfflineDataRecord{})
	if deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&records).Error; err != nil {
		return nil, 0, err
	}

	return records, total, nil
}

func (r *dataRecordRepository) UpdateStatus(ctx context.Context, id string, status string, errorMsg *string) error {
	updates := map[string]interface{}{
		"status":     status,
		"updated_at": time.Now(),
	}
	if errorMsg != nil {
		updates["error_msg"] = *errorMsg
	}
	return r.db.WithContext(ctx).Model(&model.OfflineDataRecord{}).Where("id = ?", id).Updates(updates).Error
}

func (r *dataRecordRepository) MarkAsSynced(ctx context.Context, id string) error {
	now := time.Now()
	return r.db.WithContext(ctx).Model(&model.OfflineDataRecord{}).Where("id = ?", id).Updates(map[string]interface{}{
		"status":     model.OfflineStatusSynced,
		"synced_at":  now,
		"updated_at": now,
	}).Error
}

func (r *dataRecordRepository) IncrementRetry(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Model(&model.OfflineDataRecord{}).Where("id = ?", id).
		Updates(map[string]interface{}{
			"retry_count": gorm.Expr("retry_count + 1"),
			"updated_at":  time.Now(),
		}).Error
}

type syncSessionRepository struct {
	db *gorm.DB
}

func NewSyncSessionRepository(db *gorm.DB) *syncSessionRepository {
	return &syncSessionRepository{db: db}
}

func (r *syncSessionRepository) Create(ctx context.Context, session *model.SyncSession) error {
	return r.db.WithContext(ctx).Create(session).Error
}

func (r *syncSessionRepository) GetByID(ctx context.Context, id string) (*model.SyncSession, error) {
	var session model.SyncSession
	if err := r.db.WithContext(ctx).First(&session, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &session, nil
}

func (r *syncSessionRepository) ListByDeviceID(ctx context.Context, deviceID string, page, pageSize int) ([]model.SyncSession, int64, error) {
	var sessions []model.SyncSession
	var total int64

	query := r.db.WithContext(ctx).Model(&model.SyncSession{})
	if deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&sessions).Error; err != nil {
		return nil, 0, err
	}

	return sessions, total, nil
}

func (r *syncSessionRepository) UpdateStatus(ctx context.Context, id string, status string, syncedCount, failedCount int) error {
	return r.db.WithContext(ctx).Model(&model.SyncSession{}).Where("id = ?", id).Updates(map[string]interface{}{
		"status":        status,
		"synced_count":  syncedCount,
		"failed_count":  failedCount,
		"updated_at":    time.Now(),
	}).Error
}

func (r *syncSessionRepository) Complete(ctx context.Context, id string, syncedCount, failedCount int) error {
	now := time.Now()
	return r.db.WithContext(ctx).Model(&model.SyncSession{}).Where("id = ?", id).Updates(map[string]interface{}{
		"status":        model.OfflineStatusSynced,
		"synced_count":  syncedCount,
		"failed_count":  failedCount,
		"end_time":      now,
		"updated_at":    now,
	}).Error
}
