package storage

import (
	"context"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
	"gorm.io/gorm"
)

type TaskRepo struct {
	db *DB
}

func NewTaskRepo(db *DB) *TaskRepo {
	return &TaskRepo{db: db}
}

func (r *TaskRepo) Create(ctx context.Context, task *models.Task) error {
	if task.Status == "" {
		task.Status = models.TaskStatusPending
	}
	if task.Params == nil {
		task.Params = make(models.Params)
	}
	if task.ParamsHash == "" {
		task.ParamsHash = util.HashParams(map[string]interface{}(task.Params))
	}
	if task.MaxRetries == 0 {
		task.MaxRetries = 3
	}
	if task.TimeoutSeconds == 0 {
		task.TimeoutSeconds = 600
	}
	return r.db.WithContext(ctx).Create(task).Error
}

func (r *TaskRepo) BatchCreate(ctx context.Context, tasks []*models.Task) error {
	if len(tasks) == 0 {
		return nil
	}
	for _, task := range tasks {
		if task.Status == "" {
			task.Status = models.TaskStatusPending
		}
		if task.Params == nil {
			task.Params = make(models.Params)
		}
		if task.ParamsHash == "" {
			task.ParamsHash = util.HashParams(map[string]interface{}(task.Params))
		}
		if task.MaxRetries == 0 {
			task.MaxRetries = 3
		}
		if task.TimeoutSeconds == 0 {
			task.TimeoutSeconds = 600
		}
	}
	return r.db.WithContext(ctx).Create(tasks).Error
}

func (r *TaskRepo) GetByID(ctx context.Context, id int64) (*models.Task, error) {
	var task models.Task
	err := r.db.WithContext(ctx).
		Preload("Experiment").
		Preload("Worker").
		Preload("Results").
		Preload("Checkpoints").
		First(&task, id).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &task, err
}

func (r *TaskRepo) GetByParamsHash(ctx context.Context, experimentID int64, paramsHash string) (*models.Task, error) {
	var task models.Task
	err := r.db.WithContext(ctx).
		Where("experiment_id = ? AND params_hash = ?", experimentID, paramsHash).
		Preload("Results").
		First(&task).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &task, err
}

func (r *TaskRepo) List(ctx context.Context, experimentID int64, status models.TaskStatus, workerID *int64, limit, offset int) ([]models.Task, int64, error) {
	var tasks []models.Task
	var total int64

	query := r.db.WithContext(ctx).Model(&models.Task{})
	if experimentID > 0 {
		query = query.Where("experiment_id = ?", experimentID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if workerID != nil {
		query = query.Where("worker_id = ?", *workerID)
	}

	err := query.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	err = query.
		Preload("Experiment").
		Preload("Worker").
		Order("priority DESC, created_at ASC").
		Limit(limit).
		Offset(offset).
		Find(&tasks).Error

	return tasks, total, err
}

func (r *TaskRepo) Update(ctx context.Context, task *models.Task) error {
	return r.db.WithContext(ctx).Save(task).Error
}

func (r *TaskRepo) UpdateStatus(ctx context.Context, id int64, status models.TaskStatus, workerID *int64, errorMessage string) error {
	updates := map[string]interface{}{
		"status": status,
	}
	if workerID != nil {
		updates["worker_id"] = *workerID
	}
	if errorMessage != "" {
		updates["error_message"] = errorMessage
	}
	if status == models.TaskStatusRunning {
		now := time.Now()
		updates["start_time"] = &now
	}
	if status == models.TaskStatusCompleted || status == models.TaskStatusFailed || status == models.TaskStatusCanceled || status == models.TaskStatusTimeout {
		now := time.Now()
		updates["end_time"] = &now
	}
	if status == models.TaskStatusRetrying {
		updates["retry_count"] = gorm.Expr("retry_count + 1")
	}
	return r.db.WithContext(ctx).Model(&models.Task{}).Where("id = ?", id).Updates(updates).Error
}

func (r *TaskRepo) Delete(ctx context.Context, id int64) error {
	return r.db.WithContext(ctx).Delete(&models.Task{}, id).Error
}

func (r *TaskRepo) DeleteByExperiment(ctx context.Context, experimentID int64) error {
	return r.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("task_id IN (SELECT id FROM tasks WHERE experiment_id = ?)", experimentID).Delete(&models.Checkpoint{}).Error; err != nil {
			return err
		}
		if err := tx.Where("task_id IN (SELECT id FROM tasks WHERE experiment_id = ?)", experimentID).Delete(&models.Result{}).Error; err != nil {
			return err
		}
		if err := tx.Where("experiment_id = ?", experimentID).Delete(&models.TaskChunk{}).Error; err != nil {
			return err
		}
		return tx.Where("experiment_id = ?", experimentID).Delete(&models.Task{}).Error
	})
}

func (r *TaskRepo) ClaimPendingTask(ctx context.Context, workerID int64) (*models.Task, error) {
	var task models.Task

	err := r.db.Transaction(func(tx *gorm.DB) error {
		err := tx.WithContext(ctx).
			Where("status IN ?", []models.TaskStatus{models.TaskStatusPending, models.TaskStatusRetrying, models.TaskStatusQueued}).
			Order("priority DESC, created_at ASC").
			Limit(1).
			Set("gorm:query_option", "FOR UPDATE SKIP LOCKED").
			First(&task).Error
		if err != nil {
			if err == gorm.ErrRecordNotFound {
				return nil
			}
			return err
		}

		now := time.Now()
		return tx.WithContext(ctx).Model(&task).Updates(map[string]interface{}{
			"status":     models.TaskStatusRunning,
			"worker_id":  workerID,
			"start_time": &now,
		}).Error
	})

	if err != nil {
		return nil, err
	}
	if task.ID == 0 {
		return nil, nil
	}
	return &task, nil
}

func (r *TaskRepo) GetStatistics(ctx context.Context, experimentID int64) (map[string]interface{}, error) {
	type StatusCount struct {
		Status models.TaskStatus
		Count  int64
	}

	var statusCounts []StatusCount
	query := r.db.WithContext(ctx).Model(&models.Task{})
	if experimentID > 0 {
		query = query.Where("experiment_id = ?", experimentID)
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

func (r *TaskRepo) CreateWithTx(tx *gorm.DB, task *models.Task) error {
	if task.Status == "" {
		task.Status = models.TaskStatusPending
	}
	if task.Params == nil {
		task.Params = make(models.Params)
	}
	if task.ParamsHash == "" {
		task.ParamsHash = util.HashParams(map[string]interface{}(task.Params))
	}
	return tx.Create(task).Error
}

func (r *TaskRepo) UpdateWithTx(tx *gorm.DB, task *models.Task) error {
	return tx.Save(task).Error
}

func (r *TaskRepo) UpdateStatusWithTx(tx *gorm.DB, id int64, status models.TaskStatus, workerID *int64, errorMessage string) error {
	updates := map[string]interface{}{
		"status": status,
	}
	if workerID != nil {
		updates["worker_id"] = *workerID
	}
	if errorMessage != "" {
		updates["error_message"] = errorMessage
	}
	if status == models.TaskStatusRunning {
		now := time.Now()
		updates["start_time"] = &now
	}
	if status == models.TaskStatusCompleted || status == models.TaskStatusFailed || status == models.TaskStatusCanceled || status == models.TaskStatusTimeout {
		now := time.Now()
		updates["end_time"] = &now
	}
	if status == models.TaskStatusRetrying {
		updates["retry_count"] = gorm.Expr("retry_count + 1")
	}
	return tx.Model(&models.Task{}).Where("id = ?", id).Updates(updates).Error
}
