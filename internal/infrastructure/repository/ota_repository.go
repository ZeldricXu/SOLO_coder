package ota

import (
	"context"

	"github.com/edgevision/edgevision/internal/domain/model"
	"gorm.io/gorm"
)

type firmwareRepository struct {
	db *gorm.DB
}

func NewFirmwareRepository(db *gorm.DB) *firmwareRepository {
	return &firmwareRepository{db: db}
}

func (r *firmwareRepository) Create(ctx context.Context, firmware *model.Firmware) error {
	return r.db.WithContext(ctx).Create(firmware).Error
}

func (r *firmwareRepository) GetByID(ctx context.Context, id string) (*model.Firmware, error) {
	var firmware model.Firmware
	if err := r.db.WithContext(ctx).First(&firmware, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &firmware, nil
}

func (r *firmwareRepository) GetByVersionAndDeviceType(ctx context.Context, version, deviceType string) (*model.Firmware, error) {
	var firmware model.Firmware
	if err := r.db.WithContext(ctx).Where("version = ? AND device_type = ?", version, deviceType).First(&firmware).Error; err != nil {
		return nil, err
	}
	return &firmware, nil
}

func (r *firmwareRepository) List(ctx context.Context, deviceType string, page, pageSize int) ([]model.Firmware, int64, error) {
	var firmwares []model.Firmware
	var total int64

	query := r.db.WithContext(ctx).Model(&model.Firmware{})
	if deviceType != "" {
		query = query.Where("device_type = ?", deviceType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&firmwares).Error; err != nil {
		return nil, 0, err
	}

	return firmwares, total, nil
}

func (r *firmwareRepository) Update(ctx context.Context, firmware *model.Firmware) error {
	return r.db.WithContext(ctx).Save(firmware).Error
}

func (r *firmwareRepository) Delete(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Delete(&model.Firmware{}, "id = ?", id).Error
}

type deltaPackageRepository struct {
	db *gorm.DB
}

func NewDeltaPackageRepository(db *gorm.DB) *deltaPackageRepository {
	return &deltaPackageRepository{db: db}
}

func (r *deltaPackageRepository) Create(ctx context.Context, delta *model.DeltaPackage) error {
	return r.db.WithContext(ctx).Create(delta).Error
}

func (r *deltaPackageRepository) GetByID(ctx context.Context, id string) (*model.DeltaPackage, error) {
	var delta model.DeltaPackage
	if err := r.db.WithContext(ctx).First(&delta, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &delta, nil
}

func (r *deltaPackageRepository) ListByDeviceType(ctx context.Context, deviceType string) ([]model.DeltaPackage, error) {
	var deltas []model.DeltaPackage
	err := r.db.WithContext(ctx).Where("device_type = ?", deviceType).Order("created_at DESC").Find(&deltas).Error
	return deltas, err
}

type upgradeTaskRepository struct {
	db *gorm.DB
}

func NewUpgradeTaskRepository(db *gorm.DB) *upgradeTaskRepository {
	return &upgradeTaskRepository{db: db}
}

func (r *upgradeTaskRepository) Create(ctx context.Context, task *model.OTAUpgradeTask) error {
	return r.db.WithContext(ctx).Create(task).Error
}

func (r *upgradeTaskRepository) GetByID(ctx context.Context, id string) (*model.OTAUpgradeTask, error) {
	var task model.OTAUpgradeTask
	if err := r.db.WithContext(ctx).First(&task, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &task, nil
}

func (r *upgradeTaskRepository) List(ctx context.Context, status string, page, pageSize int) ([]model.OTAUpgradeTask, int64, error) {
	var tasks []model.OTAUpgradeTask
	var total int64

	query := r.db.WithContext(ctx).Model(&model.OTAUpgradeTask{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&tasks).Error; err != nil {
		return nil, 0, err
	}

	return tasks, total, nil
}

func (r *upgradeTaskRepository) Update(ctx context.Context, task *model.OTAUpgradeTask) error {
	return r.db.WithContext(ctx).Save(task).Error
}

func (r *upgradeTaskRepository) UpdateStatus(ctx context.Context, id string, status string) error {
	return r.db.WithContext(ctx).Model(&model.OTAUpgradeTask{}).Where("id = ?", id).Update("status", status).Error
}

func (r *upgradeTaskRepository) IncrementProgress(ctx context.Context, id string, success, failed, rollback int) error {
	return r.db.WithContext(ctx).Model(&model.OTAUpgradeTask{}).Where("id = ?", id).Updates(map[string]interface{}{
		"success_count":  gorm.Expr("success_count + ?", success),
		"failed_count":   gorm.Expr("failed_count + ?", failed),
		"rollback_count": gorm.Expr("rollback_count + ?", rollback),
	}).Error
}

type deviceUpgradeRepository struct {
	db *gorm.DB
}

func NewDeviceUpgradeRepository(db *gorm.DB) *deviceUpgradeRepository {
	return &deviceUpgradeRepository{db: db}
}

func (r *deviceUpgradeRepository) Create(ctx context.Context, upgrade *model.OTADeviceUpgrade) error {
	return r.db.WithContext(ctx).Create(upgrade).Error
}

func (r *deviceUpgradeRepository) GetByID(ctx context.Context, id string) (*model.OTADeviceUpgrade, error) {
	var upgrade model.OTADeviceUpgrade
	if err := r.db.WithContext(ctx).First(&upgrade, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &upgrade, nil
}

func (r *deviceUpgradeRepository) ListByTaskID(ctx context.Context, taskID string, page, pageSize int) ([]model.OTADeviceUpgrade, int64, error) {
	var upgrades []model.OTADeviceUpgrade
	var total int64

	query := r.db.WithContext(ctx).Model(&model.OTADeviceUpgrade{}).Where("task_id = ?", taskID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&upgrades).Error; err != nil {
		return nil, 0, err
	}

	return upgrades, total, nil
}

func (r *deviceUpgradeRepository) UpdateStatus(ctx context.Context, id string, status, phase string, progress float64, errorMsg *string) error {
	updates := map[string]interface{}{
		"status":   status,
		"phase":    phase,
		"progress": progress,
	}
	if errorMsg != nil {
		updates["error_detail"] = *errorMsg
	}
	return r.db.WithContext(ctx).Model(&model.OTADeviceUpgrade{}).Where("id = ?", id).Updates(updates).Error
}

func (r *deviceUpgradeRepository) GetPendingByBatch(ctx context.Context, taskID string, batchNum int) ([]model.OTADeviceUpgrade, error) {
	var upgrades []model.OTADeviceUpgrade
	err := r.db.WithContext(ctx).Where("task_id = ? AND batch_number = ? AND status = ?", taskID, batchNum, model.OTAStatusPending).
		Order("priority DESC").Find(&upgrades).Error
	return upgrades, err
}
