package data

import (
	"context"
	"time"

	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/pkg/errors"
	"github.com/edgeplatform/session306/pkg/utils"
	"gorm.io/gorm"
)

type BaseRepository struct {
	da *DataAccess
}

func NewBaseRepository(da *DataAccess) *BaseRepository {
	return &BaseRepository{da: da}
}

type EntityRepository interface {
	Create(ctx context.Context, entity *model.Entity) error
	GetByID(ctx context.Context, id string) (*model.Entity, error)
	Update(ctx context.Context, entity *model.Entity) error
	Delete(ctx context.Context, id string) error
	List(ctx context.Context, entityType model.EntityType, status model.EntityStatus, offset, limit int) ([]model.Entity, int64, error)
	UpdateStatus(ctx context.Context, id string, status model.EntityStatus) error
}

type entityRepo struct {
	*BaseRepository
}

func NewEntityRepository(da *DataAccess) EntityRepository {
	return &entityRepo{NewBaseRepository(da)}
}

func (r *entityRepo) Create(ctx context.Context, entity *model.Entity) error {
	now := utils.NowUTC()
	entity.ID = utils.GenerateID("ent")
	entity.CreatedAt = now
	entity.UpdatedAt = now
	return r.da.DB().WithContext(ctx).Create(entity).Error
}

func (r *entityRepo) GetByID(ctx context.Context, id string) (*model.Entity, error) {
	var entity model.Entity
	err := r.da.DB().WithContext(ctx).Where("id = ?", id).First(&entity).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("entity not found")
	}
	return &entity, err
}

func (r *entityRepo) Update(ctx context.Context, entity *model.Entity) error {
	entity.UpdatedAt = utils.NowUTC()
	return r.da.DB().WithContext(ctx).Save(entity).Error
}

func (r *entityRepo) Delete(ctx context.Context, id string) error {
	return r.da.DB().WithContext(ctx).Delete(&model.Entity{}, "id = ?", id).Error
}

func (r *entityRepo) List(ctx context.Context, entityType model.EntityType, status model.EntityStatus, offset, limit int) ([]model.Entity, int64, error) {
	var entities []model.Entity
	var total int64

	query := r.da.DB().WithContext(ctx).Model(&model.Entity{})
	if entityType != "" {
		query = query.Where("type = ?", entityType)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("created_at DESC").Find(&entities).Error
	return entities, total, err
}

func (r *entityRepo) UpdateStatus(ctx context.Context, id string, status model.EntityStatus) error {
	return r.da.DB().WithContext(ctx).Model(&model.Entity{}).
		Where("id = ?", id).
		Updates(map[string]interface{}{
			"status":     status,
			"updated_at": utils.NowUTC(),
		}).Error
}

type ConfigRepository interface {
	Create(ctx context.Context, config *model.ConfigDefinition) error
	GetByID(ctx context.Context, configID string) (*model.ConfigDefinition, error)
	GetLatest(ctx context.Context, namespace string) (*model.ConfigDefinition, error)
	List(ctx context.Context, namespace string, offset, limit int) ([]model.ConfigDefinition, int64, error)
	UpdateEnabled(ctx context.Context, configID string, enabled bool) error
	Apply(ctx context.Context, configID string) error
	Update(ctx context.Context, namespace string, parameters map[string]interface{}, enabled bool) (*model.ConfigDefinition, error)
	Delete(ctx context.Context, namespace string) error
}

type configRepo struct {
	*BaseRepository
}

func NewConfigRepository(da *DataAccess) ConfigRepository {
	return &configRepo{NewBaseRepository(da)}
}

func (r *configRepo) Create(ctx context.Context, config *model.ConfigDefinition) error {
	now := utils.NowUTC()
	config.ConfigID = utils.GenerateID("cfg")
	config.CreatedAt = now
	config.UpdatedAt = now

	var maxVersion int64
	r.da.DB().WithContext(ctx).Model(&model.ConfigDefinition{}).
		Where("namespace = ?", config.Namespace).
		Select("COALESCE(MAX(version), 0)").Scan(&maxVersion)
	config.Version = maxVersion + 1

	return r.da.DB().WithContext(ctx).Create(config).Error
}

func (r *configRepo) GetByID(ctx context.Context, configID string) (*model.ConfigDefinition, error) {
	var config model.ConfigDefinition
	err := r.da.DB().WithContext(ctx).Where("config_id = ?", configID).First(&config).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("config not found")
	}
	return &config, err
}

