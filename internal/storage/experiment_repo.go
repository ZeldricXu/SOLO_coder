package storage

import (
	"context"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"gorm.io/gorm"
)

type ExperimentRepo struct {
	db *DB
}

func NewExperimentRepo(db *DB) *ExperimentRepo {
	return &ExperimentRepo{db: db}
}

func (r *ExperimentRepo) Create(ctx context.Context, experiment *models.Experiment) error {
	if experiment.Status == "" {
		experiment.Status = models.ExperimentStatusPending
	}
	if experiment.Params == nil {
		experiment.Params = make(models.Params)
	}
	if experiment.Config == nil {
		experiment.Config = make(models.Params)
	}
	return r.db.WithContext(ctx).Create(experiment).Error
}

func (r *ExperimentRepo) GetByID(ctx context.Context, id int64) (*models.Experiment, error) {
	var experiment models.Experiment
	err := r.db.WithContext(ctx).
		Preload("Tasks").
		First(&experiment, id).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &experiment, err
}

func (r *ExperimentRepo) GetByName(ctx context.Context, name string) (*models.Experiment, error) {
	var experiment models.Experiment
	err := r.db.WithContext(ctx).
		Where("name = ?", name).
		Preload("Tasks").
		First(&experiment).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &experiment, err
}

func (r *ExperimentRepo) List(ctx context.Context, namespace string, status models.ExperimentStatus, limit, offset int) ([]models.Experiment, int64, error) {
	var experiments []models.Experiment
	var total int64

	query := r.db.WithContext(ctx).Model(&models.Experiment{})
	if namespace != "" {
		query = query.Where("name LIKE ?", namespace+"%")
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	err := query.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	err = query.
		Preload("Tasks").
		Order("created_at DESC").
		Limit(limit).
		Offset(offset).
		Find(&experiments).Error

	return experiments, total, err
}

func (r *ExperimentRepo) Update(ctx context.Context, experiment *models.Experiment) error {
	return r.db.WithContext(ctx).Save(experiment).Error
}

func (r *ExperimentRepo) UpdateStatus(ctx context.Context, id int64, status models.ExperimentStatus) error {
	updates := map[string]interface{}{
		"status": status,
	}
	if status == models.ExperimentStatusRunning {
		now := time.Now()
		updates["start_time"] = &now
	}
	if status == models.ExperimentStatusCompleted || status == models.ExperimentStatusFailed || status == models.ExperimentStatusCanceled {
		now := time.Now()
		updates["end_time"] = &now
	}
	return r.db.WithContext(ctx).Model(&models.Experiment{}).Where("id = ?", id).Updates(updates).Error
}

func (r *ExperimentRepo) Delete(ctx context.Context, id int64) error {
	return r.db.WithContext(ctx).Delete(&models.Experiment{}, id).Error
}

func (r *ExperimentRepo) GetStatistics(ctx context.Context, namespace string) (map[string]interface{}, error) {
	type StatusCount struct {
		Status models.ExperimentStatus
		Count  int64
	}

	var statusCounts []StatusCount
	query := r.db.WithContext(ctx).Model(&models.Experiment{})
	if namespace != "" {
		query = query.Where("name LIKE ?", namespace+"%")
	}

	err := query.Select("status, COUNT(*) as count").
		Group("status").
		Scan(&statusCounts).Error
	if err != nil {
		return nil, err
	}

	var total int64
	err = query.Count(&total).Error
	if err != nil {
		return nil, err
	}

	result := map[string]interface{}{
		"total": total,
	}
	for _, sc := range statusCounts {
		result[string(sc.Status)] = sc.Count
	}

	return result, nil
}

func (r *ExperimentRepo) ListNamespaces(ctx context.Context) ([]string, error) {
	var names []string
	err := r.db.WithContext(ctx).
		Model(&models.Experiment{}).
		Distinct("SPLIT_PART(name, '-', 1) as namespace").
		Pluck("namespace", &names).Error
	return names, err
}

func (r *ExperimentRepo) CreateWithTx(tx *gorm.DB, experiment *models.Experiment) error {
	if experiment.Status == "" {
		experiment.Status = models.ExperimentStatusPending
	}
	if experiment.Params == nil {
		experiment.Params = make(models.Params)
	}
	if experiment.Config == nil {
		experiment.Config = make(models.Params)
	}
	return tx.Create(experiment).Error
}

func (r *ExperimentRepo) UpdateWithTx(tx *gorm.DB, experiment *models.Experiment) error {
	return tx.Save(experiment).Error
}
