package storage

import (
	"context"

	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
	"gorm.io/gorm"
)

type ResultRepo struct {
	db *DB
}

func NewResultRepo(db *DB) *ResultRepo {
	return &ResultRepo{db: db}
}

func (r *ResultRepo) Save(ctx context.Context, result *models.Result) error {
	if result.Data == nil {
		result.Data = make(models.ResultData)
	}
	if result.Checksum == "" {
		result.Checksum = calculateResultChecksum(result)
	}

	existing, err := r.GetByChecksum(ctx, result.TaskID, result.Checksum)
	if err != nil {
		return err
	}
	if existing != nil {
		return nil
	}

	return r.db.WithContext(ctx).Create(result).Error
}

func (r *ResultRepo) GetByID(ctx context.Context, id int64) (*models.Result, error) {
	var result models.Result
	err := r.db.WithContext(ctx).
		Preload("Task").
		Preload("Worker").
		First(&result, id).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &result, err
}

func (r *ResultRepo) GetByChecksum(ctx context.Context, taskID int64, checksum string) (*models.Result, error) {
	var result models.Result
	err := r.db.WithContext(ctx).
		Where("task_id = ? AND checksum = ?", taskID, checksum).
		First(&result).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &result, err
}

func (r *ResultRepo) List(ctx context.Context, taskID int64, iteration int64, limit, offset int) ([]models.Result, int64, error) {
	var results []models.Result
	var total int64

	query := r.db.WithContext(ctx).Model(&models.Result{})
	if taskID > 0 {
		query = query.Where("task_id = ?", taskID)
	}
	if iteration > 0 {
		query = query.Where("iteration = ?", iteration)
	}

	err := query.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	err = query.
		Preload("Task").
		Preload("Worker").
		Order("created_at DESC").
		Limit(limit).
		Offset(offset).
		Find(&results).Error

	return results, total, err
}

func (r *ResultRepo) BatchImport(ctx context.Context, results []*models.Result) (int64, error) {
	if len(results) == 0 {
		return 0, nil
	}

	for _, result := range results {
		if result.Data == nil {
			result.Data = make(models.ResultData)
		}
		if result.Checksum == "" {
			result.Checksum = calculateResultChecksum(result)
		}
	}

	var imported int64
	err := r.db.Transaction(func(tx *gorm.DB) error {
		for _, result := range results {
			var count int64
			err := tx.WithContext(ctx).Model(&models.Result{}).
				Where("task_id = ? AND checksum = ?", result.TaskID, result.Checksum).
				Count(&count).Error
			if err != nil {
				return err
			}
			if count > 0 {
				continue
			}

			if err := tx.WithContext(ctx).Create(result).Error; err != nil {
				return err
			}
			imported++
		}
		return nil
	})

	return imported, err
}

func (r *ResultRepo) Delete(ctx context.Context, id int64) error {
	return r.db.WithContext(ctx).Delete(&models.Result{}, id).Error
}

func (r *ResultRepo) DeleteByTask(ctx context.Context, taskID int64) error {
	return r.db.WithContext(ctx).Where("task_id = ?", taskID).Delete(&models.Result{}).Error
}

func (r *ResultRepo) GetLatestByTask(ctx context.Context, taskID int64) (*models.Result, error) {
	var result models.Result
	err := r.db.WithContext(ctx).
		Where("task_id = ?", taskID).
		Order("iteration DESC, created_at DESC").
		First(&result).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &result, err
}

func (r *ResultRepo) GetByIteration(ctx context.Context, taskID int64, iteration int64) ([]models.Result, error) {
	var results []models.Result
	err := r.db.WithContext(ctx).
		Where("task_id = ? AND iteration = ?", taskID, iteration).
		Order("created_at ASC").
		Find(&results).Error
	return results, err
}

func (r *ResultRepo) Deduplicate(ctx context.Context, taskID int64) (int64, error) {
	var deleted int64
	err := r.db.Transaction(func(tx *gorm.DB) error {
		subquery := tx.WithContext(ctx).
			Model(&models.Result{}).
			Select("MIN(id)").
			Where("task_id = ?", taskID).
			Group("checksum")

		result := tx.WithContext(ctx).
			Where("task_id = ? AND id NOT IN (?)", taskID, subquery).
			Delete(&models.Result{})
		if result.Error != nil {
			return result.Error
		}
		deleted = result.RowsAffected
		return nil
	})
	return deleted, err
}

func (r *ResultRepo) GetStatistics(ctx context.Context, taskID int64) (map[string]interface{}, error) {
	var total int64
	err := r.db.WithContext(ctx).Model(&models.Result{}).
		Where("task_id = ?", taskID).
		Count(&total).Error
	if err != nil {
		return nil, err
	}

	var avgDuration float64
	err = r.db.WithContext(ctx).Model(&models.Result{}).
		Where("task_id = ?", taskID).
		Select("AVG(duration_ms)").
		Scan(&avgDuration).Error
	if err != nil {
		return nil, err
	}

	var maxIteration int64
	err = r.db.WithContext(ctx).Model(&models.Result{}).
		Where("task_id = ?", taskID).
		Select("COALESCE(MAX(iteration), 0)").
		Scan(&maxIteration).Error
	if err != nil {
		return nil, err
	}

	return map[string]interface{}{
		"total":         total,
		"avg_duration":  avgDuration,
		"max_iteration": maxIteration,
	}, nil
}

func (r *ResultRepo) SaveWithTx(tx *gorm.DB, result *models.Result) error {
	if result.Data == nil {
		result.Data = make(models.ResultData)
	}
	if result.Checksum == "" {
		result.Checksum = calculateResultChecksum(result)
	}
	return tx.Create(result).Error
}

func calculateResultChecksum(result *models.Result) string {
	data := map[string]interface{}{
		"task_id":   result.TaskID,
		"worker_id": result.WorkerID,
		"iteration": result.Iteration,
		"data":      result.Data,
	}
	return util.HashParams(data)
}