func (r *configRepo) GetLatest(ctx context.Context, namespace string) (*model.ConfigDefinition, error) {
	var config model.ConfigDefinition
	err := r.da.DB().WithContext(ctx).
		Where("namespace = ? AND enabled = ?", namespace, true).
		Order("version DESC").
		First(&config).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("config not found for namespace")
	}
	return &config, err
}

func (r *configRepo) List(ctx context.Context, namespace string, offset, limit int) ([]model.ConfigDefinition, int64, error) {
	var configs []model.ConfigDefinition
	var total int64

	query := r.da.DB().WithContext(ctx).Model(&model.ConfigDefinition{})
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("version DESC").Find(&configs).Error
	return configs, total, err
}

func (r *configRepo) UpdateEnabled(ctx context.Context, configID string, enabled bool) error {
	return r.da.DB().WithContext(ctx).Model(&model.ConfigDefinition{}).
		Where("config_id = ?", configID).
		Updates(map[string]interface{}{
			"enabled":    enabled,
			"updated_at": utils.NowUTC(),
		}).Error
}

func (r *configRepo) Apply(ctx context.Context, configID string) error {
	now := utils.NowUTC()
	return r.da.DB().WithContext(ctx).Model(&model.ConfigDefinition{}).
		Where("config_id = ?", configID).
		Updates(map[string]interface{}{
			"applied_at": now,
			"updated_at": now,
		}).Error
}

func (r *configRepo) Update(ctx context.Context, namespace string, parameters map[string]interface{}, enabled bool) (*model.ConfigDefinition, error) {
	var maxVersion int64
	r.da.DB().WithContext(ctx).Model(&model.ConfigDefinition{}).
		Where("namespace = ?", namespace).
		Select("COALESCE(MAX(version), 0)").Scan(&maxVersion)

	now := utils.NowUTC()
	config := &model.ConfigDefinition{
		ConfigID:   utils.GenerateID("cfg"),
		Namespace:  namespace,
		Version:    maxVersion + 1,
		Parameters: parameters,
		Enabled:    enabled,
		CreatedAt:  now,
		UpdatedAt:  now,
	}

	if enabled {
		config.AppliedAt = &now
	}

	if err := r.da.DB().WithContext(ctx).Create(config).Error; err != nil {
		return nil, err
	}

	return config, nil
}

func (r *configRepo) Delete(ctx context.Context, namespace string) error {
	return r.da.DB().WithContext(ctx).
		Where("namespace = ?", namespace).
		Delete(&model.ConfigDefinition{}).Error
}

type RunInstanceRepository interface {
	Create(ctx context.Context, run *model.RunInstance) error
	GetByID(ctx context.Context, runID string) (*model.RunInstance, error)
	Update(ctx context.Context, run *model.RunInstance) error
	ListByEntity(ctx context.Context, entityID string, offset, limit int) ([]model.RunInstance, int64, error)
	UpdatePhase(ctx context.Context, runID string, phase model.RunPhase, progress float64) error
	MarkComplete(ctx context.Context, runID string, errorDetail *string) error
	IncrementRetry(ctx context.Context, runID string) (int, error)
}

type runRepo struct {
	*BaseRepository
}

func NewRunInstanceRepository(da *DataAccess) RunInstanceRepository {
	return &runRepo{NewBaseRepository(da)}
}

func (r *runRepo) Create(ctx context.Context, run *model.RunInstance) error {
	now := utils.NowUTC()
	run.RunID = utils.GenerateID("run")
	run.StartedAt = now
	return r.da.DB().WithContext(ctx).Create(run).Error
}

