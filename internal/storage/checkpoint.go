package storage

import (
	"bytes"
	"context"
	"encoding/gob"
	"encoding/json"
	"fmt"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
	"gorm.io/gorm"
)

type CheckpointSaveOption int

const (
	CheckpointSaveByStep CheckpointSaveOption = iota
	CheckpointSaveByTime
	CheckpointSaveManual
)

type CheckpointConfig struct {
	StepInterval int64
	TimeInterval time.Duration
	SerializeAs  string
}

func DefaultCheckpointConfig() CheckpointConfig {
	return CheckpointConfig{
		StepInterval: 100,
		TimeInterval: 5 * time.Minute,
		SerializeAs:  "json",
	}
}

type CheckpointRepo struct {
	db *DB
}

func NewCheckpointRepo(db *DB) *CheckpointRepo {
	return &CheckpointRepo{db: db}
}

func (r *CheckpointRepo) Save(ctx context.Context, checkpoint *models.Checkpoint) error {
	if checkpoint.Data == nil {
		checkpoint.Data = make(models.Params)
	}
	if checkpoint.Checksum == "" {
		checkpoint.Checksum = calculateChecksum(checkpoint.Data)
	}
	return r.db.WithContext(ctx).Create(checkpoint).Error
}

func (r *CheckpointRepo) GetLatest(ctx context.Context, taskID int64) (*models.Checkpoint, error) {
	var checkpoint models.Checkpoint
	err := r.db.WithContext(ctx).
		Where("task_id = ?", taskID).
		Order("step DESC, created_at DESC").
		First(&checkpoint).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &checkpoint, err
}

func (r *CheckpointRepo) GetByStep(ctx context.Context, taskID int64, step int64) (*models.Checkpoint, error) {
	var checkpoint models.Checkpoint
	err := r.db.WithContext(ctx).
		Where("task_id = ? AND step = ?", taskID, step).
		First(&checkpoint).Error
	if err == gorm.ErrRecordNotFound {
		return nil, nil
	}
	return &checkpoint, err
}

func (r *CheckpointRepo) List(ctx context.Context, taskID int64, limit, offset int) ([]models.Checkpoint, int64, error) {
	var checkpoints []models.Checkpoint
	var total int64

	err := r.db.WithContext(ctx).Model(&models.Checkpoint{}).
		Where("task_id = ?", taskID).
		Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	err = r.db.WithContext(ctx).
		Where("task_id = ?", taskID).
		Order("step DESC, created_at DESC").
		Limit(limit).
		Offset(offset).
		Find(&checkpoints).Error

	return checkpoints, total, err
}

func (r *CheckpointRepo) Delete(ctx context.Context, id int64) error {
	return r.db.WithContext(ctx).Delete(&models.Checkpoint{}, id).Error
}

func (r *CheckpointRepo) DeleteByTask(ctx context.Context, taskID int64) error {
	return r.db.WithContext(ctx).Where("task_id = ?", taskID).Delete(&models.Checkpoint{}).Error
}

func (r *CheckpointRepo) ShouldSave(lastSaveTime time.Time, lastStep, currentStep int64, config CheckpointConfig) (bool, CheckpointSaveOption) {
	if config.StepInterval > 0 && currentStep-lastStep >= config.StepInterval {
		return true, CheckpointSaveByStep
	}
	if config.TimeInterval > 0 && time.Since(lastSaveTime) >= config.TimeInterval {
		return true, CheckpointSaveByTime
	}
	return false, CheckpointSaveManual
}

func SerializeCheckpointData(data models.Params, format string) ([]byte, error) {
	switch format {
	case "gob":
		var buf bytes.Buffer
		enc := gob.NewEncoder(&buf)
		if err := enc.Encode(data); err != nil {
			return nil, fmt.Errorf("gob encode failed: %w", err)
		}
		return buf.Bytes(), nil
	case "json":
		b, err := json.Marshal(data)
		if err != nil {
			return nil, fmt.Errorf("json encode failed: %w", err)
		}
		return b, nil
	default:
		return nil, fmt.Errorf("unsupported serialization format: %s", format)
	}
}

func DeserializeCheckpointData(b []byte, format string) (models.Params, error) {
	var data models.Params
	switch format {
	case "gob":
		buf := bytes.NewBuffer(b)
		dec := gob.NewDecoder(buf)
		if err := dec.Decode(&data); err != nil {
			return nil, fmt.Errorf("gob decode failed: %w", err)
		}
		return data, nil
	case "json":
		if err := json.Unmarshal(b, &data); err != nil {
			return nil, fmt.Errorf("json decode failed: %w", err)
		}
		return data, nil
	default:
		return nil, fmt.Errorf("unsupported serialization format: %s", format)
	}
}

func calculateChecksum(data models.Params) string {
	return util.HashParams(map[string]interface{}(data))
}

func (r *CheckpointRepo) RestoreFromCheckpoint(ctx context.Context, taskID int64) (*models.Checkpoint, error) {
	checkpoint, err := r.GetLatest(ctx, taskID)
	if err != nil {
		return nil, err
	}
	if checkpoint == nil {
		return nil, nil
	}

	actualChecksum := calculateChecksum(checkpoint.Data)
	if actualChecksum != checkpoint.Checksum {
		return nil, fmt.Errorf("checkpoint checksum mismatch: expected %s, got %s", checkpoint.Checksum, actualChecksum)
	}

	return checkpoint, nil
}

func (r *CheckpointRepo) CleanOldCheckpoints(ctx context.Context, taskID int64, keepLatest int) error {
	if keepLatest <= 0 {
		keepLatest = 5
	}

	subquery := r.db.WithContext(ctx).
		Model(&models.Checkpoint{}).
		Select("id").
		Where("task_id = ?", taskID).
		Order("step DESC, created_at DESC").
		Limit(keepLatest)

	return r.db.WithContext(ctx).
		Where("task_id = ? AND id NOT IN (?)", taskID, subquery).
		Delete(&models.Checkpoint{}).Error
}
