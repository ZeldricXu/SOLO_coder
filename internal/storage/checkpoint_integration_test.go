//go:build integration

package storage

import (
	"context"
	"fmt"
	"strconv"
	"testing"
	"time"

	"github.com/df1-96/experiment/internal/config"
	"github.com/df1-96/experiment/internal/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
	gormpostgres "gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

func setupTestDB(t *testing.T) (*DB, func()) {
	t.Helper()
	ctx := context.Background()

	pgContainer, err := tcpostgres.Run(ctx,
		"postgres:16-alpine",
		tcpostgres.WithDatabase("testdb"),
		tcpostgres.WithUsername("testuser"),
		tcpostgres.WithPassword("testpass"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).
				WithStartupTimeout(30*time.Second),
		),
	)
	require.NoError(t, err)

	host, err := pgContainer.Host(ctx)
	require.NoError(t, err)

	mappedPort, err := pgContainer.MappedPort(ctx, "5432")
	require.NoError(t, err)

	portStr := mappedPort.Port()
	portInt, err := strconv.Atoi(portStr)
	require.NoError(t, err)

	dsn := fmt.Sprintf(
		"host=%s port=%s user=testuser password=testpass dbname=testdb sslmode=disable TimeZone=UTC",
		host, portStr,
	)

	gormDB, err := gorm.Open(gormpostgres.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Warn),
	})
	require.NoError(t, err)

	err = models.AutoMigrate(gormDB)
	require.NoError(t, err)

	cfg := &config.DatabaseConfig{
		Host:     host,
		Port:     portInt,
		User:     "testuser",
		Password: "testpass",
		DBName:   "testdb",
		SSLMode:  "disable",
		TimeZone: "UTC",
	}

	db := &DB{
		DB:     gormDB,
		config: cfg,
	}

	cleanup := func() {
		sqlDB, _ := gormDB.DB()
		if sqlDB != nil {
			sqlDB.Close()
		}
		if pgContainer != nil {
			_ = pgContainer.Terminate(ctx)
		}
	}

	return db, cleanup
}

func TestCheckpointRepo_SaveAndRestore(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	ctx := context.Background()
	checkpointRepo := NewCheckpointRepo(db)
	taskRepo := NewTaskRepo(db)
	workerRepo := NewWorkerRepo(db)

	worker := &models.Worker{
		Name:       "integration-test-worker",
		Status:     models.WorkerStatusIdle,
		Host:       "localhost",
		Port:       50051,
		CPUCores:   4,
		MemoryGB:   8,
	}
	err := workerRepo.Register(ctx, worker)
	require.NoError(t, err)
	require.NotZero(t, worker.ID)

	task := &models.Task{
		ExperimentID: 1,
		Name:         "checkpoint-test-task",
		Params: models.Params{
			"learning_rate": 0.001,
			"batch_size":    32,
		},
		MaxRetries: 3,
	}
	err = taskRepo.Create(ctx, task)
	require.NoError(t, err)
	require.NotZero(t, task.ID)

	totalCheckpoints := 50
	for i := 1; i <= totalCheckpoints; i++ {
		step := int64(i)
		cp := &models.Checkpoint{
			TaskID:   task.ID,
			WorkerID: worker.ID,
			Step:     step,
			Data: models.Params{
				"iteration": float64(step),
				"loss":      1.0 / float64(step),
				"weights":   []interface{}{float64(step), float64(step * 2), float64(step * 3)},
				"state": map[string]interface{}{
					"epoch": float64(step / 10),
					"seed":  float64(42 + step),
				},
			},
		}
		err := checkpointRepo.Save(ctx, cp)
		require.NoError(t, err)
		require.NotZero(t, cp.ID)
	}

	latest, err := checkpointRepo.GetLatest(ctx, task.ID)
	require.NoError(t, err)
	require.NotNil(t, latest)
	assert.Equal(t, int64(totalCheckpoints), latest.Step)
	assert.Equal(t, task.ID, latest.TaskID)
	assert.Equal(t, worker.ID, latest.WorkerID)
	assert.NotEmpty(t, latest.Checksum)

	expectedLoss := 1.0 / float64(totalCheckpoints)
	assert.Equal(t, float64(totalCheckpoints), latest.Data["iteration"])
	assert.Equal(t, expectedLoss, latest.Data["loss"])

	actualChecksum := calculateChecksum(latest.Data)
	assert.Equal(t, latest.Checksum, actualChecksum)

	restored, err := checkpointRepo.RestoreFromCheckpoint(ctx, task.ID)
	require.NoError(t, err)
	require.NotNil(t, restored)
	assert.Equal(t, int64(totalCheckpoints), restored.Step)
	assert.Equal(t, latest.Data, restored.Data)

	for step := int64(1); step <= 10; step++ {
		cp, err := checkpointRepo.GetByStep(ctx, task.ID, step)
		require.NoError(t, err)
		require.NotNil(t, cp, "checkpoint at step %d should exist", step)
		assert.Equal(t, step, cp.Step)
		assert.Equal(t, float64(step), cp.Data["iteration"])
	}

	_, total, err := checkpointRepo.List(ctx, task.ID, 10, 0)
	require.NoError(t, err)
	assert.Equal(t, int64(totalCheckpoints), total)
}

