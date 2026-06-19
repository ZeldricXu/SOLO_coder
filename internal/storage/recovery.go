package storage

import (
	"context"
	"fmt"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"gorm.io/gorm"
)

type RecoveryManager struct {
	db             *DB
	checkpointRepo *CheckpointRepo
	taskRepo       *TaskRepo
	workerRepo     *WorkerRepo
	experimentRepo *ExperimentRepo
	resultRepo     *ResultRepo
}

func NewRecoveryManager(
	db *DB,
	checkpointRepo *CheckpointRepo,
	taskRepo *TaskRepo,
	workerRepo *WorkerRepo,
	experimentRepo *ExperimentRepo,
	resultRepo *ResultRepo,
) *RecoveryManager {
	return &RecoveryManager{
		db:             db,
		checkpointRepo: checkpointRepo,
		taskRepo:       taskRepo,
		workerRepo:     workerRepo,
		experimentRepo: experimentRepo,
		resultRepo:     resultRepo,
	}
}

type RecoveryResult struct {
	TasksRecovered   int64
	TasksRetried     int64
	WorkersRecovered int64
	CheckpointsUsed  int64
}

func (rm *RecoveryManager) RecoverFromNodeFailure(ctx context.Context, heartbeatTimeout time.Duration) (*RecoveryResult, error) {
	result := &RecoveryResult{}

	err := rm.db.Transaction(func(tx *gorm.DB) error {
		offlineWorkers, err := rm.workerRepo.GetOfflineWorkers(ctx, heartbeatTimeout)
		if err != nil {
			return fmt.Errorf("failed to get offline workers: %w", err)
		}

		for _, worker := range offlineWorkers {
			if err := rm.recoverWorkerTasks(ctx, tx, worker.ID, result); err != nil {
				return fmt.Errorf("failed to recover tasks for worker %d: %w", worker.ID, err)
			}
			result.WorkersRecovered++

			if err := tx.Model(&worker).Update("status", models.WorkerStatusOffline).Error; err != nil {
				return fmt.Errorf("failed to mark worker %d as offline: %w", worker.ID, err)
			}
		}

		orphanedTasks, err := rm.findOrphanedTasks(ctx, tx, heartbeatTimeout)
		if err != nil {
			return fmt.Errorf("failed to find orphaned tasks: %w", err)
		}

		for _, task := range orphanedTasks {
			if err := rm.recoverTask(ctx, tx, &task, result); err != nil {
				return fmt.Errorf("failed to recover task %d: %w", task.ID, err)
			}
			result.TasksRecovered++
		}

		return nil
	})

	if err != nil {
		return nil, err
	}

	return result, nil
}

func (rm *RecoveryManager) recoverWorkerTasks(ctx context.Context, tx *gorm.DB, workerID int64, result *RecoveryResult) error {
	var tasks []models.Task
	err := tx.WithContext(ctx).
		Where("worker_id = ? AND status IN ?", workerID, []models.TaskStatus{
			models.TaskStatusRunning,
			models.TaskStatusQueued,
		}).
		Find(&tasks).Error
	if err != nil {
		return err
	}

	for _, task := range tasks {
		if err := rm.recoverTask(ctx, tx, &task, result); err != nil {
			return err
		}
	}

	return nil
}

func (rm *RecoveryManager) findOrphanedTasks(ctx context.Context, tx *gorm.DB, heartbeatTimeout time.Duration) ([]models.Task, error) {
	cutoff := time.Now().Add(-heartbeatTimeout)

	subquery := tx.WithContext(ctx).
		Model(&models.Worker{}).
		Select("id").
		Where("status = ? OR last_heartbeat_at <= ?", models.WorkerStatusOffline, &cutoff)

	var tasks []models.Task
	err := tx.WithContext(ctx).
		Where("status = ? AND worker_id IN (?)", models.TaskStatusRunning, subquery).
		Find(&tasks).Error

	return tasks, err
}

