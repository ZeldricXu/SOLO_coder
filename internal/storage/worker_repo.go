package storage

import (
	"context"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"gorm.io/gorm"
)

type WorkerRepo struct {
	db *DB
}

func NewWorkerRepo(db *DB) *WorkerRepo {
	return &WorkerRepo{db: db}
}

func (r *WorkerRepo) Register(ctx context.Context, worker *models.Worker) error {
	if worker.Status == "" {
		worker.Status = models.WorkerStatusIdle
	}
	if worker.HeartbeatCount == 0 {
		worker.HeartbeatCount = 0
	}
	now := time.Now()
	worker.LastHeartbeatAt = &now

	return r.db.WithContext(ctx).Create(worker).Error
}

func (r *WorkerRepo) GetByID(ctx context.Context, id int64) (*models.Worker, error) {
	var worker models.Worker
	err := r.db.WithContext(ctx).
		Preload("CurrentTask").
		First(&worker, id).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &worker, err
}

func (r *WorkerRepo) GetByName(ctx context.Context, name string) (*models.Worker, error) {
	var worker models.Worker
	err := r.db.WithContext(ctx).
		Where("name = ?", name).
		Preload("CurrentTask").
		First(&worker).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &worker, err
}

func (r *WorkerRepo) List(ctx context.Context, status models.WorkerStatus, limit, offset int) ([]models.Worker, int64, error) {
	var workers []models.Worker
	var total int64

	query := r.db.WithContext(ctx).Model(&models.Worker{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	err := query.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	err = query.
		Preload("CurrentTask").
		Order("created_at DESC").
		Limit(limit).
		Offset(offset).
		Find(&workers).Error

	return workers, total, err
}

func (r *WorkerRepo) Update(ctx context.Context, worker *models.Worker) error {
	return r.db.WithContext(ctx).Save(worker).Error
}

func (r *WorkerRepo) UpdateStatus(ctx context.Context, id int64, status models.WorkerStatus) error {
	return r.db.WithContext(ctx).Model(&models.Worker{}).
		Where("id = ?", id).
		Update("status", status).Error
}

func (r *WorkerRepo) UpdateHeartbeat(ctx context.Context, id int64) error {
	now := time.Now()
	return r.db.WithContext(ctx).Model(&models.Worker{}).
		Where("id = ?", id).
		Updates(map[string]interface{}{
			"last_heartbeat_at": &now,
			"heartbeat_count":   gorm.Expr("heartbeat_count + 1"),
		}).Error
}

func (r *WorkerRepo) UpdateStats(ctx context.Context, id int64, completed bool) error {
	updates := map[string]interface{}{}
	if completed {
		updates["tasks_completed"] = gorm.Expr("tasks_completed + 1")
	} else {
		updates["tasks_failed"] = gorm.Expr("tasks_failed + 1")
	}
	return r.db.WithContext(ctx).Model(&models.Worker{}).
		Where("id = ?", id).
		Updates(updates).Error
}

func (r *WorkerRepo) SetCurrentTask(ctx context.Context, workerID int64, taskID *int64) error {
	return r.db.WithContext(ctx).Model(&models.Worker{}).
		Where("id = ?", workerID).
		Update("current_task_id", taskID).Error
}

func (r *WorkerRepo) Delete(ctx context.Context, id int64) error {
	return r.db.WithContext(ctx).Delete(&models.Worker{}, id).Error
}

func (r *WorkerRepo) GetStatistics(ctx context.Context) (map[string]interface{}, error) {
	type StatusCount struct {
		Status models.WorkerStatus
		Count  int64
	}

	var statusCounts []StatusCount
	err := r.db.WithContext(ctx).Model(&models.Worker{}).
		Select("status, COUNT(*) as count").
		Group("status").
		Scan(&statusCounts).Error
	if err != nil {
		return nil, err
	}

	var total int64
	err = r.db.WithContext(ctx).Model(&models.Worker{}).Count(&total).Error
	if err != nil {
		return nil, err
	}

	var totalCompleted int64
	var totalFailed int64
	err = r.db.WithContext(ctx).Model(&models.Worker{}).
		Select("COALESCE(SUM(tasks_completed), 0), COALESCE(SUM(tasks_failed), 0)").
		Row().Scan(&totalCompleted, &totalFailed)
	if err != nil {
		return nil, err
	}

	result := map[string]interface{}{
		"total":           total,
		"total_completed": totalCompleted,
		"total_failed":    totalFailed,
	}
	for _, sc := range statusCounts {
		result[string(sc.Status)] = sc.Count
	}

	return result, nil
}

func (r *WorkerRepo) GetOnlineCount(ctx context.Context, heartbeatTimeout time.Duration) (int64, error) {
	cutoff := time.Now().Add(-heartbeatTimeout)
	var count int64
	err := r.db.WithContext(ctx).Model(&models.Worker{}).
		Where("status != ? AND last_heartbeat_at > ?", models.WorkerStatusOffline, &cutoff).
		Count(&count).Error
	return count, err
}

func (r *WorkerRepo) GetOfflineWorkers(ctx context.Context, heartbeatTimeout time.Duration) ([]models.Worker, error) {
	cutoff := time.Now().Add(-heartbeatTimeout)
	var workers []models.Worker
	err := r.db.WithContext(ctx).
		Where("status != ? AND (last_heartbeat_at <= ? OR last_heartbeat_at IS NULL)", models.WorkerStatusOffline, &cutoff).
		Find(&workers).Error
	return workers, err
}

func (r *WorkerRepo) MarkOffline(ctx context.Context, heartbeatTimeout time.Duration) (int64, error) {
	cutoff := time.Now().Add(-heartbeatTimeout)
	result := r.db.WithContext(ctx).Model(&models.Worker{}).
		Where("status != ? AND (last_heartbeat_at <= ? OR last_heartbeat_at IS NULL)", models.WorkerStatusOffline, &cutoff).
		Update("status", models.WorkerStatusOffline)
	return result.RowsAffected, result.Error
}

func (r *WorkerRepo) GetLoadHistory(ctx context.Context, workerID int64, since time.Time) (map[string]interface{}, error) {
	var completed int64
	var failed int64

	err := r.db.WithContext(ctx).Model(&models.Worker{}).
		Where("id = ?", workerID).
		Select("tasks_completed, tasks_failed").
		Row().Scan(&completed, &failed)
	if err != nil {
		return nil, err
	}

	return map[string]interface{}{
		"tasks_completed": completed,
		"tasks_failed":    failed,
		"since":           since,
	}, nil
}

func (r *WorkerRepo) RegisterWithTx(tx *gorm.DB, worker *models.Worker) error {
	if worker.Status == "" {
		worker.Status = models.WorkerStatusIdle
	}
	now := time.Now()
	worker.LastHeartbeatAt = &now
	return tx.Create(worker).Error
}

func (r *WorkerRepo) UpdateWithTx(tx *gorm.DB, worker *models.Worker) error {
	return tx.Save(worker).Error
}