func (r *runRepo) GetByID(ctx context.Context, runID string) (*model.RunInstance, error) {
	var run model.RunInstance
	err := r.da.DB().WithContext(ctx).Where("run_id = ?", runID).First(&run).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("run instance not found")
	}
	return &run, err
}

func (r *runRepo) Update(ctx context.Context, run *model.RunInstance) error {
	return r.da.DB().WithContext(ctx).Save(run).Error
}

func (r *runRepo) ListByEntity(ctx context.Context, entityID string, offset, limit int) ([]model.RunInstance, int64, error) {
	var runs []model.RunInstance
	var total int64

	query := r.da.DB().WithContext(ctx).Model(&model.RunInstance{}).Where("entity_id = ?", entityID)
	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}
	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("started_at DESC").Find(&runs).Error
	return runs, total, err
}

func (r *runRepo) UpdatePhase(ctx context.Context, runID string, phase model.RunPhase, progress float64) error {
	return r.da.DB().WithContext(ctx).Model(&model.RunInstance{}).
		Where("run_id = ?", runID).
		Updates(map[string]interface{}{
			"phase":    phase,
			"progress": progress,
		}).Error
}

func (r *runRepo) MarkComplete(ctx context.Context, runID string, errorDetail *string) error {
	now := utils.NowUTC()
	updates := map[string]interface{}{
		"completed_at": now,
	}
	if errorDetail != nil {
		updates["phase"] = model.RunPhaseFailed
		updates["error_detail"] = *errorDetail
		updates["progress"] = 1.0
	} else {
		updates["phase"] = model.RunPhaseCompleted
		updates["progress"] = 1.0
	}
	return r.da.DB().WithContext(ctx).Model(&model.RunInstance{}).
		Where("run_id = ?", runID).
		Updates(updates).Error
}

func (r *runRepo) IncrementRetry(ctx context.Context, runID string) (int, error) {
	var run model.RunInstance
	if err := r.da.DB().WithContext(ctx).Where("run_id = ?", runID).First(&run).Error; err != nil {
		return 0, err
	}
	run.RetryCount++
	run.Phase = model.RunPhaseExecuting
	err := r.da.DB().WithContext(ctx).Save(&run).Error
	return run.RetryCount, err
}

type MetricRepository interface {
	Create(ctx context.Context, snapshot *model.MetricSnapshot) error
	GetByID(ctx context.Context, snapshotID string) (*model.MetricSnapshot, error)
	Query(ctx context.Context, startTime, endTime time.Time, dimensions map[string]string, offset, limit int) ([]model.MetricSnapshot, int64, error)
}

type metricRepo struct {
	*BaseRepository
}

func NewMetricRepository(da *DataAccess) MetricRepository {
	return &metricRepo{NewBaseRepository(da)}
}

func (r *metricRepo) Create(ctx context.Context, snapshot *model.MetricSnapshot) error {
	snapshot.SnapshotID = utils.GenerateID("snap")
	snapshot.CreatedAt = utils.NowUTC()
	return r.da.DB().WithContext(ctx).Create(snapshot).Error
}

func (r *metricRepo) GetByID(ctx context.Context, snapshotID string) (*model.MetricSnapshot, error) {
	var snapshot model.MetricSnapshot
	err := r.da.DB().WithContext(ctx).Where("snapshot_id = ?", snapshotID).First(&snapshot).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("metric snapshot not found")
	}
	return &snapshot, err
}

func (r *metricRepo) Query(ctx context.Context, startTime, endTime time.Time, dimensions map[string]string, offset, limit int) ([]model.MetricSnapshot, int64, error) {
	var snapshots []model.MetricSnapshot
	var total int64

	query := r.da.DB().WithContext(ctx).Model(&model.MetricSnapshot{})
	if !startTime.IsZero() {
		query = query.Where("timestamp >= ?", startTime)
	}
	if !endTime.IsZero() {
		query = query.Where("timestamp <= ?", endTime)
	}

	for k, v := range dimensions {
		query = query.Where("dimensions->>? = ?", k, v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("timestamp DESC").Find(&snapshots).Error
	return snapshots, total, err
}