func (rm *RecoveryManager) recoverTask(ctx context.Context, tx *gorm.DB, task *models.Task, result *RecoveryResult) error {
	checkpoint, err := rm.checkpointRepo.GetLatest(ctx, task.ID)
	if err != nil {
		return fmt.Errorf("failed to get latest checkpoint: %w", err)
	}

	if checkpoint != nil {
		result.CheckpointsUsed++
	}

	if task.RetryCount < task.MaxRetries {
		if err := rm.taskRepo.UpdateStatusWithTx(tx, task.ID, models.TaskStatusRetrying, nil, fmt.Sprintf("recovering from checkpoint at step %d", getCheckpointStep(checkpoint))); err != nil {
			return fmt.Errorf("failed to update task status: %w", err)
		}
		result.TasksRetried++
	} else {
		if err := rm.taskRepo.UpdateStatusWithTx(tx, task.ID, models.TaskStatusFailed, nil, "max retries exceeded after node failure"); err != nil {
			return fmt.Errorf("failed to mark task as failed: %w", err)
		}
	}

	if task.WorkerID != nil {
		if err := tx.Model(&models.Worker{}).
			Where("id = ?", *task.WorkerID).
			Update("current_task_id", nil).Error; err != nil {
			return fmt.Errorf("failed to clear worker's current task: %w", err)
		}
	}

	return nil
}

func (rm *RecoveryManager) RecoverExperiment(ctx context.Context, experimentID int64) (*RecoveryResult, error) {
	result := &RecoveryResult{}

	err := rm.db.Transaction(func(tx *gorm.DB) error {
		var tasks []models.Task
		err := tx.WithContext(ctx).
			Where("experiment_id = ? AND status IN ?", experimentID, []models.TaskStatus{
				models.TaskStatusRunning,
				models.TaskStatusQueued,
			}).
			Find(&tasks).Error
		if err != nil {
			return fmt.Errorf("failed to find running tasks: %w", err)
		}

		for _, task := range tasks {
			if err := rm.recoverTask(ctx, tx, &task, result); err != nil {
				return err
			}
			result.TasksRecovered++
		}

		return nil
	})

	if err != nil {
		return nil, err
	}

	return result, nil
}

func (rm *RecoveryManager) GetTaskRecoveryState(ctx context.Context, taskID int64) (*models.Checkpoint, error) {
	return rm.checkpointRepo.RestoreFromCheckpoint(ctx, taskID)
}

func (rm *RecoveryManager) EnsureIdempotency(ctx context.Context, taskID int64, paramsHash string) (bool, *models.Result, error) {
	task, err := rm.taskRepo.GetByID(ctx, taskID)
	if err != nil {
		return false, nil, err
	}
	if task == nil {
		return false, nil, fmt.Errorf("task not found: %d", taskID)
	}

	if task.ParamsHash != paramsHash {
		return false, nil, fmt.Errorf("params hash mismatch: expected %s, got %s", task.ParamsHash, paramsHash)
	}

	if task.Status == models.TaskStatusCompleted {
		result, err := rm.resultRepo.GetLatestByTask(ctx, taskID)
		if err != nil {
			return false, nil, err
		}
		if result != nil {
			return true, result, nil
		}
	}

	return false, nil, nil
}

func (rm *RecoveryManager) RecoverAllFailedTasks(ctx context.Context, experimentID int64) (*RecoveryResult, error) {
	result := &RecoveryResult{}

	err := rm.db.Transaction(func(tx *gorm.DB) error {
		var tasks []models.Task
		err := tx.WithContext(ctx).
			Where("experiment_id = ? AND status = ? AND retry_count < max_retries",
				experimentID, models.TaskStatusFailed).
			Find(&tasks).Error
		if err != nil {
			return fmt.Errorf("failed to find failed tasks: %w", err)
		}

		for _, task := range tasks {
			if err := rm.taskRepo.UpdateStatusWithTx(tx, task.ID, models.TaskStatusRetrying, nil, "manual recovery"); err != nil {
				return fmt.Errorf("failed to retry task %d: %w", task.ID, err)
			}
			result.TasksRetried++
			result.TasksRecovered++
		}

		return nil
	})

	if err != nil {
		return nil, err
	}

	return result, nil
}

func (rm *RecoveryManager) CleanupStaleCheckpoints(ctx context.Context, maxAge time.Duration) (int64, error) {
	cutoff := time.Now().Add(-maxAge)
	result := rm.db.WithContext(ctx).
		Where("created_at < ?", &cutoff).
		Delete(&models.Checkpoint{})
	return result.RowsAffected, result.Error
}

func (rm *RecoveryManager) VerifyCheckpointIntegrity(ctx context.Context, taskID int64) (bool, error) {
	checkpoints, _, err := rm.checkpointRepo.List(ctx, taskID, 1000, 0)
	if err != nil {
		return false, err
	}

	for _, cp := range checkpoints {
		actual := calculateChecksum(cp.Data)
		if actual != cp.Checksum {
			return false, fmt.Errorf("checkpoint %d checksum mismatch", cp.ID)
		}
	}

	return true, nil
}

func getCheckpointStep(cp *models.Checkpoint) int64 {
	if cp == nil {
		return 0
	}
	return cp.Step
}