func TestCheckpointRepo_CleanOldCheckpoints(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	ctx := context.Background()
	checkpointRepo := NewCheckpointRepo(db)
	taskRepo := NewTaskRepo(db)
	workerRepo := NewWorkerRepo(db)

	worker := &models.Worker{
		Name:     "cleanup-test-worker",
		Status:   models.WorkerStatusIdle,
		CPUCores: 4,
		MemoryGB: 8,
	}
	err := workerRepo.Register(ctx, worker)
	require.NoError(t, err)

	task := &models.Task{
		ExperimentID: 1,
		Name:         "cleanup-test-task",
		Params:       models.Params{"key": "value"},
		MaxRetries:   3,
	}
	err = taskRepo.Create(ctx, task)
	require.NoError(t, err)

	totalCheckpoints := 20
	for i := 1; i <= totalCheckpoints; i++ {
		step := int64(i)
		cp := &models.Checkpoint{
			TaskID:   task.ID,
			WorkerID: worker.ID,
			Step:     step,
			Data:     models.Params{"step": float64(step)},
		}
		err := checkpointRepo.Save(ctx, cp)
		require.NoError(t, err)
	}

	_, countBefore, err := checkpointRepo.List(ctx, task.ID, 100, 0)
	require.NoError(t, err)
	assert.Equal(t, int64(totalCheckpoints), countBefore)

	keepLatest := 5
	err = checkpointRepo.CleanOldCheckpoints(ctx, task.ID, keepLatest)
	require.NoError(t, err)

	remaining, countAfter, err := checkpointRepo.List(ctx, task.ID, 100, 0)
	require.NoError(t, err)
	assert.Equal(t, int64(keepLatest), countAfter)
	assert.Len(t, remaining, keepLatest)

	for _, cp := range remaining {
		assert.Greater(t, cp.Step, int64(totalCheckpoints-keepLatest))
	}

	latest, err := checkpointRepo.GetLatest(ctx, task.ID)
	require.NoError(t, err)
	require.NotNil(t, latest)
	assert.Equal(t, int64(totalCheckpoints), latest.Step)
}

func TestCheckpointRepo_CheckSumValidationIntegration(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	ctx := context.Background()
	checkpointRepo := NewCheckpointRepo(db)
	taskRepo := NewTaskRepo(db)
	workerRepo := NewWorkerRepo(db)

	worker := &models.Worker{
		Name:     "checksum-worker",
		Status:   models.WorkerStatusIdle,
		CPUCores: 4,
		MemoryGB: 8,
	}
	err := workerRepo.Register(ctx, worker)
	require.NoError(t, err)

	task := &models.Task{
		ExperimentID: 1,
		Name:         "checksum-task",
		Params:       models.Params{"test": true},
		MaxRetries:   3,
	}
	err = taskRepo.Create(ctx, task)
	require.NoError(t, err)

	cp := &models.Checkpoint{
		TaskID:   task.ID,
		WorkerID: worker.ID,
		Step:     10,
		Data: models.Params{
			"weights": []interface{}{1.0, 2.0, 3.0},
			"loss":    0.5,
		},
	}
	err = checkpointRepo.Save(ctx, cp)
	require.NoError(t, err)

	restored, err := checkpointRepo.RestoreFromCheckpoint(ctx, task.ID)
	require.NoError(t, err)
	require.NotNil(t, restored)
	assert.Equal(t, restored.Checksum, calculateChecksum(restored.Data))

	var rawCP models.Checkpoint
	err = db.WithContext(ctx).First(&rawCP, cp.ID).Error
	require.NoError(t, err)

	rawCP.Data = models.Params{
		"weights": []interface{}{999.0, 999.0, 999.0},
		"loss":    999.0,
	}
	err = db.WithContext(ctx).Save(&rawCP).Error
	require.NoError(t, err)

	_, err = checkpointRepo.RestoreFromCheckpoint(ctx, task.ID)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "checksum mismatch")
}
